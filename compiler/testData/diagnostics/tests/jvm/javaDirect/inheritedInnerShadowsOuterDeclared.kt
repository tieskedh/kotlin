// RUN_PIPELINE_TILL: FRONTEND

// Three sibling inner classes each declare `make(): Nested`, but resolve the simple name `Nested`
// against a different scope, so we can observe how an *inherited* member type competes with a
// member type *declared* by the lexically-enclosing `Other` (which has its own `Nested`).
//
//   Case 1 (same-file)  : `FromSameFile extends Base` inherits the same-file sibling `Base.Nested`.
//   Case 2 (cross-file) : `FromCrossFile extends a.Outer` inherits the cross-file `Outer.Nested`.
//   Case 3 (enclosing)  : `FromEnclosing` has no supertype `Nested`, so the enclosing `Other.Nested` wins.
//
// Per JLS 6.4.1 an *inherited* member type shadows one merely *declared* by a lexically-enclosing
// class, for BOTH the same-file case 1 and the cross-file case 2 — so `fromBase()`/`fromOuter()`
// resolve, and case 2's `fromOther()` does NOT. java-direct reproduces this PSI Java model.

// FILE: a/Outer.java
package a;

public class Outer {
    public static class Nested {
        public int fromOuter() { return 1; }
    }
}

// FILE: b/Other.java
package b;

import a.Outer;

public class Other {
    public static class Base {
        public static class Nested {
            public int fromBase() { return 1; }
        }
    }

    public static class Nested {
        public int fromOther() { return 2; }
    }

    // Case 1: inherits the same-file sibling Base.Nested (shadows enclosing Other.Nested).
    public static class FromSameFile extends Base {
        public Nested make() { return null; }
    }

    // Case 2: inherits the cross-file Outer.Nested (the divergence case).
    public static class FromCrossFile extends Outer {
        public Nested make() { return null; }
    }

    // Case 3: no inherited Nested, so the enclosing Other.Nested is used.
    public static class FromEnclosing {
        public Nested make() { return null; }
    }
}

// FILE: main.kt
package b

fun case1() = Other.FromSameFile().make().fromBase()

fun case2Outer() = Other.FromCrossFile().make().fromOuter()

fun case2Other() = Other.FromCrossFile().make().<!UNRESOLVED_REFERENCE!>fromOther<!>()

fun case3() = Other.FromEnclosing().make().fromOther()

/* GENERATED_FIR_TAGS: flexibleType, functionDeclaration, javaFunction, javaType */
