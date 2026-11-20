package com.troysandal.timecardscanner.plugin

/**
 * The one capture that may be in flight. Whoever ends it (the scanner's result, a failed launch, the
 * plugin being destroyed) calls [take] or [settle], and only the first gets it, so a capture is settled
 * exactly once and can never leave the gate stuck busy. No Android types, so it is unit-testable.
 */
internal class CaptureGate<T : Any> {
    private var pending: T? = null

    /** False when a capture is already in flight (the caller rejects with `BUSY`). */
    @Synchronized
    fun begin(capture: T): Boolean {
        if (pending != null) return false
        pending = capture
        return true
    }

    /** The pending capture, leaving the gate busy; null if there is none. */
    @Synchronized
    fun current(): T? = pending

    /** The pending capture, freeing the gate; null if there is none (already settled). */
    @Synchronized
    fun take(): T? {
        val capture = pending
        pending = null
        return capture
    }

    /** Takes the pending capture only if it is [expected] (the same object), else leaves the gate alone. */
    @Synchronized
    fun take(expected: T): T? {
        if (pending !== expected) return null
        return take()
    }

    /** True while [capture] is still the pending one. */
    @Synchronized
    fun holds(capture: T): Boolean = pending === capture

    /** Runs [action] on the pending capture if there still is one. */
    inline fun settle(action: (T) -> Unit) {
        take()?.let(action)
    }

    /** Runs [action] on [expected] only if it is still the pending capture: a late callback for an old one does nothing. */
    inline fun settle(expected: T, action: (T) -> Unit) {
        take(expected)?.let(action)
    }
}
