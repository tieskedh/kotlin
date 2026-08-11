// ALLOW_KOTLIN_PACKAGE
// DOTNET_MEMBER_REFLECTION

// MODULE: Kotlin.Reflection
// FILE: ReflectionFactoryImpl.kt

package kotlin.reflect.dotnet.internal

import kotlin.reflect.KCallable
import kotlin.reflect.KClass

public fun getMembersV1(kClass: KClass<*>): Collection<KCallable<*>>? =
    dotNetGetGeneratedMembersV1(kClass)

private external fun dotNetGetGeneratedMembersV1(kClass: KClass<*>): Collection<KCallable<*>>?

// MODULE: lib
// FILE: declarations.kt

package member.reflection.lib

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class Marker(val value: String)

open class Base<T>(
    @Marker("base-value")
    open var value: T,
) {
    @Marker("base-only")
    fun baseOnly(prefix: String): String = prefix + value

    fun overloaded(value: String): String = "base:$value"

    protected fun protectedBase(): String = "protected"

    private fun privateBase(): String = "private-base"
}

class Derived(value: String) : Base<String>(value) {
    @Marker("derived-value")
    override var value: String = value

    @Marker("child")
    fun child(suffix: String): String = value + suffix

    fun overloaded(value: Int): String = "child:$value"

    fun <R : CharSequence> generic(value: R): R = value

    fun String.memberExtension(suffix: String): String = value + this + suffix

    val String.memberExtensionProperty: String
        get() = value + this

    fun explode(failure: Throwable): Nothing = throw failure

    private fun hidden(): String = "hidden"
}

class GenericBox<T>(
    @Marker("item")
    var item: T,
) {
    fun echo(value: T): T = value

    fun <R : CharSequence> bounded(value: R): R = value
}

class Empty

interface Contract<T> {
    val token: T

    fun transform(value: T): T

    fun decorated(suffix: String): String = token.toString() + suffix
}

class ContractImpl(override val token: String) : Contract<String> {
    override fun transform(value: String): String = token + value
}

class Outer {
    class Nested {
        fun nested(value: String): String = "nested:$value"
    }
}

object Registry {
    var value: String = "registry"

    fun append(suffix: String): String = value + suffix
}

enum class Choice {
    FIRST;

    fun label(suffix: String): String = "first:$suffix"
}

fun dynamicClass(): Any = Derived("dynamic")

// MODULE: main(lib, Kotlin.Reflection)
// FILE: main.kt

import member.reflection.lib.*
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty1

private fun Collection<KCallable<*>>.named(name: String): List<KCallable<*>> = filter { it.name == name }

private fun marker(callable: KCallable<*>): String? =
    (callable.annotations.singleOrNull() as? Marker)?.value

private fun hasStableUnsupportedMembers(kClass: KClass<*>): Boolean {
    val failure = try {
        kClass.members
        return false
    } catch (failure: Throwable) {
        failure
    }
    return failure.message == "Kotlin class-member reflection implementation is not available"
}

@Suppress("UNCHECKED_CAST")
fun box(): String {
    val derivedClass = Derived::class
    val members = derivedClass.members
    if (members !== derivedClass.members) return "fail 1: class cache"

    val child = members.named("child").single() as KFunction<String>
    if (marker(child) != "child") return "fail 2: child annotation"
    if (child.call(Derived("a"), "b") != "ab") return "fail 3: child call"
    if (child.callBy(mapOf(
            child.parameters[0] to Derived("call:"),
            child.parameters[1] to "by",
        )) != "call:by"
    ) {
        return "fail 3b: child callBy"
    }
    if (child != Derived::child) return "fail 4: direct function identity"

    val value = members.named("value").single() as KMutableProperty1<Derived, String>
    if (marker(value) != "derived-value") return "fail 5: override property annotation"
    val instance = Derived("before")
    value.setter.call(instance, "after")
    if (value.getter.call(instance) != "after" || instance.value != "after") return "fail 6: mutation"
    if (value != Derived::value || value.getter.property !== value || value.setter.property !== value) {
        return "fail 7: property/accessor identity"
    }

    val baseOnly = members.named("baseOnly").single() as KFunction<String>
    if (marker(baseOnly) != "base-only") return "fail 8: inherited annotation"
    if (baseOnly.call(instance, "x:") != "x:after") return "fail 9: inherited call"
    if (members.named("privateBase").isNotEmpty()) return "fail 10: inherited private"
    if (members.named("protectedBase").single().visibility.toString() != "PROTECTED") {
        return "fail 11: inherited protected"
    }
    if (members.named("hidden").single().visibility.toString() != "PRIVATE") {
        return "fail 12: declared private"
    }

    val overloads = members.named("overloaded")
    if (overloads.size != 2) return "fail 13: overloads ${overloads.size}"
    val baseOverload = overloads.single { it.parameters.last().type.toString() == "kotlin.String" }
    val childOverload = overloads.single { it.parameters.last().type.toString() == "kotlin.Int" }
    if (baseOverload.call(instance, "x") != "base:x") return "fail 14: base overload"
    if (childOverload.call(instance, 7) != "child:7") return "fail 15: child overload"

    val generic = members.named("generic").single()
    val genericParameter = generic.typeParameters.single()
    if (generic.returnType.classifier !== genericParameter) return "fail 16: generic return identity"
    if (generic.parameters.last().type.classifier !== genericParameter) return "fail 17: generic parameter identity"
    if (generic.call(instance, "generic") != "generic") return "fail 18: generic call"

    val extension = members.named("memberExtension").single()
    if (extension.parameters.size != 3) return "fail 19: member extension parameters"
    if (extension.call(instance, "middle", "!") != "aftermiddle!") return "fail 20: member extension call"
    val extensionProperty = members.named("memberExtensionProperty").single()
    if (extensionProperty.parameters.size != 2 || extensionProperty.call(instance, "middle") != "aftermiddle") {
        return "fail 20b: member extension property"
    }

    val failure = Throwable("identity")
    try {
        members.named("explode").single().call(instance, failure)
        return "fail 21: missing exception"
    } catch (actual: Throwable) {
        if (actual !== failure) return "fail 22: exception identity"
    }

    val genericMembers = GenericBox::class.members
    val echo = genericMembers.named("echo").single()
    if (echo.returnType.classifier !== echo.parameters.last().type.classifier) {
        return "fail 23: owner parameter graph"
    }
    val box = GenericBox(1)
    if (echo.call(box, 42) != 42) return "fail 24: erased generic owner invocation"
    val item = genericMembers.named("item").single() as KMutableProperty1<GenericBox<Any?>, Any?>
    val erasedBox = box as GenericBox<Any?>
    item.set(erasedBox, "changed")
    if (item.get(erasedBox) != "changed") return "fail 25: erased generic owner mutation"

    val bounded = genericMembers.named("bounded").single()
    val boundedParameter = bounded.typeParameters.single()
    if (boundedParameter.upperBounds.single().toString() != "kotlin.CharSequence") {
        return "fail 26: generic bound"
    }
    if (bounded.call(GenericBox("x"), "bounded") != "bounded") return "fail 27: bounded call"

    val dynamicMembers = dynamicClass()::class.members
    if (dynamicMembers.named("child").single().call(Derived("d"), "!") != "d!") {
        return "fail 28: dynamic KClass"
    }

    val empty = Empty::class.members
    if (empty.none { it.name == "equals" } || empty.none { it.name == "hashCode" } ||
        empty.none { it.name == "toString" }
    ) {
        return "fail 29: inherited Any members"
    }
    if (empty.any { it.name.length > 0 && it.name[0] == '<' }) return "fail 30: physical helper leaked"
    if (!hasStableUnsupportedMembers(String::class)) return "fail 31: mapped class did not fail closed"
    if (!hasStableUnsupportedMembers(ArrayList::class)) return "fail 32: stdlib class did not fail closed"
    class Local
    if (!hasStableUnsupportedMembers(Local::class)) return "fail 33: local class did not fail closed"
    if (!hasStableUnsupportedMembers(object {}::class)) return "fail 34: anonymous class did not fail closed"

    val contractMembers = Contract::class.members
    val contract = ContractImpl("contract:")
    if (contractMembers.named("transform").single().call(contract, "value") != "contract:value") {
        return "fail 35: interface abstract dispatch"
    }
    if (contractMembers.named("decorated").single().call(contract, "!") != "contract:!") {
        return "fail 36: interface default dispatch"
    }
    if (contractMembers.named("token").single().call(contract) != "contract:") {
        return "fail 37: interface property dispatch"
    }

    val nested = Outer.Nested::class.members.named("nested").single()
    if (nested.call(Outer.Nested(), "value") != "nested:value") return "fail 38: nested class"

    val registryMembers = Registry::class.members
    val registryValue = registryMembers.named("value").single() as KMutableProperty1<Registry, String>
    registryValue.set(Registry, "changed")
    if (registryMembers.named("append").single().call(Registry, "!") != "changed!") {
        return "fail 39: object dispatch"
    }

    val enumLabel = Choice::class.members.named("label").single()
    if (enumLabel.call(Choice.FIRST, "value") != "first:value") return "fail 40: enum dispatch"
    return "OK"
}
