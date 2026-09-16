import { DestroyRef, Injectable, inject, signal } from '@angular/core';

/** How often a relative time is worth redrawing. */
const MINUTE = 60_000;

/**
 * One ticking signal, so "6 minutes ago" becomes "7 minutes ago" without anyone polling.
 *
 * A readout that says when it was fetched is only useful if it stays true, and a timestamp rendered
 * once is wrong a minute later. Reading {@link tick} inside a `computed` is what makes that
 * computed recompute — one interval for the whole application rather than one per row, which
 * matters because a single Model Info node draws twenty-odd readouts.
 *
 * A minute is the resolution these strings have anyway: nothing here counts seconds, so a faster
 * timer would wake the app up to produce identical text.
 */
@Injectable({ providedIn: 'root' })
export class MinuteClock {
  private readonly ticks = signal(0);

  /** Read it to depend on the passing of time; its value means nothing on its own. */
  readonly tick = this.ticks.asReadonly();

  constructor() {
    const timer = setInterval(() => this.ticks.update((count) => count + 1), MINUTE);
    // Cleared with the root injector, which is what keeps a test from leaving a timer behind.
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }
}
