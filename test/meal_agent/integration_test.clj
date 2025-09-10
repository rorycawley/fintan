(ns meal-agent.integration_test
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [meal-agent.core :as sut]
            [clojure.string :as str]
            [cheshire.core :as json]))

;; ============================================================================
;; Test Configuration
;; ============================================================================

(def ^:dynamic *skip-api-tests*
  "Skip tests that require API access if no key is available"
  (nil? (System/getenv "OPENAI_API_KEY")))

(defmacro with-api
  "Only run test if API key is available"
  [& body]
  `(when-not *skip-api-tests*
     ~@body))

(defn api-test-fixture
  "Fixture for API tests - adds delay to respect rate limits"
  [f]
  (when-not *skip-api-tests*
    (f)
    (Thread/sleep 1000))) ; 1 second delay between API tests

;; ============================================================================
;; LLM Parser Integration Tests
;; ============================================================================

(deftest ^:integration test-llm-parser
  (with-api
    (testing "LLM parser handles natural language"
      (testing "vague time expressions"
        (let [result (sut/parse-user-input "I'm in a rush, have eggs and rice")]
          (is (map? result))
          (is (sequential? (:ingredients result)))
          (is (<= 5 (:time result) 15)) ; "in a rush" should be quick
          (is (integer? (:hunger result)))))

      (testing "numbers and adjectives removal"
        (let [result (sut/parse-user-input
                       "I have 3 eggs, leftover rice, and fresh bread")]
          (is (= #{"eggs" "rice" "bread"}
                 (set (map str/lower-case (:ingredients result)))))
          (is (not-any? #(str/includes? % "3") (:ingredients result)))
          (is (not-any? #(str/includes? % "leftover") (:ingredients result)))
          (is (not-any? #(str/includes? % "fresh") (:ingredients result)))))

      (testing "hunger level inference"
        (are [input expected-range]
          (let [result (sut/parse-user-input input)]
            (<= (first expected-range) (:hunger result) (second expected-range)))
          "I'm starving" [4 5]
          "I'm very hungry" [4 5]
          "I'm kinda hungry" [2 3]
          "I'm a little hungry" [1 2]
          "I could eat" [2 3]))

      (testing "complex natural language"
        (let [result (sut/parse-user-input
                       (str "Well, I'm pretty hungry right now. "
                            "I've got some eggs in the fridge, maybe 3 or 4, "
                            "and there's leftover rice from yesterday. "
                            "I need to leave in about 20 minutes for a meeting."))]
          (is (contains? (set (:ingredients result)) "eggs"))
          (is (contains? (set (:ingredients result)) "rice"))
          (is (<= 15 (:time result) 25))
          (is (<= 3 (:hunger result) 5)))))))

;; ============================================================================
;; End-to-End Integration Tests
;; ============================================================================

(deftest ^:integration test-end-to-end-with-llm
  (use-fixtures :once api-test-fixture)

  (with-api
    (testing "Complete pipeline with real LLM"
      (testing "successful meal suggestion"
        (let [result (sut/process-request
                       "I have bread, cheese, and eggs. Very hungry, got 15 minutes")]
          (is (= :success (:status result)))
          (is (some? (:meal result)))
          (is (string? (get-in result [:meal :name])))))

      (testing "no suitable meal scenario"
        (let [result (sut/process-request
                       "I only have water and salt")]
          (is (= :no-meal (:status result)))))

      (testing "handles ambiguous input"
        (let [result (sut/process-request
                       "feeling peckish, might have some dairy products around")]
          (is (map? result))
          (is (contains? #{:success :no-meal} (:status result))))))))

;; ============================================================================
;; LLM Response Validation Tests
;; ============================================================================

(deftest ^:integration test-llm-response-validation
  (with-api
    (testing "LLM response conforms to schema"
      (let [test-inputs ["I have ingredients"
                         "Random text about food"
                         "eggs eggs eggs"
                         "🍳🥚🍞"]
            results (map sut/parse-user-input test-inputs)]

        (doseq [result results]
          (testing "has required fields"
            (is (contains? result :ingredients))
            (is (contains? result :hunger))
            (is (contains? result :time)))

          (testing "correct types"
            (is (sequential? (:ingredients result)))
            (is (every? string? (:ingredients result)))
            (is (integer? (:hunger result)))
            (is (integer? (:time result))))

          (testing "valid ranges"
            (is (<= 1 (:hunger result) 5))
            (is (>= (:time result) 0))))))))

;; ============================================================================
;; Stateful Agent Integration Tests
;; ============================================================================

(deftest ^:integration test-stateful-agent-with-llm
  (with-api
    (testing "Stateful agent with LLM maintains history"
      (let [agent (sut/create-agent)
            input1 "I'm hungry, have eggs and cheese"
            input2 "Actually, I also found some bread"

            agent1 (-> agent
                       (sut/perceive input1)
                       (sut/act))
            agent2 (-> agent1
                       (sut/perceive input2)
                       (sut/act))]

        (testing "maintains conversation history"
          (is (= 2 (count (get-in agent2 [:state :history])))))

        (testing "each interaction is recorded"
          (let [history (get-in agent2 [:state :history])]
            (is (every? #(contains? % :input) history))
            (is (every? #(contains? % :suggestion) history))
            (is (every? #(contains? % :timestamp) history))))))))

;; ============================================================================
;; Error Handling Integration Tests
;; ============================================================================

(deftest ^:integration test-llm-error-handling
  (with-api
    (testing "Handles malformed or extreme inputs gracefully"
      (testing "empty input"
        (is (map? (sut/parse-user-input ""))))

      (testing "very long input"
        (let [long-input (str/join " " (repeat 100 "I have bread and cheese"))]
          (is (map? (sut/parse-user-input long-input)))))

      (testing "non-English characters"
        (is (map? (sut/parse-user-input "我有鸡蛋和米饭"))))

      (testing "special characters and emojis"
        (is (map? (sut/parse-user-input "I have 🥚 and 🍚! @#$%")))))))

;; ============================================================================
;; Performance Integration Tests
;; ============================================================================

(deftest ^:integration ^:performance test-llm-performance
  (with-api
    (testing "LLM response time is acceptable"
      (let [start (System/currentTimeMillis)
            _ (sut/parse-user-input "I have eggs and rice, hungry")
            elapsed (- (System/currentTimeMillis) start)]
        (is (< elapsed 5000) ; Should complete within 5 seconds
            (str "LLM call took " elapsed "ms"))))))

;; ============================================================================
;; Mock vs Real Comparison Tests
;; ============================================================================

(deftest ^:integration test-mock-vs-real-parser
  (with-api
    (testing "Mock parser approximates real parser behavior"
      (let [test-input "I have eggs and rice. I'm very hungry but in a rush."
            mock-result (sut/parse-user-input-mock test-input)
            real-result (sut/parse-user-input test-input)]

        (testing "similar structure"
          (is (= (set (keys mock-result)) (set (keys real-result)))))

        (testing "ingredients extracted similarly"
          (is (some #(str/includes? (str/lower-case %) "egg")
                    (:ingredients real-result)))
          (is (some #(str/includes? (str/lower-case %) "rice")
                    (:ingredients real-result))))

        (testing "hunger level in same range"
          (is (<= 4 (:hunger real-result) 5)))

        (testing "time constraint recognized"
          (is (<= 5 (:time real-result) 20)))))))

;; ============================================================================
;; Test Runner for Integration Tests
;; ============================================================================

(defn run-integration-tests
  "Run integration tests with API status check"
  []
  (if *skip-api-tests*
    (do
      (println "⚠️  Skipping integration tests - OPENAI_API_KEY not set")
      (println "   Set the environment variable to run LLM integration tests")
      {:test 0 :pass 0 :fail 0 :error 0})
    (do
      (println "🔌 Running integration tests with API...")
      (let [results (clojure.test/run-tests 'meal-agent.integration_test)]
        (println "\n=== Integration Test Summary ===")
        (println "Tests run:" (:test results))
        (println "Assertions:" (:pass results))
        (println "Failures:" (:fail results))
        (println "Errors:" (:error results))
        results))))