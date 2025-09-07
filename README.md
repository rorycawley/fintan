Fintan
======

*Fintan is a free, open-source Clojure library for building auditable, composable LLM agents with data‑first design, structured observability, and pluggable reasoning.*

> Name: “Fintan” after Fintan mac Bóchra (“the Wise”), the mythic seer who survives the flood and remembers all histories. (Wikipedia)

[<img src="./assets/fintan_logo.svg" alt="Fintan" width="200">](https://github.com/rorycawley/fintan)

**Status**: pre-alpha. APIs may shift; core ideas are stable.

---

## TL;DR

**Pain today**
- Re-scaffolding the same bits (model calls, tools, prompts, short-term history).
- Opaque behavior; logs are noisy and unauditable.
- Brittle tool wiring; loosely typed params; vendor-leaky failures.
- Hard to swap models/tools/loops without invasive changes.

**What Fintan is**
- A small set of **data-first** components for LLM agents.
- **Sense → Think → Act boundary** with a **pluggable loop**: ReAct, ReWOO, CRITIC (bring your own).
- **Uniform model adapter**, **typed tools** with schema + safe envelopes, **short-term memory**, **versioned prompt packs**, and **structured observability** (human transcript + machine `:trace`).
- **Normalized error taxonomy** for actionable failures.

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

> Until the first release, consume via `:git/tag`.


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


---

## Core Ideas & Architecture

Fintan exposes a **Sense → Think → Act** boundary with composable components — the exact loop shape is **pluggable** and depends on the chosen reasoner (e.g., ReAct, ReWOO, CRITIC):

```
(goal) → preprocess → reasoner (plan/decide) → tool exec ↔ memory ↔ prompts → observe (trace) → result
```

- **Model Adapter** (`fintan.model/*`): normalized `invoke`/`stream` over providers (OpenAI, Anthropic, local LLMs, etc.).
- **Reasoners** (`fintan.reasoner/*`): strategy objects implementing a uniform protocol.
  - `react`: interleave Thought → Action → Observation.
  - `rewoo`: plan with outside-observation orientation.
  - `critic`: self-check with tool-grounded critique.
- **Tools** (`fintan.tool/*`): typed, documented, testable capabilities with consistent result envelopes (`tool/->ok`, `tool/->error`).
- **Memory** (`fintan.memory/*`): short-term buffer with token budgeting and summarization hooks.
- **Prompt Packs** (`fintan.prompt/*`): declarative, versioned prompts with schema + tests.
- **Observability** (`fintan.observe/*`): `:transcript` (for humans) and `:trace` (for machines), both structured EDN.
- **Errors** (`fintan.error/*`): normalized taxonomy with remediation hints.

### Data Shapes (EDN)

**Agent config**

```clojure
{:model    <model-adapter>
 :reasoner <reasoner>
 :tools    [<tool> ...]
 :memory   {:short-term {:max-tokens int :policy #{:evict-oldest :summarize}}}
 :prompts  {:pack string :version string}
 :observe  {:trace? boolean :transcript? boolean}}
```

**Trace event**

```clojure
{:step 3
 :stage       :act
 :reasoner    :react
 :thought     "Search official source for latest Clojure version."
 :action      {:tool "web.search" :args {:q "site:clojure.org" :n 5}}
 :observation {:items 5}
 :latency-ms  412
 :tokens      {:prompt 128 :completion 47}
 :errors      []}
```

---

## Tools: Just-in-Time Discovery & Safe Execution

- **Describe once**: each tool declares `:name`, `:doc`, `:args` schema, and a single **effectful** function.
- **Discovery**: the reasoner selects candidate tools based on goal → tool schema affinity + optional embeddings over `:doc`.
- **Execution**: arguments are validated pre-call; results are normalized post-call; tool errors never leak vendor exceptions.

```clojure
(tool/defn-tool
  {:name "fs.read"
   :doc  "Read a UTF-8 file."
   :args {:path string?}
   :f (fn [{:keys [path]}]
        (try (tool/->ok {:text (slurp path)})
             (catch java.io.FileNotFoundException e
               (tool/->error :tool/not-found {:path path}))
             (catch Exception e
               (tool/->error :tool/failure {:hint "check permissions"}))))})
```

---

## Prompt Packs: Reproducible, Versioned, Validated

Prompt packs are EDN bundles:

```edn
{:id "default"
 :version "0.1.0"
 :components {:agent/system    "You are Fintan…"
              :reasoner/react  "Thought: … Action: … Observation: …"
              :critic/validator "Use tools to verify …"}
 :schema {:components {:agent/system string? :reasoner/react string?}}
 :tests [{:input {:goal "2+2"} :assert [:contains? "4"]}]}
```

- **Versioned** with SemVer and optional `:sha` for immutability.
- **Validated** against a schema (Malli/spec); enforcement at load time.
- **Rollout**: switch packs per component or per environment safely.

---

## Extend

- **New tool**: use `tool/defn-tool` with an `:args` schema; return `tool/->ok` or `tool/->error`.
- **New reasoner**: implement `(decide [this state])` to pick the next step or finalize an answer.
- **New model**: implement the model adapter protocol (`invoke`/`stream`).

---

## Memory: Short-Term, Structured

- **Budgeting**: token-aware buffer with `:policy` controls (evict oldest vs. summarize).
- **Shape**: messages carry roles (`:user`, `:assistant`, `:tool`) and annotations (e.g., `:source`, `:tool/id`).
- **Pluggable summarizer**: inject your own reducer `(messages → summary)`.

---

## Observability: Transcript + Trace

- **Transcript**: readable, line-by-line view suitable for audits and UX.
- **Trace**: machine-parsable EDN stream; each frame has `:stage`, `:thought`, `:action`, timings, tokens, errors.
- **Sinks**: print, file, ring buffer, or forward to your metrics/log pipeline.

```clojure
(f/on-trace agent (fn [frame] (tap> frame)))
```

---

## Error Taxonomy (normalized)

| Code                      | Meaning                       | Remediation hint                                  |
| ------------------------- | ----------------------------- | ------------------------------------------------- |
| `:config/invalid`         | Bad/missing config            | Validate config; see required keys.               |
| `:model/timeout`          | Model did not respond in time | Increase `:timeout-ms` or reduce step/tool count. |
| `:model/rate-limit`       | Provider throttled            | Backoff + retry; enable queueing.                 |
| `:model/unsupported-op`   | Adapter lacks capability      | Switch adapter/model or gate the feature.         |
| `:tool/not-found`         | Tool missing/unknown          | Check registry; correct `:name` / wire-up.        |
| `:tool/invalid-args`      | Args failed schema validation | Fix callsite; inspect `:explain`.                 |
| `:tool/failure`           | Tool threw/couldn’t complete  | Inspect `:cause`; apply fallback.                 |
| `:memory/overflow`        | Token budget exceeded         | Tighten summaries; raise budget; stream.          |
| `:prompt/invalid`         | Pack failed schema/test       | Fix prompt pack; bump version.                    |
| `:reasoner/loop-detected` | Non-terminating plan detected | Cap steps; adjust stopping criteria.              |
| `:io/network`             | Transient IO issue            | Retry with jitter; circuit-break on cascades.     |
| `:policy/violation`       | Safety/policy guard triggered | Escalate to human; redact inputs.                 |
| `:internal/unexpected`    | Bug/invariant broke           | Open an issue with `:trace` and minimal repro.    |

All user-visible failures are rendered with a concise, friendly message + a structured diagnostic map.

---

## Reasoners (pluggable)

Each reasoner implements `Reasoner`:

```clojure
(defprotocol Reasoner
  (decide [this state] "Given {:goal … :memory … :tools …}, return next step or final answer."))
```

Provided strategies:

- **`react`** — interleaves Thought → Action → Observation to ground reasoning in tools.
- **`rewoo`** — plan-first (plan-and-execute); leverages external observations; fewer LLM calls.
- **`critic`** — generate → tool-critique → revise; uses tools to self-check and correct.

Bring your own by implementing the protocol; the rest of the system stays unchanged.

---

## Data-First Functional Core

- **Push / Pull / Transform split**: IO at edges (push/pull); transforms are pure, noun-ish names.
- **Option maps over arity ladders**: required positionals, everything else threaded as immutable options.
- **Single local atom** for mutable agent state; no STM unless coordinated writes are necessary.
- **Dynamic scope is internal**: bind just-in-time at call sites, never burden callers.
- **Visible side-effects**: naming/whitespace make effects obvious; `do` blocks where clarity helps.
- **Narrow accessors**: `get`/`keys`/`nth` over generic seq ops when that’s what you mean.
- **`letfn`** only for mutual recursion; prefer `let` + named fns otherwise.
- **Style nudges**: prefer `</<=` ordering; accumulators offer 0/1/2-arity + variadic reduction where sensible.

These principles make Fintan predictable to extend and simple to test.

---

## Configuration

```clojure
{:model {:provider :openai :id "gpt-4.1" :params {:temperature 0.1}}
 :reasoner {:type :react :max-steps 8}
 :tools [#ref …]
 :memory {:short-term {:max-tokens 2000 :policy :evict-oldest}}
 :prompts {:pack "default" :version "0.1.0"}
 :observe {:trace? true :transcript? true}}
```

- Providers pick up credentials from env by default (e.g., `OPENAI_API_KEY`).
- Any unknown keys are preserved and available to downstream layers.

---

## Testing

- **Golden transcript tests**: lock expected transcripts for critical flows.
- **Trace schema**: validate each frame in CI.
- **Property tests**: tools and transforms with `test.check`/Malli.
- **Prompt pack tests**: embedded examples must pass before publish.

---

## Roadmap

- [ ] Streaming responses with backpressure + live trace.
- [ ] Built-in Red Team tool for prompt-injection hardening.
- [ ] Eval harness (task suites, cost/latency/fidelity metrics).
- [ ] More adapters (Anthropic, Azure OpenAI, local GGUF via llama.cpp).
- [ ] Tool embeddings for smarter JIT discovery.
- [ ] Long-term memory plug-ins (vector stores).
- [ ] CLI + minimal web console for traces.

---

## FAQ

**Is this a framework or a library?**

> A library: data-first components you can compose inside your app. Bring your own HTTP client, logging, and metrics if you prefer.

**Does Fintan do multi-agent orchestration?**

> Not directly; model it as tools or compose multiple agents. A "Manager" pattern can be expressed as a reasoner + tools.

**What about safety and policies?**

> Policy checks appear as transforms and trace frames; sensitive tools can require explicit allow-lists and redaction.

---

## Contributing

Issues and PRs welcome. Please attach a minimal repro and (if relevant) a redacted `:trace`. Run tests with `clojure -X:test`.


---

## Acknowledgements

This project is inspired by this article by [Pragyan Tripathi](https://bytes.vadeai.com/escaping-framework-prison-why-we-ditched-agentic-frameworks-for-simple-apis/) and the standard agent talk by [Sean Blanchfield](https://github.com/jentic/standard-agent).

## License

This project is licensed under the terms of the Apache 2.0 license. Please refer to the [LICENSE](./LICENSE) file for the full terms.
