(ns decision-agent.core
  "A simple rule-based decision-making agent for meal recommendations with observability."
  (:gen-class)
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.java.io :as io]           ; <- ADD THIS
            [aero.core :as aero]               ; <- ADD THIS
            [decision-agent.observability :as obs]))

(defn- detect-profile []
  ;; Prefer JVM system prop (-Dprofile=prod), then env var (PROFILE=prod), else :dev
  (keyword (or (System/getProperty "profile")
               (System/getenv "PROFILE")
               "dev")))

(defn load-config
  "Loads configuration from resources/config.edn with optional profile.
   If profile is omitted, uses -Dprofile / $PROFILE, falling back to :dev."
  ([] (load-config (detect-profile)))
  ([profile]
   (try
     (aero/read-config (io/resource "config.edn") {:profile profile})
     (catch Exception e
       (println "Warning: Could not load config.edn, using defaults. Cause:" (.getMessage e))
       {:observability {:logging {:enabled false}}}))))

(defn configure-observability!
  "Configures observability based on profile"
  [profile]
  (let [config (load-config profile)]
    (obs/configure! (:observability config))))

(defn init-config!
  "Initialize configuration - call this at startup"
  []
  (let [profile (keyword (or (System/getenv "PROFILE") "dev"))]
    (configure-observability! profile)))

;;; =============================================================================
;;; Domain Model - Data Structures
;;; =============================================================================

(defn recipe
  "Creates a recipe map with validation using option map pattern."
  [{:keys [name ingredients cook-time difficulty] :as options}]
  {:pre [(let [allowed #{:name :ingredients :cook-time :difficulty}
               ks      (set (keys options))]
           (and (set/subset? ks allowed)
                (string? name)
                (set? ingredients)
                (every? keyword? ingredients)
                (pos-int? cook-time)
                (#{:easy :medium :hard} difficulty)))]}
  {:name name
   :ingredients ingredients
   :cook-time cook-time
   :difficulty difficulty})

(def recipes
  "Static knowledge base of available recipes."
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

(defn agent-state
  "Creates an agent state map representing current environmental conditions."
  [available-ingredients hunger-level available-time]
  {:pre [(set? available-ingredients)
         (and (integer? hunger-level) (<= 1 hunger-level 5))
         (pos-int? available-time)]}
  {:available-ingredients available-ingredients
   :hunger-level hunger-level
   :available-time available-time})

;;; =============================================================================
;;; Core Agent Logic - Enhanced with Observability
;;; =============================================================================

(defn ingredients-match?
  "Predicate to check if all required ingredients are available."
  [available-ingredients required-ingredients]
  (set/subset? required-ingredients available-ingredients))

(defn time-sufficient?
  "Predicate to check if available time is sufficient for recipe preparation."
  [available-time required-time]
  (>= available-time required-time))

(defn feasible?
  "Comprehensive feasibility check for a recipe given current conditions."
  [recipe state]
  (and (ingredients-match? (:available-ingredients state)
                           (:ingredients recipe))
       (time-sufficient? (:available-time state)
                         (:cook-time recipe))))

(defn feasible-recipes
  "Filters the recipe knowledge base to only feasible options."
  [recipe-collection state correlation-id]
  (let [feasible (filter #(feasible? % state) recipe-collection)]
    (obs/log-decision-step "feasibility-filter"
                           {:total-recipes (count recipe-collection)
                            :feasible-count (count feasible)
                            :feasible-recipes (map :name feasible)}
                           correlation-id)
    feasible))

;;; =============================================================================
;;; Decision Heuristics
;;; =============================================================================

(defn urgency-score
  "Calculates urgency based on hunger level."
  [hunger-level]
  (* hunger-level 2))

(defn time-pressure
  "Calculates time pressure based on available time."
  [available-time]
  (double (/ 60 (max available-time 1))))

(defn time-efficiency
  "Calculates time efficiency for a recipe."
  [recipe]
  (double (/ 60 (:cook-time recipe))))

(defn difficulty-penalty
  "Calculates difficulty penalty for a recipe."
  [recipe]
  (case (:difficulty recipe)
    :easy   0
    :medium 5
    :hard   10
    10))

(defn priority-score
  "Calculates priority score for recipe selection."
  [recipe state]
  (let [urgency (urgency-score (:hunger-level state))
        pressure (time-pressure (:available-time state))
        efficiency (time-efficiency recipe)
        penalty (difficulty-penalty recipe)]
    (- (+ urgency pressure efficiency) penalty)))

(defn best-recipe
  "Selects the best recipe from feasible options using priority scoring."
  [candidates state correlation-id]
  (when (seq candidates)
    (let [scored-candidates (->> candidates
                                 (map #(assoc % :priority-score (priority-score % state)))
                                 (sort-by :priority-score >))
          best (first scored-candidates)]

      (obs/log-decision-step "priority-scoring"
                             {:candidates-scored (count scored-candidates)
                              :top-3-scores (take 3 (map #(select-keys % [:name :priority-score])
                                                         scored-candidates))
                              :selected (:name best)}
                             correlation-id)
      best)))

;;; =============================================================================
;;; Agent Interface
;;; =============================================================================

(defn perceive
  "Agent perception function enhanced with input validation logging."
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
                               set)
        state (agent-state clean-ingredients hunger-level available-time)]
    state))

(defn decide-with-analysis
  "Enhanced decision function that returns detailed analysis."
  [state correlation-id]
  (let [candidates (feasible-recipes recipes state correlation-id)
        decision (if (seq candidates)
                   (best-recipe candidates state correlation-id)
                   :no-feasible-recipes)]

    (obs/log-decision-step "decision-finalization"
                           {:final-decision (if (= decision :no-feasible-recipes)
                                              "no-options"
                                              (:name decision))
                            :total-feasible (count candidates)}
                           correlation-id)

    {:decision decision
     :analysis {:total-recipes (count recipes)
                :feasible-count (count candidates)
                :state-summary {:ingredients (count (:available-ingredients state))
                                :hunger (:hunger-level state)
                                :time (:available-time state)}}
     :feasible-recipes candidates}))

(defn decide
  "Core decision-making function - maintains compatibility with original API."
  [state]
  (let [correlation-id (str (java.util.UUID/randomUUID))
        result (decide-with-analysis state correlation-id)]
    (:decision result)))

(defn format-recommendation
  "Formats a recipe recommendation for display."
  [recipe]
  (format "Suggested Meal: %s (Takes %d minutes, Difficulty: %s)"
          (:name recipe)
          (:cook-time recipe)
          (name (:difficulty recipe))))

(defn format-no-options
  "Formats a message when no recipes are feasible."
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
  "Agent action function that formats the decision result."
  [decision state]
  (if (= decision :no-feasible-recipes)
    (format-no-options state)
    (format-recommendation decision)))

;;; =============================================================================
;;; Instrumented Agent Processes
;;; =============================================================================

(defn recommend-meal-with-observability
  "Enhanced meal recommendation with full observability integration."
  [ingredients-list hunger-level available-time]
  (let [state (perceive ingredients-list hunger-level available-time)]
    (obs/instrument-decision-process
      (fn [state]
        (let [correlation-id (str (java.util.UUID/randomUUID))]
          (obs/log-decision-start state correlation-id)
          (let [result (decide-with-analysis state correlation-id)
                recommendation (act (:decision result) state)]
            (assoc result :recommendation recommendation))))
      state)))

(defn recommend-meal
  "Pure agent function - maintains original API for backward compatibility."
  [ingredients-list hunger-level available-time]
  (let [state (perceive ingredients-list hunger-level available-time)
        decision (decide state)]
    (act decision state)))

(defn meal-agent!
  "Enhanced agent entry point with observability."
  [ingredients-list hunger-level available-time]
  (let [result (recommend-meal-with-observability ingredients-list hunger-level available-time)]
    (println (:recommendation result))
    (:recommendation result)))

;;; =============================================================================
;;; Enhanced Demonstration and Analysis
;;; =============================================================================

(defn demonstrate-agent!
  "Original demonstration function - maintains compatibility."
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

(defn demonstrate-agent-with-metrics!
  "Enhanced demonstration that shows observability features."
  []
  (println "=== Enhanced Decision Agent Demo with Observability ===\n")

  ;; Configure observability for demo
  ;(obs/configure! {:logging {:enabled true :structured false}
  ;                 :metrics {:enabled true}
  ;                 :audit {:enabled true}})
  (configure-observability! :dev)


  (println "Scenario 1: Plenty of time, moderate hunger")
  (meal-agent! [:eggs :rice :oil] 3 25)

  (println "\nScenario 2: Very hungry, limited time")
  (meal-agent! [:eggs :butter] 5 8)

  (println "\nScenario 3: Low hunger, minimal ingredients")
  (meal-agent! [:rice] 2 15)

  (println "\nScenario 4: No feasible recipes")
  (meal-agent! [:butter] 4 5)

  (println "\nScenario 5: Many ingredients, time pressure")
  (meal-agent! [:eggs :rice :pasta :oil :vegetables] 4 10)

  ;; Show metrics summary
  (Thread/sleep 100) ; Allow metrics to settle
  (println "\n=== Performance Metrics Summary ===")
  (let [metrics (obs/get-metrics-summary)]
    (println (format "Total decisions: %d" (get-in metrics [:decisions :total])))
    (println (format "Success rate: %.2f%%" (* 100.0 (double (get-in metrics [:decisions :success-rate])))))
    (println (format "Mean duration: %.1fms" (double (get-in metrics [:performance :mean-duration-ms])))))

  ;; Show decision patterns
  (println "\n=== Decision Pattern Analysis ===")
  (let [patterns (obs/analyze-decision-patterns)]
    (println (format "Total decisions analyzed: %d" (get-in patterns [:summary :total-decisions])))
    (println (format "Ingredient count patterns: %s" (:ingredient-patterns patterns)))))

(defn analyze-decision
  "Debugging helper that shows the complete decision-making process."
  [ingredients-list hunger-level available-time]
  (let [state (perceive ingredients-list hunger-level available-time)
        result (recommend-meal-with-observability ingredients-list hunger-level available-time)]
    {:state state
     :decision-result (:decision result)
     :analysis (:analysis result)
     :observability (:observability result)
     :feasible-recipes (:feasible-recipes result)
     :recommendation (:recommendation result)}))

;;; =============================================================================
;;; Main and Utility Functions
;;; =============================================================================

(defn -main
  "Main entry point for standalone JAR execution."
  [& args]
  (init-config!) ; ADD THIS LINE
  (if (empty? args)
    (demonstrate-agent-with-metrics!)
    (let [[ingredients-str hunger-str time-str] args
          ingredients (str/split ingredients-str #",")
          hunger (parse-long hunger-str)
          time (parse-long time-str)]
      (if (and hunger time)
        (println (recommend-meal ingredients hunger time))
        (println "Usage: java -jar decision-agent.jar [ingredients,list hunger-level available-time]")))))

;; Example usage:
;; (demonstrate-agent-with-metrics!)
;; (recommend-meal-with-observability [:eggs :rice :oil] 4 20)
;; (analyze-decision [:eggs :pasta :oil] 3 15)