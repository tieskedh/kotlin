// ALLOW_KOTLIN_PACKAGE
// DOTNET_MEMBER_REFLECTION

// MODULE: Kotlin.Reflection
// FILE: WrongReflectionFactory.kt

package kotlin.reflect.dotnet.internal

// Same provider type and method name, but intentionally the wrong versioned protocol shape.
public fun getMembersV1(value: String): String = value

// MODULE: main(Kotlin.Reflection)
// FILE: main.kt

class MalformedProductSubject

fun box(): String {
    val failure = try {
        MalformedProductSubject::class.members
        return "fail 1: malformed product was accepted"
    } catch (failure: Throwable) {
        failure
    }
    if (failure.message != "Kotlin class-member reflection implementation is not available") {
        return "fail 2: ${failure.message}"
    }
    if (!MalformedProductSubject::class.isInstance(MalformedProductSubject())) {
        return "fail 3: lightweight KClass changed"
    }
    return "OK"
}
