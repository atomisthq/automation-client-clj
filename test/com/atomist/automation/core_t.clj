(ns com.atomist.automation.core-t
  (:require [com.atomist.automation.core :refer :all]
            [clojure.test :refer :all]
            [clojure.data.json :as json]))

(defn trace [x] (println x) x)

(deftest message-tests
  (with-redefs [com.atomist.automation.core/send-on-socket
                (fn [x]
                  (is (= {:body "whatever"
                          :content_type "text/plain"
                          :destinations [{:slack {:channel {:name "channel"}}
                                          :user_agent "slack"}]
                          :source {:slack {:channel {:name "channel"}
                                           :user {:name "some-user"}}}}
                         x)))]
    (testing "simple message to source"
      (simple-message {:source {:slack {:channel {:name "channel"}
                                        :user {:name "some-user"}}}} "whatever")))
  (with-redefs [com.atomist.automation.core/send-on-socket
                (fn [x]
                  (is (= {:body "whatever"
                          :content_type "text/plain"
                          :destinations [{:slack {:channel {:name "general"}}
                                          :user_agent "slack"}]
                          :source {:slack {:channel {:name "notgeneral"}
                                           :user {:name "some-user"}}}}
                         x)))]
    (testing "simple message to general channel"
      (-> {:source {:slack {:channel {:name "notgeneral"}
                            :user {:name "some-user"}}}}
          (channel "general")
          (simple-message "whatever"))))
  (with-redefs [com.atomist.automation.core/send-on-socket
                (fn [x]
                  (is (= {:body "whatever"
                          :content_type "text/plain"
                          :destinations [{:slack {:user {:name "some-other-user"}}
                                          :user_agent "slack"}]
                          :source {:slack {:channel {:name "notgeneral"}
                                           :user {:name "some-user"}}}}
                         x)))]
    (testing "simple DM message"
      (-> {:source {:slack {:channel {:name "notgeneral"}
                            :user {:name "some-user"}}}}
          (user "some-other-user")
          (simple-message "whatever"))))
  (with-redefs [com.atomist.automation.core/send-on-socket
                (fn [x]
                  (is (= {:content_type "application/x-atomist-slack-file+json"
                          :destinations [{:slack {:channel {:name "notgeneral"}}
                                          :user_agent "slack"}]
                          :source {:slack {:channel {:name "notgeneral"}
                                           :user {:name "some-user"}}}}
                         (dissoc x :body)))
                  (is (= {"content" "whatever"
                          "filetype" "file"
                          "title" "crap"}
                         (json/read-str (:body x)))))]
    (testing "snippet message"
      (-> {:source {:slack {:channel {:name "notgeneral"}
                            :user {:name "some-user"}}}}
          (snippet-message "whatever" "file" "crap")))))

(deftest actionable-message-tests

  (testing "Message with button"
    (with-redefs [com.atomist.automation.core/send-on-socket (fn [m] m)
                  com.atomist.automation.core/guid (fn [] "")]
      (let [message (actionable-message
                     {:corrid "corrid" :correlation_context "correlation_context" :wrong "wrong"}
                     {:text ""
                      :attachments
                      [{:callback_id "callbackid"
                        :text "make sure you've successfully de-authed the Atomista OAuth applications before continuing"
                        :markdwn_in ["text"]
                        :actions [{:text "Continue"
                                   :type "button"
                                   :atomist/command {:command "confirm-clean-team"
                                                     :parameters [{:name "team-name" :value "team-name"}]}}
                                  {:text "Do something"
                                   :type "button"
                                   :atomist/command {:command "do-something"
                                                     :parameters [{:name "param" :value "val"}]}}]}]
                      :unfurl_links false
                      :unfurl_media false})]
        (is (= [{:parameters [{:name "team-name" :value "team-name"}]
                 :command "confirm-clean-team"
                 :automation {:name nil
                              :version "0.0.1-SNAPSHOT"}
                 :id "confirm-clean-team-1"}
                {:parameters [{:name "param" :value "val"}]
                 :automation {:name nil
                              :version "0.0.1-SNAPSHOT"}
                 :command "do-something"
                 :id "do-something-2"}]
               (:actions message)))
        (is (= [{:text "Continue"
                 :type "button"
                 :name "automation-command::confirm-clean-team-1"
                 :value "confirm-clean-team-1"}
                {:text "Do something"
                 :type "button"
                 :name "automation-command::do-something-2"
                 :value "do-something-2"}]
               (->> (json/read-str (:body message) :key-fn keyword)
                    :attachments
                    (mapcat :actions)
                    (into [])))))))

  (testing "Message with menu"
    (with-redefs [com.atomist.automation.core/send-on-socket (fn [m] m)
                  com.atomist.automation.core/guid (fn [] "")]
      (let [message (actionable-message
                     {:corrid "corrid" :correlation_context "correlation_context" :wrong "wrong"}
                     {:text ""
                      :attachments
                      [{:footer ""
                        :callback_id "callbackid"
                        :text "make sure you've successfully de-authed the Atomista OAuth applications before continuing"
                        :markdwn_in ["text"]
                        :actions [{:text "Select this"
                                   :type "select"
                                   :atomist/command {:command "confirm-clean-team"
                                                     :parameter_name "some-param"
                                                     :parameters [{:name "team-name" :value "team-name"}]}
                                   :options [{:text "Option1" :value "option1"}]}]}]
                      :unfurl_links false
                      :unfurl_media false})]
        (is (= [{:parameter_name "some-param"
                 :command "confirm-clean-team"
                 :automation {:name nil
                              :version "0.0.1-SNAPSHOT"}
                 :parameters [{:name "team-name" :value "team-name"}]
                 :id "confirm-clean-team-1"}]
               (:actions message))
            "actions part of message is wrong")
        ;; validate that the Message is right
        (is (= [{:text "Select this"
                 :type "select"
                 :name "automation-command::confirm-clean-team-1"
                 :options [{:text "Option1" :value "option1"}]}]
               (->> (json/read-str (:body message) :key-fn keyword)
                    :attachments
                    (mapcat :actions)
                    (into [])))
            "Slack-specific part of message is wrong")))))
