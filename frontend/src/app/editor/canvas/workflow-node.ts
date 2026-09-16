import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { FFlowModule } from '@foblex/flow';
import { CatalogService } from '../../core/catalog/catalog.service';
import { NodeActionSpec, NodeInputSpec, NodeSpec } from '../../core/catalog/catalog.models';
import { NodeProbeService } from '../../core/catalog/node-probe.service';
import * as commands from '../../core/graph/commands';
import { GraphStore } from '../../core/graph/graph-store';
import { WorkflowNode as WorkflowNodeModel, connectorId, isConnected } from '../../core/graph/workflow.models';
import { RunStore } from '../../core/runtime/run-store';
import { describeType, portColourKey, typeKey } from '../../core/types/port-type';
import { Icon } from '../../shared/icon';
import { NodeWidget } from '../../widgets/node-widget';

/** Which shape a row takes in the body, decided once rather than re-derived in three templates. */
type RowKind = 'socket' | 'toggle' | 'field';

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
   * Every input that should be drawn right now, in declaration order.
   *
   * Two things are filtered out here, and both are about a node saying only what currently applies.
   * A setting whose condition is false cannot affect anything — a JSON schema box above a response
   * format of "Text" is a question with no right answer — and a setting the author marked as fine
   * tuning belongs in the fold below rather than between the two fields somebody actually came for.
   *
   * Neither filter can ever remove a port: both are refused at the descriptor for a connectable
   * input, because a connector that is not laid out loses its geometry and drags its edges to the
   * corner of the node (Foblex FF1006).
   */
  private readonly allRows = computed(() =>
    this.spec().inputs.map((input) => {
      const wired = isConnected(this.graph.doc(), this.node().id, input.key);
      const widget = input.widget;
      const kind: RowKind = !widget ? 'socket' : widget.kind === 'toggle' ? 'toggle' : 'field';
      // Discovery the editor runs on its own for this field, the moment its list is opened.
      const automatic = this.spec().actions.filter(
        (action) => action.automatic && action.appliesTo === input.key,
      );
      return {
        spec: input,
        kind,
        wired,
        // A multiline field is tall enough that centring its port looks accidental; it anchors to
        // the first line instead, which is where the eye expects the connection to arrive.
        tall: widget?.kind === 'text' && widget.multiline,
        connectorId: connectorId.input(this.node().id, input.key),
        portClass: `port-${portColourKey(input.type)}`,
        typeKey: typeKey(input.type),
        portTitle: `${input.label} · ${describeType(input.type)}${input.required ? '' : ' (optional)'}`,
        value: this.valueOf(input),
        // A wired input keeps its widget on screen but disabled, so the graph does not visibly
        // change shape when an edge is attached or removed.
        widgetDisabled: wired,
        // Choices this node's own probes discovered for this input, and the ones an upstream node
        // says are available. Both are per node instance, which is why they are resolved here.
        discovered: this.probes.optionsFor(this.node().id, input.key),
        discoveryNote: this.discoveryNote(automatic),
        allowed: this.allowedFor(input),
        automatic,
      };
    }),
  );

  /** Everything a run needs to see, and nothing that is folded away or currently inapplicable. */
  protected readonly rows = computed(() =>
    this.allRows().filter((row) => !row.spec.advanced && this.applies(row.spec)),
  );

  /** The fine tuning, behind the fold. Conditions apply here too. */
  protected readonly advancedRows = computed(() =>
    this.allRows().filter((row) => row.spec.advanced && this.applies(row.spec)),
  );

  /**
   * How many folded settings this node has, and how many are no longer at their default.
   *
   * The second number is the one that matters. A fold that hides a changed setting is a fold that
   * hides the reason a run behaves oddly, so the header says so and the section opens itself.
   */
  protected readonly advancedSummary = computed(() => {
    const rows = this.advancedRows();
    const changed = rows.filter((row) => {
      const stored = this.node().values[row.spec.key];
      return stored !== undefined && serialise(stored) !== serialise(row.spec.defaultValue);
    }).length;
    return { total: rows.length, changed };
  });

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

  protected setValue(key: string, value: unknown): void {
    this.graph.dispatch(commands.setInputValue(this.node().id, key, value));
  }

  protected toggleAdvanced(): void {
    this.advancedOpen.update((open) => !open);
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

  /** What an empty list should say while, or after, its automatic discovery ran. */
  private discoveryNote(automatic: readonly NodeActionSpec[]): string {
    for (const action of automatic) {
      const state = this.probes.state(this.node().id, action.key);
      if (state === 'running') {
        return 'Asking the endpoint…';
      }
      if (state === 'failed') {
        return this.probes.result(this.node().id, action.key)?.message ?? 'Could not fetch the list.';
      }
      if (state === 'ok' && this.probes.optionsFor(this.node().id, action.appliesTo).length === 0) {
        return this.probes.result(this.node().id, action.key)?.message ?? 'The endpoint listed nothing.';
      }
    }
    return '';
  }

  // --- Conditions and narrowing -------------------------------------------

  /** True when a setting's declared condition holds, or it has none. */
  private applies(input: NodeInputSpec): boolean {
    const condition = input.showWhen;
    if (!condition) {
      return true;
    }
    const sibling = this.spec().inputs.find((candidate) => candidate.key === condition.key);
    const value = sibling ? this.valueOf(sibling) : undefined;
    // Compared as text: a toggle stores a boolean and a dropdown a string, and a condition should
    // not have to know which kind of control it is reading.
    return condition.values.includes(String(value));
  }

  /**
   * Which option values the node wired into this input's narrowing socket says are available.
   *
   * Null when nothing is wired in, and that is not the same as an empty set: nothing wired in means
   * nobody knows, which is no reason to hide a choice, while an empty list is an upstream node
   * declaring that it supports none of them.
   */
  private allowedFor(input: NodeInputSpec): ReadonlySet<string> | null {
    const widget = input.widget;
    if (!widget || widget.kind !== 'dropdown' || !widget.narrowing) {
      return null;
    }
    const { socket, listKey } = widget.narrowing;
    const doc = this.graph.doc();
    const edge = doc.edges.find(
      (candidate) => candidate.targetNode === this.node().id && candidate.targetPort === socket,
    );
    const upstream = edge ? doc.nodes.find((candidate) => candidate.id === edge.sourceNode) : undefined;
    const upstreamSpec = upstream ? this.catalog.byId().get(upstream.type) : undefined;
    if (!upstream || !upstreamSpec) {
      return null;
    }
    const declared = upstreamSpec.inputs.find((candidate) => candidate.key === listKey);
    const raw = upstream.values[listKey] ?? declared?.defaultValue ?? null;
    if (Array.isArray(raw)) {
      return new Set(raw.map((entry) => String(entry)));
    }
    if (typeof raw === 'string' && raw.trim()) {
      return new Set(raw.split(',').map((part) => part.trim()).filter(Boolean));
    }
    return new Set<string>();
  }

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

  private valueOf(input: NodeInputSpec): unknown {
    const provided = this.node().values[input.key];
    return provided === undefined ? input.defaultValue : provided;
  }
}

/** A comparable form of a widget value, for telling a changed setting from an untouched one. */
function serialise(value: unknown): string {
  try {
    return JSON.stringify(value ?? null);
  } catch {
    return String(value);
  }
}
