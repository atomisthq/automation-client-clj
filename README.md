## Running an Automation Client

[![Clojars Project](https://img.shields.io/clojars/v/com.atomist/automation-client-clj.svg)](https://clojars.org/com.atomist/automation-client-clj)
[![Build Status](https://travis-ci.org/atomisthq/automation-client-clj.svg?branch=master)](https://travis-ci.org/atomisthq/automation-client-clj)
### Getting started

See our example project (link when it's open sourced in a minute)

### Usage

You'll need to pass in mount arguments containing at least:

```clj
{:automation-client-clj 
  {:team-id "your_team_id"
 :automation-namespaces ["fingerprints"]
 :name "fingerprints-clj"
 :github-token "xxxxxxxxxxxxxx"}}
```

* The `:team-id` is your Atomist team identifier.
* The `:name` should be something unique to identify this 'automation client'.
* The automation namespaces refer to the symbol names of namespaces that may contain handlers.  We will scan these
  namespaces for metadata on function vars when the automation client first starts up.
* You can use a GitHub personal token to authenticate with Atomist.  You can also set the an environment variable
  GITHUB_TOKEN, and leave `:github-token` out of the above config.edn file.

You can start up the automation client in a repl using:

```clj
(require '[com.atomist/automation.core])
(require '[mount.core :as mount]'
(mount/start (mount/with-args {:args :as-above})
```

You can also clone and try a [sample automation here][sample].  This currently requires that you have created
an Atomist account and [enrolled our bot in your slack team][enroll-docs].

[sample]: https://github.com/atomisthq/clj-fingerprint-automation
[enroll-docs]: https://docs.atomist.com/user/

## Handlers

The actual automation handler functions are defined in namespaces that you listed in the above config.edn.  A handler is any
function that has Atomist metadata.

### Commands

Command handlers are just functions but they can declare requirements on parameters, mapped parameters, and secrets.
When an automation client starts up, it uses this metadata to define the binding with external sources of command
request events.  This is perfect for chatops use cases.  For example, if your team has invited the Atomist bot
to your slack team, then a Command Handler definition automatically becomes a new command that you can send to your
bot.

```clj
(ns fingerprints
  (:require [com.atomist/automation.core :as api]))

(defn
  ^{:command {:name         "HelloClojureWorld"
              :description  "Cheerful greetings"
              :intent       ["hello world"]
              :parameters   [{:name "username" :pattern ".*" :required true}]}}
  hello-clojure-world
  "A very simple handler that responds to `@atomist hello world` asks the user in a thread for a username
   then responds `hello $username!`"
  [o]
  (let [user (api/get-parameter-value o "username")]
    (api/simple-message o (format "Hello %s!" user))))
```

The `:intent` means that you can now say `@atomist hello world` wherever your bot has been invited.  Your handler
function will be delivered parameters, mapped_parameters, and secrets.

### Events

Let the bot subscribe to things that happen out there in the world.

```clj
(ns fingerprints
  (:require [com.atomist/automation.core :as api]))

(defn
  ^{:event   {:name        "hello-github-commit"
              :description "watch for commits"
              :secrets     [{:uri "github://org_token?scopes=repo"}]
              :subscription "subscription CommitHappened {Commit {sha repo {name org {owner ownerType}}}}}}

  handler-hello-clj
  [o]
  (api/simple-message o (format "I just noticed a commit %s" (-> o :data :Commit first :sha)))
```

The `subscription` is a graphql query that allows you to query for data coming from:

* GitHub
* Slack
* CI systems (like Jenkins, Travis, Circle, etc.)
* Kubernetes
* Custom events

We love graphql for this because the schema grows as your team configures more webhooks, or pushes more data to
ingestion points.  You create these queries in the Atomist dashboard for your team.

Command and Event handlers work together because event handlers can either run commands, or they can send messages
that embed commands.  In this way, event handlers can make suggestions, or they can directly take action.

## Bot Messages

### Simple Messages

Whenever a command handler is called, the parameter sent to the handler will have data indicating what channel the
message was sent from (here, channel can also mean direct message during a 1 on 1 conversation).  Calling the
`simple-message` function will send a message to this channel.

```clj
(api/simple-message o "simple message") ;; this just responds in whatever channel the message came from
```

Addressing a message to other channels is also possible:

```clj
;; o is the incoming command or subscription event
(-> o
    (api/user "user-name)
    (api/simple-message "eh!")) ;; send a DM to a user named user-name
(-> o
    (api/user "user-name")
    (api/simple-message "I have something to tell all of you")) ;; have the bot send a message to a channel
```

### Actionable Messages

Messages can also contain references to invokable things.  If you already have a command handler named
"hello-github-commit", then you can send a message giving users a button to click on.  It's like putting
a callback function into slack.

```clj
(api/actionable-message
  o
  {:text        "You might want to think about saying hello"
   :attachments [{:text        "here's a button you can use to say hello"
                  :callback_id "random-id"
                  :actions     [{:text    "Say hello"
                                 :type    "button"
                                 :atomist/command {
                                   :command "hello-github-commit"
                                   :parameters     [{:name "username" :value "Ben"}]}}]}]})
```

Most of the structure of this message is defined by [Slack Attachments][slack-attachments].  However, the `:atomist/command` in
the action is an Atomist-specific addition.  It allows the Slack action to reference one of your command handlers by name, and
it allows the message to partially apply some parameters to the command handler.  The parameter list can be empty.
An empty parameter list just means that the bot will have to ask more questions (if the reference command handlers has
required parameters).

[slack-messages]: "https://api.slack.com/docs/message-attachments"

You can create messages that have _lots_ of buttons actually.  Slack has some limitations on the number of actions per
attachment and on the number of attachments per message.

You can also add drop-down menus to your messages.  Selected values in these drop-downs can then become parameters
passed to your handler.

```clj
(api/actionable-message
  o
  {:text        "You might want to think about saying hello"
   :attachments [{:text        "here are some choices"
                  :callback_id "random-id"
                  :actions     [{:text    "Say hello"
                                 :type    "select"
                                 :options [{:text "say Hi to Ben" :value "Ben"}
                                           {:text "say Hi to Jim" :value "Jim"}]
                                 :atomist/command
                                           :command "HelloClojureWorld"
                                           :parameter_name "username"
                                           :parameters     []}}]}]})
```

### Custom Ingestions

```
;; define a vector of types
;; attach metadata to the vector specifying at least one of the types as being root
;; namespace must be among the namespaces scanned for handler/type registration
(def ^{:ingester {:root_type "violation"}} violation
  [{:kind "OBJECT" :name "violation" :fields [{:name "description" :type {:kind "SCALAR" :name "String"}}]}])
```

### Releasing

- Every commit to master results in a SNAPSHOT being built and published to Clojars
- Create a semver tag to do a proper release to Clojars - it must match the numerical part of the project version
(i.e. without the -SNAPSHOT)