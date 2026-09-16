import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FFlowModule } from '@foblex/flow';
import { DropdownOption, WidgetSpec } from '../core/catalog/catalog.models';
import { OptionCatalogService } from '../core/catalog/option-catalog.service';
import { CredentialService } from '../core/credentials/credential.service';
import { ProfileService } from '../core/profiles/profile.service';
import { FileBrowserService } from '../shared/file-browser/file-browser.service';
import { TextEditorService } from '../shared/text-editor/text-editor.service';
import { Icon } from '../shared/icon';

/** One row of a key/value editor, with a stable identity so typing does not re-key the list. */
interface Pair {
  readonly id: number;
  key: string;
  value: string;
}

/** A list entry: an option, plus what a credential or profile list adds to one. */
interface Choice extends DropdownOption {
  /** Where a credential came from, shown beside its name as a small tag. */
  readonly note?: string;
  /** A line under the name — a profile's description. */
  readonly detail?: string;
  /** Whether the list offers to forget it. */
  readonly removable?: boolean;
}

/**
 * Renders one input's editor, chosen by the widget kind the backend declared.
 *
 * One component with a `@switch` rather than a component per kind plus a registry. The kinds are all
 * small and they share the disabled/label behaviour; splitting them would mean ten files and an
 * indirection to look at before understanding what a node draws. The first kind that grows real
 * complexity of its own earns a component then.
 */
@Component({
  selector: 'app-node-widget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // FFlowModule supplies fDragBlocker: without it, dragging a slider also drags the node.
  imports: [Icon, FFlowModule, NgTemplateOutlet],
  templateUrl: './node-widget.html',
  styleUrl: './node-widget.scss',
  host: {
    '[class.is-disabled]': 'disabled()',
    '(document:pointerdown)': 'onDocumentPointerDown($event)',
  },
})
export class NodeWidget {
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly browser = inject(FileBrowserService);
  private readonly options = inject(OptionCatalogService);
  private readonly editor = inject(TextEditorService);
  private readonly profiles = inject(ProfileService);
  private readonly credentials = inject(CredentialService);

  readonly spec = input.required<WidgetSpec>();
  readonly value = input<unknown>(null);
  /** True when an edge drives this input, in which case the widget shows the takeover instead. */
  readonly disabled = input(false);

  /** The field's label, used as the title of the full-window editor this may open. */
  readonly label = input('');

  /**
   * Choices a node action discovered for this input, merged in ahead of the static ones.
   *
   * Passed in rather than fetched because they belong to one node *instance*: which models are
   * available depends on which endpoint is wired into this particular node, and a service keyed by
   * widget kind could not tell two Model nodes apart.
   */
  readonly discovered = input<readonly DropdownOption[]>([]);

  /**
   * What an automatic discovery for this input has to say when the list is empty — "asking the
   * endpoint…", or why it could not — so an empty list is never a silent one.
   */
  readonly discoveryNote = input('');

  /**
   * Option values an upstream node says are available, or null when nothing is wired in.
   *
   * Null and empty mean different things, and the difference is load-bearing: nothing wired in is
   * "nobody knows", which is no reason to hide a choice, while an empty list is "the model declares
   * none of these", which is every reason to.
   */
  readonly allowed = input<ReadonlySet<string> | null>(null);

  readonly valueChange = output<unknown>();

  /** The list was just opened: the moment an automatic discovery for this input should run. */
  readonly opened = output<void>();

  protected readonly listOpen = signal(false);

  protected readonly text = computed(() => (this.value() == null ? '' : String(this.value())));
  protected readonly numeric = computed(() => {
    const raw = Number(this.value());
    return Number.isFinite(raw) ? raw : 0;
  });
  protected readonly checked = computed(() => this.value() === true || this.value() === 'true');

  /**
   * What a number field shows: blank when an optional field holds nothing.
   *
   * The distinction this preserves is the whole reason `optional` exists — a temperature of zero and
   * no temperature at all are different requests, and one of the gateways this engine talks to
   * honours only the second.
   */
  protected readonly numberText = computed(() => {
    const raw = this.value();
    if (raw === null || raw === undefined || raw === '') {
      return '';
    }
    return String(this.numeric());
  });

  /** A typable dropdown, where what is typed is the value and also the filter. */
  protected readonly comboBox = computed(() => {
    const spec = this.spec();
    return spec.kind === 'dropdown' && spec.allowCustom;
  });

  protected readonly placeholder = computed(() => {
    const spec = this.spec();
    return spec.kind === 'dropdown' && spec.allowCustom ? 'Pick from the list, or type a name' : '';
  });

  /**
   * Everything this control may offer: what the descriptor declared, what the engine serves under
   * the named catalog, what a probe discovered for this node, the profiles under a schema, the
   * credential names the engine knows — minus anything the wired upstream node says is unavailable.
   *
   * The narrowing is what makes "only the response formats this model supports" happen on its own.
   * It is a rule the descriptor carries and this evaluates; nothing here knows what a model is.
   */
  protected readonly choices = computed<readonly Choice[]>(() => {
    const spec = this.spec();
    switch (spec.kind) {
      case 'multiselect':
        return spec.options;
      case 'profile':
        return this.profiles.state(spec.schema)().profiles.map((profile) => ({
          value: profile.id,
          label: profile.name,
          detail: profile.description || undefined,
        }));
      case 'credential':
        return this.credentials.names().map((credential) => ({
          value: credential.name,
          label: credential.name,
          note: credential.source,
          removable: credential.removable,
        }));
      case 'dropdown':
        break;
      default:
        return [];
    }

    const merged: Choice[] = [...spec.options];
    const known = new Set(merged.map((option) => option.value));
    const add = (option: DropdownOption) => {
      if (!known.has(option.value)) {
        known.add(option.value);
        merged.push(option);
      }
    };
    if (spec.optionsKey) {
      this.options.optionsFor(spec.optionsKey).forEach(add);
    }
    this.discovered().forEach(add);

    const allowed = this.allowed();
    if (!spec.narrowing || allowed === null) {
      return merged;
    }
    const always = new Set(spec.narrowing.always);
    return merged.filter((option) => always.has(option.value) || allowed.has(option.value));
  });

  /** What is typed into the open list's filter, if it is showing one. */
  protected readonly listQuery = signal('');

  /**
   * A list long enough to need searching, rather than a fixed threshold per node.
   *
   * One gateway answered a model listing with 1,165 entries. Scrolling that inside a node on a
   * zoomable canvas is not choosing, and rendering it is not free either — so past this many the
   * list grows a filter and stops drawing everything at once.
   */
  private static readonly SEARCHABLE_FROM = 12;

  /** Past this many rendered rows the list is not being read, it is being scrolled past. */
  private static readonly MAX_RENDERED = 150;

  protected readonly searchable = computed(() => this.choices().length >= NodeWidget.SEARCHABLE_FROM);

  /** Every choice matching the filter, before the cap. */
  private readonly matching = computed<readonly Choice[]>(() => {
    const needle = this.listQuery().trim().toLowerCase();
    if (!needle) {
      return this.choices();
    }
    const hits = this.choices().filter((option) =>
      `${option.label} ${option.value}`.toLowerCase().includes(needle),
    );
    // A combo box filters by what is typed, and what is typed is usually the start of a name —
    // so exact and prefix matches come first, and a full match is never buried under fuzzier ones.
    return this.comboBox()
      ? [...hits].sort((a, b) => rank(a, needle) - rank(b, needle))
      : hits;
  });

  /** The options actually drawn: what matches the filter, capped. */
  protected readonly visibleChoices = computed<readonly Choice[]>(() =>
    this.matching().slice(0, NodeWidget.MAX_RENDERED),
  );

  /** How many matches were left undrawn, so a capped list says so rather than lying by omission. */
  protected readonly hiddenChoices = computed(() =>
    Math.max(0, this.matching().length - this.visibleChoices().length),
  );

  /** The dropdown shows a label, not the value the graph stores. */
  protected readonly selectedLabel = computed(() => {
    const current = this.text();
    return this.choices().find((option) => option.value === current)?.label ?? current;
  });

  /** What an empty list says about itself, by kind. */
  protected readonly emptyLabel = computed(() => {
    switch (this.spec().kind) {
      case 'profile':
        return 'No profiles yet — create one with the button beside this field.';
      case 'credential':
        return 'The engine knows no credentials yet.';
      default:
        return 'Nothing to choose from.';
    }
  });

  /** Where the slider thumb sits, as a percentage, for the filled-track gradient. */
  protected readonly sliderPercent = computed(() => {
    const spec = this.spec();
    if (spec.kind !== 'slider') {
      return 0;
    }
    const span = spec.max - spec.min;
    if (span <= 0) {
      return 0;
    }
    return ((this.numeric() - spec.min) / span) * 100;
  });

  // --- Multi-select -------------------------------------------------------

  protected readonly selected = computed<ReadonlySet<string>>(() => {
    const raw = this.value();
    if (Array.isArray(raw)) {
      return new Set(raw.map((entry) => String(entry)));
    }
    // A comma-separated string is what a preset written by hand is likely to contain.
    return new Set(
      typeof raw === 'string' ? raw.split(',').map((part) => part.trim()).filter(Boolean) : [],
    );
  });

  protected toggleOption(value: string): void {
    if (this.disabled()) {
      return;
    }
    const chosen = new Set(this.selected());
    if (!chosen.delete(value)) {
      chosen.add(value);
    }
    // Emitted in the declared option order so the stored value does not depend on click order —
    // otherwise two identical configurations would compare as different documents.
    this.valueChange.emit(this.choices().map((option) => option.value).filter((option) => chosen.has(option)));
  }

  protected isSelected(value: string): boolean {
    return this.selected().has(value);
  }

  // --- Key/value ----------------------------------------------------------

  private nextPairId = 0;
  private readonly editedPairs = signal<readonly Pair[] | null>(null);
  /** What this widget last emitted, so its own echo can be told from someone else's change. */
  private lastEmitted: string | null = null;

  constructor() {
    // An undo, or a preset dropped onto the canvas, replaces the value from outside. The edit
    // buffer has to go with it, or the widget would keep showing rows the document no longer has.
    effect(() => {
      const incoming = serialise(this.value());
      if (incoming !== this.lastEmitted) {
        this.lastEmitted = null;
        this.editedPairs.set(null);
      }
    });
    // A profile list is fetched once per schema, the first time a widget needs it.
    effect(() => {
      const spec = this.spec();
      if (spec.kind === 'profile') {
        this.profiles.load(spec.schema);
      }
    });
  }

  /**
   * The rows to draw: the ones being edited if there are any, else the stored map.
   *
   * Edited rows are held separately because a map cannot represent a half-typed row — an empty key,
   * or two rows briefly sharing one. Rebuilding from the value on every keystroke would delete the
   * row the user is in the middle of naming.
   */
  protected readonly pairs = computed<readonly Pair[]>(() => {
    const edited = this.editedPairs();
    if (edited) {
      return edited;
    }
    const raw = this.value();
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
      return [];
    }
    return Object.entries(raw as Record<string, unknown>).map(([key, value]) => ({
      id: this.nextPairId++,
      key,
      value: value == null ? '' : String(value),
    }));
  });

  protected addPair(): void {
    this.editedPairs.set([...this.pairs(), { id: this.nextPairId++, key: '', value: '' }]);
  }

  protected removePair(id: number): void {
    this.commitPairs(this.pairs().filter((pair) => pair.id !== id));
  }

  protected editPairKey(id: number, event: Event): void {
    const key = (event.target as HTMLInputElement).value;
    this.commitPairs(this.pairs().map((pair) => (pair.id === id ? { ...pair, key } : pair)));
  }

  protected editPairValue(id: number, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.commitPairs(this.pairs().map((pair) => (pair.id === id ? { ...pair, value } : pair)));
  }

  /** Keeps the edited rows, and stores the ones that are complete enough to mean anything. */
  private commitPairs(rows: readonly Pair[]): void {
    this.editedPairs.set(rows);
    const map: Record<string, string> = {};
    for (const pair of rows) {
      const key = pair.key.trim();
      if (key) {
        map[key] = pair.value;
      }
    }
    this.lastEmitted = serialise(map);
    this.valueChange.emit(map);
  }

  // --- Text and numbers ---------------------------------------------------

  protected onText(event: Event): void {
    this.valueChange.emit((event.target as HTMLInputElement | HTMLTextAreaElement).value);
  }

  protected onNumber(event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    if (raw === '') {
      const spec = this.spec();
      // For an optional field an empty box is the answer "do not send this"; for a required one it
      // is a user mid-edit, and emitting zero would fight their typing.
      if (spec.kind === 'number' && spec.optional) {
        this.valueChange.emit(null);
      }
      return;
    }
    const parsed = Number(raw);
    if (Number.isFinite(parsed)) {
      this.valueChange.emit(parsed);
    }
  }

  /**
   * Clamps on the way out of the field.
   *
   * Clamping per keystroke makes a number box impossible to type in — typing `1` on the way to
   * `100` in a 10..999 field would snap to 10 and eat the rest. Leaving the field is the moment the
   * user has finished saying what they meant.
   */
  protected onNumberCommit(event: Event): void {
    const spec = this.spec();
    if (spec.kind !== 'number' && spec.kind !== 'slider') {
      return;
    }
    const input = event.target as HTMLInputElement;
    if (input.value === '' && spec.kind === 'number' && spec.optional) {
      this.valueChange.emit(null);
      return;
    }
    const parsed = Number(input.value);
    const settled = Number.isFinite(parsed) ? clamp(parsed, spec.min, spec.max) : spec.min;
    if (settled !== this.numeric() || input.value === '') {
      this.valueChange.emit(settled);
    }
    input.value = String(settled);
  }

  protected onToggle(event: Event): void {
    this.valueChange.emit((event.target as HTMLInputElement).checked);
  }

  protected step(delta: number): void {
    const spec = this.spec();
    if (spec.kind !== 'number') {
      return;
    }
    const next = clamp(this.numeric() + delta * (spec.step || 1), spec.min, spec.max);
    this.valueChange.emit(round(next, spec.step || 1));
  }

  // --- Dropdown, combo box, profile and credential lists ------------------

  protected toggleList(): void {
    if (this.disabled()) {
      return;
    }
    if (this.listOpen()) {
      this.closeList();
    } else {
      this.openList();
    }
  }

  protected openList(): void {
    if (this.disabled() || this.listOpen()) {
      return;
    }
    // A combo box filters by its own text; a plain list opens unfiltered.
    this.listQuery.set(this.comboBox() ? this.text() : '');
    this.listOpen.set(true);
    this.opened.emit();
  }

  protected closeList(): void {
    this.listOpen.set(false);
    this.listQuery.set('');
    this.cancelKey();
  }

  /**
   * A press anywhere outside this widget closes its list.
   *
   * A document listener rather than a backdrop element: the widget lives inside the transformed
   * canvas, where a `position: fixed` backdrop is fixed to the transform rather than the viewport
   * and covers only part of the screen — so a click on the next node landed on that node and left
   * this list open on top of it.
   */
  protected onDocumentPointerDown(event: PointerEvent): void {
    if (this.listOpen() && !this.host.nativeElement.contains(event.target as Node)) {
      this.closeList();
    }
  }

  protected onListSearch(event: Event): void {
    this.listQuery.set((event.target as HTMLInputElement).value);
  }

  /** Typing into a combo box is both the value and the filter. */
  protected onComboInput(event: Event): void {
    const typed = (event.target as HTMLInputElement).value;
    this.valueChange.emit(typed);
    this.listQuery.set(typed);
    if (!this.listOpen()) {
      this.listOpen.set(true);
      this.opened.emit();
    }
  }

  protected pickOption(value: string): void {
    this.closeList();
    this.valueChange.emit(value);
  }

  /** Up and down move through the options; Escape closes; Enter in a combo box takes the top match. */
  protected onSelectKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      this.closeList();
      return;
    }
    if (event.key === 'Enter' && this.comboBox() && this.listOpen()) {
      const top = this.visibleChoices()[0];
      if (top && top.value !== this.text()) {
        event.preventDefault();
        this.pickOption(top.value);
      } else {
        this.closeList();
      }
      return;
    }
    const step = event.key === 'ArrowDown' ? 1 : event.key === 'ArrowUp' ? -1 : 0;
    if (step === 0) {
      return;
    }
    event.preventDefault();
    const options = this.comboBox() ? this.visibleChoices() : this.choices();
    if (options.length === 0) {
      return;
    }
    const at = options.findIndex((option) => option.value === this.text());
    const next = clamp((at < 0 ? 0 : at) + step, 0, options.length - 1);
    this.valueChange.emit(options[next].value);
  }

  // --- Profiles -------------------------------------------------------------

  /** Opens the profile dialog; whatever profile it finishes on becomes this field's value. */
  protected async manageProfiles(): Promise<void> {
    const spec = this.spec();
    if (spec.kind !== 'profile' || this.disabled()) {
      return;
    }
    this.closeList();
    const chosen = await this.profiles.edit({ schema: spec.schema, current: this.text() });
    if (chosen !== null && chosen !== this.text()) {
      this.valueChange.emit(chosen);
    }
  }

  // --- Credentials ---------------------------------------------------------

  protected readonly addingKey = signal(false);
  protected readonly keyName = signal('');
  protected readonly keySecret = signal('');
  protected readonly credentialError = this.credentials.error;
  protected readonly canSaveKey = computed(
    () => /^[A-Za-z0-9][A-Za-z0-9._-]{0,60}$/.test(this.keyName().trim()) && this.keySecret().trim().length > 0,
  );

  protected startKey(): void {
    this.keyName.set(this.text());
    this.keySecret.set('');
    this.addingKey.set(true);
  }

  protected cancelKey(): void {
    this.addingKey.set(false);
    this.keySecret.set('');
  }

  /** Sends the key once; the secret is cleared here the moment the request has left. */
  protected saveCredential(event: Event): void {
    event.preventDefault();
    if (!this.canSaveKey()) {
      return;
    }
    const name = this.keyName().trim();
    const secret = this.keySecret();
    this.keySecret.set('');
    this.credentials.add(name, secret).subscribe((accepted) => {
      if (accepted) {
        this.addingKey.set(false);
        this.pickOption(name);
      }
    });
  }

  protected removeCredential(name: string, event: Event): void {
    event.stopPropagation();
    this.credentials.remove(name);
  }

  // --- The full-window editor ---------------------------------------------

  /**
   * Opens the field in a dialog the size of a document.
   *
   * The node keeps its four rows. Widening the node until a prompt fits would trade one unreadable
   * thing for an unreadable graph, and a canvas is a place to see the shape of a pipeline rather
   * than a place to write prose.
   */
  protected async expand(): Promise<void> {
    const spec = this.spec();
    if (spec.kind !== 'text' || this.disabled()) {
      return;
    }
    const edited = await this.editor.edit({
      title: this.label() || 'Edit text',
      text: this.text(),
      monospace: spec.monospace,
      placeholder: spec.placeholder,
      library: spec.library,
      libraryKey: spec.libraryKey,
    });
    if (edited !== null) {
      this.valueChange.emit(edited);
    }
  }

  // --- A list of files ----------------------------------------------------

  /** The chosen paths. A list, because every separator worth using is legal in a filename. */
  protected readonly files = computed<readonly string[]>(() => {
    const raw = this.value();
    if (Array.isArray(raw)) {
      return raw.map((entry) => String(entry)).filter((entry) => entry.trim().length > 0);
    }
    return typeof raw === 'string' && raw.trim() ? [raw.trim()] : [];
  });

  /** The last path segment, which is the part anyone actually reads. */
  protected fileName(path: string): string {
    const cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return cut < 0 ? path : path.slice(cut + 1);
  }

  protected async addFile(): Promise<void> {
    const spec = this.spec();
    if (spec.kind !== 'filelist' || this.disabled()) {
      return;
    }
    const chosen = await this.browser.pick({
      mode: 'file',
      title: 'Add a file',
      // Opens where the last one came from, because files chosen together usually live together.
      startAt: this.files().at(-1) ?? '',
      extensions: spec.extensions,
    });
    if (chosen === null || this.files().includes(chosen)) {
      return;
    }
    this.valueChange.emit([...this.files(), chosen]);
  }

  protected removeFile(path: string): void {
    if (!this.disabled()) {
      this.valueChange.emit(this.files().filter((entry) => entry !== path));
    }
  }

  protected clearFiles(): void {
    if (!this.disabled()) {
      this.valueChange.emit([]);
    }
  }

  // --- Path pickers -------------------------------------------------------

  /**
   * Opens the browser-side dialog for a path.
   *
   * A path field is not a browser upload: the engine is what opens it, so what the user needs to
   * see is the engine's disk. `FileBrowserService` is the seam.
   */
  protected async browse(mode: 'file' | 'directory'): Promise<void> {
    const spec = this.spec();
    const chosen = await this.browser.pick({
      mode,
      title: mode === 'directory' ? 'Choose a folder' : 'Choose a file',
      startAt: this.text(),
      extensions: spec.kind === 'file' ? spec.extensions : [],
    });
    if (chosen !== null) {
      this.valueChange.emit(chosen);
    }
  }
}

/** Lower is better: an exact match, then a prefix, then anything containing the text. */
function rank(option: DropdownOption, needle: string): number {
  const value = option.value.toLowerCase();
  const label = option.label.toLowerCase();
  if (value === needle || label === needle) {
    return 0;
  }
  if (value.startsWith(needle) || label.startsWith(needle)) {
    return 1;
  }
  return 2;
}

/** A comparable form of a widget value, for telling an echo from a genuine change. */
function serialise(value: unknown): string {
  try {
    return JSON.stringify(value ?? null);
  } catch {
    // A value that cannot be serialised is one this widget did not produce, so treating it as a
    // change is the right answer anyway.
    return String(value);
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

/** Keeps 0.1 + 0.2 from becoming 0.30000000000000004 in a visible input. */
function round(value: number, step: number): number {
  const decimals = (String(step).split('.')[1] ?? '').length;
  return Number(value.toFixed(decimals));
}
