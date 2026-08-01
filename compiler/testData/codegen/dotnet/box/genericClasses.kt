// Kotlin generic-class identity is the non-generic canonical CLR interface; the invariant
// `Box`1<T>` class is the same object's typed implementation capability. These checks keep the
// reified storage advantage while attacking erased Kotlin casts, ancestry, evaluation count,
// state identity, and parameter-overload collisions.

class Box<T>(private var value: T) {
    fun get(): T = value

    fun put(v: T) {
        value = v
    }

    val item: T
        get() = value
}

class Pair2<A, B>(val first: A, val second: B) {
    fun swap(): Pair2<B, A> = Pair2<B, A>(second, first)
}

open class ErasedBase<T>(private var stored: T) {
    open fun read(): T = stored

    open fun write(value: T) {
        stored = value
    }
}

class StringBase(value: String) : ErasedBase<String>(value)

class DerivedErased<U>(value: U) : ErasedBase<U>(value)

private fun erasedOverload(value: Box<Int>): String = "int:${value.get()}"

private fun erasedOverload(value: Box<String>): String = "string:${value.get()}"

private class ErasedMemberOverloads {
    fun select(value: Box<Int>): String = "int:${value.get()}"

    fun select(value: Box<String>): String = "string:${value.get()}"
}

private var erasedEvaluationCount = 0

private fun countedErased(value: Any?): Any? {
    erasedEvaluationCount++
    return value
}

fun box(): String {
    val bs = Box("hello")
    if (bs.get() != "hello") return "fail 1: Box<String> get"
    bs.put("world")
    if (bs.item != "world") return "fail 2: Box<String> put/item"
    val bi = Box(1)
    bi.put(bi.get() + 41)
    if (bi.get() != 42) return "fail 3: Box<Int> arithmetic"
    if (bs.get() != "world") return "fail 4: instantiations coexist"
    val bn = Box<Int?>(null)
    if (bn.get() != null) return "fail 5: Box<Int?> empty"
    bn.put(8)
    val eight: Int? = bn.get()
    if (eight != 8) return "fail 6: Box<Int?> wrapped"
    val nested = Box(Box(5))
    if (nested.get().get() != 5) return "fail 7: Box<Box<Int>> get"
    nested.get().put(6)
    if (nested.get().get() != 6) return "fail 8: Box<Box<Int>> put"
    val p = Pair2(1, "one")
    if (p.first != 1) return "fail 9: Pair2 first"
    if (p.second != "one") return "fail 10: Pair2 second"
    val s = p.swap()
    if (s.first != "one") return "fail 11: swap first"
    if (s.second != 1) return "fail 12: swap second"

    val erased = ErasedBase("erased")
    val star = erased as Any as ErasedBase<*>
    if (star !== erased || star.read() != "erased") return "fail 13: star cast identity/read"
    val erasedAny: Any = erased
    if (erasedAny !is ErasedBase<*>) return "fail 14: direct star test"
    val stringBaseAny: Any = StringBase("derived")
    if (stringBaseAny !is ErasedBase<*>) return "fail 15: non-generic derived ancestry"
    val genericDerived = DerivedErased(17)
    val genericDerivedAny: Any = genericDerived
    if (genericDerivedAny !is DerivedErased<*> || genericDerivedAny !is ErasedBase<*>) {
        return "fail 16: generic derived ancestry"
    }
    val unrelated: Any = "not a class"
    if (unrelated is ErasedBase<*>) return "fail 17: unrelated star test"

    erasedEvaluationCount = 0
    if (countedErased(erased) !is ErasedBase<*> || erasedEvaluationCount != 1) {
        return "fail 18: test evaluates once"
    }
    erasedEvaluationCount = 0
    val safe = countedErased(erased) as? ErasedBase<Int>
    if (safe !== erased || erasedEvaluationCount != 1) return "fail 19: erased safe cast"
    erasedEvaluationCount = 0
    if (countedErased("wrong") as? ErasedBase<*> != null || erasedEvaluationCount != 1) {
        return "fail 20: rejected safe cast evaluates once"
    }
    erasedEvaluationCount = 0
    try {
        countedErased("wrong") as ErasedBase<*>
        return "fail 21: checked cast accepted unrelated value"
    } catch (_: ClassCastException) {
        if (erasedEvaluationCount != 1) return "fail 22: checked cast evaluates once"
    }

    val runtimeNull = countedErased(null)
    if (runtimeNull is ErasedBase<*>) return "fail 23: null non-null test"
    if (runtimeNull !is ErasedBase<*>?) return "fail 24: null nullable test"
    if ((null as Any?) as? ErasedBase<*> != null) return "fail 25: null safe cast"
    if ((null as Any?) as ErasedBase<*>? != null) return "fail 26: nullable checked cast"
    try {
        (null as Any?) as ErasedBase<*>
        return "fail 27: non-null checked cast accepted null"
    } catch (_: NullPointerException) {
    }

    @Suppress("UNCHECKED_CAST")
    val wrongArguments = erased as Any as ErasedBase<Int>
    if (wrongArguments as Any !== erased) return "fail 28: unchecked cast changed identity"
    try {
        val impossible: Int = wrongArguments.read()
        impossible + 1
        return "fail 29: erased member result did not enforce logical type"
    } catch (_: ClassCastException) {
    }
    erased.write("changed")
    if (star.read() != "changed") return "fail 30: erased views do not share state"
    if (erasedOverload(Box(31)) != "int:31") return "fail 31: top-level Int overload"
    if (erasedOverload(Box("thirty-two")) != "string:thirty-two") {
        return "fail 32: top-level String overload"
    }
    val memberOverloads = ErasedMemberOverloads()
    if (memberOverloads.select(Box(33)) != "int:33") return "fail 33: member Int overload"
    if (memberOverloads.select(Box("thirty-four")) != "string:thirty-four") {
        return "fail 34: member String overload"
    }
    return "OK"
}
