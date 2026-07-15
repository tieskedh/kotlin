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
    val ilText: String = """
            |.namespace Kotlin.Runtime.Internal
            |{
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
}
