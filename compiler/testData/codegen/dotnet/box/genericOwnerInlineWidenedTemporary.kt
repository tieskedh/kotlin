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

interface InlineConstructedSource<out T> {
    fun source(): InlineProducer<T>
}

interface InlineLookup<K, out V> {
    fun lookup(key: K): V?
}

interface InlineRepeatedInputLookup<K, out V> {
    fun lookup(first: K, second: K): V?
}

// Repeated owner inputs do not yet compose with a MethodSpec. This declaration must remain
// outside the candidate generic-interface family until that mixed vector has its own proof.
interface InlineRepeatedMethodInputLookup<K, out V> {
    fun <R> lookup(first: K, second: K, marker: R): V?
}

interface InlineSplitLocalProducer<out T> {
    fun read(): T?
    fun readThroughLocal(returnFirst: Boolean): T?
    fun readThroughControlFlow(selectFirst: Boolean): T?
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

private class InlineConstructedSourceValue<T>(
    private val nested: InlineProducer<T>,
) : InlineConstructedSource<T> {
    override fun source(): InlineProducer<T> = nested
}

private fun selectInlineConstructedSource(first: Boolean): Boolean = first

private class InlineConstructedCallRoute<T> {
    fun sourceThroughLocal(
        source: InlineConstructedSource<T>,
    ): InlineProducer<T> {
        val callResultNaturalAlias: InlineProducer<T> = source.source()
        return callResultNaturalAlias
    }

    fun sourceThroughWidenedLocal(
        source: InlineConstructedSource<T>,
    ): Any? {
        val widenedSourceAlias: InlineConstructedSource<Any?> = source
        val widenedCallResultAlias: InlineProducer<Any?> = widenedSourceAlias.source()
        val widenedCallResultCopyAlias: InlineProducer<Any?> = widenedCallResultAlias
        return widenedCallResultCopyAlias
    }

    fun sourceThroughBroadEntry(
        source: InlineConstructedSource<Any?>,
    ): Any? {
        val genuinelyBroadSourceAlias: InlineConstructedSource<Any?> = source
        val genuinelyBroadCallResultAlias: InlineProducer<Any?> =
            genuinelyBroadSourceAlias.source()
        return genuinelyBroadCallResultAlias
    }

    fun sourceThroughPathCompleteControlFlow(
        first: InlineConstructedSource<T>,
        second: InlineConstructedSource<T>,
        selectFirst: Boolean,
    ): InlineProducer<T> {
        val pathCompleteCallResultAlias: InlineProducer<T> =
            if (selectInlineConstructedSource(selectFirst)) {
                first.source()
            } else {
                second.source()
            }
        return pathCompleteCallResultAlias
    }
}

private class InlineRepeatedInputSplitLocalRoute<T> : InlineRepeatedInputLookup<T, T> {
    override fun lookup(first: T, second: T): T? {
        val repeatedInputSourceNaturalAlias: InlineRepeatedInputLookup<T, T> =
            object : InlineRepeatedInputLookup<T, T> {
                override fun lookup(first: T, second: T): T? =
                    if (first == second) null else second
            }
        val repeatedFirstArgumentAlias: T = first
        val repeatedSecondArgumentAlias: T = second
        val repeatedInputResultAlias: T? = repeatedInputSourceNaturalAlias.lookup(
            repeatedFirstArgumentAlias,
            repeatedSecondArgumentAlias,
        )
        return repeatedInputResultAlias
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

    override fun readThroughLocal(returnFirst: Boolean): T? {
        val sourceNaturalAlias: InlineSplitLocalProducer<T> = this
        val exactResultAlias: T? = sourceNaturalAlias.read()
        if (returnFirst) return exactResultAlias
        return exactResultAlias
    }

    override fun readThroughControlFlow(selectFirst: Boolean): T? {
        val firstNaturalAlias: InlineSplitLocalProducer<T> = this
        val secondNaturalAlias: InlineSplitLocalProducer<T> = this
        val controlFlowResultAlias: T? = if (selectFirst) {
            firstNaturalAlias.read()
        } else {
            secondNaturalAlias.read()
        }
        return controlFlowResultAlias
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

    fun <R> routeCallerMethodArgumentAfterPrefixes(
        source: InlineMethodProducer<T>,
        marker: R,
    ): T {
        val orderedResultAlias: T = kotlin.run {
            val orderedSourceAlias: InlineMethodProducer<T> = source
            val orderedMarkerAlias: R = marker
            orderedSourceAlias.produce<R>(orderedMarkerAlias)
        }
        return orderedResultAlias
    }

    fun <R> routeWidenedCallerMethodArgument(
        source: InlineMethodProducer<T>,
        marker: R,
    ): T {
        val sourceNaturalAlias: InlineMethodProducer<T> = source
        val sourceWideAlias: InlineMethodProducer<Any?> = sourceNaturalAlias
        val callerMarkerAlias: R = marker
        sourceWideAlias.produce<R>(callerMarkerAlias)
        return sourceNaturalAlias.produce<R>(callerMarkerAlias)
    }

    fun routePrivateCallerMethodArgument(
        source: InlineMethodProducer<T>,
        marker: T,
    ): T = privateCallerMethodArgument<T>(source, marker)

    private fun <R> privateCallerMethodArgument(
        source: InlineMethodProducer<T>,
        marker: R,
    ): T {
        val privateCallerMarkerAlias: R = marker
        return source.produce<R>(privateCallerMarkerAlias)
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

private class InlineStringMarkedIntProducer(
    private val value: Int,
    private val expectedMarker: String,
) : InlineMethodProducer<Int> {
    override fun <R> produce(marker: R): Int {
        if (marker != expectedMarker) error("unexpected reference MethodSpec marker")
        return value
    }
}

private class InlineIntMarkedStringProducer(
    private val value: String,
    private val expectedMarker: Int,
) : InlineMethodProducer<String> {
    override fun <R> produce(marker: R): String {
        if (marker != expectedMarker) error("unexpected mixed MethodSpec marker")
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

    // Entry provenance for a constructed receiver and owner parameters is a separate proof.
    // A fixed-leaf hand-off must not make this admitted MethodSpec route exact by accident.
    fun routeDirectParameterMethodLookup(
        source: InlineMethodLookup<T, T>,
        key: T,
    ): T? {
        val directParameterMethodSpecResultAlias: T? = source.lookup<T>(key, key)
        return directParameterMethodSpecResultAlias
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

    fun mutableAliasTracks(other: InlineProducer<T>, expected: T): Boolean {
        var sourceMutableAlias: InlineProducer<Any?> = this
        if (sourceMutableAlias !== this) return false
        sourceMutableAlias = other
        return sourceMutableAlias === other && sourceMutableAlias.produce() == expected
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
    val inlineIntProducer = InlineSecondView(63)
    val inlineIntSource = InlineConstructedSourceValue(inlineIntProducer)
    val intCallResult = InlineConstructedCallRoute<Int>().sourceThroughLocal(
        inlineIntSource,
    )
    if (intCallResult !== inlineIntProducer || intCallResult.produce() != 63) {
        return "value constructed call-result route"
    }
    val inlineStringProducer = InlineSecondView("call result")
    val inlineStringSource = InlineConstructedSourceValue(inlineStringProducer)
    val stringCallResult = InlineConstructedCallRoute<String>().sourceThroughLocal(
        inlineStringSource,
    )
    if (stringCallResult !== inlineStringProducer || stringCallResult.produce() != "call result") {
        return "reference constructed call-result route"
    }
    val secondInlineIntProducer = InlineSecondView(64)
    val secondInlineIntSource = InlineConstructedSourceValue(secondInlineIntProducer)
    val pathCompleteIntRoute = InlineConstructedCallRoute<Int>()
    if (pathCompleteIntRoute.sourceThroughPathCompleteControlFlow(
            inlineIntSource,
            secondInlineIntSource,
            true,
        ) !== inlineIntProducer ||
        pathCompleteIntRoute.sourceThroughPathCompleteControlFlow(
            inlineIntSource,
            secondInlineIntSource,
            false,
        ) !== secondInlineIntProducer
    ) {
        return "value path-complete constructed call-result route"
    }
    val secondInlineStringProducer = InlineSecondView("other call result")
    val secondInlineStringSource = InlineConstructedSourceValue(secondInlineStringProducer)
    val pathCompleteStringRoute = InlineConstructedCallRoute<String>()
    if (pathCompleteStringRoute.sourceThroughPathCompleteControlFlow(
            inlineStringSource,
            secondInlineStringSource,
            true,
        ) !== inlineStringProducer ||
        pathCompleteStringRoute.sourceThroughPathCompleteControlFlow(
            inlineStringSource,
            secondInlineStringSource,
            false,
        ) !== secondInlineStringProducer
    ) {
        return "reference path-complete constructed call-result route"
    }
    val widenedIntCallResult = InlineConstructedCallRoute<Int>().sourceThroughWidenedLocal(
        inlineIntSource,
    )
    if (widenedIntCallResult !== inlineIntProducer || inlineIntProducer.produce() != 63) {
        return "value widened constructed call-result route"
    }
    val widenedStringCallResult = InlineConstructedCallRoute<String>().sourceThroughWidenedLocal(
        inlineStringSource,
    )
    if (widenedStringCallResult !== inlineStringProducer ||
        inlineStringProducer.produce() != "call result"
    ) {
        return "reference widened constructed call-result route"
    }
    val genuinelyBroadIntProducer = InlineSecondView<Any?>(63)
    val genuinelyBroadIntSource = InlineConstructedSourceValue<Any?>(
        genuinelyBroadIntProducer,
    )
    val broadIntCallResult = InlineConstructedCallRoute<String>().sourceThroughBroadEntry(
        genuinelyBroadIntSource,
    )
    if (broadIntCallResult !== genuinelyBroadIntProducer ||
        genuinelyBroadIntProducer.produce() != 63
    ) {
        return "value genuinely broad constructed call-result route"
    }
    val genuinelyBroadStringProducer = InlineSecondView<Any?>("call result")
    val genuinelyBroadStringSource = InlineConstructedSourceValue<Any?>(
        genuinelyBroadStringProducer,
    )
    val broadStringCallResult = InlineConstructedCallRoute<Int>().sourceThroughBroadEntry(
        genuinelyBroadStringSource,
    )
    if (broadStringCallResult !== genuinelyBroadStringProducer ||
        genuinelyBroadStringProducer.produce() != "call result"
    ) {
        return "reference genuinely broad constructed call-result route"
    }
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
    if (InlineRepeatedInputSplitLocalRoute<Int>().lookup(60, 61) != 61) {
        return "value repeated-input split local route"
    }
    if (InlineRepeatedInputSplitLocalRoute<Int>().lookup(60, 60) != null) {
        return "value repeated-input null split local route"
    }
    if (InlineRepeatedInputSplitLocalRoute<String>().lookup("first", "second") != "second") {
        return "reference repeated-input split local route"
    }
    if (InlineRepeatedInputSplitLocalRoute<Int?>().lookup(null, 62) != 62) {
        return "nullable value repeated-input split local route"
    }
    if (InlineRepeatedInputSplitLocalRoute<Int?>().lookup(null, null) != null) {
        return "nullable value repeated-input null split local route"
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
    if (InlineSplitLocalRoute(52).readThroughLocal(true) != 52 ||
        InlineSplitLocalRoute(52).readThroughLocal(false) != 52
    ) {
        return "value multi-return split local route"
    }
    if (InlineSplitLocalRoute<Int>(null).readThroughLocal(true) != null ||
        InlineSplitLocalRoute<Int>(null).readThroughLocal(false) != null
    ) {
        return "value null multi-return split local route"
    }
    if (InlineSplitLocalRoute("split local").readThroughLocal(true) != "split local" ||
        InlineSplitLocalRoute("split local").readThroughLocal(false) != "split local"
    ) {
        return "reference multi-return split local route"
    }
    if (InlineSplitLocalRoute<String>(null).readThroughLocal(true) != null ||
        InlineSplitLocalRoute<String>(null).readThroughLocal(false) != null
    ) {
        return "reference null multi-return split local route"
    }
    if (InlineSplitLocalRoute<Int?>(53).readThroughLocal(true) != 53 ||
        InlineSplitLocalRoute<Int?>(53).readThroughLocal(false) != 53
    ) {
        return "nullable value multi-return split local route"
    }
    if (InlineSplitLocalRoute<Int?>(null).readThroughLocal(true) != null ||
        InlineSplitLocalRoute<Int?>(null).readThroughLocal(false) != null
    ) {
        return "nullable value null multi-return split local route"
    }
    if (InlineSplitLocalRoute(58).readThroughControlFlow(true) != 58 ||
        InlineSplitLocalRoute(58).readThroughControlFlow(false) != 58
    ) {
        return "value control-flow split local route"
    }
    if (InlineSplitLocalRoute<Int>(null).readThroughControlFlow(true) != null ||
        InlineSplitLocalRoute<Int>(null).readThroughControlFlow(false) != null
    ) {
        return "value null control-flow split local route"
    }
    if (InlineSplitLocalRoute("control flow").readThroughControlFlow(true) != "control flow" ||
        InlineSplitLocalRoute("control flow").readThroughControlFlow(false) != "control flow"
    ) {
        return "reference control-flow split local route"
    }
    if (InlineSplitLocalRoute<String>(null).readThroughControlFlow(true) != null ||
        InlineSplitLocalRoute<String>(null).readThroughControlFlow(false) != null
    ) {
        return "reference null control-flow split local route"
    }
    if (InlineSplitLocalRoute<Int?>(59).readThroughControlFlow(true) != 59 ||
        InlineSplitLocalRoute<Int?>(59).readThroughControlFlow(false) != 59
    ) {
        return "nullable value control-flow split local route"
    }
    if (InlineSplitLocalRoute<Int?>(null).readThroughControlFlow(true) != null ||
        InlineSplitLocalRoute<Int?>(null).readThroughControlFlow(false) != null
    ) {
        return "nullable value null control-flow split local route"
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
    val distinctCallerMarker: Any? = "distinct caller MethodDef"
    if (InlineMethodProducerRoute<String>().routeCallerMethodArgument(
            InlineStringMethodProducer("distinct caller MethodDef"), distinctCallerMarker
        ) != "distinct caller MethodDef") {
        return "distinct owner and caller MethodDef parameters"
    }
    if (InlineMethodProducerRoute<String>().routeCallerMethodArgument(
            InlineIntMarkedStringProducer("value caller MethodDef", 53), 53
        ) != "value caller MethodDef") {
        return "reference owner and value caller MethodDef parameters"
    }
    if (InlineMethodProducerRoute<String>().routeCallerMethodArgumentAfterPrefixes(
            InlineIntMarkedStringProducer("prefixed value caller MethodDef", 55), 55
        ) != "prefixed value caller MethodDef") {
        return "ordered prefix reference owner and value caller MethodDef parameters"
    }
    if (InlineMethodProducerRoute<Int>().routeCallerMethodArgumentAfterPrefixes(
            InlineStringMarkedIntProducer(56, "prefixed reference caller MethodDef"),
            "prefixed reference caller MethodDef",
        ) != 56) {
        return "ordered prefix value owner and reference caller MethodDef parameters"
    }
    if (InlineMethodProducerRoute<String>().routeWidenedCallerMethodArgument(
            InlineIntMarkedStringProducer("widened caller MethodDef", 54), 54
        ) != "widened caller MethodDef") {
        return "widened receiver and value caller MethodDef parameters"
    }
    if (InlineMethodProducerRoute<String>().routePrivateCallerMethodArgument(
            InlineStringMethodProducer("private caller MethodDef"), "private caller MethodDef"
        ) != "private caller MethodDef") {
        return "private caller MethodDef entry"
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
    if (intMethodLookupRoute.routeDirectParameterMethodLookup(InlineIntMethodLookup(), 52) != 52) {
        return "direct parameter split MethodSpec route"
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
    if (!ints.mutableAliasTracks(otherInts, 7) ||
        !strings.mutableAliasTracks(otherStrings, "other")
    ) {
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
