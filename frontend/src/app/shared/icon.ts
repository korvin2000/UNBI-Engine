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
 */
const PATHS: Readonly<Record<string, string>> = {
  node: 'M4 7h16M4 12h16M4 17h10',
  folder: 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
  'search-folder':
    'M3 7a2 2 0 0 1 2-2h4l2 2h5a2 2 0 0 1 2 2v2M10.5 15.5a3.5 3.5 0 1 0 7 0 3.5 3.5 0 0 0-7 0M18 18l3 3',
  filter: 'M3 5h18l-7 8v6l-4 2v-8z',
  search: 'M10 4a6 6 0 1 0 0 12 6 6 0 0 0 0-12M15 15l5 5',
  replace: 'M4 7h9a4 4 0 0 1 0 8H7m0 0 3-3m-3 3 3 3M20 5v6',
  document: 'M6 3h8l4 4v14H6zM14 3v4h4M9 12h6M9 16h6',
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
