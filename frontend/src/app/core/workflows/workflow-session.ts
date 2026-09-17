import { Injectable, computed, inject, signal } from '@angular/core';
import { GraphStore } from '../graph/graph-store';
import { EMPTY_DOC, WorkflowDoc } from '../graph/workflow.models';
import { LibraryEntry } from './workflow-library.service';

/**
 * Which workflow is on the canvas, and whether it has changed since it was last saved.
 *
 * The graph store owns the document and knows nothing about files; this is the small thing that
 * sits beside it and remembers where the document came from. "Dirty" is a reference comparison:
 * every command produces a new document object and undo hands the old one back, so the canvas is
 * clean exactly when its document is the object that was saved — no counters, nothing to reset.
 *
 * Every way a document reaches the canvas goes through here — opened from the library, imported
 * from a file, the built-in example, a blank canvas — so the saved snapshot can never fall out of
 * step with what is shown.
 */
@Injectable({ providedIn: 'root' })
export class WorkflowSession {
  private readonly graph = inject(GraphStore);

  private readonly saved = signal<WorkflowDoc>(EMPTY_DOC);
  private readonly entry = signal<LibraryEntry | null>(null);

  /** The library entry this document was opened from or saved to, or null for an untitled one. */
  readonly current = this.entry.asReadonly();

  readonly name = computed(() => this.entry()?.name ?? '');

  readonly dirty = computed(() => this.graph.doc() !== this.saved());

  /** A workflow from the library: named, and clean until the first edit. */
  open(entry: LibraryEntry, document: WorkflowDoc): void {
    this.graph.load(document);
    this.saved.set(document);
    this.entry.set(entry);
  }

  /**
   * A document that is not in the library — an imported file, the example: untitled, and clean.
   *
   * Clean rather than dirty because nothing has been edited; what "Save" then does is ask for a
   * name, which is the right question for a document that has none.
   */
  openUntitled(document: WorkflowDoc): void {
    this.graph.load(document);
    this.saved.set(document);
    this.entry.set(null);
  }

  startNew(): void {
    this.graph.clear();
    this.saved.set(EMPTY_DOC);
    this.entry.set(null);
  }

  /** What is on the canvas right now has just been written to the library under this entry. */
  markSaved(entry: LibraryEntry): void {
    this.saved.set(this.graph.doc());
    this.entry.set(entry);
  }

  /**
   * The entry changed on the engine — renamed, starred — without the document changing.
   *
   * @param previousId the id it had before, when a rename gave it a new one
   */
  updateEntry(entry: LibraryEntry, previousId: string = entry.id): void {
    if (this.entry()?.id === previousId) {
      this.entry.set(entry);
    }
  }

  /** The entry is gone from the library; the document stays, untitled again. */
  forgetEntry(id: string): void {
    if (this.entry()?.id === id) {
      this.entry.set(null);
    }
  }
}
