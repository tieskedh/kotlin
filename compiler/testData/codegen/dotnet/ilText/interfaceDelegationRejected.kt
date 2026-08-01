// An unsupported delegated interface evicts the whole forwarding class through the normal
// supertype cascade. A supported delegated sibling survives, including the plain-parameter
// `$$delegate_0` field shape; a class mixing a supported and unsupported delegate is also evicted
// whole-class rather than retaining a partial interface surface.
interface Able {
    fun f(): Int
}

class Impl : Able {
    override fun f(): Int = 1
}

class GoodDelegate(delegate: Able) : Able by delegate

interface UnsupportedDelegate {
    fun convert(value: Array<Int?>): Array<Int?>
}

class UnsupportedImpl : UnsupportedDelegate {
    override fun convert(value: Array<Int?>): Array<Int?> = value
}

class BadDelegate(delegate: UnsupportedDelegate) : UnsupportedDelegate by delegate

class MixedDelegate(good: Able, bad: UnsupportedDelegate) :
    Able by good,
    UnsupportedDelegate by bad

fun main() {
    println(GoodDelegate(Impl()).f())
}
