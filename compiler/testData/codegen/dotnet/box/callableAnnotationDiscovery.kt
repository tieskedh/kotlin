package test

import kotlin.reflect.KAnnotatedElement

@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
@Repeatable
private annotation class CallableTag(val value: String)

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
private annotation class BinaryCallable

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
private annotation class SourceCallable

@CallableTag("top-first")
@CallableTag("top-second")
@BinaryCallable
@SourceCallable
private fun annotated(value: Int): Int = value + 1

private fun empty(): Int = 0

@CallableTag("top-property")
@get:CallableTag("top-getter")
@BinaryCallable
@SourceCallable
private val topProperty: Int = 41

private class Owner @CallableTag("constructor") constructor(private val seed: Int) {
    @CallableTag("member")
    fun member(value: Int): Int = seed + value

    @CallableTag("property")
    @get:CallableTag("getter")
    @set:CallableTag("setter")
    var state: Int = seed
}

private fun tags(element: KAnnotatedElement): List<String> =
    element.annotations.map { annotation ->
        (annotation as? CallableTag)?.value ?: "unexpected:$annotation"
    }

private fun fail(message: String): String = "FAIL: $message"

fun box(): String {
    val top = ::annotated
    if (tags(top) != listOf("top-first", "top-second")) return fail("top ${top.annotations}")
    if (top(41) != 42) return fail("top invocation")
    if (top.annotations !== top.annotations) return fail("top list stability")

    if (::empty.annotations.isNotEmpty()) return fail("empty")

    val owner = Owner(40)
    val bound = owner::member
    if (tags(bound) != listOf("member")) return fail("bound member ${bound.annotations}")
    if (bound(2) != 42) return fail("bound invocation")

    val unbound = Owner::member
    if (tags(unbound) != listOf("member")) return fail("unbound member ${unbound.annotations}")
    if (unbound(owner, 2) != 42) return fail("unbound invocation")

    val constructor = ::Owner
    if (tags(constructor) != listOf("constructor")) return fail("constructor ${constructor.annotations}")
    if (constructor(42).state != 42) return fail("constructor invocation")

    val topPropertyReference = ::topProperty
    if (tags(topPropertyReference) != listOf("top-property")) {
        return fail("top property ${topPropertyReference.annotations}")
    }
    if (topPropertyReference.get() != 41) return fail("top property get")

    val property = owner::state
    if (tags(property) != listOf("property")) return fail("property ${property.annotations}")
    property.set(42)
    if (property.get() != 42) return fail("property get/set")

    // Getter and setter applications belong to their accessors. A KProperty view must not infer
    // or merge them into the declaration-owned property annotation list.
    if (property.annotations.any { (it as? CallableTag)?.value in setOf("getter", "setter") }) {
        return fail("accessor leakage ${property.annotations}")
    }

    return "OK"
}
