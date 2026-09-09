import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { assignable, explainRejection } from './assignability';
import { PortType, parsePortType } from './port-type';

interface Contract {
  readonly types: Record<string, unknown>;
  readonly cases: readonly { from: string; to: string; assignable: boolean; rule: string }[];
}

/**
 * Holds the TypeScript type system to `contract/type-assignability.json`.
 *
 * The Java suite reads the same file. If either implementation drifts, one of the two goes red —
 * which is the entire reason the table lives in a shared file rather than being written twice.
 *
 * Read from disk rather than imported, so that the single copy at the repository root stays the
 * only copy: a build step that duplicated it into `src/` would quietly reintroduce the drift this
 * is here to prevent.
 */
describe('port type assignability contract', () => {
  const contract = JSON.parse(
    readFileSync(resolve(__dirname, '../../../../../contract/type-assignability.json'), 'utf8'),
  ) as Contract;

  const types = new Map<string, PortType>(
    Object.entries(contract.types).map(([alias, raw]) => [alias, parsePortType(raw)]),
  );

  const lookup = (alias: string): PortType => {
    const type = types.get(alias);
    if (!type) {
      throw new Error(`Contract case references undeclared type alias '${alias}'`);
    }
    return type;
  };

  it('declares at least one case', () => {
    expect(contract.cases.length).toBeGreaterThan(0);
  });

  for (const testCase of contract.cases) {
    const verb = testCase.assignable ? 'allows' : 'refuses';
    it(`${verb} ${testCase.from} -> ${testCase.to} (${testCase.rule})`, () => {
      expect(assignable(lookup(testCase.from), lookup(testCase.to))).toBe(testCase.assignable);
    });
  }

  it('round trips every declared type through the wire parser', () => {
    for (const [alias, raw] of Object.entries(contract.types)) {
      expect(parsePortType(raw), alias).toEqual(lookup(alias));
    }
  });

  it('explains a struct rejection by naming the offending field', () => {
    expect(explainRejection(lookup('FileRefMissingField'), lookup('FileRef'))).toContain('extension');
  });

  it('explains a list mismatch in terms of lists', () => {
    expect(explainRejection(lookup('FileRef'), lookup('FileList'))).toContain('list');
  });
});
