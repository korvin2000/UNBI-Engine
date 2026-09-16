import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { catchError, of, tap } from 'rxjs';
import * as commands from '../graph/commands';
import { GraphStore } from '../graph/graph-store';
import { WorkflowDoc, WorkflowNode } from '../graph/workflow.models';
import { ENGINE_CONFIG } from '../engine.config';
import { CatalogService } from './catalog.service';
import { DropdownOption, NodeSpec } from './catalog.models';

/** What a node's action answered. */
export interface ProbeResult {
  readonly ok: boolean;
  readonly message: string;
  /** The evidence: what was asked, what answered, what it costs. */
  readonly details: readonly string[];
  /** Choices discovered for an input, keyed by input key. */
  readonly options: ReadonlyMap<string, readonly DropdownOption[]>;
  /** Values written into the node, keyed by input key. */
  readonly applied: readonly string[];
}

/** Idle, in flight, or answered. The indicator is this and nothing else. */
export type ProbeState = 'idle' | 'running' | 'ok' | 'failed';

/** What the editor sends: this node's values, and those of everything wired upstream of it. */
interface SourcePayload {
  readonly nodeType: string;
  readonly values: Readonly<Record<string, unknown>>;
  readonly sources: Record<string, SourcePayload>;
}

/**
 * Running a node's declared actions, and remembering what they said.
 *
 * Every question this asks — is the endpoint reachable, what models does it serve, will this
 * request be honoured — is one the user could otherwise only answer by starting a run and reading
 * the wreckage. Asking before the run is the entire point, so the results are held per node
 * instance and shown as an indicator on the node itself rather than in a toast that scrolls away.
 *
 * Nothing here knows what any node does. It sends the values the editor holds, renders the message
 * that comes back, merges any discovered options into the dropdowns of the node that asked, and
 * applies any returned values through the ordinary edit command — so a discovery that guessed
 * wrong is one Ctrl+Z away, which is the only reason filling someone's fields in for them is
 * defensible.
 */
@Injectable({ providedIn: 'root' })
export class NodeProbeService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ENGINE_CONFIG);
  private readonly graph = inject(GraphStore);
  private readonly catalog = inject(CatalogService);

  /** A graph deep enough to need more than this is not one a probe should be walking. */
  private static readonly MAX_DEPTH = 8;

  private readonly states = signal<ReadonlyMap<string, ProbeState>>(new Map());
  private readonly results = signal<ReadonlyMap<string, ProbeResult>>(new Map());
  /** Discovered options, keyed by node instance and input key — never by node type. */
  private readonly discovered = signal<ReadonlyMap<string, readonly DropdownOption[]>>(new Map());
  /** What the upstream configuration looked like when an automatic action last ran, per node/action. */
  private readonly askedWith = new Map<string, string>();

  state(nodeId: string, action: string): ProbeState {
    return this.states().get(key(nodeId, action)) ?? 'idle';
  }

  result(nodeId: string, action: string): ProbeResult | null {
    return this.results().get(key(nodeId, action)) ?? null;
  }

  /** Options this node's actions discovered for one of its inputs. */
  optionsFor(nodeId: string, inputKey: string): readonly DropdownOption[] {
    return this.discovered().get(key(nodeId, inputKey)) ?? [];
  }

  /**
   * Runs an automatic action unless its answer is still good.
   *
   * Still good means: it ran against the same upstream configuration and either answered or is
   * still answering. The list a Model node offers depends on which endpoint is wired into it, so
   * rewiring the endpoint — or editing the profile it names — is what makes the answer stale, and
   * opening the list is when anyone would notice.
   */
  ensure(node: WorkflowNode, spec: NodeSpec, action: string): void {
    const sources = this.sourcesOf(node, spec, 0);
    const fingerprint = JSON.stringify(sources);
    const previous = this.askedWith.get(key(node.id, action));
    const state = this.state(node.id, action);
    if (previous === fingerprint && (state === 'ok' || state === 'running')) {
      return;
    }
    this.askedWith.set(key(node.id, action), fingerprint);
    this.send(node, action, { values: node.values, sources });
  }

  /**
   * Runs one action for one node instance.
   *
   * The upstream closure is gathered here rather than on the engine, because the graph lives here —
   * and because sending values rather than a graph is what makes a probe unable to run anything.
   */
  run(node: WorkflowNode, spec: NodeSpec, action: string): void {
    this.send(node, action, { values: node.values, sources: this.sourcesOf(node, spec, 0) });
  }

  private send(
    node: WorkflowNode,
    action: string,
    payload: { values: Readonly<Record<string, unknown>>; sources: Record<string, SourcePayload> },
  ): void {
    this.setState(node.id, action, 'running');

    this.http
      .post<Record<string, unknown>>(
        `${this.config.httpBase}/api/nodes/${encodeURIComponent(node.type)}/probe/${encodeURIComponent(action)}`,
        payload,
      )
      .pipe(
        tap((body) => this.accept(node, action, body)),
        catchError((error: unknown) => {
          this.record(node.id, action, 'failed', {
            ok: false,
            message: messageOf(error),
            details: [],
            options: new Map(),
            applied: [],
          });
          return of(null);
        }),
      )
      .subscribe();
  }

  private accept(node: WorkflowNode, action: string, body: Record<string, unknown>): void {
    const options = new Map<string, readonly DropdownOption[]>();
    const rawOptions = (body['options'] ?? {}) as Record<string, unknown>;
    for (const [inputKey, entries] of Object.entries(rawOptions)) {
      if (Array.isArray(entries)) {
        options.set(
          inputKey,
          entries.map((entry) => {
            const option = entry as Record<string, unknown>;
            return { value: String(option['value'] ?? ''), label: String(option['label'] ?? '') };
          }),
        );
      }
    }

    const values = (body['values'] ?? {}) as Record<string, unknown>;
    const applied = Object.keys(values);
    if (applied.length > 0) {
      // One command for the lot: a discovery that filled in six fields should be one press of
      // undo, not six.
      this.graph.dispatch(commands.setInputValues(node.id, values));
    }
    if (options.size > 0) {
      this.discovered.update((current) => {
        const next = new Map(current);
        options.forEach((choices, inputKey) => next.set(key(node.id, inputKey), choices));
        return next;
      });
    }

    const ok = body['ok'] === true;
    this.record(node.id, action, ok ? 'ok' : 'failed', {
      ok,
      message: String(body['message'] ?? ''),
      details: Array.isArray(body['details']) ? body['details'].map(String) : [],
      options,
      applied,
    });
  }

  /**
   * The settings of every node wired into this one, transitively.
   *
   * Depth-limited rather than cycle-checked because the document cannot hold a cycle the validator
   * accepted — but a limit costs one comparison and turns a future bug into a shallow answer rather
   * than a hung tab.
   */
  private sourcesOf(node: WorkflowNode, spec: NodeSpec, depth: number): Record<string, SourcePayload> {
    const sources: Record<string, SourcePayload> = {};
    if (depth >= NodeProbeService.MAX_DEPTH) {
      return sources;
    }
    const doc = this.graph.doc();
    const specs = this.catalog.byId();

    for (const input of spec.inputs) {
      if (!input.connectable) {
        continue;
      }
      const edge = doc.edges.find(
        (candidate) => candidate.targetNode === node.id && candidate.targetPort === input.key,
      );
      const upstream = edge ? nodeById(doc, edge.sourceNode) : undefined;
      const upstreamSpec = upstream ? specs.get(upstream.type) : undefined;
      if (upstream && upstreamSpec) {
        sources[input.key] = {
          nodeType: upstream.type,
          values: upstream.values,
          sources: this.sourcesOf(upstream, upstreamSpec, depth + 1),
        };
      }
    }
    return sources;
  }

  private setState(nodeId: string, action: string, state: ProbeState): void {
    this.states.update((current) => new Map(current).set(key(nodeId, action), state));
  }

  private record(nodeId: string, action: string, state: ProbeState, result: ProbeResult): void {
    this.setState(nodeId, action, state);
    this.results.update((current) => new Map(current).set(key(nodeId, action), result));
  }
}

function key(nodeId: string, suffix: string): string {
  return `${nodeId} ${suffix}`;
}

function nodeById(doc: WorkflowDoc, id: string): WorkflowNode | undefined {
  return doc.nodes.find((node) => node.id === id);
}

/** The engine's own sentence when it sent one, since it is the specific half of the answer. */
function messageOf(error: unknown): string {
  const body = (error as { error?: { detail?: string; message?: string } } | null)?.error;
  return body?.detail ?? body?.message ?? 'Could not reach the engine';
}
