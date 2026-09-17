import { Pipe, PipeTransform, inject } from '@angular/core';
import { PluralKey } from './messages/en';
import { MessageParams, Translator } from './translator';

/** `{{ 'palette.matches' | n: found }}` — the plural form for a count. Impure for the same reason `t` is. */
@Pipe({ name: 'n', pure: false })
export class CountPipe implements PipeTransform {
  private readonly translator = inject(Translator);

  transform(key: PluralKey, count: number, params?: MessageParams): string {
    return this.translator.count(key, count, params);
  }
}
