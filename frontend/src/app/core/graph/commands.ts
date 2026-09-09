import { Point, WorkflowDoc, WorkflowEdge, WorkflowNode } from './workflow.models';

/**
 * Every change to the document, as a value.
 *
 * The command pattern is here from the first commit rather than added later, because retrofitting
 * undo onto mutable state is the classic way this kind of editor becomes unfixable. A command
 * knows how to apply itself and how to describe itself; the store handles history.
 *
 * `apply` must be pure. Given the same document it must produce the same result, because the store
 * replays commands to redo.
 */
export interface Command {
  /** Shown in the history tooltip, e.g. "Add Scan Directory". */
  readonly label: string;
  apply(doc: WorkflowDoc): WorkflowDoc;
}

export function addNode(node: WorkflowNode, label: string): Command {
  return {
    label: `Add ${label}`,
    apply: (doc) => ({ ...doc, nodes: [...doc.nodes, node] }),
  };
}

export function removeNodes(nodeIds: readonly string[]): Command {
  const doomed = new Set(nodeIds);
  return {
    label: nodeIds.length === 1 ? 'Delete node' : `Delete ${nodeIds.length} nodes`,
    apply: (doc) => ({
      nodes: doc.nodes.filter((node) => !doomed.has(node.id)),
      // Edges dangling off a deleted node would silently reconnect if that id were ever reused.
      edges: doc.edges.filter(
        (edge) => !doomed.has(edge.sourceNode) && !doomed.has(edge.targetNode),
      ),
    }),
  };
}

export function removeEdges(edgeIds: readonly string[]): Command {
  const doomed = new Set(edgeIds);
  return {
    label: edgeIds.length === 1 ? 'Disconnect' : `Disconnect ${edgeIds.length} edges`,
    apply: (doc) => ({ ...doc, edges: doc.edges.filter((edge) => !doomed.has(edge.id)) }),
  };
}

export function connect(edge: WorkflowEdge): Command {
  return {
    label: 'Connect',
    apply: (doc) => ({
      ...doc,
      edges: [
        // An input takes one edge. Replacing rather than refusing matches what the user meant by
        // dropping a second wire onto an occupied port, and keeps the document always valid.
        ...doc.edges.filter(
          (existing) =>
            !(existing.targetNode === edge.targetNode && existing.targetPort === edge.targetPort),
        ),
        edge,
      ],
    }),
  };
}

export function moveNodes(positions: ReadonlyMap<string, Point>): Command {
  return {
    label: positions.size === 1 ? 'Move node' : `Move ${positions.size} nodes`,
    apply: (doc) => ({
      ...doc,
      nodes: doc.nodes.map((node) => {
        const moved = positions.get(node.id);
        return moved ? { ...node, position: moved } : node;
      }),
    }),
  };
}

export function setInputValue(nodeId: string, key: string, value: unknown): Command {
  return {
    label: 'Edit value',
    apply: (doc) => ({
      ...doc,
      nodes: doc.nodes.map((node) =>
        node.id === nodeId ? { ...node, values: { ...node.values, [key]: value } } : node,
      ),
    }),
  };
}

export function toggleCollapsed(nodeId: string): Command {
  return {
    label: 'Collapse node',
    apply: (doc) => ({
      ...doc,
      nodes: doc.nodes.map((node) =>
        node.id === nodeId ? { ...node, collapsed: !node.collapsed } : node,
      ),
    }),
  };
}

export function replaceDocument(next: WorkflowDoc, label: string): Command {
  return { label, apply: () => next };
}

/**
 * Whether a command actually changed anything.
 *
 * Commands are written with `map` and `filter`, which allocate a new array every time even when
 * every element is untouched — so reference equality on the document says nothing. Both operations
 * do preserve *element* identity when they change nothing, which makes this shallow scan exact
 * rather than a heuristic, and O(n) with no allocation.
 *
 * Deciding this once here is what keeps every command free of its own no-op check.
 */
export function documentsMatch(before: WorkflowDoc, after: WorkflowDoc): boolean {
  return (
    before === after ||
    (sameElements(before.nodes, after.nodes) && sameElements(before.edges, after.edges))
  );
}

function sameElements<T>(before: readonly T[], after: readonly T[]): boolean {
  return before.length === after.length && before.every((item, index) => item === after[index]);
}
