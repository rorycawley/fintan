# Fintan

A lightweight, composable library for building AI agents with pluggable LLMs, reasoning strategies, memory backends, and
tool providers.

> Name: “Fintan” after Fintan mac Bóchra (“the Wise”), the mythic seer who survives the flood and remembers all
> histories. (Wikipedia)

[<img src="./assets/fintan_logo.svg" alt="Fintan" width="200">](https://github.com/rorycawley/fintan)

## Overview

Fintan provides a modular architecture that bridges LLMs with tools through a flexible reasoning loop. It's deliberately
small and readable, allowing you to understand how agents work and build your own without boilerplate.

**Key Features:**

- **Composable Core** – Swap LLMs, reasoners, memory, and tools independently
- **Multiple Reasoning Strategies** – Ships with ReWOO and ReACT implementations
- **Goal Preprocessing** – Optional component to handle ambiguous or conversational inputs
- **Just-in-Time Tools** – Dynamic tool loading based on task context
- **Memory Management** – Pluggable memory backends with conversation history

## Architecture

```
┌─────────────────────────────────────────┐
│                Fintan                   │
│  ┌─────────────────────────────────┐    │
│  │   Goal Preprocessor (Optional)  │    │
│  └─────────────────────────────────┘    │
│  ┌─────────────────────────────────┐    │
│  │           Reasoner              │    │
│  │   (ReWOO, ReACT, or Custom)     │    │
│  └─────────────────────────────────┘    │
│  ┌──────────┬────────────┬─────────┐    │
│  │   LLM    │   Memory   │  Tools  │    │
│  └──────────┴────────────┴─────────┘    │
└─────────────────────────────────────────┘
```

## Usage

### Pre-built Agents

```clojure
(ns my-app.core
  (:require [fintan.prebuilt :as prebuilt]))

(def agent (prebuilt/create-rewoo-agent {:model "gpt-4"}))
(def result (prebuilt/solve agent "Find recent news about AI"))
```

### Custom Agent Composition

```clojure
(ns my-app.core
  (:require [fintan.core :as agent]
            [fintan.llm.openai :as openai]
            [fintan.tools.api :as api-tools]
            [fintan.memory :as memory]
            [fintan.reasoner.rewoo :as rewoo]
            [fintan.goal-preprocessor.conversational :as conv]))

;; Build your own configuration
(let [llm (openai/create {:model "gpt-4"})
      tools (api-tools/create)
      memory (memory/create-atom)  ; -> (atom {})
      reasoner (rewoo/create-reasoner {:llm llm :tools tools :memory memory})
      preproc (conv/create {:llm llm})] ; Optional
  (def my-agent
    (agent/create-agent
      {:llm               llm
       :tools             tools
       :memory            memory
       :reasoner          reasoner
       :goal-preprocessor preproc})))

(agent/solve my-agent "What's the weather today?")
```

> **Tip:** Swap in other providers by requiring their namespaces (e.g., `fintan.llm.anthropic`) and passing the
> corresponding `:model`.

## Core Components

| Component             | Protocol            | Purpose                                                 |
|-----------------------|---------------------|---------------------------------------------------------|
| **Fintan**            | Main record         | Coordinates all components                              |
| **LLM**               | `LanguageModel`     | Wraps language models (OpenAI, Anthropic, etc.)         |
| **Goal Preprocessor** | `GoalPreprocessor`  | Clarifies ambiguous goals, handles conversation context |
| **Reasoner**          | `ReasonerEngine`    | Implements reasoning strategy (ReWOO, ReACT)            |
| **Tools**             | `ToolProvider`      | Provides external actions/APIs                          |
| **Memory**            | Atom containing map | Key–value storage for state                             |

## Extending

Create custom components by implementing the protocols:

- **New Reasoner**: Implement `ReasonerEngine`
- **New LLM**: Implement `LanguageModel` for different providers
- **New Tools**: Implement `ToolProvider` for custom tools
- **New Memory**: Prefer an atom-based implementation
- **New Preprocessor**: Implement `GoalPreprocessor` for input validation/clarification

**Memory helper** (explicit atom):

```clojure
(ns fintan.memory)

(defn create-atom []
  (atom {}))
```

## Configuration

Create a `config.edn` file with nested structure:

```clojure
{:agent    {:conversation-history-window 5}

 :llm      {:provider    :openai
            :api-key     "sk-..."
            :model       "gpt-4"
            :temperature 0.7}

 :reasoner {:type           :rewoo
            :max-iterations 20
            :max-retries    2}

 :tools    {:search-limit 10
            :timeout-ms   5000}

 :memory   {:type        :atom
            :persistence nil}}
```

## Requirements

- Clojure **1.11+**
- Java **11+**
- At least one LLM API key (OpenAI, Anthropic, or Google)

## Design Notes – *Elements of Clojure* Alignment

- **Names (Narrow & Consistent):** Protocols use natural names (`ReasonerEngine`, `LanguageModel`, …); functions like
  `solve`, `execute-tool` signal effects.
- **Indirection:** Protocols separate interface from implementation; modules have clear boundaries.
- **Composition:** Demonstrated with pre-built agents and custom assembly; processes emphasize **pull → transform → push
  **.
- **State:** Memory is explicitly an **atom containing a map** for clarity.

---

## FAQ

**Is this a framework or a library?**

> A library: data-first components you can compose inside your app. Bring your own HTTP client, logging, and metrics if
> you prefer.

**Does Fintan do multi-agent orchestration?**

> Not directly; model it as tools or compose multiple agents. A "Manager" pattern can be expressed as a reasoner +
> tools.

**What about safety and policies?**

> Policy checks appear as transforms and trace frames; sensitive tools can require explicit allow-lists and redaction.

---

## Contributing

Issues and PRs welcome. Please attach a minimal repro and (if relevant) a redacted `:trace`. Run tests with
`clojure -X:test`.


---

## Acknowledgements

This project is inspired by this article
by [Pragyan Tripathi](https://bytes.vadeai.com/escaping-framework-prison-why-we-ditched-agentic-frameworks-for-simple-apis/)
and the standard agent talk by [Sean Blanchfield](https://github.com/jentic/standard-agent).

## License

This project is licensed under the terms of the Apache 2.0 license. Please refer to the [LICENSE](./LICENSE) file for
the full terms.
