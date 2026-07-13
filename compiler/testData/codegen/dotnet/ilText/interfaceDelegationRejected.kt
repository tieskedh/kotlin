// Interface delegation is rejected whole-class in BOTH source spellings — the `val`-parameter
// form and the plain-parameter form (which additionally synthesizes a loose `$$delegate_0`
// field): the frontend's forwarding members (origin DELEGATED_MEMBER) are not part of the
// interface model yet, and the two cosmetically different spellings must not diverge in
// support. `Able` itself and the ordinary implementer survive.
interface Able {
    fun f(): Int
}

class Impl : Able {
    override fun f(): Int = 1
}

class DelVal(private val d: Able) : Able by d

class DelPlain(d: Able) : Able by d

fun main() {
    println(Impl().f())
}
