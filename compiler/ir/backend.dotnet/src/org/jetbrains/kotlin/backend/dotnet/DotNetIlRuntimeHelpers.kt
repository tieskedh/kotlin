package org.jetbrains.kotlin.backend.dotnet

/**
 * The synthetic utility class hosting hand-written IL runtime helpers. It plays the role the
 * kotlin-stdlib runtime classes play on the JVM (e.g. `kotlin.jvm.internal.Intrinsics`): shared
 * code that user methods call into but that has no Kotlin source. The name follows the CLR
 * convention for compiler-emitted types (`<Module>`, `<PrivateImplementationDetails>` in Roslyn):
 * angle brackets make it unspeakable from C#/Kotlin source, and the quoted-identifier emission
 * this backend uses everywhere makes it a valid ILAsm class name. The class is emitted at most
 * once per module, and only when at least one rendered method required one of its helpers.
 */
internal const val DOTNET_RUNTIME_HELPER_CLASS_NAME = "<KotlinIl>"

/**
 * A runtime helper method of [DOTNET_RUNTIME_HELPER_CLASS_NAME]: methods that are too large to
 * inline at every use site and are instead emitted once per module, on demand. Each rendered
 * user method records the helpers it called (see [DotNetIlMethodContext.requireRuntimeHelper]);
 * [DotNetIlEmitter] appends the utility class containing every required helper.
 */
internal enum class DotNetIlRuntimeHelper {
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
     * 3. Finite values are formatted with `"R"` + `CultureInfo.InvariantCulture` and post-processed
     *    to the Kotlin shape: a mantissa without `.` gets `".0"` appended (`"1"` -> `"1.0"`,
     *    `"1E+20"` -> `"1.0E20"`), and the exponent suffix is normalized by parsing it back to an
     *    `int32` (`Int32::Parse` accepts the `"+20"`/`"-05"` forms) and re-rendering it through
     *    the invariant culture, which drops the `+` sign and the leading zeros while keeping `-`.
     *
     * Residual divergences from the JVM rendering, consciously accepted because fixing them means
     * reimplementing `java.lang.Double.toString`'s digit generation in IL:
     *
     * - Notation thresholds. The JVM uses plain decimal notation exactly for
     *   `1e-3 <= |d| < 1e7` and scientific notation outside; .NET's `"R"` stays decimal roughly
     *   within `1e-4 <= |d| < 1e15`. Values in the gap print as decimal where the JVM prints
     *   scientific: `12345678.0` here vs `1.2345678E7` on the JVM, `1.0E-4` -> `0.0001`.
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
    DoubleToString {
        override val methodIlText: String = """
            |  .method public hidebysig static string 'DoubleToString'(float64 'value') cil managed
            |  {
            |    .maxstack 5
            |    .locals init (
            |      [0] string 's',
            |      [1] int32 'e'
            |    )
            |    ldarg.0
            |    call bool [mscorlib]System.Double::IsNaN(float64)
            |    brfalse IL_notNaN
            |    ldstr "NaN"
            |    ret
            |IL_notNaN:
            |    ldarg.0
            |    call bool [mscorlib]System.Double::IsPositiveInfinity(float64)
            |    brfalse IL_notPositiveInfinity
            |    ldstr "Infinity"
            |    ret
            |IL_notPositiveInfinity:
            |    ldarg.0
            |    call bool [mscorlib]System.Double::IsNegativeInfinity(float64)
            |    brfalse IL_notNegativeInfinity
            |    ldstr "-Infinity"
            |    ret
            |IL_notNegativeInfinity:
            |    ldarg.0
            |    call int64 [mscorlib]System.BitConverter::DoubleToInt64Bits(float64)
            |    ldc.i8 -9223372036854775808
            |    bne.un IL_finite
            |    ldstr "-0.0"
            |    ret
            |IL_finite:
            |    ldarga.s 0
            |    ldstr "R"
            |    call class [mscorlib]System.Globalization.CultureInfo [mscorlib]System.Globalization.CultureInfo::get_InvariantCulture()
            |    call instance string [mscorlib]System.Double::ToString(string, class [mscorlib]System.IFormatProvider)
            |    stloc.0
            |    ldloc.0
            |    ldc.i4.s 69
            |    callvirt instance int32 [mscorlib]System.String::IndexOf(char)
            |    stloc.1
            |    ldloc.1
            |    ldc.i4.0
            |    bge IL_scientific
            |    ldloc.0
            |    ldc.i4.s 46
            |    callvirt instance int32 [mscorlib]System.String::IndexOf(char)
            |    ldc.i4.0
            |    bge IL_decimalHasDot
            |    ldloc.0
            |    ldstr ".0"
            |    call string [mscorlib]System.String::Concat(string, string)
            |    ret
            |IL_decimalHasDot:
            |    ldloc.0
            |    ret
            |IL_scientific:
            |    ldloc.0
            |    ldc.i4.0
            |    ldloc.1
            |    callvirt instance string [mscorlib]System.String::Substring(int32, int32)
            |    dup
            |    ldc.i4.s 46
            |    callvirt instance int32 [mscorlib]System.String::IndexOf(char)
            |    ldc.i4.0
            |    bge IL_mantissaHasDot
            |    ldstr ".0"
            |    call string [mscorlib]System.String::Concat(string, string)
            |IL_mantissaHasDot:
            |    ldstr "E"
            |    ldloc.0
            |    ldloc.1
            |    ldc.i4.1
            |    add
            |    callvirt instance string [mscorlib]System.String::Substring(int32)
            |    call class [mscorlib]System.Globalization.CultureInfo [mscorlib]System.Globalization.CultureInfo::get_InvariantCulture()
            |    call int32 [mscorlib]System.Int32::Parse(string, class [mscorlib]System.IFormatProvider)
            |    box [mscorlib]System.Int32
            |    ldnull
            |    call class [mscorlib]System.Globalization.CultureInfo [mscorlib]System.Globalization.CultureInfo::get_InvariantCulture()
            |    callvirt instance string [mscorlib]System.IFormattable::ToString(string, class [mscorlib]System.IFormatProvider)
            |    call string [mscorlib]System.String::Concat(string, string, string)
            |    ret
            |  }
            |
        """.trimMargin()

        override val callInstruction: String =
            "call string ${DOTNET_RUNTIME_HELPER_CLASS_NAME.toIlIdentifier()}::${"DoubleToString".toIlIdentifier()}(float64)"
    };

    /** The complete `.method` block, in the same layout [DotNetIlMethodCodegen] renders. */
    abstract val methodIlText: String

    /** The `call` instruction user methods invoke the helper with; pops its arguments, pushes 1. */
    abstract val callInstruction: String
}
