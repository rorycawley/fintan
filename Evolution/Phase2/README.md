# Meal Agent - Ports & Adapters with `with-open` Pattern

A Clojure application demonstrating the Ports and Adapters (Hexagonal) architecture using lightweight resource management with `with-open` instead of heavyweight frameworks like Component or Integrant.

## Overview

This project implements a meal suggestion system that showcases how Clojure's built-in `with-open` macro combined with Java's `Closeable` interface can elegantly handle dependency injection and resource management. Based on [Maciej Szajna's blog post](https://medium.com/@maciekszajna/reloaded-workflow-out-of-the-box-be6b5f38ea98), this approach provides all the benefits of Component's reloaded workflow using just standard Clojure features.

The system supports multiple AI providers (OpenAI, Anthropic Claude, Google Gemini) through a unified interface, demonstrating how the ports and adapters pattern enables provider-agnostic business logic.

## Architecture

```
┌─────────────────────────────────────────────────┐
│                  User Interface                  │
└─────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────┐
│              Application Layer                   │
│         (Orchestration & Lifecycle)              │
└─────────────────────────────────────────────────┘
                         │
         ┌───────────────┴───────────────┐
         ▼                               ▼
┌─────────────────┐           ┌─────────────────┐
│     Port        │           │    Adapters     │
│ (Protocol/API)  │◄──────────│ • OpenAI        │
│                 │           │ • Claude        │
│                 │           │ • Gemini        │
└─────────────────┘           └─────────────────┘
         ▲                               ▲
         │                               │
┌─────────────────────────────────────────────────┐
│               Core Business Logic                │
│          (Pure Functions, No Side Effects)       │
└─────────────────────────────────────────────────┘
```

### Three Main Layers

1. **Core Domain (`meal-agent.core`)**
    - Pure functional business logic - no side effects
    - Port definitions (protocols) that define interfaces to the outside world
    - Domain model (recipes, meal requests)
    - Business rules for meal selection

2. **Adapters (`meal-agent.adapters`)**
    - Concrete implementations of the ports
    - Resource management using `with-open` pattern
    - Multiple AI provider integrations (OpenAI, Claude, Gemini)
    - Stub implementations for testing/development

3. **Application Layer (`meal-agent.main`)**
    - System composition and lifecycle management
    - REPL-friendly workflow for development
    - Interactive and batch processing modes

## The `with-open` Pattern

### Core Concept: The `closeable` Helper

The key innovation is a simple wrapper that makes anything `Closeable`:

```clojure
(defn closeable
  ([state] (closeable state (fn [_])))
  ([state close-fn]
   (reify
     clojure.lang.IDeref
     (deref [_] state)
     Closeable
     (close [_] (close-fn state)))))

;; Usage with with-open for automatic cleanup
(with-open [parser (create-ai-client {:provider :claude 
                                      :api-key api-key})
            pool (http-connection-pool 10)]
  ;; Resources are automatically cleaned up when leaving scope
  (process-request @parser input))
```

This allows mixing resources (that need cleanup) with regular state in a single `with-open` block.

### Key Design Principles

1. **Lexical Scoping = Dependency Order**
   ```clojure
   (with-open [db (create-database)        ; First
               repo (make-repo db)          ; Depends on db
               server (make-server repo)]   ; Depends on repo
     ...)  ; All cleaned up in reverse order
   ```

2. **Ports as Protocols**
    - `MealRequestParser` - abstracts LLM operations
    - `AIService` - unified interface for all AI providers
    - Domain logic depends only on protocols, not implementations

3. **Adapters as Closeable Resources**
    - AI clients wrapped with `closeable`
    - Connection pools already implement `Closeable`
    - Test stubs wrapped for consistent interface

## AI Provider Support

The system supports three AI providers through a unified `AIService` port interface, making them completely interchangeable:

### Provider Comparison

| Provider | Models | Max Tokens | Rate Limit | Best For |
|----------|--------|------------|------------|----------|
| **OpenAI** | GPT-4, GPT-4-Turbo, GPT-3.5-Turbo | 4096 | 500 req/min | General purpose, code generation |
| **Claude** | Opus, Sonnet, Haiku | 4096 | 1000 req/min | Long context, analysis, safety |
| **Gemini** | Gemini Pro, Pro Vision | 2048 | 60 req/min | Multimodal, free tier available |

### Configuration

Set the appropriate API key for your chosen provider:

```bash
# For OpenAI
export OPENAI_API_KEY="sk-..."

# For Anthropic Claude
export ANTHROPIC_API_KEY="sk-ant-..."

# For Google Gemini
export GOOGLE_API_KEY="AIza..."
```

Select provider in config:

```clojure
(def config
  {:ai-provider :claude  ; :openai, :claude, or :gemini
   :api-key (System/getenv "ANTHROPIC_API_KEY")
   :port 3000
   :db {...}})
```

### Provider-Specific Configuration

```clojure
;; OpenAI with specific model
(def openai-config
  {:ai-provider :openai
   :api-key (System/getenv "OPENAI_API_KEY")
   :model "gpt-4-turbo"
   :temperature 0.7})

;; Claude with system prompt
(def claude-config
  {:ai-provider :claude
   :api-key (System/getenv "ANTHROPIC_API_KEY")
   :model "claude-3-opus-20240229"
   :max-tokens 4096})

;; Gemini with safety settings
(def gemini-config
  {:ai-provider :gemini
   :api-key (System/getenv "GOOGLE_API_KEY")
   :model "gemini-pro"})
```

## Benefits Over Component/Integrant

| Aspect | with-open | Component |
|--------|-----------|-----------|
| Dependencies | None (built-in) | External library |
| Resource cleanup | Guaranteed by macro | Manual in stop method |
| Exception handling | Automatic | Manual |
| Test isolation | Automatic | Requires fixtures |
| Learning curve | Familiar (like `let`) | New concepts |
| Code impact | Minimal | Lifecycle records everywhere |
| Lexical scope | Dependencies visible | Dependencies hidden in records |

## Usage

### Running the Application

```bash
# Interactive mode with OpenAI
export OPENAI_API_KEY="your-key-here"
bb run

# With Claude
export ANTHROPIC_API_KEY="your-key-here"
bb run:claude

# Offline mode with stub parser
bb run:offline

# Batch mode
clojure -M:run batch
```

### REPL Development Workflow

The system supports a reloaded workflow without Component:

```clojure
(require '[meal-agent.main :as main])

(main/start!)                            ; Start the system
(main/process! "eggs and bread, hungry") ; Process a request
(main/run-examples!)                     ; Run examples
(main/status)                            ; Check system status
(main/reload!)                           ; Reload code
(main/stop!)                             ; Stop the system

;; Switch providers at runtime
(main/config! :ai-provider :claude)
(main/restart!)
```

### System Composition Patterns

#### For Production
```clojure
(with-system config
  (fn [system]
    @(promise)))  ; Block forever
```

#### For REPL Development
```clojure
(start!)   ; Starts system in background
(stop!)    ; Cancels and cleans up
(restart!) ; Stop + reload + start
```

#### For Testing
```clojure
(with-test-system
  (fn [system]
    ; Run isolated tests with fresh resources
    ))
```

### Comparing AI Providers

```clojure
(defn compare-providers [prompt]
  (let [providers [:openai :claude :gemini]]
    (for [provider providers]
      (with-system (assoc config :ai-provider provider)
        (fn [system]
          {:provider provider
           :response (generate-completion 
                      (:ai-service system) 
                      prompt)})))))

;; Usage
(compare-providers "Suggest a meal with eggs")
;; => ({:provider :openai :response "..."}
;;     {:provider :claude :response "..."}
;;     {:provider :gemini :response "..."})
```

## Project Structure

```
src/
├── meal_agent/
│   ├── core.clj        # Pure domain logic and port definitions
│   ├── adapters.clj    # AI provider adapters (OpenAI, Claude, Gemini)
│   └── main.clj        # System composition with with-open
test/
└── meal_agent/
    └── core_test.clj   # Tests demonstrating resource isolation
```

## Extending the System

### Adding a New AI Provider

Adding a new provider is straightforward:

```clojure
;; 1. Define client
(defn create-newprovider-client [api-key]
  (closeable
    {:api-key api-key
     :base-url "https://api.newprovider.com/v1"
     :model "model-name"}
    (fn [client]
      (println "Closing NewProvider connection"))))

;; 2. Implement service
(defn newprovider-service [client]
  (closeable
    (reify AIService
      (generate-completion [_ prompt]
        (make-newprovider-request client prompt))
      (analyze-sentiment [_ text]
        (make-newprovider-sentiment client text)))))

;; 3. Add to factory
(case provider
  :newprovider (create-newprovider-client api-key)
  ...)
```

### HTTP Request Formatting

Each provider has different API formats, handled internally by adapters:

```clojure
;; OpenAI format
{:model "gpt-4"
 :messages [{:role "user" :content prompt}]
 :temperature 0.7}

;; Claude format
{:model "claude-3-opus-20240229"
 :max_tokens 1024
 :messages [{:role "user" :content prompt}]}

;; Gemini format
{:contents [{:parts [{:text prompt}]}]}
```

## Example Requests

```clojure
"I have bread and cheese, kinda hungry"
;; → Suggested: Grilled Cheese (5 min)

"I have eggs, rice, very hungry but in a rush"
;; → Suggested: Egg Fried Rice (15 min)

"eggs and bread, 5 minutes max"
;; → Suggested: Eggs on Toast (8 min)
```

## Testing

### Unit Tests with Mocks

```clojure
(deftest test-business-logic
  (with-open [ai-service (closeable
                          (reify AIService
                            (generate-completion [_ _] 
                              {:response "test response"})
                            (analyze-sentiment [_ _] 
                              {:sentiment "positive"})))]
    ;; Test your logic independently of provider
    (is (= "test response" 
           (:response (generate-completion @ai-service "test"))))))
```

### Integration Tests

```clojure
(deftest test-with-real-provider
  (testing "OpenAI integration"
    (with-system openai-config
      (fn [system]
        (let [response (generate-completion 
                        (:ai-service system) 
                        "Say 'test passed'")]
          (is (string? (:response response)))))))
  
  (testing "Claude integration"
    (with-system claude-config
      (fn [system]
        ;; Similar test with Claude
        ))))
```

## Performance Considerations

- Resource allocation overhead: < 1ms
- Concurrent request handling: Thread-safe
- Memory usage: Minimal, resources released immediately
- Startup time: Near instant (no framework overhead)

### Rate Limiting by Provider

```clojure
(defn with-rate-limit [provider f]
  (let [delay-ms (case provider
                   :openai 120    ; 500/min = ~120ms between
                   :claude 60     ; 1000/min = ~60ms
                   :gemini 1000)] ; 60/min = ~1000ms
    (Thread/sleep delay-ms)
    (f)))
```

## Cost Optimization

```clojure
;; Use cheaper models for simple tasks
(def task-appropriate-config
  {:simple-task {:ai-provider :openai :model "gpt-3.5-turbo"}
   :complex-task {:ai-provider :claude :model "claude-3-opus-20240229"}
   :free-tier {:ai-provider :gemini :model "gemini-pro"}})
```

## The Philosophy

As Maciej Szajna argues in the original blog post:

> "Clojure's slow start time makes it unworkable without the REPL... The idea of a reloaded workflow could be reduced to this recipe: contain all resources that may need to be disposed of within a single system object and always dispose of it before reloading."

This pattern achieves that goal with minimal ceremony:
- Resources are contained in the `with-open` scope
- Cleanup is guaranteed by the macro
- Reloading is safe because resources are properly closed
- Dependencies are explicit through lexical scope
- Provider switching is seamless through the port interface

## Best Practices

1. **Use the protocol, not the implementation** - Always depend on `AIService`, not specific providers
2. **Handle failures gracefully** - Each provider can fail differently
3. **Test with multiple providers** - Ensure your app works with any provider
4. **Monitor usage** - Track API calls and costs per provider
5. **Use appropriate models** - Don't use GPT-4 for simple tasks
6. **Cache responses** - Reduce API calls for repeated queries

## When to Use This Pattern

✅ **Good fit when:**
- You want minimal framework overhead
- You prefer explicit, visible dependencies
- You value simplicity and standard Clojure idioms
- Your system has a clear startup/shutdown lifecycle
- You want guaranteed resource cleanup
- You need provider flexibility

❌ **Consider alternatives when:**
- You need complex lifecycle states (paused, resumed, etc.)
- You require runtime reconfiguration without restart
- You have circular dependencies (though these should be avoided anyway)
- You need dynamic component discovery

## References

- [Original article: Reloaded Workflow Out of the Box](https://medium.com/@maciej.szajna/reloaded-workflow-out-of-the-box-be6b5f38ea98)
- [Hexagonal Architecture (Ports and Adapters)](https://alistair.cockburn.us/hexagonal-architecture/)
- [Clojure with-open documentation](https://clojuredocs.org/clojure.core/with-open)
- [Stuart Sierra's Reloaded Workflow](https://github.com/stuartsierra/reloaded)
- [OpenAI API Documentation](https://platform.openai.com/docs)
- [Anthropic Claude API](https://docs.anthropic.com)
- [Google Gemini API](https://ai.google.dev)

## License

This pattern is based on ideas from the Clojure community and is free to use in your projects.