import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, of, tap } from 'rxjs';
import { ENGINE_CONFIG } from '../engine.config';
import {
  CategoryGroup,
  NodeSpec,
  compatibleTargetTypes,
  groupIntoCategories,
  parseCatalog,
} from './catalog.models';

/**
 * Fetches and holds the node catalog.
 *
 * Loaded once at startup. Everything the editor draws comes from here, so a failure to load is a
 * visible, recoverable state rather than an empty palette with no explanation.
 */
@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ENGINE_CONFIG);

  private readonly nodes = signal<readonly NodeSpec[]>([]);
  private readonly loadState = signal<'idle' | 'loading' | 'ready' | 'failed'>('idle');
  private readonly failure = signal<string>('');

  readonly all = this.nodes.asReadonly();
  readonly state = this.loadState.asReadonly();
  readonly error = this.failure.asReadonly();

  readonly byId = computed(() => new Map(this.nodes().map((spec) => [spec.id, spec])));
  readonly categories = computed<readonly CategoryGroup[]>(() => groupIntoCategories(this.nodes()));

  /**
   * Which input types each output type may legally reach, for the canvas's drag-time filter.
   *
   * Derived rather than stored: it follows entirely from the catalog, so there is no moment at
   * which the two can be out of step. See `compatibleTargetTypes`.
   */
  readonly compatibleTargets = computed(() => compatibleTargetTypes(this.nodes()));

  load(): void {
    if (this.loadState() === 'loading') {
      return;
    }
    this.loadState.set('loading');
    this.http
      .get<unknown>(`${this.config.httpBase}/api/catalog`)
      .pipe(
        tap((raw) => {
          this.nodes.set(parseCatalog(raw));
          this.loadState.set('ready');
        }),
        catchError((error: unknown) => {
          this.failure.set(
            error instanceof Error ? error.message : 'Could not reach the engine',
          );
          this.loadState.set('failed');
          return of(null);
        }),
      )
      .subscribe();
  }

  /**
   * Search across label, category and description.
   *
   * Matching the description too is what makes the palette useful before you know the vocabulary —
   * typing "regex" should find Search In Files even though the word is not in its name.
   */
  search(query: string): readonly NodeSpec[] {
    const needle = query.trim().toLowerCase();
    if (!needle) {
      return this.nodes();
    }
    return this.nodes().filter((spec) =>
      `${spec.label} ${spec.category} ${spec.subcategory} ${spec.description}`
        .toLowerCase()
        .includes(needle),
    );
  }
}
