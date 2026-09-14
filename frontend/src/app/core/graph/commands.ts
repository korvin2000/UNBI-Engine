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

/**
 * Deletes a mixed selection in one step.
 *
 * A selection can hold both nodes and connections, and dispatching two commands would make undo
 * take two presses to put back what one press removed.
 */
export function removeSelection(nodeIds: readonly string[], edgeIds: readonly string[]): Command {
  const doomedNodes = new Set(nodeIds);
  const doomedEdges = new Set(edgeIds);
  const total = doomedNodes.size + doomedEdges.size;
  return {
    label: total === 1 ? 'Delete' : `Delete ${total} items`,
    apply: (doc) => ({
      nodes: doc.nodes.filter((node) => !doomedNodes.has(node.id)),
      edges: doc.edges.filter(
        (edge) =>
          !doomedEdges.has(edge.id) &&
          !doomedNodes.has(edge.sourceNode) &&
          !doomedNodes.has(edge.targetNode),
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

export function toggleDisabled(nodeId: string): Command {
  return {
    label: 'Toggle node',
    apply: (doc) => ({
      ...doc,
      nodes: doc.nodes.map((node) =>
        node.id === nodeId ? { ...node, disabled: !node.disabled } : node,
      ),
    }),
  };
}

/**
 * Copies nodes and the edges *between* them, offset so the copy is visibly a copy.
 *
 * Edges that leave the selection are dropped rather than duplicated: a second wire into an input
 * that already has one would be immediately replaced, so keeping them would only look like a bug.
 */
export function duplicateNodes(
  nodeIds: readonly string[],
  newId: () => string,
  offset: Point = { x: 34, y: 34 },
): Command {
  const wanted = new Set(nodeIds);
  return {
    label: nodeIds.length === 1 ? 'Duplicate node' : `Duplicate ${nodeIds.length} nodes`,
    apply: (doc) => {
      const originals = doc.nodes.filter((node) => wanted.has(node.id));
      if (originals.length === 0) {
        return doc;
      }
      const remap = new Map(originals.map((node) => [node.id, newId()]));
      const copies = originals.map((node) => ({
        ...node,
        id: remap.get(node.id)!,
        position: { x: node.position.x + offset.x, y: node.position.y + offset.y },
        values: { ...node.values },
      }));
      const copiedEdges = doc.edges
        .filter((edge) => remap.has(edge.sourceNode) && remap.has(edge.targetNode))
        .map((edge) => ({
          ...edge,
          id: newId(),
          sourceNode: remap.get(edge.sourceNode)!,
          targetNode: remap.get(edge.targetNode)!,
        }));
      return { nodes: [...doc.nodes, ...copies], edges: [...doc.edges, ...copiedEdges] };
    },
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
