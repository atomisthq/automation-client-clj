(ns automation.core-t
  (:require [automation.core :refer :all]
            [clojure.test :refer :all]
            [clojure.data.json :as json]))

(deftest actionable-message-tests
  (testing "Message with button"
    (with-redefs [automation.core/send-on-socket (fn [m] m)
                  automation.core/guid (fn [] "")]
      (let [message (actionable-message
                     {:corrid "corrid" :correlation_context "correlation_context" :wrong "wrong"}
                     {:text         ""
                      :attachments
                      [{:callback_id "callbackid"
                        :text        "make sure you've successfully de-authed the Atomista OAuth applications before continuing"
                        :markdwn_in  ["text"]
                        :actions     [{:text  "Continue"
                                       :type  "button"
                                       :rug   {:rug        {:type "command_handler" :name "confirm-clean-team"}
                                               :parameters [{:name "team-name" :value "team-name"}]}}
                                      {:text  "Do something"
                                       :type  "button"
                                       :rug   {:rug        {:type "command_handler" :name "do-something"}
                                               :parameters [{:name "param" :value "val"}]}}]}]
                      :unfurl_links false
                      :unfurl_media false})]
        (is (= [{:rug        {:type "command_handler" :name "confirm-clean-team"}
                 :parameters [{:name "team-name" :value "team-name"}]
                 :id         "confirm-clean-team-1"}
                {:rug        {:type "command_handler" :name "do-something"}
                 :parameters [{:name "param" :value "val"}]
                 :id         "do-something-2"}] (:actions message)))
        (is (= [{:text  "Continue"
                 :type  "button"
                 :name  "rug"
                 :value "confirm-clean-team-1"}
                {:text  "Do something"
                 :type  "button"
                 :name  "rug"
                 :value "do-something-2"}]
               (->> (json/read-str (:message message) :key-fn keyword)
                    :attachments
                    (mapcat :actions)
                    (into [])))))))

  (testing "Message with menu"
    (with-redefs [automation.core/send-on-socket (fn [m] m)
                  automation.core/guid (fn [] "")]
      (let [message (actionable-message
                     {:corrid "corrid" :correlation_context "correlation_context" :wrong "wrong"}
                     {:text         ""
                      :attachments
                      [{:footer      ""
                        :callback_id "callbackid"
                        :text        "make sure you've successfully de-authed the Atomista OAuth applications before continuing"
                        :markdwn_in  ["text"]
                        :actions     [{:text    "Select this"
                                       :type    "select"
                                       :rug     {:rug            {:type "command_handler"
                                                                  :name "confirm-clean-team"}
                                                 :parameter_name "some-param"
                                                 :parameters     [{:name "team-name" :value "team-name"}]}
                                       :options [{:text "Option1" :value "option1"}]}]}]
                      :unfurl_links false
                      :unfurl_media false})]
        (is (= [{:rug        {:type "command_handler"
                              :name "confirm-clean-team"}
                 :parameter_name "some-param"
                 :parameters [{:name "team-name" :value "team-name"}]
                 :id         "confirm-clean-team-1"}]
               (:actions message))
            "actions part of message is wrong")
        ;; validate that the Message is right
        (is (= [{:text  "Select this"
                 :type  "select"
                 :name  "rug::confirm-clean-team-1"
                 :options [{:text "Option1" :value "option1"}]}]
               (->> (json/read-str (:message message) :key-fn keyword)
                    :attachments
                    (mapcat :actions)
                    (into [])))
            "Slack-specific part of message is wrong")))))
