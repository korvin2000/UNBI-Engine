import { InjectionToken } from '@angular/core';

/**
 * Where the engine lives.
 *
 * An injection token rather than a constant so that tests, an embedded build and a deployment
 * behind a reverse proxy differ by one provider instead of by a rebuild.
 */
export interface EngineConfig {
  readonly httpBase: string;
  readonly socketUrl: string;
}

export const ENGINE_CONFIG = new InjectionToken<EngineConfig>('ENGINE_CONFIG');

/**
 * Defaults for the development setup: Angular on 4200, engine on 8080.
 *
 * When the UI is served by the backend itself, both resolve to the current origin, which is why
 * this is derived from `location` rather than hardcoded.
 */
export function defaultEngineConfig(): EngineConfig {
  const sameOrigin = typeof location !== 'undefined' && location.port !== '4200';
  if (sameOrigin && typeof location !== 'undefined') {
    const socketProtocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return {
      httpBase: '',
      socketUrl: `${socketProtocol}//${location.host}/ws/engine`,
    };
  }
  return {
    httpBase: 'http://localhost:8081',
    socketUrl: 'ws://localhost:8081/ws/engine',
  };
}
