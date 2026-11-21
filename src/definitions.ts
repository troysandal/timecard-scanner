import type { Directory } from '@capacitor/filesystem';

/** The `@capacitor/filesystem` directories this plugin can save into (the others are not resolved natively). */
type PhotoDirectory = Directory.Documents | Directory.Data | Directory.Library | Directory.Cache;

export interface CaptureOptions {
  /**
   * Base `Directory` (from `@capacitor/filesystem`) for the saved photo, resolved as `@capacitor/filesystem` does on each platform.
   * Default: `'DOCUMENTS'` on iOS, `'DATA'` on Android (where `'DOCUMENTS'` is the shared Documents
   * folder and needs storage permission).
   */
  directory?: PhotoDirectory;
  /** Folder inside `directory` to save into, created if missing. The file name is generated. */
  folder?: string;
  /**
   * After the scanner, show a screen where the user drags a box over the checkpoint rows, and read only that
   * box. The saved photo is still the whole card, so frame the whole card in the scanner. The box starts on the
   * table when the plugin can find it. Cancelling that screen rejects with `CANCELLED` and deletes the saved photo.
   * New in 0.5.0, and a behaviour change for callers of `captureCard`. Default `true`; `false`
   * reads the photo exactly as the scanner returned it (use it for a table-only photo).
   */
  selectTable?: boolean;
}

/** A box on a photo, as fractions (0..1) of the upright photo's width and height, origin top-left. */
export interface ScanRegion {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface ScanImageOptions {
  path: string;
  /** Read only this box. Must lie inside the photo (0..1) and be at least 0.05 wide and 0.05 high. */
  region?: ScanRegion;
}

export interface CropCardOptions {
  /** The photo to crop: a native file path or `file://` URL, e.g. from `@capacitor/camera` (`getPhoto` with `resultType: Uri`). */
  path: string;
  /** Where the cropped photo is saved. Same meaning as in {@link CaptureOptions}. */
  directory?: PhotoDirectory;
  folder?: string;
}

/** The cropped card photo. Nothing is read from it. */
export interface CardPhoto {
  image: {
    /** Native file path; usable with `@capacitor/filesystem`. */
    path: string;
    /** URL for `<img src>` in the web view. */
    webPath: string;
  };
}

export interface Row {
  /** Each cell is `''` when blank, never `null`. Characters are limited to `0-9` and `:`. */
  c1: string;
  c2: string;
  c3: string;
  /**
   * Which cells the scanner had a reason to doubt, aligned with `c1`..`c3`: `[true, false, false]` means check
   * `c1`. Any `true` makes the row one to check (see `warnings`). A cell is flagged when it holds ink nothing was
   * read from (it is left blank, never guessed), when a character was mapped to a digit, and on Android also when
   * it is an unsure read or its two readers disagree. A doubt about the whole row with no single cell (a row
   * that was inserted, or whose checkpoint number disagrees) flags all three. Have the user check flagged cells
   * against the photo.
   *
   * Breaking change in 0.4.0: this was a single `boolean` for the row. An array is always truthy, so test
   * `row.flagged.some(Boolean)` where you used `row.flagged`.
   */
  flagged: [boolean, boolean, boolean];
  /**
   * iOS only (absent on Android): a digit read offered for a cell the scanner left blank although it holds
   * ink. It is a suggestion, not a value: it is never written into `c1`..`c3`, and the cell stays flagged.
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
   * Always safe to call.
   * - `captureAvailable`: `captureCard` and `scanImage` work. True on iOS 26+ with the document scanner, and on Android
   *   when Google Play services has its document scanner module (fetched once while online; calling this requests it).
   * - `photoAvailable`: `cropCard` works (native iOS and Android; false on the web). It needs no camera or Play services.
   * - `available`: the old name of `captureAvailable`, kept for hosts not yet updated; will be removed in a later breaking release.
   */
  isAvailable(): Promise<{ captureAvailable: boolean; photoAvailable: boolean; available: boolean }>;

  /**
   * Shows a screen where the user drags a box around the card on a photo the host took (with `@capacitor/camera`,
   * which can use the phone's macro lens), starting on the card when it can be found, and saves the cropped card
   * as a new JPEG. Reads nothing, so it works on every iOS the plugin supports. The input file is left alone.
   * Rejects with `CANCELLED` (the user closed the screen), `INVALID_ARGUMENT` (unreadable `path`, bad `folder`),
   * `BUSY` or `SCAN_FAILED`.
   */
  cropCard(options: CropCardOptions): Promise<CardPhoto>;

  /**
   * Opens the system document scanner, saves the photo and returns it with
   * the table read from it. On iOS pick Manual shutter, then Save. Rejects with a
   * {@link ScanErrorCode}.
   */
  captureCard(options?: CaptureOptions): Promise<ScanResult>;

  /**
   * Reads the table from a photo already on disk (e.g. one from `captureCard`). With `region`, only that box of
   * the photo is read, for a photo of the whole card where the table was not found by itself (`NO_TABLE`) and
   * the user drags a box around the checkpoint rows. `image` in the result is still the whole photo.
   * Rejects `INVALID_ARGUMENT` for a bad `region`.
   */
  scanImage(options: ScanImageOptions): Promise<ScanResult>;
}
