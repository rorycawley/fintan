(ns standard-agent.registry
  "Registry for managing multiple agents in a system.

   Provides:
   - Agent registration and discovery
   - Routing based on capabilities
   - Lifecycle management for agent groups
   - Agent pool management"
  (:require [standard-agent.core :as core]
            [clojure.string :as str])
  (:import [java.io Closeable]
           [java.util.concurrent ConcurrentHashMap]))

;; ============================================================================
;; Registry Protocol
;; ============================================================================

(defprotocol AgentRegistry
             "Protocol for agent registries."

             (register-agent! [this id agent]
                              "Register an agent with given ID.")

             (unregister-agent! [this id]
                                "Remove an agent from the registry.")

             (get-agent [this id]
                        "Get agent by ID.")

             (find-agents [this criteria]
                          "Find agents matching criteria.")

             (list-agents [this]
                          "List all registered agents.")

             (route-to-agent [this request]
                             "Route request to appropriate agent.")

             (shutdown-all [this]
                           "Shutdown all agents in registry."))

;; ============================================================================
;; Standard Registry Implementation
;; ============================================================================

(defrecord StandardRegistry [agents-map metadata-map router-fn]
           AgentRegistry
           (register-agent! [_ id agent]
                            (.put agents-map id agent)
                            (when (satisfies? core/AgentLogic agent)
                                  (when-let [capabilities (core/get-capabilities agent)]
                                            (.put metadata-map id capabilities)))
                            id)

           (unregister-agent! [_ id]
                              (when-let [agent (.remove agents-map id)]
                                        (.remove metadata-map id)
                                        (when (instance? Closeable agent)
                                              (.close agent))
                                        agent))

           (get-agent [_ id]
                      (.get agents-map id))

           (find-agents [_ criteria]
                        (let [entries (seq metadata-map)]
                             (filter (fn [[id metadata]]
                                         (every? (fn [[k v]]
                                                     (= (get metadata k) v))
                                                 criteria))
                                     entries)))

           (list-agents [_]
                        (into {} (seq agents-map)))

           (route-to-agent [this request]
                           (if router-fn
                             (router-fn this request)
                             (throw (ex-info "No router configured" {}))))

           (shutdown-all [_]
                         (doseq [[id agent] (seq agents-map)]
                                (when (instance? Closeable agent)
                                      (.close agent)))
                         (.clear agents-map)
                         (.clear metadata-map))

           Closeable
           (close [this]
                  (shutdown-all this)))

(defn create-registry
      "Create a new agent registry."
      [& {:keys [router-fn]}]
      (->StandardRegistry
        (ConcurrentHashMap.)
        (ConcurrentHashMap.)
        router-fn))

;; ============================================================================
;; Registry Builders
;; ============================================================================

(defn registry-from-config
      "Build a registry from configuration map.

       Config format:
       {:agents {:agent-id {:logic ... :provider ...}}
        :router router-fn}"
      [{:keys [agents router]}]
      (let [registry (create-registry :router-fn router)]
           (doseq [[id config] agents]
                  (let [agent (core/create-agent (:logic config) config)]
                       (register-agent! registry id agent)))
           registry))

;; ============================================================================
;; Agent Groups - Logical Collections
;; ============================================================================

(defrecord AgentGroup [name agents selector-fn]
           core/Agent
           (execute [_ input]
                    (let [selected (selector-fn agents input)]
                         (if selected
                           (core/execute selected input)
                           (throw (ex-info "No agent selected for input" {:input input})))))

           (swap-logic! [_ new-logic]
                        ;; Swap logic for all agents in group
                        (doseq [agent agents]
                               (core/swap-logic! agent new-logic)))

           (swap-provider! [_ new-provider]
                           ;; Swap provider for all agents in group
                           (doseq [agent agents]
                                  (core/swap-provider! agent new-provider)))

           (get-state [_]
                      {:name name
                       :agents (map core/get-state agents)}))

(defn agent-group
      "Create a group of agents that work together."
      [name agents & {:keys [selector-fn]
                      :or {selector-fn first}}]
      (->AgentGroup name agents selector-fn))

;; ============================================================================
;; Routing Strategies
;; ============================================================================

(defn capability-router
      "Route based on agent capabilities."
      [capability-key]
      (fn [registry request]
          (let [required-capability (get request capability-key)
                candidates (find-agents registry {capability-key required-capability})]
               (when (seq candidates)
                     (get-agent registry (first (first candidates)))))))

(defn round-robin-router
      "Round-robin routing across all agents."
      []
      (let [counter (atom 0)]
           (fn [registry _request]
               (let [agents (vec (keys (list-agents registry)))
                     idx (swap! counter #(mod (inc %) (count agents)))]
                    (get-agent registry (nth agents idx))))))

(defn load-balanced-router
      "Route based on agent load/statistics."
      []
      (fn [registry _request]
          (let [agents (list-agents registry)
                loads (map (fn [[id agent]]
                               [id (get-in (core/get-state agent) [:stats :executions] 0)])
                           agents)
                sorted (sort-by second loads)]
               (get-agent registry (first (first sorted))))))

(defn composite-router
      "Combine multiple routing strategies."
      [& routers]
      (fn [registry request]
          (loop [remaining routers]
                (when (seq remaining)
                      (if-let [agent ((first remaining) registry request)]
                              agent
                              (recur (rest remaining)))))))

;; ============================================================================
;; Registry Patterns
;; ============================================================================

(defn create-multi-agent-system
      "Create a complete multi-agent system from configuration.

       Config format:
       {:meal-agent {:logic meal-logic
                    :provider {:provider :openai ...}
                    :capabilities {:type :meal-planner}}
        :recipe-agent {:logic recipe-logic
                      :provider {:provider :claude ...}
                      :capabilities {:type :recipe-generator}}}"
      [agents-config]
      (let [registry (create-registry)]
           (doseq [[id config] agents-config]
                  (let [agent (core/create-agent (:logic config)
                                                 {:provider (:provider config)
                                                  :context (:context config {})})]
                       (register-agent! registry id agent)))
           registry))

(defn with-registry
      "Execute function with a registry that's automatically cleaned up."
      [agents-config f]
      (with-open [registry (core/closeable
                             (create-multi-agent-system agents-config)
                             (fn [r] (shutdown-all r)))]
                 (f @registry)))

;; ============================================================================
;; Agent Discovery
;; ============================================================================

(defn discover-agents
      "Discover agents based on capabilities."
      [registry & capabilities]
      (let [all-agents (list-agents registry)]
           (filter (fn [[id agent]]
                       (when-let [agent-caps (core/get-capabilities agent)]
                                 (every? (set (keys agent-caps)) capabilities)))
                   all-agents)))

(defn agent-catalog
      "Get a catalog of all agents and their capabilities."
      [registry]
      (let [agents (list-agents registry)]
           (into {}
                 (map (fn [[id agent]]
                          [id (merge {:id id}
                                     (when (satisfies? core/Agent agent)
                                           (core/get-state agent)))])
                      agents))))

;; ============================================================================
;; Dynamic Agent Management
;; ============================================================================

(defn hot-swap-agent!
      "Hot-swap an agent in the registry."
      [registry id new-agent]
      (unregister-agent! registry id)
      (register-agent! registry id new-agent))

(defn clone-agent
      "Clone an existing agent with a new ID."
      [registry source-id new-id]
      (when-let [source (get-agent registry source-id)]
                (let [state (core/get-state source)
                      cloned (core/create-agent (:logic state) state)]
                     (register-agent! registry new-id cloned)
                     cloned)))

(defn scale-agent
      "Create multiple instances of an agent."
      [registry agent-id count]
      (when-let [source (get-agent registry agent-id)]
                (let [state (core/get-state source)]
                     (doseq [i (range count)]
                            (let [new-id (keyword (str (name agent-id) "-" i))
                                  cloned (core/create-agent (:logic state) state)]
                                 (register-agent! registry new-id cloned)))
                     count)))

;; ============================================================================
;; Monitoring & Management
;; ============================================================================

(defn registry-stats
      "Get statistics for all agents in registry."
      [registry]
      (let [agents (list-agents registry)]
           {:total-agents (count agents)
            :agents (into {}
                          (map (fn [[id agent]]
                                   [id (get-in (core/get-state agent) [:stats])])
                               agents))
            :total-executions (reduce + 0 (map #(get-in (core/get-state %)
                                                        [:stats :executions] 0)
                                               (vals agents)))}))

(defn health-check
      "Check health of all agents."
      [registry test-input]
      (let [agents (list-agents registry)]
           (into {}
                 (map (fn [[id agent]]
                          [id (try
                                {:status :healthy
                                 :response (core/execute agent test-input)}
                                (catch Exception e
                                  {:status :unhealthy
                                   :error (.getMessage e)}))])
                      agents))))

;; ============================================================================
;; Registry Middleware
;; ============================================================================

(defn with-registry-logging
      "Add logging to all registry operations."
      [registry log-fn]
      (reify AgentRegistry
             (register-agent! [_ id agent]
                              (log-fn {:op :register :id id})
                              (register-agent! registry id agent))

             (unregister-agent! [_ id]
                                (log-fn {:op :unregister :id id})
                                (unregister-agent! registry id))

             (get-agent [_ id]
                        (get-agent registry id))

             (find-agents [_ criteria]
                          (find-agents registry criteria))

             (list-agents [_]
                          (list-agents registry))

             (route-to-agent [_ request]
                             (let [agent (route-to-agent registry request)]
                                  (log-fn {:op :route :agent agent})
                                  agent))

             (shutdown-all [_]
                           (log-fn {:op :shutdown})
                           (shutdown-all registry))))

;; ============================================================================
;; Example Usage Patterns
;; ============================================================================

(comment
  ;; Create a registry with different specialized agents
  (def system-registry
    (create-multi-agent-system
      {:legal-agent {:logic legal-logic
                     :provider {:provider :claude}
                     :capabilities {:domain :legal
                                    :risk-tolerance :low}}

       :creative-agent {:logic creative-logic
                        :provider {:provider :openai}
                        :capabilities {:domain :creative
                                       :creativity :high}}

       :data-agent {:logic data-logic
                    :provider {:provider :gemini}
                    :capabilities {:domain :analysis
                                   :cost :low}}}))

  ;; Route to appropriate agent based on domain
  (def domain-router (capability-router :domain))

  ;; Execute with routing
  (route-to-agent system-registry {:domain :legal :input "Review this contract"})

  ;; Scale up the data agent
  (scale-agent system-registry :data-agent 3)

  ;; Check system health
  (health-check system-registry "test")

  ;; Get registry statistics
  (registry-stats system-registry))