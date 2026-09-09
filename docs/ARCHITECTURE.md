# UNBI-Engine — Architecture

> Universal Node-Based Intelligence Engine.
> A node/flow editor and execution engine in the shape of chaiNNer, but domain-agnostic.

## 1. Shape of the system

```
┌──────────────────────── browser ────────────────────────┐
│  Angular 22 (zoneless, standalone, strict templates)     │
│                                                          │
│  editor/   Foblex Flow canvas + chaiNNer-styled shells   │
│  graph/    GraphStore (signals) + Command pattern        │
│  catalog/  NodeCatalog — descriptors fetched from server │
│  types/    PortType lattice mirror + assignability       │
│  runtime/  RunStore ← RxJS stream of execution events    │
└────────────┬──────────────────────────┬──────────────────┘
             │ REST /api/catalog        │ WebSocket /ws/engine
             │ (descriptors, once)      │ (commands ↑, events ↓)
┌────────────┴──────────────────────────┴──────────────────┐
│  Spring Boot 4.1 / Java 26                                │
│                                                           │
│  transport/  WebSocket envelope codec, REST catalog       │
│  engine/     scheduler, run lifecycle, cancellation       │
│  registry/   NodeRegistry ← Spring injects List<NodeDef>  │
│  nodes/      one class per node, auto-discovered          │
│  core/       pure Java: types, graph, validation, SPI     │
└───────────────────────────────────────────────────────────┘
```

`core/` has **no Spring imports**. It is the part that must stay testable and portable, and it is
the part a second frontend or a CLI runner would reuse.

## 2. The three contracts everything else hangs off

Get these right and node authoring is a one-file change forever after.

### 2.1 `PortType` — the assignability lattice

chaiNNer needed a small type language so that `Upscale → MergeTransparency` knows the output is RGB
and not RGBA. We need the same thing for `ScanDirectory → SearchText`: a port carrying
`List<FileRef>` must not connect to one expecting `Directory`.

A sealed hierarchy, deliberately small — five cases, not a general type checker:

| Case        | Meaning                          | Example                        |
|-------------|----------------------------------|--------------------------------|
| `Primitive` | named leaf                       | `String`, `Number`, `Directory`|
| `Struct`    | named record of fields           | `FileRef{path,name,ext,size}`  |
| `List`      | homogeneous sequence             | `List<FileRef>`                |
| `Union`     | any-of                           | `String \| Number`             |
| `Any`       | top — accepts everything         | report inputs                  |

`assignable(from, to)` is pure, total and memoized. It is the **single** place where connection
legality is decided, and it is implemented twice — Java and TypeScript — against **one shared test
vector file** (`contract/type-assignability.json`) so the two implementations cannot drift.

> That JSON file is the load-bearing idea here. Two hand-written implementations of one rule is the
> textbook defect; a shared table both must satisfy turns it back into one decision.

### 2.2 `NodeDefinition` — a node is one class

```java
public interface NodeDefinition {
    NodeDescriptor descriptor();                    // data: id, ports, widgets, category
    void execute(NodeContext ctx) throws Exception; // behaviour
}
```

Registration is Spring DI, not a manifest scan and not a static registry:

```java
@Component
final class ScanDirectoryNode implements NodeDefinition { ... }
```

Spring injects `List<NodeDefinition>` into `NodeRegistry`. **Adding a node = adding one file.**
Removing one = deleting one file. Overriding one for a deployment = `@Primary` or a profile.
This is the plugin system as a language feature — nothing hand-rolled.

`NodeDescriptor` is built with a small fluent builder so a node file stays ~60–110 lines and reads
as a declaration, not as plumbing.

### 2.3 The wire envelope

One frame shape in both directions, discriminated by `type`. Client→server: `run`, `cancel`, `ping`.
Server→client: `run.started`, `node.state`, `node.progress`, `node.log`, `run.finished`, `error`.

Node descriptors are **served, never compiled into the frontend**. The frontend renders any node the
backend advertises, from generic widgets keyed by `widget` on each input. A new node therefore needs
zero frontend changes — which is the actual test of whether the extensibility claim is real.

## 3. Frontend structure

```
src/app/
  core/
    types/        PortType mirror, assignability, colour keys
    graph/        WorkflowDoc, GraphStore (signals), Command + history
    catalog/      CatalogService, descriptor parsing, palette grouping
    runtime/      EngineSocket (RxJS), RunStore (signals), wire protocol
    engine.config.ts   where the backend lives, as an injection token
  editor/
    canvas/       Foblex wiring + the node component
    palette/      left panel: search, categories, drag-to-canvas
    toolbar/      top bar: run/stop, status, file actions
    workflow-file.service.ts   save, open, the built-in example
  widgets/        one component, switching over the seven declared widget kinds
  shared/         inline SVG icons
styles/           tokens.scss — the palette, per-category accents, per-type port colours
```

There is no separate inspector panel: a node's widgets live in the node, as in the reference UI.
And the widgets are one component with a `@switch` rather than seven components behind a registry —
seven small controls sharing one disabled/label behaviour do not each earn a file and an
indirection. The first one that grows real complexity can be extracted then.

- **Signals** own view state: document, selection, viewport, per-node run status.
- **RxJS** owns event streams: the WebSocket, drag gestures, debounced autosave.
- **Commands** own every document mutation. Undo/redo is free and correct from day one because it
  is never retrofitted. `AddNode`, `RemoveNodes`, `Connect`, `Disconnect`, `MoveNodes`, `SetInput`.
- **Validation is derived state** (`computed`), never an imperative pass. Type errors and cycles
  recompute from the document; nothing has to remember to invalidate.

Widget lookup mirrors the backend's plugin story: an `InjectionToken<WidgetRegistration[]>` with
`multi: true`. A new widget kind is one provider.

## 4. Execution model

Topological order, computed once per run, cycles rejected before anything executes.
Each node runs on a **virtual thread** (Java 26); a run is cancellable at any node boundary and
between chunks inside long file walks.

`NodeContext` gives a node exactly four things and nothing else:

```java
<T> T input(String key, Class<T> as);   // typed, resolved from upstream
void output(String key, Object value);  // typed, checked against descriptor
void progress(double fraction, String message);
boolean cancelled();                    // cooperative, checked in loops
```

Events are published to a `Sinks.Many` per run and multicast to subscribed sockets. A node that
never calls `progress` still gets `queued → running → completed` from the engine, so the UI
animation is uniform whether or not a node bothers to report.

## 5. Node pack for v1 — batch file processing

| id                     | inputs                              | outputs            |
|------------------------|-------------------------------------|--------------------|
| `io.directory`         | path (directory widget)             | `Directory`        |
| `io.scan_directory`    | `Directory`, recursive, glob, limit | `List<FileRef>`    |
| `io.filter_files`      | `List<FileRef>`, extensions, mode   | `List<FileRef>`    |
| `text.search_in_files` | `List<FileRef>`, pattern, regex, ci | `List<TextMatch>`  |
| `text.replace_in_files`| `List<FileRef>`, find, replace, dry | `List<FileEdit>`   |
| `report.generate`      | `Any`, title, format                | `String`, written  |
| `util.preview`         | `Any`                               | —                  |

`replace_in_files` defaults to **dry-run on**. A prototype that silently rewrites a user's
directory tree on the first misclick is not a prototype, it is an incident.

## 6. Verification — decided before implementation

| Rung        | What it catches                             | How it runs                     |
|-------------|---------------------------------------------|---------------------------------|
| types       | wire/DTO drift, template errors             | `tsc` strict + strict templates |
| unit (TS)   | assignability, commands/undo, cycle detect  | `ng test`                       |
| unit (Java) | assignability, topo sort, each node's logic | `gradlew test` (JUnit 6)        |
| contract    | Java ↔ TS type system agreement             | shared `type-assignability.json`|
| e2e         | the whole stack actually working together   | `EngineEndToEndTest`            |
| build       | the whole thing actually compiles           | `gradlew build`, `ng build`     |

The contract rung is the one that matters most: it is the only rung that can catch the two
implementations of `assignable` disagreeing, and that disagreement is the single most likely way
this design breaks in a way nobody notices until a user's graph silently misbehaves.

### What this actually caught

Not decoration — every one of these was a real defect found by the rung above it:

- **Union precedence was backwards.** The contract table refused `Text|Number → Text|Number|Boolean`.
  A union *source* has to be resolved before a union *target*; checking the target first asks the
  wrong question.
- **`computeIfAbsent` cannot recurse.** Memoizing assignability that way threw
  `IllegalStateException` the moment a list or struct recursed into the same map.
- **A NUL byte in a Java source file.** A composite map key built by string concatenation had a raw
  `U+0000` where a space belonged. It compiled silently, and only surfaced because the file-search
  node skipped its own source tree as "binary". Both such keys are now records, and
  `SourceHygieneTest` fails on any stray control character.
- **`Map.copyOf` is unordered.** The registry sorted the catalog and then threw the order away,
  leaving the palette in hash order — directly contradicting the comment above it.
- **`port-FileRef[]` is not a valid CSS class.** Colouring ports by type key produced a selector
  containing brackets, so every list port silently lost its colour with nothing in the console.
- **A node's last progress message was discarded on completion**, blanking the one useful thing it
  had reported.

## 7. Decisions taken, with the reason

**Foblex Flow over ngx-vflow and Rete.js.** Verified against the installed package, not assumed:
it ships an `AI.md` code-generation contract and a `STYLING.md` naming its runtime classes, its
peer range covers Angular 22, and it was published 2026-09-06. Decisive factor is the *template-first*
model — chaiNNer nodes are not labelled boxes, they are forms with dropdowns, sliders, spinners,
file pickers and previews. `<div fNode>` wrapping our own component is the right shape for that;
a `nodes[]` data model with registered components is a worse fit. Rete.js v2's Angular renderer is
a second-class binding to a framework-agnostic core.

**Descriptors served, not compiled in.** The extensibility requirement is only real if the frontend
never needs a rebuild to gain a node. Making descriptors data is what buys that.

**Command pattern from commit one.** Retrofitting undo onto mutable state is the classic failure
mode and is not worth risking.

**`core/` free of Spring.** Keeps the domain testable without a container and portable to a CLI.

**No plugin *framework*.** DI multi-binding is the seam, and it already exists. Building a
loader/lifecycle/manifest layer before the first feature works would be exactly the over-engineering
the brief warns against.

## 8. Deliberately not in v1

Named so they are choices rather than oversights: WebGL renderer (the DOM path is behind an
interface, so it stays a swap), subgraphs/groups, collaborative editing, node versioning and graph
migration, authentication, persistent run history, a real settings store.
