import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { APP_VERSION } from '../../core/app-version';
import { GraphStore } from '../../core/graph/graph-store';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { Translator } from '../../core/i18n/translator';
import { InspectorStore } from '../inspector/inspector-store';
import { RunStore } from '../../core/runtime/run-store';
import { SettingsService } from '../../core/settings/settings.service';
import { LibraryEntry, WorkflowLibraryService } from '../../core/workflows/workflow-library.service';
import { WorkflowSession } from '../../core/workflows/workflow-session';
import { Icon } from '../../shared/icon';
import { WorkflowFileService } from '../workflow-file.service';

/** What the favourites menu is about to replace the canvas with, once unsaved changes are waved through. */
type FavoriteChoice = LibraryEntry | 'example';

/** How long the armed Clear button waits for its second click. */
const CLEAR_ARM_MS = 4_000;

/**
 * The top bar: identity, transport controls, run state and file actions.
 *
 * Run is disabled while the graph has validation problems, and the status pill next to it opens the
 * list of those problems — a disabled control whose reason is only a tooltip is the most common way
 * an editor strands someone.
 *
 * The brand mark is the way into the application settings, and the ★ is the favourites menu: the
 * workflows kept in the library's favorites/ folder, plus the built-in example.
 */
@Component({
  selector: 'app-editor-toolbar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, TranslatePipe],
  templateUrl: './editor-toolbar.html',
  styleUrl: './editor-toolbar.scss',
})
export class EditorToolbar {
  private readonly graph = inject(GraphStore);
  private readonly files = inject(WorkflowFileService);
  private readonly runs = inject(RunStore);
  private readonly inspector = inject(InspectorStore);
  private readonly session = inject(WorkflowSession);
  private readonly library = inject(WorkflowLibraryService);
  private readonly settings = inject(SettingsService);
  private readonly translator = inject(Translator);

  protected readonly version = APP_VERSION;

  /** Whether the settings panel is showing, for the button's pressed state. */
  protected readonly inspectorOpen = this.inspector.open;

  /** Whether there is a node for the panel to show, which is when the button can do anything. */
  protected readonly inspectorCanOpen = this.inspector.canOpen;

  protected readonly canUndo = this.graph.canUndo;
  protected readonly canRedo = this.graph.canRedo;
  protected readonly nodeCount = this.graph.nodeCount;
  protected readonly edgeCount = this.graph.edgeCount;
  protected readonly issues = this.graph.issues;
  protected readonly isRunning = this.runs.isRunning;
  protected readonly summary = this.runs.summary;
  protected readonly connection = this.runs.connection;

  protected readonly problemsOpen = signal(false);

  // --- The document ----------------------------------------------------------

  protected readonly documentName = this.session.name;
  protected readonly dirty = this.session.dirty;

  protected readonly documentTooltip = computed(() => {
    const name = this.documentName();
    if (!name) {
      return this.translator.t('toolbar.documentUntitledTooltip');
    }
    return this.dirty()
      ? this.translator.t('toolbar.documentUnsavedTooltip', { name })
      : this.translator.t('toolbar.documentTooltip', { name });
  });

  // --- Favourites -------------------------------------------------------------

  protected readonly favoritesOpen = signal(false);
  protected readonly favorites = this.library.favorites;
  protected readonly current = this.session.current;
  /** A choice waiting on "discard unsaved changes?" inside the menu. */
  protected readonly favoriteToConfirm = signal<FavoriteChoice | null>(null);

  // --- Clear -------------------------------------------------------------------

  /** Armed: the next click discards unsaved changes. Disarms itself after a moment. */
  protected readonly clearArmed = signal(false);
  private clearTimer: ReturnType<typeof setTimeout> | null = null;

  /**
   * What Ctrl+Z would put back, named.
   *
   * "Undo" alone leaves the user guessing how far back one press goes; "Undo Add Scan Directory"
   * does not.
   */
  protected readonly undoTooltip = computed(() =>
    this.canUndo()
      ? this.translator.t('toolbar.undo', { action: this.graph.lastAction() })
      : this.translator.t('toolbar.nothingToUndo'),
  );

  /** Everything standing between the user and a run: validation issues, then engine rejections. */
  protected readonly problems = computed(() => [
    ...this.issues(),
    ...this.runs.rejections().map((message) => ({ message })),
  ]);

  protected readonly canRun = computed(
    () => this.graph.isRunnable() && !this.isRunning() && this.connection() === 'open',
  );

  protected readonly runTooltip = computed(() => {
    if (this.connection() !== 'open') {
      return this.translator.t('toolbar.notConnected');
    }
    if (this.nodeCount() === 0) {
      return this.translator.t('toolbar.addNodesFirst');
    }
    if (this.graph.runnableCount() === 0) {
      return this.translator.t('toolbar.everyNodeOff');
    }
    const problems = this.issues();
    if (problems.length > 0) {
      return problems.length === 1
        ? problems[0].message
        : this.translator.t('toolbar.problemsStartingWith', { count: problems.length, first: problems[0].message });
    }
    return this.translator.t('toolbar.run');
  });

  protected readonly progressPercent = computed(() => Math.round(this.runs.overallProgress() * 100));

  protected readonly statusText = computed(() => {
    if (this.isRunning()) {
      return this.translator.t('toolbar.running', { percent: this.progressPercent() });
    }
    const failure = this.files.error();
    if (failure) {
      return failure;
    }
    const finished = this.summary();
    if (finished) {
      const seconds = (finished.durationMillis / 1000).toFixed(2);
      return this.translator.t('toolbar.finishedIn', { outcome: titleCase(finished.outcome), seconds });
    }
    const problems = this.problems();
    if (problems.length > 0) {
      return this.translator.count('toolbar.problems', problems.length);
    }
    // "skipped" rather than "off": the count includes nodes nobody switched off, only ones that
    // happen to sit downstream of one that is.
    const skipped = this.nodeCount() - this.graph.runnableCount();
    const params = { nodes: this.nodeCount(), edges: this.edgeCount(), skipped };
    return skipped > 0
      ? this.translator.t('toolbar.summarySkipped', params)
      : this.translator.t('toolbar.summary', params);
  });

  protected readonly statusKind = computed(() => {
    if (this.isRunning()) {
      return 'running';
    }
    if (this.files.error()) {
      return 'error';
    }
    const finished = this.summary();
    if (finished) {
      return finished.outcome === 'COMPLETED' ? 'ok' : 'error';
    }
    return this.problems().length > 0 ? 'warn' : 'idle';
  });

  protected readonly connectionText = computed(() => {
    switch (this.connection()) {
      case 'open':
        return this.translator.t('toolbar.connected');
      case 'connecting':
        return this.translator.t('toolbar.connecting');
      default:
        return this.translator.t('toolbar.offline');
    }
  });

  protected toggleProblems(): void {
    if (this.problems().length > 0) {
      this.problemsOpen.update((open) => !open);
    }
  }

  protected closeProblems(): void {
    this.problemsOpen.set(false);
  }

  protected run(): void {
    this.runs.start();
  }

  protected stop(): void {
    this.runs.cancel();
  }

  protected undo(): void {
    this.graph.undo();
  }

  protected redo(): void {
    this.graph.redo();
  }

  protected openSettings(): void {
    this.settings.show('general');
  }

  protected save(): void {
    this.files.save().subscribe({ error: () => undefined });
  }

  protected open(): void {
    this.files.showLibrary();
  }

  /**
   * Clears the canvas — in two clicks when there is something unsaved on it.
   *
   * The first click arms the button and says so; the second, within a few seconds, discards. A
   * canvas nobody has changed since it was saved, or an empty one, clears at once.
   */
  protected clear(): void {
    if (this.dirty() && this.nodeCount() > 0 && !this.clearArmed()) {
      this.clearArmed.set(true);
      this.clearTimer = setTimeout(() => this.clearArmed.set(false), CLEAR_ARM_MS);
      return;
    }
    if (this.clearTimer) {
      clearTimeout(this.clearTimer);
      this.clearTimer = null;
    }
    this.clearArmed.set(false);
    this.files.startNew();
  }

  /** The settings panel, from the toolbar: the way back to it once it has been closed. */
  protected toggleInspector(): void {
    this.inspector.toggle();
  }

  // --- Favourites -------------------------------------------------------------

  protected toggleFavorites(): void {
    const opening = !this.favoritesOpen();
    this.favoritesOpen.set(opening);
    this.favoriteToConfirm.set(null);
    if (opening) {
      // Re-read on every opening: a file may have been starred in the library, or dropped into
      // the favorites/ folder by hand.
      this.library.load();
    }
  }

  protected closeFavorites(): void {
    this.favoritesOpen.set(false);
    this.favoriteToConfirm.set(null);
  }

  /** A favourite, or the example: onto the canvas, after asking about unsaved changes. */
  protected choose(choice: FavoriteChoice): void {
    if (this.dirty() && this.nodeCount() > 0) {
      this.favoriteToConfirm.set(choice);
      return;
    }
    this.load(choice);
  }

  protected confirmChoice(): void {
    const choice = this.favoriteToConfirm();
    if (choice) {
      this.load(choice);
    }
  }

  protected cancelChoice(): void {
    this.favoriteToConfirm.set(null);
  }

  /** Stars or unstars the workflow on the canvas — only meaningful once it is in the library. */
  protected toggleCurrentFavorite(): void {
    const entry = this.current();
    if (!entry) {
      return;
    }
    this.files.setFavorite(entry, !entry.favorite).subscribe({ error: () => undefined });
  }

  protected openLibraryFromMenu(): void {
    this.closeFavorites();
    this.files.showLibrary();
  }

  protected isEntry(choice: FavoriteChoice | null): choice is LibraryEntry {
    return choice !== null && choice !== 'example';
  }

  private load(choice: FavoriteChoice): void {
    this.closeFavorites();
    if (choice === 'example') {
      this.files.loadExample();
      return;
    }
    this.files.open(choice).subscribe({ error: () => undefined });
  }
}

function titleCase(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase();
}
