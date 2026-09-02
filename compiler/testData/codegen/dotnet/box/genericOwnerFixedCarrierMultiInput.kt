// DOTNET_GENERIC_OWNER_FIXED_CARRIER_INPUT_PROBE

// Fixed declaration-independent parameter carriers must remain independent of both the owner
// binder and the split-nullable result layout. This is one structural vector proof, not a
// Boolean-, declaration-name-, package-, or stdlib-specific admission rule.

interface FixedCarrierLookup<K, out V> {
    fun lookup(
        first: K,
        selectFirst: Boolean,
        token: Int,
        label: String,
        marker: Any?,
        second: K,
    ): V?
}

// Fixed leaves do not yet compose with a MethodSpec. This hostile declaration must remain
// outside the candidate natural/semantic family until that mixed binder has its own proof.
interface FixedCarrierMethodLookup<K, out V> {
    fun <R> lookup(
        first: K,
        selectFirst: Boolean,
        token: Int,
        label: String,
        marker: Any?,
        second: K,
        methodMarker: R,
    ): V?
}

private class FixedCarrierSplitRoute<T> : FixedCarrierLookup<T, T> {
    override fun lookup(
        first: T,
        selectFirst: Boolean,
        token: Int,
        label: String,
        marker: Any?,
        second: T,
    ): T? {
        val sourceNaturalAlias: FixedCarrierLookup<T, T> =
            object : FixedCarrierLookup<T, T> {
                override fun lookup(
                    first: T,
                    selectFirst: Boolean,
                    token: Int,
                    label: String,
                    marker: Any?,
                    second: T,
                ): T? = if (token != label.length || marker != null) {
                    null
                } else if (selectFirst) {
                    first
                } else {
                    second
                }
        }
        val firstAlias: T = first
        val secondAlias: T = second
        val resultAlias: T? = sourceNaturalAlias.lookup(
            firstAlias,
            selectFirst,
            token,
            label,
            marker,
            secondAlias,
        )
        return resultAlias
    }
}

fun box(): String {
    val ints = FixedCarrierSplitRoute<Int>()
    if (ints.lookup(63, true, 4, "four", null, 64) != 63) return "int first fixed route"
    if (ints.lookup(63, false, 4, "four", null, 64) != 64) return "int second fixed route"
    if (ints.lookup(63, false, 3, "four", null, 64) != null) return "int token fixed route"
    if (ints.lookup(63, false, 4, "four", "blocked", 64) != null) return "object fixed route"

    val strings = FixedCarrierSplitRoute<String>()
    if (strings.lookup("left", true, 3, "tag", null, "right") != "left") {
        return "string first fixed route"
    }
    if (strings.lookup("left", false, 3, "tag", null, "right") != "right") {
        return "string second fixed route"
    }
    if (strings.lookup("left", false, 4, "tag", null, "right") != null) {
        return "string token fixed route"
    }

    val nullableInts = FixedCarrierSplitRoute<Int?>()
    if (nullableInts.lookup(null, false, 2, "ok", null, 65) != 65) {
        return "nullable second fixed route"
    }
    if (nullableInts.lookup(66, true, 2, "ok", null, null) != 66) {
        return "nullable first fixed route"
    }
    if (nullableInts.lookup(null, true, 2, "ok", null, 67) != null) {
        return "nullable selected-null fixed route"
    }
    return "OK"
}
