import { provideHttpClient } from '@angular/common/http';
import { Component, provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WidgetSpec } from '../core/catalog/catalog.models';
import { ENGINE_CONFIG, defaultEngineConfig } from '../core/engine.config';
import { TextEditRequest, TextEditorService } from '../shared/text-editor/text-editor.service';
import { ListPlacement, NodeWidget } from './node-widget';

/**
 * Where a dropdown's list is drawn, which is the one thing about this widget that depends on its
 * host rather than on the descriptor.
 *
 * Inside a node the list hangs off the trigger; inside the inspector — a column that scrolls — an
 * absolutely positioned panel is clipped by the column, so a list near the bottom of a scrolled
 * panel would open into a few pixels of visible space. These tests pin down the measurement, the
 * flip, and the two events that invalidate it.
 */
@Component({
  selector: 'test-host',
  imports: [NodeWidget],
  template: `<app-node-widget [spec]="spec()" [listPlacement]="placement()" />`,
})
class Host {
  readonly spec = signal<WidgetSpec>({
    kind: 'dropdown',
    options: [
      { value: 'a', label: 'Alpha' },
      { value: 'b', label: 'Beta' },
    ],
    optionsKey: '',
    allowCustom: false,
    narrowing: null,
  });
  readonly placement = signal<ListPlacement>('fixed');
}

/** jsdom has no layout, so the one measurement this feature takes is supplied. */
function stubRect(element: Element, rect: { left: number; top: number; width: number; height: number }): void {
  element.getBoundingClientRect = () =>
    ({
      x: rect.left,
      y: rect.top,
      left: rect.left,
      top: rect.top,
      width: rect.width,
      height: rect.height,
      right: rect.left + rect.width,
      bottom: rect.top + rect.height,
      toJSON: () => ({}),
    }) as DOMRect;
}

describe('NodeWidget — list placement', () => {
  let fixture: ComponentFixture<Host>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        { provide: ENGINE_CONFIG, useFactory: defaultEngineConfig },
      ],
    });
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
  });

  afterEach(() => TestBed.resetTestingModule());

  function trigger(): HTMLElement {
    return fixture.nativeElement.querySelector('.select') as HTMLElement;
  }

  function openList(): void {
    (trigger().querySelector('button.select__button') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  function panel(): HTMLElement | null {
    return fixture.nativeElement.querySelector('.select__panel') as HTMLElement | null;
  }

  it('pins the panel to the trigger it measured', () => {
    stubRect(trigger(), { left: 120, top: 200, width: 240, height: 26 });
    openList();

    const list = panel();
    expect(list).not.toBeNull();
    expect(list!.classList.contains('select__panel--fixed')).toBe(true);
    expect(list!.style.left).toBe('120px');
    expect(list!.style.width).toBe('240px');
    // 3px under the trigger, the same gap the inline panel uses.
    expect(list!.style.top).toBe('229px');
    expect(list!.style.bottom).toBe('');
    expect(list!.style.maxHeight).not.toBe('');
  });

  it('flips upward when there is no room below', () => {
    // A trigger 40px off the bottom of the window: opening downward would put the list off screen.
    const bottomOfWindow = window.innerHeight - 40;
    stubRect(trigger(), { left: 10, top: bottomOfWindow - 26, width: 200, height: 26 });
    openList();

    const list = panel()!;
    expect(list.style.top).toBe('');
    expect(list.style.bottom).toBe(`${window.innerHeight - (bottomOfWindow - 26) + 3}px`);
  });

  it('leaves an inline list exactly as it was', () => {
    fixture.componentInstance.placement.set('inline');
    fixture.detectChanges();
    stubRect(trigger(), { left: 120, top: 200, width: 240, height: 26 });
    openList();

    const list = panel()!;
    expect(list.classList.contains('select__panel--fixed')).toBe(false);
    // Nothing measured, nothing written: the stylesheet places it under the trigger.
    expect(list.style.left).toBe('');
    expect(list.style.top).toBe('');
  });

  it('closes a pinned list when the page scrolls under it, or the window resizes', () => {
    stubRect(trigger(), { left: 0, top: 100, width: 200, height: 26 });
    openList();
    expect(panel()).not.toBeNull();

    // A scroll of the container the panel was measured against — the inspector's body. It does not
    // bubble, which is why the widget listens in the capture phase.
    fixture.nativeElement.dispatchEvent(new Event('scroll', { bubbles: false }));
    fixture.detectChanges();
    expect(panel()).toBeNull();

    openList();
    expect(panel()).not.toBeNull();
    window.dispatchEvent(new Event('resize'));
    fixture.detectChanges();
    expect(panel()).toBeNull();
  });

  it('keeps a pinned list open while its own options are scrolled', () => {
    stubRect(trigger(), { left: 0, top: 100, width: 200, height: 26 });
    openList();

    // The list is the element that scrolls in fixed placement — the panel hands it the height. A
    // capture listener on `document` hears this non-bubbling event too, so the target is what
    // tells "the user is reading the options" from "the page moved under the panel".
    const list = panel()!.querySelector('.select__list') as HTMLElement;
    list.dispatchEvent(new Event('scroll', { bubbles: false }));
    fixture.detectChanges();
    expect(panel()).not.toBeNull();

    // Still closes for a scroll that happened outside the widget.
    document.dispatchEvent(new Event('scroll', { bubbles: false }));
    fixture.detectChanges();
    expect(panel()).toBeNull();
  });

  it('swallows Escape only when it had a list to close', () => {
    let reachedHost = 0;
    fixture.nativeElement.addEventListener('keydown', () => reachedHost++);
    const button = trigger().querySelector('button.select__button') as HTMLButtonElement;

    // Nothing open: the key belongs to whatever encloses this widget — the inspector, which closes.
    button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(reachedHost).toBe(1);

    stubRect(trigger(), { left: 0, top: 100, width: 200, height: 26 });
    openList();
    button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    // The list was the innermost open thing, so it took the key and closed.
    expect(panel()).toBeNull();
    expect(reachedHost).toBe(1);
  });
});

/**
 * A readout, which is the one widget that shows rather than sets.
 *
 * The rules worth pinning down are the ones a reader of the node depends on: that "nobody asked
 * yet" and "this gateway does not publish it" stay visibly different, that a timestamp is said the
 * way people say it, and that the long ones offer the dialog rather than growing the node.
 */
@Component({
  selector: 'readout-host',
  imports: [NodeWidget],
  template: `<app-node-widget [spec]="spec()" [value]="value()" [label]="label()" [hint]="hint()" />`,
})
class ReadoutHost {
  readonly spec = signal<WidgetSpec>({ kind: 'display', style: 'line', unit: '' });
  readonly value = signal<unknown>(undefined);
  readonly label = signal('Context Window');
  readonly hint = signal('');
}

describe('NodeWidget — a readout', () => {
  let fixture: ComponentFixture<ReadoutHost>;
  let opened: TextEditRequest | null;

  /** The one text dialog, stubbed: what matters here is what this widget asks it to open with. */
  class StubEditor {
    edit(request: TextEditRequest): Promise<string | null> {
      opened = request;
      return Promise.resolve(null);
    }
  }

  beforeEach(() => {
    opened = null;
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(),
        { provide: ENGINE_CONFIG, useFactory: defaultEngineConfig },
        { provide: TextEditorService, useClass: StubEditor },
      ],
    });
    fixture = TestBed.createComponent(ReadoutHost);
    fixture.detectChanges();
  });

  afterEach(() => TestBed.resetTestingModule());

  function show(value: unknown, spec?: WidgetSpec): void {
    if (spec) {
      fixture.componentInstance.spec.set(spec);
    }
    fixture.componentInstance.value.set(value);
    fixture.detectChanges();
  }

  function text(selector: string): string {
    return (fixture.nativeElement.querySelector(selector)?.textContent ?? '').trim();
  }

  it('draws its own label, because a fact is a two-column line', () => {
    show('128,000');
    expect(text('.fact__name')).toBe('Context Window');
    expect(text('.fact__text')).toBe('128,000');
  });

  it('keeps the unit quieter than the number', () => {
    show('128,000', { kind: 'display', style: 'line', unit: 'tok' });
    expect(text('.fact__unit')).toBe('tok');
  });

  it('tells "nobody has asked yet" from "this gateway does not publish it"', () => {
    // Nothing stored: the bulb has not been pressed. The row is unasked, not empty.
    expect(fixture.nativeElement.querySelector('.fact__pending')).not.toBeNull();

    show('');
    expect(fixture.nativeElement.querySelector('.fact__pending')).toBeNull();
    expect(text('.fact__blank')).toBe('—');
    expect(fixture.nativeElement.querySelector('.fact__blank')?.getAttribute('title')).toBe(
      'not published by this gateway',
    );
  });

  it('says a boolean with a glyph, in the footer\'s own two colours', () => {
    show(true);
    expect(fixture.nativeElement.querySelector('.fact__flag--ok')).not.toBeNull();
    // A tick is not text anybody would want to paste, so there is nothing to copy.
    expect(fixture.nativeElement.querySelector('.fact__copy')).toBeNull();

    show(false);
    expect(fixture.nativeElement.querySelector('.fact__flag--problem')).not.toBeNull();
  });

  it('says a timestamp the way people say it, with the instant in the tooltip', () => {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60_000).toISOString();
    show(tenMinutesAgo);

    expect(text('.fact__text')).toBe('10 minutes ago');
    expect(fixture.nativeElement.querySelector('.fact__value')?.getAttribute('title')).toBe(
      new Date(tenMinutesAgo).toLocaleString(),
    );
  });

  it('leaves a value that merely looks numeric alone', () => {
    // "2026" is a year a gateway publishes, not an instant. Rendering it as "56 years ago" would
    // be a confident lie about a fact nobody can check from the node.
    show('2026');
    expect(text('.fact__text')).toBe('2026');
  });

  it('draws a set as chips, from a list or from a written-out string', () => {
    show(['text', 'image'], { kind: 'display', style: 'chips', unit: '' });
    expect([...fixture.nativeElement.querySelectorAll('.chip--fact')].map((chip: Element) =>
      chip.textContent?.trim(),
    )).toEqual(['text', 'image']);

    show('text, image, audio');
    expect(fixture.nativeElement.querySelectorAll('.chip--fact')).toHaveLength(3);

    show([]);
    expect(text('.fact__blank')).toBe('—');
  });

  it('clamps a long block and offers it read-only in the one text dialog', async () => {
    const paragraph = Array.from({ length: 12 }, (_, line) => `line ${line}`).join('\n');
    show(paragraph, { kind: 'display', style: 'block', unit: '' });

    const prose = fixture.nativeElement.querySelector('.fact__prose') as HTMLElement;
    expect(prose.classList.contains('is-clamped')).toBe(true);

    (fixture.nativeElement.querySelector('.fact__more') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(opened?.text).toBe(paragraph);
    expect(opened?.readOnly).toBe(true);
    expect(opened?.title).toBe('Context Window');
  });

  it('leaves a short block unclamped, with nothing to expand', () => {
    show('two\nlines', { kind: 'display', style: 'block', unit: '' });
    expect(fixture.nativeElement.querySelector('.fact__prose')?.classList.contains('is-clamped')).toBe(
      false,
    );
    expect(fixture.nativeElement.querySelector('.fact__more')).toBeNull();
  });

  it('leaves the copy button in the tab order', () => {
    // The only route to the value for someone who cannot point at it: the fallback for a refused
    // clipboard is a text selection, which a keyboard cannot make on a transformed canvas either.
    show('anthropic/claude-opus');
    const button = fixture.nativeElement.querySelector('.fact__copy') as HTMLElement;
    expect(button.hasAttribute('tabindex')).toBe(false);
  });

  it('copies the value, and says so for a beat', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    show('anthropic/claude-opus');

    (fixture.nativeElement.querySelector('.fact__copy') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(writeText).toHaveBeenCalledWith('anthropic/claude-opus');
    expect(fixture.nativeElement.querySelector('.fact__copy')?.getAttribute('title')).toBe('Copied');
  });

  it('selects the value instead when the browser refuses the clipboard', async () => {
    // Refused outright inside an embedded browser view, and absent over plain HTTP on a remote
    // host. A button that silently did nothing there would read as broken, so the fallback hands
    // over a selection and the tooltip says what to press.
    const writeText = vi.fn().mockRejectedValue(new DOMException('denied', 'NotAllowedError'));
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    show('openai/gpt-4o-mini');

    (fixture.nativeElement.querySelector('.fact__copy') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('.fact__copy') as HTMLElement;
    expect(button.classList.contains('is-blocked')).toBe(true);
    expect(button.getAttribute('title')).toContain('Ctrl+C');
    expect(window.getSelection()?.toString()).toContain('openai/gpt-4o-mini');
  });
});
