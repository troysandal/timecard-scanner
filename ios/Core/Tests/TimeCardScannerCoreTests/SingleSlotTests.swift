import Dispatch
import Testing

@testable import TimeCardScannerCore

struct SingleSlotTests {
    @Test func aSecondAcquireIsRefusedUntilTheFirstIsReleased() {
        let slot = SingleSlot()
        let first = slot.acquire()
        #expect(first != nil)
        #expect(slot.acquire() == nil)
        slot.release(first!)
        #expect(slot.acquire() != nil)
    }

    /// A late release for an older holder must not free the slot of the current one.
    @Test func releasingAStaleTokenDoesNothing() {
        let slot = SingleSlot()
        let a = slot.acquire()!
        slot.release(a)
        let b = slot.acquire()!
        slot.release(a)
        #expect(slot.acquire() == nil, "b still holds the slot")
        slot.release(b)
        #expect(slot.acquire() != nil)
    }

    @Test func releasingTwiceIsHarmless() {
        let slot = SingleSlot()
        let a = slot.acquire()!
        slot.release(a)
        slot.release(a)
        #expect(slot.acquire() != nil)
    }

    /// Many callers at once (the calling thread of two quick `captureCard` calls): exactly one wins.
    @Test func exactlyOneConcurrentAcquireSucceeds() {
        for _ in 0..<50 {
            let slot = SingleSlot()
            let winners = LockedCounter()
            DispatchQueue.concurrentPerform(iterations: 64) { _ in
                if slot.acquire() != nil { winners.increment() }
            }
            #expect(winners.value == 1)
        }
    }
}

private final class LockedCounter: @unchecked Sendable {
    private let queue = DispatchQueue(label: "counter")
    private var count = 0
    func increment() { queue.sync { count += 1 } }
    var value: Int { queue.sync { count } }
}
