// '!!' on null throws the mapped Kotlin NPE (System.NullReferenceException via the exception
// registry, boxprobe_s4) and stays catchable as NullPointerException — both the Nullable<T>
// (HasValue branch) and the reference (dup/brtrue) flavors; success paths flow the value through.
fun forcePrimitive(x: Int?): Int = x!!

fun forceReference(s: String?): String = s!!

fun box(): String {
    var caught = 0
    try {
        forcePrimitive(null)
        return "fail: no NPE from primitive !!"
    } catch (e: NullPointerException) {
        caught = caught + 1
    }
    try {
        forceReference(null)
        return "fail: no NPE from reference !!"
    } catch (e: NullPointerException) {
        caught = caught + 1
    }
    try {
        val x: Long? = null
        x!!
        return "fail: no NPE from local !!"
    } catch (e: NullPointerException) {
        caught = caught + 1
    } catch (e: Throwable) {
        return "fail: '!!' threw something other than NullPointerException"
    }
    if (caught != 3) return "fail: caught $caught of 3"
    if (forcePrimitive(5) != 5) return "fail: !! success primitive"
    if (forceReference("r") != "r") return "fail: !! success reference"
    return "OK"
}
