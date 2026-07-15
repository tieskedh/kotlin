// B itself is outside the supported class shape and is skipped whole. Generic C.Nested pins the
// still-rejected generic-outer `inner` flavor, but C is an independent metadata parent and
// survives without that nested block. (An `open` class is supported since the inheritance
// model, abstract/sealed classes since the abstract class model, a plain generic class since the
// generics model, and a static-style named nested class since the nested-class model.)

class B<out T>

class C<T> {
    inner class Nested
}

fun main() {
    println("rejected")
}
