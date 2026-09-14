import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { DirectoryListing, FileBrowserService, FileEntry } from './file-browser.service';
import { Icon } from '../icon';

/**
 * The file and folder picker.
 *
 * A node's path fields are settings the *engine* dereferences, so this browses the engine's disk
 * over `GET /api/fs` rather than opening the browser's own file input, which could only ever return
 * a sandboxed upload handle. Everything else here is ordinary dialog work: breadcrumbs, roots,
 * keyboard support, and a typed path box for people who already know where they are going.
 */
@Component({
  selector: 'app-file-browser',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  templateUrl: './file-browser.html',
  styleUrl: './file-browser.scss',
  host: {
    '(document:keydown)': 'onKeydown($event)',
  },
})
export class FileBrowser {
  private readonly browser = inject(FileBrowserService);
  private readonly filterBox = viewChild<ElementRef<HTMLInputElement>>('filterBox');

  protected readonly request = this.browser.open;
  protected readonly listing = signal<DirectoryListing | null>(null);
  protected readonly loading = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly selected = signal<string | null>(null);
  protected readonly typed = signal('');
  protected readonly filter = signal('');

  protected readonly visible = computed(() => {
    const needle = this.filter().trim().toLowerCase();
    const entries = this.listing()?.entries ?? [];
    return needle ? entries.filter((entry) => entry.name.toLowerCase().includes(needle)) : entries;
  });

  /**
   * The current path split for the breadcrumb, each crumb carrying the path it leads to.
   *
   * Built from the separator the server reported rather than assumed, so a Windows engine and a
   * Linux engine both produce crumbs that actually navigate.
   */
  protected readonly crumbs = computed(() => {
    const listing = this.listing();
    if (!listing) {
      return [];
    }
    const separator = listing.separator;
    const parts = listing.path.split(separator).filter((part) => part.length > 0);
    const crumbs: { label: string; path: string }[] = [];
    let walked = listing.path.startsWith(separator) ? separator : '';
    for (const part of parts) {
      walked = walked === separator ? separator + part : walked ? `${walked}${separator}${part}` : part;
      crumbs.push({ label: part, path: walked });
    }
    return crumbs;
  });

  /** In folder mode the current folder is the answer; in file mode a file must be picked. */
  protected readonly confirmable = computed(() => {
    const request = this.request();
    if (!request) {
      return false;
    }
    return request.mode === 'directory' ? !!this.listing()?.path : !!this.selected();
  });

  protected readonly result = computed(() => {
    const request = this.request();
    if (!request) {
      return '';
    }
    return request.mode === 'directory' ? (this.selected() ?? this.listing()?.path ?? '') : (this.selected() ?? '');
  });

  constructor() {
    // Opening is what triggers the first listing: the dialog has no state of its own between uses,
    // so every open starts from the path the caller asked for rather than from where it was left.
    effect(() => {
      const request = this.request();
      if (!request) {
        return;
      }
      this.selected.set(null);
      this.filter.set('');
      this.navigate(request.startAt);
      // Typing should filter straight away. Deferred because the input does not exist until the
      // dialog's own `@if` has rendered.
      queueMicrotask(() => this.filterBox()?.nativeElement.focus());
    });
  }

  protected navigate(path: string): void {
    const request = this.request();
    if (!request) {
      return;
    }
    this.loading.set(true);
    this.failure.set(null);
    this.browser.list(path, request.mode, request.extensions).subscribe({
      next: (listing) => {
        this.listing.set(listing);
        this.typed.set(listing.path);
        this.failure.set(listing.error);
        this.loading.set(false);
        // Entering a folder clears a stale file selection; keeping it would let Select return a
        // path that is no longer on screen.
        if (request.mode === 'file') {
          this.selected.set(null);
        }
      },
      error: () => {
        this.failure.set('Could not reach the engine to list that folder.');
        this.loading.set(false);
      },
    });
  }

  protected onEntry(entry: FileEntry): void {
    if (entry.directory) {
      // In folder mode a single click both enters and selects: there is nothing else to click.
      this.navigate(entry.path);
    } else {
      this.selected.set(entry.path);
    }
  }

  protected onEntryDoubleClick(entry: FileEntry): void {
    if (entry.directory) {
      this.navigate(entry.path);
    } else {
      this.browser.choose(entry.path);
    }
  }

  protected up(): void {
    const parent = this.listing()?.parent;
    if (parent) {
      this.navigate(parent);
    }
  }

  protected goTyped(): void {
    this.navigate(this.typed().trim());
  }

  protected onTyped(event: Event): void {
    this.typed.set((event.target as HTMLInputElement).value);
  }

  protected onFilter(event: Event): void {
    this.filter.set((event.target as HTMLInputElement).value);
  }

  protected confirm(): void {
    if (this.confirmable()) {
      this.browser.choose(this.result());
    }
  }

  protected cancel(): void {
    this.browser.cancel();
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (!this.request()) {
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      this.cancel();
    }
    if (event.key === 'Enter' && !(event.target instanceof HTMLInputElement)) {
      event.preventDefault();
      this.confirm();
    }
  }

  protected sizeOf(entry: FileEntry): string {
    if (entry.directory) {
      return '';
    }
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let value = entry.size;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit += 1;
    }
    return `${unit === 0 ? value : value.toFixed(1)} ${units[unit]}`;
  }

  protected dateOf(entry: FileEntry): string {
    if (!entry.modified) {
      return '';
    }
    const when = new Date(entry.modified);
    return Number.isNaN(when.getTime()) ? '' : when.toLocaleDateString();
  }
}
