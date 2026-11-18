import CoreGraphics
import Testing

@testable import TimeCardScannerCore

/// A paper-white image with dark rectangles ("writing") at known places.
private func page(width: Int = 400, height: Int = 200, paper: UInt8 = 220, marks: [(CGRect, UInt8)]) -> InkCheck.Gray {
    var px = [UInt8](repeating: paper, count: width * height)
    for (rect, level) in marks {
        for y in Int(rect.minY)..<Int(rect.maxY) { for x in Int(rect.minX)..<Int(rect.maxX) { px[y * width + x] = level } }
    }
    return InkCheck.Gray(pixels: px, width: width, height: height)
}

struct InkCheckTests {
    private let cell = CGRect(x: 0, y: 0, width: 200, height: 100)

    @Test func writingScoresAndPaperDoesNot() {
        let g = page(marks: [(CGRect(x: 80, y: 30, width: 12, height: 40), 60)])
        #expect(InkCheck.score(g, rect: cell, inset: 7, delta: 45) > InkCheck.inkMin)
        #expect(InkCheck.score(page(marks: []), rect: cell, inset: 7, delta: 45) == 0)
    }

    @Test func aPrintedRuleAcrossTheCellIsNotInk() {
        let g = page(marks: [(CGRect(x: 0, y: 90, width: 200, height: 3), 60)]) // runs the full width, near the bottom edge
        #expect(InkCheck.score(g, rect: cell, inset: 7, delta: 45) < InkCheck.inkMin)
    }

    /// Faint carbon ink is only ~35 grey levels darker than the paper: invisible at the dark-ink bar of 45.
    @Test func faintInkNeedsTheRelativeBar() {
        let g = page(marks: [(CGRect(x: 80, y: 30, width: 12, height: 40), 185)])
        #expect(InkCheck.score(g, rect: cell, inset: 7, delta: 45) == 0)
        #expect(InkCheck.score(g, rect: cell, inset: 7, delta: 20) > InkCheck.inkMin)
    }

    @Test func theBarFollowsTheCardsInkStrength() {
        #expect(InkCheck.delta(forContrast: 200) == 45) // dark pen: capped at the old fixed bar
        #expect(InkCheck.delta(forContrast: 66) == 20)  // faint carbon
        #expect(InkCheck.delta(forContrast: 10) == 10)  // never below 10
    }

    @Test func aBlankCellWithInkIsFlaggedButAReadOrEmptyCellIsNot() {
        // three cells side by side: read, blank with ink, blank without
        let g = page(width: 600, marks: [
            (CGRect(x: 80, y: 30, width: 12, height: 40), 60),   // cell 0: written, Vision read it
            (CGRect(x: 280, y: 30, width: 12, height: 40), 60),  // cell 1: written, Vision returned blank
        ])
        let cells = [[CGRect(x: 0, y: 0, width: 200, height: 100), CGRect(x: 200, y: 0, width: 200, height: 100), CGRect(x: 400, y: 0, width: 200, height: 100)]]
        let flags = InkCheck.flags(gray: g, cells: cells, rows: [["9", "", ""]])
        #expect(flags.map(\.kind) == [.inkNotRead])
        #expect(flags.first?.row == 0 && flags.first?.column == 1)
    }
}
