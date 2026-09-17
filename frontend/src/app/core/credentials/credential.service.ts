import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, switchMap, tap } from 'rxjs';
import { ENGINE_CONFIG } from '../engine.config';

export type CredentialType =
  'api_key' | 'basic' | 'oauth2' | 'codex' | 'codex_external' | 'unknown';
export type CredentialStatus =
  'unconfigured' | 'pending' | 'ready' | 'refreshing' | 'reauth_required' | 'error';
export type OAuthGrant = 'authorization_code' | 'client_credentials' | 'device_authorization';
export type ClientAuthentication = 'none' | 'client_secret_basic' | 'client_secret_post';

/** Native ChatGPT subscription endpoint used by new ChatGPT/Codex connections. */
export const CHATGPT_CODEX_RESOURCE_BASE_URL = 'https://chatgpt.com/backend-api/codex';

/** Public metadata about a credential. Values and sessions never reach this type. */
export interface CredentialName {
  readonly name: string;
  readonly source: string;
  readonly removable: boolean;
  readonly type: CredentialType;
  readonly status: CredentialStatus;
  readonly expiresAt: string | null;
  readonly renewable: boolean;
  readonly message?: string;
}

export interface CredentialConfiguration {
  readonly name: string;
  readonly type: CredentialType;
  readonly configuration: Readonly<Record<string, unknown>>;
  readonly hasPassword: boolean;
  readonly hasClientSecret: boolean;
  readonly callbackUri?: string;
}

/** Secret-free connection draft, including the subset an OpenAPI import can offer. */
export interface CredentialDraft {
  readonly type: 'api_key' | 'basic' | 'oauth2' | 'codex';
  readonly configuration: Readonly<Record<string, unknown>>;
  readonly requiredFields?: readonly string[];
  readonly flows?: readonly CredentialFlow[];
  readonly complete?: boolean;
}

export interface CredentialFlow {
  readonly id: string;
  readonly grantType: string;
  readonly authorizationUrl?: string;
  readonly tokenUrl?: string;
  readonly refreshUrl?: string;
  readonly deviceAuthorizationUrl?: string;
}

export interface CredentialDiscovery {
  readonly configuration: Readonly<Record<string, unknown>>;
  readonly tokenAuthenticationMethodsSupported: readonly ClientAuthentication[];
  readonly scopesSupported: readonly string[];
}

export interface CredentialLogin {
  readonly loginId?: string;
  readonly status: CredentialStatus;
  readonly authorizationUrl?: string;
  readonly verificationUrl?: string;
  readonly userCode?: string;
  readonly expiresAt?: string | null;
  readonly message?: string;
}

export interface CredentialEditRequest {
  readonly id: number;
  readonly current: string;
  readonly draft?: CredentialDraft;
}

export interface ApiKeyCredentialCreation {
  readonly name: string;
  readonly type: 'api_key';
  readonly value: string;
}

export interface BasicCredentialCreation {
  readonly name: string;
  readonly type: 'basic';
  readonly username: string;
  readonly password: string;
  readonly resourceBaseUrl: string;
}

export interface OAuthCredentialCreation {
  readonly name: string;
  readonly type: 'oauth2';
  readonly configuration: Readonly<Record<string, unknown>> & { readonly clientSecret?: string };
}

export interface CodexCredentialCreation {
  readonly name: string;
  readonly type: 'codex';
  readonly configuration: Readonly<Record<string, unknown>>;
}

export type CredentialCreation =
  | ApiKeyCredentialCreation
  | BasicCredentialCreation
  | OAuthCredentialCreation
  | CodexCredentialCreation;

export interface CredentialConfigurationUpdate {
  readonly configuration: Readonly<Record<string, unknown>> & {
    readonly password?: string;
    readonly clientSecret?: string;
  };
}

@Injectable({ providedIn: 'root' })
export class CredentialService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ENGINE_CONFIG);
  private catalogRevision = 0;
  private readonly known = signal<readonly CredentialName[]>([]);
  private readonly callbackTemplate = signal('');
  private readonly failure = signal('');
  private readonly request = signal<CredentialEditRequest | null>(null);
  private resolve: ((chosen: string | null) => void) | null = null;
  private nextRequestId = 0;

  /** Only public catalog metadata is retained between dialog openings. */
  readonly names = this.known.asReadonly();
  readonly oauthCallbackUriTemplate = this.callbackTemplate.asReadonly();
  readonly error = this.failure.asReadonly();
  readonly editing = this.request.asReadonly();
  load(): void {
    this.refresh()
      .pipe(
        catchError((error: unknown) => {
          this.failure.set(messageOf(error));
          return of([]);
        }),
      )
      .subscribe();
  }

  refresh(): Observable<readonly CredentialName[]> {
    const revision = ++this.catalogRevision;
    return this.http
      .get<Record<string, unknown>>(this.url('/api/credentials'), { withCredentials: true })
      .pipe(
        map((body) => ({
          entries: (Array.isArray(body['credentials']) ? body['credentials'] : []).map(
            parseCredential,
          ),
          callback: text(body['oauthCallbackUriTemplate']),
        })),
        tap(({ entries, callback }) => {
          if (revision !== this.catalogRevision) return;
          this.known.set(entries);
          this.callbackTemplate.set(callback);
          this.failure.set('');
        }),
        map(({ entries }) => entries),
      );
  }

  configuration(name: string): Observable<CredentialConfiguration> {
    return this.http
      .get<Record<string, unknown>>(
        this.url(`/api/credentials/${encodeURIComponent(name)}/configuration`),
        {
          withCredentials: true,
        },
      )
      .pipe(map(parseConfiguration));
  }

  create(credential: CredentialCreation): Observable<CredentialName> {
    const name = credential.name;
    return this.http
      .post<Record<string, unknown>>(this.url('/api/credentials'), credential, {
        withCredentials: true,
      })
      .pipe(
        switchMap(() => this.refresh()),
        map((entries) => {
          const saved = entries.find((entry) => entry.name === name);
          if (!saved) throw new Error('The saved credential is no longer available.');
          return saved;
        }),
      );
  }

  saveConfiguration(
    name: string,
    update: CredentialConfigurationUpdate,
  ): Observable<CredentialConfiguration> {
    return this.http
      .post<Record<string, unknown>>(
        this.url(`/api/credentials/${encodeURIComponent(name)}/configuration`),
        update,
        { withCredentials: true },
      )
      .pipe(
        map(parseConfiguration),
        switchMap((configuration) => this.refresh().pipe(map(() => configuration))),
      );
  }

  edit(
    request: { readonly current?: string; readonly draft?: CredentialDraft } = {},
  ): Promise<string | null> {
    this.settle(null);
    const id = ++this.nextRequestId;
    this.request.set({ id, current: request.current ?? '', draft: request.draft });
    this.load();
    return new Promise((resolve) => {
      this.resolve = resolve;
    });
  }

  discover(
    name: string,
    configuration: Readonly<Record<string, unknown>>,
  ): Observable<CredentialDiscovery> {
    return this.http
      .post<Record<string, unknown>>(
        this.url(`/api/credentials/${encodeURIComponent(name)}/discover`),
        { configuration },
        { withCredentials: true },
      )
      .pipe(map(parseDiscovery));
  }

  login(
    name: string,
    request: {
      readonly mode?: 'browser' | 'device';
      readonly configuration?: Readonly<Record<string, unknown>>;
    } = {},
  ): Observable<CredentialLogin> {
    return this.http
      .post<Record<string, unknown>>(
        this.url(`/api/credentials/${encodeURIComponent(name)}/login`),
        request,
        { withCredentials: true },
      )
      .pipe(
        map(parseLogin),
        tap(() => this.load()),
      );
  }

  loginStatus(name: string, loginId: string): Observable<CredentialLogin> {
    return this.http
      .get<Record<string, unknown>>(
        this.url(
          `/api/credentials/${encodeURIComponent(name)}/login/${encodeURIComponent(loginId)}`,
        ),
        { withCredentials: true },
      )
      .pipe(map(parseLogin));
  }

  cancelLogin(name: string, loginId: string): Observable<void> {
    return this.http.post<void>(
      this.url(
        `/api/credentials/${encodeURIComponent(name)}/login/${encodeURIComponent(loginId)}/cancel`,
      ),
      {},
      { withCredentials: true },
    );
  }

  logout(name: string): Observable<void> {
    return this.http
      .post<void>(
        this.url(`/api/credentials/${encodeURIComponent(name)}/logout`),
        {},
        { withCredentials: true },
      )
      .pipe(tap(() => this.load()));
  }

  remove(name: string): Observable<void> {
    return this.http
      .delete<void>(this.url(`/api/credentials/${encodeURIComponent(name)}`), {
        withCredentials: true,
      })
      .pipe(tap(() => this.load()));
  }

  /** The dialog supplies the request id so an older overlay cannot settle a newer caller. */
  finish(chosen: string | null, requestId: number): void {
    if (this.request()?.id !== requestId) {
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

  private url(path: string): string {
    return `${this.config.httpBase}${path}`;
  }
}

function parseCredential(raw: unknown): CredentialName {
  const item = object(raw);
  return {
    name: text(item['name']),
    source: text(item['source']),
    removable: item['removable'] === true,
    type: credentialType(item['type']),
    status: credentialStatus(item['status']),
    expiresAt: typeof item['expiresAt'] === 'string' ? item['expiresAt'] : null,
    renewable: item['renewable'] === true,
    ...(typeof item['message'] === 'string' ? { message: item['message'] } : {}),
  };
}

function parseConfiguration(raw: unknown): CredentialConfiguration {
  const item = object(raw);
  return {
    name: text(item['name']),
    type: credentialType(item['type']),
    configuration: object(item['configuration']),
    hasPassword: item['hasPassword'] === true,
    hasClientSecret: item['hasClientSecret'] === true,
    ...(typeof item['callbackUri'] === 'string' ? { callbackUri: item['callbackUri'] } : {}),
  };
}

function parseDiscovery(raw: unknown): CredentialDiscovery {
  const item = object(raw);
  return {
    configuration: object(item['configuration']),
    tokenAuthenticationMethodsSupported: stringArray(
      item['tokenAuthenticationMethodsSupported'],
    ).filter(
      (method): method is ClientAuthentication =>
        method === 'none' || method === 'client_secret_basic' || method === 'client_secret_post',
    ),
    scopesSupported: stringArray(item['scopesSupported']),
  };
}

function parseLogin(raw: unknown): CredentialLogin {
  const item = object(raw);
  return {
    ...(typeof item['loginId'] === 'string' ? { loginId: item['loginId'] } : {}),
    status: credentialStatus(item['status']),
    ...(typeof item['authorizationUrl'] === 'string'
      ? { authorizationUrl: item['authorizationUrl'] }
      : {}),
    ...(typeof item['verificationUrl'] === 'string'
      ? { verificationUrl: item['verificationUrl'] }
      : {}),
    ...(typeof item['userCode'] === 'string' ? { userCode: item['userCode'] } : {}),
    ...(typeof item['expiresAt'] === 'string' ? { expiresAt: item['expiresAt'] } : {}),
    ...(typeof item['error'] === 'string'
      ? { message: item['error'] }
      : typeof item['message'] === 'string'
        ? { message: item['message'] }
        : {}),
  };
}

function object(value: unknown): Readonly<Record<string, unknown>> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Readonly<Record<string, unknown>>)
    : {};
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function stringArray(value: unknown): readonly string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : [];
}

function credentialType(value: unknown): CredentialType {
  switch (value) {
    case 'api_key':
    case 'basic':
    case 'oauth2':
    case 'codex':
    case 'codex_external':
      return value;
    default:
      return 'unknown';
  }
}

function credentialStatus(value: unknown): CredentialStatus {
  switch (value) {
    case 'pending':
    case 'ready':
    case 'refreshing':
    case 'reauth_required':
    case 'error':
      return value;
    default:
      return 'unconfigured';
  }
}

function messageOf(error: unknown): string {
  const body = (error as { error?: { detail?: string; message?: string } } | null)?.error;
  return body?.detail ?? body?.message ?? 'Could not reach the engine';
}
