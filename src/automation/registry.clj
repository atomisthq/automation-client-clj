(ns automation.registry
  (:require [clojure.tools.logging :as log]
            [config-service.core :as cs]
            [com.rpl.specter :refer [transform setval walker ALL NONE pred pred= setval END select FIRST]]
            [mount.core :as mount]))

(def empty-registry {:commands [] :events [] :event-handler-map {} :command-handler-map {}})
(def registry (atom empty-registry))

(defn registration []
  {:name     (if (cs/get-config-value [:dev-mode] false)
               (str (cs/get-config-value [:name]) "-" (System/getenv "USER"))
               (cs/get-config-value [:name]))
   :version  "1.0.0"
   :team_id  (:value (cs/get-config-value [:team-id]))
   :commands (or (:commands @registry) [])
   :events   (or (:events @registry) [])})

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
                (remove nil? (map #(-> % meta type) handlers)))]
    (if (= 1 (count ns))
      (->>
       @registry
       (setval [:commands END] (types :command handlers))
       (setval [:events END] (types :event handlers))
       (transform [:command-handler-map] #(merge % (name-map :command handlers)))
       (transform [:event-handler-map] #(merge % (name-map :event handlers)))
       (reset! registry))
      (log/error "all handlers must come from the same ns"))))

(defn command-handler [o]
  (if-let [handler (get-in @registry [:command-handler-map (:name o)])]
    (apply handler [o])
    (log/warnf "no handler for %s" (:name o))))

(defn event-handler [o]
  (let [{:keys [type operationName team_id team_name correlation_id]} (:extensions o)]
    (if-let [handler (get-in @registry [:event-handler-map operationName])]
      (apply handler [(assoc o :correlation_context {:team {:id team_id :name team_name}}
                             :corrid (or correlation_id "missing"))])
      (log/warnf "no event handler for %s" operationName))))

(defn add-all-handlers [ns]
  (log/infof "Scanning %s for automations..." ns)
  (require ns)
  (->> (ns-publics ns)
       (vals)
       (filter #(and (var? %) (or (-> % meta :command) (-> % meta :event))))
       (apply add)))

(defn- init
  []
  (reset! registry empty-registry)
  (doseq [ns (cs/get-config-value [:automation-namespaces])]
    (add-all-handlers (symbol ns)))
  (log/infof "register %d commands" (->> (registration) :commands count))
  (log/infof "register %d events" (->> (registration) :events count))

  (log/infof "commands:  %s" (->> (registration) :commands (map :name) (interpose ",") (apply str)))
  (log/infof "events:  %s" (->> (registration) :events (map :name) (interpose ",") (apply str)))
  @registry)

(declare registrations)
(mount/defstate registrations
  :start (init)
  :stop (reset! registry empty-registry))
