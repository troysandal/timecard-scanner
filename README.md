# TimeCardScanner (Capacitor plugin)

Photograph a paper enduro time card and get its checkpoint table back as
`rows` of three strings. Offline, on device:

- **iOS 26+**: Apple's document table detector.
- **Android** (min SDK 24): our own reader (a small digit model plus bundled ML Kit text
  recognition). **Not yet tried on a real phone**; see "Android" below.

On older iOS and on the web, `isAvailable()` is `false` and the other methods
reject with `UNAVAILABLE`.

## Install

```sh
npm install github:troysandal/timecard-scanner   # npm name: timecard-scanner
# or a checkout / git submodule: npm install ./path/to/timecard-scanner
npx cap sync ios
```

Add to the host app's `Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>Photograph your time card to read its times.</string>
```

The plugin compiles for the host's iOS 17 target; the scanner itself is behind
`#available(iOS 26)`.

For Android: `npx cap sync android`. Nothing to add to the manifest. The app grows by
roughly 4 MB per ABI (the bundled ML Kit text model) plus 0.3 MB for the digit model.

## Use

```ts
import { Directory } from '@capacitor/filesystem';
import { TimeCardScanner } from 'timecard-scanner';

if ((await TimeCardScanner.isAvailable()).captureAvailable) {
  try {
    const { image, rows, warnings } = await TimeCardScanner.captureCard({
      directory: Directory.Documents,
      folder: 'cards',
    });
    // image.webPath → <img src>; image.path → @capacitor/filesystem
    // rows[i] = { c1, c2, c3, flagged: [bool, bool, bool], suggested? }, '' for a blank cell
  } catch (e: any) {
    if (e.code === 'CANCELLED') return; // user closed the scanner
    if (e.code === 'NO_TABLE') { /* e.data.image holds the photo */ }
  }
}
```

In the scanner choose **Manual** shutter, then Save. Hold the phone about
25–30 cm away: the document scanner does not switch to the macro lens.

`captureCard` takes the first page only (a warning notes extra pages).
`scanImage({ path })` re-reads a photo already on disk.

**Behaviour change in 0.5.0:** `captureCard` now shows a screen after the scanner where the user drags a box over the
checkpoint rows, and reads only that box (the saved photo is the whole card). Pass `selectTable: false` to read the
photo exactly as the scanner returned it, as before. Cancelling that screen rejects `CANCELLED` and deletes the photo.

**Photographing the whole card.** The scanner may be pointed at the whole card (name, number, totals) so the
saved photo is the full card; the table is found by itself. If that fails (`NO_TABLE`; `error.data.image` is the
photo), let the user drag a box around the checkpoint rows and read just that (`captureCard` does this itself by default, see `selectTable`):
`scanImage({ path, region: { x, y, width, height } })`, fractions (0..1) of the photo, origin top-left. The
returned `image` is still the whole photo.

## Photo only (0.6.0): `cropCard`

When you only want a photo of the card and no reading (the reader is below the accuracy bar for some card types, see
the bench notes), take the photo with `@capacitor/camera`, whose system camera uses the phone's macro lens when the
card is close, and let the plugin crop it:

```ts
import { Camera, CameraResultType, CameraSource } from '@capacitor/camera';

const shot = await Camera.getPhoto({ source: CameraSource.Camera, resultType: CameraResultType.Uri, quality: 95 });
try {
  const { image } = await TimeCardScanner.cropCard({ path: shot.path!, directory: Directory.Documents, folder: 'cards' });
  // image.webPath → <img src>; the cropped card, saved as card-<uuid>.jpg. `shot.path` is still the host's to delete.
} catch (e: any) {
  if (e.code === 'CANCELLED') return; // the user closed the crop screen
}
```

`cropCard` shows a native screen with the photo and a box that starts on the card when it can be found (Vision on iOS,
a brightness search on Android); the user drags a corner to resize or the inside to move, then taps Use. It crops to
that rectangle (no perspective straightening) and reads nothing, so it needs no camera permission, no Play services
and no iOS 26: `isAvailable().photoAvailable` is true on native iOS and Android. Strings: `timecard_scanner_card_*`
Android resources, and the host's `Localizable.strings` on iOS ("Drag the box around the card", "Cancel", "Use").
`isAvailable()` now returns `captureAvailable` (was `available`, still returned as an alias until the next breaking
release) beside `photoAvailable`.

## Output

- Cells contain only `0-9` and `:`. Colons are meaningful (`:05`, `4:21`) and
  never stripped. Nothing is interpreted as a time.
- `rows.length` is the printed row count of the card (10/12/15/18), discovered
  from the photo.
- `flagged` is one boolean per cell (`c1`..`c3`) the scanner doubts: a character was mapped to a digit, or
  the cell holds ink nothing was read from. A row that was inserted or whose checkpoint number disagrees flags
  all three. (Before 0.4.0 it was one boolean per row.) Expect roughly one in
  five filled cells to be wrong: let the user edit.

## Android

- **Capture** uses the ML Kit document scanner, which Google Play services delivers and
  downloads once while the phone is online. `isAvailable()` is `true` only when that module
  is present, and calling it requests the download, so call it early (at app start), not
  just before scanning at an event with no signal.
- **Reading** is fully offline and needs no Play services: `scanImage({ path })` works on
  any device.
- **Photo location:** `directory` (a `Directory` from `@capacitor/filesystem`; Documents, Data, Library or Cache) defaults to `Directory.Data` on Android (the app's files
  directory). `Directory.Documents` is the shared Documents folder, as in `@capacitor/filesystem`,
  and needs storage permission.
- **Flags matter more than on iOS.** A cell is `flagged` when it is an unsure
  read, a value the two readers disagree on, a suspect shape (a line through the writing,
  possibly touching digits), or ink that nothing was read from. On the 26 test cards that
  left 2 wrong cells unflagged (345 of 358 written cells were read correctly), with about 2
  flagged cells per card that were in fact right. A written dot (`9.26`) is read as `:`.
  `warnings` names the row, column and reason.
- **Tried so far** only on an emulator: all three methods and the `CANCELLED`, `NO_TABLE`
  and `INVALID_ARGUMENT` rejections work in a Capacitor 8 app; `scanImage` takes about 1.1 s
  per card there. Out-of-focus captures read badly (and come back with every row flagged).
- **Unproven:** a real phone (speed, the document scanner's output, focus), pencil, glare,
  other writers. The numbers come from the cards the reader
  was developed on.

## Layout

- `src/` — TypeScript definitions and the web fallback.
- `ios/Core/` — `TimeCardScannerCore`, the Swift scanning package (no Capacitor
  dependency; tests: `cd ios/Core && swift test`).
- `ios/Sources/TimeCardScannerPlugin/` — the Capacitor bridge.
- `android/core/` — the Kotlin reader, pure Kotlin with no Android or Capacitor imports
  (`TimeCardScanner.scan(gray, width, height, symbols)`). It is compiled into the plugin's
  one Android module; its tests (`android/core/src/test`) run as that module's unit tests.
- `android/src/` — the Capacitor bridge: `TimeCardScannerPlugin`, `ImageScanner` (decode,
  ML Kit text recognition, core) and `DocumentCapture` (the document scanner).

## Development

Nothing outside this repo is needed.

```sh
npm install
npm run build            # TypeScript -> dist/
npm run verify:core-ios  # swift test on ios/Core (macOS 26)
npm run verify:ios       # xcodebuild of the plugin for iOS
npm run verify:android   # core unit tests + assembleDebug (JDK 21, Android SDK via ANDROID_HOME or android/local.properties)
```
