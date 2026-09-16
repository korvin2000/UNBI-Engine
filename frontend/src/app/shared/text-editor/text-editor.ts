import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { Preset, PresetService } from '../../core/presets/preset.service';
import { FileBrowserService } from '../file-browser/file-browser.service';
import { Icon } from '../icon';
import { TextEditorService } from './text-editor.service';

/**
 * A prompt, edited at the size a prompt actually is.
 *
 * The node stays small and the writing happens here. Three things sit beside the text, and each one
 * is something people were previously doing by hand through the clipboard:
 *
 * - **Load from a file** — a prompt drafted in an editor reaches the graph without a round trip
 *   through the clipboard, and it reads the *engine's* disk, because that is where the workflows
 *   and their prompts live.
 * - **Save as a template** — under a name, into the same preset store the Presets tab lists. There
 *   is no second library to keep in sync: a saved prompt is a preset for the Prompt Template node,
 *   which means it can also simply be dropped onto a canvas as a node.
 * - **Pick a saved template** — searchable, because a library worth having outgrows a dropdown by
 *   the second project.
 *
 * Inserting a template replaces the text rather than appending, and says so before it does it: an
 * editor that silently concatenated two prompts would produce a mess whose cause is invisible.
 */
@Component({
  selector: 'app-text-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  templateUrl: './text-editor.html',
  styleUrl: './text-editor.scss',
})
export class TextEditor {
  private readonly editor = inject(TextEditorService);
  private readonly presets = inject(PresetService);
  private readonly browser = inject(FileBrowserService);

  protected readonly request = this.editor.open;

  protected readonly draft = signal('');
  protected readonly notice = signal('');
  protected readonly failure = signal('');
  /** Open while a template is being chosen; the list is filtered by `templateQuery`. */
  protected readonly picking = signal(false);
  protected readonly templateQuery = signal('');
  /** Armed by the save button: the name field is shown until it is confirmed or dismissed. */
  protected readonly naming = signal(false);
  protected readonly templateName = signal('');

  protected readonly characters = computed(() => this.draft().length);
  protected readonly lines = computed(() => (this.draft() === '' ? 0 : this.draft().split('\n').length));

  /**
   * Roughly how many tokens this is, to the nearest quarter of a thousand.
   *
   * Four characters to a token is the usual rough figure and rough is all it needs to be: the
   * question this answers is "is my system prompt about to eat the context window", not "what will
   * I be billed".
   */
  protected readonly tokenEstimate = computed(() => Math.ceil(this.draft().length / 4));

  /** Saved prompts for this field's library, newest first, filtered by the search box. */
  protected readonly templates = computed<readonly Preset[]>(() => {
    const request = this.request();
    if (!request?.library) {
      return [];
    }
    const needle = this.templateQuery().trim().toLowerCase();
    return this.presets
      .forType(request.library)
      .filter(
        (preset) =>
          !needle ||
          `${preset.name} ${preset.group} ${preset.description}`.toLowerCase().includes(needle),
      );
  });

  /** A viewer rather than an editor: everything that would write is left out. */
  protected readonly readOnly = computed(() => this.request()?.readOnly === true);

  protected readonly hasLibrary = computed(() => !!this.request()?.library && !this.readOnly());
  protected readonly canSaveTemplate = computed(() => this.templateName().trim().length > 0);

  constructor() {
    // Seeded each time the dialog opens, so re-opening it never shows the previous field's text.
    effect(() => {
      const request = this.request();
      if (request) {
        this.draft.set(request.text);
        this.notice.set('');
        this.failure.set('');
        this.picking.set(false);
        this.naming.set(false);
        this.templateQuery.set('');
        this.templateName.set('');
      }
    });
  }

  protected onInput(event: Event): void {
    this.draft.set((event.target as HTMLTextAreaElement).value);
  }

  /** Commits the text. Refused outright in a viewer, so Ctrl+Enter cannot write back a readout. */
  protected save(): void {
    if (this.readOnly()) {
      this.cancel();
      return;
    }
    this.editor.commit(this.draft());
  }

  protected cancel(): void {
    this.editor.cancel();
  }

  /**
   * Escape abandons the edit; Ctrl+Enter commits it.
   *
   * Enter alone cannot commit here — this is a multi-line field, and a dialog that closed on Enter
   * would be unusable for the one thing it exists to do.
   */
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.cancel();
      return;
    }
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      this.save();
    }
  }

  // --- Loading from a file -------------------------------------------------

  protected async loadFile(): Promise<void> {
    const chosen = await this.browser.pick({
      mode: 'file',
      title: 'Choose a text file',
      startAt: '',
      extensions: ['txt', 'md', 'prompt', 'json', 'yaml', 'yml'],
    });
    if (chosen === null) {
      return;
    }
    this.editor.readFile(chosen).subscribe({
      next: (body) => {
        if (body.error) {
          this.failure.set(body.error);
          return;
        }
        this.draft.set(body.text);
        this.failure.set('');
        this.notice.set(`Loaded ${chosen}`);
      },
      error: () => this.failure.set('Could not read that file.'),
    });
  }

  // --- The template library ------------------------------------------------

  protected togglePicker(): void {
    this.picking.update((open) => !open);
    this.naming.set(false);
  }

  protected onTemplateSearch(event: Event): void {
    this.templateQuery.set((event.target as HTMLInputElement).value);
  }

  /** Replaces the text with a saved one. Always replace, never append — and it says so first. */
  protected useTemplate(preset: Preset): void {
    const request = this.request();
    if (!request) {
      return;
    }
    const text = preset.values[request.libraryKey];
    this.draft.set(text == null ? '' : String(text));
    this.picking.set(false);
    this.notice.set(`Loaded template “${preset.name}”`);
  }

  protected startNaming(): void {
    this.naming.set(true);
    this.picking.set(false);
  }

  protected cancelNaming(): void {
    this.naming.set(false);
  }

  protected onNameInput(event: Event): void {
    this.templateName.set((event.target as HTMLInputElement).value);
  }

  /**
   * Saves the text as a preset of the library's node type.
   *
   * One store, not two: what this writes is exactly what the Presets tab lists, what the Prompt
   * Template node loads, and what can be dragged onto a canvas as a configured node. A separate
   * "prompt library" would have been a second place for the same thing to live and a second place
   * for it to be deleted from.
   */
  protected saveTemplate(): void {
    const request = this.request();
    if (!request || !this.canSaveTemplate()) {
      return;
    }
    this.presets.save({
      name: this.templateName().trim(),
      group: 'Prompts',
      description: `Saved from ${request.title}`,
      nodeType: request.library,
      values: { [request.libraryKey]: this.draft() },
    });
    this.naming.set(false);
    this.notice.set(`Saved as “${this.templateName().trim()}”`);
    this.templateName.set('');
  }

  protected textOf(preset: Preset): string {
    const request = this.request();
    const text = request ? preset.values[request.libraryKey] : '';
    return text == null ? '' : String(text);
  }
}
