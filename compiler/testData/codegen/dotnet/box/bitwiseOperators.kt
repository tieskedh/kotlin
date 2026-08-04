private fun fail(message: String): String = "fail: $message"

fun box(): String {
    if ((6 and 3) != 2) return fail("Int.and")
    if ((4 or 3) != 7) return fail("Int.or")
    if ((6 xor 3) != 5) return fail("Int.xor")
    if (0.inv() != -1) return fail("Int.inv")
    if ((1 shl 31) != Int.MIN_VALUE) return fail("Int.shl sign")
    if ((1 shl 32) != 1) return fail("Int.shl masked")
    if ((1 shl -1) != Int.MIN_VALUE) return fail("Int.shl negative masked")
    if ((-8 shr 2) != -2) return fail("Int.shr")
    if ((-8 ushr 2) != 1073741822) return fail("Int.ushr")

    if ((6L and 3L) != 2L) return fail("Long.and")
    if ((4L or 3L) != 7L) return fail("Long.or")
    if ((6L xor 3L) != 5L) return fail("Long.xor")
    if (0L.inv() != -1L) return fail("Long.inv")
    if ((1L shl 63) != Long.MIN_VALUE) return fail("Long.shl sign")
    if ((1L shl 64) != 1L) return fail("Long.shl masked")
    if ((1L shl -1) != Long.MIN_VALUE) return fail("Long.shl negative masked")
    if ((-8L shr 2) != -2L) return fail("Long.shr")
    if ((-8L ushr 2) != 4611686018427387902L) return fail("Long.ushr")

    return "OK"
}
