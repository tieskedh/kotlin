package test

import kotlin.reflect.KClass

private enum class Shade { LIGHT, DARK }

private annotation class NestedValue(val text: String = "nested")

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
private annotation class RuntimeValues(
    val number: Int = 42,
    val text: String = "default",
    val klass: KClass<*> = String::class,
    val shade: Shade = Shade.DARK,
    val nested: NestedValue = NestedValue(),
    val numbers: IntArray = [1, 2, 3],
    val names: Array<String> = ["a", "b"],
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
@Repeatable
private annotation class Tag(val value: String)

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
private annotation class BinaryOnly

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
private annotation class SourceOnly

@RuntimeValues
@Tag("first")
@Tag("second")
@BinaryOnly
@SourceOnly
private class Applied

@RuntimeValues(number = 7, text = "generic")
private class GenericApplied<T>(val value: T)

@Tag("interface")
private interface AnnotatedInterface

private class Empty

private fun fail(message: String): String = "FAIL: $message"

fun box(): String {
    val annotations = Applied::class.annotations
    if (annotations.size != 3) return fail("size ${annotations.size}: $annotations")

    val values = annotations[0] as? RuntimeValues ?: return fail("first ${annotations[0]}")
    if (values.number != 42 || values.text != "default") return fail("scalar defaults")
    if (values.klass != String::class || values.shade != Shade.DARK) return fail("class/enum")
    if (values.nested != NestedValue()) return fail("nested")
    if (!values.numbers.contentEquals(intArrayOf(1, 2, 3))) return fail("primitive array")
    if (!values.names.contentEquals(arrayOf("a", "b"))) return fail("generic array")

    val firstTag = annotations[1] as? Tag ?: return fail("first tag")
    val secondTag = annotations[2] as? Tag ?: return fail("second tag")
    if (firstTag.value != "first" || secondTag.value != "second") return fail("tag order")
    if (!annotations.contains(firstTag) || annotations.indexOf(secondTag) != 2) return fail("lookup")
    if (annotations.lastIndexOf(firstTag) != 1) return fail("last index")
    if (!annotations.containsAll(annotations.subList(1, 3))) return fail("containsAll/subList")

    val iterator = annotations.listIterator(annotations.size)
    if (!iterator.hasPrevious() || iterator.previous() != secondTag) return fail("previous")
    if (iterator.nextIndex() != 2 || iterator.previousIndex() != 1) return fail("iterator indices")
    if (annotations.hashCode() != annotations.hashCode()) return fail("hash stability")
    val rendered = annotations.toString()
    if (rendered[0] != '[' || rendered[rendered.length - 1] != ']') {
        return fail("list string")
    }

    if (Applied()::class.annotations != annotations) return fail("dynamic class")
    if (Empty::class.annotations.isNotEmpty()) return fail("empty")
    if (String::class.annotations.isNotEmpty()) return fail("mapped BCL leakage")

    val genericAnnotations = GenericApplied("value")::class.annotations
    if (genericAnnotations.size != 1) return fail("generic annotation count ${genericAnnotations.size}")
    val generic = genericAnnotations[0] as? RuntimeValues
        ?: return fail("generic annotation")
    if (generic.number != 7 || generic.text != "generic") return fail("generic values")
    val interfaceAnnotations = AnnotatedInterface::class.annotations
    if (interfaceAnnotations.size != 1) return fail("interface annotation count ${interfaceAnnotations.size}")
    val interfaceTag = interfaceAnnotations[0] as? Tag
        ?: return fail("interface annotation")
    if (interfaceTag.value != "interface") return fail("interface value")

    @Tag("local")
    class Local
    val localAnnotations = Local::class.annotations
    if (localAnnotations.size != 1) return fail("local annotation count ${localAnnotations.size}")
    val localTag = localAnnotations[0] as? Tag ?: return fail("local annotation")
    if (localTag.value != "local" || Local()::class.annotations != Local::class.annotations) {
        return fail("local value")
    }

    return "OK"
}
