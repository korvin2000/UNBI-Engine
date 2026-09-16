import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { CatalogService } from '../catalog/catalog.service';
import { EngineEvent } from './engine-events';
import { EngineSocket } from './engine-socket';
import { RunStore } from './run-store';

/** The store reaches the catalog through the graph store; nothing here asks it anything. */
class StubCatalog {
  readonly byId = () => new Map();
}

/**
 * The event stream, faked.
 *
 * What this store does is fold frames into state, and every interesting question is about the
 * fold. A real WebSocket would make the test slower and less able to pose the awkward cases — a
 * chunk arriving after a restart, a ribbon longer than anyone will read.
 */
class FakeSocket {
  readonly frames = new Subject<EngineEvent>();
  readonly events: Observable<EngineEvent> = this.frames.asObservable();
  readonly state = () => 'open' as const;
  send(): void {
    // Nothing in these tests sends.
  }
}

describe('RunStore', () => {
  let socket: FakeSocket;
  let store: RunStore;

  beforeEach(() => {
    socket = new FakeSocket();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: EngineSocket, useValue: socket },
        { provide: CatalogService, useClass: StubCatalog },
      ],
    });
    store = TestBed.inject(RunStore);
    socket.frames.next({ type: 'run.started', runId: 'r1', order: ['a'], at: '' });
  });

  function stream(chunk: string): void {
    socket.frames.next({
      type: 'node.stream',
      runId: 'r1',
      nodeId: 'a',
      portKey: 'text',
      chunk,
      at: '',
    });
  }

  it('appends streamed chunks into one growing answer', () => {
    stream('Hel');
    stream('lo');
    expect(store.statusFor('a')?.streamed).toBe('Hello');
  });

  it('keeps the end rather than the beginning once the answer grows long', () => {
    stream('x'.repeat(4500));
    const streamed = store.statusFor('a')?.streamed ?? '';
    expect(streamed.length).toBe(4000);
  });

  it('clears the previous attempt when the node starts again', () => {
    stream('first answer');
    socket.frames.next({
      type: 'node.state',
      runId: 'r1',
      nodeId: 'a',
      state: 'RUNNING',
      message: null,
      durationMillis: null,
      at: '',
    });
    expect(store.statusFor('a')?.streamed).toBe('');
  });

  it('keeps the streamed text after the node finishes, so the answer stays on screen', () => {
    stream('done');
    socket.frames.next({
      type: 'node.state',
      runId: 'r1',
      nodeId: 'a',
      state: 'COMPLETED',
      message: null,
      durationMillis: 12,
      at: '',
    });
    expect(store.statusFor('a')?.streamed).toBe('done');
    expect(store.statusFor('a')?.progress).toBe(1);
  });

  it('keeps streams and logs apart — one is a value, the other is a list', () => {
    stream('answer');
    socket.frames.next({ type: 'node.log', runId: 'r1', nodeId: 'a', message: 'a log line', at: '' });
    expect(store.statusFor('a')?.streamed).toBe('answer');
    expect(store.statusFor('a')?.logs).toEqual(['a log line']);
  });
});
