// Kotlin-owned generic classes have one non-generic physical owner. Owner type parameters are
// erased from fields and members; KLIB retains the complete logical types. Overloads whose
// parameters differ only by erased class arguments receive stable Kotlin-logical physical names.

class Box<T>(private var value: T) {
    fun get(): T = value

    fun put(v: T) {
        value = v
    }

    val item: T
        get() = value
}

class Pair2<A, B>(val first: A, val second: B)

fun erasedOverload(value: Box<Int>): String = "int"

fun erasedOverload(value: Box<String>): String = "string"

class ErasedMemberOverloads {
    fun select(value: Box<Int>): String = "int"

    fun select(value: Box<String>): String = "string"
}

// Runtime checks classify the one producer-recorded non-generic Box owner and return the same
// object. Logical arguments never become CLR runtime identity.
@Suppress("UNCHECKED_CAST")
fun erasedGenericCast(value: Any): Box<String> = value as Box<String>

fun erasedGenericTest(value: Any): Boolean = value is Box<*>

fun erasedGenericSafeCast(value: Any): Box<*>? = value as? Box<*>

// A statically exact source construction still uses the erased owner and object-backed storage;
// the Int result is unboxed only at this logical use site.
fun guaranteedAliasRead(): Int {
    val constructed = Box(40)
    val alias = constructed
    return alias.get()
}

// A Kotlin Box<Int> parameter is only a logical view: unchecked casts can forge it. Calls use the
// erased owner and recover Int at the result boundary, preserving delayed-use failure semantics.
fun guardedIntRead(value: Box<Int>): Int = value.get()

fun guardedIntWrite(value: Box<Int>, replacement: Int) {
    value.put(replacement)
}

@Suppress("UNCHECKED_CAST")
fun guardedMismatchedRead(value: Box<String>): Int = (value as Any as Box<Int>).get()

fun main() {
    val bs = Box<String>("first")
    println(bs.get())
    bs.put("second")
    println(bs.item)
    val bi = Box<Int>(41)
    bi.put(bi.get() + 1)
    println(bi.get())
    val bn = Box<Int?>(7)
    println(bn.get() ?: 0)
    val nested = Box<Box<String>>(Box<String>("inner"))
    println(nested.get().get())
    val p = Pair2<Int, String>(1, "one")
    println(p.first)
    println(p.second)
    println(erasedGenericTest(bs))
    println(erasedGenericSafeCast(bs) === bs)
    println(erasedGenericCast(bs) === bs)
    println(erasedOverload(bi))
    println(erasedOverload(bs))
    println(ErasedMemberOverloads().select(bi))
    println(ErasedMemberOverloads().select(bs))
}
