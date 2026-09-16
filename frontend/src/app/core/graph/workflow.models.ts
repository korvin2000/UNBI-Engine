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
   * How wide this instance is drawn, in canvas pixels; absent means the default width.
   *
   * Per node rather than per node type, and part of the document rather than a view preference: a
   * node holding a JSON schema or a table of discovered facts needs the room, the four beside it do
   * not, and which of them was widened is something the author decided and a reader of the saved
   * file should get back. Absent rather than 252 when untouched, so the default can move without
   * rewriting every file that never expressed an opinion.
   */
  readonly width?: number;
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
 * What a node may be resized to.
 *
 * The minimum is the default width from `--node-width`: narrower than that a dropdown's label and
 * its chevron start colliding, and the point of the grip is to make room, not to take it away. The
 * maximum is where a node stops being a node — past roughly two and a half default widths the graph
 * reads as a stack of documents, and a prompt that long belongs in the full-window editor.
 *
 * The step is what the grip rounds to, so two nodes dragged to "about the same" end up identical
 * rather than four pixels apart.
 */
export const MIN_NODE_WIDTH = 252;
export const MAX_NODE_WIDTH = 640;
export const DEFAULT_NODE_WIDTH = 252;
export const NODE_WIDTH_STEP = 8;

/** A width the canvas can actually draw: a whole number of pixels, inside the bounds. */
export function clampNodeWidth(width: number): number {
  return Math.round(Math.min(Math.max(width, MIN_NODE_WIDTH), MAX_NODE_WIDTH));
}

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
