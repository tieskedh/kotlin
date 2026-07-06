fun divide(a: Long, b: Long): Long = a / b

fun remainder(a: Long, b: Long): Long = a % b

fun divideByConstMinusOne(a: Long): Long = a / -1L

fun remainderByConstMinusOne(a: Long): Long = a % -1L

fun box(): String {
    val min = -9223372036854775807L - 1L
    // JVM ldiv semantics: Long.MIN_VALUE / -1 wraps back to Long.MIN_VALUE, remainder is 0.
    if (divide(min, -1L) != min) return "fail 1: got " + divide(min, -1L)
    if (remainder(min, -1L) != 0L) return "fail 2: got " + remainder(min, -1L)
    if (divideByConstMinusOne(min) != min) return "fail 3: got " + divideByConstMinusOne(min)
    if (remainderByConstMinusOne(min) != 0L) return "fail 4: got " + remainderByConstMinusOne(min)
    // Ordinary division truncates toward zero; remainder takes the sign of the dividend.
    if (divide(-7L, 2L) != -3L) return "fail 5: got " + divide(-7L, 2L)
    if (remainder(-7L, 2L) != -1L) return "fail 6: got " + remainder(-7L, 2L)
    if (divide(7L, -2L) != -3L) return "fail 7: got " + divide(7L, -2L)
    if (remainder(7L, -2L) != 1L) return "fail 8: got " + remainder(7L, -2L)
    if (divide(min, 2L) != -4611686018427387904L) return "fail 9: got " + divide(min, 2L)
    if (remainder(min, 2L) != 0L) return "fail 10: got " + remainder(min, 2L)
    return "OK"
}
