import { PortType, describeType } from './port-type';

/**
 * Whether a value of `from` may be delivered to a port declaring `to`.
 *
 * A direct translation of `TypeSystem.assignable` in the backend, and deliberately structured the
 * same way so the two read as the same algorithm. `contract/type-assignability.json` is what keeps
 * that claim honest — see `assignability.contract.spec.ts`.
 *
 * Rule order matters:
 *  1. `Any` on either side matches (top type, and the dynamic escape hatch).
 *  2. A union *source* must find a home for every member — checked before the target, so that
 *     `Text | Number` correctly fits into `Text | Number | Boolean`.
 *  3. A union *target* is satisfied by any one member.
 *  4. Lists are covariant; structs use width subtyping; primitives match by name.
 */
export function assignable(from: PortType, to: PortType): boolean {
  if (from === to) {
    return true;
  }
  if (from.kind === 'any' || to.kind === 'any') {
    return true;
  }
  if (from.kind === 'union') {
    return from.members.every((member) => assignable(member, to));
  }
  if (to.kind === 'union') {
    return to.members.some((member) => assignable(from, member));
  }
  switch (from.kind) {
    case 'primitive':
      return to.kind === 'primitive' && from.name === to.name;
    case 'list':
      return to.kind === 'list' && assignable(from.element, to.element);
    case 'struct':
      return to.kind === 'struct' && satisfiesWidth(from.fields, to.fields);
  }
}

function satisfiesWidth(
  source: Readonly<Record<string, PortType>>,
  target: Readonly<Record<string, PortType>>,
): boolean {
  return Object.entries(target).every(([key, required]) => {
    const present = source[key];
    return present !== undefined && assignable(present, required);
  });
}

/** Why a connection was refused, phrased for a user rather than for a log. */
export function explainRejection(from: PortType, to: PortType): string {
  if (from.kind === 'struct' && to.kind === 'struct') {
    for (const [key, required] of Object.entries(to.fields)) {
      const present = from.fields[key];
      if (present === undefined) {
        return `${from.name} has no '${key}' field, which ${to.name} requires`;
      }
      if (!assignable(present, required)) {
        return `${from.name}.${key} is ${describeType(present)}, but ${to.name} needs ${describeType(required)}`;
      }
    }
  }
  if (from.kind === 'list' && to.kind === 'list') {
    return `list elements do not match: ${describeType(from.element)} cannot become ${describeType(to.element)}`;
  }
  if (to.kind === 'list') {
    return `expected a list of ${describeType(to.element)}, got a single ${describeType(from)}`;
  }
  if (from.kind === 'list') {
    return `expected a single ${describeType(to)}, got a list of ${describeType(from.element)}`;
  }
  return `${describeType(from)} cannot be connected to ${describeType(to)}`;
}
