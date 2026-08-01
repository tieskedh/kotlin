// Static-style declarations inside objects and companions fail at their own metadata subtree.
// The valid parent/siblings remain available, while real inheritance dependents of a rejected
// nested base still disappear through the live-map fixpoint. Generic nested classes with
// companions survive through one non-generic static holder.

object BrokenObjectOwner {
    annotation class BrokenAnnotation(val value: Int)

    class Good {
        fun value(): Int = 1
    }

    object BrokenObject {
        fun <T> unsupported(value: Array<T?>): Array<T?> = value
    }

    object GoodObject {
        fun value(): Int = 2
    }

    class GenericBroken<T> {
        companion object
    }

    open class BrokenBase {
        fun <T> unsupported(value: Array<T?>): Array<T?> = value
    }

    class Derived : BrokenBase()
}

class BrokenCompanionOwner {
    companion object {
        annotation class BrokenAnnotation(val value: Int)

        class Good {
            fun value(): Int = 3
        }

        object BrokenObject {
            fun unsupported(): Long {
                var result = 0L
                for (value in 1L..2L) {
                    result = value
                }
                return result
            }
        }

        object GoodObject {
            fun value(): Int = 4
        }

        class GenericBroken<T> {
            companion object
        }
    }
}

class UsesNestedObjectDeclarationSurvivors {
    fun objectClass(value: BrokenObjectOwner.Good): Int = value.value()

    fun companionClass(value: BrokenCompanionOwner.Companion.Good): Int = value.value()
}

fun main() {
    val uses = UsesNestedObjectDeclarationSurvivors()
    val classes = uses.objectClass(BrokenObjectOwner.Good()) +
            uses.companionClass(BrokenCompanionOwner.Companion.Good())
    println(classes + BrokenObjectOwner.GoodObject.value() +
            BrokenCompanionOwner.Companion.GoodObject.value())
}
