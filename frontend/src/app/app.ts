import { ChangeDetectionStrategy, Component, HostListener, OnInit, inject } from '@angular/core';
import { CatalogService } from './core/catalog/catalog.service';
import { GraphStore } from './core/graph/graph-store';
import { EngineSocket } from './core/runtime/engine-socket';
import { RunStore } from './core/runtime/run-store';
import { FlowCanvas } from './editor/canvas/flow-canvas';
import { NodePalette } from './editor/palette/node-palette';
import { EditorToolbar } from './editor/toolbar/editor-toolbar';

/**
 * The application shell: toolbar across the top, palette on the left, canvas filling the rest.
 *
 * Also the one place keyboard shortcuts live. Putting them here rather than inside the canvas means
 * undo works while the focus is in the palette search box, which is where it is most often needed.
 */
@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EditorToolbar, NodePalette, FlowCanvas],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  private readonly catalog = inject(CatalogService);
  private readonly socket = inject(EngineSocket);
  private readonly graph = inject(GraphStore);
  private readonly runs = inject(RunStore);

  ngOnInit(): void {
    this.catalog.load();
    this.socket.connect();
  }

  @HostListener('document:keydown', ['$event'])
  protected onKeydown(event: KeyboardEvent): void {
    const modifier = event.ctrlKey || event.metaKey;
    if (!modifier) {
      return;
    }
    // Never steal a shortcut from a field the user is typing in.
    if (isTextEntry(event.target)) {
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
      case 'enter':
        event.preventDefault();
        this.runs.start();
        break;
      default:
        break;
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
