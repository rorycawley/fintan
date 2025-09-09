(ns decision-agent.observability
  "Simplified observability suite for decision-agent system.

   This version focuses on core logging and basic metrics without complex dependencies."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [cheshire.core :as json])
  (:import [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]
           [java.util UUID]))

;;; =============================================================================
;;; Configuration and State Management
;;; =============================================================================

(def ^:private config-atom
  "Mutable configuration for observability settings"
  (atom {:logging {:level :info
                   :structured true}
         :metrics {:enabled true}
         :audit {:enabled true
                 :max-entries 10000}}))

(def ^:private audit-store
  "In-memory audit trail storage"
  (atom []))

(def ^:private metrics-store
  "Simple in-memory metrics storage"
  (atom {:decisions {:total 0
                     :successful 0
                     :failed 0
                     :no-options 0}
         :durations []
         :ingredients []
         :feasible-counts []}))

;;; =============================================================================
;;; Utility Functions
;;; =============================================================================

(defn- current-timestamp
  "Returns current timestamp in ISO-8601 format"
  []
  (-> (Instant/now)
      (.atZone (ZoneId/systemDefault))
      (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME)))

(defn- generate-correlation-id
  "Generates a unique correlation ID for request tracking"
  []
  (str (UUID/randomUUID)))

(defn- safe-json
  "Safely converts data to JSON, handling serialization errors"
  [data]
  (try
    (json/generate-string data)
    (catch Exception e
      (json/generate-string {:error "JSON serialization failed"
                             :type (str (type data))
                             :message (.getMessage e)}))))

;;; =============================================================================
;;; Configuration Management
;;; =============================================================================

(defn configure!
  "Updates observability configuration"
  [config-map]
  (swap! config-atom (partial merge-with merge config-map))
  (println "Observability configuration updated"))

(defn get-config
  "Returns current observability configuration"
  []
  @config-atom)

;;; =============================================================================
;;; Structured Logging
;;; =============================================================================

(defn- log-entry
  "Creates a structured log entry"
  [level message data correlation-id]
  (let [entry {:timestamp (current-timestamp)
               :level (name level)
               :message message
               :correlation-id correlation-id
               :service "decision-agent"
               :data data}]
    (if (get-in @config-atom [:logging :structured])
      (println (safe-json entry))
      (println (format "[%s] %s [%s] %s"
                       (:timestamp entry)
                       (str/upper-case (:level entry))
                       correlation-id
                       message)))))

(defn log-decision-start
  "Logs the start of a decision process"
  [state correlation-id]
  (when (get-in @config-atom [:logging :enabled] true)
    (log-entry :info "Decision process started"
               {:state state :event-type "decision-start"}
               correlation-id)))

(defn log-decision-step
  "Logs an intermediate step in the decision process"
  [step-name result correlation-id]
  (when (get-in @config-atom [:logging :enabled] true)
    (log-entry :debug "Decision step completed"
               {:step step-name :result result :event-type "decision-step"}
               correlation-id)))

(defn log-decision-complete
  "Logs the completion of a decision process"
  [decision duration-ms correlation-id]
  (when (get-in @config-atom [:logging :enabled] true)
    (log-entry :info "Decision process completed"
               {:decision decision :duration-ms duration-ms :event-type "decision-complete"}
               correlation-id)))

(defn log-decision-error
  "Logs decision process errors"
  [error state correlation-id]
  (when (get-in @config-atom [:logging :enabled] true)
    (log-entry :error "Decision process error"
               {:error (str error) :state state :event-type "decision-error"}
               correlation-id)))

;;; =============================================================================
;;; Simple Metrics Collection
;;; =============================================================================

(defn record-decision-metrics
  "Records metrics for a completed decision process"
  [state decision duration-ms feasible-count]
  (when (get-in @config-atom [:metrics :enabled])
    (swap! metrics-store
           (fn [metrics]
             (-> metrics
                 (update-in [:decisions :total] inc)
                 (update-in [:decisions
                             (cond
                               (= decision :no-feasible-recipes) :no-options
                               (map? decision) :successful
                               :else :failed)] inc)
                 (update :durations conj duration-ms)
                 (update :ingredients conj (count (:available-ingredients state)))
                 (update :feasible-counts conj feasible-count))))))

(defn get-metrics-summary
  "Returns current metrics summary"
  []
  (let [metrics @metrics-store
        durations (:durations metrics)
        total-decisions (get-in metrics [:decisions :total])]
    {:decisions (merge (:decisions metrics)
                       {:success-rate (if (pos? total-decisions)
                                        (double (/ (get-in metrics [:decisions :successful]) total-decisions))
                                        0.0)})
     :performance (if (seq durations)
                    {:mean-duration-ms (/ (reduce + durations) (count durations))
                     :min-duration-ms (apply min durations)
                     :max-duration-ms (apply max durations)
                     :total-samples (count durations)}
                    {:mean-duration-ms 0.0
                     :min-duration-ms 0.0
                     :max-duration-ms 0.0
                     :total-samples 0})
     :ingredients {:mean-count (if (seq (:ingredients metrics))
                                 (/ (reduce + (:ingredients metrics)) (count (:ingredients metrics)))
                                 0.0)
                   :max-count (if (seq (:ingredients metrics))
                                (apply max (:ingredients metrics))
                                0)}
     :feasibility {:mean-options (if (seq (:feasible-counts metrics))
                                   (/ (reduce + (:feasible-counts metrics)) (count (:feasible-counts metrics)))
                                   0.0)
                   :max-options (if (seq (:feasible-counts metrics))
                                  (apply max (:feasible-counts metrics))
                                  0)}}))

;;; =============================================================================
;;; Audit Trail Management
;;; =============================================================================

(defn- create-audit-entry
  "Creates an audit trail entry for a decision"
  [state decision duration-ms correlation-id feasible-recipes]
  {:id (generate-correlation-id)
   :correlation-id correlation-id
   :timestamp (current-timestamp)
   :event-type "decision-completed"
   :input {:available-ingredients (vec (:available-ingredients state))
           :hunger-level (:hunger-level state)
           :available-time (:available-time state)}
   :output {:decision (if (= decision :no-feasible-recipes)
                        {:type "no-options"
                         :reason "No feasible recipes found"}
                        {:type "recipe-selected"
                         :recipe-name (:name decision)
                         :cook-time (:cook-time decision)
                         :difficulty (:difficulty decision)
                         :priority-score (:priority-score decision)})
            :feasible-options-count (count feasible-recipes)}
   :performance {:duration-ms duration-ms}})

(defn record-audit-entry!
  "Records an audit entry for the decision process"
  [state decision duration-ms correlation-id feasible-recipes]
  (when (get-in @config-atom [:audit :enabled])
    (let [entry (create-audit-entry state decision duration-ms correlation-id feasible-recipes)
          max-entries (get-in @config-atom [:audit :max-entries])]
      (swap! audit-store
             (fn [store]
               (let [new-store (conj store entry)]
                 (if (> (count new-store) max-entries)
                   (vec (take-last max-entries new-store))
                   new-store)))))))

(defn get-audit-trail
  "Returns audit trail entries with optional filtering"
  ([] (get-audit-trail {}))
  ([{:keys [limit correlation-id]}]
   (let [entries @audit-store
         filtered (cond->> entries
                           correlation-id (filter #(= correlation-id (:correlation-id %)))
                           limit (take-last limit))]
     (vec filtered))))

(defn analyze-decision-patterns
  "Analyzes decision patterns from audit trail"
  []
  (let [entries (get-audit-trail)
        total-decisions (count entries)
        successful-decisions (count (filter #(= "recipe-selected"
                                                (get-in % [:output :decision :type])) entries))]
    {:summary {:total-decisions total-decisions
               :successful-decisions successful-decisions
               :success-rate (if (pos? total-decisions)
                               (/ successful-decisions total-decisions)
                               0.0)}
     :ingredient-patterns (->> entries
                               (map #(count (get-in % [:input :available-ingredients])))
                               frequencies
                               (into (sorted-map)))
     :hunger-patterns (->> entries
                           (map #(get-in % [:input :hunger-level]))
                           frequencies
                           (into (sorted-map)))}))

;;; =============================================================================
;;; Instrumentation Functions
;;; =============================================================================

(defn instrument-decision-process
  "Instruments a decision process with observability"
  [decision-fn state]
  (let [correlation-id (generate-correlation-id)
        start-time (System/currentTimeMillis)]

    (log-decision-start state correlation-id)

    (try
      (let [result (decision-fn state)
            duration-ms (- (System/currentTimeMillis) start-time)
            feasible-recipes (get-in result [:analysis :feasible-recipes] [])
            decision (get result :decision result)]

        (record-decision-metrics state decision duration-ms (count feasible-recipes))
        (record-audit-entry! state decision duration-ms correlation-id feasible-recipes)
        (log-decision-complete decision duration-ms correlation-id)

        (assoc result
          :observability {:correlation-id correlation-id
                          :duration-ms duration-ms
                          :timestamp (current-timestamp)}))

      (catch Exception e
        (log-decision-error e state correlation-id)
        (swap! metrics-store update-in [:decisions :failed] inc)
        (throw e)))))

;;; =============================================================================
;;; Reporting Functions
;;; =============================================================================

(defn export-audit-trail
  "Exports audit trail to file in JSON format"
  [filename & {:keys [correlation-id]}]
  (let [entries (get-audit-trail {:correlation-id correlation-id})]
    (with-open [writer (io/writer filename)]
      (.write writer (safe-json {:export-timestamp (current-timestamp)
                                 :entry-count (count entries)
                                 :entries entries})))
    {:exported-entries (count entries)
     :filename filename}))

(defn health-check
  "Returns system health status"
  []
  (let [metrics (get-metrics-summary)
        audit-count (count @audit-store)]
    {:status "healthy"
     :observability {:metrics-enabled (get-in @config-atom [:metrics :enabled])
                     :logging-enabled (get-in @config-atom [:logging :enabled])
                     :audit-enabled (get-in @config-atom [:audit :enabled])
                     :audit-entries audit-count
                     :total-decisions (get-in metrics [:decisions :total])}
     :timestamp (current-timestamp)}))

;; Example usage functions
(defn demo-observability
  "Demonstrates observability features"
  []
  (println "=== Observability Demo ===")
  (println "Current metrics:")
  (pprint/pprint (get-metrics-summary))
  (println "\nHealth check:")
  (pprint/pprint (health-check))
  (println "\nRecent audit entries:" (count (get-audit-trail {:limit 5}))))

;; Initialize with default configuration
(configure! {:logging {:enabled true :level :info}
             :metrics {:enabled true}
             :audit {:enabled true}})