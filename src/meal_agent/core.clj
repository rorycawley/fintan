(ns meal-agent.core
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def config
  {:openai-api-key (System/getenv "OPENAI_API_KEY")
   :model "gpt-4o" ; Use gpt-5 when available
   :api-url "https://api.openai.com/v1/chat/completions"})

;; ============================================================================
;; JSON Schema for Structured Output
;; ============================================================================

(def meal-schema
  {:type "object"
   :properties {:ingredients {:type "array"
                              :items {:type "string"}}
                :hunger {:type "integer"
                         :minimum 1
                         :maximum 5}
                :time {:type "integer"
                       :minimum 0}}
   :required ["ingredients" "hunger" "time"]
   :additionalProperties false})

;; ============================================================================
;; Recipe Database
;; ============================================================================

(def recipes
  [{:name "Grilled Cheese"
    :ingredients #{"bread" "cheese"}
    :hunger-range [2 3]
    :time 5}

   {:name "Cheese Omelette"
    :ingredients #{"eggs" "cheese"}
    :hunger-range [3 4]
    :time 10}

   {:name "Cheese Omelette Sandwich"
    :ingredients #{"bread" "cheese" "eggs"}
    :hunger-range [4 5]
    :time 10}

   {:name "Egg Fried Rice"
    :ingredients #{"eggs" "rice"}
    :hunger-range [4 5]
    :time 15}

   {:name "Simple Fried Rice"
    :ingredients #{"rice"}
    :hunger-range [3 4]
    :time 12}])

;; ============================================================================
;; LLM Integration
;; ============================================================================

(defn parse-user-input
  "Use LLM to parse natural language input into structured data"
  [user-input]
  (let [system-prompt (str "Extract 3 fields and reply ONLY with JSON matching the schema:\n"
                           "- ingredients (list[str]): Canonical ingredient names only, pluralized where natural.\n"
                           "  • Remove numbers (e.g., \"2 eggs\" → \"eggs\")\n"
                           "  • Remove adjectives (e.g., \"leftover rice\" → \"rice\")\n"
                           "  • Use simple grocery-style labels that match common recipes.\n"
                           "- hunger (1–5)\n"
                           "- time (minutes)\n\n"
                           "If user is vague (e.g., \"in a rush\"), infer a reasonable time (around 10 minutes).\n"
                           "If not in a rush, use a default time of 30 minutes.")

        request-body {:model (:model config)
                      :messages [{:role "system" :content system-prompt}
                                 {:role "user" :content user-input}]
                      :response_format {:type "json_schema"
                                        :json_schema {:name "meal_inputs"
                                                      :schema meal-schema}}}

        response (http/post (:api-url config)
                            {:headers {"Authorization" (str "Bearer " (:openai-api-key config))
                                       "Content-Type" "application/json"}
                             :body (json/write-str request-body)
                             :as :json})]

    (-> response
        :body
        :choices
        first
        :message
        :content
        (json/read-str :key-fn keyword))))

;; ============================================================================
;; Meal Agent Logic
;; ============================================================================

(defn ingredients-match?
  "Check if available ingredients contain all required ingredients"
  [available required]
  (let [available-set (set (map str/lower-case available))
        required-set (set (map str/lower-case required))]
    (clojure.set/subset? required-set available-set)))

(defn hunger-matches?
  "Check if hunger level is within recipe range"
  [hunger [min-hunger max-hunger]]
  (<= min-hunger hunger max-hunger))

(defn time-sufficient?
  "Check if available time is enough for recipe (with 2-minute tolerance)"
  [available-time recipe-time]
  (>= available-time (- recipe-time 2)))

(defn find-suitable-recipes
  "Find all recipes that match the given criteria"
  [{:keys [ingredients hunger time]}]
  (->> recipes
       (filter #(and (ingredients-match? ingredients (:ingredients %))
                     (hunger-matches? hunger (:hunger-range %))
                     (time-sufficient? time (:time %))))
       (sort-by :time))) ; Prefer quicker recipes

(defn suggest-meal
  "Main agent function that suggests a meal based on parsed input"
  [parsed-input]
  (let [suitable-recipes (find-suitable-recipes parsed-input)]
    (if (empty? suitable-recipes)
      {:status :no-meal
       :message "Sorry, no suitable meal found."}
      {:status :success
       :meal (first suitable-recipes)
       :alternatives (rest suitable-recipes)})))

;; ============================================================================
;; Agent State Management (Functional Style)
;; ============================================================================

(defrecord MealAgent [state])

(defn create-agent
  "Create a new meal agent with initial state"
  []
  (->MealAgent {:last-input nil
                :last-suggestion nil
                :history []}))

(defn perceive
  "Process user input and update agent state"
  [agent user-input]
  (let [parsed (parse-user-input user-input)]
    (assoc-in agent [:state :last-input]
              {:raw user-input
               :parsed parsed})))

(defn act
  "Generate meal suggestion based on current state"
  [agent]
  (let [parsed-input (get-in agent [:state :last-input :parsed])
        suggestion (suggest-meal parsed-input)]
    (-> agent
        (assoc-in [:state :last-suggestion] suggestion)
        (update-in [:state :history] conj
                   {:input parsed-input
                    :suggestion suggestion
                    :timestamp (java.util.Date.)}))))

(defn format-suggestion
  "Format the suggestion for display"
  [{:keys [status meal alternatives]}]
  (case status
    :success (str "👩‍🍳 Suggested Meal: " (:name meal)
                  (when (seq alternatives)
                    (str "\n   Other options: "
                         (str/join ", " (map :name alternatives)))))
    :no-meal "👩‍🍳 Sorry, no suitable meal found."
    "Unknown status"))

;; ============================================================================
;; Main Interface
;; ============================================================================

(defn process-request
  "High-level function to process a natural language request"
  [user-input]
  (try
    (println "\n📝 User Input:" user-input)

    ;; Parse the input
    (let [parsed (parse-user-input user-input)]
      (println "🔍 Parsed Input:" (pr-str parsed))

      ;; Get suggestion
      (let [suggestion (suggest-meal parsed)]
        (println (format-suggestion suggestion))
        suggestion))

    (catch Exception e
      (println "❌ Error processing request:" (.getMessage e))
      {:status :error :message (.getMessage e)})))

;; ============================================================================
;; Example Usage
;; ============================================================================

(defn run-examples
  "Run example scenarios"
  []
  (println "\n=== LLM-Powered Meal Agent Examples ===\n")

  ;; Example 1: Natural language with vague time
  (process-request "I have eggs and rice. I'm very hungry but in a rush.")

  ;; Example 2: Natural language with numbers and adjectives
  (process-request "I have 2 eggs and leftover rice. I'm very hungry")

  ;; Example 3: More casual language
  (process-request "kinda hungry, got some bread and cheese lying around")

  ;; Example 4: Using the stateful agent
  (println "\n=== Stateful Agent Example ===")
  (let [agent (-> (create-agent)
                  (perceive "I'm starving and have eggs, cheese, and bread. Got 15 minutes.")
                  (act))]
    (println (format-suggestion (get-in agent [:state :last-suggestion])))
    (println "History entries:" (count (get-in agent [:state :history])))))

;; ============================================================================
;; Alternative: Pure Functions Without LLM (for testing)
;; ============================================================================

(defn parse-user-input-mock
  "Mock parser for testing without LLM"
  [user-input]
  (cond
    (str/includes? user-input "eggs and rice")
    {:ingredients ["eggs" "rice"]
     :hunger (if (str/includes? user-input "very hungry") 5 3)
     :time (if (str/includes? user-input "rush") 10 30)}

    (str/includes? user-input "bread and cheese")
    {:ingredients ["bread" "cheese"]
     :hunger (if (str/includes? user-input "kinda hungry") 3 4)
     :time 30}

    :else
    {:ingredients []
     :hunger 3
     :time 20}))

(defn process-request-offline
  "Process request without LLM for testing"
  [user-input]
  (let [parsed (parse-user-input-mock user-input)
        suggestion (suggest-meal parsed)]
    (println "\n📝 User Input:" user-input)
    (println "🔍 Parsed Input:" (pr-str parsed))
    (println (format-suggestion suggestion))
    suggestion))

;; ============================================================================
;; Entry Point
;; ============================================================================

(defn -main
  "Main entry point"
  [& args]
  (if (:openai-api-key config)
    (run-examples)
    (do
      (println "⚠️  No OpenAI API key found. Running in offline mode with mock parser.")
      (println "\n=== Offline Examples ===")
      (process-request-offline "I have eggs and rice. I'm very hungry but in a rush.")
      (process-request-offline "kinda hungry, got some bread and cheese lying around"))))