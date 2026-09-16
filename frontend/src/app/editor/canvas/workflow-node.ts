import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { FFlowModule } from '@foblex/flow';
import { CatalogService } from '../../core/catalog/catalog.service';
import { NodeActionSpec, NodeSpec } from '../../core/catalog/catalog.models';
import {
  RowKind,
  SettingRow,
  buildRows,
  changedCount,
  sectionRows,
  serialise,
} from '../../core/catalog/node-rows';
import { NodeProbeService } from '../../core/catalog/node-probe.service';
import * as commands from '../../core/graph/commands';
import { GraphStore } from '../../core/graph/graph-store';
import {
  DEFAULT_NODE_WIDTH,
  MAX_NODE_WIDTH,
  MIN_NODE_WIDTH,
  NODE_WIDTH_STEP,
  WorkflowNode as WorkflowNodeModel,
  connectorId,
} from '../../core/graph/workflow.models';
import { RunStore } from '../../core/runtime/run-store';
import { describeType, portColourKey, typeKey } from '../../core/types/port-type';
import { Icon } from '../../shared/icon';
import { NodeWidget } from '../../widgets/node-widget';
import { InspectorStore } from '../inspector/inspector-store';
import { CanvasSelection } from './canvas-selection';
import { ViewportStore } from './viewport-store';

/** How long a value may be in a summary chip before the end of it stops carrying information. */
const CHIP_VALUE_LIMIT = 18;

/**
 * How close the grip has to come to a width that already exists before it takes it.
 *
 * Six pixels, which is about a pointer's worth of imprecision: close enough that "line this up with
 * the one next to it" happens on its own, small enough that a width chosen deliberately two steps
 * away is still reachable.
 */
const SNAP_RANGE = 6;

/**
 * One node on the canvas.
 *
 * The node is a small form, not a labelled box: an accent-tinted header over an accent rule, a body
 * banded into an input section and an output section, and a footer carrying the on/off switch, run
 * state and timing. That shape is what makes a chaiNNer graph readable, and it is why the template
 * model was the right choice of flow library.
 *
 * Everything it draws comes from the descriptor the backend served, so this component never learns
 * about any particular node type.
 */
@Component({
  selector: 'app-workflow-node',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // NgTemplateOutlet draws a settings row once and uses it in both sections; two copies of that
  // block would drift the moment one of them was touched.
  imports: [FFlowModule, Icon, NodeWidget, NgTemplateOutlet],
  templateUrl: './workflow-node.html',
  styleUrl: './workflow-node.scss',
  host: {
    '[class]': '"accent-" + spec().accent',
    // The node's own width, overriding the token for this instance only. A plain CSS width is the
    // whole mechanism: every `[fNode]` host carries a ResizeObserver that re-anchors its edges on
    // any size change, so nothing here has to tell the flow library that the node grew.
    '[style.--node-width.px]': 'widthPx()',
    '[class.is-resizing]': 'draftWidth() !== null',
    '[class.is-collapsed]': 'node().collapsed',
    '[class.is-disabled]': 'node().disabled',
    '[class.is-bypassed]': 'bypassed()',
    '[class.has-problem]': 'problems().length > 0',
    '[class.is-running]': 'status()?.state === "RUNNING"',
    '[class.is-failed]': 'status()?.state === "FAILED"',
    '[attr.aria-label]': 'spec().label',
    '(contextmenu)': 'onContextMenu($event)',
  },
})
export class WorkflowNode {
  private readonly graph = inject(GraphStore);
  private readonly runs = inject(RunStore);
  private readonly catalog = inject(CatalogService);
  private readonly probes = inject(NodeProbeService);
  private readonly inspector = inject(InspectorStore);
  private readonly selection = inject(CanvasSelection);
  private readonly viewport = inject(ViewportStore);

  /**
   * Whether the fine-tuning section is open. Per node instance, and not saved.
   *
   * Not part of the document on purpose: which settings someone is looking at right now is not a
   * property of the workflow, and putting it in the file would put it on the undo stack and into
   * everyone else's copy.
   */
  protected readonly advancedOpen = signal(false);

  /** The action whose result is being read, if any. Only one panel is open at a time. */
  protected readonly openResult = signal<string | null>(null);

  readonly node = input.required<WorkflowNodeModel>();
  readonly spec = input.required<NodeSpec>();
  /** True while this node's title is being edited, which the canvas owns. */
  readonly renaming = input(false);

  /** Right-click, reported upward: the canvas owns the menu so it is never clipped by a node. */
  readonly menuRequested = output<{ nodeId: string; x: number; y: number }>();

  /** Double-click on the title, and the end of an edit. Both belong to the canvas's rename state. */
  readonly renameRequested = output<string>();
  readonly renameFinished = output<void>();

  protected readonly status = computed(() => this.runs.nodeStatuses().get(this.node().id));

  /** The user's name for this instance, or the node type's own label. */
  protected readonly title = computed(() => this.node().title || this.spec().label);

  /** Text arriving from the model while the node still runs. */
  protected readonly streamed = computed(() => this.status()?.streamed ?? '');
  protected readonly problems = computed(() => this.graph.issuesByNode().get(this.node().id) ?? []);

  /**
   * On, but fed by something that is off, so a run will skip it.
   *
   * Worth showing distinctly from "switched off": the user did not choose this node, and the fix is
   * upstream. Without it, turning one node off silently changes what half the graph will do.
   */
  protected readonly bypassed = computed(
    () => !this.node().disabled && this.graph.excluded().has(this.node().id),
  );

  /**
   * Every input of this node, as the shared row model sees it, plus what a port needs.
   *
   * The settings half comes from `buildRows`, which the inspector panel calls too — that is what
   * makes a condition, a narrowed list or a wired takeover behave identically in the two places
   * rather than nearly identically. The port half is the canvas's own: a connector id, the type
   * colour, and which shape the row takes here.
   *
   * One `computed` around the whole thing on purpose: the callbacks read the probe signals as they
   * are called, so wrapping the call is what registers those reads.
   */
  private readonly allRows = computed(() => {
    const node = this.node();
    const spec = this.spec();
    const rows = buildRows({
      node,
      spec,
      doc: this.graph.doc(),
      specsById: this.catalog.byId(),
      probeState: (action) => this.probes.state(node.id, action),
      probeResult: (action) => this.probes.result(node.id, action),
      discoveredFor: (inputKey) => this.probes.optionsFor(node.id, inputKey),
    });
    return rows.map((row) => ({ ...row, ...this.portPart(node.id, row) }));
  });

  /**
   * What drawing a row on the canvas needs on top of the setting itself.
   *
   * Neither the condition nor the fold can ever remove a port: both are refused at the descriptor
   * for a connectable input, because a connector that is not laid out loses its geometry and drags
   * its edges to the corner of the node (Foblex FF1006).
   */
  private portPart(nodeId: string, row: SettingRow) {
    const input = row.spec;
    const widget = input.widget;
    return {
      // A number is short enough to sit beside its label, which saves a line on every one of them
      // — and a node of five numbers is five lines shorter for it.
      kind: rowKind(row),
      // A multiline field is tall enough that centring its port looks accidental; it anchors to
      // the first line instead, which is where the eye expects the connection to arrive.
      tall: widget?.kind === 'text' && widget.multiline,
      connectorId: connectorId.input(nodeId, input.key),
      portClass: `port-${portColourKey(input.type)}`,
      typeKey: typeKey(input.type),
      portTitle: `${input.label} · ${describeType(input.type)}${input.required ? '' : ' (optional)'}`,
    };
  }

  /** Everything a run needs to see, and nothing that is folded away or currently inapplicable. */
  protected readonly rows = computed(() => sectionRows(this.allRows(), false));

  /** The fine tuning, behind the fold. Conditions apply here too. */
  protected readonly advancedRows = computed(() => sectionRows(this.allRows(), true));

  /**
   * How many folded settings this node has, and how many are no longer at their default.
   *
   * The second number is the one that matters. A fold that hides a changed setting is a fold that
   * hides the reason a run behaves oddly, so the header says so and the section opens itself.
   */
  protected readonly advancedSummary = computed(() => ({
    total: this.advancedRows().length,
    changed: changedCount(this.advancedRows()),
  }));

  /**
   * What a closed fold says it is hiding: `label: value` for each changed setting.
   *
   * A count tells you that something was changed; this tells you what. It is the difference
   * between a graph you have to open four folds to understand and one that documents itself —
   * which matters most for the graph you saved a month ago.
   */
  protected readonly changedChips = computed(() =>
    this.advancedRows()
      .filter((row) => row.isChanged)
      .map((row) => ({
        key: row.spec.key,
        label: row.spec.label,
        value: chipValue(row),
      })),
  );

  /** True while the inspector panel is showing this node, so the strip can say so. */
  protected readonly inspecting = computed(
    () => this.inspector.open() && this.inspector.nodeId() === this.node().id,
  );

  /**
   * The buttons this node offers, all in the header.
   *
   * One place, whatever a button is about. A test bulb in the header on one node and a magnifier
   * beside a field on the next made the same kind of control look like two different things; and
   * an automatic action has no button at all — the editor runs it when the field it feeds is opened.
   */
  protected readonly headerActions = computed<readonly NodeActionSpec[]>(() =>
    this.spec().actions.filter((action) => !action.automatic),
  );

  protected readonly outputs = computed(() => {
    const reachable = this.catalog.compatibleTargets();
    return this.spec().outputs.map((output) => {
      const key = typeKey(output.type);
      return {
        spec: output,
        connectorId: connectorId.output(this.node().id, output.key),
        portClass: `port-${portColourKey(output.type)}`,
        typeKey: key,
        portTitle: `${output.label} · ${describeType(output.type)}${output.hint ? '\n' + output.hint : ''}`,
        // The flow library refuses a drop onto anything outside this list, so an incompatible port
        // never even lights up while the wire is in the air.
        canConnectTo: [...(reachable.get(key) ?? [])],
      };
    });
  });

  protected readonly duration = computed(() => {
    const millis = this.status()?.durationMillis;
    if (millis == null) {
      return null;
    }
    return millis < 1000 ? `${millis}ms` : `${(millis / 1000).toFixed(2)}s`;
  });

  protected readonly progressPercent = computed(() => Math.round((this.status()?.progress ?? 0) * 100));

  /** The most recent log lines, shown inline the way chaiNNer previews a result. */
  protected readonly preview = computed(() => (this.status()?.logs ?? []).slice(-6));

  /**
   * What a collapsed node says about itself, so collapsing does not hide everything.
   *
   * Counts the ports actually drawn on the collapsed edges — settings have no socket, and a
   * summary that disagrees with the dots beside it is worse than no summary.
   */
  protected readonly summary = computed(() => {
    const inputs = this.spec().inputs.filter((input) => input.connectable).length;
    const outputs = this.spec().outputs.length;
    return `${inputs} in · ${outputs} out`;
  });

  // --- Width ---------------------------------------------------------------

  /**
   * The width being dragged right now, or null.
   *
   * Local to the component and deliberately not in the document: one command per pixel would fill
   * the undo stack with a hundred steps for one gesture. The drag dispatches once, on release —
   * the same bargain the canvas makes for a node being moved.
   */
  protected readonly draftWidth = signal<number | null>(null);

  /** What the host's `--node-width` is set to: the drag, then the document, then the token. */
  protected readonly widthPx = computed(() => this.draftWidth() ?? this.node().width ?? null);

  /** The live readout beside the grip, so the number being chosen is visible while choosing it. */
  protected readonly widthLabel = computed(() => {
    const draft = this.draftWidth();
    return draft === null ? '' : `${draft} px`;
  });

  /**
   * Widths worth landing on: the default, and whatever the other nodes in this document are.
   *
   * A graph where three nodes are 320 and one is 318 looks like a mistake nobody made. Reading the
   * document rather than a remembered list means the targets are always the widths actually on
   * screen, including ones that arrived with an opened file.
   */
  private snapTargets(): readonly number[] {
    const mine = this.node().id;
    const targets = new Set<number>([DEFAULT_NODE_WIDTH]);
    for (const node of this.graph.doc().nodes) {
      if (node.id !== mine && node.width !== undefined) {
        targets.add(node.width);
      }
    }
    return [...targets];
  }

  /**
   * Drags the node wider or narrower.
   *
   * Hand-rolled rather than `fResizeHandle`: that directive writes a fixed height as well as a
   * width, which freezes a node whose body grows with its content — and it cannot express
   * width-only. The pointer is captured so a drag that leaves the node, or the window, keeps
   * arriving here, and the delta is divided by the canvas scale because the pointer moves in screen
   * pixels while the width is in canvas pixels.
   *
   * The grip carries `fDragBlocker`, which makes the flow library ignore the press entirely — so
   * the selection is made here, or resizing a node would leave the canvas selecting something else.
   */
  protected startResize(event: PointerEvent): void {
    event.preventDefault();
    event.stopPropagation();
    const targets = this.resizeTargets();
    const handle = event.currentTarget as HTMLElement;
    handle.setPointerCapture(event.pointerId);

    const startWidth = this.node().width ?? DEFAULT_NODE_WIDTH;
    const originX = event.clientX;
    const snaps = this.snapTargets();

    const move = (next: Event) => {
      const pointer = next as PointerEvent;
      const raw = startWidth + (pointer.clientX - originX) / this.viewport.scale();
      this.draftWidth.set(settleWidth(raw, snaps));
    };
    const stop = () => {
      handle.releasePointerCapture(event.pointerId);
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', stop);
      handle.removeEventListener('pointercancel', stop);
      const chosen = this.draftWidth();
      this.draftWidth.set(null);
      if (chosen !== null) {
        // One command for the whole selection: four nodes dragged to one width is one undo.
        this.graph.dispatch(commands.resizeNodes(targets, chosen));
      }
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', stop);
    handle.addEventListener('pointercancel', stop);
  }

  /** Double-click on the grip: back to the default width, for this node or the whole selection. */
  protected resetWidth(event: Event): void {
    event.stopPropagation();
    this.graph.dispatch(commands.resizeNodes(this.resizeTargets(), undefined));
  }

  /**
   * Which nodes a width gesture applies to.
   *
   * The selection when this node is in it, so lining up four nodes is one drag; this node alone
   * otherwise — and it is selected on the way, because acting on a node the canvas does not
   * consider selected is how the next Delete removes something else. Through `CanvasSelection`, so
   * the border moves with it: the grip carries `fDragBlocker`, so the flow library never saw the
   * press and would otherwise keep drawing the selection somewhere else.
   */
  private resizeTargets(): readonly string[] {
    const selection = this.graph.selection();
    if (selection.has(this.node().id)) {
      return [...selection];
    }
    this.selection.select([this.node().id]);
    return [this.node().id];
  }

  protected setValue(key: string, value: unknown): void {
    this.graph.dispatch(commands.setInputValue(this.node().id, key, value));
  }

  protected toggleAdvanced(): void {
    this.advancedOpen.update((open) => !open);
  }

  /**
   * Opens the settings panel on this node, from the Advanced strip.
   *
   * Selects it too, through `CanvasSelection` so that both owners of the selection agree.
   * `.advanced__head` carries `fDragBlocker`, which makes the flow library ignore the press
   * entirely — so a store-only selection here would open the panel on one node while the canvas
   * went on drawing the border around another, and the next Delete would remove the unhighlighted
   * one.
   */
  protected openInspector(): void {
    this.selection.select([this.node().id]);
    this.inspector.show(this.node().id);
  }

  // --- Actions -------------------------------------------------------------

  protected runAction(action: NodeActionSpec, event: Event): void {
    event.stopPropagation();
    this.probes.run(this.node(), this.spec(), action.key);
    this.openResult.set(action.key);
  }

  /**
   * A field's list was opened: run whatever discovery feeds it, unless the answer is still good.
   *
   * Silent by design. The result lands in the list itself — as options, or as a line saying why
   * there are none — rather than as a panel under the node, because nobody pressed anything.
   */
  protected onWidgetOpened(automatic: readonly NodeActionSpec[]): void {
    for (const action of automatic) {
      this.probes.ensure(this.node(), this.spec(), action.key);
    }
  }

  protected actionState(action: NodeActionSpec): string {
    return this.probes.state(this.node().id, action.key);
  }

  protected actionResult(action: NodeActionSpec) {
    return this.probes.result(this.node().id, action.key);
  }

  /** The tooltip: what the button does, or what it last answered. */
  protected actionTitle(action: NodeActionSpec): string {
    const result = this.actionResult(action);
    return result ? `${action.label}\n${result.message}` : action.label;
  }

  protected toggleResult(action: NodeActionSpec, event: Event): void {
    event.stopPropagation();
    this.openResult.update((open) => (open === action.key ? null : action.key));
  }

  protected closeResult(): void {
    this.openResult.set(null);
  }

  /** The result panel to show, if any — the one whose action was last pressed or clicked. */
  protected readonly shownResult = computed(() => {
    const key = this.openResult();
    if (!key) {
      return null;
    }
    const action = this.spec().actions.find((candidate) => candidate.key === key);
    const result = this.probes.result(this.node().id, key);
    return action && result ? { action, result } : null;
  });

  protected startRename(event: Event): void {
    event.stopPropagation();
    this.renameRequested.emit(this.node().id);
  }

  /**
   * Commits the typed name.
   *
   * Dispatched only when it actually changed: a blur with nothing edited would otherwise put an
   * empty step onto the undo stack for every node the user merely clicked on.
   */
  protected commitRename(event: Event): void {
    const typed = (event.target as HTMLInputElement).value.trim();
    if (typed !== this.node().title) {
      this.graph.dispatch(commands.renameNode(this.node().id, typed));
    }
    this.renameFinished.emit();
  }

  protected onRenameKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      (event.target as HTMLInputElement).blur();
    }
    if (event.key === 'Escape') {
      // Escape abandons the edit, so the field is reset before the blur that commits it.
      (event.target as HTMLInputElement).value = this.node().title;
      (event.target as HTMLInputElement).blur();
    }
  }

  protected toggleCollapsed(): void {
    this.graph.dispatch(commands.toggleCollapsed(this.node().id));
  }

  protected toggleDisabled(): void {
    this.graph.dispatch(commands.toggleDisabled(this.node().id));
  }

  protected onContextMenu(event: MouseEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.menuRequested.emit({ nodeId: this.node().id, x: event.clientX, y: event.clientY });
  }
}

/**
 * Which shape a row takes in the node body.
 *
 * Only two rows in a node are anything other than "label above a control": a socket, which is a
 * label reading out of its port, and a toggle, which carries its own label. The third is new — a
 * number sits beside its label, because a spinner is 60px wide and the line above it was empty.
 */
function rowKind(row: SettingRow): RowKind {
  const widget = row.spec.widget;
  if (!widget) {
    return 'socket';
  }
  if (widget.kind === 'toggle') {
    return 'toggle';
  }
  // A readout draws its own label, because a fact is a two-column line — name left, value right —
  // rather than a control under a caption.
  if (widget.kind === 'display') {
    return 'display';
  }
  return widget.kind === 'number' ? 'inline' : 'field';
}

/**
 * The width a drag has arrived at: a snap target if one is close, else the next step.
 *
 * Snapping before rounding rather than after, so a target that is not itself a multiple of the step
 * — the default 252 is not — can still be landed on exactly.
 */
function settleWidth(raw: number, snaps: readonly number[]): number {
  const nearest = snaps.find((target) => Math.abs(raw - target) <= SNAP_RANGE);
  const settled = nearest ?? Math.round(raw / NODE_WIDTH_STEP) * NODE_WIDTH_STEP;
  return Math.min(Math.max(settled, MIN_NODE_WIDTH), MAX_NODE_WIDTH);
}

/** A changed setting's value, short enough to sit in a chip. */
function chipValue(row: SettingRow): string {
  const raw = row.value;
  const text =
    raw === null || raw === undefined
      ? '—'
      : typeof raw === 'string'
        ? raw
        : typeof raw === 'boolean'
          ? raw
            ? 'on'
            : 'off'
          : typeof raw === 'number'
            ? String(raw)
            : Array.isArray(raw)
              ? `${raw.length} chosen`
              : serialise(raw);
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length > CHIP_VALUE_LIMIT ? `${flat.slice(0, CHIP_VALUE_LIMIT - 1)}…` : flat;
}
