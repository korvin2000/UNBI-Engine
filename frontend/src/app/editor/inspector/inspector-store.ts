import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { GraphStore } from '../../core/graph/graph-store';
import { Point } from '../../core/graph/workflow.models';

/** Where the panel's geometry is kept between sessions. Versioned, so a later shape can be told. */
const STORAGE_KEY = 'unbi.inspector.v1';

/** Narrower than this the label column stops being readable; wider it starves the canvas. */
export const MIN_WIDTH = 280;
export const MAX_WIDTH = 600;
export const DEFAULT_WIDTH = 320;

/** Where a freshly undocked panel appears, before anyone has dragged it. */
const DEFAULT_FLOATING: Point = { x: 96, y: 72 };

/**
 * Which node the inspector is showing, and where the panel is.
 *
 * A service rather than component state for two reasons. The panel is mounted by the shell but
 * opened from three places — a node's Advanced strip, the node context menu, the toolbar — and none
 * of them can reach into a sibling component. And its geometry outlives the panel: closing it and
 * reopening it must not lose the width somebody dragged.
 *
 * The follow rule is the interesting part. The panel tracks the canvas selection while it is not
 * pinned, but an *empty* selection does not clear it: clicking the canvas background to dismiss a
 * menu, or dragging a selection box that catches nothing, would otherwise empty the panel the user
 * was reading. Only deleting the node it shows closes it, because then there is nothing to show.
 */
@Injectable({ providedIn: 'root' })
export class InspectorStore {
  private readonly graph = inject(GraphStore);

  private readonly shownId = signal<string | null>(null);
  private readonly isOpen = signal(false);
  private readonly isDocked = signal(true);
  private readonly isPinned = signal(false);
  private readonly panelWidth = signal(DEFAULT_WIDTH);
  private readonly floatingAt = signal<Point>(DEFAULT_FLOATING);

  /** Section open-state, remembered per node *type* — keyed `<type>::<section>`. */
  private readonly sections = signal<ReadonlyMap<string, boolean>>(new Map());

  readonly nodeId = this.shownId.asReadonly();
  readonly open = this.isOpen.asReadonly();
  readonly docked = this.isDocked.asReadonly();
  readonly pinned = this.isPinned.asReadonly();
  readonly width = this.panelWidth.asReadonly();
  readonly floating = this.floatingAt.asReadonly();

  /** How many nodes are selected, for the quiet line a multi-selection shows. */
  readonly selectionCount = computed(() => this.graph.selection().size);

  /**
   * Whether opening the panel would show anything, which is what the toolbar's button reflects.
   *
   * The panel draws a node or nothing at all, so a button that could be pressed with nothing to
   * show would light up over an empty screen — see {@link toggle}.
   */
  readonly canOpen = computed(() => this.shownId() !== null || this.graph.selection().size > 0);

  /** The width the docked panel takes from the canvas, or 0 when it takes none. */
  readonly dockedWidth = computed(() =>
    this.isOpen() && this.isDocked() ? this.panelWidth() : 0,
  );

  constructor() {
    this.restore();

    // Follow the selection, unless pinned. One node selected is an unambiguous request to inspect
    // it; anything else leaves the panel on whatever it was showing.
    effect(() => {
      const selection = this.graph.selection();
      const pinned = this.isPinned();
      if (pinned || selection.size !== 1) {
        return;
      }
      const [only] = selection;
      untracked(() => this.shownId.set(only));
    });

    // The node it was showing is gone. Keeping an id that resolves to nothing would leave an empty
    // panel taking a fifth of the window.
    effect(() => {
      const id = this.shownId();
      const present = this.graph.doc().nodes.some((node) => node.id === id);
      if (id !== null && !present) {
        untracked(() => {
          this.shownId.set(null);
          this.isOpen.set(false);
          this.isPinned.set(false);
        });
      }
    });

    // One effect for persistence, so there is exactly one place that writes and one shape written.
    effect(() => {
      const geometry = {
        docked: this.isDocked(),
        width: this.panelWidth(),
        floating: this.floatingAt(),
      };
      untracked(() => persist(geometry));
    });
  }

  /** Shows a node, opening the panel if it was closed. The way in from every entry point. */
  show(nodeId: string): void {
    this.shownId.set(nodeId);
    this.isOpen.set(true);
  }

  close(): void {
    this.isOpen.set(false);
  }

  /**
   * The toolbar's button, and the way back out.
   *
   * Opening with nothing to show falls back to the selection, so pressing it with a node selected
   * does the obvious thing rather than opening an empty panel. With nothing selected either it
   * stays closed: the panel renders a node or renders nothing, so `open()` becoming true there
   * would light the toolbar button and announce a panel that is not on screen. The button is
   * disabled in that state — see {@link canOpen} — so the press cannot silently do nothing.
   */
  toggle(): void {
    if (this.isOpen()) {
      this.isOpen.set(false);
      return;
    }
    if (this.shownId() === null) {
      const [first] = this.graph.selection();
      if (first === undefined) {
        return;
      }
      this.shownId.set(first);
    }
    this.isOpen.set(true);
  }

  /** Pinning freezes the panel on this node, and makes it ignore Escape. */
  togglePinned(): void {
    this.isPinned.update((pinned) => !pinned);
  }

  toggleDocked(): void {
    this.isDocked.update((docked) => !docked);
  }

  setWidth(width: number): void {
    this.panelWidth.set(clampWidth(width));
  }

  setFloating(position: Point): void {
    this.floatingAt.set({ x: Math.round(position.x), y: Math.round(position.y) });
  }

  /**
   * Whether a section is open, per node type.
   *
   * Per type rather than per instance: someone who opens Settings on one LLM Request node means it
   * for LLM Request nodes, and re-collapsing it on each of the four on the canvas is busywork. Not
   * persisted — it is a fact about the last few minutes, not about the workflow.
   */
  isSectionOpen(nodeType: string, section: string, fallback: boolean): boolean {
    return this.sections().get(sectionKey(nodeType, section)) ?? fallback;
  }

  toggleSection(nodeType: string, section: string, fallback: boolean): void {
    const open = this.isSectionOpen(nodeType, section, fallback);
    this.sections.update((current) =>
      new Map(current).set(sectionKey(nodeType, section), !open),
    );
  }

  /**
   * Reads the saved geometry, treating anything unexpected as absent.
   *
   * Storage is shared with every other version of this app that ran on this machine, and it can
   * throw outright in a private window. A panel 40,000 pixels wide because a number arrived as a
   * string is not a failure anybody could diagnose from the symptom, so each field is checked.
   */
  private restore(): void {
    const saved = read();
    if (!saved) {
      return;
    }
    if (typeof saved['docked'] === 'boolean') {
      this.isDocked.set(saved['docked']);
    }
    if (isFiniteNumber(saved['width'])) {
      this.panelWidth.set(clampWidth(saved['width']));
    }
    const floating = saved['floating'];
    if (floating && typeof floating === 'object') {
      const point = floating as Record<string, unknown>;
      if (isFiniteNumber(point['x']) && isFiniteNumber(point['y'])) {
        // Clamped to the workspace on the way onto the screen, not here: the window may well be a
        // different size than it was, and only the panel knows how big the workspace is.
        this.floatingAt.set({ x: point['x'], y: point['y'] });
      }
    }
  }
}

function sectionKey(nodeType: string, section: string): string {
  return `${nodeType}::${section}`;
}

export function clampWidth(width: number): number {
  return Math.round(Math.min(Math.max(width, MIN_WIDTH), MAX_WIDTH));
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function read(): Record<string, unknown> | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed: unknown = JSON.parse(raw);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    // Unreadable, blocked, or not JSON. The defaults are a perfectly good panel.
    return null;
  }
}

function persist(geometry: { docked: boolean; width: number; floating: Point }): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(geometry));
  } catch {
    // Storage disabled or full: the panel still works, it just forgets.
  }
}
