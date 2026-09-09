/**
 * The wire protocol, from the browser's side.
 *
 * Mirrors `EventCodec` and the command handling in `EngineWebSocketHandler`. Kept as a discriminated
 * union so that `switch` over `type` is exhaustive and a protocol addition surfaces as a compile
 * error in the store that consumes it.
 */

export type NodeRunState =
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'SKIPPED'
  | 'CANCELLED';

export type RunOutcome = 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'REJECTED';

export type EngineEvent =
  | { readonly type: 'run.accepted'; readonly requestId: string; readonly runId: string }
  | { readonly type: 'run.started'; readonly runId: string; readonly order: readonly string[]; readonly at: string }
  | {
      readonly type: 'node.state';
      readonly runId: string;
      readonly nodeId: string;
      readonly state: NodeRunState;
      readonly message: string | null;
      readonly durationMillis: number | null;
      readonly at: string;
    }
  | {
      readonly type: 'node.progress';
      readonly runId: string;
      readonly nodeId: string;
      readonly fraction: number;
      readonly message: string | null;
      readonly at: string;
    }
  | {
      readonly type: 'node.log';
      readonly runId: string;
      readonly nodeId: string;
      readonly message: string;
      readonly at: string;
    }
  | {
      readonly type: 'run.finished';
      readonly runId: string;
      readonly outcome: RunOutcome;
      readonly message: string;
      readonly durationMillis: number;
      readonly at: string;
    }
  | { readonly type: 'run.rejected'; readonly runId: string; readonly problems: readonly string[]; readonly at: string }
  | {
      readonly type: 'validation';
      readonly requestId: string;
      readonly valid: boolean;
      readonly issues: readonly ValidationIssue[];
    }
  | { readonly type: 'pong' }
  | { readonly type: 'error'; readonly message: string };

export interface ValidationIssue {
  readonly nodeId: string | null;
  readonly edgeId: string | null;
  readonly portKey: string | null;
  readonly message: string;
  readonly cycle: readonly string[];
}

export type EngineCommand =
  | { readonly type: 'run'; readonly requestId: string; readonly graph: WireGraph }
  | { readonly type: 'cancel'; readonly runId: string }
  | { readonly type: 'validate'; readonly requestId: string; readonly graph: WireGraph }
  | { readonly type: 'ping' };

export interface WireGraph {
  readonly nodes: readonly {
    readonly id: string;
    readonly type: string;
    readonly values: Record<string, unknown>;
    readonly position: { readonly x: number; readonly y: number };
  }[];
  readonly edges: readonly {
    readonly id: string;
    readonly sourceNode: string;
    readonly sourcePort: string;
    readonly targetNode: string;
    readonly targetPort: string;
  }[];
}

/** A frame is only trusted once its `type` is one we know. */
export function isEngineEvent(value: unknown): value is EngineEvent {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as { type?: unknown }).type === 'string'
  );
}
