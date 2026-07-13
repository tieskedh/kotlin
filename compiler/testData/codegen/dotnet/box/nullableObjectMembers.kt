// Nullable primitives as members of an `object` and a `companion object`: the fields, the
// accessors and the initializers go through the singleton/static machinery (the
// DotNetInitializersLowering static marking and the enclosing-class `.cctor` path), which must
// carry Nullable<T> field types like any other — reads, writes, elvis, null tests and string
// templates all flow through the singleton instance.
object O {
    val n: Int? = 5
    var m: Long? = null
    fun get(): Int = n ?: 0
}

class WithComp {
    companion object {
        val cn: Double? = 2.5
        var cm: Char? = null
    }
}

fun box(): String {
    if (O.get() != 5) return "fail: object val through elvis"
    if (O.m != null) return "fail: object var initial"
    O.m = 9L
    if ((O.m ?: 0L) != 9L) return "fail: object var write"
    if (WithComp.cn == null) return "fail: companion val null test"
    if ((WithComp.cn ?: 0.0) != 2.5) return "fail: companion val"
    if (WithComp.cm != null) return "fail: companion var initial"
    WithComp.cm = 'q'
    if (WithComp.cm != 'q') return "fail: companion var write"
    if ("${O.n} ${O.m} ${WithComp.cn} ${WithComp.cm}" != "5 9 2.5 q") return "fail: templates"
    return "OK"
}
