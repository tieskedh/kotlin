package hybrid.composition

private fun same(actual: Any?, expected: Any?) {
    check(actual === expected) { "Receiver identity changed" }
}

private fun nestedOutReplacement() {
    val first = Box(17)
    val outer: Box<Box<out Any>> = make<Box<out Any>>(first)
    val alias = outer
    same(alias.value, first)
    check(alias.value.value == 17)

    val replacement = Box("replacement")
    replaceOut(outer, replacement)
    same(alias, outer)
    same(alias.value, replacement)
    check(alias.value.value == "replacement")
    check(first.value == 17)
}

private fun referenceOnlyInProjection() {
    val first = Box<Any>("initial")
    val outer: Box<Box<in String>> = make<Box<in String>>(first)
    val alias = outer
    same(alias.value, first)
    writeIn(outer, "first write")
    check(first.value == "first write")

    val replacement = Box("replacement")
    replaceIn(outer, replacement)
    same(alias, outer)
    same(alias.value, replacement)
    writeIn(outer, "second write")
    check(replacement.value == "second write")
    check(first.value == "first write")
}

private fun openProjectedFactoryAndWriter() {
    val first = Box(23)
    val outer: Box<Box<out Any>> = wrapProjected<Any>(first)
    val alias = outer
    same(alias.value, first)
    check(alias.value.value == 23)

    val replacement = Box("projected")
    replaceProjected<Any>(outer, replacement)
    same(alias, outer)
    same(alias.value, replacement)
    check(alias.value.value == "projected")
    check(first.value == 23)
}

private fun nullableFactoryInsideAnotherBox() {
    val first: Box<Int?> = wrapNullable<Int>(11)
    val outer: Box<Box<Int?>> = make<Box<Int?>>(first)
    val alias = outer
    same(alias.value, first)
    check(first.value == 11)

    writeNullableNested(outer, 29)
    same(alias.value, first)
    check(first.value == 29)
    val replacement: Box<Int?> = wrapNullable<Int>(null)
    replaceNullableNested(outer, replacement)
    same(alias, outer)
    same(alias.value, replacement)
    check(alias.value.value == null)
    writeNullableNested(outer, 31)
    check(replacement.value == 31)
    check(first.value == 29)
}

private fun nullableSubstitutionIsIdempotent() {
    val absent: Box<Int?> = wrapNullable<Int?>(null)
    val absentAlias = absent
    check(absent.value == null)
    writeNullable(absent, 37)
    same(absentAlias, absent)
    check(absentAlias.value == 37)

    val present: Box<Int?> = wrapNullable<Int?>(41)
    val presentAlias = present
    check(present.value == 41)
    writeNullable(present, null)
    same(presentAlias, present)
    check(presentAlias.value == null)
    check(absentAlias.value == 37)
}

private fun nullableBaseAcrossAssemblies() {
    val derived = Derived<Int>(43)
    val base: Base<Int?> = derived
    same(base, derived)
    check(base.value == 43)
    writeBase(base, null)
    check(derived.value == null)
    writeBase(base, 47)
    check(derived.value == 47)
    same(base, derived)

    val field = Base::class.java.declaredFields.single()
    check(field.name == "value" && java.lang.reflect.Modifier.isPrivate(field.modifiers))
    check(field.type == Any::class.java)
    check(Derived::class.java.declaredFields.isEmpty())
}

fun main() {
    // Reflection is a layout assertion only, never storage or dispatch implementation.
    val field = Box::class.java.declaredFields.single()
    check(field.name == "value" && java.lang.reflect.Modifier.isPrivate(field.modifiers))
    check(field.type == Any::class.java)
    val cases = listOf(
        "nested-out-replacement" to ::nestedOutReplacement,
        "reference-only-in-projection" to ::referenceOnlyInProjection,
        "open-projected-factory-and-writer" to ::openProjectedFactoryAndWriter,
        "nullable-factory-nested-storage" to ::nullableFactoryInsideAnotherBox,
        "nullable-substitution-idempotence" to ::nullableSubstitutionIsIdempotent,
        "nullable-base-separate-writer" to ::nullableBaseAcrossAssemblies,
    )
    var failures = 0
    for (case in cases) {
        try {
            case.second.invoke()
            println("PASS: " + case.first)
        } catch (failure: Throwable) {
            failures++
            println("FAIL: " + case.first + ": " + failure)
            failure.printStackTrace()
        }
    }
    check(failures == 0) { "$failures composition group(s) failed" }
    println("PASS: Common/JVM composition baseline; groups=6; separate-jars=3")
}
