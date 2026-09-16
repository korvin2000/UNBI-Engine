/**
 * The workflow document.
 *
 * Immutable by convention: every mutation goes through a command, which returns a new document.
 * That is what makes undo correct rather than approximately correct, and it is also why the store
 * can hand the same object to a `computed` without worrying about who else holds a reference.
 */

export interface WorkflowDoc {
  readonly nodes: readonly WorkflowNode[];
  readonly edges: readonly WorkflowEdge[];
}

export interface WorkflowNode {
  readonly id: string;
  /** Which `NodeSpec.id` this is an instance of. */
  readonly type: string;
  /**
   * A name the user gave this instance, or empty for the node type's own label.
   *
   * Editor-only: the engine identifies a node by id and has no use for a name. It matters on a
   * canvas holding four LLM Request nodes, where the type label alone says nothing about which is
   * which — and it is what makes a saved graph readable a month later.
   */
  readonly title: string;
  readonly position: Point;
  /** Widget values keyed by input key. An incoming edge overrides the value here. */
  readonly values: Readonly<Record<string, unknown>>;
  readonly collapsed: boolean;
  /**
   * Switched off from the node footer: kept on the canvas and in the saved file, but left out of
   * the run. Everything downstream of it goes too — see {@link excludedFromRun}.
   */
  readonly disabled: boolean;
}

export interface WorkflowEdge {
  readonly id: string;
  readonly sourceNode: string;
  readonly sourcePort: string;
  readonly targetNode: string;
  readonly targetPort: string;
}

export interface Point {
  readonly x: number;
  readonly y: number;
}

export const EMPTY_DOC: WorkflowDoc = { nodes: [], edges: [] };

/**
 * A fresh node instance.
 *
 * One factory rather than an object literal at each site: the palette, the canvas drop handler, the
 * preset instantiator and the built-in example all create nodes, and a field added to
 * {@link WorkflowNode} should be a one-line change rather than a hunt.
 */
export function newNode(
  type: string,
  position: Point,
  values: Readonly<Record<string, unknown>> = {},
  title = '',
): WorkflowNode {
  return {
    id: crypto.randomUUID(),
    type,
    title,
    position,
    values,
    collapsed: false,
    disabled: false,
  };
}

/**
 * Connector ids used by the flow canvas.
 *
 * Foblex connects connector to connector, not node to node, so the ids must be globally unique and
 * reversible. Keeping the encoding in one place means a change of separator cannot desynchronise
 * the two directions.
 */
export const connectorId = {
  output: (nodeId: string, portKey: string): string => `${nodeId}::out::${portKey}`,
  input: (nodeId: string, portKey: string): string => `${nodeId}::in::${portKey}`,

  parse(id: string): { nodeId: string; direction: 'in' | 'out'; portKey: string } | null {
    const parts = id.split('::');
    if (parts.length !== 3) {
      return null;
    }
    const [nodeId, direction, portKey] = parts;
    if (direction !== 'in' && direction !== 'out') {
      return null;
    }
    return { nodeId, direction, portKey };
  },
};

/** The value a node input currently holds, honouring the precedence the backend applies. */
export function inputValue(
  node: WorkflowNode,
  key: string,
  fallback: unknown,
): unknown {
  const provided = node.values[key];
  return provided === undefined ? fallback : provided;
}

export function isConnected(doc: WorkflowDoc, nodeId: string, portKey: string): boolean {
  return doc.edges.some((edge) => edge.targetNode === nodeId && edge.targetPort === portKey);
}

/**
 * Every node the run must leave out: the ones switched off, plus everything that feeds from them.
 *
 * A node whose producer was switched off cannot run either — it would fail for a missing input and
 * report that as an error, which is not what the user asked for by flipping one switch. Excluding
 * the whole downstream closure instead is both what chaiNNer does and the only reading under which
 * the button means anything.
 */
export function excludedFromRun(doc: WorkflowDoc): ReadonlySet<string> {
  const excluded = new Set(doc.nodes.filter((node) => node.disabled).map((node) => node.id));
  if (excluded.size === 0) {
    // The overwhelmingly common case, and this runs on every document change.
    return excluded;
  }
  // Repeat until nothing new is reached: an edge list is not topologically ordered, so one pass
  // would miss anything wired in an order the author happened to choose.
  for (let changed = true; changed; ) {
    changed = false;
    for (const edge of doc.edges) {
      if (excluded.has(edge.sourceNode) && !excluded.has(edge.targetNode)) {
        excluded.add(edge.targetNode);
        changed = true;
      }
    }
  }
  return excluded;
}
