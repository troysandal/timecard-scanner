// swift-tools-version: 5.9
import PackageDescription

// The package and product name must be what `npx cap sync` derives from the npm name (`timecard-scanner` ->
// `TimecardScanner`, lower-case "c"): the host's CapApp-SPM references exactly that. The Capacitor plugin id
// (`TimeCardScanner`, in the Swift and Kotlin classes and `registerPlugin`) is separate and stays as it is.
let package = Package(
    name: "TimecardScanner",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "TimecardScanner", targets: ["TimeCardScannerPlugin"]),
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0"),
        // The S1 scanning core, a separate package so the Mac tools can use it without Capacitor.
        .package(name: "TimeCardScannerCore", path: "ios/Core"),
    ],
    targets: [
        .target(
            name: "TimeCardScannerPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                .product(name: "TimeCardScannerCore", package: "TimeCardScannerCore"),
            ],
            path: "ios/Sources/TimeCardScannerPlugin"
        ),
    ]
)
