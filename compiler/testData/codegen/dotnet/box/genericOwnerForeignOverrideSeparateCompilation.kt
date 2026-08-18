// DOTNET_GENERIC_OWNER_FOREIGN_OVERRIDE_SEPARATE_PROBE

// MODULE: lib
// FILE: lib.kt

public open class RehearsalSeparateStore<out T>(initial: T) {
    private var value: T = initial

    public open fun read(): T = value

    public fun write(value: @UnsafeVariance T) {
        this.value = value
    }
}

public class RehearsalSeparateReader {
    public fun read(store: RehearsalSeparateStore<Any?>): Any? = store.read()
}

// MODULE: middle(lib)
// FILE: middle.kt

public open class RehearsalSeparateKotlinOverrideStore<T>(initial: T) :
    RehearsalSeparateStore<T>(initial) {
    public override fun read(): T = super.read()
}

// MODULE: main(middle)
// FILE: main.kt

fun box(): String {
    val store = RehearsalSeparateKotlinOverrideStore("kotlin-middle")
    if (RehearsalSeparateReader().read(store) != "kotlin-middle") {
        return "fail: separate Kotlin override"
    }

    val exact = RehearsalSeparateKotlinOverrideStore(11)
    val widened: RehearsalSeparateStore<Any?> = exact
    widened.write("semantic")
    if (RehearsalSeparateReader().read(widened) != "semantic") {
        return "fail: separate raw widened read"
    }
    try {
        exact.read() + 1
        return "fail: separate typed incompatible read"
    } catch (_: ClassCastException) {
        // Only this actual typed use is a checked boundary.
    }
    widened.write(19)
    if (exact.read() != 19) return "fail: separate compatible recovery"

    return "OK"
}
