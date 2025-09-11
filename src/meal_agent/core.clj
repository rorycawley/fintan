(ns meal-agent.core
  (:require [clojure.string :as str]
            [clojure.set :as set]))

;; ============================================================================
;; Recipe Database (Pure Data)
;; ============================================================================

(def recipes
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
;; Pure Functional Core - Data Transformations
;; ============================================================================

(defn normalize-ingredient
  "Normalize a single ingredient to canonical form"
  [ingredient]
  (some-> ingredient
      str/lower-case
      str/trim))

(defn normalize-ingredients
  "Transform ingredient list to canonical set"
  [ingredients]
  (->> ingredients
       (map normalize-ingredient)
       (remove str/blank?)
       set))

(defn llm-response->meal-request
  "Transform LLM response to structured meal request"
  [llm-response]
  {:pre  [(map? llm-response)
          (coll?  (:ingredients llm-response))
          (number? (:hunger llm-response))
          (number? (:time llm-response))]
   :post [(set? (:ingredients %))
          (<= 1 (:hunger %) 5)
          (pos-int? (:time %))]}
  (let [h (-> (:hunger llm-response) int (max 1) (min 5))
        t (-> (:time   llm-response) int (max 1))]
    {:ingredients (normalize-ingredients (:ingredients llm-response))
     :hunger      h
     :time        t}))


;; ============================================================================
;; Composable Recipe Matching Predicates
;; ============================================================================

(defn has-ingredients?
  "Returns predicate that checks if available ingredients contain required"
  [required]
  (fn [available]
    (set/subset? required available)))

(defn within-hunger-range?
  "Returns predicate that checks if hunger is within range"
  [[min-hunger max-hunger]]
  (fn [hunger]
    (<= min-hunger hunger max-hunger)))

(defn hunger-satisfied?
  "True if hunger is inside the recipe's range.
   Allow +1 (hungrier) tolerance only for recipes whose max-hunger ≥ 3."
  [hunger [min-hunger max-hunger]]
  (or (<= min-hunger hunger max-hunger)
      (and (>= max-hunger 3)
           (= hunger (inc max-hunger)))))



(defn within-time-limit?
  "Returns predicate that checks if time is sufficient (with 2-min tolerance)"
  [recipe-time]
  (fn [available-time]
    (>= available-time (- recipe-time 2))))

(defn recipe-matches?
  "Check if recipe matches all criteria in request"
  [request recipe]
  (and ((has-ingredients? (:ingredients recipe)) (:ingredients request))
                 (hunger-satisfied? (:hunger request) (:hunger-range recipe))
                 ((within-time-limit? (:time recipe)) (:time request))))

;; ============================================================================
;; Recipe Selection Logic
;; ============================================================================

(defn find-matching-recipes
  "Find all recipes matching the criteria, sorted by cooking time"
  [request recipes-db]
  {:pre [(set? (:ingredients request))
         (number? (:hunger request))
         (number? (:time request))]}
  (->> recipes-db
       (filter #(recipe-matches? request %))
       (sort-by :time)))

(defn select-best-recipe
  "Select the best recipe from matches, preferring quicker recipes"
  [matching-recipes]
  (if (empty? matching-recipes)
    ::no-matches
    {:primary (first matching-recipes)
     :alternatives (rest matching-recipes)}))

(defn suggest-meal
  "Pure function to suggest meal based on request and recipe database"
  [request recipes-db]
  (-> request
      (find-matching-recipes recipes-db)
      select-best-recipe))

;; ============================================================================
;; Formatting Functions (Pure)
;; ============================================================================

(defn format-recipe
  "Format a single recipe for display"
  [recipe]
  (str (:name recipe)
       " (" (:time recipe) " min)"))

(defn format-suggestion
  "Format suggestion for human-readable output"
  [suggestion]
  (if (= suggestion ::no-matches)
    {:status :no-match
     :message "Sorry, no suitable meal found with your available ingredients and constraints."}
    {:status :success
     :message (str "Suggested: " (format-recipe (:primary suggestion)))
     :alternatives (when (seq (:alternatives suggestion))
                     (str "Also consider: "
                          (str/join ", " (map format-recipe (:alternatives suggestion)))))}))

;; ============================================================================
;; LLM Client Protocol
;; ============================================================================

(defprotocol LLMClient
  "Protocol for LLM service interaction"
  (parse-natural-language [this user-input]
    "Parse natural language into structured data"))

;; ============================================================================
;; Process Boundary - Operational Code
;; ============================================================================

(defn create-meal-request-schema
  "Create the JSON schema for LLM structured output"
  []
  {:type "object"
   :properties {:ingredients {:type "array"
                              :items {:type "string"}}
                :hunger {:type "integer"
                         :minimum 1
                         :maximum 5}
                :time {:type "integer"
                       :minimum 1}}
   :required ["ingredients" "hunger" "time"]
   :additionalProperties false})

(defn create-system-prompt
  "Create the system prompt for LLM parsing"
  []
  (str "Extract 3 fields and reply ONLY with JSON matching the schema:\n"
       "- ingredients (list[str]): Canonical ingredient names only, pluralized where natural.\n"
       "  • Remove numbers (e.g., \"2 eggs\" → \"eggs\")\n"
       "  • Remove adjectives (e.g., \"leftover rice\" → \"rice\")\n"
       "  • Use simple grocery-style labels that match common recipes.\n"
       "- hunger (1–5)\n"
       "- time (minutes, minimum 1)\n\n"
       "If user is vague (e.g., \"in a rush\"), infer a reasonable time (around 10 minutes).\n"
       "If not in a rush, use a default time of 30 minutes."))

;; ============================================================================
;; Main Process Functions
;; ============================================================================

(defn meal-suggestion-process
  "Complete process: pull -> transform -> push
   Returns the formatted suggestion or error"
  [llm-client user-input recipes-db]
  (try
    (let [llm-response (parse-natural-language llm-client user-input)
          request      (llm-response->meal-request llm-response)
          suggestion   (suggest-meal request recipes-db)]
      (format-suggestion suggestion))
    (catch Exception e
      {:status :error
       :message (str "Failed to process request: " (.getMessage e))})))


;; ============================================================================
;; State Management (at process boundary only)
;; ============================================================================

(defn create-history-atom
  "Create atom for request history at process boundary"
  []
  (atom []))

(defn record-request!
  "Record a request and its result in history"
  [history-atom user-input result]
  (swap! history-atom conj
         {:timestamp (java.time.Instant/now)
          :input user-input
          :result result})
  result)

(defn process-meal-request
  "High-level function to process a meal request with history tracking"
  [llm-client user-input history-atom recipes-db]
  (record-request! history-atom
                   user-input
                   (meal-suggestion-process llm-client user-input recipes-db)))


;; ============================================================================
;; Configuration Factory
;; ============================================================================

(defn create-config
  "Create configuration map with explicit dependencies"
  [api-key]
  {:api-key api-key
   :model "gpt-4o"
   :api-url "https://api.openai.com/v1/chat/completions"
   :schema (create-meal-request-schema)
   :system-prompt (create-system-prompt)})

;; ============================================================================
;; Main Entry Point (delegates to LLM namespace)
;; ============================================================================

(defn -main
  "Main entry point - delegates to LLM implementation"
  [& _args]
  (println "Starting Meal Agent...")
  (println "Note: Full interactive mode requires the meal-agent.llm namespace")
  (println "Run with: clj -m meal-agent.llm")

  ;; For testing purposes, show that core functions work
  (println "\nExample using test data:")
  (let [test-request {:ingredients #{"bread" "cheese"}
                      :hunger 3
                      :time 10}
        suggestion (suggest-meal test-request recipes)
        formatted (format-suggestion suggestion)]
    (println "Request:" (pr-str test-request))
    (println "Result:" (:message formatted))
    (when-let [alts (:alternatives formatted)]
      (println "Alternatives:" alts))))