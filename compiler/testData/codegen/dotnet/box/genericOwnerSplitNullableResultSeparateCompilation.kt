// DOTNET_GENERIC_OWNER_SPLIT_NULLABLE_CSHARP_PROBE
// MODULE: lib
// FILE: contracts.kt

package generic.owner.split.nullable

/** Name-independent first proof for the physical `T + out bool isNull` result convention. */
public interface NullableSource<out T> {
    public fun read(missing: Boolean): T?
}

/** Two same-name/same-regular-arity slots must remain distinct physical MethodDefs. */
public interface OverloadedNullableSource<out T> {
    public fun read(missing: Boolean): T?

    public fun read(index: Int): T?
}

public class NullableSourceReader {
    public fun readInt(source: NullableSource<Int>, missing: Boolean): Int? = source.read(missing)

    public fun readWide(source: NullableSource<Any?>, missing: Boolean): Any? = source.read(missing)

    public fun readNullableInt(source: NullableSource<Int?>, missing: Boolean): Int? = source.read(missing)
}

public fun widenIntSource(source: NullableSource<Int>): NullableSource<Any?> = source

public fun widenStringSource(source: NullableSource<String>): NullableSource<Any?> = source

/**
 * A downstream override must retain this producer-emitted split MethodDef. Its logical KLIB
 * signature remains `T?`; the consumer may neither remap that type nor select the overload by
 * name and ordinary arity.
 */
public open class ExternalSplitBase<T>(private val base: T) : NullableSource<T> {
    override fun read(missing: Boolean): T? = if (missing) null else base

    public open fun read(index: Int): T? = if (index < 0) null else base
}

/** A covariant result refinement hides the root's split layout from the source-spelled result. */
public open class LocalNestedSplit<U> : NullableSource<Any?> {
    public override fun read(missing: Boolean): NullableSource<Any?>? = null
}

// MODULE: middle(lib)
// FILE: implementations.kt

package generic.owner.split.nullable

/**
 * A separately compiled, memberless open child must retain the parent's real natural
 * construction without republishing the inherited split-nullable MethodDef.
 */
public interface OpenChild<out T> : NullableSource<T>

private class IntNullableSource(private val value: Int) : NullableSource<Int> {
    override fun read(missing: Boolean): Int? = if (missing) null else value
}

private class StringNullableSource(private val value: String) : NullableSource<String> {
    override fun read(missing: Boolean): String? = if (missing) null else value
}

private class NullableIntNullableSource(private val value: Int?) : NullableSource<Int?> {
    override fun read(missing: Boolean): Int? = if (missing) null else value
}

public fun intSource(value: Int): NullableSource<Int> = IntNullableSource(value)

public fun stringSource(value: String): NullableSource<String> = StringNullableSource(value)

public fun nullableIntSource(value: Int?): NullableSource<Int?> = NullableIntNullableSource(value)

private class OverloadedIntNullableSource(
    private val value: Int,
) : OverloadedNullableSource<Int> {
    override fun read(missing: Boolean): Int? = if (missing) null else value

    override fun read(index: Int): Int? = if (index < 0) null else value + index
}

public fun overloadedIntSource(value: Int): OverloadedNullableSource<Int> =
    OverloadedIntNullableSource(value)

private class IntOpenChild(private val value: Int) : OpenChild<Int> {
    override fun read(missing: Boolean): Int? = if (missing) null else value
}

private class StringOpenChild(private val value: String) : OpenChild<String> {
    override fun read(missing: Boolean): String? = if (missing) null else value
}

public fun intOpenChild(value: Int): OpenChild<Int> = IntOpenChild(value)

public fun stringOpenChild(value: String): OpenChild<String> = StringOpenChild(value)

/** The same negative proof must consume the external producer's recorded split layout. */
public open class ExternalNestedSplit<U> : NullableSource<Any?> {
    public override fun read(missing: Boolean): NullableSource<Any?>? = null
}

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.split.nullable

public fun downstreamWidenedRead(
    source: NullableSource<Any?>,
    missing: Boolean,
): Any? = source.read(missing)

public fun downstreamOverloadedBooleanRead(
    source: OverloadedNullableSource<Any?>,
    missing: Boolean,
): Any? = source.read(missing)

public fun downstreamOverloadedIntRead(
    source: OverloadedNullableSource<Any?>,
    index: Int,
): Any? = source.read(index)

public fun downstreamOpenChildExactIntRead(
    source: OpenChild<Int>,
    missing: Boolean,
): Int? = source.read(missing)

public fun downstreamOpenChildExactStringRead(
    source: OpenChild<String>,
    missing: Boolean,
): String? = source.read(missing)

public fun downstreamOpenChildWidenedRead(
    source: OpenChild<Any?>,
    missing: Boolean,
): Any? = source.read(missing)

public fun downstreamOpenChildSame(
    source: OpenChild<Any?>,
    expected: Any?,
): Boolean = source === expected

/** An inherited split implementation already satisfies the root MethodDef exactly. */
public open class SplitIntBase(private val value: Int) : NullableSource<Int> {
    override fun read(missing: Boolean): Int? = if (missing) null else value
}

public class ReusedIntOpenChild(value: Int) : SplitIntBase(value), OpenChild<Int>

/** The same exact inherited slot must survive substitution through a generic base. */
public open class SplitGenericBase<T>(private val value: T) : NullableSource<T> {
    override fun read(missing: Boolean): T? = if (missing) null else value
}

public class ReusedGenericOpenChild<T>(value: T) : SplitGenericBase<T>(value), OpenChild<T>

/** This ordinary Kotlin member needs a split-result adapter when it fills OpenChild<String>. */
public open class PlainStringBase(private val value: String?) {
    public open fun read(missing: Boolean): String? = if (missing) null else value
}

public class AdaptedFakeStringOpenChild(value: String?) :
    PlainStringBase(value), OpenChild<String>

/** A declared split override must not rewrite this pre-existing nullable-value MethodDef. */
public open class PlainIntBase(private val value: Int?) {
    public open fun read(missing: Boolean): Int? = if (missing) null else value
}

public class AdaptedDeclaredIntOpenChild(private val leaf: Int?) :
    PlainIntBase(-901), OpenChild<Int> {
    override fun read(missing: Boolean): Int? = if (missing) null else leaf
}

/** The consumer must override the external class's recorded split slot without an adapter. */
public class ExternalDeclaredIntOpenChild(private val leaf: Int?) :
    ExternalSplitBase<Int>(-907), OpenChild<Int> {
    override fun read(missing: Boolean): Int? = if (missing) null else leaf
}

fun box(): String {
    val reader = NullableSourceReader()

    val ints = intSource(37)
    if (reader.readInt(ints, false) != 37) return "int hit"
    if (reader.readInt(ints, true) != null) return "int null"
    val wideInts = widenIntSource(ints)
    if (reader.readWide(wideInts, false) != 37) return "wide int hit"
    if (reader.readWide(wideInts, true) != null) return "wide int null"
    if (downstreamWidenedRead(wideInts, false) != 37) return "downstream wide int hit"
    if (downstreamWidenedRead(wideInts, true) != null) return "downstream wide int null"

    val strings = stringSource("typed")
    val wideStrings = widenStringSource(strings)
    if (reader.readWide(wideStrings, false) != "typed") return "wide string hit"
    if (reader.readWide(wideStrings, true) != null) return "wide string null"
    if (downstreamWidenedRead(wideStrings, false) != "typed") {
        return "downstream wide string hit"
    }
    if (downstreamWidenedRead(wideStrings, true) != null) return "downstream wide string null"

    val nullableInt = nullableIntSource(41)
    if (reader.readNullableInt(nullableInt, false) != 41) return "nullable int hit"
    if (reader.readNullableInt(nullableInt, true) != null) return "nullable int missing"
    if (reader.readNullableInt(nullableIntSource(null), false) != null) return "nullable int value null"

    val overloadedExact = overloadedIntSource(43)
    val overloadedWide: OverloadedNullableSource<Any?> = overloadedExact
    if (downstreamOverloadedBooleanRead(overloadedWide, false) != 43) {
        return "overloaded boolean hit"
    }
    if (downstreamOverloadedBooleanRead(overloadedWide, true) != null) {
        return "overloaded boolean null"
    }
    if (downstreamOverloadedIntRead(overloadedWide, 2) != 45) return "overloaded int hit"
    if (downstreamOverloadedIntRead(overloadedWide, -1) != null) return "overloaded int null"

    val intChild = intOpenChild(47)
    if (downstreamOpenChildExactIntRead(intChild, false) != 47) return "child int exact hit"
    if (downstreamOpenChildExactIntRead(intChild, true) != null) return "child int exact null"
    val wideIntChild: OpenChild<Any?> = intChild
    if (!downstreamOpenChildSame(wideIntChild, intChild)) return "child int identity"
    if (downstreamOpenChildWidenedRead(wideIntChild, false) != 47) {
        return "child int widened hit"
    }
    if (downstreamOpenChildWidenedRead(wideIntChild, true) != null) {
        return "child int widened null"
    }
    val wideIntParent: NullableSource<Any?> = intChild
    if (downstreamWidenedRead(wideIntParent, false) != 47) return "child int parent hit"
    if (downstreamWidenedRead(wideIntParent, true) != null) return "child int parent null"

    val stringChild = stringOpenChild("open-child")
    if (downstreamOpenChildExactStringRead(stringChild, false) != "open-child") {
        return "child string exact hit"
    }
    if (downstreamOpenChildExactStringRead(stringChild, true) != null) {
        return "child string exact null"
    }
    val wideStringChild: OpenChild<Any?> = stringChild
    if (!downstreamOpenChildSame(wideStringChild, stringChild)) return "child string identity"
    if (downstreamOpenChildWidenedRead(wideStringChild, false) != "open-child") {
        return "child string widened hit"
    }
    if (downstreamOpenChildWidenedRead(wideStringChild, true) != null) {
        return "child string widened null"
    }

    val reused = ReusedIntOpenChild(53)
    if ((reused as SplitIntBase).read(false) != 53) return "reused split base hit"
    if (reused.read(true) != null) return "reused split base null"
    val reusedExact: OpenChild<Int> = reused
    if (downstreamOpenChildExactIntRead(reusedExact, false) != 53) {
        return "reused split child hit"
    }
    val reusedWide: OpenChild<Any?> = reused
    if (!downstreamOpenChildSame(reusedWide, reused)) return "reused split identity"
    if (downstreamOpenChildWidenedRead(reusedWide, true) != null) {
        return "reused split child null"
    }

    val reusedGeneric = ReusedGenericOpenChild(57)
    val reusedGenericExact: OpenChild<Int> = reusedGeneric
    if (downstreamOpenChildExactIntRead(reusedGenericExact, false) != 57) {
        return "reused generic child hit"
    }
    val reusedGenericWide: OpenChild<Any?> = reusedGeneric
    if (!downstreamOpenChildSame(reusedGenericWide, reusedGeneric) ||
        downstreamOpenChildWidenedRead(reusedGenericWide, true) != null
    ) {
        return "reused generic child widened"
    }

    val adaptedFake = AdaptedFakeStringOpenChild("adapted-fake")
    if ((adaptedFake as PlainStringBase).read(false) != "adapted-fake") {
        return "adapted fake base hit"
    }
    if (adaptedFake.read(true) != null) return "adapted fake base null"
    val adaptedFakeExact: OpenChild<String> = adaptedFake
    if (downstreamOpenChildExactStringRead(adaptedFakeExact, false) != "adapted-fake") {
        return "adapted fake child hit"
    }
    val adaptedFakeWide: OpenChild<Any?> = adaptedFake
    if (!downstreamOpenChildSame(adaptedFakeWide, adaptedFake)) return "adapted fake identity"
    if (downstreamOpenChildWidenedRead(adaptedFakeWide, true) != null) {
        return "adapted fake child null"
    }
    val adaptedFakeNull = AdaptedFakeStringOpenChild(null)
    if ((adaptedFakeNull as PlainStringBase).read(false) != null) {
        return "adapted fake base stored null"
    }
    val adaptedFakeNullExact: OpenChild<String> = adaptedFakeNull
    if (downstreamOpenChildExactStringRead(adaptedFakeNullExact, false) != null) {
        return "adapted fake child stored null"
    }
    val adaptedFakeNullWide: OpenChild<Any?> = adaptedFakeNull
    if (!downstreamOpenChildSame(adaptedFakeNullWide, adaptedFakeNull) ||
        downstreamOpenChildWidenedRead(adaptedFakeNullWide, false) != null
    ) {
        return "adapted fake widened stored null"
    }

    val adaptedDeclared = AdaptedDeclaredIntOpenChild(59)
    if ((adaptedDeclared as PlainIntBase).read(false) != 59) {
        return "adapted declared base hit"
    }
    if (adaptedDeclared.read(true) != null) return "adapted declared base null"
    val adaptedDeclaredExact: OpenChild<Int> = adaptedDeclared
    if (downstreamOpenChildExactIntRead(adaptedDeclaredExact, false) != 59) {
        return "adapted declared child hit"
    }
    val adaptedDeclaredWide: OpenChild<Any?> = adaptedDeclared
    if (!downstreamOpenChildSame(adaptedDeclaredWide, adaptedDeclared)) {
        return "adapted declared identity"
    }
    if (downstreamOpenChildWidenedRead(adaptedDeclaredWide, true) != null) {
        return "adapted declared child null"
    }
    val adaptedDeclaredNull = AdaptedDeclaredIntOpenChild(null)
    if ((adaptedDeclaredNull as PlainIntBase).read(false) != null) {
        return "adapted declared base stored null"
    }
    val adaptedDeclaredNullExact: OpenChild<Int> = adaptedDeclaredNull
    if (downstreamOpenChildExactIntRead(adaptedDeclaredNullExact, false) != null) {
        return "adapted declared child stored null"
    }
    val adaptedDeclaredNullWide: OpenChild<Any?> = adaptedDeclaredNull
    if (!downstreamOpenChildSame(adaptedDeclaredNullWide, adaptedDeclaredNull) ||
        downstreamOpenChildWidenedRead(adaptedDeclaredNullWide, false) != null
    ) {
        return "adapted declared widened stored null"
    }

    val externalDeclared = ExternalDeclaredIntOpenChild(61)
    val externalDeclaredBase: ExternalSplitBase<Int> = externalDeclared
    if (externalDeclaredBase.read(false) != 61) {
        return "external declared base hit"
    }
    if (externalDeclared.read(0) != -907 || externalDeclared.read(-1) != null) {
        return "external declared overload"
    }
    val externalDeclaredExact: OpenChild<Int> = externalDeclared
    if (downstreamOpenChildExactIntRead(externalDeclaredExact, false) != 61) {
        return "external declared child hit"
    }
    val externalDeclaredWide: OpenChild<Any?> = externalDeclared
    if (!downstreamOpenChildSame(externalDeclaredWide, externalDeclared) ||
        downstreamOpenChildWidenedRead(externalDeclaredWide, true) != null
    ) {
        return "external declared widened"
    }
    val externalDeclaredNull = ExternalDeclaredIntOpenChild(null)
    val externalDeclaredNullBase: ExternalSplitBase<Int> = externalDeclaredNull
    if (externalDeclaredNullBase.read(false) != null) {
        return "external declared base stored null"
    }
    val externalDeclaredNullWide: OpenChild<Any?> = externalDeclaredNull
    if (!downstreamOpenChildSame(externalDeclaredNullWide, externalDeclaredNull) ||
        downstreamOpenChildWidenedRead(externalDeclaredNullWide, false) != null
    ) {
        return "external declared widened stored null"
    }

    return "OK"
}
