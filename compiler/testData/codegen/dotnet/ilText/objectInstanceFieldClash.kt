// As on JVM, public compiler-ABI fields reserve their stable names and same-named private storage
// receives a deterministic suffix. This accepts Kotlin source whose private backing field would
// otherwise collide exactly with the singleton field (A), and also avoids a type-distinguished
// duplicate name (B) which CLR metadata permits but ordinary C# cannot declare naturally.
object A {
    val INSTANCE: A? = null
}

object B {
    val INSTANCE = 1
}

fun main() {
    println(A.INSTANCE === null)
    println(B.INSTANCE)
}
