package test

import kotlin.reflect.KProperty
import kotlin.reflect.KFunction1
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KVariance

private class Four<A, B, C, D>

private class Outer<A, B> {
    inner class Inner<C, D> {
        inner class Innermost<E, F>
    }
}

private class Constructed(val value: Int)

private class Owner(private val seed: Int) {
    fun nullable(value: String): String? = if (value.length == 0) null else "$value$seed"

    var state: Int = seed
}

private fun projected(): Four<String, in Number, out CharSequence, *> = error("unused")

private fun innerResult(): Outer<Int, Number>.Inner<String, Float>.Innermost<Any, Any?> = error("unused")

private fun listResult(): List<String> = error("unused")

private fun unitResult(): Unit = Unit

private fun <T : Comparable<T>> identity(value: T): T = value

private val topProperty: String? = null

private class ReturnTypeDelegate {
    var observed: KType? = null

    operator fun getValue(receiver: Any?, property: KProperty<*>): String {
        observed = property.returnType
        return "local"
    }
}

private fun localDelegatedReturnType(): KType {
    val delegate = ReturnTypeDelegate()
    val local: String by delegate
    if (local != "local") error("delegate value")
    return delegate.observed ?: error("missing delegated property return type")
}

private fun fail(message: String): String = "FAIL: $message"

fun box(): String {
    val projectedReference = ::projected
    val projectionType = projectedReference.returnType
    if (projectionType.classifier != Four::class) return fail("projection classifier ${projectionType.classifier}")
    if (projectionType.arguments.map { it.variance } !=
        listOf(KVariance.INVARIANT, KVariance.IN, KVariance.OUT, null)
    ) {
        return fail("projection variance ${projectionType.arguments}")
    }
    if (projectionType.arguments[0].type?.classifier != String::class) return fail("projection invariant")
    if (projectionType.arguments[1].type?.classifier != Number::class) return fail("projection in")
    if (projectionType.arguments[2].type?.classifier != CharSequence::class) return fail("projection out")
    if (projectionType.arguments[3].type != null) return fail("projection star")
    if (projectedReference.returnType !== projectionType) return fail("reference returnType cache")

    val innerArguments = ::innerResult.returnType.arguments.map { projection ->
        projection.type ?: return fail("inner star")
    }
    if (innerArguments.map { type -> type.classifier } !=
        listOf(Any::class, Any::class, String::class, Float::class, Int::class, Number::class)
    ) {
        return fail("inner argument order ${innerArguments.map { type -> type.classifier }}")
    }
    if (innerArguments[0].isMarkedNullable || !innerArguments[1].isMarkedNullable) {
        return fail("inner nullability $innerArguments")
    }
    if (::listResult.returnType.arguments.single().variance != KVariance.INVARIANT) {
        return fail("declaration-site variance leaked into projection")
    }

    val constructor = ::Constructed
    if (constructor.returnType.classifier != Constructed::class) return fail("constructor")
    if (constructor(42).value != 42) return fail("constructor invocation")

    val owner = Owner(2)
    val bound = owner::nullable
    if (bound.returnType.classifier != String::class || !bound.returnType.isMarkedNullable) {
        return fail("bound nullable ${bound.returnType}")
    }
    if (bound("4") != "42") return fail("bound invocation")
    if (bound.returnType !== bound.returnType) return fail("bound returnType stability")

    val unbound = Owner::nullable
    if (unbound.returnType != bound.returnType) return fail("bound/unbound declaration type")
    if (unbound(owner, "4") != "42") return fail("unbound invocation")

    val property = owner::state
    if (property.returnType.classifier != Int::class || property.returnType.isMarkedNullable) {
        return fail("mutable property ${property.returnType}")
    }
    property.set(42)
    if (property.get() != 42) return fail("mutable property invocation")
    if (::topProperty.returnType.classifier != String::class || !::topProperty.returnType.isMarkedNullable) {
        return fail("top property ${::topProperty.returnType}")
    }

    if (::unitResult.returnType.classifier != Unit::class) return fail("Unit")

    val genericReference: KFunction1<String, String> = ::identity
    val generic = genericReference.returnType
    val parameter = generic.classifier as? KTypeParameter ?: return fail("generic classifier $generic")
    if (parameter.name != "T" || parameter.upperBounds.single().classifier != Comparable::class) {
        return fail("generic parameter $parameter ${parameter.upperBounds}")
    }

    val localType = localDelegatedReturnType()
    if (localType.classifier != String::class || localType.isMarkedNullable) {
        return fail("local delegated $localType")
    }

    return "OK"
}
