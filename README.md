Fintan
======

*Fintan is a free, open-source Clojure library for building auditable, composable LLM agents with data‑first design, structured observability, and pluggable reasoning.*

> Name: “Fintan” after Fintan mac Bóchra (“the Wise”), the mythic seer who survives the flood and remembers all histories. (Wikipedia)

[<img src="./assets/fintan_logo.svg" alt="Fintan" width="200">](https://github.com/rorycawley/fintan)

**Status**: pre-alpha / design-first. Interfaces being designed; internals in motion.

## Why Fintan?

**The problem we see in practice**

- **Repeated scaffolding.** Every team rewires LLM calls, tool lookup/exec, prompts, and short-term history — leading to drift and rework.
- **Opaque behavior.** Agent “reasoning” is hard to audit; logs are noisy and unstructured.
- **Brittle integrations.** Tool parameters are loosely typed; errors leak vendor details; failures aren’t actionable.
- **Composition pain.** Swapping models, tools, or loops requires invasive changes.

**What Fintan provides**

- **Model adapter (LLM).** Uniform protocol over model vendors.
- **Reasoner pluggability.** ReAct, ReWOO, CRITIC (and your own) as interchangeable strategies.
- **Just-in-time tool discovery + execution.** Describe tools once; select and call them safely at runtime.
- **Goal preprocessing.** Normalize/validate goals before planning.
- **Short-term memory.** Token-budgeted, structured, replaceable.
- **Prompt packs.** Versioned & validated prompts per component for reproducibility and safe rollout.
- **Observability with structure.** Human-readable transcript **and** machine-readable `:trace` for each step.
- **Clear error taxonomy.** Normalized error types with remediation hints.
- **Data-first, functional core.** Pure transforms inside; effects at the edges; transducers for scale.

---

## Install

Using **deps.edn** (recommended):

```clojure
{:deps {io.github.your-org/fintan {:git/tag "v0.1.0" :git/sha "REPLACE-ME"}}
 :aliases
 {:dev {:extra-paths ["dev"]
        :extra-deps {}}}}
```

Leiningen (optional):

```clojure
:dependencies [[io.github.your-org/fintan "0.1.0-SNAPSHOT"]]
```

> Artifact coordinates will be finalized after the first public release. For now, consume via `:git/tag`.


---

## Quickstart

```clojure
(ns demo
  (:require [fintan.core :as f]
            [fintan.model :as model]
            [fintan.model.openai :as openai]      ; example adapter
            [fintan.reasoner.react :as react]
            [fintan.tool :as tool]))

;; 1) Define a tool with a schema and a pure wrapper
(def web-search
  (tool/defn-tool
    {:name "web.search"
     :doc  "Search the web (returns top N snippets)."
     :args {:q string? :n pos-int?}
     :f    (fn [{:keys [q n]}]
             ;; side-effectful boundary lives here
             (tool/->ok {:items (search! q n)}))}))

;; 2) Choose a model + reasoner
(def m (openai/->model {:id "gpt-4.1" :temperature 0.1}))
(def r (react/->reasoner {:max-steps 8}))

;; 3) Assemble the agent
(def agent
  (f/agent {:model   m
            :reasoner r
            :tools   [web-search]
            :memory  {:short-term {:max-tokens 2000 :policy :evict-oldest}}
            :prompts {:pack "default" :version "0.1.0"}
            :observe {:trace? true :transcript? true}}))

;; 4) Ask with a normalized goal map
(def result
  (f/ask agent {:goal "Find the latest stable Clojure version and cite the source."
                :constraints {:sources [:official]}
                :timeout-ms 15000}))

;; => {:answer "Clojure X.Y.Z …", :transcript [...], :trace [...], :errors []}
```



## Acknowledgements

This project is inspired by this article by [Pragyan Tripathi](https://bytes.vadeai.com/escaping-framework-prison-why-we-ditched-agentic-frameworks-for-simple-apis/) and the standard agent talk by [Sean Blanchfield](https://github.com/jentic/standard-agent).

## License

This project is licensed under the terms of the Apache 2.0 license. Please refer to the [LICENSE](./LICENSE) file for the full terms.
