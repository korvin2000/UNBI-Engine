import { Messages, en } from './messages/en';

/** One language the editor can be switched to. */
export interface Language {
  /** A BCP 47 tag: what `<html lang>` and `Intl` are given. */
  readonly code: string;
  /** Its own name, in itself, for the language picker. */
  readonly label: string;
  /** Its messages — loaded on demand, so a language nobody picks costs nothing to ship. */
  readonly load: () => Promise<Messages>;
}

export const DEFAULT_LANGUAGE = 'en';

/**
 * Every language the editor offers.
 *
 * Adding one is one module and one entry here: `{ code: 'de', label: 'Deutsch', load: () =>
 * import('./messages/de').then((m) => m.de) }`. The module's export is typed `Messages`, so a key
 * missing from the translation is a compile error rather than an English string showing up in a
 * German dialog.
 */
export const LANGUAGES: readonly Language[] = [{ code: DEFAULT_LANGUAGE, label: 'English', load: async () => en }];
