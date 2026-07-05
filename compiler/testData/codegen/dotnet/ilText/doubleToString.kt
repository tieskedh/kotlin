package test

import kotlin.io.println

// Every Double-to-string conversion routes through the shared '<KotlinIl>'::DoubleToString
// runtime helper, emitted once at the end of the module. Constants are deliberately NOT folded
// at compile time (see DotNetFlattenStringConcatenationLowering), so constant and dynamic values
// of the same Double print identically.
fun main() {
    println(1.0)
    println(-0.5)
    println(100.0)
    println(1.0E20)
    println(1.5E-3)
    println(-0.0)

    val zero = 0.0
    val nan = zero / zero
    val positiveInfinity = 1.0 / zero
    val negativeInfinity = -1.0 / zero
    println(nan)
    println(positiveInfinity)
    println(negativeInfinity)

    val d = 2.0e19
    println(d * 5.0)
    println(d.toString())
    println("d = " + d)
    println("nan = " + nan)
    println("negZero = " + (-zero))

    // A Double *constant* in a concatenation: must NOT be folded at compile time (the host
    // rendering would print "1.2345678E7" even where the runtime helper diverges) and must go
    // through the same '<KotlinIl>'::DoubleToString helper as the dynamic values above; the
    // value sits in the JVM-scientific/.NET-decimal notation gap [1e7, 1e15) that the helper
    // reshapes itself.
    println("v = " + 1.2345678E7)
}
