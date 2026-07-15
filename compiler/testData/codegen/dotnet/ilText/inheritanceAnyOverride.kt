// Kotlin Any is physically System.Object. These declarations therefore reuse the existing
// Equals/GetHashCode/ToString virtual slots (`virtual`, no `newslot`), while calls through Any
// name System.Object and still dispatch to the Kotlin overrides. `parentString` pins the
// non-virtual `super.toString()` path. Null-safe `==` and string conversion call the helpers in
// Kotlin.Runtime.Internal rather than duplicating them into this module.
class Stringy {
    override fun toString(): String = "S"

    fun parentString(): String = super.toString()
}

open class EqualsBase(val hash: Int) {
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = hash
}

class Indirect : EqualsBase(7) {
    override fun toString(): String = "I"
}

fun same(left: Any?, right: Any?): Boolean = left == right

fun render(value: Any?): String = "$value"

fun explicitEquals(left: Any, right: Any?): Boolean = left.equals(right)

fun hash(value: Any): Int = value.hashCode()

fun mixedNullable(value: Int?, other: Any?): Boolean = value == other

fun <T> genericSame(left: T, right: T): Boolean = left == right

fun <T> genericRender(value: T): String = "$value"

fun <T> genericString(value: T): String = value.toString()

fun main() {
    val value = Indirect()
    val any: Any = value
    val missing: String? = null
    println(any.toString())
    println(any.hashCode())
    println(same(any, value))
    println(explicitEquals(any, value))
    println(hash(any))
    println(mixedNullable(7, any.hashCode()))
    println(genericSame(7, 7))
    println(genericRender(missing))
    println(genericString(missing))
    println(render(null))
}
