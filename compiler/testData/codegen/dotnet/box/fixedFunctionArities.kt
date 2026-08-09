import kotlin.reflect.KFunction22
import kotlin.reflect.KParameter

private class Four : (Int, Int, Int, Int) -> Int {
    override fun invoke(p1: Int, p2: Int, p3: Int, p4: Int): Int =
        p1 + p2 + p3 + p4
}

private fun weighted22(
    p1: Int, p2: Int, p3: Int, p4: Int, p5: Int, p6: Int, p7: Int, p8: Int, p9: Int, p10: Int, p11: Int,
    p12: Int, p13: Int, p14: Int, p15: Int, p16: Int, p17: Int, p18: Int, p19: Int, p20: Int, p21: Int, p22: Int,
): Int = p1 + p2 * 2 + p3 * 3 + p4 * 4 + p5 * 5 + p6 * 6 + p7 * 7 + p8 * 8 + p9 * 9 + p10 * 10 + p11 * 11 +
        p12 * 12 + p13 * 13 + p14 * 14 + p15 * 15 + p16 * 16 + p17 * 17 + p18 * 18 + p19 * 19 + p20 * 20 +
        p21 * 21 + p22 * 22

private fun chainedDefaults22(
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
): Int = p1 * 100000 + p11 * 100 + p22

fun box(): String {
    val four: (Int, Int, Int, Int) -> Int = Four()
    if (four(1, 2, 3, 4) != 10) return "fail 1: user Function4 implementation"

    val lambda22 = {
            p1: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, p11: Int,
            _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, _: Int, p22: Int,
        -> p1 + p11 + p22
    }
    if (lambda22(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
            12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
        ) != 34
    ) return "fail 2: Function22 lambda"

    val reference: KFunction22<
            Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
            Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
            Int,
            > = ::weighted22
    val expected = 3795
    if (reference(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
            12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
        ) != expected
    ) return "fail 3: direct KFunction22 invocation"
    if (reference.call(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
            12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22,
        ) != expected
    ) return "fail 4: reflective KFunction22 call"

    val byName = mutableMapOf<KParameter, Any?>()
    for (parameter in reference.parameters) {
        byName[parameter] = parameter.index + 1
    }
    if (reference.callBy(byName) != expected) return "fail 5: reflective KFunction22 callBy"

    val defaults: KFunction22<
            Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
            Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
            Int,
            > = ::chainedDefaults22
    if (defaults.callBy(emptyMap()) != 101122) return "fail 6: all 22 defaults"
    val defaultParameters = defaults.parameters
    if (defaults.callBy(mapOf(defaultParameters[0] to 10)) != 1002031) {
        return "fail 7: leading supplied default"
    }
    if (defaults.callBy(mapOf(defaultParameters[10] to 50)) != 105061) {
        return "fail 8: middle supplied default"
    }

    return "OK"
}
