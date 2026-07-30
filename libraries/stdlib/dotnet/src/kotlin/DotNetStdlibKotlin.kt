/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin

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
// loudly at codegen use. Error, NumberFormatException, NoSuchElementException, and the internal
// NoWhenBranchMatchedException map to exact Kotlin.Runtime types.

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

internal open class ExceptionInInitializerError : Error {
    internal constructor(message: String) : super(message)
    internal constructor(cause: Throwable) : super(null, cause)
}

internal open class NoClassDefFoundError : Error {
    internal constructor(message: String?) : super(message)
}

public open class RuntimeException : Exception {
    public constructor() : super()
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}

@Deprecated(
    "This exception type is not supposed to be thrown or caught in common code and will be removed from kotlin-stdlib-common soon.",
    level = DeprecationLevel.ERROR,
)
internal open class NoWhenBranchMatchedException : RuntimeException {
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
