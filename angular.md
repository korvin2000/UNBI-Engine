
| Graph lib	| OOP/DI fit | 	Reactive model	| Ecosystem risk |	TS strictness |
| - | - | - | - | - |
| ngx-vflow (signals-native), Rete.js v2 + Angular renderer, Foblex Flow | Best — DI + multi-providers ≈ a plugin system for free |	Signals + RxJS, exactly your model | Highest — no first-party xyflow port | Best (strict template checking) |

Angular gives me the cleanest, most uniform architecture. For a codebase you'll maintain for years with strict layering, Angular's output reads better.


A sub-projects / tasks:
UI-фреймворк, библиотеку редактора графа и движок выполнения workflow. Расширяемость узлов и корректность вычислений преимущественно определяются архитектурой последних двух.

По сочетанию ООП и реактивного программирования Angular подходит вам естественнее. Я использовал бы standalone-компоненты, классы сервисов и DI, Signals для состояния интерфейса, RxJS для потоков событий выполнения, отмены и сетевого взаимодействия. Angular предоставляет официальный мост между Signals и Observables. Компоненты Angular, RxJS interop.

Foblex Flow при этом закрывает перемещение, соединения, масштабирование и другие взаимодействия редактора, поддерживает ограничения соединений и содержит примеры с анимированными связями. Поэтому выбирать Angular для этой задачи вполне обоснованно. Возможности Foblex, правила соединений.


##  Чтобы код оставался компактным и расширяемым, независимо от UI я заложил бы следующие границы:

- NodeDefinition — тип и версия узла, входы/выходы, схема настроек. Типовые формы генерируются из описания; специализированный UI подключается при необходимости.
- NodeView и NodeExecutor — отдельные реализации отображения и выполнения. Вычислительная логика может использовать классы, интерфейсы и композицию даже при функциональном React UI.
- GraphValidator — совместимость типов портов, обязательные входы, допустимость циклов. TypeScript не проверит граф, который пользователь собрал во время работы; нужны runtime-проверки, включая backend.
- WorkflowDocument и RunState — отдельно сохраняемый граф и текущее выполнение. Backend передаёт события конкретных узлов; UI обновляет их статус, прогресс и анимацию.

Для ООП я предпочёл бы небольшие интерфейсы и композицию поведения узлов: глубокая иерархия наследования быстро усложнит создание новых комбинаций возможностей.

для Java потребуется собственный исполнитель либо интеграция с JS. Движки Rete, серверное выполнение

## Rendering — the real scaling constraint
   DOM-based (all xyflow-family libs) is fine to ~500–1500 visible nodes with viewport virtualization. ComfyUI uses canvas (LiteGraph) precisely to go past that. Plan for:

- v1: DOM/SVG nodes (ngx-vflow / Rete). Ship fast.
- escape hatch: PixiJS/WebGL layer for edges + collapsed node frames, DOM only for the focused/expanded node. Keep the renderer behind an interface so this is a swap, not a rewrite.

## Architecture sketch
packages/
  graph-core/     pure TS — no Angular. domain, types, validation, execution
  graph-render/   renderer interface + dom impl (+ webgl impl later)
  app-angular/    DI wiring, inspector panels, node widget components
  nodes-*/        node packs, each a manifest + optional custom widget

Pseudo code
'''ts
// ---- port type system (chaiNNer solved this with a mini type lang; do the same)
type PortType =
  | Primitive("Image" | "Number" | "String" | "Model")
  | Struct({ fields: Map<string, PortType> })
  | Union(PortType[])
  | Constrained(base, predicate)      // e.g. Image with channels=4

interface TypeSystem {
  assignable(from: PortType, to: PortType): boolean   // memoized, pure
  narrow(t: PortType, c: Constraint): PortType
  describe(t: PortType): string                        // for tooltips/errors
}

// ---- node definition = data, not code. lets backend ship new nodes w/o FE rebuild
interface NodeDef {
  id, category, icon, label
  inputs:  PortDef[]        // { key, type, widget?, default? }
  outputs: PortDef[]        // type may be an expression over input types
  inferOutputs?(inputs: PortType[]): PortType[]   // static type propagation
  execute?: "backend" | LocalFn
}

class NodeRegistry {
  register(def: NodeDef)
  fromManifest(json)        // hot-loadable packs
  get(id): NodeDef
}

// ---- graph = immutable doc + command pattern (undo/redo, and CRDT-ready)
interface Command { apply(doc): doc; invert(): Command }
class GraphStore {
  doc: Signal<GraphDoc>
  dispatch(cmd: Command)
  undo() / redo()
}

// ---- validation as derived state, never imperative
validation = computed(() => {
  for edge in doc().edges:
    srcT = inferredType(edge.source)          // topological, memoized per node
    dstT = registry.get(edge.targetNode).inputs[edge.targetPort].type
    if !types.assignable(srcT, dstT) -> Error(edge, explain(srcT, dstT))
  detectCycles(doc())
})

// ---- drag-time affordance: highlight only compatible ports
compatiblePorts = computed(() =>
  dragging() ? allPorts().filter(p => types.assignable(dragging().type, p.type)) : [])
'''

Key decisions worth locking in early

- Node defs are data (JSON manifest), not components. Custom widget is optional and looked up by key. This is what makes "adding a node" a 20-line change and lets Java/Node backend own the catalog.
- Static type propagation (inferOutputs) — without it, Upscale → Merge Transparency can't know the output is RGB not RGBA. chaiNNer built a whole type language for this; a Union/Struct/Constrained lattice is enough.
- Command pattern from day one. Retrofitting undo onto mutable state is the classic failure.
- Execution is the backend's job. Frontend sends a topologically-sorted DAG; backend streams per-node status over WebSocket/SSE (queued | running | progress% | done | error). Node glow/spinner/edge-flow animation binds to that stream — pure CSS (stroke-dashoffset) + Web Animations API, no JS per frame.
- Styling: SCSS + CSS custom properties for theming (node colors per category, as in your screenshot). Avoid CSS-in-JS — it costs you at 1000 nodes.


## Angular stack

My "not best" verdict rested on one claim: that Angular lacks a mature node-editor library. I checked, and that claim is weaker in Sept 2026 than when I made it.

Landscape now:

- Foblex Flow — Angular-native, MIT, actively maintained. Ships connection rules and reassign, waypoints, magnetic alignment, selection area, minimap, keyboard control schemes, plus optional managed state with snapshots/undo/redo, caching and virtualization. Positions itself explicitly at workflow builders and AI pipeline editors.
- ngx-vflow — Angular-native, React-Flow-shaped nodes[]/edges[] model, subflows, connection validation, dagre layout, custom edge templates. Also real and maintained.
- Still no official xyflow Angular port — the request threads are open and unanswered. ngx-xyflow is a beta wrapper that runs React internally (two runtimes, change-detection seams — avoid). ngflow is days old with two commits (ignore).

Caveat: the head-to-head comparison I found is published by Foblex itself, so treat the framing as vendor-authored; the feature lists are verifiable, the "most widely adopted" claim is not.

So the honest cost of Angular is now weeks, not months — and it's concentrated in bus-factor risk, not missing features.

### Where Angular is genuinely the best of the four for this project

01. DI multi-providers give you the plugin system as a language feature. This is not a stylistic preference; it's the thing you'd otherwise hand-roll.

'''ts
export const NODE_DEF = new InjectionToken<NodeDef[]>('NODE_DEF');
export const provideNode = (d: NodeDef): Provider =>
  ({ provide: NODE_DEF, useValue: d, multi: true });

// nodes-image/index.ts — a node pack is just an array of providers
export const imageNodes = [
  provideNode(loadImage), provideNode(upscaleImage),
  provideNode(splitTransparency), provideNode(mergeTransparency),
];

@Injectable({ providedIn: 'root' })
export class NodeRegistry {
  private byId = new Map(inject(NODE_DEF).map(d => [d.id, d]));
  get(id: string) { return this.byId.get(id); }
}

// lazy pack loading, still free:
createEnvironmentInjector([...customNodes], parentInjector);
'''

Adding a node pack = adding a provider array. Overriding a node's widget for one deployment = overriding a provider. In React/Svelte you write a registry singleton and a loader by hand.

02. The template model matches your screenshot. Those nodes aren't boxes with labels — they're dropdowns, sliders with numeric spinners, file pickers, image previews, collapsible sections, eye toggles, timing badges. That's substantial product UI with forms, DI and validation inside each node. Foblex's template-first model (<f-node> wrapping your own components) is a better fit than a nodes[] data model where each node's content is a registered component. This is the case where Angular's heavier component machinery actually pays.

03. RxJS for the execution stream. WebSocket queued|running|progress|done|error per node, drag gestures, keyboard combos, debounced autosave — signals for view state, RxJS for events. Nothing in the other three is better at this.

04. Strict template type checking matters more than usual when your port type system is a lattice of Union/Struct/Constrained.

05. Your own throughput. Deep Angular experience is worth more than a 20% code-density difference between frameworks. That's not flattery — it's the largest single term in the estimate.

## Verdict

Angular is justified. Not "acceptable given your bias" — justified on the merits for a node editor whose nodes are rich forms and whose extensibility story is a plugin registry.

'''txt
Angular 20+, zoneless, standalone, strict templates
Foblex Flow  (template model — fits rich node UI)
  ngx-vflow instead if you prefer a data-first nodes[]/edges[] model
signals = view state | RxJS = backend streams + gestures
SCSS + CSS custom properties (per-category node accents)
Nx monorepo: graph-core (zero Angular) | graph-render | app | nodes-*
Backend: Spring or NestJS; JSON Schema generated from graph-core types
'''

