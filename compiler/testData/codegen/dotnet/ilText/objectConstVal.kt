// `const val` in an object is the same CLR `literal` field shape as on a file facade, just on
// the object class: no accessors, no .property block, no entry in the object's .cctor (which
// still exists — it initializes INSTANCE); every read is inlined by the frontend.
object Config {
    const val LIMIT = 42
    const val NAME = "cfg"
}

fun main() {
    println(Config.LIMIT)
    println(Config.NAME)
}
