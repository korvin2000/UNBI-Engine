import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { Translator } from '../../core/i18n/translator';
import { messageOf } from '../../core/settings/settings.service';
import { LibraryEntry, WorkflowLibraryService } from '../../core/workflows/workflow-library.service';
import { WorkflowSession } from '../../core/workflows/workflow-session';
import { WorkflowFileService } from '../../editor/workflow-file.service';
import { Autofocus } from '../autofocus';
import { Icon } from '../icon';
import { MinuteClock } from '../minute-clock';

/** A question the footer is asking before it does something that cannot be undone. */
interface Confirmation {
  readonly kind: 'delete' | 'replace' | 'discard' | 'import';
  readonly entry: LibraryEntry | null;
}

interface Notice {
  readonly text: string;
  readonly kind: 'ok' | 'error';
}

/**
 * The workflow library: open a workflow from the engine, or save the one on the canvas to it.
 *
 * One dialog with two modes rather than two dialogs, because both are the same list — the
 * difference is a name field at the top and what the primary button says. Favourites are starred
 * in place; renaming and deleting are on each row; importing and exporting files stay in the
 * footer, where the browser's file paths belong.
 *
 * Anything irreversible — deleting, replacing, discarding unsaved changes — is asked once, in the
 * footer, in words: a browser confirm nobody reads is not a safety net.
 */
@Component({
  selector: 'app-workflow-library',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, TranslatePipe, Autofocus],
  templateUrl: './workflow-library.html',
  styleUrl: './workflow-library.scss',
  host: {
    '(document:keydown)': 'onKeydown($event)',
  },
})
export class WorkflowLibrary {
  private readonly library = inject(WorkflowLibraryService);
  private readonly files = inject(WorkflowFileService);
  private readonly session = inject(WorkflowSession);
  private readonly translator = inject(Translator);
  private readonly clock = inject(MinuteClock);

  protected readonly mode = this.library.open;
  protected readonly directory = this.library.directory;
  protected readonly loadError = this.library.error;
  protected readonly dirty = this.session.dirty;
  protected readonly currentName = this.session.name;

  protected readonly query = signal('');
  protected readonly selectedId = signal('');
  protected readonly name = signal('');
  protected readonly busy = signal(false);
  protected readonly notice = signal<Notice | null>(null);
  protected readonly confirming = signal<Confirmation | null>(null);
  protected readonly renaming = signal<{ id: string; draft: string } | null>(null);

  /** Favourites first, then the rest, each alphabetically; filtered by the search box. */
  protected readonly entries = computed(() => {
    const needle = this.query().trim().toLowerCase();
    return this.library
      .all()
      .filter((entry) => !needle || entry.name.toLowerCase().includes(needle))
      .sort(
        (a, b) =>
          Number(b.favorite) - Number(a.favorite) ||
          a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }),
      );
  });

  protected readonly selected = computed(
    () => this.entries().find((entry) => entry.id === this.selectedId()) ?? null,
  );

  protected readonly canConfirm = computed(() =>
    this.mode() === 'save' ? this.name().trim().length > 0 && !this.busy() : this.selected() !== null && !this.busy(),
  );

  constructor() {
    // Each opening starts on the workflow that is on the canvas, with its name in the box.
    effect(() => {
      const mode = this.mode();
      untracked(() => {
        if (!mode) {
          return;
        }
        this.query.set('');
        this.notice.set(null);
        this.confirming.set(null);
        this.renaming.set(null);
        this.busy.set(false);
        this.name.set(this.session.name());
        this.selectedId.set(this.session.current()?.id ?? '');
      });
    });
  }

  protected close(): void {
    this.library.close();
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (!this.mode()) {
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      if (this.renaming()) {
        this.renaming.set(null);
      } else if (this.confirming()) {
        this.confirming.set(null);
      } else {
        this.close();
      }
    }
  }

  protected onSearch(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
    this.confirming.set(null);
  }

  protected setName(event: Event): void {
    this.name.set((event.target as HTMLInputElement).value);
  }

  protected select(entry: LibraryEntry): void {
    this.selectedId.set(entry.id);
    if (this.mode() === 'save') {
      this.name.set(entry.name);
    }
    this.confirming.set(null);
  }

  /** Double-click or Enter on a row: choose it and go. */
  protected activate(entry: LibraryEntry): void {
    this.select(entry);
    this.confirm();
  }

  /** The primary button: open the selection, or save under the name. */
  protected confirm(): void {
    if (!this.canConfirm()) {
      return;
    }
    if (this.mode() === 'open') {
      const entry = this.selected();
      if (!entry) {
        return;
      }
      if (this.session.dirty()) {
        this.confirming.set({ kind: 'discard', entry });
      } else {
        this.openEntry(entry);
      }
      return;
    }
    const name = this.name().trim();
    const existing = this.library.find(name);
    const current = this.session.current();
    if (existing && existing.id !== current?.id) {
      this.confirming.set({ kind: 'replace', entry: existing });
    } else {
      this.save(name, existing !== undefined);
    }
  }

  protected proceed(): void {
    const ask = this.confirming();
    if (!ask) {
      return;
    }
    this.confirming.set(null);
    switch (ask.kind) {
      case 'discard':
        if (ask.entry) {
          this.openEntry(ask.entry);
        }
        break;
      case 'replace':
        if (ask.entry) {
          this.save(ask.entry.name, true);
        }
        break;
      case 'delete':
        if (ask.entry) {
          this.deleteEntry(ask.entry);
        }
        break;
      case 'import':
        this.pickAndImport();
        break;
    }
  }

  protected cancelConfirm(): void {
    this.confirming.set(null);
  }

  protected toggleFavorite(entry: LibraryEntry, event: Event): void {
    event.stopPropagation();
    this.files.setFavorite(entry, !entry.favorite).subscribe({
      error: (error: unknown) => this.notice.set({ text: messageOf(error), kind: 'error' }),
    });
  }

  protected askDelete(entry: LibraryEntry, event: Event): void {
    event.stopPropagation();
    this.confirming.set({ kind: 'delete', entry });
  }

  protected startRename(entry: LibraryEntry, event: Event): void {
    event.stopPropagation();
    this.renaming.set({ id: entry.id, draft: entry.name });
  }

  protected setRenameDraft(event: Event): void {
    const draft = (event.target as HTMLInputElement).value;
    this.renaming.update((current) => (current ? { ...current, draft } : current));
  }

  protected commitRename(event: Event): void {
    event.preventDefault();
    // Enter must not reach the row, whose own Enter opens the workflow.
    event.stopPropagation();
    const change = this.renaming();
    const entry = change ? this.library.find(change.id) : undefined;
    if (!change || !entry) {
      return;
    }
    const name = change.draft.trim();
    this.renaming.set(null);
    if (!name || name === entry.name) {
      return;
    }
    this.files.rename(entry, name).subscribe({
      next: (renamed) => {
        if (this.selectedId() === entry.id) {
          this.selectedId.set(renamed.id);
        }
        this.notice.set({ text: this.translator.t('library.renamed', { name: renamed.name }), kind: 'ok' });
      },
      error: (error: unknown) => this.notice.set({ text: messageOf(error), kind: 'error' }),
    });
  }

  protected cancelRename(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.renaming.set(null);
  }

  /** A file from the browser replaces the canvas, so unsaved changes are asked about first. */
  protected importFile(): void {
    if (this.session.dirty()) {
      this.confirming.set({ kind: 'import', entry: null });
    } else {
      this.pickAndImport();
    }
  }

  protected exportFile(): void {
    this.files.exportToFile();
  }

  /** "3 minutes ago", "yesterday", or a date once it is old — recomputed as the minutes pass. */
  protected when(entry: LibraryEntry): string {
    this.clock.tick();
    const then = new Date(entry.updatedAt).getTime();
    if (Number.isNaN(then)) {
      return entry.updatedAt;
    }
    const language = this.translator.language();
    const minutes = Math.round((then - Date.now()) / 60_000);
    if (Math.abs(minutes) < 1) {
      return this.translator.t('common.justNow');
    }
    const relative = new Intl.RelativeTimeFormat(language, { numeric: 'auto' });
    if (Math.abs(minutes) < 60) {
      return relative.format(minutes, 'minute');
    }
    const hours = Math.round(minutes / 60);
    if (Math.abs(hours) < 24) {
      return relative.format(hours, 'hour');
    }
    const days = Math.round(hours / 24);
    if (Math.abs(days) < 30) {
      return relative.format(days, 'day');
    }
    return new Intl.DateTimeFormat(language, { dateStyle: 'medium' }).format(then);
  }

  private openEntry(entry: LibraryEntry): void {
    this.busy.set(true);
    this.files.open(entry).subscribe({
      next: () => {
        this.busy.set(false);
        this.close();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notice.set({ text: messageOf(error), kind: 'error' });
      },
    });
  }

  private save(name: string, overwrite: boolean): void {
    this.busy.set(true);
    this.files.saveAs(name, overwrite).subscribe({
      next: () => {
        this.busy.set(false);
        this.close();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notice.set({ text: messageOf(error), kind: 'error' });
      },
    });
  }

  private deleteEntry(entry: LibraryEntry): void {
    this.files.delete(entry).subscribe({
      next: () => {
        if (this.selectedId() === entry.id) {
          this.selectedId.set('');
        }
        this.notice.set({ text: this.translator.t('library.deleted', { name: entry.name }), kind: 'ok' });
      },
      error: (error: unknown) => this.notice.set({ text: messageOf(error), kind: 'error' }),
    });
  }

  private pickAndImport(): void {
    this.files.importFromFile().subscribe((opened) => {
      if (opened) {
        this.close();
      } else if (this.files.error()) {
        this.notice.set({ text: this.files.error(), kind: 'error' });
      }
    });
  }
}
