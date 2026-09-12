// DOTNET_FOREIGN_KOTLIN_INTERFACE_RESULT_PROBE
// MODULE: lib
// FILE: source.kt
package foreign.result.kotlin

interface Source<out T> {
    fun value(): T
}

class IntSource : Source<Int> {
    override fun value(): Int = 73
}

// MODULE: main(lib)
// FILE: main.kt
package foreign.result.kotlin

fun primitive(factory: ForeignReturn.PrimitiveFactory): Int = factory.read()
fun native(factory: ForeignReturn.NativeFactory): Int = factory.read().value()
fun kotlinSource(source: Source<Int>): Int = source.value()
fun owned(factory: ForeignReturn.KotlinFactory): Any? = factory.read().value()
fun identity(factory: ForeignReturn.KotlinFactory, source: Any): Boolean = factory.read() === source
fun genericInt(factory: ForeignReturn.GenericFactory<Int>): Any? = factory.read().value()
fun genericString(factory: ForeignReturn.GenericFactory<String>): Any? = factory.read().value()
fun nested(factory: ForeignReturn.NestedFactory): Any? = (factory.read().value() as Source<*>).value()

fun box(): String = "OK"
