// A 'try' expression none of whose branches reaches its join label (the try branch and the
// catch handler both throw): nothing is drained into the synthetic result local, the join
// reload is skipped, and the phantom result keeps the stack tracker balanced so the same
// function still compiles (and executes) a later protected region. Previously the unreferenced
// join leaked a phantom stack value and crashed the backend at the second '.try'.

fun allThrow(c: Boolean): String {
    if (c) {
        val x: Int = try {
            throw IllegalStateException("a")
        } catch (e: Exception) {
            throw IllegalStateException("b")
        }
    }
    return try {
        "reached"
    } catch (e: Exception) {
        "caught"
    }
}

fun box(): String {
    if (allThrow(false) != "reached") return "FAIL reached"
    try {
        allThrow(true)
    } catch (e: IllegalStateException) {
        if (e.message == "b") return "OK"
        return "FAIL message"
    }
    return "FAIL no throw"
}
