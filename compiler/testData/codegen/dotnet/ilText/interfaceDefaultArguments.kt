interface Defaults {
    fun seed(): Int

    fun apply(value: Int = seed()): Int

    fun combine(first: Int = seed(), second: Int = first + 1): Int
}

class DefaultsImpl(private val base: Int) : Defaults {
    override fun seed(): Int = base

    override fun apply(value: Int): Int = value * 2

    override fun combine(first: Int, second: Int): Int = first * 100 + second
}

interface DerivedDefaults : Defaults

class DerivedDefaultsImpl : DerivedDefaults {
    override fun seed(): Int = 9

    override fun apply(value: Int): Int = value + 1

    override fun combine(first: Int, second: Int): Int = first * 100 + second
}

interface RedeclaredDefaults : Defaults {
    override fun apply(value: Int): Int
}

class RedeclaredDefaultsImpl : RedeclaredDefaults {
    override fun seed(): Int = 10

    override fun apply(value: Int): Int = value + 2

    override fun combine(first: Int, second: Int): Int = first * 100 + second
}

interface GenericDefaults<T> {
    fun fallback(): T

    fun choose(value: T = fallback()): T

    fun <R> echo(value: R, selected: R = value): R
}

class GenericDefaultsImpl<T>(private val fallback: T) : GenericDefaults<T> {
    override fun fallback(): T = fallback

    override fun choose(value: T): T = value

    override fun <R> echo(value: R, selected: R): R = selected
}

interface StringDefaults : GenericDefaults<String> {
    override fun choose(value: String): String
}

class StringDefaultsImpl : StringDefaults {
    override fun fallback(): String = "fixed"

    override fun choose(value: String): String = value + "!"

    override fun <R> echo(value: R, selected: R): R = selected
}

interface VariantDefaults<out T> {
    fun value(index: Int = 0): T
}

class StringVariantDefaults(private val text: String) : VariantDefaults<String> {
    override fun value(index: Int): String = text + index
}

interface BoundValue {
    fun number(): Int
}

class BoundValueImpl(private val value: Int) : BoundValue {
    override fun number(): Int = value
}

interface BoundedDefaults<T : BoundValue> {
    fun fallback(): T

    fun choose(value: T = fallback()): T
}

class BoundedDefaultsImpl<T : BoundValue>(private val value: T) : BoundedDefaults<T> {
    override fun fallback(): T = value

    override fun choose(value: T): T = value
}

interface ExtensionDefaults {
    fun suffix(): String

    fun String.decorate(extra: String = suffix()): String
}

class ExtensionDefaultsImpl : ExtensionDefaults {
    override fun suffix(): String = "!"

    override fun String.decorate(extra: String): String = this + extra
}

class DefaultContainer {
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

fun directDefault(value: DefaultsImpl): Int = value.apply()

fun interfaceDefault(value: Defaults): Int = value.combine(second = 20)

fun inheritedDefault(value: DerivedDefaults): Int = value.apply()

fun redeclaredDefault(value: RedeclaredDefaults): Int = value.apply()

fun <T : Defaults> constrainedDefault(value: T): Int = value.apply()

fun <T> genericDefault(value: GenericDefaults<T>): T = value.choose()

fun <T> genericMethodDefault(value: GenericDefaults<T>, item: T): T = value.echo(item)

fun redeclaredGenericDefault(value: StringDefaults): String = value.choose()

fun variantDefault(value: VariantDefaults<String>): String = value.value()

fun <T : BoundValue> boundedDefault(value: BoundedDefaults<T>): T = value.choose()

fun ExtensionDefaults.extensionDefault(value: String): String = value.decorate()

fun nestedDefault(value: DefaultContainer.NestedDefaults): Int = value.value()

fun main() {
    println(directDefault(DefaultsImpl(7)))
    println(interfaceDefault(DefaultsImpl(7)))
    println(inheritedDefault(DerivedDefaultsImpl()))
    println(redeclaredDefault(RedeclaredDefaultsImpl()))
    println(genericDefault(GenericDefaultsImpl("text")))
    println(genericMethodDefault(GenericDefaultsImpl(1), 2))
    println(redeclaredGenericDefault(StringDefaultsImpl()))
    println(variantDefault(StringVariantDefaults("item")))
    println(boundedDefault(BoundedDefaultsImpl(BoundValueImpl(17))).number())
    println(ExtensionDefaultsImpl().extensionDefault("ok"))
    println(nestedDefault(DefaultContainer.Impl()))
}
