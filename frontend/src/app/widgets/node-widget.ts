import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FFlowModule } from '@foblex/flow';
import { DropdownOption, WidgetSpec } from '../core/catalog/catalog.models';
import { OptionCatalogService } from '../core/catalog/option-catalog.service';
import { CredentialService } from '../core/credentials/credential.service';
import { ProfileService } from '../core/profiles/profile.service';
import { FileBrowserService } from '../shared/file-browser/file-browser.service';
import { MinuteClock } from '../shared/minute-clock';
import { TextEditorService } from '../shared/text-editor/text-editor.service';
import { Icon } from '../shared/icon';

/** One row of a key/value editor, with a stable identity so typing does not re-key the list. */
interface Pair {
  readonly id: number;
  key: string;
  value: string;
}

/**
 * Where a dropdown's list is drawn.
 *
 * `inline` is the panel this widget has always used: absolutely positioned under the trigger,
 * which is right inside a node — the canvas is transformed, so a `position: fixed` panel there
 * would be fixed to the transform rather than to the viewport and land in the wrong place at any
 * zoom other than 100%.
 *
 * `fixed` is for a host that scrolls: inside an `overflow: auto` column an absolute panel is
 * clipped by the column, so the panel is measured off the trigger and pinned to the viewport
 * instead. The two are per host rather than automatic because only the host knows which it is.
 */
export type ListPlacement = 'inline' | 'fixed';

/** Where a viewport-pinned panel goes, measured off its trigger when the list opens. */
interface FixedPanel {
  readonly left: number;
  readonly width: number;
  /** One of the two is null: a panel hangs from the trigger's bottom, or sits on its top. */
  readonly top: number | null;
  readonly bottom: number | null;
  readonly maxHeight: number;
}

/**
 * How long "Copied" stays on screen.
 *
 * Long enough to be read, short enough that it is gone before the next thing is clicked — the
 * message is an acknowledgement, not a status.
 */
const COPIED_FOR = 1400;

/**
 * An ISO-8601 instant, which is the one value shape this widget reformats.
 *
 * Deliberately strict: it has to be a full date *and* time with a zone, because "2026" and
 * "1.5" are numbers a gateway publishes and rendering either of them as "56 years ago" would be
 * worse than leaving them alone. The seconds and the fraction are optional — `Instant.toString()`
 * drops them when they are zero — and a space instead of the `T` is accepted because that is how
 * a hand-written value tends to arrive.
 */
const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:?\d{2})$/;

/** How many lines of a block are shown before it is clamped and offered in the dialog instead. */
const BLOCK_LINE_CLAMP = 8;

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
    // Escape belongs to the innermost open thing. Handled once here, on the widget rather than on
    // each control inside it, and swallowed only when it actually closed a list — so a panel
    // around this widget sees the key when there was nothing here to close, and does not when
    // there was.
    '(keydown.escape)': 'onEscape($event)',
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
   * The input's hint, drawn beside the label — but only by a readout.
   *
   * Every other kind gets its hint from its host, which has a label row of its own to hang it on. A
   * readout has no such row: it *is* the label row, so the question mark has to arrive here or it
   * would be the one kind of setting whose explanation is unreachable.
   */
  readonly hint = input('');

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

  /** How this widget's list escapes its host — see {@link ListPlacement}. */
  readonly listPlacement = input<ListPlacement>('inline');

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

  /**
   * What an unsupported control says it is.
   *
   * The declared name, so the placeholder in the node names the missing control rather than merely
   * admitting to one — that name is what someone would search the node pack for.
   */
  protected readonly declaredKind = computed(() => {
    const spec = this.spec();
    return spec.kind === 'unsupported' ? spec.declared || 'unnamed' : spec.kind;
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
    // A widget destroyed with its list open — an undo that removed the node — would otherwise
    // leave the viewport listeners behind, and a "Copied" timer behind that.
    this.destroyRef.onDestroy(() => {
      this.unwatchViewport();
      if (this.copyTimer !== null) {
        clearTimeout(this.copyTimer);
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
    if (this.listPlacement() === 'fixed') {
      this.placeFixedPanel();
      this.watchViewport();
    }
    this.listOpen.set(true);
    this.opened.emit();
  }

  protected closeList(): void {
    this.listOpen.set(false);
    this.listQuery.set('');
    this.fixedPanel.set(null);
    this.unwatchViewport();
    this.cancelKey();
  }

  /**
   * Escape, from anywhere inside this widget.
   *
   * Reported as handled only when a list was actually open, which is what lets an enclosing panel
   * use the same key for itself without the two fighting over it.
   */
  protected onEscape(event: Event): void {
    if (this.listOpen()) {
      this.closeList();
      event.stopPropagation();
    }
  }

  // --- A list pinned to the viewport ---------------------------------------

  private readonly listTrigger = viewChild<ElementRef<HTMLElement>>('listTrigger');
  private readonly destroyRef = inject(DestroyRef);

  /** Where a `fixed` panel is drawn, or null when this list is inline or closed. */
  protected readonly fixedPanel = signal<FixedPanel | null>(null);

  /** The gap between the trigger and its panel, matching the inline panel's own offset. */
  private static readonly PANEL_GAP = 3;

  /** A panel shorter than this is not worth opening downward; it flips instead. */
  private static readonly MIN_PANEL_HEIGHT = 140;

  /** Never let a panel touch the window edge. */
  private static readonly VIEWPORT_MARGIN = 8;

  /**
   * Measures the trigger and pins the panel to that rectangle.
   *
   * Taken once, when the list opens, rather than followed: while a list is open the trigger cannot
   * move except by the host scrolling or the window resizing, and both of those close the list.
   */
  private placeFixedPanel(): void {
    const trigger = this.listTrigger()?.nativeElement;
    if (!trigger) {
      this.fixedPanel.set(null);
      return;
    }
    const rect = trigger.getBoundingClientRect();
    const gap = NodeWidget.PANEL_GAP;
    const margin = NodeWidget.VIEWPORT_MARGIN;
    const below = window.innerHeight - rect.bottom - gap - margin;
    const above = rect.top - gap - margin;
    // Upward only when downward genuinely does not fit and upward is roomier, so a list near the
    // bottom of a scrolling panel opens over the trigger instead of off the screen.
    const flip = below < NodeWidget.MIN_PANEL_HEIGHT && above > below;
    this.fixedPanel.set({
      left: rect.left,
      width: rect.width,
      top: flip ? null : Math.round(rect.bottom + gap),
      bottom: flip ? Math.round(window.innerHeight - rect.top + gap) : null,
      maxHeight: Math.max(NodeWidget.MIN_PANEL_HEIGHT, Math.round(flip ? above : below)),
    });
  }

  private viewportWatcher: (() => void) | null = null;

  /**
   * Closes a pinned panel when the page moves under it.
   *
   * The scroll listener is registered in the capture phase because a scroll event does not bubble:
   * the container that actually scrolls is an ancestor — the inspector's body — and capture is the
   * only phase in which a listener on `document` hears it. Capture also delivers scrolls from
   * *descendants*, and this widget has one that scrolls: the panel's own option list, which in
   * fixed placement is handed the panel's height and overflows it. So the target is checked — a
   * scroll inside this widget is someone reading the options, not the page moving underneath them,
   * and closing the list on the first wheel tick would make a long list unusable.
   *
   * `document` and `window` are not nodes this host can contain, so a page scroll still closes.
   */
  private watchViewport(): void {
    if (this.viewportWatcher) {
      return;
    }
    const close = (event?: Event) => {
      const target = event?.target;
      if (target instanceof Node && this.host.nativeElement.contains(target)) {
        return;
      }
      this.closeList();
    };
    document.addEventListener('scroll', close, true);
    window.addEventListener('resize', close);
    this.viewportWatcher = () => {
      document.removeEventListener('scroll', close, true);
      window.removeEventListener('resize', close);
    };
  }

  private unwatchViewport(): void {
    this.viewportWatcher?.();
    this.viewportWatcher = null;
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

  /** Up and down move through the options; Enter in a combo box takes the top match. Escape is
   * handled once on the host, so that closing a list and closing the panel around it cannot both
   * happen on one press. */
  protected onSelectKeydown(event: KeyboardEvent): void {
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

  // --- A readout ----------------------------------------------------------
  //
  // Not a control: what a fetch or a run produced, shown in the node. Three shapes, because the
  // things a node reports are three shapes — a fact ("128,000 tok"), a paragraph (a model's own
  // description) and a set (which modalities it takes) — and forcing all three into one line makes
  // two of them unreadable.

  private readonly clock = inject(MinuteClock);

  protected readonly readoutStyle = computed<'line' | 'block' | 'chips'>(() => {
    const spec = this.spec();
    return spec.kind === 'display' ? spec.style : 'line';
  });

  protected readonly readoutUnit = computed(() => {
    const spec = this.spec();
    return spec.kind === 'display' ? spec.unit : '';
  });

  /**
   * Which of three things this readout currently is.
   *
   * The distinction between the first two is the whole reason a readout does not fall back to its
   * declared default. Nothing stored means nobody has pressed the bulb yet — the row is not empty,
   * it is unasked. An empty string means the question *was* asked and this gateway publishes no
   * answer, which is a fact about the gateway and worth saying out loud.
   */
  protected readonly readoutState = computed<'unfetched' | 'empty' | 'value'>(() => {
    const raw = this.value();
    if (raw === undefined) {
      return 'unfetched';
    }
    if (raw === null || raw === '' || (Array.isArray(raw) && raw.length === 0)) {
      return 'empty';
    }
    return 'value';
  });

  /** True or false when the value is a boolean, else null — a fact with a ✓ or a ✗ rather than a word. */
  protected readonly readoutFlag = computed<boolean | null>(() => {
    const raw = this.value();
    return typeof raw === 'boolean' ? raw : null;
  });

  /** The value as text: already formatted by whoever produced it, so it is never reformatted here. */
  protected readonly readoutText = computed(() => {
    const raw = this.value();
    if (raw === null || raw === undefined || typeof raw === 'boolean') {
      return '';
    }
    return Array.isArray(raw) ? raw.map(String).join(', ') : String(raw);
  });

  /** A set, as chips. A comma-separated string is what a hand-written value would hold. */
  protected readonly readoutChips = computed<readonly string[]>(() => {
    const raw = this.value();
    if (Array.isArray(raw)) {
      return raw.map(String).filter((entry) => entry.trim().length > 0);
    }
    return typeof raw === 'string'
      ? raw.split(',').map((part) => part.trim()).filter(Boolean)
      : [];
  });

  /** How many lines a block holds, which is what decides whether it needs a way out. */
  protected readonly readoutLineCount = computed(() => this.readoutText().split('\n').length);

  /**
   * Whether a block is longer than the room it is given.
   *
   * Counted rather than measured: measuring means reading `scrollHeight` after every render and
   * again whenever the node is resized, and the answer would still only decide whether to draw a
   * fade. Eight lines, or the characters that wrap to about that many in a default-width node —
   * either way the block is clamped and the dialog is offered, which is the same conclusion a
   * measurement would reach for everything except a paragraph that lands within a line of the
   * limit. There the cost is one "Show all" button that was not strictly needed.
   */
  protected readonly readoutClamped = computed(() => {
    if (this.readoutStyle() !== 'block' || this.readoutState() !== 'value') {
      return false;
    }
    return this.readoutLineCount() > BLOCK_LINE_CLAMP || this.readoutText().length > 240;
  });

  /**
   * A timestamp, said the way people say it.
   *
   * "6 minutes ago" is the form the question takes — a fetched fact is only as good as its age —
   * and the exact instant stays in the tooltip for when that is the question instead. Recomputed
   * every minute by reading the shared tick, so the node does not sit there claiming a fact is
   * fresh an hour later.
   */
  protected readonly readoutMoment = computed<{ relative: string; absolute: string } | null>(() => {
    if (this.readoutStyle() !== 'line' || this.readoutState() !== 'value') {
      return null;
    }
    const raw = this.readoutText();
    if (!ISO_INSTANT.test(raw)) {
      return null;
    }
    const at = Date.parse(raw);
    if (!Number.isFinite(at)) {
      return null;
    }
    this.clock.tick();
    return { relative: sinceText(at, Date.now()), absolute: new Date(at).toLocaleString() };
  });

  /** What the line shows: the relative time when it is one, else the value as it arrived. */
  protected readonly readoutLine = computed(
    () => this.readoutMoment()?.relative ?? this.readoutText(),
  );

  /**
   * The tooltip on a value: the exact instant, or the untruncated text.
   *
   * Null rather than an empty string for a boolean, whose value is a glyph: an empty `title` is
   * still a tooltip as far as the browser is concerned, and it shows up as a blank box.
   */
  protected readonly readoutTitle = computed<string | null>(() => {
    const moment = this.readoutMoment();
    return moment ? moment.absolute : this.readoutText() || null;
  });

  /** Shown for a beat after the copy button was pressed, because a clipboard is invisible. */
  protected readonly copied = signal(false);
  /** True once the browser has refused the clipboard, which changes what the button offers. */
  protected readonly copyBlocked = signal(false);
  private copyTimer: ReturnType<typeof setTimeout> | null = null;

  /** What the copy button says it will do, which depends on whether it can still do it. */
  protected readonly copyTitle = computed(() => {
    if (this.copied()) {
      return 'Copied';
    }
    return this.copyBlocked()
      ? 'The browser refused the clipboard — the value is selected, so press Ctrl+C'
      : 'Copy this value';
  });

  /**
   * Copies the value, not the label.
   *
   * A model id, a canonical slug or a price is a thing people paste somewhere else, and selecting
   * text inside a node on a transformed canvas is fiddly enough that "select it by hand" is not an
   * answer.
   *
   * The clipboard is not always ours to write: it is absent over plain HTTP on a remote host and
   * refused outright inside an embedded browser view. Doing nothing at all there would make the
   * button look broken, so the fallback selects the value instead and the tooltip says to press
   * Ctrl+C — which is the one thing the browser cannot take away.
   */
  protected copyValue(): void {
    const text = this.readoutText();
    if (!text) {
      return;
    }
    const clipboard = navigator.clipboard;
    if (!clipboard) {
      this.offerManualCopy();
      return;
    }
    void clipboard
      .writeText(text)
      .then(() => this.flagCopied())
      .catch(() => this.offerManualCopy());
  }

  /** The value cell, so a refused clipboard can at least hand over a selection. */
  private readonly factValue = viewChild<ElementRef<HTMLElement>>('factValue');

  private offerManualCopy(): void {
    const cell = this.factValue()?.nativeElement;
    const selection = window.getSelection();
    if (cell && selection) {
      selection.removeAllRanges();
      const range = document.createRange();
      range.selectNodeContents(cell);
      selection.addRange(range);
    }
    this.copyBlocked.set(true);
  }

  private flagCopied(): void {
    this.copied.set(true);
    this.copyBlocked.set(false);
    if (this.copyTimer !== null) {
      clearTimeout(this.copyTimer);
    }
    this.copyTimer = setTimeout(() => {
      this.copied.set(false);
      this.copyTimer = null;
    }, COPIED_FOR);
  }

  /**
   * Opens a clamped block in the full-window editor, read-only.
   *
   * The same dialog a prompt is written in, which is the point: there is one place in this editor
   * where a long piece of text is looked at, and a second viewer for the read-only case would be a
   * second thing to keep consistent. It cannot be edited, because a readout is not ours to write.
   */
  protected async showAll(): Promise<void> {
    await this.editor.edit({
      title: this.label() || 'Value',
      text: this.readoutText(),
      monospace: false,
      placeholder: '',
      library: '',
      libraryKey: '',
      readOnly: true,
    });
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

/**
 * How long ago something happened, said the way a person would say it.
 *
 * Coarse on purpose, and it gets coarser with age: the question a fetched fact raises is "is this
 * still true", and the honest answers to that are "moments ago", "this morning" and "last week".
 * Counting seconds would imply a precision the fetch itself does not have, and a timestamp older
 * than a month is better read as a date than as "43 days ago".
 *
 * A time in the future is a clock difference between this machine and the engine's, not a fact
 * about the value, so it reads as "just now" rather than as a negative age.
 */
function sinceText(at: number, now: number): string {
  const seconds = Math.round((now - at) / 1000);
  if (seconds < 45) {
    return 'just now';
  }
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) {
    return minutes <= 1 ? 'a minute ago' : `${minutes} minutes ago`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return hours === 1 ? 'an hour ago' : `${hours} hours ago`;
  }
  const days = Math.round(hours / 24);
  if (days === 1) {
    return 'yesterday';
  }
  if (days < 30) {
    return `${days} days ago`;
  }
  return new Date(at).toLocaleDateString();
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

