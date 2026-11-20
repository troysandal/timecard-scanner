import CoreGraphics
import Foundation

/// One row of Apple's detected table, reduced to plain values so the cleanup
/// below can be unit-tested without Vision.
struct AppleRow {
    /// All cell texts left to right: `Ck.#`, then the three value columns.
    var texts: [String]
    /// All cell rects (pixel space, top-left origin), same order as `texts`.
    var cells: [CGRect]

    /// Bounding box of the cells that have a region. `AppleTable.pixelRect` returns `.zero` for a cell
    /// Vision gave no region for; unioning that would stretch the row to the origin, so it is skipped.
    /// `.zero` when no cell has geometry.
    var rect: CGRect {
        let measured = cells.filter { !$0.isEmpty }
        return measured.dropFirst().reduce(measured.first ?? .zero) { $0.union($1) }
    }

    /// False when none of the row's cells has a region, so no geometry check can say anything about it.
    var hasGeometry: Bool { !rect.isEmpty }
}

struct CleanRow {
    /// The three value columns.
    var texts: [String]
    var cells: [CGRect]
    /// Printed checkpoint number if Vision read one.
    var checkpoint: Int?
    /// Index of this row in the raw table, `nil` for an inserted blank row.
    var sourceIndex: Int?
}

/// Repairs the structural mistakes Apple's table detector makes, using
/// geometry rather than the printed numerals alone (a numeral can be
/// misread — `S` for 5, `l` for 1 — or missed entirely):
/// - the caption row(s) are recognised by *shape*, in any language: the
///   leading rows whose value cells hold real words (several letters, any
///   script). Handwritten numbers never read as words, and a misread numeral
///   (`S` for 5) or a missing one doesn't matter, because the Ck cell isn't
///   consulted. No caption words are hard-coded, so a French or Japanese
///   card, or a card folded so no captions show, works the same;
/// - slivers far thinner than a real row (a phantom row seen between two
///   real ones) are dropped, since they shift every row below them;
/// - a vertical gap of about a whole row between two rows gets blank rows
///   inserted, so rows after a detector gap don't slide up.
///
/// Nothing is repaired silently: every dropped, inserted or truncated row
/// comes back as a `ScanFlag`.
enum AppleTableCleanup {
    /// A row thinner than this fraction of the median row height is a sliver.
    static let sliverFraction: CGFloat = 0.5

    /// A cell reads as a word when it has at least this many letters (any
    /// script — `Character.isLetter` covers Latin, Cyrillic, CJK, …) and
    /// letters make up most of its non-space characters. Handwritten digit
    /// cells, even with a stray misread (`S`, `l`, `HA`), stay under this.
    static let minWordLetters = 3

    static func isWordLike(_ cell: String) -> Bool {
        let chars = cell.filter { !$0.isWhitespace && !$0.isPunctuation && $0 != "#" }
        let letters = chars.filter(\.isLetter).count
        return letters >= minWordLetters && letters * 2 >= chars.count
    }

    /// Caption rows only ever come first. Once a row that isn't one has been
    /// seen, nothing later is treated as a caption, so a garbled data row in
    /// the middle of the table is never discarded.
    static func isCaptionRow(_ texts: [String]) -> Bool {
        texts.dropFirst().prefix(3).contains(where: isWordLike)
    }

    /// An empty value cell narrower than this fraction of the row's other value cells is a sliver, not a column.
    static let sliverColumnFraction: CGFloat = 0.35

    /// Removes at most one empty sliver column (never the `Ck.#` cell) from a row that has more than four
    /// cells. A row with the usual four cells, or without geometry, is returned unchanged.
    static func withoutEmptySliverColumn(_ row: AppleRow) -> AppleRow {
        guard row.texts.count > 4, row.texts.count == row.cells.count else { return row }
        let widths = row.cells.dropFirst().map(\.width).sorted()
        let median = widths[widths.count / 2]
        guard median > 0 else { return row }
        guard let i = (1..<row.cells.count).first(where: {
            row.texts[$0].isEmpty && !row.cells[$0].isEmpty && row.cells[$0].width < median * sliverColumnFraction
        }) else { return row }
        var out = row
        out.texts.remove(at: i)
        out.cells.remove(at: i)
        return out
    }

    static func clean(_ raw: [AppleRow]) -> (rows: [CleanRow], flags: [ScanFlag]) {
        var flags: [ScanFlag] = []
        var inLeadingRows = true
        let data = raw.enumerated().filter { index, row in
            guard row.texts.count >= 4, row.cells.count >= 4 else {
                flags.append(ScanFlag(
                    kind: .malformedRow,
                    detail: "dropped detected row \(index) with only \(min(row.texts.count, row.cells.count)) cell(s)"
                ))
                return false
            }
            if inLeadingRows && isCaptionRow(row.texts) { return false }
            inLeadingRows = false
            return true
        }
        guard !data.isEmpty else { return ([], flags) }

        // Rows without geometry are kept as they are and take no part in the checks below. With no
        // measurable row at all the median is 0 and the checks are skipped (gap / 0 would be NaN).
        let heights = data.filter { $0.element.hasGeometry }.map { $0.element.rect.height }.sorted()
        let median = heights.isEmpty ? 0 : heights[heights.count / 2]

        var kept: [(index: Int, row: AppleRow)] = []
        for (index, row) in data {
            if median > 0, row.hasGeometry, row.rect.height < median * sliverFraction {
                flags.append(ScanFlag(
                    kind: .droppedSliverRow,
                    detail: "ignored a \(Int(row.rect.height)) px sliver row (typical row \(Int(median)) px)"
                ))
            } else {
                kept.append((index, row))
            }
        }

        // A whole-card photo makes Apple split an empty sliver off a column (a 60 px cell beside 270 px
        // ones), so the row has 5 cells and every value would land a column to the right. An empty cell
        // far narrower than the row's other value cells is not a column: drop it.
        var slivers = 0
        kept = kept.map { item in
            let fixed = withoutEmptySliverColumn(item.row)
            if fixed.texts.count != item.row.texts.count { slivers += 1 }
            return (item.index, fixed)
        }
        if slivers > 0 {
            flags.append(ScanFlag(kind: .droppedSliverColumn, detail: "dropped an empty sliver column from \(slivers) detected row(s); values kept in their columns"))
        }

        var out: [CleanRow] = []
        // The last row that has geometry, and how many rows without geometry came after it: those
        // occupy part of the gap, so they are not "missing".
        var lastMeasured: CGRect?
        var unmeasuredSince = 0
        for item in kept {
            let valueCells = Array(item.row.cells[1..<4])
            if median > 0, item.row.hasGeometry, let previous = lastMeasured {
                let gap = item.row.rect.minY - previous.maxY
                let missing = Int((gap / median).rounded()) - unmeasuredSince
                if missing >= 1 {
                    flags.append(ScanFlag(
                        kind: .insertedGapRows, row: out.count,
                        detail: "inserted \(missing) blank row(s) for a \(Int(gap)) px gap in the detected table"
                    ))
                    // Spread the inserted rows evenly across the gap, in this row's columns.
                    let height = gap / CGFloat(missing)
                    for k in 0..<missing {
                        let y = previous.maxY + CGFloat(k) * height
                        let cells = valueCells.map { CGRect(x: $0.minX, y: y, width: $0.width, height: height) }
                        out.append(CleanRow(texts: ["", "", ""], cells: cells, checkpoint: nil, sourceIndex: nil))
                    }
                }
            }
            if item.row.texts.count > 4 || item.row.cells.count > 4 {
                flags.append(ScanFlag(
                    kind: .extraColumns, row: out.count,
                    detail: "detected row has \(item.row.texts.count) cells; kept the first three value cells, values may have shifted"
                ))
            }
            out.append(CleanRow(
                texts: Array(item.row.texts[1..<4]),
                cells: valueCells,
                checkpoint: Int(item.row.texts[0].trimmingCharacters(in: .whitespaces)),
                sourceIndex: item.index
            ))
            if item.row.hasGeometry {
                lastMeasured = item.row.rect
                unmeasuredSince = 0
            } else {
                unmeasuredSince += 1
            }
        }
        return (out, flags)
    }
}
