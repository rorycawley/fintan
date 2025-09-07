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

---

## Core Ideas & Architecture

Fintan follows a **Sense → Think → Act** loop with composable components:

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

- `` — interleaves Thought → Action → Observation to ground reasoning in tools.
- `` — plans first; leans on external observations to verify.
- `` — generates answer then critiques using tools; revises when necessary.

Bring your own by implementing the protocol; the rest of the system stays unchanged.

---

## Data-First Functional Core

- **Push / Pull / Transform split**: IO at edges (push/pull); transforms are pure, noun-ish names.
- **Option maps over arity ladders**: required positionals, everything else threaded as immutable options.
- **Single local atom** for mutable agent state; no STM unless coordinated writes are necessary.
- **Dynamic scope is internal**: bind just-in-time at call sites, never burden callers.
- **Visible side-effects**: naming/whitespace make effects obvious; `do` blocks where clarity helps.
- **Narrow accessors**: `get`/`keys`/`nth` over generic seq ops when that’s what you mean.
- `` only for mutual recursion; prefer `let` + named fns otherwise.
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

-

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
