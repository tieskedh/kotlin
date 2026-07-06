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
// DotNetMappedExceptions, which also documents why RuntimeException, Error and
// NumberFormatException resolve here and then fail loudly at any codegen use.

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
