// Rethrow preserves object identity: 'throw e' inside a catch is a plain ldloc/throw (the IL
// 'rethrow' instruction is never emitted), so the outer handler receives the identical object —
// asserted with reference '===' against the original bound in a local before throwing, plus the
// message roundtrip. The inner catch of the nested try binds the mapped
// IllegalStateException; the outer 'catch (e: Throwable)' is the CLR catch-everything handler.

fun box(): String {
    val original = IllegalStateException("orig")
    var caught: Throwable = IllegalStateException("other")
    try {
        try {
            throw original
        } catch (e: IllegalStateException) {
            throw e
        }
    } catch (outer: Throwable) {
        caught = outer
    }
    if (caught !== original) return "FAIL identity"
    if (caught.message != "orig") return "FAIL message"
    return "OK"
}
