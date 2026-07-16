// Erased KProperty0/1 wrappers: name/get/invoke/set, primitive result variance, bound receiver
// evaluation, and ordinary getter/setter callable delegation.

import kotlin.reflect.KMutableProperty2
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

private var topValue: Int = 40
private val topRead: Int
    get() = topValue + 2

private class Cell(var value: Int)

private class ManualProperty : kotlin.reflect.KMutableProperty1<Cell, Int> {
    override val name: String
        get() = "manual"

    override fun get(receiver: Cell): Int = receiver.value

    override fun invoke(receiver: Cell): Int = get(receiver)

    override fun set(receiver: Cell, value: Int) {
        receiver.value = value
    }
}

private class ManualProperty2 : KMutableProperty2<ExtensionHost, Cell, Int> {
    override val name: String
        get() = "manual2"

    override fun get(receiver1: ExtensionHost, receiver2: Cell): Int = receiver2.value

    override fun invoke(receiver1: ExtensionHost, receiver2: Cell): Int = get(receiver1, receiver2)

    override fun set(receiver1: ExtensionHost, receiver2: Cell, value: Int) {
        receiver2.value = value
    }
}

private var observedProperty2Name: String = ""

private class ExtensionDelegate {
    operator fun getValue(receiver: Cell, property: KProperty<*>): Int {
        observedProperty2Name = property.name
        return receiver.value
    }

    operator fun setValue(receiver: Cell, property: KProperty<*>, value: Int) {
        observedProperty2Name = property.name
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

private var receiverEvaluations: Int = 0

private fun evaluatedCell(cell: Cell): Cell {
    receiverEvaluations = receiverEvaluations + 1
    return cell
}

fun box(): String {
    val read = ::topRead
    if (read.name != "topRead") return "fail 1: name"
    if (read.get() != 42 || read() != 42) return "fail 2: KProperty0 get/invoke"
    val callable: () -> Any = read
    if (callable !== read || callable() != 42) return "fail 3: callable identity"

    val mutable = ::topValue
    mutable.set(41)
    if (mutable.get() != 41 || mutable() != 41) return "fail 4: KMutableProperty0"

    val cell = Cell(40)
    val unbound = Cell::value
    if (unbound.name != "value") return "fail 5: unbound name"
    if (unbound.get(cell) != 40 || unbound(cell) != 40) return "fail 6: KProperty1 get/invoke"
    unbound.set(cell, 41)
    if (cell.value != 41) return "fail 7: KMutableProperty1 set"

    val exact: KProperty1<Cell, Int> = Cell::value
    val widened: KProperty1<Cell, Any> = exact
    if (widened !== exact || widened.get(cell) != 41) return "fail 8: primitive result variance"

    receiverEvaluations = 0
    val bound = evaluatedCell(cell)::value
    if (receiverEvaluations != 1) return "fail 9: bound receiver evaluated more than once"
    bound.set(42)
    if (bound.get() != 42 || bound() != 42 || cell.value != 42) return "fail 10: bound mutable property"

    val manual = ManualProperty()
    manual.set(cell, 43)
    if (manual.name != "manual" || manual.get(cell) != 43 || manual(cell) != 43) {
        return "fail 11: user implementation"
    }

    val readFirst = ::topRead
    val readSecond = ::topRead
    if (readFirst === readSecond || readFirst != readSecond) return "fail 12: immutable equality"
    if (readFirst.hashCode() != readSecond.hashCode()) return "fail 13: immutable hash"
    if (readFirst.toString() != "property topRead (Kotlin reflection is not available)") {
        return "fail 14: property rendering"
    }

    val mutableFirst = ::topValue
    val mutableSecond = ::topValue
    if (mutableFirst === mutableSecond || mutableFirst != mutableSecond) return "fail 15: mutable equality"
    if (mutableFirst.hashCode() != mutableSecond.hashCode()) return "fail 16: mutable hash"
    if (readFirst.equals(mutableFirst)) return "fail 17: different property"

    val boundFirst = cell::value
    val boundSecond = cell::value
    if (boundFirst === boundSecond || boundFirst != boundSecond) return "fail 18: bound equality"
    if (boundFirst.hashCode() != boundSecond.hashCode()) return "fail 19: bound hash"
    if (boundFirst == Cell(43)::value) return "fail 20: distinct bound receiver"

    if (manual == ManualProperty()) return "fail 21: user implementation identity"

    val extensionHost = ExtensionHost()
    val extensionCell = Cell(40)
    if (extensionHost.read(extensionCell) != 40 || observedProperty2Name != "delegatedValue") {
        return "fail 22: KMutableProperty2 delegated get"
    }
    extensionHost.write(extensionCell, 41)
    if (extensionCell.value != 41 || observedProperty2Name != "delegatedValue") {
        return "fail 23: KMutableProperty2 delegated set"
    }
    val manual2: KMutableProperty2<ExtensionHost, Cell, Int> = ManualProperty2()
    manual2.set(extensionHost, extensionCell, 42)
    if (manual2.get(extensionHost, extensionCell) != 42 || manual2(extensionHost, extensionCell) != 42) {
        return "fail 24: explicit KMutableProperty2"
    }
    return "OK"
}
