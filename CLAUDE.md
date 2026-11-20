# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`timecard-scanner` is a Capacitor 8 plugin (plugin id `TimeCardScanner`) that photographs a paper enduro time card and returns its checkpoint table as `rows` of three strings (`c1`,`c2`,`c3`, plus `flagged`). Offline, on device. It is a self-contained repo: nothing outside it is needed to build or test.

The research/evaluation side (card corpus with rider data, Mac scoring CLI, test-bench apps, digit-model training) lives in a separate private repo, `timecard-scanner-bench`, which consumes this repo as a git submodule at `plugin/`. Reader changes are made here and gated there by corpus score (the Android reader must still score 345/358 handwritten cells via `./gradlew :eval:run --args="corpus scan ocr"` in the bench's `android/`). No corpus images are in this repo.

## Commands

```sh
npm install
npm run build              # tsc -> dist/esm (also runs on `npm install` via `prepare`)
npm run typecheck
npm run verify:core-ios    # cd ios/Core && swift test   (macOS 26; Swift Testing)
npm run verify:ios         # xcodebuild -scheme TimecardScanner -destination generic/platform=iOS build
npm run verify:android     # cd android && ./gradlew testDebugUnitTest assembleDebug  (JDK 21, Android SDK)
```

Single tests:
- Swift: `cd ios/Core && swift test --filter CleanupFlagTests`
- Kotlin: `cd android && ./gradlew testDebugUnitTest --tests 'com.troysandal.timecardscanner.DigitNetTest'`

Android needs an SDK location: `ANDROID_HOME` or `android/local.properties` (git-ignored).

## Architecture

Three layers; the reader differs per platform, the JS API and rejection codes are identical.

- **`src/`** — TypeScript only: `definitions.ts` (the public API: `isAvailable`, `captureCard`, `scanImage`, and `ScanErrorCode`), `index.ts` (`registerPlugin`), `web.ts` (fallback: `isAvailable()` false, everything else rejects `UNAVAILABLE`). `dist/` is generated and git-ignored.
- **iOS** — two SwiftPM packages, deliberately separate. The root package/product is named `TimecardScanner` (lower-case "c") because `cap sync` derives it from the npm name; the plugin id stays `TimeCardScanner`:
  - `ios/Core/` (`TimeCardScannerCore`, Swift 6.2, **no Capacitor dependency** so `swift test` and Mac tools build without it). `TimeCardScanner.scan(_:)` calls Apple's `RecognizeDocumentsRequest` (`AppleTable`, the only Vision call), then `AppleTableCleanup`, `Charset` (misread→digit mapping, must stay in sync with `Charset.kt`), `CheckpointCheck`, `ScanFlag`. Everything is `@available(iOS 26, macOS 26)`; the declared minimum is iOS 17, the host's, so the plugin can link it.
  - Root `Package.swift` + `ios/Sources/TimeCardScannerPlugin/`: the `CAPPlugin` bridge and `DocumentCameraPresenter` (`VNDocumentCameraViewController`). Compiles for the host's iOS 17 target and guards with `#available(iOS 26)`; below that it rejects `UNAVAILABLE`.
- **Android** — one Gradle module (Capacitor includes a plugin's `android/` as a single project) built from two source trees by `android/build.gradle`:
  - `android/core/src/main` — the reader: **pure Kotlin, no Android/Capacitor imports** (an Android import would break the JVM-style unit tests). `TimeCardScanner.scan(gray, w, h, symbols)` finds rows from printed checkpoint numerals (`RowAnchors`), column dividers (`ColumnFinder`), assigns cells, then reads handwriting with a small digit CNN (`DigitNet` + `GlyphReader`, weights in `resources/digits.f32`) cross-checked against ML Kit text recognition. `Charset`/`Metrics` are ports of the Swift originals.
  - `android/src/main` — the Capacitor bridge: `TimeCardScannerPlugin`, `ImageScanner` (decode upright, ML Kit OCR, hand to core), `DocumentCapture` (Google Play services document scanner; `isAvailable()` is true only once that module is downloaded).
  - Core tests (`android/core/src/test`) are compiled into this module's `testDebugUnitTest`; `golden.json` holds digit-net parity cases against the Python reference in the bench repo.

Whole-card photos (a scan of the entire card, not a table crop): the table is found by itself. iOS `AppleTable.detectBest` retries a missing/tiny table or a tall photo (height >= 2.6x width) on wider padded canvases, because Apple's detector often finds nothing in a tall narrow image (which canvas works is erratic; tuned on 3 shots of one card); `AppleTableCleanup.withoutEmptySliverColumn` drops an empty sliver column (flag `droppedSliverColumn`, table level). Android `RowAnchorFinder` caps the numeral band at 0.10 of the image width so a handwritten rider number cannot bridge it into the writing. `scanImage({ path, region })` (`ScanRegion` in both cores, same rules) reads only a user-drawn box when that fails; the saved photo stays the whole card.

Known limits of the whole-card flow: the box screen is native on both platforms (`TableBoxViewController`, `TableBoxActivity`; strings are `timecard_scanner_box_*` Android resources and the host's `Localizable.strings` on iOS). The capture slot (iOS `SingleSlot`, Android `CaptureGate`) is held through the box step; on Android, if the host activity is killed while the box screen is up, the call never settles and the saved photo is orphaned (not handled). The pre-pass that places the box does a full scan first (about 2 s on an iPhone). Carbon copies are not supported: see the bench's `CLAUDE.md` (decision of 2026-11-20).

Contract to preserve: cells contain only `0-9` and `:` (colons are meaningful, never stripped; nothing is interpreted as a time); `rows.length` is the card's printed row count (10/12/15/18); `flagged` is a `[boolean, boolean, boolean]` per row (one per value cell; a row-level doubt flags all three; a breaking change from a single boolean in 0.4.0) and is the main safety net (roughly 18% of filled cells are wrong on iOS, ~4% on Android, in-sample). There is deliberately no review screen in the plugin.

## Gradle versions

The wrapper here is **Gradle 8.14.3 with AGP 8.13.0**, because AGP 8.13 breaks on Gradle 9.6. This only affects standalone builds from this repo. A host app ignores this folder's wrapper, `settings.gradle` and `gradle.properties` and supplies its own; since 2026-11-19 the bench and the host app use Gradle 8.14.3 / AGP 8.13.0 too (matching Capacitor 8.6.0), built with Temurin 21 (Gradle 8.14.3 cannot run on Java 25). `build.gradle` still carries its AGP 9 guards (the `kotlin` vs `java` source-set check and the conditional `kotlin-android` apply) for the day the plugin, bench and host move to AGP 9 together (when Play requires targetSdk 37 and Capacitor moves); nothing builds them now. After touching `android/build.gradle`, build it in the bench (`cd android && ./gradlew :core:test :timecard-scanner:assembleDebug` there) and run `npm run verify:android` here.

## Conventions

- Port-sync: `Charset.swift` ↔ `Charset.kt` and Swift `Metrics` (in the bench) ↔ `Metrics.kt` follow the same rules; change both together.
- Bump `version` in `package.json` when behavior changes.
- **Hot loops in the Swift core are `while` loops over unsafe buffers, on purpose** (`GlyphReader` blur/rank filter/resize, `DigitNet` layers). A host's Debug run builds this package with `-Onone`, where `for i in 0..<n` and `x.indices` resolve through the runtime on every step: the digit reader took 45 s per card that way (0.4.0) and 3 s with plain loops (0.4.1), identical output. Time it in Debug after touching those loops (`TIMECARD_SCANNER_TIMING=1` prints per-phase timings from the `scan` CLI; the same line goes to OSLog, category `timing`).
- Comments citing `training/` or `DevTools/` refer to the bench repo; they are provenance only.
- The package is `private`/`UNLICENSED`; the npm `files` list in `package.json` controls what ships (check with `npm pack --dry-run`; keep tests, wrapper and build output out).
