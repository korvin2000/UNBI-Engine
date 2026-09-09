/**
 * The port type lattice, mirroring `com.unbi.engine.core.type.PortType`.
 *
 * The backend is the authority — it validates every graph before running it. This copy exists so
 * the editor can answer "may I drop this edge here?" during a drag, at pointer speed, without a
 * round trip. Both implementations are held to `contract/type-assignability.json`.
 */

export type PortType =
  | PrimitiveType
  | StructType
  | ListType
  | UnionType
  | AnyType;

export interface PrimitiveType {
  readonly kind: 'primitive';
  readonly name: string;
}

export interface StructType {
  readonly kind: 'struct';
  readonly name: string;
  readonly fields: Readonly<Record<string, PortType>>;
}

export interface ListType {
  readonly kind: 'list';
  readonly element: PortType;
}

export interface UnionType {
  readonly kind: 'union';
  readonly members: readonly PortType[];
}

export interface AnyType {
  readonly kind: 'any';
}

/** Human-readable form, for tooltips and rejection messages. */
export function describeType(type: PortType): string {
  switch (type.kind) {
    case 'primitive':
      return type.name;
    case 'struct':
      return type.name;
    case 'list':
      return `${describeType(type.element)}[]`;
    case 'union':
      return type.members.map(describeType).join(' | ');
    case 'any':
      return 'Any';
  }
}

/**
 * A stable key for a type, used to colour ports consistently.
 *
 * Two ports carrying the same type get the same colour across the whole graph, which is what makes
 * a large workflow readable at a glance — the same trick chaiNNer uses for image versus model.
 */
export function typeKey(type: PortType): string {
  switch (type.kind) {
    case 'primitive':
      return type.name;
    case 'struct':
      return type.name;
    case 'list':
      return `${typeKey(type.element)}[]`;
    case 'union':
      return type.members.map(typeKey).join('|');
    case 'any':
      return 'Any';
  }
}

/**
 * A CSS-safe token naming the colour family a port should use.
 *
 * Distinct from {@link typeKey}, which is for identity and may contain `[]` — characters that are
 * not valid in a class name and silently kill the whole selector.
 *
 * A list takes its element's colour on purpose: `FileRef` and `FileRef[]` are different types and
 * cannot be connected to each other, but they are the *same subject*, and colouring them alike is
 * what lets you follow files through a graph at a glance. The port's shape and the connection rules
 * still keep them apart.
 */
export function portColourKey(type: PortType): string {
  switch (type.kind) {
    case 'primitive':
    case 'struct':
      return cssSafe(type.name);
    case 'list':
      return portColourKey(type.element);
    case 'union':
      return 'Union';
    case 'any':
      return 'Any';
  }
}

/** Class names may only contain letters, digits, hyphen and underscore here. */
function cssSafe(name: string): string {
  return name.replace(/[^A-Za-z0-9_-]/g, '_');
}

/** Parses the wire form. Throws on anything it does not recognise rather than guessing. */
export function parsePortType(raw: unknown): PortType {
  if (typeof raw !== 'object' || raw === null) {
    throw new Error(`Not a port type: ${JSON.stringify(raw)}`);
  }
  const node = raw as Record<string, unknown>;
  switch (node['kind']) {
    case 'primitive':
      return { kind: 'primitive', name: requireString(node, 'name') };
    case 'any':
      return { kind: 'any' };
    case 'list':
      return { kind: 'list', element: parsePortType(node['element']) };
    case 'struct': {
      const rawFields = node['fields'];
      if (typeof rawFields !== 'object' || rawFields === null) {
        throw new Error(`Struct type is missing its fields: ${JSON.stringify(raw)}`);
      }
      const fields: Record<string, PortType> = {};
      for (const [key, value] of Object.entries(rawFields)) {
        fields[key] = parsePortType(value);
      }
      return { kind: 'struct', name: requireString(node, 'name'), fields };
    }
    case 'union': {
      const members = node['members'];
      if (!Array.isArray(members)) {
        throw new Error(`Union type is missing its members: ${JSON.stringify(raw)}`);
      }
      return { kind: 'union', members: members.map(parsePortType) };
    }
    default:
      throw new Error(`Unknown port type kind: ${String(node['kind'])}`);
  }
}

function requireString(node: Record<string, unknown>, field: string): string {
  const value = node[field];
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`Port type is missing '${field}'`);
  }
  return value;
}
