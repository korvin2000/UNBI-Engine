import type { DropdownOption, NodeActionSpec, NodeInputSpec, NodeSpec } from './catalog.models';
import type { ProbeResult, ProbeState } from './node-probe.service';
import { WorkflowDoc, WorkflowNode, isConnected } from '../graph/workflow.models';

/**
 * What one input of one node instance currently is, as a plain value.
 *
 * Two places draw the same settings — the node on the canvas and the inspector panel — and they
 * must agree about every one of these answers. A setting that is folded away in the node and
 * missing from the panel, or wired in one and editable in the other, is not a cosmetic difference:
 * it is two different statements about what a run will do.
 *
 * So this file is a pure function over an explicit context rather than a method on either
 * component. Nothing here reads a signal or injects anything; the call site does both and passes
 * the answers in, which is also what makes the rules below testable with plain objects.
 */

/**
 * The shape a row takes where it is drawn.
 *
 * Decided by the call site rather than here, because it is a layout question and the two call
 * sites answer it differently: the canvas has a port to anchor, the panel has a label column.
 * Kept in this file so a new shape is a change in one place — the row model is deliberately
 * extensible, and `display` — a readout that draws its own label because it is a two-column line
 * rather than a control under a caption — has no business editing three templates.
 */
export type RowKind = 'socket' | 'toggle' | 'field' | 'inline' | 'display';

/** Everything about a setting that does not depend on where it is drawn. */
export interface SettingRow {
  readonly spec: NodeInputSpec;
  /**
   * The stored value, or the declared default when nothing is stored.
   *
   * A readout is the exception and `undefined` there is load-bearing — see {@link valueOf}.
   */
  readonly value: unknown;
  /** True when an edge drives this input, which takes the widget over. */
  readonly wired: boolean;
  readonly widgetDisabled: boolean;
  /** Choices this node instance's probes discovered for this input. */
  readonly discovered: readonly DropdownOption[];
  /** What an empty list should say for itself while, or after, discovery ran. */
  readonly discoveryNote: string;
  /**
   * Option values an upstream node says are available, or null when nothing is wired in.
   *
   * Null and empty are different answers: nothing wired in is "nobody knows", which is no reason
   * to hide a choice, while an empty set is an upstream node declaring it supports none of them.
   */
  readonly allowed: ReadonlySet<string> | null;
  /** Discoveries the editor runs on its own the moment this input's list is opened. */
  readonly automatic: readonly NodeActionSpec[];
  /** Whether the declared condition holds — a row that does not apply is not drawn at all. */
  readonly applies: boolean;
  /** Whether this row is a control the user can change, as opposed to a socket or a readout. */
  readonly isEditable: boolean;
  /** Whether an editable row holds something other than its default. */
  readonly isChanged: boolean;
}

/**
 * What {@link buildRows} needs to know, handed in explicitly.
 *
 * Functions rather than services: the canvas resolves these from signals inside a `computed`, so
 * calling them here is what registers the dependency — and a test can pass three closures over
 * plain maps instead of standing up an injector.
 */
export interface RowContext {
  readonly node: WorkflowNode;
  readonly spec: NodeSpec;
  readonly doc: WorkflowDoc;
  readonly specsById: ReadonlyMap<string, NodeSpec>;
  readonly probeState: (action: string) => ProbeState;
  readonly probeResult: (action: string) => ProbeResult | null;
  readonly discoveredFor: (inputKey: string) => readonly DropdownOption[];
}

/** Every input of the node, in declaration order, with nothing filtered out. */
export function buildRows(context: RowContext): readonly SettingRow[] {
  return context.spec.inputs.map((input) => buildRow(context, input));
}

function buildRow(context: RowContext, input: NodeInputSpec): SettingRow {
  const wired = isConnected(context.doc, context.node.id, input.key);
  const automatic = context.spec.actions.filter(
    (action) => action.automatic && action.appliesTo === input.key,
  );
  const value = valueOf(context.node, input);
  const editable = isEditable(input);
  return {
    spec: input,
    value,
    wired,
    // A wired input keeps its widget on screen but disabled, so the graph does not visibly change
    // shape when an edge is attached or removed.
    widgetDisabled: wired,
    discovered: context.discoveredFor(input.key),
    discoveryNote: discoveryNote(context, automatic),
    allowed: allowedFor(context, input),
    automatic,
    applies: applies(context, input),
    isEditable: editable,
    isChanged: editable && isChanged(context.node, input),
  };
}

/**
 * The value an input currently holds: what is stored, or what the descriptor declared.
 *
 * A `display` readout is the one input whose declared default is *not* a value it holds. The engine
 * sends `""` there because a descriptor field has to say something, and the row model must keep
 * three answers apart: nothing stored is "nobody has asked yet", `""` is "asked, and this gateway
 * does not publish it", and anything else is a fact. Substituting the default would collapse the
 * first two, and a node that has never been fetched would claim every one of its twenty rows is
 * unpublished.
 */
export function valueOf(node: WorkflowNode, input: NodeInputSpec): unknown {
  const provided = node.values[input.key];
  if (isReadout(input)) {
    return provided;
  }
  return provided === undefined ? input.defaultValue : provided;
}

/** Whether this input is a readout — what a run produced — rather than a control. */
export function isReadout(input: NodeInputSpec): boolean {
  return input.widget?.kind === 'display';
}

/**
 * The values of a node worth saving as a preset, which is everything that is not a readout.
 *
 * A preset is a configuration, and what a gateway answered last Tuesday is not part of one: dropped
 * onto a canvas it would arrive as stale facts that look freshly fetched, and it would put them
 * back after every reset. Used by both the save and the instantiate side, because a preset saved by
 * an older build can still be carrying them.
 */
export function withoutReadouts(
  spec: NodeSpec,
  values: Readonly<Record<string, unknown>>,
): Record<string, unknown> {
  const readouts = new Set(spec.inputs.filter(isReadout).map((input) => input.key));
  return Object.fromEntries(
    Object.entries(values).filter(([key]) => !readouts.has(key)),
  );
}

/**
 * Whether this row is something the user can edit.
 *
 * False for a socket, which has no control at all, and false for a readout — a `display` widget
 * shows what a run produced and has nothing to set. Both matter beyond layout: a reset button must
 * never write to either, and neither can be "changed".
 */
function isEditable(input: NodeInputSpec): boolean {
  const widget = input.widget;
  return widget !== null && !isReadout(input) && widget.kind !== 'unsupported';
}

/** Whether an editable setting holds something other than its default. */
function isChanged(node: WorkflowNode, input: NodeInputSpec): boolean {
  const stored = node.values[input.key];
  return stored !== undefined && serialise(stored) !== serialise(input.defaultValue);
}

/** True when a setting's declared condition holds, or it has none. */
function applies(context: RowContext, input: NodeInputSpec): boolean {
  const condition = input.showWhen;
  if (!condition) {
    return true;
  }
  const sibling = context.spec.inputs.find((candidate) => candidate.key === condition.key);
  const value = sibling ? valueOf(context.node, sibling) : undefined;
  // Compared as text: a toggle stores a boolean and a dropdown a string, and a condition should
  // not have to know which kind of control it is reading.
  return condition.values.includes(String(value));
}

/**
 * Which option values the node wired into this input's narrowing socket says are available.
 *
 * Null when nothing is wired in — see {@link SettingRow.allowed} for why that is not an empty set.
 */
function allowedFor(context: RowContext, input: NodeInputSpec): ReadonlySet<string> | null {
  const widget = input.widget;
  if (!widget || widget.kind !== 'dropdown' || !widget.narrowing) {
    return null;
  }
  const { socket, listKey } = widget.narrowing;
  const edge = context.doc.edges.find(
    (candidate) => candidate.targetNode === context.node.id && candidate.targetPort === socket,
  );
  const upstream = edge
    ? context.doc.nodes.find((candidate) => candidate.id === edge.sourceNode)
    : undefined;
  const upstreamSpec = upstream ? context.specsById.get(upstream.type) : undefined;
  if (!upstream || !upstreamSpec) {
    return null;
  }
  const declared = upstreamSpec.inputs.find((candidate) => candidate.key === listKey);
  const raw = upstream.values[listKey] ?? declared?.defaultValue ?? null;
  if (Array.isArray(raw)) {
    return new Set(raw.map((entry) => String(entry)));
  }
  if (typeof raw === 'string' && raw.trim()) {
    return new Set(
      raw
        .split(',')
        .map((part) => part.trim())
        .filter(Boolean),
    );
  }
  return new Set<string>();
}

/** What an empty list should say while, or after, its automatic discovery ran. */
function discoveryNote(context: RowContext, automatic: readonly NodeActionSpec[]): string {
  for (const action of automatic) {
    const state = context.probeState(action.key);
    if (state === 'running') {
      return 'Asking the endpoint…';
    }
    if (state === 'failed') {
      return context.probeResult(action.key)?.message ?? 'Could not fetch the list.';
    }
    if (state === 'ok' && context.discoveredFor(action.appliesTo).length === 0) {
      return context.probeResult(action.key)?.message ?? 'The endpoint listed nothing.';
    }
  }
  return '';
}

/**
 * The rows a section holds: applicable, and on the side of the fold that section owns.
 *
 * Generic over the row so a call site that added its own fields — the canvas adds the port half —
 * gets them back rather than a plain {@link SettingRow}.
 */
export function sectionRows<T extends SettingRow>(
  rows: readonly T[],
  advanced: boolean,
): readonly T[] {
  return rows.filter((row) => row.applies && row.spec.advanced === advanced);
}

/** How many of these rows are no longer at their default. */
export function changedCount(rows: readonly SettingRow[]): number {
  return rows.filter((row) => row.isChanged).length;
}

/** A named run of rows inside a section. The unnamed one has no header and comes first. */
export interface RowGroup<T extends SettingRow = SettingRow> {
  readonly name: string;
  readonly rows: readonly T[];
}

/**
 * Splits rows into the sub-sections the descriptor named, in declaration order.
 *
 * Ungrouped rows come first whatever the declaration order: they are the ones with no answer to
 * "which part of this is it", and burying them under a named heading would make the node's own
 * ordering look arbitrary.
 */
export function groupRows<T extends SettingRow>(rows: readonly T[]): readonly RowGroup<T>[] {
  const groups: RowGroup<T>[] = [];
  const ungrouped = rows.filter((row) => !row.spec.group);
  if (ungrouped.length > 0) {
    groups.push({ name: '', rows: ungrouped });
  }
  const named = new Map<string, T[]>();
  for (const row of rows) {
    if (!row.spec.group) {
      continue;
    }
    const existing = named.get(row.spec.group);
    if (existing) {
      existing.push(row);
    } else {
      named.set(row.spec.group, [row]);
    }
  }
  for (const [name, members] of named) {
    groups.push({ name, rows: members });
  }
  return groups;
}

/** A comparable form of a widget value, for telling a changed setting from an untouched one. */
export function serialise(value: unknown): string {
  try {
    return JSON.stringify(value ?? null);
  } catch {
    return String(value);
  }
}
