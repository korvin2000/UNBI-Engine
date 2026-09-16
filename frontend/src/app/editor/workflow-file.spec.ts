import { describe, expect, it } from 'vitest';
import { WorkflowDoc, WorkflowNode } from '../core/graph/workflow.models';
import { parseWorkflow, serialiseWorkflow } from './workflow-file.service';

/**
 * The saved file, and what survives a round trip through it.
 *
 * `parseWorkflow` whitelists the fields it copies out of a file, which is the right shape — an
 * opened file is untrusted input — but it also means a field added to the model and forgotten here
 * is dropped in silence. A layout that quietly loses the widths somebody set is the one failure
 * mode a save is judged on, so the round trip is stated as a test rather than as a habit.
 */

function node(id: string, extra: Partial<WorkflowNode> = {}): WorkflowNode {
  return {
    id,
    type: 'test.node',
    title: '',
    position: { x: 10, y: 20 },
    values: { prompt: 'hello' },
    collapsed: false,
    disabled: false,
    ...extra,
  };
}

function roundTrip(doc: WorkflowDoc): WorkflowDoc {
  return parseWorkflow(serialiseWorkflow(doc));
}

describe('a workflow file', () => {
  it('brings a resized node back at the width it was saved at', () => {
    const doc: WorkflowDoc = { nodes: [node('a', { width: 400 })], edges: [] };
    expect(roundTrip(doc).nodes[0].width).toBe(400);
  });

  it('carries no width at all for a node that never had one', () => {
    const doc: WorkflowDoc = { nodes: [node('a')], edges: [] };
    // Absent in the file, and absent on the way back in — not the number 252, so a later change to
    // the default width still reaches a node that never expressed an opinion about its own.
    expect(serialiseWorkflow(doc)).not.toContain('width');
    expect(roundTrip(doc).nodes[0].width).toBeUndefined();
  });

  it('keeps the rest of a node intact across the trip', () => {
    const doc: WorkflowDoc = {
      nodes: [node('a', { title: 'Summariser', collapsed: true, disabled: true, width: 320 })],
      edges: [{ id: 'e1', sourceNode: 'a', sourcePort: 'out', targetNode: 'a', targetPort: 'in' }],
    };
    expect(roundTrip(doc)).toEqual(doc);
  });

  it('clamps a hand-edited width instead of drawing it', () => {
    // A node 4,000px wide cannot be dragged back into view, and a 4px one has no grip left to grab.
    expect(width(4000)).toBe(640);
    expect(width(4)).toBe(252);
    expect(width(317.6)).toBe(318);
  });

  it('treats anything that is not a finite number as no width', () => {
    for (const raw of [undefined, null, '400', Number.NaN, Number.POSITIVE_INFINITY, {}]) {
      expect(width(raw)).toBeUndefined();
    }
  });
});

/** The width one hand-written node comes back with. */
function width(raw: unknown): number | undefined {
  const file = {
    format: 'unbi-workflow',
    version: 1,
    nodes: [{ id: 'a', type: 'test.node', position: { x: 0, y: 0 }, values: {}, width: raw }],
    edges: [],
  };
  return parseWorkflow(JSON.stringify(file)).nodes[0].width;
}
