// MODULE: lib
// FILE: contracts.kt

package generic.owner.closed.semantic.input

public interface ClosedInputFamily<out T> {
    public fun currentView(): ClosedInputFamily<T>

    public fun acceptsAll(values: ClosedInputFamily<@UnsafeVariance T>): Boolean

    public fun hasValues(): Boolean
}

public fun widenedAcceptsAll(
    receiver: ClosedInputFamily<Any?>,
    values: ClosedInputFamily<Any?>,
): Boolean = receiver.acceptsAll(values)

public fun sameClosedInput(value: ClosedInputFamily<Any?>, expected: Any?): Boolean =
    value === expected

// MODULE: middle(lib)
// FILE: implementation.kt

package generic.owner.closed.semantic.input

private class ClosedStringInputFamily : ClosedInputFamily<String> {
    override fun currentView(): ClosedInputFamily<String> = this

    override fun acceptsAll(values: ClosedInputFamily<String>): Boolean = values === this

    override fun hasValues(): Boolean = true
}

public fun closedStringInput(): ClosedInputFamily<String> = ClosedStringInputFamily()

public fun widenClosedStringInput(value: ClosedInputFamily<String>): ClosedInputFamily<Any?> = value

// MODULE: main(middle)
// FILE: main.kt

package generic.owner.closed.semantic.input

fun box(): String {
    val exact = closedStringInput()
    if (!exact.acceptsAll(exact)) return "exact input"
    if (!exact.hasValues() || exact.currentView() !== exact) return "exact members"

    val wide = widenClosedStringInput(exact)
    if (!sameClosedInput(wide, exact)) return "widened identity"
    if (!widenedAcceptsAll(wide, wide)) return "widened matching input"

    val other = widenClosedStringInput(closedStringInput())
    if (widenedAcceptsAll(wide, other)) return "widened mismatching input"
    return "OK"
}
