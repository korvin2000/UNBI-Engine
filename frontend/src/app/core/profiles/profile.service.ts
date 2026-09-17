import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { ENGINE_CONFIG } from '../engine.config';
import { NodeInputSpec, parseInputSpec } from '../catalog/catalog.models';
import type { CredentialDraft } from '../credentials/credential.service';

/** One OpenAPI server variable exposed for review before an endpoint patch is applied. */
export interface OpenApiServerVariable {
  readonly default?: string;
  readonly enum?: readonly string[];
  readonly required: boolean;
  readonly value?: string;
}

/** A server candidate returned by the bounded OpenAPI endpoint metadata projection. */
export interface OpenApiServer {
  readonly id: string;
  readonly url: string;
  readonly variables: Readonly<Record<string, OpenApiServerVariable>>;
}

/** A recognized generation operation, or an unavailable candidate with its concrete reason. */
export interface OpenApiOperation {
  readonly id: string;
  readonly label: string;
  readonly path: string;
  readonly apiFormat: string;
  readonly available: boolean;
  readonly reason?: string;
}

/** One complete OpenAPI security alternative; unavailable alternatives remain visible for review. */
export interface OpenApiSecurityAlternative {
  readonly id: string;
  readonly label: string;
  readonly available: boolean;
  readonly reason?: string;
  readonly auth?: string;
}

/** A non-secret parser or projection finding, addressed to its source document pointer. */
export interface OpenApiImportIssue {
  readonly pointer: string;
  readonly severity: 'error' | 'warning';
  readonly message: string;
}

/** The exact review selections sent back with an OpenAPI endpoint import request. */
export interface OpenApiImportRequest {
  readonly document: string;
  readonly documentUri?: string;
  readonly operation?: string;
  readonly server?: string;
  readonly variables?: Readonly<Record<string, string>>;
  readonly securityAlternative?: string;
  readonly apiFormat?: string;
}

/** A secret-free OpenAPI import preview. Applying its values never persists a profile by itself. */
export interface OpenApiImportResult {
  readonly servers: readonly OpenApiServer[];
  readonly operations: readonly OpenApiOperation[];
  readonly securityAlternatives: readonly OpenApiSecurityAlternative[];
  readonly values: Readonly<Record<string, unknown>>;
  readonly credentialDraft: CredentialDraft | null;
  readonly issues: readonly OpenApiImportIssue[];
  readonly complete: boolean;
  readonly selectedServer?: string;
  readonly selectedOperation?: string;
  readonly selectedSecurityAlternative?: string;
}

/** The fields one kind of profile holds, as the engine declares them. */
export interface ProfileSchema {
  readonly id: string;
  readonly label: string;
  /** Whether the dialog may offer a Test button that tries the draft out before it is saved. */
  readonly testable: boolean;
  readonly importFormats: readonly string[];
  readonly fields: readonly NodeInputSpec[];
}

/** One saved, named configuration under a schema. */
export interface Profile {
  readonly id: string;
  readonly schema: string;
  readonly name: string;
  readonly description: string;
  readonly updatedAt: string;
  readonly values: Readonly<Record<string, unknown>>;
}

/** What the dialog sends to save: a blank id means "new". */
export interface ProfileDraft {
  readonly id: string;
  readonly name: string;
  readonly description: string;
  readonly values: Readonly<Record<string, unknown>>;
}

export interface ProfileTestResult {
  readonly ok: boolean;
  readonly message: string;
  readonly details: readonly string[];
}

/** One kind of profile the engine has, as the settings page lists them. */
export interface ProfileSchemaSummary {
  readonly id: string;
  readonly label: string;
  readonly testable: boolean;
  readonly count: number;
}

/** What a widget asks the dialog to open with, and what it gets back: the id to point at. */
export interface ProfileEditRequest {
  readonly schema: string;
  /** The profile to open on, or '' for whichever comes first. */
  readonly current: string;
  /** Monotonic identity for guarding asynchronous dialog work. */
  readonly id?: number;
}

interface SchemaState {
  readonly schema: ProfileSchema | null;
  readonly profiles: readonly Profile[];
  readonly error: string;
}

const EMPTY: SchemaState = { schema: null, profiles: [], error: '' };

/**
 * Profiles kept on the engine, per schema, and the one open profile dialog.
 *
 * A profile is referenced by id from a node and resolved on the engine when it runs, which is the
 * property that makes a workflow portable: "the endpoint called openrouter" means something on
 * every machine and something different on each. This service holds what the engine says each
 * schema looks like and which profiles exist under it, and it knows nothing about any schema in
 * particular — the dialog draws whatever fields the engine declared.
 *
 * The dialog is a single instance rendered by the shell, like the file picker and for the same
 * reason: a modal opened from inside the canvas would live in a transformed, clipped layer.
 */
@Injectable({ providedIn: 'root' })
export class ProfileService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ENGINE_CONFIG);
  private readonly states = signal<ReadonlyMap<string, SchemaState>>(new Map());
  private readonly known = signal<readonly ProfileSchemaSummary[]>([]);

  private readonly request = signal<ProfileEditRequest | null>(null);
  private resolve: ((chosen: string | null) => void) | null = null;
  private nextRequestId = 0;
  private readonly loading = new Set<string>();

  /** Non-null while the dialog is open; the dialog renders from this. */
  readonly editing = this.request.asReadonly();

  /** Every schema the engine has, with a count each: what the settings page lists. */
  readonly schemas = this.known.asReadonly();

  /** Everything known about one schema, as a signal the caller can compute from. */
  state(schema: string) {
    return computed(() => this.states().get(schema) ?? EMPTY);
  }

  /** Fetches the list of schemas, for a page that knows none by name. */
  loadSchemas(): void {
    this.http
      .get<{ schemas?: readonly ProfileSchemaSummary[] }>(`${this.config.httpBase}/api/profiles`)
      .pipe(
        tap((body) => this.known.set(body.schemas ?? [])),
        catchError(() => of(null)),
      )
      .subscribe();
  }
  load(schema: string, refresh = false): void {
    if (!schema || (this.states().has(schema) && !refresh) || this.loading.has(schema)) {
      return;
    }
    this.loading.add(schema);
    if (refresh) {
      this.store(schema, EMPTY);
    }
    this.http
      .get<Record<string, unknown>>(
        `${this.config.httpBase}/api/profiles/${encodeURIComponent(schema)}`,
      )
      .pipe(
        tap((body) => this.store(schema, parseSchemaState(body))),
        catchError((error: unknown) => {
          this.store(schema, { schema: null, profiles: [], error: messageOf(error) });
          return of(null);
        }),
      )
      .subscribe({ complete: () => this.loading.delete(schema) });
  }

  save(schema: string, draft: ProfileDraft): Observable<Profile> {
    return this.http
      .post<Record<string, unknown>>(
        `${this.config.httpBase}/api/profiles/${encodeURIComponent(schema)}`,
        draft,
      )
      .pipe(
        map(parseProfile),
        tap((saved) => {
          const current = this.states().get(schema) ?? EMPTY;
          const profiles = [
            ...current.profiles.filter((profile) => profile.id !== saved.id),
            saved,
          ].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
          this.store(schema, { ...current, profiles, error: '' });
        }),
      );
  }

  delete(schema: string, id: string): Observable<void> {
    return this.http
      .delete<void>(
        `${this.config.httpBase}/api/profiles/${encodeURIComponent(schema)}/${encodeURIComponent(id)}`,
      )
      .pipe(
        tap(() => {
          const current = this.states().get(schema) ?? EMPTY;
          this.store(schema, {
            ...current,
            profiles: current.profiles.filter((profile) => profile.id !== id),
          });
        }),
      );
  }

  /** Tries a draft out on the engine without saving it. */
  test(schema: string, values: Readonly<Record<string, unknown>>): Observable<ProfileTestResult> {
    return this.http
      .post<Record<string, unknown>>(
        `${this.config.httpBase}/api/profiles/${encodeURIComponent(schema)}/test`,
        { values },
      )
      .pipe(
        map((body) => ({
          ok: body['ok'] === true,
          message: String(body['message'] ?? ''),
          details: Array.isArray(body['details']) ? body['details'].map(String) : [],
        })),
        catchError((error: unknown) =>
          of({ ok: false, message: messageOf(error), details: [] as readonly string[] }),
        ),
      );
  }
  /** Runs the bounded, server-side OpenAPI metadata projection without saving a profile. */
  importOpenApi(schema: string, request: OpenApiImportRequest): Observable<OpenApiImportResult> {
    return this.http
      .post<Record<string, unknown>>(
        `${this.config.httpBase}/api/profiles/${encodeURIComponent(schema)}/import/openapi`,
        request,
        { withCredentials: true },
      )
      .pipe(map(parseOpenApiImportResult));
  }

  /**
   * Opens the dialog. Resolves with the id of the profile the user finished on, or null if the
   * dialog was dismissed — so the widget that asked can point its node at what was just made.
   */
  edit(request: ProfileEditRequest): Promise<string | null> {
    this.settle(null);
    this.load(request.schema, true);
    const identified = { ...request, id: ++this.nextRequestId };
    this.request.set(identified);
    return new Promise((resolve) => {
      this.resolve = resolve;
    });
  }

  /** Called by the dialog: done, pointing the node at this profile. */
  finish(chosen: string | null, requestId?: number): void {
    if (requestId !== undefined && this.request()?.id !== requestId) {
      return;
    }
    this.settle(chosen);
  }

  private settle(chosen: string | null): void {
    const pending = this.resolve;
    this.resolve = null;
    this.request.set(null);
    pending?.(chosen);
  }

  private store(schema: string, state: SchemaState): void {
    this.states.update((current) => new Map(current).set(schema, state));
  }
}

export function parseSchemaState(body: Record<string, unknown>): SchemaState {
  const raw = (body['schema'] ?? {}) as Record<string, unknown>;
  const schema: ProfileSchema = {
    id: String(raw['id'] ?? ''),
    label: String(raw['label'] ?? ''),
    testable: raw['testable'] === true,
    importFormats: Array.isArray(raw['importFormats'])
      ? raw['importFormats'].filter((value): value is string => typeof value === 'string')
      : [],
    fields: Array.isArray(raw['fields']) ? raw['fields'].map(parseInputSpec) : [],
  };
  const profiles = Array.isArray(body['profiles']) ? body['profiles'].map(parseProfile) : [];
  return { schema, profiles, error: '' };
}

function parseProfile(raw: unknown): Profile {
  const profile = raw as Record<string, unknown>;
  const values = profile['values'];
  return {
    id: String(profile['id'] ?? ''),
    schema: String(profile['schema'] ?? ''),
    name: String(profile['name'] ?? ''),
    description: String(profile['description'] ?? ''),
    updatedAt: String(profile['updatedAt'] ?? ''),
    values: values && typeof values === 'object' ? (values as Record<string, unknown>) : {},
  };
}

function parseOpenApiImportResult(raw: Record<string, unknown>): OpenApiImportResult {
  const draft = raw['credentialDraft'];
  return {
    servers: arrayOf(raw['servers']).map(parseOpenApiServer),
    operations: arrayOf(raw['operations']).map(parseOpenApiOperation),
    securityAlternatives: arrayOf(raw['securityAlternatives']).map(parseOpenApiSecurity),
    values: objectOf(raw['values']),
    credentialDraft: draft && typeof draft === 'object' ? parseCredentialDraft(draft) : null,
    issues: arrayOf(raw['issues']).map(parseOpenApiIssue),
    complete: raw['complete'] === true,
    selectedServer: optionalString(raw['selectedServer']),
    selectedOperation: optionalString(raw['selectedOperation']),
    selectedSecurityAlternative: optionalString(raw['selectedSecurityAlternative']),
  };
}

function parseOpenApiServer(raw: unknown): OpenApiServer {
  const value = objectOf(raw);
  const rawVariables = objectOf(value['variables']);
  const variables: Record<string, OpenApiServerVariable> = {};
  for (const [name, candidate] of Object.entries(rawVariables)) {
    const variable = objectOf(candidate);
    const choices = Array.isArray(variable['enum'])
      ? variable['enum'].filter((item): item is string => typeof item === 'string')
      : undefined;
    variables[name] = {
      default: optionalString(variable['default']),
      enum: choices,
      required: variable['required'] === true,
      value: optionalString(variable['value']),
    };
  }
  return {
    id: String(value['id'] ?? ''),
    url: String(value['url'] ?? ''),
    variables,
  };
}

function parseOpenApiOperation(raw: unknown): OpenApiOperation {
  const value = objectOf(raw);
  return {
    id: String(value['id'] ?? ''),
    label: String(value['label'] ?? ''),
    path: String(value['path'] ?? ''),
    apiFormat: String(value['apiFormat'] ?? ''),
    available: value['available'] === true,
    reason: optionalString(value['reason']),
  };
}

function parseOpenApiSecurity(raw: unknown): OpenApiSecurityAlternative {
  const value = objectOf(raw);
  return {
    id: String(value['id'] ?? ''),
    label: String(value['label'] ?? ''),
    available: value['available'] === true,
    reason: optionalString(value['reason']),
    auth: optionalString(value['auth']),
  };
}

function parseOpenApiIssue(raw: unknown): OpenApiImportIssue {
  const value = objectOf(raw);
  const severity = value['severity'] === 'warning' ? 'warning' : 'error';
  return {
    pointer: String(value['pointer'] ?? ''),
    severity,
    message: String(value['message'] ?? ''),
  };
}

function parseCredentialDraft(raw: object): CredentialDraft | null {
  const value = objectOf(raw);
  const type = value['type'];
  if (type !== 'api_key' && type !== 'basic' && type !== 'oauth2' && type !== 'codex') return null;
  const draftType: CredentialDraft['type'] = type;
  const requiredFields = Array.isArray(value['requiredFields'])
    ? value['requiredFields'].filter((item): item is string => typeof item === 'string')
    : undefined;
  const flows = Array.isArray(value['flows'])
    ? value['flows'].map((item) => {
        const flow = objectOf(item);
        const grantType = String(flow['grantType'] ?? '');
        return {
          id: String(flow['id'] ?? ''),
          grantType,
          authorizationUrl: optionalString(flow['authorizationUrl']),
          tokenUrl: optionalString(flow['tokenUrl']),
          refreshUrl: optionalString(flow['refreshUrl']),
          deviceAuthorizationUrl: optionalString(flow['deviceAuthorizationUrl']),
        };
      })
    : undefined;
  return {
    type: draftType,
    configuration: objectOf(value['configuration']),
    requiredFields,
    flows,
    complete: value['complete'] === true,
  };
}

function arrayOf(value: unknown): readonly unknown[] {
  return Array.isArray(value) ? value : [];
}

function objectOf(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

/** The engine's own sentence when it sent one, since it is the specific half of the answer. */
function messageOf(error: unknown): string {
  const body = (error as { error?: { detail?: string; message?: string } } | null)?.error;
  return body?.detail ?? body?.message ?? 'Could not reach the engine';
}
