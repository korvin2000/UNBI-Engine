import { describe, expect, it } from 'vitest';
import { PortType, describeType, parsePortType, portColourKey, typeKey } from './port-type';

const TEXT: PortType = { kind: 'primitive', name: 'Text' };
const FILE_REF: PortType = {
  kind: 'struct',
  name: 'FileRef',
  fields: { path: TEXT, size: { kind: 'primitive', name: 'Number' } },
};

describe('portColourKey', () => {
  /**
   * The bug this exists to prevent: `typeKey` returns `FileRef[]` for a list, and a class of
   * `port-FileRef[]` is not a valid CSS selector — the whole rule is dropped and every list port
   * silently loses its colour, with nothing in the console to say so.
   */
  it('never produces a character that is illegal in a class name', () => {
    const types: PortType[] = [
      TEXT,
      FILE_REF,
      { kind: 'list', element: FILE_REF },
      { kind: 'list', element: { kind: 'list', element: TEXT } },
      { kind: 'union', members: [TEXT, { kind: 'primitive', name: 'Number' }] },
      { kind: 'any' },
      { kind: 'primitive', name: 'Weird Name/With:Punctuation' },
    ];

    for (const type of types) {
      expect(portColourKey(type), describeType(type)).toMatch(/^[A-Za-z0-9_-]+$/);
    }
  });

  it('gives a list the colour of its element, so one subject reads as one colour', () => {
    expect(portColourKey({ kind: 'list', element: FILE_REF })).toBe('FileRef');
    expect(portColourKey(FILE_REF)).toBe('FileRef');
  });

  it('keeps distinct subjects distinct', () => {
    expect(portColourKey(TEXT)).not.toBe(portColourKey(FILE_REF));
  });
});

describe('typeKey', () => {
  it('does distinguish a list from its element, unlike the colour key', () => {
    expect(typeKey({ kind: 'list', element: FILE_REF })).toBe('FileRef[]');
    expect(typeKey(FILE_REF)).toBe('FileRef');
  });
});

describe('describeType', () => {
  it('reads the way a person would say it', () => {
    expect(describeType({ kind: 'list', element: FILE_REF })).toBe('FileRef[]');
    expect(describeType({ kind: 'union', members: [TEXT, { kind: 'primitive', name: 'Number' }] }))
      .toBe('Text | Number');
    expect(describeType({ kind: 'any' })).toBe('Any');
  });
});

describe('parsePortType', () => {
  it('refuses a shape it does not recognise rather than guessing', () => {
    expect(() => parsePortType({ kind: 'nonsense' })).toThrow(/Unknown port type kind/);
    expect(() => parsePortType(null)).toThrow(/Not a port type/);
    expect(() => parsePortType({ kind: 'primitive' })).toThrow(/missing 'name'/);
    expect(() => parsePortType({ kind: 'list' })).toThrow();
    expect(() => parsePortType({ kind: 'struct', name: 'X' })).toThrow(/fields/);
    expect(() => parsePortType({ kind: 'union', members: 'no' })).toThrow(/members/);
  });

  it('round trips a nested type', () => {
    const wire = {
      kind: 'list',
      element: {
        kind: 'struct',
        name: 'FileRef',
        fields: { path: { kind: 'primitive', name: 'Text' } },
      },
    };
    expect(parsePortType(wire)).toEqual({
      kind: 'list',
      element: { kind: 'struct', name: 'FileRef', fields: { path: TEXT } },
    });
  });
});
