package org.jetbrains.kotlin.backend.dotnet

/**
 * The hand-written IL for compiler support that lives in the Kotlin.Runtime assembly.
 *
 * These members play the role of JVM runtime internals such as `kotlin.jvm.internal.Intrinsics`:
 * generated Kotlin code calls them, but they are not Kotlin source APIs. The CLR type and methods
 * must be metadata-public because their callers live in other assemblies. Keeping them below the
 * reserved `Kotlin.Runtime.Internal` namespace marks the supported boundary accurately: this is a
 * versioned compiler/runtime contract, not a surface for Kotlin or CLR user code.
 */
internal object DotNetRuntimeLibraryHelpers {
    /**
     * `static string DoubleToString(float64)` — Kotlin-parity `Double.toString()`.
     *
     * The JVM target renders a `Double` with `java.lang.Double.toString`: `"1.0"`, `"100.0"`,
     * `"0.0015"`, `"1.0E20"`, `"1.0E-5"`, `"NaN"`, `"Infinity"`, `"-Infinity"`, `"-0.0"`. No CLR
     * formatting API produces that shape directly (`Double.ToString()` honors the current culture
     * and prints `"1"`, `"1E+20"`, `"-Infinity"` as locale symbols, ...), so this helper rebuilds
     * it from the CLR's round-trip format:
     *
     * 1. NaN and the infinities are matched with `Double::IsNaN`/`IsPositiveInfinity`/
     *    `IsNegativeInfinity` and answered with `ldstr` literals.
     * 2. Negative zero is matched by raw bits (`BitConverter::DoubleToInt64Bits == Long.MIN_VALUE`)
     *    and answered with `"-0.0"`: .NET Framework's `"R"` format loses the sign and prints `"0"`
     *    (verified empirically on this runtime).
     * 3. Finite values are formatted with `"R"` + `CultureInfo.InvariantCulture` to obtain the
     *    round-trip digit string, then reshaped into the JVM notation. Crucially, the
     *    decimal-vs-scientific choice does NOT follow .NET's own formatting decision: the JVM
     *    uses plain decimal notation exactly for zero and `1e-3 <= |d| < 1e7` and scientific
     *    notation outside, while .NET Framework's `"R"` stays decimal within `1e-4 <= |d| < 1e15`
     *    (verified: `1e7` -> `"10000000"`, `1e15` -> `"1E+15"`, `1e-4` -> `"0.0001"`). The helper
     *    therefore compares `Math::Abs(value)` against the JVM thresholds itself:
     *    - Decimal notation: the JVM decimal range is strictly inside .NET's, so the `"R"` string
     *      is already plain decimal; a mantissa without `.` gets `".0"` appended (`"1"` -> `"1.0"`).
     *    - Scientific notation, `"R"` string already scientific (`|d| >= 1e15` or `< 1e-4`): the
     *      mantissa gets `".0"` appended when it has no `.` (`"1E+20"` -> `"1.0E20"`), and the
     *      exponent suffix is normalized by parsing it back to an `int32` (`Int32::Parse` accepts
     *      the `"+20"`/`"-05"` forms) and re-rendering it through the invariant culture, which
     *      drops the `+` sign and the leading zeros while keeping `-`.
     *    - Scientific notation, `"R"` string plain decimal (`1e7 <= |d| < 1e15` or
     *      `1e-4 <= |d| < 1e-3`): the digit string is rebuilt as `d1.d2..dnEexp` — sign stripped,
     *      `.` removed, decimal exponent = (integer-digit count - 1) minus one per stripped
     *      leading zero, trailing zeros trimmed (keeping one digit, `"10000000"` -> `"1.0E7"`,
     *      `"0.0001"` -> `"1.0E-4"`, `"31415900000"` -> `"3.14159E10"`), single digits padded
     *      with a `"0"` fraction, and the exponent rendered through the invariant culture.
     *
     * Residual divergences from the JVM rendering, consciously accepted because fixing them means
     * reimplementing `java.lang.Double.toString`'s digit generation in IL:
     *
     * - Digit count. .NET Framework's `"R"` emits 15 or 17 significant digits (never 16), while
     *   the JVM emits the shortest digit string that identifies the value:
     *   `3.1415926535897931` here vs `3.141592653589793` on the JVM, and
     *   `4.94065645841247E-324` vs `4.9E-324` for `Double.MIN_VALUE`.
     * - Round-trip. `"R"` on .NET Framework x64 is documented as occasionally producing text that
     *   parses back to a neighboring double (the reason the default `Any?`-boxing `toString`
     *   deviation used `"G17"` before this helper existed). Kotlin-parity display shape was
     *   deliberately chosen over last-bit textual round-tripping — `println` output is display,
     *   not serialization.
     *
     * Every mscorlib member signature below, the `"R"` renderings, and the exact output of this
     * helper for the values above were verified by assembling and running an ilasm probe on the
     * targeted .NET Framework 4 runtime.
     *
     * Because the runtime rendering (this helper) and the host rendering (`Double.toString` in
     * the compiler process) disagree in the divergence classes above, `Double` constants are
     * excluded from compile-time concatenation folding (see
     * `DotNetFlattenStringConcatenationLowering`) and from the constant fast path of
     * `DotNetIlExpressionCodegen.emitStringValueExpression`: constant and non-constant values of
     * the same `Double` must print identically, so both are routed through this helper.
     */
    /**
     * Besides mutable capture storage and Double formatting, this text owns escaping array
     * iterator storage, the universal Any operations, and the explicit-export delegate
     * projection thunks. Those thunks are called only by generated CLR facade methods; their
     * metadata visibility is cross-assembly compiler/runtime access, not Kotlin callable identity.
     * The iterator deliberately stores a
     * `System.Array` and returns object: Kotlin's logical element type remains compiler metadata,
     * while one erased object preserves identity across Kotlin's legal covariant iterator views.
     * The Any primitive branches are semantic, not optimizations: CLR boxed Boolean hashes/string
     * text, boxed Char hashes, and boxed Double signed-zero/hash/string behavior differ from
     * Kotlin's JVM-backed object contract, and Framework also preserves NaN payloads in
     * Double.GetHashCode.
     */
    val ilText: String = """
            |.namespace Kotlin.Runtime.Internal
            |{
            |  .class public auto ansi sealed beforefieldinit DefaultConstructorMarker
            |         extends [mscorlib]System.Object
            |  {
            |    .method private hidebysig specialname rtspecialname instance void .ctor() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      call instance void [mscorlib]System.Object::.ctor()
            |      ret
            |    }
            |  }
            |
            |  .class interface public abstract auto ansi 'ExactFunction0`1'<+ R>
            |  {
            |    .method public hidebysig newslot abstract virtual instance !0 InvokeExact() cil managed
            |    {
            |    }
            |  }
            |
            |  .class interface public abstract auto ansi 'ExactFunction1`2'<- P0, + R>
            |  {
            |    .method public hidebysig newslot abstract virtual instance !1 InvokeExact(!0 p1) cil managed
            |    {
            |    }
            |  }
            |
            |  .class interface public abstract auto ansi 'ExactFunction2`3'<- P0, - P1, + R>
            |  {
            |    .method public hidebysig newslot abstract virtual instance !2 InvokeExact(!0 p1, !1 p2) cil managed
            |    {
            |    }
            |  }
            |
            |  .class public abstract sealed auto ansi beforefieldinit DelegateProjection
            |         extends [mscorlib]System.Object
            |  {
            |    .method public hidebysig static class [mscorlib]System.Func`1<!!0> ToFunc0<R>(class Kotlin.Function0 callable) cil managed
            |    {
            |      .maxstack 3
            |      .locals init ([0] class Kotlin.Runtime.Internal.'ExactFunction0`1'<!!0> exact)
            |      ldarg.0
            |      isinst class Kotlin.Runtime.Internal.'ExactFunction0`1'<!!0>
            |      stloc.0
            |      ldloc.0
            |      brfalse.s fallback
            |      ldloc.0
            |      ldloc.0
            |      ldvirtftn instance !0 class Kotlin.Runtime.Internal.'ExactFunction0`1'<!!0>::InvokeExact()
            |      newobj instance void class [mscorlib]System.Func`1<!!0>::.ctor(object, native int)
            |      ret
            |    fallback:
            |      ldarg.0
            |      ldftn !!0 Kotlin.Runtime.Internal.DelegateProjection::Invoke0<!!0>(class Kotlin.Function0)
            |      newobj instance void class [mscorlib]System.Func`1<!!0>::.ctor(object, native int)
            |      ret
            |    }
            |
            |    .method public hidebysig static class [mscorlib]System.Func`2<!!0, !!1> ToFunc1<P0, R>(class Kotlin.Function1 callable) cil managed
            |    {
            |      .maxstack 3
            |      .locals init ([0] class Kotlin.Runtime.Internal.'ExactFunction1`2'<!!0, !!1> exact)
            |      ldarg.0
            |      isinst class Kotlin.Runtime.Internal.'ExactFunction1`2'<!!0, !!1>
            |      stloc.0
            |      ldloc.0
            |      brfalse.s fallback
            |      ldloc.0
            |      ldloc.0
            |      ldvirtftn instance !1 class Kotlin.Runtime.Internal.'ExactFunction1`2'<!!0, !!1>::InvokeExact(!0)
            |      newobj instance void class [mscorlib]System.Func`2<!!0, !!1>::.ctor(object, native int)
            |      ret
            |    fallback:
            |      ldarg.0
            |      ldftn !!1 Kotlin.Runtime.Internal.DelegateProjection::Invoke1<!!0, !!1>(class Kotlin.Function1, !!0)
            |      newobj instance void class [mscorlib]System.Func`2<!!0, !!1>::.ctor(object, native int)
            |      ret
            |    }
            |
            |    .method public hidebysig static class [mscorlib]System.Func`3<!!0, !!1, !!2> ToFunc2<P0, P1, R>(class Kotlin.Function2 callable) cil managed
            |    {
            |      .maxstack 3
            |      .locals init ([0] class Kotlin.Runtime.Internal.'ExactFunction2`3'<!!0, !!1, !!2> exact)
            |      ldarg.0
            |      isinst class Kotlin.Runtime.Internal.'ExactFunction2`3'<!!0, !!1, !!2>
            |      stloc.0
            |      ldloc.0
            |      brfalse.s fallback
            |      ldloc.0
            |      ldloc.0
            |      ldvirtftn instance !2 class Kotlin.Runtime.Internal.'ExactFunction2`3'<!!0, !!1, !!2>::InvokeExact(!0, !1)
            |      newobj instance void class [mscorlib]System.Func`3<!!0, !!1, !!2>::.ctor(object, native int)
            |      ret
            |    fallback:
            |      ldarg.0
            |      ldftn !!2 Kotlin.Runtime.Internal.DelegateProjection::Invoke2<!!0, !!1, !!2>(class Kotlin.Function2, !!0, !!1)
            |      newobj instance void class [mscorlib]System.Func`3<!!0, !!1, !!2>::.ctor(object, native int)
            |      ret
            |    }
            |
            |    .method public hidebysig static class [mscorlib]System.Action ToAction0(class Kotlin.Function0 callable) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldftn void Kotlin.Runtime.Internal.DelegateProjection::InvokeUnit0(class Kotlin.Function0)
            |      newobj instance void [mscorlib]System.Action::.ctor(object, native int)
            |      ret
            |    }
            |
            |    .method public hidebysig static class [mscorlib]System.Action`1<!!0> ToAction1<P0>(class Kotlin.Function1 callable) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldftn void Kotlin.Runtime.Internal.DelegateProjection::InvokeUnit1<!!0>(class Kotlin.Function1, !!0)
            |      newobj instance void class [mscorlib]System.Action`1<!!0>::.ctor(object, native int)
            |      ret
            |    }
            |
            |    .method public hidebysig static class [mscorlib]System.Action`2<!!0, !!1> ToAction2<P0, P1>(class Kotlin.Function2 callable) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldftn void Kotlin.Runtime.Internal.DelegateProjection::InvokeUnit2<!!0, !!1>(class Kotlin.Function2, !!0, !!1)
            |      newobj instance void class [mscorlib]System.Action`2<!!0, !!1>::.ctor(object, native int)
            |      ret
            |    }
            |
            |    .method private hidebysig static !!0 Invoke0<R>(class Kotlin.Function0 callable) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      callvirt instance object Kotlin.Function0::Invoke()
            |      unbox.any !!0
            |      ret
            |    }
            |
            |    .method private hidebysig static !!1 Invoke1<P0, R>(class Kotlin.Function1 callable, !!0 p1) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      box !!0
            |      callvirt instance object Kotlin.Function1::Invoke(object)
            |      unbox.any !!1
            |      ret
            |    }
            |
            |    .method private hidebysig static !!2 Invoke2<P0, P1, R>(class Kotlin.Function2 callable, !!0 p1, !!1 p2) cil managed
            |    {
            |      .maxstack 3
            |      ldarg.0
            |      ldarg.1
            |      box !!0
            |      ldarg.2
            |      box !!1
            |      callvirt instance object Kotlin.Function2::Invoke(object, object)
            |      unbox.any !!2
            |      ret
            |    }
            |
            |    .method private hidebysig static void InvokeUnit0(class Kotlin.Function0 callable) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      callvirt instance object Kotlin.Function0::Invoke()
            |      pop
            |      ret
            |    }
            |
            |    .method private hidebysig static void InvokeUnit1<P0>(class Kotlin.Function1 callable, !!0 p1) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      box !!0
            |      callvirt instance object Kotlin.Function1::Invoke(object)
            |      pop
            |      ret
            |    }
            |
            |    .method private hidebysig static void InvokeUnit2<P0, P1>(class Kotlin.Function2 callable, !!0 p1, !!1 p2) cil managed
            |    {
            |      .maxstack 3
            |      ldarg.0
            |      ldarg.1
            |      box !!0
            |      ldarg.2
            |      box !!1
            |      callvirt instance object Kotlin.Function2::Invoke(object, object)
            |      pop
            |      ret
            |    }
            |  }
            |
            |  .class public auto ansi sealed beforefieldinit 'MutableRef`1'<'T'>
            |         extends [mscorlib]System.Object
            |  {
            |    .field public !0 'element'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      call instance void [mscorlib]System.Object::.ctor()
            |      ret
            |    }
            |  }
            |
            |  .class public auto ansi sealed beforefieldinit ArrayIterator
            |         extends [mscorlib]System.Object
            |         implements Kotlin.Collections.Iterator
            |  {
            |    .field private class [mscorlib]System.Array 'array'
            |    .field private int32 'index'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor(class [mscorlib]System.Array 'array') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      call instance void [mscorlib]System.Object::.ctor()
            |      ldarg.0
            |      ldarg.1
            |      stfld class [mscorlib]System.Array Kotlin.Runtime.Internal.ArrayIterator::'array'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance bool HasNext() cil managed
            |    {
            |      .override method instance bool Kotlin.Collections.Iterator::HasNext()
            |      .maxstack 2
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.ArrayIterator::'index'
            |      ldarg.0
            |      ldfld class [mscorlib]System.Array Kotlin.Runtime.Internal.ArrayIterator::'array'
            |      callvirt instance int32 [mscorlib]System.Array::get_Length()
            |      clt
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Next() cil managed
            |    {
            |      .override method instance object Kotlin.Collections.Iterator::Next()
            |      .maxstack 3
            |      .locals init ([0] object 'result')
            |      ldarg.0
            |      call instance bool Kotlin.Runtime.Internal.ArrayIterator::HasNext()
            |      brtrue.s IL_hasElement
            |      newobj instance void Kotlin.NoSuchElementException::.ctor()
            |      throw
            |IL_hasElement:
            |      ldarg.0
            |      ldfld class [mscorlib]System.Array Kotlin.Runtime.Internal.ArrayIterator::'array'
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.ArrayIterator::'index'
            |      callvirt instance object [mscorlib]System.Array::GetValue(int32)
            |      stloc.0
            |      ldarg.0
            |      dup
            |      ldfld int32 Kotlin.Runtime.Internal.ArrayIterator::'index'
            |      ldc.i4.1
            |      add
            |      stfld int32 Kotlin.Runtime.Internal.ArrayIterator::'index'
            |      ldloc.0
            |      ret
            |    }
            |  }
            |
            |  .class public abstract sealed auto ansi beforefieldinit Intrinsics
            |         extends [mscorlib]System.Object
            |  {
            |    .method public hidebysig static bool 'AreEqual'(object, object) cil managed
            |    {
            |      .maxstack 2
            |      .locals init (
            |        [0] float64 'leftDouble',
            |        [1] float64 'rightDouble'
            |      )
            |      ldarg.0
            |      brtrue.s IL_leftNotNull
            |      ldarg.1
            |      ldnull
            |      ceq
            |      ret
            |IL_leftNotNull:
            |      ldarg.0
            |      isinst [mscorlib]System.Double
            |      brfalse.s IL_objectEquals
            |      ldarg.1
            |      isinst [mscorlib]System.Double
            |      brfalse.s IL_notEqual
            |      ldarg.0
            |      unbox.any [mscorlib]System.Double
            |      stloc.0
            |      ldarg.1
            |      unbox.any [mscorlib]System.Double
            |      stloc.1
            |      ldloc.0
            |      call int64 'Kotlin.Runtime.Internal.Intrinsics'::'DoubleToLongBits'(float64)
            |      ldloc.1
            |      call int64 'Kotlin.Runtime.Internal.Intrinsics'::'DoubleToLongBits'(float64)
            |      ceq
            |      ret
            |IL_notEqual:
            |      ldc.i4.0
            |      ret
            |IL_objectEquals:
            |      ldarg.0
            |      ldarg.1
            |      callvirt instance bool [mscorlib]System.Object::Equals(object)
            |      ret
            |    }
            |
            |    .method public hidebysig static int32 'HashCode'(object) cil managed
            |    {
            |      .maxstack 3
            |      .locals init ([0] int64 'bits')
            |      ldarg.0
            |      brtrue.s IL_hashNotNull
            |      ldc.i4.0
            |      ret
            |IL_hashNotNull:
            |      ldarg.0
            |      isinst [mscorlib]System.Double
            |      brfalse.s IL_hashBoolean
            |      ldarg.0
            |      unbox.any [mscorlib]System.Double
            |      call int64 'Kotlin.Runtime.Internal.Intrinsics'::'DoubleToLongBits'(float64)
            |      stloc.0
            |      ldloc.0
            |      conv.i4
            |      ldloc.0
            |      ldc.i4.s 32
            |      shr.un
            |      conv.i4
            |      xor
            |      ret
            |IL_hashBoolean:
            |      ldarg.0
            |      isinst [mscorlib]System.Boolean
            |      brfalse.s IL_hashChar
            |      ldarg.0
            |      unbox.any [mscorlib]System.Boolean
            |      brtrue.s IL_hashTrue
            |      ldc.i4 1237
            |      ret
            |IL_hashTrue:
            |      ldc.i4 1231
            |      ret
            |IL_hashChar:
            |      ldarg.0
            |      isinst [mscorlib]System.Char
            |      brfalse.s IL_objectHash
            |      ldarg.0
            |      unbox.any [mscorlib]System.Char
            |      conv.i4
            |      ret
            |IL_objectHash:
            |      ldarg.0
            |      callvirt instance int32 [mscorlib]System.Object::GetHashCode()
            |      ret
            |    }
            |
            |    .method public hidebysig static string 'StringValueOf'(object) cil managed
            |    {
            |      .maxstack 3
            |      ldarg.0
            |      brtrue.s IL_valueNotNull
            |      ldstr "null"
            |      ret
            |IL_valueNotNull:
            |      ldarg.0
            |      isinst [mscorlib]System.Boolean
            |      brfalse.s IL_stringDouble
            |      ldarg.0
            |      unbox.any [mscorlib]System.Boolean
            |      brtrue.s IL_stringTrue
            |      ldstr "false"
            |      ret
            |IL_stringTrue:
            |      ldstr "true"
            |      ret
            |IL_stringDouble:
            |      ldarg.0
            |      isinst [mscorlib]System.Double
            |      brfalse.s IL_stringInt
            |      ldarg.0
            |      unbox.any [mscorlib]System.Double
            |      call string 'Kotlin.Runtime.Internal.DoubleFormatting'::'DoubleToString'(float64)
            |      ret
            |IL_stringInt:
            |      ldarg.0
            |      isinst [mscorlib]System.Int32
            |      brtrue.s IL_stringInvariant
            |      ldarg.0
            |      isinst [mscorlib]System.Int64
            |      brtrue.s IL_stringInvariant
            |      ldarg.0
            |      callvirt instance string [mscorlib]System.Object::ToString()
            |      ret
            |IL_stringInvariant:
            |      ldarg.0
            |      ldnull
            |      call class [mscorlib]System.Globalization.CultureInfo [mscorlib]System.Globalization.CultureInfo::get_InvariantCulture()
            |      callvirt instance string [mscorlib]System.IFormattable::ToString(string, class [mscorlib]System.IFormatProvider)
            |      ret
            |    }
            |
            |    .method public hidebysig static bool 'ArrayContentEquals'(
            |        class [mscorlib]System.Array 'left',
            |        class [mscorlib]System.Array 'right') cil managed
            |    {
            |      .maxstack 3
            |      .locals init (
            |        [0] int32 'index',
            |        [1] int32 'length'
            |      )
            |      ldarg.0
            |      ldarg.1
            |      ceq
            |      brfalse.s IL_arrayContentNotSame
            |      ldc.i4.1
            |      ret
            |IL_arrayContentNotSame:
            |      ldarg.0
            |      brfalse.s IL_arrayContentFalse
            |      ldarg.1
            |      brfalse.s IL_arrayContentFalse
            |      ldarg.0
            |      callvirt instance int32 [mscorlib]System.Array::get_Length()
            |      stloc.1
            |      ldloc.1
            |      ldarg.1
            |      callvirt instance int32 [mscorlib]System.Array::get_Length()
            |      bne.un.s IL_arrayContentFalse
            |      ldc.i4.0
            |      stloc.0
            |IL_arrayContentLoop:
            |      ldloc.0
            |      ldloc.1
            |      bge.s IL_arrayContentTrue
            |      ldarg.0
            |      ldloc.0
            |      callvirt instance object [mscorlib]System.Array::GetValue(int32)
            |      ldarg.1
            |      ldloc.0
            |      callvirt instance object [mscorlib]System.Array::GetValue(int32)
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'AreEqual'(object, object)
            |      brfalse.s IL_arrayContentFalse
            |      ldloc.0
            |      ldc.i4.1
            |      add
            |      stloc.0
            |      br.s IL_arrayContentLoop
            |IL_arrayContentTrue:
            |      ldc.i4.1
            |      ret
            |IL_arrayContentFalse:
            |      ldc.i4.0
            |      ret
            |    }
            |
            |    .method public hidebysig static bool 'ArrayContentDeepEquals'(
            |        class [mscorlib]System.Array 'left',
            |        class [mscorlib]System.Array 'right') cil managed
            |    {
            |      .maxstack 3
            |      .locals init (
            |        [0] int32 'index',
            |        [1] int32 'length',
            |        [2] object 'leftValue',
            |        [3] object 'rightValue'
            |      )
            |      ldarg.0
            |      ldarg.1
            |      ceq
            |      brfalse IL_arrayDeepNotSame
            |      ldc.i4.1
            |      ret
            |IL_arrayDeepNotSame:
            |      ldarg.0
            |      brfalse IL_arrayDeepFalse
            |      ldarg.1
            |      brfalse IL_arrayDeepFalse
            |      ldarg.0
            |      callvirt instance int32 [mscorlib]System.Array::get_Length()
            |      stloc.1
            |      ldloc.1
            |      ldarg.1
            |      callvirt instance int32 [mscorlib]System.Array::get_Length()
            |      bne.un IL_arrayDeepFalse
            |      ldc.i4.0
            |      stloc.0
            |IL_arrayDeepLoop:
            |      ldloc.0
            |      ldloc.1
            |      bge IL_arrayDeepTrue
            |      ldarg.0
            |      ldloc.0
            |      callvirt instance object [mscorlib]System.Array::GetValue(int32)
            |      stloc.2
            |      ldarg.1
            |      ldloc.0
            |      callvirt instance object [mscorlib]System.Array::GetValue(int32)
            |      stloc.3
            |      ldloc.2
            |      ldloc.3
            |      ceq
            |      brtrue IL_arrayDeepNext
            |      ldloc.2
            |      brfalse IL_arrayDeepFalse
            |      ldloc.3
            |      brfalse IL_arrayDeepFalse
            |
            |      ldloc.2
            |      isinst object[]
            |      brfalse IL_arrayDeepInt
            |      ldloc.2
            |      isinst object[]
            |      ldloc.3
            |      isinst object[]
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentDeepEquals'(
            |          class [mscorlib]System.Array, class [mscorlib]System.Array)
            |      brfalse IL_arrayDeepFalse
            |      br IL_arrayDeepNext
            |IL_arrayDeepInt:
            |      ldloc.2
            |      isinst int32[]
            |      brfalse IL_arrayDeepLong
            |      ldloc.2
            |      isinst int32[]
            |      ldloc.3
            |      isinst int32[]
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentEquals'(
            |          class [mscorlib]System.Array, class [mscorlib]System.Array)
            |      brfalse IL_arrayDeepFalse
            |      br IL_arrayDeepNext
            |IL_arrayDeepLong:
            |      ldloc.2
            |      isinst int64[]
            |      brfalse IL_arrayDeepDouble
            |      ldloc.2
            |      isinst int64[]
            |      ldloc.3
            |      isinst int64[]
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentEquals'(
            |          class [mscorlib]System.Array, class [mscorlib]System.Array)
            |      brfalse IL_arrayDeepFalse
            |      br IL_arrayDeepNext
            |IL_arrayDeepDouble:
            |      ldloc.2
            |      isinst float64[]
            |      brfalse IL_arrayDeepBoolean
            |      ldloc.2
            |      isinst float64[]
            |      ldloc.3
            |      isinst float64[]
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentEquals'(
            |          class [mscorlib]System.Array, class [mscorlib]System.Array)
            |      brfalse IL_arrayDeepFalse
            |      br IL_arrayDeepNext
            |IL_arrayDeepBoolean:
            |      ldloc.2
            |      isinst bool[]
            |      brfalse IL_arrayDeepChar
            |      ldloc.2
            |      isinst bool[]
            |      ldloc.3
            |      isinst bool[]
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentEquals'(
            |          class [mscorlib]System.Array, class [mscorlib]System.Array)
            |      brfalse IL_arrayDeepFalse
            |      br IL_arrayDeepNext
            |IL_arrayDeepChar:
            |      ldloc.2
            |      isinst char[]
            |      brfalse IL_arrayDeepScalar
            |      ldloc.2
            |      isinst char[]
            |      ldloc.3
            |      isinst char[]
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentEquals'(
            |          class [mscorlib]System.Array, class [mscorlib]System.Array)
            |      brfalse IL_arrayDeepFalse
            |      br IL_arrayDeepNext
            |IL_arrayDeepScalar:
            |      ldloc.2
            |      ldloc.3
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'AreEqual'(object, object)
            |      brfalse IL_arrayDeepFalse
            |IL_arrayDeepNext:
            |      ldloc.0
            |      ldc.i4.1
            |      add
            |      stloc.0
            |      br IL_arrayDeepLoop
            |IL_arrayDeepTrue:
            |      ldc.i4.1
            |      ret
            |IL_arrayDeepFalse:
            |      ldc.i4.0
            |      ret
            |    }
            |
            |    .method public hidebysig static int32 'ArrayContentHashCode'(class [mscorlib]System.Array 'value') cil managed
            |    {
            |      .maxstack 3
            |      .locals init (
            |        [0] int32 'result',
            |        [1] int32 'index',
            |        [2] int32 'length'
            |      )
            |      ldarg.0
            |      brtrue.s IL_arrayHashNotNull
            |      ldc.i4.0
            |      ret
            |IL_arrayHashNotNull:
            |      ldc.i4.1
            |      stloc.0
            |      ldc.i4.0
            |      stloc.1
            |      ldarg.0
            |      callvirt instance int32 [mscorlib]System.Array::get_Length()
            |      stloc.2
            |IL_arrayHashLoop:
            |      ldloc.1
            |      ldloc.2
            |      clt
            |      brfalse.s IL_arrayHashEnd
            |      ldloc.0
            |      ldc.i4.s 31
            |      mul
            |      ldarg.0
            |      ldloc.1
            |      callvirt instance object [mscorlib]System.Array::GetValue(int32)
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'HashCode'(object)
            |      add
            |      stloc.0
            |      ldloc.1
            |      ldc.i4.1
            |      add
            |      stloc.1
            |      br.s IL_arrayHashLoop
            |IL_arrayHashEnd:
            |      ldloc.0
            |      ret
            |    }
            |
            |    .method public hidebysig static int32 'ArrayContentDeepHashCode'(
            |        class [mscorlib]System.Array 'value') cil managed
            |    {
            |      .maxstack 3
            |      .locals init (
            |        [0] int32 'result',
            |        [1] int32 'index',
            |        [2] int32 'length',
            |        [3] object 'element',
            |        [4] int32 'elementHash'
            |      )
            |      ldarg.0
            |      brtrue.s IL_arrayDeepHashNotNull
            |      ldc.i4.0
            |      ret
            |IL_arrayDeepHashNotNull:
            |      ldc.i4.1
            |      stloc.0
            |      ldc.i4.0
            |      stloc.1
            |      ldarg.0
            |      callvirt instance int32 [mscorlib]System.Array::get_Length()
            |      stloc.2
            |IL_arrayDeepHashLoop:
            |      ldloc.1
            |      ldloc.2
            |      bge IL_arrayDeepHashEnd
            |      ldarg.0
            |      ldloc.1
            |      callvirt instance object [mscorlib]System.Array::GetValue(int32)
            |      stloc.3
            |      ldloc.3
            |      isinst object[]
            |      brfalse IL_arrayDeepHashInt
            |      ldloc.3
            |      isinst object[]
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentDeepHashCode'(
            |          class [mscorlib]System.Array)
            |      stloc.s 4
            |      br IL_arrayDeepHashCombine
            |IL_arrayDeepHashInt:
            |      ldloc.3
            |      isinst int32[]
            |      brfalse IL_arrayDeepHashLong
            |      ldloc.3
            |      isinst int32[]
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentHashCode'(
            |          class [mscorlib]System.Array)
            |      stloc.s 4
            |      br IL_arrayDeepHashCombine
            |IL_arrayDeepHashLong:
            |      ldloc.3
            |      isinst int64[]
            |      brfalse IL_arrayDeepHashDouble
            |      ldloc.3
            |      isinst int64[]
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentHashCode'(
            |          class [mscorlib]System.Array)
            |      stloc.s 4
            |      br IL_arrayDeepHashCombine
            |IL_arrayDeepHashDouble:
            |      ldloc.3
            |      isinst float64[]
            |      brfalse IL_arrayDeepHashBoolean
            |      ldloc.3
            |      isinst float64[]
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentHashCode'(
            |          class [mscorlib]System.Array)
            |      stloc.s 4
            |      br IL_arrayDeepHashCombine
            |IL_arrayDeepHashBoolean:
            |      ldloc.3
            |      isinst bool[]
            |      brfalse IL_arrayDeepHashChar
            |      ldloc.3
            |      isinst bool[]
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentHashCode'(
            |          class [mscorlib]System.Array)
            |      stloc.s 4
            |      br IL_arrayDeepHashCombine
            |IL_arrayDeepHashChar:
            |      ldloc.3
            |      isinst char[]
            |      brfalse IL_arrayDeepHashScalar
            |      ldloc.3
            |      isinst char[]
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentHashCode'(
            |          class [mscorlib]System.Array)
            |      stloc.s 4
            |      br IL_arrayDeepHashCombine
            |IL_arrayDeepHashScalar:
            |      ldloc.3
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'HashCode'(object)
            |      stloc.s 4
            |IL_arrayDeepHashCombine:
            |      ldloc.0
            |      ldc.i4.s 31
            |      mul
            |      ldloc.s 4
            |      add
            |      stloc.0
            |      ldloc.1
            |      ldc.i4.1
            |      add
            |      stloc.1
            |      br IL_arrayDeepHashLoop
            |IL_arrayDeepHashEnd:
            |      ldloc.0
            |      ret
            |    }
            |
            |    .method public hidebysig static int32 'DataClassArrayHashCode'(
            |        class [mscorlib]System.Array 'value') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentHashCode'(
            |          class [mscorlib]System.Array)
            |      ret
            |    }
            |
            |    .method public hidebysig static string 'ArrayContentToString'(class [mscorlib]System.Array 'value') cil managed
            |    {
            |      .maxstack 3
            |      .locals init (
            |        [0] class [mscorlib]System.Text.StringBuilder 'builder',
            |        [1] int32 'index',
            |        [2] int32 'length'
            |      )
            |      ldarg.0
            |      brtrue.s IL_arrayStringNotNull
            |      ldstr "null"
            |      ret
            |IL_arrayStringNotNull:
            |      newobj instance void [mscorlib]System.Text.StringBuilder::.ctor()
            |      stloc.0
            |      ldloc.0
            |      ldstr "["
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |      ldc.i4.0
            |      stloc.1
            |      ldarg.0
            |      callvirt instance int32 [mscorlib]System.Array::get_Length()
            |      stloc.2
            |IL_arrayStringLoop:
            |      ldloc.1
            |      ldloc.2
            |      clt
            |      brfalse.s IL_arrayStringEnd
            |      ldloc.1
            |      brfalse.s IL_arrayStringElement
            |      ldloc.0
            |      ldstr ", "
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |IL_arrayStringElement:
            |      ldloc.0
            |      ldarg.0
            |      ldloc.1
            |      callvirt instance object [mscorlib]System.Array::GetValue(int32)
            |      call string 'Kotlin.Runtime.Internal.Intrinsics'::'StringValueOf'(object)
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |      ldloc.1
            |      ldc.i4.1
            |      add
            |      stloc.1
            |      br.s IL_arrayStringLoop
            |IL_arrayStringEnd:
            |      ldloc.0
            |      ldstr "]"
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |      ldloc.0
            |      callvirt instance string [mscorlib]System.Object::ToString()
            |      ret
            |    }
            |
            |    .method private hidebysig static void 'AppendArrayContentDeepToString'(
            |        class [mscorlib]System.Array 'value',
            |        class [mscorlib]System.Text.StringBuilder 'builder',
            |        class [mscorlib]System.Collections.ArrayList 'processed') cil managed
            |    {
            |      .maxstack 4
            |      .locals init (
            |        [0] int32 'index',
            |        [1] int32 'length',
            |        [2] object 'element'
            |      )
            |      ldarg.2
            |      ldarg.0
            |      callvirt instance bool [mscorlib]System.Collections.ArrayList::Contains(object)
            |      brfalse IL_arrayDeepStringNew
            |      ldarg.1
            |      ldstr "[...]"
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |      ret
            |IL_arrayDeepStringNew:
            |      ldarg.2
            |      ldarg.0
            |      callvirt instance int32 [mscorlib]System.Collections.ArrayList::Add(object)
            |      pop
            |      ldarg.1
            |      ldstr "["
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |      ldc.i4.0
            |      stloc.0
            |      ldarg.0
            |      callvirt instance int32 [mscorlib]System.Array::get_Length()
            |      stloc.1
            |IL_arrayDeepStringLoop:
            |      ldloc.0
            |      ldloc.1
            |      bge IL_arrayDeepStringEnd
            |      ldloc.0
            |      brfalse IL_arrayDeepStringLoad
            |      ldarg.1
            |      ldstr ", "
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |IL_arrayDeepStringLoad:
            |      ldarg.0
            |      ldloc.0
            |      callvirt instance object [mscorlib]System.Array::GetValue(int32)
            |      stloc.2
            |      ldloc.2
            |      isinst object[]
            |      brfalse IL_arrayDeepStringInt
            |      ldloc.2
            |      isinst object[]
            |      ldarg.1
            |      ldarg.2
            |      call void 'Kotlin.Runtime.Internal.Intrinsics'::'AppendArrayContentDeepToString'(
            |          class [mscorlib]System.Array,
            |          class [mscorlib]System.Text.StringBuilder,
            |          class [mscorlib]System.Collections.ArrayList)
            |      br IL_arrayDeepStringNext
            |IL_arrayDeepStringInt:
            |      ldloc.2
            |      isinst int32[]
            |      brfalse IL_arrayDeepStringLong
            |      ldarg.1
            |      ldloc.2
            |      isinst int32[]
            |      call string 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentToString'(
            |          class [mscorlib]System.Array)
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |      br IL_arrayDeepStringNext
            |IL_arrayDeepStringLong:
            |      ldloc.2
            |      isinst int64[]
            |      brfalse IL_arrayDeepStringDouble
            |      ldarg.1
            |      ldloc.2
            |      isinst int64[]
            |      call string 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentToString'(
            |          class [mscorlib]System.Array)
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |      br IL_arrayDeepStringNext
            |IL_arrayDeepStringDouble:
            |      ldloc.2
            |      isinst float64[]
            |      brfalse IL_arrayDeepStringBoolean
            |      ldarg.1
            |      ldloc.2
            |      isinst float64[]
            |      call string 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentToString'(
            |          class [mscorlib]System.Array)
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |      br IL_arrayDeepStringNext
            |IL_arrayDeepStringBoolean:
            |      ldloc.2
            |      isinst bool[]
            |      brfalse IL_arrayDeepStringChar
            |      ldarg.1
            |      ldloc.2
            |      isinst bool[]
            |      call string 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentToString'(
            |          class [mscorlib]System.Array)
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |      br IL_arrayDeepStringNext
            |IL_arrayDeepStringChar:
            |      ldloc.2
            |      isinst char[]
            |      brfalse IL_arrayDeepStringScalar
            |      ldarg.1
            |      ldloc.2
            |      isinst char[]
            |      call string 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentToString'(
            |          class [mscorlib]System.Array)
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |      br IL_arrayDeepStringNext
            |IL_arrayDeepStringScalar:
            |      ldarg.1
            |      ldloc.2
            |      call string 'Kotlin.Runtime.Internal.Intrinsics'::'StringValueOf'(object)
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |IL_arrayDeepStringNext:
            |      ldloc.0
            |      ldc.i4.1
            |      add
            |      stloc.0
            |      br IL_arrayDeepStringLoop
            |IL_arrayDeepStringEnd:
            |      ldarg.1
            |      ldstr "]"
            |      callvirt instance class [mscorlib]System.Text.StringBuilder [mscorlib]System.Text.StringBuilder::Append(string)
            |      pop
            |      ldarg.2
            |      ldarg.2
            |      callvirt instance int32 [mscorlib]System.Collections.ArrayList::get_Count()
            |      ldc.i4.1
            |      sub
            |      callvirt instance void [mscorlib]System.Collections.ArrayList::RemoveAt(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig static string 'ArrayContentDeepToString'(
            |        class [mscorlib]System.Array 'value') cil managed
            |    {
            |      .maxstack 3
            |      .locals init (
            |        [0] class [mscorlib]System.Text.StringBuilder 'builder',
            |        [1] class [mscorlib]System.Collections.ArrayList 'processed'
            |      )
            |      ldarg.0
            |      brtrue IL_arrayDeepStringNotNull
            |      ldstr "null"
            |      ret
            |IL_arrayDeepStringNotNull:
            |      newobj instance void [mscorlib]System.Text.StringBuilder::.ctor()
            |      stloc.0
            |      newobj instance void [mscorlib]System.Collections.ArrayList::.ctor()
            |      stloc.1
            |      ldarg.0
            |      ldloc.0
            |      ldloc.1
            |      call void 'Kotlin.Runtime.Internal.Intrinsics'::'AppendArrayContentDeepToString'(
            |          class [mscorlib]System.Array,
            |          class [mscorlib]System.Text.StringBuilder,
            |          class [mscorlib]System.Collections.ArrayList)
            |      ldloc.0
            |      callvirt instance string [mscorlib]System.Object::ToString()
            |      ret
            |    }
            |
            |    .method public hidebysig static string 'DataClassArrayToString'(
            |        class [mscorlib]System.Array 'value') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      call string 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentToString'(
            |          class [mscorlib]System.Array)
            |      ret
            |    }
            |
            |    .method public hidebysig static void 'ArrayCopyInto'(
            |        class [mscorlib]System.Array 'source',
            |        class [mscorlib]System.Array 'destination',
            |        int32 'destinationOffset',
            |        int32 'startIndex',
            |        int32 'endIndex') cil managed
            |    {
            |      .maxstack 6
            |      ldarg.3
            |      ldc.i4.0
            |      blt.s IL_arrayCopyInvalid
            |      ldarg.s endIndex
            |      ldarg.3
            |      blt.s IL_arrayCopyInvalid
            |      ldarg.s endIndex
            |      ldarg.0
            |      callvirt instance int32 [mscorlib]System.Array::get_Length()
            |      bgt.s IL_arrayCopyInvalid
            |      ldarg.2
            |      ldc.i4.0
            |      blt.s IL_arrayCopyInvalid
            |      ldarg.2
            |      ldarg.1
            |      callvirt instance int32 [mscorlib]System.Array::get_Length()
            |      ldarg.s endIndex
            |      ldarg.3
            |      sub
            |      sub
            |      bgt.s IL_arrayCopyInvalid
            |      ldarg.0
            |      ldarg.3
            |      ldarg.1
            |      ldarg.2
            |      ldarg.s endIndex
            |      ldarg.3
            |      sub
            |      call void [mscorlib]System.Array::Copy(
            |          class [mscorlib]System.Array,
            |          int32,
            |          class [mscorlib]System.Array,
            |          int32,
            |          int32)
            |      ret
            |IL_arrayCopyInvalid:
            |      newobj instance void [mscorlib]System.IndexOutOfRangeException::.ctor()
            |      throw
            |    }
            |
            |    .method private hidebysig static int64 'DoubleToLongBits'(float64) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.0
            |      ceq
            |      brtrue.s IL_bitsNotNaN
            |      ldc.i8 0x7ff8000000000000
            |      ret
            |IL_bitsNotNaN:
            |      ldarg.0
            |      call int64 [mscorlib]System.BitConverter::DoubleToInt64Bits(float64)
            |      ret
            |    }
            |  }
            |
            |  .class public abstract sealed auto ansi beforefieldinit DoubleFormatting
            |         extends [mscorlib]System.Object
            |  {
            |  .method public hidebysig static string 'DoubleToString'(float64 'value') cil managed
            |  {
            |    .maxstack 5
            |    .locals init (
            |      [0] string 's',
            |      [1] int32 'e',
            |      [2] string 'digits',
            |      [3] int32 'decExp',
            |      [4] bool 'neg',
            |      [5] int32 'i'
            |    )
            |    ldarg.0
            |    call bool ${CORE_LIB_REF}System.Double::IsNaN(float64)
            |    brfalse IL_notNaN
            |    ldstr "NaN"
            |    ret
            |IL_notNaN:
            |    ldarg.0
            |    call bool ${CORE_LIB_REF}System.Double::IsPositiveInfinity(float64)
            |    brfalse IL_notPositiveInfinity
            |    ldstr "Infinity"
            |    ret
            |IL_notPositiveInfinity:
            |    ldarg.0
            |    call bool ${CORE_LIB_REF}System.Double::IsNegativeInfinity(float64)
            |    brfalse IL_notNegativeInfinity
            |    ldstr "-Infinity"
            |    ret
            |IL_notNegativeInfinity:
            |    ldarg.0
            |    call int64 ${CORE_LIB_REF}System.BitConverter::DoubleToInt64Bits(float64)
            |    ldc.i8 -9223372036854775808
            |    bne.un IL_finite
            |    ldstr "-0.0"
            |    ret
            |IL_finite:
            |    ldarga.s 0
            |    ldstr "R"
            |    call class ${CORE_LIB_REF}System.Globalization.CultureInfo ${CORE_LIB_REF}System.Globalization.CultureInfo::get_InvariantCulture()
            |    call instance string ${CORE_LIB_REF}System.Double::ToString(string, class ${CORE_LIB_REF}System.IFormatProvider)
            |    stloc.0
            |    ldarg.0
            |    ldc.r8 0.0
            |    beq IL_decimal
            |    ldarg.0
            |    call float64 ${CORE_LIB_REF}System.Math::Abs(float64)
            |    ldc.r8 10000000.
            |    bge IL_scientific
            |    ldarg.0
            |    call float64 ${CORE_LIB_REF}System.Math::Abs(float64)
            |    ldc.r8 0.001
            |    blt IL_scientific
            |IL_decimal:
            |    ldloc.0
            |    ldc.i4.s 46
            |    callvirt instance int32 ${CORE_LIB_REF}System.String::IndexOf(char)
            |    ldc.i4.0
            |    bge IL_decimalHasDot
            |    ldloc.0
            |    ldstr ".0"
            |    call string ${CORE_LIB_REF}System.String::Concat(string, string)
            |    ret
            |IL_decimalHasDot:
            |    ldloc.0
            |    ret
            |IL_scientific:
            |    ldloc.0
            |    ldc.i4.s 69
            |    callvirt instance int32 ${CORE_LIB_REF}System.String::IndexOf(char)
            |    stloc.1
            |    ldloc.1
            |    ldc.i4.0
            |    bge IL_scientificFromE
            |    ldloc.0
            |    ldc.i4.0
            |    callvirt instance char ${CORE_LIB_REF}System.String::get_Chars(int32)
            |    ldc.i4.s 45
            |    ceq
            |    stloc.s 'neg'
            |    ldloc.s 'neg'
            |    brfalse IL_signStripped
            |    ldloc.0
            |    ldc.i4.1
            |    callvirt instance string ${CORE_LIB_REF}System.String::Substring(int32)
            |    stloc.0
            |IL_signStripped:
            |    ldloc.0
            |    ldc.i4.s 46
            |    callvirt instance int32 ${CORE_LIB_REF}System.String::IndexOf(char)
            |    stloc.1
            |    ldloc.1
            |    ldc.i4.0
            |    bge IL_removeDot
            |    ldloc.0
            |    stloc.2
            |    ldloc.0
            |    callvirt instance int32 ${CORE_LIB_REF}System.String::get_Length()
            |    stloc.3
            |    br IL_dotRemoved
            |IL_removeDot:
            |    ldloc.0
            |    ldloc.1
            |    ldc.i4.1
            |    callvirt instance string ${CORE_LIB_REF}System.String::Remove(int32, int32)
            |    stloc.2
            |    ldloc.1
            |    stloc.3
            |IL_dotRemoved:
            |    ldloc.3
            |    ldc.i4.1
            |    sub
            |    stloc.3
            |    ldc.i4.0
            |    stloc.s 'i'
            |IL_leadingZeroLoop:
            |    ldloc.2
            |    ldloc.s 'i'
            |    callvirt instance char ${CORE_LIB_REF}System.String::get_Chars(int32)
            |    ldc.i4.s 48
            |    bne.un IL_leadingZerosSkipped
            |    ldloc.s 'i'
            |    ldc.i4.1
            |    add
            |    stloc.s 'i'
            |    ldloc.3
            |    ldc.i4.1
            |    sub
            |    stloc.3
            |    br IL_leadingZeroLoop
            |IL_leadingZerosSkipped:
            |    ldloc.2
            |    ldloc.s 'i'
            |    callvirt instance string ${CORE_LIB_REF}System.String::Substring(int32)
            |    stloc.2
            |    ldloc.2
            |    callvirt instance int32 ${CORE_LIB_REF}System.String::get_Length()
            |    stloc.s 'i'
            |IL_trailingZeroLoop:
            |    ldloc.s 'i'
            |    ldc.i4.1
            |    ble IL_trailingZerosTrimmed
            |    ldloc.2
            |    ldloc.s 'i'
            |    ldc.i4.1
            |    sub
            |    callvirt instance char ${CORE_LIB_REF}System.String::get_Chars(int32)
            |    ldc.i4.s 48
            |    bne.un IL_trailingZerosTrimmed
            |    ldloc.s 'i'
            |    ldc.i4.1
            |    sub
            |    stloc.s 'i'
            |    br IL_trailingZeroLoop
            |IL_trailingZerosTrimmed:
            |    ldloc.2
            |    ldc.i4.0
            |    ldloc.s 'i'
            |    callvirt instance string ${CORE_LIB_REF}System.String::Substring(int32, int32)
            |    stloc.2
            |    ldloc.2
            |    callvirt instance int32 ${CORE_LIB_REF}System.String::get_Length()
            |    ldc.i4.1
            |    bne.un IL_insertDot
            |    ldloc.2
            |    ldstr "0"
            |    call string ${CORE_LIB_REF}System.String::Concat(string, string)
            |    stloc.2
            |IL_insertDot:
            |    ldloc.2
            |    ldc.i4.1
            |    ldstr "."
            |    callvirt instance string ${CORE_LIB_REF}System.String::Insert(int32, string)
            |    stloc.2
            |    ldloc.s 'neg'
            |    brfalse IL_mantissaSigned
            |    ldstr "-"
            |    ldloc.2
            |    call string ${CORE_LIB_REF}System.String::Concat(string, string)
            |    stloc.2
            |IL_mantissaSigned:
            |    ldloc.2
            |    ldstr "E"
            |    ldloc.3
            |    box ${CORE_LIB_REF}System.Int32
            |    ldnull
            |    call class ${CORE_LIB_REF}System.Globalization.CultureInfo ${CORE_LIB_REF}System.Globalization.CultureInfo::get_InvariantCulture()
            |    callvirt instance string ${CORE_LIB_REF}System.IFormattable::ToString(string, class ${CORE_LIB_REF}System.IFormatProvider)
            |    call string ${CORE_LIB_REF}System.String::Concat(string, string, string)
            |    ret
            |IL_scientificFromE:
            |    ldloc.0
            |    ldc.i4.0
            |    ldloc.1
            |    callvirt instance string ${CORE_LIB_REF}System.String::Substring(int32, int32)
            |    dup
            |    ldc.i4.s 46
            |    callvirt instance int32 ${CORE_LIB_REF}System.String::IndexOf(char)
            |    ldc.i4.0
            |    bge IL_mantissaHasDot
            |    ldstr ".0"
            |    call string ${CORE_LIB_REF}System.String::Concat(string, string)
            |IL_mantissaHasDot:
            |    ldstr "E"
            |    ldloc.0
            |    ldloc.1
            |    ldc.i4.1
            |    add
            |    callvirt instance string ${CORE_LIB_REF}System.String::Substring(int32)
            |    call class ${CORE_LIB_REF}System.Globalization.CultureInfo ${CORE_LIB_REF}System.Globalization.CultureInfo::get_InvariantCulture()
            |    call int32 ${CORE_LIB_REF}System.Int32::Parse(string, class ${CORE_LIB_REF}System.IFormatProvider)
            |    box ${CORE_LIB_REF}System.Int32
            |    ldnull
            |    call class ${CORE_LIB_REF}System.Globalization.CultureInfo ${CORE_LIB_REF}System.Globalization.CultureInfo::get_InvariantCulture()
            |    callvirt instance string ${CORE_LIB_REF}System.IFormattable::ToString(string, class ${CORE_LIB_REF}System.IFormatProvider)
            |    call string ${CORE_LIB_REF}System.String::Concat(string, string, string)
            |    ret
            |  }
            |  }
            |}
            |
        """.trimMargin()

    /** The cross-assembly call emitted by compiled Kotlin code; one float64 in, one string out. */
    val doubleToStringCallInstruction: String =
        "call string [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.DoubleFormatting".toIlIdentifier()}::" +
                "${"DoubleToString".toIlIdentifier()}(float64)"

    /** JVM `Intrinsics.areEqual` semantics: null-safe left-biased virtual equality. */
    val areEqualCallInstruction: String =
        "call bool [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"AreEqual".toIlIdentifier()}(object, object)"

    /** Kotlin object-boundary hash semantics, including Boolean, Char, and boxed Double differences. */
    val hashCodeCallInstruction: String =
        "call int32 [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"HashCode".toIlIdentifier()}(object)"

    /** JVM `String.valueOf(Object)` semantics for templates, concatenation, and `println(Any?)`. */
    val stringValueOfCallInstruction: String =
        "call string [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"StringValueOf".toIlIdentifier()}(object)"

    /** Nullable shallow content equality for every supported CLR vector representation. */
    val arrayContentEqualsCallInstruction: String =
        "call bool [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayContentEquals".toIlIdentifier()}(" +
                "class ${CORE_LIB_REF}System.Array, class ${CORE_LIB_REF}System.Array)"

    /** Nullable recursive content equality for generic arrays and their supported nested arrays. */
    val arrayContentDeepEqualsCallInstruction: String =
        "call bool [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayContentDeepEquals".toIlIdentifier()}(" +
                "class ${CORE_LIB_REF}System.Array, class ${CORE_LIB_REF}System.Array)"

    /** Nullable shallow List-compatible content hash for every supported CLR vector. */
    val arrayContentHashCodeCallInstruction: String =
        "call int32 [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayContentHashCode".toIlIdentifier()}(class ${CORE_LIB_REF}System.Array)"

    /** Nullable recursive List-compatible content hash for generic arrays. */
    val arrayContentDeepHashCodeCallInstruction: String =
        "call int32 [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayContentDeepHashCode".toIlIdentifier()}(class ${CORE_LIB_REF}System.Array)"

    /** Nullable shallow List-compatible content rendering for every supported CLR vector. */
    val arrayContentToStringCallInstruction: String =
        "call string [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayContentToString".toIlIdentifier()}(class ${CORE_LIB_REF}System.Array)"

    /** Nullable recursive List-compatible content rendering for generic arrays. */
    val arrayContentDeepToStringCallInstruction: String =
        "call string [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayContentDeepToString".toIlIdentifier()}(class ${CORE_LIB_REF}System.Array)"

    /** Content hash for the CLR vector behind an array property of a generated data class. */
    val dataClassArrayHashCodeCallInstruction: String =
        "call int32 [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"DataClassArrayHashCode".toIlIdentifier()}(class ${CORE_LIB_REF}System.Array)"

    /** Content rendering for the CLR vector behind an array property of a generated data class. */
    val dataClassArrayToStringCallInstruction: String =
        "call string [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"DataClassArrayToString".toIlIdentifier()}(class ${CORE_LIB_REF}System.Array)"

    /** Kotlin range validation plus overlap-safe CLR copying for `Array.copyInto`. */
    val arrayCopyIntoCallInstruction: String =
        "call void [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayCopyInto".toIlIdentifier()}(" +
                "class ${CORE_LIB_REF}System.Array, class ${CORE_LIB_REF}System.Array, int32, int32, int32)"

    /** Creates the shared erased iterator object over one CLR vector. */
    val arrayIteratorConstructorInstruction: String =
        "newobj instance void [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.ArrayIterator".toIlIdentifier()}::.ctor(" +
                "class ${CORE_LIB_REF}System.Array)"
}
