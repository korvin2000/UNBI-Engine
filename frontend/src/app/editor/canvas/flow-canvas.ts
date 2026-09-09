import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import {
  FCanvasComponent,
  FCreateConnectionEvent,
  FCreateNodeEvent,
  FFlowModule,
  FMoveNodesEvent,
  FReassignConnectionEvent,
  FSelectionChangeEvent,
} from '@foblex/flow';
import { CatalogService } from '../../core/catalog/catalog.service';
import { NodeSpec } from '../../core/catalog/catalog.models';
import * as commands from '../../core/graph/commands';
import { GraphStore } from '../../core/graph/graph-store';
import { Point, connectorId } from '../../core/graph/workflow.models';
import { RunStore } from '../../core/runtime/run-store';
import { assignable } from '../../core/types/assignability';
import { Icon } from '../../shared/icon';
import { WorkflowNode } from './workflow-node';

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
})
export class FlowCanvas {
  private readonly graph = inject(GraphStore);
  private readonly catalog = inject(CatalogService);
  private readonly runs = inject(RunStore);

  private readonly canvas = viewChild.required(FCanvasComponent);

  protected readonly doc = this.graph.doc;
  protected readonly zoomLabel = signal('100%');

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
    return this.doc().edges.map((edge) => ({
      edge,
      source: connectorId.output(edge.sourceNode, edge.sourcePort),
      target: connectorId.input(edge.targetNode, edge.targetPort),
      invalid: invalid.has(edge.id),
      // An edge animates once its producer is done and its consumer has not finished: that is
      // exactly the window in which data is conceptually in flight.
      flowing:
        statuses.get(edge.sourceNode)?.state === 'COMPLETED' &&
        statuses.get(edge.targetNode)?.state === 'RUNNING',
    }));
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
    if (!this.isCompatible(source, target)) {
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

  /** A node dragged out of the palette. `data` is the descriptor id the palette attached. */
  protected onCreateNode(event: FCreateNodeEvent<string>): void {
    const spec = this.catalog.byId().get(event.data);
    if (!spec) {
      return;
    }
    const at = event.dropPosition ?? { x: event.externalItemRect.x, y: event.externalItemRect.y };
    this.graph.dispatch(
      commands.addNode(
        {
          id: crypto.randomUUID(),
          type: spec.id,
          position: { x: at.x, y: at.y },
          values: {},
          collapsed: false,
        },
        spec.label,
      ),
    );
  }

  protected onSelectionChange(event: FSelectionChangeEvent): void {
    this.graph.select(event.nodeIds);
  }

  protected onDeleteSelected(): void {
    const selected = [...this.graph.selection()];
    if (selected.length > 0) {
      this.graph.dispatch(commands.removeNodes(selected));
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
      this.canvas().fitToScreen({ x: 80, y: 80 }, false);
      this.syncZoomLabel();
    }
  }

  protected zoomIn(): void {
    this.canvas().setScale(Math.min(this.canvas().getScale() * 1.2, 3));
    this.syncZoomLabel();
  }

  protected zoomOut(): void {
    this.canvas().setScale(Math.max(this.canvas().getScale() / 1.2, 0.15));
    this.syncZoomLabel();
  }

  protected fit(): void {
    this.canvas().fitToScreen({ x: 80, y: 80 }, true);
    this.syncZoomLabel();
  }

  protected resetZoom(): void {
    this.canvas().resetScaleAndCenter(true);
    this.syncZoomLabel();
  }

  protected onCanvasChange(): void {
    this.syncZoomLabel();
  }

  private syncZoomLabel(): void {
    this.zoomLabel.set(`${Math.round(this.canvas().getScale() * 100)}%`);
  }

  /**
   * The drag-time legality check.
   *
   * Runs the same assignability rule the backend uses, which is why refusing here and refusing
   * there can never disagree — see `contract/type-assignability.json`.
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
