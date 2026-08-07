package test

import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1
import kotlin.reflect.KTypeParameter

private fun <A, B : Comparable<B>, C> choose(first: A, second: B, third: C): B = second

private class Host<A> {
    fun <B : A> echo(value: B): B = value

    val plain: A? = null
}

private class Crate<T : Comparable<T>>(val value: T)

private val <T : CharSequence> T.selfProperty: T
    get() = this

private fun fail(message: String): String = "FAIL: $message"

fun box(): String {
    val chooseReference: KFunction3<Int, String, Any, String> = ::choose
    val chooseParameters = chooseReference.typeParameters
    if (chooseParameters.map { it.name } != listOf("A", "B", "C")) {
        return fail("declaration order ${chooseParameters.map { it.name }}")
    }
    if (chooseReference.typeParameters !== chooseParameters) return fail("type-parameter list cache")

    val returnParameter = chooseReference.returnType.classifier as? KTypeParameter
        ?: return fail("generic return classifier ${chooseReference.returnType.classifier}")
    if (returnParameter !== chooseParameters[1]) return fail("return/type-parameter graph identity")

    val comparableBound = chooseParameters[1].upperBounds.single()
    if (comparableBound.classifier != Comparable::class) return fail("recursive bound classifier")
    val recursiveArgument = comparableBound.arguments.single().type?.classifier
    if (recursiveArgument !== chooseParameters[1]) return fail("recursive bound graph identity")

    val secondChooseReference: KFunction3<Int, String, Any, String> = ::choose
    if (secondChooseReference.typeParameters != chooseParameters) return fail("cross-site equality")
    if (secondChooseReference.typeParameters.map { it.hashCode() } != chooseParameters.map { it.hashCode() }) {
        return fail("cross-site hash")
    }

    val unbound: KFunction2<Host<CharSequence>, String, String> = Host<CharSequence>::echo
    val own = unbound.typeParameters.single()
    if (own.name != "B" || unbound.returnType.classifier !== own) {
        return fail("member own parameter")
    }
    val enclosing = own.upperBounds.single().classifier as? KTypeParameter
        ?: return fail("enclosing bound classifier ${own.upperBounds.single().classifier}")
    if (enclosing.name != "A" || enclosing == own || enclosing in unbound.typeParameters) {
        return fail("enclosing parameter exclusion")
    }

    val bound: KFunction1<String, String> = Host<CharSequence>()::echo
    if (bound.typeParameters != unbound.typeParameters) return fail("bound/unbound ownership")
    if (bound.returnType.classifier !== bound.typeParameters.single()) {
        return fail("bound return/type-parameter graph identity")
    }
    if (bound("OK") != "OK" || unbound(Host(), "OK") != "OK") return fail("member invocation")

    val constructor: KFunction1<String, Crate<String>> = ::Crate
    val constructorParameter = constructor.typeParameters.single()
    if (constructorParameter.name != "T") return fail("constructor class parameter")
    if (constructor.returnType.arguments.single().type?.classifier !== constructorParameter) {
        return fail("constructor return graph identity")
    }
    val constructorBoundArgument = constructorParameter.upperBounds.single()
        .arguments.single().type?.classifier
    if (constructorBoundArgument !== constructorParameter || constructor("OK").value != "OK") {
        return fail("constructor bound graph identity or invocation")
    }

    val ordinaryProperty: KProperty1<Host<String>, String?> = Host<String>::plain
    if (ordinaryProperty.typeParameters.isNotEmpty()) return fail("enclosing property parameter leaked")

    val extensionProperty: KProperty1<String, String> = String::selfProperty
    val propertyParameter = extensionProperty.typeParameters.single()
    if (propertyParameter.name != "T" || extensionProperty.returnType.classifier !== propertyParameter) {
        return fail("extension property graph")
    }
    val boundProperty: KProperty0<String> = "OK"::selfProperty
    if (boundProperty.typeParameters != extensionProperty.typeParameters) {
        return fail("bound extension property ownership")
    }
    if (boundProperty.returnType.classifier !== boundProperty.typeParameters.single()) {
        return fail("bound property graph identity")
    }
    if (extensionProperty.get("OK") != "OK" || boundProperty.get() != "OK") {
        return fail("property invocation")
    }

    return "OK"
}
