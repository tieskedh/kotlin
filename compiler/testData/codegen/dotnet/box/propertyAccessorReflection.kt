import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KMutableProperty2
import kotlin.reflect.KProperty
import kotlin.reflect.KVisibility

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
private annotation class AccessorTag(val value: String)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.VALUE_PARAMETER)
private annotation class ParameterTag(val value: String)

private const val topConst: Int = 41

private object Constants {
    const val member: Int = 42
}

@get:AccessorTag("top-getter")
@set:AccessorTag("top-setter")
private var topState: Int = 40

private inline val inlineRead: Int
    get() = 42

@get:AccessorTag("parameter-getter")
@set:AccessorTag("parameter-setter")
private var parameterState: Int = 0
    set(@ParameterTag("setter-value") value) {
        field = value
    }

private val getterFailure = IllegalStateException("getter failure")

private val failingRead: Int
    get() = throw getterFailure

private class Cell(
    @get:AccessorTag("member-getter")
    @set:AccessorTag("member-setter")
    var value: Int,
)

private open class BaseCell(open var number: Int)

private class DerivedCell(override var number: Int) : BaseCell(number)

private fun tag(element: KAnnotatedElement): String? =
    (element.annotations.singleOrNull() as? AccessorTag)?.value

private fun parameterTag(element: KAnnotatedElement): String? =
    (element.annotations.singleOrNull() as? ParameterTag)?.value

private var capturedProperty2: KMutableProperty2<ExtensionHost, Cell, Int>? = null

private class CapturingDelegate {
    @Suppress("UNCHECKED_CAST")
    operator fun getValue(receiver: Cell, property: KProperty<*>): Int {
        capturedProperty2 = property as KMutableProperty2<ExtensionHost, Cell, Int>
        return receiver.value
    }

    @Suppress("UNCHECKED_CAST")
    operator fun setValue(receiver: Cell, property: KProperty<*>, value: Int) {
        capturedProperty2 = property as KMutableProperty2<ExtensionHost, Cell, Int>
        receiver.value = value
    }
}

private class ExtensionHost {
    var Cell.extensionValue: Int by CapturingDelegate()

    fun read(receiver: Cell): Int = receiver.extensionValue
}

private fun fail(message: String): String = "FAIL: $message"

fun box(): String {
    if (!::topConst.isConst || ::topConst.isLateinit) return fail("const flags")
    if (!Constants::member.isConst || Constants::member.getter() != 42) return fail("object const")
    if (::topState.isConst || ::topState.isLateinit) return fail("ordinary flags")
    if (!::inlineRead.getter.isInline) return fail("inline getter flag")

    val top = ::topState
    val topGetter = top.getter
    val topSetter = top.setter
    if (top.getter !== topGetter || top.setter !== topSetter) return fail("stable top accessors")
    if (topGetter.property !== top || topSetter.property !== top) return fail("top accessor property")
    if (topGetter.name != "<get-topState>" || topSetter.name != "<set-topState>") {
        return fail("top accessor names ${topGetter.name}/${topSetter.name}")
    }
    if (tag(topGetter) != "top-getter" || tag(topSetter) != "top-setter") {
        return fail("top accessor annotations ${topGetter.annotations}/${topSetter.annotations}")
    }
    if (topGetter.visibility != KVisibility.PRIVATE || !topGetter.isFinal || topGetter.isOpen || topGetter.isAbstract) {
        return fail("top getter declaration facts")
    }
    if (topGetter() != 40 || topGetter.call() != 40 || topGetter.callBy(emptyMap()) != 40) {
        return fail("top getter invocation")
    }
    topSetter(41)
    if (topState != 41) return fail("top setter invoke")
    if (topSetter.call(42) != Unit || topState != 42) return fail("top setter call")
    if (topSetter.callBy(mapOf(topSetter.parameters.single() to 43)) != Unit || topState != 43) {
        return fail("top setter callBy")
    }

    val parameterSetter = ::parameterState.setter
    if (tag(parameterSetter) != "parameter-setter" ||
        parameterTag(parameterSetter.parameters.single()) != "setter-value"
    ) {
        return fail("setter value parameter metadata ${parameterSetter.parameters}")
    }
    parameterSetter(42)
    if (parameterState != 42) return fail("annotated setter invocation")

    val getterFailurePreserved = try {
        ::failingRead.getter()
        false
    } catch (exception: IllegalStateException) {
        exception === getterFailure
    }
    if (!getterFailurePreserved) return fail("getter exception identity")

    val cell = Cell(40)
    val member = Cell::value
    val memberGetter = member.getter
    val memberSetter = member.setter
    if (member.getter !== memberGetter || member.setter !== memberSetter) return fail("stable member accessors")
    if (memberGetter.property !== member || memberSetter.property !== member) return fail("member accessor property")
    if (tag(memberGetter) != "member-getter" || tag(memberSetter) != "member-setter") {
        return fail("member accessor annotations")
    }
    if (memberGetter(cell) != 40 || memberGetter.call(cell) != 40) return fail("member getter")
    if (memberGetter.callBy(mapOf(memberGetter.parameters.single() to cell)) != 40) {
        return fail("member getter callBy")
    }
    memberSetter(cell, 41)
    if (memberSetter.call(cell, 42) != Unit || cell.value != 42) return fail("member setter call")
    if (memberSetter.callBy(
            mapOf(memberSetter.parameters[0] to cell, memberSetter.parameters[1] to 43),
        ) != Unit || cell.value != 43
    ) {
        return fail("member setter callBy")
    }

    val bound = cell::value
    if (bound.getter.property !== bound || bound.setter.property !== bound) return fail("bound ownership")
    if (bound.getter() != 43 || bound.getter.callBy(emptyMap()) != 43) return fail("bound getter")
    if (bound.setter.callBy(mapOf(bound.setter.parameters.single() to 44)) != Unit || cell.value != 44) {
        return fail("bound setter")
    }

    val derived: BaseCell = DerivedCell(40)
    val virtual = BaseCell::number
    if (virtual.getter(derived) != 40) return fail("virtual getter")
    virtual.setter(derived, 42)
    if (derived.number != 42) return fail("virtual setter")

    val host = ExtensionHost()
    if (host.read(cell) != 44) return fail("capture property2")
    val property2 = capturedProperty2 ?: return fail("missing property2")
    val getter2 = property2.getter
    val setter2 = property2.setter
    if (getter2.property !== property2 || setter2.property !== property2) return fail("property2 ownership")
    if (getter2(host, cell) != 44 || getter2.call(host, cell) != 44) return fail("property2 getter")
    setter2(host, cell, 44)
    if (setter2.call(host, cell, 45) != Unit || cell.value != 45) return fail("property2 setter")
    if (getter2.callBy(
            mapOf(getter2.parameters[0] to host, getter2.parameters[1] to cell),
        ) != 45
    ) {
        return fail("property2 getter callBy")
    }
    if (setter2.callBy(
            mapOf(
                setter2.parameters[0] to host,
                setter2.parameters[1] to cell,
                setter2.parameters[2] to 46,
            ),
        ) != Unit || cell.value != 46
    ) {
        return fail("property2 setter callBy")
    }

    val anotherTop = ::topState
    if (anotherTop.getter === topGetter || anotherTop.setter === topSetter) {
        return fail("accessor identity leaked across property objects")
    }
    if (anotherTop.getter != topGetter || anotherTop.setter != topSetter) {
        return fail("structurally equal accessors")
    }
    return "OK"
}
