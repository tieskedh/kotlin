// Mapped exception types + throw: built-in exception classes are TYPE-MAPPED onto the CLR
// hierarchy (Throwable/Exception -> System.Exception, IllegalStateException ->
// System.InvalidOperationException, IllegalArgumentException -> System.ArgumentException) and
// IrThrow maps 1:1 onto IL 'throw' (JVM precedent, no lowering). 'describe' reads
// Throwable.message via System.Exception::get_Message(); 'chain' uses the (message, cause)
// constructor overload.
package test

import kotlin.io.println

fun fail(msg: String) {
    throw IllegalStateException(msg)
}

fun failDefault() {
    throw Exception()
}

fun describe(e: Throwable): String? = e.message

// The receiver's static type is a subclass: 'message' arrives as a fake override owned by
// IllegalStateException (not by Throwable), and 'e' itself widens to the System.Exception slot
// of the 'describe' parameter.
fun describeIse(e: IllegalStateException): String? {
    if (e.cause === null) {
        return e.message
    }
    return describe(e)
}

fun chain(m: String, c: Throwable) {
    throw IllegalArgumentException(m, c)
}

fun main() {
    println(describe(Exception("hi")))
}
