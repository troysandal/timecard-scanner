import CoreGraphics
import Foundation

/// "Is there handwriting in this cell?" from pixels alone, no OCR and no model.
/// Used to flag cells Vision returned blank although they hold ink, so the host's
/// review screen can ask the user to fill them in. It never reads or fills a value.
///
/// A port of `InkDetector.kt` (plugin/android/core): keep the two in sync. Ink is
/// pixels clearly darker than the cell's own paper level (its median); printed rules
/// that leak into the crop are erased first (long straight runs, then any pixel row or
/// column that is mostly ink). The darkness bar is relative to the card: faint ink
/// (a carbon copy) is only 35-65 grey levels darker than the paper, and a fixed bar of
/// 45 misses it.
public enum InkCheck {
    /// Ink fraction at or above which a blank cell is flagged. Same as Android's `INK_MIN`.
    public static let inkMin = 0.012
    /// Margin kept off each side of a cell, as a fraction of the row height. Same as Android's `INK_INSET`.
    public static let insetFraction = 0.07
    /// The darkness bar for dark ink; a faint card lowers it ([delta(forContrast:)]).
    public static let defaultDelta = 45

    /// 8-bit grayscale pixels, row-major, 0 = black, top-left origin.
    public struct Gray: Sendable {
        public let pixels: [UInt8]
        public let width: Int
        public let height: Int
        public init(pixels: [UInt8], width: Int, height: Int) {
            self.pixels = pixels
            self.width = width
            self.height = height
        }
    }

    public static func gray(from image: CGImage) -> Gray? {
        let w = image.width, h = image.height
        guard w > 0, h > 0 else { return nil }
        var pixels = [UInt8](repeating: 255, count: w * h)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let ctx = CGContext(
                data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return false }
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return drawn ? Gray(pixels: pixels, width: w, height: h) : nil
    }

    // MARK: - one cell

    private static func region(_ g: Gray, _ rect: CGRect, inset: Int) -> (x0: Int, y0: Int, w: Int, h: Int)? {
        let x0 = min(max(Int(rect.minX) + inset, 0), g.width)
        let x1 = min(max(Int(rect.maxX) - inset, x0), g.width)
        let y0 = min(max(Int(rect.minY) + inset, 0), g.height)
        let y1 = min(max(Int(rect.maxY) - inset, y0), g.height)
        let w = x1 - x0, h = y1 - y0
        return (w < 4 || h < 4) ? nil : (x0, y0, w, h)
    }

    private static func histogram(_ g: Gray, _ r: (x0: Int, y0: Int, w: Int, h: Int)) -> [Int] {
        var hist = [Int](repeating: 0, count: 256)
        for y in r.y0..<(r.y0 + r.h) {
            let base = y * g.width
            for x in r.x0..<(r.x0 + r.w) { hist[Int(g.pixels[base + x])] += 1 }
        }
        return hist
    }

    private static func paperLevel(_ hist: [Int], count: Int) -> Int {
        var acc = 0
        for v in 0...255 {
            acc += hist[v]
            if acc * 2 >= count { return v }
        }
        return 255
    }

    /// How much darker than its paper the cell's darkest 0.5% of pixels is (0...255). Measured on a cell
    /// that holds writing, it says how strong the ink is on this card.
    public static func contrast(_ g: Gray, rect: CGRect, inset: Int) -> Int {
        guard let r = region(g, rect, inset: inset) else { return 0 }
        let hist = histogram(g, r)
        let count = r.w * r.h
        let paper = paperLevel(hist, count: count)
        var acc = 0
        var dark = 255
        let target = max(1, count / 200)
        for v in 0...255 {
            acc += hist[v]
            if acc >= target { dark = v; break }
        }
        return max(0, paper - dark)
    }

    /// The darkness bar for a card whose writing is `contrast` grey levels darker than the paper (median over
    /// cells that were read). 0.3 of it, but never above the dark-ink bar of 45 and never below 10.
    public static func delta(forContrast contrast: Int) -> Int {
        min(defaultDelta, max(10, Int((Double(contrast) * 0.3).rounded())))
    }

    /// Fraction (0...1) of the inset cell that is ink after rule removal. `delta` is how much darker than the
    /// paper a pixel must be to count.
    public static func score(_ g: Gray, rect: CGRect, inset: Int, delta: Int) -> Double {
        guard let r = region(g, rect, inset: inset) else { return 0 }
        let w = r.w, h = r.h
        let hist = histogram(g, r)
        let limit = paperLevel(hist, count: w * h) - delta

        var ink = [Bool](repeating: false, count: w * h)
        var rowInk = [Int](repeating: 0, count: h)
        var colInk = [Int](repeating: 0, count: w)
        for y in 0..<h {
            let base = (r.y0 + y) * g.width + r.x0
            for x in 0..<w where Int(g.pixels[base + x]) < limit {
                ink[y * w + x] = true
                rowInk[y] += 1
                colInk[x] += 1
            }
        }
        eraseRuleRuns(&ink, w, h)
        var count = 0
        for y in 0..<h {
            if Double(rowInk[y]) > Double(w) * 0.7 { continue } // a horizontal rule, not writing
            for x in 0..<w where ink[y * w + x] && Double(colInk[x]) <= Double(h) * 0.7 { count += 1 }
        }
        return Double(count) / Double(w * h)
    }

    /// Clears long straight runs of ink, which are printed rules and not writing (see InkDetector.kt).
    private static func eraseRuleRuns(_ ink: inout [Bool], _ w: Int, _ h: Int) {
        var kill = [Bool](repeating: false, count: w * h)
        let margin = max(2, Int((Double(h) * 0.03).rounded()))
        let edge = margin
        for y in 0..<h {
            var x = 0
            while x < w {
                if !ink[y * w + x] { x += 1; continue }
                var e = x
                while e < w && ink[y * w + e] { e += 1 }
                let atEdge = x <= edge || e >= w - edge
                if Double(e - x) >= 0.45 * Double(w) || (atEdge && Double(e - x) >= 0.30 * Double(w)) {
                    for yy in max(0, y - margin)...min(h - 1, y + margin) {
                        for xx in x..<e { kill[yy * w + xx] = true }
                    }
                }
                x = e
            }
        }
        for x in 0..<w {
            var y = 0
            while y < h {
                if !ink[y * w + x] { y += 1; continue }
                var e = y
                while e < h && ink[e * w + x] { e += 1 }
                let atEdge = y <= edge || e >= h - edge
                // a column divider that leans into the cell stays close to the cell's side
                let bySide = Double(x) <= 0.04 * Double(w) || Double(x) >= 0.96 * Double(w)
                if Double(e - y) >= 0.90 * Double(h) || (atEdge && Double(e - y) >= 0.50 * Double(h)) || (bySide && Double(e - y) >= 0.30 * Double(h)) {
                    for xx in max(0, x - margin)...min(w - 1, x + margin) {
                        for yy in y..<e { kill[yy * w + xx] = true }
                    }
                }
                y = e
            }
        }
        for i in kill.indices where kill[i] { ink[i] = false }
    }

    // MARK: - a whole scan

    /// The darkness bar for this card: from the cells Vision read, since they hold writing.
    /// Falls back to the dark-ink bar when fewer than 4 cells were read.
    public static func cardDelta(_ g: Gray, cells: [[CGRect]], rows: [[String]]) -> Int {
        var contrasts: [Int] = []
        for r in rows.indices where r < cells.count {
            for c in rows[r].indices where c < cells[r].count && !rows[r][c].isEmpty {
                let inset = Int((Double(cells[r][c].height) * insetFraction).rounded())
                contrasts.append(contrast(g, rect: cells[r][c], inset: inset))
            }
        }
        guard contrasts.count >= 4 else { return defaultDelta }
        contrasts.sort()
        return delta(forContrast: contrasts[contrasts.count / 2])
    }

    /// A flag for every cell Vision returned blank that holds ink.
    public static func flags(gray g: Gray, cells: [[CGRect]], rows: [[String]]) -> [ScanFlag] {
        let delta = cardDelta(g, cells: cells, rows: rows)
        var flags: [ScanFlag] = []
        for r in rows.indices where r < cells.count {
            for c in rows[r].indices where c < cells[r].count && rows[r][c].isEmpty {
                let inset = Int((Double(cells[r][c].height) * insetFraction).rounded())
                if score(g, rect: cells[r][c], inset: inset, delta: delta) >= inkMin {
                    flags.append(ScanFlag(kind: .inkNotRead, row: r, column: c, detail: "ink in the cell but nothing read"))
                }
            }
        }
        return flags
    }
}
