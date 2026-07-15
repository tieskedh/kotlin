class Item(val value: Int)

private class Initializer(private val offset: Int) : (Int) -> Int {
    override fun invoke(index: Int): Int = offset + index
}

fun ints(size: Int): IntArray = IntArray(size) { index -> index + 1 }

fun longs(size: Int): LongArray = LongArray(size) { 5L }

fun doubles(size: Int): DoubleArray = DoubleArray(size) { 1.5 }

fun booleans(size: Int): BooleanArray = BooleanArray(size) { index -> index == 0 }

fun chars(size: Int): CharArray = CharArray(size) { 'A' }

fun strings(size: Int, value: String): Array<String> = Array(size) { value }

fun nullableStrings(size: Int): Array<String?> = Array(size) { index ->
    if (index == 0) "first" else null
}

fun items(size: Int): Array<Item> = Array(size) { index -> Item(index) }

fun callable(size: Int, initializer: (Int) -> Int): IntArray = IntArray(size, initializer)

fun implemented(size: Int): IntArray = IntArray(size, Initializer(10))

fun localReturn(size: Int): IntArray = IntArray(size) initializer@{ index ->
    if (index == 1) return@initializer 9
    index
}

fun main() {
    println(ints(2)[1])
}
