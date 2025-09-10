# Complete Tutorial: Clojure Decision Agent Architecture

## Table of Contents

1. [Overview & Architecture](#overview--architecture)
2. [Domain Modeling & Data Structures](#domain-modeling--data-structures)
3. [Agent-Based Decision Making](#agent-based-decision-making)
4. [Functional Programming Patterns](#functional-programming-patterns)
5. [Core Decision Logic](#core-decision-logic)
6. [Heuristics & Scoring](#heuristics--scoring)
7. [Side Effect Management](#side-effect-management)
8. [Usage & Extension](#usage--extension)

[Article on simple agent](https://www.anupshinde.com/simple-decision-agent-python/)

## Overview & Architecture

This code implements a **rule-based decision agent** that recommends meals based on available ingredients, hunger level,
and time constraints. It's an excellent example of functional programming principles applied to agent-based systems.

### Key Design Principles

The code follows several important architectural patterns:

**Agent-Based Design Pattern:**

- **Perceive**: Process environmental inputs (ingredients, hunger, time)
- **Decide**: Apply rules to select best option
- **Act**: Format and return recommendation

**Functional Programming Principles:**

- Immutable data structures throughout
- Pure functions separated from side effects
- Data transformations over object mutation
- Clear separation of concerns

**"Elements of Clojure" Principles:**

- Narrow, consistent names that reveal intent
- Clear function boundaries and responsibilities
- Option map pattern for complex parameters
- Explicit validation at process boundaries

## Domain Modeling & Data Structures

### Recipe Representation

```clojure
(defn recipe
  [{:keys [name ingredients cook-time difficulty] :as options}]
  ;; Validation logic here
  {:name        name
   :ingredients ingredients
   :cook-time   cook-time
   :difficulty  difficulty})
```

**Key Concepts:**

1. **Option Map Pattern**: Instead of positional parameters, this uses a destructured map. This makes function calls
   self-documenting and order-independent:
   ```clojure
   (recipe {:name "Pasta" :ingredients #{:pasta :oil} :cook-time 12 :difficulty :easy})
   ```

2. **Precondition Validation**: The `:pre` metadata validates inputs at runtime:
   ```clojure
   {:pre [(set/subset? ks allowed)            ; no unknown keys
          (string? name)                      ; name must be string
          (set? ingredients)                  ; ingredients must be set
          (every? keyword? ingredients)       ; all ingredients are keywords
          (pos-int? cook-time)               ; positive integer time
          (#{:easy :medium :hard} difficulty)]} ; valid difficulty
   ```

3. **Keywords as Enums**: Clojure keywords (`:easy`, `:medium`, `:hard`) provide type-safe enumeration without requiring
   separate enum definitions.

### Agent State Management

```clojure
(defn agent-state
  [available-ingredients hunger-level available-time]
  {:available-ingredients available-ingredients
   :hunger-level          hunger-level
   :available-time        available-time})
```

**Design Insights:**

- State is a **pure data structure** - no hidden mutable state
- Validation ensures data integrity at construction time
- The agent's "perception" of the world is captured in this immutable snapshot

## Agent-Based Decision Making

### The Perception Phase

```clojure
(defn perceive
  [ingredients-list hunger-level available-time]
  (let [clean-ingredients (->> ingredients-list
                               (keep identity)
                               (keep (fn [x] ...))
                               set)]
    (agent-state clean-ingredients hunger-level available-time)))
```

**What's Happening:**

1. **Input Sanitization**: Handles mixed input types (keywords, strings)
2. **Normalization**: Converts strings to lowercase keywords
3. **Filtering**: Removes invalid/empty values using `keep`
4. **Threading Macro**: `->>` pipes data through transformations

**Why This Matters**: Real-world agents must handle messy, inconsistent input. This function creates a clean boundary
between the external world and internal logic.

### The Decision Phase

```clojure
(defn decide
  [state]
  (let [candidates (feasible-recipes recipes state)]
    (if (seq candidates)
      (best-recipe candidates state)
      :no-feasible-recipes)))
```

**Decision Pipeline:**

1. Filter recipes by feasibility constraints
2. Apply scoring heuristics to rank options
3. Select optimal choice
4. Handle edge case of no viable options

## Functional Programming Patterns

### Higher-Order Functions

**Filter Pattern:**

```clojure
(defn feasible-recipes
  [recipe-collection state]
  (filter #(feasible? % state) recipe-collection))
```

The `#()` syntax creates an anonymous function. This is equivalent to:

```clojure
(filter (fn [recipe] (feasible? recipe state)) recipe-collection)
```

**Max-Key Pattern:**

```clojure
(defn best-recipe
  [candidates state]
  (when (seq candidates)
    (apply max-key #(priority-score % state) candidates)))
```

`max-key` finds the element that maximizes a given function - perfect for optimization problems.

### Predicate Functions

```clojure
(defn ingredients-match?
  [available-ingredients required-ingredients]
  (set/subset? required-ingredients available-ingredients))

(defn time-sufficient?
  [available-time required-time]
  (>= available-time required-time))
```

**Design Pattern**: Small, focused predicate functions that:

- Have descriptive names ending in `?`
- Test single conditions
- Are easily composable
- Make complex logic readable

### Composition and Pure Functions

```clojure
(defn feasible?
  [recipe state]
  (and (ingredients-match? (:available-ingredients state)
                           (:ingredients recipe))
       (time-sufficient? (:available-time state)
                         (:cook-time recipe))))
```

This demonstrates **functional composition** - building complex logic from simple, testable parts.

## Core Decision Logic

### Constraint Satisfaction

The agent uses **constraint satisfaction** to filter options:

1. **Ingredient Constraints**: Must have all required ingredients
2. **Time Constraints**: Must fit within available time
3. **Combined Feasibility**: Both constraints must be satisfied

### Set Operations

```clojure
(set/subset? required-ingredients available-ingredients)
```

Clojure's set operations provide clean, mathematical semantics for relationship testing.

## Heuristics & Scoring

### Multi-Factor Scoring System

```clojure
(defn priority-score
  [recipe state]
  (let [urgency (urgency-score (:hunger-level state))
        pressure (time-pressure (:available-time state))
        efficiency (time-efficiency recipe)
        penalty (difficulty-penalty recipe)]
    (- (+ urgency pressure efficiency) penalty)))
```

**Scoring Components:**

1. **Urgency Score**: Higher hunger = higher priority
   ```clojure
   (* hunger-level 2)
   ```

2. **Time Pressure**: Less time = more pressure to choose quick options
   ```clojure
   (double (/ 60 (max available-time 1)))
   ```

3. **Time Efficiency**: Rewards faster recipes
   ```clojure
   (double (/ 60 (:cook-time recipe)))
   ```

4. **Difficulty Penalty**: Penalizes complex recipes
   ```clojure
   (case (:difficulty recipe)
     :easy   0
     :medium 5
     :hard   10)
   ```

**Why This Design Works:**

- Each factor is calculated independently
- Scoring is transparent and tunable
- Easy to add new factors
- Mathematical combination allows balancing trade-offs

### Pattern Matching with `case`

```clojure
(case (:difficulty recipe)
  :easy 0
  :medium 5
  :hard 10
  10) ; defensive default
```

Clojure's `case` provides efficient pattern matching with a defensive default value.

## Side Effect Management

### Pure Core, Impure Shell

The code demonstrates the **"pure core, impure shell"** pattern:

**Pure Functions** (no side effects):

```clojure
(defn recommend-meal
  [ingredients-list hunger-level available-time]
  (let [state (perceive ingredients-list hunger-level available-time)
        decision (decide state)]
    (act decision state)))
```

**Impure Functions** (with side effects):

```clojure
(defn meal-agent!
  [ingredients-list hunger-level available-time]
  (let [recommendation (recommend-meal ingredients-list hunger-level available-time)]
    (println recommendation)  ; Side effect!
    recommendation))
```

**Why This Matters:**

- Pure functions are easy to test
- Side effects are isolated and explicit
- `!` suffix marks functions with side effects
- Business logic remains testable

### Let Bindings for Clarity

```clojure
(let [state (perceive ingredients-list hunger-level available-time)
      decision (decide state)]
  (act decision state))
```

`let` bindings make data flow explicit and avoid deeply nested function calls.

## Usage & Extension

### Basic Usage

```clojure
;; Simple recommendation
(recommend-meal [:eggs :rice :oil] 4 20)

;; With side effects
(meal-agent! [:eggs :rice :oil] 4 20)

;; Debugging analysis
(analyze-decision [:eggs :pasta :oil] 3 15)
```

### Extension Points

**1. Add New Recipes:**

```clojure
(def my-recipes
  (conj recipes
        (recipe {:name        "Smoothie"
                 :ingredients #{:fruit :yogurt}
                 :cook-time   2
                 :difficulty  :easy})))
```

**2. Custom Scoring Factors:**

```clojure
(defn dietary-score [recipe dietary-restrictions]
  ;; Custom scoring logic
  )

(defn enhanced-priority-score [recipe state dietary-restrictions]
  (+ (priority-score recipe state)
     (dietary-score recipe dietary-restrictions)))
```

**3. Dynamic Recipe Loading:**

```clojure
(defn load-recipes-from-db []
  ;; Database integration
  )

(defn decide-with-dynamic-recipes [state]
  (let [current-recipes (load-recipes-from-db)]
    ;; Decision logic with fresh recipes
    ))
```

### Testing Strategies

**Unit Testing Individual Components:**

```clojure
(deftest test-ingredients-match
         (is (ingredients-match? #{:eggs :oil} #{:eggs}))
         (is (not (ingredients-match? #{:eggs} #{:eggs :oil}))))

(deftest test-recipe-creation
         (is (= "Pasta" (:name (recipe {:name        "Pasta"
                                        :ingredients #{:pasta}
                                        :cook-time   10
                                        :difficulty  :easy})))))
```

**Integration Testing:**

```clojure
(deftest test-end-to-end
         (let [result (recommend-meal [:eggs :oil] 3 10)]
           (is (string? result))
           (is (re-find #"Suggested Meal:" result))))
```

### Performance Considerations

**Lazy Sequences**: The `filter` operations return lazy sequences, so filtering only happens when results are consumed.

**Set Operations**: Using sets for ingredient matching provides O(1) lookup performance.

**Immutable Data**: No defensive copying needed - data structures are inherently safe to share.

## Advanced Concepts Demonstrated

### Option Map Pattern

Provides named parameters in a language without them natively.

### Threading Macros

`->>` makes data transformation pipelines readable.

### Keyword Namespacing

Keywords provide lightweight enums without ceremony.

### Precondition Validation

Runtime validation at function boundaries catches errors early.

### Pure Functional Design

Separating computation from side effects enables testing and reasoning.

This decision agent demonstrates how functional programming principles create maintainable, testable, and extensible
systems. The clear separation of concerns and immutable data structures make it easy to understand, modify, and debug.

## Benefits and Limitations

### Why This Approach Works Well

**Advantages:**

- **Predictable**: Same inputs always produce same outputs
- **Fast**: No network calls or complex model inference
- **Cheap**: Minimal computational resources required
- **Debuggable**: Easy to trace exactly why a decision was made
- **Testable**: Pure functions are straightforward to unit test
- **Extensible**: New rules and scoring factors can be added incrementally

**Performance Characteristics:**

- **Latency**: Sub-millisecond response times
- **Throughput**: Can handle thousands of decisions per second
- **Memory**: Minimal memory footprint
- **Cost**: Nearly zero operational cost

### Limitations to Consider

**Where Rule-Based Agents Fall Short:**

- **Rigid**: Cannot adapt to unexpected situations
- **Maintenance**: Rules need manual updates as requirements change
- **Natural Language**: Cannot understand "I want something light but filling"
- **Context**: Doesn't learn from user preferences over time
- **Nuance**: Cannot handle edge cases not explicitly programmed

### Evolution Path

This rule-based foundation can evolve into more sophisticated systems:

**Level 1: Enhanced Rules**

- Add more sophisticated scoring factors
- Include user preference learning
- Dynamic recipe loading from databases

**Level 2: Hybrid Approach**

- Use LLM for natural language understanding
- Apply rule-based logic for final decisions
- Best of both worlds: flexibility + predictability

**Level 3: Learning Agent**

- Track user feedback on recommendations
- Adjust scoring weights based on preferences
- Maintain rule-based core with learned parameters

The key insight is that **simple, well-designed rule-based agents often solve 80% of real-world problems** with 20% of
the complexity. Starting with this foundation provides a solid base for evolution when more sophisticated capabilities
are truly needed.