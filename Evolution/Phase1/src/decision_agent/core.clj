(ns decision-agent.core)

;; --- data ----------------------------------------------------------

(def recipes
  [{:name        "Egg Fried Rice"
    :ingredients #{"rice" "eggs"}
    :time        15
    :filling     4}
   {:name        "Cheese Omelette Sandwich"
    :ingredients #{"bread" "cheese" "eggs"}
    :time        10
    :filling     5}
   {:name        "Bread and Jam"
    :ingredients #{"bread" "jam"}
    :time        5
    :filling     2}])

;; --- functions -----------------------------------------------------

(defn perceive [ingredients hunger time]
  {:ingredients (set ingredients)
   :hunger      hunger
   :time        time})

(defn match-recipe? [{:keys [ingredients time]} recipe]
  (and (<= (:time recipe) time)
       (every? ingredients (:ingredients recipe))))

(defn reason [{:keys [hunger] :as state}]
  (let [matches (filter (partial match-recipe? state) recipes)]
    (if (seq matches)
      (:name (first (sort-by #(Math/abs (- (:filling %) hunger)) matches)))
      "Sorry, no suitable meal.")))

(defn act [state]
  (println (str "👩‍🍳 Suggested Meal: " (reason state))))

;; --- run -----------------------------------------------------------

(defn -main [& _]
  (let [state (perceive ["bread" "cheese" "eggs"] 5 10)]
    (act state)))

(comment
  (def state (perceive ["bread" "cheese" "eggs"] 5 10))
  (act state)
  )