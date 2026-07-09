// An object used from another class's method, as a parameter and return type of a top-level
// function, and as a bare statement: Kotlin makes a bare object reference a first-active-use
// trigger, so `A` in statement position is ldsfld + pop, never a no-op.
object A {
    val tag = "A"
}

class User {
    fun readTag(): String = A.tag
}

fun pass(a: A): A = a

fun main() {
    A
    println(User().readTag())
    println(pass(A).tag)
}
