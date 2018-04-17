(ns com.atomist.automation.config-service
  (:require [cprop.core :refer [load-config]]
            [cprop.source :as source]
            [mount.core :as mount]
            [clojure.tools.logging :as log]))

(declare env)
(mount/defstate env :start (load-config
                            :merge
                            [(mount/args)
                             (source/from-system-props)
                             (source/from-env)]))

(defn get-config-value
  "Returns a value from the config service. Will init service is not already started."
  ([path] (get-config-value path nil))
  ([path default]
   (log/infof "get %s from env" (str path))
   (or
    (get-in env path)
    default)))
