// MODULE: lib
// FILE: lib.kt

package property.accessor.lib

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class AccessorTag(val value: String)

const val constant: Int = 40

@get:AccessorTag("top-getter")
@set:AccessorTag("top-setter")
var state: Int = 40

class Cell(
    @get:AccessorTag("member-getter")
    @set:AccessorTag("member-setter")
    var value: Int,
)

fun constantReference() = ::constant

fun stateReference() = ::state

fun memberReference() = Cell::value

// MODULE: main(lib)
// FILE: main.kt

import property.accessor.lib.*
import kotlin.reflect.KAnnotatedElement

private fun tag(element: KAnnotatedElement): String? =
    (element.annotations.singleOrNull() as? AccessorTag)?.value

fun box(): String {
    val constant = constantReference()
    if (!constant.isConst || constant.getter() != 40) return "fail 1: producer const"

    val producerState = stateReference()
    if (tag(producerState.getter) != "top-getter" || tag(producerState.setter) != "top-setter") {
        return "fail 2: producer top annotations"
    }
    producerState.setter(41)
    if (producerState.getter() != 41) return "fail 3: producer top accessors"

    val consumerState = ::state
    if (tag(consumerState.getter) != "top-getter" || tag(consumerState.setter) != "top-setter") {
        return "fail 4: imported top annotations"
    }
    consumerState.setter(42)
    if (producerState.getter() != 42) return "fail 5: imported top accessors"

    val cell = Cell(40)
    val producerMember = memberReference()
    if (tag(producerMember.getter) != "member-getter" || tag(producerMember.setter) != "member-setter") {
        return "fail 6: producer member annotations"
    }
    producerMember.setter(cell, 41)
    if (producerMember.getter.call(cell) != 41) return "fail 7: producer member accessors"

    val consumerMember = Cell::value
    if (tag(consumerMember.getter) != "member-getter" || tag(consumerMember.setter) != "member-setter") {
        return "fail 8: imported member annotations"
    }
    if (consumerMember.getter.callBy(mapOf(consumerMember.getter.parameters[0] to cell)) != 41) {
        return "fail 9: imported member getter callBy"
    }
    consumerMember.setter.callBy(
        mapOf(
            consumerMember.setter.parameters[0] to cell,
            consumerMember.setter.parameters[1] to 42,
        ),
    )
    if (cell.value != 42) return "fail 10: imported member setter callBy"
    return "OK"
}
