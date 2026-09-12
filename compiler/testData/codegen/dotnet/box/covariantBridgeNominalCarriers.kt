// DOTNET_COVARIANT_NOMINAL_CARRIER_PROBE
// MODULE: lib
// FILE: base.kt
package covariant.bridge.nominal.carriers

value class Id(val raw: Int)
value class Name(val raw: String)
value class Maybe(val raw: Int?)
value class Packet<T>(val raw: T)

open class Base<A, B>(private val result: B) {
    open fun choose(prefix: Int, key: A): B = result
}

open class PairBase<A, B, R>(private val result: R) {
    open fun choose(first: A, number: Int, second: B): R = result
}

open class ExactInput {
    open fun choose(key: Id): Any = "base"
}

fun <A> fromBase(value: Base<A, String>, prefix: Int, key: A): String = value.choose(prefix, key)
fun <A, B> fromPair(value: PairBase<A, B, String>, first: A, second: B): String =
    value.choose(first, 7, second)

fun idObject(raw: Int): Any = Id(raw)

// MODULE: middle(lib)
// FILE: derived.kt
package covariant.bridge.nominal.carriers

open class IdMiddle : Base<Id, String>("base") {
    override fun choose(prefix: Int, key: Id): String = "middle:$prefix:${key.raw}"
}

class IdLeaf : IdMiddle() {
    override fun choose(prefix: Int, key: Id): String = "leaf:" + super.choose(prefix, key)
}

class NameLeaf : Base<Name, String>("base") {
    override fun choose(prefix: Int, key: Name): String = "$prefix:${key.raw}"
}

class NullableNameLeaf : Base<Name?, String>("base") {
    override fun choose(prefix: Int, key: Name?): String = "$prefix:${key?.raw ?: "absent"}"
}

class NullableIdLeaf : Base<Id?, String>("base") {
    override fun choose(prefix: Int, key: Id?): String = "$prefix:${key?.raw ?: -1}"
}

class MaybeLeaf : Base<Maybe, String>("base") {
    override fun choose(prefix: Int, key: Maybe): String = "$prefix:${key.raw ?: -2}"
}

class PacketLeaf : Base<Packet<String>, String>("base") {
    override fun choose(prefix: Int, key: Packet<String>): String = "$prefix:${key.raw}"
}

class PairLeaf : PairBase<Id, Name, String>("base") {
    override fun choose(first: Id, number: Int, second: Name): String = "$number:${first.raw}:${second.raw}"
}

class ExactInputLeaf : ExactInput() {
    override fun choose(key: Id): String = "exact:${key.raw}"
}

// MODULE: main(lib, middle)
// FILE: main.kt
package covariant.bridge.nominal.carriers

open class LocalBase<A, B>(private val result: B) {
    open fun choose(key: A): B = result
}

class LocalLeaf : LocalBase<Id, String>("base") {
    override fun choose(key: Id): String = "local:${key.raw}"
}

var produced = 0
fun <T> produce(value: T): T { produced++; return value }

fun box(): String {
    val middle = fromBase(IdMiddle(), 1, Id(2))
    if (middle != "middle:1:2") return "middle=$middle"
    if (fromBase(IdLeaf(), 2, Id(3)) != "leaf:middle:2:3") return "leaf"
    if (fromBase(NameLeaf(), 3, Name("name")) != "3:name") return "name"
    if (fromBase(NullableNameLeaf(), 4, Name("present")) != "4:present") return "nullable name present"
    if (fromBase(NullableNameLeaf(), 4, null) != "4:absent") return "nullable name absent"
    if (fromBase(NullableIdLeaf(), 5, Id(6)) != "5:6") return "nullable id present"
    if (fromBase(NullableIdLeaf(), 5, null) != "5:-1") return "nullable id absent"
    if (fromBase(MaybeLeaf(), 6, Maybe(null)) != "6:-2") return "null payload"
    if (fromBase(MaybeLeaf(), 6, Maybe(8)) != "6:8") return "present payload"
    if (fromBase(PacketLeaf(), 7, Packet("packet")) != "7:packet") return "packet"
    if (fromPair(PairLeaf(), Id(8), Name("pair")) != "7:8:pair") return "pair"
    val local: LocalBase<Id, String> = LocalLeaf()
    if (local.choose(Id(9)) != "local:9") return "local"
    val exact: ExactInput = ExactInputLeaf()
    if (exact.choose(Id(10)) != "exact:10") return "already underlying"
    val absent: Name? = produce<Name?>(null)
    val present: Name? = produce<Name?>(Name("once"))
    if (produced != 2 || absent != null || present?.raw != "once") return "nullable production"
    return "OK"
}
