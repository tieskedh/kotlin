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
        fun viaSkipped(): Long = skippedBody(arrayOf("value"))
    }
}

// The signature has an ordinary open T, but the body creates Array<T?>. That open nullable
// element carrier remains deliberately unsupported, so the emitter skips this function only
// after call resolution has admitted it into the first render round.
@Suppress("UNCHECKED_CAST")
fun <T> skippedBody(value: Array<T>): Long {
    val values: Array<T?> = value as Array<T?>
    return values.size.toLong()
}

fun main() {
    println("fixpoint eviction")
}
