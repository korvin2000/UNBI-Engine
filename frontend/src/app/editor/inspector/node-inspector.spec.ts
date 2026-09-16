import { provideHttpClient } from '@angular/common/http';
import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { NodeInputSpec, NodeSpec, WidgetSpec } from '../../core/catalog/catalog.models';
import { CatalogService } from '../../core/catalog/catalog.service';
import { ENGINE_CONFIG, defaultEngineConfig } from '../../core/engine.config';
import * as commands from '../../core/graph/commands';
import { GraphStore } from '../../core/graph/graph-store';
import { WorkflowNode, newNode } from '../../core/graph/workflow.models';
import { PortType } from '../../core/types/port-type';
import { InspectorStore } from './inspector-store';
import { NodeInspector } from './node-inspector';

/**
 * The panel, rendered.
 *
 * What is worth covering here is the panel's own behaviour rather than the rows it draws — those
 * come from `buildRows`, which has its own tests. So: that a typed width cannot resize a node by
 * accident, that a filter belongs to the node it was typed on, and that the panel never shows less
 * than the node does.
 */

const TEXT: PortType = { kind: 'primitive', name: 'Text' };

const FIELD: WidgetSpec = {
  kind: 'text',
  placeholder: '',
  multiline: false,
  rows: 0,
  monospace: false,
  editor: false,
  library: '',
  libraryKey: '',
};

function input(key: string, extra: Partial<NodeInputSpec> = {}): NodeInputSpec {
  return {
    key,
    label: key,
    type: TEXT,
    required: false,
    connectable: false,
    widget: FIELD,
    defaultValue: '',
    hint: null,
    advanced: false,
    group: '',
    showWhen: null,
    ...extra,
  };
}

function spec(id: string, inputs: readonly NodeInputSpec[]): NodeSpec {
  return {
    id,
    label: id,
    category: 'Test',
    subcategory: '',
    icon: 'node',
    accent: 'slate',
    description: '',
    inputs,
    outputs: [],
    actions: [],
  };
}

/** The catalog, stubbed: the panel only ever asks it for the spec of the node it is showing. */
class StubCatalog {
  static specs: readonly NodeSpec[] = [];
  readonly byId = () => new Map(StubCatalog.specs.map((one) => [one.id, one]));
}

function start(specs: readonly NodeSpec[]): {
  fixture: ComponentFixture<NodeInspector>;
  graph: GraphStore;
  store: InspectorStore;
} {
  StubCatalog.specs = specs;
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideHttpClient(),
      { provide: ENGINE_CONFIG, useFactory: defaultEngineConfig },
      { provide: CatalogService, useClass: StubCatalog },
    ],
  });
  return {
    fixture: TestBed.createComponent(NodeInspector),
    graph: TestBed.inject(GraphStore),
    store: TestBed.inject(InspectorStore),
  };
}

describe('NodeInspector — the node width field', () => {
  let fixture: ComponentFixture<NodeInspector>;
  let graph: GraphStore;
  let node: WorkflowNode;

  beforeEach(() => {
    const started = start([spec('test.one', [input('prompt')])]);
    fixture = started.fixture;
    graph = started.graph;
    node = newNode('test.one', { x: 0, y: 0 });
    graph.load({ nodes: [node], edges: [] });
    started.store.show(node.id);
    fixture.detectChanges();
  });

  afterEach(() => TestBed.resetTestingModule());

  function field(): HTMLInputElement {
    return fixture.nativeElement.querySelector('.width__input') as HTMLInputElement;
  }

  function commit(value: string): void {
    const input = field();
    input.value = value;
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('commits a typed width', () => {
    commit('480');
    expect(graph.node(node.id)?.width).toBe(480);
    expect(graph.lastAction()).toBe('Resize node');
  });

  it('treats an emptied field as no change, and puts the width back', () => {
    // `Number('')` is 0, which clamps to the minimum: selecting the field's contents and pressing
    // Delete on the way to typing a new number would otherwise snap the node down to 252 and put a
    // step on the history stack.
    graph.dispatch(commands.resizeNodes([node.id], 480));
    fixture.detectChanges();
    const before = graph.doc();

    commit('');

    expect(graph.doc()).toBe(before);
    expect(graph.node(node.id)?.width).toBe(480);
    // The [value] binding's expression has not changed, so nothing else would redraw the field.
    expect(field().value).toBe('480');
  });

  it('leaves a node with no width of its own without one', () => {
    // An explicit 252 is not the same as no opinion: the second still follows a later change to
    // the default width, which is the whole reason Reset clears the key.
    commit('');
    expect(graph.node(node.id) && 'width' in graph.node(node.id)!).toBe(false);
    expect(graph.canUndo()).toBe(false);
  });
});

describe('NodeInspector — the filter', () => {
  /** Past ten rows the header grows a filter; a shorter node has no control to clear one with. */
  const long = spec(
    'test.long',
    Array.from({ length: 12 }, (_, index) => input(`setting${index}`)),
  );
  const short = spec('test.short', [input('directory'), input('pattern')]);

  let fixture: ComponentFixture<NodeInspector>;
  let store: InspectorStore;
  let nodes: readonly WorkflowNode[];

  beforeEach(() => {
    const started = start([long, short]);
    fixture = started.fixture;
    store = started.store;
    nodes = [newNode('test.long', { x: 0, y: 0 }), newNode('test.short', { x: 0, y: 0 })];
    started.graph.load({ nodes: [...nodes], edges: [] });
    store.show(nodes[0].id);
    fixture.detectChanges();
  });

  afterEach(() => TestBed.resetTestingModule());

  function rows(): number {
    return fixture.nativeElement.querySelectorAll('.row').length;
  }

  function search(): HTMLInputElement | null {
    return fixture.nativeElement.querySelector('.tools__search input') as HTMLInputElement | null;
  }

  function type(text: string): void {
    const box = search()!;
    box.value = text;
    box.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('filters the node it was typed on', () => {
    expect(rows()).toBe(12);
    type('setting1');
    // setting1, setting10, setting11.
    expect(rows()).toBe(3);
  });

  it('forgets the filter when the panel moves to another node', () => {
    type('setting1');
    expect(rows()).toBe(3);

    store.show(nodes[1].id);
    fixture.detectChanges();

    // Two rows, not "Nothing here matches": a short node draws no filter box, so a query that
    // survived the switch would be one nothing on screen could clear.
    expect(rows()).toBe(2);
    expect(search()).toBeNull();
    expect(fixture.nativeElement.querySelector('.part__hidden')).toBeNull();
  });

  it('forgets "Modified only" too', () => {
    const only = fixture.nativeElement.querySelector('.tools__only') as HTMLButtonElement;
    only.click();
    fixture.detectChanges();
    expect(rows()).toBe(0);

    store.show(nodes[1].id);
    fixture.detectChanges();
    expect(rows()).toBe(2);
  });
});

describe('NodeInspector — what the panel shows', () => {
  afterEach(() => TestBed.resetTestingModule());

  function render(inputs: readonly NodeInputSpec[]): ComponentFixture<NodeInspector> {
    const started = start([spec('test.one', inputs)]);
    const node = newNode('test.one', { x: 0, y: 0 });
    started.graph.load({ nodes: [node], edges: [] });
    started.store.show(node.id);
    started.fixture.detectChanges();
    return started.fixture;
  }

  it('draws a control this build cannot draw, exactly as the node does', () => {
    // A node pack newer than the editor. The node shows the placeholder; a panel that dropped the
    // row would disagree with it about what the node even has.
    const fixture = render([input('mystery', { widget: { kind: 'unsupported', declared: 'gauge' } })]);

    expect(fixture.nativeElement.querySelectorAll('.row')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('.unsupported')?.textContent).toContain('gauge');
    expect(fixture.nativeElement.querySelector('.empty')).toBeNull();
  });

  it('gives a toggle row its width back, and leaves the other rows lined up', () => {
    const fixture = render([
      input('strict', { widget: { kind: 'toggle' } }),
      input('prompt'),
    ]);

    const [toggle, field] = fixture.nativeElement.querySelectorAll('.row') as NodeListOf<HTMLElement>;
    expect(toggle.classList.contains('row--switch')).toBe(true);
    expect(field.classList.contains('row--switch')).toBe(false);
  });

  it('says so when a node has nothing to set at all', () => {
    const fixture = render([input('data', { widget: null })]);
    expect(fixture.nativeElement.querySelector('.empty')).not.toBeNull();
  });
});
