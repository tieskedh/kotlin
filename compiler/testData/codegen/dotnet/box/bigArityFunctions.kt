// MODULE: lib
// FILE: lib.kt

package big.arity

import kotlin.reflect.KFunction23

typealias Function23OfInt = (
    Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
    Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
) -> Int

interface BigCombiner : Function23OfInt

fun apply23(function: Function23OfInt): Int = function(
    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
    13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
)

fun applyBigCombiner(function: BigCombiner): Int = apply23(function)

fun make23(): Function23OfInt = {
        p1, _, _, _, _, _, _, _, _, _, _, p12,
        _, _, _, _, _, _, _, _, _, _, p23,
    -> p1 + p12 + p23
}

private fun weighted23(
    p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int, p8: Int,
    p9: Int, p10: Int, p11: Int, p12: Int, p13: Int, p14: Int, p15: Int, p16: Int,
    p17: Int, p18: Int, p19: Int, p20: Int, p21: Int, p22: Int, p23: Int,
): Int = p1 + p12 + p23

fun reference23(): KFunction23<
    Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
    Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
    Int,
> = ::weighted23

fun chainedDefaults33(
    p1: Int = 1,
    p2: Int = p1 + 1,
    p3: Int = p2 + 1,
    p4: Int = p3 + 1,
    p5: Int = p4 + 1,
    p6: Int = p5 + 1,
    p7: Int = p6 + 1,
    p8: Int = p7 + 1,
    p9: Int = p8 + 1,
    p10: Int = p9 + 1,
    p11: Int = p10 + 1,
    p12: Int = p11 + 1,
    p13: Int = p12 + 1,
    p14: Int = p13 + 1,
    p15: Int = p14 + 1,
    p16: Int = p15 + 1,
    p17: Int = p16 + 1,
    p18: Int = p17 + 1,
    p19: Int = p18 + 1,
    p20: Int = p19 + 1,
    p21: Int = p20 + 1,
    p22: Int = p21 + 1,
    p23: Int = p22 + 1,
    p24: Int = p23 + 1,
    p25: Int = p24 + 1,
    p26: Int = p25 + 1,
    p27: Int = p26 + 1,
    p28: Int = p27 + 1,
    p29: Int = p28 + 1,
    p30: Int = p29 + 1,
    p31: Int = p30 + 1,
    p32: Int = p31 + 1,
    p33: Int = p32 + 1,
): Int = p1 * 1_000_000 + p32 * 1_000 + p33

fun defaultsReference33() = ::chainedDefaults33

// MODULE: main(lib)
// FILE: main.kt

import big.arity.*

private typealias LocalFunction23 = (
    Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
    Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
) -> Int

private typealias StarFunction23 = Function23<
    *, *, *, *, *, *, *, *, *, *, *, *,
    *, *, *, *, *, *, *, *, *, *, *, *,
>

private typealias StarFunction24 = Function24<
    *, *, *, *, *, *, *, *, *, *, *, *,
    *, *, *, *, *, *, *, *, *, *, *, *,
    *,
>

private typealias StarKFunction23 = kotlin.reflect.KFunction23<
    *, *, *, *, *, *, *, *, *, *, *, *,
    *, *, *, *, *, *, *, *, *, *, *, *,
>

private typealias StarKFunction24 = kotlin.reflect.KFunction24<
    *, *, *, *, *, *, *, *, *, *, *, *,
    *, *, *, *, *, *, *, *, *, *, *, *,
    *,
>

private class TwentyThree : LocalFunction23 {
    override fun invoke(
        p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int, p8: Int,
        p9: Int, p10: Int, p11: Int, p12: Int, p13: Int, p14: Int, p15: Int, p16: Int,
        p17: Int, p18: Int, p19: Int, p20: Int, p21: Int, p22: Int, p23: Int,
    ): Int = p1 + p12 + p23
}

private class TransitiveTwentyThree : BigCombiner {
    override fun invoke(
        p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int, p8: Int,
        p9: Int, p10: Int, p11: Int, p12: Int, p13: Int, p14: Int, p15: Int, p16: Int,
        p17: Int, p18: Int, p19: Int, p20: Int, p21: Int, p22: Int, p23: Int,
    ): Int = p1 + p12 + p23
}

private fun invoke23(function: LocalFunction23): Int = function(
    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
    13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
)

fun box(): String {
    if (invoke23(TwentyThree()) != 36) return "fail 1: user Function23"
    if (applyBigCombiner(TransitiveTwentyThree()) != 36) return "fail 1a: transitive Function23 interface"

    val lambda: LocalFunction23 = {
            p1, _, _, _, _, _, _, _, _, _, _, p12,
            _, _, _, _, _, _, _, _, _, _, p23,
        -> p1 + p12 + p23
    }
    if (apply23(lambda) != 36) return "fail 2: consumer Function23 in producer"
    val produced = make23()
    if (invoke23(produced) != 36) return "fail 3: producer Function23 in consumer"
    val widened: Any = lambda
    if (widened !is StarFunction23) return "fail 3a: Function23 is"
    if (widened is StarFunction24) return "fail 3b: wrong Function24 is"
    if (widened as? StarFunction24 != null) return "fail 3c: wrong Function24 as?"
    val same = widened as StarFunction23
    if (same !== lambda) return "fail 3d: correct cast changed identity"
    try {
        widened as StarFunction24
        return "fail 3e: wrong Function24 cast succeeded"
    } catch (_: ClassCastException) {
        // Expected: all big arities share FunctionN physically, so the runtime arity is checked.
    }

    val reference = reference23()
    if (invoke23(reference) != 36) return "fail 4: producer KFunction23 direct invoke"
    val reflectiveWidened: Any = reference
    if (reflectiveWidened !is StarKFunction23) return "fail 4a: KFunction23 is"
    if (reflectiveWidened is StarKFunction24) return "fail 4b: wrong KFunction24 is"
    if (reflectiveWidened as? StarKFunction24 != null) return "fail 4c: wrong KFunction24 as?"
    if ((reflectiveWidened as StarKFunction23) !== reference) return "fail 4d: KFunction23 cast identity"
    if (lambda as? StarKFunction23 != null) return "fail 4e: plain Function23 is KFunction23"
    try {
        reflectiveWidened as StarKFunction24
        return "fail 4f: wrong KFunction24 cast succeeded"
    } catch (_: ClassCastException) {
        // Expected: reflection identity and execution arity are both checked.
    }
    if (reference.call(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
            13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
        ) != 36
    ) return "fail 5: producer KFunction23 call"

    val defaults33 = defaultsReference33()
    if (defaults33(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
            12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
            23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33,
        ) != 1_032_033
    ) return "fail 6: producer KFunction33 direct invoke"
    if (defaults33.callBy(emptyMap()) != 1_032_033) return "fail 7: two omitted mask words"
    val parameters33 = defaults33.parameters
    if (defaults33.callBy(mapOf(parameters33[0] to 10)) != 10_041_042) {
        return "fail 8: leading value with two mask words"
    }
    if (defaults33.callBy(mapOf(parameters33[31] to 100)) != 1_100_101) {
        return "fail 9: supplied bit 31"
    }
    if (defaults33.callBy(mapOf(parameters33[32] to 200)) != 1_032_200) {
        return "fail 10: supplied bit 32"
    }

    return "OK"
}
