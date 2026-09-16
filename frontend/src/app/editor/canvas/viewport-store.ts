import { Injectable, signal } from '@angular/core';

/**
 * What the canvas is currently doing to everything drawn inside it.
 *
 * One number, and it exists because a hand-rolled pointer drag inside a node measures the pointer
 * in *screen* pixels while it is setting a width in *canvas* pixels. At 50% zoom a 100px drag is a
 * 200px node, and dividing by the wrong scale is a bug nobody notices until they zoom out.
 *
 * Published by `FlowCanvas` from `fCanvasChange`, which the library emits for every pan, zoom and
 * fit — including the programmatic ones, because the canvas re-emits after driving them itself.
 * A store rather than an input threaded through the node component: the scale is a property of the
 * viewport, not of any node, and a node deep inside a template should not have to be handed it.
 */
@Injectable({ providedIn: 'root' })
export class ViewportStore {
  /**
   * Seeded at 1 rather than 0 so a drag that somehow precedes the first canvas event divides by
   * something sensible instead of producing an infinite width.
   */
  private readonly current = signal(1);

  readonly scale = this.current.asReadonly();

  setScale(scale: number): void {
    // A zero or negative scale is not a viewport this editor can be in, and it is the one value
    // that would turn a division into a jump to the maximum width.
    if (Number.isFinite(scale) && scale > 0) {
      this.current.set(scale);
    }
  }
}
