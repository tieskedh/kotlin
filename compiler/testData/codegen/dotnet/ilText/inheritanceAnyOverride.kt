// Overriding a kotlin.Any member needs a virtual-slot relationship with System.Object's
// members — an Any model that does not exist yet — so each class here is rejected whole at the
// shape gate with a message naming the member, and only the file facade is emitted. The
// rejection also covers indirect overrides in a derived class: `Indirect` both extends a
// rejected base (cascade) and re-declares `toString` (its own gate rejection).
class Stringy {
    override fun toString(): String = "S"
}

open class EqualsBase {
    override fun equals(other: Any?): Boolean = other is EqualsBase
    override fun hashCode(): Int = 1
}

class Indirect : EqualsBase() {
    override fun toString(): String = "I"
}

fun main() {
    println("any-override")
}
