// Whole-PAIR eviction through the member pre-pass, in both directions. In A the COMPANION fails:
// the getter of its `val x` and its user-declared `fun get_x(): Int` map to the same IL method
// identity, and the eviction is paired — the ENCLOSING class goes down too (its .cctor news the
// companion and its singleton field is typed as the companion; a partial pair must never be
// emitted). In B the ENCLOSING class fails the same gate, taking its (otherwise fine) companion
// with it. Only the file facade survives, with one warning per class of each pair.
//
// The shape that would collide with the singleton field itself in IL — a user property named
// after the companion on the enclosing class — is a frontend REDECLARATION (a companion also
// occupies the value namespace), so the field-identity gate's coverage of the companion singleton
// field is defense-in-depth; the legal coexistence of the synthesized FIELD 'Companion' with the
// nested TYPE 'Companion' (objprobe_s6) is pinned by companionObject.kt.
class A {
    fun probe(): Int = x

    companion object {
        val x = 1
        fun get_x(): Int = 2
    }
}

class B {
    val y = 1
    fun get_y(): Int = 2

    companion object {
        fun fine(): Int = 3
    }
}

fun main() {
    println("clash rejected")
}
