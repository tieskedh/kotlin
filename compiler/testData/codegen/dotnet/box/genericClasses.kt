// Kotlin generic-owner checks pin classifier-only runtime tests/safe casts, same-object behavior,
// member narrowing, and virtual dispatch. An explicitly unchecked throwing cast may either fail
// at its CLR-reified cast point or later at typed use, as permitted by Kotlin.

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

private class ProbePacket<out T>(val value: T)

private class WidenedProbe<out T>(private val expected: T) {
    private var lastPacket: Any? = null

    fun accepts(value: @UnsafeVariance T): Boolean = expected == value

    fun inspects(packet: ProbePacket<@UnsafeVariance T>): Boolean {
        lastPacket = packet
        return expected == packet.value
    }

    fun sawSamePacket(packet: Any?): Boolean = lastPacket === packet
}

private class ErasedUncheckedRead<T>(private val stored: Any?) {
    @Suppress("UNCHECKED_CAST")
    fun read(): T = stored as T
}

private class NonNullErasedUncheckedRead<T : Any>(private val stored: Any?) {
    @Suppress("UNCHECKED_CAST")
    fun read(): T = stored as T
}

private open class RouteBase<out T>(private val value: T) {
    open fun label(): String = "Base>$value"
}

private open class RouteMid<T>(value: T) : RouteBase<T>(value) {
    override fun label(): String = "Mid>" + super.label()
}

private class RouteLeaf<T>(value: T) : RouteMid<T>(value) {
    override fun label(): String = "Leaf>" + super.label()
}

open class ErasedBase<T>(private var stored: T) {
    open fun read(): T = stored

    open fun write(value: T) {
        stored = value
    }
}

class StringBase(value: String) : ErasedBase<String>(value)

class DerivedErased<U>(value: U) : ErasedBase<U>(value)

class OverridingIntBase(value: Int) : ErasedBase<Int>(value) {
    override fun read(): Int = super.read() + 1
}

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

private var erasedReceiverCount = 0
private var erasedArgumentCount = 0
private var widenedReceiverCount = 0
private var widenedPacketCount = 0

private fun countedErasedReceiver(value: Box<Int>): Box<Int> {
    erasedReceiverCount++
    return value
}

private fun countedErasedArgument(value: Int): Int {
    erasedArgumentCount++
    return value
}

private fun guardedRead(value: Box<Int>): Int = value.get()

private fun guardedWrite(value: Box<Int>, replacement: Int) {
    value.put(replacement)
}

private fun guardedVirtualRead(value: ErasedBase<Int>): Int = value.read()

private fun countedWidenedReceiver(value: WidenedProbe<Any?>): WidenedProbe<Any?> {
    widenedReceiverCount++
    return value
}

private fun countedWidenedPacket(value: ProbePacket<Any?>): ProbePacket<Any?> {
    widenedPacketCount++
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

    try {
        @Suppress("UNCHECKED_CAST")
        val wrongArguments = erased as Any as ErasedBase<Int>
        if (wrongArguments as Any !== erased) return "fail 28: unchecked cast changed identity"
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

    val guarded = Box(35)
    if (guardedRead(guarded) != 35) return "fail 35: guarded typed result"
    guardedWrite(guarded, 36)
    if (guardedRead(guarded) != 36) return "fail 36: guarded typed argument"
    erasedReceiverCount = 0
    erasedArgumentCount = 0
    countedErasedReceiver(guarded).put(countedErasedArgument(37))
    if (erasedReceiverCount != 1 || erasedArgumentCount != 1 || guardedRead(guarded) != 37) {
        return "fail 37: guarded receiver/argument evaluation"
    }
    if (guardedVirtualRead(OverridingIntBase(38)) != 39) return "fail 38: guarded virtual override"

    try {
        @Suppress("UNCHECKED_CAST")
        val mismatchedBox = Box("mismatch") as Any as Box<Int>
        guardedRead(mismatchedBox)
        return "fail 39: mismatched erased view did not fail at result use"
    } catch (_: ClassCastException) {
    }

    val mutableString = Box("before")
    @Suppress("UNCHECKED_CAST")
    val mutableAsInt = try {
        mutableString as Any as Box<Int>
    } catch (_: ClassCastException) {
        null
    }
    if (mutableAsInt != null) {
        mutableAsInt.put(40)
        val mutableStar: Box<*> = mutableString
        if (mutableStar.get() != 40) return "fail 41: erased mutation did not update shared storage"
        try {
            val impossible: String = mutableString.get()
            impossible.length
            return "fail 42: erased mutation did not fail at later String use"
        } catch (_: ClassCastException) {
        }
    }

    val mutableInt = Box(43)
    @Suppress("UNCHECKED_CAST")
    val mutableAsString = try {
        mutableInt as Any as Box<String>
    } catch (_: ClassCastException) {
        null
    }
    if (mutableAsString != null) {
        mutableAsString.put("after")
        val inverseStar: Box<*> = mutableInt
        if (inverseStar.get() != "after") return "fail 44: inverse mutation did not update shared storage"
        try {
            val impossible: Int = mutableInt.get()
            impossible + 1
            return "fail 45: inverse mutation did not fail at later Int use"
        } catch (_: ClassCastException) {
        }
    }

    val widenedProbe: WidenedProbe<Any?> = WidenedProbe(46)
    if (!widenedProbe.accepts(46)) return "fail 46: widened direct input true"
    if (widenedProbe.accepts("wrong")) return "fail 47: widened direct input false"
    val matchingPacket = ProbePacket<Any?>(46)
    if (!widenedProbe.inspects(matchingPacket)) return "fail 48: widened nested input true"
    if (!widenedProbe.sawSamePacket(matchingPacket)) return "fail 49: nested argument identity"
    val wrongPacket = ProbePacket<Any?>("wrong")
    if (widenedProbe.inspects(wrongPacket)) return "fail 50: widened nested input false"
    if (!widenedProbe.sawSamePacket(wrongPacket)) return "fail 51: wrong nested argument identity"
    widenedReceiverCount = 0
    widenedPacketCount = 0
    if (countedWidenedReceiver(widenedProbe).inspects(countedWidenedPacket(wrongPacket))) {
        return "fail 52: counted widened nested input false"
    }
    if (widenedReceiverCount != 1 || widenedPacketCount != 1) {
        return "fail 53: widened receiver/argument evaluation"
    }

    val intRoute: RouteBase<Any?> = RouteLeaf(54)
    if (intRoute.label() != "Leaf>Mid>Base>54") return "fail 54: widened Int override chain"
    val stringRoute: RouteBase<Any?> = RouteLeaf("fifty-five")
    if (stringRoute.label() != "Leaf>Mid>Base>fifty-five") {
        return "fail 55: widened String override chain"
    }
    val nullableUnchecked: Int? = ErasedUncheckedRead<Int?>(null).read()
    if (nullableUnchecked != null) return "fail 56: erased unchecked nullable read"
    try {
        NonNullErasedUncheckedRead<Any>(null).read()
        return "fail 57: erased unchecked non-null read accepted null"
    } catch (_: NullPointerException) {
    }
    return "OK"
}
