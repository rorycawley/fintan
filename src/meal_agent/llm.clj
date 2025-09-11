(ns meal-agent.llm
  (:require [clojure.data.json :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [meal-agent.core :as core]))

;; ============================================================================
;; OpenAI LLM Client Implementation
;; ============================================================================

(defrecord OpenAIClient [config]
  core/LLMClient
  (parse-natural-language [_this user-input]
    (let [request-body {:model           (:model config)
                        :messages        [{:role    "system"
                                           :content (:system-prompt config)}
                                          {:role    "user"
                                           :content user-input}]
                        :response_format {:type        "json_schema"
                                          :json_schema {:name   "meal_inputs"
                                                        :schema (:schema config)}}}

          response (http/post (:api-url config)
                              {:headers            {"Authorization" (str "Bearer " (:api-key config))
                                                    "Content-Type"  "application/json"}
                               :body               (json/write-str request-body)
                               :as                 :json
                               :throw-exceptions   true
                               :socket-timeout     10000
                               :connection-timeout 5000})]

      (-> response
          :body
          :choices
          first
          :message
          :content
          (json/read-str :key-fn keyword)))))

(defn create-openai-client
  "Factory function to create OpenAI client with configuration"
  [api-key]
  (->OpenAIClient (core/create-config api-key)))

;; ============================================================================
;; Stub Client for Offline Development
;; ============================================================================

(defrecord StubClient [parse-fn]
  core/LLMClient
  (parse-natural-language [this user-input]
    ((:parse-fn this) user-input)))


(defn create-simple-stub
  "Create a stub that uses simple pattern matching + basic NLP"
  []
  (->StubClient
    (fn [user-input]
      (let [s (-> user-input (or "") str/lower-case)
            has? #(str/includes? s %)

            ;; hunger: 1..5
            hunger (cond
                     ;; strongest first
                     (re-find #"\bstarv(?:ing|ed)\b" s) 5
                     (re-find #"\breally hungry\b" s) 5
                     (re-find #"\bvery hungry\b" s) 5

                     ;; soft negatives
                     (re-find #"\bnot too hungry\b" s) 3
                     (re-find #"\bnot that hungry\b" s) 3

                     ;; stronger negative
                     (re-find #"\bnot very hungry\b" s) 2
                     (re-find #"\bkinda hungry\b" s) 3

                     ;; generic positive
                     (re-find #"\bhungry\b" s) 4

                     :else 3)

            ;; minutes: try "30 min(s)/minutes", else any number; fallback 30
            time (cond
                   (re-find #"\b(?:rush|quick|fast)\b" s) 10
                   (re-find #"\b(\d+)\s*(?:min|mins|minute|minutes)\b" s)
                   (Integer/parseInt (second (re-find #"\b(\d+)\s*(?:min|mins|minute|minutes)\b" s)))
                   (re-find #"\b(\d+)\b" s)
                   (Integer/parseInt (second (re-find #"\b(\d+)\b" s)))
                   :else 30)

            ;; ingredients: prefer combos first
            ingredients (cond
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

                          (has? "eggs") ["eggs"]
                          (has? "rice") ["rice"]
                          (has? "bread") ["bread"]
                          (has? "cheese") ["cheese"]
                          :else [])]
        {:ingredients ingredients
         :hunger      hunger
         :time        time}))))


;; ============================================================================
;; Client Factory with Fallback
;; ============================================================================

(defn create-client
  "Create appropriate client based on availability of API key"
  [api-key]
  (if (and api-key (not (str/blank? api-key)))
    (create-openai-client api-key)
    (do
      (println "⚠️  No API key provided. Using stub client for development.")
      (create-simple-stub))))

;; ============================================================================
;; Example Scenarios
;; ============================================================================

(defn run-examples
  "Run example scenarios to demonstrate the system"
  []
  (let [client (create-simple-stub)
        history (core/create-history-atom)]

    (println "\n=== Meal Agent Example Scenarios ===\n")

    ;; Example 1
    (println "Example 1: Quick meal with limited ingredients")
    (let [result (core/process-meal-request
                   client
                   "I have bread and cheese, kinda hungry"
                   history
                   core/recipes)]
      (println "→" (:message result))
      (when-let [alts (:alternatives result)]
        (println "  " alts)))

    ;; Example 2
    (println "\nExample 2: Very hungry with more ingredients")
    (let [result (core/process-meal-request
                   client
                   "I have eggs and rice. I'm very hungry but in a rush."
                   history
                   core/recipes)]
      (println "→" (:message result))
      (when-let [alts (:alternatives result)]
        (println "  " alts)))

    ;; Example 3
    (println "\nExample 3: No matching recipes")
    (let [result (core/process-meal-request
                   client
                   "I only have caviar"
                   history
                   core/recipes)]
      (println "→" (:message result)))

    (println "\n" (count @history) "requests processed total.")))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn -main
  "Main entry point for the application"
  [& _args]
  (let [api-key (System/getenv "OPENAI_API_KEY")
        client (create-client api-key)
        history (core/create-history-atom)]

    (println "\n=== Meal Agent ===")
    (println "Enter your request (or 'quit' to exit):\n")

    (loop []
      (print "> ")
      (flush)
      (let [input (read-line)]
        (when-not (= "quit" (clojure.string/lower-case (or input "")))
          (let [result (core/process-meal-request
                         client input history core/recipes)]
            (println "\n" (:message result))
            (when-let [alts (:alternatives result)]
              (println " " alts))
            (println))
          (recur))))

    (println "\nGoodbye! History saved:")
    (println (str (count @history) " requests processed."))))