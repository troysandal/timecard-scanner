/** Where the captured photo is stored. Same names as `@capacitor/filesystem`'s `Directory`. */
export type ScanDirectory = 'DOCUMENTS' | 'DATA' | 'LIBRARY' | 'CACHE';

export interface CaptureOptions {
  /**
   * Base directory for the saved photo, resolved as `@capacitor/filesystem` does on each platform.
   * Default: `'DOCUMENTS'` on iOS, `'DATA'` on Android (where `'DOCUMENTS'` is the shared Documents
   * folder and needs storage permission).
   */
  directory?: ScanDirectory;
  /** Folder inside `directory` to save into, created if missing. The file name is generated. */
  folder?: string;
}

export interface Row {
  /** Each cell is `''` when blank, never `null`. Characters are limited to `0-9` and `:`. */
  c1: string;
  c2: string;
  c3: string;
  /**
   * True when the scanner had a reason to doubt this row (see `warnings`, or a mapped character). On both
   * platforms this includes a cell that holds ink nothing was read from (it is left blank, never guessed);
   * on Android also any cell that is an unsure read or that its two readers disagree on. Have the user
   * check flagged rows against the photo.
   */
  flagged?: boolean;
  /**
   * iOS only (absent on Android): a digit read offered for a cell the scanner left blank although it holds
   * ink. It is a suggestion, not a value: it is never written into `c1`..`c3`, and the row stays `flagged`.
   * Show it next to the blank cell and apply it only after the user accepts it, cell by cell: do not
   * pre-fill it, and do not offer "confirm all" over rows that carry suggestions. About 7 in 10 are right
   * (measured on one carbon-copy card), so a wrong one is routine; log accepts and edits.
   */
  suggested?: { c1?: string; c2?: string; c3?: string };
}

export interface ScanResult {
  image: {
    /** Native file path; usable with `@capacitor/filesystem`. */
    path: string;
    /** URL for `<img src>` in the web view. */
    webPath: string;
  };
  /** One entry per table row on the card, including blank trailing rows. */
  rows: Row[];
  warnings: string[];
}

/** `error.code` values the plugin rejects with. */
export type ScanErrorCode =
  | 'CANCELLED' // the user closed the scanner: ignore it
  | 'UNAVAILABLE' // iOS below 26, no document scanner (on Android: no Google Play services, or its scanner module not downloaded yet), or the web
  | 'NO_TABLE' // a photo was taken but no table found; `error.data` holds a ScanResult-shaped `image`
  | 'INVALID_ARGUMENT'
  | 'BUSY' // a capture is already in progress
  | 'SCAN_FAILED';

export interface TimeCardScannerPlugin {
  /**
   * True on iOS 26+ with the document scanner, and on Android when Google Play services has its document
   * scanner module (fetched once while online; calling this requests it). Always safe to call.
   */
  isAvailable(): Promise<{ available: boolean }>;

  /**
   * Opens the system document scanner, saves the photo and returns it with
   * the table read from it. On iOS pick Manual shutter, then Save. Rejects with a
   * {@link ScanErrorCode}.
   */
  captureCard(options?: CaptureOptions): Promise<ScanResult>;

  /** Reads the table from a photo already on disk (e.g. one from `captureCard`). */
  scanImage(options: { path: string }): Promise<ScanResult>;
}
