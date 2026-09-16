import { ChangeDetectionStrategy, Component, HostListener, OnInit, inject } from '@angular/core';
import { CatalogService } from './core/catalog/catalog.service';
import { OptionCatalogService } from './core/catalog/option-catalog.service';
import { PresetService } from './core/presets/preset.service';
import * as commands from './core/graph/commands';
import { GraphStore } from './core/graph/graph-store';
import { EngineSocket } from './core/runtime/engine-socket';
import { RunStore } from './core/runtime/run-store';
import { CanvasSelection } from './editor/canvas/canvas-selection';
import { FlowCanvas } from './editor/canvas/flow-canvas';
import { NodeInspector } from './editor/inspector/node-inspector';
import { NodePalette } from './editor/palette/node-palette';
import { EditorToolbar } from './editor/toolbar/editor-toolbar';
import { FileBrowser } from './shared/file-browser/file-browser';
import { FileBrowserService } from './shared/file-browser/file-browser.service';
import { PresetDialog } from './shared/preset-dialog/preset-dialog';
import { ProfileDialog } from './shared/profile-dialog/profile-dialog';
import { ProfileService } from './core/profiles/profile.service';
import { CredentialService } from './core/credentials/credential.service';
import { TextEditor } from './shared/text-editor/text-editor';
import { TextEditorService } from './shared/text-editor/text-editor.service';

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
    TextEditor,
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

  ngOnInit(): void {
    this.catalog.load();
    // Neither is worth a loading state: a dropdown with no server-side options still renders its
    // static ones, and an empty Presets tab is a true statement about a fresh engine.
    this.options.load();
    this.presets.load();
    this.credentials.load();
    this.socket.connect();
  }

  @HostListener('document:keydown', ['$event'])
  protected onKeydown(event: KeyboardEvent): void {
    // Never steal a keystroke from a field the user is typing in, or from the modal picker — the
    // dialog has its own Escape and Enter, and Delete there must not reach the canvas behind it.
    if (
      isTextEntry(event.target) ||
      this.browser.open() !== null ||
      this.presets.pending() !== null ||
      this.profiles.editing() !== null ||
      this.textEditor.open() !== null
    ) {
      return;
    }

    // Inside the inspector these keys belong to the panel, not to the canvas. Delete on a focused
    // toggle would otherwise delete the very node being edited, and Ctrl+A would select every node
    // rather than the text in the filter box — both from a keystroke aimed at the panel.
    const inPanel = isInsideInspector(event.target);

    const modifier = event.ctrlKey || event.metaKey;
    if (!modifier) {
      // Delete and Backspace both remove the selection: which one people reach for is a habit, and
      // an editor that honours only one of them feels broken to half its users.
      if (!inPanel && (event.key === 'Delete' || event.key === 'Backspace')) {
        this.deleteSelection(event);
      }
      return;
    }

    const key = event.key.toLowerCase();
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
