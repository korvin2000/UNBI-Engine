import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { CredentialService, CredentialDraft } from '../../core/credentials/credential.service';
import { NodeInputSpec } from '../../core/catalog/catalog.models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { Translator } from '../../core/i18n/translator';
import {
  ProfileEditRequest,
  OpenApiImportRequest,
  OpenApiImportResult,
  Profile,
  ProfileService,
  ProfileTestResult,
} from '../../core/profiles/profile.service';
import { NodeWidget } from '../../widgets/node-widget';
import { Icon } from '../icon';
/**
 * Where profiles are made, tested, edited and deleted.
 *
 * Generic on purpose: the fields it draws are whatever the engine declared for the schema, drawn
 * with the same widgets a node uses. This dialog knows that a profile has a name, a description and
 * values, and nothing about what an endpoint is — which is what lets a second schema appear with no
 * change here.
 *
 * Rendered once by the shell, like the file picker and for the same reason: a dialog opened from a
 * node would live in the canvas's transformed, clipped layer.
 */
@Component({
  selector: 'app-profile-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, NodeWidget, TranslatePipe],
  templateUrl: './profile-dialog.html',
  styleUrl: './profile-dialog.scss',
  host: {
    '(document:keydown)': 'onKeydown($event)',
  },
})
export class ProfileDialog {
  private readonly profiles = inject(ProfileService);
  private readonly credentials = inject(CredentialService);
  private readonly translator = inject(Translator);
  private opening: ProfileEditRequest | null = null;
  private seeded = false;
  private revision = 0;
  private importRevision = 0;

  protected readonly importDocument = signal('');
  protected readonly documentUri = signal('');
  protected readonly operation = signal<string | undefined>(undefined);
  protected readonly server = signal<string | undefined>(undefined);
  protected readonly security = signal<string | undefined>(undefined);
  protected readonly protocol = signal<string | undefined>(undefined);
  protected readonly variables = signal<Readonly<Record<string, string>>>({});
  protected readonly importResult = signal<OpenApiImportResult | null>(null);
  protected readonly importing = signal(false);
  protected readonly importCurrent = signal(false);
  protected readonly importError = signal('');
  protected readonly credentialDraft = signal<CredentialDraft | null>(null);
  protected readonly importSupported = computed(
    () => this.schema()?.importFormats.includes('openapi') ?? false,
  );
  protected readonly serverVariables = computed(() =>
    Object.entries(
      this.importResult()?.servers.find((server) => server.id === this.server())?.variables ?? {},
    ),
  );
  protected readonly importChanges = computed(() =>
    Object.entries(this.importResult()?.values ?? {})
      .filter(([key, value]) => serialise(this.draft()[key]) !== serialise(value))
      .map(([key, value]) => ({
        key,
        label: this.schema()?.fields.find((field) => field.key === key)?.label ?? key,
        before: serialise(this.draft()[key]),
        after: serialise(value),
      })),
  );

  protected readonly request = this.profiles.editing;

  /** The schema and its profiles, for whichever schema is open. */
  protected readonly state = computed(() => {
    const request = this.request();
    return request ? this.profiles.state(request.schema)() : null;
  });

  protected readonly schema = computed(() => this.state()?.schema ?? null);
  protected readonly list = computed(() => this.state()?.profiles ?? []);

  /** The id being edited, or '' for a profile that has not been saved yet. */
  protected readonly selectedId = signal('');
  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly draft = signal<Readonly<Record<string, unknown>>>({});

  protected readonly saving = signal(false);
  protected readonly testing = signal(false);
  protected readonly confirmingDelete = signal(false);
  protected readonly testResult = signal<ProfileTestResult | null>(null);
  protected readonly notice = signal('');
  protected readonly failure = signal('');

  protected readonly selected = computed<Profile | null>(
    () => this.list().find((profile) => profile.id === this.selectedId()) ?? null,
  );

  protected readonly plainFields = computed(() =>
    (this.schema()?.fields ?? []).filter((f) => !f.advanced),
  );
  protected readonly advancedFields = computed(() =>
    (this.schema()?.fields ?? []).filter((f) => f.advanced),
  );

  protected readonly canSave = computed(
    () => !!this.schema() && this.name().trim().length > 0 && !this.saving(),
  );

  /** Whether what is on screen differs from what is saved. */
  protected readonly dirty = computed(() => {
    const saved = this.selected();
    if (!saved) {
      return this.name().trim().length > 0 || Object.keys(this.draft()).length > 0;
    }
    return (
      saved.name !== this.name().trim() ||
      saved.description !== this.description().trim() ||
      serialise(this.withDefaults(saved.values)) !== serialise(this.withDefaults(this.draft()))
    );
  });

  constructor() {
    effect(() => {
      const request = this.request();
      const state = this.state();
      untracked(() => {
        if (request !== this.opening) {
          this.opening = request;
          this.startNew();
          this.seeded = false;
        }
        if (!request || !state?.schema || this.seeded) return;
        const first =
          state.profiles.find((profile) => profile.id === request.current) ?? state.profiles[0];
        if (first) this.select(first);
        else this.startNew();
      });
    });
  }

  protected select(profile: Profile): void {
    this.selectedId.set(profile.id);
    this.name.set(profile.name);
    this.description.set(profile.description);
    this.draft.set(this.withDefaults(profile.values));
    this.clearMessages();
    this.resetImport();
  }

  protected startNew(): void {
    this.selectedId.set('');
    this.name.set('');
    this.description.set('');
    this.draft.set(this.withDefaults({}));
    this.clearMessages();
    this.resetImport();
  }

  /** A copy to start from: the same values under a new name, unsaved until Save. */
  protected duplicate(): void {
    const source = this.selected();
    if (!source) {
      return;
    }
    this.selectedId.set('');
    this.name.set(`${source.name} copy`);
    this.clearMessages();
  }

  protected setValue(key: string, value: unknown): void {
    this.draft.update((current) => ({ ...current, [key]: value }));
    // A test result describes the values it ran against, and they just changed.
    this.clearMessages();
  }

  protected save(use = false): void {
    const request = this.request();
    if (!request || !this.canSave()) return;
    const revision = this.revision;
    this.saving.set(true);
    this.failure.set('');
    this.profiles
      .save(request.schema, {
        id: this.selectedId(),
        name: this.name().trim(),
        description: this.description().trim(),
        values: this.withDefaults(this.draft()),
      })
      .subscribe({
        next: (saved) => {
          if (!this.matches(request, revision)) return;
          this.saving.set(false);
          if (use) this.close(saved.id);
          else {
            this.select(saved);
            this.notice.set(`Saved ${saved.name}.`);
          }
        },
        error: (error: unknown) => {
          if (!this.matches(request, revision)) return;
          this.saving.set(false);
          this.failure.set(messageOf(error));
        },
      });
  }

  protected test(): void {
    const request = this.request();
    if (!request || this.testing()) {
      return;
    }
    const revision = this.revision;
    this.testing.set(true);
    this.testResult.set(null);
    this.profiles.test(request.schema, this.draft()).subscribe((result) => {
      if (!this.matches(request, revision)) return;
      this.testing.set(false);
      this.testResult.set(result);
    });
  }

  protected askDelete(): void {
    this.confirmingDelete.set(true);
  }

  protected cancelDelete(): void {
    this.confirmingDelete.set(false);
  }

  protected deleteSelected(): void {
    const request = this.request();
    const doomed = this.selected();
    if (!request || !doomed) {
      return;
    }
    const revision = this.revision;
    this.confirmingDelete.set(false);
    this.profiles.delete(request.schema, doomed.id).subscribe({
      next: () => {
        if (!this.matches(request, revision)) return;
        const remaining = this.list().filter((profile) => profile.id !== doomed.id);
        if (remaining.length > 0) {
          this.select(remaining[0]);
        } else {
          this.startNew();
        }
        this.notice.set(`Deleted ${doomed.name}.`);
      },
      error: (error: unknown) => {
        if (this.matches(request, revision)) this.failure.set(messageOf(error));
      },
    });
  }

  /**
   * Done: the node points at what is on screen.
   *
   * Saves first when something changed and can be saved, because the profile the user just typed
   * out is the one they mean to use — asking them to press Save before Done would only teach them
   * that Done loses work.
   */
  protected finish(): void {
    if (this.saving()) return;
    if (this.dirty() && this.canSave()) this.save(true);
    else this.close(this.selectedId() || null);
  }

  /** Close without saving what is on screen; the node keeps whichever saved profile is selected. */
  protected dismiss(): void {
    this.close(this.selectedId() || null);
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (!this.request() || this.credentials.editing()) {
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      this.dismiss();
    }
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      this.finish();
    }
  }

  protected setName(event: Event): void {
    this.name.set((event.target as HTMLInputElement).value);
    this.clearMessages();
  }

  protected setDescription(event: Event): void {
    this.description.set((event.target as HTMLInputElement).value);
    this.clearMessages();
  }

  protected valueOf(field: NodeInputSpec): unknown {
    const value = this.draft()[field.key];
    return value === undefined ? field.defaultValue : value;
  }

  /** Whether a field's declared condition holds against the draft. */
  protected applies(field: NodeInputSpec): boolean {
    const condition = field.showWhen;
    if (!condition) {
      return true;
    }
    const sibling = this.schema()?.fields.find((candidate) => candidate.key === condition.key);
    return condition.values.includes(String(sibling ? this.valueOf(sibling) : undefined));
  }

  private close(chosen: string | null): void {
    this.selectedId.set('');
    this.name.set('');
    this.description.set('');
    this.draft.set({});
    this.clearMessages();
    this.resetImport();
    this.profiles.finish(chosen, this.request()?.id);
  }

  private clearMessages(): void {
    ++this.revision;
    this.seeded = true;
    this.saving.set(false);
    this.testing.set(false);
    this.importing.set(false);
    this.testResult.set(null);
    this.notice.set('');
    this.failure.set('');
    this.confirmingDelete.set(false);
  }

  protected variableValue(name: string, fallback?: string): string {
    return this.variables()[name] ?? fallback ?? '';
  }

  private matches(request: ProfileEditRequest, revision: number): boolean {
    return this.request() === request && this.revision === revision;
  }

  protected setImportText(event: Event, base = false): void {
    const value = (event.target as HTMLInputElement).value;
    if (base) this.documentUri.set(value);
    else {
      this.resetImport();
      this.importDocument.set(value);
    }
    this.invalidateImport();
  }

  protected async uploadImport(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    const request = this.request();
    if (!file || !request) return;
    this.resetImport();
    const revision = this.revision;
    const importing = this.importRevision;
    if (file.size > 10 * 1024 * 1024) {
      this.importError.set(this.translator.t('profile.import.oversize'));
      return;
    }
    try {
      const text = await file.text();
      if (!this.matches(request, revision) || importing !== this.importRevision) return;
      this.importDocument.set(text);
      this.previewImport();
    } catch (error) {
      if (this.matches(request, revision) && importing === this.importRevision)
        this.importError.set(messageOf(error));
    }
  }

  protected chooseImport(
    kind: 'operation' | 'server' | 'security' | 'protocol',
    event: Event,
  ): void {
    const value = (event.target as HTMLSelectElement).value;
    this[kind].set(value === '__unset__' ? undefined : value);
    if (kind === 'operation') {
      this.server.set(undefined);
      this.security.set(undefined);
    }
    if (kind === 'operation' || kind === 'server') this.variables.set({});
    this.invalidateImport();
    this.previewImport();
  }

  protected setVariable(name: string, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.variables.update((current) => ({ ...current, [name]: value }));
    this.invalidateImport();
  }

  protected previewImport(): void {
    const request = this.request();
    if (!request || !this.importSupported() || !this.importDocument().trim()) return;
    if (new TextEncoder().encode(this.importDocument()).byteLength > 10 * 1024 * 1024) {
      this.invalidateImport();
      this.importError.set(this.translator.t('profile.import.oversize'));
      return;
    }
    const revision = this.revision;
    const importing = ++this.importRevision;
    const selection: OpenApiImportRequest = {
      document: this.importDocument(),
      documentUri: this.documentUri() || undefined,
      operation: this.operation(),
      server: this.server(),
      securityAlternative: this.security(),
      variables: this.variables(),
      apiFormat: this.protocol(),
    };
    this.importing.set(true);
    this.importCurrent.set(false);
    this.importError.set('');
    this.profiles.importOpenApi(request.schema, selection).subscribe({
      next: (result) => {
        if (!this.matches(request, revision) || importing !== this.importRevision) return;
        this.importing.set(false);
        this.importResult.set(result);
        this.importCurrent.set(true);
        this.operation.set(result.selectedOperation);
        this.server.set(result.selectedServer);
        this.security.set(result.selectedSecurityAlternative);
      },
      error: (error: unknown) => {
        if (!this.matches(request, revision) || importing !== this.importRevision) return;
        this.importing.set(false);
        this.importError.set(messageOf(error));
      },
    });
  }

  protected applyImport(): void {
    const result = this.importResult();
    if (!result?.complete || !this.importCurrent() || this.importing()) return;
    this.draft.update((current) => ({ ...current, ...result.values }));
    this.clearMessages();
    this.credentialDraft.set(result.credentialDraft);
    this.importCurrent.set(false);
    this.notice.set(this.translator.t('profile.import.applied'));
  }

  protected async configureCredential(): Promise<void> {
    const request = this.request();
    const draft = this.credentialDraft();
    if (!request || !draft) return;
    const revision = this.revision;
    const chosen = await this.credentials.edit({ current: '', draft });
    if (chosen !== null && this.matches(request, revision)) this.setValue('credential', chosen);
  }

  private invalidateImport(): void {
    ++this.importRevision;
    this.importing.set(false);
    this.importCurrent.set(false);
    this.importError.set('');
  }

  private resetImport(): void {
    this.invalidateImport();
    this.importDocument.set('');
    this.documentUri.set('');
    this.operation.set(undefined);
    this.server.set(undefined);
    this.security.set(undefined);
    this.protocol.set(undefined);
    this.variables.set({});
    this.importResult.set(null);
    this.credentialDraft.set(null);
  }

  /** Every declared field present, so a comparison of two drafts is a comparison of the same keys. */
  private withDefaults(values: Readonly<Record<string, unknown>>): Record<string, unknown> {
    const complete: Record<string, unknown> = {};
    for (const field of this.schema()?.fields ?? []) {
      const value = values[field.key];
      complete[field.key] = value === undefined || value === null ? field.defaultValue : value;
    }
    return complete;
  }
}

function serialise(value: unknown): string {
  try {
    return JSON.stringify(value ?? null);
  } catch {
    return String(value);
  }
}

function messageOf(error: unknown): string {
  const body = (error as { error?: { detail?: string; message?: string } } | null)?.error;
  return body?.detail ?? body?.message ?? 'Could not reach the engine';
}
