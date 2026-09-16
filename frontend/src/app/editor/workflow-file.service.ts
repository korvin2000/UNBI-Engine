import { Injectable, inject } from '@angular/core';
import { CatalogService } from '../core/catalog/catalog.service';
import { GraphStore } from '../core/graph/graph-store';
import { WorkflowDoc, WorkflowEdge, WorkflowNode } from '../core/graph/workflow.models';

/** The on-disk format. Versioned from the first release so a later change has something to migrate from. */
interface WorkflowFile {
  readonly format: 'unbi-workflow';
  readonly version: 1;
  readonly nodes: readonly WorkflowNode[];
  readonly edges: readonly WorkflowEdge[];
}

/**
 * Saving, opening, and the built-in example.
 *
 * Files go through the browser's download and file-input paths rather than the File System Access
 * API, which is still not available everywhere and would need a fallback anyway.
 */
@Injectable({ providedIn: 'root' })
export class WorkflowFileService {
  private readonly graph = inject(GraphStore);
  private readonly catalog = inject(CatalogService);

  save(): void {
    const doc = this.graph.doc();
    const file: WorkflowFile = {
      format: 'unbi-workflow',
      version: 1,
      nodes: doc.nodes,
      edges: doc.edges,
    };
    const blob = new Blob([JSON.stringify(file, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `workflow-${new Date().toISOString().slice(0, 10)}.unbi.json`;
    anchor.click();
    // Revoking immediately can cancel the download in some browsers; one turn later is enough.
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }

  open(): void {
    const picker = document.createElement('input');
    picker.type = 'file';
    picker.accept = '.json,application/json';
    picker.onchange = async () => {
      const file = picker.files?.[0];
      if (!file) {
        return;
      }
      try {
        this.graph.load(parseWorkflow(await file.text()));
      } catch (error) {
        // A bad file is a user mistake, not a crash. Reported where they are looking.
        console.error('Could not open that workflow', error);
        window.alert(
          error instanceof Error ? `Could not open that file: ${error.message}` : 'Could not open that file',
        );
      }
    };
    picker.click();
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

    this.graph.load({ nodes, edges });
  }
}

/** Validates enough of an opened file that a malformed one fails here rather than mid-render. */
export function parseWorkflow(text: string): WorkflowDoc {
  const parsed: unknown = JSON.parse(text);
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
