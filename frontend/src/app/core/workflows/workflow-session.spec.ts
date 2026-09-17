import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { NodeSpec } from '../catalog/catalog.models';
import { CatalogService } from '../catalog/catalog.service';
import * as commands from '../graph/commands';
import { GraphStore } from '../graph/graph-store';
import { WorkflowDoc, newNode } from '../graph/workflow.models';
import { LibraryEntry } from './workflow-library.service';
import { WorkflowSession } from './workflow-session';

const entry: LibraryEntry = { id: 'Flow', name: 'Flow', favorite: false, updatedAt: '2026-09-17T00:00:00Z', size: 1 };

const doc: WorkflowDoc = { nodes: [newNode('util.preview', { x: 1, y: 2 })], edges: [] };

/** The graph store validates against the catalog; an empty one is enough to add and remove nodes. */
class StubCatalog {
  readonly byId = () => new Map<string, NodeSpec>();
}

/**
 * "Unsaved changes" as a reference comparison against the document that was last saved.
 *
 * Stated as tests because the whole scheme rests on one property of the graph store — every
 * command yields a new object and undo hands the old one back — and a store that started copying
 * documents on undo would make the canvas look dirty after Ctrl+Z put it back exactly as saved.
 */
describe('the workflow session', () => {
  let session: WorkflowSession;
  let graph: GraphStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: CatalogService, useClass: StubCatalog }],
    });
    session = TestBed.inject(WorkflowSession);
    graph = TestBed.inject(GraphStore);
  });

  it('starts clean, untitled and empty', () => {
    expect(session.dirty()).toBe(false);
    expect(session.current()).toBeNull();
    expect(session.name()).toBe('');
  });

  it('is clean right after opening and dirty after the first edit', () => {
    session.open(entry, doc);
    expect(session.dirty()).toBe(false);
    expect(session.name()).toBe('Flow');

    graph.dispatch(commands.addNode(newNode('util.preview', { x: 9, y: 9 }), 'Preview'));
    expect(session.dirty()).toBe(true);
  });

  it('is clean again when undo restores the saved document', () => {
    session.open(entry, doc);
    graph.dispatch(commands.addNode(newNode('util.preview', { x: 9, y: 9 }), 'Preview'));
    graph.undo();
    expect(session.dirty()).toBe(false);
    graph.redo();
    expect(session.dirty()).toBe(true);
  });

  it('marks what is on the canvas as saved under the entry it was written to', () => {
    session.openUntitled(doc);
    graph.dispatch(commands.addNode(newNode('util.preview', { x: 9, y: 9 }), 'Preview'));
    expect(session.current()).toBeNull();

    session.markSaved(entry);
    expect(session.dirty()).toBe(false);
    expect(session.current()).toEqual(entry);
  });

  it('follows a rename or a star of its own entry and ignores anyone else’s', () => {
    session.open(entry, doc);
    session.updateEntry({ ...entry, favorite: true });
    expect(session.current()?.favorite).toBe(true);

    session.updateEntry({ ...entry, id: 'Other', name: 'Other' });
    expect(session.current()?.name).toBe('Flow');

    // A rename hands over the old id, which is how the session knows it is the same workflow.
    session.updateEntry({ ...entry, id: 'Flow 2', name: 'Flow 2' }, 'Flow');
    expect(session.current()?.name).toBe('Flow 2');
    session.updateEntry({ ...entry, id: 'Flow', name: 'Flow' }, 'Flow 2');

    session.forgetEntry('Other');
    expect(session.current()).not.toBeNull();
    session.forgetEntry('Flow');
    expect(session.current()).toBeNull();
    expect(session.dirty()).toBe(false);
  });

  it('starts new with an empty, clean, untitled canvas', () => {
    session.open(entry, doc);
    graph.dispatch(commands.addNode(newNode('util.preview', { x: 9, y: 9 }), 'Preview'));
    session.startNew();
    expect(graph.doc().nodes).toHaveLength(0);
    expect(session.dirty()).toBe(false);
    expect(session.current()).toBeNull();
  });
});
