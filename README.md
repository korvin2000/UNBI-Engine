# UNBI-Engine

**U**niversal **N**ode-**B**ased **I**ntelligence Engine — a node/flow editor and execution engine
in the shape of [chaiNNer](https://github.com/chaiNNer-org/chainner), but domain-agnostic.

Spring Boot 4.1 / Java 26 backend, Angular 22 frontend, WebSocket transport. The first node pack
does batch file processing: scan a folder, filter by type, search text, replace text, write a report.

![the editor](docs/screenshot.png)

---

## Running it

Both halves need to be running. The backend serves the node catalog and executes graphs; the
frontend is the editor.

**Backend** — needs a JDK 26 on `JAVA_HOME`:

```bash
./gradlew :backend:bootRun
```

**Frontend** — needs Node 20+:

```bash
npm --prefix frontend install && npm --prefix frontend start
```

Then open <http://localhost:4200>. The status pill in the toolbar turns green when the editor has
found the engine.

To try the shipped example, press the ★ button in the toolbar: it builds a
*scan → filter → search → report* pipeline. Point **Scan Directory** at a folder, set a search term,
and press ▶.

### No JDK installed?

The build uses a Gradle toolchain, so `gradlew` will fetch a JDK 26 if your Gradle is configured
with a toolchain repository. Otherwise download one (for example
[Temurin 26](https://adoptium.net/)) and point `JAVA_HOME` at it. Nothing needs to be installed
system-wide — an extracted archive is enough.

---

## Layout

```
contract/          one shared test table, read by both language implementations
backend/
  core/            pure Java: port types, graph, validation, node SPI. No Spring.
  registry/        NodeRegistry — DI-discovered node index
  engine/          scheduler, run lifecycle, cancellation
  nodes/           one class per node, auto-registered
  transport/       WebSocket handler, REST catalog, JSON codecs
frontend/src/app/
  core/            type mirror, graph store + commands, catalog, engine socket
  editor/          canvas, palette, toolbar
  widgets/         the seven input controls a node can declare
docs/ARCHITECTURE.md   the design, and why
```

---

## The three ideas worth knowing

**A node is one file.** A node is a `@Component` implementing `NodeDefinition` — a descriptor plus
an `execute`. Spring injects every one of them into `NodeRegistry`. There is no manifest to edit and
no registration call to forget; dropping a class onto the classpath puts it in the palette.

**The frontend has no node definitions in it.** Descriptors are served from `GET /api/catalog` and
rendered generically. Adding a node, or adding a widget to an existing one, needs no frontend
change at all — this was verified during development by adding an input to the report node and
watching it appear in the editor after a backend restart.

**One rule, two implementations, one test table.** Port compatibility is decided by
`assignable(from, to)`, which exists in Java (authoritative — it validates every graph before
running) and in TypeScript (so the editor can refuse an illegal edge during the drag, without a
round trip). Two implementations of one domain rule is the classic way behaviour drifts, so both
are held to [`contract/type-assignability.json`](contract/type-assignability.json). Add a rule?
Add rows there first; one of the two suites will go red until both agree.

---

## Tests

```bash
./gradlew :backend:test          # 91 tests
npm --prefix frontend test       # 54 tests
```

The backend suite includes an end-to-end test that boots the application, fetches the catalog over
HTTP, runs a scan → filter → search → report pipeline over a real WebSocket against a temporary
directory, and asserts the report file on disk.

---

## Node pack: batch file processing

| Node | Does |
|---|---|
| **Directory** | Names a folder once so several nodes can share it |
| **Scan Directory** | Lists files, with glob, recursion, depth and count limits |
| **Filter Files** | Keeps or removes files by extension and minimum size |
| **Search In Files** | Literal or regex search, reporting line and column per hit |
| **Replace In Files** | Search and replace across many files — **dry run by default** |
| **Generate Report** | Markdown, CSV or plain text; optionally written to disk |
| **Preview** | Shows whatever is wired into it |

`Replace In Files` starts in dry-run mode deliberately. It is the only node that destroys
information, and the two outcomes are not symmetric: a needless dry run costs one click, an
unintended write costs a directory tree.

---

## Status

A first prototype. Working and verified end to end, with real limits — see
[the architecture notes](docs/ARCHITECTURE.md#8-deliberately-not-in-v1) for what was deliberately
left out and where the seams for it are.
