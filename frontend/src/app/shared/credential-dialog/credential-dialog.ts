import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  CHATGPT_CODEX_RESOURCE_BASE_URL,
  CredentialCreation,
  CredentialDraft,
  CredentialLogin,
  CredentialName,
  CredentialService,
  CredentialStatus,
  CredentialType,
  OAuthGrant,
} from '../../core/credentials/credential.service';
import { MessageKey } from '../../core/i18n/messages/en';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { Translator } from '../../core/i18n/translator';
import { Icon } from '../icon';

const NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,60}$/;
type FormType = 'api_key' | 'basic' | 'oauth2' | 'codex';

@Component({
  selector: 'app-credential-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, TranslatePipe],
  templateUrl: './credential-dialog.html',
  styleUrl: './credential-dialog.scss',
  host: { '(document:keydown)': 'onKeydown($event)' },
})
export class CredentialDialog {
  private readonly credentials = inject(CredentialService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly translator = inject(Translator);
  private seededRequest = 0;
  private revision = 0;
  private pendingInitial: { name: string; revision: number } | null = null;
  private timer: number | null = null;
  private popup: Window | null = null;
  private activeLogin: { name: string; id: string; request: number; revision: number } | null =
    null;

  protected readonly request = this.credentials.editing;
  protected readonly names = this.credentials.names;
  protected readonly serviceError = this.credentials.error;
  protected readonly selected = signal<CredentialName | null>(null);
  protected readonly name = signal('');
  protected readonly type = signal<FormType>('api_key');
  protected readonly value = signal('');
  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly resourceBaseUrl = signal('');
  protected readonly clientId = signal('');
  protected readonly clientSecret = signal('');
  protected readonly clientAuthentication = signal('');
  protected readonly grant = signal<OAuthGrant | ''>('');
  protected readonly mode = signal<'browser' | 'device'>('browser');
  protected readonly issuer = signal('');
  protected readonly metadataUrl = signal('');
  protected readonly authorizationUrl = signal('');
  protected readonly tokenUrl = signal('');
  protected readonly refreshUrl = signal('');
  protected readonly deviceAuthorizationUrl = signal('');
  protected readonly scopes = signal('');
  protected readonly callbackUri = signal('');
  protected readonly requiredFields = signal<readonly string[]>([]);
  protected readonly hasPassword = signal(false);
  protected readonly hasClientSecret = signal(false);
  protected readonly flows = signal<NonNullable<CredentialDraft['flows']>>([]);
  protected readonly selectedFlow = signal('');
  protected readonly discoveryPreview = signal<Readonly<Record<string, unknown>> | null>(null);
  protected readonly login = signal<CredentialLogin | null>(null);
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly discovering = signal(false);
  protected readonly loggingIn = signal(false);
  protected readonly confirmingDelete = signal(false);
  protected readonly notice = signal<MessageKey | ''>('');
  protected readonly failure = signal('');
  protected readonly popupBlocked = signal(false);
  private readonly savedPublic = signal('');

  protected readonly existing = computed(() => this.selected() !== null);
  protected readonly readOnly = computed(
    () => this.selected()?.removable === false || this.selected()?.type === 'unknown',
  );
  protected readonly canChooseType = computed(
    () => !this.existing() && !this.loading() && !this.saving(),
  );
  protected readonly hasMultipleFlows = computed(() => this.flows().length > 1);
  protected readonly dirty = computed(
    () =>
      !this.existing() ||
      !!this.value() ||
      !!this.password() ||
      !!this.clientSecret() ||
      JSON.stringify(this.publicConfiguration()) !== this.savedPublic(),
  );
  protected readonly canSave = computed(() => {
    if (
      !this.request() ||
      this.readOnly() ||
      this.loading() ||
      this.saving() ||
      !this.dirty() ||
      !NAME.test(this.name().trim())
    )
      return false;
    if (this.type() === 'api_key') return this.value().trim().length > 0;
    if (!isHttpUrl(this.resourceBaseUrl())) return false;
    if (this.type() === 'basic')
      return !!this.username().trim() && (!!this.password() || this.hasPassword());
    if (this.type() === 'codex') return true;
    if (
      !this.grant() ||
      (this.hasMultipleFlows() && !this.selectedFlow()) ||
      !this.clientId().trim() ||
      !isHttpUrl(this.tokenUrl())
    )
      return false;
    if (this.grant() === 'authorization_code' && !isHttpUrl(this.authorizationUrl())) return false;
    if (this.grant() === 'device_authorization' && !isHttpUrl(this.deviceAuthorizationUrl()))
      return false;
    return (
      this.clientAuthentication() === 'none' ||
      (['client_secret_basic', 'client_secret_post'].includes(this.clientAuthentication()) &&
        (!!this.clientSecret() || this.hasClientSecret()))
    );
  });
  protected readonly canSignIn = computed(() => {
    if (
      !this.request() ||
      this.loading() ||
      this.saving() ||
      this.loggingIn() ||
      this.login()?.status === 'pending'
    )
      return false;
    if (this.selected()?.type === 'codex_external') return isHttpUrl(this.resourceBaseUrl());
    return (
      (this.type() === 'oauth2' || this.type() === 'codex') &&
      (this.canSave() || (this.existing() && !this.dirty()))
    );
  });
  protected readonly canDiscover = computed(
    () =>
      this.type() === 'oauth2' &&
      NAME.test(this.name().trim()) &&
      isHttpUrl(this.issuer()) &&
      !this.discovering(),
  );
  protected readonly statusLabel = computed<MessageKey>(() =>
    this.statusKey(this.selected()?.status ?? 'unconfigured'),
  );
  protected readonly expiresText = computed(() =>
    this.selected()?.expiresAt ? new Date(this.selected()!.expiresAt!).toLocaleString() : '',
  );
  protected readonly displayCallback = computed(
    () =>
      this.callbackUri() ||
      this.credentials
        .oauthCallbackUriTemplate()
        .replace(
          '{name}',
          NAME.test(this.name().trim()) ? encodeURIComponent(this.name().trim()) : '{name}',
        ),
  );
  protected readonly previewEntries = computed(() => Object.entries(this.discoveryPreview() ?? {}));

  constructor() {
    effect(() => {
      const request = this.request();
      untracked(() => {
        if (!request) {
          this.revision++;
          this.cancelPendingLogin();
          this.clearSecrets();
          this.seededRequest = 0;
          return;
        }
        if (request.id === this.seededRequest) return;
        this.seededRequest = request.id;
        this.beginRequest(request.current, request.draft);
      });
    });
    effect(() => {
      const items = this.names();
      const request = this.request();
      const selected = this.selected();
      untracked(() => {
        if (!request) return;
        if (selected) {
          const current = items.find((item) => item.name === selected.name);
          if (current && current !== selected) this.selected.set(current);
        } else if (this.pendingInitial && this.pendingInitial.revision === this.revision) {
          const found = items.find((item) => item.name === this.pendingInitial?.name);
          if (found) this.beginRequest(found.name, request.draft);
        }
      });
    });
    this.destroyRef.onDestroy(() => {
      this.cancelPendingLogin();
      this.clearSecrets();
      this.credentials.finish(null, this.request()?.id ?? -1);
    });
  }

  protected typeLabel(type: CredentialType): MessageKey {
    switch (type) {
      case 'api_key':
        return 'credential.apiKey';
      case 'basic':
        return 'credential.basic';
      case 'oauth2':
        return 'credential.oauth';
      case 'codex':
      case 'codex_external':
        return 'credential.codex';
      default:
        return 'credential.type';
    }
  }
  protected statusKey(status: CredentialStatus): MessageKey {
    return `credential.status.${status}`;
  }
  protected startNew(): void {
    this.beginRequest('');
  }
  protected select(item: CredentialName): void {
    if (item.name !== this.selected()?.name) this.beginRequest(item.name);
  }
  protected setType(event: Event): void {
    const type = (event.target as HTMLSelectElement).value;
    if (!this.canChooseType() || !['api_key', 'basic', 'oauth2', 'codex'].includes(type)) return;
    const existingResource = this.resourceBaseUrl().trim();
    this.clearForm();
    this.type.set(type as FormType);
    this.applyTypeDefaults();
    if (type === 'codex' && existingResource) this.resourceBaseUrl.set(existingResource);
    this.changed();
  }
  protected setGrant(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    const flow = this.flows().find((item) => item.id === value);
    if (flow && isGrant(flow.grantType)) {
      this.selectedFlow.set(flow.id);
      this.grant.set(flow.grantType);
      this.authorizationUrl.set(flow.authorizationUrl ?? '');
      this.tokenUrl.set(flow.tokenUrl ?? '');
      this.refreshUrl.set(flow.refreshUrl ?? '');
      this.deviceAuthorizationUrl.set(flow.deviceAuthorizationUrl ?? '');
    } else if (isGrant(value) || value === '') {
      this.grant.set(value);
      this.selectedFlow.set('');
    }
    this.changed();
  }
  protected setMode(event: Event): void {
    const mode = (event.target as HTMLSelectElement).value;
    if (mode === 'browser' || mode === 'device') {
      this.mode.set(mode);
      this.changed();
    }
  }
  protected setClientAuthentication(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (['', 'none', 'client_secret_basic', 'client_secret_post'].includes(value)) {
      this.clientAuthentication.set(value);
      if (value === 'none') this.clientSecret.set('');
      this.changed();
    }
  }
  protected setText(
    target:
      | 'name'
      | 'value'
      | 'username'
      | 'password'
      | 'resource'
      | 'clientId'
      | 'clientSecret'
      | 'issuer'
      | 'metadataUrl'
      | 'authorizationUrl'
      | 'tokenUrl'
      | 'refreshUrl'
      | 'deviceAuthorizationUrl'
      | 'scopes',
    event: Event,
  ): void {
    const value = (event.target as HTMLInputElement).value;
    if (target === 'resource') this.resourceBaseUrl.set(value);
    else this[target].set(value);
    this.changed();
  }

  protected async discover(): Promise<void> {
    if (!this.canDiscover()) return;
    const request = this.request()?.id,
      revision = this.revision;
    this.discovering.set(true);
    this.failure.set('');
    try {
      const result = await firstValueFrom(
        this.credentials.discover(this.name().trim(), this.publicConfiguration()),
      );
      if (!this.matches(request, revision)) return;
      this.discoveryPreview.set(result.configuration);
      this.notice.set('credential.metadataReview');
    } catch (error) {
      if (this.matches(request, revision)) {
        this.clearSecrets();
        this.failure.set(messageOf(error));
      }
    } finally {
      if (this.matches(request, revision)) this.discovering.set(false);
    }
  }
  protected applyDiscovery(): void {
    const preview = this.discoveryPreview();
    if (!preview) return;
    this.issuer.set(text(preview['issuer']));
    this.metadataUrl.set(text(preview['metadataUrl']));
    this.authorizationUrl.set(text(preview['authorizationUrl']));
    this.tokenUrl.set(text(preview['tokenUrl']));
    this.refreshUrl.set(text(preview['refreshUrl']));
    this.deviceAuthorizationUrl.set(text(preview['deviceAuthorizationUrl']));
    this.changed();
    this.discoveryPreview.set(null);
  }

  protected async save(andLogin = false): Promise<void> {
    if (!this.canSave()) return;
    const request = this.request()?.id,
      revision = this.revision,
      name = this.name().trim();
    const wantsLogin = andLogin && (this.type() === 'oauth2' || this.type() === 'codex');
    const popup = wantsLogin ? this.openPopup() : null;
    const operation =
      this.selected() && this.type() !== 'api_key'
        ? this.credentials.saveConfiguration(name, { configuration: this.configurationForSave() })
        : this.credentials.create(this.creationForSave(name));
    this.clearSecrets();
    this.saving.set(true);
    this.failure.set('');
    try {
      await firstValueFrom<unknown>(operation);
      if (!this.matches(request, revision)) {
        popup?.close();
        return;
      }
      const row = this.names().find((item) => item.name === name);
      if (!row) throw new Error(this.translator.t('credential.failed'));
      this.selected.set(row);
      this.pendingInitial = null;
      if (row.type !== 'api_key') await this.loadConfiguration(name, request, revision);
      if (!this.matches(request, revision)) {
        popup?.close();
        return;
      }
      this.savedPublic.set(JSON.stringify(this.publicConfiguration()));
      this.notice.set('credential.saved');
      if (wantsLogin) await this.beginLogin(name, request, revision, popup);
    } catch (error) {
      popup?.close();
      if (this.matches(request, revision)) {
        this.clearSecrets();
        this.failure.set(messageOf(error));
      }
    } finally {
      if (this.matches(request, revision)) this.saving.set(false);
    }
  }

  protected async signIn(): Promise<void> {
    if (!this.canSignIn()) return;
    if (this.selected()?.type !== 'codex_external' && (this.dirty() || !this.existing())) {
      await this.save(true);
      return;
    }
    await this.beginLogin(this.name().trim(), this.request()?.id, this.revision, this.openPopup());
  }
  private async beginLogin(
    name: string,
    request: number | undefined,
    revision: number,
    popup: Window | null,
  ): Promise<void> {
    this.loggingIn.set(true);
    this.failure.set('');
    const mode = this.loginMode();
    const configuration =
      this.selected()?.type === 'codex_external' ? this.publicConfiguration() : undefined;
    this.clearSecrets();
    try {
      const result = await firstValueFrom(this.credentials.login(name, { mode, configuration }));
      if (!this.matches(request, revision)) {
        popup?.close();
        if (result.loginId && result.status === 'pending') this.cancelRemote(name, result.loginId);
        return;
      }
      if (
        (result.authorizationUrl && !isHttpUrl(result.authorizationUrl)) ||
        (result.verificationUrl && !isHttpUrl(result.verificationUrl))
      ) {
        if (result.loginId) this.cancelRemote(name, result.loginId);
        throw new Error(this.translator.t('credential.failed'));
      }
      this.login.set(result);
      if (result.authorizationUrl) {
        if (popup && !popup.closed) {
          popup.opener = null;
          popup.location.href = result.authorizationUrl;
        } else this.popupBlocked.set(true);
      } else popup?.close();
      if (result.loginId && result.status === 'pending') {
        this.activeLogin = { name, id: result.loginId, request: request ?? -1, revision };
        this.schedulePoll();
      } else this.credentials.load();
    } catch (error) {
      popup?.close();
      if (this.matches(request, revision)) {
        this.clearSecrets();
        this.failure.set(messageOf(error));
      }
    } finally {
      if (this.matches(request, revision)) this.loggingIn.set(false);
    }
  }

  private openPopup(): Window | null {
    if (this.loginMode() !== 'browser') return null;
    try {
      this.popup = window.open('', '_blank', 'popup,width=640,height=760');
    } catch {
      this.popup = null;
    }
    if (this.popup) this.popup.opener = null;
    this.popupBlocked.set(this.popup === null);
    return this.popup;
  }
  private loginMode(): 'browser' | 'device' | undefined {
    if (this.type() === 'codex') return this.mode();
    return this.grant() === 'authorization_code'
      ? 'browser'
      : this.grant() === 'device_authorization'
        ? 'device'
        : undefined;
  }
  private schedulePoll(): void {
    this.timer = window.setTimeout(() => {
      void this.poll();
    }, 600);
  }
  private async poll(): Promise<void> {
    const active = this.activeLogin;
    if (!active || !this.matches(active.request, active.revision)) return;
    try {
      const result = await firstValueFrom(this.credentials.loginStatus(active.name, active.id));
      if (this.activeLogin !== active || !this.matches(active.request, active.revision)) return;
      if (result.status === 'pending' || result.status === 'refreshing') {
        this.login.update((previous) => ({ ...previous, ...result }));
        this.schedulePoll();
      } else {
        this.login.set(result);
        this.activeLogin = null;
        this.popup?.close();
        this.popup = null;
        const entries = await firstValueFrom(this.credentials.refresh());
        if (!this.matches(active.request, active.revision)) return;
        const current = entries.find((entry) => entry.name === active.name);
        if (current) {
          this.selected.set(current);
          if (result.status === 'ready' && current.removable && current.type !== 'api_key')
            await this.loadConfiguration(current.name, active.request, active.revision);
        }
      }
    } catch (error) {
      if (this.activeLogin === active && this.matches(active.request, active.revision)) {
        this.cancelPendingLogin();
        this.failure.set(messageOf(error));
      }
    }
  }
  protected cancelLogin(): void {
    this.changed();
  }
  private cancelRemote(name: string, id: string): void {
    this.credentials.cancelLogin(name, id).subscribe({ error: () => this.credentials.load() });
  }
  private cancelPendingLogin(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    const active = this.activeLogin;
    this.activeLogin = null;
    if (active) this.cancelRemote(active.name, active.id);
    this.popup?.close();
    this.popup = null;
  }

  protected async logout(): Promise<void> {
    const current = this.selected();
    if (!current) return;
    this.changed();
    this.clearSecrets();
    const request = this.request()?.id,
      revision = this.revision;
    try {
      await firstValueFrom(this.credentials.logout(current.name));
    } catch (error) {
      if (this.matches(request, revision)) this.failure.set(messageOf(error));
    }
  }
  protected askDelete(): void {
    this.confirmingDelete.set(true);
  }
  protected cancelDelete(): void {
    this.confirmingDelete.set(false);
  }
  protected async remove(): Promise<void> {
    const current = this.selected();
    if (!current?.removable) return;
    this.changed();
    this.clearSecrets();
    const request = this.request()?.id,
      revision = this.revision;
    try {
      await firstValueFrom(this.credentials.remove(current.name));
      if (this.matches(request, revision)) this.close('');
    } catch (error) {
      if (this.matches(request, revision)) this.failure.set(messageOf(error));
    }
  }
  protected use(): void {
    if (!this.saving() && this.selected()) this.close(this.selected()!.name);
  }
  protected dismiss(): void {
    this.close(null);
  }
  protected onKeydown(event: KeyboardEvent): void {
    if (!this.request()) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopImmediatePropagation();
      this.dismiss();
    }
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      void this.save();
    }
  }

  private beginRequest(current: string, draft?: CredentialDraft): void {
    this.changed();
    this.clearForm();
    const row = this.names().find((item) => item.name === current) ?? null;
    this.selected.set(row);
    this.name.set(current);
    this.type.set(
      draft?.type ??
        (row?.type === 'basic' || row?.type === 'oauth2' || row?.type === 'codex'
          ? row.type
          : row?.type === 'codex_external'
            ? 'codex'
            : 'api_key'),
    );
    this.applyTypeDefaults();
    this.pendingInitial = current && !row ? { name: current, revision: this.revision } : null;
    if (draft) {
      this.applyConfiguration(draft.configuration);
      this.flows.set(draft.flows ?? []);
      this.requiredFields.set(draft.requiredFields ?? []);
      return;
    }
    if (row?.removable && ['basic', 'oauth2', 'codex'].includes(row.type)) {
      const request = this.request()?.id,
        revision = this.revision;
      this.loading.set(true);
      void this.loadConfiguration(row.name, request, revision)
        .catch((error) => {
          if (this.matches(request, revision)) {
            this.clearSecrets();
            this.failure.set(messageOf(error));
          }
        })
        .finally(() => {
          if (this.matches(request, revision)) this.loading.set(false);
        });
    } else this.savedPublic.set(JSON.stringify(this.publicConfiguration()));
  }
  private async loadConfiguration(
    name: string,
    request: number | undefined,
    revision: number,
  ): Promise<void> {
    const view = await firstValueFrom(this.credentials.configuration(name));
    if (!this.matches(request, revision)) return;
    this.applyConfiguration(view.configuration);
    this.callbackUri.set(view.callbackUri ?? '');
    this.hasPassword.set(view.hasPassword);
    this.hasClientSecret.set(view.hasClientSecret);
    this.savedPublic.set(JSON.stringify(this.publicConfiguration()));
  }
  private applyConfiguration(configuration: Readonly<Record<string, unknown>>): void {
    this.username.set(text(configuration['username']));
    const resourceBaseUrl = text(configuration['resourceBaseUrl']).trim();
    this.resourceBaseUrl.set(resourceBaseUrl || this.defaultResourceBaseUrl());
    this.clientId.set(text(configuration['clientId']));
    this.issuer.set(text(configuration['issuer']));
    this.metadataUrl.set(text(configuration['metadataUrl']));
    this.authorizationUrl.set(text(configuration['authorizationUrl']));
    this.tokenUrl.set(text(configuration['tokenUrl']));
    this.refreshUrl.set(text(configuration['refreshUrl']));
    this.deviceAuthorizationUrl.set(text(configuration['deviceAuthorizationUrl']));
    this.scopes.set(
      Array.isArray(configuration['scopes'])
        ? configuration['scopes']
            .filter((value): value is string => typeof value === 'string')
            .join(' ')
        : '',
    );
    const grant = text(configuration['grantType']);
    this.grant.set(isGrant(grant) ? grant : '');
    const method = text(configuration['clientAuthentication']);
    this.clientAuthentication.set(
      ['none', 'client_secret_basic', 'client_secret_post'].includes(method) ? method : '',
    );
  }
  private applyTypeDefaults(): void {
    this.resourceBaseUrl.set(this.defaultResourceBaseUrl());
  }
  private defaultResourceBaseUrl(): string {
    return this.type() === 'codex' ? CHATGPT_CODEX_RESOURCE_BASE_URL : '';
  }

  private publicConfiguration(): Readonly<Record<string, unknown>> {
    if (this.type() === 'api_key') return {};
    if (this.type() === 'codex') return { resourceBaseUrl: this.resourceBaseUrl().trim() };
    if (this.type() === 'basic')
      return { resourceBaseUrl: this.resourceBaseUrl().trim(), username: this.username().trim() };
    return Object.fromEntries(
      Object.entries({
        resourceBaseUrl: this.resourceBaseUrl().trim(),
        grantType: this.grant() || undefined,
        clientId: this.clientId().trim(),
        clientAuthentication: this.clientAuthentication() || undefined,
        issuer: this.issuer().trim() || undefined,
        metadataUrl: this.metadataUrl().trim() || undefined,
        authorizationUrl: this.authorizationUrl().trim() || undefined,
        tokenUrl: this.tokenUrl().trim() || undefined,
        refreshUrl: this.refreshUrl().trim() || undefined,
        deviceAuthorizationUrl: this.deviceAuthorizationUrl().trim() || undefined,
        scopes: this.scopes().split(/\s+/).filter(Boolean),
      }).filter(([, value]) => value !== undefined),
    );
  }
  private configurationForSave(): Readonly<Record<string, unknown>> {
    return {
      ...this.publicConfiguration(),
      ...(this.password() ? { password: this.password() } : {}),
      ...(this.clientSecret() ? { clientSecret: this.clientSecret() } : {}),
    };
  }
  private creationForSave(name: string): CredentialCreation {
    const type = this.type();
    if (type === 'api_key') return { name, type, value: this.value() };
    if (type === 'basic')
      return {
        name,
        type,
        username: this.username().trim(),
        password: this.password(),
        resourceBaseUrl: this.resourceBaseUrl().trim(),
      };
    return { name, type, configuration: this.configurationForSave() };
  }
  private changed(): void {
    this.revision++;
    this.pendingInitial = null;
    this.cancelPendingLogin();
    this.saving.set(false);
    this.loading.set(false);
    this.discovering.set(false);
    this.loggingIn.set(false);
    this.login.set(null);
    this.notice.set('');
    this.failure.set('');
    this.confirmingDelete.set(false);
    this.discoveryPreview.set(null);
  }
  private matches(request: number | undefined, revision: number): boolean {
    return (
      !this.destroyRef.destroyed &&
      request !== undefined &&
      this.request()?.id === request &&
      this.revision === revision
    );
  }
  private clearForm(): void {
    this.clearSecrets();
    this.username.set('');
    this.resourceBaseUrl.set('');
    this.clientId.set('');
    this.clientAuthentication.set('');
    this.grant.set('');
    this.mode.set('browser');
    this.issuer.set('');
    this.metadataUrl.set('');
    this.authorizationUrl.set('');
    this.tokenUrl.set('');
    this.refreshUrl.set('');
    this.deviceAuthorizationUrl.set('');
    this.scopes.set('');
    this.callbackUri.set('');
    this.hasPassword.set(false);
    this.hasClientSecret.set(false);
    this.flows.set([]);
    this.requiredFields.set([]);
    this.selectedFlow.set('');
    this.discoveryPreview.set(null);
    this.savedPublic.set('');
    this.popupBlocked.set(false);
  }
  private clearSecrets(): void {
    this.value.set('');
    this.password.set('');
    this.clientSecret.set('');
  }
  private close(chosen: string | null): void {
    const request = this.request()?.id ?? -1;
    this.changed();
    this.clearSecrets();
    this.credentials.finish(chosen, request);
  }
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}
function isGrant(value: string): value is OAuthGrant {
  return (
    value === 'authorization_code' ||
    value === 'client_credentials' ||
    value === 'device_authorization'
  );
}
function isHttpUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return (
      (url.protocol === 'http:' || url.protocol === 'https:') && !url.username && !url.password
    );
  } catch {
    return false;
  }
}
function messageOf(error: unknown): string {
  const body = error as { error?: { detail?: string; message?: string }; message?: string } | null;
  return (
    body?.error?.detail ?? body?.error?.message ?? body?.message ?? 'Could not reach the engine'
  );
}
