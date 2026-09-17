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
             │ REST /api/catalog, /fs   │ WebSocket /ws/engine
             │ (descriptors; disk)      │ (commands ↑, events ↓)
┌────────────┴──────────────────────────┴──────────────────┐
│  Spring Boot 4.1 / Java 26                                │
│                                                           │
│  transport/  WebSocket codec, REST catalog + filesystem   │
│  engine/     scheduler, run lifecycle, cancellation       │
│  registry/   NodeRegistry ← Spring injects List<NodeDef>  │
│  nodes/      one class per node, auto-discovered          │
│  llm/        wire formats, credentials, pacing, templates │
│  presets/    saved node configurations, on disk           │
│  profiles/   named configurations nodes refer to by id    │
│  settings/   settings.json, relocatable dirs, .ucfg       │
│  workflows/  the library: one file each, favorites/       │
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

A node may also implement a second, entirely optional interface:

```java
public interface NodeProbe {
    Result probe(String action, Request request) throws Exception;  // "does this work?"
}
```

A separate interface rather than more methods on `NodeDefinition`, because most nodes have nothing
to check and a node with nothing to check should not have to say so. See
[§6b](#6b-asking-before-running).

Registration is Spring DI, not a manifest scan and not a static registry:

```java
@Component
final class ScanDirectoryNode implements NodeDefinition { ... }
```

Spring injects `List<NodeDefinition>` into `NodeRegistry`. **Adding a node = adding one file.**
Removing one = deleting one file. Overriding one for a deployment = `@Primary` or a profile.
This is the plugin system as a language feature — nothing hand-rolled.

`NodeDescriptor` is built with a small fluent builder so a node file stays ~60–110 lines and reads
as a declaration, not as plumbing. Three of its methods are about *presentation* rather than data,
and all three exist because a node with twenty settings on screen says nothing about which two of
them matter:

| Builder call | Effect in the editor |
|---|---|
| `.setting(…)` | drawn in the node |
| `.advancedSetting(…)` / `.advanced()` | folded into an **Advanced** strip, with a badge counting what has been changed |
| `.onlyWhen(sibling, values…)` | drawn only while a sibling setting holds one of those values |
| `.section(name)` | every input declared *after* it carries that group name, until the next call; `section("")` ends grouping. The inspector draws the groups as sub-sections and the node body ignores them |
| `.display(…)` / `.advancedDisplay(…)` | a fact the engine found out: drawn, never typed into, never wired |
| `.action(NodeAction…)` | a button in the node header, answered by `NodeProbe` — or, for `NodeAction.automatic`, no button: the editor runs it when the field it feeds is opened |

The ranking is declared by the node's author because nothing else can know it.

`.section` is positional for the same reason `.hint` is: a node with four groups of settings would
otherwise carry the group name on twenty lines, and a reader would have to diff those strings to see
where one group ends. Declared in order, the grouping reads as the outline it is. Groups are
**presentation only** — nothing in an execute path reads one.

`.display` earns a widget kind rather than an output port, and that is the decision worth stating.
An output exists only during a run and vanishes with it; a widget value persists in the saved
workflow and is on screen without anything being run — which is what "how much credit is left on
this endpoint" has to be to be worth reading. The value is already formatted by the backend, because
the backend is what parsed the body: a gateway's `"0.0000001625"` per token is `$0.1625 per M`, and a
second implementation of that multiplication in TypeScript is a second place for it to be wrong.
A display input is refused at construction if it is connectable or required, since a port into a
fact offers to overwrite it and "required" would make a node invalid until a gateway had been asked. Both folding rules
are refused at construction for a *connectable* input: a connector that is not laid out loses its
geometry and drags its edges to the corner of the node (Foblex FF1006), so the restriction sidesteps
that rather than working around it.

### 2.3 The wire envelope

One frame shape in both directions, discriminated by `type`. Client→server: `run`, `cancel`,
`validate`, `ping`. Server→client: `run.started`, `node.state`, `node.progress`, `node.log`,
`node.stream`, `run.finished`, `run.rejected`, `validation`, `error`.

`node.stream` carries partial output while a node is still producing it. It is a *preview* channel:
the complete value always arrives through `output()`, which is why `NodeContext.stream` has a no-op
default — a context that ignores it loses live text and never loses data.

Node descriptors are **served, never compiled into the frontend**. The frontend renders any node the
backend advertises, from generic widgets keyed by `widget` on each input. A new node therefore needs
zero frontend changes — which is the actual test of whether the extensibility claim is real.

## 3. Frontend structure

```
src/app/
  core/
    types/        PortType mirror, assignability, colour keys
    graph/        WorkflowDoc, GraphStore (signals), Command + history
    catalog/      CatalogService, descriptor parsing, palette grouping, node probes
                  node-rows.ts — the row model both the node and the inspector render from
    runtime/      EngineSocket (RxJS), RunStore (signals), wire protocol
    presets/      saved node configurations, fetched from the engine
    profiles/     engine-side named configurations (endpoints), and the dialog that edits them
    credentials/  the names of the secrets the engine holds — never their values
    i18n/         Translator (signals), the `t` and `n` pipes, messages/en.ts — see §10
    settings/     the engine's settings, and the export/import calls
    workflows/    the workflow library on the engine, and the session (which file, dirty?)
    engine.config.ts   where the backend lives, as an injection token
  editor/
    canvas/       Foblex wiring + the node component
    palette/      left panel: search, categories, drag-to-canvas
    inspector/    one node's settings, beside the canvas: panel + its own signal store
    toolbar/      top bar: run/stop, status, file actions
    workflow-file.service.ts   save, open, the built-in example
  widgets/        one component, switching over the declared widget kinds
  shared/         inline SVG icons
    file-browser/ the folder and file dialogs, and the service the widgets ask
    preset-dialog/ naming a preset before it is saved
    profile-dialog/ creating, testing, editing and deleting profiles, from fields the engine declares
    text-editor/  the full-window prompt editor, and the saved-prompt library behind it
    settings-dialog/ the application settings: general, storage, endpoints & keys, backup, about
    workflow-library/ open a workflow from the engine, or save the one on the canvas to it
styles/           tokens.scss — the palette, per-category accents, per-type port colours
                  dialog.scss — the shared shape of a modal, as mixins
```

A node's widgets live **in the node**, as in the reference UI; the inspector panel is a second view
of the same rows rather than the place settings live. Both call `buildRows` (see §3, *Nodes stay
small*), so neither can disagree with the other about what a node currently is.
And the widgets are one component with a `@switch` rather than one component per kind behind a
registry — small controls sharing one disabled/label behaviour do not each earn a file and an
indirection. The first one that grows real complexity of its own can be extracted then; the LLM
pack added three kinds without any of them reaching that bar.

- **Signals** own view state: document, selection, viewport, per-node run status.
- **The selection has two owners**, and a programmatic one has to write both. `GraphStore.selection`
  is what Delete, Ctrl+D, the context menu and the inspector read; the flow library keeps its own
  copy and is what draws the border, and the app only ever *hears* about that one, through
  `fSelectionChange`. So every press the library does not see — the node's Advanced strip and its
  width grip carry `fDragBlocker` — selects through `CanvasSelection`, which writes the store and
  hands the ids to the flow (`FFlowComponent.select`, registered by `FlowCanvas`). A store-only
  selection is how the canvas ends up highlighting one node while Delete removes another.
- **RxJS** owns event streams: the WebSocket, drag gestures, debounced autosave.
- **Commands** own every document mutation. Undo/redo is free and correct from day one because it
  is never retrofitted. `AddNode`, `RemoveNodes`, `Connect`, `Disconnect`, `MoveNodes`, `SetInput`.
- **Validation is derived state** (`computed`), never an imperative pass. Type errors and cycles
  recompute from the document; nothing has to remember to invalidate.

### Ports, wires, and why they are the way they are

Three things about the canvas are worth stating, because each one is a decision that a later change
could quietly undo:

- **A port's centre is the node's border.** The row's horizontal padding is cancelled and the circle
  pulled back by half its width, so the element the flow library measures *is* the socket. Endpoints
  are `fBehavior="fixed_center"`, which anchors a wire at the connector's centre — so a wire ends in
  the middle of the circle by construction, not by tuning offsets.
- **The wire's colour is the port type's colour**, and `fCanBeConnectedTo` is given the set of input
  types each output may legally reach — computed from the same `assignable` predicate the drop
  handler and the backend validator use. An incompatible port therefore never lights up during a
  drag: colour is not decoration, it is the rule.
- **A path field is not a browser upload.** The engine is what opens the path, so the picker lists
  the *engine's* disk over `GET /api/fs`. A browser file input could only ever hand back a sandboxed
  handle the engine cannot use.

### Nodes can be switched off

The footer of every node carries an on/off switch. A node that is off — and everything downstream of
it, computed as a closure — is left out of the graph sent to the engine entirely, rather than sent
with a flag. The engine therefore needs no notion of a disabled node, and the graph it receives is
always one it can run. Excluded nodes are also excluded from validation: reporting "missing input"
on a node whose producer the user just switched off is noise, not a finding.

### Nodes stay small, by three rules the editor applies and does not understand

The node body is the one place where "the frontend knows no node types" is under constant pressure,
because the thing that makes a node unreadable is always specific to that node. Three rules keep it
generic, and each is a field on the descriptor rather than a branch in the editor:

- **Advanced.** Inputs marked as fine tuning are folded away. The fold's badge counts how many of
  them are no longer at their default, because a fold that silently hides a changed setting hides
  the reason a run behaves oddly.
- **Conditional.** `showWhen` names a sibling setting and the values that make this one apply. A
  JSON schema box above a response format of "Text" is not merely wasted height — it is a question
  with no right answer, and every one of those makes the questions that matter less trustworthy.
- **Referenced, not carried.** A setting that differs per machine — where a gateway lives — is a
  `Widget.Profile`: the node holds the id of a configuration saved on the engine, and a dialog the
  engine describes field by field is where it is edited. See `profiles/` and
  [LLM-NODES §3b](LLM-NODES.md#3b-endpoint-profiles).

A node body never scrolls. It did, briefly, behind a ceiling — and any `overflow` other than
`visible` clips the ports that straddle the node border, while a body that scrolls carries the
output sockets away with it the moment the fold opens. Height comes from content; the three rules
above are what keep it short.

Two layout decisions do the rest of the shortening. A `number` sits **beside** its label rather than
under it — a spinner is a quarter of the node's width, so the line above it was empty, and a node of
five numbers is five lines shorter for it. And a *closed* Advanced strip whose settings have been
moved lists them as tiny read-only `label: value` chips, so a saved graph says what was tuned
without anything being opened.

#### A node's width belongs to the document

The rules above keep a node short; none of them makes it *wide*, and a few nodes genuinely need to
be — a JSON schema, a long prompt, a table of discovered facts. `WorkflowNode.width` is therefore an
optional number of canvas pixels, and `resizeNodes(ids, width)` sets or clears it for a whole
selection in one undoable step. It is part of the workflow rather than a view preference: which node
was widened is something the author decided, and a reader of the saved file should get it back.
Absent rather than `252` when untouched, so a node that never expressed an opinion still follows
`--node-width` and the default can move without rewriting every file.

Three things about the mechanism are worth stating, because each is a decision a later change could
quietly undo:

- **It is a plain CSS width** — `[style.--node-width.px]` on the node host, and nothing else. Every
  `[fNode]` host already carries a `ResizeObserver` that re-anchors its edges on any size change, so
  no `fNodeSize` or `redraw()` is involved. `fResizeHandle` is not used either: it writes a fixed
  height as well, which would freeze a body whose height comes from its content.
- **The grip is in the footer's bottom-right corner**, not down the right edge, because the output
  ports are 14px circles centred *on* that edge and a full-height grip would cover every one of
  them. The drag is hand-rolled with pointer capture and divides the pointer delta by the canvas
  scale — published by `FlowCanvas` through `ViewportStore` — since the pointer moves in screen
  pixels while the width is in canvas pixels. It rounds to 8px and snaps to the default and to
  widths other nodes in the document already have, so nodes lined up by eye end up identical rather
  than four pixels apart. The in-progress width stays in a local signal and dispatches once on
  release: one undo step per gesture, exactly as for a node being dragged.
- **A pointer drag is not the accessible route.** The same width is a number field (252–640, step 8)
  with a Reset in the inspector's footer, "Reset width" is in the node menu when there is a width to
  reset, and a double-click on the grip does the same.

Growing a node does **not** push its neighbours aside. The canvas is a layout the author arranged,
and a resize that moved four other nodes would be a resize that has to be undone.

#### The inspector panel, and why it is not a second node body

Twenty sampling knobs are workable in a 252px node only in the sense that they fit. The inspector
(`editor/inspector/`) is where one node's settings are actually edited: a docked column at the right
edge, or a card floating over the canvas, styled as the node it is editing — same accent header,
same 2px rule, same sunken body, same footer. It opens from the node's Advanced strip, the node
context menu and a toolbar toggle, and follows the canvas selection while it is not pinned. The
toggle is disabled while there is no node to show: the panel draws a node or draws nothing, so a
button that reported itself pressed over an empty screen would announce a panel nobody could see.

The rule that keeps it honest is that it is **not** a second implementation. `core/catalog/node-rows.ts`
is a pure function from an explicit context — node, descriptor, document, probe answers — to the
rows of one node: value, wired, discovered, allowed, applies, editable, changed. The node adds the
port half (connector id, type colour, row shape); the panel adds a label column, sub-sections from
each input's declared `group`, and a filter past ten rows — cleared when the panel moves to another
node, since a query typed on a twenty-row node would otherwise go on filtering a five-row one whose
header has no filter box to clear it with. A control this build cannot draw is drawn in both places
as the same placeholder, although it is not editable: a panel that dropped the row would disagree
with the node about what the node has. A condition, a narrowed list, a
discovered model list and a "wired" takeover therefore behave identically in the two places, because
there is one place that decides what they are.

Two things follow from the panel being outside the canvas. Its body *may* scroll, since it has no
ports to clip — which is the whole reason the settings are workable there. And a dropdown inside a
scrolling body cannot use an absolutely positioned list, because the body would clip it; `NodeWidget`
takes a `listPlacement`, and the panel passes `fixed`, which measures the trigger when the list opens
and pins the list to the viewport instead (flipping upward near the bottom, and closing once the
measurement goes stale — the page scrolling under it, or the window resizing). That scroll listener
is in the capture phase, because a scroll does not bubble and the container that moves is an
ancestor; capture also delivers scrolls from *descendants*, so the target is checked — the panel's
own option list is a scroller, and closing the list on the first wheel tick would make exactly the
thousand-model listing the feature exists for unusable. Inside the canvas the placement stays
`inline`: the canvas is transformed, so `position:
fixed` there is fixed to the transform rather than to the window.

The docked panel takes its width from the canvas rather than overlaying it, and the graph does not
move when it opens: the flow canvas's origin is its own left edge, so shrinking it from the right
leaves every node on the same pixel. (Foblex does expose a public way to shift the canvas — the
`position` input on `f-canvas`, against the combined position `fCanvasChange` reports — if a future
panel ever needs to open from the left.) Below 1100px the column becomes a right-edge overlay and
floating is turned off; below 640px it is a bottom sheet. All three are CSS media queries, so there
is no second source of truth about the window's size.

A fourth rule is about *options* rather than layout. `Widget.Dropdown.narrowedBy` says "offer only
the options the node wired into this socket lists in that input". That is what makes the LLM Request
node show only the response formats the connected model declares, automatically, while the editor
still knows nothing about models or response formats. Nothing wired in means nothing is known, which
is no reason to hide a choice, so every option is offered — an *empty* list is a statement and a
*missing* one is not.

#### A `display` row reports instead of asking

`Widget.Display` is the one widget kind that is not a control: its value is written by a node's
`discover` action — what a gateway publishes about itself, or about a model — and the node shows it.
The row model is where that has to be known. `isEditable` is false for a readout, so it is never
counted as changed, never touched by a reset dot or by "Reset *n* changes", and `withoutReadouts`
drops those keys both when a node is saved as a preset and when a preset is instantiated: what a
gateway answered last Tuesday is not part of a configuration, and a node that arrived already
claiming freshly-fetched facts would be worse than an empty one. `GraphStore.issues` skips readouts
in the required-value loop for the same reason — a fact nobody has fetched yet is not a missing
input, and reporting it would put a warning on the one row nobody can act on.

A readout draws its **own** label, because a fact reads as a two-column line — muted name left,
value right in tabular figures — rather than as a control under a caption; a node that reports
thirty facts would otherwise be sixty rows tall. That is also why the inspector suppresses its own
label row for these and renders the widget alone: one rendering of a readout, in both places. Three
styles carry the three shapes the facts actually have: a line, a paragraph (clamped, with the full
text offered in the existing full-window editor, read-only) and a set (read-only chips).

Three states stay distinguishable, which is the whole reason a readout does not fall back to its
declared default: nothing stored is "nobody has asked yet", `""` is "asked, and this gateway
publishes no answer", and anything else is a fact. A line that parses as an ISO-8601 instant is said
the way people say it — "6 minutes ago", recomputed each minute from one shared `MinuteClock` rather
than one timer per row — with the exact time in its tooltip, because a fetched fact is only as good
as its age.

## 4. Execution model

Topological order, computed once per run, cycles rejected before anything executes.
Each node runs on a **virtual thread** (Java 26); a run is cancellable at any node boundary and
between chunks inside long file walks.

`NodeContext` gives a node exactly four things and nothing else:

```java
<T> T input(String key, Class<T> as);   // typed, resolved from upstream
void output(String key, Object value);  // typed, checked against descriptor
void progress(double fraction, String message);
void stream(String key, String chunk);  // partial output, for a live view
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

A second pack — ten nodes for talking to LLMs — was added afterwards and is documented in
[LLM-NODES.md](LLM-NODES.md). What it cost the design is [§9](#9-the-second-node-pack-and-what-it-proved).

## 6. Verification — decided before implementation

| Rung        | What it catches                             | How it runs                     |
|-------------|---------------------------------------------|---------------------------------|
| types       | wire/DTO drift, template errors             | `tsc` strict + strict templates |
| unit (TS)   | assignability, commands/undo, cycle detect  | `ng test`                       |
| unit (Java) | assignability, topo sort, each node's logic | `gradlew test` (JUnit 6)        |
| contract    | Java ↔ TS type system agreement             | shared `type-assignability.json`|
| e2e         | the whole stack actually working together   | `EngineEndToEndTest`            |
| build       | the whole thing actually compiles           | `gradlew build`, `ng build`     |

The LLM pack adds rungs of the same shape and for the same reason: its request bodies are built by
pure functions so a test can assert **what went on the wire** rather than what was configured, its
pacing runs on an injected clock so "sixty a minute" can be asserted in milliseconds, and one test
drives the real HTTP path — buffered, streamed and failing — against a JDK `HttpServer`.

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

And, from driving the running editor rather than reading it:

- **A control nested inside a draggable palette item can never be clicked.** Foblex's
  `fExternalItem` sets `pointer-events: none` on *every descendant* of its host on init, so that a
  drag always targets the item. The preset rows had their delete button inside that host: visible,
  hoverable, focusable, and inert. The fix is structural — the button is a sibling of the draggable,
  not a child of it.
- **The JDK HTTP client's HTTP/2 default kills one gateway.** Over plain `http://` it opens with an
  h2c upgrade, and OmniRoute answers by closing the socket ("header parser received no bytes") while
  serving the identical request happily over 1.1. Nothing in this pack multiplexes, so the client is
  pinned to HTTP/1.1.
- **A discovered reasoning dialect without the reasoning switch disables reasoning.** Filling in
  `reasoning_effort` and leaving "Reasoning On" off means *send an instruction to not reason*, which
  a model that reasons mandatorily rejects on every call. The two are now discovered together,
  because they are one fact.
- **A test double that is not thread-safe fails inside the node under test.** `RecordingContext`
  collected logs in a plain `ArrayList`; the request node deliberately runs a batch concurrently,
  and the resulting `ArrayIndexOutOfBoundsException` surfaced from inside the node looking exactly
  like a bug in it.
- **A default URL in the code is a connection refused on every other machine.** The gateway kinds
  carried `http://localhost:8080/v1` and friends as defaults, and a blank Base URL took them
  silently. On the one machine where the gateways lived elsewhere, every test button answered
  `Connection refused: getsockopt`. No kind carries an address now; the profile requires one.
- **`"a" + "b".formatted(x)` formats only `"b"`.** Three messages shipped with a literal `%s` in
  them before the tests that read them caught it. The parentheses are not optional.
- **A `position: fixed` backdrop inside a transformed canvas is fixed to the transform.** The
  dropdown's click-away layer covered part of the screen, so a click on the next node landed on
  that node and left the list open on top of it. A document-level pointer listener replaced it.

And, from the visual pass over the first UI — all four invisible in the console, and all four found
only by looking at the pixels:

- **A large hollow ring at the end of every wire.** The flow library renders an `r=8` circle at each
  endpoint as the grab target for re-routing, and the theme's `connection-all()` mixin *strokes* it.
  It is not a decoration to delete: it is kept, and painted with a transparent fill so it stays
  hit-testable and stops being visible.
- **Specificity, not source order, decided the wire colour.** The library ships its rule as
  `f-flow .f-connection .f-connection-path, .f-flow .f-connection .f-connection-path` — the second
  alternative is three classes, so a two-class override loses however late it appears. Every wire
  silently fell back to one grey. The app's rules now mirror that selector shape.
- **`calc(var(--fill) * 1%)` killed the slider.** Angular's `[style.--fill.%]` writes `40%`, not
  `40`, so the multiplication produced `calc(40% * 1%)` — invalid, and one invalid stop discards the
  whole gradient. The track rendered as nothing at all.
- **Zoom buttons that did nothing.** `setScale` anchors on the flow origin unless given a point, and
  does not repaint; the graph crawled toward the top-left corner and only moved when some later
  gesture happened to trigger a redraw.

## 6b. Asking before running

Configuring a gateway used to be a guess that only got graded at the end of a run: a typo in a base
URL, a credential the engine cannot see, a model renamed last week and a response format the target
silently ignores all look identical until something fails halfway through a batch.

A **node action** is the answer to "is this right?" asked while the user is still looking at the
field. A descriptor advertises one; `NodeProbe` answers it; the editor draws a button and an
indicator and understands neither.

What crosses the wire is the property that makes this safe. `POST /api/nodes/{type}/probe/{action}`
takes **widget values only** — this node's, plus those of everything wired upstream of it, gathered
by the editor because the editor is where the graph lives. No graph, no node ids, no instruction to
execute anything. Pressing a test button can ask a gateway what it serves; it can never run the
Replace In Files node three hops back.

Two kinds, because two is what an indicator can show honestly: a `CHECK` lights up, and a `DISCOVER`
also hands back values and option lists. Discovered values are applied through the ordinary edit
command, so a discovery that guessed wrong is one `Ctrl+Z` away — which is the only reason writing
into a user's fields is defensible at all. Anything the gateway did not mention is left untouched: a
discovery that blanked a carefully set price because *this* gateway does not publish prices would be
a data-loss bug wearing a feature's clothes.

The same rule governs what discovery is allowed to claim. `ModelListingReader` normalises three
genuinely different `/v1/models` shapes — OpenRouter's `supported_parameters` and decimal-string
prices, OmniRoute's `capabilities` object and `api_format`, llama.cpp's bare `meta.n_ctx` — and
infers nothing beyond what is written down, because a capability this engine *guessed* is exactly
the untested claim `CapabilityCheck` exists to refuse.

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

**Profiles are referenced; presets are copied.** Two mechanisms, because they answer two questions.
A preset is a node configuration dropped onto a canvas, after which the node owns the values. A
profile is a named configuration kept on the engine and *resolved when the node runs*, so a workflow
carries the name "openrouter" and each machine decides what that means. That is the property an
environment-specific setting needs, and it is exactly the property a preset must not have. The
gateway *kind* inside a profile is a third thing again — what sort of server — and each of the three
has one word.

**Every button a node offers lives in its header.** A test bulb in the header of one node and a
magnifier beside a field of the next made one kind of control look like two. Field-level discovery
became automatic — the editor asks when the list is opened — so the only buttons left are node-level
ones, and they all sit in one place.

**Nodes grow; they do not scroll.** See [§3](#nodes-stay-small-by-three-rules-the-editor-applies-and-does-not-understand).

**A saved prompt is a preset.** The prompt editor's library reads and writes Prompt Template
presets rather than a store of its own. One mechanism means a prompt saved from an LLM Request node
appears in the Presets tab, in every other prompt editor, and can be dropped onto a canvas as a
configured node — and means there is exactly one place to delete it from.

## 8. Deliberately not in v1

Named so they are choices rather than oversights: WebGL renderer (the DOM path is behind an
interface, so it stays a swap), subgraphs/groups, collaborative editing, node versioning and graph
migration, authentication, persistent run history. A settings store, a workflow library and
settings migration were added later — [§10](#10-settings-the-workflow-library-and-bundles).

The LLM pack has its own list of deliberate omissions and its own reasoning — see
[LLM-NODES.md](LLM-NODES.md).

## 9. The second node pack, and what it proved

`nodes/llm` was added after everything above was written, and it is the test of whether the
extensibility claims were real. What it needed:

| Needed | Cost |
|---|---|
| eleven nodes | eleven files, zero frontend changes to make them appear |
| its own port types | one file (`LlmTypes`), same shape as `FileTypes` |
| five new widget kinds, two extended | one sealed case each, and the compiler pointed at every place that had to handle them |
| named, machine-specific configuration | `profiles/` — a generic store and a schema SPI, one schema so far |
| a live streaming channel | one event, one `NodeContext` default method |
| runtime-served dropdown options | one widget field and one REST endpoint — the frontend still knows no node types |
| credentials, pacing, wire formats | a subsystem of its own under `llm/`, reached only through `LlmCaller` |

What it did *not* need: a change to the type lattice, the graph validator, the execution engine, the
registry, or any existing node. The one thing that did have to change — `Widget` — changed because
it is sealed, which is exactly the failure mode sealing exists to produce.

## 10. Settings, the workflow library and bundles

Three things were missing for anyone with a second machine: a place to keep workflows other than a
downloads folder, a way to carry profiles, keys and presets across, and any settings at all. They
share one design and one page — the dialog behind the brand mark — and the decisions are these.

**One fixed home, two relocatable directories.** `settings.json` lives in `~/.unbi-engine` (or
`unbi.home`) and cannot move, because a setting that says where the settings are is a setting nobody
can find. Everything else can: the *data* directory (profiles, credentials, presets) and the
*workflows* directory are paths in that file, blank for the default. `DataDirectory` asks
`SettingsStore` for its root on every call and the stores read their files on every query, so
pointing the engine elsewhere takes effect on the next request rather than the next restart — the
only behaviour under which a "Change…" button in a running editor is honest. Relocation *copies*
what is there and never deletes: a half-failed move can lose a directory tree, and the cost of the
alternative is one folder to clean up by hand. Nothing at the destination is overwritten either.

**JSON, because everything else is.** Profiles, presets and workflows are JSON files written to be
opened in an editor; a TOML or YAML settings file would be a second syntax and a second parser for
one small file. Preferences are an opaque map of scalars the engine validates and never reads —
adding one is a frontend change — and the two paths are the only keys the engine understands.

**A workflow is a file named after itself.** The library is a folder: `<name>.unbi.json`, with the
favourites in `favorites/`. The name *is* the id, which is why it is validated against every
platform's filesystem rules before anything is written, and why starring is a move rather than a
flag — a subfolder is something anyone can see, sort and back up without the editor. The document
is stored verbatim after a format check; what a workflow means stays the editor's business. The
editor's `WorkflowSession` remembers which entry is on the canvas and decides "unsaved" by
reference: every command yields a new document and undo hands the old one back, so the canvas is
clean exactly when its document is the object that was last saved.

**A bundle is a zip with a readable manifest.** `.ucfg` is a zip so that the container is one
everybody already has a tool for, and AES-256 (zip4j) when a password is given so that the same
tools can open it. `manifest.json` is the one entry never encrypted: the import dialog reads it to
say what the file holds and whether it will ask for a password before touching anything, and it
carries counts, not contents. Which sections exist is a matter of which `SettingsSection` beans
exist — profiles, credentials, presets, workflows, preferences — each owning its own file names,
validation and idea of "already exists", with one conflict policy (skip, replace, keep both) applied
by all of them. Every incoming entry name is treated as hostile until it has been shown to be one of
the section's own; `presets/../.bashrc` is exactly what a zip can carry. A password is optional
throughout: the credentials section is marked sensitive and the dialog warns in colour when keys
would travel in plain text, and that is the user's call to make. Paths are never exported.

**Runtime i18n, not `$localize`.** Angular's compile-time i18n produces one build per language and
switches by loading a different bundle, which does not fit an editor served as one build by the
engine, or a language setting that lives on the engine and takes effect when it is changed. The
`Translator` is a signal-backed map lookup with `{placeholder}` filling and `Intl.PluralRules`; the
`t` and `n` pipes are impure because a pure pipe would cache the answer for a key that never
changes. Keys are typed: a typo in a template is a compile error, and a second language is one
module typed `Record<MessageKey, string>`, so a missing key fails the build rather than showing
English in a German dialog. Only the shell chrome and the new dialogs are keyed so far; node bodies,
the inspector and the widgets are not, and node names and hints come from the engine untranslated.

**What this found.** Every earlier dialog carried its `z-index` on a host styled `display: contents`
— a box that does not exist — so their layering was DOM order all along. The settings dialog was the
first to put a real `z-index` on a scrim, and promptly painted over the folder picker it had just
opened. The values now sit on the boxes that paint. And the browser honours the `autofocus`
attribute once per document: the first dialog inserted with it gets focus and every later one does
not, hence the `appAutofocus` directive.
