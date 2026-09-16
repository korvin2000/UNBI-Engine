import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { catchError, of, tap } from 'rxjs';
import { ENGINE_CONFIG } from '../engine.config';
import { DropdownOption } from './catalog.models';

/**
 * Dropdown options the engine knows and a descriptor cannot.
 *
 * A node descriptor is built once at startup, so a list that changes while the engine is running —
 * which credentials exist, which preset groups are in use — has to be fetched separately. A widget
 * names a catalog key; this holds what the engine says is in it.
 *
 * That is what keeps the property the whole design rests on intact: the editor learns *that* a field
 * offers credentials from the descriptor, and *which* from here, and never from code written about
 * one particular node.
 *
 * A failure is not worth a visible error — a dropdown with no server-side options still renders its
 * static ones, and the fields that use this all accept a typed value too.
 */
@Injectable({ providedIn: 'root' })
export class OptionCatalogService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ENGINE_CONFIG);

  private readonly catalogs = signal<ReadonlyMap<string, readonly DropdownOption[]>>(new Map());

  readonly all = this.catalogs.asReadonly();

  load(): void {
    this.http
      .get<Record<string, unknown>>(`${this.config.httpBase}/api/options`)
      .pipe(
        tap((raw) => this.catalogs.set(parseOptionCatalogs(raw))),
        catchError(() => of(null)),
      )
      .subscribe();
  }

  optionsFor(key: string): readonly DropdownOption[] {
    return this.catalogs().get(key) ?? [];
  }
}

export function parseOptionCatalogs(
  raw: Record<string, unknown> | null,
): ReadonlyMap<string, readonly DropdownOption[]> {
  const catalogs = new Map<string, readonly DropdownOption[]>();
  if (!raw || typeof raw !== 'object') {
    return catalogs;
  }
  for (const [key, value] of Object.entries(raw)) {
    if (!Array.isArray(value)) {
      continue;
    }
    catalogs.set(
      key,
      value.map((entry) => {
        const option = entry as Record<string, unknown>;
        return { value: String(option['value'] ?? ''), label: String(option['label'] ?? '') };
      }),
    );
  }
  return catalogs;
}
