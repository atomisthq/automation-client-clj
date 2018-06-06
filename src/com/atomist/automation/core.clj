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
            [com.rpl.specter :as specter])
  (:import (clojure.lang ExceptionInfo)
           (java.util UUID)))

(defn get-token []
  (if-let [api-key (some-> (mount/args) :automation-client-clj :api-key)]
    (str "Bearer " api-key)
    (let [gt (or (System/getenv "ATOMIST_TOKEN") (cs/get-config-value [:github-token]))]
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

(defn get-parameter-value [request parameter-name]
  (some->> (get-in request [:parameters])
           (filter #(= parameter-name (:name %)))
           first
           :value))

(defn mapped-parameter-value [request parameter-name]
  (some->> (get-in request [:mapped_parameters])
           (filter #(= parameter-name (:name %)))
           first
           :value))

(defn get-secret-value [request secret-name]
  (if (= "1" (:api_version request))
    (some->> (get-in request [:secrets])
             (filter #(= secret-name (:uri %)))
             first
             :value)
    (some->> (get-in request [:secrets])
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

(defn run-query [team-id query]
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

(defn success-status [command]
  (-> (select-keys command [:correlation_id :api_version :automation :team :command])
      (add-slack-details)
      (assoc :status {:code 0 :reason "success"})
      (send-on-socket)))

(defn failed-status [command]
  (-> (select-keys command [:correlation_id :api_version :automation :team :command])
      (add-slack-details)
      (assoc :status {:code 1 :reason "failure"})
      (send-on-socket)))

(defn simple-message [command s]
  (-> (select-keys command [:correlation_id :api_version :automation :team :source :command :destinations])
      (assoc :content_type "text/plain")
      (assoc :body s)
      (default-destination)
      (send-on-socket)))

(defn snippet-message [command content-str filetype title]
  (-> (select-keys command [:correlation_id :api_version :automation :team :source :command :destinations])
      (assoc :content_type "application/x-atomist-slack-file+json")
      (assoc :body (json/write-str {:content content-str :filetype filetype :title title}))
      (default-destination)
      (send-on-socket)))

(defn ingest [o x channel]
  (-> x
      (select-keys [:api_version :correlation_id :team :automation])
      (assoc :content_type "application/json"
             :body (json/json-str x)
             :destinations [{:user_agent "ingester"
                             :ingester {:root_type channel}}])
      (send-on-socket)))

(defn pprint-data-message [command data]
  (let [message (str "```"
                     (-> data
                         (clojure.pprint/pprint)
                         (with-out-str))
                     "```")]
    (simple-message command message)))

(defn guid []
  (UUID/randomUUID))

(defn update-when-seq [a-map k fn]
  (if (seq (get a-map k))
    (update a-map k fn)
    a-map))

(defn channel [o c]
  (-> o
      (default-destination)
      (update-in [:destinations 0 :slack] (fn [x] (-> x (assoc :channel {:name c}) (dissoc :user))))))

(defn user [o u]
  (-> o
      (default-destination)
      (update-in [:destinations 0 :slack] (fn [x] (-> x (assoc :user {:name u}) (dissoc :channel))))))

(defn get-team-id
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

(defn actionable-message
  "  params
       command - incoming Command Request data
       slack   - slack Message data where all actions may refer to
                 other CommandHandlers"
  [command slack & [opts]]

  (let [commands-with-ids (add-ids-to-commands slack)]

    (-> (select-keys command [:correlation_id :api_version :automation :team :source :command :destinations])

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
