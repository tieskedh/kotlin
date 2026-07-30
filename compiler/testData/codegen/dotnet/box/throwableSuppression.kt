// TARGET_BACKEND: DOTNET

private class DerivedConcurrentModificationException :
    ConcurrentModificationException("derived concurrent")

private class DerivedAssertionError :
    AssertionError("derived assertion")

private fun exactExceptionIdentities(): String? {
    val concurrent = ConcurrentModificationException("concurrent")
    val concurrentCaught = try {
        throw concurrent
    } catch (failure: ConcurrentModificationException) {
        failure
    }
    if (concurrentCaught !== concurrent) return "ConcurrentModificationException identity"
    val concurrentAsThrowable: Throwable = concurrent
    if (concurrentAsThrowable !is RuntimeException || concurrentAsThrowable !is Exception) {
        return "concurrent hierarchy"
    }
    if (concurrentAsThrowable is IllegalStateException) return "concurrent collapsed into IllegalStateException"
    val derivedConcurrentCaught: Throwable = try {
        throw DerivedConcurrentModificationException()
    } catch (failure: ConcurrentModificationException) {
        failure
    }
    if (derivedConcurrentCaught !is DerivedConcurrentModificationException) {
        return "ConcurrentModificationException was not physically open"
    }

    val assertionCause = RuntimeException("assertion cause")
    val assertion = AssertionError(assertionCause)
    if (assertion.cause !== assertionCause) return "AssertionError cause"
    if (assertion.message != assertionCause.toString()) return "AssertionError message"
    val assertionAsThrowable: Throwable = assertion
    if (assertionAsThrowable !is Error || assertionAsThrowable is Exception) {
        return "AssertionError hierarchy"
    }
    val derivedAssertionCaught: Throwable = try {
        throw DerivedAssertionError()
    } catch (failure: AssertionError) {
        failure
    }
    if (derivedAssertionCaught !is DerivedAssertionError) {
        return "AssertionError was not physically open"
    }

    @Suppress("DEPRECATION_ERROR")
    val uninitialized = UninitializedPropertyAccessException("property")
    @Suppress("DEPRECATION_ERROR")
    val uninitializedCaught = try {
        throw uninitialized
    } catch (failure: UninitializedPropertyAccessException) {
        failure
    }
    if (uninitializedCaught !== uninitialized) return "UninitializedPropertyAccessException identity"
    return null
}

fun box(): String {
    val exactFailure = exactExceptionIdentities()
    if (exactFailure != null) return "FAIL exact: $exactFailure"

    val root = RuntimeException("root")
    val first = IllegalStateException("first")
    val second = ConcurrentModificationException("second")

    root.addSuppressed(root)
    if (!root.suppressedExceptions.isEmpty()) return "FAIL self suppression"

    root.addSuppressed(first)
    root.addSuppressed(second)
    val snapshot = root.suppressedExceptions
    if (snapshot.size != 2 || snapshot[0] !== first || snapshot[1] !== second) {
        return "FAIL order"
    }
    if (!snapshot.contains(first) || snapshot.indexOf(second) != 1) return "FAIL list operations"
    if (snapshot.subList(1, 2)[0] !== second) return "FAIL subList"
    try {
        snapshot.subList(1, 0)
        return "FAIL reversed subList did not throw"
    } catch (_: IllegalArgumentException) {
    }
    try {
        snapshot.subList(-1, 0)
        return "FAIL out-of-bounds subList did not throw"
    } catch (_: IndexOutOfBoundsException) {
    }

    root.addSuppressed(first)
    if (snapshot.size != 2) return "FAIL snapshot changed"
    val updated = root.suppressedExceptions
    if (updated.size != 3 || updated[2] !== first) return "FAIL duplicate/order"

    // The suppression graph can be cyclic even though self-suppression is ignored. Rendering must
    // preserve the CLR descriptions, retain insertion order, and terminate on reference cycles.
    first.addSuppressed(root)
    val trace = root.stackTraceToString()
    if (trace == root.toString()) return "FAIL suppressed trace was not composed"
    return "OK"
}
