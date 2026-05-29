declare namespace JS_TESTS {
    type Nullable<T> = T | null | undefined
    function KtSingleton<T>(): T & (abstract new() => any);
    namespace foo {
        function companionExtensionFun(p: string): string;
        class MyClass {
            constructor();
            instanceBar(): string;
            get instanceFoo(): string;
            get instanceMutable(): string;
            set instanceMutable(value: string);
            get instanceBaz(): string;
            static bar(): string;
            static get foo(): string;
            static get mutable(): string;
            static set mutable(value: string);
            static get baz(): string;
        }
        namespace MyClass {
            /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
            namespace $metadata$ {
                const constructor: abstract new () => MyClass;
            }
        }
        class Base {
            constructor();
            static foo(): string;
            static bar(): string;
        }
        namespace Base {
            /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
            namespace $metadata$ {
                const constructor: abstract new () => Base;
            }
        }
        class Child extends foo.Base.$metadata$.constructor {
            constructor();
            static foo(): string;
            static childOnly(): string;
        }
        namespace Child {
            /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
            namespace $metadata$ {
                const constructor: abstract new () => Child;
            }
        }
        abstract class AbstractWithCompanion {
            constructor();
            abstract instanceAbstractFun(): string;
            static abstractCompanionFun(): string;
            static get abstractCompanionVal(): string;
        }
        namespace AbstractWithCompanion {
            /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
            namespace $metadata$ {
                const constructor: abstract new () => AbstractWithCompanion;
            }
        }
        interface InterfaceWithCompanion {
            readonly __doNotUseOrImplementIt: {
                readonly "foo.InterfaceWithCompanion": unique symbol;
            };
        }
        namespace InterfaceWithCompanion {
            function interfaceCompanionFun(): string;
        }
        class VisibilityInCompanion {
            constructor();
            static publicFun(): string;
            static get publicVal(): string;
        }
        namespace VisibilityInCompanion {
            /** @deprecated $metadata$ is used for internal purposes, please don't use it in your code, because it can be removed at any moment */
            namespace $metadata$ {
                const constructor: abstract new () => VisibilityInCompanion;
            }
        }
    }
}


