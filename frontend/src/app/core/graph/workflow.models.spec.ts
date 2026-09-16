import { describe, expect, it } from 'vitest';
import { WorkflowDoc, WorkflowNode, excludedFromRun } from './workflow.models';

function node(id: string, disabled = false): WorkflowNode {
  return { id, type: 'test.node', title: '', position: { x: 0, y: 0 }, values: {}, collapsed: false, disabled };
}

function edge(id: string, from: string, to: string) {
  return { id, sourceNode: from, sourcePort: 'out', targetNode: to, targetPort: 'in' };
}

/**
 * Switching a node off.
 *
 * The interesting part is not the node itself but everything behind it: a consumer whose producer
 * is off cannot run either, and reporting that as "missing input" would turn one deliberate click
 * into a screenful of errors.
 */
describe('excludedFromRun', () => {
  it('excludes nothing when every node is on', () => {
    const doc: WorkflowDoc = { nodes: [node('a'), node('b')], edges: [edge('e1', 'a', 'b')] };
    expect([...excludedFromRun(doc)]).toEqual([]);
  });

  it('excludes the whole downstream chain, however the edges are ordered', () => {
    const doc: WorkflowDoc = {
      nodes: [node('a', true), node('b'), node('c'), node('d')],
      // Deliberately not in topological order: one pass over this list would stop at `b`.
      edges: [edge('e3', 'c', 'd'), edge('e2', 'b', 'c'), edge('e1', 'a', 'b')],
    };
    expect([...excludedFromRun(doc)].sort()).toEqual(['a', 'b', 'c', 'd']);
  });

  it('leaves a branch that does not depend on the disabled node alone', () => {
    const doc: WorkflowDoc = {
      nodes: [node('a', true), node('b'), node('x'), node('y')],
      edges: [edge('e1', 'a', 'b'), edge('e2', 'x', 'y')],
    };
    expect([...excludedFromRun(doc)].sort()).toEqual(['a', 'b']);
  });

  it('terminates on a cycle instead of looping forever', () => {
    const doc: WorkflowDoc = {
      nodes: [node('a', true), node('b'), node('c')],
      edges: [edge('e1', 'a', 'b'), edge('e2', 'b', 'c'), edge('e3', 'c', 'b')],
    };
    expect([...excludedFromRun(doc)].sort()).toEqual(['a', 'b', 'c']);
  });
});
