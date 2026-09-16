import { Injectable, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { GraphStore } from '../graph/graph-store';
import { WorkflowDoc, excludedFromRun } from '../graph/workflow.models';
import { EngineSocket } from './engine-socket';
import { NodeRunState, RunOutcome, WireGraph } from './engine-events';

/** What the editor knows about one node during a run. */
export interface NodeRunStatus {
  readonly state: NodeRunState;
  readonly progress: number;
  readonly message: string | null;
  readonly durationMillis: number | null;
  readonly logs: readonly string[];
  /** Text the node has streamed so far this run, while it is still producing it. */
  readonly streamed: string;
}

export interface RunSummary {
  readonly runId: string;
  readonly outcome: RunOutcome;
  readonly message: string;
  readonly durationMillis: number;
}

const IDLE: NodeRunStatus = {
  state: 'QUEUED',
  progress: 0,
  message: null,
  durationMillis: null,
  logs: [],
  streamed: '',
};

/** Past this, the live view is showing a wall of text nobody is reading. */
const MAX_STREAMED_CHARS = 4000;

/**
 * Folds the engine event stream into the state the editor renders.
 *
 * This is the RxJS-to-signals seam: events arrive as a stream, and everything downstream reads
 * signals. Components never subscribe, so none of them can leak a subscription or miss an update.
 */
@Injectable({ providedIn: 'root' })
export class RunStore {
  private readonly socket = inject(EngineSocket);
  private readonly graph = inject(GraphStore);

  private readonly statuses = signal<ReadonlyMap<string, NodeRunStatus>>(new Map());
  private readonly activeRunId = signal<string | null>(null);
  private readonly lastSummary = signal<RunSummary | null>(null);
  private readonly problems = signal<readonly string[]>([]);
  private readonly requestId = signal<string>('');

  readonly nodeStatuses = this.statuses.asReadonly();
  readonly isRunning = computed(() => this.activeRunId() !== null);
  readonly summary = this.lastSummary.asReadonly();
  readonly rejections = this.problems.asReadonly();
  readonly connection = this.socket.state;

  /** Overall progress: completed nodes over total, so the toolbar bar advances monotonically. */
  readonly overallProgress = computed(() => {
    const statuses = [...this.statuses().values()];
    if (statuses.length === 0) {
      return 0;
    }
    const done = statuses.filter(
      (status) => status.state === 'COMPLETED' || status.state === 'SKIPPED' || status.state === 'FAILED',
    ).length;
    const running = statuses
      .filter((status) => status.state === 'RUNNING')
      .reduce((sum, status) => sum + status.progress, 0);
    return Math.min(1, (done + running) / statuses.length);
  });

  constructor() {
    this.socket.events.pipe(takeUntilDestroyed()).subscribe((event) => {
      switch (event.type) {
        case 'run.accepted':
          if (event.requestId === this.requestId()) {
            this.activeRunId.set(event.runId);
          }
          break;

        case 'run.started':
          this.activeRunId.set(event.runId);
          this.problems.set([]);
          this.lastSummary.set(null);
          this.statuses.set(new Map(event.order.map((nodeId) => [nodeId, IDLE])));
          break;

        case 'node.stream':
          this.patch(event.nodeId, (status) => ({
            ...status,
            // Trimmed from the front: what a person watches is the end of the answer as it lands.
            streamed: (status.streamed + event.chunk).slice(-MAX_STREAMED_CHARS),
          }));
          break;

        case 'node.state':
          this.patch(event.nodeId, (status) => ({
            ...status,
            state: event.state,
            // Restarting a node clears what the last attempt streamed, so two runs never appear
            // as one answer twice as long.
            streamed: event.state === 'RUNNING' ? '' : status.streamed,
            // A terminal state carries a message only when something went wrong. Keeping the last
            // progress message otherwise is what leaves "142 files" on screen after a node
            // finishes, instead of blanking the one useful thing it reported.
            message: event.message ?? status.message,
            durationMillis: event.durationMillis,
            // A node that finishes without ever reporting progress should still show a full bar.
            progress: event.state === 'COMPLETED' ? 1 : status.progress,
          }));
          break;

        case 'node.progress':
          this.patch(event.nodeId, (status) => ({
            ...status,
            progress: event.fraction,
            message: event.message ?? status.message,
          }));
          break;

        case 'node.log':
          this.patch(event.nodeId, (status) => ({
            ...status,
            // Bounded: a node logging per file would otherwise grow without limit for a long run.
            logs: [...status.logs, event.message].slice(-50),
          }));
          break;

        case 'run.finished':
          this.activeRunId.set(null);
          this.lastSummary.set({
            runId: event.runId,
            outcome: event.outcome,
            message: event.message,
            durationMillis: event.durationMillis,
          });
          break;

        case 'run.rejected':
          this.problems.set(event.problems);
          break;

        case 'error':
          this.problems.set([event.message]);
          break;

        case 'validation':
        case 'pong':
          break;
      }
    });
  }

  start(): void {
    if (this.isRunning()) {
      return;
    }
    const request = crypto.randomUUID();
    this.requestId.set(request);
    this.problems.set([]);
    this.lastSummary.set(null);
    // Seed every node as queued immediately: waiting for run.started would leave the canvas inert
    // for a round trip after the user pressed Run.
    const doc = this.graph.doc();
    const excluded = excludedFromRun(doc);
    this.statuses.set(
      new Map(doc.nodes.filter((node) => !excluded.has(node.id)).map((node) => [node.id, IDLE])),
    );
    this.socket.send({ type: 'run', requestId: request, graph: toWireGraph(doc) });
  }

  cancel(): void {
    const runId = this.activeRunId();
    if (runId) {
      this.socket.send({ type: 'cancel', runId });
    }
  }

  statusFor(nodeId: string): NodeRunStatus | undefined {
    return this.statuses().get(nodeId);
  }

  reset(): void {
    this.statuses.set(new Map());
    this.lastSummary.set(null);
    this.problems.set([]);
  }

  private patch(nodeId: string, update: (status: NodeRunStatus) => NodeRunStatus): void {
    this.statuses.update((current) => {
      const next = new Map(current);
      next.set(nodeId, update(current.get(nodeId) ?? IDLE));
      return next;
    });
  }
}

/**
 * Strips editor-only fields; the engine has no use for `collapsed`.
 *
 * Nodes switched off in the editor — and everything downstream of them — are left out entirely
 * rather than sent with a flag, so the engine needs no notion of a disabled node and the graph it
 * receives is always one it can actually run.
 */
export function toWireGraph(doc: WorkflowDoc): WireGraph {
  const excluded = excludedFromRun(doc);
  return {
    nodes: doc.nodes
      .filter((node) => !excluded.has(node.id))
      .map((node) => ({
        id: node.id,
        type: node.type,
        values: { ...node.values },
        position: { x: node.position.x, y: node.position.y },
      })),
    edges: doc.edges
      .filter((edge) => !excluded.has(edge.sourceNode) && !excluded.has(edge.targetNode))
      .map((edge) => ({
        id: edge.id,
        sourceNode: edge.sourceNode,
        sourcePort: edge.sourcePort,
        targetNode: edge.targetNode,
        targetPort: edge.targetPort,
      })),
  };
}
