// ECMA-335 implicit interface mapping matches candidate methods by name AND full signature
// INCLUDING the return type, and this backend emits no `.override` arrows (the JVM supports
// this shape via generated bridge methods), so an INHERITED covariant-return member cannot
// satisfy an interface slot: the shape assembles without any ilasm diagnostic and every use of
// the class throws TypeLoadException at first JIT of a using method (probe ifaceprobe_s10,
// function and property variants). The member pre-pass therefore rejects `Combo` (inherited
// `make(): Bottom` vs `Maker.make(): Top`) and `Combo2` (inherited `thing: Bottom` vs
// `HasThing.thing: Top`) whole-class; the interfaces and base classes are fine and survive.
// The DECLARED covariant override of an interface member is caught by the pre-existing
// covariant-return gate (ilText/inheritanceCovariantReturnRejected.kt pins the base-class
// flavor).
interface Maker {
    fun make(): Top
}

interface HasThing {
    val thing: Top
}

open class Top

class Bottom : Top()

open class Factory {
    open fun make(): Bottom = Bottom()
}

class Combo : Factory(), Maker

open class ThingHolder {
    open val thing: Bottom = Bottom()
}

class Combo2 : ThingHolder(), HasThing

fun main() {
    println("rejected")
}
