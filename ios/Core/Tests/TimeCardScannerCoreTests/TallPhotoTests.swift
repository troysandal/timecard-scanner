import CoreGraphics
import Testing

@testable import TimeCardScannerCore

struct TallPhotoTests {
    private func image(width: Int, height: Int) -> CGImage {
        let ctx = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        )!
        ctx.setFillColor(red: 0, green: 0, blue: 0, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        return ctx.makeImage()!
    }

    @Test func paddingCentresThePhotoOnAWiderCanvas() throws {
        let (padded, offset) = try #require(AppleTable.padded(image(width: 100, height: 300), toWidth: 240, gray: 255))
        #expect(padded.width == 240 && padded.height == 300)
        #expect(offset == 70)
    }

    private func attempt(checkpoints: [Int?], malformed: Int = 0) -> AppleTable.Attempt {
        let rows = checkpoints.map { CleanRow(texts: ["", "", ""], cells: [], checkpoint: $0, sourceIndex: nil) }
        let flags = (0..<malformed).map { ScanFlag(kind: .extraColumns, row: $0, detail: "") }
        return AppleTable.Attempt(
            detection: .init(rows: [], tableCount: 1), cleaned: (rows, flags),
            matching: rows.enumerated().filter { $0.element.checkpoint == $0.offset + 1 }.count, malformed: malformed
        )
    }

    @Test func aTableWhoseNumeralsFollowTheRowsIsGood() {
        #expect(attempt(checkpoints: Array(1...18).map { Optional($0) }).isGood)
        // numerals misread or missing on a few rows are fine
        #expect(attempt(checkpoints: [nil, 2, nil, 4, 5, nil, nil, nil, 9, 10, 11, 12]).isGood)
    }

    @Test func aMalformedOrShortOrOutOfSequenceTableIsNotGood() {
        #expect(!attempt(checkpoints: Array(1...18).map { Optional($0) }, malformed: 1).isGood)
        #expect(!attempt(checkpoints: [1, 2, 3]).isGood)
        #expect(!attempt(checkpoints: Array(repeating: nil, count: 12)).isGood)
    }
}
