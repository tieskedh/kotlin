// Shapes that stay rejected loudly after generic-interfaces stage 4 (each declaration is skipped
// with a specific warning and absent from the emitted IL; `Gen`, `Constrained`, `WithCompanion`,
// `Marked`, and `main` are the supported remainder):
// - declaration-site variance (`out`/`in`) on classes: ECMA-335 (II.10.1.7) allows variance only
//   on interfaces and delegates — emitting the parameter as invariant would silently change
//   assignability;
// - `===` on `T` operands: an unconstrained `T` may instantiate to a value type with no stable
//   reference identity, and boxing would manufacture two unrelated references;
// - `as`/`is` on generic types: the existing type-operator rejection stays authoritative;
// - reified inline functions: ordinary inlining is supported, but reified operations are not;
// - varargs of `T`: the parameter type is the unsupported projected `Array<out T>` ABI;
// Widening an unconstrained `T` to `Any?`, structural `==`/`== null`, templates, and `toString`
// are supported through CLR `box !!n` plus the System.Object Any foundation. Other unconstrained
// member calls still need a declared bound. The erased callable bridge needs the same general
// object-boundary conversion for an open logical result. Generic extension properties are
// supported as ordinary generic accessor methods; they do not emit a CLR property row.

open class Gen<T>(val v: T)

class Variant<out T>(val v: T)

class Constrained<T : CharSequence>(val v: T)

class WithCompanion<T>(val v: T) {
    companion object
}

interface Marked

fun <T> identity(a: T, b: T): Boolean = a === b

fun <T> widen(a: T): Any? = a

fun castGeneric(x: Any): Gen<String> = x as Gen<String>

inline fun <T> inlined(x: T): T = x

inline fun <reified T> reifiedCheck(x: Any): Boolean = x is T

fun <T> varargsOf(vararg xs: T): Int = 0

val <T> T.mark: Int
    get() = 0

fun main() {
    println(if ("ok".mark == 0) "ok" else "fail")
}
