// DOTNET_GENERIC_OWNER_PHYSICAL_VALUE_SHADOW_PROBE
// DOTNET_GENERIC_OWNER_PHYSICAL_VALUE_PLACEMENT_SOURCE_PROBE

private class ShadowOwner<out T>(private val value: T) {
    @Suppress("UNCHECKED_CAST", "REDUNDANT_PROJECTION")
    fun observe(candidate: @UnsafeVariance T): Boolean {
        val genuinelyBroadAsObject: Any? = candidate
        if (candidate !is ShadowOwner<*>) return false

        val sourceDeclaredExactWidening: ShadowOwner<Any?> = this
        val exactWideningAlias: ShadowOwner<Any?> = sourceDeclaredExactWidening
        val exactWideningAsObject: Any? = exactWideningAlias
        val exactObjectAliasRead: Any? = exactWideningAsObject
        val exactReceiverAsObject: Any? = this
        val exactAliasReadAgain: Any? = exactReceiverAsObject
        val broadSameLogicalGeneric: ShadowOwner<Any?> = candidate
        val directStarProjection: ShadowOwner<*> = this
        val nestedStarProjection: ShadowOwner<ShadowOwner<*>>? = null
        val nonInvariantProjection: ShadowOwner<out Any?> = this
        var mutableMixedAsObject: Any? = this
        mutableMixedAsObject = candidate
        val explicitlyCastWidening: ShadowOwner<Any?>? =
            exactWideningAsObject as? ShadowOwner<Any?>
        val unsupportedControlFlowJoin: Any? =
            if (candidate === this) candidate as Any? else this as Any?
        val unsupportedLexicalLastJoin: Any? = run {
            if (candidate === this) return@run candidate as Any?
            this as Any?
        }
        return sourceDeclaredExactWidening === this &&
                exactWideningAlias === this &&
                exactWideningAsObject === this &&
                exactObjectAliasRead === this &&
                exactReceiverAsObject === this &&
                exactAliasReadAgain === exactReceiverAsObject &&
                genuinelyBroadAsObject === candidate &&
                broadSameLogicalGeneric === candidate &&
                directStarProjection === this &&
                nestedStarProjection == null &&
                nonInvariantProjection === this &&
                mutableMixedAsObject === candidate &&
                (explicitlyCastWidening == null || explicitlyCastWidening === this) &&
                unsupportedControlFlowJoin === this &&
                unsupportedLexicalLastJoin === this
    }
}

fun box(): String {
    val exact = ShadowOwner(42)
    val widened: ShadowOwner<Any?> = exact
    return if (widened.observe(ShadowOwner("broad"))) "OK" else "identity"
}
