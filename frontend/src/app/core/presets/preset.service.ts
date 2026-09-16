import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, of, tap } from 'rxjs';
import { ENGINE_CONFIG } from '../engine.config';

/** A configured node, saved under a name so it can be used again in another workflow. */
export interface Preset {
  readonly id: string;
  readonly name: string;
  readonly group: string;
  readonly nodeType: string;
  /** The node type's own label, resolved by the engine so the palette needs no lookup. */
  readonly label: string;
  readonly description: string;
  readonly updatedAt: string;
  readonly values: Readonly<Record<string, unknown>>;
}

/** What the save dialog collects. */
export interface PresetDraft {
  readonly name: string;
  readonly group: string;
  readonly description: string;
  readonly nodeType: string;
  readonly values: Readonly<Record<string, unknown>>;
}

/**
 * Saved node configurations, and the one open save dialog.
 *
 * Presets live on the engine rather than in browser storage on purpose: a prompt worth keeping is
 * worth having on the machine that runs the workflows, and browser storage would lose it on a
 * different browser, a different profile, or a cleared cache.
 *
 * The dialog is a single instance rendered by the shell, for the same reason the file picker is: a
 * modal opened from inside the canvas would live in a transformed, clipped layer.
 */
@Injectable({ providedIn: 'root' })
export class PresetService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ENGINE_CONFIG);

  private readonly presets = signal<readonly Preset[]>([]);
  private readonly draft = signal<PresetDraft | null>(null);
  private readonly failure = signal<string>('');

  readonly all = this.presets.asReadonly();
  readonly error = this.failure.asReadonly();
  /** Non-null while the save dialog is open; the dialog renders from this. */
  readonly pending = this.draft.asReadonly();

  readonly groups = computed(() => {
    const found = new Set<string>();
    for (const preset of this.presets()) {
      if (preset.group) {
        found.add(preset.group);
      }
    }
    return [...found].sort((a, b) => a.localeCompare(b));
  });

  /** Presets for one node type — what a node's own "load preset" list would show. */
  forType(nodeType: string): readonly Preset[] {
    return this.presets().filter((preset) => preset.nodeType === nodeType);
  }

  load(): void {
    this.http
      .get<{ presets?: readonly Preset[] }>(`${this.config.httpBase}/api/presets`)
      .pipe(
        tap((body) => {
          this.presets.set(body.presets ?? []);
          this.failure.set('');
        }),
        catchError((error: unknown) => {
          this.failure.set(error instanceof Error ? error.message : 'Could not reach the engine');
          return of(null);
        }),
      )
      .subscribe();
  }

  /** Opens the save dialog. Resolves with the saved preset, or null if it was dismissed. */
  askToSave(draft: PresetDraft): Promise<Preset | null> {
    this.settle(null);
    this.draft.set(draft);
    return new Promise((resolve) => {
      this.resolve = resolve;
    });
  }

  /** Called by the dialog when the user confirms. */
  confirmSave(draft: PresetDraft): void {
    this.save(draft, (saved) => {
      this.draft.set(null);
      this.settle(saved);
    });
  }

  /**
   * Saves without the dialog, for a caller that already collected a name.
   *
   * The prompt editor is that caller: it asks for the name in place, beside the text being saved,
   * because stacking a second modal on top of the one the user is typing in would be a worse way
   * to ask one question.
   */
  save(draft: PresetDraft, then?: (saved: Preset) => void): void {
    this.http
      .post<Preset>(`${this.config.httpBase}/api/presets`, draft)
      .pipe(
        tap((saved) => {
          // Replace by id rather than reloading: the list is already correct everywhere else, and
          // a round trip would make the palette flicker for no new information.
          this.presets.update((current) => [
            saved,
            ...current.filter((preset) => preset.id !== saved.id),
          ]);
          this.failure.set('');
          then?.(saved);
        }),
        catchError((error: unknown) => {
          this.failure.set(messageOf(error));
          return of(null);
        }),
      )
      .subscribe();
  }

  cancelSave(): void {
    this.draft.set(null);
    this.settle(null);
  }

  delete(id: string): void {
    this.http
      .delete<void>(`${this.config.httpBase}/api/presets/${encodeURIComponent(id)}`)
      .pipe(
        tap(() => this.presets.update((current) => current.filter((preset) => preset.id !== id))),
        catchError((error: unknown) => {
          this.failure.set(messageOf(error));
          return of(null);
        }),
      )
      .subscribe();
  }

  private resolve: ((saved: Preset | null) => void) | null = null;

  private settle(saved: Preset | null): void {
    const resolve = this.resolve;
    this.resolve = null;
    resolve?.(saved);
  }
}

/** The engine's own sentence when it sent one, since it is the specific half of the answer. */
function messageOf(error: unknown): string {
  const body = (error as { error?: { detail?: string; message?: string } } | null)?.error;
  return body?.detail ?? body?.message ?? 'Could not save the preset';
}
