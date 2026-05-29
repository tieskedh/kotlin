// CHECK_TYPESCRIPT_DECLARATIONS
// RUN_PLAIN_BOX_FUNCTION
// SKIP_NODE_JS
// INFER_MAIN_MODULE
// LANGUAGE: +CompanionBlocksAndExtensions
// MODULE: JS_TESTS
// FILE: companion-blocks.kt

package foo

@JsExport
class MyClass {
    companion {
        val foo = "FOOOO"

        var mutable = "INITIAL"

        fun bar(): String = "BARRRR"

        val baz get() = "BAZZZZ"
    }

    val instanceFoo = "INSTANCE_FOOOO"

    var instanceMutable = "INSTANCE_INITIAL"

    fun instanceBar(): String = "INSTANCE_BARRRR"

    val instanceBaz get() = "INSTANCE_BAZZZZ"
}

@JsExport
companion fun MyClass.companionExtensionFun(p: String): String = "COMPANION_EXT_FUN"

// Inheritance: an open base and its subclass both declare companion blocks. The child re-declares `foo` (shadows the
// parent's static) and adds `childOnly`, while `bar` is only present in the base.
@JsExport
open class Base {
    companion {
        fun foo(): String = "BASE_FOO"

        fun bar(): String = "BASE_BAR"
    }
}

@JsExport
class Child : Base() {
    companion {
        fun foo(): String = "CHILD_FOO"

        fun childOnly(): String = "CHILD_ONLY"
    }
}

@JsExport
abstract class AbstractWithCompanion {
    companion {
        val abstractCompanionVal = "ABSTRACT_COMPANION_VAL"

        fun abstractCompanionFun(): String = "ABSTRACT_COMPANION_FUN"
    }

    abstract fun instanceAbstractFun(): String
}

@JsExport
interface InterfaceWithCompanion {
    companion {
        fun interfaceCompanionFun(): String = "INTERFACE_COMPANION_FUN"
    }
}

// Visibility: only public companion members must be exported
@JsExport
class VisibilityInCompanion {
    companion {
        private val privateVal = "PRIVATE_VAL"

        internal val internalVal = "INTERNAL_VAL"

        val publicVal = "PUBLIC_VAL"

        private fun privateFun(): String = "PRIVATE_FUN"

        internal fun internalFun(): String = "INTERNAL_FUN"

        fun publicFun(): String = "PUBLIC_FUN"
    }
}
