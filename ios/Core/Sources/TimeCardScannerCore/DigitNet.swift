import Foundation

/// The tiny digit CNN from the bench's `training/` (28x28 glyph -> 10 classes): forward pass in plain
/// Swift loops, in the same order as `DigitNet.kt` (plugin/android/core) so the results match it to
/// float rounding. Weights are `digits.f32`: little-endian float32 in the order
/// c1.w c1.b c2.w c2.b c3.w c3.b fc1.w fc1.b fc2.w fc2.b (PyTorch layouts), 79,658 parameters.
///
/// Architecture: [conv3x3 pad1 -> relu -> maxpool2] x3 (16/32/64 ch: 28->14->7->3) -> fc 576->96 ->
/// relu -> fc 96->10.
///
/// Keep in sync with DigitNet.kt; `DigitNetTests` pins it to the same PyTorch golden cases.
final class DigitNet: Sendable {
    private let p: [Float]
    private let c1w: Int, c1b: Int, c2w: Int, c2b: Int, c3w: Int, c3b: Int
    private let f1w: Int, f1b: Int, f2w: Int, f2b: Int

    init(weights: [Float]) throws {
        p = weights
        var off = 0
        func take(_ n: Int) -> Int { let r = off; off += n; return r }
        c1w = take(16 * 1 * 9); c1b = take(16)
        c2w = take(32 * 16 * 9); c2b = take(32)
        c3w = take(64 * 32 * 9); c3b = take(64)
        f1w = take(96 * 576); f1b = take(96)
        f2w = take(10 * 96); f2b = take(10)
        guard off == weights.count else { throw DigitNetError.badWeights(have: weights.count, want: off) }
    }

    enum DigitNetError: Error { case badWeights(have: Int, want: Int), missingResource }

    /// The bundled weights, loaded once.
    static let shared: DigitNet? = try? DigitNet(data: bundledWeights())

    convenience init(data: Data) throws {
        guard data.count % 4 == 0 else { throw DigitNetError.badWeights(have: data.count / 4, want: 0) }
        var floats = [Float](repeating: 0, count: data.count / 4)
        data.withUnsafeBytes { raw in
            for i in floats.indices { floats[i] = Float(bitPattern: UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: i * 4, as: UInt32.self))) }
        }
        try self.init(weights: floats)
    }

    static func bundledWeights() throws -> Data {
        guard let url = Bundle.module.url(forResource: "digits", withExtension: "f32") else { throw DigitNetError.missingResource }
        return try Data(contentsOf: url)
    }

    /// Raw logits for a 28x28 image (row-major, values 0...1).
    ///
    /// The loops are plain `while` loops over unsafe buffers, in the order of the Kotlin original: `for i in 0..<n` goes
    /// through a generic range iterator that an unoptimized (Debug) build resolves through the runtime on every
    /// step (about 100x slower there). The arithmetic and its order are unchanged, so the result is identical.
    func logits(_ img: [Float]) -> [Float] {
        precondition(img.count == 784)
        var x = pool(conv(img, 1, 16, 28, c1w, c1b), 16, 28)
        x = pool(conv(x, 16, 32, 14, c2w, c2b), 32, 14)
        x = pool(conv(x, 32, 64, 7, c3w, c3b), 64, 7)
        var h = [Float](repeating: 0, count: 96)
        var out = [Float](repeating: 0, count: 10)
        p.withUnsafeBufferPointer { pp in
            x.withUnsafeBufferPointer { xp in
                h.withUnsafeMutableBufferPointer { hp in
                    var o = 0
                    while o < 96 {
                        var s = pp[f1b + o]
                        let base = f1w + o * 576
                        var i = 0
                        while i < 576 { s += pp[base + i] * xp[i]; i += 1 }
                        hp[o] = s > 0 ? s : 0
                        o += 1
                    }
                }
            }
            h.withUnsafeBufferPointer { hp in
                out.withUnsafeMutableBufferPointer { op in
                    var o = 0
                    while o < 10 {
                        var s = pp[f2b + o]
                        let base = f2w + o * 96
                        var i = 0
                        while i < 96 { s += pp[base + i] * hp[i]; i += 1 }
                        op[o] = s
                        o += 1
                    }
                }
            }
        }
        return out
    }

    /// Softmax probabilities.
    func probabilities(_ img: [Float]) -> [Float] {
        let l = logits(img)
        let m = l.max()!
        let e = l.map { Float(Foundation.exp(Double($0 - m))) }
        let sum = e.reduce(0, +)
        return e.map { $0 / sum }
    }

    /// 3x3 conv, zero padding 1, then ReLU. Input/outputs are channel-major (c, y, x).
    private func conv(_ x: [Float], _ cin: Int, _ cout: Int, _ size: Int, _ w: Int, _ b: Int) -> [Float] {
        var out = [Float](repeating: 0, count: cout * size * size)
        p.withUnsafeBufferPointer { pp in
            x.withUnsafeBufferPointer { xp in
                out.withUnsafeMutableBufferPointer { op in
                    var o = 0
                    while o < cout {
                        var y = 0
                        while y < size {
                            var xx = 0
                            while xx < size {
                                var s = pp[b + o]
                                var c = 0
                                while c < cin {
                                    let wb = w + (o * cin + c) * 9
                                    let xb = c * size * size
                                    var ky = 0
                                    while ky < 3 {
                                        let yy = y + ky - 1
                                        if yy >= 0 && yy < size {
                                            var kx = 0
                                            while kx < 3 {
                                                let xi = xx + kx - 1
                                                if xi >= 0 && xi < size { s += pp[wb + ky * 3 + kx] * xp[xb + yy * size + xi] }
                                                kx += 1
                                            }
                                        }
                                        ky += 1
                                    }
                                    c += 1
                                }
                                op[(o * size + y) * size + xx] = s > 0 ? s : 0
                                xx += 1
                            }
                            y += 1
                        }
                        o += 1
                    }
                }
            }
        }
        return out
    }

    /// 2x2 max pool, stride 2, floor (7 -> 3 drops the last row/column, as PyTorch does).
    private func pool(_ x: [Float], _ ch: Int, _ size: Int) -> [Float] {
        let n = size / 2
        var out = [Float](repeating: 0, count: ch * n * n)
        x.withUnsafeBufferPointer { xp in
            out.withUnsafeMutableBufferPointer { op in
                var c = 0
                while c < ch {
                    var y = 0
                    while y < n {
                        var xx = 0
                        while xx < n {
                            let b = c * size * size + 2 * y * size + 2 * xx
                            op[(c * n + y) * n + xx] = max(max(xp[b], xp[b + 1]), max(xp[b + size], xp[b + size + 1]))
                            xx += 1
                        }
                        y += 1
                    }
                    c += 1
                }
            }
        }
        return out
    }
}
