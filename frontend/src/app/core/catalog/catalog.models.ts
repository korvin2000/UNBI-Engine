import { assignable } from '../types/assignability';
import { PortType, parsePortType, typeKey } from '../types/port-type';

/**
 * The node catalog, as served by `GET /api/catalog`.
 *
 * These types are the frontend's only knowledge of what nodes exist. Nothing here is hardcoded per
 * node: the palette, the node bodies and the port colours are all rendered from this data, which is
 * what lets a backend node pack appear in the UI without a frontend change.
 */

export type WidgetSpec =
  | { readonly kind: 'text'; readonly placeholder: string; readonly multiline: boolean }
  | { readonly kind: 'number'; readonly min: number; readonly max: number; readonly step: number; readonly unit: string }
  | { readonly kind: 'slider'; readonly min: number; readonly max: number; readonly step: number }
  | { readonly kind: 'toggle' }
  | { readonly kind: 'dropdown'; readonly options: readonly DropdownOption[] }
  | { readonly kind: 'directory' }
  | { readonly kind: 'file'; readonly extensions: readonly string[] };

export interface DropdownOption {
  readonly value: string;
  readonly label: string;
}

export interface NodeInputSpec {
  readonly key: string;
  readonly label: string;
  readonly type: PortType;
  readonly required: boolean;
  /** False for pure settings, which render in the node but never accept an edge. */
  readonly connectable: boolean;
  readonly widget: WidgetSpec | null;
  readonly defaultValue: unknown;
  readonly hint: string | null;
}

export interface NodeOutputSpec {
  readonly key: string;
  readonly label: string;
  readonly type: PortType;
  readonly hint: string | null;
}

export interface NodeSpec {
  readonly id: string;
  readonly label: string;
  readonly category: string;
  readonly subcategory: string;
  readonly icon: string;
  readonly accent: string;
  readonly description: string;
  readonly inputs: readonly NodeInputSpec[];
  readonly outputs: readonly NodeOutputSpec[];
}

/** Palette grouping, derived once so the left panel does not regroup on every render. */
export interface CategoryGroup {
  readonly name: string;
  readonly accent: string;
  readonly icon: string;
  readonly sections: readonly { readonly name: string; readonly nodes: readonly NodeSpec[] }[];
}

export function parseCatalog(raw: unknown): NodeSpec[] {
  const root = raw as { nodes?: unknown[] };
  if (!Array.isArray(root.nodes)) {
    throw new Error('Catalog response has no "nodes" array');
  }
  return root.nodes.map(parseNodeSpec);
}

function parseNodeSpec(raw: unknown): NodeSpec {
  const node = raw as Record<string, unknown>;
  return {
    id: String(node['id']),
    label: String(node['label']),
    category: String(node['category'] ?? 'Other'),
    subcategory: String(node['subcategory'] ?? ''),
    icon: String(node['icon'] ?? 'node'),
    accent: String(node['accent'] ?? 'slate'),
    description: String(node['description'] ?? ''),
    inputs: asArray(node['inputs']).map(parseInput),
    outputs: asArray(node['outputs']).map(parseOutput),
  };
}

function parseInput(raw: unknown): NodeInputSpec {
  const input = raw as Record<string, unknown>;
  return {
    key: String(input['key']),
    label: String(input['label']),
    type: parsePortType(input['type']),
    required: input['required'] === true,
    connectable: input['connectable'] === true,
    widget: input['widget'] == null ? null : (input['widget'] as WidgetSpec),
    defaultValue: input['default'] ?? null,
    hint: input['hint'] == null ? null : String(input['hint']),
  };
}

function parseOutput(raw: unknown): NodeOutputSpec {
  const output = raw as Record<string, unknown>;
  return {
    key: String(output['key']),
    label: String(output['label']),
    type: parsePortType(output['type']),
    hint: output['hint'] == null ? null : String(output['hint']),
  };
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

/**
 * Category given to a connector nothing may connect to.
 *
 * The flow library reads an *empty* allow-list as "no restriction", so an output with no legal
 * target anywhere in the catalog has to be given a category that exists but matches nothing.
 * Without this the one genuinely incompatible case would be the one case that connects to anything.
 */
export const NO_COMPATIBLE_TARGET = '__no-compatible-target__';

/**
 * Which input types each output type may legally reach, keyed by {@link typeKey}.
 *
 * This is what the canvas hands the flow library as a per-connector allow-list, so an illegal drop
 * is refused *while the wire is in the air* rather than silently ignored when it lands. The rule is
 * {@link assignable} — the same predicate the drop handler and the backend validator use, which is
 * why the three cannot disagree about what "compatible" means.
 *
 * A catalog has tens of ports and this runs once per load, so the quadratic scan costs nothing and
 * buys an exact answer rather than a heuristic.
 */
export function compatibleTargetTypes(
  nodes: readonly NodeSpec[],
): ReadonlyMap<string, readonly string[]> {
  const inputs = new Map<string, PortType>();
  const outputs = new Map<string, PortType>();
  for (const spec of nodes) {
    for (const input of spec.inputs) {
      if (input.connectable) {
        inputs.set(typeKey(input.type), input.type);
      }
    }
    for (const output of spec.outputs) {
      outputs.set(typeKey(output.type), output.type);
    }
  }

  const result = new Map<string, readonly string[]>();
  for (const [key, type] of outputs) {
    const reachable = [...inputs.entries()]
      .filter(([, candidate]) => assignable(type, candidate))
      .map(([candidateKey]) => candidateKey);
    result.set(key, reachable.length > 0 ? reachable : [NO_COMPATIBLE_TARGET]);
  }
  return result;
}

/**
 * Groups nodes for the palette, preserving the order the backend sent.
 *
 * The backend already sorts by category then subcategory then id, so insertion order into these
 * maps is the display order and no comparator is needed here.
 */
export function groupIntoCategories(nodes: readonly NodeSpec[]): CategoryGroup[] {
  const categories = new Map<string, Map<string, NodeSpec[]>>();
  const accents = new Map<string, { accent: string; icon: string }>();

  for (const node of nodes) {
    let sections = categories.get(node.category);
    if (!sections) {
      sections = new Map<string, NodeSpec[]>();
      categories.set(node.category, sections);
      accents.set(node.category, { accent: node.accent, icon: node.icon });
    }
    const section = sections.get(node.subcategory) ?? [];
    section.push(node);
    sections.set(node.subcategory, section);
  }

  return [...categories.entries()].map(([name, sections]) => ({
    name,
    accent: accents.get(name)?.accent ?? 'slate',
    icon: accents.get(name)?.icon ?? 'node',
    sections: [...sections.entries()].map(([sectionName, sectionNodes]) => ({
      name: sectionName,
      nodes: sectionNodes,
    })),
  }));
}
