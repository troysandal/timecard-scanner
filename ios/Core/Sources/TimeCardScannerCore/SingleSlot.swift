import Foundation

/// A slot that at most one holder can occupy: the plugin's "one capture at a time" guard.
///
/// The check and the claim happen in one atomic step on the caller's thread, so two calls arriving
/// back to back cannot both pass (checking a property that a later main-actor task sets could).
/// A holder releases with the token it got, and a release by anyone else, or a second release, does nothing.
public final class SingleSlot: @unchecked Sendable {
    public struct Token: Equatable, Sendable {
        fileprivate let id: UInt64
    }

    private let lock = NSLock()
    private var current: Token?
    private var next: UInt64 = 0

    public init() {}

    /// A token if the slot was free (now taken), `nil` if it is busy.
    public func acquire() -> Token? {
        lock.lock()
        defer { lock.unlock() }
        guard current == nil else { return nil }
        next += 1
        let token = Token(id: next)
        current = token
        return token
    }

    /// Frees the slot if [token] is still the holder.
    public func release(_ token: Token) {
        lock.lock()
        defer { lock.unlock() }
        if current == token { current = nil }
    }
}
