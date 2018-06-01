(ns com.atomist.automation.graphql
  (:require [schema.core :as s]
            [com.atomist.automation.core :as api]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]))

(defn team-id->chat-team-id
  [team-id]
  (let [chat-team (some-> (api/run-query team-id "{ChatTeam {id}}")
                          :data
                          :ChatTeam
                          first)]
    (log/infof "Load ChatTeam from %s: %s" team-id chat-team)
    (:id chat-team)))

(defn default-team
  "copy chat team id into [:destinations 0 :slack :team :id]"
  [o]
  (update-in o [:destinations 0 :slack]
             #(assoc % :team {:id (-> o
                                      (api/get-team-id)
                                      (team-id->chat-team-id))})))

(s/defn get-team-preference :- {s/Str s/Any}
  [team-id :- s/Str pref-name :- s/Str]
  (let [prefs (some-> (api/run-query team-id "{ChatTeam {id preferences {name value}}}")
                      :data
                      :ChatTeam
                      first
                      :preferences
                      (->>
                       (filter #(= pref-name (:name %))))
                      first
                      :value
                      (json/read-str))]
    (log/infof "Loaded %s preference %s -> %s" team-id pref-name prefs)
    prefs))