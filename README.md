Fintan
======

Fintan is a free, open-source Clojure library for building auditable, composable LLM agents with data‑first design, structured observability, and pluggable reasoning.

> Name: “Fintan” after Fintan mac Bóchra (“the Wise”), the mythic seer who survives the flood and remembers all histories — a fitting patron for deliberate, memory‑aware agents. (Wikipedia)

[<img src="./assets/fintan_logo.svg" alt="Fintan" width="160">](https://github.com/rorycawley/fintan)

## Why Fintan?

The problem we see in practice
- Repeated scaffolding. Every team rewires LLM calls, tool lookup/exec, prompts, and short‑term history — leading to drift and rework.
- Opaque behavior. Agent “reasoning” is hard to audit; logs are noisy and unstructured.
- Brittle integrations. Tool parameters are loosely typed; errors leak vendor details; failures aren’t actionable.
- Composition pain. Swapping models, tools, or loops requires invasive changes.

What Fintan provides (roadmap):
- Model adapter (LLM). Uniform protocol over model vendors.
- Reasoner pluggability. ReAct, ReWOO, CRITIC (and your own) as interchangeable strategies.
- Just‑in‑time tool discovery + execution. Describe tools once; select and call them safely at runtime.
- Goal preprocessing. Normalize/validate goals before planning.
- Short‑term memory. Token‑budgeted, structured, replaceable.
- Prompt packs. Versioned & validated prompts per component for reproducibility and safe rollout.
- Observability with structure. Human‑readable transcript and machine‑readable :trace for each step.
- Clear error taxonomy. Normalized error types with remediation hints.
- Data‑first, functional core. Pure transforms inside; effects at the edges; transducers for scale.


## Acknowledgements

This project is inspired by this article by [Pragyan Tripathi](https://bytes.vadeai.com/escaping-framework-prison-why-we-ditched-agentic-frameworks-for-simple-apis/) and the standard agent talk by [Sean Blanchfield](https://github.com/jentic/standard-agent).

## License

This project is licensed under the terms of the Apache 2.0 license. Please refer to the [LICENSE](./LICENSE) file for the full terms.
