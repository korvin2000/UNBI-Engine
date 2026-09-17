import { Pipe, PipeTransform, inject } from '@angular/core';
import { MessageKey } from './messages/en';
import { MessageParams, Translator } from './translator';

/**
 * `{{ 'toolbar.save' | t }}` — a message in the current language.
 *
 * Impure on purpose. A pure pipe re-runs only when its input changes, and the input is the key,
 * which never does; switching language would leave every pipe showing its cached answer. Impure
 * means one map lookup per binding per change-detection pass, which is what a template does with
 * any property anyway. The key is typed, so a typo in a template is a compile error.
 */
@Pipe({ name: 't', pure: false })
export class TranslatePipe implements PipeTransform {
  private readonly translator = inject(Translator);

  transform(key: MessageKey, params?: MessageParams): string {
    return this.translator.t(key, params);
  }
}
