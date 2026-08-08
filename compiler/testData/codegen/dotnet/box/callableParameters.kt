// WITH_STDLIB

import kotlin.reflect.KFunction0
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3
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

private fun noArguments(): String = "zero"

private fun nullableDefault(value: String? = "fallback"): String? = value

private fun allDefaults(a: Int = 1, b: Int = a + 1, c: Int = b + 1): Int = a * 100 + b * 10 + c

private inline fun inlineZero(): String = "inline"

private open class OperatorBase {
    open operator fun plus(value: Int): Int = value
}

private class OperatorDerived : OperatorBase() {
    override fun plus(value: Int): Int = value + 1

    infix fun combine(value: String): String = value
}

private var defaultTrace: String = ""

private fun tracedDefaults(
    first: Int = run { defaultTrace += "A"; 2 },
    second: Int = run { defaultTrace += "B$first"; first + 3 },
): Int = first * 10 + second

private var defaultFailure: Throwable? = null

private fun failByDefault(error: Throwable = defaultFailure!!): Nothing = throw error

private fun referenceVararg(vararg values: String): Array<out String> = values

private fun primitiveVararg(vararg values: Int): Int = values.size

fun @receiver:ParameterMark("receiver") String.decorate(
    @ParameterMark("count") count: Int = 1,
    vararg tails: String,
): String = this + count + tails.size

open class Base {
    open fun inherited(number: Int = 7): Int = number
}

class Derived : Base() {
    override fun inherited(number: Int): Int = number + 100
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
    if (chooseReference.callBy(mapOf(secondChooseReference.parameters[0] to "equal-key")) != "equal-key") {
        return fail("equal callable parameter map key")
    }
    if (chooseReference.call("OK", "ignored") != "OK") return fail("top-level positional call")
    if (chooseReference.callBy(mapOf(chooseParameters[0] to "named")) != "named") {
        return fail("top-level default call")
    }
    if (chooseReference.callBy(mapOf(
            chooseParameters[0] to "named",
            chooseParameters[1] to "?",
        )) != "named"
    ) return fail("top-level supplied optional call")
    val missingRequired = try {
        chooseReference.callBy(emptyMap())
        null
    } catch (exception: IllegalArgumentException) {
        exception.message
    }
    if (missingRequired != "No argument provided for a required parameter: ${chooseParameters[0]}") {
        return fail("missing-required message $missingRequired")
    }
    val foreignKey = ::nullableDefault.parameters.single()
    if (chooseReference.callBy(mapOf(chooseParameters[0] to "extra", foreignKey to null)) != "extra") {
        return fail("unknown map key")
    }

    val nullableReference = ::nullableDefault
    val nullableParameter = nullableReference.parameters.single()
    if (nullableReference.callBy(emptyMap()) != "fallback") return fail("absent nullable default")
    if (nullableReference.callBy(mapOf(nullableParameter to null)) != null) return fail("explicit nullable value")

    val noArgumentsReference: KFunction0<String> = ::noArguments
    if (noArgumentsReference.callBy(emptyMap()) != "zero") return fail("KFunction0 callBy")

    val inlineReference: KFunction0<String> = ::inlineZero
    if (!inlineReference.isInline || inlineReference.isExternal || inlineReference.isOperator ||
        inlineReference.isInfix || inlineReference.isSuspend
    ) return fail("KFunction0 inline declaration flags")
    val inheritedOperator: KFunction2<OperatorDerived, Int, Int> = OperatorDerived::plus
    if (!inheritedOperator.isOperator || inheritedOperator.isInline || inheritedOperator.isExternal ||
        inheritedOperator.isInfix || inheritedOperator.isSuspend
    ) return fail("inherited operator declaration flags")
    val infixReference: KFunction2<OperatorDerived, String, String> = OperatorDerived::combine
    if (!infixReference.isInfix || infixReference.isInline || infixReference.isExternal ||
        infixReference.isOperator || infixReference.isSuspend
    ) return fail("infix declaration flags")

    val defaults: KFunction3<Int, Int, Int, Int> = ::allDefaults
    if (defaults.isInline || defaults.isExternal || defaults.isOperator || defaults.isInfix || defaults.isSuspend) {
        return fail("ordinary KFunction3 declaration flags")
    }
    val defaultsParameters = defaults.parameters
    val defaultCases = listOf(
        emptyMap<KParameter, Any?>() to 123,
        mapOf(defaultsParameters[0] to 4) to 456,
        mapOf(defaultsParameters[1] to 7) to 178,
        mapOf(defaultsParameters[2] to 9) to 129,
        mapOf(defaultsParameters[0] to 4, defaultsParameters[1] to 7) to 478,
        mapOf(defaultsParameters[0] to 4, defaultsParameters[2] to 9) to 459,
        mapOf(defaultsParameters[1] to 7, defaultsParameters[2] to 9) to 179,
        mapOf(defaultsParameters[0] to 4, defaultsParameters[1] to 7, defaultsParameters[2] to 9) to 479,
    )
    for ((arguments, expected) in defaultCases) {
        val actual = defaults.callBy(arguments)
        if (actual != expected) return fail("default mask $arguments: $actual instead of $expected")
    }

    defaultTrace = ""
    if (::tracedDefaults.callBy(emptyMap()) != 25 || defaultTrace != "AB2") {
        return fail("dependent default order $defaultTrace")
    }
    defaultTrace = ""
    val tracedParameters = ::tracedDefaults.parameters
    if (::tracedDefaults.callBy(mapOf(tracedParameters[0] to 5)) != 58 || defaultTrace != "B5") {
        return fail("partly supplied dependent default $defaultTrace")
    }

    val reflectiveFailure = Error("reflective default target")
    defaultFailure = reflectiveFailure
    val observedDefaultFailure = try {
        ::failByDefault.callBy(emptyMap())
        null
    } catch (exception: Throwable) {
        exception
    }
    if (observedDefaultFailure !== reflectiveFailure) return fail("default target exception identity")

    val referenceVararg = ::referenceVararg
    val emptyReferenceVararg1 = referenceVararg.callBy(emptyMap())
    val emptyReferenceVararg2 = referenceVararg.callBy(emptyMap())
    if (emptyReferenceVararg1.isNotEmpty() || emptyReferenceVararg2.isNotEmpty() ||
        emptyReferenceVararg1 === emptyReferenceVararg2
    ) return fail("fresh reference vararg")
    if (referenceVararg.callBy(mapOf(referenceVararg.parameters[0] to arrayOf("a", "b"))).size != 2) {
        return fail("supplied reference vararg")
    }
    val primitiveVararg = ::primitiveVararg
    if (primitiveVararg.callBy(emptyMap()) != 0) return fail("empty primitive vararg")
    if (primitiveVararg.callBy(mapOf(primitiveVararg.parameters[0] to intArrayOf(1, 2))) != 2) {
        return fail("supplied primitive vararg")
    }

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
    if (unboundExtension.callBy(mapOf(unboundExtension.parameters[0] to "x")) != "x10") {
        return fail("unbound extension defaults")
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
    if (boundExtension.callBy(emptyMap()) != "x10") return fail("bound extension defaults")

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
    if (unboundMember.callBy(mapOf(
            unboundMember.parameters[0] to holder,
            unboundMember.parameters[1] to "named",
        )) != "named"
    ) return fail("unbound member named call")
    if (boundMember.callBy(mapOf(boundMember.parameters[0] to "named-bound")) != "named-bound") {
        return fail("bound member named call")
    }

    val holderConstructor: KFunction1<String, Holder<String>> = ::Holder
    if (holderConstructor.parameters.map { it.summary() } != listOf("0:value:VALUE:false:false")) {
        return fail("ordinary constructor ${holderConstructor.parameters.map { it.summary() }}")
    }
    if (holderConstructor.call("constructed").value != "constructed") return fail("constructor positional call")
    if (holderConstructor.callBy(mapOf(holderConstructor.parameters[0] to "named-constructor")).value !=
        "named-constructor"
    ) return fail("constructor named call")

    val inherited = Derived::inherited.parameters
    if (!inherited[1].isOptional) return fail("inherited default")
    if (Derived::inherited.call(Derived(), 9) != 109) return fail("virtual positional dispatch")
    if (Derived::inherited.callBy(mapOf(Derived::inherited.parameters[0] to Derived())) != 107) {
        return fail("inherited default virtual dispatch")
    }

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
    if (property.callBy(mapOf(property.parameters[0] to holder)) != "stored") {
        return fail("unbound property named call")
    }
    if (holder::value.callBy(emptyMap()) != "stored") return fail("bound property named call")

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
    if (unboundInner.callBy(mapOf(unboundInner.parameters[0] to Outer())).text != "inner") {
        return fail("unbound inner default call")
    }
    if (boundInner.callBy(emptyMap()).text != "inner") return fail("bound inner default call")

    if (KParameter.Kind.VALUE.toString() != "VALUE") return fail("kind enum")
    if (chooseParameters[0].toString() != "parameter #0 value of function choose") {
        return fail("parameter rendering ${chooseParameters[0]}")
    }
    return "OK"
}
