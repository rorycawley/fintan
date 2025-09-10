(ns decision-agent.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [decision-agent.core :as sut]))

(defn recipe-by-name [n]
  (first (filter #(= n (:name %)) sut/recipes)))

(deftest perceive-basic
  (let [s (sut/perceive ["bread" "cheese" "eggs"] 5 10)]
    (is (= #{:ingredients :hunger :time} (set (keys s))))
    (is (= #{"bread" "cheese" "eggs"} (:ingredients s)))
    (is (= 5 (:hunger s)))
    (is (= 10 (:time s)))))

(deftest match-recipe?-ingredients-and-time
  (let [state (sut/perceive ["bread" "cheese" "eggs"] 5 10)
        omelette (recipe-by-name "Cheese Omelette Sandwich")
        fried-rice (recipe-by-name "Egg Fried Rice")
        bread-jam (recipe-by-name "Bread and Jam")]
    (testing "passes when all ingredients available and within time"
      (is (true? (sut/match-recipe? state omelette))))
    (testing "fails when time too long"
      (is (false? (sut/match-recipe? state fried-rice)))) ; needs 15, we have 10
    (testing "fails when missing ingredient"
      (is (false? (sut/match-recipe? (sut/perceive ["bread" "eggs"] 5 10)
                                     omelette))))
    (testing "passes simpler recipe within time"
      (is (true? (sut/match-recipe? state bread-jam))))))

(deftest reason-chooses-closest-filling
  (testing "picks omelette at hunger=5"
    (is (= "Cheese Omelette Sandwich"
           (sut/reason (sut/perceive ["bread" "cheese" "eggs"] 5 10)))))
  (testing "with both bread meals available, hunger=2 prefers Bread and Jam"
    (let [state (sut/perceive ["bread" "cheese" "eggs" "jam"] 2 10)]
      (is (= "Bread and Jam" (sut/reason state)))))
  (testing "no matches -> apology"
    (is (= "Sorry, no suitable meal."
           (sut/reason (sut/perceive ["water"] 10 1))))))

(deftest act-prints-suggestion
  (let [out (with-out-str
              (sut/act (sut/perceive ["bread" "cheese" "eggs"] 5 10)))]
    (is (re-find #"👩‍🍳 Suggested Meal: Cheese Omelette Sandwich" out))))
