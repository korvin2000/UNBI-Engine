import { Injectable, inject } from '@angular/core';
import { GraphStore } from '../../core/graph/graph-store';

/**
 * Selecting nodes from outside the canvas's own gestures.
 *
 * The selection has two owners, and they have to be written together. The store's copy is what
 * Delete, Ctrl+D, the context menu and the inspector read; the flow library's copy is what draws
 * the selection border — and the app only ever *hears* about that one, through `fSelectionChange`.
 * So a `graph.select()` on its own leaves the canvas highlighting one node while the next Delete
 * removes another, which is exactly what happens for a press the library never saw: the Advanced
 * strip and the width grip both carry `fDragBlocker`, so it ignores them entirely.
 *
 * `FlowCanvas` registers the library half on the way in; everything that selects programmatically —
 * a node's Advanced strip, its width grip, the context menu, Ctrl+A — comes through here. A
 * registered painter rather than an injected `FlowCanvas`, because the canvas already imports the
 * node component and injecting it back would make that a cycle.
 */
@Injectable({ providedIn: 'root' })
export class CanvasSelection {
  private readonly graph = inject(GraphStore);

  /** How the flow library is told, once a canvas has registered itself. */
  private paint: ((nodeIds: readonly string[]) => void) | null = null;

  /**
   * Called by `FlowCanvas` with the way to write the library's half.
   *
   * Returns the way to take it back: this service outlives the canvas, and a painter left pointing
   * at a destroyed flow would throw the next time anything selected a node.
   */
  register(paint: (nodeIds: readonly string[]) => void): () => void {
    this.paint = paint;
    return () => {
      if (this.paint === paint) {
        this.paint = null;
      }
    };
  }

  /** Makes these nodes the selection, in both halves. Edges are never part of one of these. */
  select(nodeIds: readonly string[]): void {
    this.graph.select(nodeIds);
    this.paint?.(nodeIds);
  }
}
