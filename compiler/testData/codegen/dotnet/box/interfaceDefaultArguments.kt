// TARGET_BACKEND: DOTNET

private var defaultEvaluations: Int = 0

private interface DefaultOps {
    fun seed(): Int

    fun apply(value: Int = seed()): Int

    fun combine(first: Int = seed(), second: Int = first + 1): Int
}

private class DefaultOpsImpl(private val base: Int) : DefaultOps {
    override fun seed(): Int {
        defaultEvaluations = defaultEvaluations + 1
        return base
    }

    override fun apply(value: Int): Int = value * 2

    override fun combine(first: Int, second: Int): Int = first * 100 + second
}

private interface DerivedOps : DefaultOps

private class DerivedOpsImpl(private val base: Int) : DerivedOps {
    override fun seed(): Int {
        defaultEvaluations = defaultEvaluations + 1
        return base
    }

    override fun apply(value: Int): Int = value + 1

    override fun combine(first: Int, second: Int): Int = first * 100 + second
}

private interface RedeclaredOps : DefaultOps {
    override fun apply(value: Int): Int
}

private class RedeclaredOpsImpl(private val base: Int) : RedeclaredOps {
    override fun seed(): Int {
        defaultEvaluations = defaultEvaluations + 1
        return base
    }

    override fun apply(value: Int): Int = value + 2

    override fun combine(first: Int, second: Int): Int = first * 100 + second
}

private class DelegatingOps(delegate: DefaultOps) : DefaultOps by delegate

private fun <T : DefaultOps> constrainedApply(value: T): Int = value.apply()

private interface GenericDefaults<T> {
    fun fallback(): T

    fun choose(value: T = fallback()): T

    fun <R> echo(value: R, selected: R = value): R
}

private class GenericDefaultsImpl<T>(private val fallback: T) : GenericDefaults<T> {
    override fun fallback(): T = fallback

    override fun choose(value: T): T = value

    override fun <R> echo(value: R, selected: R): R = selected
}

private interface StringDefaults : GenericDefaults<String> {
    override fun choose(value: String): String
}

private class StringDefaultsImpl : StringDefaults {
    override fun fallback(): String = "fixed"

    override fun choose(value: String): String = value + "!"

    override fun <R> echo(value: R, selected: R): R = selected
}

private interface VariantDefaults<out T> {
    fun value(index: Int = 0): T
}

private class StringVariantDefaults(private val text: String) : VariantDefaults<String> {
    override fun value(index: Int): String = text + index
}

private interface BoundValue {
    fun number(): Int
}

private class BoundValueImpl(private val value: Int) : BoundValue {
    override fun number(): Int = value
}

private interface BoundedDefaults<T : BoundValue> {
    fun fallback(): T

    fun choose(value: T = fallback()): T
}

private class BoundedDefaultsImpl<T : BoundValue>(private val value: T) : BoundedDefaults<T> {
    override fun fallback(): T = value

    override fun choose(value: T): T = value
}

private interface ExtensionDefaults {
    fun suffix(): String

    fun String.decorate(extra: String = suffix()): String
}

private class ExtensionDefaultsImpl(private val extra: String) : ExtensionDefaults {
    override fun suffix(): String = extra

    override fun String.decorate(extra: String): String = this + extra
}

private fun ExtensionDefaults.decorateValue(value: String): String = value.decorate()

private class DefaultContainer {
    interface NestedDefaults {
        class DefaultImpls(val marker: Int)

        fun seed(): Int

        fun value(number: Int = seed()): Int
    }

    class Impl : NestedDefaults {
        override fun seed(): Int = 31

        override fun value(number: Int): Int = number + 1
    }
}

fun box(): String {
    defaultEvaluations = 0
    val implementation = DefaultOpsImpl(7)
    if (implementation.apply() != 14 || defaultEvaluations != 1) return "fail 1: concrete"

    val interfaceView: DefaultOps = implementation
    if (interfaceView.apply() != 14 || defaultEvaluations != 2) return "fail 2: interface"
    if (implementation.apply(3) != 6 || defaultEvaluations != 2) return "fail 3: explicit"

    if (interfaceView.combine() != 708 || defaultEvaluations != 3) return "fail 4: both defaults"
    if (interfaceView.combine(second = 20) != 720 || defaultEvaluations != 4) return "fail 5: first default"
    if (interfaceView.combine(first = 4) != 405 || defaultEvaluations != 4) return "fail 6: second default"

    val derived = DerivedOpsImpl(9)
    val derivedView: DerivedOps = derived
    if (derivedView.apply() != 10 || defaultEvaluations != 5) return "fail 7: inherited"
    if (constrainedApply(derived) != 10 || defaultEvaluations != 6) return "fail 8: constrained"

    val redeclared: RedeclaredOps = RedeclaredOpsImpl(10)
    if (redeclared.apply() != 12 || defaultEvaluations != 7) return "fail 9: redeclared"

    val delegated = DelegatingOps(implementation)
    if (delegated.apply() != 14 || defaultEvaluations != 8) return "fail 10: delegated"

    val genericInt = GenericDefaultsImpl(11)
    if (genericInt.choose() != 11) return "fail 11: generic int"
    val genericString: GenericDefaults<String> = GenericDefaultsImpl("text")
    if (genericString.choose() != "text") return "fail 12: generic string"
    if (genericInt.echo("value") != "value") return "fail 13: generic method"
    val fixedString: StringDefaults = StringDefaultsImpl()
    if (fixedString.choose() != "fixed!") return "fail 14: generic redeclaration"

    val extension = ExtensionDefaultsImpl("!")
    if (extension.decorateValue("ok") != "ok!") return "fail 15: extension"

    if (StringVariantDefaults("item").value() != "item0") return "fail 16: variant"
    if (BoundedDefaultsImpl(BoundValueImpl(17)).choose().number() != 17) return "fail 17: bounded"

    if (DefaultContainer.Impl().value() != 32) return "fail 18: nested"
    return "OK"
}
