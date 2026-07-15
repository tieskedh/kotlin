// Shapes that stay rejected loudly after generic-interfaces stage 4 (each declaration is skipped
// with a specific warning and absent from the emitted IL; `Gen`, `Marked`, and `main` are the
// supported remainder):
// - declaration-site variance (`out`/`in`) on classes: ECMA-335 (II.10.1.7) allows variance only
//   on interfaces and delegates — emitting the parameter as invariant would silently change
//   assignability;
// - constraints outside the supported direct module-local class/interface model
//   (`T : CharSequence` below);
// - `T?` ANYWHERE in a generic declaration (parameter, local): a nullable type parameter has no
//   uniform CLR representation — `T` may instantiate to a value type needing `Nullable<T>` and
//   to a reference type needing nothing — the deferred ABI problem of the hybrid nullability
//   model; the declaration is rejected loudly, never given an ad-hoc representation;
// - `==`/`===` on `T` operands and `x == null` on `T`: an unconstrained `T` may instantiate to
//   a value type where the reference `ceq` is meaningless — no lifted story without constraints;
// - string templates and Any member calls on `T` (`x.toString()` resolves to kotlin.Any, for
//   which this backend has no member model);
// - `as`/`is` on generic types: the existing type-operator rejection stays authoritative;
// - inline generic functions (and with them `reified`): no inlining model;
// - varargs of `T`: the parameter type is the unsupported projected `Array<out T>` ABI;
// - generic classes containing direct companions: the companion field would live on the generic
//   CLR owner and be duplicated per constructed type. Direct named objects are supported because
//   their INSTANCE field lives on the independently non-generic object type;
// - generic (extension) properties: the property metadata/accessor model does not cover generic
//   accessors;
// Widening an unconstrained `T` to `Any?` is now supported through CLR `box !!n`; the erased
// callable bridge needs that same general conversion for an open logical result type.

open class Gen<T>(val v: T)

class Variant<out T>(val v: T)

class Constrained<T : CharSequence>(val v: T)

class WithCompanion<T>(val v: T) {
    companion object
}

interface Marked

fun <T> nullableParam(x: T?): Int = 0

fun <T> nullableLocal(x: T): Int {
    val y: T? = x
    return if (y == null) 0 else 1
}

fun <T> eq(a: T, b: T): Boolean = a == b

fun <T> identity(a: T, b: T): Boolean = a === b

fun <T> eqNull(a: T): Boolean = a == null

fun <T> render(a: T): String = "$a"

fun <T> callMember(a: T): String = a.toString()

fun <T> widen(a: T): Any? = a

fun castGeneric(x: Any): Gen<String> = x as Gen<String>

inline fun <T> inlined(x: T): T = x

inline fun <reified T> reifiedCheck(x: Any): Boolean = x is T

fun <T> varargsOf(vararg xs: T): Int = 0

val <T> T.mark: Int
    get() = 0

fun main() {
    println("ok")
}
