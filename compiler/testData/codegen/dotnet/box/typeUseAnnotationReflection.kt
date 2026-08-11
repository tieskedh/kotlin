// MODULE: lib
// FILE: lib.kt

package typeannotations

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.TYPE)
@Repeatable
annotation class RuntimeTypeTag(val value: String)

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.TYPE)
annotation class BinaryTypeTag

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.TYPE)
annotation class SourceTypeTag

class InvariantBox<T>(val value: T)

fun taggedReturn():
        @RuntimeTypeTag("root-first")
        @RuntimeTypeTag("root-second")
        @BinaryTypeTag
        @SourceTypeTag
        InvariantBox<out @RuntimeTypeTag("argument") String?> = InvariantBox(null)

fun plainReturn(): InvariantBox<out String?> = InvariantBox(null)

fun taggedParameter(value: @RuntimeTypeTag("parameter") String): String = value

fun (@RuntimeTypeTag("receiver") String).taggedReceiver(): String = this

fun <T : @RuntimeTypeTag("bound") CharSequence> taggedBound(value: T): T = value

// MODULE: main(lib)
// FILE: main.kt

import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KFunction1
import kotlin.reflect.typeOf
import typeannotations.*

private fun tags(type: KAnnotatedElement): List<String> =
    type.annotations.map { annotation ->
        (annotation as? RuntimeTypeTag)?.value ?: "unexpected:$annotation"
    }

private fun fail(message: String): String = "FAIL: $message"

fun box(): String {
    val returned = ::taggedReturn.returnType
    if (tags(returned) != listOf("root-first", "root-second")) {
        return fail("return annotations ${returned.annotations}")
    }
    if (returned.annotations !== returned.annotations) return fail("annotation list identity")
    if (returned.annotations.any { it is BinaryTypeTag || it is SourceTypeTag }) {
        return fail("non-runtime retention ${returned.annotations}")
    }

    val argument = returned.arguments.single().type ?: return fail("missing argument type")
    if (tags(argument) != listOf("argument") || !argument.isMarkedNullable) {
        return fail("argument annotations ${argument.annotations}, nullable=${argument.isMarkedNullable}")
    }
    val plain = ::plainReturn.returnType
    if (plain.annotations.isNotEmpty() || plain.arguments.single().type!!.annotations.isNotEmpty()) {
        return fail("plain type annotations")
    }
    if (returned != plain || returned.hashCode() != plain.hashCode()) {
        return fail("annotations changed structural KType identity")
    }

    val parameter = ::taggedParameter.parameters.single().type
    if (tags(parameter) != listOf("parameter") || taggedParameter("OK") != "OK") {
        return fail("parameter annotations ${parameter.annotations}")
    }

    val receiver = String::taggedReceiver.parameters.single().type
    if (tags(receiver) != listOf("receiver") || "OK".taggedReceiver() != "OK") {
        return fail("receiver annotations ${receiver.annotations}")
    }

    val boundReference: KFunction1<String, String> = ::taggedBound
    val bound = boundReference.typeParameters.single().upperBounds.single()
    if (tags(bound) != listOf("bound") || taggedBound("OK") != "OK") {
        return fail("bound annotations ${bound.annotations}")
    }

    val direct = typeOf<@RuntimeTypeTag("typeOf-root") String>()
    val nested = typeOf<InvariantBox<out @RuntimeTypeTag("typeOf-argument") String>>()
    if (direct.annotations.isNotEmpty() ||
        nested.annotations.isNotEmpty() ||
        nested.arguments.single().type!!.annotations.isNotEmpty()
    ) {
        return fail("typeOf annotation leakage")
    }

    return "OK"
}
