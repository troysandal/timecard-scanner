package com.troysandal.timecardscanner

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * The tiny digit CNN from training/ (28x28 glyph -> 10 classes), forward pass
 * in plain Kotlin so it runs identically on the JVM and on Android with no
 * runtime dependency. Weights are digits.f32: little-endian float32 in the
 * order c1.w c1.b c2.w c2.b c3.w c3.b fc1.w fc1.b fc2.w fc2.b (PyTorch
 * layouts). 79,658 parameters; the golden test pins it to the PyTorch output.
 *
 * Architecture: [conv3x3 pad1 -> relu -> maxpool2] x3 (16/32/64 ch: 28->14->7->3)
 * -> fc 576->96 -> relu -> fc 96->10. Dropout is training-only.
 */
class DigitNet private constructor(private val p: FloatArray) {
    private var off = 0
    private fun take(n: Int): IntRange { val r = off until off + n; off += n; return r }

    private val c1w: IntRange; private val c1b: IntRange
    private val c2w: IntRange; private val c2b: IntRange
    private val c3w: IntRange; private val c3b: IntRange
    private val f1w: IntRange; private val f1b: IntRange
    private val f2w: IntRange; private val f2b: IntRange

    init {
        c1w = take(16 * 1 * 9); c1b = take(16)
        c2w = take(32 * 16 * 9); c2b = take(32)
        c3w = take(64 * 32 * 9); c3b = take(64)
        f1w = take(96 * 576); f1b = take(96)
        f2w = take(10 * 96); f2b = take(10)
        require(off == p.size) { "weights file has ${p.size} floats, expected $off" }
    }

    /** Raw logits for a 28x28 image (row-major, values 0..1). */
    fun logits(img: FloatArray): FloatArray {
        require(img.size == 784)
        var x = pool(conv(img, 1, 16, 28, c1w, c1b), 16, 28)
        x = pool(conv(x, 16, 32, 14, c2w, c2b), 32, 14)
        x = pool(conv(x, 32, 64, 7, c3w, c3b), 64, 7)
        val h = FloatArray(96) { o ->
            var s = p[f1b.first + o]
            val base = f1w.first + o * 576
            for (i in 0 until 576) s += p[base + i] * x[i]
            if (s > 0f) s else 0f
        }
        return FloatArray(10) { o ->
            var s = p[f2b.first + o]
            val base = f2w.first + o * 96
            for (i in 0 until 96) s += p[base + i] * h[i]
            s
        }
    }

    /** Softmax probabilities. */
    fun probabilities(img: FloatArray): FloatArray {
        val l = logits(img)
        val m = l.max()
        val e = FloatArray(10) { exp(l[it] - m) }
        val sum = e.sum()
        return FloatArray(10) { e[it] / sum }
    }

    /** 3x3 conv, zero padding 1, then ReLU. Input/outputs are channel-major (c, y, x). */
    private fun conv(x: FloatArray, cin: Int, cout: Int, size: Int, w: IntRange, b: IntRange): FloatArray {
        val out = FloatArray(cout * size * size)
        for (o in 0 until cout) for (y in 0 until size) for (xx in 0 until size) {
            var s = p[b.first + o]
            for (c in 0 until cin) {
                val wb = w.first + (o * cin + c) * 9
                val xb = c * size * size
                for (ky in 0 until 3) {
                    val yy = y + ky - 1
                    if (yy < 0 || yy >= size) continue
                    for (kx in 0 until 3) {
                        val xi = xx + kx - 1
                        if (xi < 0 || xi >= size) continue
                        s += p[wb + ky * 3 + kx] * x[xb + yy * size + xi]
                    }
                }
            }
            out[(o * size + y) * size + xx] = if (s > 0f) s else 0f
        }
        return out
    }

    /** 2x2 max pool, stride 2, floor (7 -> 3 drops the last row/column, as PyTorch does). */
    private fun pool(x: FloatArray, ch: Int, size: Int): FloatArray {
        val n = size / 2
        val out = FloatArray(ch * n * n)
        for (c in 0 until ch) for (y in 0 until n) for (xx in 0 until n) {
            val b = c * size * size + 2 * y * size + 2 * xx
            out[(c * n + y) * n + xx] = maxOf(maxOf(x[b], x[b + 1]), maxOf(x[b + size], x[b + size + 1]))
        }
        return out
    }

    companion object {
        /** Loads the bundled weights (resource digits.f32). */
        fun load(): DigitNet {
            val bytes = DigitNet::class.java.getResourceAsStream("/digits.f32")!!.use { it.readBytes() }
            return fromBytes(bytes)
        }

        fun fromBytes(bytes: ByteArray): DigitNet {
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return DigitNet(FloatArray(fb.remaining()).also { fb.get(it) })
        }
    }
}
