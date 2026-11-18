import Foundation
import Testing

@testable import TimeCardScannerCore

struct DigitNetTests {
    private func net() throws -> DigitNet { try #require(DigitNet.shared) }

    /// The same six PyTorch golden cases as Android's DigitNetTest (random inputs, no rider data).
    @Test func matchesPyTorchLogits() throws {
        let url = try #require(Bundle.module.url(forResource: "golden", withExtension: "json"))
        let cases = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [[String: Any]])
        #expect(cases.count >= 5)
        let net = try net()
        for (i, c) in cases.enumerated() {
            let input = (c["input"] as! [Double]).map { Float($0) }
            let want = (c["logits"] as! [Double]).map { Float($0) }
            let got = net.logits(input)
            for k in 0..<10 { #expect(abs(want[k] - got[k]) < 1e-3, "case \(i) logit \(k): \(want[k]) vs \(got[k])") }
        }
    }

    @Test func probabilitiesSumToOne() throws {
        let p = try net().probabilities((0..<784).map { $0 % 29 < 3 ? 1 : 0 })
        #expect(abs(p.reduce(0, +) - 1) < 1e-4)
    }

    /// The two platforms ship separate copies of the weights; they must stay the same file.
    @Test func theWeightsAreTheSameFileAsAndroids() throws {
        let android = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("android/core/src/main/resources/digits.f32")
        guard FileManager.default.fileExists(atPath: android.path) else { return } // not in a checkout that has Android
        #expect(try Data(contentsOf: android) == DigitNet.bundledWeights())
    }
}
