import { Directive, ElementRef, afterNextRender, inject } from '@angular/core';

/**
 * Focuses the element once it is rendered, and selects its text.
 *
 * The `autofocus` attribute is honoured by a browser only once per document — the first element
 * inserted with it wins, and every dialog opened afterwards gets nothing. A field that opens for
 * typing has to ask for focus itself.
 */
@Directive({ selector: '[appAutofocus]' })
export class Autofocus {
  constructor() {
    const element = inject(ElementRef).nativeElement as HTMLElement;
    afterNextRender(() => {
      element.focus();
      if (element instanceof HTMLInputElement) {
        element.select();
      }
    });
  }
}
