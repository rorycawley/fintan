(ns meal-agent.main
  "Main entry point with reloaded workflow support.

   This namespace demonstrates how to implement a reloaded workflow
   using the with-open pattern instead of Component. The system can
   be started, stopped, and reloaded without accumulating resources.

   Key features:
   - REPL-friendly development workflow
   - Automatic resource cleanup
   - No dependency on Component framework
   - Thread-safe system management"
  (:require [meal-agent.core :as core]
            [meal-agent.adapters :as adapters]
            [clojure.string :as str]))

;; ============================================================================
;; System State Management
;; ============================================================================

;; Atom to hold the running system (if any)
;; Using a future allows us to run the system in the background
;; and cancel it when needed
(defonce ^:private system-state (atom nil))

;; Atom to hold system configuration
(defonce ^:private system-config
         (atom {:api-key (System/getenv "OPENAI_API_KEY")
                :use-stub false}))

;; ============================================================================
;; Reloaded Workflow Functions
;; ============================================================================

(defn config!
  "Update system configuration.

   Use this to change configuration without restarting:
   (config! :use-stub true)  ; Switch to stub mode
   (config! :api-key \"...\") ; Set API key"
  [k v]
  (swap! system-config assoc k v)
  (println (str "Configuration updated: " k " = " v)))

(defn start!
  "Start the meal agent system.

   This function starts the system in a background thread using
   a future. The system can be stopped by calling stop!"
  []
  (if @system-state
    (println "System is already running. Call (stop!) first.")
    (do
      (println "Starting meal agent system...")
      ;; Create a promise to signal when system is ready
      (let [ready-promise (promise)
            ;; Start system in a future (background thread)
            system-future
            (future
              ;; Use with-open to manage resources
              (adapters/with-meal-system @system-config
                                         (fn [{:keys [parser] :as system}]
                                           ;; Signal that system is ready
                                           (deliver ready-promise system)

                                           ;; Keep the with-open scope alive
                                           ;; This thread will block here until interrupted
                                           (try
                                             (println "System started successfully")
                                             (println "Parser type:" (type parser))
                                             ;; Block indefinitely until interrupted
                                             (Thread/sleep Long/MAX_VALUE)
                                             (catch InterruptedException e
                                               (println "System interrupted, shutting down..."))))))]

        ;; Wait for system to be ready (with timeout)
        (let [system (deref ready-promise 5000 nil)]
          (if system
            (do
              ;; Store the future and system info
              (reset! system-state {:future system-future
                                    :system system
                                    :started-at (java.util.Date.)})
              (println "✅ System is ready for requests")
              (println "Use (process! \"your request\") to make requests")
              (println "Use (stop!) to shut down")
              :started)
            (do
              ;; Timeout - cancel the future
              (future-cancel system-future)
              (println "❌ Failed to start system (timeout)")
              :failed)))))))

  (defn stop!
    "Stop the meal agent system.

     Cancels the background thread and ensures all resources
     are cleaned up via with-open's automatic cleanup."
    []
    (if-let [state @system-state]
      (do
        (println "Stopping meal agent system...")
        ;; Cancel the future (interrupts the thread)
        (future-cancel (:future state))
        ;; Wait a bit for cleanup
        (Thread/sleep 100)
        ;; Clear the state
        (reset! system-state nil)
        (println "✅ System stopped")
        :stopped)
      (do
        (println "System is not running")
        :not-running)))

  (defn restart!
    "Restart the system (stop then start).

     Useful during development when code or configuration changes."
    []
    (stop!)
    (Thread/sleep 500) ; Give time for cleanup
    (start!))

  (defn reload!
    "Reload code and restart the system.

     This implements the reloaded workflow:
     1. Stop the system (releasing all resources)
     2. Reload the code from disk
     3. Start the system again"
    []
    (println "Reloading meal agent...")
    (stop!)
    ;; In a real implementation, you'd use tools.namespace here
    ;; (require '[clojure.tools.namespace.repl :as repl])
    ;; (repl/refresh)
    (require 'meal-agent.core :reload)
    (require 'meal-agent.adapters :reload)
    (require 'meal-agent.main :reload)
    (start!))

  ;; ============================================================================
  ;; Interactive Functions (for REPL use)
  ;; ============================================================================

  (defn process!
    "Process a meal request using the running system.

     This function can be called from the REPL to test the system:
     (process! \"I have eggs and bread, very hungry, 10 minutes\")"
    [user-input]
    (if-let [state @system-state]
      (let [parser (-> state :system :parser)]
        (core/process-meal-request parser user-input core/recipes))
      {:status :error
       :message "System is not running. Call (start!) first."}))

  (defn status
    "Check the status of the system.

     Returns information about whether the system is running,
     when it was started, and current configuration."
    []
    (if-let [state @system-state]
      {:status :running
       :started-at (:started-at state)
       :config @system-config
       :parser-type (type (-> state :system :parser))}
      {:status :stopped
       :config @system-config}))

  ;; ============================================================================
  ;; Example Scenarios
  ;; ============================================================================

  (defn run-examples!
    "Run example scenarios using the running system.

     This demonstrates how to use the system for various requests."
    []
    (if-let [state @system-state]
      (let [parser (-> state :system :parser)]
        (println "\n=== Running Example Scenarios ===\n")

        ;; Example 1
        (println "Example 1: Quick meal with limited ingredients")
        (let [result (core/process-meal-request
                       parser
                       "I have bread and cheese, kinda hungry"
                       core/recipes)]
          (println "→" (:message result))
          (when-let [alts (:alternatives result)]
            (println "  " alts)))

        ;; Example 2
        (println "\nExample 2: Very hungry with more ingredients")
        (let [result (core/process-meal-request
                       parser
                       "I have eggs and rice. I'm very hungry but in a rush."
                       core/recipes)]
          (println "→" (:message result))
          (when-let [alts (:alternatives result)]
            (println "  " alts)))

        ;; Example 3
        (println "\nExample 3: Full pantry, moderate hunger")
        (let [result (core/process-meal-request
                       parser
                       "I have bread, eggs, cheese, and rice. Hungry, 15 minutes available."
                       core/recipes)]
          (println "→" (:message result))
          (when-let [alts (:alternatives result)]
            (println "  " alts)))

        ;; Example 4
        (println "\nExample 4: No matching recipes")
        (let [result (core/process-meal-request
                       parser
                       "I only have caviar and truffles"
                       core/recipes)]
          (println "→" (:message result)))

        (println "\n✅ Examples completed"))
      (println "System is not running. Call (start!) first.")))

  ;; ============================================================================
  ;; Main Entry Points
  ;; ============================================================================

  (defn run-interactive
    "Run the system in interactive mode (blocking).

     This is useful for command-line usage where you want
     the system to run until explicitly quit."
    []
    (adapters/run-system @system-config))

  (defn run-batch
    "Run the system in batch mode with predefined requests."
    [requests]
    (adapters/run-batch-requests @system-config requests))

  (defn -main
    "Main entry point for the application.

     Can be run in different modes:
     - No args: Interactive mode
     - 'batch': Batch mode with example requests
     - 'repl': Start system for REPL use"
    [& args]
    (let [mode (first args)]
      (case mode
        "batch"
        (run-batch ["I have eggs and bread, hungry"
                    "Rice available, very hungry, 20 minutes"
                    "Bread and cheese, not too hungry, quick meal"])

        "repl"
        (do
          (println "Starting system for REPL use...")
          (start!)
          (println "\nSystem is running. Available commands:")
          (println "  (process! \"your request\") - Process a meal request")
          (println "  (run-examples!) - Run example scenarios")
          (println "  (status) - Check system status")
          (println "  (restart!) - Restart the system")
          (println "  (reload!) - Reload code and restart")
          (println "  (stop!) - Stop the system"))

        ;; Default: interactive mode
        (run-interactive))))

  ;; ============================================================================
  ;; REPL Development Helpers
  ;; ============================================================================

  (comment
    ;; Start the system for REPL development
    (start!)

    ;; Check system status
    (status)

    ;; Process a request
    (process! "I have eggs and bread, very hungry")

    ;; Run examples
    (run-examples!)

    ;; Switch to stub mode (for offline development)
    (config! :use-stub true)
    (restart!)

    ;; Switch back to OpenAI
    (config! :use-stub false)
    (restart!)

    ;; Stop the system
    (stop!)

    ;; Reload everything
    (reload!)

    ;; Run in batch mode
    (run-batch ["eggs and rice, hungry"
                "bread and cheese, quick"])

    )