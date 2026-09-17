import { Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, switchMap, tap, throwError } from 'rxjs';
import { CatalogService } from '../core/catalog/catalog.service';
import { GraphStore } from '../core/graph/graph-store';
import {
  WorkflowDoc,
  WorkflowEdge,
  WorkflowNode,
  clampNodeWidth,
} from '../core/graph/workflow.models';
import { Translator } from '../core/i18n/translator';
import { RunStore } from '../core/runtime/run-store';
import { messageOf } from '../core/settings/settings.service';
import { LibraryEntry, WorkflowLibraryService } from '../core/workflows/workflow-library.service';
import { WorkflowSession } from '../core/workflows/workflow-session';
import { download } from '../shared/download';

/** The on-disk format. Versioned from the first release so a later change has something to migrate from. */
interface WorkflowFile {
  readonly format: 'unbi-workflow';
  readonly version: 1;
  readonly nodes: readonly WorkflowNode[];
  readonly edges: readonly WorkflowEdge[];
}

/**
 * Everything that moves a workflow between the canvas and somewhere else.
 *
 * The library on the engine is where workflows live: Save writes there, Open reads from there, and
 * a favourite is a workflow the engine keeps in the library's favorites/ folder. Files through the
 * browser remain as import and export — the way a workflow leaves for a colleague, or arrives from
 * one — and the built-in example is a third way onto the canvas. All of them go through the
 * session, which is what keeps "unsaved changes" true.
 */
@Injectable({ providedIn: 'root' })
export class WorkflowFileService {
  private readonly graph = inject(GraphStore);
  private readonly catalog = inject(CatalogService);
  private readonly library = inject(WorkflowLibraryService);
  private readonly session = inject(WorkflowSession);
  private readonly runs = inject(RunStore);
  private readonly translator = inject(Translator);

  private readonly failure = signal('');

  /** The last thing that went wrong opening or saving, for whoever is showing it. */
  readonly error = this.failure.asReadonly();

  /**
   * Ctrl+S: write the current workflow over the entry it came from, or ask for a name.
   *
   * @returns the saved entry, or null when a dialog was opened instead
   */
  save(): Observable<LibraryEntry | null> {
    const current = this.session.current();
    if (!current) {
      this.library.show('save');
      return of(null);
    }
    return this.saveAs(current.name, true);
  }

  /** Ctrl+Shift+S: always ask for a name. */
  saveAs(): void;
  saveAs(name: string, overwrite: boolean): Observable<LibraryEntry>;
  saveAs(name?: string, overwrite = false): Observable<LibraryEntry> | void {
    if (name === undefined) {
      this.library.show('save');
      return;
    }
    return this.library.save(name, JSON.parse(serialiseWorkflow(this.graph.doc())), overwrite).pipe(
      tap((saved) => {
        this.session.markSaved(saved);
        this.failure.set('');
      }),
      catchError((error: unknown) => {
        this.failure.set(messageOf(error));
        return throwError(() => error);
      }),
    );
  }

  showLibrary(): void {
    this.library.show('open');
  }

  /** Puts a library entry on the canvas. The caller has already dealt with unsaved changes. */
  open(entry: LibraryEntry): Observable<LibraryEntry> {
    return this.library.read(entry.id, parseWorkflowObject).pipe(
      tap(({ entry: found, document }) => {
        this.session.open(found, document);
        this.runs.reset();
        this.failure.set('');
      }),
      map(({ entry: found }) => found),
      catchError((error: unknown) => {
        this.failure.set(messageOf(error));
        return throwError(() => error);
      }),
    );
  }

  /** Stars or unstars a library entry, keeping the session's idea of it current. */
  setFavorite(entry: LibraryEntry, favorite: boolean): Observable<LibraryEntry> {
    return this.library.setFavorite(entry.id, favorite).pipe(tap((saved) => this.session.updateEntry(saved)));
  }

  rename(entry: LibraryEntry, name: string): Observable<LibraryEntry> {
    return this.library.rename(entry.id, name).pipe(tap((saved) => this.session.updateEntry(saved, entry.id)));
  }

  delete(entry: LibraryEntry): Observable<void> {
    return this.library.delete(entry.id).pipe(tap(() => this.session.forgetEntry(entry.id)));
  }

  /** A blank canvas. The caller has already dealt with unsaved changes. */
  startNew(): void {
    this.session.startNew();
    this.runs.reset();
  }

  /** Downloads the current workflow as a file, for sharing outside the library. */
  exportToFile(): void {
    const blob = new Blob([serialiseWorkflow(this.graph.doc())], { type: 'application/json' });
    const name = this.session.name() || `workflow-${new Date().toISOString().slice(0, 10)}`;
    download(blob, `${name}.unbi.json`);
  }

  /**
   * Opens a workflow file from the browser onto the canvas, untitled.
   *
   * Files go through the browser's file-input path rather than the File System Access API, which
   * is still not available everywhere and would need a fallback anyway.
   *
   * @returns whether a file was chosen and opened
   */
  importFromFile(): Observable<boolean> {
    return pickFile('.json,application/json').pipe(
      switchMap(async (file) => {
        if (!file) {
          return false;
        }
        try {
          this.session.openUntitled(parseWorkflow(await file.text()));
          this.runs.reset();
          this.failure.set('');
          return true;
        } catch (error) {
          this.failure.set(
            this.translator.t('library.importFailed', {
              reason: error instanceof Error ? error.message : String(error),
            }),
          );
          return false;
        }
      }),
    );
  }

  /**
   * The batch file-processing example from the brief: scan a folder, keep the text files, search
   * them, and write a report.
   *
   * Built from the served catalog rather than from hardcoded node ids, so it degrades to whatever
   * subset of nodes the backend actually offers instead of producing a graph full of unknown types.
   */
  loadExample(): void {
    const known = this.catalog.byId();
    const wanted = ['io.scan_directory', 'io.filter_files', 'text.search_in_files', 'report.generate'];
    if (!wanted.every((id) => known.has(id))) {
      return;
    }

    const nodes: WorkflowNode[] = [
      {
        id: 'scan',
        type: 'io.scan_directory',
        position: { x: 60, y: 140 },
        values: { pattern: '*', recursive: true, maxDepth: 8, limit: 2000 },
        title: '',
        collapsed: false,
        disabled: false,
      },
      {
        id: 'filter',
        type: 'io.filter_files',
        position: { x: 380, y: 160 },
        values: { extensions: 'txt, md, java, ts', mode: 'keep', minSize: 0 },
        title: '',
        collapsed: false,
        disabled: false,
      },
      {
        id: 'search',
        type: 'text.search_in_files',
        position: { x: 700, y: 150 },
        values: { query: 'TODO', regex: false, caseSensitive: false },
        title: '',
        collapsed: false,
        disabled: false,
      },
      {
        id: 'report',
        type: 'report.generate',
        position: { x: 1020, y: 130 },
        values: { title: 'TODO Report', format: 'markdown', fileName: 'todo-report.md' },
        title: '',
        collapsed: false,
        disabled: false,
      },
    ];

    const edges: WorkflowEdge[] = [
      { id: 'e1', sourceNode: 'scan', sourcePort: 'files', targetNode: 'filter', targetPort: 'files' },
      { id: 'e2', sourceNode: 'filter', sourcePort: 'files', targetNode: 'search', targetPort: 'files' },
      { id: 'e3', sourceNode: 'search', sourcePort: 'matches', targetNode: 'report', targetPort: 'data' },
    ];

    this.session.openUntitled({ nodes, edges });
    this.runs.reset();
  }
}

/** One file from the user, or null when the picker was dismissed. */
function pickFile(accept: string): Observable<File | null> {
  return new Observable<File | null>((subscriber) => {
    const picker = document.createElement('input');
    picker.type = 'file';
    picker.accept = accept;
    picker.onchange = () => {
      subscriber.next(picker.files?.[0] ?? null);
      subscriber.complete();
    };
    // There is no reliable "cancelled" event; a dismissed picker simply never fires. The
    // subscriber stays pending, which costs nothing and reports nothing false.
    picker.click();
  });
}

/**
 * The document as the file on disk.
 *
 * A function rather than four lines inside `save`, so the round trip that matters — save, reopen,
 * get the same graph back, width and all — can be stated as one test instead of through a download
 * and a file picker. `JSON.stringify` drops an absent `width` on its own, which is what keeps a
 * node that never expressed an opinion about its width from acquiring one in the file.
 */
export function serialiseWorkflow(doc: WorkflowDoc): string {
  const file: WorkflowFile = {
    format: 'unbi-workflow',
    version: 1,
    nodes: doc.nodes,
    edges: doc.edges,
  };
  return JSON.stringify(file, null, 2);
}

/** Validates enough of an opened file that a malformed one fails here rather than mid-render. */
export function parseWorkflow(text: string): WorkflowDoc {
  return parseWorkflowObject(JSON.parse(text));
}

/** The same validation for a document that already arrived parsed — from the library, say. */
export function parseWorkflowObject(parsed: unknown): WorkflowDoc {
  if (typeof parsed !== 'object' || parsed === null) {
    throw new Error('not a workflow file');
  }
  const file = parsed as Partial<WorkflowFile>;
  if (file.format !== 'unbi-workflow') {
    throw new Error('not a UNBI workflow file');
  }
  if (file.version !== 1) {
    throw new Error(`unsupported workflow version ${String(file.version)}`);
  }
  if (!Array.isArray(file.nodes) || !Array.isArray(file.edges)) {
    throw new Error('the file is missing its nodes or edges');
  }
  return {
    nodes: file.nodes.map((node, index) => {
      if (!node || typeof node.id !== 'string' || typeof node.type !== 'string') {
        throw new Error(`node ${index} is missing an id or a type`);
      }
      return {
        id: node.id,
        type: node.type,
        position: { x: Number(node.position?.x ?? 0), y: Number(node.position?.y ?? 0) },
        values: node.values ?? {},
        title: typeof node.title === 'string' ? node.title : '',
        collapsed: node.collapsed === true,
        disabled: node.disabled === true,
        // Whitelisted like every other field: a width left out here would be dropped silently on
        // reopen, which is the one failure mode a saved layout must not have.
        width: parseWidth(node.width),
      };
    }),
    edges: file.edges.map((edge, index) => {
      if (!edge || typeof edge.id !== 'string') {
        throw new Error(`connection ${index} is missing an id`);
      }
      return {
        id: edge.id,
        sourceNode: String(edge.sourceNode),
        sourcePort: String(edge.sourcePort),
        targetNode: String(edge.targetNode),
        targetPort: String(edge.targetPort),
      };
    }),
  };
}

/**
 * A saved width, or nothing.
 *
 * Absent, null, a string, `NaN` and `Infinity` all mean "this file says nothing about the width" —
 * which is a node at the default rather than a node 0px wide. Anything else is clamped, because a
 * hand-edited 4000 would produce a node that cannot be dragged back into view.
 */
function parseWidth(raw: unknown): number | undefined {
  const width = typeof raw === 'number' ? raw : Number.NaN;
  return Number.isFinite(width) ? clampNodeWidth(width) : undefined;
}
