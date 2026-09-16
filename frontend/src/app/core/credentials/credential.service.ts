import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { ENGINE_CONFIG } from '../engine.config';

/** A secret the engine holds, by name and by where it found it. Never a value. */
export interface CredentialName {
  readonly name: string;
  readonly source: string;
  /** Only what the engine's own credentials file holds can be removed from here. */
  readonly removable: boolean;
}

/**
 * The names of the secrets the engine holds, and the two things the editor may do about them.
 *
 * Asymmetric on purpose: a key can be added from here, travelling to the engine once, and can be
 * removed, and can never be read back — this service has no method that returns a value and the
 * engine has no endpoint that would. From the moment it is saved a credential is a name in a
 * dropdown, which is the same property the environment-variable path has.
 */
@Injectable({ providedIn: 'root' })
export class CredentialService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ENGINE_CONFIG);

  private readonly known = signal<readonly CredentialName[]>([]);
  private readonly failure = signal('');

  readonly names = this.known.asReadonly();
  readonly error = this.failure.asReadonly();

  load(): void {
    this.http
      .get<Record<string, unknown>>(`${this.config.httpBase}/api/credentials`)
      .pipe(
        tap((body) => {
          const raw = Array.isArray(body['credentials']) ? body['credentials'] : [];
          this.known.set(
            raw.map((entry) => {
              const item = entry as Record<string, unknown>;
              return {
                name: String(item['name'] ?? ''),
                source: String(item['source'] ?? ''),
                removable: item['removable'] === true,
              };
            }),
          );
          this.failure.set('');
        }),
        catchError(() => of(null)),
      )
      .subscribe();
  }

  /**
   * Sends a key to the engine. Resolves to whether it was accepted; the value is not kept here.
   *
   * A boolean rather than the response body, because nothing about a credential is worth passing
   * on — the name list is reloaded from the engine instead.
   */
  add(name: string, value: string): Observable<boolean> {
    return this.http
      .post<Record<string, unknown>>(`${this.config.httpBase}/api/credentials`, { name, value })
      .pipe(
        tap(() => {
          this.failure.set('');
          this.load();
        }),
        map(() => true),
        catchError((error: unknown) => {
          this.failure.set(messageOf(error));
          return of(false);
        }),
      );
  }

  remove(name: string): void {
    this.http
      .delete<void>(`${this.config.httpBase}/api/credentials/${encodeURIComponent(name)}`)
      .pipe(
        tap(() => this.load()),
        catchError((error: unknown) => {
          this.failure.set(messageOf(error));
          return of(null);
        }),
      )
      .subscribe();
  }
}

function messageOf(error: unknown): string {
  const body = (error as { error?: { detail?: string; message?: string } } | null)?.error;
  return body?.detail ?? body?.message ?? 'Could not reach the engine';
}
