import CoreGraphics
import Foundation

/// A digit read offered for a cell Vision left blank. A suggestion is never a value: it is never written into
/// `TableScan.rows`, the cell stays flagged (`ScanFlag.Kind.inkNotRead`), and the host should only use it after
/// the user accepts it.
public struct DigitSuggestion: Sendable, Codable, Equatable {
    public var row: Int
    public var column: Int
    /// Over `[0-9:]`, like every cell.
    public var text: String
    /// The digit model's lowest softmax confidence over the glyphs it read (0...1).
    public var confidence: Float

    public init(row: Int, column: Int, text: String, confidence: Float) {
        self.row = row
        self.column = column
        self.text = text
        self.confidence = confidence
    }
}

/// Suggests a value for cells that hold ink Vision returned blank, with the same digit model Android uses
/// (`GlyphReader.swift`, a port of `GlyphReader.kt`). It runs only on cells already flagged `inkNotRead`, so it
/// can never change or add to what Vision read.
///
/// The confidence threshold was fixed before scoring (it is Android's `SURE`), not tuned: on the 8 sharp carbon
/// shots the model at that confidence was right for 44 of the 62 cells it suggested (the bench's `RESULTS.md`).
public enum DigitSuggester {
    /// Suggest only reads at least this confident.
    public static let minConfidence: Float = 0.9
    /// Faint ink peaks at 0.26-0.37 on the carbon cards and dark ink above 0.9; this is below the faint cards and
    /// above noise. A fixed gate, kept out of tuning.
    static let peakMin: Float = 0.25
    /// Stroke floor for the reader: the shipped Android value (faint strokes stay whole).
    static let inkFloor: Float = 0.14
    /// Margin kept off each side of the cell crop, as a fraction of the row height (Android's `CROP_INSET`).
    static let cropInset = 0.055

    /// A suggestion for every flagged cell where the model reads something confidently.
    public static func suggestions(gray: InkCheck.Gray, cells: [[CGRect]], flags: [ScanFlag]) -> [DigitSuggestion] {
        guard let net = DigitNet.shared else { return [] }
        let reader = GlyphReader(net: net, peakMin: peakMin, inkFloor: inkFloor)
        var out: [DigitSuggestion] = []
        for f in flags where f.kind == .inkNotRead {
            guard let r = f.row, let c = f.column, r < cells.count, c < cells[r].count else { continue }
            let read = readCell(gray, cells[r][c], reader)
            if !read.text.isEmpty && read.confidence >= minConfidence {
                out.append(DigitSuggestion(row: r, column: c, text: read.text, confidence: read.confidence))
            }
        }
        return out
    }

    static func readCell(_ g: InkCheck.Gray, _ rect: CGRect, _ reader: GlyphReader) -> GlyphRead {
        let inset = Int((cropInset * Double(rect.height)).rounded())
        let x0 = min(max(Int(rect.minX) + inset, 0), g.width - 2)
        let x1 = min(max(Int(rect.maxX) - inset, x0 + 1), g.width)
        let y0 = min(max(Int(rect.minY) + inset, 0), g.height - 2)
        let y1 = min(max(Int(rect.maxY) - inset, y0 + 1), g.height)
        let w = x1 - x0, h = y1 - y0
        var crop = [Float](repeating: 0, count: w * h)
        for y in 0..<h { for x in 0..<w { crop[y * w + x] = Float(g.pixels[(y0 + y) * g.width + x0 + x]) } }
        return reader.read(crop, w, h)
    }

    /// For tooling (the bench's Swift-vs-Kotlin parity check): the model's raw read of a gray crop with no gate.
    public static func readCrop(pixels: [UInt8], width: Int, height: Int) -> (text: String, confidence: Float, peak: Float)? {
        guard let net = DigitNet.shared else { return nil }
        let r = GlyphReader(net: net, peakMin: 0, inkFloor: inkFloor).read(pixels.map { Float($0) }, width, height)
        return (r.text, r.confidence, r.peak)
    }
}
