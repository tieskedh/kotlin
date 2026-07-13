// Interface extends interface (ifaceprobe_s6): the super-interface list is an `implements`
// line on the interface declaration, a class implements only its DIRECT interfaces (transitively
// implied super-interfaces are never repeated), and a call to an inherited interface member
// through a sub-interface-typed receiver MUST name the DECLARING interface in the callvirt
// operand (naming the sub-interface is a runtime MissingMethodException). Sub->super interface
// widening is a free reference upcast.
interface Super {
    fun s(): String
}

interface Sub : Super {
    fun t(): String
}

class Impl : Sub {
    override fun s(): String = "s"
    override fun t(): String = "t"
}

fun viaSub(x: Sub): String = x.s() + x.t()

fun viaSuper(x: Super): String = x.s()

fun main() {
    val i = Impl()
    println(viaSub(i))
    println(viaSuper(i))
    val sub: Sub = i
    val sup: Super = sub
    println(sup.s())
}
