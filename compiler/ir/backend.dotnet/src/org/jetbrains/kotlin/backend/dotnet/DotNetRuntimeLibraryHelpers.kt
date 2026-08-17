package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.builtins.functions.BuiltInFunctionArity

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
     * `static string FloatToString(float32)` — deterministic Kotlin-shaped `Float.toString()`.
     *
     * `System.Single.ToString("R", InvariantCulture)` is not stable across the supported
     * Framework and CoreCLR profiles for the same bits. This helper instead tries invariant `G7`,
     * accepts it only when parsing reproduces the canonical input bits, and otherwise uses the
     * guaranteed-roundtrip `G9` representation. Special values, signed zero, exponent spelling,
     * the decimal/scientific threshold, and integral `.0` are normalized explicitly. This keeps
     * culture, runtime profile, primitive rendering, and boxed-Any rendering aligned; it does not
     * claim the JVM's minimum distinguishing digit sequence where `G9` is required.
     *
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
     * Besides mutable capture storage and Float/Double formatting, this text owns escaping array
     * iterator storage, the universal Any operations, and the explicit-export delegate
     * projection thunks. Those thunks are called only by generated CLR facade methods; their
     * metadata visibility is cross-assembly compiler/runtime access, not Kotlin callable identity.
     * The iterator deliberately stores a
     * `System.Array` and returns object: Kotlin's logical element type remains compiler metadata,
     * while one erased object preserves identity across Kotlin's legal covariant iterator views.
     * The Any primitive branches are semantic, not optimizations: CLR boxed Boolean hashes/string
     * text, boxed Char hashes, and boxed Float/Double signed-zero/hash/string behavior differ from
     * Kotlin's JVM-backed object contract; Framework also preserves NaN payloads in floating
     * `GetHashCode` implementations.
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
        val throwableSupportTypesIl = DotNetThrowableRuntime.supportTypesIl(
            coreLibraryReference,
            compilerAbiTypeAttributesIl.replace("            |", ""),
        ).prependIndent("            |")
        val kClassSupportTypesIl = DotNetKClassRuntime.supportTypesIl(
            coreLibraryReference,
            compilerAbiTypeAttributesIl.replace("            |", ""),
        ).prependIndent("            |")
        val propertyAccessorSupportTypesIl = propertyAccessorSupportTypesIl(coreLibraryReference)
            .prependIndent("            |")
        val memberReferenceSupportTypesIl = memberReferenceSupportTypesIl(
            coreLibraryReference,
            compilerAbiTypeAttributesIl.replace("            |", ""),
        ).prependIndent("            |")
        val callableInvokerSwitchLabelsIl = (0 until BuiltInFunctionArity.BIG_ARITY)
            .joinToString(", ") { arity -> "CI_Call$arity" }
        val callableInvokerCasesIl = (0 until BuiltInFunctionArity.BIG_ARITY)
            .joinToString("\n") { arity -> callableInvokerCaseIl(arity) }
        return """
            |.namespace Kotlin.Runtime.Internal
            |{
$primitiveArrayHelperTypeIl
            |
$throwableSupportTypesIl
            |
$kClassSupportTypesIl
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
            |  .class interface public abstract auto ansi FunctionAdapter
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method public hidebysig newslot abstract virtual instance class Kotlin.Function getFunctionDelegate() cil managed
            |    {
            |    }
            |  }
            |
            |  .class private abstract sealed auto ansi beforefieldinit CallableDeclarationFacts
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    .method assembly hidebysig static class Kotlin.KVisibility Visibility(int32 'flags') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldc.i4 ${DotNetCallableDeclarationFlags.VISIBILITY_PUBLIC}
            |      and
            |      brfalse.s CDF_VisibilityProtected
            |      call void Kotlin.KVisibility::'<EnsureInitialized>'()
            |      ldsfld class Kotlin.KVisibility Kotlin.KVisibility::PUBLIC
            |      ret
            |    CDF_VisibilityProtected:
            |      ldarg.0
            |      ldc.i4 ${DotNetCallableDeclarationFlags.VISIBILITY_PROTECTED}
            |      and
            |      brfalse.s CDF_VisibilityInternal
            |      call void Kotlin.KVisibility::'<EnsureInitialized>'()
            |      ldsfld class Kotlin.KVisibility Kotlin.KVisibility::PROTECTED
            |      ret
            |    CDF_VisibilityInternal:
            |      ldarg.0
            |      ldc.i4 ${DotNetCallableDeclarationFlags.VISIBILITY_INTERNAL}
            |      and
            |      brfalse.s CDF_VisibilityPrivate
            |      call void Kotlin.KVisibility::'<EnsureInitialized>'()
            |      ldsfld class Kotlin.KVisibility Kotlin.KVisibility::INTERNAL
            |      ret
            |    CDF_VisibilityPrivate:
            |      ldarg.0
            |      ldc.i4 ${DotNetCallableDeclarationFlags.VISIBILITY_PRIVATE}
            |      and
            |      brfalse.s CDF_VisibilityUnknown
            |      call void Kotlin.KVisibility::'<EnsureInitialized>'()
            |      ldsfld class Kotlin.KVisibility Kotlin.KVisibility::PRIVATE
            |      ret
            |    CDF_VisibilityUnknown:
            |      ldnull
            |      ret
            |    }
            |
            |    .method assembly hidebysig static bool IsFinal(int32 'flags') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldc.i4 ${DotNetCallableDeclarationFlags.MODALITY_FINAL}
            |      and
            |      ldc.i4.0
            |      cgt.un
            |      ret
            |    }
            |
            |    .method assembly hidebysig static bool IsOpen(int32 'flags') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldc.i4 ${DotNetCallableDeclarationFlags.MODALITY_OPEN}
            |      and
            |      ldc.i4.0
            |      cgt.un
            |      ret
            |    }
            |
            |    .method assembly hidebysig static bool IsAbstract(int32 'flags') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldc.i4 ${DotNetCallableDeclarationFlags.MODALITY_ABSTRACT}
            |      and
            |      ldc.i4.0
            |      cgt.un
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
            |  .class private auto ansi abstract sealed beforefieldinit CallableInvoker
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method public hidebysig static object Invoke(
            |        object 'callable', int32 'arity', object[] 'args') cil managed
            |    {
            |      .maxstack ${BuiltInFunctionArity.BIG_ARITY + 1}
            |      .locals init ([0] int32 actual)
            |      ldarg.2
            |      ldlen
            |      conv.i4
            |      stloc.0
            |      ldloc.0
            |      ldarg.1
            |      beq.s CI_ArityCorrect
            |      ldstr "Callable expects "
            |      ldarga.s 1
            |      call instance string ${coreLibraryReference}System.Int32::ToString()
            |      ldstr " arguments, but "
            |      call string ${coreLibraryReference}System.String::Concat(string, string, string)
            |      ldloca.s 0
            |      call instance string ${coreLibraryReference}System.Int32::ToString()
            |      ldstr " were provided."
            |      call string ${coreLibraryReference}System.String::Concat(string, string, string)
            |      newobj instance void ${coreLibraryReference}System.ArgumentException::.ctor(string)
            |      throw
            |    CI_ArityCorrect:
            |      ldarg.1
            |      switch ($callableInvokerSwitchLabelsIl)
            |      ldarg.0
            |      castclass Kotlin.FunctionN
            |      ldarg.2
            |      callvirt instance object Kotlin.FunctionN::Invoke(object[])
            |      ret
$callableInvokerCasesIl
            |    }
            |
            |    .method public hidebysig static object InvokeRequiredBy(
            |        object 'callable', int32 'arity', class Kotlin.Collections.List 'parameters',
            |        class Kotlin.Collections.Map 'args') cil managed
            |    {
            |      .maxstack 4
            |      .locals init (
            |        [0] object[] positional,
            |        [1] int32 index,
            |        [2] object parameter
            |      )
            |      ldarg.1
            |      newarr ${coreLibraryReference}System.Object
            |      stloc.0
            |      ldc.i4.0
            |      stloc.1
            |    CI_By_Loop:
            |      ldloc.1
            |      ldarg.1
            |      bge.s CI_By_Invoke
            |      ldarg.2
            |      ldloc.1
            |      callvirt instance object Kotlin.Collections.List::Get(int32)
            |      stloc.2
            |      ldarg.3
            |      ldloc.2
            |      callvirt instance bool Kotlin.Collections.Map::ContainsKeyErased(object)
            |      brtrue.s CI_By_Present
            |      ldstr "No argument provided for a required parameter: "
            |      ldloc.2
            |      callvirt instance string ${coreLibraryReference}System.Object::ToString()
            |      call string ${coreLibraryReference}System.String::Concat(string, string)
            |      newobj instance void ${coreLibraryReference}System.ArgumentException::.ctor(string)
            |      throw
            |    CI_By_Present:
            |      ldloc.0
            |      ldloc.1
            |      ldarg.3
            |      ldloc.2
            |      callvirt instance object Kotlin.Collections.Map::GetErased(object)
            |      stelem.ref
            |      ldloc.1
            |      ldc.i4.1
            |      add
            |      stloc.1
            |      br.s CI_By_Loop
            |    CI_By_Invoke:
            |      ldarg.0
            |      ldarg.1
            |      ldloc.0
            |      call object Kotlin.Runtime.Internal.CallableInvoker::Invoke(object, int32, object[])
            |      ret
            |    }
            |  }
            |
            |  .class public abstract auto ansi beforefieldinit FunctionReferenceBase
            |         extends ${coreLibraryReference}System.Object
            |         implements Kotlin.KAnnotatedElement
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .field private initonly string 'id'
            |    .field private initonly int32 'arity'
            |    .field private initonly int32 'flags'
            |    .field private initonly int32 'boundValueCount'
            |    .field private initonly string 'name'
            |    .field private initonly class Kotlin.Collections.List 'annotations'
            |    .field private initonly class Kotlin.KType 'returnType'
            |    .field private initonly class Kotlin.Collections.List 'parameters'
            |    .field private initonly class Kotlin.Collections.List 'typeParameters'
            |    .field private initonly object[] 'parameterDescriptors'
            |
            |    .method family hidebysig specialname rtspecialname instance void .ctor(
            |        string 'id', int32 'arity', int32 'flags', int32 'boundValueCount', string 'name',
            |        class Kotlin.Collections.List 'annotations', object[] 'signature',
            |        class Kotlin.Function2 'parameterFactory') cil managed
            |    {
            |      .maxstack 5
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
            |      ldarg.0
            |      ldarg.s 6
            |      stfld class Kotlin.Collections.List Kotlin.Runtime.Internal.FunctionReferenceBase::'annotations'
            |      ldarg.s 7
            |      brfalse.s FR_SignatureMissing
            |      ldarg.0
            |      ldarg.s 7
            |      ldc.i4.0
            |      ldelem.ref
            |      castclass Kotlin.KType
            |      stfld class Kotlin.KType Kotlin.Runtime.Internal.FunctionReferenceBase::'returnType'
            |      ldarg.0
            |      ldarg.s 7
            |      ldc.i4.1
            |      ldelem.ref
            |      castclass object[]
            |      newobj instance void Kotlin.Runtime.Internal.ReflectionAnnotationList::.ctor(object[])
            |      stfld class Kotlin.Collections.List Kotlin.Runtime.Internal.FunctionReferenceBase::'typeParameters'
            |      ldarg.0
            |      ldarg.s 7
            |      ldc.i4.2
            |      ldelem.ref
            |      castclass object[]
            |      stfld object[] Kotlin.Runtime.Internal.FunctionReferenceBase::'parameterDescriptors'
            |      br.s FR_SignatureDone
            |    FR_SignatureMissing:
            |      ldarg.0
            |      ldnull
            |      stfld class Kotlin.KType Kotlin.Runtime.Internal.FunctionReferenceBase::'returnType'
            |      ldarg.0
            |      call class Kotlin.Collections.List Kotlin.Runtime.Internal.ReflectionAnnotationList::'Empty'()
            |      stfld class Kotlin.Collections.List Kotlin.Runtime.Internal.FunctionReferenceBase::'typeParameters'
            |      ldarg.0
            |      ldc.i4.0
            |      newarr ${coreLibraryReference}System.Object
            |      stfld object[] Kotlin.Runtime.Internal.FunctionReferenceBase::'parameterDescriptors'
            |    FR_SignatureDone:
            |      ldarg.s 8
            |      brfalse.s FR_ParametersEmpty
            |      ldarg.s 7
            |      brfalse.s FR_ParametersEmpty
            |      ldarg.0
            |      ldarg.s 8
            |      ldarg.0
            |      ldarg.s 7
            |      callvirt instance object Kotlin.Function2::Invoke(object, object)
            |      castclass Kotlin.Collections.List
            |      stfld class Kotlin.Collections.List Kotlin.Runtime.Internal.FunctionReferenceBase::'parameters'
            |      br.s FR_ParametersDone
            |    FR_ParametersEmpty:
            |      ldarg.0
            |      call class Kotlin.Collections.List Kotlin.Runtime.Internal.ReflectionAnnotationList::'Empty'()
            |      stfld class Kotlin.Collections.List Kotlin.Runtime.Internal.FunctionReferenceBase::'parameters'
            |    FR_ParametersDone:
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.Collections.List get_annotations() cil managed
            |    {
            |      .override method instance class Kotlin.Collections.List Kotlin.KAnnotatedElement::get_annotations()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.FunctionReferenceBase::'annotations'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.KVisibility get_visibility() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'flags'
            |      call class Kotlin.KVisibility Kotlin.Runtime.Internal.CallableDeclarationFacts::Visibility(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isFinal() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'flags'
            |      call bool Kotlin.Runtime.Internal.CallableDeclarationFacts::IsFinal(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isOpen() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'flags'
            |      call bool Kotlin.Runtime.Internal.CallableDeclarationFacts::IsOpen(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isAbstract() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'flags'
            |      call bool Kotlin.Runtime.Internal.CallableDeclarationFacts::IsAbstract(int32)
            |      ret
            |    }
            |
            |    .property instance class Kotlin.KVisibility visibility()
            |    {
            |      .get instance class Kotlin.KVisibility Kotlin.Runtime.Internal.FunctionReferenceBase::get_visibility()
            |    }
            |    .property instance bool isFinal()
            |    {
            |      .get instance bool Kotlin.Runtime.Internal.FunctionReferenceBase::get_isFinal()
            |    }
            |    .property instance bool isOpen()
            |    {
            |      .get instance bool Kotlin.Runtime.Internal.FunctionReferenceBase::get_isOpen()
            |    }
            |    .property instance bool isAbstract()
            |    {
            |      .get instance bool Kotlin.Runtime.Internal.FunctionReferenceBase::get_isAbstract()
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isInline() cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'flags'
            |      ldc.i4 ${DotNetFunctionReferenceFlags.IS_INLINE}
            |      and
            |      ldc.i4.0
            |      cgt.un
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isExternal() cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'flags'
            |      ldc.i4 ${DotNetFunctionReferenceFlags.IS_EXTERNAL}
            |      and
            |      ldc.i4.0
            |      cgt.un
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isOperator() cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'flags'
            |      ldc.i4 ${DotNetFunctionReferenceFlags.IS_OPERATOR}
            |      and
            |      ldc.i4.0
            |      cgt.un
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isInfix() cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'flags'
            |      ldc.i4 ${DotNetFunctionReferenceFlags.IS_INFIX}
            |      and
            |      ldc.i4.0
            |      cgt.un
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isSuspend() cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'flags'
            |      ldc.i4 ${DotNetFunctionReferenceFlags.IS_SUSPEND}
            |      and
            |      ldc.i4.0
            |      cgt.un
            |      ret
            |    }
            |
            |    .property instance bool isInline()
            |    {
            |      .get instance bool Kotlin.Runtime.Internal.FunctionReferenceBase::get_isInline()
            |    }
            |    .property instance bool isExternal()
            |    {
            |      .get instance bool Kotlin.Runtime.Internal.FunctionReferenceBase::get_isExternal()
            |    }
            |    .property instance bool isOperator()
            |    {
            |      .get instance bool Kotlin.Runtime.Internal.FunctionReferenceBase::get_isOperator()
            |    }
            |    .property instance bool isInfix()
            |    {
            |      .get instance bool Kotlin.Runtime.Internal.FunctionReferenceBase::get_isInfix()
            |    }
            |    .property instance bool isSuspend()
            |    {
            |      .get instance bool Kotlin.Runtime.Internal.FunctionReferenceBase::get_isSuspend()
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance string get_name() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld string Kotlin.Runtime.Internal.FunctionReferenceBase::'name'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.KType get_returnType() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.KType Kotlin.Runtime.Internal.FunctionReferenceBase::'returnType'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.Collections.List get_typeParameters() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.FunctionReferenceBase::'typeParameters'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.Collections.List get_parameters() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.FunctionReferenceBase::'parameters'
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Call(object[] 'args') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      callvirt instance object Kotlin.Runtime.Internal.FunctionReferenceBase::CallErased(object[])
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object CallBy(
            |        class Kotlin.Collections.Map 'args') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      callvirt instance object Kotlin.Runtime.Internal.FunctionReferenceBase::CallByErased(
            |          class Kotlin.Collections.Map)
            |      ret
            |    }
            |
            |    .property instance string name()
            |    {
            |      .get instance string Kotlin.Runtime.Internal.FunctionReferenceBase::get_name()
            |    }
            |    .property instance class Kotlin.KType returnType()
            |    {
            |      .get instance class Kotlin.KType Kotlin.Runtime.Internal.FunctionReferenceBase::get_returnType()
            |    }
            |    .property instance class Kotlin.Collections.List typeParameters()
            |    {
            |      .get instance class Kotlin.Collections.List Kotlin.Runtime.Internal.FunctionReferenceBase::get_typeParameters()
            |    }
            |    .property instance class Kotlin.Collections.List parameters()
            |    {
            |      .get instance class Kotlin.Collections.List Kotlin.Runtime.Internal.FunctionReferenceBase::get_parameters()
            |    }
            |
            |    .method family hidebysig instance class Kotlin.Collections.List GetTypeParameters() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.FunctionReferenceBase::'typeParameters'
            |      ret
            |    }
            |
            |    .method family hidebysig instance class Kotlin.Collections.List GetParameters() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.FunctionReferenceBase::'parameters'
            |      ret
            |    }
            |
            |    .method family hidebysig instance class Kotlin.KType GetReturnType() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.KType Kotlin.Runtime.Internal.FunctionReferenceBase::'returnType'
            |      ret
            |    }
            |
            |    .method family hidebysig newslot virtual instance object CallErased(object[] 'args') cil managed
            |    {
            |      .maxstack 3
            |      ldarg.0
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'arity'
            |      ldarg.1
            |      call object Kotlin.Runtime.Internal.CallableInvoker::Invoke(object, int32, object[])
            |      ret
            |    }
            |
            |    .method family hidebysig newslot virtual instance object CallByErased(
            |        class Kotlin.Collections.Map 'args') cil managed
            |    {
            |      .maxstack 6
            |      .locals init (
            |        [0] object[] positional,
            |        [1] int32 index,
            |        [2] class Kotlin.IntArray masks,
            |        [3] object parameter,
            |        [4] object[] descriptor,
            |        [5] int32 maskIndex,
            |        [6] bool hasDefaults
            |      )
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'arity'
            |      newarr ${coreLibraryReference}System.Object
            |      stloc.0
            |      ldc.i4.0
            |      stloc.1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'arity'
            |      ldc.i4.s 31
            |      add
            |      ldc.i4.s 32
            |      div.un
            |      newarr ${coreLibraryReference}System.Int32
            |      newobj instance void Kotlin.IntArray::.ctor(int32[])
            |      stloc.2
            |      ldc.i4.0
            |      stloc.s 6
            |    FR_By_Loop:
            |      ldloc.1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.FunctionReferenceBase::'arity'
            |      bge FR_By_Invoke
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.FunctionReferenceBase::'parameters'
            |      ldloc.1
            |      callvirt instance object Kotlin.Collections.List::Get(int32)
            |      stloc.3
            |      ldarg.1
            |      ldloc.3
            |      callvirt instance bool Kotlin.Collections.Map::ContainsKeyErased(object)
            |      brfalse.s FR_By_Missing
            |      ldloc.0
            |      ldloc.1
            |      ldarg.1
            |      ldloc.3
            |      callvirt instance object Kotlin.Collections.Map::GetErased(object)
            |      stelem.ref
            |      br FR_By_Next
            |    FR_By_Missing:
            |      ldarg.0
            |      ldfld object[] Kotlin.Runtime.Internal.FunctionReferenceBase::'parameterDescriptors'
            |      ldloc.1
            |      ldelem.ref
            |      castclass object[]
            |      stloc.s 4
            |      ldloc.s 4
            |      ldc.i4.3
            |      ldelem.ref
            |      unbox.any ${coreLibraryReference}System.Boolean
            |      brfalse.s FR_By_NotOptional
            |      ldloc.1
            |      ldc.i4.s 32
            |      div.un
            |      stloc.s 5
            |      ldloc.2
            |      ldloc.s 5
            |      ldloc.2
            |      ldloc.s 5
            |      callvirt instance int32 Kotlin.IntArray::Get(int32)
            |      ldc.i4.1
            |      ldloc.1
            |      ldc.i4.s 32
            |      rem.un
            |      shl
            |      or
            |      callvirt instance void Kotlin.IntArray::Set(int32, int32)
            |      ldc.i4.1
            |      stloc.s 6
            |      br.s FR_By_Next
            |    FR_By_NotOptional:
            |      ldloc.s 4
            |      ldc.i4.4
            |      ldelem.ref
            |      unbox.any ${coreLibraryReference}System.Boolean
            |      brfalse.s FR_By_Required
            |      ldloc.0
            |      ldloc.1
            |      ldarg.0
            |      ldloc.1
            |      callvirt instance object Kotlin.Runtime.Internal.FunctionReferenceBase::EmptyVarargAt(int32)
            |      stelem.ref
            |      br.s FR_By_Next
            |    FR_By_Required:
            |      ldstr "No argument provided for a required parameter: "
            |      ldloc.3
            |      callvirt instance string ${coreLibraryReference}System.Object::ToString()
            |      call string ${coreLibraryReference}System.String::Concat(string, string)
            |      newobj instance void ${coreLibraryReference}System.ArgumentException::.ctor(string)
            |      throw
            |    FR_By_Next:
            |      ldloc.1
            |      ldc.i4.1
            |      add
            |      stloc.1
            |      br FR_By_Loop
            |    FR_By_Invoke:
            |      ldloc.s 6
            |      brtrue.s FR_By_Default
            |      ldarg.0
            |      ldloc.0
            |      callvirt instance object Kotlin.Runtime.Internal.FunctionReferenceBase::CallErased(object[])
            |      ret
            |    FR_By_Default:
            |      ldarg.0
            |      ldloc.0
            |      ldloc.2
            |      callvirt instance object Kotlin.Runtime.Internal.FunctionReferenceBase::CallDefaultErased(
            |          object[], class Kotlin.IntArray)
            |      ret
            |    }
            |
            |    .method family hidebysig newslot virtual instance object CallDefaultErased(
            |        object[] 'args', class Kotlin.IntArray 'masks') cil managed
            |    {
            |      .maxstack 1
            |      ldstr "Callable has no optional arguments."
            |      newobj instance void ${coreLibraryReference}System.NotSupportedException::.ctor(string)
            |      throw
            |    }
            |
            |    .method family hidebysig newslot virtual instance object EmptyVarargAt(int32 'index') cil managed
            |    {
            |      .maxstack 1
            |      ldstr "Callable has no vararg at this position."
            |      newobj instance void ${coreLibraryReference}System.NotSupportedException::.ctor(string)
            |      throw
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
$memberReferenceSupportTypesIl
            |
$propertyAccessorSupportTypesIl
            |
            |  .class private abstract auto ansi beforefieldinit PropertyReferenceBase
            |         extends ${coreLibraryReference}System.Object
            |         implements Kotlin.KProperty
            |  {
            |    .field private initonly string 'name'
            |    .field private initonly class Kotlin.KType 'returnType'
            |    .field private initonly class Kotlin.Collections.List 'parameters'
            |    .field private initonly class Kotlin.Collections.List 'typeParameters'
            |    .field private initonly class Kotlin.Collections.List 'annotations'
            |    .field private int32 'declarationFlags'
            |    .field private class Kotlin.KProperty/Getter 'getterAccessor'
            |    .field private class Kotlin.KMutableProperty/Setter 'setterAccessor'
            |
            |    .method family hidebysig specialname rtspecialname instance void .ctor(
            |        string 'name', object[] 'signature',
            |        class Kotlin.Function2 'parameterFactory',
            |        class Kotlin.Collections.List 'annotations') cil managed
            |    {
            |      .maxstack 5
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ldarg.0
            |      ldarg.1
            |      stfld string Kotlin.Runtime.Internal.PropertyReferenceBase::'name'
            |      ldarg.0
            |      ldarg.2
            |      ldc.i4.0
            |      ldelem.ref
            |      castclass Kotlin.KType
            |      stfld class Kotlin.KType Kotlin.Runtime.Internal.PropertyReferenceBase::'returnType'
            |      ldarg.0
            |      ldarg.2
            |      ldc.i4.1
            |      ldelem.ref
            |      castclass object[]
            |      newobj instance void Kotlin.Runtime.Internal.ReflectionAnnotationList::.ctor(object[])
            |      stfld class Kotlin.Collections.List Kotlin.Runtime.Internal.PropertyReferenceBase::'typeParameters'
            |      ldarg.3
            |      brfalse.s PR_ParametersEmpty
            |      ldarg.0
            |      ldarg.3
            |      ldarg.0
            |      ldarg.2
            |      callvirt instance object Kotlin.Function2::Invoke(object, object)
            |      castclass Kotlin.Collections.List
            |      stfld class Kotlin.Collections.List Kotlin.Runtime.Internal.PropertyReferenceBase::'parameters'
            |      br.s PR_ParametersDone
            |    PR_ParametersEmpty:
            |      ldarg.0
            |      call class Kotlin.Collections.List Kotlin.Runtime.Internal.ReflectionAnnotationList::'Empty'()
            |      stfld class Kotlin.Collections.List Kotlin.Runtime.Internal.PropertyReferenceBase::'parameters'
            |    PR_ParametersDone:
            |      ldarg.0
            |      ldarg.s 4
            |      stfld class Kotlin.Collections.List Kotlin.Runtime.Internal.PropertyReferenceBase::'annotations'
            |      ret
            |    }
            |
            |    .method assembly hidebysig instance void InitializeAccessors(
            |        int32 'receiverCount', object[] 'getterSignature',
            |        class Kotlin.Collections.List 'getterAnnotations', int32 'getterFlags',
            |        object[] 'setterSignature', class Kotlin.Collections.List 'setterAnnotations',
            |        int32 'setterFlags', class Kotlin.Function2 'parameterFactory') cil managed
            |    {
            |      .maxstack 7
            |      ldarg.0
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      ldarg.s 8
            |      ldarg.3
            |      ldarg.s 4
            |      call class Kotlin.KProperty/Getter Kotlin.Runtime.Internal.PropertyAccessorFactory::CreateGetter(
            |          class Kotlin.KProperty, int32, object[], class Kotlin.Function2,
            |          class Kotlin.Collections.List, int32)
            |      stfld class Kotlin.KProperty/Getter Kotlin.Runtime.Internal.PropertyReferenceBase::'getterAccessor'
            |      ldarg.s 5
            |      brfalse.s PR_AccessorsDone
            |      ldarg.0
            |      ldarg.0
            |      castclass Kotlin.KMutableProperty
            |      ldarg.1
            |      ldarg.s 5
            |      ldarg.s 8
            |      ldarg.s 6
            |      ldarg.s 7
            |      call class Kotlin.KMutableProperty/Setter Kotlin.Runtime.Internal.PropertyAccessorFactory::CreateSetter(
            |          class Kotlin.KMutableProperty, int32, object[], class Kotlin.Function2,
            |          class Kotlin.Collections.List, int32)
            |      stfld class Kotlin.KMutableProperty/Setter Kotlin.Runtime.Internal.PropertyReferenceBase::'setterAccessor'
            |    PR_AccessorsDone:
            |      ret
            |    }
            |
            |    .method family hidebysig instance class Kotlin.KProperty/Getter GetGetterAccessor() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.KProperty/Getter Kotlin.Runtime.Internal.PropertyReferenceBase::'getterAccessor'
            |      ret
            |    }
            |
            |    .method family hidebysig instance class Kotlin.KMutableProperty/Setter GetSetterAccessor() cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.KMutableProperty/Setter Kotlin.Runtime.Internal.PropertyReferenceBase::'setterAccessor'
            |      ret
            |    }
            |
            |    .method assembly hidebysig instance void SetDeclarationFlags(int32 'flags') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      stfld int32 Kotlin.Runtime.Internal.PropertyReferenceBase::'declarationFlags'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isLateinit() cil managed
            |    {
            |      .override method instance bool Kotlin.KProperty::get_isLateinit()
            |      .maxstack 2
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.PropertyReferenceBase::'declarationFlags'
            |      ldc.i4 ${DotNetPropertyDeclarationFlags.IS_LATEINIT}
            |      and
            |      ldc.i4.0
            |      cgt.un
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isConst() cil managed
            |    {
            |      .override method instance bool Kotlin.KProperty::get_isConst()
            |      .maxstack 2
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.PropertyReferenceBase::'declarationFlags'
            |      ldc.i4 ${DotNetPropertyDeclarationFlags.IS_CONST}
            |      and
            |      ldc.i4.0
            |      cgt.un
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.KProperty/Getter get_getter() cil managed
            |    {
            |      .override method instance class Kotlin.KProperty/Getter Kotlin.KProperty::get_getter()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.KProperty/Getter Kotlin.Runtime.Internal.PropertyReferenceBase::'getterAccessor'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.Collections.List get_typeParameters() cil managed
            |    {
            |      .override method instance class Kotlin.Collections.List Kotlin.KCallable::get_typeParameters()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.PropertyReferenceBase::'typeParameters'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.Collections.List get_parameters() cil managed
            |    {
            |      .override method instance class Kotlin.Collections.List Kotlin.KCallable::get_parameters()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.PropertyReferenceBase::'parameters'
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
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.KType get_returnType() cil managed
            |    {
            |      .override method instance class Kotlin.KType Kotlin.KCallable::get_returnType()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.KType Kotlin.Runtime.Internal.PropertyReferenceBase::'returnType'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.Collections.List get_annotations() cil managed
            |    {
            |      .override method instance class Kotlin.Collections.List Kotlin.KAnnotatedElement::get_annotations()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.PropertyReferenceBase::'annotations'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.KVisibility get_visibility() cil managed
            |    {
            |      .override method instance class Kotlin.KVisibility Kotlin.KCallable::get_visibility()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.PropertyReferenceBase::'declarationFlags'
            |      call class Kotlin.KVisibility Kotlin.Runtime.Internal.CallableDeclarationFacts::Visibility(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isFinal() cil managed
            |    {
            |      .override method instance bool Kotlin.KCallable::get_isFinal()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.PropertyReferenceBase::'declarationFlags'
            |      call bool Kotlin.Runtime.Internal.CallableDeclarationFacts::IsFinal(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isOpen() cil managed
            |    {
            |      .override method instance bool Kotlin.KCallable::get_isOpen()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.PropertyReferenceBase::'declarationFlags'
            |      call bool Kotlin.Runtime.Internal.CallableDeclarationFacts::IsOpen(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isAbstract() cil managed
            |    {
            |      .override method instance bool Kotlin.KCallable::get_isAbstract()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.PropertyReferenceBase::'declarationFlags'
            |      call bool Kotlin.Runtime.Internal.CallableDeclarationFacts::IsAbstract(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Call(object[] 'args') cil managed
            |    {
            |      .override method instance object Kotlin.KCallable::Call(object[])
            |      .maxstack 3
            |      ldarg.0
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.PropertyReferenceBase::'parameters'
            |      callvirt instance int32 Kotlin.Collections.List::get_Size()
            |      ldarg.1
            |      call object Kotlin.Runtime.Internal.CallableInvoker::Invoke(object, int32, object[])
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object CallBy(
            |        class Kotlin.Collections.Map 'args') cil managed
            |    {
            |      .override method instance object Kotlin.KCallable::CallBy(class Kotlin.Collections.Map)
            |      .maxstack 4
            |      ldarg.0
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.PropertyReferenceBase::'parameters'
            |      callvirt instance int32 Kotlin.Collections.List::get_Size()
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.PropertyReferenceBase::'parameters'
            |      ldarg.1
            |      call object Kotlin.Runtime.Internal.CallableInvoker::InvokeRequiredBy(
            |          object, int32, class Kotlin.Collections.List, class Kotlin.Collections.Map)
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
            |    .method public hidebysig specialname rtspecialname instance void .ctor(
            |        string 'name', class Kotlin.Function0 'getter', object[] 'signature',
            |        class Kotlin.Function2 'parameterFactory', class Kotlin.Collections.List 'annotations') cil managed
            |    {
            |      .maxstack 5
            |      ldarg.0
            |      ldarg.1
            |      ldarg.3
            |      ldarg.s 4
            |      ldarg.s 5
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::.ctor(
            |          string, object[], class Kotlin.Function2, class Kotlin.Collections.List)
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
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KProperty0/Getter get_fixedGetter() cil managed
            |    {
            |      .override method instance class Kotlin.KProperty0/Getter Kotlin.KProperty0::get_getter()
            |      .maxstack 1
            |      ldarg.0
            |      call instance class Kotlin.KProperty/Getter Kotlin.Runtime.Internal.PropertyReferenceBase::GetGetterAccessor()
            |      castclass Kotlin.KProperty0/Getter
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
            |    .method public hidebysig specialname rtspecialname instance void .ctor(
            |        string 'name', class Kotlin.Function0 'getter', class Kotlin.Function1 'setter',
            |        object[] 'signature', class Kotlin.Function2 'parameterFactory',
            |        class Kotlin.Collections.List 'annotations') cil managed
            |    {
            |      .maxstack 5
            |      ldarg.0
            |      ldarg.1
            |      ldarg.s 4
            |      ldarg.s 5
            |      ldarg.s 6
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::.ctor(
            |          string, object[], class Kotlin.Function2, class Kotlin.Collections.List)
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
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KProperty0/Getter get_fixedGetter() cil managed
            |    {
            |      .override method instance class Kotlin.KProperty0/Getter Kotlin.KProperty0::get_getter()
            |      .maxstack 1
            |      ldarg.0
            |      call instance class Kotlin.KProperty/Getter Kotlin.Runtime.Internal.PropertyReferenceBase::GetGetterAccessor()
            |      castclass Kotlin.KProperty0/Getter
            |      ret
            |    }
            |
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KMutableProperty/Setter get_baseSetter() cil managed
            |    {
            |      .override method instance class Kotlin.KMutableProperty/Setter Kotlin.KMutableProperty::get_setter()
            |      .maxstack 1
            |      ldarg.0
            |      call instance class Kotlin.KMutableProperty/Setter Kotlin.Runtime.Internal.PropertyReferenceBase::GetSetterAccessor()
            |      ret
            |    }
            |
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KMutableProperty0/Setter get_fixedSetter() cil managed
            |    {
            |      .override method instance class Kotlin.KMutableProperty0/Setter Kotlin.KMutableProperty0::get_setter()
            |      .maxstack 1
            |      ldarg.0
            |      call instance class Kotlin.KMutableProperty/Setter Kotlin.Runtime.Internal.PropertyReferenceBase::GetSetterAccessor()
            |      castclass Kotlin.KMutableProperty0/Setter
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
            |    .method public hidebysig specialname rtspecialname instance void .ctor(
            |        string 'name', class Kotlin.Function1 'getter', object[] 'signature',
            |        class Kotlin.Function2 'parameterFactory', class Kotlin.Collections.List 'annotations') cil managed
            |    {
            |      .maxstack 5
            |      ldarg.0
            |      ldarg.1
            |      ldarg.3
            |      ldarg.s 4
            |      ldarg.s 5
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::.ctor(
            |          string, object[], class Kotlin.Function2, class Kotlin.Collections.List)
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
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KProperty1/Getter get_fixedGetter() cil managed
            |    {
            |      .override method instance class Kotlin.KProperty1/Getter Kotlin.KProperty1::get_getter()
            |      .maxstack 1
            |      ldarg.0
            |      call instance class Kotlin.KProperty/Getter Kotlin.Runtime.Internal.PropertyReferenceBase::GetGetterAccessor()
            |      castclass Kotlin.KProperty1/Getter
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
            |    .method public hidebysig specialname rtspecialname instance void .ctor(
            |        string 'name', class Kotlin.Function1 'getter', class Kotlin.Function2 'setter',
            |        object[] 'signature', class Kotlin.Function2 'parameterFactory',
            |        class Kotlin.Collections.List 'annotations') cil managed
            |    {
            |      .maxstack 5
            |      ldarg.0
            |      ldarg.1
            |      ldarg.s 4
            |      ldarg.s 5
            |      ldarg.s 6
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::.ctor(
            |          string, object[], class Kotlin.Function2, class Kotlin.Collections.List)
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
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KProperty1/Getter get_fixedGetter() cil managed
            |    {
            |      .override method instance class Kotlin.KProperty1/Getter Kotlin.KProperty1::get_getter()
            |      .maxstack 1
            |      ldarg.0
            |      call instance class Kotlin.KProperty/Getter Kotlin.Runtime.Internal.PropertyReferenceBase::GetGetterAccessor()
            |      castclass Kotlin.KProperty1/Getter
            |      ret
            |    }
            |
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KMutableProperty/Setter get_baseSetter() cil managed
            |    {
            |      .override method instance class Kotlin.KMutableProperty/Setter Kotlin.KMutableProperty::get_setter()
            |      .maxstack 1
            |      ldarg.0
            |      call instance class Kotlin.KMutableProperty/Setter Kotlin.Runtime.Internal.PropertyReferenceBase::GetSetterAccessor()
            |      ret
            |    }
            |
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KMutableProperty1/Setter get_fixedSetter() cil managed
            |    {
            |      .override method instance class Kotlin.KMutableProperty1/Setter Kotlin.KMutableProperty1::get_setter()
            |      .maxstack 1
            |      ldarg.0
            |      call instance class Kotlin.KMutableProperty/Setter Kotlin.Runtime.Internal.PropertyReferenceBase::GetSetterAccessor()
            |      castclass Kotlin.KMutableProperty1/Setter
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
            |    .method public hidebysig specialname rtspecialname instance void .ctor(
            |        string 'name', class Kotlin.Function2 'getter', object[] 'signature',
            |        class Kotlin.Function2 'parameterFactory', class Kotlin.Collections.List 'annotations') cil managed
            |    {
            |      .maxstack 5
            |      ldarg.0
            |      ldarg.1
            |      ldarg.3
            |      ldarg.s 4
            |      ldarg.s 5
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::.ctor(
            |          string, object[], class Kotlin.Function2, class Kotlin.Collections.List)
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
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KProperty2/Getter get_fixedGetter() cil managed
            |    {
            |      .override method instance class Kotlin.KProperty2/Getter Kotlin.KProperty2::get_getter()
            |      .maxstack 1
            |      ldarg.0
            |      call instance class Kotlin.KProperty/Getter Kotlin.Runtime.Internal.PropertyReferenceBase::GetGetterAccessor()
            |      castclass Kotlin.KProperty2/Getter
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
            |    .method public hidebysig specialname rtspecialname instance void .ctor(
            |        string 'name', class Kotlin.Function2 'getter', class Kotlin.Function3 'setter',
            |        object[] 'signature', class Kotlin.Function2 'parameterFactory',
            |        class Kotlin.Collections.List 'annotations') cil managed
            |    {
            |      .maxstack 5
            |      ldarg.0
            |      ldarg.1
            |      ldarg.s 4
            |      ldarg.s 5
            |      ldarg.s 6
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::.ctor(
            |          string, object[], class Kotlin.Function2, class Kotlin.Collections.List)
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
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KProperty2/Getter get_fixedGetter() cil managed
            |    {
            |      .override method instance class Kotlin.KProperty2/Getter Kotlin.KProperty2::get_getter()
            |      .maxstack 1
            |      ldarg.0
            |      call instance class Kotlin.KProperty/Getter Kotlin.Runtime.Internal.PropertyReferenceBase::GetGetterAccessor()
            |      castclass Kotlin.KProperty2/Getter
            |      ret
            |    }
            |
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KMutableProperty/Setter get_baseSetter() cil managed
            |    {
            |      .override method instance class Kotlin.KMutableProperty/Setter Kotlin.KMutableProperty::get_setter()
            |      .maxstack 1
            |      ldarg.0
            |      call instance class Kotlin.KMutableProperty/Setter Kotlin.Runtime.Internal.PropertyReferenceBase::GetSetterAccessor()
            |      ret
            |    }
            |
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KMutableProperty2/Setter get_fixedSetter() cil managed
            |    {
            |      .override method instance class Kotlin.KMutableProperty2/Setter Kotlin.KMutableProperty2::get_setter()
            |      .maxstack 1
            |      ldarg.0
            |      call instance class Kotlin.KMutableProperty/Setter Kotlin.Runtime.Internal.PropertyReferenceBase::GetSetterAccessor()
            |      castclass Kotlin.KMutableProperty2/Setter
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
            |    .field private initonly class Kotlin.KType 'returnType'
            |    .field private initonly class Kotlin.Collections.List 'parameters'
            |    .field private initonly class Kotlin.Collections.List 'typeParameters'
            |    .field private initonly class Kotlin.Collections.List 'annotations'
            |    .field private int32 'declarationFlags'
            |    .field family class Kotlin.KProperty0/Getter 'getterAccessor'
            |    .field family class Kotlin.KMutableProperty0/Setter 'setterAccessor'
            |
            |    .method family hidebysig specialname rtspecialname instance void .ctor(
            |        string 'name', object[] 'signature',
            |        class Kotlin.Function2 'parameterFactory',
            |        class Kotlin.Collections.List 'annotations') cil managed
            |    {
            |      .maxstack 5
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ldarg.0
            |      ldarg.1
            |      stfld string Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'name'
            |      ldarg.0
            |      ldarg.2
            |      ldc.i4.0
            |      ldelem.ref
            |      castclass Kotlin.KType
            |      stfld class Kotlin.KType Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'returnType'
            |      ldarg.0
            |      ldarg.2
            |      ldc.i4.1
            |      ldelem.ref
            |      castclass object[]
            |      newobj instance void Kotlin.Runtime.Internal.ReflectionAnnotationList::.ctor(object[])
            |      stfld class Kotlin.Collections.List Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'typeParameters'
            |      ldarg.3
            |      brfalse.s LDP_ParametersEmpty
            |      ldarg.0
            |      ldarg.3
            |      ldarg.0
            |      ldarg.2
            |      callvirt instance object Kotlin.Function2::Invoke(object, object)
            |      castclass Kotlin.Collections.List
            |      stfld class Kotlin.Collections.List Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'parameters'
            |      br.s LDP_ParametersDone
            |    LDP_ParametersEmpty:
            |      ldarg.0
            |      call class Kotlin.Collections.List Kotlin.Runtime.Internal.ReflectionAnnotationList::'Empty'()
            |      stfld class Kotlin.Collections.List Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'parameters'
            |    LDP_ParametersDone:
            |      ldarg.0
            |      ldarg.s 4
            |      stfld class Kotlin.Collections.List Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'annotations'
            |      ret
            |    }
            |
            |    .method assembly hidebysig instance void InitializeAccessors(
            |        object[] 'getterSignature', class Kotlin.Collections.List 'getterAnnotations',
            |        int32 'getterFlags', object[] 'setterSignature',
            |        class Kotlin.Collections.List 'setterAnnotations', int32 'setterFlags',
            |        class Kotlin.Function2 'parameterFactory') cil managed
            |    {
            |      .maxstack 7
            |      ldarg.0
            |      ldarg.0
            |      ldc.i4.0
            |      ldarg.1
            |      ldarg.s 7
            |      ldarg.2
            |      ldarg.3
            |      call class Kotlin.KProperty/Getter Kotlin.Runtime.Internal.PropertyAccessorFactory::CreateGetter(
            |          class Kotlin.KProperty, int32, object[], class Kotlin.Function2,
            |          class Kotlin.Collections.List, int32)
            |      castclass Kotlin.KProperty0/Getter
            |      stfld class Kotlin.KProperty0/Getter Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'getterAccessor'
            |      ldarg.s 4
            |      brfalse.s LDP_AccessorsDone
            |      ldarg.0
            |      ldarg.0
            |      castclass Kotlin.KMutableProperty
            |      ldc.i4.0
            |      ldarg.s 4
            |      ldarg.s 7
            |      ldarg.s 5
            |      ldarg.s 6
            |      call class Kotlin.KMutableProperty/Setter Kotlin.Runtime.Internal.PropertyAccessorFactory::CreateSetter(
            |          class Kotlin.KMutableProperty, int32, object[], class Kotlin.Function2,
            |          class Kotlin.Collections.List, int32)
            |      castclass Kotlin.KMutableProperty0/Setter
            |      stfld class Kotlin.KMutableProperty0/Setter Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'setterAccessor'
            |    LDP_AccessorsDone:
            |      ret
            |    }
            |
            |    .method assembly hidebysig instance void SetDeclarationFlags(int32 'flags') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      stfld int32 Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'declarationFlags'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isLateinit() cil managed
            |    {
            |      .override method instance bool Kotlin.KProperty::get_isLateinit()
            |      .maxstack 2
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'declarationFlags'
            |      ldc.i4 ${DotNetPropertyDeclarationFlags.IS_LATEINIT}
            |      and
            |      ldc.i4.0
            |      cgt.un
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isConst() cil managed
            |    {
            |      .override method instance bool Kotlin.KProperty::get_isConst()
            |      .maxstack 2
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'declarationFlags'
            |      ldc.i4 ${DotNetPropertyDeclarationFlags.IS_CONST}
            |      and
            |      ldc.i4.0
            |      cgt.un
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.KProperty/Getter get_getter() cil managed
            |    {
            |      .override method instance class Kotlin.KProperty/Getter Kotlin.KProperty::get_getter()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.KProperty0/Getter Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'getterAccessor'
            |      ret
            |    }
            |
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KProperty0/Getter get_fixedGetter() cil managed
            |    {
            |      .override method instance class Kotlin.KProperty0/Getter Kotlin.KProperty0::get_getter()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.KProperty0/Getter Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'getterAccessor'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.Collections.List get_typeParameters() cil managed
            |    {
            |      .override method instance class Kotlin.Collections.List Kotlin.KCallable::get_typeParameters()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'typeParameters'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.Collections.List get_parameters() cil managed
            |    {
            |      .override method instance class Kotlin.Collections.List Kotlin.KCallable::get_parameters()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'parameters'
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
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.KType get_returnType() cil managed
            |    {
            |      .override method instance class Kotlin.KType Kotlin.KCallable::get_returnType()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.KType Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'returnType'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.Collections.List get_annotations() cil managed
            |    {
            |      .override method instance class Kotlin.Collections.List Kotlin.KAnnotatedElement::get_annotations()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'annotations'
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance class Kotlin.KVisibility get_visibility() cil managed
            |    {
            |      .override method instance class Kotlin.KVisibility Kotlin.KCallable::get_visibility()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'declarationFlags'
            |      call class Kotlin.KVisibility Kotlin.Runtime.Internal.CallableDeclarationFacts::Visibility(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isFinal() cil managed
            |    {
            |      .override method instance bool Kotlin.KCallable::get_isFinal()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'declarationFlags'
            |      call bool Kotlin.Runtime.Internal.CallableDeclarationFacts::IsFinal(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isOpen() cil managed
            |    {
            |      .override method instance bool Kotlin.KCallable::get_isOpen()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'declarationFlags'
            |      call bool Kotlin.Runtime.Internal.CallableDeclarationFacts::IsOpen(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig specialname newslot virtual final instance bool get_isAbstract() cil managed
            |    {
            |      .override method instance bool Kotlin.KCallable::get_isAbstract()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld int32 Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'declarationFlags'
            |      call bool Kotlin.Runtime.Internal.CallableDeclarationFacts::IsAbstract(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object Call(object[] 'args') cil managed
            |    {
            |      .override method instance object Kotlin.KCallable::Call(object[])
            |      .maxstack 3
            |      ldarg.0
            |      ldc.i4.0
            |      ldarg.1
            |      call object Kotlin.Runtime.Internal.CallableInvoker::Invoke(object, int32, object[])
            |      ret
            |    }
            |
            |    .method public hidebysig newslot virtual final instance object CallBy(
            |        class Kotlin.Collections.Map 'args') cil managed
            |    {
            |      .override method instance object Kotlin.KCallable::CallBy(class Kotlin.Collections.Map)
            |      .maxstack 4
            |      ldarg.0
            |      ldc.i4.0
            |      ldarg.0
            |      ldfld class Kotlin.Collections.List Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'parameters'
            |      ldarg.1
            |      call object Kotlin.Runtime.Internal.CallableInvoker::InvokeRequiredBy(
            |          object, int32, class Kotlin.Collections.List, class Kotlin.Collections.Map)
            |      ret
            |    }
            |
            |    .property instance string 'name'()
            |    {
            |      .get instance string Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::get_name()
            |    }
            |    .property instance class Kotlin.KType 'returnType'()
            |    {
            |      .get instance class Kotlin.KType Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::get_returnType()
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
            |    .method public hidebysig specialname rtspecialname instance void .ctor(
            |        string 'name', object[] 'signature',
            |        class Kotlin.Function2 'parameterFactory', class Kotlin.Collections.List 'annotations') cil managed
            |    {
            |      .maxstack 5
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      ldarg.3
            |      ldarg.s 4
            |      call instance void Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::.ctor(
            |          string, object[], class Kotlin.Function2, class Kotlin.Collections.List)
            |      ret
            |    }
            |  }
            |
            |  .class private auto ansi sealed beforefieldinit LocalDelegatedMutableProperty0Impl
            |         extends Kotlin.Runtime.Internal.LocalDelegatedProperty0Base
            |         implements Kotlin.KMutableProperty0
            |  {
            |    .method public hidebysig specialname rtspecialname instance void .ctor(
            |        string 'name', object[] 'signature',
            |        class Kotlin.Function2 'parameterFactory', class Kotlin.Collections.List 'annotations') cil managed
            |    {
            |      .maxstack 5
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      ldarg.3
            |      ldarg.s 4
            |      call instance void Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::.ctor(
            |          string, object[], class Kotlin.Function2, class Kotlin.Collections.List)
            |      ret
            |    }
            |
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KMutableProperty/Setter get_baseSetter() cil managed
            |    {
            |      .override method instance class Kotlin.KMutableProperty/Setter Kotlin.KMutableProperty::get_setter()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.KMutableProperty0/Setter Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'setterAccessor'
            |      ret
            |    }
            |
            |    .method private hidebysig specialname newslot virtual final instance class Kotlin.KMutableProperty0/Setter get_fixedSetter() cil managed
            |    {
            |      .override method instance class Kotlin.KMutableProperty0/Setter Kotlin.KMutableProperty0::get_setter()
            |      .maxstack 1
            |      ldarg.0
            |      ldfld class Kotlin.KMutableProperty0/Setter Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::'setterAccessor'
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
            |    .method public hidebysig static class Kotlin.KProperty0 CreateProperty0<V>(
            |        string 'name', class Kotlin.Function0 'getter', object[] 'signature',
            |        object[] 'getterSignature', class Kotlin.Collections.List 'getterAnnotations',
            |        int32 'getterFlags', class Kotlin.Function2 'parameterFactory',
            |        class Kotlin.Collections.List 'annotations', int32 'declarationFlags') cil managed
            |    {
            |      .maxstack 10
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      ldarg.s 6
            |      ldarg.s 7
            |      newobj instance void Kotlin.Runtime.Internal.Property0Impl::.ctor(
            |          string, class Kotlin.Function0, object[], class Kotlin.Function2, class Kotlin.Collections.List)
            |      dup
            |      ldc.i4.0
            |      ldarg.3
            |      ldarg.s 4
            |      ldarg.s 5
            |      ldnull
            |      ldnull
            |      ldc.i4.0
            |      ldarg.s 6
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::InitializeAccessors(
            |          int32, object[], class Kotlin.Collections.List, int32, object[],
            |          class Kotlin.Collections.List, int32, class Kotlin.Function2)
            |      dup
            |      ldarg.s 8
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::SetDeclarationFlags(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KMutableProperty0 CreateMutableProperty0<V>(
            |        string 'name', class Kotlin.Function0 'getter', class Kotlin.Function1 'setter',
            |        object[] 'signature', object[] 'getterSignature',
            |        class Kotlin.Collections.List 'getterAnnotations', int32 'getterFlags',
            |        object[] 'setterSignature', class Kotlin.Collections.List 'setterAnnotations',
            |        int32 'setterFlags', class Kotlin.Function2 'parameterFactory',
            |        class Kotlin.Collections.List 'annotations', int32 'declarationFlags') cil managed
            |    {
            |      .maxstack 10
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      ldarg.3
            |      ldarg.s 10
            |      ldarg.s 11
            |      newobj instance void Kotlin.Runtime.Internal.MutableProperty0Impl::.ctor(
            |          string, class Kotlin.Function0, class Kotlin.Function1, object[],
            |          class Kotlin.Function2, class Kotlin.Collections.List)
            |      dup
            |      ldc.i4.0
            |      ldarg.s 4
            |      ldarg.s 5
            |      ldarg.s 6
            |      ldarg.s 7
            |      ldarg.s 8
            |      ldarg.s 9
            |      ldarg.s 10
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::InitializeAccessors(
            |          int32, object[], class Kotlin.Collections.List, int32, object[],
            |          class Kotlin.Collections.List, int32, class Kotlin.Function2)
            |      dup
            |      ldarg.s 12
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::SetDeclarationFlags(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KProperty1 CreateProperty1<R0, V>(
            |        string 'name', class Kotlin.Function1 'getter', object[] 'signature',
            |        object[] 'getterSignature', class Kotlin.Collections.List 'getterAnnotations',
            |        int32 'getterFlags', class Kotlin.Function2 'parameterFactory',
            |        class Kotlin.Collections.List 'annotations', int32 'declarationFlags') cil managed
            |    {
            |      .maxstack 10
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      ldarg.s 6
            |      ldarg.s 7
            |      newobj instance void Kotlin.Runtime.Internal.Property1Impl::.ctor(
            |          string, class Kotlin.Function1, object[], class Kotlin.Function2, class Kotlin.Collections.List)
            |      dup
            |      ldc.i4.1
            |      ldarg.3
            |      ldarg.s 4
            |      ldarg.s 5
            |      ldnull
            |      ldnull
            |      ldc.i4.0
            |      ldarg.s 6
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::InitializeAccessors(
            |          int32, object[], class Kotlin.Collections.List, int32, object[],
            |          class Kotlin.Collections.List, int32, class Kotlin.Function2)
            |      dup
            |      ldarg.s 8
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::SetDeclarationFlags(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KMutableProperty1 CreateMutableProperty1<R0, V>(
            |        string 'name', class Kotlin.Function1 'getter', class Kotlin.Function2 'setter',
            |        object[] 'signature', object[] 'getterSignature',
            |        class Kotlin.Collections.List 'getterAnnotations', int32 'getterFlags',
            |        object[] 'setterSignature', class Kotlin.Collections.List 'setterAnnotations',
            |        int32 'setterFlags', class Kotlin.Function2 'parameterFactory',
            |        class Kotlin.Collections.List 'annotations', int32 'declarationFlags') cil managed
            |    {
            |      .maxstack 10
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      ldarg.3
            |      ldarg.s 10
            |      ldarg.s 11
            |      newobj instance void Kotlin.Runtime.Internal.MutableProperty1Impl::.ctor(
            |          string, class Kotlin.Function1, class Kotlin.Function2, object[],
            |          class Kotlin.Function2, class Kotlin.Collections.List)
            |      dup
            |      ldc.i4.1
            |      ldarg.s 4
            |      ldarg.s 5
            |      ldarg.s 6
            |      ldarg.s 7
            |      ldarg.s 8
            |      ldarg.s 9
            |      ldarg.s 10
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::InitializeAccessors(
            |          int32, object[], class Kotlin.Collections.List, int32, object[],
            |          class Kotlin.Collections.List, int32, class Kotlin.Function2)
            |      dup
            |      ldarg.s 12
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::SetDeclarationFlags(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KProperty2 CreateProperty2<R0, R1, V>(
            |        string 'name', class Kotlin.Function2 'getter', object[] 'signature',
            |        object[] 'getterSignature', class Kotlin.Collections.List 'getterAnnotations',
            |        int32 'getterFlags', class Kotlin.Function2 'parameterFactory',
            |        class Kotlin.Collections.List 'annotations', int32 'declarationFlags') cil managed
            |    {
            |      .maxstack 10
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      ldarg.s 6
            |      ldarg.s 7
            |      newobj instance void Kotlin.Runtime.Internal.Property2Impl::.ctor(
            |          string, class Kotlin.Function2, object[], class Kotlin.Function2, class Kotlin.Collections.List)
            |      dup
            |      ldc.i4.2
            |      ldarg.3
            |      ldarg.s 4
            |      ldarg.s 5
            |      ldnull
            |      ldnull
            |      ldc.i4.0
            |      ldarg.s 6
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::InitializeAccessors(
            |          int32, object[], class Kotlin.Collections.List, int32, object[],
            |          class Kotlin.Collections.List, int32, class Kotlin.Function2)
            |      dup
            |      ldarg.s 8
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::SetDeclarationFlags(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KMutableProperty2 CreateMutableProperty2<R0, R1, V>(
            |        string 'name', class Kotlin.Function2 'getter', class Kotlin.Function3 'setter',
            |        object[] 'signature', object[] 'getterSignature',
            |        class Kotlin.Collections.List 'getterAnnotations', int32 'getterFlags',
            |        object[] 'setterSignature', class Kotlin.Collections.List 'setterAnnotations',
            |        int32 'setterFlags', class Kotlin.Function2 'parameterFactory',
            |        class Kotlin.Collections.List 'annotations', int32 'declarationFlags') cil managed
            |    {
            |      .maxstack 10
            |      ldarg.0
            |      ldarg.1
            |      ldarg.2
            |      ldarg.3
            |      ldarg.s 10
            |      ldarg.s 11
            |      newobj instance void Kotlin.Runtime.Internal.MutableProperty2Impl::.ctor(
            |          string, class Kotlin.Function2, class Kotlin.Function3, object[],
            |          class Kotlin.Function2, class Kotlin.Collections.List)
            |      dup
            |      ldc.i4.2
            |      ldarg.s 4
            |      ldarg.s 5
            |      ldarg.s 6
            |      ldarg.s 7
            |      ldarg.s 8
            |      ldarg.s 9
            |      ldarg.s 10
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::InitializeAccessors(
            |          int32, object[], class Kotlin.Collections.List, int32, object[],
            |          class Kotlin.Collections.List, int32, class Kotlin.Function2)
            |      dup
            |      ldarg.s 12
            |      call instance void Kotlin.Runtime.Internal.PropertyReferenceBase::SetDeclarationFlags(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KProperty0 CreateLocalDelegatedProperty0<V>(
            |        string 'name', object[] 'signature', object[] 'getterSignature',
            |        class Kotlin.Collections.List 'getterAnnotations', int32 'getterFlags',
            |        class Kotlin.Function2 'parameterFactory', class Kotlin.Collections.List 'annotations',
            |        int32 'declarationFlags') cil managed
            |    {
            |      .maxstack 9
            |      ldarg.0
            |      ldarg.1
            |      ldarg.s 5
            |      ldarg.s 6
            |      newobj instance void Kotlin.Runtime.Internal.LocalDelegatedProperty0Impl::.ctor(
            |          string, object[], class Kotlin.Function2, class Kotlin.Collections.List)
            |      dup
            |      ldarg.2
            |      ldarg.3
            |      ldarg.s 4
            |      ldnull
            |      ldnull
            |      ldc.i4.0
            |      ldarg.s 5
            |      call instance void Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::InitializeAccessors(
            |          object[], class Kotlin.Collections.List, int32, object[],
            |          class Kotlin.Collections.List, int32, class Kotlin.Function2)
            |      dup
            |      ldarg.s 7
            |      call instance void Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::SetDeclarationFlags(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig static class Kotlin.KMutableProperty0 CreateLocalDelegatedMutableProperty0<V>(
            |        string 'name', object[] 'signature', object[] 'getterSignature',
            |        class Kotlin.Collections.List 'getterAnnotations', int32 'getterFlags',
            |        object[] 'setterSignature', class Kotlin.Collections.List 'setterAnnotations',
            |        int32 'setterFlags', class Kotlin.Function2 'parameterFactory',
            |        class Kotlin.Collections.List 'annotations', int32 'declarationFlags') cil managed
            |    {
            |      .maxstack 9
            |      ldarg.0
            |      ldarg.1
            |      ldarg.s 8
            |      ldarg.s 9
            |      newobj instance void Kotlin.Runtime.Internal.LocalDelegatedMutableProperty0Impl::.ctor(
            |          string, object[], class Kotlin.Function2, class Kotlin.Collections.List)
            |      dup
            |      ldarg.2
            |      ldarg.3
            |      ldarg.s 4
            |      ldarg.s 5
            |      ldarg.s 6
            |      ldarg.s 7
            |      ldarg.s 8
            |      call instance void Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::InitializeAccessors(
            |          object[], class Kotlin.Collections.List, int32, object[],
            |          class Kotlin.Collections.List, int32, class Kotlin.Function2)
            |      dup
            |      ldarg.s 10
            |      call instance void Kotlin.Runtime.Internal.LocalDelegatedProperty0Base::SetDeclarationFlags(int32)
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
            |  .class public auto ansi sealed beforefieldinit 'MutableRef'
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .field public object 'element'
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
            |  // One failure-state object is allocated only when a Kotlin-owned CLR type
            |  // initializer fails. The CLR still supplies once-only execution and publication;
            |  // this object supplies Kotlin's first-observer/later-observer distinction after the
            |  // physical .cctor catches the original System.Exception.
            |  .class private auto ansi sealed beforefieldinit StaticInitializationFailureState
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    .field private initonly class ${coreLibraryReference}System.Exception 'reason'
            |    .field private int32 'observed'
            |
            |    .method assembly hidebysig specialname rtspecialname instance void .ctor(
            |        class ${coreLibraryReference}System.Exception 'reason') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      call instance void ${coreLibraryReference}System.Object::.ctor()
            |      ldarg.0
            |      ldarg.1
            |      stfld class ${coreLibraryReference}System.Exception Kotlin.Runtime.Internal.StaticInitializationFailureState::'reason'
            |      ret
            |    }
            |
            |    .method assembly hidebysig instance class ${coreLibraryReference}System.Exception 'Observe'() cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldflda int32 Kotlin.Runtime.Internal.StaticInitializationFailureState::'observed'
            |      ldc.i4.1
            |      call int32 ${coreLibraryReference}System.Threading.Interlocked::Exchange(int32&, int32)
            |      brtrue SIF_Later
            |
            |      ldarg.0
            |      ldfld class ${coreLibraryReference}System.Exception Kotlin.Runtime.Internal.StaticInitializationFailureState::'reason'
            |      ret
            |
            |    SIF_Later:
            |      ldnull
            |      ret
            |    }
            |  }
            |
            |  // Public only as compiler/runtime ABI. Generated assemblies keep their private
            |  // state fields object-typed, so the concrete failure-state class is not part of
            |  // producer metadata and may evolve with Kotlin.Runtime.
            |  .class public abstract sealed auto ansi beforefieldinit StaticInitialization
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method public hidebysig static object 'Capture'(
            |        class ${coreLibraryReference}System.Exception 'reason') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      newobj instance void Kotlin.Runtime.Internal.StaticInitializationFailureState::.ctor(
            |          class ${coreLibraryReference}System.Exception)
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Exception 'Observe'(
            |        object 'state') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      castclass Kotlin.Runtime.Internal.StaticInitializationFailureState
            |      callvirt instance class ${coreLibraryReference}System.Exception Kotlin.Runtime.Internal.StaticInitializationFailureState::'Observe'()
            |      ret
            |    }
            |
            |    // Target implementation of the Common non-JVM
            |    // kotlin.internal.staticInitializationFailure(reason, className) contract.
            |    .method public hidebysig static void 'Throw'(
            |        class ${coreLibraryReference}System.Exception 'reason',
            |        string 'className') cil managed
            |    {
            |      .maxstack 3
            |      ldarg.0
            |      brfalse SIF_NoReason
            |      ldarg.0
            |      ldc.i4.4
            |      call bool Kotlin.Runtime.Internal.ExceptionClassifier::'IsKotlinExceptionInstance'(
            |          class ${coreLibraryReference}System.Exception, int32)
            |      brfalse SIF_Wrap
            |      ldarg.0
            |      throw
            |
            |    SIF_Wrap:
            |      ldarg.0
            |      newobj instance void Kotlin.ExceptionInInitializerError::.ctor(
            |          class ${coreLibraryReference}System.Exception)
            |      throw
            |
            |    SIF_NoReason:
            |      ldstr "Could not initialize "
            |      ldarg.1
            |      brfalse SIF_File
            |      ldstr "class "
            |      ldarg.1
            |      call string ${coreLibraryReference}System.String::Concat(string, string)
            |      br SIF_Message
            |
            |    SIF_File:
            |      ldstr "file"
            |
            |    SIF_Message:
            |      call string ${coreLibraryReference}System.String::Concat(string, string)
            |      newobj instance void Kotlin.NoClassDefFoundError::.ctor(string)
            |      throw
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
            |      ldc.i4.${DotNetKotlinExceptionTypeId.THROWABLE.abiValue}
            |      beq EC_True
            |      ldarg.1
            |      ldc.i4.${DotNetKotlinExceptionTypeId.EXCEPTION.abiValue}
            |      beq EC_Exception
            |      ldarg.1
            |      ldc.i4.${DotNetKotlinExceptionTypeId.RUNTIME_EXCEPTION.abiValue}
            |      beq EC_RuntimeException
            |      ldarg.1
            |      ldc.i4.${DotNetKotlinExceptionTypeId.ERROR.abiValue}
            |      beq EC_Error
            |      ldarg.1
            |      ldc.i4.${DotNetKotlinExceptionTypeId.ILLEGAL_ARGUMENT_EXCEPTION.abiValue}
            |      beq EC_IllegalArgument
            |      ldarg.1
            |      ldc.i4.${DotNetKotlinExceptionTypeId.ILLEGAL_STATE_EXCEPTION.abiValue}
            |      beq EC_IllegalState
            |      ldarg.1
            |      ldc.i4.${DotNetKotlinExceptionTypeId.UNSUPPORTED_OPERATION_EXCEPTION.abiValue}
            |      beq EC_UnsupportedOperation
            |      ldarg.1
            |      ldc.i4.${DotNetKotlinExceptionTypeId.NO_SUCH_ELEMENT_EXCEPTION.abiValue}
            |      beq EC_NoSuchElement
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.INDEX_OUT_OF_BOUNDS_EXCEPTION.abiValue}
            |      beq EC_IndexOutOfBounds
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.ARITHMETIC_EXCEPTION.abiValue}
            |      beq EC_Arithmetic
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.NUMBER_FORMAT_EXCEPTION.abiValue}
            |      beq EC_NumberFormat
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.NULL_POINTER_EXCEPTION.abiValue}
            |      beq EC_NullPointer
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.CLASS_CAST_EXCEPTION.abiValue}
            |      beq EC_ClassCast
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.CANCELLATION_EXCEPTION.abiValue}
            |      beq EC_Cancellation
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.EXCEPTION_IN_INITIALIZER_ERROR.abiValue}
            |      beq EC_ExceptionInInitializerError
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.NO_CLASS_DEF_FOUND_ERROR.abiValue}
            |      beq EC_NoClassDefFoundError
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.NO_WHEN_BRANCH_MATCHED_EXCEPTION.abiValue}
            |      beq EC_NoWhenBranchMatched
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.CONCURRENT_MODIFICATION_EXCEPTION.abiValue}
            |      beq EC_ConcurrentModification
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.ASSERTION_ERROR.abiValue}
            |      beq EC_AssertionError
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.UNINITIALIZED_PROPERTY_ACCESS_EXCEPTION.abiValue}
            |      beq EC_UninitializedPropertyAccess
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.KOTLIN_NOTHING_VALUE_EXCEPTION.abiValue}
            |      beq EC_KotlinNothingValue
            |      ldarg.1
            |      ldc.i4.s ${DotNetKotlinExceptionTypeId.OUT_OF_MEMORY_ERROR.abiValue}
            |      beq EC_OutOfMemory
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
            |      isinst ${coreLibraryReference}System.OperationCanceledException
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
            |    EC_NoWhenBranchMatched:
            |      ldarg.0
            |      isinst Kotlin.NoWhenBranchMatchedException
            |      brtrue EC_True
            |      br EC_False
            |
            |    EC_ConcurrentModification:
            |      ldarg.0
            |      isinst Kotlin.ConcurrentModificationException
            |      br EC_MatchedObject
            |    EC_AssertionError:
            |      ldarg.0
            |      isinst Kotlin.AssertionError
            |      br EC_MatchedObject
            |    EC_UninitializedPropertyAccess:
            |      ldarg.0
            |      isinst Kotlin.UninitializedPropertyAccessException
            |      br EC_MatchedObject
            |    EC_KotlinNothingValue:
            |      ldarg.0
            |      isinst Kotlin.KotlinNothingValueException
            |      br EC_MatchedObject
            |    EC_OutOfMemory:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.OutOfMemoryException
            |      br EC_MatchedObject
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
            |      brtrue EC_True
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.OperationCanceledException
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
            |      br EC_MatchedObject
            |    EC_Cancellation:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.OperationCanceledException
            |      br EC_MatchedObject
            |    EC_ExceptionInInitializerError:
            |      ldarg.0
            |      isinst Kotlin.ExceptionInInitializerError
            |      br EC_MatchedObject
            |    EC_NoClassDefFoundError:
            |      ldarg.0
            |      isinst Kotlin.NoClassDefFoundError
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
            |  .class private explicit ansi sealed beforefieldinit FloatIntBits
            |         extends ${coreLibraryReference}System.ValueType
            |  {
            |    .pack 1
            |    .size 4
            |    .field [0] public float32 FloatValue
            |    .field [0] public int32 IntValue
            |  }
            |
            |  // One cache is materialized per constructed T. Structural equality can use a
            |  // constrained Object.Equals call for non-floating value types: this preserves
            |  // Kotlin's left-biased object-equality contract while avoiding a receiver box
            |  // whenever T overrides Object.Equals. References retain the null-safe object
            |  // helper, and Float/Double (including Nullable<T>) retain Kotlin's canonical-NaN
            |  // and signed-zero rules instead of inheriting CLR floating Equals semantics.
            |  .class private abstract sealed auto ansi 'GenericEqualityMode`1'<T>
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    .field assembly static initonly bool 'UseConstrainedValue'
            |
            |    .method private hidebysig specialname rtspecialname static void .cctor() cil managed
            |    {
            |      .maxstack 2
            |      .locals init (
            |        [0] class ${coreLibraryReference}System.Type 'type',
            |        [1] class ${coreLibraryReference}System.Type 'underlying'
            |      )
            |      ldtoken !0
            |      call class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::GetTypeFromHandle(
            |          valuetype ${coreLibraryReference}System.RuntimeTypeHandle)
            |      stloc.0
            |      ldloc.0
            |      callvirt instance bool ${coreLibraryReference}System.Type::get_IsValueType()
            |      brfalse.s IL_genericEqualityObject
            |      ldloc.0
            |      ldtoken ${coreLibraryReference}System.Single
            |      call class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::GetTypeFromHandle(
            |          valuetype ${coreLibraryReference}System.RuntimeTypeHandle)
            |      ceq
            |      brtrue.s IL_genericEqualityObject
            |      ldloc.0
            |      ldtoken ${coreLibraryReference}System.Double
            |      call class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::GetTypeFromHandle(
            |          valuetype ${coreLibraryReference}System.RuntimeTypeHandle)
            |      ceq
            |      brtrue.s IL_genericEqualityObject
            |      ldloc.0
            |      call class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Nullable::GetUnderlyingType(
            |          class ${coreLibraryReference}System.Type)
            |      stloc.1
            |      ldloc.1
            |      brfalse.s IL_genericEqualityConstrained
            |      ldloc.1
            |      ldtoken ${coreLibraryReference}System.Single
            |      call class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::GetTypeFromHandle(
            |          valuetype ${coreLibraryReference}System.RuntimeTypeHandle)
            |      ceq
            |      brtrue.s IL_genericEqualityObject
            |      ldloc.1
            |      ldtoken ${coreLibraryReference}System.Double
            |      call class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::GetTypeFromHandle(
            |          valuetype ${coreLibraryReference}System.RuntimeTypeHandle)
            |      ceq
            |      brtrue.s IL_genericEqualityObject
            |IL_genericEqualityConstrained:
            |      ldc.i4.1
            |      stsfld bool class Kotlin.Runtime.Internal.'GenericEqualityMode`1'<!0>::'UseConstrainedValue'
            |      ret
            |IL_genericEqualityObject:
            |      ldc.i4.0
            |      stsfld bool class Kotlin.Runtime.Internal.'GenericEqualityMode`1'<!0>::'UseConstrainedValue'
            |      ret
            |    }
            |  }
            |
            |  .class public abstract sealed auto ansi beforefieldinit Intrinsics
            |         extends ${coreLibraryReference}System.Object
            |  {
            |    $compilerAbiTypeAttributesIl
            |    .method public hidebysig static bool 'IsNumber'(object 'value') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.SByte
            |      brtrue.s IL_numberTrue
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int16
            |      brtrue.s IL_numberTrue
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int32
            |      brtrue.s IL_numberTrue
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int64
            |      brtrue.s IL_numberTrue
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Single
            |      brtrue.s IL_numberTrue
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Double
            |      brtrue.s IL_numberTrue
            |      ldarg.0
            |      isinst Kotlin.Number
            |      brtrue.s IL_numberTrue
            |      ldc.i4.0
            |      ret
            |IL_numberTrue:
            |      ldc.i4.1
            |      ret
            |    }
            |
            |    .method public hidebysig static object 'CheckNumberCast'(object 'value') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brfalse.s IL_numberCastValid
            |      ldarg.0
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'IsNumber'(object)
            |      brtrue.s IL_numberCastValid
            |      newobj instance void ${coreLibraryReference}System.InvalidCastException::.ctor()
            |      throw
            |IL_numberCastValid:
            |      ldarg.0
            |      ret
            |    }
            |
            |    .method public hidebysig static object 'SafeNumberCast'(object 'value') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'IsNumber'(object)
            |      brfalse.s IL_numberSafeCastNull
            |      ldarg.0
            |      ret
            |IL_numberSafeCastNull:
            |      ldnull
            |      ret
            |    }
            |
            |    .method private hidebysig static int32 'SaturatingDoubleToInt32'(float64 'value') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.0
            |      bne.un.s IL_numberDoubleToIntNaN
            |      ldarg.0
            |      ldc.r8 2147483647.0
            |      cgt
            |      brtrue.s IL_numberDoubleToIntMax
            |      ldarg.0
            |      ldc.r8 -2147483648.0
            |      clt
            |      brtrue.s IL_numberDoubleToIntMin
            |      ldarg.0
            |      conv.i4
            |      ret
            |IL_numberDoubleToIntNaN:
            |      ldc.i4.0
            |      ret
            |IL_numberDoubleToIntMax:
            |      ldc.i4 2147483647
            |      ret
            |IL_numberDoubleToIntMin:
            |      ldc.i4 -2147483648
            |      ret
            |    }
            |
            |    .method private hidebysig static int64 'SaturatingDoubleToInt64'(float64 'value') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.0
            |      bne.un.s IL_numberDoubleToLongNaN
            |      ldarg.0
            |      ldc.r8 float64(0x43E0000000000000)
            |      clt
            |      brfalse.s IL_numberDoubleToLongMax
            |      ldarg.0
            |      ldc.r8 float64(0xC3E0000000000000)
            |      clt
            |      brtrue.s IL_numberDoubleToLongMin
            |      ldarg.0
            |      conv.i8
            |      ret
            |IL_numberDoubleToLongNaN:
            |      ldc.i4.0
            |      conv.i8
            |      ret
            |IL_numberDoubleToLongMax:
            |      ldc.i8 9223372036854775807
            |      ret
            |IL_numberDoubleToLongMin:
            |      ldc.i8 -9223372036854775808
            |      ret
            |    }
            |
            |    .method public hidebysig static int32 'NumberToInt'(object 'value') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.SByte
            |      dup
            |      brfalse.s IL_numberIntShort
            |      unbox.any ${coreLibraryReference}System.SByte
            |      ret
            |IL_numberIntShort:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int16
            |      dup
            |      brfalse.s IL_numberIntInt
            |      unbox.any ${coreLibraryReference}System.Int16
            |      ret
            |IL_numberIntInt:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int32
            |      dup
            |      brfalse.s IL_numberIntLong
            |      unbox.any ${coreLibraryReference}System.Int32
            |      ret
            |IL_numberIntLong:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int64
            |      dup
            |      brfalse.s IL_numberIntFloat
            |      unbox.any ${coreLibraryReference}System.Int64
            |      conv.i4
            |      ret
            |IL_numberIntFloat:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Single
            |      dup
            |      brfalse.s IL_numberIntDouble
            |      unbox.any ${coreLibraryReference}System.Single
            |      conv.r8
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'SaturatingDoubleToInt32'(float64)
            |      ret
            |IL_numberIntDouble:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Double
            |      dup
            |      brfalse.s IL_numberIntImplementation
            |      unbox.any ${coreLibraryReference}System.Double
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'SaturatingDoubleToInt32'(float64)
            |      ret
            |IL_numberIntImplementation:
            |      pop
            |      ldarg.0
            |      castclass Kotlin.Number
            |      callvirt instance int32 Kotlin.Number::toInt()
            |      ret
            |    }
            |
            |    .method public hidebysig static int8 'NumberToByte'(object 'value') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst Kotlin.Number
            |      dup
            |      brfalse.s IL_numberByteBuiltIn
            |      callvirt instance int8 Kotlin.Number::toByte()
            |      ret
            |IL_numberByteBuiltIn:
            |      pop
            |      ldarg.0
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'NumberToInt'(object)
            |      conv.i1
            |      ret
            |    }
            |
            |    .method public hidebysig static int16 'NumberToShort'(object 'value') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst Kotlin.Number
            |      dup
            |      brfalse.s IL_numberShortBuiltIn
            |      callvirt instance int16 Kotlin.Number::toShort()
            |      ret
            |IL_numberShortBuiltIn:
            |      pop
            |      ldarg.0
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'NumberToInt'(object)
            |      conv.i2
            |      ret
            |    }
            |
            |    .method public hidebysig static int64 'NumberToLong'(object 'value') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.SByte
            |      dup
            |      brfalse.s IL_numberLongShort
            |      unbox.any ${coreLibraryReference}System.SByte
            |      conv.i8
            |      ret
            |IL_numberLongShort:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int16
            |      dup
            |      brfalse.s IL_numberLongInt
            |      unbox.any ${coreLibraryReference}System.Int16
            |      conv.i8
            |      ret
            |IL_numberLongInt:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int32
            |      dup
            |      brfalse.s IL_numberLongLong
            |      unbox.any ${coreLibraryReference}System.Int32
            |      conv.i8
            |      ret
            |IL_numberLongLong:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int64
            |      dup
            |      brfalse.s IL_numberLongFloat
            |      unbox.any ${coreLibraryReference}System.Int64
            |      ret
            |IL_numberLongFloat:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Single
            |      dup
            |      brfalse.s IL_numberLongDouble
            |      unbox.any ${coreLibraryReference}System.Single
            |      conv.r8
            |      call int64 'Kotlin.Runtime.Internal.Intrinsics'::'SaturatingDoubleToInt64'(float64)
            |      ret
            |IL_numberLongDouble:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Double
            |      dup
            |      brfalse.s IL_numberLongImplementation
            |      unbox.any ${coreLibraryReference}System.Double
            |      call int64 'Kotlin.Runtime.Internal.Intrinsics'::'SaturatingDoubleToInt64'(float64)
            |      ret
            |IL_numberLongImplementation:
            |      pop
            |      ldarg.0
            |      castclass Kotlin.Number
            |      callvirt instance int64 Kotlin.Number::toLong()
            |      ret
            |    }
            |
            |    .method public hidebysig static float32 'NumberToFloat'(object 'value') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.SByte
            |      dup
            |      brfalse.s IL_numberFloatShort
            |      unbox.any ${coreLibraryReference}System.SByte
            |      conv.r4
            |      ret
            |IL_numberFloatShort:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int16
            |      dup
            |      brfalse.s IL_numberFloatInt
            |      unbox.any ${coreLibraryReference}System.Int16
            |      conv.r4
            |      ret
            |IL_numberFloatInt:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int32
            |      dup
            |      brfalse.s IL_numberFloatLong
            |      unbox.any ${coreLibraryReference}System.Int32
            |      conv.r4
            |      ret
            |IL_numberFloatLong:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int64
            |      dup
            |      brfalse.s IL_numberFloatFloat
            |      unbox.any ${coreLibraryReference}System.Int64
            |      conv.r4
            |      ret
            |IL_numberFloatFloat:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Single
            |      dup
            |      brfalse.s IL_numberFloatDouble
            |      unbox.any ${coreLibraryReference}System.Single
            |      ret
            |IL_numberFloatDouble:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Double
            |      dup
            |      brfalse.s IL_numberFloatImplementation
            |      unbox.any ${coreLibraryReference}System.Double
            |      conv.r4
            |      ret
            |IL_numberFloatImplementation:
            |      pop
            |      ldarg.0
            |      castclass Kotlin.Number
            |      callvirt instance float32 Kotlin.Number::toFloat()
            |      ret
            |    }
            |
            |    .method public hidebysig static float64 'NumberToDouble'(object 'value') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.SByte
            |      dup
            |      brfalse.s IL_numberDoubleShort
            |      unbox.any ${coreLibraryReference}System.SByte
            |      conv.r8
            |      ret
            |IL_numberDoubleShort:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int16
            |      dup
            |      brfalse.s IL_numberDoubleInt
            |      unbox.any ${coreLibraryReference}System.Int16
            |      conv.r8
            |      ret
            |IL_numberDoubleInt:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int32
            |      dup
            |      brfalse.s IL_numberDoubleLong
            |      unbox.any ${coreLibraryReference}System.Int32
            |      conv.r8
            |      ret
            |IL_numberDoubleLong:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int64
            |      dup
            |      brfalse.s IL_numberDoubleFloat
            |      unbox.any ${coreLibraryReference}System.Int64
            |      conv.r8
            |      ret
            |IL_numberDoubleFloat:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Single
            |      dup
            |      brfalse.s IL_numberDoubleDouble
            |      unbox.any ${coreLibraryReference}System.Single
            |      conv.r8
            |      ret
            |IL_numberDoubleDouble:
            |      pop
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Double
            |      dup
            |      brfalse.s IL_numberDoubleImplementation
            |      unbox.any ${coreLibraryReference}System.Double
            |      ret
            |IL_numberDoubleImplementation:
            |      pop
            |      ldarg.0
            |      castclass Kotlin.Number
            |      callvirt instance float64 Kotlin.Number::toDouble()
            |      ret
            |    }
            |
            |    .method public hidebysig static char 'NumberToChar'(object 'value') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst Kotlin.Number
            |      dup
            |      brfalse.s IL_numberCharBuiltIn
            |      callvirt instance char Kotlin.Number::toChar()
            |      ret
            |IL_numberCharBuiltIn:
            |      pop
            |      ldarg.0
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'NumberToInt'(object)
            |      conv.u2
            |      ret
            |    }
            |
            |    .method public hidebysig static bool 'IsCharSequence'(object 'value') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.String
            |      brtrue.s IL_charSequenceTrue
            |      ldarg.0
            |      isinst Kotlin.CharSequence
            |      brtrue.s IL_charSequenceTrue
            |      ldc.i4.0
            |      ret
            |IL_charSequenceTrue:
            |      ldc.i4.1
            |      ret
            |    }
            |
            |    .method public hidebysig static bool 'IsFunctionOfArity'(
            |        object 'value', int32 'arity') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst Kotlin.FunctionN
            |      dup
            |      brfalse.s IL_functionArityFalse
            |      callvirt instance int32 Kotlin.FunctionN::get_arity()
            |      ldarg.1
            |      ceq
            |      ret
            |IL_functionArityFalse:
            |      pop
            |      ldc.i4.0
            |      ret
            |    }
            |
            |    .method public hidebysig static object 'CheckFunctionCast'(
            |        object 'value', int32 'arity') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      brfalse.s IL_functionCastValid
            |      ldarg.0
            |      ldarg.1
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'IsFunctionOfArity'(object, int32)
            |      brtrue.s IL_functionCastValid
            |      newobj instance void ${coreLibraryReference}System.InvalidCastException::.ctor()
            |      throw
            |IL_functionCastValid:
            |      ldarg.0
            |      ret
            |    }
            |
            |    .method public hidebysig static object 'SafeFunctionCast'(
            |        object 'value', int32 'arity') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      ldarg.1
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'IsFunctionOfArity'(object, int32)
            |      brfalse.s IL_functionSafeCastNull
            |      ldarg.0
            |      ret
            |IL_functionSafeCastNull:
            |      ldnull
            |      ret
            |    }
            |
            |    .method public hidebysig static bool 'IsGenericArray'(object 'value') cil managed
            |    {
            |      .maxstack 2
            |      .locals init (
            |        [0] class ${coreLibraryReference}System.Array 'array',
            |        [1] class ${coreLibraryReference}System.Type 'runtimeType',
            |        [2] class ${coreLibraryReference}System.Type 'elementType'
            |      )
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Array
            |      stloc.0
            |      ldloc.0
            |      brfalse.s IL_genericArrayFalse
            |      ldloc.0
            |      callvirt instance int32 ${coreLibraryReference}System.Array::get_Rank()
            |      ldc.i4.1
            |      bne.un.s IL_genericArrayFalse
            |      ldloc.0
            |      callvirt instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Object::GetType()
            |      stloc.1
            |      ldloc.1
            |      callvirt instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::GetElementType()
            |      stloc.2
            |      ldloc.2
            |      brfalse.s IL_genericArrayFalse
            |      ldloc.1
            |      ldloc.2
            |      callvirt instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::MakeArrayType()
            |      call bool ${coreLibraryReference}System.Type::op_Equality(
            |          class ${coreLibraryReference}System.Type, class ${coreLibraryReference}System.Type)
            |      ret
            |IL_genericArrayFalse:
            |      ldc.i4.0
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Array 'CheckGenericArrayCast'(
            |        object 'value') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brfalse.s IL_genericArrayCastValid
            |      ldarg.0
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'IsGenericArray'(object)
            |      brtrue.s IL_genericArrayCastValid
            |      newobj instance void ${coreLibraryReference}System.InvalidCastException::.ctor()
            |      throw
            |IL_genericArrayCastValid:
            |      ldarg.0
            |      castclass ${coreLibraryReference}System.Array
            |      ret
            |    }
            |
            |    .method public hidebysig static class ${coreLibraryReference}System.Array 'SafeGenericArrayCast'(
            |        object 'value') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'IsGenericArray'(object)
            |      brfalse.s IL_genericArraySafeCastNull
            |      ldarg.0
            |      castclass ${coreLibraryReference}System.Array
            |      ret
            |IL_genericArraySafeCastNull:
            |      ldnull
            |      ret
            |    }
            |
            |    .method public hidebysig static object 'CheckCharSequenceCast'(object 'value') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      brfalse.s IL_charSequenceCastValid
            |      ldarg.0
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'IsCharSequence'(object)
            |      brtrue.s IL_charSequenceCastValid
            |      newobj instance void ${coreLibraryReference}System.InvalidCastException::.ctor()
            |      throw
            |IL_charSequenceCastValid:
            |      ldarg.0
            |      ret
            |    }
            |
            |    .method public hidebysig static object 'SafeCharSequenceCast'(object 'value') cil managed
            |    {
            |      .maxstack 1
            |      ldarg.0
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'IsCharSequence'(object)
            |      brfalse.s IL_charSequenceSafeCastNull
            |      ldarg.0
            |      ret
            |IL_charSequenceSafeCastNull:
            |      ldnull
            |      ret
            |    }
            |
            |    .method public hidebysig static int32 'CharSequenceLength'(object 'value') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.String
            |      dup
            |      brfalse.s IL_charSequenceLengthImplementation
            |      callvirt instance int32 ${coreLibraryReference}System.String::get_Length()
            |      ret
            |IL_charSequenceLengthImplementation:
            |      pop
            |      ldarg.0
            |      castclass Kotlin.CharSequence
            |      callvirt instance int32 Kotlin.CharSequence::get_length()
            |      ret
            |    }
            |
            |    .method public hidebysig static char 'CharSequenceGet'(object 'value', int32 'index') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.String
            |      dup
            |      brfalse.s IL_charSequenceGetImplementation
            |      ldarg.1
            |      callvirt instance char ${coreLibraryReference}System.String::get_Chars(int32)
            |      ret
            |IL_charSequenceGetImplementation:
            |      pop
            |      ldarg.0
            |      castclass Kotlin.CharSequence
            |      ldarg.1
            |      callvirt instance char Kotlin.CharSequence::get(int32)
            |      ret
            |    }
            |
            |    .method public hidebysig static object 'CharSequenceSubSequence'(
            |        object 'value', int32 'startIndex', int32 'endIndex') cil managed
            |    {
            |      .maxstack 4
            |      .locals init ([0] string 'stringValue')
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.String
            |      stloc.0
            |      ldloc.0
            |      brfalse.s IL_charSequenceSubSequenceImplementation
            |      ldarg.1
            |      ldc.i4.0
            |      blt.s IL_charSequenceSubSequenceInvalid
            |      ldarg.2
            |      ldarg.1
            |      blt.s IL_charSequenceSubSequenceInvalid
            |      ldarg.2
            |      ldloc.0
            |      callvirt instance int32 ${coreLibraryReference}System.String::get_Length()
            |      bgt.s IL_charSequenceSubSequenceInvalid
            |      ldloc.0
            |      ldarg.1
            |      ldarg.2
            |      ldarg.1
            |      sub
            |      callvirt instance string ${coreLibraryReference}System.String::Substring(int32, int32)
            |      ret
            |IL_charSequenceSubSequenceInvalid:
            |      newobj instance void ${coreLibraryReference}System.IndexOutOfRangeException::.ctor()
            |      throw
            |IL_charSequenceSubSequenceImplementation:
            |      ldarg.0
            |      castclass Kotlin.CharSequence
            |      ldarg.1
            |      ldarg.2
            |      callvirt instance object Kotlin.CharSequence::subSequence(int32, int32)
            |      ret
            |    }
            |
            |    .method public hidebysig static int32 'ComparableCompareTo'(object 'receiver', object 'other') cil managed
            |    {
            |      .maxstack 2
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Single
            |      brfalse.s IL_comparableDouble
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Single
            |      ldarg.1
            |      unbox.any ${coreLibraryReference}System.Single
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'CompareFloat'(float32, float32)
            |      ret
            |IL_comparableDouble:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Double
            |      brfalse.s IL_comparableString
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Double
            |      ldarg.1
            |      unbox.any ${coreLibraryReference}System.Double
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'CompareDouble'(float64, float64)
            |      ret
            |IL_comparableString:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.String
            |      brfalse.s IL_comparableInterface
            |      ldarg.1
            |      brtrue.s IL_comparableStringArgument
            |      newobj instance void ${coreLibraryReference}System.NullReferenceException::.ctor()
            |      throw
            |IL_comparableStringArgument:
            |      ldarg.0
            |      castclass ${coreLibraryReference}System.String
            |      ldarg.1
            |      castclass ${coreLibraryReference}System.String
            |      call int32 ${coreLibraryReference}System.String::CompareOrdinal(string, string)
            |      ret
            |IL_comparableInterface:
            |      ldarg.0
            |      castclass ${coreLibraryReference}System.IComparable
            |      ldarg.1
            |      callvirt instance int32 ${coreLibraryReference}System.IComparable::CompareTo(object)
            |      ret
            |    }
            |
            |    .method public hidebysig static bool 'AreEqual'(object, object) cil managed
            |    {
            |      .maxstack 2
            |      .locals init (
            |        [0] float64 'leftDouble',
            |        [1] float64 'rightDouble',
            |        [2] float32 'leftFloat',
            |        [3] float32 'rightFloat'
            |      )
            |      ldarg.0
            |      brtrue.s IL_leftNotNull
            |      ldarg.1
            |      ldnull
            |      ceq
            |      ret
            |IL_leftNotNull:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Single
            |      brfalse.s IL_leftNotFloat
            |      ldarg.1
            |      isinst ${coreLibraryReference}System.Single
            |      brfalse.s IL_notEqual
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Single
            |      stloc.2
            |      ldarg.1
            |      unbox.any ${coreLibraryReference}System.Single
            |      stloc.3
            |      ldloc.2
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'FloatToIntBits'(float32)
            |      ldloc.3
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'FloatToIntBits'(float32)
            |      ceq
            |      ret
            |IL_leftNotFloat:
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
            |    .method public hidebysig static bool 'AreEqualGeneric'<T>(
            |        !!0 'left', !!0 'right') cil managed
            |    {
            |      .maxstack 2
            |      ldsfld bool class Kotlin.Runtime.Internal.'GenericEqualityMode`1'<!!0>::'UseConstrainedValue'
            |      brfalse.s IL_genericEqualityFallback
            |      ldarga.s 'left'
            |      ldarg.1
            |      box !!0
            |      constrained. !!0
            |      callvirt instance bool ${coreLibraryReference}System.Object::Equals(object)
            |      ret
            |IL_genericEqualityFallback:
            |      ldarg.0
            |      box !!0
            |      ldarg.1
            |      box !!0
            |      call bool 'Kotlin.Runtime.Internal.Intrinsics'::'AreEqual'(object, object)
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
            |      isinst ${coreLibraryReference}System.Single
            |      brfalse.s IL_hashDouble
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Single
            |      call int32 'Kotlin.Runtime.Internal.Intrinsics'::'FloatToIntBits'(float32)
            |      ret
            |IL_hashDouble:
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
            |      brfalse.s IL_hashSByte
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Boolean
            |      brtrue.s IL_hashTrue
            |      ldc.i4 1237
            |      ret
            |IL_hashTrue:
            |      ldc.i4 1231
            |      ret
            |IL_hashSByte:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.SByte
            |      brfalse.s IL_hashInt16
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.SByte
            |      conv.i1
            |      ret
            |IL_hashInt16:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int16
            |      brfalse.s IL_hashChar
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Int16
            |      conv.i2
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
            |      brfalse.s IL_stringFloat
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Boolean
            |      brtrue.s IL_stringTrue
            |      ldstr "false"
            |      ret
            |IL_stringTrue:
            |      ldstr "true"
            |      ret
            |IL_stringFloat:
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Single
            |      brfalse.s IL_stringDouble
            |      ldarg.0
            |      unbox.any ${coreLibraryReference}System.Single
            |      call string 'Kotlin.Runtime.Internal.DoubleFormatting'::'FloatToString'(float32)
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
            |      isinst ${coreLibraryReference}System.SByte
            |      brtrue.s IL_stringInvariant
            |      ldarg.0
            |      isinst ${coreLibraryReference}System.Int16
            |      brtrue.s IL_stringInvariant
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
            |    .method public hidebysig static void 'ArrayFill'(
            |        class ${coreLibraryReference}System.Array 'array',
            |        object 'element',
            |        int32 'fromIndex',
            |        int32 'toIndex') cil managed
            |    {
            |      .maxstack 3
            |      .locals init ([0] int32 'index')
            |      ldarg.2
            |      ldc.i4.0
            |      blt.s IL_arrayFillOutOfBounds
            |      ldarg.3
            |      ldarg.0
            |      callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()
            |      bgt.s IL_arrayFillOutOfBounds
            |      ldarg.2
            |      ldarg.3
            |      bgt.s IL_arrayFillReversed
            |      ldarg.2
            |      stloc.0
            |IL_arrayFillLoop:
            |      ldloc.0
            |      ldarg.3
            |      bge.s IL_arrayFillDone
            |      ldarg.0
            |      ldarg.1
            |      ldloc.0
            |      callvirt instance void ${coreLibraryReference}System.Array::SetValue(object, int32)
            |      ldloc.0
            |      ldc.i4.1
            |      add
            |      stloc.0
            |      br.s IL_arrayFillLoop
            |IL_arrayFillDone:
            |      ret
            |IL_arrayFillOutOfBounds:
            |      newobj instance void ${coreLibraryReference}System.IndexOutOfRangeException::.ctor()
            |      throw
            |IL_arrayFillReversed:
            |      newobj instance void ${coreLibraryReference}System.ArgumentException::.ctor()
            |      throw
            |    }
            |
            |    .method assembly hidebysig static int32 'FloatToIntBits'(float32 'value') cil managed
            |    {
            |      .maxstack 2
            |      .locals init ([0] valuetype Kotlin.Runtime.Internal.FloatIntBits 'bits')
            |      ldarg.0
            |      ldarg.0
            |      ceq
            |      brtrue.s IL_floatBitsNotNaN
            |      ldc.i4 2143289344
            |      ret
            |IL_floatBitsNotNaN:
            |      ldloca.s 0
            |      ldarg.0
            |      stfld float32 Kotlin.Runtime.Internal.FloatIntBits::FloatValue
            |      ldloca.s 0
            |      ldfld int32 Kotlin.Runtime.Internal.FloatIntBits::IntValue
            |      ret
            |    }
            |
            |    .method public hidebysig static int32 'CompareFloat'(float32 'left', float32 'right') cil managed
            |    {
            |      .maxstack 2
            |      .locals init ([0] int32 'leftBits', [1] int32 'rightBits')
            |      ldarg.0
            |      ldarg.1
            |      blt.s IL_compareFloatLess
            |      ldarg.0
            |      ldarg.1
            |      bgt.s IL_compareFloatGreater
            |      ldarg.0
            |      call int32 Kotlin.Runtime.Internal.Intrinsics::FloatToIntBits(float32)
            |      stloc.0
            |      ldarg.1
            |      call int32 Kotlin.Runtime.Internal.Intrinsics::FloatToIntBits(float32)
            |      stloc.1
            |      ldloc.0
            |      ldloc.1
            |      beq.s IL_compareFloatEqual
            |      ldloc.0
            |      ldloc.1
            |      blt.s IL_compareFloatLess
            |IL_compareFloatGreater:
            |      ldc.i4.1
            |      ret
            |IL_compareFloatLess:
            |      ldc.i4.m1
            |      ret
            |IL_compareFloatEqual:
            |      ldc.i4.0
            |      ret
            |    }
            |
            |    .method public hidebysig static int32 'CompareDouble'(float64 'left', float64 'right') cil managed
            |    {
            |      .maxstack 2
            |      .locals init ([0] int64 'leftBits', [1] int64 'rightBits')
            |      ldarg.0
            |      ldarg.1
            |      blt.s IL_compareDoubleLess
            |      ldarg.0
            |      ldarg.1
            |      bgt.s IL_compareDoubleGreater
            |      ldarg.0
            |      call int64 Kotlin.Runtime.Internal.Intrinsics::DoubleToLongBits(float64)
            |      stloc.0
            |      ldarg.1
            |      call int64 Kotlin.Runtime.Internal.Intrinsics::DoubleToLongBits(float64)
            |      stloc.1
            |      ldloc.0
            |      ldloc.1
            |      beq.s IL_compareDoubleEqual
            |      ldloc.0
            |      ldloc.1
            |      blt.s IL_compareDoubleLess
            |IL_compareDoubleGreater:
            |      ldc.i4.1
            |      ret
            |IL_compareDoubleLess:
            |      ldc.i4.m1
            |      ret
            |IL_compareDoubleEqual:
            |      ldc.i4.0
            |      ret
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
            |  .method public hidebysig static string 'FloatToString'(float32 'value') cil managed
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
            |    call bool ${coreLibraryReference}System.Single::IsNaN(float32)
            |    brfalse IL_floatNotNaN
            |    ldstr "NaN"
            |    ret
            |IL_floatNotNaN:
            |    ldarg.0
            |    call bool ${coreLibraryReference}System.Single::IsPositiveInfinity(float32)
            |    brfalse IL_floatNotPositiveInfinity
            |    ldstr "Infinity"
            |    ret
            |IL_floatNotPositiveInfinity:
            |    ldarg.0
            |    call bool ${coreLibraryReference}System.Single::IsNegativeInfinity(float32)
            |    brfalse IL_floatNotNegativeInfinity
            |    ldstr "-Infinity"
            |    ret
            |IL_floatNotNegativeInfinity:
            |    ldarg.0
            |    call int32 'Kotlin.Runtime.Internal.Intrinsics'::'FloatToIntBits'(float32)
            |    ldc.i4 -2147483648
            |    bne.un IL_floatFinite
            |    ldstr "-0.0"
            |    ret
            |IL_floatFinite:
            |    ldarga.s 0
            |    ldstr "G7"
            |    call class ${coreLibraryReference}System.Globalization.CultureInfo ${coreLibraryReference}System.Globalization.CultureInfo::get_InvariantCulture()
            |    call instance string ${coreLibraryReference}System.Single::ToString(string, class ${coreLibraryReference}System.IFormatProvider)
            |    stloc.0
            |    ldloc.0
            |    call class ${coreLibraryReference}System.Globalization.CultureInfo ${coreLibraryReference}System.Globalization.CultureInfo::get_InvariantCulture()
            |    call float32 ${coreLibraryReference}System.Single::Parse(string, class ${coreLibraryReference}System.IFormatProvider)
            |    call int32 'Kotlin.Runtime.Internal.Intrinsics'::'FloatToIntBits'(float32)
            |    ldarg.0
            |    call int32 'Kotlin.Runtime.Internal.Intrinsics'::'FloatToIntBits'(float32)
            |    beq.s IL_floatDigitsReady
            |    ldarga.s 0
            |    ldstr "G9"
            |    call class ${coreLibraryReference}System.Globalization.CultureInfo ${coreLibraryReference}System.Globalization.CultureInfo::get_InvariantCulture()
            |    call instance string ${coreLibraryReference}System.Single::ToString(string, class ${coreLibraryReference}System.IFormatProvider)
            |    stloc.0
            |IL_floatDigitsReady:
            |    ldarg.0
            |    ldc.r4 0.0
            |    beq IL_floatDecimal
            |    ldarg.0
            |    conv.r8
            |    call float64 ${coreLibraryReference}System.Math::Abs(float64)
            |    ldc.r8 10000000.
            |    bge IL_floatScientific
            |    ldarg.0
            |    conv.r8
            |    call float64 ${coreLibraryReference}System.Math::Abs(float64)
            |    ldc.r8 0.001
            |    blt IL_floatScientific
            |IL_floatDecimal:
            |    ldloc.0
            |    ldc.i4.s 46
            |    callvirt instance int32 ${coreLibraryReference}System.String::IndexOf(char)
            |    ldc.i4.0
            |    bge IL_floatDecimalHasDot
            |    ldloc.0
            |    ldstr ".0"
            |    call string ${coreLibraryReference}System.String::Concat(string, string)
            |    ret
            |IL_floatDecimalHasDot:
            |    ldloc.0
            |    ret
            |IL_floatScientific:
            |    ldloc.0
            |    ldc.i4.s 69
            |    callvirt instance int32 ${coreLibraryReference}System.String::IndexOf(char)
            |    stloc.1
            |    ldloc.1
            |    ldc.i4.0
            |    bge IL_floatScientificFromE
            |    ldloc.0
            |    ldc.i4.0
            |    callvirt instance char ${coreLibraryReference}System.String::get_Chars(int32)
            |    ldc.i4.s 45
            |    ceq
            |    stloc.s 'neg'
            |    ldloc.s 'neg'
            |    brfalse IL_floatSignStripped
            |    ldloc.0
            |    ldc.i4.1
            |    callvirt instance string ${coreLibraryReference}System.String::Substring(int32)
            |    stloc.0
            |IL_floatSignStripped:
            |    ldloc.0
            |    ldc.i4.s 46
            |    callvirt instance int32 ${coreLibraryReference}System.String::IndexOf(char)
            |    stloc.1
            |    ldloc.1
            |    ldc.i4.0
            |    bge IL_floatRemoveDot
            |    ldloc.0
            |    stloc.2
            |    ldloc.0
            |    callvirt instance int32 ${coreLibraryReference}System.String::get_Length()
            |    stloc.3
            |    br IL_floatDotRemoved
            |IL_floatRemoveDot:
            |    ldloc.0
            |    ldloc.1
            |    ldc.i4.1
            |    callvirt instance string ${coreLibraryReference}System.String::Remove(int32, int32)
            |    stloc.2
            |    ldloc.1
            |    stloc.3
            |IL_floatDotRemoved:
            |    ldloc.3
            |    ldc.i4.1
            |    sub
            |    stloc.3
            |    ldc.i4.0
            |    stloc.s 'i'
            |IL_floatLeadingZeroLoop:
            |    ldloc.2
            |    ldloc.s 'i'
            |    callvirt instance char ${coreLibraryReference}System.String::get_Chars(int32)
            |    ldc.i4.s 48
            |    bne.un IL_floatLeadingZerosSkipped
            |    ldloc.s 'i'
            |    ldc.i4.1
            |    add
            |    stloc.s 'i'
            |    ldloc.3
            |    ldc.i4.1
            |    sub
            |    stloc.3
            |    br IL_floatLeadingZeroLoop
            |IL_floatLeadingZerosSkipped:
            |    ldloc.2
            |    ldloc.s 'i'
            |    callvirt instance string ${coreLibraryReference}System.String::Substring(int32)
            |    stloc.2
            |    ldloc.2
            |    callvirt instance int32 ${coreLibraryReference}System.String::get_Length()
            |    stloc.s 'i'
            |IL_floatTrailingZeroLoop:
            |    ldloc.s 'i'
            |    ldc.i4.1
            |    ble IL_floatTrailingZerosTrimmed
            |    ldloc.2
            |    ldloc.s 'i'
            |    ldc.i4.1
            |    sub
            |    callvirt instance char ${coreLibraryReference}System.String::get_Chars(int32)
            |    ldc.i4.s 48
            |    bne.un IL_floatTrailingZerosTrimmed
            |    ldloc.s 'i'
            |    ldc.i4.1
            |    sub
            |    stloc.s 'i'
            |    br IL_floatTrailingZeroLoop
            |IL_floatTrailingZerosTrimmed:
            |    ldloc.2
            |    ldc.i4.0
            |    ldloc.s 'i'
            |    callvirt instance string ${coreLibraryReference}System.String::Substring(int32, int32)
            |    stloc.2
            |    ldloc.2
            |    callvirt instance int32 ${coreLibraryReference}System.String::get_Length()
            |    ldc.i4.1
            |    bne.un IL_floatInsertDot
            |    ldloc.2
            |    ldstr "0"
            |    call string ${coreLibraryReference}System.String::Concat(string, string)
            |    stloc.2
            |IL_floatInsertDot:
            |    ldloc.2
            |    ldc.i4.1
            |    ldstr "."
            |    callvirt instance string ${coreLibraryReference}System.String::Insert(int32, string)
            |    stloc.2
            |    ldloc.s 'neg'
            |    brfalse IL_floatMantissaSigned
            |    ldstr "-"
            |    ldloc.2
            |    call string ${coreLibraryReference}System.String::Concat(string, string)
            |    stloc.2
            |IL_floatMantissaSigned:
            |    ldloc.2
            |    ldstr "E"
            |    ldloc.3
            |    box ${coreLibraryReference}System.Int32
            |    ldnull
            |    call class ${coreLibraryReference}System.Globalization.CultureInfo ${coreLibraryReference}System.Globalization.CultureInfo::get_InvariantCulture()
            |    callvirt instance string ${coreLibraryReference}System.IFormattable::ToString(string, class ${coreLibraryReference}System.IFormatProvider)
            |    call string ${coreLibraryReference}System.String::Concat(string, string, string)
            |    ret
            |IL_floatScientificFromE:
            |    ldloc.0
            |    ldc.i4.0
            |    ldloc.1
            |    callvirt instance string ${coreLibraryReference}System.String::Substring(int32, int32)
            |    dup
            |    ldc.i4.s 46
            |    callvirt instance int32 ${coreLibraryReference}System.String::IndexOf(char)
            |    ldc.i4.0
            |    bge IL_floatMantissaHasDot
            |    ldstr ".0"
            |    call string ${coreLibraryReference}System.String::Concat(string, string)
            |IL_floatMantissaHasDot:
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
            |
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

    private fun propertyAccessorSupportTypesIl(coreLibraryReference: String): String {
        val getterTypesIl = (0..2).joinToString("\n\n") { arity ->
            val arguments = (1..arity).joinToString(", ") { index -> "object receiver$index" }
            val loadArguments = (1..arity).joinToString("\n") { index -> "      ldarg.$index" }
            val getSignature = when (arity) {
                0 -> "instance object Kotlin.KProperty0::Get()"
                1 -> "instance object Kotlin.KProperty1::Get(object)"
                else -> "instance object Kotlin.KProperty2::Get(object, object)"
            }
            val invokeSignature = if (arguments.isEmpty()) "Invoke()" else "Invoke($arguments)"
            val invokeOverride = "instance object Kotlin.Function$arity::Invoke(" +
                    List(arity) { "object" }.joinToString(", ") + ")"
            val maxStack = arity + 1
            """
            .class private auto ansi sealed beforefieldinit PropertyGetter${arity}Accessor
                   extends Kotlin.Runtime.Internal.PropertyAccessorBase
                   implements Kotlin.KProperty$arity/Getter
            {
              .method assembly hidebysig specialname rtspecialname instance void .ctor(
                  class Kotlin.KProperty 'property', object[] 'signature',
                  class Kotlin.Function2 'parameterFactory', class Kotlin.Collections.List 'annotations',
                  int32 'flags') cil managed
              {
                .maxstack 8
                ldarg.0
                ldarg.1
                ldstr "<get-"
                ldarg.1
                callvirt instance string Kotlin.KCallable::get_name()
                ldstr ">"
                call string ${coreLibraryReference}System.String::Concat(string, string, string)
                ldarg.2
                ldarg.3
                ldarg.s 4
                ldarg.s 5
                ldc.i4.$arity
                call instance void Kotlin.Runtime.Internal.PropertyAccessorBase::.ctor(
                    class Kotlin.KProperty, string, object[], class Kotlin.Function2,
                    class Kotlin.Collections.List, int32, int32)
                ret
              }

              .method public hidebysig newslot virtual final instance object $invokeSignature cil managed
              {
                .override method $invokeOverride
                .maxstack $maxStack
                ldarg.0
                call instance class Kotlin.KProperty Kotlin.Runtime.Internal.PropertyAccessorBase::GetProperty()
                castclass Kotlin.KProperty$arity
$loadArguments
                callvirt $getSignature
                ret
              }
            }
            """.trimIndent()
        }
        val setterTypesIl = (0..2).joinToString("\n\n") { receiverCount ->
            val parameterCount = receiverCount + 1
            val arguments = (1..receiverCount).map { index -> "object receiver$index" } + "object 'value'"
            val loadArguments = (1..parameterCount).joinToString("\n") { index -> "      ldarg.$index" }
            val setParameterTypes = List(parameterCount) { "object" }.joinToString(", ")
            val setSignature = "instance void Kotlin.KMutableProperty$receiverCount::Set($setParameterTypes)"
            val invokeSignature = "Invoke(${arguments.joinToString(", ")})"
            val maxStack = parameterCount + 1
            """
            .class private auto ansi sealed beforefieldinit PropertySetter${receiverCount}Accessor
                   extends Kotlin.Runtime.Internal.PropertyAccessorBase
                   implements Kotlin.KMutableProperty$receiverCount/Setter
            {
              .method assembly hidebysig specialname rtspecialname instance void .ctor(
                  class Kotlin.KMutableProperty 'property', object[] 'signature',
                  class Kotlin.Function2 'parameterFactory', class Kotlin.Collections.List 'annotations',
                  int32 'flags') cil managed
              {
                .maxstack 8
                ldarg.0
                ldarg.1
                ldstr "<set-"
                ldarg.1
                callvirt instance string Kotlin.KCallable::get_name()
                ldstr ">"
                call string ${coreLibraryReference}System.String::Concat(string, string, string)
                ldarg.2
                ldarg.3
                ldarg.s 4
                ldarg.s 5
                ldc.i4.$parameterCount
                call instance void Kotlin.Runtime.Internal.PropertyAccessorBase::.ctor(
                    class Kotlin.KProperty, string, object[], class Kotlin.Function2,
                    class Kotlin.Collections.List, int32, int32)
                ret
              }

              .method public hidebysig newslot virtual final instance object $invokeSignature cil managed
              {
                .override method instance object Kotlin.Function$parameterCount::$invokeSignature
                .maxstack $maxStack
                ldarg.0
                call instance class Kotlin.KProperty Kotlin.Runtime.Internal.PropertyAccessorBase::GetProperty()
                castclass Kotlin.KMutableProperty$receiverCount
$loadArguments
                callvirt $setSignature
                ldsfld class Kotlin.Unit Kotlin.Unit::INSTANCE
                ret
              }
            }
            """.trimIndent()
        }
        return """
            .class private abstract auto ansi beforefieldinit PropertyAccessorBase
                   extends Kotlin.Runtime.Internal.FunctionReferenceBase
                   implements Kotlin.KProperty/Accessor, Kotlin.KFunction
            {
              .field private initonly class Kotlin.KProperty 'property'

              .method family hidebysig specialname rtspecialname instance void .ctor(
                  class Kotlin.KProperty 'property', string 'name', object[] 'signature',
                  class Kotlin.Function2 'parameterFactory', class Kotlin.Collections.List 'annotations',
                  int32 'flags', int32 'arity') cil managed
              {
                .maxstack 9
                ldarg.0
                ldarg.2
                ldarg.s 7
                ldarg.s 6
                ldc.i4.1
                ldarg.2
                ldarg.s 5
                ldarg.3
                ldarg.s 4
                call instance void Kotlin.Runtime.Internal.FunctionReferenceBase::.ctor(
                    string, int32, int32, int32, string, class Kotlin.Collections.List,
                    object[], class Kotlin.Function2)
                ldarg.0
                ldarg.1
                stfld class Kotlin.KProperty Kotlin.Runtime.Internal.PropertyAccessorBase::'property'
                ret
              }

              .method family hidebysig instance class Kotlin.KProperty GetProperty() cil managed
              {
                .maxstack 1
                ldarg.0
                ldfld class Kotlin.KProperty Kotlin.Runtime.Internal.PropertyAccessorBase::'property'
                ret
              }

              .method family hidebysig virtual final instance object BoundValueAt(int32 'index') cil managed
              {
                .maxstack 1
                ldarg.0
                ldfld class Kotlin.KProperty Kotlin.Runtime.Internal.PropertyAccessorBase::'property'
                ret
              }

              .method public hidebysig specialname newslot virtual final instance class Kotlin.KProperty get_property() cil managed
              {
                .override method instance class Kotlin.KProperty Kotlin.KProperty/Accessor::get_property()
                .maxstack 1
                ldarg.0
                ldfld class Kotlin.KProperty Kotlin.Runtime.Internal.PropertyAccessorBase::'property'
                ret
              }

            }

$getterTypesIl

$setterTypesIl

            .class private abstract sealed auto ansi beforefieldinit PropertyAccessorFactory
                   extends ${coreLibraryReference}System.Object
            {
              .method assembly hidebysig static class Kotlin.KProperty/Getter CreateGetter(
                  class Kotlin.KProperty 'property', int32 'arity', object[] 'signature',
                  class Kotlin.Function2 'parameterFactory', class Kotlin.Collections.List 'annotations',
                  int32 'flags') cil managed
              {
                .maxstack 5
                ldarg.1
                switch (PAF_Get0, PAF_Get1, PAF_Get2)
                ldstr "Unsupported Kotlin property-reference getter arity."
                newobj instance void ${coreLibraryReference}System.InvalidOperationException::.ctor(string)
                throw
              PAF_Get0:
                ldarg.0
                ldarg.2
                ldarg.3
                ldarg.s 4
                ldarg.s 5
                newobj instance void Kotlin.Runtime.Internal.PropertyGetter0Accessor::.ctor(
                    class Kotlin.KProperty, object[], class Kotlin.Function2, class Kotlin.Collections.List, int32)
                ret
              PAF_Get1:
                ldarg.0
                ldarg.2
                ldarg.3
                ldarg.s 4
                ldarg.s 5
                newobj instance void Kotlin.Runtime.Internal.PropertyGetter1Accessor::.ctor(
                    class Kotlin.KProperty, object[], class Kotlin.Function2, class Kotlin.Collections.List, int32)
                ret
              PAF_Get2:
                ldarg.0
                ldarg.2
                ldarg.3
                ldarg.s 4
                ldarg.s 5
                newobj instance void Kotlin.Runtime.Internal.PropertyGetter2Accessor::.ctor(
                    class Kotlin.KProperty, object[], class Kotlin.Function2, class Kotlin.Collections.List, int32)
                ret
              }

              .method assembly hidebysig static class Kotlin.KMutableProperty/Setter CreateSetter(
                  class Kotlin.KMutableProperty 'property', int32 'receiverCount', object[] 'signature',
                  class Kotlin.Function2 'parameterFactory', class Kotlin.Collections.List 'annotations',
                  int32 'flags') cil managed
              {
                .maxstack 5
                ldarg.1
                switch (PAF_Set0, PAF_Set1, PAF_Set2)
                ldstr "Unsupported Kotlin property-reference setter arity."
                newobj instance void ${coreLibraryReference}System.InvalidOperationException::.ctor(string)
                throw
              PAF_Set0:
                ldarg.0
                ldarg.2
                ldarg.3
                ldarg.s 4
                ldarg.s 5
                newobj instance void Kotlin.Runtime.Internal.PropertySetter0Accessor::.ctor(
                    class Kotlin.KMutableProperty, object[], class Kotlin.Function2, class Kotlin.Collections.List, int32)
                ret
              PAF_Set1:
                ldarg.0
                ldarg.2
                ldarg.3
                ldarg.s 4
                ldarg.s 5
                newobj instance void Kotlin.Runtime.Internal.PropertySetter1Accessor::.ctor(
                    class Kotlin.KMutableProperty, object[], class Kotlin.Function2, class Kotlin.Collections.List, int32)
                ret
              PAF_Set2:
                ldarg.0
                ldarg.2
                ldarg.3
                ldarg.s 4
                ldarg.s 5
                newobj instance void Kotlin.Runtime.Internal.PropertySetter2Accessor::.ctor(
                    class Kotlin.KMutableProperty, object[], class Kotlin.Function2, class Kotlin.Collections.List, int32)
                ret
              }
            }
        """.trimIndent()
    }

    /**
     * Shared arity-correct KFunction carriers for producer-owned KClass member enumeration.
     *
     * One generated Function3 dispatcher remains in each reflected producer class. These Runtime
     * types preserve the exact fixed Function$arity physical test while keeping member count out
     * of producer TypeDef growth. The dispatcher receives only boxed arguments; its ordinary IR
     * thunks own all casts, default dispatch and suspend continuation forwarding.
     */
    private fun memberReferenceSupportTypesIl(
        coreLibraryReference: String,
        compilerAbiTypeAttributesIl: String,
    ): String {
        val systemObject = coreLibraryReference + "System.Object"
        val systemInt32 = coreLibraryReference + "System.Int32"
        val systemArgumentException = coreLibraryReference + "System.ArgumentException"
        val systemNotSupportedException = coreLibraryReference + "System.NotSupportedException"
        val suspendFlag = DotNetFunctionReferenceFlags.IS_SUSPEND
        val constructorParameters =
            "class Kotlin.Function3 'dispatcher', int32 'memberIndex', string 'id', int32 'arity', " +
                    "int32 'flags', string 'name', class Kotlin.Collections.List 'annotations', " +
                    "object[] 'signature', class Kotlin.Function2 'parameterFactory', object[] 'emptyVarargs'"

        fun loadArgument(index: Int): String = when (index) {
            0 -> "ldarg.0"
            1 -> "ldarg.1"
            2 -> "ldarg.2"
            3 -> "ldarg.3"
            else -> "ldarg.s $index"
        }

        val fixedCarrierTypes = (0 until BuiltInFunctionArity.BIG_ARITY).joinToString("\n\n") { arity ->
            val parameterNames = (0 until arity).joinToString(", ") { index -> "object p$index" }
            val parameterTypes = List(arity) { "object" }.joinToString(", ")
            val argumentStores = (0 until arity).joinToString("\n") { index ->
                val load = loadArgument(index + 1)
                """
                  ldloc.0
                  ldc.i4 $index
                  $load
                  stelem.ref
                """.trimIndent()
            }
            """
            .class private auto ansi sealed beforefieldinit MemberFunction$arity
                   extends Kotlin.Runtime.Internal.MemberFunctionReferenceBase
                   implements Kotlin.Function$arity
            {
              .method assembly hidebysig specialname rtspecialname instance void .ctor(
                  $constructorParameters) cil managed
              {
                .maxstack 11
                ldarg.0
                ldarg.1
                ldarg.2
                ldarg.3
                ldarg.s 4
                ldarg.s 5
                ldarg.s 6
                ldarg.s 7
                ldarg.s 8
                ldarg.s 9
                ldarg.s 10
                call instance void Kotlin.Runtime.Internal.MemberFunctionReferenceBase::.ctor(
                    $constructorParameters)
                ret
              }

              .method public hidebysig newslot virtual final instance object Invoke($parameterNames) cil managed
              {
                .override method instance object Kotlin.Function$arity::Invoke($parameterTypes)
                .maxstack 3
                .locals init ([0] object[] args)
                ldc.i4 $arity
                newarr $systemObject
                stloc.0
$argumentStores
                ldarg.0
                ldloc.0
                callvirt instance object Kotlin.Runtime.Internal.FunctionReferenceBase::CallErased(object[])
                ret
              }
            }
            """.trimIndent()
        }
        val switchLabels = (0 until BuiltInFunctionArity.BIG_ARITY)
            .joinToString(", ") { arity -> "MRF_Create$arity" }
        val factoryCases = (0 until BuiltInFunctionArity.BIG_ARITY).joinToString("\n") { arity ->
            val loads = (0..9).joinToString("\n") { index -> "      " + loadArgument(index) }
            """
              MRF_Create$arity:
$loads
                newobj instance void Kotlin.Runtime.Internal.MemberFunction$arity::.ctor(
                    $constructorParameters)
                ret
            """.trimIndent()
        }
        val factoryLoads = (0..9).joinToString("\n") { index -> "      " + loadArgument(index) }

        return """
            .class private abstract auto ansi beforefieldinit MemberFunctionReferenceBase
                   extends Kotlin.Runtime.Internal.FunctionReferenceBase
                   implements Kotlin.KFunction
            {
              .field private initonly class Kotlin.Function3 'dispatcher'
              .field private initonly int32 'memberIndex'
              .field family initonly int32 'memberArity'
              .field private initonly bool 'isSuspend'
              .field private initonly object[] 'emptyVarargs'

              .method family hidebysig specialname rtspecialname instance void .ctor(
                  $constructorParameters) cil managed
              {
                .maxstack 9
                ldarg.0
                ldarg.3
                ldarg.s 4
                ldarg.s 5
                ldc.i4.0
                ldarg.s 6
                ldarg.s 7
                ldarg.s 8
                ldarg.s 9
                call instance void Kotlin.Runtime.Internal.FunctionReferenceBase::.ctor(
                    string, int32, int32, int32, string, class Kotlin.Collections.List,
                    object[], class Kotlin.Function2)
                ldarg.0
                ldarg.1
                stfld class Kotlin.Function3 Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'dispatcher'
                ldarg.0
                ldarg.2
                stfld int32 Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'memberIndex'
                ldarg.0
                ldarg.s 4
                stfld int32 Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'memberArity'
                ldarg.0
                ldarg.s 5
                ldc.i4 $suspendFlag
                and
                ldc.i4.0
                cgt.un
                stfld bool Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'isSuspend'
                ldarg.0
                ldarg.s 10
                stfld object[] Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'emptyVarargs'
                ret
              }

              .method family hidebysig virtual instance object CallErased(object[] 'args') cil managed
              {
                .maxstack 4
                ldarg.0
                ldfld class Kotlin.Function3 Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'dispatcher'
                ldarg.0
                ldfld int32 Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'memberIndex'
                box $systemInt32
                ldarg.1
                ldnull
                callvirt instance object Kotlin.Function3::Invoke(object, object, object)
                ret
              }

              .method family hidebysig virtual instance object CallDefaultErased(
                  object[] 'args', class Kotlin.IntArray 'masks') cil managed
              {
                .maxstack 4
                ldarg.0
                ldfld class Kotlin.Function3 Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'dispatcher'
                ldarg.0
                ldfld int32 Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'memberIndex'
                box $systemInt32
                ldarg.1
                ldarg.2
                callvirt instance object Kotlin.Function3::Invoke(object, object, object)
                ret
              }

              .method family hidebysig virtual instance object CallByErased(
                  class Kotlin.Collections.Map 'args') cil managed
              {
                .maxstack 2
                ldarg.0
                ldfld bool Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'isSuspend'
                brfalse.s MRF_CallByOrdinary
                ldstr "callBy cannot supply a suspend continuation; use a coroutine-aware reflective call."
                newobj instance void $systemNotSupportedException::.ctor(string)
                throw
              MRF_CallByOrdinary:
                ldarg.0
                ldarg.1
                call instance object Kotlin.Runtime.Internal.FunctionReferenceBase::CallByErased(
                    class Kotlin.Collections.Map)
                ret
              }

              .method family hidebysig virtual instance object EmptyVarargAt(int32 'index') cil managed
              {
                .maxstack 2
                .locals init ([0] object 'value')
                ldarg.1
                ldc.i4.0
                blt.s MRF_NoVararg
                ldarg.0
                ldfld object[] Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'emptyVarargs'
                brfalse.s MRF_NoVararg
                ldarg.1
                ldarg.0
                ldfld object[] Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'emptyVarargs'
                ldlen
                conv.i4
                bge.s MRF_NoVararg
                ldarg.0
                ldfld object[] Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'emptyVarargs'
                ldarg.1
                ldelem.ref
                stloc.0
                ldloc.0
                brfalse.s MRF_NoVararg
                ldloc.0
                ret
              MRF_NoVararg:
                ldstr "Callable has no vararg at this position."
                newobj instance void $systemNotSupportedException::.ctor(string)
                throw
              }
            }

$fixedCarrierTypes

            .class private auto ansi sealed beforefieldinit MemberFunctionN
                   extends Kotlin.Runtime.Internal.MemberFunctionReferenceBase
                   implements Kotlin.FunctionN
            {
              .method assembly hidebysig specialname rtspecialname instance void .ctor(
                  $constructorParameters) cil managed
              {
                .maxstack 11
                ldarg.0
                ldarg.1
                ldarg.2
                ldarg.3
                ldarg.s 4
                ldarg.s 5
                ldarg.s 6
                ldarg.s 7
                ldarg.s 8
                ldarg.s 9
                ldarg.s 10
                call instance void Kotlin.Runtime.Internal.MemberFunctionReferenceBase::.ctor(
                    $constructorParameters)
                ret
              }

              .method public hidebysig newslot virtual final instance object Invoke(object[] 'args') cil managed
              {
                .override method instance object Kotlin.FunctionN::Invoke(object[])
                .maxstack 2
                ldarg.0
                ldarg.1
                callvirt instance object Kotlin.Runtime.Internal.FunctionReferenceBase::CallErased(object[])
                ret
              }

              .method public hidebysig specialname newslot virtual final instance int32 get_arity() cil managed
              {
                .override method instance int32 Kotlin.FunctionN::get_arity()
                .maxstack 1
                ldarg.0
                ldfld int32 Kotlin.Runtime.Internal.MemberFunctionReferenceBase::'memberArity'
                ret
              }

              .property instance int32 arity()
              {
                .get instance int32 Kotlin.Runtime.Internal.MemberFunctionN::get_arity()
              }
            }

            .class public abstract sealed auto ansi beforefieldinit MemberReferenceFactory
                   extends $systemObject
            {
              $compilerAbiTypeAttributesIl
              .method public hidebysig static object CreateFunction(
                  $constructorParameters) cil managed
              {
                .maxstack 10
                ldarg.3
                ldc.i4.0
                blt MRF_InvalidArity
                ldarg.3
                switch ($switchLabels)
$factoryLoads
                newobj instance void Kotlin.Runtime.Internal.MemberFunctionN::.ctor(
                    $constructorParameters)
                ret
$factoryCases
              MRF_InvalidArity:
                ldstr "Invalid reflected member arity."
                newobj instance void $systemArgumentException::.ctor(string)
                throw
              }
            }
        """.trimIndent()
    }

    private fun callableInvokerCaseIl(arity: Int): String = buildString {
        appendLine("    CI_Call$arity:")
        appendLine("      ldarg.0")
        appendLine("      castclass Kotlin.Function$arity")
        repeat(arity) { index ->
            appendLine("      ldarg.2")
            appendLine("      ldc.i4 $index")
            appendLine("      ldelem.ref")
        }
        val parameterTypes = List(arity) { "object" }.joinToString(", ")
        appendLine("      callvirt instance object Kotlin.Function$arity::Invoke($parameterTypes)")
        append("      ret")
    }

    /** The cross-assembly call emitted by compiled Kotlin code; one float32 in, one string out. */
    val floatToStringCallInstruction: String =
        "call string [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.DoubleFormatting".toIlIdentifier()}::" +
                "${"FloatToString".toIlIdentifier()}(float32)"

    /** Kotlin total ordering for a pair of float32 values. */
    val compareFloatCallInstruction: String =
        "call int32 [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"CompareFloat".toIlIdentifier()}(float32, float32)"

    /** Kotlin total ordering for a pair of float64 values. */
    val compareDoubleCallInstruction: String =
        "call int32 [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"CompareDouble".toIlIdentifier()}(float64, float64)"

    /** Common Comparable dispatch with Kotlin built-in ordering and canonical CLR fallback. */
    val comparableCompareToCallInstruction: String =
        "call int32 [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ComparableCompareTo".toIlIdentifier()}(object, object)"

    val isNumberCallInstruction: String =
        "call bool [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"IsNumber".toIlIdentifier()}(object)"

    val checkNumberCastCallInstruction: String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"CheckNumberCast".toIlIdentifier()}(object)"

    val safeNumberCastCallInstruction: String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"SafeNumberCast".toIlIdentifier()}(object)"

    fun numberConversionCallInstruction(methodName: String, returnType: DotNetIlValueType): String =
        "call ${returnType.nameInSignature} [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${methodName.toIlIdentifier()}(object)"

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

    /**
     * The exact open-T equality entry. Runtime caches the constructed T classification and avoids
     * the receiver box for semantically safe value types while retaining [areEqualCallInstruction]
     * behavior for references and Kotlin floating equality.
     */
    fun areEqualGenericCallInstruction(typeParameter: DotNetIlValueType.TypeParameter): String =
        "call bool [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"AreEqualGeneric".toIlIdentifier()}<${typeParameter.nameInSignature}>" +
                "(${typeParameter.nameInSignature}, ${typeParameter.nameInSignature})"

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

    val isCharSequenceCallInstruction: String =
        "call bool [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"IsCharSequence".toIlIdentifier()}(object)"

    val isFunctionOfArityCallInstruction: String =
        "call bool [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"IsFunctionOfArity".toIlIdentifier()}(object, int32)"

    val checkFunctionCastCallInstruction: String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"CheckFunctionCast".toIlIdentifier()}(object, int32)"

    val safeFunctionCastCallInstruction: String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"SafeFunctionCast".toIlIdentifier()}(object, int32)"

    val isGenericArrayCallInstruction: String =
        "call bool [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"IsGenericArray".toIlIdentifier()}(object)"

    fun checkGenericArrayCastCallInstruction(coreLibraryReference: String): String =
        "call class ${coreLibraryReference}System.Array [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"CheckGenericArrayCast".toIlIdentifier()}(object)"

    fun safeGenericArrayCastCallInstruction(coreLibraryReference: String): String =
        "call class ${coreLibraryReference}System.Array [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"SafeGenericArrayCast".toIlIdentifier()}(object)"

    val checkCharSequenceCastCallInstruction: String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"CheckCharSequenceCast".toIlIdentifier()}(object)"

    val safeCharSequenceCastCallInstruction: String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"SafeCharSequenceCast".toIlIdentifier()}(object)"

    val charSequenceLengthCallInstruction: String =
        "call int32 [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"CharSequenceLength".toIlIdentifier()}(object)"

    val charSequenceGetCallInstruction: String =
        "call char [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"CharSequenceGet".toIlIdentifier()}(object, int32)"

    val charSequenceSubSequenceCallInstruction: String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"CharSequenceSubSequence".toIlIdentifier()}(object, int32, int32)"

    /** Captures one original CLR exception in the runtime-owned per-initializer failure state. */
    fun captureStaticInitializationFailureCallInstruction(coreLibraryReference: String): String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.StaticInitialization".toIlIdentifier()}::" +
                "${"Capture".toIlIdentifier()}(class ${coreLibraryReference}System.Exception)"

    /** Atomically exposes the original reason once and null to every later failed active use. */
    fun observeStaticInitializationFailureCallInstruction(coreLibraryReference: String): String =
        "call class ${coreLibraryReference}System.Exception [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.StaticInitialization".toIlIdentifier()}::" +
                "${"Observe".toIlIdentifier()}(object)"

    /** Target implementation of Common non-JVM static-initialization failure classification. */
    fun throwStaticInitializationFailureCallInstruction(coreLibraryReference: String): String =
        "call void [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.StaticInitialization".toIlIdentifier()}::" +
                "${"Throw".toIlIdentifier()}(class ${coreLibraryReference}System.Exception, string)"

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

    /** Kotlin range validation plus portable element writes for Common `Array.fill`. */
    fun arrayFillCallInstruction(coreLibraryReference: String): String =
        "call void [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.Intrinsics".toIlIdentifier()}::" +
                "${"ArrayFill".toIlIdentifier()}(" +
                "class ${coreLibraryReference}System.Array, object, int32, int32)"

}
