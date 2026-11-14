package com.troysandal.timecardscanner

/**
 * Something the scan wants the host to know about. Flags are data, not prose:
 * the host decides which kinds to surface ([detail] is for humans). Mirrors
 * the Swift `ScanFlag`; the kinds differ because the pipeline does.
 */
data class ScanFlag(
    val kind: Kind,
    /** Zero-based row in the returned table, or null for a table-level flag. */
    val row: Int? = null,
    /** Zero-based value column (0..2), or null for a row- or table-level flag. */
    val column: Int? = null,
    val detail: String,
) {
    enum class Kind {
        /** The printed checkpoint numerals were not found, so there is no table. */
        NO_TABLE,
        /** The column dividers were not all found as printed rules; the grid is partly guessed. */
        GUESSED_COLUMNS,
        /** Fewer than half of the printed checkpoint numerals were read; row positions are mostly interpolated. */
        FEW_ROW_ANCHORS,
        /** The value is not a confident read (low model confidence, or it came from the fallback reader). */
        UNSURE_VALUE,
        /** The two readers read different values. */
        READERS_DISAGREE,
        /** The writing's shape is suspect: a line through it, or a blob that may be several touching digits. */
        SUSPECT_SHAPE,
        /** The cell holds ink but no value was read: the user must fill it in. */
        INK_NOT_READ,
    }
}

/** The result of scanning one card. Mirrors the Swift `TableScan`. */
data class TableScan(
    /** One entry per table row, each with three value cells over `[0-9:]`; "" for a blank cell. */
    val rows: List<List<String>>,
    /** Value-cell rects per row, in pixels of the scanned image, top-left origin. */
    val cells: List<List<Box>>,
    val flags: List<ScanFlag>,
    val pipelineVersion: String,
) {
    /** True when the cell carries a flag: the review screen should ask the user to check it. */
    fun isFlagged(row: Int, column: Int) = flags.any { it.row == row && it.column == column }

    /** Flags attached to [row] (its cells' flags included). */
    fun flags(forRow: Int) = flags.filter { it.row == forRow }
}

/**
 * The Android pipeline. No OS API returns table structure on Android, so the
 * table is rebuilt here: rows from the printed checkpoint numerals, columns
 * from the printed rules, each cell read by the digit model with the text
 * recognizer's characters as a second opinion.
 *
 * The caller supplies the recognizer's characters ([OcrSymbol], from ML Kit
 * Text Recognition on the phone), so this code has no Android dependency and
 * runs unchanged on the JVM against recorded characters.
 *
 * Policy (measured in android/RESULTS.md, "Policy"):
 *  - value: the digit model's read at confidence >= [SURE]; else the
 *    recognizer's value if it has one; else the model's down to [CONF_MIN];
 *    blank where the ink detector sees no writing;
 *  - a filled cell is flagged unless it is a confident model read that the
 *    recognizer does not contradict and whose shape is unremarkable; a value
 *    both readers agree on is never flagged for being unsure;
 *  - an empty cell is flagged when there is ink in it, or when the ink gate
 *    removed a confident read.
 */
object TimeCardScanner {
    /** Bump when a change can alter the extracted table. */
    const val pipelineVersion = "A1.1"

    const val PEAK_MIN = 0.7f
    const val CONF_MIN = 0.5f
    const val SURE = 0.9f
    /** Ink fraction below which a cell is taken as empty. Low, because [InkDetector] erases printed rules first. */
    const val INK_MIN = 0.012
    /**
     * Ink fraction below which the digit model is not even run: the cell comes back empty and unflagged.
     * Half of [INK_MIN], so a faint mark just under the gate is still read and can still be flagged.
     */
    const val INK_SKIP = 0.006
    private val NOTHING_READ = GlyphRead("", 1f, 0f)
    /** A glyph wider than this many times the tallest one in its cell may be touching digits. */
    const val WIDE_GLYPH = 1.3f
    /** Margins kept off each side of a cell, as fractions of the row height (about 8 and 10 px on the corpus). */
    const val CROP_INSET = 0.055
    const val INK_INSET = 0.07
    const val INK_DELTA = 45

    // The peak gate is applied here, not in the reader, so a weak read can still be compared with the recognizer's.
    private val defaultReader by lazy { GlyphReader(DigitNet.load(), peakMin = 0f) }

    /**
     * [gray] is the upright photo, row-major, one byte per pixel (0 = black);
     * [symbols] are the recognizer's characters in the same pixel space.
     * A photo with no table comes back with no rows and a [ScanFlag.Kind.NO_TABLE] flag.
     */
    fun scan(gray: ByteArray, width: Int, height: Int, symbols: List<OcrSymbol>, reader: GlyphReader = defaultReader): TableScan {
        val anchors = RowAnchorFinder.find(symbols, width)
            ?: return TableScan(emptyList(), emptyList(), listOf(ScanFlag(ScanFlag.Kind.NO_TABLE, detail = "no printed checkpoint numerals found")), pipelineVersion)
        val flags = mutableListOf<ScanFlag>()
        if (anchors.found * 2 < anchors.rowCount) {
            flags += ScanFlag(ScanFlag.Kind.FEW_ROW_ANCHORS, detail = "only ${anchors.found} of ${anchors.rowCount} checkpoint numerals were read")
        }
        val rowEdges = anchors.rowEdges
        val columns = ColumnFinder.find(gray, width, height, rowEdges.first().toInt(), rowEdges.last().toInt(), anchors.bandRight)
        if (columns.source != ColumnEdges.Source.RULES) {
            flags += ScanFlag(ScanFlag.Kind.GUESSED_COLUMNS, detail = "column dividers: ${columns.source.name.lowercase()}")
        }
        val grid = Grid(rowEdges, columns.edges)
        val recognized = CellAssigner.assign(symbols, grid)

        val rows = ArrayList<List<String>>(grid.rows)
        val cells = ArrayList<List<Box>>(grid.rows)
        for (r in 0 until grid.rows) {
            val rowTexts = ArrayList<String>(3)
            val rowBoxes = ArrayList<Box>(3)
            val rowH = grid.rowEdges[r + 1] - grid.rowEdges[r]
            for (k in 0 until 3) {
                val box = Box(grid.colEdges[k + 1], grid.rowEdges[r], grid.colEdges[k + 2], grid.rowEdges[r + 1])
                rowBoxes += box
                val ink = InkDetector.score(gray, width, height, box.left, box.top, box.right, box.bottom, inset = Math.round(INK_INSET * rowH).toInt(), delta = INK_DELTA)
                // most cells are empty: the digit model, by far the slowest step, is not run on them
                val model = if (ink < INK_SKIP) NOTHING_READ else readCell(gray, width, height, box, Math.round(CROP_INSET * rowH).toInt(), reader)
                val (text, flag) = decide(model, recognized[r][k].text, ink)
                rowTexts += text
                if (flag != null) flags += ScanFlag(flag.first, r, k, flag.second)
            }
            rows += rowTexts
            cells += rowBoxes
        }
        return TableScan(rows, cells, flags, pipelineVersion)
    }

    /**
     * Grayscale for [scan] from packed ARGB pixels (Android `Bitmap.getPixels`): ITU-R 601 luma in 16-bit
     * fixed point, the same conversion the corpus evaluation uses.
     */
    fun grayFromArgb(argb: IntArray): ByteArray = ByteArray(argb.size) { i ->
        val p = argb[i]
        ((((p shr 16) and 0xFF) * 19595 + ((p shr 8) and 0xFF) * 38470 + (p and 0xFF) * 7471 + 0x8000) shr 16).toByte()
    }

    private fun readCell(gray: ByteArray, width: Int, height: Int, box: Box, inset: Int, reader: GlyphReader): GlyphRead {
        val x0 = (box.left + inset).toInt().coerceIn(0, width - 2)
        val x1 = (box.right - inset).toInt().coerceIn(x0 + 1, width)
        val y0 = (box.top + inset).toInt().coerceIn(0, height - 2)
        val y1 = (box.bottom - inset).toInt().coerceIn(y0 + 1, height)
        val w = x1 - x0
        val h = y1 - y0
        val crop = FloatArray(w * h) { i -> (gray[(y0 + i / w) * width + x0 + i % w].toInt() and 0xFF).toFloat() }
        return reader.read(crop, w, h)
    }

    /** One cell's final text and, when the user should check it, the reason. */
    internal fun decide(model: GlyphRead, recognized: String, ink: Double): Pair<String, Pair<ScanFlag.Kind, String>?> {
        val modelOk = model.peak >= PEAK_MIN && model.text.isNotEmpty() && model.confidence >= CONF_MIN
        val sure = modelOk && model.confidence >= SURE
        var text = when {
            sure -> model.text
            recognized.isNotEmpty() -> recognized
            modelOk -> model.text
            else -> recognized
        }
        var flag: Pair<ScanFlag.Kind, String>? =
            if (sure) null else ScanFlag.Kind.UNSURE_VALUE to "unsure read (digit model \"${model.text}\" at ${"%.2f".format(model.confidence)}, recognizer \"$recognized\")"
        if (recognized == model.text) flag = null
        if (recognized.isNotEmpty() && recognized != text) {
            flag = ScanFlag.Kind.READERS_DISAGREE to "digit model read \"${model.text}\", recognizer read \"$recognized\""
        }
        if (text == model.text && (model.suspect || model.widest > WIDE_GLYPH)) {
            flag = ScanFlag.Kind.SUSPECT_SHAPE to
                if (model.suspect) "a line crosses the writing, or a blob was cut into digits" else "a wide glyph may be touching digits"
        }
        if (ink < INK_MIN) text = ""
        if (text.isEmpty()) {
            flag = when {
                ink >= INK_MIN -> ScanFlag.Kind.INK_NOT_READ to "ink in the cell but nothing read"
                sure -> ScanFlag.Kind.INK_NOT_READ to "faint mark read as \"${model.text}\" but too little ink to trust"
                else -> null
            }
        }
        return text to flag
    }
}
