import CoreGraphics
import Testing

@testable import TimeCardScannerCore

struct DigitSuggesterTests {
    /// A page of paper holding a dark "1" in the second of three cells, as it would sit in a scan.
    private func page(stroke: UInt8 = 40) -> (InkCheck.Gray, [[CGRect]]) {
        let width = 600, height = 100
        var px = [UInt8](repeating: 225, count: width * height)
        for y in 15..<85 { for x in 276..<284 { px[y * width + x] = stroke } }
        let cells = [[CGRect(x: 0, y: 0, width: 200, height: 100), CGRect(x: 200, y: 0, width: 200, height: 100), CGRect(x: 400, y: 0, width: 200, height: 100)]]
        return (InkCheck.Gray(pixels: px, width: width, height: height), cells)
    }

    @Test func aFlaggedCellGetsASuggestion() {
        let (g, cells) = page()
        let flags = [ScanFlag(kind: .inkNotRead, row: 0, column: 1, detail: "")]
        let s = DigitSuggester.suggestions(gray: g, cells: cells, flags: flags)
        #expect(s.count == 1)
        #expect(s.first?.text == "1" && s.first?.row == 0 && s.first?.column == 1)
    }

    /// Only cells flagged `inkNotRead` are read: a cell Vision read, or an empty one, is never touched.
    @Test func unflaggedCellsGetNoSuggestion() {
        let (g, cells) = page()
        #expect(DigitSuggester.suggestions(gray: g, cells: cells, flags: []).isEmpty)
        let other = [ScanFlag(kind: .charsetMapped, row: 0, column: 1, detail: "")]
        #expect(DigitSuggester.suggestions(gray: g, cells: cells, flags: other).isEmpty)
    }

    @Test func aFlaggedCellWithNothingReadableGetsNone() {
        let (g, cells) = page()
        let flags = [ScanFlag(kind: .inkNotRead, row: 0, column: 2, detail: "")] // the empty third cell
        #expect(DigitSuggester.suggestions(gray: g, cells: cells, flags: flags).isEmpty)
    }
}
