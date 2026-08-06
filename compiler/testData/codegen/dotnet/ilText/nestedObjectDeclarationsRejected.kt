// Static-style declarations inside objects and companions fail at their own metadata subtree.
// Valued nested annotations are ordinary admitted declarations; the other valid parent/siblings
// remain available, while real inheritance dependents of a rejected nested base still disappear
// through the live-map fixpoint. Generic nested classes with companions survive through one
// non-generic static holder.

object BrokenObjectOwner {
    annotation class NestedAnnotation(val value: Int)

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
        annotation class NestedAnnotation(val value: Int)

        class Good {
            fun value(): Int = 3
        }

        object BrokenObject {
            @Suppress("UNCHECKED_CAST")
            fun <T> unsupported(value: Array<T>): Int {
                val values: Array<T?> = value as Array<T?>
                return values.size
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
