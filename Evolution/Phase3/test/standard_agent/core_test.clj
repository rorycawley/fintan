(ns standard-agent.core-test
  (:require [clojure.test :refer :all]
            [standard-agent.core :as agent])
  (:import [java.io Closeable]))

;; ============================================================================
;; Test Fixtures and Helpers
;; ============================================================================

(defrecord TestLogic [call-count]
           agent/AgentLogic
           (prepare-prompt [_ input]
                           (swap! call-count inc)
                           (str "prompt:" input))

           (process-response [_ response]
                             (swap! call-count inc)
                             {:result (str "processed:" response)}))

(defrecord FailingLogic []
           agent/AgentLogic
           (prepare-prompt [_ input]
                           (throw (ex-info "Logic failed" {:input input})))

           (process-response [_ response]
                             (throw (ex-info "Processing failed" {:response response}))))

(defn test-provider [response]
      (reify agent/LLMProvider
             (invoke [_ prompt options]
                     (str "response-to:" prompt))))

(defn failing-provider []
      (reify agent/LLMProvider
             (invoke [_ prompt options]
                     (throw (ex-info "Provider failed" {:prompt prompt})))))

(defn counting-provider [counter]
      (reify agent/LLMProvider
             (invoke [_ prompt options]
                     (swap! counter inc)
                     "response")))

;; ============================================================================
;; Core Agent Tests
;; ============================================================================

(deftest test-create-agent
         (testing "Agent creation"
                  (let [logic (->TestLogic (atom 0))
                        provider (test-provider "test")
                        agent (agent/create-agent logic provider)]

                       (is (= logic @(:logic agent)))
                       (is (= provider @(:provider agent)))
                       (is (= {} @(:cache agent)))
                       (is (map? agent))
                       (is (every? #(instance? clojure.lang.Atom (val %)) agent)))))

(deftest test-execute
         (testing "Basic execution flow"
                  (let [call-count (atom 0)
                        logic (->TestLogic call-count)
                        provider (test-provider "mock-response")
                        agent (agent/create-agent logic provider)
                        result (agent/execute agent "test-input")]

                       (is (= {:result "processed:response-to:prompt:test-input"} result))
                       (is (= 2 @call-count)))) ; prepare-prompt + process-response

         (testing "Execution with nil input"
                  (let [logic (->TestLogic (atom 0))
                        provider (test-provider "response")
                        agent (agent/create-agent logic provider)
                        result (agent/execute agent nil)]

                       (is (= {:result "processed:response-to:prompt:"} result))))

         (testing "Execution with empty string"
                  (let [logic (->TestLogic (atom 0))
                        provider (test-provider "response")
                        agent (agent/create-agent logic provider)
                        result (agent/execute agent "")]

                       (is (= {:result "processed:response-to:prompt:"} result)))))

(deftest test-hot-swapping
         (testing "Swap logic at runtime"
                  (let [count1 (atom 0)
                        count2 (atom 0)
                        logic1 (->TestLogic count1)
                        logic2 (->TestLogic count2)
                        provider (test-provider "response")
                        agent (agent/create-agent logic1 provider)]

                       (agent/execute agent "input1")
                       (is (= 2 @count1))
                       (is (= 0 @count2))

                       (agent/swap-logic! agent logic2)
                       (agent/execute agent "input2")
                       (is (= 2 @count1)) ; No more calls to logic1
                       (is (= 2 @count2)) ; Calls go to logic2

                       (is (= logic2 @(:logic agent)))))

         (testing "Swap provider at runtime"
                  (let [count1 (atom 0)
                        count2 (atom 0)
                        logic (->TestLogic (atom 0))
                        provider1 (counting-provider count1)
                        provider2 (counting-provider count2)
                        agent (agent/create-agent logic provider1)]

                       (agent/execute agent "input1")
                       (is (= 1 @count1))
                       (is (= 0 @count2))

                       (agent/swap-provider! agent provider2)
                       (agent/execute agent "input2")
                       (is (= 1 @count1)) ; No more calls to provider1
                       (is (= 1 @count2)) ; Calls go to provider2

                       (is (= provider2 @(:provider agent)))))

         (testing "Cache clears on provider swap"
                  (let [logic (->TestLogic (atom 0))
                        provider1 (test-provider "response1")
                        provider2 (test-provider "response2")
                        agent (agent/create-agent logic provider1)]

                       (swap! (:cache agent) assoc "key" "value")
                       (is (= {"key" "value"} @(:cache agent)))

                       (agent/swap-provider! agent provider2)
                       (is (= {} @(:cache agent))))))

;; ============================================================================
;; Error Handling Tests
;; ============================================================================

(deftest test-error-handling
         (testing "Logic prepare-prompt failure"
                  (let [logic (->FailingLogic)
                        provider (test-provider "response")
                        agent (agent/create-agent logic provider)]

                       (is (thrown-with-msg? Exception #"Logic failed"
                                             (agent/execute agent "input")))))

         (testing "Provider failure"
                  (let [logic (->TestLogic (atom 0))
                        provider (failing-provider)
                        agent (agent/create-agent logic provider)]

                       (is (thrown-with-msg? Exception #"Provider failed"
                                             (agent/execute agent "input")))))

         (testing "Logic process-response failure"
                  (let [logic (->FailingLogic)
                        provider (test-provider "response")
                        agent (agent/create-agent logic provider)]

                       ;; Need a logic that passes prepare-prompt but fails process-response
                       (let [partial-logic (reify agent/AgentLogic
                                                  (prepare-prompt [_ input] "prompt")
                                                  (process-response [_ response]
                                                                    (throw (ex-info "Process failed" {}))))]
                            (agent/swap-logic! agent partial-logic)
                            (is (thrown-with-msg? Exception #"Process failed"
                                                  (agent/execute agent "input")))))))

;; ============================================================================
;; Middleware Tests
;; ============================================================================

(deftest test-retry-middleware
         (testing "Successful on first attempt"
                  (let [attempts (atom 0)
                        f (fn [& args]
                              (swap! attempts inc)
                              "success")
                        wrapped (agent/with-retry f 3 10)]

                       (is (= "success" (wrapped "arg")))
                       (is (= 1 @attempts))))

         (testing "Retry on failure then success"
                  (let [attempts (atom 0)
                        f (fn [& args]
                              (swap! attempts inc)
                              (if (< @attempts 3)
                                (throw (Exception. "Fail"))
                                "success"))
                        wrapped (agent/with-retry f 5 10)]

                       (is (= "success" (wrapped "arg")))
                       (is (= 3 @attempts))))

         (testing "Max retries exceeded"
                  (let [attempts (atom 0)
                        f (fn [& args]
                              (swap! attempts inc)
                              (throw (Exception. "Always fails")))
                        wrapped (agent/with-retry f 3 10)]

                       (is (thrown-with-msg? Exception #"Max retries exceeded"
                                             (wrapped "arg")))
                       (is (= 3 @attempts))))

         (testing "Retry with delay"
                  (let [attempts (atom 0)
                        start-time (System/currentTimeMillis)
                        f (fn [& args]
                              (swap! attempts inc)
                              (if (< @attempts 2)
                                (throw (Exception. "Fail"))
                                "success"))
                        wrapped (agent/with-retry f 3 50)]

                       (wrapped "arg")
                       (let [elapsed (- (System/currentTimeMillis) start-time)]
                            (is (>= elapsed 50)))))) ; At least one retry with 50ms delay

(deftest test-cache-middleware
         (testing "Cache hit"
                  (let [cache (atom {})
                        call-count (atom 0)
                        f (fn [agent input]
                              (swap! call-count inc)
                              {:result input})
                        wrapped (agent/with-cache f cache)]

                       (is (= {:result "test"} (wrapped nil "test")))
                       (is (= 1 @call-count))
                       (is (= {"test" {:result "test"}} @cache))

                       ;; Second call should hit cache
                       (is (= {:result "test"} (wrapped nil "test")))
                       (is (= 1 @call-count)) ; No additional calls

                       ;; Different input should miss cache
                       (is (= {:result "other"} (wrapped nil "other")))
                       (is (= 2 @call-count))))

         (testing "Cache with agent execution"
                  (let [logic (->TestLogic (atom 0))
                        provider (test-provider "response")
                        agent (agent/create-agent logic provider)
                        cached-execute (agent/with-cache agent/execute (:cache agent))]

                       (let [result1 (cached-execute agent "input1")
                             result2 (cached-execute agent "input1") ; Same input
                             result3 (cached-execute agent "input2")] ; Different input

                            (is (= result1 result2))
                            (is (not= result1 result3))
                            (is (= 2 (count @(:cache agent))))))))

;; ============================================================================
;; Resource Management Tests
;; ============================================================================

(deftest test-closeable
         (testing "Basic closeable"
                  (let [closed? (atom false)
                        resource {:data "test"}
                        closeable (agent/closeable resource (fn [_] (reset! closed? true)))]

                       (is (= resource @closeable))
                       (is (false? @closed?))

                       (.close closeable)
                       (is (true? @closed?))))

         (testing "Closeable with with-open"
                  (let [closed? (atom false)
                        resource {:data "test"}]

                       (with-open [c (agent/closeable resource (fn [_] (reset! closed? true)))]
                                  (is (= resource @c))
                                  (is (false? @closed?)))

                       (is (true? @closed?))))

         (testing "Closeable with exception in with-open"
                  (let [closed? (atom false)]

                       (is (thrown? Exception
                                    (with-open [c (agent/closeable {} (fn [_] (reset! closed? true)))]
                                               (throw (Exception. "Test error")))))

                       (is (true? @closed?))))) ; Should still close on exception)

         ;; ============================================================================
         ;; Registry Tests
         ;; ============================================================================

         (deftest test-registry
                  (testing "Register and retrieve agent"
                           (reset! agent/registry {})
                           (let [logic (->TestLogic (atom 0))
                                 provider (test-provider "response")
                                 agent1 (agent/create-agent logic provider)]

                                (agent/register! :test-agent agent1)
                                (is (= agent1 (agent/get-agent :test-agent)))
                                (is (nil? (agent/get-agent :non-existent)))))

                  (testing "List all agents"
                           (reset! agent/registry {})
                           (let [agent1 (agent/create-agent (->TestLogic (atom 0)) (test-provider "r1"))
                                 agent2 (agent/create-agent (->TestLogic (atom 0)) (test-provider "r2"))]

                                (agent/register! :agent1 agent1)
                                (agent/register! :agent2 agent2)

                                (let [all-agents (agent/list-agents)]
                                     (is (= 2 (count all-agents)))
                                     (is (= agent1 (:agent1 all-agents)))
                                     (is (= agent2 (:agent2 all-agents))))))

                  (testing "Overwrite existing agent"
                           (reset! agent/registry {})
                           (let [agent1 (agent/create-agent (->TestLogic (atom 0)) (test-provider "r1"))
                                 agent2 (agent/create-agent (->TestLogic (atom 0)) (test-provider "r2"))]

                                (agent/register! :test agent1)
                                (is (= agent1 (agent/get-agent :test)))

                                (agent/register! :test agent2)
                                (is (= agent2 (agent/get-agent :test))))))

         ;; ============================================================================
         ;; Thread Safety Tests
         ;; ============================================================================

         (deftest test-concurrent-execution
                  (testing "Multiple threads executing same agent"
                           (let [execution-count (atom 0)
                                 logic (reify agent/AgentLogic
                                              (prepare-prompt [_ input] input)
                                              (process-response [_ response]
                                                                (swap! execution-count inc)
                                                                response))
                                 provider (test-provider "response")
                                 agent (agent/create-agent logic provider)
                                 threads (for [i (range 10)]
                                              (future (agent/execute agent (str "input-" i))))]

                                (doseq [t threads] @t)
                                (is (= 10 @execution-count))))

                  (testing "Concurrent hot-swapping"
                           (let [agent (agent/create-agent (->TestLogic (atom 0)) (test-provider "r"))
                                 swap-count (atom 0)
                                 threads (concat
                                           (for [i (range 5)]
                                                (future
                                                  (agent/swap-logic! agent (->TestLogic (atom 0)))
                                                  (swap! swap-count inc)))
                                           (for [i (range 5)]
                                                (future
                                                  (agent/swap-provider! agent (test-provider (str "r" i)))
                                                  (swap! swap-count inc))))]

                                (doseq [t threads] @t)
                                (is (= 10 @swap-count))
                                ;; Agent should still be functional
                                (is (map? (agent/execute agent "test"))))))

         ;; ============================================================================
         ;; End-to-End Tests
         ;; ============================================================================

         (deftest test-complete-workflow
                  (testing "Full agent lifecycle"
                           (reset! agent/registry {})

                           ;; Create agent with retry and cache
                           (let [logic (->TestLogic (atom 0))
                                 provider (test-provider "response")
                                 agent (agent/create-agent logic provider)
                                 safe-execute (agent/with-retry agent/execute 3 100)
                                 cached-execute (agent/with-cache safe-execute (:cache agent))]

                                ;; Register agent
                                (agent/register! :main agent)

                                ;; Execute with caching
                                (let [result1 (cached-execute agent "test")
                                      result2 (cached-execute agent "test")]
                                     (is (= result1 result2)))

                                ;; Hot-swap provider
                                (agent/swap-provider! agent (test-provider "new-response"))
                                (is (= {} @(:cache agent))) ; Cache cleared

                                ;; Execute after swap
                                (let [result3 (agent/execute agent "test")]
                                     (is (= {:result "processed:response-to:prompt:test"} result3)))

                                ;; Verify in registry
                                (is (= agent (agent/get-agent :main))))))

         ;; ============================================================================
         ;; Performance Tests
         ;; ============================================================================

         (deftest test-performance
                  (testing "Agent creation performance"
                           (let [start (System/currentTimeMillis)]
                                (dotimes [_ 1000]
                                         (agent/create-agent (->TestLogic (atom 0)) (test-provider "r")))
                                (let [elapsed (- (System/currentTimeMillis) start)]
                                     (is (< elapsed 100))))) ; Should create 1000 agents in < 100ms

                  (testing "Execution performance"
                           (let [logic (->TestLogic (atom 0))
                                 provider (test-provider "response")
                                 agent (agent/create-agent logic provider)
                                 start (System/currentTimeMillis)]
                                (dotimes [_ 1000]
                                         (agent/execute agent "input"))
                                (let [elapsed (- (System/currentTimeMillis) start)]
                                     (is (< elapsed 100))))) ; Should execute 1000 times in < 100ms

                  (testing "Hot-swap performance"
                           (let [agent (agent/create-agent (->TestLogic (atom 0)) (test-provider "r"))
                                 start (System/currentTimeMillis)]
                                (dotimes [i 1000]
                                         (if (even? i)
                                           (agent/swap-logic! agent (->TestLogic (atom 0)))
                                           (agent/swap-provider! agent (test-provider "r"))))
                                (let [elapsed (- (System/currentTimeMillis) start)]
                                     (is (< elapsed 100)))))) ; Should swap 1000 times in < 100ms