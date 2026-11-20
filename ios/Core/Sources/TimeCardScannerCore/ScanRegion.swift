import CoreGraphics
import Foundation

/// The part of a photo to read, as fractions (0...1) of the upright photo's width and height, top-left origin.
/// Used when the user drags a box around the table on a photo of the whole card. Same rules as
/// `ScanRegion.kt` (plugin/android/core): keep the two in sync.
public struct ScanRegion: Sendable, Equatable {
    public let x: Double, y: Double, width: Double, height: Double

    /// Smallest side accepted, as a fraction of the photo: a box this small cannot hold a table.
    public static let minSide = 0.05

    /// `nil` when a value is not finite, the box leaves the photo (beyond 0.001 of rounding), or a side is
    /// under `minSide`.
    public init?(x: Double, y: Double, width: Double, height: Double) {
        let tolerance = 0.001
        guard [x, y, width, height].allSatisfy(\.isFinite),
              x >= -tolerance, y >= -tolerance,
              width >= Self.minSide, height >= Self.minSide,
              x + width <= 1 + tolerance, y + height <= 1 + tolerance
        else { return nil }
        self.x = max(0, x)
        self.y = max(0, y)
        self.width = min(width, 1 - max(0, x))
        self.height = min(height, 1 - max(0, y))
    }

    /// The box in pixels of a photo of `width` x `height`, rounded outward and clamped to the photo.
    public func pixelRect(width w: Int, height h: Int) -> CGRect {
        let x0 = max(0, Int((x * Double(w)).rounded(.down)))
        let y0 = max(0, Int((y * Double(h)).rounded(.down)))
        let x1 = min(w, Int(((x + width) * Double(w)).rounded(.up)))
        let y1 = min(h, Int(((y + height) * Double(h)).rounded(.up)))
        return CGRect(x: x0, y: y0, width: max(1, x1 - x0), height: max(1, y1 - y0))
    }

    /// The part of `image` inside the box, or `nil` if it cannot be cut.
    public func crop(_ image: CGImage) -> CGImage? {
        image.cropping(to: pixelRect(width: image.width, height: image.height))
    }

    // MARK: - a starting box

    /// A box over the middle of a whole card, where its table usually is: the starting box when no table was found.
    public static let fallbackBox = ScanRegion(x: 0.04, y: 0.2, width: 0.92, height: 0.62)!

    /// A starting box for the user, around a table that was found: the value cells (`cells`, in pixels of an image of
    /// `imageWidth` x `imageHeight`) plus the printed `Ck.#` column to their left, the caption row above them,
    /// and a small margin. `nil` when no cell has geometry.
    public static func enclosing(cells: [[CGRect]], imageWidth: Int, imageHeight: Int) -> ScanRegion? {
        let measured = cells.flatMap { $0 }.filter { !$0.isEmpty }
        guard let first = measured.first, imageWidth > 0, imageHeight > 0 else { return nil }
        let union = measured.dropFirst().reduce(first) { $0.union($1) }
        let heights = measured.map(\.height).sorted()
        let rowHeight = heights[heights.count / 2]
        let margin = 0.02
        let w = Double(imageWidth), h = Double(imageHeight)
        let left = Double(union.minX) / w - 0.20 * Double(union.width) / w - margin
        let top = Double(union.minY) / h - 1.0 * Double(rowHeight) / h - margin
        let right = Double(union.maxX) / w + margin
        let bottom = Double(union.maxY) / h + margin
        let x = max(0, left), y = max(0, top)
        return ScanRegion(x: x, y: y, width: min(1, right) - x, height: min(1, bottom) - y)
    }
}
