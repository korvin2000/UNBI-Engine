# Implement a new UNBI Engine Nodes to work with LLM while simultaneously refining the UNBI architecture and code to meet new requirements

Design and implement new UNBI Engine nodes for interacting with different LLMs and LLM endpoints across a broad range of use cases. Develop a flexible, extensible solution, while trying to follow the project's architecture and concepts and implement an intuitive, user-friendly configuration interface for nodes. Critically review and improve, refine the existing project architecture where necessary. Use additional skills like engineering skill to improve results, architecture and design.

## Investigate and reuse before implementing

Thoroughly inspect the existing project and the `./biomd-process` claude code subproject. Use the latter as the primary baseline for endpoint connections, authentication, model selection, capabilities, parameter configuration, and request execution. Understand its provider-specific behavior and edge-case handling before adapting them. Reuse useful code and implementation experience, critically assessing their fit with this project's requirements.

Research current official documentation and relevant GitHub implementations, like for example: OmniRoute, pi/pi-mono, and oh-my-pi (omp). Investigate practical use cases, request patterns, supported input/output types, and API/gateway differences. Prefer adapting suitable existing integrations to rebuilding them from scratch. Use these findings to support simple text-in/text-out workflows, structured outputs, web search, streaming, and batch processing of different file types and formats.

## Required node responsibilities

1. **Endpoint and authentication nodes.** Configure connections to OpenRouter, llama.cpp, OmniRoute, and OpenAI/Codex, with applicable authentication, including Codex OAuth. Include request pacing and rate limits such as `minRequestSpacingMs` and `requestsPerMinute`. Keep the design extensible for additional OAuth flows and future Claude SDK / Claude Code MCP integrations. Treat SDK/MCP integration and authentication as distinct concerns, checking what each integration actually exposes.

2. **Model configuration nodes.** Select a model and configure its reasoning level, capabilities (`json_object`, `json_schema`, `prompt_cache`, `tools`, `web_search`), pricing, generation parameters, tags, and other relevant settings or metadata.

3. **LLM request nodes.** Provide flexible system-prompt and user-prompt configuration. The system prompt must be template-based: users can edit it or select a saved template, with data (variables?) populated from other nodes. The user prompt can come entirely from another node or be composed from a template and upstream data. Accept upstream text, images, files of different types, and other data the selected integration can process. Carefully design the interface for composing templates, binding upstream values, and supplying attachments.

4. **Result processing nodes.** Parse, process, transform, save, and pass LLM results to downstream nodes. Handle relevant result types, including text, JSON, and files, with support for complete and streamed responses where applicable.

**Composition, usability, and persistence**

If covering these use cases in one node creates excessive complexity, introduce focused, composable node types where they make workflows easier to understand and configure. Keep simple workflows easy while allowing advanced configurations. Carefully address errors, timeouts, progress reporting, token usage, and relevant provider-specific edge cases. Think deeply about how convenient and practical it will be to use these Nodes in differend mixed real-world use cases, and how well-designed, flexible, and user-friendly they are.


Allow users to create and persist reusable configured node presets, their settings, templates, and system/user prompts independently of workflow persistence. Users must be able to instantiate and reuse them across different workflows. Support user-defined node names and discovery by name, type, and group.

**Engineering priorities**

* Keep node UI, workflow execution, and provider-specific integration logic separate; prefer small adapters to duplicated provider branches.
* Define explicit, typed contracts for node inputs, outputs, and configuration, preserving structured and multimodal data across node boundaries.
* Validate requested features and parameters against the selected endpoint/model's actual capabilities; surface incompatibilities clearly rather than silently discarding settings.
* Keep persisted presets separate from execution state, using references to separately managed credentials rather than embedding secrets in reusable node definitions.

Choose concrete abstractions and implementation details based on the existing code and researched use cases. Favor readable, reliable, cohesive, extensible code; avoid antipatterns, bloated copy-paste code and unnecessary complexity. If the implementation is substantial, divide it into coherent stages and implement them progressively.
