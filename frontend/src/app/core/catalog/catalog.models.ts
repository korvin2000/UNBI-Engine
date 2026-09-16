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
  | {
      readonly kind: 'text';
      readonly placeholder: string;
      readonly multiline: boolean;
      /** Preferred height for a multiline field; 0 leaves it to the stylesheet. */
      readonly rows: number;
      /** For content whose alignment carries meaning — a schema, a prompt template. */
      readonly monospace: boolean;
      /**
       * Offer a full-window editor beside the field.
       *
       * A prompt is a document. Writing one through four rows inside a node on a zoomable canvas
       * is the usability problem; widening the node until it fits is a worse answer, because it
       * trades one unreadable thing for an unreadable graph.
       */
      readonly editor: boolean;
      /** Preset node type stocking this field's template list, or '' for none. */
      readonly library: string;
      /** Which value inside those presets holds the text. */
      readonly libraryKey: string;
    }
  | {
      readonly kind: 'number';
      readonly min: number;
      readonly max: number;
      readonly step: number;
      readonly unit: string;
      /**
       * An empty field is a value of its own — "do not send this" — rather than a number the user
       * has not finished typing. Load-bearing for sampling parameters, where zero and unset are
       * different requests.
       */
      readonly optional: boolean;
      /** What an empty optional field means, in the user's words — "unset", "no limit". */
      readonly blankLabel: string;
    }
  | { readonly kind: 'slider'; readonly min: number; readonly max: number; readonly step: number }
  | { readonly kind: 'toggle' }
  | {
      readonly kind: 'dropdown';
      readonly options: readonly DropdownOption[];
      /**
       * Names a catalog the engine serves at runtime — credential names, preset groups — which the
       * editor merges into `options`. A list that changes while the engine runs cannot live in a
       * descriptor built once at startup, and hardcoding one here would undo the property that the
       * frontend knows no node types.
       */
      readonly optionsKey: string;
      /** Lets a value be typed as well as picked, for a list that can never be complete. */
      readonly allowCustom: boolean;
      /**
       * Offer only the options an upstream node says it supports.
       *
       * Evaluated in the editor rather than fetched, so a response format the wired model cannot
       * honour disappears the moment the model changes — with no button to press, and with no
       * knowledge here of what a model or a response format is.
       */
      readonly narrowing: Narrowing | null;
    }
  | { readonly kind: 'multiselect'; readonly options: readonly DropdownOption[] }
  | { readonly kind: 'keyvalue'; readonly keyPlaceholder: string; readonly valuePlaceholder: string }
  | { readonly kind: 'directory' }
  | { readonly kind: 'file'; readonly extensions: readonly string[] }
  | { readonly kind: 'filelist'; readonly extensions: readonly string[] }
  /**
   * A reference to a named configuration kept on the engine — an endpoint, say — chosen from a
   * list and edited in a dialog whose fields the engine declares under `schema`. The node stores
   * the profile's id, never its contents, which is what lets one workflow run against different
   * servers on different machines.
   */
  | { readonly kind: 'profile'; readonly schema: string }
  /** The name of a secret the engine holds, chosen from the names it knows or added on the spot. */
  | { readonly kind: 'credential' }
  /**
   * A readout rather than a control: what a run produced, shown in the node.
   *
   * Parsed here because the row model has to know that such a row is not editable — a reset button
   * must never write to one, and it can never be "changed". Drawing it is a separate job.
   */
  | {
      readonly kind: 'display';
      readonly style: 'line' | 'block' | 'chips';
      readonly unit: string;
    }
  /**
   * A kind this build has never heard of, kept rather than dropped.
   *
   * The catalog is served by another process, so a node pack can name a control this editor does
   * not have. Silently rendering nothing would make a setting that exists look like a setting that
   * does not — the node would simply be missing a field, with no hint that anything was wrong. So
   * the unknown name is carried through to a visible placeholder, and the parser says so once on
   * the console.
   */
  | { readonly kind: 'unsupported'; readonly declared: string };

/** "Keep the options named by the list-valued input `listKey` on whatever is wired into `socket`." */
export interface Narrowing {
  readonly socket: string;
  readonly listKey: string;
  /** Offered whatever the upstream node says — the choices that need no capability. */
  readonly always: readonly string[];
}

/** "Only while the sibling setting `key` holds one of `values`." */
export interface ShowWhen {
  readonly key: string;
  readonly values: readonly string[];
}

/**
 * A button the node offers before anything is run.
 *
 * A `discover` action may hand back values to write into the node; a `check` only lights an
 * indicator. The editor treats both the same way and knows what neither of them does.
 */
export interface NodeActionSpec {
  readonly key: string;
  readonly label: string;
  readonly icon: string;
  /** The input key this action feeds — where discovered options land — or '' for none. */
  readonly appliesTo: string;
  readonly kind: 'check' | 'discover';
  /**
   * Run by the editor on its own when `appliesTo` is opened, and again when the upstream wiring
   * changes, rather than from a button. How a model list arrives without a magnifier to press.
   */
  readonly automatic: boolean;
}

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
  /** Fine tuning: folded into the node's Advanced section rather than shown by default. */
  readonly advanced: boolean;
  /**
   * The sub-section this setting belongs to, or '' for none.
   *
   * A name rather than a number, and declared by the node author: twenty sampling knobs in one
   * list is a list nobody reads, and the editor has no way to guess which of them belong together.
   */
  readonly group: string;
  /** Hidden while it cannot apply; null to always show it. */
  readonly showWhen: ShowWhen | null;
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
  readonly actions: readonly NodeActionSpec[];
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
    inputs: asArray(node['inputs']).map(parseInputSpec),
    outputs: asArray(node['outputs']).map(parseOutput),
    actions: asArray(node['actions']).map(parseAction),
  };
}

function parseAction(raw: unknown): NodeActionSpec {
  const action = raw as Record<string, unknown>;
  return {
    key: String(action['key']),
    label: String(action['label'] ?? action['key']),
    icon: String(action['icon'] ?? 'bolt'),
    appliesTo: String(action['appliesTo'] ?? ''),
    kind: action['kind'] === 'discover' ? 'discover' : 'check',
    automatic: action['automatic'] === true,
  };
}

/** One input, as the catalog and a profile schema both serve it. */
export function parseInputSpec(raw: unknown): NodeInputSpec {
  const input = raw as Record<string, unknown>;
  return {
    key: String(input['key']),
    label: String(input['label']),
    type: parsePortType(input['type']),
    required: input['required'] === true,
    connectable: input['connectable'] === true,
    widget: parseWidget(input['widget']),
    defaultValue: input['default'] ?? null,
    hint: input['hint'] == null ? null : String(input['hint']),
    advanced: input['advanced'] === true,
    group: String(input['group'] ?? ''),
    showWhen: parseShowWhen(input['showWhen']),
  };
}

function parseShowWhen(raw: unknown): ShowWhen | null {
  if (raw == null || typeof raw !== 'object') {
    return null;
  }
  const condition = raw as Record<string, unknown>;
  const values = asArray(condition['values']).map(String);
  return values.length === 0 ? null : { key: String(condition['key']), values };
}

/**
 * Fills in what an older engine may not have sent.
 *
 * The catalog is served rather than compiled in, so a frontend can meet a backend that predates a
 * widget field. Defaulting here — once — is what keeps every template free of `?? false`.
 */
function parseWidget(raw: unknown): WidgetSpec | null {
  if (raw == null || typeof raw !== 'object') {
    return null;
  }
  const widget = raw as Record<string, unknown>;
  switch (widget['kind']) {
    case 'text':
      return {
        kind: 'text',
        placeholder: String(widget['placeholder'] ?? ''),
        multiline: widget['multiline'] === true,
        rows: Number(widget['rows'] ?? 0),
        monospace: widget['monospace'] === true,
        editor: widget['editor'] === true,
        library: String(widget['library'] ?? ''),
        libraryKey: String(widget['libraryKey'] ?? ''),
      };
    case 'number':
      return {
        kind: 'number',
        min: Number(widget['min'] ?? 0),
        max: Number(widget['max'] ?? 0),
        step: Number(widget['step'] ?? 1),
        unit: String(widget['unit'] ?? ''),
        optional: widget['optional'] === true,
        blankLabel: String(widget['blankLabel'] ?? 'unset'),
      };
    case 'dropdown':
      return {
        kind: 'dropdown',
        options: parseOptions(widget['options']),
        optionsKey: String(widget['optionsKey'] ?? ''),
        allowCustom: widget['allowCustom'] === true,
        narrowing: parseNarrowing(widget['narrowing']),
      };
    case 'slider':
      return {
        kind: 'slider',
        min: Number(widget['min'] ?? 0),
        max: Number(widget['max'] ?? 0),
        step: Number(widget['step'] ?? 1),
      };
    case 'toggle':
      return { kind: 'toggle' };
    case 'multiselect':
      return { kind: 'multiselect', options: parseOptions(widget['options']) };
    case 'directory':
      return { kind: 'directory' };
    case 'file':
      return { kind: 'file', extensions: asArray(widget['extensions']).map(String) };
    case 'filelist':
      return { kind: 'filelist', extensions: asArray(widget['extensions']).map(String) };
    case 'keyvalue':
      return {
        kind: 'keyvalue',
        keyPlaceholder: String(widget['keyPlaceholder'] ?? 'Key'),
        valuePlaceholder: String(widget['valuePlaceholder'] ?? 'Value'),
      };
    case 'profile':
      return { kind: 'profile', schema: String(widget['schema'] ?? '') };
    case 'credential':
      return { kind: 'credential' };
    case 'display':
      return {
        kind: 'display',
        style: displayStyle(widget['style']),
        unit: String(widget['unit'] ?? ''),
      };
    default: {
      // Loudly, once, and then visibly in the node: a kind this build cannot draw is a setting the
      // user cannot reach, and the one thing it must not do is look like an absence.
      const declared = String(widget['kind'] ?? '');
      console.warn(`[catalog] unsupported widget kind: ${declared || '(none)'}`);
      return { kind: 'unsupported', declared };
    }
  }
}

function displayStyle(raw: unknown): 'line' | 'block' | 'chips' {
  return raw === 'block' || raw === 'chips' ? raw : 'line';
}

function parseNarrowing(raw: unknown): Narrowing | null {
  if (raw == null || typeof raw !== 'object') {
    return null;
  }
  const narrowing = raw as Record<string, unknown>;
  const socket = String(narrowing['socket'] ?? '');
  const listKey = String(narrowing['listKey'] ?? '');
  if (!socket || !listKey) {
    return null;
  }
  return { socket, listKey, always: asArray(narrowing['always']).map(String) };
}

function parseOptions(raw: unknown): readonly DropdownOption[] {
  return asArray(raw).map((entry) => {
    const option = entry as Record<string, unknown>;
    return { value: String(option['value']), label: String(option['label']) };
  });
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
