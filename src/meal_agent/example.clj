(ns meal-agent.example
  (:require [meal-agent.core :as core]))

(defn add [x y]
  (+ x y))

(defn -main
  []
  (println "This is main" core/config))