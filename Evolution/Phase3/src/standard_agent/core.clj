(ns standard-agent.core
  "Standard Agent - A pluggable, in-process AI agent library for Clojure.

   Like DuckDB or SQLite, this library runs entirely in your process.
   Users provide the business logic, we provide the infrastructure.

   Core abstractions:
   - AgentLogic: User-provided business logic protocol
   - LLMProvider: Abstraction over different LLM services
   - Agent: Runtime entity combining logic + provider
   - Registry: Multi-agent management"
  (:require [clojure.spec.alpha :as s])
  (:import [java.io Closeable]
           [java.util.concurrent.locks ReentrantReadWriteLock]))

;; ============================================================================
;; Core Protocols - The Contract
;; ============================================================================

(defprotocol AgentLogic
             "Protocol for user-provided business logic.
              Users implement this to define their agent's behavior."

             (prepare-prompt [this context input]
                             "Transform user input into LLM prompt.
                              Returns {:prompt string :options map}")

             (process-response [this context llm-response]
                               "Process LLM response into domain-specific result.
                                Returns user-defined data structure.")

             (validate-input [this input]
                             "Optional: Validate input before processing.
                              Returns {:valid? bool :error string}")

             (get-capabilities [this]
                               "Optional: Declare agent capabilities for discovery.
                                Returns {:name string :description string :version string}"))

(defprotocol LLMProvider
             "Abstraction over LLM services.
              Minimal interface that all providers must support."

             (invoke-llm [this prompt options]
                         "Call the LLM with prompt and provider-specific options.
                          Returns {:response string :usage map :metadata map}")

             (get-provider-info [this]
                                "Get provider information.
                                 Returns {:name string :models [...] :capabilities #{...}}"))

(defprotocol Agent
             "A running agent instance."

             (execute [this input]
                      "Execute the agent with given input.")

             (swap-logic! [this new-logic]
                          "Hot-swap the business logic.")

             (swap-provider! [this new-provider]
                             "Hot-swap the LLM provider.")

             (get-state [this]
                        "Get current agent state."))

;; ============================================================================
;; Agent Implementation - The Engine
;; ============================================================================

(defrecord StandardAgent [logic-atom provider-atom context-atom stats-atom lock]
           Agent
           (execute [this input]
                    (let [logic @logic-atom
                          provider @provider-atom
                          context @context-atom]

                         ;; Track execution
                         (swap! stats-atom update :executions inc)

                         ;; Validate input if logic supports it
                         (when (satisfies? AgentLogic logic)
                               (when-let [validation (validate-input logic input)]
                                         (when-not (:valid? validation)
                                                   (throw (ex-info "Invalid input" validation)))))

                         ;; Prepare prompt using user's logic
                         (let [{:keys [prompt options]} (prepare-prompt logic context input)

                               ;; Invoke LLM
                               start-time (System/currentTimeMillis)
                               llm-result (try
                                            (invoke-llm provider prompt options)
                                            (catch Exception e
                                              (swap! stats-atom update :errors inc)
                                              (throw e)))

                               ;; Track timing
                               _ (swap! stats-atom update :total-time + (- (System/currentTimeMillis) start-time))

                               ;; Process response using user's logic
                               result (process-response logic context (:response llm-result))]

                              ;; Update context if needed
                              (when-let [new-context (:context result)]
                                        (reset! context-atom new-context))

                              ;; Return processed result
                              (dissoc result :context))))

           (swap-logic! [this new-logic]
                        (.writeLock (.writeLock lock))
                        (try
                          (reset! logic-atom new-logic)
                          (swap! stats-atom assoc :logic-swapped-at (java.util.Date.))
                          finally
                          (.unlock (.writeLock lock)))))

           (swap-provider! [this new-provider]
                           (.writeLock (.writeLock lock))
                           (try
                             (reset! provider-atom new-provider)
                             (swap! stats-atom assoc :provider-swapped-at (java.util.Date.))
                             finally
                             (.unlock (.writeLock lock))))

           (get-state [this]
                      {:logic (when-let [logic @logic-atom]
                                        (when (satisfies? AgentLogic logic)
                                              (get-capabilities logic)))
                       :provider (when-let [provider @provider-atom]
                                           (get-provider-info provider))
                       :context @context-atom
                       :stats @stats-atom})

           Closeable
           (close [this]
                  ;; Clean up any resources
                  (when (instance? Closeable @provider-atom)
                        (.close @provider-atom))))

;; ============================================================================
;; Agent Creation - Factory Functions
;; ============================================================================

(defn create-agent
      "Create a new agent with given logic and configuration.

       Options:
       - :provider - LLM provider instance
       - :context - Initial context map
       - :middleware - Vector of middleware functions"
      ([logic] (create-agent logic {}))
      ([logic {:keys [provider context middleware] :as options}]
       (let [agent (->StandardAgent
                     (atom logic)
                     (atom provider)
                     (atom (or context {}))
                     (atom {:executions 0
                            :errors 0
                            :total-time 0
                            :created-at (java.util.Date.)})
                     (ReentrantReadWriteLock.))]

            ;; Apply middleware if provided
            (if middleware
              (reduce (fn [a mw] (mw a)) agent middleware)
              agent))))

;; ============================================================================
;; Middleware System - Cross-cutting Concerns
;; ============================================================================

(defn with-retry
      "Middleware to add retry logic."
      [max-retries delay-ms]
      (fn [agent]
          (reify Agent
                 (execute [_ input]
                          (loop [attempts 0]
                                (if (< attempts max-retries)
                                  (try
                                    (execute agent input)
                                    (catch Exception e
                                      (Thread/sleep delay-ms)
                                      (recur (inc attempts))))
                                  (execute agent input))))

                 (swap-logic! [_ new-logic] (swap-logic! agent new-logic))
                 (swap-provider! [_ new-provider] (swap-provider! agent new-provider))
                 (get-state [_] (get-state agent)))))

(defn with-timeout
      "Middleware to add timeout."
      [timeout-ms]
      (fn [agent]
          (reify Agent
                 (execute [_ input]
                          (let [future-result (future (execute agent input))
                                result (deref future-result timeout-ms ::timeout)]
                               (if (= result ::timeout)
                                 (do
                                   (future-cancel future-result)
                                   (throw (ex-info "Agent execution timeout" {:timeout-ms timeout-ms})))
                                 result)))

                 (swap-logic! [_ new-logic] (swap-logic! agent new-logic))
                 (swap-provider! [_ new-provider] (swap-provider! agent new-provider))
                 (get-state [_] (get-state agent)))))

(defn with-logging
      "Middleware to add logging."
      [log-fn]
      (fn [agent]
          (reify Agent
                 (execute [_ input]
                          (log-fn {:event :execute-start :input input})
                          (try
                            (let [result (execute agent input)]
                                 (log-fn {:event :execute-success :result result})
                                 result)
                            (catch Exception e
                              (log-fn {:event :execute-error :error e})
                              (throw e))))

                 (swap-logic! [_ new-logic]
                              (log-fn {:event :swap-logic})
                              (swap-logic! agent new-logic))

                 (swap-provider! [_ new-provider]
                                 (log-fn {:event :swap-provider})
                                 (swap-provider! agent new-provider))

                 (get-state [_] (get-state agent)))))

(defn with-caching
      "Middleware to add simple caching."
      [cache-atom hash-fn]
      (fn [agent]
          (reify Agent
                 (execute [_ input]
                          (let [cache-key (hash-fn input)]
                               (if-let [cached (get @cache-atom cache-key)]
                                       (:result cached)
                                       (let [result (execute agent input)]
                                            (swap! cache-atom assoc cache-key {:result result
                                                                               :timestamp (System/currentTimeMillis)})
                                            result))))

                 (swap-logic! [_ new-logic]
                              (reset! cache-atom {})  ; Clear cache on logic change
                              (swap-logic! agent new-logic))

                 (swap-provider! [_ new-provider]
                                 (reset! cache-atom {})  ; Clear cache on provider change
                                 (swap-provider! agent new-provider))

                 (get-state [_] (get-state agent)))))

;; ============================================================================
;; Resource Management - with-open Support
;; ============================================================================

(defn closeable
      "Create a Closeable wrapper for use with with-open."
      ([resource] (closeable resource (constantly nil)))
      ([resource close-fn]
       (reify
         Closeable
         (close [_] (close-fn resource))
         clojure.lang.IDeref
         (deref [_] resource))))

(defn with-agent
      "Execute function with an agent that's automatically cleaned up."
      [logic provider-config f]
      (with-open [agent (closeable
                          (create-agent logic provider-config)
                          (fn [a] (when (instance? Closeable a) (.close a))))]
                 (f @agent)))

;; ============================================================================
;; Agent Builder - Fluent API
;; ============================================================================

(defn agent-builder
      "Start building an agent with a fluent API."
      []
      {:logic nil
       :provider nil
       :context {}
       :middleware []})

(defn with-logic [builder logic]
      (assoc builder :logic logic))

(defn with-provider [builder provider]
      (assoc builder :provider provider))

(defn with-context [builder context]
      (assoc builder :context context))

(defn add-middleware [builder mw]
      (update builder :middleware conj mw))

(defn build
      "Build the agent from the builder configuration."
      [{:keys [logic provider context middleware]}]
      (when-not logic
                (throw (ex-info "Agent requires logic" {})))
      (when-not provider
                (throw (ex-info "Agent requires provider" {})))
      (create-agent logic {:provider provider
                           :context context
                           :middleware middleware}))

;; ============================================================================
;; Testing Support - Mock Providers
;; ============================================================================

(defrecord MockProvider [responses-atom]
           LLMProvider
           (invoke-llm [_ prompt options]
                       (let [responses @responses-atom
                             response (if (sequential? responses)
                                        (first responses)
                                        responses)]
                            (when (sequential? responses)
                                  (swap! responses-atom rest))
                            {:response response
                             :usage {:tokens 0}
                             :metadata {:mock true}}))

           (get-provider-info [_]
                              {:name "mock"
                               :models ["mock-model"]
                               :capabilities #{:test}}))

(defn mock-provider
      "Create a mock provider for testing."
      [responses]
      (->MockProvider (atom responses)))

;; ============================================================================
;; Specs for Validation
;; ============================================================================

(s/def ::prompt string?)
(s/def ::options map?)
(s/def ::prepare-prompt-result (s/keys :req-un [::prompt] :opt-un [::options]))

(s/def ::response string?)
(s/def ::usage map?)
(s/def ::metadata map?)
(s/def ::llm-result (s/keys :req-un [::response] :opt-un [::usage ::metadata]))

(s/def ::agent-logic #(satisfies? AgentLogic %))
(s/def ::llm-provider #(satisfies? LLMProvider %))
(s/def ::context map?)
(s/def ::middleware (s/coll-of fn?))

(s/def ::agent-options (s/keys :opt-un [::provider ::context ::middleware]))