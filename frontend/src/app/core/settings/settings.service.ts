import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { ENGINE_CONFIG } from '../engine.config';
import { DEFAULT_LANGUAGE } from '../i18n/languages';
import { Translator } from '../i18n/translator';

/** One relocatable directory as the engine reports it. */
export interface EnginePath {
  /** What settings.json says, or '' for the default. */
  readonly configured: string;
  /** Where the engine is actually reading from right now. */
  readonly effective: string;
  readonly default: string;
}

export interface EngineSettings {
  /** Where settings.json lives. */
  readonly file: string;
  /** Why the file on disk was not used, or ''. */
  readonly error: string;
  readonly paths: { readonly data: EnginePath; readonly workflows: EnginePath };
  readonly preferences: Readonly<Record<string, unknown>>;
  readonly engine: { readonly version: string; readonly java: string; readonly os: string };
}

/** What a directory change did, beside the settings that resulted. */
export interface Relocation {
  readonly target: 'data' | 'workflows';
  readonly from: string;
  readonly to: string;
  readonly copied: number;
  readonly skipped: number;
}

/** One kind of thing a bundle can carry, as the engine lists it for the checklist. */
export interface BundleSection {
  readonly id: string;
  readonly label: string;
  readonly description: string;
  readonly sensitive: boolean;
  readonly count: number;
}

/** What a chosen .ucfg turned out to hold, before anything is imported. */
export interface BundleInspection {
  readonly engine: string;
  readonly createdAt: string;
  readonly encrypted: boolean;
  readonly sections: readonly {
    readonly id: string;
    readonly count: number;
    readonly known: boolean;
    readonly label: string;
    readonly sensitive: boolean;
  }[];
}

export type ConflictPolicy = 'skip' | 'replace' | 'keep-both';

export interface ImportReport {
  readonly sections: readonly {
    readonly id: string;
    readonly imported: number;
    readonly replaced: number;
    readonly skipped: number;
    readonly problems: readonly string[];
  }[];
}

/** The pages of the settings dialog, in rail order. */
export type SettingsPage = 'general' | 'storage' | 'profiles' | 'backup' | 'about';

/**
 * The engine's settings, and the one open settings dialog.
 *
 * Settings live on the engine, not in the browser, for the same reason presets do: a preference
 * set in one browser profile should hold in the next, and everything a user would want to carry to
 * another machine has to be somewhere the export can reach. The editor keeps a copy in a signal and
 * applies the one preference it owns — the language — the moment it arrives.
 *
 * Everything the dialog does goes through here, so the dialog holds only what is on screen.
 */
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ENGINE_CONFIG);
  private readonly translator = inject(Translator);

  private readonly state = signal<EngineSettings | null>(null);
  private readonly failure = signal('');
  private readonly page = signal<SettingsPage | null>(null);

  readonly settings = this.state.asReadonly();
  readonly error = this.failure.asReadonly();
  /** Non-null while the dialog is open: the page it is showing. */
  readonly open = this.page.asReadonly();

  readonly language = computed(() => {
    const chosen = this.state()?.preferences['language'];
    return typeof chosen === 'string' && chosen ? chosen : DEFAULT_LANGUAGE;
  });

  load(): void {
    this.http
      .get<EngineSettings>(`${this.config.httpBase}/api/settings`)
      .pipe(
        tap((settings) => this.accept(settings)),
        catchError((error: unknown) => {
          this.failure.set(messageOf(error));
          return of(null);
        }),
      )
      .subscribe();
  }

  show(page: SettingsPage = 'general'): void {
    this.page.set(page);
    // Fresh on every opening: another editor, or a hand-edited file, may have changed things.
    this.load();
  }

  /** Another page of the open dialog. */
  goTo(page: SettingsPage): void {
    this.page.set(page);
  }

  close(): void {
    this.page.set(null);
  }

  /** Merges preferences; a `null` value removes a key. The language takes effect on success. */
  savePreferences(changes: Readonly<Record<string, unknown>>): Observable<EngineSettings> {
    return this.http
      .post<EngineSettings>(`${this.config.httpBase}/api/settings/preferences`, changes)
      .pipe(tap((settings) => this.accept(settings)));
  }

  /** Points one directory elsewhere; '' means the default. */
  relocate(
    target: 'data' | 'workflows',
    directory: string,
    copyExisting: boolean,
  ): Observable<{ settings: EngineSettings; relocation: Relocation }> {
    return this.http
      .post<EngineSettings & { relocation: Relocation }>(`${this.config.httpBase}/api/settings/paths`, {
        target,
        directory,
        copyExisting,
      })
      .pipe(
        tap((settings) => this.accept(settings)),
        map((settings) => ({ settings, relocation: settings.relocation })),
      );
  }

  sections(): Observable<readonly BundleSection[]> {
    return this.http.get<readonly BundleSection[]>(`${this.config.httpBase}/api/settings/sections`);
  }

  /** The bundle as a blob, ready to be handed to a download link. */
  exportBundle(sections: readonly string[], password: string): Observable<Blob> {
    return this.http.post(
      `${this.config.httpBase}/api/settings/export`,
      { sections, password },
      { responseType: 'blob' },
    );
  }

  inspectBundle(file: File): Observable<BundleInspection> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<BundleInspection>(`${this.config.httpBase}/api/settings/import/inspect`, form);
  }

  importBundle(
    file: File,
    sections: readonly string[],
    password: string,
    conflicts: ConflictPolicy,
  ): Observable<ImportReport> {
    const form = new FormData();
    form.append('file', file, file.name);
    form.append('sections', sections.join(','));
    form.append('conflicts', conflicts);
    if (password) {
      form.append('password', password);
    }
    return this.http.post<ImportReport>(`${this.config.httpBase}/api/settings/import`, form);
  }

  private accept(settings: EngineSettings): void {
    this.state.set(settings);
    this.failure.set('');
    void this.translator.use(this.language());
  }
}

/** The engine's own sentence when it sent one, since it is the specific half of the answer. */
export function messageOf(error: unknown): string {
  const body = (error as { error?: { detail?: string; message?: string } } | null)?.error;
  return body?.detail ?? body?.message ?? 'Could not reach the engine';
}
