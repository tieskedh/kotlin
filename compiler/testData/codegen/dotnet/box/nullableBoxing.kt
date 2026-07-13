// Any? boundary round-trips: Int? -> Any? with a value and with null (the CLR collapses
// `box Nullable<T>` to boxed-T-or-null, boxprobe_s3), plain Int -> Any?, the supported
// ===/identity behaviors on Any?/reference operands, and string templates of null and values.
fun roundTrip(x: Int?): Any? = x

fun asAny(x: Int): Any? = x

fun isNull(a: Any?): Boolean = a == null

fun box(): String {
    if (!isNull(roundTrip(null))) return "fail: empty Nullable must collapse to null"
    if (isNull(roundTrip(3))) return "fail: some(3) must box non-null"
    if (isNull(asAny(0))) return "fail: boxed zero must be non-null"

    val a: Any? = "s"
    val b: Any? = a
    if (!(a === b)) return "fail: identity preserved through Any?"
    if (a === null) return "fail: non-null identity with null"
    val n: Any? = null
    if (!(n === null)) return "fail: null identity"
    if (!isNull(n)) return "fail: Any? == null"

    val x: Int? = null
    val y: Int? = 7
    if ("$x" != "null") return "fail: template of null Int?"
    if ("$y" != "7") return "fail: template of Int? value"
    val d: Double? = 2.5
    if ("$d" != "2.5") return "fail: template of Double? value"
    val noneD: Double? = null
    if ("$noneD" != "null") return "fail: template of null Double?"
    val c: Char? = 'z'
    if ("v=$c" != "v=z") return "fail: template of Char? value"
    val bl: Boolean? = null
    if ("$bl" != "null") return "fail: template of null Boolean?"
    val someBl: Boolean? = false
    if ("$someBl" != "false") return "fail: template of Boolean? value"
    val l: Long? = 12L
    if ("$l" != "12") return "fail: template of Long? value"
    val s: String? = null
    if ("$s" != "null") return "fail: template of null String?"

    return "OK"
}
