// Generic top-level functions remain real CLR generic methods: `.method ... !!0 'id'<'T'>(!!0
// 'x')`, with call-site instantiation on the method token. Kotlin-owned generic classes do not:
// `Box<T>` is one erased `Box` owner whose object-backed constructor/member slots compose with
// string, scalar, nullable-scalar, user-class, nested Box, and open `!!0` method arguments. KLIB
// retains the logical `Box<T>` return of `wrap`; CIL returns the one non-generic Box classifier.

class Marker(val tag: Int)

class Box<T>(val v: T)

fun <T> id(x: T): T = x

fun <T> wrap(x: T): Box<T> = Box<T>(x)

fun <A, B> second(a: A, b: B): B = b

fun <T> chain(x: T): T = id<T>(x)

fun main() {
    val s: String = id<String>("hello")
    val i: Int = id<Int>(1)
    val n: Int? = id<Int?>(2)
    val m: Marker = id<Marker>(Marker(3))
    val b: Boolean = second<Int, Boolean>(1, true)
    val c: String = chain<String>("chained")
    val w: Box<Int> = wrap<Int>(7)
    val nested: Box<String> = id<Box<String>>(Box<String>("nested"))
    println(s)
    println(i)
    println(n ?: 0)
    println(m.tag)
    println(b)
    println(c)
    println(w.v)
    println(nested.v)
}
