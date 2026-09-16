import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Inline SVG icons.
 *
 * Inline rather than a font or sprite sheet: icons inherit `currentColor`, so a node's accent
 * colour reaches its icon with no extra plumbing, and there is no second network request before
 * the editor looks finished.
 *
 * Node packs name an icon in their descriptor. An unknown name falls back to a neutral glyph
 * rather than rendering nothing, so a pack this build has never seen still looks deliberate.
 *
 * Every glyph is drawn to span roughly 3..21 of its 24-unit box. The palette puts a fixed gap
 * between icon and label, and a glyph that only used the middle ten units read as a wider gap than
 * its neighbours — the same 8px looked like 12 on one row and 8 on the next.
 */
const PATHS: Readonly<Record<string, string>> = {
  node: 'M4 7h16M4 12h16M4 17h10',
  folder: 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
  'search-folder':
    'M3 7a2 2 0 0 1 2-2h4l2 2h5a2 2 0 0 1 2 2v2M10.5 15.5a3.5 3.5 0 1 0 7 0 3.5 3.5 0 0 0-7 0M18 18l3 3',
  filter: 'M3 5h18l-7 8v6l-4 2v-8z',
  search: 'M10 4a6 6 0 1 0 0 12 6 6 0 0 0 0-12M15 15l5 5',
  replace: 'M4 7h9a4 4 0 0 1 0 8H7m0 0 3-3m-3 3 3 3M20 5v6',
  document: 'M5 2h9l5 5v15H5zM14 2v5h5M8.5 12h7M8.5 16h7',
  eye: 'M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6M12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5',
  play: 'M8 5v14l11-7z',
  pause: 'M9 5v14M15 5v14',
  stop: 'M6 6h12v12H6z',
  undo: 'M9 10H5V6M5.5 10.5A7 7 0 1 1 5 14',
  redo: 'M15 10h4V6M18.5 10.5A7 7 0 1 0 19 14',
  save: 'M5 3h11l3 3v15H5zM8 3v6h7V3M8 14h8v7H8z',
  open: 'M3 6a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
  trash: 'M4 7h16M9 7V4h6v3M6 7l1 14h10l1-14M10 11v6M14 11v6',
  chevron: 'M6 9l6 6 6-6',
  star: 'M12 3l2.7 5.8 6.3.8-4.6 4.4 1.2 6.3L12 17.4 6.4 20.3l1.2-6.3L3 9.6l6.3-.8z',
  grid: 'M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z',
  plus: 'M12 5v14M5 12h14',
  minus: 'M5 12h14',
  fit: 'M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5',
  lock: 'M7 11V8a5 5 0 0 1 10 0v3M5 11h14v10H5z',
  settings:
    'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-2.9 1.2 2 2 0 1 1-4 0 1.7 1.7 0 0 0-2.9-1.2l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.7 1.7 0 0 0 3 15a2 2 0 1 1 0-4 1.7 1.7 0 0 0 1.2-2.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A1.7 1.7 0 0 0 10 4a2 2 0 1 1 4 0a1.7 1.7 0 0 0 2.9 1.2l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1A1.7 1.7 0 0 0 21 11a2 2 0 1 1 0 4z',
  check: 'M5 13l4 4L19 7',
  warning: 'M12 4l9 16H3zM12 10v4M12 17v.5',
  clock: 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16M12 8v4l3 2',
  link: 'M9 15l6-6M10.5 6.5 12 5a4.2 4.2 0 0 1 6 6l-1.5 1.5M13.5 17.5 12 19a4.2 4.2 0 0 1-6-6l1.5-1.5',
  power: 'M12 4v8M7.5 6.8a7 7 0 1 0 9 0',
  help: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18M9.6 9.4a2.5 2.5 0 0 1 4.9.6c0 1.7-2.5 2-2.5 3.5M12 17v.5',
  copy: 'M9 9h11v11H9zM5 15H4V4h11v1',
  close: 'M6 6l12 12M18 6 6 18',
  home: 'M4 11l8-7 8 7M6 10v10h12V10',
  drive: 'M4 6h16v5H4zM4 13h16v5H4zM7.5 8.5h.5M7.5 15.5h.5',
  'arrow-up': 'M12 20V5M5 12l7-7 7 7',
  'folder-open': 'M3 7a2 2 0 0 1 2-2h4l2 2h6a2 2 0 0 1 2 2v1H6l-3 8zM6 10h15l-3 9H3z',
  'file-blank': 'M5 2h9l5 5v15H5zM14 2v5h5',
  select: 'M4 5h16M4 5v14M4 19h16M20 5v14',

  // The LLM pack. One glyph per node group, so the palette reads as sections at a glance.
  plug: 'M8 2v6M16 2v6M5 8h14v4a7 7 0 0 1-14 0zM12 19v3',
  chip: 'M8 8h8v8H8zM4 10h4M4 14h4M16 10h4M16 14h4M10 4v4M14 4v4M10 16v4M14 16v4',
  sliders: 'M4 7h9M17 7h3M4 17h3M11 17h9M15 4v6M7 14v6',
  tag: 'M4 4h7l9 9-7 7-9-9zM8 8h.5',
  paperclip: 'M20 11.5 12 19.5a5 5 0 0 1-7-7l8-8a3.5 3.5 0 0 1 5 5l-8 8a2 2 0 0 1-3-3l7.5-7.5',
  sparkles: 'M12 3l1.8 4.7L18.5 9.5l-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8zM18 15l.8 2.2L21 18l-2.2.8L18 21l-.8-2.2L15 18l2.2-.8z',
  layers: 'M12 3 3 8l9 5 9-5zM3 13l9 5 9-5M3 17l9 5 9-5',
  braces: 'M9 4H8a3 3 0 0 0-3 3v2a3 3 0 0 1-3 3 3 3 0 0 1 3 3v2a3 3 0 0 0 3 3h1M15 4h1a3 3 0 0 1 3 3v2a3 3 0 0 0 3 3 3 3 0 0 0-3 3v2a3 3 0 0 1-3 3h-1',
  bookmark: 'M5 3h14v18l-7-4.5L5 21z',
  // Several files, which is a different thing from one file and reads as one at 13px.
  documents: 'M8 2h7l4 4v12H8zM15 2v4h4M5 6v16h11',
  // The indicator on a node's test button: a bulb, lit or not, coloured by the result.
  bulb: 'M9.5 18h5M10 21h4M12 3a6 6 0 0 0-3.5 10.9V16h7v-2.1A6 6 0 0 0 12 3',
  // Arrows out of a corner: "this opens somewhere bigger".
  expand: 'M4 10V4h6M20 14v6h-6M4 4l6 6M20 20l-6-6',
  bolt: 'M13 3 5 14h6l-1 7 8-11h-6z',
  // A grid of rows: a dataset, a table of items.
  table: 'M3 5h18v14H3zM3 10h18M3 15h18M9 5v14',
  // A key: a credential the engine holds.
  key: 'M14.5 3.5a6 6 0 1 0 0 12 6 6 0 0 0 0-12M10.3 13.7 3 21M6 18l2.5 2.5M9 15l2.5 2.5M14.5 9.5h.5',
  // Two arrows chasing each other: reload this list.
  refresh: 'M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5',
  // A pencil over a card: edit the thing behind this field.
  edit: 'M4 20h4l10.5-10.5a2.1 2.1 0 0 0-3-3L5 17zM13 8l3 3',
};

@Component({
  selector: 'app-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [attr.width]="size()"
      [attr.height]="size()"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      [attr.stroke-width]="weight()"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <path [attr.d]="path()" [attr.fill]="filled() ? 'currentColor' : 'none'" />
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex: none;
    }
  `,
})
export class Icon {
  readonly name = input.required<string>();
  readonly size = input(16);
  readonly weight = input(1.8);
  readonly filled = input(false);

  protected readonly path = computed(() => PATHS[this.name()] ?? PATHS['node']);
}
