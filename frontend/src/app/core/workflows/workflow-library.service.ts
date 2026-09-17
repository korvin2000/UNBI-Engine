import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { ENGINE_CONFIG } from '../engine.config';
import { WorkflowDoc } from '../graph/workflow.models';
import { messageOf } from '../settings/settings.service';

/** One workflow in the engine's library, as the listing describes it. */
export interface LibraryEntry {
  readonly id: string;
  readonly name: string;
  /** Whether the file lives in the library's favorites/ folder. */
  readonly favorite: boolean;
  readonly updatedAt: string;
  readonly size: number;
}

/** Why the library dialog is open: to pick a workflow, or to name the one being saved. */
export type LibraryMode = 'open' | 'save';

/** Turns the engine's raw document into a `WorkflowDoc`; injected so this service knows no file format. */
export type DocumentParser = (raw: unknown) => WorkflowDoc;

/**
 * The workflow library on the engine, and the one open library dialog.
 *
 * Workflows live in the engine's workflows directory — a folder of `.unbi.json` files, with the
 * favourites in `favorites/` — rather than in browser downloads, because a workflow is something to
 * come back to and a download is something to find again. The engine owns the folder; this holds
 * the listing and the calls.
 */
@Injectable({ providedIn: 'root' })
export class WorkflowLibraryService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ENGINE_CONFIG);

  private readonly entries = signal<readonly LibraryEntry[]>([]);
  private readonly where = signal('');
  private readonly failure = signal('');
  private readonly request = signal<LibraryMode | null>(null);

  readonly all = this.entries.asReadonly();
  /** The library's directory on the engine, for the dialog's subtitle. */
  readonly directory = this.where.asReadonly();
  readonly error = this.failure.asReadonly();
  /** Non-null while the dialog is open. */
  readonly open = this.request.asReadonly();

  readonly favorites = computed(() => this.entries().filter((entry) => entry.favorite));

  load(): void {
    this.http
      .get<{ directory?: string; workflows?: readonly LibraryEntry[] }>(`${this.config.httpBase}/api/workflows`)
      .pipe(
        tap((body) => {
          this.entries.set(body.workflows ?? []);
          this.where.set(body.directory ?? '');
          this.failure.set('');
        }),
        catchError((error: unknown) => {
          this.failure.set(messageOf(error));
          return of(null);
        }),
      )
      .subscribe();
  }

  show(mode: LibraryMode): void {
    this.request.set(mode);
    this.load();
  }

  close(): void {
    this.request.set(null);
  }

  find(id: string): LibraryEntry | undefined {
    const wanted = id.toLowerCase();
    return this.entries().find((entry) => entry.id.toLowerCase() === wanted);
  }

  read(id: string, parse: DocumentParser): Observable<{ entry: LibraryEntry; document: WorkflowDoc }> {
    return this.http
      .get<LibraryEntry & { document: unknown }>(`${this.config.httpBase}/api/workflows/${encodeURIComponent(id)}`)
      .pipe(map((body) => ({ entry: stripDocument(body), document: parse(body.document) })));
  }

  /** Saves under a name; a 409 means the name is taken and `overwrite` was not set. */
  save(name: string, document: unknown, overwrite: boolean): Observable<LibraryEntry> {
    return this.http
      .post<LibraryEntry>(`${this.config.httpBase}/api/workflows`, { name, document, overwrite })
      .pipe(tap((saved) => this.replace(saved)));
  }

  delete(id: string): Observable<void> {
    return this.http
      .delete<void>(`${this.config.httpBase}/api/workflows/${encodeURIComponent(id)}`)
      .pipe(tap(() => this.entries.update((current) => current.filter((entry) => entry.id !== id))));
  }

  setFavorite(id: string, favorite: boolean): Observable<LibraryEntry> {
    return this.http
      .post<LibraryEntry>(`${this.config.httpBase}/api/workflows/${encodeURIComponent(id)}/favorite`, { favorite })
      .pipe(tap((saved) => this.replace(saved, id)));
  }

  rename(id: string, name: string): Observable<LibraryEntry> {
    return this.http
      .post<LibraryEntry>(`${this.config.httpBase}/api/workflows/${encodeURIComponent(id)}/rename`, { name })
      .pipe(tap((saved) => this.replace(saved, id)));
  }

  /** Puts one entry into the listing in place of the one it replaces, keeping the alphabetical order. */
  private replace(saved: LibraryEntry, previousId: string = saved.id): void {
    this.entries.update((current) =>
      [...current.filter((entry) => entry.id !== previousId && entry.id !== saved.id), saved].sort((a, b) =>
        a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }),
      ),
    );
  }
}

function stripDocument(body: LibraryEntry & { document: unknown }): LibraryEntry {
  return { id: body.id, name: body.name, favorite: body.favorite, updatedAt: body.updatedAt, size: body.size };
}
