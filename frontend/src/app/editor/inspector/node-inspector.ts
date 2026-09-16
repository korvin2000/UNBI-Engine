import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { NodeActionSpec, NodeSpec, WidgetSpec } from '../../core/catalog/catalog.models';
import { CatalogService } from '../../core/catalog/catalog.service';
import {
  RowGroup,
  SettingRow,
  buildRows,
  changedCount,
  groupRows,
  isReadout,
  sectionRows,
} from '../../core/catalog/node-rows';
import { NodeProbeService } from '../../core/catalog/node-probe.service';
import * as commands from '../../core/graph/commands';
import { GraphStore } from '../../core/graph/graph-store';
import {
  DEFAULT_NODE_WIDTH,
  MAX_NODE_WIDTH,
  MIN_NODE_WIDTH,
  NODE_WIDTH_STEP,
  Point,
  WorkflowNode,
} from '../../core/graph/workflow.models';
import { portColourKey } from '../../core/types/port-type';
import { Icon } from '../../shared/icon';
import { NodeWidget } from '../../widgets/node-widget';
import { InspectorStore, clampWidth } from './inspector-store';

/** One row as the panel draws it: the shared setting, plus the panel's own layout answer. */
interface InspectorRow {
  readonly setting: SettingRow;
  /** True for a control that needs the full width, so its label goes above it. */
  readonly tall: boolean;
  /**
   * True for a readout, which draws its own label and can never be reset.
   *
   * The panel suppresses its own label row for these: the widget renders a fact as one two-column
   * line — name left, value right — and a caption above that line would say the name twice. It is
   * also what keeps a readout looking identical in the panel and in the node.
   */
  readonly readout: boolean;
  /**
   * True for a toggle, whose control is a 15px box.
   *
   * It gives the rest of the row back to the label: a fixed control column that wide beside a
   * checkbox is 130 pixels of nothing, paid for by truncating the half that carries the meaning.
   */
  readonly switchRow: boolean;
  /** The port type's colour class, so the "wired" badge matches the edge that drives the row. */
  readonly colourClass: string;
}

/** A sub-section inside a section: the rows of one declared `group`, collapsible. */
interface InspectorGroup {
  readonly name: string;
  readonly open: boolean;
  readonly rows: readonly InspectorRow[];
}

/** One of the two sections the panel shows, after the filter has been applied. */
interface InspectorSection {
  readonly id: 'advanced' | 'settings';
  readonly label: string;
  readonly open: boolean;
  /** How many rows the section holds, before filtering. */
  readonly total: number;
  readonly changed: number;
  /** How many the filter is hiding, so a filtered section never lies by omission. */
  readonly hidden: number;
  readonly groups: readonly InspectorGroup[];
}

/** Past this many editable rows the panel is a list to search rather than a form to read. */
const FILTER_FROM = 10;

/** How far a freshly undocked card sits from the edge it detached from. */
const FLOAT_INSET = 16;

/** Kinds that need the full width of the panel; everything else fits beside its label. */
const TALL_KINDS: readonly WidgetSpec['kind'][] = [
  'keyvalue',
  'multiselect',
  'filelist',
  'directory',
  'file',
  'display',
  'unsupported',
];

/**
 * One node's settings, edited beside the canvas instead of inside it.
 *
 * The node stays the summary: what it is, what is wired into it, what it is doing. The panel is
 * where the twenty-odd knobs of a generation request are actually workable — full width for a
 * prompt, a label column that lines up, a filter when there are more rows than anyone reads.
 *
 * It is not a second implementation of a node body. Both call {@link buildRows}, so a condition, a
 * narrowed list, a discovered model list and a "wired" takeover behave identically in the two
 * places — the panel adds a layout and a filter, and nothing else.
 *
 * Styled as a node on purpose: same accent header over the same 2px rule, same sunken body, same
 * footer. A panel in its own visual language would read as a different application.
 */
@Component({
  selector: 'app-node-inspector',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, NodeWidget],
  templateUrl: './node-inspector.html',
  styleUrl: './node-inspector.scss',
  host: {
    '[class]': 'accentClass()',
    '[class.is-open]': 'view() !== null',
    '[class.is-docked]': 'docked()',
    '[class.is-floating]': '!docked()',
    '[class.is-sizing]': 'sizing()',
    '[style.--inspector-width.px]': 'width()',
    '[style.--inspector-x.px]': 'floating().x',
    '[style.--inspector-y.px]': 'floating().y',
    '(keydown.escape)': 'onEscape($event)',
    '(window:resize)': 'clampIntoWorkspace()',
  },
})
export class NodeInspector {
  private readonly store = inject(InspectorStore);
  private readonly graph = inject(GraphStore);
  private readonly catalog = inject(CatalogService);
  private readonly probes = inject(NodeProbeService);
  private readonly host = inject(ElementRef<HTMLElement>);

  protected readonly docked = this.store.docked;
  protected readonly pinned = this.store.pinned;
  protected readonly width = this.store.width;
  protected readonly floating = this.store.floating;
  protected readonly selectionCount = this.store.selectionCount;

  /** True while a drag or a resize is in progress, which suspends the panel's transitions. */
  protected readonly sizing = signal(false);

  protected readonly query = signal('');
  protected readonly modifiedOnly = signal(false);

  /** The node being shown, with its descriptor, or null when the panel has nothing to draw. */
  protected readonly view = computed<{ node: WorkflowNode; spec: NodeSpec } | null>(() => {
    if (!this.store.open()) {
      return null;
    }
    const id = this.store.nodeId();
    const node = id === null ? undefined : this.graph.doc().nodes.find((one) => one.id === id);
    const spec = node ? this.catalog.byId().get(node.type) : undefined;
    return node && spec ? { node, spec } : null;
  });

  protected readonly accentClass = computed(() => `accent-${this.view()?.spec.accent ?? 'slate'}`);

  /** The user's name for this instance, or the node type's own label. */
  protected readonly title = computed(() => {
    const view = this.view();
    return view ? view.node.title || view.spec.label : '';
  });

  /**
   * Every row of this node, resolved once.
   *
   * One `computed` around one `buildRows` call: the context's callbacks read the probe signals as
   * they are called, so wrapping the whole call is what registers those reads — and it means the
   * panel recomputes on exactly the same inputs the node does.
   */
  private readonly rows = computed<readonly SettingRow[]>(() => {
    const view = this.view();
    if (!view) {
      return [];
    }
    const { node, spec } = view;
    return buildRows({
      node,
      spec,
      doc: this.graph.doc(),
      specsById: this.catalog.byId(),
      probeState: (action) => this.probes.state(node.id, action),
      probeResult: (action) => this.probes.result(node.id, action),
      discoveredFor: (inputKey) => this.probes.optionsFor(node.id, inputKey),
    });
  });

  /**
   * Everything the panel draws: applicable rows that are either a control or a readout.
   *
   * A socket is the only kind left out — it has nothing to show here and its label is already on
   * the node, beside the port it belongs to. Readouts are in, because an info node is *all*
   * readouts, and a panel that showed nothing for one would be a panel that cannot open the node
   * with the most rows in the pack.
   *
   * A control this build cannot draw is in as well, although it is not editable: the node shows the
   * "unsupported control" placeholder for it, and a panel that silently dropped the row would
   * disagree with the node about what the node has — which reads as the panel losing settings.
   */
  private readonly shown = computed(() =>
    this.rows().filter(
      (row) =>
        row.applies &&
        (row.isEditable || isReadout(row.spec) || row.spec.widget?.kind === 'unsupported'),
    ),
  );

  /** The rows a reset can touch: controls only, never a socket and never a readout. */
  private readonly editable = computed(() => this.rows().filter((row) => row.applies && row.isEditable));

  protected readonly changed = computed(() => changedCount(this.editable()));

  /** Whether this node has any settings at all, as opposed to readouts and sockets. */
  protected readonly hasSettings = computed(() => this.editable().length > 0);

  /** Whether the header grows a filter: a form this long is searched, not read. */
  protected readonly filterable = computed(() => this.shown().length > FILTER_FROM);

  /**
   * Whether a filter is in force, which is also what keeps the control that clears it on screen.
   *
   * The header normally appears past {@link FILTER_FROM} rows, so a filter left over from a long
   * node would otherwise be applied to a short one with no visible way to undo it.
   */
  protected readonly filtering = computed(
    () => this.query().trim().length > 0 || this.modifiedOnly(),
  );

  protected readonly sections = computed<readonly InspectorSection[]>(() => {
    const view = this.view();
    if (!view) {
      return [];
    }
    // Advanced first and open, which inverts the node: someone who came to the panel came for the
    // fine tuning, and the settings already on the node are the ones they can see.
    const advanced = this.section(view.spec, 'advanced', 'Advanced', true);
    // Unless there is no fine tuning at all — then the ordinary settings are the whole panel, and
    // opening on a single collapsed strip would look like a panel with nothing in it.
    const settings = this.section(view.spec, 'settings', 'Settings', advanced.total === 0);
    return [advanced, settings].filter((section) => section.total > 0);
  });

  /** True when this node has neither a setting nor a readout to show. */
  protected readonly isEmpty = computed(() => this.view() !== null && this.shown().length === 0);

  private section(
    spec: NodeSpec,
    id: 'advanced' | 'settings',
    label: string,
    openByDefault: boolean,
  ): InspectorSection {
    const all = sectionRows(this.shown(), id === 'advanced');
    const needle = this.query().trim().toLowerCase();
    const matching = all.filter(
      (row) =>
        (!needle || `${row.spec.label} ${row.spec.key}`.toLowerCase().includes(needle)) &&
        // "Modified only" is about settings, and a readout is never modified — so it hides them,
        // which is right: the question that filter asks is "what did somebody change here".
        (!this.modifiedOnly() || row.isChanged),
    );
    return {
      id,
      label,
      // A filter that left a section closed would look broken, so filtering opens what it matches.
      open: this.filtering() || this.store.isSectionOpen(spec.id, id, openByDefault),
      total: all.length,
      changed: changedCount(all),
      hidden: all.length - matching.length,
      groups: groupRows(matching).map((group) => this.group(spec, id, group)),
    };
  }

  private group(spec: NodeSpec, sectionId: string, group: RowGroup): InspectorGroup {
    return {
      name: group.name,
      open: this.filtering() || this.store.isSectionOpen(spec.id, `${sectionId}/${group.name}`, true),
      rows: group.rows.map((row) => ({
        setting: row,
        tall: isTall(row),
        readout: isReadout(row.spec),
        switchRow: row.spec.widget?.kind === 'toggle',
        colourClass: `port-${portColourKey(row.spec.type)}`,
      })),
    };
  }

  // --- Editing -------------------------------------------------------------

  protected setValue(row: InspectorRow, value: unknown): void {
    const view = this.view();
    if (view) {
      this.graph.dispatch(commands.setInputValue(view.node.id, row.setting.spec.key, value));
    }
  }

  /** Puts one setting back to what the descriptor declared. Undoable, like any other edit. */
  protected reset(row: InspectorRow): void {
    const view = this.view();
    if (view) {
      this.graph.dispatch(
        commands.setInputValue(view.node.id, row.setting.spec.key, row.setting.spec.defaultValue),
      );
    }
  }

  /**
   * Puts every changed setting back, in one step.
   *
   * One command rather than a loop, so undo takes one press — which is the only reason a button
   * that discards someone's work is defensible. Non-editable rows are never touched: a socket has
   * nothing to reset, and a readout is not ours to write.
   */
  protected resetAll(): void {
    const view = this.view();
    if (!view) {
      return;
    }
    const defaults: Record<string, unknown> = {};
    for (const row of this.editable()) {
      if (row.isChanged) {
        defaults[row.spec.key] = row.spec.defaultValue;
      }
    }
    if (Object.keys(defaults).length > 0) {
      this.graph.dispatch(commands.setInputValues(view.node.id, defaults));
    }
  }

  // --- The node's own width ------------------------------------------------
  //
  // The accessible route to a width. The grip in the node's footer is a pointer drag, which a
  // keyboard cannot perform at all — so the same setting is a number field here, where it can be
  // typed, stepped with the arrow keys and read out by a screen reader.

  protected readonly MIN_WIDTH = MIN_NODE_WIDTH;
  protected readonly MAX_WIDTH = MAX_NODE_WIDTH;
  protected readonly WIDTH_STEP = NODE_WIDTH_STEP;

  /** What the field shows: this node's width, or the default it would be drawn at. */
  protected readonly nodeWidth = computed(() => this.view()?.node.width ?? DEFAULT_NODE_WIDTH);

  /** Whether this node has a width of its own, which is the only case Reset applies to. */
  protected readonly hasWidth = computed(() => this.view()?.node.width !== undefined);

  /**
   * Commits a typed width.
   *
   * On `change` rather than `input`: typing "3", "31", "312" would otherwise be three undoable
   * steps, two of them at the clamped minimum. A value that is not a number at all — an emptied
   * field, or one the control could not parse — is no change, and the width it shows is put back:
   * `Number('')` is 0, which would resize the node to the clamped minimum, and the `[value]`
   * binding cannot redraw a field whose expression never changed.
   */
  protected onWidth(event: Event): void {
    const input = event.target as HTMLInputElement;
    const view = this.view();
    const raw = input.value.trim();
    const typed = raw === '' ? Number.NaN : Number(raw);
    if (!view || !Number.isFinite(typed)) {
      input.value = String(this.nodeWidth());
      return;
    }
    this.graph.dispatch(commands.resizeNodes([view.node.id], typed));
  }

  /** Back to the default width, which is the absence of a width rather than the number 252. */
  protected resetWidth(): void {
    const view = this.view();
    if (view) {
      this.graph.dispatch(commands.resizeNodes([view.node.id], undefined));
    }
  }

  /** A list was opened: run whatever discovery feeds it, exactly as the node does. */
  protected onOpened(automatic: readonly NodeActionSpec[]): void {
    const view = this.view();
    if (!view) {
      return;
    }
    for (const action of automatic) {
      this.probes.ensure(view.node, view.spec, action.key);
    }
  }

  // --- The panel itself ----------------------------------------------------

  protected toggleSection(section: InspectorSection): void {
    const spec = this.view()?.spec;
    if (spec) {
      this.store.toggleSection(spec.id, section.id, section.id === 'advanced');
    }
  }

  protected toggleGroup(section: InspectorSection, group: InspectorGroup): void {
    const spec = this.view()?.spec;
    if (spec) {
      this.store.toggleSection(spec.id, `${section.id}/${group.name}`, true);
    }
  }

  protected togglePinned(): void {
    this.store.togglePinned();
  }

  /**
   * Docks or floats the panel.
   *
   * Undocking places the card where the column already was, so it reads as the panel detaching
   * rather than as a second panel appearing somewhere else — and it keeps it clear of the palette,
   * which a remembered position from a much wider window would not. `afterRenderEffect` re-clamps
   * once the floating card has been measured.
   */
  protected toggleDocked(): void {
    if (this.docked()) {
      const workspace = this.host.nativeElement.parentElement?.getBoundingClientRect();
      const panel = this.host.nativeElement.getBoundingClientRect();
      if (workspace) {
        this.store.setFloating({ x: panel.left - workspace.left - FLOAT_INSET, y: FLOAT_INSET });
      }
    }
    this.store.toggleDocked();
  }

  protected close(): void {
    this.store.close();
  }

  protected onQuery(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }

  protected toggleModifiedOnly(): void {
    this.modifiedOnly.update((only) => !only);
  }

  /**
   * Escape closes the innermost open thing.
   *
   * A widget whose list was open swallows the key, so an Escape that arrives here is one with
   * nothing smaller left to close. That handshake is the whole mechanism: asking the DOM whether a
   * list is open instead would read the *previous* frame — this app is zoneless, so the panel a
   * widget just closed is still in the document when this handler runs, and two quick presses
   * would leave the panel open.
   *
   * A pinned panel ignores the key entirely: pinning it is a statement that it should stay.
   */
  protected onEscape(event: Event): void {
    if (this.pinned()) {
      return;
    }
    this.close();
    event.stopPropagation();
  }

  constructor() {
    // The filter belongs to the node that was being searched, not to the panel. Kept across a
    // switch it would go on filtering the next node — and on a node with few enough rows to lose
    // the filter header, with no control left on screen to clear it.
    //
    // Keyed on the shown id rather than on `view()`, which is a fresh object on every graph edit:
    // an effect on that would wipe the query on each keystroke the user made in a filtered row.
    effect(() => {
      this.store.nodeId();
      untracked(() => {
        this.query.set('');
        this.modifiedOnly.set(false);
      });
    });

    // A position saved in a larger window, or a window that has since been resized, would put the
    // panel's header off screen — where it cannot be dragged back. Re-clamped once it is measured.
    afterRenderEffect(() => {
      if (this.view() === null || this.docked()) {
        return;
      }
      this.clampIntoWorkspace();
    });
  }

  // --- Dragging and resizing ----------------------------------------------

  /** The panel is dragged by its header, and only while floating. */
  protected startDrag(event: PointerEvent): void {
    if (this.docked() || (event.target as HTMLElement).closest('button')) {
      return;
    }
    const start = this.floating();
    const originX = event.clientX;
    const originY = event.clientY;
    this.trackPointer(event, (move) => {
      this.store.setFloating(
        this.clamp({
          x: start.x + (move.clientX - originX),
          y: start.y + (move.clientY - originY),
        }),
      );
    });
  }

  /**
   * The resize handle: on the left edge when docked, on the right edge when floating.
   *
   * Two edges rather than one, because in both cases the handle is the edge that faces the canvas —
   * dragging it makes the panel wider in the direction the user is pulling.
   */
  protected startResize(event: PointerEvent, edge: 'left' | 'right'): void {
    const startWidth = this.width();
    const originX = event.clientX;
    this.trackPointer(event, (move) => {
      const delta = edge === 'left' ? originX - move.clientX : move.clientX - originX;
      this.store.setWidth(clampWidth(startWidth + delta));
    });
  }

  /**
   * Follows one pointer until it is released.
   *
   * `setPointerCapture` rather than document listeners: with the pointer captured, a drag that
   * leaves the panel — over the canvas, or out of the window — keeps arriving here, and no other
   * element sees the moves as hovers.
   */
  private trackPointer(event: PointerEvent, onMove: (move: PointerEvent) => void): void {
    const handle = event.currentTarget as HTMLElement;
    event.preventDefault();
    handle.setPointerCapture(event.pointerId);
    this.sizing.set(true);
    const move = (next: Event) => onMove(next as PointerEvent);
    const stop = () => {
      handle.releasePointerCapture(event.pointerId);
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', stop);
      handle.removeEventListener('pointercancel', stop);
      this.sizing.set(false);
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', stop);
    handle.addEventListener('pointercancel', stop);
  }

  /** Keeps a floating panel inside the workspace, header first. */
  protected clampIntoWorkspace(): void {
    if (this.docked() || this.view() === null) {
      return;
    }
    const clamped = this.clamp(this.floating());
    const current = this.floating();
    if (clamped.x !== current.x || clamped.y !== current.y) {
      this.store.setFloating(clamped);
    }
  }

  private clamp(position: Point): Point {
    const workspace = this.host.nativeElement.parentElement?.getBoundingClientRect();
    const panel = this.host.nativeElement.getBoundingClientRect();
    if (!workspace || workspace.width === 0) {
      return position;
    }
    const maxX = Math.max(0, workspace.width - panel.width);
    const maxY = Math.max(0, workspace.height - panel.height);
    return {
      x: Math.round(Math.min(Math.max(position.x, 0), maxX)),
      y: Math.round(Math.min(Math.max(position.y, 0), maxY)),
    };
  }
}

/**
 * Whether a control gets its own line.
 *
 * A prompt, a key/value map or a row of capability chips inside a 44% column is a control nobody
 * can use; a toggle or a number spinner above its own label wastes two lines saying one thing. The
 * kind decides, which is why this is a list and not a heuristic about widths.
 */
function isTall(row: SettingRow): boolean {
  const widget = row.spec.widget;
  if (!widget) {
    return false;
  }
  if (widget.kind === 'text') {
    return widget.multiline;
  }
  return TALL_KINDS.includes(widget.kind);
}
