import { describe, expect, it } from 'vitest';
import { PortType } from '../types/port-type';
import {
  NO_COMPATIBLE_TARGET,
  NodeInputSpec,
  NodeOutputSpec,
  NodeSpec,
  compatibleTargetTypes,
} from './catalog.models';

const TEXT: PortType = { kind: 'primitive', name: 'Text' };
const NUMBER: PortType = { kind: 'primitive', name: 'Number' };
const ANY: PortType = { kind: 'any' };
const TEXT_LIST: PortType = { kind: 'list', element: TEXT };

function input(key: string, type: PortType, connectable = true): NodeInputSpec {
  return {
    key,
    label: key,
    type,
    required: false,
    connectable,
    widget: null,
    defaultValue: null,
    hint: null,
    advanced: false,
    showWhen: null,
  };
}

function output(key: string, type: PortType): NodeOutputSpec {
  return { key, label: key, type, hint: null };
}

function spec(id: string, inputs: NodeInputSpec[], outputs: NodeOutputSpec[]): NodeSpec {
  return {
    id,
    label: id,
    category: 'Test',
    subcategory: '',
    icon: 'node',
    accent: 'slate',
    description: '',
    inputs,
    outputs,
    actions: [],
  };
}

/**
 * The drag-time allow-list.
 *
 * This is the rule that stops a wire from being dropped onto a port of a different type, so the
 * cases below are the ones a user can actually see going wrong on the canvas.
 */
describe('compatibleTargetTypes', () => {
  const catalog = [
    spec('a', [], [output('text', TEXT), output('number', NUMBER), output('files', TEXT_LIST)]),
    spec('b', [input('text', TEXT), input('files', TEXT_LIST), input('anything', ANY)], []),
    // A setting is drawn in the node but never accepts an edge, so its type must not appear as a
    // legal destination for anything.
    spec('c', [input('setting', NUMBER, false)], []),
  ];

  const reachable = compatibleTargetTypes(catalog);

  it('lets a type reach its own kind', () => {
    expect(reachable.get('Text')).toContain('Text');
    expect(reachable.get('Text[]')).toContain('Text[]');
  });

  it('does not let a scalar reach a list of itself, or the reverse', () => {
    expect(reachable.get('Text')).not.toContain('Text[]');
    expect(reachable.get('Text[]')).not.toContain('Text');
  });

  it('lets everything reach an Any input', () => {
    for (const key of ['Text', 'Number', 'Text[]']) {
      expect(reachable.get(key)).toContain('Any');
    }
  });

  it('ignores inputs that cannot be connected', () => {
    // `Number` exists only as a non-connectable setting, so the only thing it can reach is `Any`.
    expect(reachable.get('Number')).toEqual(['Any']);
  });

  it('marks an output with nowhere to go rather than leaving the list empty', () => {
    // An empty allow-list means "no restriction" to the flow library, which would make an
    // unconnectable output connectable to everything — the exact opposite of the intent.
    const isolated = compatibleTargetTypes([spec('lonely', [], [output('text', TEXT)])]);
    expect(isolated.get('Text')).toEqual([NO_COMPATIBLE_TARGET]);
  });
});
