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
    fun ilText(coreLibraryReference: String, editorBrowsableReference: String): String {
        val compilerAbiTypeAttributesIl = listOf(
            DotNetCompilerAbi.markerAttributeIl(runtimeAssemblyReference = ""),
            DotNetCompilerAbi.editorBrowsableNeverAttributeIl(editorBrowsableReference),
        ).joinToString("\n            |    ")
        val primitiveArrayHelperTypeIl = DotNetPrimitiveArrays.runtimeHelperTypeIl(
            coreLibraryReference,
            compilerAbiTypeAttributesIl.replace("            |", ""),
        ).prependIndent("            |")
        return """
            |.namespace Kotlin.Runtime.Internal
            |{
$primitiveArrayHelperTypeIl
            |
            |  .class public auto ansi sealed beforefieldinit DefaultConstructorMarker
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method private hidebysig specialname rtspecialname instance void .ctor() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ret
            |    }
            |  }
            |
            |  .class public auto ansi sealed beforefieldinit SyntheticConstructorMarker
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method private hidebysig specialname rtspecialname instance void .ctor() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ret
            |    }
            |  }
            |
            |  .class interface public abstract auto ansi 'ExactFunction0`1'<+ R>
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method public hidebysig newslot abstract virtual instance !0 InvokeExact() cil managed
            |    {
            |    }
            |  }
            |
            |  .class interface public abstract auto ansi 'ExactFunction1`2'<- P0, + R>
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method public hidebysig newslot abstract virtual instance !1 InvokeExact(!0 p1) cil managed
            |    {
            |    }
            |  }
            |
            |  .class interface public abstract auto ansi 'ExactFunction2`3'<- P0, - P1, + R>
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method public hidebysig newslot abstract virtual instance !2 InvokeExact(!0 p1, !1 p2) cil managed
            |    {
            |    }
            |  }
            |
            |  .class interface public abstract auto ansi 'ExactFunction3`4'<- P0, - P1, - P2, + R>
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method public hidebysig newslot abstract virtual instance !3 InvokeExact(!0 p1, !1 p2, !2 p3) cil managed
            |    {
            |    }
            |  }
            |
            |  .class interface public abstract auto ansi 'TypedArgumentsFunction1`1'<- P0>
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method public hidebysig newslot abstract virtual instance object InvokeTyped(!0 p1) cil managed
            |    {
            |    }
            |  }
            |
            |  .class interface public abstract auto ansi 'TypedArgumentsFunction2`2'<- P0, - P1>
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method public hidebysig newslot abstract virtual instance object InvokeTyped(!0 p1, !1 p2) cil managed
            |    {
            |    }
            |  }
            |
            |  .class public abstract auto ansi beforefieldinit FunctionReferenceBase
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .field private initonly string 'id'
            |    .field private initonly int32 'arity'
            |    .field private initonly int32 'flags'
            |    .field private initonly int32 'boundValueCount'
            |    .field private initonly string 'name'
            |
            |    .method family hidebysig specialname rtspecialname instance void .ctor(
            |        string 'id', int32 'arity', int32 'flags', int32 'boundValueCount', string 'name') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ldarg.0
            |      ldarg.1
            |      stfld string Kotlin.Runtime.Internal.FunctionReferenceBase::'id'
            |      ldarg.0
            |      ldarg.2
            |      stfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'arity'
            |      ldarg.0
            |      ldarg.3
            |      stfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'flags'
            |      ldarg.0
            |      ldarg.s 4
            |      stfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'boundValueCount'
            |      ldarg.0
            |      ldarg.s 5
            |      stfld string Kotlin.Runtime.Internal.FunctionReferenceBase::'name'
            |      ret
            |    }
            |
            |    .method family hidebysig newslot virtual instance object BoundValueAt(int32 'index') cil managed
            |    {
            |      .maxstack 1
            |      ldnull
            |      ret
            |    }
            |
            |    .method public hidebysig virtual instance bool Equals(object 'other') cil managed
            |    {
            |      .maxstack 3
            |      .locals init (
            |        [0] class Kotlin.Runtime.Internal.FunctionReferenceBase otherReference,
            |        [1] int32 index
            |      )
            |      ldarg.0
            |      ldarg.1
            |      beq FR_Equals_True
            |      ldarg.1
            |      isinst Kotlin.Runtime.Internal.FunctionReferenceBase
            |      stloc.0
            |      ldloc.0
            |      brfalse FR_Equals_False
            |      ldarg.0
            |      ldfld string Kotlin.Runtime.Internal.FunctionReferenceBase::'id'
            |      ldloc.0
            |      ldfld string Kotlin.Runtime.Internal.FunctionReferenceBase::'id'
            |      call bool Kotlin.Runtime.Internal.Intrinsics::AreEqual(object, object)
            |      brfalse FR_Equals_False
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'arity'
            |      ldloc.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'arity'
            |      bne.un FR_Equals_False
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'flags'
            |      ldloc.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'flags'
            |      bne.un FR_Equals_False
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'boundValueCount'
            |      ldloc.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'boundValueCount'
            |      bne.un FR_Equals_False
            |      ldc.i4.0
            |      stloc.1
            |    FR_Equals_Loop:
            |      ldloc.1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'boundValueCount'
            |      bge FR_Equals_True
            |      ldarg.0
            |      ldloc.1
            |      callvirt instance object Kotlin.Runtime.Internal.FunctionReferenceBase::BoundValueAt(int32)
            |      ldloc.0
            |      ldloc.1
            |      callvirt instance object Kotlin.Runtime.Internal.FunctionReferenceBase::BoundValueAt(int32)
            |      call bool Kotlin.Runtime.Internal.Intrinsics::AreEqual(object, object)
            |      brfalse FR_Equals_False
            |      ldloc.1
            |      ldc.i4.1
            |      add
            |      stloc.1
            |      br FR_Equals_Loop
            |    FR_Equals_True:
            |      ldc.i4.1
            |      ret
            |    FR_Equals_False:
            |      ldc.i4.0
            |      ret
            |    }
            |
            |    .method public hidebysig virtual instance int32 GetHashCode() cil managed
            |    {
            |      .maxstack 3
            |      .locals init ([0] int32 result, [1] int32 index)
            |      ldarg.0
            |      ldfld string Kotlin.Runtime.Internal.FunctionReferenceBase::'id'
            |      callvirt instance int32 ${coreLibraryReference}System.Object::GetHashCode()
            |      stloc.0
            |      ldloc.0
            |      ldc.i4.s 31
            |      mul
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'arity'
            |      add
            |      stloc.0
            |      ldloc.0
            |      ldc.i4.s 31
            |      mul
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'flags'
            |      add
            |      stloc.0
            |      ldc.i4.0
            |      stloc.1
            |    FR_Hash_Loop:
            |      ldloc.1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'boundValueCount'
            |      bge FR_Hash_Done
            |      ldloc.0
            |      ldc.i4.s 31
            |      mul
            |      ldarg.0
            |      ldloc.1
            |      callvirt instance object Kotlin.Runtime.Internal.FunctionReferenceBase::BoundValueAt(int32)
            |      call int32 Kotlin.Runtime.Internal.Intrinsics::HashCode(object)
            |      add
            |      stloc.0
            |      ldloc.1
            |      ldc.i4.1
            |      add
            |      stloc.1
            |      br FR_Hash_Loop
            |    FR_Hash_Done:
            |      ldloc.0
            |      ret
            |    }
            |
            |    .method public hidebysig virtual instance string ToString() cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldfld string Kotlin.Runtime.Internal.FunctionReferenceBase::'name'
            |      ldstr "<init>"
            |      call bool ${coreLibraryReference}System.String::op_Equality(string, string)
            |      brfalse FR_ToString_Function
            |      ldstr "constructor"
            |      ret
            |    FR_ToString_Function:
            |      ldstr "function "
            |      ldarg.0
            |      ldfld string Kotlin.Runtime.Internal.FunctionReferenceBase::'name'
            |      call string ${coreLibraryReference}System.String::Concat(string, string)
            |      ret
            |    }
            |  }
            |
            |  .class private abstract auto ansi beforefieldinit PropertyReferenceBase
            |         extends ${coreLibraryReference}System.Object
            |         implements Kotlin.KProperty
            |  {
            |    .field private initonly string 'name'
            |
            |    .method family hidebysig specialname rtspecialname instance void .ctor(string 'name') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ldarg.0
            |      ldarg.1
            |      stfld string Kotlin.Runtime.Internal.PropertyReferenceBase::'name'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance string get_name() cil managed
            |    {
            |      .override method instance string Kotlin.KCallable::get_name()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld string Kotlin.Runtime.Internal.PropertyReferenceBase::'name'
            |      ret
            |    }
            |
            |    .method family hidebysig newslot abstract virtual instance object GetGetterIdentity() cil managed
            |    {
            |    }
            |
            |    .method family hidebysig newslot virtual instance object GetSetterIdentity() cil managed
            |    {
            |      .maxstack 1
            |      ldnull
            |      ret
            |    }
            |
            |    .method public hidebysig virtual instance bool Equals(object 'other') cil managed
            |    {
            |      .maxstack 2
            |      .locals init ([0] class Kotlin.Runtime.Internal.PropertyReferenceBase otherReference)
            |      ldarg.0
            |      ldarg.1
            |      beq PR_Equals_True
            |      ldarg.1
            |      isinst Kotlin.Runtime.Internal.PropertyReferenceBase
            |      stloc.0
            |      ldloc.0
            |      brfalse PR_Equals_False
            |      ldarg.0
            |      call instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Object::GetType()
            |      ldloc.0
            |      callvirt instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Object::GetType()
            |      bne.un PR_Equals_False
            |      ldarg.0
            |      ldfld string Kotlin.Runtime.Internal.PropertyReferenceBase::'name'
            |      ldloc.0
            |      ldfld string Kotlin.Runtime.Internal.PropertyReferenceBase::'name'
            |      call bool Kotlin.Runtime.Internal.Intrinsics::AreEqual(object, object)
            |      brfalse PR_Equals_False
            |      ldarg.0
            |      callvirt instance object Kotlin.Runtime.Internal.PropertyReferenceBase::GetGetterIdentity()
            |      ldloc.0
            |      callvirt instance object Kotlin.Runtime.Internal.PropertyReferenceBase::GetGetterIdentity()
            |      call bool Kotlin.Runtime.Internal.Intrinsics::AreEqual(object, object)
            |      brfalse PR_Equals_False
            |      ldarg.0
            |      callvirt instance object Kotlin.Runtime.Internal.PropertyReferenceBase::GetSetterIdentity()
            |      ldloc.0
            |      callvirt instance object Kotlin.Runtime.Internal.PropertyReferenceBase::GetSetterIdentity()
            |      call bool Kotlin.Runtime.Internal.Intrinsics::AreEqual(object, object)
            |      brfalse PR_Equals_False
            |    PR_Equals_True:
            |      ldc.i4.1
            |      ret
            |    PR_Equals_False:
            |      ldc.i4.0
            |      ret
            |    }
            |
            |    .method public hidebysig virtual instance int32 GetHashCode() cil managed
            |    {
            |      .maxstack 2
            |      .locals init ([0] int32 result)
            |      ldarg.0
            |      ldfld string Kotlin.Runtime.Internal.PropertyReferenceBase::'name'
            |      call int32 Kotlin.Runtime.Internal.Intrinsics::HashCode(object)
            |      ldc.i4.s 31
            |      mul
            |      ldarg.0
            |      callvirt instance object Kotlin.Runtime.Internal.PropertyReferenceBase::GetGetterIdentity()
            |      call int32 Kotlin.Runtime.Internal.Intrinsics::HashCode(object)
            |      add
            |      stloc.0
            |      ldarg.0
            |      callvirt instance object Kotlin.Runtime.Internal.PropertyReferenceBase::GetSetterIdentity()
            |      brfalse PR_Hash_Done
            |      ldloc.0
            |      ldc.i4.s 31
            |      mul
            |      ldarg.0
            |      callvirt instance object Kotlin.Runtime.Internal.PropertyReferenceBase::GetSetterIdentity()
            |      call int32 Kotlin.Runtime.Internal.Intrinsics::HashCode(object)
            |      add
            |      stloc.0
            |    PR_Hash_Done:
            |      ldloc.0
            |      ret
            |    }
            |
            |    .method public hidebysig virtual instance string ToString() cil managed
            |    {
            |      .maxstack 3
            |      ldstr "property "
            |      ldarg.0
            |      ldfld string Kotlin.Runtime.Internal.PropertyReferenceBase::'name'
            |      ldstr " (Kotlin reflection is not available)"
            |      call string ${coreLibraryReference}System.String::Concat(string, string, string)
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit Property0Impl
            |         extends Kotlin.Runtime.Internal.PropertyReferenceBase
            |         implements Kotlin.KProperty0
            |  {
            |    .field private initonly class Kotlin.Function0 'getter'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor(string 'name', class Kotlin.Function0 'getter') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::.ctor(string)
            |      ldarg.0
            |      ldarg.2
            |      stfld class Kotlin.Function0 Kotlin.Runtime.Internal.Property0Impl::'getter'
            |      ret
            |    }
            |
            |    .method family hidebysig virtual final instance object GetGetterIdentity() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Function0 Kotlin.Runtime.Internal.Property0Impl::'getter'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Get() cil managed
            |    {
            |      .override method instance object Kotlin.KProperty0::Get()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Function0 Kotlin.Runtime.Internal.Property0Impl::'getter'
            |      callvirt instance object Kotlin.Function0::Invoke()
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Invoke() cil managed
            |    {
            |      .override method instance object Kotlin.Function0::Invoke()
            |      .maxstack 1
            |      ldarg.0
            |      call instance object Kotlin.Runtime.Internal.Property0Impl::Get()
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit MutableProperty0Impl
            |         extends Kotlin.Runtime.Internal.PropertyReferenceBase
            |         implements Kotlin.KMutableProperty0
            |  {
            |    .field private initonly class Kotlin.Function0 'getter'
            |    .field private initonly class Kotlin.Function1 'setter'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor(string 'name', class Kotlin.Function0 'getter', class Kotlin.Function1 'setter') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::.ctor(string)
            |      ldarg.0
            |      ldarg.2
            |      stfld class Kotlin.Function0 Kotlin.Runtime.Internal.MutableProperty0Impl::'getter'
            |      ldarg.0
            |      ldarg.3
            |      stfld class Kotlin.Function1 Kotlin.Runtime.Internal.MutableProperty0Impl::'setter'
            |      ret
            |    }
            |
            |    .method family hidebysig virtual final instance object GetGetterIdentity() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Function0 Kotlin.Runtime.Internal.MutableProperty0Impl::'getter'
            |      ret
            |    }
            |
            |    .method family hidebysig virtual final instance object GetSetterIdentity() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Function1 Kotlin.Runtime.Internal.MutableProperty0Impl::'setter'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Get() cil managed
            |    {
            |      .override method instance object Kotlin.KProperty0::Get()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Function0 Kotlin.Runtime.Internal.MutableProperty0Impl::'getter'
            |      callvirt instance object Kotlin.Function0::Invoke()
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Invoke() cil managed
            |    {
            |      .override method instance object Kotlin.Function0::Invoke()
            |      .maxstack 1
            |      ldarg.0
            |      call instance object Kotlin.Runtime.Internal.MutableProperty0Impl::Get()
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance void Set(object 'value') cil managed
            |    {
            |      .override method instance void Kotlin.KMutableProperty0::Set(object)
            |      .maxstack 2
            |      ldarg.0
            |      ldfld class Kotlin.Function1 Kotlin.Runtime.Internal.MutableProperty0Impl::'setter'
            |      ldarg.1
            |      callvirt instance object Kotlin.Function1::Invoke(object)
            |      pop
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit Property1Impl
            |         extends Kotlin.Runtime.Internal.PropertyReferenceBase
            |         implements Kotlin.KProperty1
            |  {
            |    .field private initonly class Kotlin.Function1 'getter'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor(string 'name', class Kotlin.Function1 'getter') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::.ctor(string)
            |      ldarg.0
            |      ldarg.2
            |      stfld class Kotlin.Function1 Kotlin.Runtime.Internal.Property1Impl::'getter'
            |      ret
            |    }
            |
            |    .method family hidebysig virtual final instance object GetGetterIdentity() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Function1 Kotlin.Runtime.Internal.Property1Impl::'getter'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Get(object receiver) cil managed
            |    {
            |      .override method instance object Kotlin.KProperty1::Get(object)
            |      .maxstack 2
            |      ldarg.0
            |      ldfld class Kotlin.Function1 Kotlin.Runtime.Internal.Property1Impl::'getter'
            |      ldarg.1
            |      callvirt instance object Kotlin.Function1::Invoke(object)
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Invoke(object receiver) cil managed
            |    {
            |      .override method instance object Kotlin.Function1::Invoke(object)
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      call instance object Kotlin.Runtime.Internal.Property1Impl::Get(object)
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit MutableProperty1Impl
            |         extends Kotlin.Runtime.Internal.PropertyReferenceBase
            |         implements Kotlin.KMutableProperty1
            |  {
            |    .field private initonly class Kotlin.Function1 'getter'
            |    .field private initonly class Kotlin.Function2 'setter'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor(string 'name', class Kotlin.Function1 'getter', class Kotlin.Function2 'setter') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::.ctor(string)
            |      ldarg.0
            |      ldarg.2
            |      stfld class Kotlin.Function1 Kotlin.Runtime.Internal.MutableProperty1Impl::'getter'
            |      ldarg.0
            |      ldarg.3
            |      stfld class Kotlin.Function2 Kotlin.Runtime.Internal.MutableProperty1Impl::'setter'
            |      ret
            |    }
            |
            |    .method family hidebysig virtual final instance object GetGetterIdentity() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Function1 Kotlin.Runtime.Internal.MutableProperty1Impl::'getter'
            |      ret
            |    }
            |
            |    .method family hidebysig virtual final instance object GetSetterIdentity() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Function2 Kotlin.Runtime.Internal.MutableProperty1Impl::'setter'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Get(object receiver) cil managed
            |    {
            |      .override method instance object Kotlin.KProperty1::Get(object)
            |      .maxstack 2
            |      ldarg.0
            |      ldfld class Kotlin.Function1 Kotlin.Runtime.Internal.MutableProperty1Impl::'getter'
            |      ldarg.1
            |      callvirt instance object Kotlin.Function1::Invoke(object)
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Invoke(object receiver) cil managed
            |    {
            |      .override method instance object Kotlin.Function1::Invoke(object)
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      call instance object Kotlin.Runtime.Internal.MutableProperty1Impl::Get(object)
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance void Set(object receiver, object 'value') cil managed
            |    {
            |      .override method instance void Kotlin.KMutableProperty1::Set(object, object)
            |      .maxstack 3
            |      ldarg.0
            |      ldfld class Kotlin.Function2 Kotlin.Runtime.Internal.MutableProperty1Impl::'setter'
            |      ldarg.1
            |      ldarg.2
            |      callvirt instance object Kotlin.Function2::Invoke(object, object)
            |      pop
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit Property2Impl
            |         extends Kotlin.Runtime.Internal.PropertyReferenceBase
            |         implements Kotlin.KProperty2
            |  {
            |    .field private initonly class Kotlin.Function2 'getter'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor(string 'name', class Kotlin.Function2 'getter') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::.ctor(string)
            |      ldarg.0
            |      ldarg.2
            |      stfld class Kotlin.Function2 Kotlin.Runtime.Internal.Property2Impl::'getter'
            |      ret
            |    }
            |
            |    .method family hidebysig virtual final instance object GetGetterIdentity() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Function2 Kotlin.Runtime.Internal.Property2Impl::'getter'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Get(object receiver1, object receiver2) cil managed
            |    {
            |      .override method instance object Kotlin.KProperty2::Get(object, object)
            |      .maxstack 3
            |      ldarg.0
            |      ldfld class Kotlin.Function2 Kotlin.Runtime.Internal.Property2Impl::'getter'
            |      ldarg.1
            |      ldarg.2
            |      callvirt instance object Kotlin.Function2::Invoke(object, object)
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Invoke(object receiver1, object receiver2) cil managed
            |    {
            |      .override method instance object Kotlin.Function2::Invoke(object, object)
            |      .maxstack 3
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      call instance object Kotlin.Runtime.Internal.Property2Impl::Get(object, object)
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit MutableProperty2Impl
            |         extends Kotlin.Runtime.Internal.PropertyReferenceBase
            |         implements Kotlin.KMutableProperty2
            |  {
            |    .field private initonly class Kotlin.Function2 'getter'
            |    .field private initonly class Kotlin.Function3 'setter'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor(string 'name', class Kotlin.Function2 'getter', class Kotlin.Function3 'setter') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::.ctor(string)
            |      ldarg.0
            |      ldarg.2
            |      stfld class Kotlin.Function2 Kotlin.Runtime.Internal.MutableProperty2Impl::'getter'
            |      ldarg.0
            |      ldarg.3
            |      stfld class Kotlin.Function3 Kotlin.Runtime.Internal.MutableProperty2Impl::'setter'
            |      ret
            |    }
            |
            |    .method family hidebysig virtual final instance object GetGetterIdentity() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Function2 Kotlin.Runtime.Internal.MutableProperty2Impl::'getter'
            |      ret
            |    }
            |
            |    .method family hidebysig virtual final instance object GetSetterIdentity() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Function3 Kotlin.Runtime.Internal.MutableProperty2Impl::'setter'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Get(object receiver1, object receiver2) cil managed
            |    {
            |      .override method instance object Kotlin.KProperty2::Get(object, object)
            |      .maxstack 3
            |      ldarg.0
            |      ldfld class Kotlin.Function2 Kotlin.Runtime.Internal.MutableProperty2Impl::'getter'
            |      ldarg.1
            |      ldarg.2
            |      callvirt instance object Kotlin.Function2::Invoke(object, object)
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Invoke(object receiver1, object receiver2) cil managed
            |    {
            |      .override method instance object Kotlin.Function2::Invoke(object, object)
            |      .maxstack 3
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      call instance object Kotlin.Runtime.Internal.MutableProperty2Impl::Get(object, object)
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance void Set(object receiver1, object receiver2, object 'value') cil managed
            |    {
            |      .override method instance void Kotlin.KMutableProperty2::Set(object, object, object)
            |      .maxstack 4
            |      ldarg.0
            |      ldfld class Kotlin.Function3 Kotlin.Runtime.Internal.MutableProperty2Impl::'setter'
            |      ldarg.1
            |      ldarg.2
            |      ldarg.3
            |      callvirt instance object Kotlin.Function3::Invoke(object, object, object)
            |      pop
            |      ret
            |    }
            |  }
            |
            |  .class private abstract auto ansi beforefieldinit LocalDelegatedProperty0Base
            |         extends ${coreLibraryReference}System.Object
            |         implements Kotlin.KProperty0
            |  {
            |    .field private initonly string 'name'
            |
            |    .method family hidebysig specialname rtspecialname instance void .ctor(string 'name') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ldarg.0
            |      ldarg.1
            |      stfld string Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'name'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance string get_name() cil managed
            |    {
            |      .override method instance string Kotlin.KCallable::get_name()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld string Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'name'
            |      ret
            |    }
            |
            |    .property instance string 'name'()
            |    {
            |      .get instance string Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::get_name()
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Get() cil managed
            |    {
            |      .override method instance object Kotlin.KProperty0::Get()
            |      .maxstack 1
            |      ldstr "Not supported for local property reference."
            |      newobj instance void ${coreLibraryReference}System.NotSupportedException::.ctor(string)
            |      throw
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Invoke() cil managed
            |    {
            |      .override method instance object Kotlin.Function0::Invoke()
            |      .maxstack 1
            |      ldarg.0
            |      call instance object Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::Get()
            |      ret
            |    }
            |
            |    .method public hidebysig virtual instance string ToString() cil managed
            |    {
            |      .maxstack 3
            |      ldstr "property "
            |      ldarg.0
            |      ldfld string Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'name'
            |      ldstr " (Kotlin reflection is not available)"
            |      call string ${coreLibraryReference}System.String::Concat(string, string, string)
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit LocalDelegatedProperty0Impl
            |         extends Kotlin.Runtime.Internal.LocalDelegatedProperty0Base
            |  {
            |    .method public hidebysig specialname rtspecialname instance void .ctor(string 'name') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      call instance void Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::.ctor(string)
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit LocalDelegatedMutableProperty0Impl
            |         extends Kotlin.Runtime.Internal.LocalDelegatedProperty0Base
            |         implements Kotlin.KMutableProperty0
            |  {
            |    .method public hidebysig specialname rtspecialname instance void .ctor(string 'name') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      call instance void Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::.ctor(string)
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance void Set(object 'value') cil managed
            |    {
            |      .override method instance void Kotlin.KMutableProperty0::Set(object)
            |      .maxstack 1
            |      ldstr "Not supported for local property reference."
            |      newobj instance void ${coreLibraryReference}System.NotSupportedException::.ctor(string)
            |      throw
            |    }
            |  }
            |
            |  .class public abstract sealed auto ansi beforefieldinit PropertyReferenceFactory
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method public hidebysig static class Kotlin.KProperty0 CreateProperty0<V>(string 'name', class Kotlin.Function0 'getter') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      newobj instance void Kotlin.Runtime.Internal.Property0Impl::.ctor(string, class Kotlin.Function0)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KMutableProperty0 CreateMutableProperty0<V>(string 'name', class Kotlin.Function0 'getter', class Kotlin.Function1 'setter') cil managed
            |    {
            |      .maxstack 3
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      newobj instance void Kotlin.Runtime.Internal.MutableProperty0Impl::.ctor(string, class Kotlin.Function0, class Kotlin.Function1)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KProperty1 CreateProperty1<R0, V>(string 'name', class Kotlin.Function1 'getter') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      newobj instance void Kotlin.Runtime.Internal.Property1Impl::.ctor(string, class Kotlin.Function1)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KMutableProperty1 CreateMutableProperty1<R0, V>(string 'name', class Kotlin.Function1 'getter', class Kotlin.Function2 'setter') cil managed
            |    {
            |      .maxstack 3
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      newobj instance void Kotlin.Runtime.Internal.MutableProperty1Impl::.ctor(string, class Kotlin.Function1, class Kotlin.Function2)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KProperty2 CreateProperty2<R0, R1, V>(string 'name', class Kotlin.Function2 'getter') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      newobj instance void Kotlin.Runtime.Internal.Property2Impl::.ctor(string, class Kotlin.Function2)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KMutableProperty2 CreateMutableProperty2<R0, R1, V>(string 'name', class Kotlin.Function2 'getter', class Kotlin.Function3 'setter') cil managed
            |    {
            |      .maxstack 3
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      newobj instance void Kotlin.Runtime.Internal.MutableProperty2Impl::.ctor(string, class Kotlin.Function2, class Kotlin.Function3)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KProperty0 CreateLocalDelegatedProperty0<V>(string 'name') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      newobj instance void Kotlin.Runtime.Internal.LocalDelegatedProperty0Impl::.ctor(string)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KMutableProperty0 CreateLocalDelegatedMutableProperty0<V>(string 'name') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      newobj instance void Kotlin.Runtime.Internal.LocalDelegatedMutableProperty0Impl::.ctor(string)
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit 'Func0Adapter`1'<R>
            |         extends ${coreLibraryReference}System.Object
            |         implements Kotlin.Function0, class Kotlin.Runtime.Internal.'ExactFunction0`1'<!0>
            |  {
            |    .field assembly class ${coreLibraryReference}System.Func`1<!0> 'delegate'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor(class ${coreLibraryReference}System.Func`1<!0>) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ldarg.0
            |      ldarg.1
            |      stfld class ${coreLibraryReference}System.Func`1<!0> class Kotlin.Runtime.Internal.'Func0Adapter`1'<!0>::'delegate'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance !0 InvokeExact() cil managed
            |    {
            |      .override method instance !0 class Kotlin.Runtime.Internal.'ExactFunction0`1'<!0>::InvokeExact()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class ${coreLibraryReference}System.Func`1<!0> class Kotlin.Runtime.Internal.'Func0Adapter`1'<!0>::'delegate'
            |      callvirt instance !0 class ${coreLibraryReference}System.Func`1<!0>::Invoke()
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Invoke() cil managed
            |    {
            |      .override method instance object Kotlin.Function0::Invoke()
            |      .maxstack 1
            |      ldarg.0
            |      call instance !0 class Kotlin.Runtime.Internal.'Func0Adapter`1'<!0>::InvokeExact()
            |      box !0
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit 'Func1Adapter`2'<P0, R>
            |         extends ${coreLibraryReference}System.Object
            |         implements Kotlin.Function1,
            |                    class Kotlin.Runtime.Internal.'ExactFunction1`2'<!0, !1>,
            |                    class Kotlin.Runtime.Internal.'TypedArgumentsFunction1`1'<!0>
            |  {
            |    .field assembly class ${coreLibraryReference}System.Func`2<!0, !1> 'delegate'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor(class ${coreLibraryReference}System.Func`2<!0, !1>) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ldarg.0
            |      ldarg.1
            |      stfld class ${coreLibraryReference}System.Func`2<!0, !1> class Kotlin.Runtime.Internal.'Func1Adapter`2'<!0, !1>::'delegate'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance !1 InvokeExact(!0 p1) cil managed
            |    {
            |      .override method instance !1 class Kotlin.Runtime.Internal.'ExactFunction1`2'<!0, !1>::InvokeExact(!0)
            |      .maxstack 2
            |      ldarg.0
            |      ldfld class ${coreLibraryReference}System.Func`2<!0, !1> class Kotlin.Runtime.Internal.'Func1Adapter`2'<!0, !1>::'delegate'
            |      ldarg.1
            |      callvirt instance !1 class ${coreLibraryReference}System.Func`2<!0, !1>::Invoke(!0)
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object InvokeTyped(!0 p1) cil managed
            |    {
            |      .override method instance object class Kotlin.Runtime.Internal.'TypedArgumentsFunction1`1'<!0>::InvokeTyped(!0)
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      call instance !1 class Kotlin.Runtime.Internal.'Func1Adapter`2'<!0, !1>::InvokeExact(!0)
            |      box !1
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Invoke(object p1) cil managed
            |    {
            |      .override method instance object Kotlin.Function1::Invoke(object)
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      unbox.any !0
            |      call instance !1 class Kotlin.Runtime.Internal.'Func1Adapter`2'<!0, !1>::InvokeExact(!0)
            |      box !1
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit 'Func2Adapter`3'<P0, P1, R>
            |         extends ${coreLibraryReference}System.Object
            |         implements Kotlin.Function2,
            |                    class Kotlin.Runtime.Internal.'ExactFunction2`3'<!0, !1, !2>,
            |                    class Kotlin.Runtime.Internal.'TypedArgumentsFunction2`2'<!0, !1>
            |  {
            |    .field assembly class ${coreLibraryReference}System.Func`3<!0, !1, !2> 'delegate'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor(class ${coreLibraryReference}System.Func`3<!0, !1, !2>) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ldarg.0
            |      ldarg.1
            |      stfld class ${coreLibraryReference}System.Func`3<!0, !1, !2> class Kotlin.Runtime.Internal.'Func2Adapter`3'<!0, !1, !2>::'delegate'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance !2 InvokeExact(!0 p1, !1 p2) cil managed
            |    {
            |      .override method instance !2 class Kotlin.Runtime.Internal.'ExactFunction2`3'<!0, !1, !2>::InvokeExact(!0, !1)
            |      .maxstack 3
            |      ldarg.0
            |      ldfld class ${coreLibraryReference}System.Func`3<!0, !1, !2> class Kotlin.Runtime.Internal.'Func2Adapter`3'<!0, !1, !2>::'delegate'
            |      ldarg.1
            |      ldarg.2
            |      callvirt instance !2 class ${coreLibraryReference}System.Func`3<!0, !1, !2>::Invoke(!0, !1)
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object InvokeTyped(!0 p1, !1 p2) cil managed
            |    {
            |      .override method instance object class Kotlin.Runtime.Internal.'TypedArgumentsFunction2`2'<!0, !1>::InvokeTyped(!0, !1)
            |      .maxstack 3
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      call instance !2 class Kotlin.Runtime.Internal.'Func2Adapter`3'<!0, !1, !2>::InvokeExact(!0, !1)
            |      box !2
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Invoke(object p1, object p2) cil managed
            |    {
            |      .override method instance object Kotlin.Function2::Invoke(object, object)
            |      .maxstack 3
            |      ldarg.0
            |      ldarg.1
            |      unbox.any !0
            |      ldarg.2
            |      unbox.any !1
            |      call instance !2 class Kotlin.Runtime.Internal.'Func2Adapter`3'<!0, !1, !2>::InvokeExact(!0, !1)
            |      box !2
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit Action0Adapter
            |         extends ${coreLibraryReference}System.Object
            |         implements Kotlin.Function0
            |  {
            |    .field assembly class ${coreLibraryReference}System.Action 'delegate'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor(class ${coreLibraryReference}System.Action) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ldarg.0
            |      ldarg.1
            |      stfld class ${coreLibraryReference}System.Action Kotlin.Runtime.Internal.Action0Adapter::'delegate'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Invoke() cil managed
            |    {
            |      .override method instance object Kotlin.Function0::Invoke()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class ${coreLibraryReference}System.Action Kotlin.Runtime.Internal.Action0Adapter::'delegate'
            |      callvirt instance void ${coreLibraryReference}System.Action::Invoke()
            |      ldsfld class Kotlin.Unit Kotlin.Unit::INSTANCE
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit 'Action1Adapter`1'<P0>
            |         extends ${coreLibraryReference}System.Object
            |         implements Kotlin.Function1
            |  {
            |    .field assembly class ${coreLibraryReference}System.Action`1<!0> 'delegate'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor(class ${coreLibraryReference}System.Action`1<!0>) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ldarg.0
            |      ldarg.1
            |      stfld class ${coreLibraryReference}System.Action`1<!0> class Kotlin.Runtime.Internal.'Action1Adapter`1'<!0>::'delegate'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Invoke(object p1) cil managed
            |    {
            |      .override method instance object Kotlin.Function1::Invoke(object)
            |      .maxstack 2
            |      ldarg.0
            |      ldfld class ${coreLibraryReference}System.Action`1<!0> class Kotlin.Runtime.Internal.'Action1Adapter`1'<!0>::'delegate'
            |      ldarg.1
            |      unbox.any !0
            |      callvirt instance void class ${coreLibraryReference}System.Action`1<!0>::Invoke(!0)
            |      ldsfld class Kotlin.Unit Kotlin.Unit::INSTANCE
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit 'Action2Adapter`2'<P0, P1>
            |         extends ${coreLibraryReference}System.Object
            |         implements Kotlin.Function2
            |  {
            |    .field assembly class ${coreLibraryReference}System.Action`2<!0, !1> 'delegate'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor(class ${coreLibraryReference}System.Action`2<!0, !1>) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ldarg.0
            |      ldarg.1
            |      stfld class ${coreLibraryReference}System.Action`2<!0, !1> class Kotlin.Runtime.Internal.'Action2Adapter`2'<!0, !1>::'delegate'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Invoke(object p1, object p2) cil managed
            |    {
            |      .override method instance object Kotlin.Function2::Invoke(object, object)
            |      .maxstack 3
            |      ldarg.0
            |      ldfld class ${coreLibraryReference}System.Action`2<!0, !1> class Kotlin.Runtime.Internal.'Action2Adapter`2'<!0, !1>::'delegate'
            |      ldarg.1
            |      unbox.any !0
            |      ldarg.2
            |      unbox.any !1
            |      callvirt instance void class ${coreLibraryReference}System.Action`2<!0, !1>::Invoke(!0, !1)
            |      ldsfld class Kotlin.Unit Kotlin.Unit::INSTANCE
            |      ret
            |    }
            |  }
            |
            |  .class public abstract sealed auto ansi beforefieldinit DelegateProjection
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method public hidebysig static class Kotlin.Function0 FromFunc0<R>(class ${coreLibraryReference}System.Func`1<!!0>) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      brtrue.s valid
            |      ldstr "callable"
            |      newobj instance void ${coreLibraryReference}System.ArgumentNullException::.ctor(string)
            |      throw
            |    valid:
            |      ldarg.0
            |      newobj instance void class Kotlin.Runtime.Internal.'Func0Adapter`1'<!!0>::.ctor(class ${coreLibraryReference}System.Func`1<!0>)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.Function1 FromFunc1<P0, R>(class ${coreLibraryReference}System.Func`2<!!0, !!1>) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      brtrue.s valid
            |      ldstr "callable"
            |      newobj instance void ${coreLibraryReference}System.ArgumentNullException::.ctor(string)
            |      throw
            |    valid:
            |      ldarg.0
            |      newobj instance void class Kotlin.Runtime.Internal.'Func1Adapter`2'<!!0, !!1>::.ctor(class ${coreLibraryReference}System.Func`2<!0, !1>)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.Function2 FromFunc2<P0, P1, R>(class ${coreLibraryReference}System.Func`3<!!0, !!1, !!2>) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      brtrue.s valid
            |      ldstr "callable"
            |      newobj instance void ${coreLibraryReference}System.ArgumentNullException::.ctor(string)
            |      throw
            |    valid:
            |      ldarg.0
            |      newobj instance void class Kotlin.Runtime.Internal.'Func2Adapter`3'<!!0, !!1, !!2>::.ctor(class ${coreLibraryReference}System.Func`3<!0, !1, !2>)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.Function0 FromAction0(class ${coreLibraryReference}System.Action) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      brtrue.s valid
            |      ldstr "callable"
            |      newobj instance void ${coreLibraryReference}System.ArgumentNullException::.ctor(string)
            |      throw
            |    valid:
            |      ldarg.0
            |      newobj instance void Kotlin.Runtime.Internal.Action0Adapter::.ctor(class ${coreLibraryReference}System.Action)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.Function1 FromAction1<P0>(class ${coreLibraryReference}System.Action`1<!!0>) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      brtrue.s valid
            |      ldstr "callable"
            |      newobj instance void ${coreLibraryReference}System.ArgumentNullException::.ctor(string)
            |      throw
            |    valid:
            |      ldarg.0
            |      newobj instance void class Kotlin.Runtime.Internal.'Action1Adapter`1'<!!0>::.ctor(class ${coreLibraryReference}System.Action`1<!0>)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.Function2 FromAction2<P0, P1>(class ${coreLibraryReference}System.Action`2<!!0, !!1>) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      brtrue.s valid
            |      ldstr "callable"
            |      newobj instance void ${coreLibraryReference}System.ArgumentNullException::.ctor(string)
            |      throw
            |    valid:
            |      ldarg.0
            |      newobj instance void class Kotlin.Runtime.Internal.'Action2Adapter`2'<!!0, !!1>::.ctor(class ${coreLibraryReference}System.Action`2<!0, !1>)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.Function0 FromNullableFunc0<R>(class ${coreLibraryReference}System.Func`1<!!0>) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brtrue.s nonnull
            |      ldnull
            |      ret
            |    nonnull:
            |      ldarg.0
            |      call class Kotlin.Function0 Kotlin.Runtime.Internal.DelegateProjection::FromFunc0<!!0>(class ${coreLibraryReference}System.Func`1<!!0>)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.Function1 FromNullableFunc1<P0, R>(class ${coreLibraryReference}System.Func`2<!!0, !!1>) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brtrue.s nonnull
            |      ldnull
            |      ret
            |    nonnull:
            |      ldarg.0
            |      call class Kotlin.Function1 Kotlin.Runtime.Internal.DelegateProjection::FromFunc1<!!0, !!1>(class ${coreLibraryReference}System.Func`2<!!0, !!1>)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.Function2 FromNullableFunc2<P0, P1, R>(class ${coreLibraryReference}System.Func`3<!!0, !!1, !!2>) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brtrue.s nonnull
            |      ldnull
            |      ret
            |    nonnull:
            |      ldarg.0
            |      call class Kotlin.Function2 Kotlin.Runtime.Internal.DelegateProjection::FromFunc2<!!0, !!1, !!2>(class ${coreLibraryReference}System.Func`3<!!0, !!1, !!2>)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.Function0 FromNullableAction0(class ${coreLibraryReference}System.Action) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brtrue.s nonnull
            |      ldnull
            |      ret
            |    nonnull:
            |      ldarg.0
            |      call class Kotlin.Function0 Kotlin.Runtime.Internal.DelegateProjection::FromAction0(class ${coreLibraryReference}System.Action)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.Function1 FromNullableAction1<P0>(class ${coreLibraryReference}System.Action`1<!!0>) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brtrue.s nonnull
            |      ldnull
            |      ret
            |    nonnull:
            |      ldarg.0
            |      call class Kotlin.Function1 Kotlin.Runtime.Internal.DelegateProjection::FromAction1<!!0>(class ${coreLibraryReference}System.Action`1<!!0>)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.Function2 FromNullableAction2<P0, P1>(class ${coreLibraryReference}System.Action`2<!!0, !!1>) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brtrue.s nonnull
            |      ldnull
            |      ret
            |    nonnull:
            |      ldarg.0
            |      call class Kotlin.Function2 Kotlin.Runtime.Internal.DelegateProjection::FromAction2<!!0, !!1>(class ${coreLibraryReference}System.Action`2<!!0, !!1>)
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Func`1<!!0> ToFunc0<R>(class Kotlin.Function0 callable) cil managed
            |    {
            |      .maxstack 3
            |      .locals init ([0] class Kotlin.Runtime.Internal.'ExactFunction0`1'<!!0> exact)
            |      ldarg.0
            |      isinst class Kotlin.Runtime.Internal.'Func0Adapter`1'<!!0>
            |      dup
            |      brfalse.s project
            |      ldfld class ${coreLibraryReference}System.Func`1<!0> class Kotlin.Runtime.Internal.'Func0Adapter`1'<!!0>::'delegate'
            |      ret
            |    project:
            |      pop
            |      ldarg.0
            |      isinst class Kotlin.Runtime.Internal.'ExactFunction0`1'<!!0>
            |      stloc.0
            |      ldloc.0
            |      brfalse.s fallback
            |      ldloc.0
            |      ldloc.0
            |      ldvirtftn instance !0 class Kotlin.Runtime.Internal.'ExactFunction0`1'<!!0>::InvokeExact()
            |      newobj instance void class ${coreLibraryReference}System.Func`1<!!0>::.ctor(object, native int)
            |      ret
            |    fallback:
            |      ldarg.0
            |      ldftn !!0 Kotlin.Runtime.Internal.DelegateProjection::Invoke0<!!0>(class Kotlin.Function0)
            |      newobj instance void class ${coreLibraryReference}System.Func`1<!!0>::.ctor(object, native int)
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Func`2<!!0, !!1> ToFunc1<P0, R>(class Kotlin.Function1 callable) cil managed
            |    {
            |      .maxstack 3
            |      .locals init ([0] class Kotlin.Runtime.Internal.'ExactFunction1`2'<!!0, !!1> exact)
            |      ldarg.0
            |      isinst class Kotlin.Runtime.Internal.'Func1Adapter`2'<!!0, !!1>
            |      dup
            |      brfalse.s project
            |      ldfld class ${coreLibraryReference}System.Func`2<!0, !1> class Kotlin.Runtime.Internal.'Func1Adapter`2'<!!0, !!1>::'delegate'
            |      ret
            |    project:
            |      pop
            |      ldarg.0
            |      isinst class Kotlin.Runtime.Internal.'ExactFunction1`2'<!!0, !!1>
            |      stloc.0
            |      ldloc.0
            |      brfalse.s fallback
            |      ldloc.0
            |      ldloc.0
            |      ldvirtftn instance !1 class Kotlin.Runtime.Internal.'ExactFunction1`2'<!!0, !!1>::InvokeExact(!0)
            |      newobj instance void class ${coreLibraryReference}System.Func`2<!!0, !!1>::.ctor(object, native int)
            |      ret
            |    fallback:
            |      ldarg.0
            |      ldftn !!1 Kotlin.Runtime.Internal.DelegateProjection::Invoke1<!!0, !!1>(class Kotlin.Function1, !!0)
            |      newobj instance void class ${coreLibraryReference}System.Func`2<!!0, !!1>::.ctor(object, native int)
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Func`3<!!0, !!1, !!2> ToFunc2<P0, P1, R>(class Kotlin.Function2 callable) cil managed
            |    {
            |      .maxstack 3
            |      .locals init ([0] class Kotlin.Runtime.Internal.'ExactFunction2`3'<!!0, !!1, !!2> exact)
            |      ldarg.0
            |      isinst class Kotlin.Runtime.Internal.'Func2Adapter`3'<!!0, !!1, !!2>
            |      dup
            |      brfalse.s project
            |      ldfld class ${coreLibraryReference}System.Func`3<!0, !1, !2> class Kotlin.Runtime.Internal.'Func2Adapter`3'<!!0, !!1, !!2>::'delegate'
            |      ret
            |    project:
            |      pop
            |      ldarg.0
            |      isinst class Kotlin.Runtime.Internal.'ExactFunction2`3'<!!0, !!1, !!2>
            |      stloc.0
            |      ldloc.0
            |      brfalse.s fallback
            |      ldloc.0
            |      ldloc.0
            |      ldvirtftn instance !2 class Kotlin.Runtime.Internal.'ExactFunction2`3'<!!0, !!1, !!2>::InvokeExact(!0, !1)
            |      newobj instance void class ${coreLibraryReference}System.Func`3<!!0, !!1, !!2>::.ctor(object, native int)
            |      ret
            |    fallback:
            |      ldarg.0
            |      ldftn !!2 Kotlin.Runtime.Internal.DelegateProjection::Invoke2<!!0, !!1, !!2>(class Kotlin.Function2, !!0, !!1)
            |      newobj instance void class ${coreLibraryReference}System.Func`3<!!0, !!1, !!2>::.ctor(object, native int)
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Action ToAction0(class Kotlin.Function0 callable) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst Kotlin.Runtime.Internal.Action0Adapter
            |      dup
            |      brfalse.s project
            |      ldfld class ${coreLibraryReference}System.Action Kotlin.Runtime.Internal.Action0Adapter::'delegate'
            |      ret
            |    project:
            |      pop
            |      ldarg.0
            |      ldftn void Kotlin.Runtime.Internal.DelegateProjection::InvokeUnit0(class Kotlin.Function0)
            |      newobj instance void ${coreLibraryReference}System.Action::.ctor(object, native int)
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Action`1<!!0> ToAction1<P0>(class Kotlin.Function1 callable) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst class Kotlin.Runtime.Internal.'Action1Adapter`1'<!!0>
            |      dup
            |      brfalse.s project
            |      ldfld class ${coreLibraryReference}System.Action`1<!0> class Kotlin.Runtime.Internal.'Action1Adapter`1'<!!0>::'delegate'
            |      ret
            |    project:
            |      pop
            |      ldarg.0
            |      ldftn void Kotlin.Runtime.Internal.DelegateProjection::InvokeUnit1<!!0>(class Kotlin.Function1, !!0)
            |      newobj instance void class ${coreLibraryReference}System.Action`1<!!0>::.ctor(object, native int)
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Action`2<!!0, !!1> ToAction2<P0, P1>(class Kotlin.Function2 callable) cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst class Kotlin.Runtime.Internal.'Action2Adapter`2'<!!0, !!1>
            |      dup
            |      brfalse.s project
            |      ldfld class ${coreLibraryReference}System.Action`2<!0, !1> class Kotlin.Runtime.Internal.'Action2Adapter`2'<!!0, !!1>::'delegate'
            |      ret
            |    project:
            |      pop
            |      ldarg.0
            |      ldftn void Kotlin.Runtime.Internal.DelegateProjection::InvokeUnit2<!!0, !!1>(class Kotlin.Function2, !!0, !!1)
            |      newobj instance void class ${coreLibraryReference}System.Action`2<!!0, !!1>::.ctor(object, native int)
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Func`1<!!0> ToNullableFunc0<R>(class Kotlin.Function0 callable) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brtrue.s nonnull
            |      ldnull
            |      ret
            |    nonnull:
            |      ldarg.0
            |      call class ${coreLibraryReference}System.Func`1<!!0> Kotlin.Runtime.Internal.DelegateProjection::ToFunc0<!!0>(class Kotlin.Function0)
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Func`2<!!0, !!1> ToNullableFunc1<P0, R>(class Kotlin.Function1 callable) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brtrue.s nonnull
            |      ldnull
            |      ret
            |    nonnull:
            |      ldarg.0
            |      call class ${coreLibraryReference}System.Func`2<!!0, !!1> Kotlin.Runtime.Internal.DelegateProjection::ToFunc1<!!0, !!1>(class Kotlin.Function1)
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Func`3<!!0, !!1, !!2> ToNullableFunc2<P0, P1, R>(class Kotlin.Function2 callable) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brtrue.s nonnull
            |      ldnull
            |      ret
            |    nonnull:
            |      ldarg.0
            |      call class ${coreLibraryReference}System.Func`3<!!0, !!1, !!2> Kotlin.Runtime.Internal.DelegateProjection::ToFunc2<!!0, !!1, !!2>(class Kotlin.Function2)
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Action ToNullableAction0(class Kotlin.Function0 callable) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brtrue.s nonnull
            |      ldnull
            |      ret
            |    nonnull:
            |      ldarg.0
            |      call class ${coreLibraryReference}System.Action Kotlin.Runtime.Internal.DelegateProjection::ToAction0(class Kotlin.Function0)
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Action`1<!!0> ToNullableAction1<P0>(class Kotlin.Function1 callable) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brtrue.s nonnull
            |      ldnull
            |      ret
            |    nonnull:
            |      ldarg.0
            |      call class ${coreLibraryReference}System.Action`1<!!0> Kotlin.Runtime.Internal.DelegateProjection::ToAction1<!!0>(class Kotlin.Function1)
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Action`2<!!0, !!1> ToNullableAction2<P0, P1>(class Kotlin.Function2 callable) cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brtrue.s nonnull
            |      ldnull
            |      ret
            |    nonnull:
            |      ldarg.0
            |      call class ${coreLibraryReference}System.Action`2<!!0, !!1> Kotlin.Runtime.Internal.DelegateProjection::ToAction2<!!0, !!1>(class Kotlin.Function2)
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
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .field public !0 'element'
            |
            |    .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ret
            |    }
            |  }
            |
            |  // The only runtime authority for Kotlin logical exception membership. Broad
            |  // categories cannot be represented by the CLR inheritance tree without either
            |  // wrapping foreign exceptions or collapsing Kotlin's Exception/Error split.
            |  // Keep this method allocation-free and nonthrowing: generated IL calls it from
            |  // first-pass CLR exception filters.
            |  .class public abstract sealed auto ansi beforefieldinit ExceptionClassifier
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method public hidebysig static bool 'IsKotlinExceptionInstance'(
            |        class ${coreLibraryReference}System.Exception 'value', int32 'targetTypeId') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      brfalse EC_False
            |
            |      ldarg.1
            |      ldc.i4.1
            |      beq EC_True
            |      ldarg.1
            |      ldc.i4.2
            |      beq EC_Exception
            |      ldarg.1
            |      ldc.i4.3
            |      beq EC_RuntimeException
            |      ldarg.1
            |      ldc.i4.4
            |      beq EC_Error
            |      ldarg.1
            |      ldc.i4.5
            |      beq EC_IllegalArgument
            |      ldarg.1
            |      ldc.i4.6
            |      beq EC_IllegalState
            |      ldarg.1
            |      ldc.i4.7
            |      beq EC_UnsupportedOperation
            |      ldarg.1
            |      ldc.i4.8
            |      beq EC_NoSuchElement
            |      ldarg.1
            |      ldc.i4.s 9
            |      beq EC_IndexOutOfBounds
            |      ldarg.1
            |      ldc.i4.s 10
            |      beq EC_Arithmetic
            |      ldarg.1
            |      ldc.i4.s 11
            |      beq EC_NumberFormat
            |      ldarg.1
            |      ldc.i4.s 12
            |      beq EC_NullPointer
            |      ldarg.1
            |      ldc.i4.s 13
            |      beq EC_ClassCast
            |      br EC_False
            |
            |    EC_Exception:
            |      ldarg.0
            |      call bool Kotlin.Runtime.Internal.ExceptionClassifier::IsError(
            |          class ${coreLibraryReference}System.Exception)
            |      ldc.i4.0
            |      ceq
            |      ret
            |
            |    EC_RuntimeException:
            |      ldarg.0
            |      isinst Kotlin.RuntimeException
            |      brtrue EC_True
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.ArgumentException
            |      brtrue EC_True
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.InvalidOperationException
            |      brtrue EC_True
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.NotSupportedException
            |      brtrue EC_True
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.ArithmeticException
            |      brtrue EC_True
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.IndexOutOfRangeException
            |      brtrue EC_True
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.NullReferenceException
            |      brtrue EC_True
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.InvalidCastException
            |      brtrue EC_True
            |      br EC_False
            |
            |    EC_Error:
            |      ldarg.0
            |      call bool Kotlin.Runtime.Internal.ExceptionClassifier::IsError(
            |          class ${coreLibraryReference}System.Exception)
            |      ret
            |
            |    EC_IllegalArgument:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.ArgumentException
            |      br EC_MatchedObject
            |    EC_IllegalState:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.InvalidOperationException
            |      br EC_MatchedObject
            |    EC_UnsupportedOperation:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.NotSupportedException
            |      br EC_MatchedObject
            |    EC_NoSuchElement:
            |      ldarg.0
            |      isinst Kotlin.NoSuchElementException
            |      br EC_MatchedObject
            |    EC_IndexOutOfBounds:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.IndexOutOfRangeException
            |      br EC_MatchedObject
            |    EC_Arithmetic:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.ArithmeticException
            |      br EC_MatchedObject
            |    EC_NumberFormat:
            |      ldarg.0
            |      isinst Kotlin.NumberFormatException
            |      br EC_MatchedObject
            |    EC_NullPointer:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.NullReferenceException
            |      br EC_MatchedObject
            |    EC_ClassCast:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.InvalidCastException
            |    EC_MatchedObject:
            |      ldnull
            |      cgt.un
            |      ret
            |
            |    EC_True:
            |      ldc.i4.1
            |      ret
            |    EC_False:
            |      ldc.i4.0
            |      ret
            |    }
            |
            |    .method private hidebysig static bool IsError(
            |        class ${coreLibraryReference}System.Exception 'value') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      isinst Kotlin.Error
            |      brtrue EC_IsError_True
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.OutOfMemoryException
            |      brtrue EC_IsError_True
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.StackOverflowException
            |      brtrue EC_IsError_True
            |      ldc.i4.0
            |      ret
            |    EC_IsError_True:
            |      ldc.i4.1
            |      ret
            |    }
            |  }
            |
            |  .class public abstract sealed auto ansi beforefieldinit Intrinsics
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    $compilerAbiTypeAttributesIl
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
            |      isinst ${coreLibraryReference}System.Double
            |      brfalse.s IL_objectEquals
            |      ldarg.1
            |      isinst ${coreLibraryReference}System.Double
            |      brfalse.s IL_notEqual
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Double
            |      stloc.0
            |      ldarg.1
            |      unbox.any ${coreLibraryReference}System.Double
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
            |      callvirt instance bool ${coreLibraryReference}System.Object::Equals(object)
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
            |      isinst ${coreLibraryReference}System.Double
            |      brfalse.s IL_hashBoolean
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Double
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
            |      isinst ${coreLibraryReference}System.Boolean
            |      brfalse.s IL_hashChar
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Boolean
            |      brtrue.s IL_hashTrue
            |      ldc.i4 1237
            |      ret
            |IL_hashTrue:
            |      ldc.i4 1231
            |      ret
            |IL_hashChar:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Char
            |      brfalse.s IL_objectHash
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Char
            |      conv.i4
            |      ret
            |IL_objectHash:
            |      ldarg.0
            |      callvirt instance int32 ${coreLibraryReference}System.Object::GetHashCode()
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
            |      isinst ${coreLibraryReference}System.Boolean
            |      brfalse.s IL_stringDouble
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Boolean
            |      brtrue.s IL_stringTrue
            |      ldstr "false"
            |      ret
            |IL_stringTrue:
            |      ldstr "true"
            |      ret
            |IL_stringDouble:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Double
            |      brfalse.s IL_stringInt
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Double
            |      call string 'Kotlin.Runtime.Internal.DoubleFormatting'::'DoubleToString'(float64)
            |      ret
            |IL_stringInt:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int32
            |      brtrue.s IL_stringInvariant
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int64
            |      brtrue.s IL_stringInvariant
            |      ldarg.0
            |      callvirt instance string ${coreLibraryReference}System.Object::ToString()
            |      ret
            |IL_stringInvariant:
            |      ldarg.0
            |      ldnull
            |      call class ${coreLibraryReference}System.Globalization.CultureInfo ${coreLibraryReference}System.Globalization.CultureInfo::get_InvariantCulture()
            |      callvirt instance string ${coreLibraryReference}System.IFormattable::ToString(string, class ${coreLibraryReference}System.IFormatProvider)
            |      ret
            |    }
            |
            |    .method public hidebysig static bool 'ArrayContentEquals'(
            |        class ${coreLibraryReference}System.Array 'left',
            |        class ${coreLibraryReference}System.Array 'right') cil managed
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
            |      callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()
            |      stloc.1
            |      ldloc.1
            |      ldarg.1
            |      callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()
            |      bne.un.s IL_arrayContentFalse
            |      ldc.i4.0
            |      stloc.0
            |IL_arrayContentLoop:
            |      ldloc.0
            |      ldloc.1
            |      bge.s IL_arrayContentTrue
            |      ldarg.0
            |      ldloc.0
            |      callvirt instance object ${coreLibraryReference}System.Array::GetValue(int32)
            |      ldarg.1
            |      ldloc.0
            |      callvirt instance object ${coreLibraryReference}System.Array::GetValue(int32)
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
            |        class ${coreLibraryReference}System.Array 'left',
            |        class ${coreLibraryReference}System.Array 'right') cil managed
            |    {
            |      .maxstack 3
            |      .locals init (
            |        [0] int32 'index',
            |        [1] int32 'length',
            |        [2] object 'leftValue',
            |        [3] object 'rightValue',
            |        [4] class ${coreLibraryReference}System.Array 'leftArray',
            |        [5] class ${coreLibraryReference}System.Array 'rightArray'
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
            |      callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()
            |      stloc.1
            |      ldloc.1
            |      ldarg.1
            |      callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()
            |      bne.un IL_arrayDeepFalse
            |      ldc.i4.0
            |      stloc.0
            |IL_arrayDeepLoop:
            |      ldloc.0
            |      ldloc.1
            |      bge IL_arrayDeepTrue
            |      ldarg.0
            |      ldloc.0
            |      callvirt instance object ${coreLibraryReference}System.Array::GetValue(int32)
            |      stloc.2
            |      ldarg.1
            |      ldloc.0
            |      callvirt instance object ${coreLibraryReference}System.Array::GetValue(int32)
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
            |      ldloc.3
            |      call class ${coreLibraryReference}System.Array 'Kotlin.Runtime.Internal.PrimitiveArrays'::'GetStorageOrNull'(object)
            |      stloc.s 5
            |      call class ${coreLibraryReference}System.Array 'Kotlin.Runtime.Internal.PrimitiveArrays'::'GetStorageOrNull'(object)
            |      stloc.s 4
            |      ldloc.s 4
            |      brfalse IL_arrayDeepScalar
            |      ldloc.s 5
            |      brfalse IL_arrayDeepFalse
            |      ldloc.s 4
            |      isinst object[]
            |      brfalse IL_arrayDeepShallow
            |      ldloc.s 4
            |      ldloc.s 5
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentDeepEquals'(
            |          class ${coreLibraryReference}System.Array, class ${coreLibraryReference}System.Array)
            |      brfalse IL_arrayDeepFalse
            |      br IL_arrayDeepNext
            |IL_arrayDeepShallow:
            |      ldloc.s 4
            |      ldloc.s 5
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentEquals'(
            |          class ${coreLibraryReference}System.Array, class ${coreLibraryReference}System.Array)
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
            |    .method public hidebysig static int32 'ArrayContentHashCode'(class ${coreLibraryReference}System.Array 'value') cil managed
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
            |      callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()
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
            |      callvirt instance object ${coreLibraryReference}System.Array::GetValue(int32)
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
            |        class ${coreLibraryReference}System.Array 'value') cil managed
            |    {
            |      .maxstack 3
            |      .locals init (
            |        [0] int32 'result',
            |        [1] int32 'index',
            |        [2] int32 'length',
            |        [3] object 'element',
            |        [4] int32 'elementHash',
            |        [5] class ${coreLibraryReference}System.Array 'elementArray'
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
            |      callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()
            |      stloc.2
            |IL_arrayDeepHashLoop:
            |      ldloc.1
            |      ldloc.2
            |      bge IL_arrayDeepHashEnd
            |      ldarg.0
            |      ldloc.1
            |      callvirt instance object ${coreLibraryReference}System.Array::GetValue(int32)
            |      stloc.3
            |      ldloc.3
            |      call class ${coreLibraryReference}System.Array 'Kotlin.Runtime.Internal.PrimitiveArrays'::'GetStorageOrNull'(object)
            |      stloc.s 5
            |      ldloc.s 5
            |      brfalse IL_arrayDeepHashScalar
            |      ldloc.s 5
            |      isinst object[]
            |      brfalse IL_arrayDeepHashShallow
            |      ldloc.s 5
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentDeepHashCode'(
            |          class ${coreLibraryReference}System.Array)
            |      stloc.s 4
            |      br IL_arrayDeepHashCombine
            |IL_arrayDeepHashShallow:
            |      ldloc.s 5
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentHashCode'(
            |          class ${coreLibraryReference}System.Array)
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
            |        class ${coreLibraryReference}System.Array 'value') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentHashCode'(
            |          class ${coreLibraryReference}System.Array)
            |      ret
            |    }
            |
            |    .method public hidebysig static string 'ArrayContentToString'(class ${coreLibraryReference}System.Array 'value') cil managed
            |    {
            |      .maxstack 3
            |      .locals init (
            |        [0] class ${coreLibraryReference}System.Text.StringBuilder 'builder',
            |        [1] int32 'index',
            |        [2] int32 'length'
            |      )
            |      ldarg.0
            |      brtrue.s IL_arrayStringNotNull
            |      ldstr "null"
            |      ret
            |IL_arrayStringNotNull:
            |      newobj instance void ${coreLibraryReference}System.Text.StringBuilder::.ctor()
            |      stloc.0
            |      ldloc.0
            |      ldstr "["
            |      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
            |      pop
            |      ldc.i4.0
            |      stloc.1
            |      ldarg.0
            |      callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()
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
            |      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
            |      pop
            |IL_arrayStringElement:
            |      ldloc.0
            |      ldarg.0
            |      ldloc.1
            |      callvirt instance object ${coreLibraryReference}System.Array::GetValue(int32)
            |      call string 'Kotlin.Runtime.Internal.Intrinsics'::'StringValueOf'(object)
            |      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
            |      pop
            |      ldloc.1
            |      ldc.i4.1
            |      add
            |      stloc.1
            |      br.s IL_arrayStringLoop
            |IL_arrayStringEnd:
            |      ldloc.0
            |      ldstr "]"
            |      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
            |      pop
            |      ldloc.0
            |      callvirt instance string ${coreLibraryReference}System.Object::ToString()
            |      ret
            |    }
            |
            |    .method private hidebysig static void 'AppendArrayContentDeepToString'(
            |        class ${coreLibraryReference}System.Array 'value',
            |        class ${coreLibraryReference}System.Text.StringBuilder 'builder',
            |        class ${coreLibraryReference}System.Collections.ArrayList 'processed') cil managed
            |    {
            |      .maxstack 4
            |      .locals init (
            |        [0] int32 'index',
            |        [1] int32 'length',
            |        [2] object 'element',
            |        [3] class ${coreLibraryReference}System.Array 'elementArray'
            |      )
            |      ldarg.2
            |      ldarg.0
            |      callvirt instance bool ${coreLibraryReference}System.Collections.ArrayList::Contains(object)
            |      brfalse IL_arrayDeepStringNew
            |      ldarg.1
            |      ldstr "[...]"
            |      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
            |      pop
            |      ret
            |IL_arrayDeepStringNew:
            |      ldarg.2
            |      ldarg.0
            |      callvirt instance int32 ${coreLibraryReference}System.Collections.ArrayList::Add(object)
            |      pop
            |      ldarg.1
            |      ldstr "["
            |      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
            |      pop
            |      ldc.i4.0
            |      stloc.0
            |      ldarg.0
            |      callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()
            |      stloc.1
            |IL_arrayDeepStringLoop:
            |      ldloc.0
            |      ldloc.1
            |      bge IL_arrayDeepStringEnd
            |      ldloc.0
            |      brfalse IL_arrayDeepStringLoad
            |      ldarg.1
            |      ldstr ", "
            |      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
            |      pop
            |IL_arrayDeepStringLoad:
            |      ldarg.0
            |      ldloc.0
            |      callvirt instance object ${coreLibraryReference}System.Array::GetValue(int32)
            |      stloc.2
            |      ldloc.2
            |      call class ${coreLibraryReference}System.Array 'Kotlin.Runtime.Internal.PrimitiveArrays'::'GetStorageOrNull'(object)
            |      stloc.3
            |      ldloc.3
            |      brfalse IL_arrayDeepStringScalar
            |      ldloc.3
            |      isinst object[]
            |      brfalse IL_arrayDeepStringShallow
            |      ldloc.3
            |      ldarg.1
            |      ldarg.2
            |      call void 'Kotlin.Runtime.Internal.Intrinsics'::'AppendArrayContentDeepToString'(
            |          class ${coreLibraryReference}System.Array,
            |          class ${coreLibraryReference}System.Text.StringBuilder,
            |          class ${coreLibraryReference}System.Collections.ArrayList)
            |      br IL_arrayDeepStringNext
            |IL_arrayDeepStringShallow:
            |      ldarg.1
            |      ldloc.3
            |      call string 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentToString'(
            |          class ${coreLibraryReference}System.Array)
            |      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
            |      pop
            |      br IL_arrayDeepStringNext
            |IL_arrayDeepStringScalar:
            |      ldarg.1
            |      ldloc.2
            |      call string 'Kotlin.Runtime.Internal.Intrinsics'::'StringValueOf'(object)
            |      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
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
            |      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
            |      pop
            |      ldarg.2
            |      ldarg.2
            |      callvirt instance int32 ${coreLibraryReference}System.Collections.ArrayList::get_Count()
            |      ldc.i4.1
            |      sub
            |      callvirt instance void ${coreLibraryReference}System.Collections.ArrayList::RemoveAt(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig static string 'ArrayContentDeepToString'(
            |        class ${coreLibraryReference}System.Array 'value') cil managed
            |    {
            |      .maxstack 3
            |      .locals init (
            |        [0] class ${coreLibraryReference}System.Text.StringBuilder 'builder',
            |        [1] class ${coreLibraryReference}System.Collections.ArrayList 'processed'
            |      )
            |      ldarg.0
            |      brtrue IL_arrayDeepStringNotNull
            |      ldstr "null"
            |      ret
            |IL_arrayDeepStringNotNull:
            |      newobj instance void ${coreLibraryReference}System.Text.StringBuilder::.ctor()
            |      stloc.0
            |      newobj instance void ${coreLibraryReference}System.Collections.ArrayList::.ctor()
            |      stloc.1
            |      ldarg.0
            |      ldloc.0
            |      ldloc.1
            |      call void 'Kotlin.Runtime.Internal.Intrinsics'::'AppendArrayContentDeepToString'(
            |          class ${coreLibraryReference}System.Array,
            |          class ${coreLibraryReference}System.Text.StringBuilder,
            |          class ${coreLibraryReference}System.Collections.ArrayList)
            |      ldloc.0
            |      callvirt instance string ${coreLibraryReference}System.Object::ToString()
            |      ret
            |    }
            |
            |    .method public hidebysig static string 'DataClassArrayToString'(
            |        class ${coreLibraryReference}System.Array 'value') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      call string 'Kotlin.Runtime.Internal.Intrinsics'::'ArrayContentToString'(
            |          class ${coreLibraryReference}System.Array)
            |      ret
            |    }
            |
            |    .method public hidebysig static void 'ArrayCopyInto'(
            |        class ${coreLibraryReference}System.Array 'source',
            |        class ${coreLibraryReference}System.Array 'destination',
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
            |      callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()
            |      bgt.s IL_arrayCopyInvalid
            |      ldarg.2
            |      ldc.i4.0
            |      blt.s IL_arrayCopyInvalid
            |      ldarg.2
            |      ldarg.1
            |      callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()
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
            |      call void ${coreLibraryReference}System.Array::Copy(
            |          class ${coreLibraryReference}System.Array,
            |          int32,
            |          class ${coreLibraryReference}System.Array,
            |          int32,
            |          int32)
            |      ret
            |IL_arrayCopyInvalid:
            |      newobj instance void ${coreLibraryReference}System.IndexOutOfRangeException::.ctor()
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
            |      call int64 ${coreLibraryReference}System.BitConverter::DoubleToInt64Bits(float64)
            |      ret
            |    }
            |  }
            |
            |  .class public abstract sealed auto ansi beforefieldinit DoubleFormatting
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    $compilerAbiTypeAttributesIl
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
            |    call bool ${coreLibraryReference}System.Double::IsNaN(float64)
            |    brfalse IL_notNaN
            |    ldstr "NaN"
            |    ret
            |IL_notNaN:
            |    ldarg.0
            |    call bool ${coreLibraryReference}System.Double::IsPositiveInfinity(float64)
            |    brfalse IL_notPositiveInfinity
            |    ldstr "Infinity"
            |    ret
            |IL_notPositiveInfinity:
            |    ldarg.0
            |    call bool ${coreLibraryReference}System.Double::IsNegativeInfinity(float64)
            |    brfalse IL_notNegativeInfinity
            |    ldstr "-Infinity"
            |    ret
            |IL_notNegativeInfinity:
            |    ldarg.0
            |    call int64 ${coreLibraryReference}System.BitConverter::DoubleToInt64Bits(float64)
            |    ldc.i8 -9223372036854775808
            |    bne.un IL_finite
            |    ldstr "-0.0"
            |    ret
            |IL_finite:
            |    ldarga.s 0
            |    ldstr "R"
            |    call class ${coreLibraryReference}System.Globalization.CultureInfo ${coreLibraryReference}System.Globalization.CultureInfo::get_InvariantCulture()
            |    call instance string ${coreLibraryReference}System.Double::ToString(string, class ${coreLibraryReference}System.IFormatProvider)
            |    stloc.0
            |    ldarg.0
            |    ldc.r8 0.0
            |    beq IL_decimal
            |    ldarg.0
            |    call float64 ${coreLibraryReference}System.Math::Abs(float64)
            |    ldc.r8 10000000.
            |    bge IL_scientific
            |    ldarg.0
            |    call float64 ${coreLibraryReference}System.Math::Abs(float64)
            |    ldc.r8 0.001
            |    blt IL_scientific
            |IL_decimal:
            |    ldloc.0
            |    ldc.i4.s 46
            |    callvirt instance int32 ${coreLibraryReference}System.String::IndexOf(char)
            |    ldc.i4.0
            |    bge IL_decimalHasDot
            |    ldloc.0
            |    ldstr ".0"
            |    call string ${coreLibraryReference}System.String::Concat(string, string)
            |    ret
            |IL_decimalHasDot:
            |    ldloc.0
            |    ret
            |IL_scientific:
            |    ldloc.0
            |    ldc.i4.s 69
            |    callvirt instance int32 ${coreLibraryReference}System.String::IndexOf(char)
            |    stloc.1
            |    ldloc.1
            |    ldc.i4.0
            |    bge IL_scientificFromE
            |    ldloc.0
            |    ldc.i4.0
            |    callvirt instance char ${coreLibraryReference}System.String::get_Chars(int32)
            |    ldc.i4.s 45
            |    ceq
            |    stloc.s 'neg'
            |    ldloc.s 'neg'
            |    brfalse IL_signStripped
            |    ldloc.0
            |    ldc.i4.1
            |    callvirt instance string ${coreLibraryReference}System.String::Substring(int32)
            |    stloc.0
            |IL_signStripped:
            |    ldloc.0
            |    ldc.i4.s 46
            |    callvirt instance int32 ${coreLibraryReference}System.String::IndexOf(char)
            |    stloc.1
            |    ldloc.1
            |    ldc.i4.0
            |    bge IL_removeDot
            |    ldloc.0
            |    stloc.2
            |    ldloc.0
            |    callvirt instance int32 ${coreLibraryReference}System.String::get_Length()
            |    stloc.3
            |    br IL_dotRemoved
            |IL_removeDot:
            |    ldloc.0
            |    ldloc.1
            |    ldc.i4.1
            |    callvirt instance string ${coreLibraryReference}System.String::Remove(int32, int32)
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
            |    callvirt instance char ${coreLibraryReference}System.String::get_Chars(int32)
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
            |    callvirt instance string ${coreLibraryReference}System.String::Substring(int32)
            |    stloc.2
            |    ldloc.2
            |    callvirt instance int32 ${coreLibraryReference}System.String::get_Length()
            |    stloc.s 'i'
            |IL_trailingZeroLoop:
            |    ldloc.s 'i'
            |    ldc.i4.1
            |    ble IL_trailingZerosTrimmed
            |    ldloc.2
            |    ldloc.s 'i'
            |    ldc.i4.1
            |    sub
            |    callvirt instance char ${coreLibraryReference}System.String::get_Chars(int32)
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
            |    callvirt instance string ${coreLibraryReference}System.String::Substring(int32, int32)
            |    stloc.2
            |    ldloc.2
            |    callvirt instance int32 ${coreLibraryReference}System.String::get_Length()
            |    ldc.i4.1
            |    bne.un IL_insertDot
            |    ldloc.2
            |    ldstr "0"
            |    call string ${coreLibraryReference}System.String::Concat(string, string)
            |    stloc.2
            |IL_insertDot:
            |    ldloc.2
            |    ldc.i4.1
            |    ldstr "."
            |    callvirt instance string ${coreLibraryReference}System.String::Insert(int32, string)
            |    stloc.2
            |    ldloc.s 'neg'
            |    brfalse IL_mantissaSigned
            |    ldstr "-"
            |    ldloc.2
            |    call string ${coreLibraryReference}System.String::Concat(string, string)
            |    stloc.2
            |IL_mantissaSigned:
            |    ldloc.2
            |    ldstr "E"
            |    ldloc.3
            |    box ${coreLibraryReference}System.Int32
            |    ldnull
            |    call class ${coreLibraryReference}System.Globalization.CultureInfo ${coreLibraryReference}System.Globalization.CultureInfo::get_InvariantCulture()
            |    callvirt instance string ${coreLibraryReference}System.IFormattable::ToString(string, class ${coreLibraryReference}System.IFormatProvider)
            |    call string ${coreLibraryReference}System.String::Concat(string, string, string)
            |    ret
            |IL_scientificFromE:
            |    ldloc.0
            |    ldc.i4.0
            |    ldloc.1
            |    callvirt instance string ${coreLibraryReference}System.String::Substring(int32, int32)
            |    dup
            |    ldc.i4.s 46
            |    callvirt instance int32 ${coreLibraryReference}System.String::IndexOf(char)
            |    ldc.i4.0
            |    bge IL_mantissaHasDot
            |    ldstr ".0"
            |    call string ${coreLibraryReference}System.String::Concat(string, string)
            |IL_mantissaHasDot:
            |    ldstr "E"
            |    ldloc.0
            |    ldloc.1
            |    ldc.i4.1
            |    add
            |    callvirt instance string ${coreLibraryReference}System.String::Substring(int32)
            |    call class ${coreLibraryReference}System.Globalization.CultureInfo ${coreLibraryReference}System.Globalization.CultureInfo::get_InvariantCulture()
            |    call int32 ${coreLibraryReference}System.Int32::Parse(string, class ${coreLibraryReference}System.IFormatProvider)
            |    box ${coreLibraryReference}System.Int32
            |    ldnull
            |    call class ${coreLibraryReference}System.Globalization.CultureInfo ${coreLibraryReference}System.Globalization.CultureInfo::get_InvariantCulture()
            |    callvirt instance string ${coreLibraryReference}System.IFormattable::ToString(string, class ${coreLibraryReference}System.IFormatProvider)
            |    call string ${coreLibraryReference}System.String::Concat(string, string, string)
            |    ret
            |  }
            |  }
            |}
            |
        """.trimMargin()
    }

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
    fun arrayContentEqualsCallInstruction(coreLibraryReference: String): String =
        "call bool [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayContentEquals".toIlIdentifier()}(" +
                "class ${coreLibraryReference}System.Array, class ${coreLibraryReference}System.Array)"

    /** Nullable recursive content equality for generic arrays and their supported nested arrays. */
    fun arrayContentDeepEqualsCallInstruction(coreLibraryReference: String): String =
        "call bool [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayContentDeepEquals".toIlIdentifier()}(" +
                "class ${coreLibraryReference}System.Array, class ${coreLibraryReference}System.Array)"

    /** Nullable shallow List-compatible content hash for every supported CLR vector. */
    fun arrayContentHashCodeCallInstruction(coreLibraryReference: String): String =
        "call int32 [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayContentHashCode".toIlIdentifier()}(class ${coreLibraryReference}System.Array)"

    /** Nullable recursive List-compatible content hash for generic arrays. */
    fun arrayContentDeepHashCodeCallInstruction(coreLibraryReference: String): String =
        "call int32 [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayContentDeepHashCode".toIlIdentifier()}(class ${coreLibraryReference}System.Array)"

    /** Nullable shallow List-compatible content rendering for every supported CLR vector. */
    fun arrayContentToStringCallInstruction(coreLibraryReference: String): String =
        "call string [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayContentToString".toIlIdentifier()}(class ${coreLibraryReference}System.Array)"

    /** Nullable recursive List-compatible content rendering for generic arrays. */
    fun arrayContentDeepToStringCallInstruction(coreLibraryReference: String): String =
        "call string [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayContentDeepToString".toIlIdentifier()}(class ${coreLibraryReference}System.Array)"

    /** Content hash for the CLR vector behind an array property of a generated data class. */
    fun dataClassArrayHashCodeCallInstruction(coreLibraryReference: String): String =
        "call int32 [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"DataClassArrayHashCode".toIlIdentifier()}(class ${coreLibraryReference}System.Array)"

    /** Content rendering for the CLR vector behind an array property of a generated data class. */
    fun dataClassArrayToStringCallInstruction(coreLibraryReference: String): String =
        "call string [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"DataClassArrayToString".toIlIdentifier()}(class ${coreLibraryReference}System.Array)"

    /** Kotlin range validation plus overlap-safe CLR copying for `Array.copyInto`. */
    fun arrayCopyIntoCallInstruction(coreLibraryReference: String): String =
        "call void [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayCopyInto".toIlIdentifier()}(" +
                "class ${coreLibraryReference}System.Array, class ${coreLibraryReference}System.Array, int32, int32, int32)"

}
