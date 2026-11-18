import Testing

@testable import TimeCardScannerCore

/// One-for-one ports of Android's GlyphReaderTest (synthetic crops, no rider data), so the two readers are
/// held to the same behaviour.
struct GlyphReaderTests {
    private let reader = GlyphReader(net: DigitNet.shared!)
    private let w = 160
    private let h = 100

    private func paper(_ level: Float = 225) -> [Float] { [Float](repeating: level, count: 160 * 100) }

    private func fill(_ img: inout [Float], _ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int, _ v: Float = 20) {
        for y in y0..<y1 { for x in x0..<x1 { img[y * 160 + x] = v } }
    }

    @Test func blankCellReadsNothing() {
        let r = reader.read(paper(), w, h)
        #expect(r.text == "")
        #expect(r.confidence == 1)
    }

    @Test func aThickVerticalStrokeIsAOne() {
        var img = paper(); fill(&img, 76, 15, 84, 85)
        let r = reader.read(img, w, h)
        #expect(r.text == "1")
        #expect(r.confidence > 0.5)
    }

    @Test func twoStrokesReadAsTwoDigitsLeftToRight() {
        var img = paper(); fill(&img, 50, 15, 58, 85); fill(&img, 100, 15, 108, 85)
        #expect(reader.read(img, w, h).text == "11")
    }

    @Test func printedRulesAreNotWriting() {
        var img = paper(); fill(&img, 0, 0, 4, h); fill(&img, 0, h - 4, w, h)
        #expect(reader.read(img, w, h).text == "")
    }

    @Test func aThickSlantedRuleThroughTheCropIsErased() {
        var img = paper()
        fill(&img, 76, 10, 84, 65)
        for x in 0..<w { fill(&img, x, 78 + x * 8 / w, x + 1, 88 + x * 8 / w) }
        #expect(reader.read(img, w, h).text == "1")
    }

    @Test func aSliverOfTheDividerAtTheCropEdgeIsNotAOne() {
        var img = paper(); fill(&img, 76, 15, 84, 85); fill(&img, w - 2, 5, w, 45)
        #expect(reader.read(img, w, h).text == "1")
    }

    @Test func aWideDigitIsNotCutInTwo() {
        var img = paper(); fill(&img, 50, 22, 112, 78); fill(&img, 58, 30, 104, 70, 225) // a ring wider than tall
        #expect(reader.read(img, w, h).text == "0")
    }

    @Test func twoStackedDotsBetweenDigitsAreAColon() {
        var img = paper(); fill(&img, 40, 15, 48, 85); fill(&img, 76, 32, 84, 40); fill(&img, 76, 60, 84, 68); fill(&img, 112, 15, 120, 85)
        #expect(reader.read(img, w, h).text == "1:1")
    }

    @Test func aSlantedColonWhoseDotsDoNotOverlapIsStillAColon() {
        var img = paper(); fill(&img, 40, 15, 48, 85)
        fill(&img, 80, 32, 88, 40); fill(&img, 72, 60, 80, 68) // upper dot entirely right of the lower one
        fill(&img, 112, 15, 120, 85)
        #expect(reader.read(img, w, h).text == "1:1")
    }

    @Test func aLeadingAndATrailingColonAreKept() {
        var img = paper()
        fill(&img, 20, 32, 28, 40); fill(&img, 18, 60, 26, 68)
        fill(&img, 76, 15, 84, 85)
        fill(&img, 130, 32, 138, 40); fill(&img, 128, 60, 136, 68)
        #expect(reader.read(img, w, h).text == ":1:")
    }

    @Test func aSpeckAtTheCropEdgeDoesNotJoinAColon() {
        var img = paper()
        fill(&img, 40, 15, 48, 85); fill(&img, 76, 32, 84, 40); fill(&img, 76, 60, 84, 68)
        fill(&img, 78, h - 1, 80, h) // what shows of a rule below the colon
        fill(&img, 112, 15, 120, 85)
        #expect(reader.read(img, w, h).text == "1:1")
    }

    @Test func aSingleDotBetweenDigitsIsAColon() {
        var img = paper(); fill(&img, 40, 15, 48, 85); fill(&img, 76, 70, 84, 78); fill(&img, 112, 15, 120, 85)
        #expect(reader.read(img, w, h).text == "1:1")
    }

    @Test func aSingleDotBeforeOrAfterTheDigitsIsNotAColon() {
        var img = paper(); fill(&img, 20, 70, 28, 78); fill(&img, 60, 15, 68, 85); fill(&img, 100, 15, 108, 85); fill(&img, 135, 70, 143, 78)
        #expect(reader.read(img, w, h).text == "11")
    }

    @Test func dotsAloneAreNotAColon() {
        var img = paper(); fill(&img, 76, 22, 84, 30); fill(&img, 76, 60, 84, 68)
        #expect(!reader.read(img, w, h).text.contains(":"))
    }

    @Test func aSliverAtTheLeftEdgeIsDroppedToo() {
        var img = paper(); fill(&img, 76, 15, 84, 85); fill(&img, 0, 30, 2, 95)
        #expect(reader.read(img, w, h).text == "1")
    }

    @Test func aTallOneIsNotErasedAsARule() {
        var img = paper(); fill(&img, 76, 9, 84, 91) // 82% of the crop height
        #expect(reader.read(img, w, h).text == "1")
    }

    @Test func theSameCellReadsTheSameAtOtherResolutions() {
        var img = paper()
        fill(&img, 40, 15, 48, 85); fill(&img, 76, 32, 84, 40); fill(&img, 76, 60, 84, 68); fill(&img, 112, 15, 120, 85)
        fill(&img, 0, h - 5, w, h) // a printed rule along the bottom
        for scale in [0.5, 1.0, 2.0, 3.0] {
            let sw = Int(Double(w) * scale), sh = Int(Double(h) * scale)
            #expect(reader.read(reader.resizeGray(img, w, h, sw, sh), sw, sh).text == "1:1", "at \(scale)x")
        }
    }

    @Test func aStruckThroughValueIsSuspect() {
        var img = paper(); fill(&img, 50, 22, 112, 78); fill(&img, 58, 30, 104, 70, 225)
        #expect(reader.read(img, w, h).suspect == false)
        fill(&img, 20, 47, 140, 52) // a pen line through the middle of the digit
        #expect(reader.read(img, w, h).suspect == true)
    }

    @Test func aRuleBesideTheWritingIsNotSuspect() {
        var img = paper(); fill(&img, 76, 15, 84, 80); fill(&img, 0, 86, w, 92)
        let r = reader.read(img, w, h)
        #expect(r.text == "1")
        #expect(r.suspect == false)
    }

    @Test func aBlobTooWideForOneDigitIsCutAndSuspect() {
        var img = paper()
        fill(&img, 45, 30, 115, 70) // 70 wide, 40 tall: short enough not to look like a printed rule
        fill(&img, 52, 37, 76, 63, 225); fill(&img, 84, 37, 108, 63, 225)
        let r = reader.read(img, w, h)
        #expect(r.text.count >= 2, "read '\(r.text)'")
        #expect(r.suspect == true)
    }

    @Test func aStrokeBrokenInTwoIsNotAColon() {
        var img = paper(); fill(&img, 40, 15, 48, 85)
        fill(&img, 100, 12, 108, 46); fill(&img, 100, 54, 108, 88) // a 1 with a gap, pieces too long to be dots
        #expect(reader.read(img, w, h).text == "11")
    }

    @Test func faintMarksBelowThePeakGateAreIgnored() {
        var img = paper(); fill(&img, 76, 15, 84, 85, 200) // barely darker than the paper
        #expect(reader.read(img, w, h).text == "")
    }

    @Test func otsuSplitsTwoLevels() {
        let a = (0..<1000).map { $0 < 800 ? Float(0.05) : Float(0.9) }
        let t = reader.otsu(a)
        #expect(t > 0.05 && t < 0.9, "otsu was \(t)")
    }
}
