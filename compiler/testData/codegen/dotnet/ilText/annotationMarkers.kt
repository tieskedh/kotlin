package test

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class RuntimeMarker

@Retention(AnnotationRetention.BINARY)
annotation class BinaryMarker

@Retention(AnnotationRetention.SOURCE)
annotation class SourceMarker

fun markerValueSemantics(): Boolean {
    val first: Annotation = RuntimeMarker()
    return first is RuntimeMarker &&
            first == RuntimeMarker() &&
            first != BinaryMarker() &&
            first.hashCode() == 0 &&
            first.toString() == "@test.RuntimeMarker()"
}

@RuntimeMarker
@BinaryMarker
@SourceMarker
class Annotated @RuntimeMarker constructor(@RuntimeMarker seed: Int) {
    @RuntimeMarker
    @get:RuntimeMarker
    @set:RuntimeMarker
    @field:RuntimeMarker
    var state: Int = seed

    @RuntimeMarker
    fun update(@RuntimeMarker value: Int) {
        state = value
    }
}
