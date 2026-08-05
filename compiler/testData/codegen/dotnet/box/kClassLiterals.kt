import kotlin.reflect.cast
import kotlin.reflect.safeCast

private class Plain

private class Box<T>(val value: T)

private open class GenericParent<T>

private class GenericChild : GenericParent<String>()

private interface GenericMarker<T>

private class GenericMarkerImpl : GenericMarker<String>

private class Other

private class Outer {
    class Nested
}

private class CustomSequence(private val value: String) : CharSequence {
    override val length: Int get() = value.length

    override fun get(index: Int): Char = value[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = this
}

private class CustomException(message: String) : IllegalArgumentException(message)

private fun fail(message: String): String = "fail: $message"

fun box(): String {
    if (Plain::class.simpleName != "Plain") return fail("simple name")
    if (Plain::class.qualifiedName != "Plain") return fail("qualified name")
    if (Outer.Nested::class.simpleName != "Nested") return fail("nested simple name")
    if (Outer.Nested::class.qualifiedName != "Outer.Nested") return fail("nested qualified name")

    if (Plain()::class != Plain::class) return fail("dynamic plain identity")
    if (Plain::class == Other::class) return fail("different class equality")
    if (Plain::class.hashCode() != Plain::class.hashCode()) return fail("stable hash")

    if (Int::class.simpleName != "Int" || Int::class.qualifiedName != "kotlin.Int") {
        return fail("Int names")
    }
    if (1::class != Int::class) return fail("dynamic Int identity")
    if (true::class != Boolean::class) return fail("dynamic Boolean identity")
    if (1.toByte()::class != Byte::class) return fail("dynamic Byte identity")
    if (1.toShort()::class != Short::class) return fail("dynamic Short identity")
    if (1L::class != Long::class) return fail("dynamic Long identity")
    if (1f::class != Float::class) return fail("dynamic Float identity")
    if (1.0::class != Double::class) return fail("dynamic Double identity")
    if ('x'::class != Char::class) return fail("dynamic Char identity")
    if ("x"::class != String::class) return fail("dynamic String identity")
    if (Unit::class.simpleName != "Unit" || Unit::class.qualifiedName != "kotlin.Unit") {
        return fail("Unit names")
    }
    if (Unit::class != Unit::class) return fail("Unit equality")
    val dynamicUnit: Any = Unit
    if (dynamicUnit::class.simpleName != "Unit" || dynamicUnit::class.qualifiedName != "kotlin.Unit") {
        return fail("dynamic Unit names")
    }
    if (Nothing::class.isInstance(null)) return fail("Nothing instance")

    var dynamicEvaluations = 0
    fun evaluatedPlain(): Any {
        dynamicEvaluations += 1
        return Plain()
    }
    if (evaluatedPlain()::class != Plain::class || dynamicEvaluations != 1) {
        return fail("dynamic single evaluation")
    }

    val boxedString: Any = Box("text")
    if (boxedString::class != Box::class) return fail("dynamic generic identity")
    if (Box::class == Plain::class) return fail("generic/plain equality")
    if (!Box::class.isInstance(boxedString)) return fail("generic instance")
    if (Box::class.isInstance(Other())) return fail("generic wrong instance")
    if (!GenericParent::class.isInstance(GenericChild())) return fail("generic base instance")
    if (!GenericMarker::class.isInstance(GenericMarkerImpl())) return fail("generic interface instance")
    if (Box::class.cast(boxedString).value != "text") return fail("cast")
    if (Box::class.safeCast(Other()) != null) return fail("safeCast")
    if (Box::class.safeCast(null) != null) return fail("nullable safeCast")
    try {
        Box::class.cast(Other())
        return fail("cast accepted wrong value")
    } catch (_: ClassCastException) {
    }
    try {
        Box::class.cast(null)
        return fail("cast accepted null")
    } catch (_: ClassCastException) {
    }

    if (arrayOf("x")::class != Array::class) return fail("dynamic Array identity")
    if (!Array::class.isInstance(arrayOf(1))) return fail("Array instance")
    if (Array::class.isInstance(intArrayOf(1))) return fail("primitive admitted as Array")
    if (IntArray::class == Array::class) return fail("primitive/generic Array identity")
    if (intArrayOf(1)::class != IntArray::class) return fail("dynamic primitive array identity")
    val dynamicIntArray: Any = intArrayOf(1)
    if (dynamicIntArray::class.simpleName != "IntArray" ||
        dynamicIntArray::class.qualifiedName != "kotlin.IntArray"
    ) {
        return fail("dynamic primitive array names")
    }
    val primitiveArrays = arrayOf<Any>(
        booleanArrayOf(true),
        byteArrayOf(1),
        shortArrayOf(1),
        intArrayOf(1),
        longArrayOf(1),
        floatArrayOf(1f),
        doubleArrayOf(1.0),
        charArrayOf('x'),
    )
    val primitiveArrayClasses = arrayOf(
        BooleanArray::class,
        ByteArray::class,
        ShortArray::class,
        IntArray::class,
        LongArray::class,
        FloatArray::class,
        DoubleArray::class,
        CharArray::class,
    )
    val primitiveArrayNames = arrayOf(
        "BooleanArray",
        "ByteArray",
        "ShortArray",
        "IntArray",
        "LongArray",
        "FloatArray",
        "DoubleArray",
        "CharArray",
    )
    var primitiveArrayIndex = 0
    while (primitiveArrayIndex < primitiveArrays.size) {
        val dynamicClass = primitiveArrays[primitiveArrayIndex]::class
        if (dynamicClass != primitiveArrayClasses[primitiveArrayIndex]) {
            return fail("primitive array identity $primitiveArrayIndex")
        }
        if (dynamicClass.simpleName != primitiveArrayNames[primitiveArrayIndex] ||
            dynamicClass.qualifiedName != "kotlin.${primitiveArrayNames[primitiveArrayIndex]}"
        ) {
            return fail("primitive array names $primitiveArrayIndex")
        }
        primitiveArrayIndex += 1
    }

    if (!CharSequence::class.isInstance("text")) return fail("String CharSequence")
    if (!CharSequence::class.isInstance(CustomSequence("text"))) return fail("custom CharSequence")
    if (CharSequence::class.isInstance(1)) return fail("wrong CharSequence")
    if (!Number::class.isInstance(1) || !Number::class.isInstance(1.5)) return fail("Number instance")
    if (Number::class.isInstance('x')) return fail("Char admitted as Number")

    if (Throwable::class == Exception::class) return fail("exception class equality")
    if (Throwable()::class != Throwable::class) return fail("dynamic Throwable identity")
    if (Exception()::class != Exception::class) return fail("dynamic Exception identity")
    val mappedExceptions = arrayOf<Any>(
        Error(),
        RuntimeException(),
        IllegalStateException(),
        ClassCastException(),
        OutOfMemoryError(),
    )
    val mappedExceptionClasses = arrayOf(
        Error::class,
        RuntimeException::class,
        IllegalStateException::class,
        ClassCastException::class,
        OutOfMemoryError::class,
    )
    var exceptionIndex = 0
    while (exceptionIndex < mappedExceptions.size) {
        if (mappedExceptions[exceptionIndex]::class != mappedExceptionClasses[exceptionIndex]) {
            return fail("mapped exception identity $exceptionIndex")
        }
        exceptionIndex += 1
    }
    val custom = CustomException("custom")
    if (custom::class != CustomException::class) return fail("custom exception identity")
    if (!IllegalArgumentException::class.isInstance(custom)) return fail("mapped exception ancestry")
    if (!Error::class.isInstance(OutOfMemoryError())) return fail("out-of-memory Error ancestry")

    class `Dollar$Name`
    val localClass = `Dollar$Name`::class
    if (localClass.simpleName != "Dollar\$Name") return fail("local source name")
    if (localClass.qualifiedName != null) return fail("local qualified name")
    if (`Dollar$Name`()::class != localClass) return fail("dynamic local identity")

    val anonymous = object {}::class
    if (anonymous.simpleName != null || anonymous.qualifiedName != null) return fail("anonymous names")

    if (Any::class.isInstance(null)) return fail("null instance")
    return "OK"
}
