import CoreGraphics
import TimeCardScannerCore
import Vision

/// Where a card lies in a photo, as the crop screen's starting box. Vision's rectangle detector (iOS 11+) is cheap
/// and needs no iOS 26 model; the user always adjusts the box, so a miss only costs a drag.
enum CardDetector {
    /// A box inset from the photo's edges, for when no card is found.
    static let fallbackBox = ScanRegion(x: 0.05, y: 0.05, width: 0.90, height: 0.90)!

    static func startingBox(in image: CGImage) -> ScanRegion {
        let request = VNDetectRectanglesRequest()
        request.minimumAspectRatio = 0.2
        request.maximumAspectRatio = 1.0
        request.minimumSize = 0.3
        request.quadratureTolerance = 30
        request.maximumObservations = 1
        guard (try? VNImageRequestHandler(cgImage: image).perform([request])) != nil,
              let found = request.results?.first
        else { return fallbackBox }
        // Vision's boxes have a bottom-left origin; the crop screen's have a top-left one.
        let b = found.boundingBox
        let margin = 0.02
        let x = max(0, Double(b.minX) - margin)
        let y = max(0, 1 - Double(b.maxY) - margin)
        let right = min(1, Double(b.maxX) + margin)
        let bottom = min(1, 1 - Double(b.minY) + margin)
        return ScanRegion(x: x, y: y, width: right - x, height: bottom - y) ?? fallbackBox
    }
}
