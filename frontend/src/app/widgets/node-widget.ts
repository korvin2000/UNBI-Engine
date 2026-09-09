import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { FFlowModule } from '@foblex/flow';
import { WidgetSpec } from '../core/catalog/catalog.models';
import { Icon } from '../shared/icon';

/**
 * Renders one input's editor, chosen by the widget kind the backend declared.
 *
 * One component with a `@switch` rather than a component per kind plus a registry. There are seven
 * kinds, they are all small, and they share the disabled/label behaviour — splitting them would
 * mean seven files and an indirection to look at before understanding what a node draws. If a kind
 * grows its own real complexity (a curve editor, an image picker), it earns its own component then.
 */
@Component({
  selector: 'app-node-widget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // FFlowModule supplies fDragBlocker: without it, dragging a slider also drags the node.
  imports: [Icon, FFlowModule],
  templateUrl: './node-widget.html',
  styleUrl: './node-widget.scss',
  host: {
    '[class.is-disabled]': 'disabled()',
  },
})
export class NodeWidget {
  readonly spec = input.required<WidgetSpec>();
  readonly value = input<unknown>(null);
  /** True when an edge drives this input, in which case the widget shows the takeover instead. */
  readonly disabled = input(false);

  readonly valueChange = output<unknown>();

  protected readonly text = computed(() => (this.value() == null ? '' : String(this.value())));
  protected readonly numeric = computed(() => {
    const raw = Number(this.value());
    return Number.isFinite(raw) ? raw : 0;
  });
  protected readonly checked = computed(() => this.value() === true || this.value() === 'true');

  /** Where the slider thumb sits, as a percentage, for the filled-track gradient. */
  protected readonly sliderPercent = computed(() => {
    const spec = this.spec();
    if (spec.kind !== 'slider') {
      return 0;
    }
    const span = spec.max - spec.min;
    if (span <= 0) {
      return 0;
    }
    return ((this.numeric() - spec.min) / span) * 100;
  });

  protected onText(event: Event): void {
    this.valueChange.emit((event.target as HTMLInputElement | HTMLTextAreaElement).value);
  }

  protected onNumber(event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    // An empty field is a user mid-edit, not a zero. Emitting 0 would fight their typing.
    if (raw === '') {
      return;
    }
    const parsed = Number(raw);
    if (Number.isFinite(parsed)) {
      this.valueChange.emit(parsed);
    }
  }

  protected onToggle(event: Event): void {
    this.valueChange.emit((event.target as HTMLInputElement).checked);
  }

  protected onSelect(event: Event): void {
    this.valueChange.emit((event.target as HTMLSelectElement).value);
  }

  protected step(delta: number): void {
    const spec = this.spec();
    if (spec.kind !== 'number') {
      return;
    }
    const next = clamp(this.numeric() + delta * (spec.step || 1), spec.min, spec.max);
    this.valueChange.emit(round(next, spec.step || 1));
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

/** Keeps 0.1 + 0.2 from becoming 0.30000000000000004 in a visible input. */
function round(value: number, step: number): number {
  const decimals = (String(step).split('.')[1] ?? '').length;
  return Number(value.toFixed(decimals));
}
