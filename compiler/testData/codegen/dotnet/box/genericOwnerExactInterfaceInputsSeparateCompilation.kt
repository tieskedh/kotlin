// MODULE: lib
// FILE: contracts.kt

// DOTNET_GENERIC_OWNER_EXACT_INTERFACE_INPUTS_CSHARP_PROBE

package generic.owner.exact.inputs

public interface ExactInputCursor<out T> {
    public fun hasExactNext(): Boolean

    public fun nextExact(): T
}

public interface ExactInputFamily<out T> : ExactInputCursor<T> {
    public val exactSize: Int

    public fun exactCursor(): ExactInputCursor<T>

    public fun acceptsAll(values: ExactInputFamily<@UnsafeVariance T>): Boolean

    public fun hasExactValues(): Boolean
}

public class ExactInputFamilyReader {
    public fun read(value: ExactInputFamily<Any?>): Any? = value.exactCursor().nextExact()

    public fun acceptsAll(
        receiver: ExactInputFamily<Any?>,
        values: ExactInputFamily<Any?>,
    ): Boolean = receiver.acceptsAll(values)

    public fun size(value: ExactInputFamily<Any?>): Int = value.exactSize

    public fun same(value: ExactInputFamily<Any?>, expected: Any?): Boolean = value === expected
}

// MODULE: middle(lib)
// FILE: implementation.kt

package generic.owner.exact.inputs

public class ExactInputValue<T>(private val current: T) : ExactInputFamily<T> {
    public override val exactSize: Int
        get() = 1

    public override fun hasExactNext(): Boolean = true

    public override fun nextExact(): T = current

    public override fun exactCursor(): ExactInputCursor<T> = this

    public override fun acceptsAll(values: ExactInputFamily<T>): Boolean =
        values.nextExact() == current

    public override fun hasExactValues(): Boolean = true
}

public fun widenExactInput(value: ExactInputFamily<Int>): ExactInputFamily<Any?> = value

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.exact.inputs

fun box(): String {
    val exact = ExactInputValue(37)
    val wide = widenExactInput(exact)
    val reader = ExactInputFamilyReader()
    if (!reader.same(wide, exact)) return "identity"
    if (reader.read(wide) != 37) return "constructed result"
    if (!exact.acceptsAll(ExactInputValue(37))) return "exact nested input"
    if (reader.acceptsAll(wide, ExactInputValue("wrong"))) return "semantic nested input"
    if (!wide.hasExactNext() || !wide.hasExactValues() || reader.size(wide) != 1) {
        return "primitive queries"
    }
    return "OK"
}
