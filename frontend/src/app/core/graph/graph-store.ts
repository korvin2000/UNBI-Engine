import { Injectable, computed, inject, signal } from '@angular/core';
import { CatalogService } from '../catalog/catalog.service';
import { NodeSpec } from '../catalog/catalog.models';
import { assignable, explainRejection } from '../types/assignability';
import { Command, documentsMatch } from './commands';
import { EMPTY_DOC, WorkflowDoc, WorkflowNode } from './workflow.models';

/** A problem the editor found, shown on the offending node or edge. */
export interface GraphIssue {
  readonly nodeId?: string;
  readonly edgeId?: string;
  readonly portKey?: string;
  readonly message: string;
}

/**
 * The document, its history, and everything derived from it.
 *
 * Signals for state, `computed` for everything that follows from it. Validation in particular is
 * derived rather than recomputed imperatively: there is no "remember to revalidate after X" to
 * forget, because there is no imperative path at all.
 */
@Injectable({ providedIn: 'root' })
export class GraphStore {
  private readonly catalog = inject(CatalogService);

  private readonly past = signal<readonly WorkflowDoc[]>([]);
  private readonly future = signal<readonly WorkflowDoc[]>([]);
  private readonly current = signal<WorkflowDoc>(EMPTY_DOC);
  private readonly lastLabel = signal<string>('');

  readonly doc = this.current.asReadonly();
  readonly selection = signal<ReadonlySet<string>>(new Set());

  readonly canUndo = computed(() => this.past().length > 0);
  readonly canRedo = computed(() => this.future().length > 0);
  readonly lastAction = this.lastLabel.asReadonly();

  readonly nodeCount = computed(() => this.current().nodes.length);
  readonly edgeCount = computed(() => this.current().edges.length);

  /**
   * Validation, derived.
   *
   * Mirrors the backend's `GraphValidator` for the checks a user needs to see immediately. The
   * backend remains the authority and re-runs all of them before executing — this exists so a
   * mistake is visible while it is being made, not after pressing Run.
   */
  readonly issues = computed<readonly GraphIssue[]>(() => {
    const doc = this.current();
    const specs = this.catalog.byId();
    if (specs.size === 0) {
      return [];
    }
    const found: GraphIssue[] = [];

    for (const node of doc.nodes) {
      if (!specs.has(node.type)) {
        found.push({ nodeId: node.id, message: `Unknown node type: ${node.type}` });
      }
    }

    for (const edge of doc.edges) {
      const source = specs.get(doc.nodes.find((n) => n.id === edge.sourceNode)?.type ?? '');
      const target = specs.get(doc.nodes.find((n) => n.id === edge.targetNode)?.type ?? '');
      const output = source?.outputs.find((port) => port.key === edge.sourcePort);
      const input = target?.inputs.find((port) => port.key === edge.targetPort);
      if (!output || !input) {
        found.push({ edgeId: edge.id, message: 'This connection refers to a port that no longer exists' });
        continue;
      }
      if (!assignable(output.type, input.type)) {
        found.push({
          edgeId: edge.id,
          message: `${output.label} → ${input.label}: ${explainRejection(output.type, input.type)}`,
        });
      }
    }

    for (const node of doc.nodes) {
      const spec = specs.get(node.type);
      if (!spec) {
        continue;
      }
      for (const input of spec.inputs) {
        if (!input.required) {
          continue;
        }
        const wired = doc.edges.some(
          (edge) => edge.targetNode === node.id && edge.targetPort === input.key,
        );
        const typed = node.values[input.key];
        const hasValue = wired || (typed !== undefined && typed !== null && typed !== '');
        if (!hasValue && (input.defaultValue === null || input.defaultValue === '')) {
          found.push({
            nodeId: node.id,
            portKey: input.key,
            message: `${spec.label} needs a value for ${input.label}`,
          });
        }
      }
    }

    const cycle = findCycle(doc);
    if (cycle.length > 0) {
      found.push({ message: `These nodes form a loop: ${cycle.join(' → ')}` });
    }

    return found;
  });

  readonly isRunnable = computed(() => this.current().nodes.length > 0 && this.issues().length === 0);

  /** Issues indexed by node, so a node component reads its own state in O(1). */
  readonly issuesByNode = computed(() => {
    const map = new Map<string, GraphIssue[]>();
    for (const issue of this.issues()) {
      if (!issue.nodeId) {
        continue;
      }
      const existing = map.get(issue.nodeId) ?? [];
      existing.push(issue);
      map.set(issue.nodeId, existing);
    }
    return map;
  });

  readonly issuesByEdge = computed(() => {
    const map = new Map<string, GraphIssue[]>();
    for (const issue of this.issues()) {
      if (!issue.edgeId) {
        continue;
      }
      const existing = map.get(issue.edgeId) ?? [];
      existing.push(issue);
      map.set(issue.edgeId, existing);
    }
    return map;
  });

  dispatch(command: Command): void {
    const before = this.current();
    const after = command.apply(before);
    if (documentsMatch(before, after)) {
      return;
    }
    this.past.update((history) => [...history, before]);
    this.future.set([]);
    this.current.set(after);
    this.lastLabel.set(command.label);
  }

  /**
   * Applies a command without adding a history entry.
   *
   * Used for the position updates the canvas emits continuously during a drag: one history entry
   * per pixel would make undo useless. The drag's final position is dispatched normally.
   */
  applyWithoutHistory(command: Command): void {
    this.current.set(command.apply(this.current()));
  }

  undo(): void {
    const history = this.past();
    if (history.length === 0) {
      return;
    }
    const previous = history[history.length - 1];
    this.past.set(history.slice(0, -1));
    this.future.update((redo) => [this.current(), ...redo]);
    this.current.set(previous);
  }

  redo(): void {
    const [next, ...rest] = this.future();
    if (!next) {
      return;
    }
    this.future.set(rest);
    this.past.update((history) => [...history, this.current()]);
    this.current.set(next);
  }

  select(ids: readonly string[]): void {
    this.selection.set(new Set(ids));
  }

  clear(): void {
    this.past.set([]);
    this.future.set([]);
    this.current.set(EMPTY_DOC);
    this.selection.set(new Set());
  }

  load(doc: WorkflowDoc): void {
    this.past.set([]);
    this.future.set([]);
    this.current.set(doc);
    this.selection.set(new Set());
  }

  node(id: string): WorkflowNode | undefined {
    return this.current().nodes.find((node) => node.id === id);
  }

  specFor(node: WorkflowNode): NodeSpec | undefined {
    return this.catalog.byId().get(node.type);
  }
}

/**
 * Returns one cycle, or an empty array.
 *
 * Iterative depth-first search with an explicit stack rather than recursion: a pasted or generated
 * graph can be deep enough to blow the call stack, and a crashed editor is a worse failure than a
 * slow one.
 */
function findCycle(doc: WorkflowDoc): string[] {
  const outgoing = new Map<string, string[]>();
  for (const node of doc.nodes) {
    outgoing.set(node.id, []);
  }
  for (const edge of doc.edges) {
    outgoing.get(edge.sourceNode)?.push(edge.targetNode);
  }

  const UNVISITED = 0;
  const IN_PROGRESS = 1;
  const SETTLED = 2;
  const state = new Map<string, number>(doc.nodes.map((node) => [node.id, UNVISITED]));

  for (const start of doc.nodes) {
    if (state.get(start.id) !== UNVISITED) {
      continue;
    }
    const path: string[] = [];
    const stack: { id: string; nextChild: number }[] = [{ id: start.id, nextChild: 0 }];
    state.set(start.id, IN_PROGRESS);
    path.push(start.id);

    while (stack.length > 0) {
      const frame = stack[stack.length - 1];
      const children = outgoing.get(frame.id) ?? [];
      if (frame.nextChild >= children.length) {
        state.set(frame.id, SETTLED);
        stack.pop();
        path.pop();
        continue;
      }
      const child = children[frame.nextChild++];
      const childState = state.get(child);
      if (childState === IN_PROGRESS) {
        return path.slice(path.indexOf(child));
      }
      if (childState === UNVISITED) {
        state.set(child, IN_PROGRESS);
        path.push(child);
        stack.push({ id: child, nextChild: 0 });
      }
    }
  }
  return [];
}
