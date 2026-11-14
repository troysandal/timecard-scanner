import { WebPlugin } from '@capacitor/core';

import type { CaptureOptions, ScanResult, TimeCardScannerPlugin } from './definitions';

/** Used on the web, where scanning isn't supported. */
export class TimeCardScannerUnsupported extends WebPlugin implements TimeCardScannerPlugin {
  async isAvailable(): Promise<{ available: boolean }> {
    return { available: false };
  }

  async captureCard(_options?: CaptureOptions): Promise<ScanResult> {
    throw this.unavailable('Card scanning is only available in the iOS and Android apps.');
  }

  async scanImage(_options: { path: string }): Promise<ScanResult> {
    throw this.unavailable('Card scanning is only available in the iOS and Android apps.');
  }
}
