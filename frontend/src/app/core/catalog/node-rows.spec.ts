import { describe, expect, it } from 'vitest';
import { NodeActionSpec, NodeInputSpec, NodeSpec, WidgetSpec } from './catalog.models';
import { ProbeResult, ProbeState } from './node-probe.service';
import {
  RowContext,
  buildRows,
  changedCount,
  groupRows,
  sectionRows,
  withoutReadouts,
} from './node-rows';
import { WorkflowDoc, WorkflowNode } from '../graph/workflow.models';
import { PortType } from '../types/port-type';

/**
 * The row model, which is the one piece of logic the node and the inspector share.
 *
 * Tested with plain objects rather than through either component, because that is the point of it
 * being a function: the rules below — a condition, the difference between "nobody said" and "none
 * of them", what counts as changed — are exactly the things that used to be answered twice.
 */

const TEXT: PortType = { kind: 'primitive', name: 'Text' };

function input(key: string, extra: Partial<NodeInputSpec> = {}): NodeInputSpec {
  return {
    key,
    label: key,
    type: TEXT,
    required: false,
    connectable: false,
    widget: { kind: 'text', placeholder: '', multiline: false, rows: 0, monospace: false, editor: false, library: '', libraryKey: '' },
    defaultValue: null,
    hint: null,
    advanced: false,
    group: '',
    showWhen: null,
    ...extra,
  };
}

function spec(id: string, inputs: readonly NodeInputSpec[], actions: readonly NodeActionSpec[] = []): NodeSpec {
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
    actions,
  };
}

function node(id: string, type: string, values: Record<string, unknown> = {}): WorkflowNode {
  return { id, type, title: '', position: { x: 0, y: 0 }, values, collapsed: false, disabled: false };
}

/** A context with nothing wired, nothing discovered and no probe having run. */
function context(overrides: Partial<RowContext> & Pick<RowContext, 'node' | 'spec'>): RowContext {
  return {
    doc: { nodes: [overrides.node], edges: [] },
    specsById: new Map([[overrides.spec.id, overrides.spec]]),
    probeState: () => 'idle' as ProbeState,
    probeResult: () => null,
    discoveredFor: () => [],
    ...overrides,
  };
}

const DROPDOWN = (options: readonly string[]): WidgetSpec => ({
  kind: 'dropdown',
  options: options.map((value) => ({ value, label: value.toUpperCase() })),
  optionsKey: '',
  allowCustom: false,
  narrowing: null,
});

describe('buildRows — conditions', () => {
  const sut = spec('test.node', [
    input('format', { widget: DROPDOWN(['text', 'json']), defaultValue: 'text' }),
    input('schema', { showWhen: { key: 'format', values: ['json'] } }),
    input('orphan', { showWhen: { key: 'missing', values: ['yes'] } }),
  ]);

  it('applies a condition against the sibling\'s stored value, and falls back to its default', () => {
    const atDefault = buildRows(context({ node: node('n1', sut.id), spec: sut }));
    expect(atDefault.map((row) => `${row.spec.key}:${row.applies}`)).toEqual([
      'format:true',
      'schema:false',
      'orphan:false',
    ]);

    const switched = buildRows(
      context({ node: node('n1', sut.id, { format: 'json' }), spec: sut }),
    );
    expect(switched[1].applies).toBe(true);
  });

  it('keeps an inapplicable row in the list rather than dropping it', () => {
    // A row that does not apply is still a row: the canvas needs every connector laid out, because
    // a connector that is not rendered loses its geometry (Foblex FF1006). Filtering is the call
    // site's job, and `sectionRows` is where it happens.
    const rows = buildRows(context({ node: node('n1', sut.id), spec: sut }));
    expect(rows).toHaveLength(3);
    expect(sectionRows(rows, false).map((row) => row.spec.key)).toEqual(['format']);
  });

  it('compares as text, so a toggle and a dropdown read the same', () => {
    const toggles = spec('test.toggle', [
      input('stream', { widget: { kind: 'toggle' }, defaultValue: true }),
      input('chunk', { showWhen: { key: 'stream', values: ['true'] } }),
    ]);
    expect(buildRows(context({ node: node('n1', toggles.id), spec: toggles }))[1].applies).toBe(true);
    expect(
      buildRows(context({ node: node('n1', toggles.id, { stream: false }), spec: toggles }))[1].applies,
    ).toBe(false);
  });
});

describe('buildRows — narrowing', () => {
  const model = spec('test.model', [input('capabilities', { defaultValue: ['text', 'json'] })]);
  const request = spec('test.request', [
    input('model', { connectable: true, widget: null }),
    input('format', {
      widget: {
        kind: 'dropdown',
        options: [{ value: 'text', label: 'Text' }],
        optionsKey: '',
        allowCustom: false,
        narrowing: { socket: 'model', listKey: 'capabilities', always: ['text'] },
      },
    }),
  ]);

  function wired(values: Record<string, unknown>): RowContext {
    const upstream = node('m1', model.id, values);
    const target = node('r1', request.id);
    const doc: WorkflowDoc = {
      nodes: [upstream, target],
      edges: [{ id: 'e1', sourceNode: 'm1', sourcePort: 'out', targetNode: 'r1', targetPort: 'model' }],
    };
    return context({
      node: target,
      spec: request,
      doc,
      specsById: new Map([
        [model.id, model],
        [request.id, request],
      ]),
    });
  }

  it('says nothing when nothing is wired in — which is not the same as saying none', () => {
    const rows = buildRows(context({ node: node('r1', request.id), spec: request }));
    expect(rows[1].allowed).toBeNull();
  });

  it('reads the upstream list, from a value or from its default', () => {
    expect([...(buildRows(wired({ capabilities: ['json'] }))[1].allowed ?? [])]).toEqual(['json']);
    // Nothing stored upstream: the declared default is what that node currently means.
    expect([...(buildRows(wired({}))[1].allowed ?? [])]).toEqual(['text', 'json']);
  });

  it('reads an empty upstream list as an empty set, not as no answer', () => {
    const allowed = buildRows(wired({ capabilities: [] }))[1].allowed;
    expect(allowed).not.toBeNull();
    expect(allowed?.size).toBe(0);
  });

  it('splits a comma-separated list, which is what a hand-written preset holds', () => {
    expect([...(buildRows(wired({ capabilities: 'text, json ,' }))[1].allowed ?? [])]).toEqual([
      'text',
      'json',
    ]);
  });
});

describe('buildRows — what counts as changed', () => {
  const sut = spec('test.node', [
    input('kept', { defaultValue: 'a' }),
    input('moved', { defaultValue: 'a' }),
    input('socket', { widget: null, connectable: true }),
    input('readout', { widget: { kind: 'display', style: 'line', unit: '' } }),
  ]);

  it('is stored-and-different, not merely stored', () => {
    const rows = buildRows(
      context({
        node: node('n1', sut.id, { kept: 'a', moved: 'b' }),
        spec: sut,
      }),
    );
    expect(rows[0].isChanged).toBe(false);
    expect(rows[1].isChanged).toBe(true);
    expect(changedCount(rows)).toBe(1);
  });

  it('never counts a socket or a readout, whatever is stored against it', () => {
    const rows = buildRows(
      context({
        node: node('n1', sut.id, { socket: 'x', readout: 'y' }),
        spec: sut,
      }),
    );
    expect(rows[2].isEditable).toBe(false);
    expect(rows[2].isChanged).toBe(false);
    // A `display` widget shows what a run produced. Resetting one is meaningless, and a reset
    // button that wrote to it would write into a readout.
    expect(rows[3].isEditable).toBe(false);
    expect(rows[3].isChanged).toBe(false);
    expect(changedCount(rows)).toBe(0);
  });

  it('compares by value, so an equal object is not a change', () => {
    const withMap = spec('test.map', [
      input('headers', {
        widget: { kind: 'keyvalue', keyPlaceholder: 'k', valuePlaceholder: 'v' },
        defaultValue: { a: '1' },
      }),
    ]);
    expect(
      buildRows(context({ node: node('n1', withMap.id, { headers: { a: '1' } }), spec: withMap }))[0]
        .isChanged,
    ).toBe(false);
    expect(
      buildRows(context({ node: node('n1', withMap.id, { headers: { a: '2' } }), spec: withMap }))[0]
        .isChanged,
    ).toBe(true);
  });
});

/**
 * What a preset is allowed to carry.
 *
 * A preset is a configuration, and what a gateway answered last Tuesday is not part of one.
 * Dropped onto a canvas it would arrive as stale facts that look freshly fetched — and it would put
 * them back after every reset, because a preset's values are what "default" then means.
 */
describe('withoutReadouts', () => {
  const sut = spec('test.info', [
    input('endpoint', { widget: null, connectable: true }),
    input('temperature', { defaultValue: 0.7 }),
    input('fetchedAt', { widget: { kind: 'display', style: 'line', unit: '' } }),
    input('capabilities', { widget: { kind: 'display', style: 'chips', unit: '' } }),
  ]);

  it('keeps the settings and drops every readout', () => {
    expect(
      withoutReadouts(sut, {
        temperature: 0.2,
        fetchedAt: '2026-09-16T10:00:00Z',
        capabilities: ['text'],
      }),
    ).toEqual({ temperature: 0.2 });
  });

  it('leaves a value whose key the descriptor no longer declares alone', () => {
    // Only declared readouts are dropped. An unknown key is a setting from another version of the
    // pack, and silently discarding one would lose a configuration nobody could get back.
    expect(withoutReadouts(sut, { legacy: 'keep me' })).toEqual({ legacy: 'keep me' });
  });

  it('reads a readout as unfetched rather than as its declared default', () => {
    // The engine sends `""` as the default because a descriptor field has to say something. Using
    // it as the value would make a node nobody has fetched claim every row is unpublished.
    const rows = buildRows(context({ node: node('n1', sut.id), spec: sut }));
    expect(rows[2].value).toBeUndefined();
    expect(rows[1].value).toBe(0.7);
  });
});

describe('buildRows — a list that discovers itself', () => {
  const action: NodeActionSpec = {
    key: 'models',
    label: 'List models',
    icon: 'search',
    appliesTo: 'model',
    kind: 'discover',
    automatic: true,
  };
  const sut = spec('test.model', [input('model', { widget: DROPDOWN([]) })], [action]);

  function withProbe(state: ProbeState, message: string, discovered: readonly string[] = []) {
    const result: ProbeResult = {
      ok: state === 'ok',
      message,
      details: [],
      options: new Map(),
      applied: [],
    };
    return buildRows(
      context({
        node: node('n1', sut.id),
        spec: sut,
        probeState: () => state,
        probeResult: () => result,
        discoveredFor: () => discovered.map((value) => ({ value, label: value })),
      }),
    )[0];
  }

  it('hands the automatic actions to the row that they feed', () => {
    expect(withProbe('idle', '').automatic.map((one) => one.key)).toEqual(['models']);
  });

  it('says what an empty list is waiting for, or why there is nothing in it', () => {
    expect(withProbe('running', '').discoveryNote).toBe('Asking the endpoint…');
    expect(withProbe('failed', 'No API key').discoveryNote).toBe('No API key');
    expect(withProbe('ok', 'The endpoint listed nothing.').discoveryNote).toBe(
      'The endpoint listed nothing.',
    );
    // An answer that arrived with options in it is not a note at all.
    expect(withProbe('ok', 'Found 12', ['a']).discoveryNote).toBe('');
  });
});

describe('buildRows — wiring', () => {
  const sut = spec('test.node', [input('prompt', { connectable: true })]);

  it('disables the widget of a wired input rather than removing it', () => {
    const target = node('n1', sut.id);
    const rows = buildRows(
      context({
        node: target,
        spec: sut,
        doc: {
          nodes: [target],
          edges: [{ id: 'e', sourceNode: 'x', sourcePort: 'o', targetNode: 'n1', targetPort: 'prompt' }],
        },
      }),
    );
    expect(rows[0].wired).toBe(true);
    expect(rows[0].widgetDisabled).toBe(true);
  });
});

describe('grouping', () => {
  const sut = spec('test.node', [
    input('temperature', { group: 'Sampling' }),
    input('model', {}),
    input('topP', { group: 'Sampling' }),
    input('stop', { group: 'Stopping' }),
    input('seed', {}),
  ]);

  it('puts the ungrouped rows first, then each group in declaration order', () => {
    const groups = groupRows(buildRows(context({ node: node('n1', sut.id), spec: sut })));
    expect(groups.map((group) => group.name)).toEqual(['', 'Sampling', 'Stopping']);
    expect(groups[0].rows.map((row) => row.spec.key)).toEqual(['model', 'seed']);
    expect(groups[1].rows.map((row) => row.spec.key)).toEqual(['temperature', 'topP']);
  });

  it('has no unnamed group at all when every row is grouped', () => {
    const allGrouped = spec('test.grouped', [input('a', { group: 'One' })]);
    expect(groupRows(buildRows(context({ node: node('n1', allGrouped.id), spec: allGrouped }))).map(
      (group) => group.name,
    )).toEqual(['One']);
  });
});

describe('sectionRows', () => {
  const sut = spec('test.node', [
    input('plain', {}),
    input('tuning', { advanced: true }),
    input('hidden', { advanced: true, showWhen: { key: 'plain', values: ['never'] } }),
  ]);

  it('splits on the fold and drops what does not apply', () => {
    const rows = buildRows(context({ node: node('n1', sut.id), spec: sut }));
    expect(sectionRows(rows, false).map((row) => row.spec.key)).toEqual(['plain']);
    expect(sectionRows(rows, true).map((row) => row.spec.key)).toEqual(['tuning']);
  });
});
