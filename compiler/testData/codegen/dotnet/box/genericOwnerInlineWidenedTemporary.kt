// DOTNET_GENERIC_OWNER_PHYSICAL_VALUE_PLACEMENT_COMPILER_ALIAS_PROBE
// DOTNET_GENERIC_OWNER_PHYSICAL_OPERATION_ROUTE_PROBE
// DOTNET_GENERIC_OWNER_COMPLETE_EMISSION_PROBE

// Kotlin inference may choose Any? for the covariant receiver of an inline helper even when the
// call originates on an exact generic owner. The inliner's immutable argument temporaries must
// retain that owner's natural CLR construction instead of fabricating Producer<object> or
// degrading the whole typed member body to the semantic carrier.

interface InlineProducer<out T> {
    fun produce(): T
}

interface InlineLookup<K, out V> {
    fun lookup(key: K): V?
}

interface InlineSplitLocalProducer<out T> {
    fun read(): T?
    fun readThroughLocal(): T?
    fun readThroughMaterialization(): T?
    fun readThroughProtectedRegion(): T?
}

interface InlineMethodProducer<out T> {
    fun <R> produce(marker: R): T
}

interface InlineMethodLookup<K, out V> {
    fun <R> lookup(key: K, marker: R): V?
}

private class InlineLookupRoute<T> {
    fun routeExactArgument(source: InlineLookup<T, T>, key: T): T? {
        val sourceNaturalAlias: InlineLookup<T, T> = source
        val exactArgumentAlias: T = key
        return sourceNaturalAlias.lookup(exactArgumentAlias)
    }

    fun routeWidenedResult(source: InlineLookup<T, T>, key: T): Any? {
        val sourceNaturalAlias: InlineLookup<T, T> = source
        val sourceWideAlias: InlineLookup<T, Any?> = sourceNaturalAlias
        val exactArgumentAlias: T = key
        return sourceWideAlias.lookup(exactArgumentAlias)
    }
}

private class InlineArgumentSplitLocalRoute<T> : InlineLookup<T, T> {
    override fun lookup(key: T): T? {
        val sourceNaturalAlias: InlineLookup<T, T> = object : InlineLookup<T, T> {
            override fun lookup(key: T): T? = key
        }
        val exactArgumentAlias: T = key
        val exactResultAlias: T? = sourceNaturalAlias.lookup(exactArgumentAlias)
        return exactResultAlias
    }
}

private class InlineMethodSpecSplitLocalRoute<T> : InlineLookup<T, T> {
    override fun lookup(key: T): T? {
        val methodSpecSourceNaturalAlias: InlineMethodLookup<T, T> =
            object : InlineMethodLookup<T, T> {
                override fun <S> lookup(key: T, marker: S): T? = key
            }
        val methodSpecKeyAlias: T = key
        val methodSpecMarkerAlias: T = key
        val methodSpecResultAlias: T? =
            methodSpecSourceNaturalAlias.lookup<T>(
                methodSpecKeyAlias,
                methodSpecMarkerAlias,
            )
        return methodSpecResultAlias
    }
}

private class InlineSplitLocalRoute<T>(private val value: T?) : InlineSplitLocalProducer<T> {
    override fun read(): T? = value

    override fun readThroughLocal(): T? {
        val sourceNaturalAlias: InlineSplitLocalProducer<T> = this
        val exactResultAlias: T? = sourceNaturalAlias.read()
        return exactResultAlias
    }

    private fun materialize(value: T?): T? = value

    override fun readThroughMaterialization(): T? {
        val sourceNaturalAlias: InlineSplitLocalProducer<T> = this
        val materializedResultAlias: T? = sourceNaturalAlias.read()
        return materialize(materializedResultAlias)
    }

    override fun readThroughProtectedRegion(): T? {
        val sourceNaturalAlias: InlineSplitLocalProducer<T> = this
        val protectedResultAlias: T? = sourceNaturalAlias.read()
        try {
            return protectedResultAlias
        } finally {
            inlineSplitFinallyCount++
        }
    }
}

private var inlineSplitFinallyCount = 0

private class InlineIntLookup : InlineLookup<Int, Int> {
    override fun lookup(key: Int): Int? = key.takeUnless { it < 0 }
}

private class InlineStringLookup : InlineLookup<String, String> {
    override fun lookup(key: String): String? = key.takeUnless { it.isEmpty() }
}

private class InlineMethodProducerRoute<T> {
    fun routeExactMethodArgument(
        source: InlineMethodProducer<T>,
        marker: T,
    ): T {
        val sourceNaturalAlias: InlineMethodProducer<T> = source
        val exactMarkerAlias: T = marker
        return sourceNaturalAlias.produce<T>(exactMarkerAlias)
    }

    fun routeWidenedMethodResult(
        source: InlineMethodProducer<T>,
        marker: T,
    ): Any? {
        val sourceNaturalAlias: InlineMethodProducer<T> = source
        val sourceWideAlias: InlineMethodProducer<Any?> = sourceNaturalAlias
        val exactMarkerAlias: T = marker
        return sourceWideAlias.produce<T>(exactMarkerAlias)
    }

    fun <R> routeCallerMethodArgument(
        source: InlineMethodProducer<T>,
        marker: R,
    ): T {
        val sourceNaturalAlias: InlineMethodProducer<T> = source
        val callerMarkerAlias: R = marker
        return sourceNaturalAlias.produce<R>(callerMarkerAlias)
    }
}

private class InlineIntMethodProducer(private val value: Int) : InlineMethodProducer<Int> {
    override fun <R> produce(marker: R): Int {
        if (marker != value) error("unexpected Int MethodSpec marker")
        return value
    }
}

private class InlineStringMethodProducer(private val value: String) : InlineMethodProducer<String> {
    override fun <R> produce(marker: R): String {
        if (marker != value) error("unexpected String MethodSpec marker")
        return value
    }
}

private class InlineMethodLookupRoute<T> {
    fun routeExactMethodLookup(
        source: InlineMethodLookup<T, T>,
        key: T,
        marker: T,
    ): T? {
        val sourceNaturalAlias: InlineMethodLookup<T, T> = source
        val exactKeyAlias: T = key
        val exactMarkerAlias: T = marker
        return sourceNaturalAlias.lookup<T>(exactKeyAlias, exactMarkerAlias)
    }

    fun routeWidenedMethodLookup(
        source: InlineMethodLookup<T, T>,
        key: T,
        marker: T,
    ): Any? {
        val sourceNaturalAlias: InlineMethodLookup<T, T> = source
        val sourceWideAlias: InlineMethodLookup<T, Any?> = sourceNaturalAlias
        val exactKeyAlias: T = key
        val exactMarkerAlias: T = marker
        return sourceWideAlias.lookup<T>(exactKeyAlias, exactMarkerAlias)
    }

    fun <R> routeCallerMethodLookup(
        source: InlineMethodLookup<T, T>,
        key: T,
        marker: R,
    ): T? {
        val sourceNaturalAlias: InlineMethodLookup<T, T> = source
        val exactKeyAlias: T = key
        val callerMarkerAlias: R = marker
        return sourceNaturalAlias.lookup<R>(exactKeyAlias, callerMarkerAlias)
    }
}

private class InlineIntMethodLookup : InlineMethodLookup<Int, Int> {
    override fun <R> lookup(key: Int, marker: R): Int? {
        if (marker != key) error("unexpected Int split MethodSpec marker")
        return key.takeUnless { it < 0 }
    }
}

private class InlineStringMethodLookup : InlineMethodLookup<String, String> {
    override fun <R> lookup(key: String, marker: R): String? {
        if (marker != key) error("unexpected String split MethodSpec marker")
        return key.takeUnless { it.isEmpty() }
    }
}

private inline fun <T> InlineProducer<T>.indexOfFirst(
    predicate: (T) -> Boolean,
): Int = if (predicate(produce())) 0 else -1

private class InlineSelfView<T>(private val value: T) : InlineProducer<T> {
    override fun produce(): T = value

    fun indexOf(element: T): Int = indexOfFirst { it == element }

    fun sourceAliasMatches(element: T): Boolean {
        val sourceNaturalAlias: InlineProducer<T> = this
        val exactResultAlias: T = sourceNaturalAlias.produce()
        return exactResultAlias == element
    }

    fun parameterAliasMatches(candidate: T, expected: T): Boolean {
        val exactParameterAlias: T = candidate
        return exactParameterAlias == expected
    }

    fun wideAliasMatches(element: T): Boolean {
        val sourceWideAlias: InlineProducer<Any?> = this
        return sourceWideAlias.produce() == element
    }

    fun nullableAliasMatches(element: T): Boolean {
        val sourceNullableAlias: InlineProducer<T?> = this
        return sourceNullableAlias.produce() == element
    }

    fun nestedAliasMatches(element: T): Boolean {
        val sourceNestedAlias: InlineProducer<Any?> = this
        val nested = { sourceNestedAlias.produce() == element }
        return nested()
    }

    fun starAliasMatches(): Boolean {
        val sourceStarAlias: InlineProducer<*> = this
        return sourceStarAlias === this && sourceStarAlias.produce() == value
    }

    fun mutableAliasTracks(other: InlineProducer<T>): Boolean {
        var sourceMutableAlias: InlineProducer<Any?> = this
        if (sourceMutableAlias !== this) return false
        sourceMutableAlias = other
        return sourceMutableAlias === other
    }

    fun joinedAliasMatches(
        selectThis: Boolean,
        otherValue: T,
        expected: T,
    ): Boolean {
        val other = InlineSecondView(otherValue)
        val sourceJoinedAlias: InlineProducer<Any?> = if (selectThis) this else other
        val selected: Any? = if (selectThis) this else other
        return sourceJoinedAlias === selected && sourceJoinedAlias.produce() == expected
    }
}

// Both implementations share InlineProducer's semantic declaration slot. Their explicit
// MethodImpl rows must remain owned by their respective implementation families.
private class InlineSecondView<T>(private val value: T) : InlineProducer<T> {
    override fun produce(): T = value
}

fun box(): String {
    if (InlineLookupRoute<Int>().routeExactArgument(InlineIntLookup(), 42) != 42) {
        return "value argument route"
    }
    if (InlineLookupRoute<Int>().routeExactArgument(InlineIntLookup(), -1) != null) {
        return "value null argument route"
    }
    if (InlineLookupRoute<String>().routeExactArgument(InlineStringLookup(), "typed") != "typed") {
        return "reference result argument route"
    }
    if (InlineLookupRoute<String>().routeExactArgument(InlineStringLookup(), "") != null) {
        return "reference null result argument route"
    }
    if (InlineArgumentSplitLocalRoute<Int>().lookup(52) != 52) {
        return "value argument split local route"
    }
    if (InlineArgumentSplitLocalRoute<String>().lookup("argument split") !=
        "argument split"
    ) {
        return "reference argument split local route"
    }
    if (InlineArgumentSplitLocalRoute<Int?>().lookup(null) != null) {
        return "nullable value argument split local route"
    }
    if (InlineMethodSpecSplitLocalRoute<Int>().lookup(56) != 56) {
        return "value MethodSpec split local route"
    }
    if (InlineMethodSpecSplitLocalRoute<String>().lookup("method split local") !=
        "method split local"
    ) {
        return "reference MethodSpec split local route"
    }
    if (InlineMethodSpecSplitLocalRoute<Int?>().lookup(57) != 57) {
        return "nullable value MethodSpec split local value route"
    }
    if (InlineMethodSpecSplitLocalRoute<Int?>().lookup(null) != null) {
        return "nullable value MethodSpec split local route"
    }
    if (InlineSplitLocalRoute(52).readThroughLocal() != 52) {
        return "value split local route"
    }
    if (InlineSplitLocalRoute<Int>(null).readThroughLocal() != null) {
        return "value null split local argument route"
    }
    if (InlineSplitLocalRoute("split local").readThroughLocal() != "split local") {
        return "reference split local argument route"
    }
    if (InlineSplitLocalRoute<String>(null).readThroughLocal() != null) {
        return "reference null split local argument route"
    }
    if (InlineSplitLocalRoute<Int?>(53).readThroughLocal() != 53) {
        return "nullable value split local argument route"
    }
    if (InlineSplitLocalRoute<Int?>(null).readThroughLocal() != null) {
        return "nullable value null split local argument route"
    }
    if (InlineSplitLocalRoute(54).readThroughMaterialization() != 54) {
        return "ordinary split materialization route"
    }
    if (InlineSplitLocalRoute<Int>(null).readThroughMaterialization() != null) {
        return "ordinary null split materialization route"
    }
    val finallyCountBefore = inlineSplitFinallyCount
    if (InlineSplitLocalRoute(55).readThroughProtectedRegion() != 55 ||
        inlineSplitFinallyCount != finallyCountBefore + 1
    ) {
        return "protected split materialization route"
    }
    if (InlineLookupRoute<Int>().routeWidenedResult(InlineIntLookup(), 43) != 43) {
        return "value widened result route"
    }
    if (InlineLookupRoute<Int>().routeWidenedResult(InlineIntLookup(), -1) != null) {
        return "value null widened result route"
    }
    if (InlineLookupRoute<String>().routeWidenedResult(InlineStringLookup(), "wide") != "wide") {
        return "reference widened result route"
    }
    if (InlineLookupRoute<String>().routeWidenedResult(InlineStringLookup(), "") != null) {
        return "reference null widened result route"
    }

    if (InlineMethodProducerRoute<Int>().routeExactMethodArgument(
            InlineIntMethodProducer(44), 44
        ) != 44) {
        return "value MethodSpec route"
    }
    if (InlineMethodProducerRoute<String>().routeExactMethodArgument(
            InlineStringMethodProducer("method"), "method"
        ) != "method") {
        return "reference MethodSpec route"
    }
    if (InlineMethodProducerRoute<Int>().routeWidenedMethodResult(
            InlineIntMethodProducer(46), 46
        ) != 46) {
        return "value widened MethodSpec route"
    }
    if (InlineMethodProducerRoute<String>().routeWidenedMethodResult(
            InlineStringMethodProducer("wide method"), "wide method"
        ) != "wide method") {
        return "reference widened MethodSpec route"
    }
    if (InlineMethodProducerRoute<Int>().routeCallerMethodArgument(
            InlineIntMethodProducer(48), 48
        ) != 48) {
        return "caller MethodDef MethodSpec route"
    }

    val intMethodLookupRoute = InlineMethodLookupRoute<Int>()
    if (intMethodLookupRoute.routeExactMethodLookup(InlineIntMethodLookup(), 49, 49) != 49) {
        return "value split MethodSpec route"
    }
    if (intMethodLookupRoute.routeExactMethodLookup(InlineIntMethodLookup(), -1, -1) != null) {
        return "value null split MethodSpec route"
    }
    val stringMethodLookupRoute = InlineMethodLookupRoute<String>()
    if (stringMethodLookupRoute.routeExactMethodLookup(
            InlineStringMethodLookup(), "split method", "split method"
        ) != "split method") {
        return "reference split MethodSpec route"
    }
    if (stringMethodLookupRoute.routeExactMethodLookup(
            InlineStringMethodLookup(), "", ""
        ) != null) {
        return "reference null split MethodSpec route"
    }
    if (intMethodLookupRoute.routeWidenedMethodLookup(
            InlineIntMethodLookup(), 50, 50
        ) != 50) {
        return "value widened split MethodSpec route"
    }
    if (intMethodLookupRoute.routeWidenedMethodLookup(
            InlineIntMethodLookup(), -1, -1
        ) != null) {
        return "value null widened split MethodSpec route"
    }
    if (stringMethodLookupRoute.routeWidenedMethodLookup(
            InlineStringMethodLookup(), "wide split", "wide split"
        ) != "wide split") {
        return "reference widened split MethodSpec route"
    }
    if (intMethodLookupRoute.routeCallerMethodLookup(InlineIntMethodLookup(), 51, 51) != 51) {
        return "caller MethodDef split MethodSpec route"
    }

    val ints = InlineSelfView(42)
    if (ints.indexOf(42) != 0 || ints.indexOf(43) != -1) return "value self-view"
    if (!ints.sourceAliasMatches(42) || ints.sourceAliasMatches(43)) return "value source alias"
    if (!ints.parameterAliasMatches(42, 42) || ints.parameterAliasMatches(42, 43)) {
        return "value parameter alias"
    }
    if (!ints.wideAliasMatches(42) || ints.wideAliasMatches(43)) return "value wide alias"
    if (!ints.nullableAliasMatches(42) || ints.nullableAliasMatches(43)) return "value nullable alias"
    if (!ints.nestedAliasMatches(42) || ints.nestedAliasMatches(43)) return "value nested alias"

    val strings = InlineSelfView("inline")
    if (strings.indexOf("inline") != 0 || strings.indexOf("other") != -1) {
        return "reference self-view"
    }
    if (!strings.sourceAliasMatches("inline") || strings.sourceAliasMatches("other")) {
        return "reference source alias"
    }
    if (!strings.parameterAliasMatches("inline", "inline") ||
        strings.parameterAliasMatches("inline", "other")
    ) {
        return "reference parameter alias"
    }
    if (!strings.wideAliasMatches("inline") || strings.wideAliasMatches("other")) {
        return "reference wide alias"
    }
    if (!strings.nullableAliasMatches("inline") || strings.nullableAliasMatches("other")) {
        return "reference nullable alias"
    }
    if (!strings.nestedAliasMatches("inline") || strings.nestedAliasMatches("other")) {
        return "reference nested alias"
    }
    if (!ints.starAliasMatches() || !strings.starAliasMatches()) return "star alias"
    val otherInts = InlineSecondView(7)
    val otherStrings = InlineSecondView("other")
    if (!ints.mutableAliasTracks(otherInts) || !strings.mutableAliasTracks(otherStrings)) {
        return "mutable alias"
    }
    if (!ints.joinedAliasMatches(true, 7, 42) ||
        !ints.joinedAliasMatches(false, 7, 7) ||
        !strings.joinedAliasMatches(true, "other", "inline") ||
        !strings.joinedAliasMatches(false, "other", "other")
    ) {
        return "joined alias"
    }
    // A source-declared wide variable remains a semantic Kotlin view. In particular, its
    // physical carrier must not be pinned to the first exact value by the temporary fast path.
    var widened: InlineProducer<Any?> = ints
    if (widened !== ints || widened.produce() != 42) return "value widened view"
    widened = strings
    if (widened !== strings || widened.produce() != "inline") return "reference widened view"

    // Execute the second implementation through the same shared semantic declaration slot. Its
    // private MethodImpl body must remain distinct from InlineSelfView's dispatcher.
    val secondExact = otherInts
    val second: InlineProducer<Any?> = secondExact
    if (second !== secondExact || second.produce() != 7) return "second implementation"

    return "OK"
}
