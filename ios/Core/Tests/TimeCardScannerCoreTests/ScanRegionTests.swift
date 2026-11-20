import CoreGraphics
import Testing

@testable import TimeCardScannerCore

struct ScanRegionTests {
    @Test func aBoxInsideThePhotoIsAccepted() throws {
        let r = try #require(ScanRegion(x: 0.1, y: 0.2, width: 0.8, height: 0.6))
        #expect(r.pixelRect(width: 1000, height: 3000) == CGRect(x: 100, y: 600, width: 800, height: 1800))
    }

    @Test func rubbishIsRejected() {
        #expect(ScanRegion(x: .nan, y: 0, width: 1, height: 1) == nil)
        #expect(ScanRegion(x: -0.2, y: 0, width: 0.5, height: 0.5) == nil)
        #expect(ScanRegion(x: 0.6, y: 0, width: 0.6, height: 0.5) == nil) // leaves the photo
        #expect(ScanRegion(x: 0, y: 0, width: 0.02, height: 0.5) == nil) // too thin
        #expect(ScanRegion(x: 0, y: 0, width: 0, height: 0) == nil)
    }

    @Test func roundingNoiseAtTheEdgesIsClamped() throws {
        let r = try #require(ScanRegion(x: -0.0004, y: 0.5, width: 1.0008, height: 0.5004))
        #expect(r.pixelRect(width: 400, height: 800) == CGRect(x: 0, y: 400, width: 400, height: 400))
    }

    @Test func fractionsRoundOutwardSoNothingIsCutOff() throws {
        let r = try #require(ScanRegion(x: 0.333, y: 0.333, width: 0.334, height: 0.334))
        let rect = r.pixelRect(width: 100, height: 100)
        #expect(rect.minX == 33 && rect.maxX == 67)
    }

    @Test func theStartingBoxCoversTheTablePlusItsNumeralColumnAndCaptions() throws {
        // 3 value cells per row, 2 rows, in a 1000 x 3000 photo
        let cells = (0..<2).map { r in (0..<3).map { c in CGRect(x: 300 + c * 200, y: 1000 + r * 100, width: 200, height: 100) } }
        let box = try #require(ScanRegion.enclosing(cells: cells, imageWidth: 1000, imageHeight: 3000))
        let px = box.pixelRect(width: 1000, height: 3000)
        #expect(px.minX < 300 - 80)          // room for the Ck column (0.20 of 600 = 120 px) and a margin
        #expect(px.maxX >= 900)
        #expect(px.minY < 1000 - 100)        // the caption row above
        #expect(px.maxY >= 1200)
    }

    @Test func theStartingBoxStaysInsideThePhotoAndNeedsGeometry() throws {
        let edge = [[CGRect(x: 0, y: 0, width: 1000, height: 3000)]]
        let box = try #require(ScanRegion.enclosing(cells: edge, imageWidth: 1000, imageHeight: 3000))
        #expect(box.x == 0 && box.y == 0 && box.width == 1 && box.height == 1)
        #expect(ScanRegion.enclosing(cells: [[.zero, .zero]], imageWidth: 1000, imageHeight: 3000) == nil)
    }
}
