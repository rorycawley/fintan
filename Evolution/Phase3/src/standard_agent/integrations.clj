(ns standard-agent.integrations
  "Integration adapters for various Clojure DI frameworks.

   Allows standard-agent to be used with:
   - Component
   - Integrant
   - Mount
   - Plain with-open
   - Functional style"
  (:require [standard-agent.core :as core]
            [standard-agent.providers :as providers]
            [standard-agent.registry :as registry])
  (:import [java.io Closeable]))

;; ============================================================================
;; Functional Integration - No Framework
;; ============================================================================

(defn create-agent-fn
      "Create an agent as a simple function.
       Returns a function that executes the agent."
      [logic provider-config]
      (let [provider (providers/create-provider provider-config)
            agent (core/create-agent logic {:provider provider})]
           (fn [input]
               (core/execute agent input))))

(defn create-agent-map
      "Create an agent as a map of functions.
       Provides full agent API as a map."
      [logic provider-config]
      (let [provider (providers/create-provider provider-config)
            agent (core/create-agent logic {:provider provider})]
           {:execute (partial core/execute agent)
            :swap-logic! (partial core/swap-logic! agent)
            :swap-provider! (partial core/swap-provider! agent)
            :get-state (partial core/get-state agent)
            :close (fn [] (when (instance? Closeable agent) (.close agent)))}))

;; ============================================================================
;; with-open Integration
;; ============================================================================

(defn agent-resource
      "Create an agent as a Closeable resource for with-open."
      [logic provider-config]
      (let [provider (providers/create-provider provider-config)
            agent (core/create-agent logic {:provider provider})]
           (core/closeable agent
                           (fn [a]
                               (when (instance? Closeable a) (.close a))
                               (when (instance? Closeable provider) (.close provider))))))

(defmacro with-agents
          "Execute body with multiple agents bound and auto-cleaned."
          [bindings & body]
          `(with-open ~(vec (mapcat (fn [[sym config]]
                                        [sym `(agent-resource ~(:logic config)
                                                              ~(:provider config))])
                                    (partition 2 bindings)))
                      ~@body))

;; ============================================================================
;; Component Integration (Requires com.stuartsierra/component)
;; ============================================================================

;; This is a conditional namespace - only loaded if Component is available
(defn create-component
      "Create a Component-compatible agent.

       Note: Requires [com.stuartsierra/component] dependency"
      [logic provider-config]
      (try
        (require 'com.stuartsierra.component)
        (let [Lifecycle (resolve 'com.stuartsierra.component/Lifecycle)]
             (reify
               ;; Implement Lifecycle protocol
               Lifecycle
               (start [this]
                      (let [provider (providers/create-provider provider-config)
                            agent (core/create-agent logic {:provider provider})]
                           (assoc this :agent agent :provider provider)))

               (stop [this]
                     (when-let [agent (:agent this)]
                               (when (instance? Closeable agent) (.close agent)))
                     (when-let [provider (:provider this)]
                               (when (instance? Closeable provider) (.close provider)))
                     (dissoc this :agent :provider))

               ;; Implement Agent protocol
               core/Agent
               (execute [this input]
                        (if-let [agent (:agent this)]
                                (core/execute agent input)
                                (throw (ex-info "Component not started" {}))))

               (swap-logic! [this new-logic]
                            (when-let [agent (:agent this)]
                                      (core/swap-logic! agent new-logic)))

               (swap-provider! [this new-provider]
                               (when-let [agent (:agent this)]
                                         (core/swap-provider! agent new-provider)))

               (get-state [this]
                          (when-let [agent (:agent this)]
                                    (core/get-state agent)))))

        (catch Exception _
          (throw (ex-info "Component integration requires com.stuartsierra/component"
                          {:add-dependency '[com.stuartsierra/component "1.1.0"]})))))

;; ============================================================================
;; Integrant Integration (Requires integrant/integrant)
;; ============================================================================

(defn integrant-init-key
      "Integrant init-key method for agents.

       Note: Requires [integrant/integrant] dependency"
      [_ {:keys [logic provider-config]}]
      (try
        (require 'integrant.core)
        (let [provider (providers/create-provider provider-config)
              agent (core/create-agent logic {:provider provider})]
             {:agent agent :provider provider})
        (catch Exception _
          (throw (ex-info "Integrant integration requires integrant/integrant"
                          {:add-dependency '[integrant/integrant "0.8.1"]})))))

(defn integrant-halt-key!
      "Integrant halt-key! method for agents."
      [_ {:keys [agent provider]}]
      (when agent
            (when (instance? Closeable agent) (.close agent)))
      (when provider
            (when (instance? Closeable provider) (.close provider))))

;; ============================================================================
;; Mount Integration (Requires mount/mount)
;; ============================================================================

(defn create-mount-state
      "Create a Mount state definition for an agent.

       Note: Requires [mount/mount] dependency

       Usage:
       (mount/defstate my-agent
         :start (create-mount-state logic provider-config)
         :stop (.close my-agent))"
      [logic provider-config]
      (try
        (require 'mount.core)
        (let [provider (providers/create-provider provider-config)
              agent (core/create-agent logic {:provider provider})]
             agent)
        (catch Exception _
          (throw (ex-info "Mount integration requires mount/mount"
                          {:add-dependency '[mount/mount "0.1.17"]})))))

;; ============================================================================
;; Unified Integration API
;; ============================================================================

(defmulti create-integration
          "Create an agent with specified integration style.

           Styles:
           - :functional - Simple function
           - :map - Map of functions
           - :resource - Closeable resource
           - :component - Component compatible
           - :integrant - Integrant compatible
           - :mount - Mount compatible"
          (fn [style _logic _provider-config] style))

(defmethod create-integration :functional
           [_ logic provider-config]
           (create-agent-fn logic provider-config))

(defmethod create-integration :map
           [_ logic provider-config]
           (create-agent-map logic provider-config))

(defmethod create-integration :resource
           [_ logic provider-config]
           (agent-resource logic provider-config))

(defmethod create-integration :component
           [_ logic provider-config]
           (create-component logic provider-config))

(defmethod create-integration :integrant
           [_ logic provider-config]
           {:init-key (partial integrant-init-key nil)
            :halt-key! integrant-halt-key!
            :config {:logic logic :provider-config provider-config}})

(defmethod create-integration :mount
           [_ logic provider-config]
           (create-mount-state logic provider-config))

(defmethod create-integration :default
           [style _ _]
           (throw (ex-info "Unknown integration style"
                           {:style style
                            :available [:functional :map :resource
                                        :component :integrant :mount]})))

;; ============================================================================
;; System Integration Patterns
;; ============================================================================

(defn create-system
      "Create a complete system with multiple agents.

       Config format:
       {:agents {:agent-1 {:logic ... :provider ...}}
        :style :component  ; or :integrant, :mount, etc.
        :registry true}"
      [{:keys [agents style registry?] :or {style :map registry? true}}]
      (let [created-agents (into {}
                                 (map (fn [[id config]]
                                          [id (create-integration style
                                                                  (:logic config)
                                                                  (:provider config))])
                                      agents))]
           (cond-> {:agents created-agents}
                   registry? (assoc :registry (registry/create-multi-agent-system agents)))))

;; ============================================================================
;; Testing Support
;; ============================================================================

(defn test-agent
      "Create a test agent with mock provider."
      [logic responses]
      (create-agent-fn logic {:provider :mock :responses responses}))

(defn test-system
      "Create a test system with mock agents."
      [agents-config]
      (create-system (update-vals agents-config
                                  (fn [config]
                                      (assoc config :provider {:provider :mock
                                                               :responses ["test"]})))))

;; ============================================================================
;; Migration Helpers
;; ============================================================================

(defn from-component
      "Migrate from Component to standard-agent.

       Takes a Component system and extracts agents."
      [component-system agent-keys]
      (into {}
            (map (fn [k]
                     [k (get component-system k)])
                 agent-keys)))

(defn to-component
      "Convert standard-agent system to Component system.

       Note: Requires Component dependency"
      [agents]
      (try
        (require 'com.stuartsierra.component)
        (let [system-map (resolve 'com.stuartsierra.component/system-map)]
             (apply system-map (mapcat (fn [[k v]] [k v]) agents)))
        (catch Exception e
          (throw (ex-info "Component required for conversion" {:error e})))))

;; ============================================================================
;; Usage Examples
;; ============================================================================

(comment
  ;; Functional style - simplest
  (def my-agent (create-agent-fn my-logic {:provider :openai
                                           :api-key "..."}))
  (my-agent "process this")

  ;; Map style - more control
  (def agent-map (create-agent-map my-logic {:provider :claude
                                             :api-key "..."}))
  ((:execute agent-map) "process this")
  ((:swap-provider! agent-map) new-provider)

  ;; Resource style - with-open
  (with-open [agent (agent-resource my-logic {:provider :gemini
                                              :api-key "..."})]
             (core/execute @agent "process this"))

  ;; Multiple agents
  (with-agents [meal-agent {:logic meal-logic
                            :provider {:provider :openai}}
                recipe-agent {:logic recipe-logic
                              :provider {:provider :claude}}]
               (core/execute @meal-agent "I'm hungry")
               (core/execute @recipe-agent "Make pasta"))

  ;; Component style
  (def component-agent (create-component my-logic {:provider :openai}))
  ;; Use with Component system...

  ;; System with registry
  (def system (create-system
                {:agents {:legal {:logic legal-logic
                                  :provider {:provider :claude}}
                          :creative {:logic creative-logic
                                     :provider {:provider :openai}}}
                 :style :map
                 :registry? true})))