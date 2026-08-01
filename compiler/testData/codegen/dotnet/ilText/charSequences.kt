package test.charsequences

interface LocalSequence : CharSequence

class LocalSequenceImpl(private val value: String) : LocalSequence {
    override val length: Int
        get() = value.length

    override fun get(index: Int): Char = value[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        value.subSequence(startIndex, endIndex)
}

class SequenceHolder<T : CharSequence>(val value: T)

fun lengthOf(value: CharSequence): Int = value.length

fun getAt(value: CharSequence, index: Int): Char = value[index]

fun slice(value: CharSequence, startIndex: Int, endIndex: Int): CharSequence =
    value.subSequence(startIndex, endIndex)

fun isSequence(value: Any?): Boolean = value is CharSequence

fun isNullableSequence(value: Any?): Boolean = value is CharSequence?

fun checkedSequence(value: Any): CharSequence = value as CharSequence

fun safeSequence(value: Any): CharSequence? = value as? CharSequence

fun <T : CharSequence> genericLength(value: T): Int = value.length

fun <T : CharSequence> genericIdentity(value: T): T = value

fun main() {
    val string: CharSequence = "abcd"
    val local: CharSequence = LocalSequenceImpl("wxyz")
    println(lengthOf(string))
    println(getAt(local, 1))
    println(slice(string, 1, 3))
    println(isSequence(string))
    println(isNullableSequence(null))
    println(checkedSequence(local).length)
    println(safeSequence(42))
    println(genericLength("generic"))
    println(genericIdentity(LocalSequenceImpl("id")).length)
    println(SequenceHolder("holder").value)
}
