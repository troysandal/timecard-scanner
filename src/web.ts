import { WebPlugin } from '@capacitor/core';

import type { CaptureOptions, CardPhoto, CropCardOptions, ScanResult, TimeCardScannerPlugin } from './definitions';

/** Used on the web, where scanning isn't supported. */
export class TimeCardScannerUnsupported extends WebPlugin implements TimeCardScannerPlugin {
  async isAvailable(): Promise<{ captureAvailable: boolean; photoAvailable: boolean; available: boolean }> {
    return { captureAvailable: false, photoAvailable: false, available: false };
  }

  async cropCard(_options: CropCardOptions): Promise<CardPhoto> {
    throw this.unavailable('Card cropping is only available in the iOS and Android apps.');
  }

  async captureCard(_options?: CaptureOptions): Promise<ScanResult> {
    throw this.unavailable('Card scanning is only available in the iOS and Android apps.');
  }

  async scanImage(_options: { path: string }): Promise<ScanResult> {
    throw this.unavailable('Card scanning is only available in the iOS and Android apps.');
  }
}
