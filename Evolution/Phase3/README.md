# Standard Agent 🤖

A lightweight, pluggable AI agent library for Clojure that runs entirely in your process - like DuckDB or SQLite for AI agents.

**You bring the business logic, we provide the infrastructure.**

## Core Philosophy

Standard Agent separates concerns cleanly:
- **Your Logic**: Domain-specific prompt engineering and response processing
- **Our Infrastructure**: LLM provider abstraction, resource management, hot-swapping, retries, caching

## Features

- 🔌 **Pluggable Business Logic** - Implement the `AgentLogic` protocol with your domain knowledge
- 🔄 **Hot-Swappable** - Change providers or logic at runtime without restart
- 🏭 **Multi-Provider Support** - OpenAI, Claude, Gemini out of the box
- 🎯 **Multiple Integration Styles** - Use with Component, Integrant, Mount, or plain functions
- 📦 **Zero Framework Lock-in** - Uses `with-open` internally, no heavy dependencies
- 🏗️ **Registry Pattern** - Manage multiple specialized agents
- 🧪 **Testing First** - Built-in mock providers and test utilities

## Quick Start

### Installation

```clojure
;; deps.edn
{:deps {com.yourorg/standard-agent {:mvn/version "1.0.0"}}}
```

### Basic Usage

```clojure
(ns my-app
  (:require [standard-agent.core :as agent]
            [standard-agent.providers :as providers]))

;; 1. Define your business logic
(defrecord MyLogic []
  agent/AgentLogic
  
  (prepare-prompt [_ context input]
    {:prompt (str "Process this: " input)
     :options {:temperature 0.7}})
  
  (process-response [_ context llm-response]
    {:result (parse-response llm-response)}))

;; 2. Create an agent
(def my-agent
  (agent/create-agent
    (->MyLogic)
    {:provider (providers/create-provider
                 {:provider :openai
                  :api-key "your-key"})}))

;; 3. Use it
(agent/execute my-agent "Hello world")
```

## Integration Patterns

### Functional Style
```clojure
(require '[standard-agent.integrations :as integrate])

;; Simple function
(def process 
  (integrate/create-agent-fn
    (->MyLogic)
    {:provider :claude :api-key "..."}))

(process "input") ; => result
```

### With-Open Pattern
```clojure
(with-open [agent (integrate/agent-resource
                    (->MyLogic)
                    {:provider :gemini :api-key "..."})]
  (agent/execute @agent "input"))
```

### Component Integration
```clojure
(def system
  (component/system-map
    :agent (integrate/create-component
             (->MyLogic)
             {:provider :openai})))
```

## Hot-Swapping at Runtime

```clojure
;; Start with OpenAI
(def agent (create-agent (->MyLogic) {:provider openai-provider}))

;; OpenAI is down? Switch to Claude instantly
(agent/swap-provider! agent claude-provider)

;; User upgraded? Switch to premium logic
(agent/swap-logic! agent (->PremiumLogic))

;; A/B testing
(if (rand-nth [true false])
  (agent/swap-provider! agent provider-a)
  (agent/swap-provider! agent provider-b))
```

## Registry Pattern for Multiple Agents

```clojure
(def corporate-system
  {:legal-reviewer    {:logic (->LegalLogic) :provider :claude}      ; Cautious
   :creative-writer   {:logic (->CreativeLogic) :provider :openai}   ; Creative
   :data-processor    {:logic (->DataLogic) :provider :local-llm}    ; Private
   :customer-service  {:logic (->ServiceLogic) :provider :gemini}})  ; Cost-effective

;; Route based on need
(defn process-request [type input]
  (-> (registry/get-agent registry type)
      (agent/execute input)))
```

## Provider Flexibility

### Built-in Providers
- OpenAI (GPT-4, GPT-3.5)
- Anthropic Claude (Opus, Sonnet, Haiku)
- Google Gemini
- Mock (for testing)

### Provider Decorators
```clojure
;; Add rate limiting
(def limited (providers/with-rate-limiting provider 60))

;; Add fallback
(def reliable (providers/with-fallback primary backup))

;; Track costs
(def tracked (providers/with-cost-tracking provider 0.02 cost-atom))
```

## Testing Your Agents

```clojure
;; Deterministic testing with mock providers
(def test-agent
  (agent/create-agent
    (->MyLogic)
    {:provider (agent/mock-provider ["response1" "response2"])}))

(deftest my-logic-test
  (is (= expected (agent/execute test-agent "input"))))
```

## Architecture

```
Your Application
    ↓
┌─────────────────────────────────┐
│   Your Business Logic (Ports)   │ ← You implement AgentLogic
├─────────────────────────────────┤
│   Standard Agent Core           │ ← We provide infrastructure
│   • Resource Management          │
│   • Hot-swapping                 │
│   • Middleware (retry, timeout)  │
├─────────────────────────────────┤
│   Provider Adapters             │ ← We provide adapters
│   OpenAI │ Claude │ Gemini      │
└─────────────────────────────────┘
```

## Middleware System

```clojure
(def robust-agent
  (agent/create-agent
    (->MyLogic)
    {:provider provider
     :middleware [(agent/with-retry 3 1000)        ; Retry 3 times
                  (agent/with-timeout 10000)       ; 10s timeout
                  (agent/with-logging println)     ; Log all operations
                  (agent/with-caching cache-atom)]})) ; Cache results
```

## Examples

See the `examples/` directory for complete examples:
- `meal-agent` - Meal planning agent demonstrating all patterns
- `legal-agent` - Document review with high accuracy requirements
- `creative-agent` - Content generation with creative parameters

## Development

```bash
# Setup
bb setup

# Start REPL
bb dev

# Run tests
bb test

# Run examples
bb example

# Check everything
bb check
```

## Performance

- **Startup**: ~100ms to create an agent
- **Hot-swap**: <1ms to swap provider or logic
- **Memory**: ~10MB base + provider-specific
- **Concurrency**: Thread-safe, supports concurrent execution

## Comparison with Alternatives

| Feature | Standard Agent | LangChain | Component-based |
|---------|---------------|-----------|-----------------|
| In-process | ✅ | ✅ | ✅ |
| No framework required | ✅ | ❌ | ❌ |
| Hot-swapping | ✅ | ❌ | ❌ |
| Pluggable logic | ✅ | Partial | ❌ |
| Multiple DI styles | ✅ | ❌ | One only |
| Clojure-first | ✅ | ❌ | ✅ |

## Design Principles

1. **Library, not framework** - Integrate with your existing architecture
2. **Lightweight core** - Uses `with-open` internally, no heavy dependencies
3. **User owns the logic** - We don't dictate how you process responses
4. **Runtime flexibility** - Everything can be changed at runtime
5. **Testing first** - Mock providers and test utilities built-in

