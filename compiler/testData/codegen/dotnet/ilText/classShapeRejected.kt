// B itself is outside the supported class shape and is skipped whole. C is an independent valid
// metadata parent and survives. (An `open` class is supported since the inheritance model,
// abstract/sealed classes since the abstract class model, a plain generic class since the
// generics model, and generic-outer inner classes since the copied-parameter model.)

class B<out T>

class C<T>

fun main() {
    println("rejected")
}
