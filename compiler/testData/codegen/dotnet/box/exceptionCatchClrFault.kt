// A CLR-native fault flows through a Kotlin catch: the runtime's DivideByZeroException IS-A
// System.ArithmeticException (probe-verified), so 'catch (e: ArithmeticException)' catches a
// division fault — closing the divide-by-zero catchability debt — with the CLR's message kept
// verbatim (JVM precedent: the platform message is used as-is). The Int.MIN_VALUE / -1 guard is
// unchanged and still returns Int.MIN_VALUE without throwing (the CLR would throw
// OverflowException there, which Kotlin defines as a normal result). 'catch (e: Throwable)'
// catches everything the CLR throws, including the same fault.

fun zero(): Int = 0

fun minusOne(): Int = -1

fun box(): String {
    val z = zero()
    var ok = false
    try {
        val x = 1 / z
        ok = x == 42
    } catch (e: ArithmeticException) {
        ok = e.message == "Attempted to divide by zero."
    }
    if (!ok) return "FAIL 1"
    if (Int.MIN_VALUE / minusOne() != Int.MIN_VALUE) return "FAIL 2"
    var thrown = false
    try {
        val y = 5 / z
        thrown = y == 42
    } catch (e: Throwable) {
        thrown = true
    }
    if (!thrown) return "FAIL 3"
    return "OK"
}
