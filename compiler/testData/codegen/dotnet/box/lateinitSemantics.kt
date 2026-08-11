// MODULE: lib
// FILE: lib.kt

package lateinit.lib

lateinit var top: String

fun isTopInitialized(): Boolean = ::top.isInitialized

fun topReference() = ::top

open class Base {
    lateinit var member: String

    fun isMemberInitialized(): Boolean = this::member.isInitialized

    fun memberReference() = this::member
}

class Derived : Base()

class Marker

class GenericHolder<T : Any> {
    lateinit var value: T

    fun isValueInitialized(): Boolean = this::value.isInitialized

    fun valueReference() = this::value
}

fun localResult(initialize: Boolean): String {
    lateinit var local: String
    fun read(): String = local
    if (initialize) local = "local"
    return read()
}

// MODULE: main(lib)
// FILE: main.kt

import lateinit.lib.*

private fun failure(block: () -> Any?): String {
    return try {
        block()
        "no exception"
    } catch (exception: UninitializedPropertyAccessException) {
        exception.message ?: "null message"
    } catch (exception: Throwable) {
        "wrong exception: ${exception::class}"
    }
}

fun box(): String {
    if (isTopInitialized()) return "fail 1: top initially initialized"
    val topProperty = topReference()
    if (!topProperty.isLateinit) return "fail 2: top reflection flag"
    val topFailure = failure { topProperty.get() }
    if (topFailure != "lateinit property top has not been initialized") {
        return "fail 3: top failure $topFailure"
    }
    topProperty.set("first")
    if (!isTopInitialized() || topProperty.get() != "first") return "fail 4: top initialization"
    topProperty.setter("second")
    if (top != "second") return "fail 5: top property setter"

    val derived = Derived()
    if (derived.isMemberInitialized()) return "fail 6: member initially initialized"
    val memberProperty = derived.memberReference()
    if (!memberProperty.isLateinit) return "fail 7: member reflection flag"
    val memberFailure = failure { memberProperty.get() }
    if (memberFailure != "lateinit property member has not been initialized") {
        return "fail 8: member failure $memberFailure"
    }
    memberProperty.set("member")
    if (!derived.isMemberInitialized() || derived.member != "member") {
        return "fail 9: inherited member state"
    }

    val marker = Marker()
    val generic = GenericHolder<Any>()
    if (generic.isValueInitialized()) return "fail 10: generic initially initialized"
    val genericFailure = failure { generic.value }
    if (genericFailure != "lateinit property value has not been initialized") {
        return "fail 11: generic failure $genericFailure"
    }
    generic.valueReference().set(marker)
    if (!generic.isValueInitialized() || generic.value !== marker) {
        return "fail 12: generic identity"
    }

    val localFailure = failure { localResult(false) }
    if (localFailure != "lateinit property local has not been initialized") {
        return "fail 13: captured local failure $localFailure"
    }
    if (localResult(true) != "local") return "fail 14: captured local initialization"

    return "OK"
}
