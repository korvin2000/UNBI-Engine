import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { PresetService } from '../../core/presets/preset.service';
import { Icon } from '../icon';

/**
 * Naming a preset before it is saved.
 *
 * Three fields, because three are what make one findable again: a name, a group to file it under,
 * and a line saying what it is for. Discovery in the palette searches all three.
 *
 * Rendered once by the shell, like the file picker and for the same reason — a modal opened from a
 * node would live inside the canvas's transformed, clipped layer.
 */
@Component({
  selector: 'app-preset-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  templateUrl: './preset-dialog.html',
  styleUrl: './preset-dialog.scss',
})
export class PresetDialog {
  private readonly presets = inject(PresetService);

  protected readonly draft = this.presets.pending;
  protected readonly error = this.presets.error;
  protected readonly knownGroups = this.presets.groups;

  protected readonly name = signal('');
  protected readonly group = signal('');
  protected readonly description = signal('');

  protected readonly canSave = computed(() => this.name().trim().length > 0);

  constructor() {
    // Seeded from the draft each time the dialog opens, so re-opening it does not show what was
    // typed the time before.
    effect(() => {
      const draft = this.draft();
      if (draft) {
        this.name.set(draft.name);
        this.group.set(draft.group);
        this.description.set(draft.description);
      }
    });
  }

  protected save(): void {
    const draft = this.draft();
    if (!draft || !this.canSave()) {
      return;
    }
    this.presets.confirmSave({
      ...draft,
      name: this.name().trim(),
      group: this.group().trim(),
      description: this.description().trim(),
    });
  }

  protected cancel(): void {
    this.presets.cancelSave();
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.cancel();
    }
    // Enter saves from the single-line fields; the description is a textarea and keeps its newlines.
    if (event.key === 'Enter' && !(event.target instanceof HTMLTextAreaElement)) {
      event.preventDefault();
      this.save();
    }
  }

  protected setName(event: Event): void {
    this.name.set((event.target as HTMLInputElement).value);
  }

  protected setGroup(event: Event): void {
    this.group.set((event.target as HTMLInputElement).value);
  }

  protected setDescription(event: Event): void {
    this.description.set((event.target as HTMLTextAreaElement).value);
  }
}
