// DOTNET_GENERIC_OWNER_PHYSICAL_VALUE_SHADOW_PROBE

private class ShadowOwner<out T>(private val value: T) {
    @Suppress("UNCHECKED_CAST")
    fun observe(candidate: @UnsafeVariance T): Boolean {
        val exactReceiverAsObject: Any? = this
        val exactAliasReadAgain: Any? = exactReceiverAsObject
        val genuinelyBroadAsObject: Any? = candidate
        var mutableMixedAsObject: Any? = this
        mutableMixedAsObject = candidate
        val uncheckedCastAsObject: Any? = candidate as? ShadowOwner<T>
        return exactReceiverAsObject === this &&
                exactAliasReadAgain === exactReceiverAsObject &&
                genuinelyBroadAsObject === candidate &&
                mutableMixedAsObject === candidate &&
                uncheckedCastAsObject == null
    }
}

fun box(): String {
    val exact = ShadowOwner(42)
    val widened: ShadowOwner<Any?> = exact
    return if (widened.observe("broad")) "OK" else "identity"
}
