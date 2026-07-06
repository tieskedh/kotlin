fun divide(a: Int, b: Int): Int = a / b

fun remainder(a: Int, b: Int): Int = a % b

fun divideByConstMinusOne(a: Int): Int = a / -1

fun remainderByConstMinusOne(a: Int): Int = a % -1

fun box(): String {
    val min = -2147483647 - 1
    // JVM idiv semantics: Int.MIN_VALUE / -1 wraps back to Int.MIN_VALUE, remainder is 0.
    if (divide(min, -1) != min) return "fail 1: got " + divide(min, -1)
    if (remainder(min, -1) != 0) return "fail 2: got " + remainder(min, -1)
    if (divideByConstMinusOne(min) != min) return "fail 3: got " + divideByConstMinusOne(min)
    if (remainderByConstMinusOne(min) != 0) return "fail 4: got " + remainderByConstMinusOne(min)
    // Ordinary division truncates toward zero; remainder takes the sign of the dividend.
    if (divide(-7, 2) != -3) return "fail 5: got " + divide(-7, 2)
    if (remainder(-7, 2) != -1) return "fail 6: got " + remainder(-7, 2)
    if (divide(7, -2) != -3) return "fail 7: got " + divide(7, -2)
    if (remainder(7, -2) != 1) return "fail 8: got " + remainder(7, -2)
    if (divide(min, 2) != -1073741824) return "fail 9: got " + divide(min, 2)
    if (remainder(min, 2) != 0) return "fail 10: got " + remainder(min, 2)
    return "OK"
}
