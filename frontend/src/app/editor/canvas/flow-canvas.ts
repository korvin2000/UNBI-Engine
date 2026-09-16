import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import {
  FCanvasComponent,
  FCreateConnectionEvent,
  FCreateNodeEvent,
  FDeleteSelectedEvent,
  FFlowComponent,
  FFlowModule,
  FMoveNodesEvent,
  FReassignConnectionEvent,
  FSelectionChangeEvent,
} from '@foblex/flow';
import { CatalogService } from '../../core/catalog/catalog.service';
import { NodeSpec } from '../../core/catalog/catalog.models';
import { withoutReadouts } from '../../core/catalog/node-rows';
import * as commands from '../../core/graph/commands';
import { GraphStore } from '../../core/graph/graph-store';
import { PresetService } from '../../core/presets/preset.service';
import { CanvasSelection } from './canvas-selection';
import { InspectorStore } from '../inspector/inspector-store';
import { Point, connectorId, newNode } from '../../core/graph/workflow.models';
import { RunStore } from '../../core/runtime/run-store';
import { assignable } from '../../core/types/assignability';
import { portColourKey } from '../../core/types/port-type';
import { Icon } from '../../shared/icon';
import { ViewportStore } from './viewport-store';
import { WorkflowNode } from './workflow-node';

/** What a context menu can do. A union so the template cannot ask for anything else. */
type MenuAction =
  | 'rename'
  | 'settings'
  | 'save-preset'
  | 'collapse'
  | 'disable'
  | 'duplicate'
  | 'reset-width'
  | 'disconnect'
  | 'delete'
  | 'fit'
  | 'select-all'
  | 'clear';

/** Marks palette drag data as a saved configuration rather than a bare node type. */
export const PRESET_PREFIX = 'preset:';

interface OpenMenu {
  /** `null` when the menu was opened on empty canvas rather than on a node. */
  readonly nodeId: string | null;
  readonly x: number;
  readonly y: number;
  readonly collapsed: boolean;
  readonly disabled: boolean;
  /** Whether this node has a width of its own, which is the only case "Reset width" applies to. */
  readonly resized: boolean;
}

/**
 * The canvas.
 *
 * Owns the graph document (classic Foblex mode: the app holds state, the library reports gestures)
 * and translates every gesture into a command. Nothing here mutates the document directly, so undo
 * covers dragging a node from the palette exactly as it covers typing in a field.
 */
@Component({
  selector: 'app-flow-canvas',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FFlowModule, Icon, WorkflowNode],
  templateUrl: './flow-canvas.html',
  styleUrl: './flow-canvas.scss',
  host: {
    // Right-clicking a node is handled by the node, which stops propagation, so anything that
    // reaches here happened on empty canvas.
    '(contextmenu)': 'openCanvasMenu($event)',
  },
})
export class FlowCanvas {
  private readonly graph = inject(GraphStore);
  private readonly catalog = inject(CatalogService);
  private readonly runs = inject(RunStore);
  private readonly presets = inject(PresetService);
  private readonly inspector = inject(InspectorStore);
  private readonly viewport = inject(ViewportStore);
  private readonly selection = inject(CanvasSelection);
  private readonly host = inject(ElementRef<HTMLElement>);

  private readonly canvas = viewChild.required(FCanvasComponent);
  private readonly flow = viewChild(FFlowComponent);

  constructor() {
    // The library's half of the selection, handed to whatever selects a node without the library
    // having seen the press — see CanvasSelection. `isSelectedChanged: false` because the store has
    // already been written here, and the library's own state restore uses it the same way.
    const forget = this.selection.register((ids) => this.flow()?.select([...ids], [], false));
    inject(DestroyRef).onDestroy(forget);
  }

  /**
   * Zoom limits, shared with the wheel.
   *
   * The library clamps wheel zoom to the directive's own minimum and maximum; if the buttons used
   * different numbers the canvas would have two disagreeing notions of "as far as it goes".
   */
  protected readonly MIN_ZOOM = 0.2;
  protected readonly MAX_ZOOM = 2.5;

  protected readonly doc = this.graph.doc;
  protected readonly zoomLabel = signal('100%');
  protected readonly menu = signal<OpenMenu | null>(null);

  /**
   * The node whose title is being edited, if any.
   *
   * Held here rather than in the node component because the canvas is what starts the edit: the
   * context menu lives here, so "Rename…" has to reach a node that may not even be the one the
   * menu was opened over. (The `@for` below tracks by node id, so the node components themselves
   * are reused across document changes and could hold per-instance state — the fold does.)
   */
  protected readonly renameRequested = signal<string | null>(null);

  /** Node plus its descriptor, resolved once per render rather than per binding. */
  protected readonly placed = computed(() => {
    const specs = this.catalog.byId();
    return this.doc()
      .nodes.map((node) => ({ node, spec: specs.get(node.type) }))
      .filter((entry): entry is { node: (typeof entry)['node']; spec: NodeSpec } => entry.spec !== undefined);
  });

  protected readonly edges = computed(() => {
    const invalid = this.graph.issuesByEdge();
    const statuses = this.runs.nodeStatuses();
    const excluded = this.graph.excluded();
    const specs = this.catalog.byId();
    return this.doc().edges.map((edge) => {
      const sourceSpec = specs.get(this.graph.node(edge.sourceNode)?.type ?? '');
      const output = sourceSpec?.outputs.find((port) => port.key === edge.sourcePort);
      return {
        edge,
        source: connectorId.output(edge.sourceNode, edge.sourcePort),
        target: connectorId.input(edge.targetNode, edge.targetPort),
        invalid: invalid.has(edge.id),
        // An edge carries the colour of what flows along it, which is what lets you follow one
        // kind of value through a large graph without reading a label.
        colourClass: output ? `port-${portColourKey(output.type)}` : '',
        muted: excluded.has(edge.sourceNode) || excluded.has(edge.targetNode),
        // An edge animates once its producer is done and its consumer has not finished: that is
        // exactly the window in which data is conceptually in flight.
        flowing:
          statuses.get(edge.sourceNode)?.state === 'COMPLETED' &&
          statuses.get(edge.targetNode)?.state === 'RUNNING',
      };
    });
  });

  protected readonly isEmpty = computed(() => this.doc().nodes.length === 0);

  // --- Gestures -----------------------------------------------------------

  protected onCreateConnection(event: FCreateConnectionEvent): void {
    if (!event.targetId) {
      return;
    }
    const source = connectorId.parse(event.sourceId);
    const target = connectorId.parse(event.targetId);
    if (!source || !target || source.direction !== 'out' || target.direction !== 'in') {
      return;
    }
    if (source.nodeId === target.nodeId || !this.isCompatible(source, target)) {
      return;
    }
    this.graph.dispatch(
      commands.connect({
        id: crypto.randomUUID(),
        sourceNode: source.nodeId,
        sourcePort: source.portKey,
        targetNode: target.nodeId,
        targetPort: target.portKey,
      }),
    );
  }

  protected onReassignConnection(event: FReassignConnectionEvent): void {
    // Reassign is a disconnect plus a connect. Modelling it as one command would need a third
    // command type that does nothing the other two do not.
    this.graph.dispatch(commands.removeEdges([event.connectionId]));
    if (event.nextTargetId && event.nextSourceId) {
      this.onCreateConnection(
        new FCreateConnectionEvent(event.nextSourceId, event.nextTargetId, event.dropPosition),
      );
    }
  }

  protected onMoveNodes(event: FMoveNodesEvent): void {
    // Emitted once when the drag settles, not per frame, so one history entry per drag is correct.
    const moved = new Map<string, Point>(
      event.nodes.map((node) => [node.id, { x: node.position.x, y: node.position.y }]),
    );
    if (moved.size > 0) {
      this.graph.dispatch(commands.moveNodes(moved));
    }
  }

  /**
   * Something dragged out of the palette.
   *
   * `data` is a node id, or `preset:<id>` for a saved configuration. One channel rather than two
   * drop handlers, because to the canvas both are the same gesture producing the same command — the
   * only difference is which values the new node starts with.
   */
  protected onCreateNode(event: FCreateNodeEvent<string>): void {
    const at = event.dropPosition ?? { x: event.externalItemRect.x, y: event.externalItemRect.y };
    if (event.data.startsWith(PRESET_PREFIX)) {
      this.addPreset(event.data.slice(PRESET_PREFIX.length), { x: at.x, y: at.y });
      return;
    }
    const spec = this.catalog.byId().get(event.data);
    if (!spec) {
      return;
    }
    this.graph.dispatch(commands.addNode(newNode(spec.id, { x: at.x, y: at.y }), spec.label));
  }

  private addPreset(presetId: string, at: Point): void {
    const preset = this.presets.all().find((candidate) => candidate.id === presetId);
    const spec = preset ? this.catalog.byId().get(preset.nodeType) : undefined;
    if (!preset || !spec) {
      return;
    }
    // The preset's name becomes the node's, which is the whole point of naming one: a canvas with
    // four LLM Request nodes on it should say which is which. Readouts are dropped on the way in as
    // well as on the way out, because a preset saved by an older build may still carry them — and a
    // node that arrives already claiming facts nobody fetched is worse than an empty one.
    this.graph.dispatch(
      commands.addNode(
        newNode(preset.nodeType, at, withoutReadouts(spec, preset.values), preset.name),
        preset.name,
      ),
    );
  }

  protected onSelectionChange(event: FSelectionChangeEvent): void {
    this.graph.select(event.nodeIds, event.connectionIds);
  }

  /**
   * Delete, from the library's own key handling.
   *
   * A selection can be a mix of nodes and connections; deleting only the nodes made a selected
   * wire look undeletable.
   */
  protected onDeleteSelected(event: FDeleteSelectedEvent): void {
    if (event.nodeIds.length > 0 || event.connectionIds.length > 0) {
      this.graph.dispatch(commands.removeSelection(event.nodeIds, event.connectionIds));
    }
  }

  // --- Context menu -------------------------------------------------------

  protected openMenu(request: { nodeId: string; x: number; y: number }): void {
    const node = this.graph.node(request.nodeId);
    if (!node) {
      return;
    }
    // Right-clicking a node that is not in the selection acts on that node, which is what every
    // other editor does and what stops a menu from silently applying to something off screen.
    if (!this.graph.selection().has(node.id)) {
      this.selection.select([node.id]);
    }
    this.showMenu(request.x, request.y, {
      nodeId: node.id,
      collapsed: node.collapsed,
      disabled: node.disabled,
      resized: node.width !== undefined,
    });
  }

  protected openCanvasMenu(event: MouseEvent): void {
    event.preventDefault();
    this.showMenu(event.clientX, event.clientY, {
      nodeId: null,
      collapsed: false,
      disabled: false,
      resized: false,
    });
  }

  private showMenu(clientX: number, clientY: number, about: Omit<OpenMenu, 'x' | 'y'>): void {
    const rect = this.host.nativeElement.getBoundingClientRect();
    // Roughly how tall the menu about to open is, so it is placed somewhere it fits rather than
    // clipped at the bottom edge. "Reset width" is conditional, so it counts a row.
    const height = about.nodeId ? 282 + (about.resized ? 26 : 0) : 128;
    this.menu.set({
      ...about,
      // Clamped inside the canvas so a menu opened near the right or bottom edge stays reachable.
      x: Math.max(4, Math.min(clientX - rect.left, rect.width - 180)),
      y: Math.max(4, Math.min(clientY - rect.top, rect.height - height)),
    });
  }

  protected closeMenu(): void {
    this.menu.set(null);
  }

  protected finishRename(): void {
    this.renameRequested.set(null);
  }

  /**
   * Saves this node's current settings under a name.
   *
   * Widget values only. Position, connections and run state are properties of this graph, not of
   * the configuration, and a preset that carried them would drop a node onto the next canvas in
   * the wrong place, half-connected.
   *
   * Readouts go the same way and for the same reason: what a gateway answered is not a setting, and
   * a preset carrying last week's credit balance would present it as freshly fetched.
   */
  private saveAsPreset(nodeId: string): void {
    const node = this.graph.node(nodeId);
    const spec = node ? this.catalog.byId().get(node.type) : undefined;
    if (!node || !spec) {
      return;
    }
    void this.presets.askToSave({
      name: node.title || spec.label,
      group: spec.category,
      description: spec.description,
      nodeType: node.type,
      values: withoutReadouts(spec, node.values),
    });
  }

  protected runMenu(action: MenuAction): void {
    const open = this.menu();
    this.closeMenu();
    if (!open) {
      return;
    }

    switch (action) {
      case 'fit':
        this.fit();
        return;
      case 'select-all':
        this.selection.select(this.doc().nodes.map((node) => node.id));
        return;
      case 'clear':
        this.graph.clear();
        return;
      default:
        break;
    }

    if (!open.nodeId) {
      return;
    }
    const nodeId = open.nodeId;
    const targets = this.graph.selection().has(nodeId) ? [...this.graph.selection()] : [nodeId];

    switch (action) {
      case 'rename':
        this.renameRequested.set(nodeId);
        break;
      case 'settings':
        this.inspector.show(nodeId);
        break;
      case 'save-preset':
        this.saveAsPreset(nodeId);
        break;
      case 'collapse':
        targets.forEach((id) => this.graph.dispatch(commands.toggleCollapsed(id)));
        break;
      case 'disable':
        targets.forEach((id) => this.graph.dispatch(commands.toggleDisabled(id)));
        break;
      case 'duplicate':
        this.graph.dispatch(commands.duplicateNodes(targets, () => crypto.randomUUID()));
        break;
      case 'reset-width':
        this.graph.dispatch(commands.resizeNodes(targets, undefined));
        break;
      case 'disconnect': {
        const attached = this.doc()
          .edges.filter((edge) => targets.includes(edge.sourceNode) || targets.includes(edge.targetNode))
          .map((edge) => edge.id);
        if (attached.length > 0) {
          this.graph.dispatch(commands.removeEdges(attached));
        }
        break;
      }
      case 'delete':
        this.graph.dispatch(commands.removeNodes(targets));
        break;
      default:
        break;
    }
  }

  // --- Viewport -----------------------------------------------------------

  /**
   * Fitting must wait for nodes to be measured.
   *
   * Foblex computes the viewport from the node bounding box, so calling this earlier produces a
   * wrong transform and a dev-mode FF1009 warning.
   */
  protected onNodesRendered(): void {
    if (!this.isEmpty()) {
      this.canvas().fitToScreen({ x: 90, y: 90 }, false);
      this.syncZoomLabel();
    }
  }

  protected zoomIn(): void {
    this.zoomTo(this.canvas().getScale() * 1.25);
  }

  protected zoomOut(): void {
    this.zoomTo(this.canvas().getScale() / 1.25);
  }

  protected fit(): void {
    this.canvas().fitToScreen({ x: 90, y: 90 }, true);
    this.syncZoomLabel();
  }

  protected resetZoom(): void {
    this.canvas().resetScaleAndCenter(true);
    this.syncZoomLabel();
  }

  protected onCanvasChange(): void {
    this.syncZoomLabel();
  }

  /**
   * Zooms about the middle of the visible canvas.
   *
   * Two things the library will not do for a programmatic zoom, and both are why the buttons used
   * to feel broken: `setScale` anchors on the flow origin unless it is given a point — so zooming
   * walked the graph off toward the top-left corner — and it does not repaint, so nothing moved at
   * all until some later gesture happened to trigger a redraw.
   */
  private zoomTo(scale: number): void {
    const canvas = this.canvas();
    const next = clamp(scale, this.MIN_ZOOM, this.MAX_ZOOM);
    if (Math.abs(next - canvas.getScale()) < 0.0001) {
      return;
    }
    const rect = this.host.nativeElement.getBoundingClientRect();
    canvas.setScale(next, { x: rect.width / 2, y: rect.height / 2 });
    canvas.redrawWithAnimation();
    canvas.emitCanvasChangeEvent();
    this.syncZoomLabel();
  }

  /**
   * The zoom, as the toolbar shows it and as the nodes need it.
   *
   * Called from every gesture and every programmatic change, which is why publishing the scale
   * belongs here rather than in `onCanvasChange` alone: a node's width grip divides the pointer
   * delta by this number, and a stale scale after a fit would make one drag resize by double.
   */
  private syncZoomLabel(): void {
    const scale = this.canvas().getScale();
    this.zoomLabel.set(`${Math.round(scale * 100)}%`);
    this.viewport.setScale(scale);
  }

  /**
   * The drag-time legality check.
   *
   * Runs the same assignability rule the backend uses, which is why refusing here and refusing
   * there can never disagree — see `contract/type-assignability.json`. The flow library is given
   * the same rule up front (`fCanBeConnectedTo`), so this is the belt to that pair of braces.
   */
  private isCompatible(
    source: { nodeId: string; portKey: string },
    target: { nodeId: string; portKey: string },
  ): boolean {
    const specs = this.catalog.byId();
    const sourceSpec = specs.get(this.graph.node(source.nodeId)?.type ?? '');
    const targetSpec = specs.get(this.graph.node(target.nodeId)?.type ?? '');
    const output = sourceSpec?.outputs.find((port) => port.key === source.portKey);
    const input = targetSpec?.inputs.find((port) => port.key === target.portKey);
    if (!output || !input || !input.connectable) {
      return false;
    }
    return assignable(output.type, input.type);
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}
