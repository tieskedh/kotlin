// Generic eviction rides the existing fixpoint (the generics analogue of
// inheritanceBaseEvicted.kt): `Bad` fails the member pre-pass (a `FloatArray` has no IL
// mapping), and because instantiations map their arguments through the LIVE class map, every
// USE of an instantiation naming `Bad` fails too — `useBad`'s `GB<Bad>` parameter evicts the
// function per-function, and `DBad`, whose `extends` re-resolution maps the base
// instantiation's arguments each render round, is evicted whole-class with a reason carrying
// the type-argument eviction down the chain. The sibling `DInt : GB<Int>` keeps its
// instantiated `extends class 'GB`1'<int32>` line and base-ctor chain untouched; no emitted
// token may ever name an evicted class.
open class Bad {
    fun f(x: FloatArray): FloatArray = x
}

open class GB<T>(val v: T)

class DBad(b: Bad) : GB<Bad>(b)

class DInt(x: Int) : GB<Int>(x)

fun useBad(g: GB<Bad>): Int = 0

fun main() {
    println(DInt(7).v)
}
