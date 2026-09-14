import { ChangeDetectionStrategy, Component, HostListener, OnInit, inject } from '@angular/core';
import { CatalogService } from './core/catalog/catalog.service';
import * as commands from './core/graph/commands';
import { GraphStore } from './core/graph/graph-store';
import { EngineSocket } from './core/runtime/engine-socket';
import { RunStore } from './core/runtime/run-store';
import { FlowCanvas } from './editor/canvas/flow-canvas';
import { NodePalette } from './editor/palette/node-palette';
import { EditorToolbar } from './editor/toolbar/editor-toolbar';
import { FileBrowser } from './shared/file-browser/file-browser';
import { FileBrowserService } from './shared/file-browser/file-browser.service';

/**
 * The application shell: toolbar across the top, palette on the left, canvas filling the rest.
 *
 * Also the one place keyboard shortcuts live. Putting them here rather than inside the canvas means
 * undo works while the focus is in the palette search box, which is where it is most often needed.
 */
@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EditorToolbar, NodePalette, FlowCanvas, FileBrowser],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  private readonly catalog = inject(CatalogService);
  private readonly socket = inject(EngineSocket);
  private readonly graph = inject(GraphStore);
  private readonly runs = inject(RunStore);
  private readonly browser = inject(FileBrowserService);

  ngOnInit(): void {
    this.catalog.load();
    this.socket.connect();
  }

  @HostListener('document:keydown', ['$event'])
  protected onKeydown(event: KeyboardEvent): void {
    // Never steal a keystroke from a field the user is typing in, or from the modal picker — the
    // dialog has its own Escape and Enter, and Delete there must not reach the canvas behind it.
    if (isTextEntry(event.target) || this.browser.open() !== null) {
      return;
    }

    const modifier = event.ctrlKey || event.metaKey;
    if (!modifier) {
      // Delete and Backspace both remove the selection: which one people reach for is a habit, and
      // an editor that honours only one of them feels broken to half its users.
      if (event.key === 'Delete' || event.key === 'Backspace') {
        this.deleteSelection(event);
      }
      return;
    }

    switch (event.key.toLowerCase()) {
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
        this.graph.select(this.graph.doc().nodes.map((node) => node.id));
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
