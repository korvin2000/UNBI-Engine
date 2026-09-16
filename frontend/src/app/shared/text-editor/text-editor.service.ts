import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ENGINE_CONFIG } from '../../core/engine.config';

/** What a field asks the editor to open with. */
export interface TextEditRequest {
  /** The field's label, so the dialog says which of a node's four prompts is being edited. */
  readonly title: string;
  readonly text: string;
  /** Alignment carries meaning here — a schema, a template with indented blocks. */
  readonly monospace: boolean;
  readonly placeholder: string;
  /** Preset node type stocking the template list, or '' for a field with no library. */
  readonly library: string;
  /** Which value inside those presets holds the text. */
  readonly libraryKey: string;
  /**
   * Open as a viewer: the text can be read, selected and copied, but not written.
   *
   * There is one place in this editor where a long piece of text is looked at, and a readout that
   * a gateway published — a model's description, a table of providers — is looked at in exactly
   * the same way as a prompt is written in. A second, read-only viewer would be a second thing to
   * keep consistent with this one; the flag drops the three controls that write instead.
   */
  readonly readOnly?: boolean;
}

/**
 * The one full-window text editor, and the field waiting on it.
 *
 * Rendered once by the shell for the same reason the file picker is: a dialog opened from inside a
 * node would live in the canvas's transformed, clipped layer, where it would be scaled by the zoom
 * and cropped by the node it came from.
 *
 * Opening resolves with the finished text, or null if the edit was abandoned — so the widget that
 * asked can commit one value through the ordinary edit command and undo keeps working.
 */
@Injectable({ providedIn: 'root' })
export class TextEditorService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ENGINE_CONFIG);

  private readonly request = signal<TextEditRequest | null>(null);
  private resolve: ((text: string | null) => void) | null = null;

  /** Non-null while the editor is open; the dialog renders from this. */
  readonly open = this.request.asReadonly();

  edit(request: TextEditRequest): Promise<string | null> {
    // Opening while one is already open settles the previous request as abandoned rather than
    // leaving its promise pending for the lifetime of the page.
    this.settle(null);
    this.request.set(request);
    return new Promise<string | null>((resolve) => {
      this.resolve = resolve;
    });
  }

  commit(text: string): void {
    this.settle(text);
  }

  cancel(): void {
    this.settle(null);
  }

  /**
   * One text file off the engine's disk.
   *
   * The engine's, not the browser's — the same reason the picker lists the engine's filesystem.
   * A draft prompt lives next to the workflows that use it, on the machine that runs them.
   */
  readFile(path: string): Observable<{ text: string; error: string | null }> {
    return this.http
      .get<{ text?: string; error?: string | null }>(
        `${this.config.httpBase}/api/fs/text?path=${encodeURIComponent(path)}`,
      )
      .pipe(map((body) => ({ text: String(body?.text ?? ''), error: body?.error ?? null })));
  }

  private settle(text: string | null): void {
    const pending = this.resolve;
    this.resolve = null;
    this.request.set(null);
    pending?.(text);
  }
}
