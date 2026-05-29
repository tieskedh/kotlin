// RUN_PLAIN_BOX_FUNCTION
// INFER_MAIN_MODULE
// LANGUAGE: +CompanionBlocksAndExtensions

// MODULE: export_companion_block
// FILE: lib.kt

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
        // Shadows parent `foo`, doesn't override
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

// FILE: test.js
function box() {
    var module = this["export_companion_block"];
    var MyClass = module.MyClass;

    if (MyClass.bar() !== "BARRRR") return "FAIL: bar() problem"
    if (MyClass.foo !== "FOOOO") return "FAIL: foo problem"
    if (MyClass.baz !== "BAZZZZ") return "FAIL: baz problem"
    if (MyClass.mutable !== "INITIAL") return "FAIL: mutable before mutation"
    MyClass.mutable = "CHANGED"
    if (MyClass.mutable !== "CHANGED") return "FAIL: mutable after mutation"
    if (module.companionExtensionFun("") !== "COMPANION_EXT_FUN") return "FAIL: companion extension function"

    var instance = new MyClass();
    if (instance.instanceBar() !== "INSTANCE_BARRRR") return "FAIL: instanceBar() problem"
    if (instance.instanceFoo !== "INSTANCE_FOOOO") return "FAIL: instanceFoo problem"
    if (instance.instanceBaz !== "INSTANCE_BAZZZZ") return "FAIL: instanceBaz problem"
    if (instance.instanceMutable !== "INSTANCE_INITIAL") return "FAIL: instanceMutable before mutation"
    instance.instanceMutable = "INSTANCE_CHANGED"
    if (instance.instanceMutable !== "INSTANCE_CHANGED") return "FAIL: instanceMutable after mutation"

    // Inheritance: base statics and child statics (child shadows `foo`, `bar` stays on the base only).
    var Base = module.Base;
    var Child = module.Child;
    if (Base.foo() !== "BASE_FOO") return "FAIL: Base.foo() problem"
    if (Base.bar() !== "BASE_BAR") return "FAIL: Base.bar() problem"
    if (Child.foo() !== "CHILD_FOO") return "FAIL: Child.foo() problem"
    if (Child.childOnly() !== "CHILD_ONLY") return "FAIL: Child.childOnly() problem"

    // Abstract class companion statics.
    var AbstractWithCompanion = module.AbstractWithCompanion;
    if (AbstractWithCompanion.abstractCompanionFun() !== "ABSTRACT_COMPANION_FUN") return "FAIL: abstractCompanionFun() problem"
    if (AbstractWithCompanion.abstractCompanionVal !== "ABSTRACT_COMPANION_VAL") return "FAIL: abstractCompanionVal problem"

    // Interface companion members.
    var InterfaceWithCompanion = module.InterfaceWithCompanion;
    if (InterfaceWithCompanion.interfaceCompanionFun() !== "INTERFACE_COMPANION_FUN") return "FAIL: interfaceCompanionFun() problem"

    // Visibility: only public companion members are exported.
    var VisibilityInCompanion = module.VisibilityInCompanion;
    if (VisibilityInCompanion.publicFun() !== "PUBLIC_FUN") return "FAIL: publicFun() problem"
    if (VisibilityInCompanion.publicVal !== "PUBLIC_VAL") return "FAIL: publicVal problem"

    return "OK"
}
