// Data objects reuse the ordinary CLR singleton representation. Their shared generated bodies
// provide type-based equality, a declaration-stable constant hash, and the simple source name.
data object Ready

class DataObjectHost {
    data object Nested
}

fun equalReady(other: Any?): Boolean = Ready == other

fun main() {
    println(Ready)
    println(Ready.hashCode())
    println(DataObjectHost.Nested)
}
