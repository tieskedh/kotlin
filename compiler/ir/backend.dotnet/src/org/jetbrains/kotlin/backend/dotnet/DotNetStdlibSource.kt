package org.jetbrains.kotlin.backend.dotnet

/**
 * Source texts of a fake Kotlin/.NET standard library that are injected as additional sources
 * during compilation, keyed by file name (one file per package, because a Kotlin file has a
 * single package directive).
 *
 * This is a temporary stand-in until a real .NET stdlib exists: the backend recognizes these
 * declarations and emits the corresponding CIL intrinsics, but the frontend still needs the
 * declarations to resolve against.
 */
val DOTNET_STDLIB_SOURCES: Map<String, String> = mapOf(
    "DotNetStdlibIo.kt" to """@file:Suppress("UNUSED_PARAMETER")
package kotlin.io

public fun println() {}

public fun println(message: String) {}

public fun println(message: Int) {}

public fun println(message: Long) {}

public fun println(message: Double) {}

public fun println(message: Char) {}

public fun println(message: Boolean) {}

public fun println(message: Any?) {}
""",
    "DotNetStdlibCollections.kt" to """package kotlin.collections

// Resolution-only declarations for the first array-operation slices. The backend intercepts every
// call through DotNetIlIntrinsicMethods and excludes these declarations from emitted facades.
// Keeping them external also leaves omitted defaults visible on the original call, so the
// intrinsic can preserve Kotlin's receiver/argument/default-expression evaluation order without
// generating a fake Kotlin implementation into each consumer assembly.

public external fun <T> Array<out T>.copyInto(
    destination: Array<T>,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): Array<T>

public external fun IntArray.copyInto(
    destination: IntArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): IntArray

public external fun LongArray.copyInto(
    destination: LongArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): LongArray

public external fun DoubleArray.copyInto(
    destination: DoubleArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): DoubleArray

public external fun BooleanArray.copyInto(
    destination: BooleanArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): BooleanArray

public external fun CharArray.copyInto(
    destination: CharArray,
    destinationOffset: Int = 0,
    startIndex: Int = 0,
    endIndex: Int = size,
): CharArray

public external fun <T> Array<T>.copyOf(): Array<T>
public external fun IntArray.copyOf(): IntArray
public external fun LongArray.copyOf(): LongArray
public external fun DoubleArray.copyOf(): DoubleArray
public external fun BooleanArray.copyOf(): BooleanArray
public external fun CharArray.copyOf(): CharArray

public external fun IntArray.copyOf(newSize: Int): IntArray
public external fun LongArray.copyOf(newSize: Int): LongArray
public external fun DoubleArray.copyOf(newSize: Int): DoubleArray
public external fun BooleanArray.copyOf(newSize: Int): BooleanArray
public external fun CharArray.copyOf(newSize: Int): CharArray

// Concrete reference substitutions map Array<T?> to the same exact CLR reference vector and are
// supported. An open T? still reaches the owning generic-array gate and fails explicitly.
public external fun <T> Array<T>.copyOf(newSize: Int): Array<T?>

// Shallow content equality follows the common stdlib contract: nullable arrays compare equal when
// both are null, elements use Kotlin equality, and nested arrays keep their identity-based equals.
public external infix fun <T> Array<out T>?.contentEquals(other: Array<out T>?): Boolean
public external infix fun IntArray?.contentEquals(other: IntArray?): Boolean
public external infix fun LongArray?.contentEquals(other: LongArray?): Boolean
public external infix fun DoubleArray?.contentEquals(other: DoubleArray?): Boolean
public external infix fun BooleanArray?.contentEquals(other: BooleanArray?): Boolean
public external infix fun CharArray?.contentEquals(other: CharArray?): Boolean
""",
    "DotNetStdlibKotlin.kt" to """package kotlin

// The real stdlib's `Char.code` is an `@InlineOnly` extension property; it is declared here as
// a plain property because this backend does not run an IR inliner — the getter call reaches
// codegen and is intercepted by the intrinsic registry. The body is never emitted, but it must
// produce no diagnostics: test infrastructure maps every reported diagnostic back to a test
// file, and injected files have none (hence the suppressed `Char.toInt()` deprecation).
public val Char.code: Int
    @Suppress("DEPRECATION")
    get() = this.toInt()

// The built-in exception hierarchy, mirroring the constructor sets of the real stdlib's
// ExceptionsH.kt (kotlin.Throwable itself is a FIR fallback builtin and needs no declaration).
// The full Kotlin subtype hierarchy is declared so frontend catch/assignment subtyping matches
// the real stdlib, but the backend never emits these classes: each concrete class is either
// TYPE-MAPPED onto a CLR exception type or rejected with a per-type reason — see
// DotNetMappedExceptions, which also documents why RuntimeException resolves here and then fails
// loudly at codegen use. Error, NumberFormatException, and NoSuchElementException map to exact
// Kotlin.Runtime types.

public open class Exception : Throwable {
    public constructor() : super()
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}

public open class Error : Throwable {
    public constructor() : super()
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}

public open class RuntimeException : Exception {
    public constructor() : super()
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}

public open class IllegalArgumentException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}

public open class IllegalStateException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}

public open class UnsupportedOperationException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}

public open class NoSuchElementException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class IndexOutOfBoundsException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class ArithmeticException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class NumberFormatException : IllegalArgumentException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class NullPointerException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class ClassCastException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}
""",
)
