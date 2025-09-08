(ns user
  "Development namespace for decision-agent project.

   This namespace is automatically loaded when starting a REPL in development mode.
   It provides convenient access to the main functionality and development utilities.

   Usage:
     (demo!)           ; Run the demonstration
     (recommend ...)   ; Quick meal recommendation
     (analyze ...)     ; Detailed decision analysis
     (test!)           ; Run all tests
     (reload!)         ; Reload changed namespaces"
  (:require [clojure.tools.namespace.repl :as repl]
            [clojure.repl :refer [doc source apropos dir pst]]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.test :as test]
            [decision-agent.core :as agent]
            [decision-agent.core-test :as agent-test]))

;;; =============================================================================
;;; REPL Utilities and Configuration
;;; =============================================================================

(println "🤖 Decision Agent Development Environment Loaded")
(println "📖 Type (help) for available commands")

(defn help
  "Show available development commands and examples."
  []
  (println "\n=== Decision Agent Development Commands ===")
  (println "\n🚀 Quick Start:")
  (println "  (demo!)                              ; Run full demonstration")
  (println "  (recommend [:eggs :rice] 4 20)       ; Quick recommendation")
  (println "  (analyze [:eggs :oil :pasta] 3 15)   ; Detailed analysis")
  (println "\n🧪 Testing:")
  (println "  (test!)                              ; Run all tests")
  (println "  (test-ns 'decision-agent.core-test)  ; Run specific namespace tests")
  (println "  (coverage!)                          ; Show test coverage summary")
  (println "\n🔧 Development:")
  (println "  (reload!)                            ; Reload changed namespaces")
  (println "  (reset!)                             ; Full reload and test")
  (println "  (recipes)                            ; Show all available recipes")
  (println "  (ingredients)                        ; Show all known ingredients")
  (println "\n🔍 Exploration:")
  (println "  (scenarios)                          ; Run example scenarios")
  (println "  (benchmark)                          ; Performance benchmarking")
  (println "  (validate-recipes!)                  ; Validate recipe database")
  (println "\n📚 Standard REPL:")
  (println "  (doc function-name)                  ; Show documentation")
  (println "  (source function-name)               ; Show source code")
  (println "  (dir namespace)                      ; List namespace contents")
  (println "\n💡 Examples:")
  (println "  (recommend [\"eggs\" \"butter\"] 5 10)")
  (println "  (analyze [:eggs :rice :oil :vegetables] 2 30)")
  (println "  (compare-scenarios)")
  (println))

;;; =============================================================================
;;; Namespace Management
;;; =============================================================================

(defn reload!
  "Reload changed namespaces and show what was reloaded."
  []
  (println "🔄 Reloading changed namespaces...")
  (repl/refresh))

(defn reset!
  "Full development reset: reload all namespaces and run tests."
  []
  (println "🔄 Full reset: reloading all namespaces...")
  (repl/refresh)
  (println "🧪 Running tests after reload...")
  (test!))

;;; =============================================================================
;;; Agent Interface Shortcuts
;;; =============================================================================

(defn demo!
  "Run the decision agent demonstration."
  []
  (agent/demonstrate-agent!))

(defn recommend
  "Quick meal recommendation with flexible input handling.

   Examples:
     (recommend [:eggs :rice] 4 20)
     (recommend [\"eggs\" \"rice\" \"oil\"] 3 15)
     (recommend \"eggs,rice,oil\" 5 10)"
  ([ingredients hunger time]
   (let [parsed-ingredients (cond
                              (string? ingredients) (mapv keyword (str/split ingredients #","))
                              (sequential? ingredients) ingredients
                              :else [ingredients])]
     (println (agent/recommend-meal parsed-ingredients hunger time))))
  ([ingredients]
   (recommend ingredients 3 20)))

(defn analyze
  "Detailed decision analysis with pretty-printed output.

   Examples:
     (analyze [:eggs :rice :oil] 4 20)
     (analyze [\"pasta\" \"eggs\"] 2 25)"
  [ingredients hunger time]
  (println "\n🔍 Decision Analysis:")
  (pprint (agent/analyze-decision ingredients hunger time)))

(defn test!
  "Run all tests with summary."
  []
  (println "🧪 Running all tests...")
  (agent-test/run-all-tests))

(defn test-ns
  "Run tests for a specific namespace."
  [namespace]
  (test/run-tests namespace))

(defn coverage!
  "Show test coverage summary."
  []
  (println "\n📊 Test Coverage Summary:")
  (let [results (agent-test/run-all-tests)]
    (when (zero? (+ (:fail results) (:error results)))
      (println "✅ 100% test coverage achieved!"))))

;;; =============================================================================
;;; Data Exploration
;;; =============================================================================

(defn recipes
  "Show all available recipes in the knowledge base."
  []
  (println "\n🍳 Available Recipes:")
  (doseq [recipe agent/recipes]
    (println (format "  %-25s | %2d min | %-6s | %s"
                     (:name recipe)
                     (:cook-time recipe)
                     (name (:difficulty recipe))
                     (str/join ", " (map name (:ingredients recipe))))))
  (println (format "\nTotal: %d recipes" (count agent/recipes))))

(defn ingredients
  "Show all known ingredients across all recipes."
  []
  (let [all-ingredients (->> agent/recipes
                             (mapcat :ingredients)
                             set
                             sort)]
    (println "\n🥚 Known Ingredients:")
    (println (str/join ", " (map name all-ingredients)))
    (println (format "\nTotal: %d unique ingredients" (count all-ingredients)))))

(defn validate-recipes!
  "Validate the recipe database for consistency."
  []
  (println "🔍 Validating recipe database...")
  (try
    (doseq [recipe agent/recipes]
      (when-not (map? recipe)
        (throw (ex-info "Invalid recipe format" {:recipe recipe})))
      (when-not (every? #{:name :ingredients :cook-time :difficulty} (keys recipe))
        (throw (ex-info "Recipe missing required fields" {:recipe recipe}))))
    (println "✅ All recipes are valid!")
    (catch Exception e
      (println "❌ Validation failed:")
      (println (.getMessage e)))))

;;; =============================================================================
;;; Scenario Testing
;;; =============================================================================

(defn scenarios
  "Run various test scenarios to explore agent behavior."
  []
  (println "\n🎬 Running Example Scenarios:\n")

  (let [test-cases [
                    {:desc "🏃‍♂️ Quick & Easy (Very Hungry, No Time)"
                     :ingredients [:eggs :butter]
                     :hunger 5
                     :time 8}

                    {:desc "👨‍🍳 Chef Mode (Not Hungry, Lots of Time)"
                     :ingredients [:eggs :rice :pasta :oil :vegetables]
                     :hunger 2
                     :time 45}

                    {:desc "🥡 Takeout Alternative (Moderate Hunger, Some Ingredients)"
                     :ingredients [:noodles :eggs :oil]
                     :hunger 4
                     :time 15}

                    {:desc "🌾 Minimalist (Low Hunger, Basic Ingredients)"
                     :ingredients [:rice]
                     :hunger 2
                     :time 20}

                    {:desc "🚨 Emergency Mode (Very Hungry, Very Limited)"
                     :ingredients [:noodles]
                     :hunger 5
                     :time 5}]]

    (doseq [{:keys [desc ingredients hunger time]} test-cases]
      (println desc)
      (println (agent/recommend-meal ingredients hunger time))
      (println))))

(defn compare-scenarios
  "Compare agent decisions across different conditions."
  []
  (println "\n⚖️  Scenario Comparison:\n")
  (let [ingredients [:eggs :rice :oil :pasta]
        conditions [[1 60] [3 20] [5 8]]]  ; [hunger time] pairs

    (doseq [[hunger time] conditions]
      (println (format "Hunger: %d/5, Time: %d min" hunger time))
      (let [analysis (agent/analyze-decision ingredients hunger time)]
        (if-let [selected (:selected analysis)]
          (println (format "→ %s (Score: %.1f)"
                           (:name selected)
                           (:priority-score selected)))
          (println "→ No feasible options"))
        (println)))))

;;; =============================================================================
;;; Performance and Benchmarking
;;; =============================================================================

(defn benchmark
  "Simple performance benchmark of core functions."
  []
  (println "\n⏱️  Performance Benchmark:")
  (let [ingredients [:eggs :rice :oil :pasta :vegetables]
        hunger 4
        time 20
        iterations 1000]

    (println (format "Running %d iterations..." iterations))

    ;; Benchmark perceive
    (let [start (System/nanoTime)
          _ (dotimes [_ iterations]
              (agent/perceive ingredients hunger time))
          elapsed (/ (- (System/nanoTime) start) 1000000.0)]
      (println (format "perceive:        %.2f ms total (%.4f ms/call)" elapsed (/ elapsed iterations))))

    ;; Benchmark decide
    (let [state (agent/perceive ingredients hunger time)
          start (System/nanoTime)
          _ (dotimes [_ iterations]
              (agent/decide state))
          elapsed (/ (- (System/nanoTime) start) 1000000.0)]
      (println (format "decide:          %.2f ms total (%.4f ms/call)" elapsed (/ elapsed iterations))))

    ;; Benchmark full pipeline
    (let [start (System/nanoTime)
          _ (dotimes [_ iterations]
              (agent/recommend-meal ingredients hunger time))
          elapsed (/ (- (System/nanoTime) start) 1000000.0)]
      (println (format "full pipeline:   %.2f ms total (%.4f ms/call)" elapsed (/ elapsed iterations))))))

;;; =============================================================================
;;; Interactive Development Helpers
;;; =============================================================================

(defn random-scenario
  "Generate a random scenario for testing."
  []
  (let [all-ingredients [:eggs :butter :rice :oil :pasta :vegetables :noodles]
        ingredient-count (+ 1 (rand-int 4))
        ingredients (take ingredient-count (shuffle all-ingredients))
        hunger (+ 1 (rand-int 5))
        time (+ 5 (rand-int 55))]

    (println (format "\n🎲 Random Scenario: %s, Hunger: %d/5, Time: %d min"
                     (str/join ", " (map name ingredients)) hunger time))
    (println (agent/recommend-meal ingredients hunger time))))

(defn interactive-agent
  "Start an interactive session with the agent."
  []
  (println "\n🤖 Interactive Decision Agent")
  (println "Enter ingredients (comma-separated), hunger level (1-5), and time (minutes)")
  (println "Type 'quit' to exit")

  (loop []
    (print "\n> ")
    (flush)
    (let [input (read-line)]
      (when-not (= input "quit")
        (try
          (let [[ingredients-str hunger-str time-str] (str/split input #"\s+")
                ingredients (mapv keyword (str/split ingredients-str #","))
                hunger (Integer/parseInt hunger-str)
                time (Integer/parseInt time-str)]
            (println (agent/recommend-meal ingredients hunger time)))
          (catch Exception e
            (println "Invalid input. Format: ingredients,list hunger-level time-minutes")
            (println "Example: eggs,rice,oil 4 20")))
        (recur)))))

;;; =============================================================================
;;; REPL State
;;; =============================================================================

;; Development state for experimentation
(def ^:dynamic *debug* false)

(defn debug-on! [] (alter-var-root #'*debug* (constantly true)))
(defn debug-off! [] (alter-var-root #'*debug* (constantly false)))

;; Quick access to commonly used data
(def sample-ingredients [:eggs :rice :oil :pasta :vegetables])
(def sample-state (agent/agent-state #{:eggs :rice :oil} 3 20))

;;; =============================================================================
;;; Startup Message
;;; =============================================================================

(defn startup-message []
  (println "\n" (str/join (repeat 60 "=")))
  (println "🤖 Decision Agent Development Environment")
  (println (str/join (repeat 60 "=")))
  (println "Quick commands:")
  (println "  (help)     - Show all available commands")
  (println "  (demo!)    - Run demonstration")
  (println "  (test!)    - Run test suite")
  (println "  (recipes)  - Show recipe database")
  (println (str/join (repeat 60 "=")))
  (println))

;; Show startup message when this namespace loads
(startup-message)

;; Set up convenient aliases in the user namespace
(def r recommend)       ; (r [:eggs] 3 20)
(def a analyze)         ; (a [:eggs :rice] 4 15)
(def d demo!)           ; (d)
(def t test!)           ; (t)

(println "💡 Tip: Use short aliases: (r [:eggs] 3 20), (a [:eggs :rice] 4 15), (d), (t)")
(println "🔗 Type (help) for full documentation\n")