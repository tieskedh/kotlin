private class PlainException(message: String) : Exception(message)

private open class DomainRuntimeException(message: String) : RuntimeException(message)

private class ChildRuntimeException(message: String) : DomainRuntimeException(message)

private class MappedRuntimeException(message: String) : IllegalStateException(message)

private class DomainError(message: String) : Error(message)

private fun caughtAsRuntime(value: Throwable): Boolean = try {
    throw value
} catch (failure: RuntimeException) {
    failure === value
} catch (failure: Throwable) {
    false
}

private fun caughtAsDomainRuntime(value: Throwable): Boolean = try {
    throw value
} catch (failure: DomainRuntimeException) {
    failure === value
} catch (failure: Throwable) {
    false
}

private fun caughtAsException(value: Throwable): Boolean = try {
    throw value
} catch (failure: Exception) {
    failure === value
} catch (failure: Throwable) {
    false
}

private fun caughtDirectConstruction(): Boolean = try {
    throw DomainRuntimeException("direct")
} catch (failure: DomainRuntimeException) {
    true
}

fun box(): String {
    val plain: Throwable = PlainException("plain")
    if (plain !is Exception || plain is RuntimeException || plain is Error) return "plain classification"
    if (!caughtAsException(plain) || caughtAsRuntime(plain)) return "plain catch"

    val child: Throwable = ChildRuntimeException("child")
    if (child !is DomainRuntimeException || child !is RuntimeException || child !is Exception) {
        return "runtime classification"
    }
    if (!caughtAsDomainRuntime(child) || !caughtAsRuntime(child) || !caughtAsException(child)) {
        return "runtime catch"
    }
    if (!caughtDirectConstruction()) return "direct construction throw"

    val mapped: Throwable = MappedRuntimeException("mapped")
    if (mapped !is RuntimeException || !caughtAsRuntime(mapped) || !caughtAsException(mapped)) {
        return "mapped subclass"
    }

    val error: Throwable = DomainError("error")
    if (error !is Error || error is Exception) return "error classification"
    if (caughtAsException(error) || caughtAsRuntime(error)) return "error catch"
    return "OK"
}
