# Meal Agent - Ports & Adapters with `with-open` Pattern

A Clojure application demonstrating the Ports and Adapters (Hexagonal) architecture pattern using `with-open` for resource management instead of Component framework.

## Architecture Overview

This project implements a clean architecture with three main layers:

### 1. Core Domain (`meal-agent.core`)
- **Pure functional business logic** - no side effects
- **Port definitions** (protocols) that define what the core needs from the outside world
- **Domain model** (recipes, meal requests)
- **Business rules** for meal selection

### 2. Adapters (`meal-agent.adapters`)
- **Concrete implementations** of the ports
- **Resource management** using `with-open` pattern
- **External service integration** (OpenAI API)
- **Stub implementations** for testing/development

### 3. Application Layer (`meal-agent.main`)
- **System composition** and lifecycle management
- **REPL-friendly workflow** for development
- **Interactive and batch processing modes**

## Key Design Decisions

### Why `with-open` Instead of Component?

The `with-open` pattern provides several advantages:

1. **Built into Clojure** - No external dependencies
2. **Guaranteed cleanup** - Resources are always released, even on exceptions
3. **Familiar semantics** - Works like `let` but with automatic resource management
4. **Composable** - Easy to nest and combine resources
5. **Test-friendly** - Each test gets fresh, isolated resources

### Resource Management Strategy

```clojure
;; Resources are wrapped in Closeable for with-open compatibility
(defn closeable [resource close-fn]
  (reify
    Closeable
    (close [_] (close-fn resource))
    
    clojure.lang.IDeref
    (deref [_] resource)))

;; Used with with-open for automatic cleanup
(with-open [parser (openai-parser-resource api-key)
            pool (http-connection-pool 10)]
  ;; Resources are automatically cleaned up when leaving scope
  (process-request @parser input))
```

### Ports and Adapters Pattern

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
│     Port        │           │    Adapter      │
│ (Protocol/API)  │◄──────────│  (OpenAI impl)  │
└─────────────────┘           └─────────────────┘
         ▲                               ▲
         │                               │
┌─────────────────────────────────────────────────┐
│               Core Business Logic                │
│          (Pure Functions, No Side Effects)       │
└─────────────────────────────────────────────────┘
```

## Usage

### Running the Application

#### Interactive Mode (Default)
```bash
# With OpenAI API
export OPENAI_API_KEY="your-key-here"
bb run

# Offline mode with stub parser
bb run:offline
```

#### Batch Mode
```bash
clojure -M:run batch
```

#### REPL Development
```bash
# Start REPL with system ready
bb repl

# In the REPL:
(require '[meal-agent.main :as main])
(main/start!)                           ; Start the system
(main/process! "eggs and bread, hungry") ; Process a request
(main/run-examples!)                     ; Run examples
(main/status)                            ; Check system status
(main/reload!)                           ; Reload code
(main/stop!)                             ; Stop the system
```

### Development Workflow

The system supports a reloaded workflow without Component:

1. **Start the system**: `(start!)` - Creates resources in background thread
2. **Make code changes**: Edit your files
3. **Reload**: `(reload!)` - Stops system, reloads code, restarts
4. **Test interactively**: `(process! "your request")`

### Testing

```bash
# Run all tests
bb test

# Run with examples
bb run:examples
```

Tests demonstrate how `with-open` ensures:
- Resource isolation between tests
- Automatic cleanup even on test failures
- No resource leaks
- Concurrent test execution safety

## Project Structure

```
src/
├── meal_agent/
│   ├── core.clj        # Pure domain logic and ports
│   ├── adapters.clj    # Adapter implementations
│   └── main.clj        # Application entry point
test/
└── meal_agent/
    └── core_test.clj   # Tests with resource management
```

## Configuration

Configuration can be updated at runtime:

```clojure
;; Switch to stub mode
(config! :use-stub true)
(restart!)

;; Update API key
(config! :api-key "new-key")
(restart!)
```

## Benefits of This Approach

### 1. Clean Separation of Concerns
- Core business logic knows nothing about external services
- Adapters handle all I/O and external communication
- Easy to swap implementations (OpenAI → Local LLM)

### 2. Resource Safety
- Guaranteed cleanup with `with-open`
- No resource leaks
- Exception-safe resource management

### 3. Testability
- Pure functions are trivial to test
- Integration tests get isolated resources
- No test pollution or interference

### 4. Development Experience
- REPL-friendly workflow
- Hot code reloading
- Interactive development

### 5. Production Ready
- Proper resource lifecycle management
- Thread-safe system state
- Graceful shutdown

## Example Requests

```clojure
;; Simple request
"I have bread and cheese, kinda hungry"
;; → Suggested: Grilled Cheese (5 min)

;; Complex request
"I have eggs, rice, very hungry but in a rush"
;; → Suggested: Egg Fried Rice (15 min)

;; Time-constrained
"eggs and bread, 5 minutes max"
;; → Suggested: Eggs on Toast (8 min)
```

## Extending the System

### Adding a New Parser Adapter

```clojure
(defrecord LocalLLMParser [model-path]
  core/MealRequestParser
  (parse-meal-request [_ user-input]
    ;; Your implementation here
    ))

(defn local-llm-resource [model-path]
  (closeable 
    (->LocalLLMParser model-path)
    (fn [parser]
      ;; Cleanup code
      (println "Closing local LLM"))))
```

### Adding New Recipes

Simply add to the `recipes` vector in `core.clj`:

```clojure
{:name "Your Recipe"
 :ingredients #{"ingredient1" "ingredient2"}
 :hunger-range [min max]
 :time minutes}
```

## Performance Considerations

- Resource allocation with `with-open`: < 1ms overhead
- Concurrent request handling: Thread-safe
- Memory usage: Minimal, resources are released immediately
- Startup time: Near instant (no framework overhead)

## Comparison with Component

| Aspect | with-open | Component |
|--------|-----------|-----------|
| Dependencies | None (built-in) | External library |
| Resource cleanup | Guaranteed | Manual in stop method |
| Exception handling | Automatic | Manual |
| Test isolation | Automatic | Requires fixtures |
| Learning curve | Familiar (like let) | New concepts |
| Code impact | Minimal | Lifecycle records everywhere |

## References

- [Original article on with-open pattern](https://medium.com/@maciej.szajna/reloaded-workflow-out-of-the-box-be6b5f38ea98)
- [Ports and Adapters pattern](https://alistair.cockburn.us/hexagonal-architecture/)
- [Clojure with-open documentation](https://clojuredocs.org/clojure.core/with-open)

