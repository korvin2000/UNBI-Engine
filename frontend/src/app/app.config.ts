import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { ENGINE_CONFIG, defaultEngineConfig } from './core/engine.config';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withFetch()),
    { provide: ENGINE_CONFIG, useFactory: defaultEngineConfig },
  ],
};
