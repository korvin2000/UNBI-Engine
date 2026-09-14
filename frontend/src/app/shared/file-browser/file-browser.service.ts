import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ENGINE_CONFIG } from '../../core/engine.config';

export interface FileEntry {
  readonly name: string;
  readonly path: string;
  readonly directory: boolean;
  readonly size: number;
  readonly modified: string | null;
}

export interface DirectoryListing {
  readonly path: string;
  readonly parent: string | null;
  readonly separator: string;
  readonly roots: readonly string[];
  readonly entries: readonly FileEntry[];
  readonly error: string | null;
}

/** What a caller asks the dialog for. */
export interface PickRequest {
  readonly mode: 'file' | 'directory';
  readonly title: string;
  /** Where to open. A file path opens its folder with the file selected. */
  readonly startAt: string;
  /** For file mode: only these extensions are listed. Empty means everything. */
  readonly extensions: readonly string[];
}

/**
 * Browsing the engine's filesystem, and the one open picker dialog.
 *
 * <p>The picker has to run against the *engine's* disk, not the browser's: every path a node takes
 * is opened by the backend, so a browser file input — which can only hand back a sandboxed upload —
 * would produce a value the engine cannot use. `GET /api/fs` is the server side of that.
 *
 * The dialog is a single instance rendered by the shell rather than one per widget: a modal that
 * exists once cannot be opened twice, and a widget deep inside a transformed, clipped canvas cannot
 * host an overlay without being clipped by it.
 */
@Injectable({ providedIn: 'root' })
export class FileBrowserService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ENGINE_CONFIG);

  private readonly request = signal<PickRequest | null>(null);
  private resolve: ((chosen: string | null) => void) | null = null;

  /** Non-null while the dialog is open; the dialog renders from this. */
  readonly open = this.request.asReadonly();

  /**
   * Opens the dialog and resolves with the chosen path, or `null` if it was dismissed.
   *
   * Opening while one is already open settles the previous request as a dismissal rather than
   * leaving its promise pending for the lifetime of the page.
   */
  pick(request: PickRequest): Promise<string | null> {
    this.settle(null);
    this.request.set(request);
    return new Promise<string | null>((resolve) => {
      this.resolve = resolve;
    });
  }

  choose(path: string): void {
    this.settle(path);
  }

  cancel(): void {
    this.settle(null);
  }

  list(path: string, mode: 'file' | 'directory', extensions: readonly string[]): Observable<DirectoryListing> {
    const query = new URLSearchParams();
    if (path) {
      query.set('path', path);
    }
    if (mode === 'directory') {
      query.set('directoriesOnly', 'true');
    }
    if (extensions.length > 0) {
      query.set('extensions', extensions.join(','));
    }
    return this.http
      .get<DirectoryListing>(`${this.config.httpBase}/api/fs?${query.toString()}`)
      .pipe(map((listing) => normalise(listing)));
  }

  private settle(path: string | null): void {
    const pending = this.resolve;
    this.resolve = null;
    this.request.set(null);
    pending?.(path);
  }
}

/** Defends the dialog against a malformed response rather than letting it render `undefined`. */
function normalise(listing: DirectoryListing): DirectoryListing {
  return {
    path: String(listing?.path ?? ''),
    parent: listing?.parent ?? null,
    separator: listing?.separator || '/',
    roots: Array.isArray(listing?.roots) ? listing.roots : [],
    entries: Array.isArray(listing?.entries) ? listing.entries : [],
    error: listing?.error ?? null,
  };
}
