import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { APP_VERSION } from '../../core/app-version';
import { WidgetSpec } from '../../core/catalog/catalog.models';
import { CredentialService } from '../../core/credentials/credential.service';
import { CountPipe } from '../../core/i18n/count.pipe';
import { MessageKey, PluralKey } from '../../core/i18n/messages/en';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { Translator } from '../../core/i18n/translator';
import { PresetService } from '../../core/presets/preset.service';
import { ProfileSchemaSummary, ProfileService } from '../../core/profiles/profile.service';
import {
  BundleInspection,
  BundleSection,
  ConflictPolicy,
  ImportReport,
  SettingsPage,
  SettingsService,
  messageOf,
} from '../../core/settings/settings.service';
import { WorkflowLibraryService } from '../../core/workflows/workflow-library.service';
import { NodeWidget } from '../../widgets/node-widget';
import { download } from '../download';
import { FileBrowserService } from '../file-browser/file-browser.service';
import { Icon } from '../icon';

type Target = 'data' | 'workflows';

/** A line of the folder tree drawn under a location. */
interface TreeLine {
  readonly icon: string;
  readonly name: string;
  readonly count: number;
  readonly countKey: PluralKey;
}

/** One relocatable directory, as the storage page draws it. */
interface Location {
  readonly target: Target;
  readonly titleKey: MessageKey;
  readonly hintKey: MessageKey;
  readonly effective: string;
  readonly configured: string;
  readonly tree: readonly TreeLine[];
}

interface Notice {
  readonly text: string;
  readonly kind: 'ok' | 'error';
}

/**
 * The application settings: opened from the brand mark, five pages down a rail.
 *
 * Every page is a view over a service — settings, profiles, credentials, the bundle endpoints —
 * and holds nothing but what is on screen: which page, which boxes are ticked, what was typed into
 * the password field. Closing it discards exactly that.
 *
 * Rendered once by the shell, above the canvas and below the pickers: the folder browser and the
 * profile dialog open *over* this one, since this is where they are launched from.
 */
@Component({
  selector: 'app-settings-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, NodeWidget, TranslatePipe, CountPipe],
  templateUrl: './settings-dialog.html',
  styleUrl: './settings-dialog.scss',
  host: {
    '(document:keydown)': 'onKeydown($event)',
  },
})
export class SettingsDialog {
  private readonly settings = inject(SettingsService);
  private readonly translator = inject(Translator);
  private readonly profiles = inject(ProfileService);
  private readonly credentials = inject(CredentialService);
  private readonly presets = inject(PresetService);
  private readonly library = inject(WorkflowLibraryService);
  private readonly browser = inject(FileBrowserService);

  protected readonly page = this.settings.open;
  protected readonly state = this.settings.settings;
  protected readonly loadError = this.settings.error;
  protected readonly editorVersion = APP_VERSION;

  protected readonly pages: readonly { id: SettingsPage; icon: string; key: MessageKey }[] = [
    { id: 'general', icon: 'settings', key: 'settings.page.general' },
    { id: 'storage', icon: 'drive', key: 'settings.page.storage' },
    { id: 'profiles', icon: 'plug', key: 'settings.page.profiles' },
    { id: 'backup', icon: 'layers', key: 'settings.page.backup' },
    { id: 'about', icon: 'help', key: 'settings.page.about' },
  ];

  // --- General ---------------------------------------------------------------

  protected readonly language = this.settings.language;
  protected readonly languageSpec = computed<WidgetSpec>(() => ({
    kind: 'dropdown',
    options: this.translator.languages.map((language) => ({ value: language.code, label: language.label })),
    optionsKey: '',
    allowCustom: false,
    narrowing: null,
  }));
  protected readonly generalNotice = signal<Notice | null>(null);

  // --- Storage ---------------------------------------------------------------

  /** The bundle sections, whose counts double as the storage page's folder counts. */
  protected readonly sections = signal<readonly BundleSection[]>([]);
  protected readonly counts = computed(() => {
    const counts: Record<string, number> = {};
    for (const section of this.sections()) {
      counts[section.id] = section.count;
    }
    return counts;
  });

  protected readonly locations = computed<readonly Location[]>(() => {
    const current = this.state();
    if (!current) {
      return [];
    }
    const count = (id: string) => this.counts()[id] ?? 0;
    return [
      {
        target: 'data',
        titleKey: 'settings.storage.data',
        hintKey: 'settings.storage.dataHint',
        effective: current.paths.data.effective,
        configured: current.paths.data.configured,
        tree: [
          { icon: 'key', name: 'credentials.properties', count: count('credentials'), countKey: 'settings.storage.keys' },
          { icon: 'folder', name: 'profiles/', count: count('profiles'), countKey: 'settings.storage.profiles' },
          { icon: 'folder', name: 'presets/', count: count('presets'), countKey: 'settings.storage.presets' },
        ],
      },
      {
        target: 'workflows',
        titleKey: 'settings.storage.workflows',
        hintKey: 'settings.storage.workflowsHint',
        effective: current.paths.workflows.effective,
        configured: current.paths.workflows.configured,
        tree: [
          { icon: 'star', name: 'favorites/', count: 0, countKey: 'settings.storage.workflowsCount' },
          { icon: 'documents', name: '*.unbi.json', count: count('workflows'), countKey: 'settings.storage.workflowsCount' },
        ],
      },
    ];
  });

  /** A change chosen but not applied yet: the folder, and whether to copy into it. */
  protected readonly pending = signal<{ target: Target; directory: string } | null>(null);
  protected readonly copyExisting = signal(true);
  protected readonly relocating = signal(false);
  protected readonly storageNotice = signal<Notice | null>(null);

  // --- Endpoints & keys ------------------------------------------------------

  protected readonly schemas = this.profiles.schemas;
  protected readonly keys = this.credentials.names;
  protected readonly keyError = this.credentials.error;
  protected readonly newKeyName = signal('');
  protected readonly newKeyValue = signal('');
  protected readonly addingKey = signal(false);
  protected readonly keyNotice = signal<Notice | null>(null);
  protected readonly removingKey = signal<string | null>(null);
  protected readonly canAddKey = computed(
    () => /^[A-Za-z0-9][A-Za-z0-9._-]{0,60}$/.test(this.newKeyName().trim()) && this.newKeyValue().trim().length > 0 && !this.addingKey(),
  );

  // --- Backup: export ---------------------------------------------------------

  protected readonly chosen = signal<ReadonlySet<string>>(new Set());
  protected readonly protect = signal(false);
  protected readonly password = signal('');
  protected readonly passwordAgain = signal('');
  protected readonly exporting = signal(false);
  protected readonly exportNotice = signal<Notice | null>(null);

  protected readonly passwordProblem = computed<MessageKey | ''>(() => {
    if (!this.protect()) {
      return '';
    }
    if (this.password().length < 8) {
      return 'settings.backup.passwordShort';
    }
    return this.password() === this.passwordAgain() ? '' : 'settings.backup.passwordMismatch';
  });

  /** Secrets are ticked and nothing protects them: the one warning worth a colour. */
  protected readonly plainKeys = computed(() => {
    const sensitive = this.sections().filter((section) => section.sensitive && this.chosen().has(section.id));
    return sensitive.length > 0 && !(this.protect() && this.password().length > 0);
  });

  protected readonly canExport = computed(
    () => this.chosen().size > 0 && !this.exporting() && this.passwordProblem() === '',
  );

  // --- Backup: import ---------------------------------------------------------

  protected readonly importFile = signal<File | null>(null);
  protected readonly inspection = signal<BundleInspection | null>(null);
  protected readonly inspecting = signal(false);
  protected readonly importChosen = signal<ReadonlySet<string>>(new Set());
  protected readonly importPassword = signal('');
  protected readonly conflicts = signal<ConflictPolicy>('skip');
  protected readonly importing = signal(false);
  protected readonly importNotice = signal<Notice | null>(null);
  protected readonly report = signal<ImportReport | null>(null);

  protected readonly conflictOptions: readonly { value: ConflictPolicy; key: MessageKey }[] = [
    { value: 'skip', key: 'settings.backup.conflicts.skip' },
    { value: 'replace', key: 'settings.backup.conflicts.replace' },
    { value: 'keep-both', key: 'settings.backup.conflicts.keepBoth' },
  ];

  protected readonly canImport = computed(() => {
    const found = this.inspection();
    return (
      !!found &&
      !!this.importFile() &&
      this.importChosen().size > 0 &&
      !this.importing() &&
      (!found.encrypted || this.importPassword().length > 0)
    );
  });

  /** "Written 17 Sep 2026, 11:02 by engine 0.1.0", in the user's language and zone. */
  protected readonly fileFrom = computed(() => {
    const found = this.inspection();
    if (!found) {
      return '';
    }
    const when = new Date(found.createdAt);
    const formatted = Number.isNaN(when.getTime())
      ? found.createdAt
      : new Intl.DateTimeFormat(this.translator.language(), { dateStyle: 'medium', timeStyle: 'short' }).format(when);
    return this.translator.t('settings.backup.fileFrom', { when: formatted, engine: found.engine || '?' });
  });

  constructor() {
    // Each opening starts fresh: the counts, the schemas and the key names are re-read, and what
    // was ticked or typed last time is gone.
    let wasOpen = false;
    effect(() => {
      const open = this.page() !== null;
      if (open && !wasOpen) {
        untracked(() => this.refresh());
      }
      if (!open && wasOpen) {
        untracked(() => this.reset());
      }
      wasOpen = open;
    });
  }

  protected goTo(page: SettingsPage): void {
    this.settings.goTo(page);
  }

  protected close(): void {
    this.settings.close();
  }

  protected onKeydown(event: KeyboardEvent): void {
    // The pickers that open from here own Escape while they are up.
    if (!this.page() || this.browser.open() !== null || this.profiles.editing() !== null) {
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      this.close();
    }
  }

  // --- General ---------------------------------------------------------------

  protected setLanguage(value: unknown): void {
    const code = String(value ?? '');
    if (!code || code === this.language()) {
      return;
    }
    this.settings.savePreferences({ language: code }).subscribe({
      next: () => this.generalNotice.set({ text: this.translator.t('settings.general.saved'), kind: 'ok' }),
      error: (error: unknown) => this.generalNotice.set({ text: messageOf(error), kind: 'error' }),
    });
  }

  // --- Storage ---------------------------------------------------------------

  protected async pickDirectory(location: Location): Promise<void> {
    const chosen = await this.browser.pick({
      mode: 'directory',
      title: this.translator.t('settings.storage.pick', { what: this.translator.t(location.titleKey) }),
      startAt: location.effective,
      extensions: [],
    });
    if (chosen) {
      this.propose(location.target, chosen);
    }
  }

  protected useDefault(location: Location): void {
    this.propose(location.target, '');
  }

  protected cancelRelocation(): void {
    this.pending.set(null);
  }

  protected applyRelocation(): void {
    const change = this.pending();
    if (!change || this.relocating()) {
      return;
    }
    this.relocating.set(true);
    this.storageNotice.set(null);
    this.settings.relocate(change.target, change.directory, this.copyExisting()).subscribe({
      next: ({ relocation }) => {
        this.relocating.set(false);
        this.pending.set(null);
        this.storageNotice.set({
          kind: 'ok',
          text:
            relocation.copied > 0 || relocation.skipped > 0
              ? this.translator.t('settings.storage.appliedCopied', {
                  to: relocation.to,
                  copied: relocation.copied,
                  skipped: relocation.skipped,
                })
              : this.translator.t('settings.storage.applied', { to: relocation.to }),
        });
        this.refresh();
        this.reloadStores();
      },
      error: (error: unknown) => {
        this.relocating.set(false);
        this.storageNotice.set({ text: messageOf(error), kind: 'error' });
      },
    });
  }

  protected setCopyExisting(event: Event): void {
    this.copyExisting.set((event.target as HTMLInputElement).checked);
  }

  /** A change to confirm: copying is offered only when there is something to copy. */
  private propose(target: Target, directory: string): void {
    const counts = this.counts();
    const content =
      target === 'data'
        ? (counts['profiles'] ?? 0) + (counts['presets'] ?? 0) + (counts['credentials'] ?? 0)
        : (counts['workflows'] ?? 0);
    this.copyExisting.set(content > 0);
    this.storageNotice.set(null);
    this.pending.set({ target, directory });
  }

  // --- Endpoints & keys ------------------------------------------------------

  protected manage(schema: ProfileSchemaSummary): void {
    void this.profiles.edit({ schema: schema.id, current: '' }).then(() => {
      this.profiles.loadSchemas();
      this.credentials.load();
    });
  }

  protected setNewKeyName(event: Event): void {
    this.newKeyName.set((event.target as HTMLInputElement).value);
  }

  protected setNewKeyValue(event: Event): void {
    this.newKeyValue.set((event.target as HTMLInputElement).value);
  }

  protected addKey(): void {
    if (!this.canAddKey()) {
      return;
    }
    const name = this.newKeyName().trim();
    this.addingKey.set(true);
    this.credentials.add(name, this.newKeyValue().trim()).subscribe((accepted) => {
      this.addingKey.set(false);
      if (accepted) {
        this.newKeyName.set('');
        this.newKeyValue.set('');
        this.keyNotice.set({ text: this.translator.t('settings.credentials.added', { name }), kind: 'ok' });
        this.refreshCounts();
      } else {
        this.keyNotice.set({ text: this.keyError(), kind: 'error' });
      }
    });
  }

  protected askRemoveKey(name: string): void {
    this.removingKey.set(name);
  }

  protected cancelRemoveKey(): void {
    this.removingKey.set(null);
  }

  protected removeKey(name: string): void {
    this.removingKey.set(null);
    this.credentials.remove(name);
    this.keyNotice.set(null);
    this.refreshCounts();
  }

  // --- Backup: export ---------------------------------------------------------

  protected toggleSection(id: string): void {
    this.chosen.update((current) => {
      const next = new Set(current);
      if (!next.delete(id)) {
        next.add(id);
      }
      return next;
    });
  }

  protected setProtect(event: Event): void {
    this.protect.set((event.target as HTMLInputElement).checked);
  }

  protected setPassword(event: Event): void {
    this.password.set((event.target as HTMLInputElement).value);
  }

  protected setPasswordAgain(event: Event): void {
    this.passwordAgain.set((event.target as HTMLInputElement).value);
  }

  protected exportBundle(): void {
    if (!this.canExport()) {
      return;
    }
    this.exporting.set(true);
    this.exportNotice.set(null);
    const fileName = `unbi-settings-${new Date().toISOString().slice(0, 10)}.ucfg`;
    this.settings.exportBundle([...this.chosen()], this.protect() ? this.password() : '').subscribe({
      next: (blob) => {
        this.exporting.set(false);
        download(blob, fileName);
        this.exportNotice.set({ text: this.translator.t('settings.backup.exported', { file: fileName }), kind: 'ok' });
      },
      error: (error: unknown) => {
        this.exporting.set(false);
        this.exportNotice.set({ text: messageOf(error), kind: 'error' });
      },
    });
  }

  // --- Backup: import ---------------------------------------------------------

  protected chooseFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    // Cleared so that choosing the same file again fires a change event.
    input.value = '';
    if (!file) {
      return;
    }
    this.importFile.set(file);
    this.inspection.set(null);
    this.report.set(null);
    this.importNotice.set(null);
    this.importPassword.set('');
    this.inspecting.set(true);
    this.settings.inspectBundle(file).subscribe({
      next: (found) => {
        this.inspecting.set(false);
        this.inspection.set(found);
        this.importChosen.set(
          new Set(found.sections.filter((section) => section.known && section.count > 0).map((section) => section.id)),
        );
      },
      error: (error: unknown) => {
        this.inspecting.set(false);
        this.importFile.set(null);
        this.importNotice.set({ text: messageOf(error), kind: 'error' });
      },
    });
  }

  protected toggleImportSection(id: string): void {
    this.importChosen.update((current) => {
      const next = new Set(current);
      if (!next.delete(id)) {
        next.add(id);
      }
      return next;
    });
  }

  protected setImportPassword(event: Event): void {
    this.importPassword.set((event.target as HTMLInputElement).value);
  }

  protected setConflicts(value: ConflictPolicy): void {
    this.conflicts.set(value);
  }

  protected importBundle(): void {
    const file = this.importFile();
    if (!file || !this.canImport()) {
      return;
    }
    this.importing.set(true);
    this.importNotice.set(null);
    // A report describes one attempt; one that failed must not leave the last success on screen.
    this.report.set(null);
    this.settings.importBundle(file, [...this.importChosen()], this.importPassword(), this.conflicts()).subscribe({
      next: (report) => {
        this.importing.set(false);
        this.report.set(report);
        this.importNotice.set({ text: this.translator.t('settings.backup.done'), kind: 'ok' });
        this.refresh();
        this.reloadStores();
      },
      error: (error: unknown) => {
        this.importing.set(false);
        this.importNotice.set({ text: messageOf(error), kind: 'error' });
      },
    });
  }

  /** The label for a section id: the editor's own words where it has them, the engine's otherwise. */
  protected sectionLabel(id: string, fallback: string): string {
    const key = `settings.sections.${id}` as MessageKey;
    const known: readonly MessageKey[] = [
      'settings.sections.profiles',
      'settings.sections.credentials',
      'settings.sections.presets',
      'settings.sections.workflows',
      'settings.sections.preferences',
    ];
    return known.includes(key) ? this.translator.t(key) : fallback;
  }

  protected problemsOf(report: ImportReport): number {
    return report.sections.reduce((total, section) => total + section.problems.length, 0);
  }

  // --- Lifecycle -------------------------------------------------------------

  private refresh(): void {
    this.refreshCounts();
    this.profiles.loadSchemas();
    this.credentials.load();
  }

  private refreshCounts(): void {
    this.settings.sections().subscribe({
      next: (sections) => this.sections.set(sections),
      error: () => this.sections.set([]),
    });
  }

  /** After an import or a relocation, everything the rest of the editor caches is stale. */
  private reloadStores(): void {
    this.presets.load();
    this.credentials.load();
    this.library.load();
    this.profiles.loadSchemas();
    for (const schema of this.profiles.schemas()) {
      this.profiles.load(schema.id, true);
    }
  }

  private reset(): void {
    this.pending.set(null);
    this.storageNotice.set(null);
    this.generalNotice.set(null);
    this.keyNotice.set(null);
    this.removingKey.set(null);
    this.newKeyName.set('');
    this.newKeyValue.set('');
    this.chosen.set(new Set());
    this.protect.set(false);
    this.password.set('');
    this.passwordAgain.set('');
    this.exportNotice.set(null);
    this.importFile.set(null);
    this.inspection.set(null);
    this.importChosen.set(new Set());
    this.importPassword.set('');
    this.conflicts.set('skip');
    this.importNotice.set(null);
    this.report.set(null);
  }
}
