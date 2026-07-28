// Kotlin static-initialization failure semantics are independent of the physical CLR owner:
// classic companions, inherited class events, ordinary objects, and file facades all preserve
// the first failure and reject every later active use. CLR TypeInitializationException must
// never replace these Kotlin-visible identities.

// FILE: declarations.kt
package initialization

class CompanionFailure {
    companion object {
        val never: Int = throw IllegalStateException("companion")
    }
}

open class ParentFailure {
    companion object {
        val never: Int = throw IllegalStateException("parent")
    }
}

class ChildFailure : ParentFailure() {
    companion object {
        val normal: Int = 42
    }
}

open class GenericParentFailure {
    companion object {
        val never: Int = throw IllegalStateException("generic parent")
    }
}

class GenericChildFailure<T> : GenericParentFailure()

object ObjectFailure {
    val never: Int = throw IllegalStateException("object")
}

class ExactInitializationError(message: String) : Error(message)

val exactInitializationError = ExactInitializationError("exact")

object ExactErrorFailure {
    val never: Int = throw exactInitializationError
}

// FILE: failingFile.kt
package initialization

val failingFileValue: Int = throw IllegalStateException("file")
val unreachableFileValue: Int = 42

// FILE: box.kt
package initialization

private fun failureMessage(failure: Throwable): String? = failure.message

private fun failureCause(failure: Throwable): Throwable? = failure.cause

fun box(): String {
    @Suppress("INVISIBLE_REFERENCE")
    try {
        CompanionFailure()
        return "FAIL companion first: no failure"
    } catch (failure: ExceptionInInitializerError) {
        val cause = failureCause(failure)
        if (cause !is IllegalStateException) return "FAIL companion cause type"
        if (cause.message != "companion") return "FAIL companion cause message: ${cause.message}"
        if (failureMessage(failure) != null) {
            return "FAIL companion wrapper message: ${failureMessage(failure)}"
        }
    }

    @Suppress("INVISIBLE_REFERENCE")
    try {
        CompanionFailure()
        return "FAIL companion later: no failure"
    } catch (failure: NoClassDefFoundError) {
        if (failureMessage(failure) != "Could not initialize class initialization.CompanionFailure") {
            return "FAIL companion later message: ${failureMessage(failure)}"
        }
    }

    @Suppress("INVISIBLE_REFERENCE")
    try {
        ChildFailure()
        return "FAIL inherited first: no failure"
    } catch (failure: ExceptionInInitializerError) {
        val cause = failureCause(failure)
        if (cause !is IllegalStateException) return "FAIL inherited cause type"
        if (cause.message != "parent") return "FAIL inherited cause message: ${cause.message}"
    }

    @Suppress("INVISIBLE_REFERENCE")
    try {
        ChildFailure()
        return "FAIL child later: no failure"
    } catch (failure: NoClassDefFoundError) {
        if (failureMessage(failure) != "Could not initialize class initialization.ChildFailure") {
            return "FAIL child later message: ${failureMessage(failure)}"
        }
    }

    @Suppress("INVISIBLE_REFERENCE")
    try {
        ParentFailure()
        return "FAIL parent later: no failure"
    } catch (failure: NoClassDefFoundError) {
        if (failureMessage(failure) != "Could not initialize class initialization.ParentFailure") {
            return "FAIL parent later message: ${failureMessage(failure)}"
        }
    }

    @Suppress("INVISIBLE_REFERENCE")
    try {
        GenericChildFailure<String>()
        return "FAIL generic inherited first: no failure"
    } catch (failure: ExceptionInInitializerError) {
        val cause = failureCause(failure)
        if (cause !is IllegalStateException) return "FAIL generic inherited cause type"
        if (cause.message != "generic parent") {
            return "FAIL generic inherited cause message: ${cause.message}"
        }
    }

    @Suppress("INVISIBLE_REFERENCE")
    try {
        GenericChildFailure<Int>()
        return "FAIL generic inherited later: no failure"
    } catch (failure: NoClassDefFoundError) {
        if (failureMessage(failure) !=
            "Could not initialize class initialization.GenericChildFailure"
        ) {
            return "FAIL generic inherited later message: ${failureMessage(failure)}"
        }
    }

    @Suppress("INVISIBLE_REFERENCE")
    try {
        ObjectFailure.never
        return "FAIL object first: no failure"
    } catch (failure: ExceptionInInitializerError) {
        val cause = failureCause(failure)
        if (cause !is IllegalStateException) return "FAIL object cause type"
        if (cause.message != "object") return "FAIL object cause message: ${cause.message}"
    }

    @Suppress("INVISIBLE_REFERENCE")
    try {
        ObjectFailure.never
        return "FAIL object later: no failure"
    } catch (failure: NoClassDefFoundError) {
        if (failureMessage(failure) != "Could not initialize class initialization.ObjectFailure") {
            return "FAIL object later message: ${failureMessage(failure)}"
        }
    }

    try {
        ExactErrorFailure.never
        return "FAIL exact Error first: no failure"
    } catch (failure: ExactInitializationError) {
        if (failure !== exactInitializationError) return "FAIL exact Error identity"
        if (failureMessage(failure) != "exact") {
            return "FAIL exact Error message: ${failureMessage(failure)}"
        }
    }

    @Suppress("INVISIBLE_REFERENCE")
    try {
        ExactErrorFailure.never
        return "FAIL exact Error later: no failure"
    } catch (failure: NoClassDefFoundError) {
        if (failureMessage(failure) != "Could not initialize class initialization.ExactErrorFailure") {
            return "FAIL exact Error later message: ${failureMessage(failure)}"
        }
    }

    @Suppress("INVISIBLE_REFERENCE")
    try {
        failingFileValue
        return "FAIL file first: no failure"
    } catch (failure: ExceptionInInitializerError) {
        val cause = failureCause(failure)
        if (cause !is IllegalStateException) return "FAIL file cause type"
        if (cause.message != "file") return "FAIL file cause message: ${cause.message}"
    }

    @Suppress("INVISIBLE_REFERENCE")
    try {
        unreachableFileValue
        return "FAIL file later: no failure"
    } catch (failure: NoClassDefFoundError) {
        if (failureMessage(failure) != "Could not initialize file") {
            return "FAIL file later message: ${failureMessage(failure)}"
        }
    }

    return "OK"
}
