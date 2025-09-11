(ns meal-agent.core
  "Pure functional core of the meal agent application.

   This namespace contains:
   - The domain model (recipes, meal requests)
   - Pure business logic for meal selection
   - Port definitions (protocols) for external services
   - No side effects or I/O operations

   Following the Ports and Adapters pattern, this core defines
   what it needs from the outside world through protocols (ports)
   without knowing how those needs are fulfilled (adapters)."
  (:require [clojure.string :as str]
            [clojure.set :as set]))

;; ============================================================================
;; Domain Model - Recipe Database
;; ============================================================================

(def recipes
  "The in-memory recipe database.
   Each recipe contains:
   - :name - Display name of the dish
   - :ingredients - Set of required ingredients
   - :hunger-range - [min max] hunger level this satisfies
   - :time - Minutes needed to prepare"
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
;; Ports - External Service Interfaces
;; ============================================================================

(defprotocol MealRequestParser
  "Port for parsing natural language into structured meal requests.

   This is a PORT in the hexagonal architecture - it defines what
   the core needs from the outside world without knowing how it's
   implemented. Adapters will provide concrete implementations."

  (parse-meal-request [this user-input]
    "Parse natural language input into a structured meal request.
     Returns a map with :ingredients (set), :hunger (1-5), and :time (minutes).
     May throw exceptions on parse failure."))

;; ============================================================================
;; Pure Functions - Data Transformations
;; ============================================================================

(defn normalize-ingredient
  "Normalize a single ingredient to canonical form.
   Converts to lowercase and trims whitespace."
  [ingredient]
  (some-> ingredient
          str/lower-case
          str/trim))

(defn normalize-ingredients
  "Transform ingredient list to canonical set.
   Removes empty strings and normalizes each ingredient."
  [ingredients]
  (->> ingredients
       (map normalize-ingredient)
       (remove str/blank?)
       set))

(defn validate-meal-request
  "Validate and transform external input into internal meal request format.
   Ensures all required fields are present and valid."
  [raw-request]
  {:pre [(contains? raw-request :ingredients)
         (contains? raw-request :hunger)
         (contains? raw-request :time)]
   :post [(set? (:ingredients %))
          (<= 1 (:hunger %) 5)
          (pos? (:time %))]}
  {:ingredients (normalize-ingredients (:ingredients raw-request))
   :hunger (:hunger raw-request)
   :time (:time raw-request)})

;; ============================================================================
;; Recipe Matching Logic (Pure Functions)
;; ============================================================================

(defn has-ingredients?
  "Returns predicate that checks if available ingredients contain required.
   This is a higher-order function for composability."
  [required]
  (fn [available]
    (set/subset? required available)))

(defn within-hunger-range?
  "Returns predicate that checks if hunger is within range."
  [[min-hunger max-hunger]]
  (fn [hunger]
    (<= min-hunger hunger max-hunger)))

(defn hunger-satisfied?
  "Check if hunger level matches recipe's satisfaction range.
   Allows +1 tolerance for hungrier users on substantial meals."
  [hunger [min-hunger max-hunger]]
  (or (<= min-hunger hunger max-hunger)
      (and (>= max-hunger 3)
           (= hunger (inc max-hunger)))))

(defn within-time-limit?
  "Returns predicate that checks if available time is sufficient.
   Allows 2-minute tolerance for quick recipes."
  [recipe-time]
  (fn [available-time]
    (>= available-time (- recipe-time 2))))

(defn recipe-matches?
  "Pure predicate: check if recipe matches all criteria in request."
  [request recipe]
  (and ((has-ingredients? (:ingredients recipe)) (:ingredients request))
       (hunger-satisfied? (:hunger request) (:hunger-range recipe))
       ((within-time-limit? (:time recipe)) (:time request))))

;; ============================================================================
;; Recipe Selection (Pure Business Logic)
;; ============================================================================

(defn find-matching-recipes
  "Find all recipes matching the criteria, sorted by cooking time.
   This is pure business logic - no side effects."
  [request recipes-db]
  {:pre [(set? (:ingredients request))
         (number? (:hunger request))
         (number? (:time request))]}
  (->> recipes-db
       (filter #(recipe-matches? request %))
       (sort-by :time)))

(defn select-best-recipe
  "Select the best recipe from matches.
   Returns a map with :primary and :alternatives, or ::no-matches."
  [matching-recipes]
  (if (empty? matching-recipes)
    ::no-matches
    {:primary (first matching-recipes)
     :alternatives (rest matching-recipes)}))

(defn suggest-meal
  "Core business logic: suggest meal based on request and recipe database.
   This is a pure function - same inputs always produce same outputs."
  [request recipes-db]
  (-> request
      (find-matching-recipes recipes-db)
      select-best-recipe))

;; ============================================================================
;; Formatting Functions (Pure Presentation Logic)
;; ============================================================================

(defn format-recipe
  "Format a single recipe for display."
  [recipe]
  (str (:name recipe)
       " (" (:time recipe) " min)"))

(defn format-suggestion
  "Format suggestion for human-readable output.
   Transforms internal representation to presentation format."
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
;; Application Service (Orchestration Layer)
;; ============================================================================

(defn process-meal-request
  "Application service that orchestrates the meal suggestion process.

   This function sits at the boundary between the pure core and the
   outside world. It uses a parser (port) to convert natural language
   to structured data, then applies pure business logic.

   Parameters:
   - parser: Implementation of MealRequestParser protocol
   - user-input: Natural language string from user
   - recipes-db: Recipe database to search

   Returns formatted suggestion or error message."
  [parser user-input recipes-db]
  (try
    ;; Use the port to parse natural language
    (let [raw-request (parse-meal-request parser user-input)
          ;; Validate and transform to internal format
          request (validate-meal-request raw-request)
          ;; Apply pure business logic
          suggestion (suggest-meal request recipes-db)]
      ;; Format for presentation
      (format-suggestion suggestion))
    (catch Exception e
      {:status :error
       :message (str "Failed to process request: " (.getMessage e))})))

;; ============================================================================
;; Schema Definitions (for external adapters)
;; ============================================================================

(defn meal-request-schema
  "JSON schema for structured meal request output.
   Used by LLM adapters to ensure consistent parsing."
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

(defn system-prompt
  "System prompt for LLM-based parsers.
   Defines how natural language should be converted to structured data."
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