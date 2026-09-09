import { PortType, parsePortType } from '../types/port-type';

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
