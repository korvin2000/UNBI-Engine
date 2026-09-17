# UNBI-Engine — the LLM node pack

> Design and rationale for `nodes/llm` and the `llm/` subsystem behind it.
> Companion to [ARCHITECTURE.md](ARCHITECTURE.md); read that first for the node SPI and type lattice.

## 1. What this pack has to do

Talk to OpenRouter, llama.cpp, OmniRoute, OpenAI (Chat **and** Responses) and Codex, from a visual
graph, for text-in/text-out, structured output, web search, multimodal attachments, streaming and
batch — while keeping node UI, execution and provider quirks apart, and keeping secrets out of the
files people share.

The baseline is the `biomd-process` sub-project, which does exactly this job in TypeScript against
these exact gateways and has the measurements to prove which of its decisions were load-bearing.
The parts adapted here, and why, are listed in [§8](#8-what-was-taken-from-biomd-process).

## 2. The shape of the pack

```
llm/                          the subsystem — no NodeDefinition lives here
  spec/       ChatCall, ChatMessage, Attachment, ChatResult, TokenUsage, ResponseFormat,
              EndpointSpec, ModelSpec, SamplingParams, LlmFailure, ProviderProfile —
              the typed contract between a node and a provider
  provider/   LlmProvider SPI + the two wire formats (chat completions, responses)
              + SSE reader + HTTP transport
 auth/       CredentialStore, source-owned renewal, managed credential storage, generic OAuth and native ChatGPT authentication
  discovery/  GatewayDirectory + ModelListingReader + EndpointFacts + ModelFacts +
              Amounts (formatting) + TimeBudget + OptionalFetch + ModelEndpointPath —
              asking a gateway about itself, as pure functions of the bodies it returns
  runtime/    RequestPacer (rpm · spacing · concurrency), CapabilityCheck, LlmCaller
  prompt/     PromptTemplate
  openapi/    bounded OpenAPI 3.0–3.2 endpoint/server/security metadata import
nodes/llm/    LlmTypes + one class per node + EndpointProfiles (the endpoint profile schema)
              + RequestPlan (which requests one node makes)
profiles/     named, engine-side configurations referenced by id — generic, not LLM-specific
presets/      node presets — generic, not LLM-specific
```

`llm/` has no Spring imports except where a bean is unavoidable (the store, the caller, the
registry), matching `core/`'s rule for the same reason: every wire-format decision is then testable
without a container or a socket.

### 2.1 Twelve nodes, not one

One node with forty widgets is unreadable and one node per provider is four copies of the same
logic. The split below follows *what changes together*:

| Node | Produces | Why it is its own node |
|---|---|---|
| **Endpoint** | `LlmEndpoint` | Names a saved endpoint *profile*. One connection serves many models, and where it lives differs per machine — see [§3b](#3b-endpoint-profiles). |
| **Model** | `LlmModel` | Capabilities, pricing and reasoning are properties of a model, not of a call. Several models share one endpoint. |
| **Endpoint Info** | `LlmEndpointInfo` + `Text` + `Text[]` | What a gateway says about *itself*. All read, no settings — see [§4c](#4c-info-nodes-facts-with-a-timestamp). |
| **Model Info** | `LlmModelInfo` + `Text` + `Text[]` | What a gateway says about *one model*, including every host serving it. Its own node for the same reason. |
| **Generation Params** | `LlmSampling` | Optional. Absent in the simple case; overrides model defaults when present. |
| **Variables** | `LlmVariables` | Names upstream values so a template can read them. |
| **Prompt Template** | `Text` | Editable text or a saved preset, rendered against variables. Used for system *and* user prompts — one node, two instances. |
| **Prompt Variants** | `Text[]` | Several prompts in one box, separated by `---`; wired into a prompt, each becomes its own request. |
| **Attach Files** | `LlmAttachment[]` | Turns `FileRef[]` from the files pack into typed multimodal parts. |
| **LLM Request** | `Text` + `LlmResult[]` + `Text[]` | The call — or one call per item, decided by what is wired in. See [§2.3](#23-one-request-node-that-iterates). |
| **Parse JSON** | `Any` + `Text` | Fences, pointers, strict/lenient. |
| **Save Result** | `FileRef` | Writes, and hands the file back to the files pack. |

Alongside them, in the files pack: **Load Dataset** reads a JSON, JSON Lines, CSV or plain-text
file into a list of items, which is what a batch iterates over.

The simple workflow is three nodes: `Endpoint → Model → LLM Request`. Everything else is opt-in.

### 2.2 Types on the wire between nodes

```
LlmEndpoint     opaque handle — carries a credential *reference*, never a secret
LlmModel        opaque handle — endpoint + model + capabilities + pricing
LlmSampling     opaque handle
LlmVariables    opaque handle — a named map
LlmAttachment   opaque handle — an image, a document, or text read from a file
LlmResult       Struct{ text, finishReason, model, promptTokens, completionTokens,
                        reasoningTokens, costUsd, latencyMillis, sources }
LlmEndpointInfo Struct{ gateway, baseUrl, reachable, fetchedAt, modelsServed, keyLabel,
                        creditLimit, creditsRemaining, usageTotal, usageToday, freeTier,
                        freeRequestsUsed, freeRequestsLimit, modelsWith…, inputModalitiesCsv }
LlmModelInfo    Struct{ id, name, canonicalSlug, contextWindow, maxOutputTokens, inputPer1M,
                        outputPer1M, pricePerM, capabilitiesCsv, inputModalitiesCsv,
                        outputModalitiesCsv, providerCount, parametersCsv, released,
                        knowledgeCutoff, moderated, aliasOf, huggingFaceId, tokenizer,
                        pricingNote, description }
```

`LlmResult` is a struct rather than a handle so that `Preview` and `Generate Report` — which accept
`Any` and reflect over record components — render it as a table without knowing this pack exists.
Its fields and the record behind it are held to each other by `LlmTypeShapeTest`, because nothing
else connects the two but a programmer's memory.

The handles are `Primitive`s because nothing downstream should reach inside them. `Attachment` is
deliberately a class rather than a record for the same family of reason: a record would put a base64
payload into a table cell the moment someone wired one into a preview.

The two info structs are **flat and scalar-only**, which is the same argument arriving from the other
side. A table cell is one line, so a nested `LlmKeyUsage{daily, weekly, monthly}` would render as
`LlmKeyUsage[daily=0.42, …]` inside one column — a worse answer than the three columns it replaced.
The lists a model has travel on their own `Text[]` output and, inside the struct, as joined text in a
field named for it (`capabilitiesCsv`, `parametersCsv`). Every one of them, and the `toString` that
makes a lone record preview as a report rather than a field dump, is held to its struct by
`LlmTypeShapeTest`.

### 2.3 One request node that iterates

There used to be an LLM Request and an LLM Batch, with different sockets, different template rules
and different outputs, and the user had to decide up front which of two shapes their problem had.
The shape is already visible in what is wired in, so one node reads it:

- **A single value on an input applies to every request; a list makes one request per entry.**
  This holds for System Prompt, User Prompt, Data and Attachments alike.
- **Several lists** either **pair up by position** (the third system prompt with the third item)
  or **cross** (every system prompt against every item) — one setting, *Combine Lists*, because
  inferring it would be a guess.
- Both prompts are templates rendered per request: `{{item}}`, `{{index}}`, `{{count}}`, a file's
  `{{name}}` and `{{path}}`, an attachment's `{{file}}`, a dataset row's columns by name, and
  anything from a Variables node.
- Attachments have one extra choice, *Attach: all in one request / one request per file*, since a
  list of attachments is ambiguous in a way a list of prompts is not.

`RequestPlan` turns the wired inputs into the requests to make, and it is pure, so every shape a
batch can take is a row in `RequestPlanTest` rather than a run against a gateway:

| Want | Wire |
|---|---|
| Try three system prompts on the same document | Prompt Variants → System Prompt; the document on Data or User Prompt |
| Ask several questions of one system prompt | Prompt Variants → User Prompt |
| One file per request | Attach Files → Attachments, *one request per file* |
| All files in one request | Attach Files → Attachments, *all in one request* (the default) |
| One request per row of a spreadsheet | Load Dataset → Data; `{{column}}` in the prompt |
| X system prompts by Y user prompts | both from Prompt Variants; *Combine Lists: every combination* |

Outputs are `Text` (the answer, or every answer separated by a blank line), `Results[]`,
`Texts[]`, `Cost` and `Failures`. A batch counts a failed request and goes on; the node fails only
when nothing answered, which covers the single request naturally. Streaming is on for a single
request and off for a batch, where several answers at once would interleave into one ribbon.

## 3. Secrets

**A credential never enters a workflow file.** The Endpoint node stores a *name*; the value is
resolved at call time from a `CredentialStore` fed by ordered sources:

| Source | Where it reads | Ref it answers to |
|---|---|---|
| environment | `UNBI_LLM_KEY_<NAME>` | any |
| file | `credentials.properties` under the engine's data directory | any |
| managed | private `credentials/<name>.json` under the data directory | basic, OAuth and managed Codex references |
| external Codex | `CODEX_AUTH_JSON`, `$CODEX_HOME/auth.json`, else `~/.codex/auth.json` | `codex`, unless a managed definition owns it |

`GET /api/credentials` returns names and public type/source/status/expiry metadata, never secrets.
The shared credential editor opens from Settings or an endpoint's credential widget. It supports
write-only API keys, username/password, gateway OAuth and ChatGPT/Codex. API keys remain in
`credentials.properties`; managed definitions and sessions use private atomic files. Environment
and external Codex sources are read-only.

Managed credentials are bound to an explicit endpoint base URL. OAuth supports authorization code
with PKCE, client credentials and device authorization. Supply a registered client ID and the
provider's token authentication method; metadata discovery previews endpoints but never invents a
registration. Register the callback URI shown in the editor. For remote deployments set
`unbi.llm.oauth.callback-base-url` to the externally reachable trusted engine origin.

Generation and discovery resolve credentials after pacing, renew near expiry, and allow one
source-owned refresh/replay after a pre-output HTTP 401. A 403, static key, rejected refresh token,
or already-emitted output never starts a refresh/retry loop. Renewal persists rotated tokens before
publishing them. Closing the editor does not log out; Disconnect clears the managed session.

Sensitive settings bundles can include connection definitions and write-only configured secrets,
but never reusable OAuth sessions or native Codex tokens. Reconnect after importing a backup.
Data relocation refuses with HTTP 409 while authentication holds a data lease; finish or cancel
that operation, then retry.

## 3b. Endpoint profiles

**A base URL never lives in a workflow file.** Ordinary gateway addresses live in an **endpoint
profile** rather than in code: a named configuration saved on the engine under
`profiles/llm.endpoint/<id>.json`, holding the gateway kind, the base URL, authentication, the
credential *name*, streaming, timeout, pacing and extra headers. The native ChatGPT/Codex gateway is
the deliberate exception: when its profile base is blank, the engine supplies the official native
ChatGPT endpoint; an explicit nonblank profile value still wins. The Endpoint node holds the
profile's id and nothing else. A workflow that says "openrouter" runs unchanged wherever a profile
called openrouter exists, and points at whatever that machine means by it.

A profile is not a preset, and the two stores are deliberately separate. A preset is *copied* into a
node when it is dropped on the canvas; a profile is *referenced* and resolved when the node runs.
Putting an endpoint in the Presets tab as something to drop onto a canvas would embed the URL again.

The mechanism is generic. `ProfileSchema` is an SPI bean — id, label, a list of `NodeInput` fields,
an optional `test` — discovered by injection like nodes and credential sources; `ProfileStore`
keeps the files; `ProfileController` serves the schema *as a form* alongside the profiles. The
editor's profile dialog draws whatever fields the engine declared, with the same widgets a node
uses, and knows nothing about endpoints. `EndpointProfiles` is the one schema so far and the one
place an `EndpointSpec` is built from values, so the node, the test button, the model listing and the
request check cannot disagree about which URL, which credential or which headers were meant.

The gateway **kind** (`openrouter`, `llamacpp`, `omniroute`, `openai`, `codex`, `custom`) is a field
of the profile. It carries what genuinely differs between gateways on the wire — auth scheme, probe
path, preferred wire format, cached-token accounting, a pacing default and a note. Most kinds have no
address; `codex` also carries the deliberate native ChatGPT endpoint default described above.

### ChatGPT Plus/Pro and Codex, honestly

ChatGPT subscription sign-in is native to UNBI — it does not require a Codex executable or a shell
command. A new ChatGPT/Codex credential defaults its allowed resource base
to `https://chatgpt.com/backend-api/codex`; keep that value unless a deliberately configured gateway
uses another compatible base. Choose **Browser sign-in** for a local engine: the native browser flow
returns to `http://localhost:1455/auth/callback`. For a remote engine, choose **Device sign-in** and
complete the displayed verification flow on a device that can reach ChatGPT.

This native provider protocol is compatibility-sensitive: it uses the ChatGPT/Codex client protocol,
not the general OpenAI Platform API, and live account authorization remains the operator's
responsibility. Managed connections created by an older broker-based build do not silently copy or
reuse their old tokens: reconnect them explicitly in UNBI. The old connection definition may remain
visible so it can be reviewed, but it is not proof of a ready native session.

External Codex CLI credentials remain explicitly externally managed and read-only: UNBI may read them
for a configured external reference, but never copies, refreshes or logs out that CLI account.
**Connect in UNBI** establishes an independent managed session; logging out that session cannot
silently reactivate an external account.

Codex wire behavior is separate from authentication: the built-in ChatGPT/Codex gateway requires the
Codex Responses dialect, Responses protocol and streaming. It sends `store:false` and `instructions`,
and reports unsupported sampling or output limits through the existing capability policy. OpenAI Platform
API keys and ChatGPT subscriptions are separate authentication and billing surfaces.

### OpenAPI endpoint configuration import

In an endpoint profile, expand **OpenAPI endpoint configuration import (3.0–3.2)**. Paste JSON/YAML
or upload a file, supplying a document base URL for relative URLs. Preview and review the operation,
server variables, complete security alternative and changed fields before **Apply to draft**.
The import does not save a profile, create credentials, fetch remote references, execute operations,
or introduce HTTP/tool nodes. Configure the suggested credential separately, Test, then Save.

Operation/path/root precedence, local references and supported security alternatives are preserved.
Unsupported AND combinations, external consumed references, mutual TLS and insecure OAuth grants
remain unavailable with reasons, never silently anonymous. Documents are bounded to 10 MiB and
depth 100 with bounded YAML aliases. Protocol must be selected explicitly when no recognized
generation operation exists. Applying a patch clears the old credential reference while preserving
unrelated name, headers and pacing settings.

Protocol precedence is explicit Model override → endpoint format → gateway default. Chat Completions
and Responses remain supported. SSE framing, terminal-state checks and whole-body deadlines apply
to both; cancellation closes stalled bodies, and no generation retry follows visible output.

## 4. Providers: quirks are data

Two wire formats, one HTTP transport:

- `chat_completions` — OpenRouter, llama.cpp, OmniRoute chat, OpenAI
- `responses` — OmniRoute `cx/*`, OpenAI Responses, Codex (Responses only)

Everything that differs between gateways is a **field**, not a branch:

| Field | Lives on | Exists because |
|---|---|---|
| `authScheme` | endpoint | llama.cpp takes no key; Codex takes three headers |
| `stream` | endpoint | OmniRoute returns *another request's* completion when two buffered calls overlap |
| `chatCachedTokens` | endpoint | some gateways report cached tokens *in addition to* `prompt_tokens` |
| `responsesPromptCache` | endpoint | some gateways reject explicit cache controls |
| `maxTokensParam` | model | reasoning-era models reject `max_tokens`; some gateways reject the new name. `auto` derives it from the model, `none` sends no ceiling at all, and an output ceiling of 0 means the same — asking for no limit had no spelling before |
| `reasoningDialect` | model | `reasoning_effort` · `reasoning` · `thinking` · say nothing at all |
| `webSearchMode` | model | hosted tool · `:online` suffix · plugin · hosted search model |
| `providerOrder` / `requireParameters` | model | one OpenRouter model id is served by many hosts, and they silently drop parameters they do not implement |

A gateway **kind** (`ProviderProfile` in the code: `openrouter`, `llamacpp`, `omniroute`, `openai`,
`codex`, `custom`) is a pre-filled set of those fields, chosen in the endpoint profile dialog. Every
option that defers to it says "From gateway", and what the kind supplied is logged when the node
runs. A gateway that behaves unexpectedly is then a field to change, not a code path to find.

Two words, two things: the *gateway* is what kind of server it is; the *profile* is the saved,
named configuration that says where that server is and how to reach it ([§3b](#3b-endpoint-profiles)).

## 4b. Discovery: a first draft, never a claim

A model name typed from memory and eight capability boxes ticked by guesswork is not a declaration —
it is the same guess with more steps, and the capability check then holds every request to it. So
the Model list asks the gateway by itself the first time it is opened — an *automatic* action,
cached per node instance and per upstream configuration, so rewiring the endpoint refreshes it and
opening it twice asks once — and the bulb in the node header checks the chosen model and writes the
answer into the fields, where it stays visible, editable and undoable.

`ModelListingReader` turns three genuinely different `/v1/models` shapes into one:

| Gateway | What it publishes |
|---|---|
| OpenRouter | `supported_parameters`, `architecture.input_modalities`, per-token prices as decimal **strings**, `top_provider.max_completion_tokens`, `reasoning.{mandatory,default_enabled,supported_efforts,default_effort}` |
| OmniRoute | `api_format`, a `capabilities` object, `effort_tiers`, an explicit `max_output_tokens` |
| llama.cpp | an id and `meta.n_ctx`, and nothing else at all |

Three rules keep it honest, and each one is a test:

- **Nothing is inferred.** A gateway silent about pricing leaves the price alone. Null means "not
  discovered", never "free" — an invented number is the one the capability check would enforce.
- **Only what was answered is written.** The probe returns the fields the gateway spoke about and no
  others, so a discovery cannot blank a value somebody set deliberately.
- **Facts that belong together travel together.** A reasoning *dialect* without the reasoning
  *switch* means "send an instruction not to reason", which a model that reasons mandatorily rejects
  on every call — measured, on a live gateway, as `Reasoning is mandatory for this endpoint and
  cannot be disabled`. The two are discovered as one fact, and so is the effort tier: a gateway's
  stated default wins, a tier the user chose is kept when the model accepts it, and only an
  unsupported one is replaced.

Discovery also feeds the editor without the editor learning anything. Once the Model node holds a
capability list, the LLM Request node's Response Format dropdown offers only the formats that list
contains — a `Widget.Dropdown.narrowedBy` rule the descriptor carries and the browser evaluates, with
no button to press and no knowledge there of what a model or a response format is.

### Testing a connection is not the same as listing models

`GatewayDirectory.check` probes whatever the profile nominates, because a model listing is not
universally an authentication check: OpenRouter serves its catalogue unauthenticated and answers 200
for a key revoked an hour ago. `ProviderProfile.probePath` is therefore a field — `/key` there,
`/models` everywhere else — which is [§4](#4-providers-quirks-are-data)'s rule applied to discovery.

Both paths resolve the endpoint through `LlmEndpointNode.resolve` and `EndpointProfiles.build`,
the functions a *run* uses, so a green light and a working run cannot disagree about which URL, which
credential or which headers the node meant.

## 4c. Info nodes: facts with a timestamp

"Reachable — 443 models offered" is a green light that disappears the moment the dialog closes. What
someone planning a batch actually needs is how much credit is left, how many of those 443 models take
images, whether today's free-model allowance is spent, and which of the seven hosts serving one model
is cheapest — facts that belong on the canvas, beside the endpoint they describe, with the time they
were read.

So **Endpoint Info** and **Model Info** are nodes with no settings at all. Every row on them is a
`Widget.Display`: drawn by the editor, filled in by a probe, and impossible to type into.

**Why a widget value rather than an output.** An output exists only during a run and vanishes with
it. A widget value persists in the saved workflow and is visible without anything being run, which is
the whole point — the alternative is a node you have to execute to see what it already knows. The
rows are also *undoable*, because a `DISCOVER` probe applies its values through the ordinary edit
command, exactly as the Model node's bulb does.

**Three styles, because three things genuinely differ on screen.** `line` for a figure or a sentence
(a boolean draws as a ✓/✗ glyph; an ISO-8601 instant draws as "6 minutes ago" with the absolute value
in a tooltip, which is what the `fetchedAt` row is for); `block` for a paragraph that has to wrap, so
a published description reads as prose; `chips` for a list, so five modalities are five chips rather
than the string `"text, image, file, video, audio"` that a reader has to parse back into a set. A
`line` may carry a `unit` — `tok` on a context window — so the number stays a number on screen.

**The value is already formatted.** A gateway quotes `"0.0000001625"` per token; what the row shows is
`$0.1625 per M`. Doing that multiplication in the browser would be a second implementation of it, and
the backend is what parsed the body. `Amounts` is where it lives, and what makes it testable: cents
above a dollar, up to six decimals below one — because a per-million price of `$0.1625` shown as
`$0.16` is a 1.5% lie about a batch, and a day's usage of `$0.000362` shown as `$0.00` says nothing
was spent when something was.

**Formatted also means formatted *enough*.** A `line` row holding an ISO-8601 instant is drawn as
relative time, which is exactly what `fetchedAt` wants and the opposite of what a key expiry wants:
every future instant is under 45 seconds old by that arithmetic, so a key expiring in three months
would read "just now". An expiry is therefore formatted to a plain date before it becomes a row. The
same argument runs through `LlmModelInfo`, which carries `pricePerM` as text beside its two numbers —
a published `0` and an unquoted price are both `0.0` in a `Number` field, and `free in / free out per
M` is the only place that difference survives into a saved struct.

**Blank is a fact.** `""` means "this gateway does not publish it", and it is drawn as nothing rather
than as a zero. That is the same rule as [§4b](#4b-discovery-a-first-draft-never-a-claim)'s first one
and it needs one extra guarantee to hold: **a fetch writes every declared row, every time.** A fetch
that only wrote the rows it found values for would leave the rest showing the *previous* endpoint's
figures under a fresh timestamp, so re-pointing a node from a paid account to a free one would keep
the old balance on screen. The row set is read off the descriptor rather than listed by hand, so
adding a row cannot be forgotten in a clearing pass that does not exist. `LlmInfoNodesTest` fetches
twice, against two different captured accounts, and asserts both answers wrote the identical key set.

**A row is also no place for key-shaped text.** A display value persists in the saved workflow and
travels with the file, so "widget values never hold secrets" has to hold for values the *gateway*
chose as well as for the ones a user typed. OpenRouter names an unnamed key after the key itself —
`sk-or-v1-0ca...618` — which is its own redaction and still key-shaped, so `EndpointFacts` reports
such a label as `unnamed key`. Two signals are required to call it one, a known prefix *and* the
truncating ellipsis, because a prefix alone would also mask a real label like `api-team`.

**One budget per fetch, spent in priority order.** Three questions at one endpoint timeout each is a
button that can hold the editor for six minutes; `TimeBudget` allows
`min(endpoint timeout, 30 s)` for the whole thing. The call the verdict rests on goes first with the
whole allowance, and the extras spend what is left — so a slow gateway costs the balance row rather
than the answer, and a row names the call that did not get its turn.

**An optional call's failure is a sentence, not a failure.** `HttpTransport.get` throws for every
status ≥ 400, because a *call* cannot know which of them matters. `OptionalFetch` is where a probe
decides: 404 is "no such endpoint on this gateway", 401/403 is "not readable with this key", anything
else keeps the gateway's own words. It branches on the **status** and never on `LlmFailure.Kind`,
which exists to answer "retry, change something, or stop" and collapses cases that differ here — 404
classifies as `MODEL_UNAVAILABLE`, and about a credits endpoint that sentence would send the reader
after the wrong setting. Measured: `/credits` answers 403 for a key that is not a management key,
which is most of them, so a node that went red for it would be red for nearly every user. The same
`/key` body that refuses the balance says `is_management_key: false`, so when *that* is the reason the
row says it — `not readable: this key is not a management key`, the sentence that ends the
investigation instead of starting one into a credential that is fine. Every other refusal keeps the
transport's own words, because a 404, a spent budget or a timeout blamed on the key's kind would be
the same misdirection from the other direction.

**The model endpoints URL never splits the id.** An OpenRouter id is `vendor/model`, sometimes
`vendor/model:free` and sometimes `~vendor/model-latest`; every separator a parser would reach for is
a character that legitimately appears inside one. `ProviderProfile.modelEndpointsPath` is therefore a
`{slug}` template rather than a prefix and a suffix, the gateway's own `canonical_slug` fills it when
the listing has one, and only characters a URI path cannot carry are escaped. Live-verified:
`/models/<full id>/endpoints` answers 200 for `:free`, for `:batch` and for a leading `~`; an alias
answers `"endpoints": []`, which is rendered as "the gateway publishes no endpoints for this alias"
rather than as an empty box.

**Two paths were added to the gateway kind**, following [§4](#4-providers-quirks-are-data): `creditsPath`
(`/credits` on OpenRouter) and `modelEndpointsPath`. Both default to blank, meaning "this gateway has
none" — blank rather than a guess, because a path invented for llama.cpp turns "it does not do that"
into a 404 the user has to interpret. `GatewayDirectory.get` is the only way the nodes reach a
gateway, so `HttpTransport` stays injected in exactly one place and credential resolution is not
re-implemented anywhere.

**Grouped detail.** Six to eight headline rows stay in the node; everything else is an
`advancedSetting` under a `section(…)` group — "Usage", "Balance", "Catalogue" on one node,
"Identity", "Modalities", "Pricing", "Reasoning", "Limits", "Providers" on the other. A group is
presentation only, and the same mechanism was applied to the settings that were already there: the
Model node's twenty advanced fields now read as "Output limits", "Protocol", "Cost", "Routing" and
"Escape hatch" instead of as one list, and LLM Request's as "Response shape", "Batching",
"Web search" and "Refusals and retries".

**One deliberate departure from "per M".** `pricing.web_search` arrives at `0.01` while every
per-token field beside it is around `1e-7`. Multiplied, it would read `$10,000 per M`, which is the
kind of number that makes a whole panel untrustworthy — so it is reported as the figure the gateway
wrote, with the row saying so. In the same spirit, `pricing.overrides` turned out to come in two
kinds that mean different things: 3 of the 69 models with overrides vary **by time of day**
(`utc_days`, `utc_start`) and 66 vary **above a prompt-token threshold** (`min_prompt_tokens`). The
note names which applies — "varies by time of day: $0.66–$1.32 /M in" — because a disclaimer that
does not say why is one users learn to ignore.

## 5. Capabilities are checked, not hoped for

Before a request is built, `CapabilityCheck` compares what the call asks for against what the model
*declares*:

- `json_schema` / `json_object` response format → the matching capability
- attachments → `vision` (images) or `files` (documents)
- web search → `web_search` **and** a `webSearchMode`
- web search + JSON mode → refused: the configured gateways answer `400 Web Search cannot be used
  with JSON mode`

Findings are `ERROR` or `WARNING`. The node's **Strict** toggle (on by default) decides whether an
`ERROR` fails the node or degrades with a loud log. Silently dropping the field — which is what
sending `response_format` to a model that ignores it amounts to — is the behaviour this exists to
prevent.

## 6. Pacing, cancellation, streaming

`RequestPacer` is one per endpoint id: a requests-per-minute token bucket, a concurrency semaphore,
and a floor on the gap between two *dispatches*. The floor is not the bucket: a bucket starts full,
so 60/min happily lets sixty requests leave in the same millisecond — which is precisely the arrival
pattern that breaks a gateway that mishandles overlap.

Cancellation is checked between SSE lines and between batch items, so a long generation stops at the
next chunk rather than at the end of the call.

Streaming adds one engine event, `node.stream`, and one `NodeContext` method, `stream(key, chunk)`.
It is a *preview* channel: the complete value always arrives through `output()`, so a context that
does not implement it loses live text and never loses data. That is why its default is a no-op.

## 7. Presets — and why a saved prompt is one

A preset is `{ id, name, group, nodeType, description, values }`, stored as one JSON file per preset
under the engine's data directory — **separate from workflow files**, which is the requirement:
a prompt worth keeping outlives the graph it was written in.

The full-window prompt editor reads and writes *these*, as presets of the `llm.prompt` node type,
rather than keeping a library of its own. One store means a prompt saved from an LLM Request node
appears in the Presets tab, in every other prompt editor, and can be dropped onto a canvas as a
configured Prompt Template node — and it means there is exactly one place to delete it from. A
second "prompt library" would have been a second place for the same thing to live, a second place to
search it, and a second place to forget to clean it up.

- `GET /api/presets?type=&group=&q=` — discovery by type, group and name
- `POST /api/presets`, `DELETE /api/presets/{id}`
- the editor's palette gains a **Presets** section; dropping one creates that node type with those
  values already filled in
- a node carries an optional user-given **title**, which is what "discovery by name" is about at the
  canvas end

Presets hold widget values, and widget values never hold secrets — the Endpoint node stores a
credential *name* — so a preset is safe to share by construction rather than by a filter.

## 8. What was taken from `biomd-process`

Adapted, with the reasoning intact:

| Taken | Where it lives now |
|---|---|
| request-body construction as a **pure function** of (target, request) | `ChatWire` / `ResponsesWire` — tested without a socket, which is the only way to tell a parameter that was *configured* from one that was *sent* |
| `response_format` gated on declared capability | `ChatWire.responseFormat` |
| reasoning dialects, and `dialect: none` meaning "say nothing" | `ReasoningDialect` |
| streamed reassembly into the non-streamed shape | `ChatWire.reassemble`, `ResponsesWire.reassemble` |
| the `response.incomplete` terminal event carrying usage and truncation | `ResponsesWire` |
| cached-token accounting modes (`included` / `additional`) | `TokenUsage` mapping |
| provider routing block, omitted entirely when empty | `ChatWire.provider` |
| error taxonomy with retry/fallback dispositions | `LlmFailure` |
| rpm bucket + concurrency + **dispatch spacing** | `RequestPacer` |
| web-search *evidence* rather than URLs written in prose | `ChatResult.sources` |

Deliberately **not** taken: routing pools, adaptive strategies, budget guards, circuit breakers,
translation memory. They are the shape of a batch corpus tool, not of a node in a visual graph —
here the user picks the model by wiring one, and the graph is the fallback chain.

## 9. Verification, decided before implementation

| Rung | What it catches |
|---|---|
| `PromptTemplateTest` | substitution, escaping, unknown-variable policy, variable discovery |
| `ChatWireTest` · `ResponsesWireTest` | exactly what goes on the wire, per gateway quirk |
| `WireResponseTest` | usage mapping, finish reasons, SSE reassembly, search evidence |
| `CapabilityCheckTest` | every incompatibility and the sentence it produces |
| `RequestPacerTest` | bucket, spacing and concurrency, on an injected clock |
| `CredentialStoreTest` | resolution order, redaction, Codex `auth.json`, the missing-ref message |
| `LlmNodesTest` | each node against a recording context and a stub provider, including every batch shape |
| `RequestPlanTest` | which requests a set of wired inputs produces: broadcasting, pairing, crossing, bindings |
| `PresetStoreTest` · `ProfileStoreTest` | round-trip, query, delete, path safety; defaults filled in for a partial file |
| `LoadDatasetNodeTest` | JSON, JSON Lines, CSV with quoting and sniffed delimiters, plain lines |
| `LlmProviderHttpTest` | the real HTTP path, streamed and buffered, against a JDK `HttpServer` |
| `ModelListingReaderTest` | what three real gateways published, and what was concluded from it — including everything that was *not*, plus the `{data, total_count, links}` envelope |
| `EndpointFactsTest` · `ModelFactsTest` | captured OpenRouter bodies in, formatted rows out; every blank that has to stay blank |
| `AmountsTest` | the money and per-token arithmetic that would otherwise be done twice, in two languages |
| `ModelEndpointPathTest` | the ids that must reach the gateway whole — live-verified for `:free`, `:batch` and a leading `~` |
| `LlmInfoNodesTest` | both info nodes over fixtures, including "every display row is written on every fetch" |
| `CatalogCodecTest` | the `display` widget and the `group` on every input — the payload the browser draws a node from |

## 10. Deliberately not in this pack

Named so they are choices: tool/function calling round-trips (the capability is declared and
validated, the loop is not implemented), Anthropic's native Messages format, MCP servers, and
embeddings.

Image *generation* is also not here yet, and the reason is verification rather than design. The
shape is small — a capability, a request toggle, `modalities: ["image","text"]` on the wire, image
parts read back from the answer, Save Result writing them to files — and the iteration in
[§2.3](#23-one-request-node-that-iterates) already covers "one image per request". But none of the
models this pack has been allowed to test against produce images, and a wire path that has never
been driven against a real gateway is exactly the kind of code this document argues against
shipping. Image *analysis* per request works today: Attach Files → Attachments with *one request
per file*.

Model discovery from `/v1/models` **was** on this list, on the grounds that it would need credentials
in a browser round trip. It is now implemented, and that objection turned out to name the design
rather than block it: the browser never holds a credential, because the probe runs on the engine and
the editor sends only widget values — see
[ARCHITECTURE §6b](ARCHITECTURE.md#6b-asking-before-running).
