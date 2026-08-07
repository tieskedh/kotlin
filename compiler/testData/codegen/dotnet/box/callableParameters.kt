// WITH_STDLIB

import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
import kotlin.reflect.KParameter
import kotlin.reflect.KTypeParameter

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ParameterMark(val value: String)

fun fail(message: String): String = "Fail: $message"

fun <T> choose(@ParameterMark("value") value: T, suffix: String = "!"): T = value

private fun failWith(error: Throwable): Nothing = throw error

private fun consume(value: Int) {
    if (value == Int.MIN_VALUE) throw Error("unreachable")
}

fun @receiver:ParameterMark("receiver") String.decorate(
    @ParameterMark("count") count: Int = 1,
    vararg tails: String,
): String = this + count + tails.size

open class Base {
    open fun inherited(number: Int = 7): Int = number
}

class Derived : Base() {
    override fun inherited(number: Int): Int = number
}

class Holder<T>(val value: T) {
    fun member(@ParameterMark("argument") argument: T): T = argument
}

class Outer {
    inner class Inner(val text: String = "inner")
}

private fun KParameter.summary(): String =
    "$index:$name:${kind.name}:$isOptional:$isVararg"

fun box(): String {
    val chooseReference: KFunction2<String, String, String> = ::choose
    val chooseParameters = chooseReference.parameters
    if (chooseParameters.map { it.summary() } != listOf(
            "0:value:VALUE:false:false",
            "1:suffix:VALUE:true:false",
        )
    ) return fail("top-level parameters ${chooseParameters.map { it.summary() }}")
    val own = chooseReference.typeParameters.single()
    if (chooseParameters[0].type.classifier !== own) return fail("parameter classifier identity")
    if ((chooseParameters[0].type.classifier as? KTypeParameter)?.name != "T") return fail("parameter type")
    val valueMark = chooseParameters[0].annotations.singleOrNull() as? ParameterMark
        ?: return fail("value parameter annotation ${chooseParameters[0].annotations}")
    if (valueMark.value != "value") return fail("value parameter annotation value")
    if (chooseParameters[1].annotations.isNotEmpty()) return fail("empty parameter annotations")
    if (chooseReference.parameters !== chooseParameters) return fail("parameter list stability")
    val secondChooseReference: KFunction2<String, String, String> = ::choose
    if (secondChooseReference.parameters != chooseParameters) return fail("equal callable parameter equality")
    if (secondChooseReference.parameters[0].hashCode() != chooseParameters[0].hashCode()) return fail("parameter hash")
    if (chooseParameters[0] == chooseParameters[1]) return fail("parameter index identity")
    if (chooseReference.call("OK", "ignored") != "OK") return fail("top-level positional call")

    val tooFew = try {
        chooseReference.call("value")
        null
    } catch (exception: IllegalArgumentException) {
        exception.message
    }
    if (tooFew != "Callable expects 2 arguments, but 1 were provided.") {
        return fail("wrong-count message $tooFew")
    }
    val wrongType = try {
        chooseReference.call(42, "ignored")
        false
    } catch (_: ClassCastException) {
        true
    }
    if (!wrongType) return fail("wrong argument type")

    val targetFailure = Error("target")
    val observedFailure = try {
        ::failWith.call(targetFailure)
        null
    } catch (exception: Throwable) {
        exception
    }
    if (observedFailure !== targetFailure) return fail("target exception identity")

    val unitResult = ::consume.call(42)
    if (unitResult !== Unit) return fail("Unit positional result")

    val unboundExtension = String::decorate
    if (unboundExtension.parameters.map { it.summary() } != listOf(
            "0:null:EXTENSION_RECEIVER:false:false",
            "1:count:VALUE:true:false",
            "2:tails:VALUE:false:true",
        )
    ) return fail("unbound extension ${unboundExtension.parameters.map { it.summary() }}")
    if (unboundExtension.parameters[0].type.classifier != String::class) return fail("extension type")
    if ((unboundExtension.parameters[0].annotations.singleOrNull() as? ParameterMark)?.value != "receiver") {
        return fail("receiver annotation ${unboundExtension.parameters[0].annotations}")
    }
    if ((unboundExtension.parameters[1].annotations.singleOrNull() as? ParameterMark)?.value != "count") {
        return fail("ordinary annotation ${unboundExtension.parameters[1].annotations}")
    }
    if (unboundExtension.parameters[2].type.classifier != Array::class) return fail("vararg array type")
    if (unboundExtension.parameters[0].toString() != "extension receiver parameter of function decorate") {
        return fail("extension rendering ${unboundExtension.parameters[0]}")
    }
    if (unboundExtension.call("x", 2, arrayOf("a", "b")) != "x22") {
        return fail("unbound extension positional call")
    }

    val boundExtension = "x"::decorate
    if (boundExtension.parameters.map { it.summary() } != listOf(
            "0:count:VALUE:true:false",
            "1:tails:VALUE:false:true",
        )
    ) return fail("bound extension ${boundExtension.parameters.map { it.summary() }}")
    if (boundExtension.call(3, arrayOf("tail")) != "x31") {
        return fail("bound extension positional call")
    }

    val unboundMember = Holder<String>::member
    if (unboundMember.parameters.map { it.summary() } != listOf(
            "0:null:INSTANCE:false:false",
            "1:argument:VALUE:false:false",
        )
    ) return fail("unbound member ${unboundMember.parameters.map { it.summary() }}")
    if (unboundMember.parameters[0].type.classifier != Holder::class) return fail("instance type")
    if (unboundMember.parameters[0].toString() != "instance parameter of function member") {
        return fail("instance rendering ${unboundMember.parameters[0]}")
    }
    val holder = Holder("stored")
    val boundMember = holder::member
    if (boundMember.parameters.map { it.summary() } != listOf("0:argument:VALUE:false:false")) {
        return fail("bound member ${boundMember.parameters.map { it.summary() }}")
    }
    if (boundMember("OK") != "OK") return fail("bound invocation")
    if (unboundMember.call(holder, "unbound") != "unbound") return fail("unbound member positional call")
    if (boundMember.call("bound") != "bound") return fail("bound member positional call")

    val holderConstructor: KFunction1<String, Holder<String>> = ::Holder
    if (holderConstructor.parameters.map { it.summary() } != listOf("0:value:VALUE:false:false")) {
        return fail("ordinary constructor ${holderConstructor.parameters.map { it.summary() }}")
    }
    if (holderConstructor.call("constructed").value != "constructed") return fail("constructor positional call")

    val inherited = Derived::inherited.parameters
    if (!inherited[1].isOptional) return fail("inherited default")
    if (Derived::inherited.call(Derived(), 9) != 9) return fail("virtual positional dispatch")

    val property = Holder<String>::value
    if (property.parameters.map { it.summary() } != listOf("0:null:INSTANCE:false:false")) {
        return fail("property parameters ${property.parameters.map { it.summary() }}")
    }
    if (holder::value.parameters.isNotEmpty()) return fail("bound property parameters")
    if (Holder<String>::value.parameters.single() != property.parameters.single()) {
        return fail("property parameter equality")
    }
    if (property.call(holder) != "stored") return fail("unbound property positional call")
    if (holder::value.call() != "stored") return fail("bound property positional call")

    val unboundInner = Outer::Inner
    if (unboundInner.parameters.map { it.summary() } != listOf(
            "0:null:INSTANCE:false:false",
            "1:text:VALUE:true:false",
        )
    ) return fail("inner constructor ${unboundInner.parameters.map { it.summary() }}")
    if (unboundInner.parameters[0].type.classifier != Outer::class) return fail("outer instance type")
    val boundInner = Outer()::Inner
    if (boundInner.parameters.map { it.summary() } != listOf("0:text:VALUE:true:false")) {
        return fail("bound inner constructor ${boundInner.parameters.map { it.summary() }}")
    }
    if (unboundInner.call(Outer(), "unbound").text != "unbound") return fail("unbound inner positional call")
    if (boundInner.call("bound").text != "bound") return fail("bound inner positional call")

    if (KParameter.Kind.VALUE.toString() != "VALUE") return fail("kind enum")
    if (chooseParameters[0].toString() != "parameter #0 value of function choose") {
        return fail("parameter rendering ${chooseParameters[0]}")
    }
    return "OK"
}
