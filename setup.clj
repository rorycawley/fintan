#!/usr/bin/env bb

;; setup.clj - Quick setup script for the meal agent project

(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(println "🚀 Setting up Meal Agent Project")
(println "================================\n")

;; Check Java version
(println "📌 Checking Java version...")
(let [java-version (-> (p/shell {:out :string} "java" "-version")
                       :err
                       (str/split-lines)
                       first)]
  (println "   " java-version))

;; Check Clojure CLI
(println "\n📌 Checking Clojure CLI...")
(if (fs/which "clj")
  (let [clj-version (-> (p/shell {:out :string} "clj" "--version")
                        :out
                        str/trim)]
    (println "   " clj-version))
  (do
    (println "   ❌ Clojure CLI not found!")
    (println "   Please install from: https://clojure.org/guides/install_clojure")
    (System/exit 1)))

;; Check Babashka
(println "\n📌 Checking Babashka...")
(if (fs/which "bb")
  (let [bb-version (-> (p/shell {:out :string} "bb" "--version")
                       :out
                       str/trim)]
    (println "   " bb-version))
  (println "   ⚠️  Babashka not found (optional but recommended)"))

;; Download dependencies
(println "\n📦 Downloading dependencies...")
(p/shell "clj" "-P" "-M:test:dev")
(println "   ✅ Dependencies downloaded")

;; Check for OpenAI API key
(println "\n🔑 Checking OpenAI API key...")
(if-let [api-key (System/getenv "OPENAI_API_KEY")]
  (println "   ✅ API key found (length:" (count api-key) ")")
  (do
    (println "   ⚠️  No OPENAI_API_KEY found")
    (println "   The agent will run in offline mode with mock parser")
    (println "   To enable LLM features, set: export OPENAI_API_KEY=\"sk-...\"")
    (println "\n   Get your API key from: https://platform.openai.com/api-keys")))

;; Create .env file if it doesn't exist
(when-not (fs/exists? ".env")
  (println "\n📝 Creating .env template...")
  (spit ".env" "# OpenAI API Configuration\nOPENAI_API_KEY=\"sk-your-key-here\"\n# OPENAI_MODEL=\"gpt-4o\"  # Optional: override default model\n")
  (println "   ✅ Created .env file (remember to add your API key)"))

;; Test the setup
(println "\n🧪 Testing basic functionality...")
(try
  (require '[meal-agent.core :as meal])
  (let [result (meal/parse-user-input-mock "test input")]
    (if (map? result)
      (println "   ✅ Mock parser working")
      (println "   ❌ Mock parser failed")))
  (catch Exception e
    (println "   ❌ Error loading meal agent:" (.getMessage e))))

;; Summary
(println "\n" (str/join "" (repeat 40 "=")))
(println "✅ Setup Complete!\n")
(println "Next steps:")
(println "1. Run examples:     bb run")
(println "2. Run tests:        bb test")
(println "3. Start REPL:       bb repl")
(println "4. See all tasks:    bb tasks")

(when-not (System/getenv "OPENAI_API_KEY")
  (println "\n💡 TIP: To enable LLM features:")
  (println "   export OPENAI_API_KEY=\"sk-your-key-here\"")
  (println "   or add it to your .env file"))

(System/exit 0)