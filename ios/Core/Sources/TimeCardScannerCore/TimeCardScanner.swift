import CoreGraphics
import Foundation
import OSLog

private let timingLog = Logger(subsystem: "TimeCardScanner", category: "timing")

/// Milliseconds in a `Duration`.
private func ms(_ d: Duration) -> Int { Int(Double(d.components.seconds) * 1000 + Double(d.components.attoseconds) / 1e15) }

/// The result of scanning one card.
public struct TableScan: Sendable, Codable, Equatable {
    /// One entry per table row, each with three value cells (minutes, then the
    /// two others), over `[0-9:]`. `""` for a blank cell, never missing.
    public var rows: [[String]]
    /// Vision's text for the same cells before the character mapping.
    public var rawText: [[String]]
    /// Value-cell rects per row, in pixels of the scanned image, top-left origin.
    public var cells: [[CGRect]]
    /// The printed checkpoint number Vision read for each row, if any.
    public var printedCheckpoints: [Int?]
    public var flags: [ScanFlag]
    /// Digit reads offered for cells Vision left blank that hold ink (those flagged `inkNotRead`). Never part
    /// of `rows`: a suggestion is only for the user to accept.
    public var suggestions: [DigitSuggestion] = []
    public var pipelineVersion: String

    /// Flags attached to `row`.
    public func flags(forRow row: Int) -> [ScanFlag] { flags.filter { $0.row == row } }

    /// Which of the row's three value cells the user should check. A cell's own flags mark that cell; a flag on
    /// the row with no cell (a checkpoint mismatch, a malformed row) marks all three. Mirrors the Kotlin
    /// `TableScan.flaggedCells`.
    public func flaggedCells(forRow row: Int) -> [Bool] {
        var out = [false, false, false]
        for f in flags(forRow: row) {
            if let c = f.column, (0..<3).contains(c) { out[c] = true } else { out = [true, true, true] }
        }
        return out
    }
}

/// S1: Apple's document-table detector, repaired by `AppleTableCleanup`, with
/// recognizer output mapped onto the card's `[0-9:]` alphabet.
@available(iOS 26, macOS 26, *)
public enum TimeCardScanner {
    /// Bump when a change can alter the extracted table.
    public static let pipelineVersion = "S1.4"

    /// Scans an upright photo of the table. Throws only if Vision fails; a
    /// photo with no table comes back with no rows and a `.noTable` flag.
    public static func scan(_ image: CGImage) async throws -> TableScan {
        let clock = ContinuousClock()
        let started = clock.now
        guard let attempt = try await AppleTable.detectBest(in: image) else {
            return TableScan(
                rows: [], rawText: [], cells: [], printedCheckpoints: [],
                flags: [ScanFlag(kind: .noTable, detail: "no table found by RecognizeDocumentsRequest")],
                pipelineVersion: pipelineVersion
            )
        }
        let detection = attempt.detection

        var flags: [ScanFlag] = []
        if detection.tableCount > 1 {
            flags.append(ScanFlag(kind: .multipleTables, detail: "\(detection.tableCount) tables detected; using the largest"))
        }

        let cleaned = attempt.cleaned
        flags += cleaned.flags

        let raw = cleaned.rows.map(\.texts)
        var rows = raw
        for r in rows.indices {
            for c in rows[r].indices {
                let (text, changed) = Charset.normalize(rows[r][c])
                if changed {
                    flags.append(ScanFlag(kind: .charsetMapped, row: r, column: c, detail: "mapped \"\(rows[r][c])\" → \"\(text)\""))
                }
                rows[r][c] = text
            }
        }

        let printed = cleaned.rows.map(\.checkpoint)
        flags += CheckpointCheck.flags(for: printed)

        // Cells Vision left blank although they hold ink (it refuses bare strokes such as a lone `11`).
        let cells = cleaned.rows.map(\.cells)
        let afterVision = clock.now
        var suggestions: [DigitSuggestion] = []
        var inkMs = 0, suggestMs = 0, inkFlagCount = 0
        if let gray = InkCheck.gray(from: image) {
            let t = clock.now
            let inkFlags = InkCheck.flags(gray: gray, cells: cells, rows: rows)
            flags += inkFlags
            inkFlagCount = inkFlags.count
            inkMs = ms(clock.now - t)
            // ...and a digit read offered for each, for the user to accept. Never written into `rows`.
            let t2 = clock.now
            suggestions = DigitSuggester.suggestions(gray: gray, cells: cells, flags: inkFlags)
            suggestMs = ms(clock.now - t2)
        }
        let visionMs = ms(afterVision - started)
        let message = "scan timing: vision+cleanup \(visionMs) ms, ink check \(inkMs) ms (\(inkFlagCount) cells flagged), suggestions \(suggestMs) ms (\(suggestions.count) offered), total \(ms(clock.now - started)) ms"
        timingLog.notice("\(message, privacy: .public)")
        if ProcessInfo.processInfo.environment["TIMECARD_SCANNER_TIMING"] != nil {
            FileHandle.standardError.write(Data((message + "\n").utf8))
        }

        return TableScan(
            rows: rows, rawText: raw, cells: cells,
            printedCheckpoints: printed, flags: flags, suggestions: suggestions, pipelineVersion: pipelineVersion
        )
    }
}
