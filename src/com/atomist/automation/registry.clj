(ns com.atomist.automation.registry
  (:require [clojure.tools.logging :as log]
            [com.atomist.automation.config-service :as cs]
            [com.rpl.specter :refer [transform setval walker ALL NONE pred pred= setval END select FIRST]]
            [mount.core :as mount]))

(def empty-registry {:commands [] :events [] :event-handler-map {} :command-handler-map {}})
(def registry (atom empty-registry))

(defn registration []
  {:name (if (cs/get-config-value [:dev-mode] false)
           (str (cs/get-config-value [:name]) "-" (System/getenv "USER"))
           (cs/get-config-value [:name]))
   :version (cs/get-config-value [:version] "0.0.1-SNAPSHOT")
   :team_ids [(or (System/getenv "ATOMIST_TEAM") (cs/get-config-value [:team-id]))]
   :commands (or (:commands @registry) [])
   :events (or (:events @registry) [])
   :ingesters (or (:ingesters @registry) [])
   :api_version "1"})

(defn- add
  "add a handler var which exposes a command handler
     - you can recompile handlers without bumping anything restarting the client
     - you can edit the metadata but you have to rebuild the automation.core namespace afterwards
     - if you edit the name of a command, all bets are off."
  [& handlers]
  {:pre [(every? var? handlers)]}
  (let [ns (->> handlers (map meta) (map :ns) (into #{}))
        name-map (fn [type handlers]
                   (reduce
                    (fn [acc h]
                      (if (type (meta h))
                        (assoc acc (-> h meta type :name) h)
                        acc))
                    {}
                    handlers))
        types (fn [type handlers]
                (remove nil? (map #(-> % meta type) handlers)))
        ingesters (fn [handlers]
                    (->> handlers
                         (filter #(-> % meta :ingester))
                         (map (fn [type] (assoc (-> type meta :ingester)
                                                :types (var-get type))))
                         (into [])))]
    (if (= 1 (count ns))
      (->>
       @registry
       (setval [:commands END] (types :command handlers))
       (setval [:events END] (types :event handlers))
       (setval [:ingesters END] (ingesters handlers))
       (transform [:command-handler-map] #(merge % (name-map :command handlers)))
       (transform [:event-handler-map] #(merge % (name-map :event handlers)))
       (reset! registry))
      (log/error "all handlers must come from the same ns")))) @(defn command-handler
                                                                  "everything in command-handler-map should be a var"
                                                                  [o]
                                                                  (if-let [handler (get-in @registry [:command-handler-map (:command o)])]
                                                                    (apply handler [o])
                                                                    (log/warnf "no handler for %s" (:command o))))

(defn event-handler [o]
  (let [{:keys [operationName team_id team_name correlation_id]} (:extensions o)]
    (if-let [handler (get-in @registry [:event-handler-map operationName])]
      (apply handler [(assoc o
                             :correlation_id (or correlation_id "missing")
                             :correlation_context {:team {:id team_id :name team_name}}
                             :corrid (or correlation_id "missing")
                             :team {:id team_id :name team_name})])
      (log/warnf "no event handler for %s" operationName))))

(defn add-all-handlers [ns]
  (log/infof "Scanning %s for automations..." ns)
  (require ns)
  (->> (ns-publics ns)
       (vals)
       (filter #(and (var? %) (or (-> % meta :command) (-> % meta :event) (-> % meta :ingester))))
       (apply add)))

(defn- init
  []
  (reset! registry empty-registry)
  (doseq [ns (cs/get-config-value [:automation-namespaces])]
    (add-all-handlers (symbol ns)))
  (log/infof "register %d commands" (->> (registration) :commands count))
  (log/infof "register %d events" (->> (registration) :events count))
  (log/infof "register %d ingesters" (->> (registration) :ingesters count))
  (log/infof "commands:  %s" (->> (registration) :commands (map :name) (interpose ",") (apply str)))
  (log/infof "events:  %s" (->> (registration) :events (map :name) (interpose ",") (apply str)))
  (log/infof "ingesters:  %s" (->> (registration) :ingesters (str)))
  @registry)

(declare registrations)
(mount/defstate registrations
  :start (do
           (let [config (:automation-client-clj (mount/args))]
             (if-not (and
                      (:team-id config)
                      (:github-token config)
                      (:automation-namespaces config)
                      (:name config)
                      (:version config))
               (throw (ex-info "team-id, github-token, automation-namespaces, name and version are all required arguments" config))))
           (init))
  :stop (reset! registry empty-registry))
