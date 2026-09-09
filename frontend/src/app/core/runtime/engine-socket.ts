import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { Observable, Subject, timer } from 'rxjs';
import { ENGINE_CONFIG } from '../engine.config';
import { EngineCommand, EngineEvent, isEngineEvent } from './engine-events';

export type ConnectionState = 'connecting' | 'open' | 'closed';

/**
 * The WebSocket, as an RxJS stream.
 *
 * This is the part of the app that is genuinely event-shaped, so it is RxJS rather than signals:
 * frames arrive unbidden, in order, and consumers care about the sequence. Signals hold the
 * *result* of folding that stream — see `RunStore`.
 *
 * Reconnects with a bounded backoff. A dropped socket is normal (a backend restart during
 * development), and an editor that silently stops responding to Run is the worst possible answer.
 */
@Injectable({ providedIn: 'root' })
export class EngineSocket {
  private readonly config = inject(ENGINE_CONFIG);
  private readonly destroyRef = inject(DestroyRef);

  private socket: WebSocket | null = null;
  private reconnectAttempt = 0;
  private closedByUs = false;
  private readonly queued: string[] = [];

  private readonly frames = new Subject<EngineEvent>();
  private readonly connection = signal<ConnectionState>('closed');

  /** Every frame from the engine, in arrival order. */
  readonly events: Observable<EngineEvent> = this.frames.asObservable();
  readonly state = this.connection.asReadonly();

  constructor() {
    this.destroyRef.onDestroy(() => this.disconnect());
  }

  connect(): void {
    if (this.socket && this.socket.readyState <= WebSocket.OPEN) {
      return;
    }
    this.closedByUs = false;
    this.connection.set('connecting');

    const socket = new WebSocket(this.config.socketUrl);
    this.socket = socket;

    socket.onopen = () => {
      this.reconnectAttempt = 0;
      this.connection.set('open');
      // Anything the user asked for while we were down goes now, in the order it was asked.
      while (this.queued.length > 0) {
        const pending = this.queued.shift();
        if (pending) {
          socket.send(pending);
        }
      }
    };

    socket.onmessage = (message: MessageEvent<string>) => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(message.data);
      } catch {
        this.frames.next({ type: 'error', message: 'The engine sent something unreadable' });
        return;
      }
      if (isEngineEvent(parsed)) {
        this.frames.next(parsed);
      }
    };

    socket.onerror = () => {
      // `onclose` always follows, and that is where reconnection is handled. Emitting here as well
      // would double every failure in the UI.
    };

    socket.onclose = () => {
      this.socket = null;
      this.connection.set('closed');
      if (!this.closedByUs) {
        this.scheduleReconnect();
      }
    };
  }

  disconnect(): void {
    this.closedByUs = true;
    this.socket?.close();
    this.socket = null;
    this.connection.set('closed');
  }

  send(command: EngineCommand): void {
    const payload = JSON.stringify(command);
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(payload);
      return;
    }
    // A `run` the user pressed while reconnecting is worth holding; a `ping` is not.
    if (command.type !== 'ping') {
      this.queued.push(payload);
    }
    this.connect();
  }

  private scheduleReconnect(): void {
    // 0.5s, 1s, 2s, 4s, then every 8s. Bounded so a backend that is down for an hour does not
    // produce an hour of exponentially rarer, eventually useless retries.
    const delay = Math.min(500 * 2 ** this.reconnectAttempt, 8000);
    this.reconnectAttempt += 1;
    timer(delay).subscribe(() => {
      if (!this.closedByUs) {
        this.connect();
      }
    });
  }
}
