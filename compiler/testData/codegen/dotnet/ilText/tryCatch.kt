// Statement-position try/catch: IrTry maps 1:1 onto the CLR exception table (JVM precedent, no
// lowering) — one '.try' block with consecutive typed 'catch' handlers in source order (the CLR
// matches strictly first-to-last), each handler binding its catch parameter with a 'stloc' of
// the exception object the CLR pushes at handler entry. Branches that complete normally exit
// the protected region with 'leave' to the shared join label after the construct.
package test

import kotlin.io.println

fun handle(flag: Boolean) {
    try {
        if (flag) {
            throw IllegalArgumentException("bad argument")
        }
        println("ok")
    } catch (e: IllegalArgumentException) {
        println(e.message)
    } catch (e: Throwable) {
        println("other")
    }
    println("after")
}

fun main() {
    handle(false)
    handle(true)
}
