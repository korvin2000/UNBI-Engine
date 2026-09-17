import { ChangeDetectionStrategy, Component, HostListener, OnInit, inject } from '@angular/core';
import { CatalogService } from './core/catalog/catalog.service';
import { OptionCatalogService } from './core/catalog/option-catalog.service';
import { PresetService } from './core/presets/preset.service';
import * as commands from './core/graph/commands';
import { GraphStore } from './core/graph/graph-store';
import { EngineSocket } from './core/runtime/engine-socket';
import { RunStore } from './core/runtime/run-store';
import { SettingsService } from './core/settings/settings.service';
import { WorkflowLibraryService } from './core/workflows/workflow-library.service';
import { WorkflowSession } from './core/workflows/workflow-session';
import { CanvasSelection } from './editor/canvas/canvas-selection';
import { FlowCanvas } from './editor/canvas/flow-canvas';
import { NodeInspector } from './editor/inspector/node-inspector';
import { NodePalette } from './editor/palette/node-palette';
import { EditorToolbar } from './editor/toolbar/editor-toolbar';
import { WorkflowFileService } from './editor/workflow-file.service';
import { FileBrowser } from './shared/file-browser/file-browser';
import { FileBrowserService } from './shared/file-browser/file-browser.service';
import { PresetDialog } from './shared/preset-dialog/preset-dialog';
import { ProfileDialog } from './shared/profile-dialog/profile-dialog';
import { ProfileService } from './core/profiles/profile.service';
import { CredentialService } from './core/credentials/credential.service';
import { SettingsDialog } from './shared/settings-dialog/settings-dialog';
import { CredentialDialog } from './shared/credential-dialog/credential-dialog';
import { TextEditor } from './shared/text-editor/text-editor';
import { TextEditorService } from './shared/text-editor/text-editor.service';
import { WorkflowLibrary } from './shared/workflow-library/workflow-library';

/**
 * The application shell: toolbar across the top, palette on the left, canvas filling the rest.
 *
 * Also the one place keyboard shortcuts live. Putting them here rather than inside the canvas means
 * undo works while the focus is in the palette search box, which is where it is most often needed.
 */
@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    EditorToolbar,
    NodePalette,
    FlowCanvas,
    NodeInspector,
    FileBrowser,
    PresetDialog,
    ProfileDialog,
    CredentialDialog,
    TextEditor,
    SettingsDialog,
    WorkflowLibrary,
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  private readonly catalog = inject(CatalogService);
  private readonly socket = inject(EngineSocket);
  private readonly graph = inject(GraphStore);
  private readonly selection = inject(CanvasSelection);
  private readonly runs = inject(RunStore);
  private readonly browser = inject(FileBrowserService);
  private readonly options = inject(OptionCatalogService);
  private readonly presets = inject(PresetService);
  private readonly textEditor = inject(TextEditorService);
  private readonly profiles = inject(ProfileService);
  private readonly credentials = inject(CredentialService);
  private readonly settings = inject(SettingsService);
  private readonly library = inject(WorkflowLibraryService);
  private readonly session = inject(WorkflowSession);
  private readonly files = inject(WorkflowFileService);

  ngOnInit(): void {
    this.catalog.load();
    // None of these is worth a loading state: a dropdown with no server-side options still renders
    // its static ones, an empty Presets tab is a true statement about a fresh engine, and the
    // settings arrive with the language — English until they do, which is what English is for.
    this.options.load();
    this.presets.load();
    this.credentials.load();
    this.settings.load();
    this.library.load();
    this.socket.connect();
  }

  /** The browser's own "leave this page?" — the one safety net a reload cannot get past. */
  @HostListener('window:beforeunload', ['$event'])
  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.session.dirty() && this.graph.nodeCount() > 0) {
      event.preventDefault();
    }
  }

  @HostListener('document:keydown', ['$event'])
  protected onKeydown(event: KeyboardEvent): void {
    // Never steal a keystroke from the modal dialogs — each has its own Escape and Enter, and
    // Delete there must not reach the canvas behind it.
    if (
      this.browser.open() !== null ||
      this.presets.pending() !== null ||
      this.profiles.editing() !== null ||
      this.credentials.editing() !== null ||
      this.textEditor.open() !== null ||
      this.settings.open() !== null ||
      this.library.open() !== null
    ) {
      return;
    }

    const modifier = event.ctrlKey || event.metaKey;
    const key = event.key.toLowerCase();

    // Save and Open are taken even from inside a text field: the browser's own Ctrl+S ("save this
    // page") and Ctrl+O would otherwise fire from wherever the cursor happens to be.
    if (modifier && key === 's') {
      event.preventDefault();
      if (event.shiftKey) {
        this.files.saveAs();
      } else {
        this.files.save().subscribe({ error: () => undefined });
      }
      return;
    }
    if (modifier && key === 'o') {
      event.preventDefault();
      this.files.showLibrary();
      return;
    }

    if (isTextEntry(event.target)) {
      return;
    }

    // Inside the inspector these keys belong to the panel, not to the canvas. Delete on a focused
    // toggle would otherwise delete the very node being edited, and Ctrl+A would select every node
    // rather than the text in the filter box — both from a keystroke aimed at the panel.
    const inPanel = isInsideInspector(event.target);

    if (!modifier) {
      // Delete and Backspace both remove the selection: which one people reach for is a habit, and
      // an editor that honours only one of them feels broken to half its users.
      if (!inPanel && (event.key === 'Delete' || event.key === 'Backspace')) {
        this.deleteSelection(event);
      }
      return;
    }

    if (inPanel && (key === 'a' || key === 'd')) {
      return;
    }

    switch (key) {
      case 'z':
        event.preventDefault();
        if (event.shiftKey) {
          this.graph.redo();
        } else {
          this.graph.undo();
        }
        break;
      case 'y':
        event.preventDefault();
        this.graph.redo();
        break;
      case 'd':
        event.preventDefault();
        this.duplicateSelection();
        break;
      case 'a':
        event.preventDefault();
        // Through CanvasSelection so the canvas draws what the store now holds: a keystroke is not
        // a gesture the flow library saw.
        this.selection.select(this.graph.doc().nodes.map((node) => node.id));
        break;
      case 'enter':
        event.preventDefault();
        this.runs.start();
        break;
      default:
        break;
    }
  }

  private deleteSelection(event: KeyboardEvent): void {
    const nodes = [...this.graph.selection()];
    const edges = [...this.graph.edgeSelection()];
    if (nodes.length > 0 || edges.length > 0) {
      event.preventDefault();
      this.graph.dispatch(commands.removeSelection(nodes, edges));
    }
  }

  private duplicateSelection(): void {
    const selected = [...this.graph.selection()];
    if (selected.length > 0) {
      this.graph.dispatch(commands.duplicateNodes(selected, () => crypto.randomUUID()));
    }
  }
}

/** Whether a keystroke happened inside the inspector panel, which owns its own keys. */
function isInsideInspector(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && target.closest('app-node-inspector') !== null;
}

function isTextEntry(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  return (
    target.isContentEditable ||
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement
  );
}
