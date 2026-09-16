import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FFlowModule } from '@foblex/flow';
import { CatalogService } from '../../core/catalog/catalog.service';
import { CategoryGroup, NodeSpec, groupIntoCategories } from '../../core/catalog/catalog.models';
import { withoutReadouts } from '../../core/catalog/node-rows';
import * as commands from '../../core/graph/commands';
import { GraphStore } from '../../core/graph/graph-store';
import { newNode } from '../../core/graph/workflow.models';
import { Preset, PresetService } from '../../core/presets/preset.service';
import { PRESET_PREFIX } from '../canvas/flow-canvas';
import { Icon } from '../../shared/icon';

/**
 * The node palette.
 *
 * Categories collapse, search filters across name and description, and a node reaches the canvas
 * either by dragging it (Foblex `fExternalItem`) or by double-clicking. Both matter: dragging is
 * how you place something precisely, double-click is how you build a graph quickly.
 *
 * Entirely driven by the served catalog, so a new backend node pack populates this with no change
 * here whatsoever.
 */
@Component({
  selector: 'app-node-palette',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FFlowModule, Icon],
  templateUrl: './node-palette.html',
  styleUrl: './node-palette.scss',
})
export class NodePalette {
  private readonly catalog = inject(CatalogService);
  private readonly graph = inject(GraphStore);
  private readonly presets = inject(PresetService);

  protected readonly tab = signal<'nodes' | 'presets'>('nodes');
  protected readonly query = signal('');
  protected readonly collapsed = signal<ReadonlySet<string>>(new Set());

  protected readonly state = this.catalog.state;
  protected readonly loadError = this.catalog.error;

  /**
   * Search re-groups the filtered result rather than filtering the pre-grouped tree, so a category
   * that ends up empty disappears instead of showing a header with nothing beneath it.
   */
  protected readonly groups = computed<readonly CategoryGroup[]>(() => {
    const needle = this.query().trim();
    if (!needle) {
      return this.catalog.categories();
    }
    return groupIntoCategories(this.catalog.search(needle));
  });

  protected readonly matchCount = computed(() =>
    this.groups().reduce(
      (total, group) => total + group.sections.reduce((sum, section) => sum + section.nodes.length, 0),
      0,
    ),
  );

  protected readonly isSearching = computed(() => this.query().trim().length > 0);

  protected readonly presetPrefix = PRESET_PREFIX;
  protected readonly presetError = this.presets.error;

  /**
   * The preset whose delete button is armed, if any.
   *
   * Deleting one removes a file from the engine's disk and no undo reaches it, so the button asks
   * once. Held here rather than in the row so that arming a second row disarms the first — two
   * rows both showing "Delete" is how the wrong one gets pressed.
   */
  protected readonly confirming = signal<string | null>(null);

  /**
   * Saved configurations, searched the same way nodes are and grouped by their own group.
   *
   * Ungrouped presets come last under a neutral heading rather than being hidden: a preset saved in
   * a hurry, with no group, is still one somebody wants to find again.
   */
  protected readonly presetGroups = computed(() => {
    const needle = this.query().trim().toLowerCase();
    const matching = this.presets.all().filter(
      (preset) =>
        !needle ||
        `${preset.name} ${preset.group} ${preset.description} ${preset.label}`
          .toLowerCase()
          .includes(needle),
    );

    const byGroup = new Map<string, Preset[]>();
    for (const preset of matching) {
      const key = preset.group || 'Ungrouped';
      const bucket = byGroup.get(key) ?? [];
      bucket.push(preset);
      byGroup.set(key, bucket);
    }
    return [...byGroup.entries()]
      .sort(([a], [b]) => (a === 'Ungrouped' ? 1 : b === 'Ungrouped' ? -1 : a.localeCompare(b)))
      .map(([name, entries]) => ({ name, entries }));
  });

  protected readonly presetCount = computed(() =>
    this.presetGroups().reduce((total, group) => total + group.entries.length, 0),
  );

  /** Every saved preset, not just the ones matching a search — the tab badge counts what exists. */
  protected readonly presetTotal = computed(() => this.presets.all().length);

  protected showTab(tab: 'nodes' | 'presets'): void {
    this.tab.set(tab);
    this.confirming.set(null);
    if (tab === 'presets') {
      // Re-read on every visit: a preset may have been added by another editor, or by dropping a
      // file into the engine's data directory.
      this.presets.load();
    }
  }

  /**
   * Adds a preset's node, already configured, with the preset's name on it.
   *
   * Readouts are dropped on the way in, exactly as they are on the way out: a preset saved by an
   * older build can still be carrying what a gateway answered last week, and a node that arrives
   * already claiming facts nobody fetched is worse than an empty one.
   */
  protected addPreset(preset: Preset): void {
    const spec = this.catalog.byId().get(preset.nodeType);
    if (!spec) {
      return;
    }
    this.graph.dispatch(
      commands.addNode(
        newNode(
          preset.nodeType,
          this.defaultPosition(),
          withoutReadouts(spec, preset.values),
          preset.name,
        ),
        preset.name,
      ),
    );
  }

  protected askDelete(preset: Preset): void {
    this.confirming.set(preset.id);
  }

  protected cancelDelete(): void {
    this.confirming.set(null);
  }

  protected deletePreset(preset: Preset): void {
    this.confirming.set(null);
    this.presets.delete(preset.id);
  }

  /** The tooltip for a preset row: what it is for, then what it configures. */
  protected describe(preset: Preset): string {
    const what = this.isOrphan(preset)
      ? `${preset.nodeType} — this engine no longer serves that node type`
      : preset.label;
    return preset.description ? `${preset.description}\n${what}` : what;
  }

  /** True for a preset whose node type this engine no longer serves. */
  protected isOrphan(preset: Preset): boolean {
    return !this.catalog.byId().has(preset.nodeType);
  }

  protected isCollapsed(category: string): boolean {
    // Searching expands everything: hiding a match behind a collapsed header makes search look broken.
    return !this.isSearching() && this.collapsed().has(category);
  }

  protected toggle(category: string): void {
    this.collapsed.update((current) => {
      const next = new Set(current);
      if (!next.delete(category)) {
        next.add(category);
      }
      return next;
    });
  }

  protected onSearch(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
    // A row that scrolls out from under an armed button is a row that gets deleted by accident.
    this.confirming.set(null);
  }

  protected clearSearch(): void {
    this.query.set('');
  }

  protected retry(): void {
    this.catalog.load();
  }

  /**
   * Double-click drops a node near the middle of the current graph.
   *
   * Placing it at a fixed origin would stack every node on top of the last one; offsetting from the
   * existing bounding box keeps a fast-built graph legible without a layout engine.
   */
  protected addAtDefaultPosition(spec: NodeSpec): void {
    const position = this.defaultPosition();

    this.graph.dispatch(
      commands.addNode(newNode(spec.id, position), spec.label),
    );
  }

  private defaultPosition(): { x: number; y: number } {
    const nodes = this.graph.doc().nodes;
    const rightmost = nodes.reduce((max, node) => Math.max(max, node.position.x), 0);
    return nodes.length === 0
      ? { x: 120, y: 120 }
      : { x: rightmost + 300, y: 120 + (nodes.length % 4) * 60 };
  }
}
