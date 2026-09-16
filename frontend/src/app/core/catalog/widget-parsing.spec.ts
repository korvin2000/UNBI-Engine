import { describe, expect, it } from 'vitest';
import { parseCatalog } from './catalog.models';
import { parseOptionCatalogs } from './option-catalog.service';

/**
 * Widget parsing, where the frontend and a served descriptor meet.
 *
 * The catalog is data from another process, so the fields it carries can be missing — an older
 * engine, a hand-written fixture, a node pack built against an earlier contract. Defaulting once,
 * here, is what keeps every template free of `?? false`, and this is where that promise is checked.
 */
describe('widget parsing', () => {
  function widgetOf(widget: unknown): unknown {
    const catalog = parseCatalog({
      nodes: [
        {
          id: 'test.node',
          label: 'Test',
          inputs: [
            {
              key: 'field',
              label: 'Field',
              type: { kind: 'primitive', name: 'Text' },
              required: false,
              connectable: false,
              widget,
              default: null,
              hint: null,
            },
          ],
          outputs: [],
        },
      ],
    });
    return catalog[0].inputs[0].widget;
  }

  it('reads a profile reference and a credential name', () => {
    expect(widgetOf({ kind: 'profile', schema: 'llm.endpoint' })).toEqual({ kind: 'profile', schema: 'llm.endpoint' });
    expect(widgetOf({ kind: 'credential' })).toEqual({ kind: 'credential' });
  });

  it('reads a text field with its height and font', () => {
    expect(widgetOf({ kind: 'text', placeholder: 'p', multiline: true, rows: 8, monospace: true }))
      .toMatchObject({ kind: 'text', placeholder: 'p', multiline: true, rows: 8, monospace: true });
  });

  it('defaults the fields an older engine would not send', () => {
    expect(widgetOf({ kind: 'text', placeholder: 'p', multiline: false }))
      .toEqual({
        kind: 'text',
        placeholder: 'p',
        multiline: false,
        rows: 0,
        monospace: false,
        editor: false,
        library: '',
        libraryKey: '',
      });
    expect(widgetOf({ kind: 'number', min: 0, max: 1, step: 1, unit: '' }))
      .toMatchObject({ optional: false, blankLabel: 'unset' });
    expect(widgetOf({ kind: 'dropdown', options: [] }))
      .toMatchObject({ optionsKey: '', allowCustom: false, narrowing: null });
  });

  it('carries the editor and its template library', () => {
    expect(
      widgetOf({
        kind: 'text',
        placeholder: 'Ask something…',
        multiline: true,
        rows: 4,
        monospace: false,
        editor: true,
        library: 'llm.prompt',
        libraryKey: 'template',
      }),
    ).toMatchObject({ editor: true, library: 'llm.prompt', libraryKey: 'template' });
  });

  it('names what an empty optional number means, in the node\'s own words', () => {
    expect(
      widgetOf({ kind: 'number', min: 0, max: 9, step: 1, unit: 'tok', optional: true, blankLabel: 'no limit' }),
    ).toMatchObject({ optional: true, blankLabel: 'no limit' });
  });

  it('reads a narrowing rule, and refuses a half-written one', () => {
    expect(
      widgetOf({
        kind: 'dropdown',
        options: [{ value: 'text', label: 'Text' }],
        narrowing: { socket: 'model', listKey: 'capabilities', always: ['text'] },
      }),
    ).toMatchObject({ narrowing: { socket: 'model', listKey: 'capabilities', always: ['text'] } });

    // A rule missing either half names nothing, and a rule that names nothing must not silently
    // become "hide every option" — it becomes no rule at all.
    expect(widgetOf({ kind: 'dropdown', options: [{ value: 'a', label: 'A' }], narrowing: { socket: 'model' } }))
      .toMatchObject({ narrowing: null });
  });

  it('reads a file list, which is a list of paths rather than a separated string', () => {
    expect(widgetOf({ kind: 'filelist', extensions: ['md', 'txt'] }))
      .toEqual({ kind: 'filelist', extensions: ['md', 'txt'] });
    expect(widgetOf({ kind: 'filelist' })).toEqual({ kind: 'filelist', extensions: [] });
  });

  it('carries an options key so the editor knows to merge in what the engine serves', () => {
    expect(
      widgetOf({
        kind: 'dropdown',
        options: [{ value: 'a', label: 'A' }],
        optionsKey: 'llm.credentials',
        allowCustom: true,
      }),
    ).toEqual({
      kind: 'dropdown',
      options: [{ value: 'a', label: 'A' }],
      optionsKey: 'llm.credentials',
      allowCustom: true,
      narrowing: null,
    });
  });

  it('reads the multi-select and key/value kinds', () => {
    expect(widgetOf({ kind: 'multiselect', options: [{ value: 'v', label: 'L' }] }))
      .toEqual({ kind: 'multiselect', options: [{ value: 'v', label: 'L' }] });
    expect(widgetOf({ kind: 'keyvalue', keyPlaceholder: 'H', valuePlaceholder: 'V' }))
      .toEqual({ kind: 'keyvalue', keyPlaceholder: 'H', valuePlaceholder: 'V' });
  });

  it('treats a socket with no widget as a socket', () => {
    expect(widgetOf(null)).toBeNull();
    expect(widgetOf(undefined)).toBeNull();
  });

  /**
   * Every kind the editor can draw has its own arm.
   *
   * There used to be a `default` that returned the raw object, which meant a kind with nothing to
   * default — a toggle, a folder picker — was never actually parsed, and a `file` widget arrived
   * with `extensions: undefined`. Now that the default arm is a visible "unsupported" placeholder,
   * a missing arm is a control the user cannot reach, so this enumerates the lot.
   */
  it('parses every kind it can draw, rather than leaving any to the fallback', () => {
    expect(widgetOf({ kind: 'toggle' })).toEqual({ kind: 'toggle' });
    expect(widgetOf({ kind: 'slider', min: 0, max: 2, step: 0.1 }))
      .toEqual({ kind: 'slider', min: 0, max: 2, step: 0.1 });
    expect(widgetOf({ kind: 'directory' })).toEqual({ kind: 'directory' });
    expect(widgetOf({ kind: 'file', extensions: ['md'] }))
      .toEqual({ kind: 'file', extensions: ['md'] });
    expect(widgetOf({ kind: 'file' })).toEqual({ kind: 'file', extensions: [] });
  });

  it('carries a readout through, for the row model to mark as not editable', () => {
    expect(widgetOf({ kind: 'display', style: 'chips', unit: 'tok' }))
      .toEqual({ kind: 'display', style: 'chips', unit: 'tok' });
    // An unnamed style is a line, which is the shape that cannot look broken.
    expect(widgetOf({ kind: 'display' })).toEqual({ kind: 'display', style: 'line', unit: '' });
  });

  it('names a kind this build has never heard of instead of pretending it is one', () => {
    // Kept and named: a control that renders as nothing is indistinguishable from a setting the
    // node does not have, which is how a run ends up behaving in a way the screen cannot explain.
    expect(widgetOf({ kind: 'hologram' })).toEqual({ kind: 'unsupported', declared: 'hologram' });
    expect(widgetOf({})).toEqual({ kind: 'unsupported', declared: '' });
  });
});

/**
 * The three descriptor fields that decide what a node *shows*, rather than what it holds.
 *
 * All three default to "show it, plainly" when the engine says nothing, which is what lets an older
 * engine keep working against this editor: the worst an unaware backend can produce is a node with
 * everything on screen at once, which is exactly what it produced before these existed.
 */
describe('node presentation', () => {
  function inputOf(extra: Record<string, unknown>) {
    const catalog = parseCatalog({
      nodes: [
        {
          id: 'test.node',
          label: 'Test',
          inputs: [
            {
              key: 'field',
              label: 'Field',
              type: { kind: 'primitive', name: 'Text' },
              required: false,
              connectable: false,
              widget: { kind: 'text', placeholder: '', multiline: false },
              default: null,
              hint: null,
              ...extra,
            },
          ],
          outputs: [],
        },
      ],
    });
    return catalog[0].inputs[0];
  }

  it('reads the advanced flag, defaulting to shown', () => {
    expect(inputOf({}).advanced).toBe(false);
    expect(inputOf({ advanced: true }).advanced).toBe(true);
  });

  it('reads a condition, and treats one with no values as no condition', () => {
    expect(inputOf({}).showWhen).toBeNull();
    expect(inputOf({ showWhen: { key: 'responseFormat', values: ['json_schema'] } }).showWhen)
      .toEqual({ key: 'responseFormat', values: ['json_schema'] });
    // A condition that can never be true would hide the input for good, which is never what an
    // engine meant to say — so it is read as no condition at all.
    expect(inputOf({ showWhen: { key: 'responseFormat', values: [] } }).showWhen).toBeNull();
  });

  it('reads the actions a node declares, and defaults a kind it does not recognise to a check', () => {
    const catalog = parseCatalog({
      nodes: [
        {
          id: 'test.node',
          label: 'Test',
          inputs: [],
          outputs: [],
          actions: [
            { key: 'test', label: 'Test this connection', icon: 'bulb', appliesTo: '', kind: 'check' },
            { key: 'models', label: 'List models', icon: 'search', appliesTo: 'model', kind: 'discover' },
            { key: 'odd', label: 'Odd', icon: 'bolt', appliesTo: '', kind: 'something-new' },
          ],
        },
      ],
    });

    expect(catalog[0].actions).toEqual([
      { key: 'test', label: 'Test this connection', icon: 'bulb', appliesTo: '', kind: 'check', automatic: false },
      { key: 'models', label: 'List models', icon: 'search', appliesTo: 'model', kind: 'discover', automatic: false },
      // Unknown means "light an indicator" rather than "write into my fields", which is the
      // conservative half of the pair.
      { key: 'odd', label: 'Odd', icon: 'bolt', appliesTo: '', kind: 'check', automatic: false },
    ]);
  });

  it('a node from an engine that has never heard of actions simply has none', () => {
    const catalog = parseCatalog({
      nodes: [{ id: 'test.node', label: 'Test', inputs: [], outputs: [] }],
    });
    expect(catalog[0].actions).toEqual([]);
  });
});

describe('option catalogs', () => {
  it('reads the value/label pairs the engine serves', () => {
    const catalogs = parseOptionCatalogs({
      'llm.credentials': [{ value: 'openrouter', label: 'openrouter (environment)' }],
    });
    expect(catalogs.get('llm.credentials')).toEqual([
      { value: 'openrouter', label: 'openrouter (environment)' },
    ]);
  });

  it('survives a response that is missing, empty or the wrong shape', () => {
    expect(parseOptionCatalogs(null).size).toBe(0);
    expect(parseOptionCatalogs({}).size).toBe(0);
    expect(parseOptionCatalogs({ 'a.key': 'not an array' }).size).toBe(0);
  });
});
