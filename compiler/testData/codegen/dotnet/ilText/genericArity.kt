// Generic ARITY is part of CLR method identity (the Roslyn overload rule: `f` and ``f`1`` are
// distinct methods), so the facade identity gate keys on it: a generic `fun <T> pick(x: String)`
// and a plain `fun pick(x: String)` — the same IL name and parameter list — are BOTH emitted as
// legal overloads instead of clash-evicting each other. The class-side coexistence needs no
// gate at all: a generic class's IL name always carries the CLS `` `n `` suffix INSIDE the
// quoted identifier (`'demo.Holder`1'`, genprobe_s2), so it can never collide with a plain
// class occupying the unsuffixed name.

class Holder(val n: Int)

class HolderG<T>(val v: T)

fun pick(x: String): Int = 1

fun <T> pick(x: String): Int = 2

fun main() {
    println(pick("plain"))
    println(pick<Boolean>("generic"))
    println(Holder(1).n)
    println(HolderG<Int>(2).v)
}
