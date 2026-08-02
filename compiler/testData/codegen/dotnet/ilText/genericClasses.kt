// Kotlin generic-class identity is a non-generic canonical interface. The Roslyn-shaped
// `Box`1<T>` remains as the same object's invariant implementation capability, with `!0` fields
// and member bodies. Canonical ABI signatures therefore erase class arguments; overloads whose
// parameters differ only by those arguments receive stable Kotlin-logical physical names.

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

// Kotlin class identity is erased even though the companion CLR capability remains reified.
// These operations must classify the exact producer-recorded open Box`1 ancestry, then return
// the same object through the non-generic canonical view.
@Suppress("UNCHECKED_CAST")
fun erasedGenericCast(value: Any): Box<String> = value as Box<String>

fun erasedGenericTest(value: Any): Boolean = value is Box<*>

fun erasedGenericSafeCast(value: Any): Box<*>? = value as? Box<*>

// A fresh construction and immutable aliases are the deliberately bounded proof set. These calls
// must target Box`1<int32> directly and must not box the value result.
fun guaranteedAliasRead(): Int {
    val constructed = Box(40)
    val alias = constructed
    return alias.get()
}

// A Kotlin Box<Int> parameter is not physical proof: unchecked casts can forge this logical view.
// The fast branch probes Box`1<int32>; the miss must retain canonical delayed-failure semantics.
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
