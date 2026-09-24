package hybrid.composition

fun replaceOut(outer: Box<Box<out Any>>, replacement: Box<String>) {
    outer.value = replacement
}

fun writeIn(outer: Box<Box<in String>>, value: String) {
    outer.value.value = value
}

fun replaceIn(outer: Box<Box<in String>>, replacement: Box<String>) {
    outer.value = replacement
}

fun <T> replaceProjected(outer: Box<Box<out T>>, replacement: Box<out T>) {
    outer.value = replacement
}

fun writeNullable(box: Box<Int?>, value: Int?) {
    box.value = value
}

fun writeNullableNested(outer: Box<Box<Int?>>, value: Int?) {
    outer.value.value = value
}

fun replaceNullableNested(outer: Box<Box<Int?>>, replacement: Box<Int?>) {
    outer.value = replacement
}

fun writeBase(base: Base<Int?>, value: Int?) {
    base.value = value
}
