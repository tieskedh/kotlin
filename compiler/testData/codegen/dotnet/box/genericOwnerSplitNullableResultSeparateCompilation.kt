// DOTNET_GENERIC_OWNER_SPLIT_NULLABLE_CSHARP_PROBE
// MODULE: lib
// FILE: contracts.kt

package generic.owner.split.nullable

/** Name-independent first proof for the physical `T + out bool isNull` result convention. */
public interface NullableSource<out T> {
    public fun read(missing: Boolean): T?
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

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.split.nullable

fun box(): String {
    val reader = NullableSourceReader()

    val ints = intSource(37)
    if (reader.readInt(ints, false) != 37) return "int hit"
    if (reader.readInt(ints, true) != null) return "int null"
    val wideInts = widenIntSource(ints)
    if (reader.readWide(wideInts, false) != 37) return "wide int hit"
    if (reader.readWide(wideInts, true) != null) return "wide int null"

    val strings = stringSource("typed")
    val wideStrings = widenStringSource(strings)
    if (reader.readWide(wideStrings, false) != "typed") return "wide string hit"
    if (reader.readWide(wideStrings, true) != null) return "wide string null"

    val nullableInt = nullableIntSource(41)
    if (reader.readNullableInt(nullableInt, false) != 41) return "nullable int hit"
    if (reader.readNullableInt(nullableInt, true) != null) return "nullable int missing"
    if (reader.readNullableInt(nullableIntSource(null), false) != null) return "nullable int value null"

    return "OK"
}
