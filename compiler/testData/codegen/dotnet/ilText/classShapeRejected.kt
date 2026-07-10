// Every class here is outside the supported class shape; each is skipped whole with a
// warning and only the file facade is emitted — never a silent partial class. (An `open`
// class is supported since the inheritance model; `abstract` needs an abstract-member model
// and stays rejected.)
abstract class A

class B<T>

class C {
    class Nested
}

fun main() {
    println("rejected")
}
