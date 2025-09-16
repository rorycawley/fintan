(ns standard-agent.providers-test
  (:require [clojure.test :refer :all]
            [standard-agent.providers :as providers]
            [standard-agent.core :as agent]
            [clojure.data.json :as json])
  (:import [java.io Closeable]))

;; ============================================================================
;; Mock HTTP Client for Testing
;; ============================================================================

(def ^:dynamic *mock-http-responses* nil)

(defn mock-http-post [url options]
      (if *mock-http-responses*
        (let [response (first @*mock-http-responses*)]
             (swap! *mock-http-responses* rest)
             response)
        (throw (Exception. "No mock response configured"))))

;; ============================================================================
;; Provider Implementation Tests
;; ============================================================================

(deftest test-mock-provider
         (testing "Sequential responses"
                  (let [provider (providers/mock-provider ["r1" "r2" "r3"])]
                       (is (= "r1" (agent/invoke provider "prompt1" {})))
                       (is (= "r2" (agent/invoke provider "prompt2" {})))
                       (is (= "r3" (agent/invoke provider "prompt3" {}))))

                  (testing "Single response repeated"
                           (let [provider (providers/mock-provider ["single"])]
                                (is (= "single" (agent/invoke provider "p1" {})))
                                ;; After exhausting responses, continues returning nil or could repeat last
                                )))

         (deftest test-openai-provider-structure
                  (testing "Provider creation"
                           (let [provider (providers/openai-provider "test-key")]
                                (is (satisfies? agent/LLMProvider provider))))

                  (testing "Request formatting with mocked HTTP"
                           (with-redefs [providers/make-http-request
                                         (fn [{:keys [url headers body]}]
                                             ;; Verify request structure
                                             (is (= "https://api.openai.com/v1/chat/completions" url))
                                             (is (= "Bearer test-key" (get headers "Authorization")))
                                             (let [parsed-body (json/read-str body :key-fn keyword)]
                                                  (is (= "gpt-4" (:model parsed-body)))
                                                  (is (= [{:role "user" :content "test prompt"}] (:messages parsed-body)))
                                                  (is (= 0.7 (:temperature parsed-body))))
                                             ;; Return mock response
                                             {:status 200
                                              :body {:choices [{:message {:content "test response"}}]
                                                     :usage {:total_tokens 100}}})]

                                        (let [provider (providers/openai-provider "test-key")
                                              result (agent/invoke provider "test prompt" {})]
                                             (is (= "test response" result)))))

                  (testing "Error handling"
                           (with-redefs [providers/make-http-request
                                         (fn [_] {:status 500 :body {:error "Server error"}})]

                                        (let [provider (providers/openai-provider "test-key")]
                                             (is (thrown-with-msg? Exception #"OpenAI API error"
                                                                   (agent/invoke provider "test" {})))))))

         (deftest test-claude-provider-structure
                  (testing "Provider creation and configuration"
                           (let [provider (providers/claude-provider {:api-key "claude-key"
                                                                      :model "claude-3-sonnet"})]
                                (is (satisfies? agent/LLMProvider provider))))

                  (testing "Request formatting"
                           (with-redefs [providers/make-http-request
                                         (fn [{:keys [url headers body]}]
                                             (is (= "https://api.anthropic.com/v1/messages" url))
                                             (is (= "claude-key" (get headers "x-api-key")))
                                             (is (= "2023-06-01" (get headers "anthropic-version")))
                                             {:status 200
                                              :body {:content [{:text "claude response"}]
                                                     :usage {:input_tokens 10 :output_tokens 20}}})]

                                        (let [provider (providers/claude-provider {:api-key "claude-key"})
                                              result (agent/invoke provider "test" {})]
                                             (is (= "claude response" result))))))

         (deftest test-gemini-provider-structure
                  (testing "Provider creation"
                           (let [provider (providers/gemini-provider {:api-key "gemini-key"})]
                                (is (satisfies? agent/LLMProvider provider))))

                  (testing "Request formatting"
                           (with-redefs [providers/make-http-request
                                         (fn [{:keys [url body]}]
                                             (is (str/includes? url "generativelanguage.googleapis.com"))
                                             (is (str/includes? url "key=gemini-key"))
                                             (let [parsed-body (json/read-str body :key-fn keyword)]
                                                  (is (= [{:parts [{:text "test"}]}] (:contents parsed-body))))
                                             {:status 200
                                              :body {:candidates [{:content {:parts [{:text "gemini response"}]}}]}})]

                                        (let [provider (providers/gemini-provider {:api-key "gemini-key"})
                                              result (agent/invoke provider "test" {})]
                                             (is (= "gemini response" result))))))

         ;; ============================================================================
         ;; Provider Factory Tests
         ;; ============================================================================

         (deftest test-create-provider
                  (testing "Create OpenAI provider"
                           (let [provider (providers/create-provider {:provider :openai
                                                                      :api-key "key"})]
                                (is (satisfies? agent/LLMProvider provider))))

                  (testing "Create Claude provider"
                           (let [provider (providers/create-provider {:provider :claude
                                                                      :api-key "key"})]
                                (is (satisfies? agent/LLMProvider provider))))

                  (testing "Create Gemini provider"
                           (let [provider (providers/create-provider {:provider :gemini
                                                                      :api-key "key"})]
                                (is (satisfies? agent/LLMProvider provider))))

                  (testing "Create mock provider"
                           (let [provider (providers/create-provider {:provider :mock
                                                                      :responses ["test"]})]
                                (is (= "test" (agent/invoke provider "input" {})))))

                  (testing "Unknown provider throws"
                           (is (thrown-with-msg? Exception #"Unknown provider"
                                                 (providers/create-provider {:provider :unknown})))))

         ;; ============================================================================
         ;; Provider Options Tests
         ;; ============================================================================

         (deftest test-provider-options
                  (testing "Temperature option"
                           (with-redefs [providers/make-http-request
                                         (fn [{:keys [body]}]
                                             (let [parsed (json/read-str body :key-fn keyword)]
                                                  (is (= 0.9 (:temperature parsed))))
                                             {:status 200 :body {:choices [{:message {:content "r"}}]}})]

                                        (let [provider (providers/openai-provider "key")]
                                             (agent/invoke provider "test" {:temperature 0.9}))))

                  (testing "Max tokens option"
                           (with-redefs [providers/make-http-request
                                         (fn [{:keys [body]}]
                                             (let [parsed (json/read-str body :key-fn keyword)]
                                                  (is (= 500 (:max_tokens parsed))))
                                             {:status 200 :body {:choices [{:message {:content "r"}}]}})]

                                        (let [provider (providers/openai-provider "key")]
                                             (agent/invoke provider "test" {:max-tokens 500}))))

                  (testing "Provider-specific options merge with defaults"
                           (with-redefs [providers/make-http-request
                                         (fn [{:keys [body]}]
                                             (let [parsed (json/read-str body :key-fn keyword)]
                                                  (is (= 0.5 (:temperature parsed))) ; Override
                                                  (is (= 2000 (:max_tokens parsed)))) ; From options
                                             {:status 200 :body {:choices [{:message {:content "r"}}]}})]

                                        (let [provider (providers/openai-provider {:api-key "key"
                                                                                   :options {:temperature 0.5}})]
                                             (agent/invoke provider "test" {:max-tokens 2000})))))

         ;; ============================================================================
         ;; Provider Closeable Tests
         ;; ============================================================================

         (deftest test-provider-closeable
                  (testing "Providers implement Closeable"
                           (let [providers [(providers/openai-provider "key")
                                            (providers/claude-provider {:api-key "key"})
                                            (providers/gemini-provider {:api-key "key"})
                                            (providers/mock-provider ["test"])]]

                                (doseq [p providers]
                                       (is (instance? Closeable p))
                                       (.close p))))) ; Should not throw

         ;; ============================================================================
         ;; Integration with Agent Tests
         ;; ============================================================================

         (deftest test-provider-with-agent
                  (testing "Provider works with agent"
                           (let [logic (reify agent/AgentLogic
                                              (prepare-prompt [_ input] (str "Process: " input))
                                              (process-response [_ response] {:result response}))
                                 provider (providers/mock-provider ["mock response"])
                                 agent (agent/create-agent logic provider)]

                                (is (= {:result "mock response"} (agent/execute agent "test")))))

                  (testing "Hot-swap between providers"
                           (let [logic (reify agent/AgentLogic
                                              (prepare-prompt [_ input] input)
                                              (process-response [_ response] response))
                                 provider1 (providers/mock-provider ["response1"])
                                 provider2 (providers/mock-provider ["response2"])
                                 agent (agent/create-agent logic provider1)]

                                (is (= "response1" (agent/execute agent "test")))
                                (agent/swap-provider! agent provider2)
                                (is (= "response2" (agent/execute agent "test"))))))

         ;; ============================================================================
         ;; Provider Configuration Tests
         ;; ============================================================================

         (deftest test-provider-configs
                  (testing "Standard configurations"
                           (let [configs providers/provider-configs]
                                (is (contains? configs :fast-cheap))
                                (is (contains? configs :creative))
                                (is (contains? configs :accurate))
                                (is (contains? configs :balanced))

                                (is (= :gemini (get-in configs [:fast-cheap :provider])))
                                (is (= :openai (get-in configs [:creative :provider])))
                                (is (= :claude (get-in configs [:accurate :provider]))))))

         (deftest test-get-provider-config
                  (testing "Get config with API keys"
                           (let [api-keys {:openai "oai-key"
                                           :claude "cl-key"
                                           :gemini "gem-key"}
                                 config (providers/get-provider-config :creative api-keys)]

                                (is (= :openai (:provider config)))
                                (is (= "oai-key" (:api-key config)))
                                (is (= 0.9 (get-in config [:options :temperature]))))))

         ;; ============================================================================
         ;; Error Recovery Tests
         ;; ============================================================================

         (deftest test-provider-error-scenarios
                  (testing "Network timeout handling"
                           (with-redefs [providers/make-http-request
                                         (fn [_]
                                             (Thread/sleep 100)
                                             (throw (Exception. "Connection timeout")))]

                                        (let [provider (providers/openai-provider "key")]
                                             (is (thrown-with-msg? Exception #"Connection timeout"
                                                                   (agent/invoke provider "test" {}))))))

                  (testing "Invalid API key"
                           (with-redefs [providers/make-http-request
                                         (fn [_] {:status 401 :body {:error "Invalid API key"}})]

                                        (let [provider (providers/openai-provider "bad-key")]
                                             (is (thrown? Exception (agent/invoke provider "test" {}))))))

                  (testing "Rate limiting"
                           (with-redefs [providers/make-http-request
                                         (fn [_] {:status 429 :body {:error "Rate limit exceeded"}})]

                                        (let [provider (providers/claude-provider {:api-key "key"})]
                                             (is (thrown? Exception (agent/invoke provider "test" {})))))))

         ;; ============================================================================
         ;; Provider Performance Tests
         ;; ============================================================================

         (deftest test-provider-performance
                  (testing "Mock provider performance"
                           (let [provider (providers/mock-provider (repeat 1000 "response"))
                                 start (System/currentTimeMillis)]

                                (dotimes [_ 1000]
                                         (agent/invoke provider "test" {}))

                                (let [elapsed (- (System/currentTimeMillis) start)]
                                     (is (< elapsed 100))))) ; Should handle 1000 calls in < 100ms

                  (testing "Provider creation performance"
                           (let [start (System/currentTimeMillis)]

                                (dotimes [_ 100]
                                         (providers/openai-provider "key")
                                         (providers/claude-provider {:api-key "key"})
                                         (providers/gemini-provider {:api-key "key"}))

                                (let [elapsed (- (System/currentTimeMillis) start)]
                                     (is (< elapsed 100))))))) ; Should create 300 providers in < 100ms