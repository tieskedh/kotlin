// Whole-PAIR eviction through the render FIXPOINT (the member pre-pass direction is pinned by
// companionMemberClash.kt): every signature below is fine, but the companion member viaSkipped()
// calls a top-level function whose BODY is unsupported. Round one renders both classes (the
// callee is still call-resolvable) and then skips the callee; in round two the companion member's
// body fails while re-rendering, the failure is attributed to the COMPANION (its render is
// recursive inside the enclosing class's render), and the whole (C, Companion) pair is evicted.
// The eviction must trigger a further round: Early rendered successfully EARLIER in round two,
// while the pair still existed, and its IL references the companion — without the re-render its
// stale IL would survive; round three skips it (its call target no longer exists). Only the file
// facade with main survives.
class Early {
    fun viaCompanion(): Int = C.tag()
}

class C {
    companion object {
        fun tag(): Int = 3
        fun viaSkipped(): Long = skippedBody()
    }
}

// This loop shape is NOT lowered (only Int.rangeTo(Int) is; this is Long.rangeTo(Long)): the
// function keeps its iterator-based desugaring whose LongIterator local has no IL mapping, so
// the emitter skips it with its regular unsupported-function warning.
fun skippedBody(): Long {
    var lastSeen = 0L
    for (i in 1L..3L) {
        lastSeen = i
    }
    return lastSeen
}

fun main() {
    println("fixpoint eviction")
}
