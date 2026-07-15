// Omitted generic defaults need a real CLR default(T) placeholder. The generated mask-owning
// helper overwrites it before the source declaration can observe the value.
fun <T> topDefault(value: T, selected: T = value): T = selected

class GenericDefaults<T>(val value: T, val selected: T = value) {
    fun member(selected: T = value): T = selected

    fun throughMember(): T = member()

    fun <R> method(value: R, selected: R = value): R = selected
}

data class GenericDefaultData<T>(val value: T)

fun <T> exerciseDefaults(value: T): T {
    val owner = GenericDefaults(value)
    owner.member()
    owner.throughMember()
    owner.method(value)
    GenericDefaultData(value).copy()
    return topDefault(value)
}

fun exerciseConcreteDefaults() {
    topDefault(true)
    topDefault('k')
    topDefault(11)
    topDefault(12L)
    topDefault(1.5)
    topDefault("direct")
    topDefault<Int?>(null)
}

fun main() {
    exerciseConcreteDefaults()
    println(exerciseDefaults(7))
    println(exerciseDefaults("value"))
}
