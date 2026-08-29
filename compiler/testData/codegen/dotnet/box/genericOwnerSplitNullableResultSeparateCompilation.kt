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

    return "OK"
}
