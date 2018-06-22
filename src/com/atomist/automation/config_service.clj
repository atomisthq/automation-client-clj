(ns com.atomist.automation.config-service
  (:require [mount.core :as mount]
            [clojure.tools.logging :as log]))

(defn get-config-value
  "Returns a value from the config service. Will init service is not already started."
  ([path] (get-config-value path nil))
  ([path default]
   (get-in (:automation-client-clj (mount/args)) path default)))
