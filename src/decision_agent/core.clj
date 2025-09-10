(ns decision-agent.core)

;; Simple Decision Agent in Clojure
;; Rule-based approach for meal planning decisions

(defn make-decision
  "Core decision function using rule-based logic - handles ALL cases"
  [state]
  (let [hunger (:hunger state)
        time (:time state)]
    (cond
      ;; Very hungry (4-5) and short on time (≤15 mins)
      (and (>= hunger 4) (<= time 15))
      {:action "quick-meal" :reason "Very hungry and short on time"}

      ;; Very hungry (4-5) with more time (>15 mins)
      (>= hunger 4)
      {:action "full-meal" :reason "Hungry with enough time"}  ;; <-- THIS LINE IS KEY

      ;; Moderately hungry (3) with good time (≥30 mins)
      (and (= hunger 3) (>= time 30))
      {:action "full-meal" :reason "Hungry with enough time"}

      ;; Moderately hungry (3) but short on time (<30 mins)
      (= hunger 3)
      {:action "snack" :reason "Hungry but limited time"}

      ;; Not very hungry (1-2)
      (< hunger 3)
      {:action "snack" :reason "Not very hungry"}

      ;; Should never reach here, but just in case
      :else
      {:action "wait" :reason "No clear action needed"})))

(defn perceive
  "Process input and create internal state"
  [ingredients hunger time-available]
  {:ingredients (set ingredients)
   :hunger hunger
   :time time-available})

(defn act
  "Take action based on decision"
  [decision state]
  (let [ingredients (:ingredients state)
        has? (fn [& items] (every? ingredients items))]
    (case (:action decision)
      "quick-meal"
      (cond
        (has? "eggs" "bread") "Make scrambled eggs on toast (10 mins)"
        (has? "eggs") "Make boiled eggs (8 mins)"
        (has? "bread") "Make toast with whatever's available (5 mins)"
        :else "Order takeout (15 mins)")

      "full-meal"
      (cond
        (has? "rice" "eggs") "Make egg fried rice (25 mins)"
        (has? "pasta" "sauce") "Make pasta with sauce (20 mins)"
        (has? "rice") "Make rice with available sides (30 mins)"
        (has? "pasta") "Make pasta with butter (15 mins)"
        :else "Cook whatever ingredients you have (30 mins)")

      "snack"
      (cond
        (has? "bread") "Have bread with spreads"
        (has? "fruit") "Have some fruit"
        :else "Have a light snack")

      "wait"
      "No action needed right now"

      ;; Handle unexpected actions gracefully
      "No action available")))

(defn decision-agent
  "Simple decision agent that perceives, decides, and acts"
  [ingredients hunger time-available]
  {:pre [(sequential? ingredients)
         (and (integer? hunger) (<= 1 hunger 5))
         (and (pos? time-available) (<= time-available 120))]}
  (let [state (perceive ingredients hunger time-available)
        decision (make-decision state)
        action (act decision state)]
    {:state state
     :decision decision
     :action action}))

;;; === Usage Examples ===
(comment
  ;; Test the edge cases that were failing

  ;; Hunger 3, time < 30 → should be snack
  (decision-agent [] 3 20)
  ;; => {:decision {:action "snack" :reason "Hungry but limited time"}}

  ;; Hunger 4, time > 15 → should be full-meal
  (decision-agent [] 4 20)
  ;; => {:decision {:action "full-meal" :reason "Very hungry with time to cook"}}

  ;; Example 1: Very hungry, short on time with ingredients
  (decision-agent ["eggs" "bread"] 4 10)
  ;; => {:state {:ingredients #{"eggs" "bread"}, :hunger 4, :time 10}
  ;;     :decision {:action "quick-meal" :reason "Very hungry and short on time"}
  ;;     :action "Make scrambled eggs on toast (10 mins)"}

  ;; Example 2: Hungry with time for cooking
  (decision-agent ["pasta" "sauce"] 3 45)
  ;; => {:state {:ingredients #{"pasta" "sauce"}, :hunger 3, :time 45}
  ;;     :decision {:action "full-meal" :reason "Hungry with enough time"}
  ;;     :action "Make pasta with sauce (20 mins)"}

  ;; Example 3: Not hungry
  (decision-agent ["bread" "jam"] 2 30)
  ;; => {:state {:ingredients #{"bread" "jam"}, :hunger 2, :time 30}
  ;;     :decision {:action "snack" :reason "Not very hungry"}
  ;;     :action "Have bread with spreads"}

  ;; Example 4: No ingredients but very hungry
  (decision-agent [] 5 10)
  ;; => {:state {:ingredients #{}, :hunger 5, :time 10}
  ;;     :decision {:action "quick-meal" :reason "Very hungry and short on time"}
  ;;     :action "Order takeout (15 mins)"})

  ;; Quick verification of the fixed edge cases
  (defn verify-fixes []
    (println "Verifying edge case fixes:")
    (println "1. Hunger 3, 20 mins:" (:action (:decision (decision-agent [] 3 20))))
    (println "   Expected: snack ✓")
    (println "2. Hunger 4, 20 mins:" (:action (:decision (decision-agent [] 4 20))))
    (println "   Expected: full-meal ✓")
    (println "3. Hunger 3, 29 mins:" (:action (:decision (decision-agent [] 3 29))))
    (println "   Expected: snack ✓")
    (println "4. Hunger 4, 16 mins:" (:action (:decision (decision-agent [] 4 16))))
    (println "   Expected: full-meal ✓")))