import MyClass = JS_TESTS.foo.MyClass;
import Base = JS_TESTS.foo.Base;
import Child = JS_TESTS.foo.Child;
import AbstractWithCompanion = JS_TESTS.foo.AbstractWithCompanion;
import InterfaceWithCompanion = JS_TESTS.foo.InterfaceWithCompanion;
import VisibilityInCompanion = JS_TESTS.foo.VisibilityInCompanion;
import companionExtensionFun = JS_TESTS.foo.companionExtensionFun;

function assert(condition: boolean) {
    if (!condition) {
        throw "Assertion failed";
    }
}

function box(): string {
    assert(MyClass.bar() === "BARRRR")
    assert(MyClass.foo === "FOOOO")
    assert(MyClass.baz === "BAZZZZ")
    assert(MyClass.mutable === "INITIAL")
    MyClass.mutable = "CHANGED"
    assert(MyClass.mutable === "CHANGED")

    const instance = new MyClass()
    assert(instance.instanceBar() === "INSTANCE_BARRRR")
    assert(instance.instanceFoo === "INSTANCE_FOOOO")
    assert(instance.instanceBaz === "INSTANCE_BAZZZZ")
    assert(instance.instanceMutable === "INSTANCE_INITIAL")
    instance.instanceMutable = "INSTANCE_CHANGED"
    assert(instance.instanceMutable === "INSTANCE_CHANGED")

    // Companion (static) extensions are exported as top-level functions.
    assert(companionExtensionFun("") === "COMPANION_EXT_FUN")

    assert(Base.foo() === "BASE_FOO")
    assert(Base.bar() === "BASE_BAR")
    assert(Child.foo() === "CHILD_FOO")
    assert(Child.childOnly() === "CHILD_ONLY")

    assert(AbstractWithCompanion.abstractCompanionFun() === "ABSTRACT_COMPANION_FUN")
    assert(AbstractWithCompanion.abstractCompanionVal === "ABSTRACT_COMPANION_VAL")

    assert(InterfaceWithCompanion.interfaceCompanionFun() === "INTERFACE_COMPANION_FUN")

    assert(VisibilityInCompanion.publicFun() === "PUBLIC_FUN")
    assert(VisibilityInCompanion.publicVal === "PUBLIC_VAL")

    return "OK";
}
