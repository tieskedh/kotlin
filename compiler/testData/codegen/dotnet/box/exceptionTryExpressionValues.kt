// 'try' used as an expression, executed on the real CoreCLR: the normal branch's value and the
// catch branch's value both flow through the synthetic result local across the region boundary
// (probe-verified values 42/-42), the faulting division being the CLR's own
// DivideByZeroException caught as kotlin.ArithmeticException. The finally variant reuses the
// same result plumbing with the finally's side effect observed.

fun two(): Int = 2

fun zero(): Int = 0

fun box(): String {
    val a = try {
        84 / two()
    } catch (e: ArithmeticException) {
        -42
    }
    if (a != 42) return "FAIL a"
    val b = try {
        84 / zero()
    } catch (e: ArithmeticException) {
        -42
    }
    if (b != -42) return "FAIL b"
    var log = ""
    val c = try {
        84 / two()
    } catch (e: ArithmeticException) {
        -1
    } finally {
        log = log + "f"
    }
    if (c != 42) return "FAIL c"
    if (log != "f") return "FAIL log: " + log
    return "OK"
}
