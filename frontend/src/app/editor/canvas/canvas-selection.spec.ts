import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { NodeSpec } from '../../core/catalog/catalog.models';
import { CatalogService } from '../../core/catalog/catalog.service';
import { GraphStore } from '../../core/graph/graph-store';
import { CanvasSelection } from './canvas-selection';

/** The graph store reaches the catalog only for validation, which these tests do not exercise. */
class StubCatalog {
  readonly byId = () => new Map<string, NodeSpec>();
}

/**
 * The selection's two owners.
 *
 * The store's copy is what Delete and Ctrl+D read; the flow library's copy is what draws the
 * border. A programmatic selection that wrote only the first is how the canvas ends up
 * highlighting one node while the next keystroke removes another.
 */
describe('CanvasSelection', () => {
  let selection: CanvasSelection;
  let graph: GraphStore;
  let painted: string[][];

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection(), { provide: CatalogService, useClass: StubCatalog }],
    });
    selection = TestBed.inject(CanvasSelection);
    graph = TestBed.inject(GraphStore);
    painted = [];
  });

  afterEach(() => TestBed.resetTestingModule());

  it('writes the store and the canvas with the same nodes', () => {
    selection.register((ids) => painted.push([...ids]));

    selection.select(['a', 'b']);

    expect([...graph.selection()]).toEqual(['a', 'b']);
    expect(painted).toEqual([['a', 'b']]);
  });

  it('still selects before a canvas has registered, and stops painting once it is gone', () => {
    // The store is the half that must always be right: it is what Delete acts on.
    selection.select(['a']);
    expect([...graph.selection()]).toEqual(['a']);

    const forget = selection.register((ids) => painted.push([...ids]));
    forget();
    selection.select(['b']);

    expect([...graph.selection()]).toEqual(['b']);
    expect(painted).toEqual([]);
  });
});
