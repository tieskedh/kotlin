// CLR class generics stay reified for storage and signatures, but generated data-class equality
// uses a private non-generic nested view so different GenericData<T> constructions retain
// Kotlin's erased class identity. Component bridges are private explicit interface methods.
data class GenericData<T>(val value: T, val count: Int)

fun compareGenericData(left: GenericData<Any?>, right: Any?): Boolean = left == right

fun <T> duplicateGenericData(value: T): GenericData<T> = GenericData(value, 2).copy()

fun main() {
    println(compareGenericData(GenericData(null, 1), GenericData<String?>(null, 1)))
}
