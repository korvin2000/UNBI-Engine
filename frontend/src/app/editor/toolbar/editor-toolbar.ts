import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { GraphStore } from '../../core/graph/graph-store';
import { InspectorStore } from '../inspector/inspector-store';
import { RunStore } from '../../core/runtime/run-store';
import { Icon } from '../../shared/icon';
import { WorkflowFileService } from '../workflow-file.service';

/**
 * The top bar: identity, transport controls, run state and file actions.
 *
 * Run is disabled while the graph has validation problems, and the status pill next to it opens the
 * list of those problems — a disabled control whose reason is only a tooltip is the most common way
 * an editor strands someone.
 */
@Component({
  selector: 'app-editor-toolbar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  templateUrl: './editor-toolbar.html',
  styleUrl: './editor-toolbar.scss',
})
export class EditorToolbar {
  private readonly graph = inject(GraphStore);
  private readonly files = inject(WorkflowFileService);
  private readonly runs = inject(RunStore);
  private readonly inspector = inject(InspectorStore);

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

  /**
   * What Ctrl+Z would put back, named.
   *
   * "Undo" alone leaves the user guessing how far back one press goes; "Undo Add Scan Directory"
   * does not.
   */
  protected readonly undoTooltip = computed(() =>
    this.canUndo() ? `Undo ${this.graph.lastAction()} (Ctrl+Z)` : 'Nothing to undo',
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
      return 'Not connected to the engine';
    }
    if (this.nodeCount() === 0) {
      return 'Add some nodes first';
    }
    if (this.graph.runnableCount() === 0) {
      return 'Every node is switched off';
    }
    const problems = this.issues();
    if (problems.length > 0) {
      return problems.length === 1
        ? problems[0].message
        : `${problems.length} problems, starting with: ${problems[0].message}`;
    }
    return 'Run the workflow (Ctrl+Enter)';
  });

  protected readonly progressPercent = computed(() => Math.round(this.runs.overallProgress() * 100));

  protected readonly statusText = computed(() => {
    if (this.isRunning()) {
      return `Running — ${this.progressPercent()}%`;
    }
    const finished = this.summary();
    if (finished) {
      const seconds = (finished.durationMillis / 1000).toFixed(2);
      return `${titleCase(finished.outcome)} in ${seconds}s`;
    }
    const problems = this.problems();
    if (problems.length > 0) {
      return `${problems.length} ${problems.length === 1 ? 'problem' : 'problems'}`;
    }
    // "skipped" rather than "off": the count includes nodes nobody switched off, only ones that
    // happen to sit downstream of one that is.
    const skipped = this.nodeCount() - this.graph.runnableCount();
    const base = `${this.nodeCount()} nodes · ${this.edgeCount()} connections`;
    return skipped > 0 ? `${base} · ${skipped} skipped` : base;
  });

  protected readonly statusKind = computed(() => {
    if (this.isRunning()) {
      return 'running';
    }
    const finished = this.summary();
    if (finished) {
      return finished.outcome === 'COMPLETED' ? 'ok' : 'error';
    }
    return this.problems().length > 0 ? 'warn' : 'idle';
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

  protected save(): void {
    this.files.save();
  }

  protected open(): void {
    this.files.open();
  }

  protected clear(): void {
    this.graph.clear();
    this.runs.reset();
  }

  /** The settings panel, from the toolbar: the way back to it once it has been closed. */
  protected toggleInspector(): void {
    this.inspector.toggle();
  }

  protected loadExample(): void {
    this.files.loadExample();
    this.runs.reset();
  }
}

function titleCase(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase();
}
