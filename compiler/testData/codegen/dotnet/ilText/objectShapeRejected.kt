// This singleton object shape remains outside the supported model and is skipped with a warning.
// A data object needs audited generated equals/hashCode/toString bodies like a data class.
// Named objects nested in non-generic
// plain classes and anonymous object expressions are supported and covered separately.
data object D

fun main() {
    println("rejected")
}
