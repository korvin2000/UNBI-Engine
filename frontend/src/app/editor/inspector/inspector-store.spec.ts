import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { CatalogService } from '../../core/catalog/catalog.service';
import { NodeSpec } from '../../core/catalog/catalog.models';
import { GraphStore } from '../../core/graph/graph-store';
import { WorkflowDoc, newNode } from '../../core/graph/workflow.models';
import { DEFAULT_WIDTH, InspectorStore, MAX_WIDTH, MIN_WIDTH } from './inspector-store';

/** The store reaches the catalog only through the graph store, which asks it for `byId`. */
class StubCatalog {
  readonly byId = () => new Map<string, NodeSpec>();
}

const STORAGE_KEY = 'unbi.inspector.v1';

function start(): { store: InspectorStore; graph: GraphStore } {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: CatalogService, useClass: StubCatalog },
    ],
  });
  return { store: TestBed.inject(InspectorStore), graph: TestBed.inject(GraphStore) };
}

/** Two nodes on a canvas, so a selection can be single, empty or plural. */
function twoNodes(): WorkflowDoc {
  return { nodes: [newNode('test.a', { x: 0, y: 0 }), newNode('test.b', { x: 0, y: 0 })], edges: [] };
}

describe('InspectorStore — following the selection', () => {
  beforeEach(() => localStorage.removeItem(STORAGE_KEY));
  afterEach(() => TestBed.resetTestingModule());

  it('shows the node when exactly one is selected', () => {
    const { store, graph } = start();
    const doc = twoNodes();
    graph.load(doc);

    graph.select([doc.nodes[0].id]);
    TestBed.tick();
    expect(store.nodeId()).toBe(doc.nodes[0].id);

    graph.select([doc.nodes[1].id]);
    TestBed.tick();
    expect(store.nodeId()).toBe(doc.nodes[1].id);
  });

  it('keeps the last node when the selection empties or grows', () => {
    const { store, graph } = start();
    const doc = twoNodes();
    graph.load(doc);
    graph.select([doc.nodes[0].id]);
    TestBed.tick();

    // Clicking the canvas background to dismiss a menu empties the selection. Emptying the panel
    // with it would take away what the user was reading, for a gesture that meant nothing.
    graph.select([]);
    TestBed.tick();
    expect(store.nodeId()).toBe(doc.nodes[0].id);

    graph.select([doc.nodes[0].id, doc.nodes[1].id]);
    TestBed.tick();
    expect(store.nodeId()).toBe(doc.nodes[0].id);
    expect(store.selectionCount()).toBe(2);
  });

  it('stops following once pinned, and follows again when unpinned', () => {
    const { store, graph } = start();
    const doc = twoNodes();
    graph.load(doc);
    graph.select([doc.nodes[0].id]);
    TestBed.tick();

    store.togglePinned();
    graph.select([doc.nodes[1].id]);
    TestBed.tick();
    expect(store.nodeId()).toBe(doc.nodes[0].id);

    store.togglePinned();
    TestBed.tick();
    expect(store.nodeId()).toBe(doc.nodes[1].id);
  });

  it('closes when the node it shows is deleted', () => {
    const { store, graph } = start();
    const doc = twoNodes();
    graph.load(doc);
    store.show(doc.nodes[0].id);
    store.togglePinned();
    TestBed.tick();
    expect(store.open()).toBe(true);

    graph.load({ nodes: [doc.nodes[1]], edges: [] });
    TestBed.tick();
    expect(store.open()).toBe(false);
    expect(store.nodeId()).toBeNull();
    // Pinning is about a node, so it cannot outlive one.
    expect(store.pinned()).toBe(false);
  });

  it('opens on the selection when the toolbar toggles it with nothing shown yet', () => {
    const { store, graph } = start();
    const doc = twoNodes();
    graph.load(doc);
    graph.selection.set(new Set([doc.nodes[1].id, doc.nodes[0].id]));

    store.toggle();
    expect(store.open()).toBe(true);
    expect(store.nodeId()).not.toBeNull();

    store.toggle();
    expect(store.open()).toBe(false);
  });

  it('stays closed when the toolbar toggles it with nothing to show', () => {
    // The panel draws a node or draws nothing, so `open()` here would light the toolbar button and
    // announce a panel that is not on screen. The button is disabled instead.
    const { store, graph } = start();
    graph.load(twoNodes());

    expect(store.canOpen()).toBe(false);
    store.toggle();
    expect(store.open()).toBe(false);
    expect(store.nodeId()).toBeNull();
  });

  it('can open again as soon as something is selected', () => {
    const { store, graph } = start();
    const doc = twoNodes();
    graph.load(doc);

    graph.select([doc.nodes[0].id]);
    TestBed.tick();
    expect(store.canOpen()).toBe(true);
    store.toggle();
    expect(store.open()).toBe(true);
    expect(store.nodeId()).toBe(doc.nodes[0].id);
  });
});

describe('InspectorStore — geometry', () => {
  beforeEach(() => localStorage.removeItem(STORAGE_KEY));
  afterEach(() => TestBed.resetTestingModule());

  it('clamps the width to something usable', () => {
    const { store } = start();
    store.setWidth(10);
    expect(store.width()).toBe(MIN_WIDTH);
    store.setWidth(9000);
    expect(store.width()).toBe(MAX_WIDTH);
    store.setWidth(412.6);
    expect(store.width()).toBe(413);
  });

  it('persists the geometry, and reads it back into a fresh store', () => {
    const first = start();
    first.store.setWidth(480);
    first.store.toggleDocked();
    first.store.setFloating({ x: 40.4, y: 88.8 });
    TestBed.tick();

    TestBed.resetTestingModule();
    const second = start();
    expect(second.store.width()).toBe(480);
    expect(second.store.docked()).toBe(false);
    expect(second.store.floating()).toEqual({ x: 40, y: 89 });
  });

  it('ignores a stored value that is not the shape it expects', () => {
    // Storage is shared with every version of this app that ever ran here, so what comes back is
    // input, not state. A panel 9,000 pixels wide is not a symptom anybody could diagnose.
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ docked: 'yes', width: '900', floating: { x: 'left', y: null } }),
    );
    const { store } = start();
    expect(store.docked()).toBe(true);
    expect(store.width()).toBe(DEFAULT_WIDTH);
    expect(store.floating()).toEqual({ x: 96, y: 72 });
  });

  it('survives storage that holds something other than JSON', () => {
    localStorage.setItem(STORAGE_KEY, 'not json {');
    const { store } = start();
    expect(store.width()).toBe(DEFAULT_WIDTH);
  });
});

describe('InspectorStore — section memory', () => {
  beforeEach(() => localStorage.removeItem(STORAGE_KEY));
  afterEach(() => TestBed.resetTestingModule());

  it('remembers per node type, not per node', () => {
    const { store } = start();
    expect(store.isSectionOpen('llm.request', 'advanced', true)).toBe(true);
    store.toggleSection('llm.request', 'advanced', true);
    expect(store.isSectionOpen('llm.request', 'advanced', true)).toBe(false);
    // Another type is untouched, and still answers with its own default.
    expect(store.isSectionOpen('llm.model', 'advanced', true)).toBe(true);
    expect(store.isSectionOpen('llm.request', 'settings', false)).toBe(false);
  });
});
