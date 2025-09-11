(ns meal-agent.config
  "Configuration management for the meal agent application.

   This namespace demonstrates how to handle configuration
   in a production environment using the with-open pattern.

   Features:
   - Environment-based configuration
   - Configuration validation
   - Multi-environment support
   - Secrets management"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [meal-agent.adapters :as adapters]))

;; ============================================================================
;; Configuration Schema
;; ============================================================================

(def ^:private config-schema
  "Schema defining valid configuration options and their types."
  {:api-key {:type :string
             :required false  ; Can use stub if not provided
             :sensitive true  ; Don't log this value
             :env-var "OPENAI_API_KEY"}

   :model {:type :string
           :default "gpt-4o"
           :env-var "OPENAI_MODEL"}

   :api-url {:type :string
             :default "https://api.openai.com/v1/chat/completions"
             :env-var "OPENAI_API_URL"}

   :use-stub {:type :boolean
              :default false
              :env-var "USE_STUB_PARSER"}

   :max-connections {:type :integer
                     :default 10
                     :env-var "HTTP_MAX_CONNECTIONS"}

   :request-timeout {:type :integer
                     :default 10000  ; milliseconds
                     :env-var "HTTP_REQUEST_TIMEOUT"}

   :environment {:type :keyword
                 :default :development
                 :env-var "APP_ENV"}

   :log-level {:type :keyword
               :default :info
               :env-var "LOG_LEVEL"}})

;; ============================================================================
;; Configuration Loading
;; ============================================================================

(defn- load-env-var
  "Load a value from environment variable with type coercion."
  [env-var type]
  (when-let [value (System/getenv env-var)]
    (case type
      :string value
      :integer (Integer/parseInt value)
      :boolean (Boolean/parseBoolean value)
      :keyword (keyword (str/lower-case value))
      value)))

(defn- load-config-file
  "Load configuration from an EDN file if it exists."
  [path]
  (let [file (io/file path)]
    (when (.exists file)
      (-> file slurp edn/read-string))))

(defn- merge-config-sources
  "Merge configuration from multiple sources with precedence:
   1. Environment variables (highest)
   2. Config file
   3. Defaults (lowest)"
  [_defaults file-config]
  (reduce-kv
    (fn [config key schema]
      (let [env-value (when-let [env-var (:env-var schema)]
                        (load-env-var env-var (:type schema)))
            file-value (get file-config key)
            default-value (:default schema)]
        (assoc config key
                      (or env-value      ; Highest priority
                          file-value     ; Medium priority
                          default-value  ; Lowest priority
                          nil))))       ; No value available
    {}
    config-schema))

(defn- validate-config
  "Validate configuration against schema.
   Throws exception if required fields are missing."
  [config]
  (doseq [[key schema] config-schema]
    (when (and (:required schema)
               (nil? (get config key)))
      (throw (ex-info (str "Required configuration missing: " key)
                      {:key key
                       :env-var (:env-var schema)}))))
  config)

(defn- mask-sensitive
  "Mask sensitive configuration values for logging."
  [config]
  (reduce-kv
    (fn [masked key value]
      (if (get-in config-schema [key :sensitive])
        (assoc masked key (when value "***MASKED***"))
        (assoc masked key value)))
    {}
    config))

;; ============================================================================
;; Public API
;; ============================================================================

(defn load-config
  "Load configuration from all available sources.

   Precedence order:
   1. Environment variables
   2. Config file (if provided)
   3. Built-in defaults

   Parameters:
   - config-file: Optional path to EDN config file

   Returns validated configuration map."
  ([]
   (load-config nil))
  ([config-file]
   (let [file-config (when config-file
                       (load-config-file config-file))
         merged (merge-config-sources {} file-config)
         validated (validate-config merged)]
     (println "Configuration loaded:" (mask-sensitive validated))
     validated)))

;; ============================================================================
;; Environment-Specific Configurations
;; ============================================================================

(def environments
  "Pre-defined configurations for different environments."
  {:development
   {:use-stub false
    :log-level :debug
    :max-connections 5
    :request-timeout 30000}

   :test
   {:use-stub true  ; Always use stub in tests
    :log-level :warn
    :max-connections 2
    :request-timeout 5000}

   :staging
   {:use-stub false
    :log-level :info
    :max-connections 10
    :request-timeout 15000}

   :production
   {:use-stub false
    :log-level :warn
    :max-connections 20
    :request-timeout 10000}})

(defn load-for-environment
  "Load configuration for a specific environment.

   Environment-specific settings override defaults but
   are overridden by explicit config file or env vars."
  [env]
  (let [env-config (get environments env {})
        file-config nil  ; Could load env-specific file
        merged (merge-config-sources env-config file-config)]
    (validate-config merged)))

;; ============================================================================
;; Configuration Management with with-open
;; ============================================================================

(defn with-config
  "Execute a function with a configuration context.

   This demonstrates how configuration can be treated as
   a managed resource using the with-open pattern.

   The configuration might include resources like:
   - Decrypted secrets
   - Temporary credentials
   - Config watchers for hot-reload"
  [config-source f]
  (with-open [;; Config could be a closeable resource
              ;; e.g., watching for changes, managing secrets
              config-resource (closeable
                                (load-config config-source)
                                (fn [_]
                                  (println "Closing configuration context")))]
    (f @config-resource)))

;; ============================================================================
;; Dynamic Configuration Updates
;; ============================================================================

(defprotocol ConfigProvider
  "Protocol for configuration providers that can be hot-reloaded."
  (get-config [this])
  (reload! [this])
  (watch! [this callback]))

(defrecord FileConfigProvider [path config-atom]
  ConfigProvider
  (get-config [_]
    @config-atom)

  (reload! [_]
    (let [new-config (load-config path)]
      (reset! config-atom new-config)
      (println "Configuration reloaded from" path)))

  (watch! [this callback]
    ;; In production, could use file watcher
    ;; For demo, just periodic check
    (future
      (loop []
        (Thread/sleep 30000)  ; Check every 30 seconds
        (let [old-config @config-atom]
          (reload! this)
          (when (not= old-config @config-atom)
            (callback @config-atom)))
        (recur)))))

(defn closeable-config-provider
  "Create a config provider that can be used with with-open."
  [config-path]
  (let [config-atom (atom (load-config config-path))
        provider (->FileConfigProvider config-path config-atom)
        watcher (atom nil)]

    ;; Start watching for changes
    (reset! watcher
            (watch! provider
                    (fn [new-config]
                      (println "Configuration changed:"
                               (mask-sensitive new-config)))))

    ;; Return as closeable resource
    (closeable provider
               (fn [_]
                 (when-let [w @watcher]
                   (future-cancel w))
                 (println "Stopped configuration watcher")))))

;; ============================================================================
;; Usage Examples
;; ============================================================================

(defn example-with-managed-config
  "Example showing configuration as a managed resource."
  []
  ;; Configuration is treated as a resource that needs cleanup
  (with-open [config-provider (closeable-config-provider "config.edn")]
    (let [config (get-config @config-provider)]
      (println "Using configuration:" (mask-sensitive config))

      ;; Simulate application running
      (Thread/sleep 5000)

      ;; Config can be reloaded while running
      (reload! @config-provider)

      ;; Continue with updated config
      (let [new-config (get-config @config-provider)]
        (println "Updated configuration:" (mask-sensitive new-config))))))

;; ============================================================================
;; Production System with Configuration
;; ============================================================================

(defn with-configured-system
  "Create a fully configured system using with-open pattern.

   This shows how configuration and system resources are
   composed together with proper lifecycle management."
  [environment f]
  (let [config (load-for-environment environment)]
    (println (str "Starting system in " environment " mode"))

    ;; Layer resource management with with-open
    (with-open [;; Configuration provider (could watch for changes)
                config-provider (closeable-config-provider nil)

                ;; HTTP connection pool based on config
                conn-pool (adapters/closeable
                            {:max (:max-connections config)
                             :timeout (:request-timeout config)}
                            (constantly nil))

                ;; Parser based on configuration
                parser (if (:use-stub config)
                         (adapters/stub-parser-resource)
                         (adapters/openai-parser-resource
                           (:api-key config)))]

      ;; Execute with fully configured system
      (f {:config @config-provider
          :parser @parser
          :connection-pool @conn-pool
          :environment environment}))))

;; ============================================================================
;; Environment Detection
;; ============================================================================

(defn detect-environment
  "Detect the current environment from various sources."
  []
  (or ;; Check environment variable
    (some-> (System/getenv "APP_ENV")
            str/lower-case
            keyword)
    ;; Check system property
    (some-> (System/getProperty "app.env")
            str/lower-case
            keyword)
    ;; Check for production indicators
    (when (System/getenv "KUBERNETES_SERVICE_HOST")
      :production)
    ;; Default
    :development))

(defn current-config
  "Get the configuration for the current environment."
  []
  (load-for-environment (detect-environment)))

;; ============================================================================
;; REPL Helpers
;; ============================================================================

(comment
  ;; Load default configuration
  (load-config)

  ;; Load from file
  (load-config "config.edn")

  ;; Load for specific environment
  (load-for-environment :production)

  ;; Detect current environment
  (detect-environment)

  ;; Use configuration as managed resource
  (with-config "config.edn"
               (fn [config]
                 (println "Config:" config)))

  ;; Run with configured system
  (with-configured-system :development
                          (fn [system]
                            (println "System ready:" (keys system))))

  )