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
  - `ios/Core/` (`TimeCardScannerCore`, Swift 6.2, **no Capacitor dependency** so `swift test` and Mac tools build without it). `TimeCardScanner.scan(_:)` calls Apple's `RecognizeDocumentsRequest` (`AppleTable`, the only Vision call), then `AppleTableCleanup`, `Charset` (misread→digit mapping, must stay in sync with `Charset.kt`), `CheckpointCheck`, `ScanFlag`. Everything is `@available(iOS 26, macOS 26)`; the declared minimum is iOS 15 only so the plugin can link it.
  - Root `Package.swift` + `ios/Sources/TimeCardScannerPlugin/`: the `CAPPlugin` bridge and `DocumentCameraPresenter` (`VNDocumentCameraViewController`). Compiles for the host's iOS 15 target and guards with `#available(iOS 26)`; below that it rejects `UNAVAILABLE`.
- **Android** — one Gradle module (Capacitor includes a plugin's `android/` as a single project) built from two source trees by `android/build.gradle`:
  - `android/core/src/main` — the reader: **pure Kotlin, no Android/Capacitor imports** (an Android import would break the JVM-style unit tests). `TimeCardScanner.scan(gray, w, h, symbols)` finds rows from printed checkpoint numerals (`RowAnchors`), column dividers (`ColumnFinder`), assigns cells, then reads handwriting with a small digit CNN (`DigitNet` + `GlyphReader`, weights in `resources/digits.f32`) cross-checked against ML Kit text recognition. `Charset`/`Metrics` are ports of the Swift originals.
  - `android/src/main` — the Capacitor bridge: `TimeCardScannerPlugin`, `ImageScanner` (decode upright, ML Kit OCR, hand to core), `DocumentCapture` (Google Play services document scanner; `isAvailable()` is true only once that module is downloaded).
  - Core tests (`android/core/src/test`) are compiled into this module's `testDebugUnitTest`; `golden.json` holds digit-net parity cases against the Python reference in the bench repo.

Contract to preserve: cells contain only `0-9` and `:` (colons are meaningful, never stripped; nothing is interpreted as a time); `rows.length` is the card's printed row count (10/12/15/18); `flagged` marks rows the scanner doubts and is the main safety net (roughly 18% of filled cells are wrong on iOS, ~4% on Android, in-sample). There is deliberately no review screen in the plugin.

## Gradle versions

The wrapper here is **Gradle 8.14.3 with AGP 8.13.0**, because AGP 8.13 breaks on Gradle 9.6. This only affects standalone builds from this repo. A host app ignores this folder's wrapper, `settings.gradle` and `gradle.properties` and supplies its own (the bench and the host app use Gradle 9.6 / AGP 9.5.1). `build.gradle` must keep working on both AGP generations: the `kotlin` vs `java` source-set guard and the conditional `kotlin-android` apply exist for that. After touching `android/build.gradle`, also build it under AGP 9 via the bench (`cd android && ./gradlew :core:test :timecard-scanner:assembleDebug` there).

## Conventions

- Port-sync: `Charset.swift` ↔ `Charset.kt` and Swift `Metrics` (in the bench) ↔ `Metrics.kt` follow the same rules; change both together.
- Bump `version` in `package.json` when behavior changes.
- Comments citing `training/` or `DevTools/` refer to the bench repo; they are provenance only.
- The package is `private`/`UNLICENSED`; the npm `files` list in `package.json` controls what ships (check with `npm pack --dry-run`; keep tests, wrapper and build output out).
