(ns decision-agent.core
  "A simple rule-based decision-making agent for meal recommendations.

   This agent demonstrates fundamental concepts of agent-based systems:
   - Perception: Processing environmental inputs (ingredients, hunger, time)
   - Decision-making: Applying rules to match user needs with available options
   - Action: Providing structured recommendations

   Design follows 'Elements of Clojure' principles:
   - Narrow, consistent names that reveal intent
   - Immutable data structures with clear transformations
   - Separation of concerns between data, rules, and presentation
   - Functions that are either pure transformations or have clear side effects"
  (:gen-class)
  (:require [clojure.set :as set]
            [clojure.string :as str]))


;;; =============================================================================
;;; Domain Model - Data Structures
;;; =============================================================================

;; Recipe representation using option map pattern for multiple parameters
(defn recipe
  "Creates a recipe map with validation using option map pattern.

   Options:
     :name - String name of the recipe (required)
     :ingredients - Set of required ingredient keywords (required)
     :cook-time - Integer minutes required to prepare (required)
     :difficulty - Keyword representing complexity (:easy, :medium, :hard, required)

   Returns:
     Map representing a complete recipe with all required fields"
  [{:keys [name ingredients cook-time difficulty] :as options}]
  {:pre [(let [allowed #{:name :ingredients :cook-time :difficulty}
               ks      (set (keys options))]
           (and (set/subset? ks allowed)            ; no unknown keys
                (string? name)
                (set? ingredients)
                (every? keyword? ingredients)
                (pos-int? cook-time)
                (#{:easy :medium :hard} difficulty)))]}
  {:name name
   :ingredients ingredients
   :cook-time cook-time
   :difficulty difficulty})

;; Knowledge base of available recipes
(def recipes
  "Static knowledge base of available recipes.
   Each recipe specifies required ingredients, cooking time, and difficulty.
   This could be extended to load from external data sources."
  [(recipe {:name "Scrambled Eggs"
            :ingredients #{:eggs :butter}
            :cook-time 5
            :difficulty :easy})
   (recipe {:name "Egg Fried Rice"
            :ingredients #{:eggs :rice :oil}
            :cook-time 15
            :difficulty :medium})
   (recipe {:name "Simple Pasta"
            :ingredients #{:pasta :oil}
            :cook-time 12
            :difficulty :easy})
   (recipe {:name "Pasta with Eggs"
            :ingredients #{:pasta :eggs :oil}
            :cook-time 18
            :difficulty :medium})
   (recipe {:name "Rice Bowl"
            :ingredients #{:rice}
            :cook-time 10
            :difficulty :easy})
   (recipe {:name "Vegetable Stir Fry"
            :ingredients #{:vegetables :oil}
            :cook-time 20
            :difficulty :medium})
   (recipe {:name "Egg and Vegetable Scramble"
            :ingredients #{:eggs :vegetables :oil}
            :cook-time 8
            :difficulty :easy})
   (recipe {:name "Instant Noodles"
            :ingredients #{:noodles}
            :cook-time 3
            :difficulty :easy})
   (recipe {:name "Rice and Egg Bowl"
            :ingredients #{:rice :eggs :oil}
            :cook-time 12
            :difficulty :easy})])

;; Agent state representation
(defn agent-state
  "Creates an agent state map representing current environmental conditions.

   Args:
     available-ingredients - Set of ingredient keywords currently available
     hunger-level - Integer from 1 (not hungry) to 5 (very hungry)
     available-time - Integer minutes available for cooking

   Returns:
     Map representing the agent's current perception of the environment"
  [available-ingredients hunger-level available-time]
  {:pre [(set? available-ingredients)
         (and (integer? hunger-level) (<= 1 hunger-level 5))
         (pos-int? available-time)]}
  {:available-ingredients available-ingredients
   :hunger-level hunger-level
   :available-time available-time})

;;; =============================================================================
;;; Core Agent Logic - Rule-Based Decision Making
;;; =============================================================================

(defn ingredients-match?
  "Predicate to check if all required ingredients are available.

   Args:
     available-ingredients - Set of available ingredient keywords
     required-ingredients - Set of required ingredient keywords

   Returns:
     Boolean indicating if all required ingredients are available"
  [available-ingredients required-ingredients]
  (set/subset? required-ingredients available-ingredients))

(defn time-sufficient?
  "Predicate to check if available time is sufficient for recipe preparation.

   Args:
     available-time - Integer minutes available
     required-time - Integer minutes required for recipe

   Returns:
     Boolean indicating if time is sufficient"
  [available-time required-time]
  (>= available-time required-time))

(defn feasible?
  "Comprehensive feasibility check for a recipe given current conditions.

   Args:
     recipe - Recipe map to evaluate
     state - Current agent state map

   Returns:
     Boolean indicating if recipe can be prepared given current conditions"
  [recipe state]
  (and (ingredients-match? (:available-ingredients state)
                           (:ingredients recipe))
       (time-sufficient? (:available-time state)
                         (:cook-time recipe))))

(defn feasible-recipes
  "Filters the recipe knowledge base to only feasible options.

   Args:
     recipe-collection - Collection of recipe maps to filter
     state - Current agent state map

   Returns:
     Lazy sequence of feasible recipe maps"
  [recipe-collection state]
  (filter #(feasible? % state) recipe-collection))

;;; =============================================================================
;;; Decision Heuristics - Preference Logic
;;; =============================================================================

(defn urgency-score
  "Calculates urgency based on hunger level.

   Args:
     hunger-level - Integer from 1-5

   Returns:
     Numeric urgency score (higher = more urgent)"
  [hunger-level]
  (* hunger-level 2))

(defn time-pressure
  "Calculates time pressure based on available time.

   Args:
     available-time - Integer minutes available

   Returns:
     Numeric pressure score (higher = more time pressure)"
  [available-time]
  (double (/ 60 (max available-time 1))))

(defn time-efficiency
  "Calculates time efficiency for a recipe.

   Args:
     recipe - Recipe map

   Returns:
     Numeric efficiency score (higher = more efficient)"
  [recipe]
  (double (/ 60 (:cook-time recipe))))

(defn difficulty-penalty
  "Calculates difficulty penalty for a recipe.

   Args:
     recipe - Recipe map

   Returns:
     Numeric penalty (higher = more penalty for difficulty)"
  [recipe]
  (case (:difficulty recipe)
    :easy   0
    :medium 5
    :hard   10
    10)) ; defensive default

(defn priority-score
  "Calculates priority score for recipe selection.

   This function combines multiple scoring factors to rank recipes.

   Args:
     recipe - Recipe map to score
     state - Current agent state map

   Returns:
     Numeric priority score (higher = more preferred)"
  [recipe state]
  (let [urgency (urgency-score (:hunger-level state))
        pressure (time-pressure (:available-time state))
        efficiency (time-efficiency recipe)
        penalty (difficulty-penalty recipe)]
    (- (+ urgency pressure efficiency) penalty)))

(defn best-recipe
  "Selects the best recipe from feasible options using priority scoring.

   Args:
     feasible-recipes - Collection of feasible recipe maps
     state - Current agent state map

   Returns:
     Single recipe map with highest priority score, or nil if none available"
  [candidates state]
  (when (seq candidates)
    (let [best (apply max-key #(priority-score % state) candidates)]
      ;; keep the score on the map since analyze-decision expects it
      (assoc best :priority-score (priority-score best state)))))

;;; =============================================================================
;;; Agent Interface - Public API
;;; =============================================================================

(defn perceive
  "Agent perception function that processes environmental inputs.

   This function validates and structures inputs into the agent's
   internal representation. Follows the 'Elements of Clojure' principle
   of validating assumptions at process boundaries.

   Args:
     ingredients-list - Collection of ingredient keywords or strings
     hunger-level - Integer from 1-5 indicating hunger intensity
     available-time - Integer minutes available for cooking

   Returns:
     Agent state map representing current environmental conditions

   Note:
     Gracefully handles mixed input types by filtering invalid values"
  [ingredients-list hunger-level available-time]
  (let [clean-ingredients (->> ingredients-list
                               (keep identity)
                               (keep (fn [x]
                                       (cond
                                         (keyword? x) (-> x name str/lower-case keyword)
                                         (string?  x) (let [s (-> x str/trim str/lower-case
                                                                  (str/replace #"^:" ""))]
                                                        (when-not (str/blank? s)
                                                          (keyword s)))
                                         :else nil)))
                               set)]
    (agent-state clean-ingredients hunger-level available-time)))

(defn decide
  "Core decision-making function that selects best recipe given current state.

   This function implements the complete decision pipeline:
   1. Filter recipes by feasibility constraints
   2. Apply preference heuristics to rank options
   3. Select optimal choice

   Args:
     state - Agent state map from perceive function

   Returns:
     Recipe map of selected option, or :no-feasible-recipes if none available"
  [state]
  (let [candidates (feasible-recipes recipes state)]
    (if (seq candidates)
      (best-recipe candidates state)
      :no-feasible-recipes)))

(defn format-recommendation
  "Formats a recipe recommendation for display.

   Args:
     recipe - Recipe map to format

   Returns:
     String describing the recommendation"
  [recipe]
  (format "Suggested Meal: %s (Takes %d minutes, Difficulty: %s)"
          (:name recipe)
          (:cook-time recipe)
          (name (:difficulty recipe))))

(defn format-no-options
  "Formats a message when no recipes are feasible.

   Args:
     state - Agent state map for context

   Returns:
     String explaining why no options are available"
  [state]
  (let [ingredients (->> (:available-ingredients state)
                         (map name)
                         sort)
        hunger (:hunger-level state)
        time   (:available-time state)]
    (format (str "No feasible recipes found.\n"
                 "Available: %s\nHunger: %d/5, Time: %d minutes\n"
                 "Try adding more ingredients or allowing more time.")
            (str/join ", " ingredients)
            hunger
            time)))

(defn act
  "Agent action function that formats the decision result.

   Args:
     decision - Recipe map from decide function, or :no-feasible-recipes
     state - Agent state map for context

   Returns:
     String message describing the recommendation or explaining constraints"
  [decision state]
  (if (= decision :no-feasible-recipes)
    (format-no-options state)
    (format-recommendation decision)))

;;; =============================================================================
;;; Pure Agent Process - No Side Effects
;;; =============================================================================

(defn recommend-meal
  "Pure agent function that processes inputs and returns recommendation.

   This function represents the complete decision process without side effects:
   - Pull: Accepts environmental inputs via perceive
   - Transform: Applies decision logic via decide
   - Push: Formats output via act

   Args:
     ingredients-list - Collection of available ingredients (keywords or strings)
     hunger-level - Integer from 1-5
     available-time - Integer minutes available

   Returns:
     String recommendation message"
  [ingredients-list hunger-level available-time]
  (let [state (perceive ingredients-list hunger-level available-time)
        decision (decide state)]
    (act decision state)))

;;; =============================================================================
;;; Side-Effect Functions - Separated from Pure Logic
;;; =============================================================================

(defn meal-agent!
  "Agent entry point with side effects for interactive use.

   This function wraps the pure recommend-meal function with I/O side effects.
   The exclamation mark indicates side effects (printing).

   Args:
     ingredients-list - Collection of available ingredients
     hunger-level - Integer from 1-5
     available-time - Integer minutes available

   Returns:
     String recommendation message

   Side Effects:
     Prints recommendation to stdout"
  [ingredients-list hunger-level available-time]
  (let [recommendation (recommend-meal ingredients-list hunger-level available-time)]
    (println recommendation)
    recommendation))

;;; =============================================================================
;;; Usage Examples and Testing
;;; =============================================================================

(defn demonstrate-agent!
  "Demonstrates the agent with various scenarios.

   Side Effects:
     Prints demonstration output to stdout"
  []
  (println "=== Simple Decision Agent Demo ===\n")

  (println "Scenario 1: Plenty of time, moderate hunger")
  (meal-agent! [:eggs :rice :oil] 3 25)

  (println "\nScenario 2: Very hungry, limited time")
  (meal-agent! [:eggs :butter] 5 8)

  (println "\nScenario 3: Low hunger, minimal ingredients")
  (meal-agent! [:rice] 2 15)

  (println "\nScenario 4: No feasible recipes")
  (meal-agent! [:butter] 4 5)

  (println "\nScenario 5: Many ingredients, time pressure")
  (meal-agent! [:eggs :rice :pasta :oil :vegetables] 4 10))

;; Helper function for REPL experimentation
(defn analyze-decision
  "Debugging helper that shows the complete decision-making process.

   Args:
     ingredients-list - Collection of available ingredients
     hunger-level - Integer from 1-5
     available-time - Integer minutes available

   Returns:
     Map with detailed decision analysis including all feasible options"
  [ingredients-list hunger-level available-time]
  (let [state (perceive ingredients-list hunger-level available-time)
        candidates (feasible-recipes recipes state)
        scored (->> candidates
                    (map #(assoc % :priority-score (priority-score % state)))
                    (sort-by :priority-score >))]
    {:state state
     :feasible-count (count candidates)
     :all-candidates scored
     :selected (first scored)}))

(defn -main
  "Main entry point for standalone JAR execution."
  [& args]
  (if (empty? args)
    (demonstrate-agent!)
    (let [[ingredients-str hunger-str time-str] args
          ingredients (str/split ingredients-str #",")
          hunger (parse-long hunger-str)
          time (parse-long time-str)]
      (if (and hunger time)
        (println (recommend-meal ingredients hunger time))
        (println "Usage: java -jar decision-agent.jar [ingredients,list hunger-level available-time]")))))

;; Example usage:
;; (demonstrate-agent!)
;; (recommend-meal [:eggs :rice :oil] 4 20)
;; (analyze-decision [:eggs :pasta :oil] 3 15)