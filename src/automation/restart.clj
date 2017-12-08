(ns automation.restart
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]))

(defn with-restart
  "manage a reconnect semantics for some connection thingy
    params
      create-fn  - CONNECT unary function
      destroy-fn - DISCONNECT function (fn [channel])
      send-fn    - notify automation client about new connection"
  [create-fn destroy-fn send-fn]
  (let [stop (async/chan)
        channel-closed (async/chan)
        state (atom :stopped)
        x (atom nil)
        time-in-ms 4000]
    (async/go-loop [counter 0]
      (async/alt!
        channel-closed
        (do
          (reset! state :stopped)
          (recur 0))

        (async/timeout time-in-ms)
        (recur
         (case @state
           :stopped (do
                      (reset! state :starting)
                      (try
                        (log/info "try to start " counter)
                        (reset! x (create-fn channel-closed))
                        (send-fn @x)
                        (reset! state :started)
                        0
                        (catch Throwable t
                          (reset! state :stopped)
                          (log/error t (.getMessage t))
                          (log/info @state)
                          (inc counter))))
           :starting (do
                       (log/info "... starting " counter)
                       0)
           :started 0))

        stop (do
               (destroy-fn @x)
               :stop)))
    stop))
