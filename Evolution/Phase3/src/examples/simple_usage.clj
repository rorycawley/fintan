(ns examples.simple-usage
  "Simple example showing how to use standard-agent"
  (:require [standard-agent.core :as agent]
            [standard-agent.providers :as providers]
            [standard-agent.integrations :as integrate]))

;; ============================================================================
;; Define Your Business Logic
;; ============================================================================

(defrecord MealLogic []
           agent/AgentLogic
           (prepare-prompt [_ input]
                           (str "Suggest a meal for someone who says: " input))

           (process-response [_ response]
                             {:suggestion response}))

(defrecord TranslateLogic [target-lang]
           agent/AgentLogic
           (prepare-prompt [_ input]
                           (str "Translate to " target-lang ": " input))

           (process-response [_ response]
                             {:translation response}))

;; ============================================================================
;; Basic Usage Examples
;; ============================================================================

(defn example-basic []
      (println "\n=== Basic Usage ===")

      ;; Create agent with mock provider
      (let [logic (->MealLogic)
            provider (providers/mock-provider ["Grilled cheese sandwich"])
            agent (agent/create-agent logic provider)]

           (println "Result:" (agent/execute agent "I'm hungry"))
           ;; => {:suggestion "Grilled cheese sandwich"}
           ))

(defn example-hot-swap []
      (println "\n=== Hot Swapping ===")

      (let [meal-logic (->MealLogic)
            translate-logic (->TranslateLogic "Spanish")
            provider (providers/mock-provider ["Pizza" "Hola mundo"])
            agent (agent/create-agent meal-logic provider)]

           ;; Start with meal logic
           (println "Meal:" (agent/execute agent "hungry"))

           ;; Hot-swap to translation logic
           (agent/swap-logic! agent translate-logic)
           (println "Translation:" (agent/execute agent "hello world"))))

(defn example-retry []
      (println "\n=== Retry Middleware ===")

      (let [attempts (atom 0)
            flaky-provider (reify agent/LLMProvider
                                  (invoke [_ prompt options]
                                          (swap! attempts inc)
                                          (if (< @attempts 3)
                                            (throw (Exception. "Temporary failure"))
                                            "Success after retries")))
            logic (->MealLogic)
            agent (agent/create-agent logic flaky-provider)
            safe-execute (agent/with-retry agent/execute 5 100)]

           (println "Result:" (safe-execute agent "test"))
           (println "Attempts needed:" @attempts)))

(defn example-cache []
      (println "\n=== Caching ===")

      (let [call-count (atom 0)
            counting-provider (reify agent/LLMProvider
                                     (invoke [_ prompt options]
                                             (swap! call-count inc)
                                             (str "Response " @call-count)))
            logic (->MealLogic)
            agent (agent/create-agent logic counting-provider)
            cached-execute (agent/with-cache agent/execute (:cache agent))]

           (println "First call:" (cached-execute agent "test"))
           (println "Second call (cached):" (cached-execute agent "test"))
           (println "Different input:" (cached-execute agent "other"))
           (println "Total API calls:" @call-count))) ; Should be 2, not 3

(defn example-registry []
      (println "\n=== Registry Pattern ===")

      (reset! agent/registry {})

      ;; Register multiple agents
      (agent/register! :meals
                       (agent/create-agent (->MealLogic)
                                           (providers/mock-provider ["Pasta"])))

      (agent/register! :translate
                       (agent/create-agent (->TranslateLogic "French")
                                           (providers/mock-provider ["Bonjour"])))

      ;; Use agents by ID
      (println "Meals:" (agent/execute (agent/get-agent :meals) "dinner"))
      (println "Translation:" (agent/execute (agent/get-agent :translate) "hello"))
      (println "All agents:" (keys (agent/list-agents))))

(defn example-functional-style []
      (println "\n=== Functional Style ===")

      ;; Agent as a simple function
      (let [process (integrate/create-agent-fn
                      (->MealLogic)
                      (providers/mock-provider ["Salad"]))]

           (println "Function result:" (process "healthy food"))))

(defn example-with-open []
      (println "\n=== Resource Management ===")

      (with-open [agent (integrate/agent-resource
                          (->MealLogic)
                          (providers/mock-provider ["Soup"]))]
                 (println "With-open result:" (agent/execute @agent "cold day")))

      (println "Resources cleaned up"))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn -main [& args]
      (println "Standard Agent - Simple Examples")
      (println "=================================")

      (example-basic)
      (example-hot-swap)
      (example-retry)
      (example-cache)
      (example-registry)
      (example-functional-style)
      (example-with-open)

      (println "\n✅ All examples completed!")))