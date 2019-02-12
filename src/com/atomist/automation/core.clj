(ns com.atomist.automation.core
  (:require [gniazdo.core :as ws]
            [mount.core :as mount]
            [clojure.data.json :as json]
            [clj-http.client :as client]
            [com.atomist.automation.config-service :as cs]
            [clojure.tools.logging :as log]
            [com.atomist.automation.registry :as registry]
            [clojure.core.async :as async]
            [com.atomist.automation.restart :refer [with-restart]]
            [com.rpl.specter :as specter]
            [clojure.repl])
  (:import (clojure.lang ExceptionInfo)
           (java.util UUID)))

(defn get-token []
  (if-let [api-key (some-> (mount/args) :automation-client-clj :api-key)]
    (str "Bearer " api-key)
    (let [gt (or (System/getenv "ATOMIST_TOKEN") (cs/get-config-value [:api-key]))]
      (str "token " (or (:value gt) gt)))))

(defn automation-url [end]
  (str (or (cs/get-config-value [:automation-api]) "https://automation.atomist.com") end))

(defn get-registration []
  (->> (client/get (automation-url "/registration") {:headers {:authorization (get-token)}
                                                     :as :json})
       :body))

(defn delete-registration [session-id]
  (client/delete (format (str (automation-url "/registration") "/%s") session-id)
                 {:headers {:authorization (get-token)}}))

(defn register
  "Register for events and listen on websocket"
  []
  (let [auth-header (get-token)
        url (automation-url "/registration")]
    (log/info (registry/registration))
    (log/info url)
    (let [response (client/post url
                                {:body (json/write-str (registry/registration))
                                 :content-type :json
                                 :headers {:authorization auth-header}
                                 :socket-timeout 10000
                                 :conn-timeout 5000
                                 :accept :json
                                 :throw-exceptions false})]
      (if (= 200 (:status response))
        (-> response
            :body
            (json/read-str :key-fn keyword))
        (do
          (log/errorf "failed to register %s" response)
          (throw (ex-info "failed to register" response)))))))

(defn ^:api get-parameter-value
  "search command request for parameter
     params
       o              - command request
       parameter-name - string name
     returns nil if there's no parameter"
  [o parameter-name]
  (some->> (get-in o [:parameters])
           (filter #(= parameter-name (:name %)))
           first
           :value))

(defn ^:api mapped-parameter-value
  "search command request for mapped parameter
    params
      o              - command request
      parameter-name - string name
    returns nil if there's no parameter"
  [o parameter-name]
  (some->> (get-in o [:mapped_parameters])
           (filter #(= parameter-name (:name %)))
           first
           :value))

(defn ^:api get-secret-value
  "search command request for mapped parameter
    params
      o              - command request
      secret-name - string name
    returns nil if there's no parameter"
  [o secret-name]
  (if (= "1" (:api_version o))
    (some->> (get-in o [:secrets])
             (filter #(= secret-name (:uri %)))
             first
             :value)
    (some->> (get-in o [:secrets])
             (filter #(= secret-name (:uri %)))
             first
             :name)))

(declare simple-message failed-status success-status on-receive)

(defn- connect-automation-api
  [channel-closed]
  (let [response (register)]
    (log/info "response " response)

    {:response response
     :connection (ws/connect
                  (:url response)
                  :on-receive on-receive
                  :on-error (fn [e] (log/error e "error processing websocket"))
                  :on-close (fn [code message]
                              (log/warnf "websocket closing (%d):  %s" code message)
                              (async/go (async/>! channel-closed :channel-closed))))}))

(defn- close-automation-api [{:keys [connection]}]
  (try
    (ws/close connection)
    (catch Throwable t (log/error t (.getMessage t)))))

(def connection (atom nil))

(defn- send-new-socket [{socket :connection {:keys [url jwt endpoints]} :response :as conn}]
  (log/info "updating current api websocket")
  (log/infof "endpoints:  %s" endpoints)
  (log/infof "connected to %s" url)
  (reset! connection conn))

(defn- on-receive [msg]
  (let [o (json/read-str msg :key-fn keyword)]
    (if (:ping o)
      (do
        (log/debugf "ping %s" (:ping o))
        (ws/send-msg (:connection @connection) (json/write-str {:pong (:ping o)})))
      (if (:data o)
        (do
          (try
            (log/infof "received event %s" (->> o :data keys))
            (log/debugf "event payload %s" (with-out-str (clojure.pprint/pprint o)))
            (registry/event-handler o)
            (catch Throwable t
              (log/error t (format "problem processing the event loop %s" o)))))
        (do
          (log/info "Received commands:\n" (with-out-str (clojure.pprint/pprint (dissoc o :secrets))))
          (try
            (registry/command-handler o)
            (success-status o)
            (catch ExceptionInfo ex
              (simple-message o (.getMessage ex))
              (simple-message o (str "```" (with-out-str (clojure.pprint/pprint (ex-data ex))) "```"))
              (failed-status o))
            (catch Throwable t
              (log/error t (str "problem in processing the command loop" (.getMessage t)))
              (failed-status o))))))))

(declare api-connection)
(mount/defstate api-connection
  :start (with-restart #'connect-automation-api #'close-automation-api #'send-new-socket)
  :stop (async/>!! api-connection :stop))

(defn ^:api run-query
  "An automation can run queries on an open-ended set of teams (it can be registered to multiple teams.
     params
       team-id  - Atomist workspace id
       query    - string graphql query
     returns nil for errors or the body of the query response"
  [team-id query]
  (let [response
        (client/post
         (automation-url (format "/graphql/team/%s" team-id))
         {:body (json/json-str {:query query :variables {}})
          :headers {:authorization (format "Bearer %s" (-> @connection :response :jwt))}
          :throw-exceptions false})]
    (if (and (not (:errors response)) (= 200 (:status response)))
      (-> response :body (json/read-str :key-fn keyword))
      (log/warnf "failure to run %s query %s\n%s" team-id query response))))

(defn- send-on-socket [x]
  (log/infof "send-on-socket %s" x)
  (log/debugf "send-on-socket %s" (with-out-str (clojure.pprint/pprint x)))
  (ws/send-msg (-> @connection :connection) (json/json-str x)))

(defn- add-slack-details [command]
  (assoc command :source [(:source command)]))

(defn- default-destination [o]
  (if (or (not (:destinations o)) (empty? (:destinations o)))
    (-> o
        (update :destinations (constantly [(merge
                                            (:source o)
                                            {:user_agent "slack"})]))
        (update-in [:destinations 0 :slack] #(dissoc % :user)))
    o))

(defn add-slack-source [command team-id team-name]
  (assoc command :source {:user_agent "slack"
                          :slack {:team {:id team-id :name team-name}}}))

(defn ^:api success-status
  "on command request, send status that the invocation was successful"
  [o]
  (-> (select-keys o [:correlation_id :api_version :automation :team :command])
      (add-slack-details)
      (assoc :status {:code 0 :reason "success"})
      (send-on-socket)))

(defn ^:api failed-status
  "on command request, send status that the invocation failed"
  [o]
  (-> (select-keys o [:correlation_id :api_version :automation :team :command])
      (add-slack-details)
      (assoc :status {:code 1 :reason "failure"})
      (send-on-socket)))

(defn ^:api simple-message
  "send simple message as bot
     params
       o - command request or event
       s - string message"
  [o s]
  (-> (select-keys o [:correlation_id :api_version :automation :team :source :command :destinations])
      (assoc :content_type "text/plain")
      (assoc :body s)
      (default-destination)
      (send-on-socket)))

(defn ^:api continue [o params]
  (-> (select-keys o [:correlation_id :parameters :api_version :automation :team :source :command :destinations :parameter_specs])
      (assoc :content_type "application/x-atomist-continuation+json")
      (update :parameter_specs (fnil concat []) params)
      (default-destination)
      (send-on-socket)))

(defn ^:api snippet-message
  "send snippet as bot
    params
      o           - command request or event
      content-str - content as string
      filetype    - valid slack filetype
      title       - string title"
  [o content-str filetype title]
  (-> (select-keys o [:correlation_id :api_version :automation :team :source :command :destinations])
      (assoc :content_type "application/x-atomist-slack-file+json")
      (assoc :body (json/write-str {:content content-str :filetype filetype :title title}))
      (default-destination)
      (send-on-socket)))

(defn ^:api ingest
  "ingest a new custom event
     params
       o         - incoming event or command request
       x         - custom event
       channel   - name of custom event channel"
  [o x channel]
  (-> x
      (select-keys [:api_version :correlation_id :team :automation])
      (assoc :content_type "application/json"
             :body (json/json-str x)
             :destinations [{:user_agent "ingester"
                             :ingester {:root_type channel}}])
      (send-on-socket)))

(defn pprint-data-message [o data]
  (let [message (str "```"
                     (-> data
                         (clojure.pprint/pprint)
                         (with-out-str))
                     "```")]
    (simple-message o message)))

(defn guid []
  (UUID/randomUUID))

(defn update-when-seq [a-map k fn]
  (if (seq (get a-map k))
    (update a-map k fn)
    a-map))

(defn ^:api channel
  "set message destination channel name
     params
       o      - command request or incoming event payload
       c      - string name of message channel"
  [o c]
  (-> o
      (default-destination)
      (update-in [:destinations 0 :slack] (fn [x] (-> x (assoc :channel {:name c}) (dissoc :user))))))

(defn ^:api user
  "set message destination to user DM
     params
       o      - command request or incoming event payload
       c      - string name of user to DM"
  [o u]
  (-> o
      (default-destination)
      (update-in [:destinations 0 :slack] (fn [x] (-> x (assoc :user {:name u}) (dissoc :channel))))))

(defn ^:api get-team-id
  "get team-id from incoming command request or event payload
     params
       o - incoming command request or event payload"
  [o]
  ;; we also have (-> o :correlation_context :team :id
  (or (-> o :extensions :team_id)
      (-> o :team :id)))

(defn- add-ids-to-commands [slack]
  (let [num (atom 0)]
    (specter/transform [:attachments specter/ALL :actions specter/ALL]
                       #(if (:atomist/command %)
                          (-> %
                              (assoc-in [:atomist/command :id]
                                        (str (get-in % [:atomist/command :command])
                                             "-"
                                             (swap! num inc)))
                              (assoc-in [:atomist/command :automation]
                                        {:name (cs/get-config-value [:name])
                                         :version (cs/get-config-value [:version] "0.0.1-SNAPSHOT")}))
                          %)
                       slack)))

(defn- transform-to-slack-actions [slack]
  (specter/transform [:attachments specter/ALL :actions specter/ALL]
                     #(if (:atomist/command %)
                        (let [action-id (get-in % [:atomist/command :id])]
                          (case (:type %)
                            "button"
                            (-> %
                                (dissoc :atomist/command)
                                (assoc :name (str  "automation-command::" action-id))
                                (assoc :value action-id))
                            "select"
                            (-> %
                                (dissoc :atomist/command)
                                (assoc :name (str "automation-command::" action-id)))
                            %))
                        %)
                     slack))

(defn ^:api actionable-message
  "  params
       o       - incoming command request or event payload
       slack   - slack Message data where all actions may refer to
                 other CommandHandlers"
  [o slack & [opts]]

  (let [commands-with-ids (add-ids-to-commands slack)]

    (-> (select-keys o [:correlation_id :api_version :automation :team :source :command :destinations])

        (merge opts)
        (assoc :content_type "application/x-atomist-slack+json")
        (assoc :body (-> commands-with-ids
                         (transform-to-slack-actions)
                         (json/json-str)))
        (assoc :actions (->> (:attachments commands-with-ids)
                             (mapcat :actions)
                             (filter :atomist/command)
                             (mapv :atomist/command)))
        (default-destination)
        (send-on-socket))))

(defn print-api []
  (->> (ns-publics *ns*)
       (filter #(contains? (meta (second %)) :api))
       (map #(#'clojure.repl/print-doc (meta (second %))))))
