import { Injectable, computed, signal } from '@angular/core';
import { DEFAULT_LANGUAGE, LANGUAGES, Language } from './languages';
import { MessageKey, Messages, PluralKey, en } from './messages/en';

/** Values for the `{name}` placeholders in a message. */
export type MessageParams = Readonly<Record<string, string | number>>;

/**
 * The editor's strings, in the language the user chose.
 *
 * A signal-backed lookup rather than Angular's compile-time `$localize`: that produces one build
 * per language and switches by reloading a different bundle, which does not fit an editor served
 * as one build by the engine, or a language setting that lives on the engine and takes effect
 * when it is changed. Everything here is a map lookup and a `{placeholder}` fill; `Intl` does the
 * plural rules and the dates.
 *
 * English is compiled in as the fallback, so a message no translation has yet still reads as a
 * sentence rather than as its key.
 */
@Injectable({ providedIn: 'root' })
export class Translator {
  private readonly messages = signal<Messages>(en);
  private readonly code = signal(DEFAULT_LANGUAGE);

  /** The language in use: a BCP 47 tag. */
  readonly language = this.code.asReadonly();

  /** What the language picker offers. */
  readonly languages: readonly Language[] = LANGUAGES;

  private readonly plurals = computed(() => new Intl.PluralRules(this.code()));

  /** The message under a key, with its placeholders filled in. */
  t(key: MessageKey, params?: MessageParams): string {
    return fill(this.messages()[key] ?? en[key], params);
  }

  /**
   * The plural form for a count: `key.one`, `key.other`, and whichever categories a language adds.
   *
   * `{count}` is filled from the number; other placeholders come from `params`.
   */
  count(key: PluralKey, count: number, params?: MessageParams): string {
    const category = this.plurals().select(count);
    const messages = this.messages() as Readonly<Record<string, string | undefined>>;
    const text = messages[`${key}.${category}`] ?? messages[`${key}.other`] ?? en[`${key}.other` as MessageKey];
    return fill(text, { ...params, count });
  }

  /**
   * Switches language, loading its messages first so the change is never half done.
   *
   * An unknown code falls back to English rather than failing: a settings file from a build that
   * had a language this one does not is a stale preference, not an error.
   */
  async use(code: string): Promise<void> {
    const language = this.languages.find((candidate) => candidate.code === code) ?? this.languages[0];
    if (language.code === this.code() && this.messages() !== en) {
      return;
    }
    const messages = await language.load();
    this.messages.set(messages);
    this.code.set(language.code);
    if (typeof document !== 'undefined') {
      document.documentElement.lang = language.code;
    }
  }
}

/** `{name}` → the value; an unknown placeholder is left as it is, which is easier to spot than a blank. */
function fill(text: string, params?: MessageParams): string {
  if (!params) {
    return text;
  }
  return text.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = params[name];
    return value === undefined ? match : String(value);
  });
}
