import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { beforeEach, describe, expect, it } from 'vitest';
import { CatalogService } from '../catalog/catalog.service';
import { NodeSpec } from '../catalog/catalog.models';
import { PortType } from '../types/port-type';
import * as commands from './commands';
import { GraphStore } from './graph-store';
import { WorkflowNode, connectorId } from './workflow.models';

const TEXT: PortType = { kind: 'primitive', name: 'Text' };
const NUMBER: PortType = { kind: 'primitive', name: 'Number' };
const TEXT_LIST: PortType = { kind: 'list', element: TEXT };

const SOURCE: NodeSpec = {
  id: 'test.source',
  label: 'Source',
  category: 'Test',
  subcategory: '',
  icon: 'node',
  accent: 'amber',
  description: '',
  inputs: [],
  outputs: [{ key: 'files', label: 'Files', type: TEXT_LIST, hint: null }],
};

const SINK: NodeSpec = {
  id: 'test.sink',
  label: 'Sink',
  category: 'Test',
  subcategory: '',
  icon: 'node',
  accent: 'sky',
  description: '',
  inputs: [
    {
      key: 'files',
      label: 'Files',
      type: TEXT_LIST,
      required: true,
      connectable: true,
      widget: null,
      defaultValue: null,
      hint: null,
    },
    {
      key: 'count',
      label: 'Count',
      type: NUMBER,
      required: false,
      connectable: true,
      widget: { kind: 'number', min: 0, max: 10, step: 1, unit: '' },
      defaultValue: 1,
      hint: null,
    },
  ],
  outputs: [{ key: 'done', label: 'Done', type: TEXT, hint: null }],
};

/** A catalog stub — the store only ever reads `byId`. */
class StubCatalog {
  readonly byId = () => new Map<string, NodeSpec>([[SOURCE.id, SOURCE], [SINK.id, SINK]]);
}

function node(id: string, type: string): WorkflowNode {
  return { id, type, position: { x: 0, y: 0 }, values: {}, collapsed: false };
}

describe('GraphStore', () => {
  let store: GraphStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: CatalogService, useClass: StubCatalog },
      ],
    });
    store = TestBed.inject(GraphStore);
  });

  describe('history', () => {
    it('undoes and redoes a change', () => {
      store.dispatch(commands.addNode(node('a', 'test.source'), 'Source'));
      expect(store.nodeCount()).toBe(1);

      store.undo();
      expect(store.nodeCount()).toBe(0);
      expect(store.canRedo()).toBe(true);

      store.redo();
      expect(store.nodeCount()).toBe(1);
    });

    it('has nothing to undo when nothing has happened', () => {
      expect(store.canUndo()).toBe(false);
      store.undo();
      expect(store.nodeCount()).toBe(0);
    });

    it('drops the redo stack once a new change is made', () => {
      store.dispatch(commands.addNode(node('a', 'test.source'), 'Source'));
      store.undo();
      expect(store.canRedo()).toBe(true);

      store.dispatch(commands.addNode(node('b', 'test.sink'), 'Sink'));
      expect(store.canRedo()).toBe(false);
    });

    it('does not record a command that changed nothing', () => {
      store.dispatch(commands.removeNodes([]));
      expect(store.canUndo()).toBe(false);
    });

    it('applies drag positions without flooding the history', () => {
      store.dispatch(commands.addNode(node('a', 'test.source'), 'Source'));
      const historyDepth = store.canUndo();

      store.applyWithoutHistory(commands.moveNodes(new Map([['a', { x: 10, y: 20 }]])));
      store.applyWithoutHistory(commands.moveNodes(new Map([['a', { x: 30, y: 40 }]])));

      expect(store.node('a')?.position).toEqual({ x: 30, y: 40 });
      expect(store.canUndo()).toBe(historyDepth);
      store.undo();
      expect(store.nodeCount()).toBe(0);
    });
  });

  describe('editing', () => {
    beforeEach(() => {
      store.dispatch(commands.addNode(node('a', 'test.source'), 'Source'));
      store.dispatch(commands.addNode(node('b', 'test.sink'), 'Sink'));
    });

    it('removes the edges of a deleted node', () => {
      store.dispatch(
        commands.connect({ id: 'e1', sourceNode: 'a', sourcePort: 'files', targetNode: 'b', targetPort: 'files' }),
      );
      expect(store.edgeCount()).toBe(1);

      store.dispatch(commands.removeNodes(['a']));
      expect(store.edgeCount()).toBe(0);
    });

    it('replaces an existing edge when a second one is dropped on the same input', () => {
      store.dispatch(commands.addNode(node('c', 'test.source'), 'Source'));
      store.dispatch(
        commands.connect({ id: 'e1', sourceNode: 'a', sourcePort: 'files', targetNode: 'b', targetPort: 'files' }),
      );
      store.dispatch(
        commands.connect({ id: 'e2', sourceNode: 'c', sourcePort: 'files', targetNode: 'b', targetPort: 'files' }),
      );

      expect(store.doc().edges).toHaveLength(1);
      expect(store.doc().edges[0].sourceNode).toBe('c');
    });

    it('stores a widget value against the node', () => {
      store.dispatch(commands.setInputValue('b', 'count', 7));
      expect(store.node('b')?.values['count']).toBe(7);
    });
  });

  describe('validation', () => {
    it('accepts a well-formed graph', () => {
      store.dispatch(commands.addNode(node('a', 'test.source'), 'Source'));
      store.dispatch(commands.addNode(node('b', 'test.sink'), 'Sink'));
      store.dispatch(
        commands.connect({ id: 'e1', sourceNode: 'a', sourcePort: 'files', targetNode: 'b', targetPort: 'files' }),
      );

      expect(store.issues()).toHaveLength(0);
      expect(store.isRunnable()).toBe(true);
    });

    it('reports a required input that nothing supplies', () => {
      store.dispatch(commands.addNode(node('b', 'test.sink'), 'Sink'));

      expect(store.issues().map((issue) => issue.message).join()).toContain('needs a value for Files');
      expect(store.isRunnable()).toBe(false);
    });

    it('reports an incompatible connection', () => {
      store.dispatch(commands.addNode(node('a', 'test.source'), 'Source'));
      store.dispatch(commands.addNode(node('b', 'test.sink'), 'Sink'));
      store.dispatch(
        commands.connect({ id: 'e1', sourceNode: 'a', sourcePort: 'files', targetNode: 'b', targetPort: 'count' }),
      );

      expect(store.issuesByEdge().get('e1')?.[0].message).toContain('expected a single Number');
    });

    it('detects a cycle and names the nodes in it', () => {
      store.dispatch(commands.addNode(node('a', 'test.sink'), 'Sink'));
      store.dispatch(commands.addNode(node('b', 'test.sink'), 'Sink'));
      store.dispatch(
        commands.connect({ id: 'e1', sourceNode: 'a', sourcePort: 'done', targetNode: 'b', targetPort: 'files' }),
      );
      store.dispatch(
        commands.connect({ id: 'e2', sourceNode: 'b', sourcePort: 'done', targetNode: 'a', targetPort: 'files' }),
      );

      expect(store.issues().map((issue) => issue.message).join()).toContain('form a loop');
    });

    it('does not report a loop for a diamond, which is acyclic', () => {
      store.dispatch(commands.addNode(node('root', 'test.source'), 'Source'));
      store.dispatch(commands.addNode(node('left', 'test.sink'), 'Sink'));
      store.dispatch(commands.addNode(node('right', 'test.sink'), 'Sink'));
      store.dispatch(commands.addNode(node('join', 'test.sink'), 'Sink'));
      store.dispatch(
        commands.connect({ id: 'e1', sourceNode: 'root', sourcePort: 'files', targetNode: 'left', targetPort: 'files' }),
      );
      store.dispatch(
        commands.connect({ id: 'e2', sourceNode: 'root', sourcePort: 'files', targetNode: 'right', targetPort: 'files' }),
      );
      store.dispatch(
        commands.connect({ id: 'e3', sourceNode: 'left', sourcePort: 'done', targetNode: 'join', targetPort: 'files' }),
      );

      expect(store.issues().map((issue) => issue.message).join()).not.toContain('loop');
    });
  });
});

describe('connectorId', () => {
  it('round trips a node and port', () => {
    expect(connectorId.parse(connectorId.output('n1', 'files'))).toEqual({
      nodeId: 'n1',
      direction: 'out',
      portKey: 'files',
    });
    expect(connectorId.parse(connectorId.input('n1', 'files'))).toEqual({
      nodeId: 'n1',
      direction: 'in',
      portKey: 'files',
    });
  });

  it('rejects anything that is not a connector id', () => {
    expect(connectorId.parse('nonsense')).toBeNull();
  });
});
