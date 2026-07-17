// ListIterator is a canonical identity plus a covariant declared execution capability. It needs
// no invariant exact view because its element type occurs only in result positions.

class PairCursor<T>(
    private val first: T,
    private val second: T,
) : ListIterator<T> {
    private var position: Int = 0

    override fun hasNext(): Boolean = position < 2

    override fun next(): T {
        val result = if (position == 0) first else if (position == 1) second else throw NoSuchElementException()
        position++
        return result
    }

    override fun hasPrevious(): Boolean = position > 0

    override fun previous(): T {
        if (position == 0) throw NoSuchElementException()
        position--
        return if (position == 0) first else second
    }

    override fun nextIndex(): Int = position

    override fun previousIndex(): Int = position - 1
}

open class ForwardCursor<T>(
    protected val first: T,
    protected val second: T,
) : Iterator<T> {
    protected var position: Int = 0

    override fun hasNext(): Boolean = position < 2

    override fun next(): T {
        val result = if (position == 0) first else if (position == 1) second else throw NoSuchElementException()
        position++
        return result
    }
}

class InheritedPairCursor<T>(first: T, second: T) :
    ForwardCursor<T>(first, second), ListIterator<T> {
    override fun hasPrevious(): Boolean = position > 0

    override fun previous(): T {
        if (position == 0) throw NoSuchElementException()
        position--
        return if (position == 0) first else second
    }

    override fun nextIndex(): Int = position

    override fun previousIndex(): Int = position - 1
}

fun exactNext(iterator: ListIterator<Int>): Int = iterator.next()

fun wideNext(iterator: ListIterator<Any?>): Any? = iterator.next()

fun exactPrevious(iterator: ListIterator<Int>): Int = iterator.previous()

fun widePrevious(iterator: ListIterator<Any?>): Any? = iterator.previous()

fun <T> openNext(iterator: ListIterator<T>): T = iterator.next()

fun inheritedNext(iterator: InheritedPairCursor<Int>): Int = iterator.next()

fun widen(iterator: ListIterator<Int>): ListIterator<Any?> = iterator

fun main() {
    val iterator: ListIterator<Int> = PairCursor(4, 9)
    println(exactNext(iterator))
    println(wideNext(widen(iterator)))
    println(exactPrevious(iterator))
    println(widePrevious(widen(iterator)))
    println(openNext(PairCursor(1, 2)))
    println(inheritedNext(InheritedPairCursor(6, 8)))
    println(widen(iterator) === iterator)
}
