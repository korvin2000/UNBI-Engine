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
  readonly position: Point;
  /** Widget values keyed by input key. An incoming edge overrides the value here. */
  readonly values: Readonly<Record<string, unknown>>;
  readonly collapsed: boolean;
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
