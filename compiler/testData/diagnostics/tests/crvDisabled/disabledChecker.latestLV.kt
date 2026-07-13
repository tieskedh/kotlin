// RUN_PIPELINE_TILL: FRONTEND
// WITH_STDLIB
// LANGUAGE_VERSION: 2.4
// API_VERSION: 2.4
// ALLOW_DANGEROUS_LANGUAGE_VERSION_TESTING
// This test can be deleted when 2.4 is obsolete
// LATEST_LV_DIFFERENCE

@file:MustUseReturnValues

fun foo(): String = ""

@IgnorableReturnValue
fun bar(): Int = 42

@MustUseReturnValues
class Test {
    @IgnorableReturnValue
    fun method(): Double = 0.0
}

/* GENERATED_FIR_TAGS: annotationUseSiteTargetFile, classDeclaration, functionDeclaration, integerLiteral, stringLiteral */
