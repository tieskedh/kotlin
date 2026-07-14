// Every class here is outside the supported class shape; each is skipped whole with a
// warning and only the file facade is emitted — never a silent partial class. (An `open`
// class is supported since the inheritance model, abstract/sealed classes since the abstract
// class model, a plain generic class since the generics model, and a static-style named nested
// class since the nested-class model — `B` pins declaration-site VARIANCE and `C.Nested` pins
// the still-rejected outer-instance-capturing `inner` flavor.)

class B<out T>

class C {
    inner class Nested
}

fun main() {
    println("rejected")
}
