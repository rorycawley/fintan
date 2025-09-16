(ns standard-agent.providers
  "LLM provider implementations for Standard Agent.

   Includes support for:
   - OpenAI (GPT-4, GPT-3.5)
   - Anthropic Claude (Opus, Sonnet, Haiku)
   - Google Gemini
   - Local/Mock providers for testing"
  (:require [standard-agent.core :as core]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [clojure.string :as str])
  (:import [java.io Closeable]))

;; ============================================================================
;; Provider Protocol Extensions
;; ============================================================================

(defn make-http-request
      "Common HTTP request function for all providers."
      [{:keys [url headers body timeout]
        :or {timeout 30000}}]
      (http/post url
                 {:headers headers
                  :body (json/write-str body)
                  :as :json
                  :throw-exceptions false
                  :socket-timeout timeout
                  :connection-timeout 5000}))

;; ============================================================================
;; OpenAI Provider
;; ============================================================================

(defrecord OpenAIProvider [api-key model base-url options]
           core/LLMProvider
           (invoke-llm [_ prompt provider-options]
                       (let [merged-options (merge options provider-options)
                             request-body {:model model
                                           :messages [{:role "user" :content prompt}]
                                           :temperature (:temperature merged-options 0.7)
                                           :max_tokens (:max-tokens merged-options 1000)
                                           :top_p (:top-p merged-options 1.0)}

                             response (make-http-request
                                        {:url (str base-url "/chat/completions")
                                         :headers {"Authorization" (str "Bearer " api-key)
                                                   "Content-Type" "application/json"}
                                         :body request-body
                                         :timeout (:timeout merged-options 30000)})]

                            (if (= 200 (:status response))
                              (let [body (:body response)
                                    choice (first (:choices body))]
                                   {:response (get-in choice [:message :content])
                                    :usage (:usage body)
                                    :metadata {:model model
                                               :finish-reason (:finish_reason choice)}})
                              (throw (ex-info "OpenAI API error" response)))))

           (get-provider-info [_]
                              {:name "openai"
                               :models ["gpt-4o" "gpt-4-turbo" "gpt-3.5-turbo"]
                               :capabilities #{:chat :json-mode :function-calling}
                               :base-url base-url})

           Closeable
           (close [_]
                  ;; Cleanup if needed
                  ))

(defn openai-provider
      "Create an OpenAI provider."
      [{:keys [api-key model base-url options]
        :or {model "gpt-4o"
             base-url "https://api.openai.com/v1"
             options {}}}]
      (->OpenAIProvider api-key model base-url options))

;; ============================================================================
;; Anthropic Claude Provider
;; ============================================================================

(defrecord ClaudeProvider [api-key model base-url options]
           core/LLMProvider
           (invoke-llm [_ prompt provider-options]
                       (let [merged-options (merge options provider-options)
                             request-body {:model model
                                           :messages [{:role "user" :content prompt}]
                                           :max_tokens (:max-tokens merged-options 1000)
                                           :temperature (:temperature merged-options 0.7)}

                             response (make-http-request
                                        {:url (str base-url "/messages")
                                         :headers {"x-api-key" api-key
                                                   "anthropic-version" "2023-06-01"
                                                   "Content-Type" "application/json"}
                                         :body request-body
                                         :timeout (:timeout merged-options 30000)})]

                            (if (= 200 (:status response))
                              (let [body (:body response)]
                                   {:response (get-in body [:content 0 :text])
                                    :usage {:input_tokens (:input_tokens (:usage body))
                                            :output_tokens (:output_tokens (:usage body))}
                                    :metadata {:model model
                                               :stop-reason (:stop_reason body)}})
                              (throw (ex-info "Claude API error" response)))))

           (get-provider-info [_]
                              {:name "claude"
                               :models ["claude-3-opus" "claude-3-sonnet" "claude-3-haiku"]
                               :capabilities #{:chat :vision :long-context}
                               :base-url base-url})

           Closeable
           (close [_]))

(defn claude-provider
      "Create a Claude provider."
      [{:keys [api-key model base-url options]
        :or {model "claude-3-sonnet"
             base-url "https://api.anthropic.com/v1"
             options {}}}]
      (->ClaudeProvider api-key model base-url options))

;; ============================================================================
;; Google Gemini Provider
;; ============================================================================

(defrecord GeminiProvider [api-key model base-url options]
           core/LLMProvider
           (invoke-llm [_ prompt provider-options]
                       (let [merged-options (merge options provider-options)
                             request-body {:contents [{:parts [{:text prompt}]}]
                                           :generationConfig {:temperature (:temperature merged-options 0.7)
                                                              :maxOutputTokens (:max-tokens merged-options 1000)
                                                              :topP (:top-p merged-options 1.0)}}

                             response (make-http-request
                                        {:url (str base-url "/models/" model ":generateContent?key=" api-key)
                                         :headers {"Content-Type" "application/json"}
                                         :body request-body
                                         :timeout (:timeout merged-options 30000)})]

                            (if (= 200 (:status response))
                              (let [body (:body response)
                                    candidate (first (:candidates body))]
                                   {:response (get-in candidate [:content :parts 0 :text])
                                    :usage {:total_tokens (get-in body [:usageMetadata :totalTokenCount])}
                                    :metadata {:model model
                                               :finish-reason (get-in candidate [:finishReason])}})
                              (throw (ex-info "Gemini API error" response)))))

           (get-provider-info [_]
                              {:name "gemini"
                               :models ["gemini-pro" "gemini-pro-vision"]
                               :capabilities #{:chat :vision :multi-modal}
                               :base-url base-url})

           Closeable
           (close [_]))

(defn gemini-provider
      "Create a Gemini provider."
      [{:keys [api-key model base-url options]
        :or {model "gemini-pro"
             base-url "https://generativelanguage.googleapis.com/v1"
             options {}}}]
      (->GeminiProvider api-key model base-url options))

;; ============================================================================
;; Provider Factory - Dynamic Selection
;; ============================================================================

(defn create-provider
      "Create a provider based on configuration.

       Config map should contain:
       - :provider - One of :openai :claude :gemini :mock
       - :api-key - API key for the provider
       - :model - Optional model override
       - :options - Provider-specific options"
      [{:keys [provider api-key model options] :as config}]
      (case provider
            :openai (openai-provider (assoc config :api-key api-key))
            :claude (claude-provider (assoc config :api-key api-key))
            :gemini (gemini-provider (assoc config :api-key api-key))
            :mock (core/mock-provider (or (:responses config) ["Mock response"]))
            (throw (ex-info "Unknown provider" {:provider provider}))))

;; ============================================================================
;; Provider Pool - Load Balancing & Failover
;; ============================================================================

(defrecord ProviderPool [providers strategy current-index]
           core/LLMProvider
           (invoke-llm [this prompt options]
                       (case @strategy
                             :round-robin
                             (let [idx (swap! current-index #(mod (inc %) (count providers)))
                                   provider (nth providers idx)]
                                  (core/invoke-llm provider prompt options))

                             :failover
                             (loop [remaining providers
                                    errors []]
                                   (if (empty? remaining)
                                     (throw (ex-info "All providers failed" {:errors errors}))
                                     (let [provider (first remaining)]
                                          (try
                                            (core/invoke-llm provider prompt options)
                                            (catch Exception e
                                              (recur (rest remaining) (conj errors e)))))))

                             :random
                             (let [provider (rand-nth providers)]
                                  (core/invoke-llm provider prompt options))))

           (get-provider-info [_]
                              {:name "provider-pool"
                               :providers (map core/get-provider-info providers)
                               :strategy @strategy})

           Closeable
           (close [_]
                  (doseq [p providers]
                         (when (instance? Closeable p)
                               (.close p)))))

(defn provider-pool
      "Create a pool of providers with load balancing."
      [providers & {:keys [strategy] :or {strategy :round-robin}}]
      (->ProviderPool providers (atom strategy) (atom 0)))

;; ============================================================================
;; Provider Decorators - Adding Capabilities
;; ============================================================================

(defn with-rate-limiting
      "Decorator to add rate limiting to a provider."
      [provider requests-per-minute]
      (let [call-times (atom [])]
           (reify core/LLMProvider
                  (invoke-llm [_ prompt options]
                              (let [now (System/currentTimeMillis)
                                    minute-ago (- now 60000)
                                    recent-calls (swap! call-times
                                                        (fn [times]
                                                            (filterv #(> % minute-ago) times)))]
                                   (when (>= (count recent-calls) requests-per-minute)
                                         (let [wait-time (- (+ (first recent-calls) 60000) now)]
                                              (Thread/sleep wait-time)))
                                   (swap! call-times conj now)
                                   (core/invoke-llm provider prompt options)))

                  (get-provider-info [_]
                                     (assoc (core/get-provider-info provider)
                                            :rate-limit requests-per-minute)))))

(defn with-fallback
      "Decorator to add fallback provider."
      [primary-provider fallback-provider]
      (reify core/LLMProvider
             (invoke-llm [_ prompt options]
                         (try
                           (core/invoke-llm primary-provider prompt options)
                           (catch Exception e
                             (println "Primary provider failed, using fallback:" (.getMessage e))
                             (core/invoke-llm fallback-provider prompt options))))

             (get-provider-info [_]
                                {:primary (core/get-provider-info primary-provider)
                                 :fallback (core/get-provider-info fallback-provider)})))

(defn with-cost-tracking
      "Decorator to track API costs."
      [provider cost-per-1k-tokens cost-atom]
      (reify core/LLMProvider
             (invoke-llm [_ prompt options]
                         (let [result (core/invoke-llm provider prompt options)
                               tokens (get-in result [:usage :total_tokens] 0)
                               cost (* (/ tokens 1000.0) cost-per-1k-tokens)]
                              (swap! cost-atom + cost)
                              (assoc-in result [:metadata :cost] cost)))

             (get-provider-info [_]
                                (assoc (core/get-provider-info provider)
                                       :cost-tracking true
                                       :total-cost @cost-atom))))

;; ============================================================================
;; Provider Comparison - A/B Testing
;; ============================================================================

(defn compare-providers
      "Run the same prompt through multiple providers for comparison."
      [providers prompt options]
      (into {}
            (map (fn [[name provider]]
                     [name (try
                             (let [start (System/currentTimeMillis)
                                   result (core/invoke-llm provider prompt options)
                                   elapsed (- (System/currentTimeMillis) start)]
                                  (assoc result :elapsed-ms elapsed))
                             (catch Exception e
                               {:error (.getMessage e)}))])
                 providers)))

;; ============================================================================
;; Configuration Templates
;; ============================================================================

(def provider-configs
  "Standard configurations for common use cases."
  {:fast-cheap {:provider :gemini
                :model "gemini-pro"
                :options {:temperature 0.5
                          :max-tokens 500}}

   :creative {:provider :openai
              :model "gpt-4o"
              :options {:temperature 0.9
                        :top-p 0.95
                        :max-tokens 2000}}

   :accurate {:provider :claude
              :model "claude-3-opus"
              :options {:temperature 0.2
                        :max-tokens 1500}}

   :balanced {:provider :openai
              :model "gpt-4-turbo"
              :options {:temperature 0.7
                        :max-tokens 1000}}})

(defn get-provider-config
      "Get a standard provider configuration by name."
      [config-name api-keys]
      (let [config (get provider-configs config-name)]
           (assoc config :api-key (get api-keys (:provider config)))))