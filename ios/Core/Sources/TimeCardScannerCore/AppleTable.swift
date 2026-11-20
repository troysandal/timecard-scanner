import CoreGraphics
import Foundation
import OSLog
import Vision

private let log = Logger(subsystem: "TimeCardScanner", category: "scan")

/// Vision's `RecognizeDocumentsRequest` table detector: structure *and* text
/// in one call. This is the only place the scan touches Vision.
@available(iOS 26, macOS 26, *)
enum AppleTable {
    struct Detection {
        var rows: [AppleRow]
        /// How many tables Vision found; the one with the most rows is used.
        var tableCount: Int
    }

    /// `nil` when Vision found no table.
    static func detect(in image: CGImage) async throws -> Detection? {
        let observations = try await RecognizeDocumentsRequest().perform(on: image)
        let tables = observations.flatMap { $0.document.tables }
        guard let table = tables.max(by: { $0.rows.count < $1.rows.count }) else { return nil }

        let rows = table.rows.map { row in
            let texts = row.map { $0.content.text.transcript.trimmingCharacters(in: .whitespacesAndNewlines) }
            log.debug("raw row: \(texts, privacy: .private)")
            return AppleRow(texts: texts, cells: row.map { pixelRect(of: $0.content.boundingRegion, in: image) })
        }
        return Detection(rows: rows, tableCount: tables.count)
    }

    /// Bounding box of a Vision region in top-left-origin pixel coordinates.
    static func pixelRect(of region: NormalizedRegion, in image: CGImage) -> CGRect {
        let points = region.normalizedPoints
        guard !points.isEmpty else { return .zero }
        let xs = points.map { CGFloat($0.x) }
        let ys = points.map { CGFloat($0.y) }
        let minX = xs.min() ?? 0, maxX = xs.max() ?? 0
        let minY = ys.min() ?? 0, maxY = ys.max() ?? 0
        // normalizedPoints use Vision's bottom-left origin; flip to top-left.
        let w = CGFloat(image.width), h = CGFloat(image.height)
        return CGRect(x: minX * w, y: (1 - maxY) * h, width: (maxX - minX) * w, height: (maxY - minY) * h)
    }

    // MARK: - tall photos

    /// One detection with its cleanup and how self-consistent it is.
    struct Attempt {
        var detection: Detection
        var cleaned: (rows: [CleanRow], flags: [ScanFlag])
        /// Rows whose printed numeral is the row's position (1, 2, 3, ...).
        var matching: Int
        /// Rows that came back with the wrong number of cells.
        var malformed: Int
        var score: Int { matching - 2 * malformed }
        /// Plausible whole table: at least 6 rows, most numerals in sequence, no row with the wrong cell count.
        var isGood: Bool { cleaned.rows.count >= 6 && malformed == 0 && matching * 2 >= cleaned.rows.count }
    }

    /// Canvas widths (as a multiple of the photo's height) and the grey level of the margin, tried in this order
    /// when the photo as shot gives no table or an inconsistent one. A whole card photographed by the document
    /// scanner is tall and narrow (about 1:3), and Apple's detector then often finds nothing; the same photo on a
    /// wider canvas reads like a table-only crop. Which canvas works is erratic (a different width or margin
    /// colour fails), so a short ladder is tried and the first self-consistent result wins.
    static let canvasLadder: [(width: CGFloat, gray: UInt8)] = [(0.8, 128), (0.6, 255), (0.7, 30), (0.5, 255), (1.0, 128)]

    /// Height / width from which a photo counts as tall (table-only photos are about 2.2 at most; a whole card 3+).
    static let tallRatio: CGFloat = 2.6

    static func attempt(in image: CGImage) async throws -> Attempt? {
        guard let detection = try await detect(in: image) else { return nil }
        let cleaned = AppleTableCleanup.clean(detection.rows)
        let matching = cleaned.rows.enumerated().filter { $0.element.checkpoint == $0.offset + 1 }.count
        let malformed = cleaned.flags.filter { $0.kind == .extraColumns || $0.kind == .malformedRow }.count
        return Attempt(detection: detection, cleaned: cleaned, matching: matching, malformed: malformed)
    }

    /// The photo as shot; if that is not a good table and the photo is narrower than a canvas of the ladder,
    /// the best of the padded canvases. Cell rects are always in the original photo's pixel space.
    static func detectBest(in image: CGImage) async throws -> Attempt? {
        var best = try await attempt(in: image)
        if best?.isGood == true { return best }
        // Only a missing or tiny table, or a tall photo (a whole card), is retried: a table-only photo that read
        // as a table keeps its result exactly as before, whatever its flags.
        let tall = CGFloat(image.height) >= tallRatio * CGFloat(image.width)
        guard best == nil || best!.cleaned.rows.count < 6 || tall else { return best }
        for step in canvasLadder {
            let width = Int((CGFloat(image.height) * step.width).rounded())
            guard width > image.width, let padded = padded(image, toWidth: width, gray: step.gray) else { continue }
            guard var candidate = try await attempt(in: padded.image) else { continue }
            candidate.detection.rows = candidate.detection.rows.map { row in
                AppleRow(texts: row.texts, cells: row.cells.map { $0.isEmpty ? $0 : $0.offsetBy(dx: -padded.offset, dy: 0) })
            }
            candidate.cleaned = AppleTableCleanup.clean(candidate.detection.rows)
            if best == nil || candidate.score > best!.score { best = candidate }
            if candidate.isGood { break }
        }
        return best
    }

    /// The photo centred on a wider canvas of one grey level. `offset` is the left margin in pixels.
    static func padded(_ image: CGImage, toWidth width: Int, gray: UInt8) -> (image: CGImage, offset: CGFloat)? {
        let offset = (width - image.width) / 2
        guard let ctx = CGContext(
            data: nil, width: width, height: image.height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }
        let level = CGFloat(gray) / 255
        ctx.setFillColor(red: level, green: level, blue: level, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: image.height))
        ctx.draw(image, in: CGRect(x: offset, y: 0, width: image.width, height: image.height))
        guard let out = ctx.makeImage() else { return nil }
        return (out, CGFloat(offset))
    }
}
