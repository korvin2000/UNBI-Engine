import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { FFlowModule } from '@foblex/flow';
import { WidgetSpec } from '../core/catalog/catalog.models';
import { FileBrowserService } from '../shared/file-browser/file-browser.service';
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
  private readonly browser = inject(FileBrowserService);

  readonly spec = input.required<WidgetSpec>();
  readonly value = input<unknown>(null);
  /** True when an edge drives this input, in which case the widget shows the takeover instead. */
  readonly disabled = input(false);

  readonly valueChange = output<unknown>();

  protected readonly listOpen = signal(false);

  protected readonly text = computed(() => (this.value() == null ? '' : String(this.value())));
  protected readonly numeric = computed(() => {
    const raw = Number(this.value());
    return Number.isFinite(raw) ? raw : 0;
  });
  protected readonly checked = computed(() => this.value() === true || this.value() === 'true');

  /** The dropdown shows a label, not the value the graph stores. */
  protected readonly selectedLabel = computed(() => {
    const spec = this.spec();
    if (spec.kind !== 'dropdown') {
      return '';
    }
    const current = this.text();
    return spec.options.find((option) => option.value === current)?.label ?? current;
  });

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

  /**
   * Clamps on the way out of the field.
   *
   * Clamping per keystroke makes a number box impossible to type in — typing `1` on the way to
   * `100` in a 10..999 field would snap to 10 and eat the rest. Leaving the field is the moment the
   * user has finished saying what they meant.
   */
  protected onNumberCommit(event: Event): void {
    const spec = this.spec();
    if (spec.kind !== 'number' && spec.kind !== 'slider') {
      return;
    }
    const input = event.target as HTMLInputElement;
    const parsed = Number(input.value);
    const settled = Number.isFinite(parsed) ? clamp(parsed, spec.min, spec.max) : spec.min;
    if (settled !== this.numeric() || input.value === '') {
      this.valueChange.emit(settled);
    }
    input.value = String(settled);
  }

  protected onToggle(event: Event): void {
    this.valueChange.emit((event.target as HTMLInputElement).checked);
  }

  protected step(delta: number): void {
    const spec = this.spec();
    if (spec.kind !== 'number') {
      return;
    }
    const next = clamp(this.numeric() + delta * (spec.step || 1), spec.min, spec.max);
    this.valueChange.emit(round(next, spec.step || 1));
  }

  // --- Dropdown -----------------------------------------------------------

  protected toggleList(): void {
    if (!this.disabled()) {
      this.listOpen.update((open) => !open);
    }
  }

  protected closeList(): void {
    this.listOpen.set(false);
  }

  protected pickOption(value: string): void {
    this.closeList();
    this.valueChange.emit(value);
  }

  /** Up and down move through the options without opening the list, as a native select does. */
  protected onSelectKeydown(event: KeyboardEvent): void {
    const spec = this.spec();
    if (spec.kind !== 'dropdown') {
      return;
    }
    if (event.key === 'Escape') {
      this.closeList();
      return;
    }
    const step = event.key === 'ArrowDown' ? 1 : event.key === 'ArrowUp' ? -1 : 0;
    if (step === 0) {
      return;
    }
    event.preventDefault();
    const options = spec.options;
    const at = options.findIndex((option) => option.value === this.text());
    const next = clamp((at < 0 ? 0 : at) + step, 0, options.length - 1);
    this.valueChange.emit(options[next].value);
  }

  // --- Path pickers -------------------------------------------------------

  /**
   * Opens the browser-side dialog for a path.
   *
   * A path field is not a browser upload: the engine is what opens it, so what the user needs to
   * see is the engine's disk. `FileBrowserService` is the seam.
   */
  protected async browse(mode: 'file' | 'directory'): Promise<void> {
    const spec = this.spec();
    const chosen = await this.browser.pick({
      mode,
      title: mode === 'directory' ? 'Choose a folder' : 'Choose a file',
      startAt: this.text(),
      extensions: spec.kind === 'file' ? spec.extensions : [],
    });
    if (chosen !== null) {
      this.valueChange.emit(chosen);
    }
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
