class Score(private val value: Int) : Comparable<Score> {
    override fun compareTo(other: Score): Int = value - other.value
}

fun compareThroughInterface(left: Comparable<Score>, right: Score): Int = left.compareTo(right)

fun <T : Comparable<T>> compareThroughBound(left: T, right: T): Int = left.compareTo(right)

fun compareStrings(left: String, right: String): Int = left.compareTo(right)

fun compareDoubles(left: Double, right: Double): Int = left.compareTo(right)
