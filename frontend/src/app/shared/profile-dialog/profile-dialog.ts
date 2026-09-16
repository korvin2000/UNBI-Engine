import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { NodeInputSpec } from '../../core/catalog/catalog.models';
import {
  Profile,
  ProfileService,
  ProfileTestResult,
} from '../../core/profiles/profile.service';
import { NodeWidget } from '../../widgets/node-widget';
import { Icon } from '../icon';

/**
 * Where profiles are made, tested, edited and deleted.
 *
 * Generic on purpose: the fields it draws are whatever the engine declared for the schema, drawn
 * with the same widgets a node uses. This dialog knows that a profile has a name, a description and
 * values, and nothing about what an endpoint is — which is what lets a second schema appear with no
 * change here.
 *
 * Rendered once by the shell, like the file picker and for the same reason: a dialog opened from a
 * node would live in the canvas's transformed, clipped layer.
 */
@Component({
  selector: 'app-profile-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, NodeWidget],
  templateUrl: './profile-dialog.html',
  styleUrl: './profile-dialog.scss',
  host: {
    '(document:keydown)': 'onKeydown($event)',
  },
})
export class ProfileDialog {
  private readonly profiles = inject(ProfileService);

  protected readonly request = this.profiles.editing;

  /** The schema and its profiles, for whichever schema is open. */
  protected readonly state = computed(() => {
    const request = this.request();
    return request ? this.profiles.state(request.schema)() : null;
  });

  protected readonly schema = computed(() => this.state()?.schema ?? null);
  protected readonly list = computed(() => this.state()?.profiles ?? []);

  /** The id being edited, or '' for a profile that has not been saved yet. */
  protected readonly selectedId = signal('');
  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly draft = signal<Readonly<Record<string, unknown>>>({});

  protected readonly saving = signal(false);
  protected readonly testing = signal(false);
  protected readonly confirmingDelete = signal(false);
  protected readonly testResult = signal<ProfileTestResult | null>(null);
  protected readonly notice = signal('');
  protected readonly failure = signal('');

  protected readonly selected = computed<Profile | null>(
    () => this.list().find((profile) => profile.id === this.selectedId()) ?? null,
  );

  protected readonly plainFields = computed(() => (this.schema()?.fields ?? []).filter((f) => !f.advanced));
  protected readonly advancedFields = computed(() => (this.schema()?.fields ?? []).filter((f) => f.advanced));

  protected readonly canSave = computed(() => this.name().trim().length > 0 && !this.saving());

  /** Whether what is on screen differs from what is saved. */
  protected readonly dirty = computed(() => {
    const saved = this.selected();
    if (!saved) {
      return this.name().trim().length > 0 || Object.keys(this.draft()).length > 0;
    }
    return (
      saved.name !== this.name().trim() ||
      saved.description !== this.description().trim() ||
      serialise(this.withDefaults(saved.values)) !== serialise(this.withDefaults(this.draft()))
    );
  });

  constructor() {
    // Each opening starts on the profile the widget pointed at, or the first one, or a blank draft
    // when there are none yet — and re-seeds once the list has actually arrived.
    effect(() => {
      const request = this.request();
      const list = this.list();
      if (!request) {
        return;
      }
      untracked(() => {
        if (this.selectedId() && list.some((profile) => profile.id === this.selectedId())) {
          return;
        }
        const first = list.find((profile) => profile.id === request.current) ?? list[0] ?? null;
        if (first) {
          this.select(first);
        } else if (!this.selectedId() && !this.name()) {
          this.startNew();
        }
      });
    });
  }

  protected select(profile: Profile): void {
    this.selectedId.set(profile.id);
    this.name.set(profile.name);
    this.description.set(profile.description);
    this.draft.set(this.withDefaults(profile.values));
    this.clearMessages();
  }

  protected startNew(): void {
    this.selectedId.set('');
    this.name.set('');
    this.description.set('');
    this.draft.set(this.withDefaults({}));
    this.clearMessages();
  }

  /** A copy to start from: the same values under a new name, unsaved until Save. */
  protected duplicate(): void {
    const source = this.selected();
    if (!source) {
      return;
    }
    this.selectedId.set('');
    this.name.set(`${source.name} copy`);
    this.clearMessages();
  }

  protected setValue(key: string, value: unknown): void {
    this.draft.update((current) => ({ ...current, [key]: value }));
    // A test result describes the values it ran against, and they just changed.
    this.testResult.set(null);
  }

  protected save(): void {
    const request = this.request();
    if (!request || !this.canSave()) {
      return;
    }
    this.saving.set(true);
    this.failure.set('');
    this.profiles
      .save(request.schema, {
        id: this.selectedId(),
        name: this.name().trim(),
        description: this.description().trim(),
        values: this.draft(),
      })
      .subscribe({
        next: (saved) => {
          this.saving.set(false);
          this.select(saved);
          this.notice.set(`Saved ${saved.name}.`);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.failure.set(messageOf(error));
        },
      });
  }

  protected test(): void {
    const request = this.request();
    if (!request || this.testing()) {
      return;
    }
    this.testing.set(true);
    this.testResult.set(null);
    this.profiles.test(request.schema, this.draft()).subscribe((result) => {
      this.testing.set(false);
      this.testResult.set(result);
    });
  }

  protected askDelete(): void {
    this.confirmingDelete.set(true);
  }

  protected cancelDelete(): void {
    this.confirmingDelete.set(false);
  }

  protected deleteSelected(): void {
    const request = this.request();
    const doomed = this.selected();
    if (!request || !doomed) {
      return;
    }
    this.confirmingDelete.set(false);
    this.profiles.delete(request.schema, doomed.id).subscribe({
      next: () => {
        const remaining = this.list().filter((profile) => profile.id !== doomed.id);
        if (remaining.length > 0) {
          this.select(remaining[0]);
        } else {
          this.startNew();
        }
        this.notice.set(`Deleted ${doomed.name}.`);
      },
      error: (error: unknown) => this.failure.set(messageOf(error)),
    });
  }

  /**
   * Done: the node points at what is on screen.
   *
   * Saves first when something changed and can be saved, because the profile the user just typed
   * out is the one they mean to use — asking them to press Save before Done would only teach them
   * that Done loses work.
   */
  protected finish(): void {
    if (this.dirty() && this.canSave()) {
      const request = this.request();
      if (!request) {
        return;
      }
      this.saving.set(true);
      this.profiles
        .save(request.schema, {
          id: this.selectedId(),
          name: this.name().trim(),
          description: this.description().trim(),
          values: this.draft(),
        })
        .subscribe({
          next: (saved) => {
            this.saving.set(false);
            this.close(saved.id);
          },
          error: (error: unknown) => {
            this.saving.set(false);
            this.failure.set(messageOf(error));
          },
        });
      return;
    }
    this.close(this.selectedId() || null);
  }

  /** Close without saving what is on screen; the node keeps whichever saved profile is selected. */
  protected dismiss(): void {
    this.close(this.selectedId() || null);
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (!this.request()) {
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      this.dismiss();
    }
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      this.finish();
    }
  }

  protected setName(event: Event): void {
    this.name.set((event.target as HTMLInputElement).value);
  }

  protected setDescription(event: Event): void {
    this.description.set((event.target as HTMLInputElement).value);
  }

  protected valueOf(field: NodeInputSpec): unknown {
    const value = this.draft()[field.key];
    return value === undefined ? field.defaultValue : value;
  }

  /** Whether a field's declared condition holds against the draft. */
  protected applies(field: NodeInputSpec): boolean {
    const condition = field.showWhen;
    if (!condition) {
      return true;
    }
    const sibling = this.schema()?.fields.find((candidate) => candidate.key === condition.key);
    return condition.values.includes(String(sibling ? this.valueOf(sibling) : undefined));
  }

  private close(chosen: string | null): void {
    this.selectedId.set('');
    this.name.set('');
    this.description.set('');
    this.draft.set({});
    this.clearMessages();
    this.profiles.finish(chosen);
  }

  private clearMessages(): void {
    this.testResult.set(null);
    this.notice.set('');
    this.failure.set('');
    this.confirmingDelete.set(false);
  }

  /** Every declared field present, so a comparison of two drafts is a comparison of the same keys. */
  private withDefaults(values: Readonly<Record<string, unknown>>): Record<string, unknown> {
    const complete: Record<string, unknown> = {};
    for (const field of this.schema()?.fields ?? []) {
      const value = values[field.key];
      complete[field.key] = value === undefined || value === null ? field.defaultValue : value;
    }
    return complete;
  }
}

function serialise(value: unknown): string {
  try {
    return JSON.stringify(value ?? null);
  } catch {
    return String(value);
  }
}

function messageOf(error: unknown): string {
  const body = (error as { error?: { detail?: string; message?: string } } | null)?.error;
  return body?.detail ?? body?.message ?? 'Could not reach the engine';
}
