// Objects flow through the same member pre-pass as classes: the getter of `val x` and the
// user-declared `fun get_x(): Int` map to the same IL method identity `get_x()`, so the whole
// object is rejected with a warning (JVM analogue: PLATFORM_DECLARATION_CLASH) and only the
// file facade is emitted — no INSTANCE field, no .cctor, no partial object.
object O {
    val x = 1
    fun get_x(): Int = 2
}

fun main() {
    println("clash rejected")
}
