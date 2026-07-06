// Every class here is outside the supported final-class shape; each is skipped whole with a
// warning and only the file facade is emitted — never a silent partial class.
open class A

class B<T>

class C {
    class Nested
}

fun main() {
    println("rejected")
}
