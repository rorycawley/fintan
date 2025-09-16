(ns examples.meal-agent
  "Example implementation of an agent using the standard-agent library.

   This demonstrates how users would implement their own business logic
   using the standard-agent infrastructure."
  (:require [standard-agent.core :as agent]
            [standard-agent.providers :as providers]
            [standard-agent.registry :as registry]
            [standard-agent.integrations :as integrate]
            [clojure.string :as str]
            [clojure.set :as set]))

;; ============================================================================
;; Domain Model - User's Business Logic
;; ============================================================================

(def recipes
  "The recipe database - domain-specific data."
  [{:name "Grilled Cheese"
    :ingredients #{"bread" "cheese"}
    :hunger-range [2 3]
    :time 5}

   {:name "Eggs on Toast"
    :ingredients #{"eggs" "bread"}
    :hunger-range [3 5]
    :time 8}

   {:name "Cheese Omelette"
    :ingredients #{"eggs" "cheese"}
    :hunger-range [3 4]
    :time 10}

   {:name "Egg Fried Rice"
    :ingredients #{"eggs" "rice"}
    :hunger-range [4 5]
    :time 15}])

;; ============================================================================
;; Implementation of AgentLogic Protocol
;; ============================================================================

(defrecord MealAgentLogic [recipes]
           agent/AgentLogic

           (prepare-prompt [_ context input]
                           {:prompt (str "Extract meal request details from this input: '" input "'\n"
                                         "Respond with JSON containing:\n"
                                         "- ingredients: array of ingredient names\n"
                                         "- hunger: number 1-5\n"
                                         "- time: available minutes\n"
                                         "Be concise and accurate.")
                            :options {:temperature 0.3
                                      :max-tokens 200
                                      :response-format :json}})

           (process-response [this context llm-response]
                             (let [parsed (try
                                            (read-string llm-response)
                                            (catch Exception _
                                              ;; Fallback parsing
                                              {:ingredients ["bread" "cheese"]
                                               :hunger 3
                                               :time 30}))

                                   ;; Find matching recipes
                                   matches (filter #(recipe-matches? parsed %) recipes)

                                   ;; Format result
                                   result (if (empty? matches)
                                            {:status :no-match
                                             :message "No suitable meal found"}
                                            {:status :success
                                             :recipe (first matches)
                                             :alternatives (rest matches)
                                             :message (str "Suggested: " (:name (first matches)))})]
                                  result))

           (validate-input [_ input]
                           (cond
                             (str/blank? input)
                             {:valid? false :error "Input cannot be empty"}

                             (< (count input) 5)
                             {:valid? false :error "Input too short"}

                             :else
                             {:valid? true}))

           (get-capabilities [_]
                             {:name "Meal Planning Agent"
                              :description "Suggests meals based on available ingredients"
                              :version "1.0.0"
                              :domain :meal-planning
                              :input-format :natural-language
                              :output-format :structured-suggestion}))

;; Helper function for recipe matching
(defn- recipe-matches? [request recipe]
       (and (set/subset? (:ingredients recipe)
                         (set (:ingredients request)))
            (<= (first (:hunger-range recipe))
                (:hunger request)
                (second (:hunger-range recipe)))
            (>= (:time request) (:time recipe))))

;; ============================================================================
;; Advanced Logic Implementations
;; ============================================================================

(defrecord NutritionAwareLogic [recipes nutrition-db]
           agent/AgentLogic

           (prepare-prompt [_ context input]
                           {:prompt (str input "\nAlso identify any dietary restrictions mentioned.")
                            :options {:temperature 0.3}})

           (process-response [this context llm-response]
                             ;; Enhanced logic that considers nutrition
                             (let [base-result ((MealAgentLogic. recipes)
                                                agent/process-response context llm-response)]
                                  (if (= :success (:status base-result))
                                    (assoc base-result
                                           :nutrition (get nutrition-db (:name (:recipe base-result))))
                                    base-result)))

           (validate-input [_ input] {:valid? true})

           (get-capabilities [_]
                             {:name "Nutrition-Aware Meal Agent"
                              :version "2.0.0"
                              :features #{:nutrition :dietary-restrictions}}))

(defrecord ChefAgentLogic []
           agent/AgentLogic

           (prepare-prompt [_ context input]
                           {:prompt (str "As a professional chef, analyze this request and suggest "
                                         "a gourmet meal: " input "\nProvide cooking instructions.")
                            :options {:temperature 0.7
                                      :max-tokens 500}})

           (process-response [_ context llm-response]
                             {:status :success
                              :type :gourmet
                              :instructions llm-response})

           (validate-input [_ input] {:valid? true})

           (get-capabilities [_]
                             {:name "Chef Agent"
                              :expertise :gourmet
                              :output :detailed-instructions}))

;; ============================================================================
;; Creating and Using Agents
;; ============================================================================

(defn create-meal-agent
      "Create a meal planning agent with specified provider."
      [provider-config]
      (agent/create-agent
        (->MealAgentLogic recipes)
        {:provider (providers/create-provider provider-config)
         :middleware [(agent/with-retry 3 1000)
                      (agent/with-timeout 10000)]}))

(defn create-agent-suite
      "Create a suite of meal-related agents."
      []
      (registry/create-multi-agent-system
        {:basic-meal {:logic (->MealAgentLogic recipes)
                      :provider {:provider :gemini
                                 :api-key (System/getenv "GEMINI_API_KEY")}
                      :capabilities {:type :basic
                                     :cost :low}}

         :nutrition {:logic (->NutritionAwareLogic recipes {})
                     :provider {:provider :openai
                                :api-key (System/getenv "OPENAI_API_KEY")}
                     :capabilities {:type :nutrition
                                    :cost :medium}}

         :chef {:logic (->ChefAgentLogic)
                :provider {:provider :claude
                           :api-key (System/getenv "CLAUDE_API_KEY")}
                :capabilities {:type :gourmet
                               :cost :high}}}))

;; ============================================================================
;; Integration Examples
;; ============================================================================

(defn functional-example
      "Example using functional integration."
      []
      (let [meal-fn (integrate/create-agent-fn
                      (->MealAgentLogic recipes)
                      {:provider :openai
                       :api-key (System/getenv "OPENAI_API_KEY")})]

           ;; Simple function call
           (meal-fn "I have eggs and bread, very hungry")))

(defn with-open-example
      "Example using with-open pattern."
      []
      (with-open [agent (integrate/agent-resource
                          (->MealAgentLogic recipes)
                          {:provider :claude
                           :api-key (System/getenv "CLAUDE_API_KEY")})]

                 ;; Use the agent
                 (agent/execute @agent "I have cheese and bread, quick meal needed")))

(defn registry-example
      "Example using registry for multiple agents."
      []
      (let [suite (create-agent-suite)]

           ;; Route to appropriate agent based on user preference
           (defn process-meal-request [request-type input]
                 (let [agent-id (case request-type
                                      :quick :basic-meal
                                      :healthy :nutrition
                                      :fancy :chef
                                      :basic-meal)]
                      (when-let [agent (registry/get-agent suite agent-id)]
                                (agent/execute agent input))))))

;; ============================================================================
;; Runtime Flexibility Examples
;; ============================================================================

(defn hot-swap-example
      "Demonstrate hot-swapping capabilities."
      []
      (let [agent (create-meal-agent {:provider :openai
                                      :api-key "key1"})]

           ;; Use with OpenAI
           (println "With OpenAI:" (agent/execute agent "hungry"))

           ;; Swap to Claude
           (agent/swap-provider! agent
                                 (providers/claude-provider {:api-key "key2"}))
           (println "With Claude:" (agent/execute agent "hungry"))

           ;; Swap logic to nutrition-aware
           (agent/swap-logic! agent (->NutritionAwareLogic recipes {}))
           (println "With nutrition:" (agent/execute agent "healthy meal"))))

(defn failover-example
      "Demonstrate failover between providers."
      []
      (let [primary (providers/openai-provider {:api-key "key1"})
            backup (providers/gemini-provider {:api-key "key2"})
            provider (providers/with-fallback primary backup)

            agent (agent/create-agent
                    (->MealAgentLogic recipes)
                    {:provider provider})]

           ;; Will automatically failover to Gemini if OpenAI fails
           (agent/execute agent "I need food")))

;; ============================================================================
;; Testing the Implementation
;; ============================================================================

(defn test-meal-agent
      "Test the meal agent with mock responses."
      []
      (let [mock-responses ["{\"ingredients\": [\"eggs\", \"bread\"], \"hunger\": 4, \"time\": 10}"
                            "{\"ingredients\": [\"cheese\"], \"hunger\": 2, \"time\": 5}"]
            agent (agent/create-agent
                    (->MealAgentLogic recipes)
                    {:provider (agent/mock-provider mock-responses)})]

           ;; Test with mock responses
           (println "Test 1:" (agent/execute agent "test input 1"))
           (println "Test 2:" (agent/execute agent "test input 2"))))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn -main
      "Run the meal agent example."
      [& args]
      (println "=== Meal Agent Example ===\n")

      (let [provider-type (or (first args) "mock")]
           (case provider-type
                 "functional"
                 (do (println "Using functional style:")
                     (println (functional-example)))

                 "registry"
                 (do (println "Using registry pattern:")
                     (registry-example)
                     (println (process-meal-request :quick "I have eggs")))

                 "hot-swap"
                 (do (println "Demonstrating hot-swap:")
                     (hot-swap-example))

                 "test"
                 (do (println "Running tests:")
                     (test-meal-agent))

                 ;; Default: mock
                 (do (println "Using mock provider:")
                     (let [agent (agent/create-agent
                                   (->MealAgentLogic recipes)
                                   {:provider (agent/mock-provider
                                                ["{\"ingredients\": [\"bread\", \"cheese\"], \"hunger\": 3, \"time\": 10}"])})]
                          (println (agent/execute agent "I'm hungry")))))))