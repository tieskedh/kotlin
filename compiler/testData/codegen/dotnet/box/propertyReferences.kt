// Erased KProperty0/1/2 wrappers: name/annotations/get/invoke/set, primitive result variance,
// bound receiver evaluation, ordinary getter/setter callable delegation, and name-only local
// delegate tokens.

import kotlin.reflect.KMutableProperty
import kotlin.reflect.KMutableProperty2
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1
import kotlin.reflect.typeOf

private var topValue: Int = 40
private val topRead: Int
    get() = topValue + 2

private class Cell(var value: Int)

private class ManualProperty : kotlin.reflect.KMutableProperty1<Cell, Int> {
    override val name: String
        get() = "manual"

    override val annotations: List<Annotation> = emptyList()

    override val returnType = typeOf<Int>()

    override val parameters = emptyList<kotlin.reflect.KParameter>()

    override val typeParameters = emptyList<kotlin.reflect.KTypeParameter>()

    override fun call(vararg args: Any?): Int = get(args[0] as Cell)

    override fun callBy(args: Map<kotlin.reflect.KParameter, Any?>): Int =
        throw UnsupportedOperationException("manual callBy")

    override fun get(receiver: Cell): Int = receiver.value

    override fun invoke(receiver: Cell): Int = get(receiver)

    override fun set(receiver: Cell, value: Int) {
        receiver.value = value
    }
}

private class ManualProperty2 : KMutableProperty2<ExtensionHost, Cell, Int> {
    override val name: String
        get() = "manual2"

    override val annotations: List<Annotation> = emptyList()

    override val returnType = typeOf<Int>()

    override val parameters = emptyList<kotlin.reflect.KParameter>()

    override val typeParameters = emptyList<kotlin.reflect.KTypeParameter>()

    override fun call(vararg args: Any?): Int = get(args[0] as ExtensionHost, args[1] as Cell)

    override fun callBy(args: Map<kotlin.reflect.KParameter, Any?>): Int =
        throw UnsupportedOperationException("manual callBy")

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

private var observedLocalName: String = ""
private var observedLocalMutable: Boolean = false
private var observedLocalGetFailure: Boolean = false
private var observedLocalInvokeFailure: Boolean = false
private var observedLocalCallFailure: Boolean = false
private var observedLocalCallByFailure: Boolean = false
private var observedLocalRendering: String = ""

private fun observeLocalProperty(property: KProperty<*>) {
    observedLocalName = property.name
    observedLocalMutable = property is KMutableProperty<*>
    observedLocalRendering = property.toString()
    if (property is KProperty0<*>) {
        observedLocalGetFailure = try {
            property.get()
            false
        } catch (exception: UnsupportedOperationException) {
            exception.message == "Not supported for local property reference."
        }
        observedLocalInvokeFailure = try {
            property()
            false
        } catch (exception: UnsupportedOperationException) {
            exception.message == "Not supported for local property reference."
        }
        observedLocalCallFailure = try {
            property.call()
            false
        } catch (exception: UnsupportedOperationException) {
            exception.message == "Not supported for local property reference."
        }
        observedLocalCallByFailure = try {
            property.callBy(emptyMap())
            false
        } catch (exception: UnsupportedOperationException) {
            exception.message == "Not supported for local property reference."
        }
    } else {
        observedLocalGetFailure = false
        observedLocalInvokeFailure = false
        observedLocalCallFailure = false
        observedLocalCallByFailure = false
    }
}

private class LocalDelegate(private var value: Int) {
    operator fun getValue(receiver: Any?, property: KProperty<*>): Int {
        observeLocalProperty(property)
        return value
    }

    operator fun setValue(receiver: Any?, property: KProperty<*>, value: Int) {
        observeLocalProperty(property)
        this.value = value
    }
}

private fun readLocalDelegate(): Int {
    val localRead by LocalDelegate(40)
    return localRead
}

private fun writeLocalDelegate(): Int {
    var localWrite by LocalDelegate(40)
    localWrite = 42
    return localWrite
}

private var localProvideCalls: Int = 0

private class ProvidingLocalDelegate(private val value: Int) {
    operator fun provideDelegate(receiver: Any?, property: KProperty<*>): ProvidingLocalDelegate {
        if (receiver == null) {
            localProvideCalls = localProvideCalls + 1
        }
        observeLocalProperty(property)
        return this
    }

    operator fun getValue(receiver: Any?, property: KProperty<*>): Int {
        observeLocalProperty(property)
        return value
    }
}

private fun readProvidedLocalDelegate(): Int {
    val localProvided by ProvidingLocalDelegate(43)
    return localProvided
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
    if (read.call() != 42) return "fail 2a: KProperty0 call"
    if (read.callBy(emptyMap()) != 42) return "fail 2b: KProperty0 callBy"
    val callable: () -> Any = read
    if (callable !== read || callable() != 42) return "fail 3: callable identity"

    val mutable = ::topValue
    mutable.set(41)
    if (mutable.get() != 41 || mutable() != 41) return "fail 4: KMutableProperty0"

    val cell = Cell(40)
    val unbound = Cell::value
    if (unbound.name != "value") return "fail 5: unbound name"
    if (unbound.get(cell) != 40 || unbound(cell) != 40) return "fail 6: KProperty1 get/invoke"
    if (unbound.call(cell) != 40) return "fail 6a: KProperty1 call"
    if (unbound.callBy(mapOf(unbound.parameters[0] to cell)) != 40) return "fail 6b: KProperty1 callBy"
    val missingReceiver = try {
        unbound.callBy(emptyMap())
        null
    } catch (exception: IllegalArgumentException) {
        exception.message
    }
    if (missingReceiver != "No argument provided for a required parameter: ${unbound.parameters[0]}") {
        return "fail 6c: KProperty1 missing receiver $missingReceiver"
    }
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
    if (bound.callBy(emptyMap()) != 42) return "fail 10a: bound KProperty callBy"

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

    if (readLocalDelegate() != 40) return "fail 25: local delegated val"
    if (observedLocalName != "localRead" || observedLocalMutable) return "fail 26: local val token"
    if (!observedLocalGetFailure || !observedLocalInvokeFailure || !observedLocalCallFailure ||
        !observedLocalCallByFailure
    ) {
        return "fail 27: local val unsupported access"
    }
    if (observedLocalRendering != "property localRead (Kotlin reflection is not available)") {
        return "fail 28: local val rendering"
    }

    if (writeLocalDelegate() != 42) return "fail 29: local delegated var"
    if (observedLocalName != "localWrite" || !observedLocalMutable) return "fail 30: local var token"
    if (!observedLocalGetFailure || !observedLocalInvokeFailure || !observedLocalCallFailure ||
        !observedLocalCallByFailure
    ) {
        return "fail 31: local var unsupported access"
    }
    if (observedLocalRendering != "property localWrite (Kotlin reflection is not available)") {
        return "fail 32: local var rendering"
    }

    localProvideCalls = 0
    if (readProvidedLocalDelegate() != 43 || localProvideCalls != 1) {
        return "fail 33: local provideDelegate"
    }
    if (observedLocalName != "localProvided" || observedLocalMutable) {
        return "fail 34: local provided token"
    }
    return "OK"
}
