// A companion object is a real CLR nested type ('.class nested public' inside the enclosing
// class body) whose singleton field — named after the companion, default 'Companion'; a NAMED
// companion keeps its own name ('Factory') — lives on the ENCLOSING class, typed with the nested
// 'test.C'/'Companion' spelling, and is created by the enclosing class's .cctor (which therefore
// drops beforefieldinit, while the companion itself has no .cctor and keeps it). The companion's
// Kotlin-private constructor is emitted with IL 'assembly' visibility (the CLR grants no
// enclosing->nested private access); the uniform rule covers property ACCESSORS too — the
// private 'secret' pins the '.method assembly hidebysig specialname' getter/setter spelling and
// the enclosing class calls both across the nesting boundary (sum() reads, hide() writes).
// Companion state stays on the companion instance (no JVM-style
// field hoist) and a companion const val is a literal field on the NESTED class. Access works from
// inside the enclosing class by bare name, and from outside via both C.Companion.x and the C.x
// shorthand — every path is an ldsfld of the Companion field on C.
package test

class C {
    fun sum(): Int = base + offset + secret

    fun hide(n: Int) {
        secret = n
    }

    companion object {
        val base = 10
        var offset = 0
        private var secret = 1

        fun bump(): Int {
            offset = offset + 1
            return offset
        }

        const val TAG = "c"
    }
}

class Named {
    companion object Factory {
        fun make(): Int = 7
    }
}

fun main() {
    println(C.Companion.base)
    println(C.base)
    println(C.bump())
    println(C().sum())
    println(C.Companion.TAG)
    println(Named.make())
    println(Named.Factory.make())
}
