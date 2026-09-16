import { Point, WorkflowDoc, WorkflowEdge, WorkflowNode, clampNodeWidth } from './workflow.models';

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

/** Renames one node instance. Empty puts the node type's own label back. */
export function renameNode(nodeId: string, title: string): Command {
  const trimmed = title.trim();
  return {
    label: trimmed ? `Rename to ${trimmed}` : 'Clear name',
    apply: (doc) => ({
      ...doc,
      nodes: doc.nodes.map((node) => (node.id === nodeId ? { ...node, title: trimmed } : node)),
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

/**
 * Sets, or clears, the drawn width of one or more nodes.
 *
 * `undefined` is "back to the default" rather than "252": the node then has no opinion about its
 * width, so a later change to `--node-width` reaches it. One command for the whole selection,
 * because resizing four selected nodes together should be one press of undo.
 *
 * Nodes already at the asked-for width are returned untouched, which is what lets the store's
 * identity check see a drag that ended where it started as the no-op it is.
 */
export function resizeNodes(nodeIds: readonly string[], width: number | undefined): Command {
  const wanted = new Set(nodeIds);
  const settled = width === undefined ? undefined : clampNodeWidth(width);
  return {
    label: wanted.size === 1 ? 'Resize node' : `Resize ${wanted.size} nodes`,
    apply: (doc) => ({
      ...doc,
      nodes: doc.nodes.map((node) => {
        if (!wanted.has(node.id) || node.width === settled) {
          return node;
        }
        if (settled === undefined) {
          // Deleted rather than set to undefined, so a saved file carries no empty key and a
          // node with no opinion about its width is indistinguishable from one that never had one.
          const { width: _cleared, ...rest } = node;
          return rest;
        }
        return { ...node, width: settled };
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

/**
 * Several of one node's values at once.
 *
 * One command rather than a loop over {@link setInputValue}, because a discovery that filled in six
 * settings should be one press of undo and not six. That reversibility is what makes it acceptable
 * for the editor to write into a user's fields at all.
 */
export function setInputValues(nodeId: string, values: Readonly<Record<string, unknown>>): Command {
  const count = Object.keys(values).length;
  return {
    label: count === 1 ? 'Edit value' : `Fill in ${count} settings`,
    apply: (doc) => ({
      ...doc,
      nodes: doc.nodes.map((node) =>
        node.id === nodeId ? { ...node, values: { ...node.values, ...values } } : node,
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
