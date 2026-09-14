import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { FFlowModule } from '@foblex/flow';
import { CatalogService } from '../../core/catalog/catalog.service';
import { NodeInputSpec, NodeSpec } from '../../core/catalog/catalog.models';
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
  imports: [FFlowModule, Icon, NodeWidget],
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

  readonly node = input.required<WorkflowNodeModel>();
  readonly spec = input.required<NodeSpec>();

  /** Right-click, reported upward: the canvas owns the menu so it is never clipped by a node. */
  readonly menuRequested = output<{ nodeId: string; x: number; y: number }>();

  protected readonly status = computed(() => this.runs.nodeStatuses().get(this.node().id));
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

  /** Inputs that render in the body: sockets always, widgets when nothing is wired into them. */
  protected readonly rows = computed(() =>
    this.spec().inputs.map((input) => {
      const wired = isConnected(this.graph.doc(), this.node().id, input.key);
      const widget = input.widget;
      const kind: RowKind = !widget ? 'socket' : widget.kind === 'toggle' ? 'toggle' : 'field';
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
      };
    }),
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
        portTitle: `${output.label} · ${describeType(output.type)}`,
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
