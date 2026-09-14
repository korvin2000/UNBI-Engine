import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FFlowModule } from '@foblex/flow';
import { CatalogService } from '../../core/catalog/catalog.service';
import { CategoryGroup, NodeSpec, groupIntoCategories } from '../../core/catalog/catalog.models';
import * as commands from '../../core/graph/commands';
import { GraphStore } from '../../core/graph/graph-store';
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
    const nodes = this.graph.doc().nodes;
    const rightmost = nodes.reduce((max, node) => Math.max(max, node.position.x), 0);
    const position = nodes.length === 0 ? { x: 120, y: 120 } : { x: rightmost + 300, y: 120 + (nodes.length % 4) * 60 };

    this.graph.dispatch(
      commands.addNode(
        { id: crypto.randomUUID(), type: spec.id, position, values: {}, collapsed: false, disabled: false },
        spec.label,
      ),
    );
  }
}
