// The getter of `val x` and the user-declared `fun get_x(): Int` map to the same IL method
// identity `get_x()`, which ilasm rejects as a duplicate method declaration; the member
// pre-pass skips the whole class with a warning (JVM analogue: PLATFORM_DECLARATION_CLASH)
// and only the file facade is emitted — never duplicate-member IL.
class C {
    val x = 1
    fun get_x(): Int = 2
}

fun main() {
    println("clash rejected")
}
