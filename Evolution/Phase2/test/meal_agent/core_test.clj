(ns meal-agent.core-test
  "Tests for the meal agent system.

   These tests demonstrate how the with-open pattern makes testing
   easier by ensuring each test gets fresh, isolated resources that
   are automatically cleaned up."
  (:require [clojure.test :refer :all]
            [meal-agent.core :as core]
            [meal-agent.adapters :as adapters]))

;; ============================================================================
;; Test Fixtures and Helpers
;; ============================================================================

(defn with-test-system
  "Test fixture that provides a fresh system for each test.

   Using with-open ensures that:
   1. Each test gets completely fresh resources
   2. Resources are cleaned up even if tests fail
   3. Tests can't interfere with each other
   4. No resource leaks between test runs"
  [test-fn]
  ;; Always use stub parser for tests (no external dependencies)
  (adapters/with-meal-system {:use-stub true}
                             (fn [{:keys [parser]}]
                               (test-fn parser))))

;; ============================================================================
;; Pure Function Tests (No Resources Needed)
;; ============================================================================

(deftest test-normalize-ingredients
  (testing "Ingredient normalization"
    (is (= #{"eggs" "bread"}
           (core/normalize-ingredients ["Eggs" " bread " ""])))
    (is (= #{"cheese"}
           (core/normalize-ingredients ["CHEESE"])))
    (is (= #{}
           (core/normalize-ingredients ["" "  " nil])))))

(deftest test-hunger-satisfied?
  (testing "Hunger satisfaction logic"
    ;; Within range
    (is (core/hunger-satisfied? 3 [2 4]))
    (is (core/hunger-satisfied? 2 [2 4]))
    (is (core/hunger-satisfied? 4 [2 4]))

    ;; Tolerance for substantial meals
    (is (core/hunger-satisfied? 5 [3 4]))  ; max-hunger >= 3
    (is (not (core/hunger-satisfied? 5 [1 2]))) ; max-hunger < 3

    ;; Out of range
    (is (not (core/hunger-satisfied? 1 [2 4])))
    (is (not (core/hunger-satisfied? 6 [2 4])))))

(deftest test-recipe-matches?
  (testing "Recipe matching logic"
    (let [request {:ingredients #{"eggs" "bread"}
                   :hunger 3
                   :time 10}
          matching-recipe {:name "Eggs on Toast"
                           :ingredients #{"eggs" "bread"}
                           :hunger-range [3 5]
                           :time 8}
          wrong-ingredients {:name "Cheese Omelette"
                             :ingredients #{"eggs" "cheese"}
                             :hunger-range [3 4]
                             :time 10}
          wrong-hunger {:name "Simple Meal"
                        :ingredients #{"eggs" "bread"}
                        :hunger-range [1 2]
                        :time 8}]

      (is (core/recipe-matches? request matching-recipe))
      (is (not (core/recipe-matches? request wrong-ingredients)))
      (is (not (core/recipe-matches? request wrong-hunger))))))

;; ============================================================================
;; Integration Tests (Using with-open for Resource Management)
;; ============================================================================

(deftest test-stub-parser-integration
  (testing "Stub parser extracts information correctly"
    (with-test-system
      (fn [parser]
        ;; Test basic parsing
        (let [result (core/parse-meal-request
                       parser
                       "I have eggs and bread, very hungry")]
          (is (contains? (set (:ingredients result)) "eggs"))
          (is (contains? (set (:ingredients result)) "bread"))
          (is (= 5 (:hunger result))))

        ;; Test time extraction
        (let [result (core/parse-meal-request
                       parser
                       "I have cheese, 15 minutes available")]
          (is (contains? (set (:ingredients result)) "cheese"))
          (is (= 15 (:time result))))

        ;; Test rush detection
        (let [result (core/parse-meal-request
                       parser
                       "eggs available, in a rush")]
          (is (= 10 (:time result))))))))

(deftest test-process-meal-request
  (testing "End-to-end meal request processing"
    (with-test-system
      (fn [parser]
        ;; Test successful match
        (let [result (core/process-meal-request
                       parser
                       "I have bread and cheese, kinda hungry"
                       core/recipes)]
          (is (= :success (:status result)))
          (is (re-find #"Grilled Cheese" (:message result))))

        ;; Test no match
        (let [result (core/process-meal-request
                       parser
                       "I only have caviar"
                       core/recipes)]
          (is (= :no-match (:status result))))

        ;; Test with multiple matches
        (let [result (core/process-meal-request
                       parser
                       "I have eggs, bread and cheese, hungry, 30 minutes"
                       core/recipes)]
          (is (= :success (:status result)))
          (is (not (nil? (:alternatives result)))))))))

;; ============================================================================
;; Resource Cleanup Tests
;; ============================================================================

(deftest test-resource-cleanup
  (testing "Resources are properly cleaned up after with-open"
    (let [cleanup-called? (atom false)]
      ;; Create a custom parser that tracks cleanup
      (with-open [parser (adapters/closeable
                           (reify core/MealRequestParser
                             (parse-meal-request [_ input]
                               {:ingredients ["test"]
                                :hunger 3
                                :time 10}))
                           (fn [_]
                             (reset! cleanup-called? true)))]
        ;; Use the parser
        (is (not @cleanup-called?))
        (core/parse-meal-request @parser "test"))
      ;; After with-open, cleanup should have been called
      (is @cleanup-called?))))

(deftest test-exception-cleanup
  (testing "Resources are cleaned up even when exceptions occur"
    (let [cleanup-called? (atom false)]
      (is (thrown? Exception
                   (with-open [parser (adapters/closeable
                                        (reify core/MealRequestParser
                                          (parse-meal-request [_ input]
                                            (throw (Exception. "Test error"))))
                                        (fn [_]
                                          (reset! cleanup-called? true)))]
                     ;; This will throw an exception
                     (core/parse-meal-request @parser "test"))))
      ;; Cleanup should still have been called
      (is @cleanup-called?))))

;; ============================================================================
;; Concurrent Access Tests
;; ============================================================================

(deftest test-concurrent-requests
  (testing "Multiple concurrent requests with isolated resources"
    ;; Each thread gets its own resources via with-open
    (let [results (atom [])
          threads (for [i (range 5)]
                    (future
                      ;; Each thread has its own isolated system
                      (adapters/with-meal-system {:use-stub true}
                                                 (fn [{:keys [parser]}]
                                                   (let [result (core/process-meal-request
                                                                  parser
                                                                  (str "I have bread and cheese, request " i)
                                                                  core/recipes)]
                                                     (swap! results conj result))))))]

      ;; Wait for all threads to complete
      (doseq [t threads] @t)

      ;; All requests should succeed independently
      (is (= 5 (count @results)))
      (is (every? #(= :success (:status %)) @results)))))

;; ============================================================================
;; Performance Tests
;; ============================================================================

(deftest test-resource-allocation-performance
  (testing "with-open has minimal overhead"
    (let [iterations 1000
          start-time (System/nanoTime)]

      ;; Run many iterations of creating and destroying resources
      (dotimes [_ iterations]
        (adapters/with-meal-system {:use-stub true}
                                   (fn [{:keys [parser]}]
                                     ;; Just create and immediately release
                                     nil)))

      (let [elapsed-ms (/ (- (System/nanoTime) start-time) 1000000.0)
            per-iteration (/ elapsed-ms iterations)]
        (println (format "Resource allocation: %.2f ms per iteration" per-iteration))
        ;; Should be very fast (< 1ms per iteration)
        (is (< per-iteration 1.0))))))

;; ============================================================================
;; Test Runner
;; ============================================================================

(defn run-suite
  "Run all tests with summary."
  []
  (println "\n=== Running Meal Agent Tests ===\n")
  (let [results (run-tests)]
    (println "\n=== Test Summary ===")
    (println "Tests run:" (:test results))
    (println "Assertions:" (:pass results))
    (println "Failures:" (:fail results))
    (println "Errors:" (:error results))
    results))