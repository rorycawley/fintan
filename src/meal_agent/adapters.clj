(ns meal-agent.adapters
  "Adapter implementations for the meal agent application.

   This namespace provides concrete implementations of the ports
   defined in the core namespace. Following the with-open pattern
   for resource management instead of Component.

   Key concepts:
   - Adapters implement the ports (protocols) from the core
   - Resources are managed using with-open and Closeable
   - Side effects are contained here, not in the core"
  (:require [clojure.data.json :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [meal-agent.core :as core])
  (:import [java.io Closeable]
           [java.util.concurrent TimeUnit]))

;; ============================================================================
;; Resource Management Utilities
;; ============================================================================

(defn closeable
  "Create a Closeable wrapper around arbitrary resources.

   This utility allows us to use with-open with any resource that
   needs cleanup, even if it doesn't implement Closeable natively.

   Parameters:
   - resource: The actual resource to wrap
   - close-fn: Function to call when closing (optional, defaults to no-op)

   The wrapper implements both Closeable (for with-open) and IDeref
   (for convenient access to the wrapped resource via @ or deref)."
  ([resource]
   (closeable resource (constantly nil)))
  ([resource close-fn]
   (reify
     Closeable
     (close [_]
       (close-fn resource))

     clojure.lang.IDeref
     (deref [_] resource))))

;; ============================================================================
;; OpenAI Adapter Implementation
;; ============================================================================

(defrecord
  ^{:doc "Adapter that implements MealRequestParser using OpenAI API.

   This is an ADAPTER in the hexagonal architecture - it provides
   a concrete implementation of the port using a specific technology
   (OpenAI's API in this case)."}
  OpenAIParser [config]
  core/MealRequestParser
  (parse-meal-request [_ user-input]
    ;; Build the API request according to OpenAI's format
    (let [request-body {:model           (:model config)
                        :messages        [{:role    "system"
                                           :content (:system-prompt config)}
                                          {:role    "user"
                                           :content user-input}]
                        :response_format {:type        "json_schema"
                                          :json_schema {:name   "meal_inputs"
                                                        :schema (:schema config)}}}

          ;; Make the HTTP call with timeout settings
          response (http/post (:api-url config)
                              {:headers            {"Authorization" (str "Bearer " (:api-key config))
                                                    "Content-Type"  "application/json"}
                               :body               (json/write-str request-body)
                               :as                 :json
                               :throw-exceptions   true
                               :socket-timeout     10000
                               :connection-timeout 5000})]

      ;; Extract and parse the JSON response
      (-> response
          :body
          :choices
          first
          :message
          :content
          (json/read-str :key-fn keyword)))))

(defn create-openai-config
  "Create configuration for OpenAI adapter.

   Centralizes all OpenAI-specific configuration including
   the model, API endpoint, and prompts."
  [api-key]
  {:api-key       api-key
   :model         "gpt-4o"
   :api-url       "https://api.openai.com/v1/chat/completions"
   :schema        (core/meal-request-schema)
   :system-prompt (core/system-prompt)})

(defn openai-parser-resource
  "Create an OpenAI parser as a Closeable resource.

   This factory function creates a parser wrapped in a Closeable
   so it can be used with with-open. While the parser itself
   doesn't hold resources that need cleanup, this pattern allows
   for consistent resource management and future extensibility."
  [api-key]
  (let [config (create-openai-config api-key)
        parser (->OpenAIParser config)]
    ;; Wrap in closeable for consistent resource management
    (closeable parser
               (fn [_]
                 (println "Closing OpenAI parser connection")))))

;; ============================================================================
;; Offline/Stub Adapter Implementation
;; ============================================================================

(defrecord StubParser [parse-fn]
  ;; Stub adapter for offline development and testing.
  ;; This adapter uses simple pattern matching instead of
  ;; calling an external API. Useful for development without
  ;; API keys and for testing.

  core/MealRequestParser
  (parse-meal-request [this user-input]
    ((:parse-fn this) user-input)))

(defn create-pattern-matcher
  "Create a pattern matching function for the stub parser.

   Uses regular expressions and string matching to extract
   ingredients, hunger level, and time from natural language.
   This is a simplified heuristic approach for development."
  []
  (fn [user-input]
    (let [s (-> user-input (or "") str/lower-case)
          has? #(str/includes? s %)

          ;; Extract hunger level from keywords
          hunger (cond
                   ;; Check for strong hunger indicators
                   (re-find #"\bstarv(?:ing|ed)\b" s) 5
                   (re-find #"\b(?:really|very) hungry\b" s) 5

                   ;; Check for moderate hunger
                   (re-find #"\b(?:not too|not that) hungry\b" s) 3
                   (re-find #"\bkinda hungry\b" s) 3

                   ;; Check for low hunger
                   (re-find #"\bnot very hungry\b" s) 2

                   ;; Default if "hungry" is mentioned
                   (re-find #"\bhungry\b" s) 4

                   ;; Default
                   :else 3)

          ;; Extract time from the input (be conservative)
          ;; Only accept explicit minute mentions or "rush/quick/fast".
          time (cond
                 (re-find #"\b(?:rush|quick|fast)\b" s)
                 10

                 (re-find #"\b(\d+)\s*(?:min|mins|minute|minutes)\b" s)
                 (let [[_ n] (re-find #"\b(\d+)\s*(?:min|mins|minute|minutes)\b" s)]
                   (max 1 (Integer/parseInt n)))

                 :else
                 30)

          ;; Extract ingredients
          ingredients (cond
                        ;; Check for ingredient combinations
                        (and (has? "eggs") (has? "rice"))
                        (cond-> ["eggs" "rice"]
                                (has? "cheese") (conj "cheese")
                                (has? "bread") (conj "bread"))

                        (and (has? "eggs") (has? "bread"))
                        (cond-> ["eggs" "bread"]
                                (has? "cheese") (conj "cheese"))

                        (and (has? "bread") (has? "cheese"))
                        (cond-> ["bread" "cheese"]
                                (has? "eggs") (conj "eggs"))

                        ;; Single ingredients
                        (has? "eggs") ["eggs"]
                        (has? "rice") ["rice"]
                        (has? "bread") ["bread"]
                        (has? "cheese") ["cheese"]
                        :else [])]

      {:ingredients ingredients
       :hunger      hunger
       :time        time})))

(defn stub-parser-resource
  "Create a stub parser as a Closeable resource.

   Used for development and testing without external dependencies."
  []
  (let [parser (->StubParser (create-pattern-matcher))]
    (closeable parser
               (fn [_]
                 (println "Closing stub parser")))))

;; ============================================================================
;; HTTP Connection Pool Management
;; ============================================================================

(defn http-connection-pool
  "Create a managed HTTP connection pool as a Closeable resource.

   This demonstrates how to manage connection pools using with-open.
   The pool is automatically cleaned up when leaving the with-open scope."
  [max-connections]
  (let [;; In a real implementation, you'd create an actual connection pool
        ;; For demonstration, we'll simulate it
        pool {:max-connections    max-connections
              :active-connections (atom 0)
              :pool-id            (str (java.util.UUID/randomUUID))}]
    (println (str "Creating HTTP connection pool " (:pool-id pool)
                  " with max " max-connections " connections"))
    (closeable pool
               (fn [p]
                 (println (str "Closing HTTP connection pool " (:pool-id p)))
                 (reset! (:active-connections p) 0)))))

;; ============================================================================
;; System Composition with with-open
;; ============================================================================

(defn with-meal-system
  "Compose the meal suggestion system using with-open for resource management.

   This function demonstrates the with-open pattern as an alternative to
   Component. Resources are automatically managed and cleaned up.

   Parameters:
   - config: System configuration map with :api-key and :use-stub
   - f: Function to execute with the system (receives parser as argument)

   The function ensures all resources are properly cleaned up even if
   exceptions occur during execution."
  [config f]
  ;; Use with-open to manage resources automatically
  (with-open [;; Create connection pool (simulated)
              conn-pool (http-connection-pool 10)

              ;; Create parser based on configuration
              parser (if (and (:api-key config)
                              (not (:use-stub config)))
                       (openai-parser-resource (:api-key config))
                       (stub-parser-resource))]

    ;; Execute the provided function with the system resources
    ;; The @ derefs the closeable wrapper to get the actual parser
    (f {:parser          @parser
        :connection-pool @conn-pool})))

;; ============================================================================
;; System Runner Functions
;; ============================================================================

(defn run-system
  "Run the meal agent system with proper resource management.

   This function sets up the system using with-open and runs
   an interactive REPL for meal suggestions."
  [config]
  (with-meal-system config
                    (fn [{:keys [parser]}]
                      (println "\n=== Meal Agent (with-open Pattern) ===")
                      (println "Using" (if (:use-stub config) "stub" "OpenAI") "parser")
                      (println "Enter your request (or 'quit' to exit):\n")

                      ;; Interactive loop
                      (loop []
                        (print "> ")
                        (flush)
                        (let [input (read-line)]
                          (when-not (= "quit" (str/lower-case (or input "")))
                            (let [result (core/process-meal-request
                                           parser input core/recipes)]
                              (println "\n" (:message result))
                              (when-let [alts (:alternatives result)]
                                (println " " alts))
                              (println))
                            (recur))))

                      (println "\nShutting down meal agent system..."))))

(defn run-batch-requests
  "Process multiple meal requests in batch mode.

   Demonstrates how to use the system for batch processing
   with automatic resource cleanup."
  [config requests]
  (with-meal-system config
                    (fn [{:keys [parser]}]
                      (println "\n=== Processing Batch Requests ===\n")
                      (doseq [request requests]
                        (println "Request:" request)
                        (let [result (core/process-meal-request
                                       parser request core/recipes)]
                          (println "→" (:message result))
                          (when-let [alts (:alternatives result)]
                            (println "  " alts))
                          (println))))))

;; ============================================================================
;; Test Helpers
;; ============================================================================

(defn run-integration-test
  "Run an integration test with isolated resources.

   This demonstrates how with-open makes testing easier by
   ensuring each test gets fresh resources that are automatically
   cleaned up."
  [test-name test-fn]
  (println (str "\n=== Test: " test-name " ==="))
  (with-meal-system {:use-stub true}
                    (fn [{:keys [parser]}]
                      (try
                        (test-fn parser)
                        (println "✅ Test passed")
                        (catch Exception e
                          (println "❌ Test failed:" (.getMessage e)))))))