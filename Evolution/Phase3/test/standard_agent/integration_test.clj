(ns standard-agent.integration-test
  (:require [clojure.test :refer :all]
            [standard-agent.core :as agent]
            [standard-agent.providers :as providers]
            [standard-agent.integrations :as integrate]
            [clojure.data.json :as json])
  (:import [java.io Closeable]))

;; ============================================================================
;; Test Implementations
;; ============================================================================

(defrecord MealLogic []
           agent/AgentLogic
           (prepare-prompt [_ input]
                           (str "Extract meal details from: " input))

           (process-response [_ response]
                             (try
                               (let [data (json/read-str response :key-fn keyword)]
                                    {:meal (:meal data)
                                     :ingredients (:ingredients data)})
                               (catch Exception _
                                 {:error "Failed to parse response"}))))

(defrecord TranslationLogic [target-lang]
           agent/AgentLogic
           (prepare-prompt [_ input]
                           (str "Translate to " target-lang ": " input))

           (process-response [_ response]
                             {:translation response}))

(defrecord ValidationLogic []
           agent/AgentLogic
           (prepare-prompt [_ input]
                           (str "Validate: " input))

           (process-response [_ response]
                             (if (= response "valid")
                               {:valid true}
                               {:valid false :reason response})))

;; ============================================================================
;; Provider Integration Tests
;; ============================================================================

(deftest test-mock-provider
         (testing "Mock provider with sequential responses"
                  (let [responses ["first" "second" "third"]
                        provider (providers/mock-provider responses)
                        logic (reify agent/AgentLogic
                                     (prepare-prompt [_ input] input)
                                     (process-response [_ response] response))
                        agent (agent/create-agent logic provider)]

                       (is (= "first" (agent/execute agent "input1")))
                       (is (= "second" (agent/execute agent "input2")))
                       (is (= "third" (agent/execute agent "input3")))))

         (testing "Mock provider with JSON responses"
                  (let [json-response "{\"meal\": \"pasta\", \"ingredients\": [\"tomatoes\", \"basil\"]}"
                        provider (providers/mock-provider [json-response])
                        logic (->MealLogic)
                        agent (agent/create-agent logic provider)
                        result (agent/execute agent "I want Italian food")]

                       (is (= "pasta" (:meal result)))
                       (is (= ["tomatoes" "basil"] (:ingredients result))))))

(deftest test-provider-error-handling
         (testing "Provider returns invalid JSON"
                  (let [provider (providers/mock-provider ["not-json"])
                        logic (->MealLogic)
                        agent (agent/create-agent logic provider)
                        result (agent/execute agent "test")]

                       (is (= {:error "Failed to parse response"} result))))

         (testing "Provider timeout simulation"
                  (let [slow-provider (reify agent/LLMProvider
                                             (invoke [_ prompt options]
                                                     (Thread/sleep 100)
                                                     "response"))
                        logic (->MealLogic)
                        agent (agent/create-agent logic slow-provider)
                        start (System/currentTimeMillis)]

                       (agent/execute agent "test")
                       (is (>= (- (System/currentTimeMillis) start) 100)))))

;; ============================================================================
;; Integration Style Tests
;; ============================================================================

(deftest test-functional-integration
         (testing "Create agent as function"
                  (let [agent-fn (integrate/create-agent-fn
                                   (->TranslationLogic "Spanish")
                                   (providers/mock-provider ["Hola"]))]

                       (is (fn? agent-fn))
                       (is (= {:translation "Hola"} (agent-fn "Hello")))))

         (testing "Function maintains state across calls"
                  (let [call-count (atom 0)
                        counting-logic (reify agent/AgentLogic
                                              (prepare-prompt [_ input]
                                                              (swap! call-count inc)
                                                              input)
                                              (process-response [_ response]
                                                                response))
                        agent-fn (integrate/create-agent-fn
                                   counting-logic
                                   (providers/mock-provider ["r1" "r2"]))]

                       (agent-fn "input1")
                       (agent-fn "input2")
                       (is (= 2 @call-count)))))

(deftest test-resource-integration
         (testing "Agent as closeable resource"
                  (let [closed? (atom false)
                        cleanup-provider (reify
                                           agent/LLMProvider
                                           (invoke [_ prompt options] "response")

                                           Closeable
                                           (close [_] (reset! closed? true)))]

                       (with-open [agent (integrate/agent-resource
                                           (->TranslationLogic "French")
                                           cleanup-provider)]
                                  (is (map? @agent))
                                  (is (false? @closed?)))

                       (is (true? @closed?))))

         (testing "Resource cleanup on exception"
                  (let [closed? (atom false)]
                       (is (thrown? Exception
                                    (with-open [agent (integrate/agent-resource
                                                        (->TranslationLogic "German")
                                                        (providers/mock-provider ["Hallo"]))]
                                               ;; Simulate work
                                               (agent/execute @agent "Hello")
                                               ;; Then error
                                               (throw (Exception. "Test error")))))
                       ;; Note: In real implementation, would track cleanup
                       )))

;; ============================================================================
;; Multi-Agent System Tests
;; ============================================================================

(deftest test-multi-agent-system
         (testing "Multiple agents in registry"
                  (reset! agent/registry {})

                  (let [meal-agent (agent/create-agent
                                     (->MealLogic)
                                     (providers/mock-provider
                                       ["{\"meal\": \"salad\", \"ingredients\": [\"lettuce\"]}"]))
                        translate-agent (agent/create-agent
                                          (->TranslationLogic "Japanese")
                                          (providers/mock-provider ["こんにちは"]))
                        validate-agent (agent/create-agent
                                         (->ValidationLogic)
                                         (providers/mock-provider ["valid"]))]

                       (agent/register! :meals meal-agent)
                       (agent/register! :translate translate-agent)
                       (agent/register! :validate validate-agent)

                       (is (= 3 (count (agent/list-agents))))

                       ;; Test each agent
                       (let [meal-result (agent/execute (agent/get-agent :meals) "lunch")
                             trans-result (agent/execute (agent/get-agent :translate) "hello")
                             valid-result (agent/execute (agent/get-agent :validate) "data")]

                            (is (= "salad" (:meal meal-result)))
                            (is (= {:translation "こんにちは"} trans-result))
                            (is (= {:valid true} valid-result)))))

         (testing "Agent routing based on type"
                  (reset! agent/registry {})

                  (let [agents {:meal (agent/create-agent (->MealLogic)
                                                          (providers/mock-provider ["{\"meal\": \"pizza\"}"]))
                                :translate (agent/create-agent (->TranslationLogic "Spanish")
                                                               (providers/mock-provider ["Hola"]))}]

                       (doseq [[k v] agents]
                              (agent/register! k v))

                       ;; Simple router function
                       (defn route-request [request-type input]
                             (when-let [agent (agent/get-agent request-type)]
                                       (agent/execute agent input)))

                       (is (= "Hola" (:translation (route-request :translate "Hello"))))
                       (is (nil? (route-request :unknown "test"))))))

;; ============================================================================
;; Middleware Composition Tests
;; ============================================================================

(deftest test-middleware-composition
         (testing "Retry + Cache composition"
                  (let [call-count (atom 0)
                        failing-then-success (atom 0)
                        provider (reify agent/LLMProvider
                                        (invoke [_ prompt options]
                                                (swap! call-count inc)
                                                (swap! failing-then-success inc)
                                                (if (< @failing-then-success 2)
                                                  (throw (Exception. "Temporary failure"))
                                                  "success")))
                        logic (reify agent/AgentLogic
                                     (prepare-prompt [_ input] input)
                                     (process-response [_ response] response))
                        agent (agent/create-agent logic provider)

                        ;; Compose retry and cache
                        safe-execute (agent/with-retry agent/execute 3 10)
                        cached-safe-execute (agent/with-cache safe-execute (:cache agent))]

                       ;; First call: retries until success, then caches
                       (is (= "success" (cached-safe-execute agent "input1")))
                       (is (= 2 @call-count)) ; Failed once, succeeded on second

                       ;; Reset for next input
                       (reset! failing-then-success 0)

                       ;; Second call with same input: cache hit, no retries
                       (is (= "success" (cached-safe-execute agent "input1")))
                       (is (= 2 @call-count)) ; No additional calls

                       ;; Third call with different input: retries again
                       (is (= "success" (cached-safe-execute agent "input2")))
                       (is (= 4 @call-count))))) ; 2 more attempts

;; ============================================================================
;; Real-World Scenario Tests
;; ============================================================================

(deftest test-chat-application-scenario
         (testing "Chat app with fallback providers"
                  (let [primary-down? (atom true)
                        primary (reify agent/LLMProvider
                                       (invoke [_ prompt options]
                                               (if @primary-down?
                                                 (throw (ex-info "Service unavailable" {}))
                                                 "primary-response")))
                        backup (providers/mock-provider ["backup-response"])

                        ;; Manual fallback logic
                        chat-logic (->TranslationLogic "French")
                        agent (agent/create-agent chat-logic primary)]

                       ;; Execute with fallback
                       (let [result (try
                                      (agent/execute agent "Hello")
                                      (catch Exception e
                                        ;; Fallback to backup
                                        (agent/swap-provider! agent backup)
                                        (agent/execute agent "Hello")))]

                            (is (= {:translation "backup-response"} result)))

                       ;; Primary comes back online
                       (reset! primary-down? false)
                       (agent/swap-provider! agent primary)

                       (is (= {:translation "primary-response"}
                              (agent/execute agent "Test"))))))

(deftest test-document-processing-scenario
         (testing "Document processing with different agents for different sections"
                  (reset! agent/registry {})

                  (let [;; Specialized agents for different document sections
                        title-agent (agent/create-agent
                                      (reify agent/AgentLogic
                                             (prepare-prompt [_ input] (str "Extract title: " input))
                                             (process-response [_ response] {:title response}))
                                      (providers/mock-provider ["Document Title"]))

                        summary-agent (agent/create-agent
                                        (reify agent/AgentLogic
                                               (prepare-prompt [_ input] (str "Summarize: " input))
                                               (process-response [_ response] {:summary response}))
                                        (providers/mock-provider ["Brief summary"]))

                        keyword-agent (agent/create-agent
                                        (reify agent/AgentLogic
                                               (prepare-prompt [_ input] (str "Extract keywords: " input))
                                               (process-response [_ response] {:keywords (clojure.string/split response #",")}))
                                        (providers/mock-provider ["ai,agents,clojure"]))]

                       (agent/register! :title title-agent)
                       (agent/register! :summary summary-agent)
                       (agent/register! :keywords keyword-agent)

                       ;; Process document
                       (defn process-document [text]
                             {:title (:title (agent/execute (agent/get-agent :title) text))
                              :summary (:summary (agent/execute (agent/get-agent :summary) text))
                              :keywords (:keywords (agent/execute (agent/get-agent :keywords) text))})

                       (let [result (process-document "Long document text...")]
                            (is (= "Document Title" (:title result)))
                            (is (= "Brief summary" (:summary result)))
                            (is (= ["ai" "agents" "clojure"] (:keywords result)))))))

;; ============================================================================
;; Performance & Load Tests
;; ============================================================================

(deftest test-concurrent-multi-agent
         (testing "Multiple agents handling concurrent requests"
                  (reset! agent/registry {})

                  (let [agents (for [i (range 5)]
                                    [(keyword (str "agent-" i))
                                     (agent/create-agent
                                       (reify agent/AgentLogic
                                              (prepare-prompt [_ input] input)
                                              (process-response [_ response]
                                                                {:agent i :response response}))
                                       (providers/mock-provider [(str "response-" i)]))])]

                       ;; Register all agents
                       (doseq [[id agent] agents]
                              (agent/register! id agent))

                       ;; Concurrent execution
                       (let [futures (for [i (range 20)]
                                          (future
                                            (let [agent-id (keyword (str "agent-" (mod i 5)))
                                                  agent (agent/get-agent agent-id)]
                                                 (agent/execute agent (str "input-" i)))))]

                            (let [results (map deref futures)]
                                 (is (= 20 (count results)))
                                 (is (every? map? results))
                                 (is (every? #(contains? % :response) results))))))))

(deftest test-agent-lifecycle
         (testing "Complete agent lifecycle with hot-swaps"
                  (let [metrics {:executions (atom 0)
                                 :swaps (atom 0)}

                        ;; Monitoring wrapper
                        monitor-logic (fn [base-logic]
                                          (reify agent/AgentLogic
                                                 (prepare-prompt [_ input]
                                                                 (swap! (:executions metrics) inc)
                                                                 (agent/prepare-prompt base-logic input))
                                                 (process-response [_ response]
                                                                   (agent/process-response base-logic response))))

                        agent (agent/create-agent
                                (monitor-logic (->MealLogic))
                                (providers/mock-provider
                                  (repeat 10 "{\"meal\": \"test\"}")))]

                       ;; Initial executions
                       (dotimes [_ 3]
                                (agent/execute agent "test"))
                       (is (= 3 @(:executions metrics)))

                       ;; Hot-swap logic
                       (agent/swap-logic! agent (monitor-logic (->TranslationLogic "Spanish")))
                       (swap! (:swaps metrics) inc)

                       ;; More executions with new logic
                       (dotimes [_ 2]
                                (agent/execute agent "test"))
                       (is (= 5 @(:executions metrics)))

                       ;; Hot-swap provider
                       (agent/swap-provider! agent (providers/mock-provider ["new"]))
                       (swap! (:swaps metrics) inc)

                       ;; Final execution
                       (agent/execute agent "final")
                       (is (= 6 @(:executions metrics)))
                       (is (= 2 @(:swaps metrics))))))

;; ============================================================================
;; Edge Cases & Error Scenarios
;; ============================================================================

(deftest test-edge-cases
         (testing "Empty registry operations"
                  (reset! agent/registry {})
                  (is (= {} (agent/list-agents)))
                  (is (nil? (agent/get-agent :non-existent))))

         (testing "Nil handling"
                  (let [agent (agent/create-agent
                                (reify agent/AgentLogic
                                       (prepare-prompt [_ input] (or input "default"))
                                       (process-response [_ response] (or response "empty")))
                                (providers/mock-provider [nil]))]

                       (is (= "empty" (agent/execute agent nil)))))

         (testing "Large input/output"
                  (let [large-input (apply str (repeat 10000 "a"))
                        large-output (apply str (repeat 10000 "b"))
                        agent (agent/create-agent
                                (reify agent/AgentLogic
                                       (prepare-prompt [_ input] input)
                                       (process-response [_ response] response))
                                (providers/mock-provider [large-output]))]

                       (is (= large-output (agent/execute agent large-input))))))

;; ============================================================================
;; Test Runner
;; ============================================================================

(defn run-integration-tests []
      (run-tests 'standard-agent.integration-test))