(ns meal-agent.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [meal-agent.core :as meal]
            [clojure.string :as str]))


;; ============================================================================
;; Test Fixtures and Helpers
;; ============================================================================

(def test-recipes
  [{:name "Quick Snack"
    :ingredients #{"bread"}
    :hunger-range [1 2]
    :time 3}

   {:name "Simple Meal"
    :ingredients #{"bread" "cheese"}
    :hunger-range [2 3]
    :time 5}

   {:name "Hearty Meal"
    :ingredients #{"bread" "cheese" "eggs"}
    :hunger-range [4 5]
    :time 10}])

(defrecord MockLLMClient [responses]
  meal/LLMClient
  (parse-natural-language [this user-input]
    (get @(:responses this) user-input
         {:ingredients ["bread"] :hunger 3 :time 20})))

(defn create-mock-client
  "Create a mock LLM client with predefined responses"
  [response-map]
  (->MockLLMClient (atom response-map)))

;; ============================================================================
;; Tests for Pure Functions - Data Transformations
;; ============================================================================

(deftest test-normalize-ingredient
  (testing "Normalize ingredient handles various inputs"
    (is (= "eggs" (meal/normalize-ingredient "Eggs")))
    (is (= "eggs" (meal/normalize-ingredient " eggs ")))
    (is (= "eggs" (meal/normalize-ingredient "EGGS")))
    (is (= "fried rice" (meal/normalize-ingredient " Fried Rice ")))))

(deftest test-normalize-ingredients
  (testing "Normalize ingredients creates canonical set"
    (is (= #{"eggs" "rice"}
           (meal/normalize-ingredients ["Eggs" "rice" " EGGS "])))
    (is (= #{"bread" "cheese"}
           (meal/normalize-ingredients ["  bread" "CHEESE  " ""])))
    (is (= #{}
           (meal/normalize-ingredients ["" "  " nil])))
    (is (= #{}
           (meal/normalize-ingredients [nil nil nil])))
    (is (= #{"eggs"}
           (meal/normalize-ingredients ["eggs" nil "" "  "])))))

(deftest test-llm-response->meal-request
  (testing "Transform LLM response to meal request"
    (let [response {:ingredients ["Eggs" "RICE"]
                    :hunger 4
                    :time 15}
          request (meal/llm-response->meal-request response)]
      (is (= #{"eggs" "rice"} (:ingredients request)))
      (is (= 4 (:hunger request)))
      (is (= 15 (:time request)))))

  (testing "Validates preconditions"
    (is (thrown? AssertionError
                 (meal/llm-response->meal-request {:hunger 4 :time 15}))))

  (testing "Validates postconditions"
    (is (thrown? AssertionError
                 (meal/llm-response->meal-request
                   {:ingredients ["eggs"] :hunger 0 :time 15})))
    (is (thrown? AssertionError
                 (meal/llm-response->meal-request
                   {:ingredients ["eggs"] :hunger 6 :time 15})))
    (is (thrown? AssertionError
                 (meal/llm-response->meal-request
                   {:ingredients ["eggs"] :hunger 3 :time -1})))))

;; ============================================================================
;; Tests for Composable Predicates
;; ============================================================================

(deftest test-has-ingredients?
  (testing "Ingredient predicate checks subset relationship"
    (let [needs-eggs-rice (meal/has-ingredients? #{"eggs" "rice"})]
      (is (true? (needs-eggs-rice #{"eggs" "rice" "salt"})))
      (is (true? (needs-eggs-rice #{"eggs" "rice"})))
      (is (false? (needs-eggs-rice #{"eggs"})))
      (is (false? (needs-eggs-rice #{"bread" "cheese"}))))))

(deftest test-within-hunger-range?
  (testing "Hunger range predicate"
    (let [moderate-hunger (meal/within-hunger-range? [2 4])]
      (is (true? (moderate-hunger 2)))
      (is (true? (moderate-hunger 3)))
      (is (true? (moderate-hunger 4)))
      (is (false? (moderate-hunger 1)))
      (is (false? (moderate-hunger 5))))))

(deftest test-within-time-limit?
  (testing "Time limit predicate with 2-minute tolerance"
    (let [needs-10-min (meal/within-time-limit? 10)]
      (is (true? (needs-10-min 10)))
      (is (true? (needs-10-min 15)))
      (is (true? (needs-10-min 8)))  ; 2-minute tolerance
      (is (false? (needs-10-min 7)))
      (is (false? (needs-10-min 5))))))

(deftest test-recipe-matches?
  (testing "Recipe matching combines all predicates"
    (let [request {:ingredients #{"bread" "cheese"}
                   :hunger 3
                   :time 10}
          matching-recipe {:name "Test"
                           :ingredients #{"bread" "cheese"}
                           :hunger-range [2 4]
                           :time 8}
          wrong-ingredients {:name "Test"
                             :ingredients #{"eggs"}
                             :hunger-range [2 4]
                             :time 8}
          wrong-hunger {:name "Test"
                        :ingredients #{"bread" "cheese"}
                        :hunger-range [4 5]
                        :time 8}
          too-slow {:name "Test"
                    :ingredients #{"bread" "cheese"}
                    :hunger-range [2 4]
                    :time 15}]
      (is (true? (meal/recipe-matches? request matching-recipe)))
      (is (false? (meal/recipe-matches? request wrong-ingredients)))
      (is (false? (meal/recipe-matches? request wrong-hunger)))
      (is (false? (meal/recipe-matches? request too-slow))))))

;; ============================================================================
;; Tests for Recipe Selection
;; ============================================================================

(deftest test-find-matching-recipes
  (testing "Find matching recipes returns sorted list"
    (let [request {:ingredients #{"bread" "cheese" "eggs"}
                   :hunger 4
                   :time 20}
          matches (meal/find-matching-recipes request test-recipes)]
      (is (= 2 (count matches)))
      (is (= "Simple Meal" (:name (first matches))))
      (is (= "Hearty Meal" (:name (second matches))))))

  (testing "Returns empty list when no matches"
    (let [request {:ingredients #{"unknown"}
                   :hunger 3
                   :time 20}
          matches (meal/find-matching-recipes request test-recipes)]
      (is (empty? matches))))

  (testing "Validates preconditions"
    (is (thrown? AssertionError
                 (meal/find-matching-recipes
                   {:ingredients ["not-a-set"] :hunger 3 :time 20}
                   test-recipes)))))

(deftest test-select-best-recipe
  (testing "Selects first recipe as primary"
    (let [recipes [{:name "A" :time 5}
                   {:name "B" :time 10}
                   {:name "C" :time 15}]
          selection (meal/select-best-recipe recipes)]
      (is (= "A" (:name (:primary selection))))
      (is (= 2 (count (:alternatives selection))))
      (is (= "B" (:name (first (:alternatives selection)))))))

  (testing "Returns ::no-matches for empty list"
    (is (= ::meal/no-matches (meal/select-best-recipe []))))

  (testing "Handles single recipe"
    (let [selection (meal/select-best-recipe [{:name "Only" :time 5}])]
      (is (= "Only" (:name (:primary selection))))
      (is (empty? (:alternatives selection))))))

(deftest test-suggest-meal
  (testing "Complete meal suggestion flow"
    (let [request {:ingredients #{"bread" "cheese"}
                   :hunger 3
                   :time 10}
          suggestion (meal/suggest-meal request test-recipes)]
      (is (= "Simple Meal" (:name (:primary suggestion))))))

  (testing "Returns ::no-matches when appropriate"
    (let [request {:ingredients #{"caviar"}
                   :hunger 5
                   :time 5}
          suggestion (meal/suggest-meal request test-recipes)]
      (is (= ::meal/no-matches suggestion)))))

;; ============================================================================
;; Tests for Formatting Functions
;; ============================================================================

(deftest test-format-recipe
  (testing "Recipe formatting"
    (is (= "Grilled Cheese (5 min)"
           (meal/format-recipe {:name "Grilled Cheese" :time 5})))))

(deftest test-format-suggestion
  (testing "Format successful suggestion"
    (let [suggestion {:primary {:name "Main" :time 10}
                      :alternatives [{:name "Alt1" :time 15}
                                     {:name "Alt2" :time 20}]}
          formatted (meal/format-suggestion suggestion)]
      (is (= :success (:status formatted)))
      (is (str/includes? (:message formatted) "Main"))
      (is (str/includes? (:alternatives formatted) "Alt1"))
      (is (str/includes? (:alternatives formatted) "Alt2"))))

  (testing "Format no-matches"
    (let [formatted (meal/format-suggestion ::meal/no-matches)]
      (is (= :no-match (:status formatted)))
      (is (str/includes? (:message formatted) "Sorry"))))

  (testing "Format with no alternatives"
    (let [suggestion {:primary {:name "Only" :time 5}
                      :alternatives []}
          formatted (meal/format-suggestion suggestion)]
      (is (= :success (:status formatted)))
      (is (nil? (:alternatives formatted))))))

;; ============================================================================
;; Tests for Process Functions
;; ============================================================================

(deftest test-meal-suggestion-process
  (testing "Successful process flow"
    (let [client (create-mock-client
                   {"test input" {:ingredients ["bread" "cheese"]
                                  :hunger 3
                                  :time 10}})
          result (meal/meal-suggestion-process client "test input" test-recipes)]
      (is (= :success (:status result)))
      (is (str/includes? (:message result) "Simple Meal"))))

  (testing "Handles parsing errors gracefully"
    (let [client (reify meal/LLMClient
                   (parse-natural-language [_ _]
                     (throw (Exception. "API error"))))
          result (meal/meal-suggestion-process client "test" test-recipes)]
      (is (= :error (:status result)))
      (is (str/includes? (:message result) "API error"))))

  (testing "Handles no matches"
    (let [client (create-mock-client
                   {"test" {:ingredients ["unknown"]
                            :hunger 3
                            :time 5}})
          result (meal/meal-suggestion-process client "test" test-recipes)]
      (is (= :no-match (:status result))))))

;; ============================================================================
;; Tests for State Management
;; ============================================================================

(deftest test-history-management
  (testing "Records requests in history"
    (let [history (meal/create-history-atom)
          client (create-mock-client
                   {"input1" {:ingredients ["bread"] :hunger 2 :time 10}
                    "input2" {:ingredients ["eggs"] :hunger 4 :time 15}})
          result1 (meal/process-meal-request client "input1" history test-recipes)
          result2 (meal/process-meal-request client "input2" history test-recipes)]

      (is (= 2 (count @history)))
      (is (= "input1" (:input (first @history))))
      (is (= "input2" (:input (second @history))))
      (is (every? :timestamp @history))
      (is (= result1 (:result (first @history))))
      (is (= result2 (:result (second @history)))))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest test-end-to-end-flow
  (testing "Complete flow with valid input"
    (let [client (create-mock-client
                   {"I have bread, cheese and eggs. Very hungry, 15 minutes"
                    {:ingredients ["bread" "cheese" "eggs"]
                     :hunger 5
                     :time 15}})
          history (meal/create-history-atom)
          result (meal/process-meal-request
                   client
                   "I have bread, cheese and eggs. Very hungry, 15 minutes"
                   history
                   test-recipes)]

      (is (= :success (:status result)))
      (is (str/includes? (:message result) "Hearty Meal"))
      (is (= 1 (count @history)))))

  (testing "Complete flow with partial matches"
    (let [client (create-mock-client
                   {"Just bread, kinda hungry"
                    {:ingredients ["bread"]
                     :hunger 2
                     :time 30}})
          result (meal/meal-suggestion-process
                   client
                   "Just bread, kinda hungry"
                   test-recipes)]

      (is (= :success (:status result)))
      (is (str/includes? (:message result) "Quick Snack"))))

  (testing "Complete flow with no matches"
    (let [client (create-mock-client
                   {"I have caviar and truffles"
                    {:ingredients ["caviar" "truffles"]
                     :hunger 3
                     :time 30}})
          result (meal/meal-suggestion-process
                   client
                   "I have caviar and truffles"
                   test-recipes)]

      (is (= :no-match (:status result)))
      (is (str/includes? (:message result) "Sorry")))))

;; ============================================================================
;; Property-Based Tests
;; ============================================================================

(deftest test-properties
  (testing "Normalized ingredients are always lowercase sets"
    (doseq [input [["EGGS" "Rice" "bread"]
                   ["  cheese  " "CHEESE" "Cheese"]
                   ["a" "B" "c" "D" "e"]]]
      (let [result (meal/normalize-ingredients input)]
        (is (set? result))
        (is (every? #(= % (str/lower-case %)) result)))))

  (testing "Recipe matching is deterministic"
    (let [request {:ingredients #{"bread" "cheese"}
                   :hunger 3
                   :time 10}
          recipe {:name "Test"
                  :ingredients #{"bread"}
                  :hunger-range [2 4]
                  :time 8}]
      (dotimes [_ 10]
        (is (= (meal/recipe-matches? request recipe)
               (meal/recipe-matches? request recipe))))))

  (testing "Suggestion always returns valid structure"
    (doseq [request [{:ingredients #{"bread"} :hunger 1 :time 5}
                     {:ingredients #{"unknown"} :hunger 5 :time 100}
                     {:ingredients #{} :hunger 3 :time 30}]]
      (let [result (meal/suggest-meal request test-recipes)]
        (is (or (= ::meal/no-matches result)
                (and (contains? result :primary)
                     (contains? result :alternatives))))))))