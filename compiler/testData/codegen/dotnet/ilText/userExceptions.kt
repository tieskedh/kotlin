open class DomainFailure(message: String) : RuntimeException(message)

class ChildFailure(message: String) : DomainFailure(message)

class MappedFailure(message: String) : IllegalStateException(message)

class FatalFailure(message: String) : Error(message)

fun throwChild(): Nothing = throw ChildFailure("child")

fun catchExact(): Boolean = try {
    throw ChildFailure("child")
} catch (failure: DomainFailure) {
    true
}

fun catchMappedSubclassAsRuntime(): Boolean = try {
    throw MappedFailure("mapped")
} catch (failure: RuntimeException) {
    true
}

fun main() {
    println("user-exceptions")
}
