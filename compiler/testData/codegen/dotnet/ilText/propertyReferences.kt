// KProperty is a non-generic reflection identity whose Get/Set slots and inherited FunctionN
// invocation stay erased. Runtime-private wrappers are constructed through internal factories.

import kotlin.reflect.KProperty

private var topValue: Int = 40
private val topRead: Int
    get() = topValue + 2

private class Cell(var value: Int)

private class ExtensionDelegate {
    operator fun getValue(receiver: Cell, property: KProperty<*>): Int = receiver.value

    operator fun setValue(receiver: Cell, property: KProperty<*>, value: Int) {
        receiver.value = value
    }
}

private class ExtensionHost {
    var Cell.delegatedValue: Int by ExtensionDelegate()

    fun read(receiver: Cell): Int = receiver.delegatedValue

    fun write(receiver: Cell, value: Int) {
        receiver.delegatedValue = value
    }
}

private class LocalDelegate(private var value: Int) {
    operator fun getValue(receiver: Any?, property: KProperty<*>): Int {
        println(property.name)
        return value
    }

    operator fun setValue(receiver: Any?, property: KProperty<*>, value: Int) {
        println(property.name)
        this.value = value
    }
}

private fun localDelegates() {
    val localRead by LocalDelegate(40)
    println(localRead)
    var localWrite by LocalDelegate(41)
    localWrite = 42
    println(localWrite)
}

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

    val extensionHost = ExtensionHost()
    val extensionCell = Cell(40)
    println(extensionHost.read(extensionCell))
    extensionHost.write(extensionCell, 42)
    println(extensionCell.value)

    localDelegates()
}
