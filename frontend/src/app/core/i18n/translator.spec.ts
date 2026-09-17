import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { LANGUAGES } from './languages';
import { en } from './messages/en';
import { Translator } from './translator';

describe('the translator', () => {
  let translator: Translator;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    translator = TestBed.inject(Translator);
  });

  it('starts in English and fills placeholders', () => {
    expect(translator.language()).toBe('en');
    expect(translator.t('toolbar.running', { percent: 40 })).toBe('Running — 40%');
  });

  it('leaves an unknown placeholder visible rather than blank', () => {
    expect(translator.t('toolbar.running')).toBe('Running — {percent}%');
  });

  it('picks the plural form from the count', () => {
    expect(translator.count('toolbar.problems', 1)).toBe('1 problem');
    expect(translator.count('toolbar.problems', 3)).toBe('3 problems');
    expect(translator.count('palette.matches', 0)).toBe('0 matches');
  });

  it('falls back to English for a language this build does not have', async () => {
    await translator.use('xx');
    expect(translator.language()).toBe('en');
    expect(translator.t('common.cancel')).toBe('Cancel');
  });

  it('offers every registered language, English first', () => {
    expect(translator.languages).toBe(LANGUAGES);
    expect(translator.languages[0].code).toBe('en');
  });

  it('has a plural pair for every key that claims one', () => {
    // A `.one` without an `.other` (or the reverse) is a message that renders as its key for some
    // count; the check is here so a new pair cannot be half-added.
    const keys = Object.keys(en);
    for (const key of keys) {
      if (key.endsWith('.one')) {
        expect(keys, key).toContain(key.replace(/\.one$/, '.other'));
      }
      if (key.endsWith('.other')) {
        expect(keys, key).toContain(key.replace(/\.other$/, '.one'));
      }
    }
  });
});
