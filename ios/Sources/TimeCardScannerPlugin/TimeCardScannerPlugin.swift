import Capacitor
import Foundation
import TimeCardScannerCore
import UIKit
import VisionKit

/// Rejection codes the JS side can switch on (see `src/definitions.ts`).
private enum ErrorCode {
    static let cancelled = "CANCELLED"
    static let unavailable = "UNAVAILABLE"
    static let noTable = "NO_TABLE"
    static let invalidArgument = "INVALID_ARGUMENT"
    static let busy = "BUSY"
    static let scanFailed = "SCAN_FAILED"
}

/// Capacitor bridge for the S1 timecard scanner. The scanning itself lives in
/// `TimeCardScannerCore`, which needs iOS 26; this class compiles for older
/// deployment targets and reports `UNAVAILABLE` there.
@objc(TimeCardScannerPlugin)
public class TimeCardScannerPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "TimeCardScannerPlugin"
    public let jsName = "TimeCardScanner"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "isAvailable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "captureCard", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "scanImage", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cropCard", returnType: CAPPluginReturnPromise),
    ]

    /// Kept alive for the duration of one camera presentation.
    private var presenter: AnyObject?
    /// One capture at a time. Claimed synchronously in `captureCard`, before the main-actor hop, so two
    /// quick calls cannot both pass; released when the camera finishes, or when there is no view controller to
    /// present from.
    private let captureSlot = SingleSlot()

    // MARK: Methods

    @objc func isAvailable(_ call: CAPPluginCall) {
        var available = false
        if #available(iOS 26, *) { available = VNDocumentCameraViewController.isSupported }
        // `available` is the old name of `captureAvailable`, kept for hosts not yet updated. Cropping needs no camera.
        call.resolve(["captureAvailable": available, "available": available, "photoAvailable": true])
    }

    @objc func captureCard(_ call: CAPPluginCall) {
        guard #available(iOS 26, *) else {
            return call.reject("Card scanning requires iOS 26 or later.", ErrorCode.unavailable)
        }
        guard VNDocumentCameraViewController.isSupported else {
            return call.reject("The document scanner is not supported on this device.", ErrorCode.unavailable)
        }
        let folder: URL
        do {
            folder = try Self.destinationFolder(directory: call.getString("directory"), folder: call.getString("folder"))
        } catch {
            return call.reject("Invalid destination: \(error.localizedDescription)", ErrorCode.invalidArgument)
        }
        guard let slot = captureSlot.acquire() else {
            return call.reject("A capture is already in progress.", ErrorCode.busy)
        }
        // The user draws a box around the table after the scanner unless the host turns that off.
        let selectTable = call.getBool("selectTable") ?? true

        Task { @MainActor in
            guard let host = self.bridge?.viewController else {
                self.captureSlot.release(slot)
                return call.reject("No view controller to present the scanner from.", ErrorCode.unavailable)
            }
            let camera = DocumentCameraPresenter()
            self.presenter = camera
            camera.present(from: host) { outcome in
                if self.presenter === camera { self.presenter = nil }
                switch outcome {
                case .cancelled:
                    self.captureSlot.release(slot)
                    call.reject("The user cancelled the scan.", ErrorCode.cancelled)
                case .failed(let error):
                    self.captureSlot.release(slot)
                    call.reject("The document scanner failed: \(error.localizedDescription)", ErrorCode.scanFailed, error)
                case .captured(let image, let pageCount):
                    // The slot stays held through the box step and the read.
                    Task {
                        await self.process(image: image, pageCount: pageCount, folder: folder, selectTable: selectTable, call: call)
                        self.captureSlot.release(slot)
                    }
                }
            }
        }
    }

    /// Lets the user drag a box around the card on a photo the host took, and saves the cropped card. No reading,
    /// so it works on every iOS the plugin supports.
    @objc func cropCard(_ call: CAPPluginCall) {
        guard let path = call.getString("path"), !path.isEmpty else {
            return call.reject("`path` is required.", ErrorCode.invalidArgument)
        }
        let source = path.hasPrefix("file://") ? URL(string: path) : URL(fileURLWithPath: path)
        guard let source, let image = ImageLoading.uprightImage(contentsOf: source) else {
            return call.reject("Could not read an image at \(path).", ErrorCode.invalidArgument)
        }
        let folder: URL
        do {
            folder = try Self.destinationFolder(directory: call.getString("directory"), folder: call.getString("folder"))
        } catch {
            return call.reject("Invalid destination: \(error.localizedDescription)", ErrorCode.invalidArgument)
        }
        guard let slot = captureSlot.acquire() else {
            return call.reject("A capture is already in progress.", ErrorCode.busy)
        }
        Task {
            defer { self.captureSlot.release(slot) }
            let start = CardDetector.startingBox(in: image)
            let outcome = await withCheckedContinuation { (continuation: CheckedContinuation<TableBoxViewController.Outcome, Never>) in
                Task { @MainActor in
                    guard let host = self.bridge?.viewController else { return continuation.resume(returning: .cancelled) }
                    let screen = TableBoxViewController(image: UIImage(cgImage: image), initial: start, strings: .card) { continuation.resume(returning: $0) }
                    host.present(screen, animated: true)
                }
            }
            guard case .confirmed(let region) = outcome else {
                return call.reject("The user cancelled the crop.", ErrorCode.cancelled)
            }
            guard let part = region.crop(image), let jpeg = ImageLoading.jpegData(from: part) else {
                return call.reject("Could not cut the card out of the photo.", ErrorCode.scanFailed)
            }
            let file = folder.appendingPathComponent("card-\(UUID().uuidString).jpg")
            do {
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                try jpeg.write(to: file, options: .atomic)
            } catch {
                return call.reject("Could not save the photo: \(error.localizedDescription)", ErrorCode.scanFailed, error)
            }
            call.resolve(["image": ["path": file.path, "webPath": self.webPath(for: file)]])
        }
    }

    @objc func scanImage(_ call: CAPPluginCall) {
        guard #available(iOS 26, *) else {
            return call.reject("Card scanning requires iOS 26 or later.", ErrorCode.unavailable)
        }
        guard let path = call.getString("path"), !path.isEmpty else {
            return call.reject("`path` is required.", ErrorCode.invalidArgument)
        }
        let url = path.hasPrefix("file://") ? URL(string: path) : URL(fileURLWithPath: path)
        guard let url, let image = ImageLoading.uprightImage(contentsOf: url) else {
            return call.reject("Could not read an image at \(path).", ErrorCode.invalidArgument)
        }
        var region: ScanRegion?
        if let raw = call.getObject("region") {
            func number(_ key: String) -> Double? { (raw[key] as? NSNumber)?.doubleValue }
            guard let x = number("x"), let y = number("y"), let w = number("width"), let h = number("height"),
                  let parsed = ScanRegion(x: x, y: y, width: w, height: h)
            else {
                return call.reject("`region` must be {x, y, width, height}, fractions of the photo inside 0...1, each side at least 0.05.", ErrorCode.invalidArgument)
            }
            region = parsed
        } else if call.hasOption("region") {
            return call.reject("`region` must be an object {x, y, width, height}.", ErrorCode.invalidArgument)
        }
        Task { await self.scan(image, savedAt: url, extraWarnings: [], region: region, call: call) }
    }

    // MARK: Scanning

    @available(iOS 26, *)
    private func process(image: UIImage, pageCount: Int, folder: URL, selectTable: Bool, call: CAPPluginCall) async {
        guard let upright = image.uprightCGImage(), let jpeg = ImageLoading.jpegData(from: upright) else {
            return call.reject("Could not encode the captured image.", ErrorCode.scanFailed)
        }
        let file = folder.appendingPathComponent("timecard-\(UUID().uuidString).jpg")
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try jpeg.write(to: file, options: .atomic)
        } catch {
            return call.reject("Could not save the photo: \(error.localizedDescription)", ErrorCode.scanFailed, error)
        }
        let extra = pageCount > 1 ? ["The scanner returned \(pageCount) pages; only the first was read."] : []
        guard selectTable else {
            return await scan(upright, savedAt: file, extraWarnings: extra, region: nil, call: call)
        }
        // Where the table is, as far as the automatic read can tell, is where the user's box starts.
        let found = try? await TimeCardScanner.scan(upright)
        let start = found.flatMap { ScanRegion.enclosing(cells: $0.cells, imageWidth: upright.width, imageHeight: upright.height) } ?? ScanRegion.fallbackBox
        switch await selectBox(on: image, startingAt: start) {
        case .cancelled:
            try? FileManager.default.removeItem(at: file) // nothing was asked of the photo: do not leave it behind
            call.reject("The user cancelled the scan.", ErrorCode.cancelled)
        case .confirmed(let region):
            await scan(upright, savedAt: file, extraWarnings: extra, region: region, call: call)
        }
    }

    /// Shows the box screen over the host and waits for the user.
    @available(iOS 26, *)
    private func selectBox(on image: UIImage, startingAt start: ScanRegion) async -> TableBoxViewController.Outcome {
        await withCheckedContinuation { continuation in
            Task { @MainActor in
                guard let host = self.bridge?.viewController else { return continuation.resume(returning: .cancelled) }
                let screen = TableBoxViewController(image: image, initial: start) { continuation.resume(returning: $0) }
                host.present(screen, animated: true)
            }
        }
    }

    @available(iOS 26, *)
    private func scan(_ image: CGImage, savedAt file: URL, extraWarnings: [String], region: ScanRegion?, call: CAPPluginCall) async {
        do {
            // The saved photo stays the whole card; only the box is read.
            guard let part = region.map({ $0.crop(image) }) ?? image else {
                return call.reject("Could not cut the region out of the photo.", ErrorCode.invalidArgument)
            }
            let scan = try await TimeCardScanner.scan(part)
            let result = Self.result(for: scan, file: file, extraWarnings: extraWarnings, webPath: webPath(for: file))
            if scan.rows.isEmpty {
                // Keep the photo so the host can show what was captured.
                call.reject("No table was found in the photo.", ErrorCode.noTable, nil, result)
            } else {
                call.resolve(result)
            }
        } catch {
            call.reject("Scanning failed: \(error.localizedDescription)", ErrorCode.scanFailed, error)
        }
    }

    private func webPath(for file: URL) -> String {
        bridge?.portablePath(fromLocalURL: file)?.absoluteString ?? file.absoluteString
    }

    // MARK: Mapping

    @available(iOS 26, *)
    private static func result(for scan: TableScan, file: URL, extraWarnings: [String], webPath: String) -> [String: Any] {
        let rows: [[String: Any]] = scan.rows.enumerated().map { index, row in
            let cells = row + Array(repeating: "", count: max(0, 3 - row.count))
            var out: [String: Any] = ["c1": cells[0], "c2": cells[1], "c3": cells[2], "flagged": scan.flaggedCells(forRow: index)]
            // Offered for cells left blank that hold ink; never part of c1...c3.
            var suggested: [String: String] = [:]
            for s in scan.suggestions where s.row == index && (0..<3).contains(s.column) { suggested["c\(s.column + 1)"] = s.text }
            if !suggested.isEmpty { out["suggested"] = suggested }
            return out
        }
        // A mapped character is already visible as a flagged row; it isn't worth a line of its own.
        let warnings = scan.flags.filter { $0.kind != .charsetMapped }.map { flag in
            flag.row.map { "row \($0 + 1): \(flag.detail)" } ?? flag.detail
        }
        return [
            "image": ["path": file.path, "webPath": webPath],
            "rows": rows,
            "warnings": extraWarnings + warnings,
        ]
    }

    /// Resolves a `@capacitor/filesystem`-style directory name plus a relative
    /// folder. Unknown names fall back to Documents, as that plugin does.
    static func destinationFolder(directory: String?, folder: String?) throws -> URL {
        let search: FileManager.SearchPathDirectory
        switch directory {
        case "CACHE": search = .cachesDirectory
        case "LIBRARY": search = .libraryDirectory
        default: search = .documentDirectory
        }
        var url = FileManager.default.urls(for: search, in: .userDomainMask)[0]
        for part in (folder ?? "").split(separator: "/") {
            guard part != ".." else { throw CocoaError(.fileReadInvalidFileName) }
            url.appendPathComponent(String(part), isDirectory: true)
        }
        return url
    }
}
