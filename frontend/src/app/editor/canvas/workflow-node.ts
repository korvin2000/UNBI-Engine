import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { FFlowModule } from '@foblex/flow';
import { NodeInputSpec, NodeSpec } from '../../core/catalog/catalog.models';
import * as commands from '../../core/graph/commands';
import { GraphStore } from '../../core/graph/graph-store';
import { WorkflowNode as WorkflowNodeModel, connectorId, isConnected } from '../../core/graph/workflow.models';
import { RunStore } from '../../core/runtime/run-store';
import { portColourKey } from '../../core/types/port-type';
import { Icon } from '../../shared/icon';
import { NodeWidget } from '../../widgets/node-widget';

/**
 * One node on the canvas.
 *
 * The node is a small form, not a labelled box: a tinted header carrying the category accent, a
 * body of sockets and widgets, and a footer with run state and timing. That shape is what makes a
 * chaiNNer graph readable, and it is why the template model was the right choice of flow library.
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
    '[class.has-problem]': 'problems().length > 0',
    '[class.is-running]': 'status()?.state === "RUNNING"',
    '[class.is-failed]': 'status()?.state === "FAILED"',
    '[class.is-complete]': 'status()?.state === "COMPLETED"',
  },
})
export class WorkflowNode {
  private readonly graph = inject(GraphStore);
  private readonly runs = inject(RunStore);

  readonly node = input.required<WorkflowNodeModel>();
  readonly spec = input.required<NodeSpec>();

  protected readonly status = computed(() => this.runs.nodeStatuses().get(this.node().id));
  protected readonly problems = computed(() => this.graph.issuesByNode().get(this.node().id) ?? []);

  /** Inputs that render in the body: sockets always, widgets when nothing is wired into them. */
  protected readonly rows = computed(() =>
    this.spec().inputs.map((input) => {
      const wired = isConnected(this.graph.doc(), this.node().id, input.key);
      return {
        spec: input,
        wired,
        connectorId: connectorId.input(this.node().id, input.key),
        portClass: `port-${portColourKey(input.type)}`,
        value: this.valueOf(input),
        // A wired input keeps its widget on screen but disabled, so the graph does not visibly
        // change shape when an edge is attached or removed.
        widgetDisabled: wired,
      };
    }),
  );

  protected readonly outputs = computed(() =>
    this.spec().outputs.map((output) => ({
      spec: output,
      connectorId: connectorId.output(this.node().id, output.key),
      portClass: `port-${portColourKey(output.type)}`,
    })),
  );

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

  protected setValue(key: string, value: unknown): void {
    this.graph.dispatch(commands.setInputValue(this.node().id, key, value));
  }

  protected toggleCollapsed(): void {
    this.graph.dispatch(commands.toggleCollapsed(this.node().id));
  }

  protected remove(): void {
    this.graph.dispatch(commands.removeNodes([this.node().id]));
  }

  private valueOf(input: NodeInputSpec): unknown {
    const provided = this.node().values[input.key];
    return provided === undefined ? input.defaultValue : provided;
  }
}
