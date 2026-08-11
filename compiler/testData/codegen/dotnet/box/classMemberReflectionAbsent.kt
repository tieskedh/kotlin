// DOTNET_MEMBER_REFLECTION

class LightweightOnly

fun box(): String {
    if (LightweightOnly::class.simpleName != "LightweightOnly") return "fail 1: lightweight KClass"
    val failure = try {
        LightweightOnly::class.members
        return "fail 2: missing reflection product was accepted"
    } catch (failure: Throwable) {
        failure
    }
    if (failure.message != "Kotlin class-member reflection implementation is not available") {
        return "fail 3: ${failure.message}"
    }
    if (!LightweightOnly::class.isInstance(LightweightOnly())) return "fail 4: KClass changed"
    return "OK"
}
