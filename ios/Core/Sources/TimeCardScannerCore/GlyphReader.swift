import Foundation

/// Result of reading one cell with the digit model.
struct GlyphRead: Sendable, Equatable {
    /// Digits, plus ':' for two small stacked dots or a single dot between digits; "" when nothing handwritten was found.
    var text: String
    /// Lowest softmax confidence over the digit glyphs (1.0 when nothing was read; a colon does not lower it).
    var confidence: Float
    /// Ink strength of the strongest stroke (0...1), before the peak gate.
    var peak: Float
    /// The shape gives a reason to distrust the value however confident the digits are (a line erased through
    /// the writing, or a blob cut into several digits).
    var suspect = false
    /// Width of the widest glyph relative to the tallest one in the cell; well above 1 may be touching digits.
    var widest: Float = 0
}

/// Reads a cell crop with `DigitNet`: ink map -> Otsu -> connected components -> drop printed rules -> pair
/// colons -> merge broken strokes -> split wide blobs -> centre each glyph MNIST-style -> classify.
///
/// A port of the shipped path of `GlyphReader.kt` (plugin/android/core), in the same order and with the same
/// constants, so it reads the same as Android to float rounding. KEEP THE TWO IN SYNC. Not ported: the
/// Python-parity options and the diagnostic methods; the options are fixed at the shipped values.
///
/// The thresholds were tuned in-sample on a small corpus; treat results as an upper bound.
struct GlyphReader: Sendable {
    let net: DigitNet
    /// Minimum ink strength of the strongest stroke.
    let peakMin: Float
    /// Lowest ink level (0...1) a pixel may have and still count as writing; the threshold is the larger of Otsu's and this.
    let inkFloor: Float

    private static let normHeight = 140
    private static let splitAspect = 1.5
    private static let splitPitch = 0.8

    init(net: DigitNet, peakMin: Float = 0.7, inkFloor: Float = 0.18) {
        self.net = net
        self.peakMin = peakMin
        self.inkFloor = inkFloor
    }

    /// `gray` is an `h` x `w` crop, row-major, 0 = black ... 255 = white.
    func read(_ gray: [Float], _ w: Int, _ h: Int) -> GlyphRead {
        let nh = Self.normHeight
        if h != nh && h > 0 && w > 0 {
            let nw = max(1, jRound(Double(w) * Double(nh) / Double(h)))
            return readAtScale(resizeGray(gray, w, h, nw, nh), nw, nh)
        }
        return readAtScale(gray, w, h)
    }

    /// Resamples a 0...255 grayscale crop (anti-aliased when shrinking, bilinear when enlarging).
    func resizeGray(_ gray: [Float], _ w: Int, _ h: Int, _ nw: Int, _ nh: Int) -> [Float] {
        let src = gray.map { min(255, max(0, Int($0))) }
        return resizeBilinear(src, w, h, nw, nh).map { Float($0) }
    }

    private func readAtScale(_ gray: [Float], _ w: Int, _ h: Int) -> GlyphRead {
        let ink = inkMap(gray, w, h)
        var ruleRuns: [[Int]] = []
        let glyphs = segment(ink, w, h, &ruleRuns)
        if glyphs.isEmpty { return GlyphRead(text: "", confidence: 1, peak: 0) }
        var peak: Float = 0
        for g in glyphs {
            for yy in 0..<g.h {
                for xx in 0..<g.w where g.mask[yy * g.w + xx] {
                    peak = max(peak, ink[(g.y0 + yy) * w + g.x0 + xx])
                }
            }
        }
        if peak < peakMin { return GlyphRead(text: "", confidence: 1, peak: peak) }

        var out = ""
        var conf: Float = 1
        let tallest = glyphs.map(\.h).max()!
        var suspect = false
        var widest: Float = 0
        for g in glyphs.enumerated().sorted(by: { ($0.element.x0, $0.offset) < ($1.element.x0, $1.offset) }).map(\.element) { // stable, like Kotlin's sortedBy
            if g.colon { out.append(":"); continue }
            widest = max(widest, Float(g.w) / Float(tallest))
            // an erased line through the middle half of this glyph: a strike-through, not a rule beside it
            if ruleRuns.contains(where: { r in
                let y = Double(r[0])
                return y > Double(g.y0) + 0.25 * Double(g.h) && y < Double(g.y1) - 0.25 * Double(g.h)
                    && Double(min(r[2], g.x1) - max(r[1], g.x0)) >= 0.5 * Double(g.w)
            }) { suspect = true }
            let gh = g.h
            let gw = g.w
            let ref = tallest
            let k = Double(gw) > Self.splitAspect * Double(ref) ? max(1, pyRound(Double(gw) / (Self.splitPitch * Double(ref)))) : 1
            if k > 1 { suspect = true } // an even cut is a guess
            for j in 0..<k {
                let xs = pyRound(Double(j) * Double(gw) / Double(k))
                let xe = pyRound(Double(j + 1) * Double(gw) / Double(k))
                let pw = xe - xs
                if pw <= 0 { continue }
                var patch = [Float](repeating: 0, count: gh * pw)
                for yy in 0..<gh {
                    for xx in 0..<pw {
                        let inside = g.mask[yy * gw + xs + xx]
                        patch[yy * pw + xx] = inside ? ink[(g.y0 + yy) * w + g.x0 + xs + xx] : 0
                    }
                }
                guard let img = glyphTo28(patch, pw, gh) else { continue }
                let p = net.probabilities(img)
                var best = 0
                for c in 1..<10 where p[c] > p[best] { best = c }
                out.append(String(best))
                conf = min(conf, p[best])
            }
        }
        return GlyphRead(text: out, confidence: conf, peak: peak, suspect: suspect, widest: widest)
    }

    // MARK: - ink map

    /// Dark strokes on any paper tint, in 0...1: (background - pixel) / background.
    func inkMap(_ g: [Float], _ w: Int, _ h: Int) -> [Float] {
        let closed = rankFilter(rankFilter(g, w, h, 15, true), w, h, 15, false)
        let bg = gaussian(closed, w, h, Double(max(w, h)) / 10.0)
        var out = [Float](repeating: 0, count: w * h)
        for i in 0..<(w * h) { out[i] = min(1, max(0, (bg[i] - g[i]) / max(bg[i], 1))) }
        return out
    }

    private func refl(_ i: Int, _ n: Int) -> Int { // scipy 'reflect': d c b a | a b c d | d c b a
        if n == 1 { return 0 }
        let period = 2 * n
        let j = ((i % period) + period) % period
        return j < n ? j : period - 1 - j
    }

    /// Separable box max/min filter with reflected borders (== a size x size square footprint).
    private func rankFilter(_ a: [Float], _ w: Int, _ h: Int, _ size: Int, _ isMax: Bool) -> [Float] {
        let r = size / 2
        var tmp = [Float](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                var v = a[y * w + refl(x - r, w)]
                for k in (-r + 1)...r { let c = a[y * w + refl(x + k, w)]; v = isMax ? max(v, c) : min(v, c) }
                tmp[y * w + x] = v
            }
        }
        var out = [Float](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                var v = tmp[refl(y - r, h) * w + x]
                for k in (-r + 1)...r { let c = tmp[refl(y + k, h) * w + x]; v = isMax ? max(v, c) : min(v, c) }
                out[y * w + x] = v
            }
        }
        return out
    }

    /// scipy.ndimage.gaussian_filter: truncate 4.0, reflected borders.
    private func gaussian(_ a: [Float], _ w: Int, _ h: Int, _ sigma: Double) -> [Float] {
        let radius = Int(4.0 * sigma + 0.5)
        var kernel = [Double](repeating: 0, count: 2 * radius + 1)
        var total = 0.0
        for i in kernel.indices {
            let v = Foundation.exp(-0.5 * Double(i - radius) * Double(i - radius) / (sigma * sigma))
            kernel[i] = v
            total += v
        }
        for i in kernel.indices { kernel[i] /= total }

        var tmp = [Float](repeating: 0, count: w * h)
        var row = [Float](repeating: 0, count: w + 2 * radius)
        for y in 0..<h {
            for i in row.indices { row[i] = a[y * w + refl(i - radius, w)] }
            for x in 0..<w {
                var s = 0.0 // scipy accumulates in double; intermediate rows are stored as float32
                for k in kernel.indices { s += kernel[k] * Double(row[x + k]) }
                tmp[y * w + x] = Float(s)
            }
        }
        var out = [Float](repeating: 0, count: w * h)
        var col = [Float](repeating: 0, count: h + 2 * radius)
        for x in 0..<w {
            for i in col.indices { col[i] = tmp[refl(i - radius, h) * w + x] }
            for y in 0..<h {
                var s = 0.0
                for k in kernel.indices { s += kernel[k] * Double(col[y + k]) }
                out[y * w + x] = Float(s)
            }
        }
        return out
    }

    // MARK: - threshold

    /// Otsu over a 256-bin histogram, as in training/common.py.
    func otsu(_ a: [Float]) -> Float {
        var lo = Float.greatestFiniteMagnitude
        var hi = -Float.greatestFiniteMagnitude
        for v in a { lo = min(lo, v); hi = max(hi, v) }
        if hi <= lo { return hi }
        let bins = 256
        var hist = [Double](repeating: 0, count: bins)
        let step = Double(hi - lo) / Double(bins)
        for v in a { hist[min(bins - 1, Int(Double(v - lo) / step))] += 1 }
        let mids = (0..<bins).map { Double(lo) + step * (Double($0) + 0.5) }
        var w0 = [Double](repeating: 0, count: bins)
        var cum = [Double](repeating: 0, count: bins)
        var s0 = 0.0, sm = 0.0
        for i in 0..<bins { s0 += hist[i]; sm += hist[i] * mids[i]; w0[i] = s0; cum[i] = sm }
        let total = w0[bins - 1]
        var m1 = [Double](repeating: 0, count: bins)
        var rs = 0.0, rw = 0.0
        for i in stride(from: bins - 1, through: 0, by: -1) { rs += hist[i] * mids[i]; rw += hist[i]; m1[i] = rs / max(rw, 1.0) }
        var best = -1.0
        var bestI = 0
        for i in 0..<(bins - 1) {
            let m0 = cum[i] / max(w0[i], 1.0)
            let v = w0[i] * (total - w0[i]) * (m0 - m1[i + 1]) * (m0 - m1[i + 1])
            if v > best { best = v; bestI = i }
        }
        return Float(mids[bestI])
    }

    // MARK: - segmentation

    private final class Comp {
        var x0: Int, x1: Int, y0: Int, y1: Int, area: Int
        var mask: [Bool]
        let colon: Bool
        init(_ x0: Int, _ x1: Int, _ y0: Int, _ y1: Int, _ area: Int, _ mask: [Bool], colon: Bool = false) {
            self.x0 = x0; self.x1 = x1; self.y0 = y0; self.y1 = y1; self.area = area; self.mask = mask; self.colon = colon
        }
        var w: Int { x1 - x0 }
        var h: Int { y1 - y0 }
    }

    // all relative to the tallest piece in the cell
    private static let colonDotMaxHeight = 0.40
    private static let colonDotMaxWidth = 0.30
    private static let colonMaxSlant = 0.20
    private static let colonMinGap = 0.04
    private static let singleDotMinArea = 0.006
    // relative to the crop height
    private static let speckArea = 0.001
    private static let speckEdgeHeight = 0.03
    private static let ruleMinWidth = 0.45
    private static let ruleMinWidthAtEdge = 0.30
    private static let ruleMinHeight = 0.90
    private static let ruleMargin = 4

    /// Pairs up colons before strokes are merged: two small dots, one above the other with a clear gap,
    /// beside at least one real digit; plus a single dot standing between two digits ("9.26" means ':').
    private func pairColons(_ comps: inout [Comp]) {
        guard let tall = comps.map(\.h).max() else { return }
        let t = Double(tall)
        func dot(_ c: Comp) -> Bool { !c.colon && Double(c.h) <= Self.colonDotMaxHeight * t && Double(c.w) <= Self.colonDotMaxWidth * t }
        while true {
            var best: (Comp, Comp)?
            var bestDx = Self.colonMaxSlant * t
            for a in comps {
                for b in comps {
                    if a === b || !dot(a) || !dot(b) { continue }
                    let gap = b.y0 - a.y1 // a is the upper dot
                    if Double(gap) < Self.colonMinGap * t || b.y1 - a.y0 > tall { continue }
                    let dx = Double(abs((a.x0 + a.x1) - (b.x0 + b.x1))) / 2.0
                    if dx <= bestDx { bestDx = dx; best = (a, b) }
                }
            }
            guard let (a, b) = best else { break }
            let x0 = min(a.x0, b.x0), x1 = max(a.x1, b.x1)
            let mw = x1 - x0
            var m = [Bool](repeating: false, count: mw * (b.y1 - a.y0))
            for c in [a, b] {
                for yy in 0..<c.h { for xx in 0..<c.w where c.mask[yy * c.w + xx] { m[(c.y0 - a.y0 + yy) * mw + (c.x0 - x0 + xx)] = true } }
            }
            let ai = comps.firstIndex(where: { $0 === a })!
            comps[ai] = Comp(x0, x1, a.y0, b.y1, a.area + b.area, m, colon: true)
            comps.removeAll(where: { $0 === b })
        }
        // A single dot standing between two digits is a colon too. It must be clear of both neighbours, or it
        // is a fragment the stroke merge should take.
        for i in comps.indices {
            let d = comps[i]
            if !dot(d) || Double(d.area) < Self.singleDotMinArea * t * t { continue }
            func digit(_ c: Comp) -> Bool { !c.colon && !dot(c) && Double(c.h) >= 0.5 * t }
            if comps.contains(where: { $0 !== d && min($0.x1, d.x1) > max($0.x0, d.x0) }) { continue }
            if !comps.contains(where: { digit($0) && $0.x1 <= d.x0 }) || !comps.contains(where: { digit($0) && $0.x0 >= d.x1 }) { continue }
            comps[i] = Comp(d.x0, d.x1, d.y0, d.y1, d.area, d.mask, colon: true)
        }
    }

    private func segment(_ ink: [Float], _ w: Int, _ h: Int, _ ruleRuns: inout [[Int]]) -> [Comp] {
        let thr = max(otsu(ink), inkFloor)
        var on = [Bool](repeating: false, count: w * h)
        for i in 0..<(w * h) { on[i] = ink[i] > thr }
        let nearRule = eraseRules(&on, w, h, &ruleRuns)
        var labels = [Int](repeating: 0, count: w * h)
        var comps: [Comp] = []
        var next = 0
        var stack = [Int](repeating: 0, count: w * h)
        let dh = Double(h), dw = Double(w)
        for start in 0..<(w * h) {
            if !on[start] || labels[start] != 0 { continue }
            next += 1
            var sp = 0
            stack[sp] = start; sp += 1
            labels[start] = next
            var x0 = w, x1 = 0, y0 = h, y1 = 0
            var pixels: [Int] = []
            while sp > 0 {
                sp -= 1
                let p = stack[sp]
                pixels.append(p)
                let px = p % w, py = p / w
                x0 = min(x0, px); x1 = max(x1, px + 1); y0 = min(y0, py); y1 = max(y1, py + 1)
                for dy in -1...1 {
                    for dx in -1...1 {
                        let nx = px + dx, ny = py + dy
                        if nx < 0 || ny < 0 || nx >= w || ny >= h { continue }
                        let q = ny * w + nx
                        if on[q] && labels[q] == 0 { labels[q] = next; stack[sp] = q; sp += 1 }
                    }
                }
            }
            let cw = x1 - x0, ch = y1 - y0
            var m = [Bool](repeating: false, count: cw * ch)
            for p in pixels { m[(p / w - y0) * cw + (p % w - x0)] = true }
            // printed rules are not handwriting
            if Double(ch) >= 0.85 * dh && Double(cw) <= 0.12 * dw { continue }
            if Double(cw) >= 0.6 * dw && Double(ch) <= 0.15 * dh { continue }
            if (x0 == 0 || x1 == w) && Double(cw) <= 0.08 * dw && Double(ch) >= 0.5 * dh { continue }
            // the edge of a column divider just inside the crop: a sliver a few pixels wide, of any height
            if (x0 == 0 || x1 == w) && Double(cw) <= max(4.0, 0.02 * dw) { continue }
            if y0 == 0 && y1 == h { continue }
            // what is left of an erased rule: a flat sliver lying on it
            if Double(ch) <= 0.08 * dh && cw >= 2 * ch && (y0..<y1).allSatisfy({ nearRule[$0] }) { continue }
            // specks: stray pixels, and what shows of a rule lying along the crop's top or bottom edge
            if Double(pixels.count) < Self.speckArea * dh * dh || ((y0 == 0 || y1 == h) && Double(ch) <= Self.speckEdgeHeight * dh) { continue }
            comps.append(Comp(x0, x1, y0, y1, pixels.count, m))
        }
        comps = comps.enumerated().sorted { ($0.element.x0, $0.offset) < ($1.element.x0, $1.offset) }.map(\.element) // stable, like Python's sort

        pairColons(&comps)

        // merge parts of one glyph (an open 4, a broken 5): strong horizontal overlap
        var merged = true
        while merged {
            merged = false
            search: for a in comps.indices {
                for b in (a + 1)..<max(a + 1, comps.count) {
                    let A = comps[a], B = comps[b]
                    if A.colon || B.colon { continue }
                    let ov = min(A.x1, B.x1) - max(A.x0, B.x0)
                    if Double(ov) > 0.5 * Double(min(A.w, B.w)) {
                        let x0 = min(A.x0, B.x0), x1 = max(A.x1, B.x1)
                        let y0 = min(A.y0, B.y0), y1 = max(A.y1, B.y1)
                        let mw = x1 - x0
                        var m = [Bool](repeating: false, count: mw * (y1 - y0))
                        for c in [A, B] {
                            for yy in 0..<c.h { for xx in 0..<c.w where c.mask[yy * c.w + xx] { m[(c.y0 - y0 + yy) * mw + (c.x0 - x0 + xx)] = true } }
                        }
                        comps[a] = Comp(x0, x1, y0, y1, A.area + B.area, m)
                        comps.remove(at: b)
                        merged = true
                        break search
                    }
                }
            }
        }
        return comps.filter { $0.colon || (Double($0.h) >= 0.22 * dh && Double($0.area) >= 0.004 * dh * dh) }
    }

    /// Clears printed rules from the binary ink mask (see GlyphReader.kt). Returns the rows that lie on or
    /// beside an erased horizontal rule.
    private func eraseRules(_ on: inout [Bool], _ w: Int, _ h: Int, _ runs: inout [[Int]]) -> [Bool] {
        var kill = [Bool](repeating: false, count: w * h)
        var nearRule = [Bool](repeating: false, count: h)
        let margin = Self.ruleMargin
        let minH = max(8, Int(Self.ruleMinWidth * Double(w)))
        let minEdgeH = max(8, Int(Self.ruleMinWidthAtEdge * Double(w)))
        for y in 0..<h {
            var x = 0
            while x < w {
                if !on[y * w + x] { x += 1; continue }
                var e = x
                while e < w && on[y * w + e] { e += 1 }
                let reachesEdge = x <= margin || e >= w - margin
                if e - x >= minH || (reachesEdge && e - x >= minEdgeH) {
                    runs.append([y, x, e])
                    for yy in max(0, y - margin)...min(h - 1, y + margin) {
                        nearRule[yy] = true
                        for xx in x..<e { kill[yy * w + xx] = true }
                    }
                    for yy in max(0, y - 2 * margin)...min(h - 1, y + 2 * margin) { nearRule[yy] = true }
                }
                x = e
            }
        }
        let minV = max(8, Int(Self.ruleMinHeight * Double(h)))
        for x in 0..<w {
            var y = 0
            while y < h {
                if !on[y * w + x] { y += 1; continue }
                var e = y
                while e < h && on[e * w + x] { e += 1 }
                if e - y >= minV {
                    for xx in max(0, x - margin)...min(w - 1, x + margin) { for yy in y..<e { kill[yy * w + xx] = true } }
                }
                y = e
            }
        }
        for i in kill.indices where kill[i] { on[i] = false }
        return nearRule
    }

    // MARK: - glyph -> 28x28

    /// Python's round(): half to even.
    private func pyRound(_ v: Double) -> Int { Int(v.rounded(.toNearestOrEven)) }
    /// Java's Math.round(): half up.
    private func jRound(_ v: Double) -> Int { Int((v + 0.5).rounded(.down)) }

    /// MNIST-style: longest side to 20 px (PIL-style bilinear), centred by centre of mass in 28x28.
    func glyphTo28(_ patch: [Float], _ w: Int, _ h: Int) -> [Float]? {
        var mx: Float = 0
        for v in patch { mx = max(mx, v) }
        if mx <= 0 { return nil }
        let u8 = patch.map { min(255, max(0, Int(($0 / mx) * 255))) } // astype(uint8) truncates
        let s = 20.0 / Double(max(h, w))
        let nw = max(1, pyRound(Double(w) * s))
        let nh = max(1, pyRound(Double(h) * s))
        let a = resizeBilinear(u8, w, h, nw, nh)
        var sum = 0.0, cy = 0.0, cx = 0.0
        for y in 0..<nh { for x in 0..<nw { let v = Double(a[y * nw + x]) / 255.0; sum += v; cy += v * Double(y); cx += v * Double(x) } }
        if sum > 0 { cy /= sum; cx /= sum } else { cy = Double(nh) / 2.0; cx = Double(nw) / 2.0 }
        var oy = pyRound(14 - cy)
        var ox = pyRound(14 - cx)
        oy = max(0, min(28 - nh, oy))
        ox = max(0, min(28 - nw, ox))
        var canvas = [Float](repeating: 0, count: 784)
        for y in 0..<nh { for x in 0..<nw { canvas[(oy + y) * 28 + ox + x] = Float(a[y * nw + x]) / 255 } }
        return canvas
    }

    private static let precision = 22

    /// PIL Image.resize(BILINEAR) for 8-bit data: triangle filter widened when shrinking, horizontal pass then
    /// vertical, with PIL's 22-bit fixed-point coefficients and round-half-up (matches Pillow exactly).
    private func resizeBilinear(_ src: [Int], _ w: Int, _ h: Int, _ nw: Int, _ nh: Int) -> [Int] {
        let hx = coefficients(w, nw)
        var tmp = [Int](repeating: 0, count: nw * h)
        for y in 0..<h {
            for ox in 0..<nw {
                let c = hx[ox]
                var acc = 1 << (Self.precision - 1)
                for i in c.k.indices { acc += c.k[i] * src[y * w + c.start + i] }
                tmp[y * nw + ox] = clip8(acc >> Self.precision)
            }
        }
        let vy = coefficients(h, nh)
        var out = [Int](repeating: 0, count: nw * nh)
        for oy in 0..<nh {
            for x in 0..<nw {
                let c = vy[oy]
                var acc = 1 << (Self.precision - 1)
                for i in c.k.indices { acc += c.k[i] * tmp[(c.start + i) * nw + x] }
                out[oy * nw + x] = clip8(acc >> Self.precision)
            }
        }
        return out
    }

    private struct Coeff { let start: Int; let k: [Int] }

    private func clip8(_ v: Int) -> Int { v < 0 ? 0 : (v > 255 ? 255 : v) }

    /// Pillow's precompute_coeffs + normalize_coeffs_8bpc for the bilinear filter.
    private func coefficients(_ inSize: Int, _ outSize: Int) -> [Coeff] {
        let scale = Double(inSize) / Double(outSize)
        let filterScale = max(scale, 1.0)
        let support = 1.0 * filterScale
        let ss = 1.0 / filterScale
        return (0..<outSize).map { xx in
            let center = (Double(xx) + 0.5) * scale
            let xmin = max(Int(center - support + 0.5), 0)
            let xmax = min(Int(center + support + 0.5), inSize)
            let w = (0..<(xmax - xmin)).map { x -> Double in
                let t = abs((Double(x + xmin) - center + 0.5) * ss)
                return t < 1.0 ? 1.0 - t : 0.0
            }
            let sum = w.reduce(0, +)
            let k = w.map { wi -> Int in
                let n = sum != 0.0 ? wi / sum : wi
                let scaled = n * Double(1 << Self.precision)
                return Int(n < 0 ? -0.5 + scaled : 0.5 + scaled)
            }
            return Coeff(start: xmin, k: k)
        }
    }
}
