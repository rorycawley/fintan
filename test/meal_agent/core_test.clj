(ns meal-agent.core_test
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [meal-agent.core :as sut] ; system under test
            [clojure.string :as str]
            [cheshire.core :as json]))

;; ============================================================================
;; Test Fixtures & Helpers
;; ============================================================================

(def test-recipes
  [{:name "Test Sandwich"
    :ingredients #{"bread" "cheese"}
    :hunger-range [2 3]
    :time 5}
   {:name "Test Omelette"
    :ingredients #{"eggs"}
    :hunger-range [3 4]
    :time 8}])

(defn with-test-recipes
  "Fixture to temporarily replace recipes for testing"
  [f]
  (with-redefs [sut/recipes test-recipes]
    (f)))

(defn with-mock-llm
  "Fixture to mock LLM calls for testing"
  [f]
  (with-redefs [sut/parse-user-input sut/parse-user-input-mock]
    (f)))

;; ============================================================================
;; Unit Tests - Recipe Matching Logic
;; ============================================================================

(deftest test-ingredients-match?
  (testing "Ingredient matching logic"
    (testing "exact match"
      (is (sut/ingredients-match? ["bread" "cheese"] #{"bread" "cheese"})))

    (testing "superset of required ingredients"
      (is (sut/ingredients-match? ["bread" "cheese" "tomato"] #{"bread" "cheese"})))

    (testing "case insensitive matching"
      (is (sut/ingredients-match? ["Bread" "CHEESE"] #{"bread" "cheese"})))

    (testing "missing ingredients"
      (is (not (sut/ingredients-match? ["bread"] #{"bread" "cheese"}))))

    (testing "empty ingredients"
      (is (not (sut/ingredients-match? [] #{"bread"})))
      (is (sut/ingredients-match? ["bread"] #{})))))

(deftest test-hunger-matches?
  (testing "Hunger level matching"
    (are [hunger range expected] (= expected (sut/hunger-matches? hunger range))
                                 3 [2 4] true    ; within range
                                 2 [2 4] true    ; at minimum
                                 4 [2 4] true    ; at maximum
                                 1 [2 4] false   ; below range
                                 5 [2 4] false   ; above range
                                 3 [3 3] true))) ; exact match

(deftest test-time-sufficient?
  (testing "Time constraint checking with tolerance"
    (are [available recipe-time expected] (= expected (sut/time-sufficient? available recipe-time))
                                          10 10 true    ; exact match
                                          15 10 true    ; more than enough
                                          8  10 true    ; within 2-minute tolerance
                                          7  10 false   ; outside tolerance
                                          5  10 false   ; significantly less
                                          20 15 true))) ; plenty of time

;; ============================================================================
;; Unit Tests - Recipe Finding
;; ============================================================================

(deftest test-find-suitable-recipes
  (use-fixtures :once with-test-recipes)

  (testing "Finding suitable recipes"
    (testing "single match"
      (let [result (sut/find-suitable-recipes
                     {:ingredients ["bread" "cheese"]
                      :hunger 3
                      :time 10})]
        (is (= 1 (count result)))
        (is (= "Test Sandwich" (:name (first result))))))

    (testing "no match - missing ingredients"
      (is (empty? (sut/find-suitable-recipes
                    {:ingredients ["rice"]
                     :hunger 3
                     :time 10}))))

    (testing "no match - wrong hunger level"
      (is (empty? (sut/find-suitable-recipes
                    {:ingredients ["bread" "cheese"]
                     :hunger 5
                     :time 10}))))

    (testing "no match - insufficient time"
      (is (empty? (sut/find-suitable-recipes
                    {:ingredients ["eggs"]
                     :hunger 3
                     :time 5}))))

    (testing "results sorted by time"
      (let [results (sut/find-suitable-recipes
                      {:ingredients ["bread" "cheese" "eggs"]
                       :hunger 3
                       :time 20})]
        (is (= ["Test Sandwich" "Test Omelette"]
               (map :name results)))))))

;; ============================================================================
;; Unit Tests - Meal Suggestion
;; ============================================================================

(deftest test-suggest-meal
  (use-fixtures :once with-test-recipes)

  (testing "Meal suggestion logic"
    (testing "successful suggestion"
      (let [result (sut/suggest-meal
                     {:ingredients ["bread" "cheese"]
                      :hunger 3
                      :time 10})]
        (is (= :success (:status result)))
        (is (= "Test Sandwich" (get-in result [:meal :name])))
        (is (empty? (:alternatives result)))))

    (testing "no suitable meal"
      (let [result (sut/suggest-meal
                     {:ingredients ["unknown"]
                      :hunger 3
                      :time 10})]
        (is (= :no-meal (:status result)))
        (is (str/includes? (:message result) "no suitable meal"))))

    (testing "with alternatives"
      (let [result (sut/suggest-meal
                     {:ingredients ["bread" "cheese" "eggs"]
                      :hunger 3
                      :time 20})]
        (is (= :success (:status result)))
        (is (= 1 (count (:alternatives result))))))))

;; ============================================================================
;; Unit Tests - Agent State Management
;; ============================================================================

(deftest test-agent-creation
  (testing "Agent initialization"
    (let [agent (sut/create-agent)]
      (is (instance? meal_agent.core.MealAgent agent))
      (is (nil? (get-in agent [:state :last-input])))
      (is (nil? (get-in agent [:state :last-suggestion])))
      (is (empty? (get-in agent [:state :history]))))))

(deftest test-agent-perceive
  (use-fixtures :once with-mock-llm)

  (testing "Agent perception updates state"
    (let [agent (sut/create-agent)
          input "I have bread and cheese"
          updated-agent (sut/perceive agent input)]
      (is (= input (get-in updated-agent [:state :last-input :raw])))
      (is (map? (get-in updated-agent [:state :last-input :parsed])))
      (is (contains? (get-in updated-agent [:state :last-input :parsed]) :ingredients)))))

(deftest test-agent-act
  (use-fixtures :once with-test-recipes with-mock-llm)

  (testing "Agent action generates suggestion and updates history"
    (let [agent (-> (sut/create-agent)
                    (sut/perceive "I have bread and cheese")
                    (sut/act))]
      (is (some? (get-in agent [:state :last-suggestion])))
      (is (= 1 (count (get-in agent [:state :history]))))
      (is (instance? java.util.Date
                     (get-in agent [:state :history 0 :timestamp]))))))

;; ============================================================================
;; Unit Tests - Output Formatting
;; ============================================================================

(deftest test-format-suggestion
  (testing "Suggestion formatting"
    (testing "successful suggestion without alternatives"
      (is (str/includes?
            (sut/format-suggestion {:status :success
                                    :meal {:name "Grilled Cheese"}
                                    :alternatives []})
            "Grilled Cheese")))

    (testing "successful suggestion with alternatives"
      (let [output (sut/format-suggestion
                     {:status :success
                      :meal {:name "Main Dish"}
                      :alternatives [{:name "Alt 1"} {:name "Alt 2"}]})]
        (is (str/includes? output "Main Dish"))
        (is (str/includes? output "Alt 1"))
        (is (str/includes? output "Alt 2"))))

    (testing "no meal found"
      (is (str/includes?
            (sut/format-suggestion {:status :no-meal})
            "no suitable meal")))

    (testing "unknown status"
      (is (= "Unknown status"
             (sut/format-suggestion {:status :unknown}))))))

;; ============================================================================
;; Integration Tests - Mock Parser
;; ============================================================================

(deftest test-parse-user-input-mock
  (testing "Mock parser handles various inputs"
    (testing "eggs and rice with urgency"
      (let [result (sut/parse-user-input-mock
                     "I have eggs and rice. I'm very hungry but in a rush.")]
        (is (= ["eggs" "rice"] (:ingredients result)))
        (is (= 5 (:hunger result)))
        (is (= 10 (:time result)))))

    (testing "bread and cheese, casual"
      (let [result (sut/parse-user-input-mock
                     "kinda hungry, got some bread and cheese")]
        (is (= ["bread" "cheese"] (:ingredients result)))
        (is (= 3 (:hunger result)))
        (is (= 30 (:time result)))))

    (testing "unknown input"
      (let [result (sut/parse-user-input-mock "random text")]
        (is (empty? (:ingredients result)))
        (is (= 3 (:hunger result)))
        (is (= 20 (:time result)))))))

;; ============================================================================
;; Integration Tests - Full Pipeline
;; ============================================================================

(deftest test-process-request-offline
  (use-fixtures :once with-test-recipes)

  (testing "Full offline processing pipeline"
    (testing "successful processing"
      (let [result (sut/process-request-offline
                     "kinda hungry, got some bread and cheese lying around")]
        (is (= :success (:status result)))
        (is (some? (:meal result)))))

    (testing "no suitable meal"
      (let [result (sut/process-request-offline "just have water")]
        (is (= :no-meal (:status result)))))))

;; ============================================================================
;; Property-Based Tests
;; ============================================================================

(defspec ingredients-match-is-reflexive 100
  (prop/for-all [ingredients (gen/vector (gen/elements ["bread" "cheese" "eggs" "rice"]))]
    (sut/ingredients-match? ingredients (set ingredients))))

(defspec hunger-within-range-always-matches 100
  (prop/for-all [min-hunger (gen/choose 1 4)
                 max-hunger (gen/choose 1 5)]
    (let [range [(min min-hunger max-hunger) (max min-hunger max-hunger)]
          mid-point (quot (+ (first range) (second range)) 2)]
      (sut/hunger-matches? mid-point range))))

(defspec sufficient-time-always-works 100
  (prop/for-all [recipe-time (gen/choose 5 30)
                 extra-time (gen/choose 0 20)]
    (sut/time-sufficient? (+ recipe-time extra-time) recipe-time)))

;; ============================================================================
;; Edge Cases & Error Handling
;; ============================================================================

(deftest test-edge-cases
  (testing "Edge cases and error conditions"
    (testing "nil inputs"
      (is (thrown? Exception (sut/ingredients-match? nil #{"bread"})))
      (is (thrown? Exception (sut/hunger-matches? nil [1 3]))))

    (testing "empty recipe database"
      (with-redefs [sut/recipes []]
        (let [result (sut/suggest-meal {:ingredients ["anything"]
                                        :hunger 3
                                        :time 20})]
          (is (= :no-meal (:status result))))))

    (testing "extreme values"
      (is (not (sut/hunger-matches? 0 [1 5])))
      (is (not (sut/hunger-matches? 6 [1 5])))
      (is (not (sut/time-sufficient? -1 10))))))

;; ============================================================================
;; Performance Tests
;; ============================================================================

(deftest ^:performance test-recipe-search-performance
  (testing "Recipe search performance with large dataset"
    (let [large-recipe-db (vec (repeatedly 1000
                                           #(hash-map
                                              :name (str "Recipe " (rand-int 1000))
                                              :ingredients (set (take (inc (rand-int 5))
                                                                      (shuffle ["a" "b" "c" "d" "e"])))
                                              :hunger-range [(inc (rand-int 3)) (+ 3 (rand-int 3))]
                                              :time (+ 5 (rand-int 25)))))]
      (with-redefs [sut/recipes large-recipe-db]
        (let [start (System/nanoTime)
              _ (sut/find-suitable-recipes {:ingredients ["a" "b" "c"]
                                            :hunger 3
                                            :time 15})
              elapsed (/ (- (System/nanoTime) start) 1000000.0)]
          (is (< elapsed 100) ; Should complete within 100ms
              (str "Search took " elapsed "ms")))))))

;; ============================================================================
;; Test Runner Configuration
;; ============================================================================

(defn run-tests
  "Run all tests with summary"
  []
  (let [results (clojure.test/run-tests)]
    (println "\n=== Test Summary ===")
    (println "Tests run:" (:test results))
    (println "Assertions:" (:pass results))
    (println "Failures:" (:fail results))
    (println "Errors:" (:error results))
    (if (and (zero? (:fail results)) (zero? (:error results)))
      (println "✅ All tests passed!")
      (println "❌ Some tests failed"))
    results))