// A local data class is closure-converted before generated defaults and generic equality are
// lowered. Its lifted CLR class keeps the source name in generated text and carries captures into
// constructor and copy calls without changing data-property identity.
fun localData(seed: Int, value: Int): Any {
    data class Local(val value: Int) {
        fun captured(): Int = seed
    }

    return Local(value).copy()
}

fun <T> genericLocalData(seed: Int, value: T): Any {
    data class Local(val value: T) {
        fun captured(): Int = seed
    }

    return Local(value).copy()
}

fun main() {
    println(localData(1, 7) == localData(2, 7))
    println(localData(3, 7))
    println(genericLocalData(4, "x"))
}
