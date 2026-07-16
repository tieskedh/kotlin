// KProperty is a non-generic reflection identity whose Get/Set slots and inherited FunctionN
// invocation stay erased. Runtime-private wrappers are constructed through internal factories.

private var topValue: Int = 40
private val topRead: Int
    get() = topValue + 2

private class Cell(var value: Int)

fun main() {
    val read = ::topRead
    println(read.name)
    println(read.get())
    println(read())

    val mutable = ::topValue
    mutable.set(41)
    println(mutable())

    val cell = Cell(40)
    val unbound = Cell::value
    println(unbound.name)
    println(unbound.get(cell))
    unbound.set(cell, 42)
    println(unbound(cell))

    val bound = cell::value
    println(bound.name)
    println(bound())
}
