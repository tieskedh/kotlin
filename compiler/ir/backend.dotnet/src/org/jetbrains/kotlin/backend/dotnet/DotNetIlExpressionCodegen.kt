package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_INTERFACE_DEFAULT_EXACT_CALL
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetClass
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isNullableNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFalseConst
import org.jetbrains.kotlin.ir.util.isAnonymousObject
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isNullConst
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isOriginallyLocalDeclaration
import org.jetbrains.kotlin.ir.util.isTrueConst
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract

/**
 * Emits statement-bearing constructs in value position. Implemented by [DotNetIlMethodCodegen]:
 * a `try` branch body and the leading statements of a value-position block (the safe-call/elvis
 * shape fir2ir emits: `IrBlock { val tmp = ...; IrWhen }`) contain arbitrary statements, and
 * statement emission lives on the method codegen, so both dispatch back through this hook — the
 * reverse of the method codegen delegating value emission to [DotNetIlExpressionCodegen].
 */
internal interface DotNetIlStatementScopeEmitter {
    fun emitTryExpression(expression: IrTry, expectedType: DotNetIlValueType)

    /** A block in value position: leading statements, then the trailing expression as the value. */
    fun emitBlockExpression(block: IrContainerExpression, expectedType: DotNetIlValueType)

    /** Executes a Unit-typed effect expression, then materializes the Kotlin Unit value. */
    fun emitUnitEffectExpression(expression: IrExpression)
}

/**
 * Emits value-producing expressions into the method's [DotNetIlMethodContext]. Any construct
 * outside the supported subset aborts the enclosing method render with [DotNetIlUnsupportedException].
 */
internal class DotNetIlExpressionCodegen(
    private val methodContext: DotNetIlMethodContext,
    private val availableFunctions: Map<IrSimpleFunction, DotNetIlFunctionInfo>,
    private val intrinsicMethods: DotNetIlIntrinsicMethods,
    private val typeMapper: DotNetIlTypeMapper,
    private val facadeClassInfoByFile: Map<IrFile, DotNetIlClassInfo>,
    private val currentOwner: DotNetIlClassInfo,
    private val statementScopeEmitter: DotNetIlStatementScopeEmitter,
) {
    internal val coreLibraryReference = typeMapper.coreLibrary.reference
    private val canonicalGenericSignatureTypeMapper by lazy(LazyThreadSafetyMode.NONE) {
        typeMapper.canonicalGenericInterfaceSignatureView()
    }
    private val declaredGenericSignatureTypeMapper by lazy(LazyThreadSafetyMode.NONE) {
        typeMapper.declaredGenericInterfaceSignatureView()
    }
    private val exactGenericSignatureTypeMapper by lazy(LazyThreadSafetyMode.NONE) {
        typeMapper.exactGenericInterfaceSignatureView()
    }

    fun emit(instruction: String, pops: Int = 0, pushes: Int = 0) {
        methodContext.emit(instruction, pops, pushes)
    }

    fun recordAssemblyReference(assemblyName: String) {
        typeMapper.recordAssemblyReference(assemblyName)
    }

    /**
     * Maps [type] through the emission-scoped [DotNetIlTypeMapper]; null when the type has no IL
     * mapping. Exposed so intrinsics can dispatch on operand and parameter types.
     */
    fun toDotNetIlValueType(type: IrType): DotNetIlValueType? = typeMapper.toDotNetIlValueType(type)

    fun permitsErasedGenericArrayElementWrite(type: IrType): Boolean =
        typeMapper.permitsErasedGenericArrayElementWrite(type)

    /**
     * The CLR type actually produced by [expression]. Most IR expression types map directly, but
     * an imported or runtime generic member call may retain an open owner type in IR even when its
     * dispatch receiver closes that owner. Use the same call resolution as emission so later
     * coercion/cast decisions observe the substituted CLR result. Kotlin-owned class owner
     * parameters take the erased-object result path instead.
     * Erased callable `Invoke` and property `get` are excluded: their physical result is always
     * object, but their logical IR result remains authoritative for the call-specific cast/unbox
     * performed by codegen.
     */
    private fun mappedNaturalType(expression: IrExpression): DotNetIlValueType? {
        if (
            expression is IrCall &&
            intrinsicMethods.getIntrinsic(expression.symbol) == null &&
            !expression.symbol.owner.isDotNetErasedObjectResult() &&
            !expression.symbol.owner.isErasedGenericInterfaceMember() &&
            !expression.symbol.owner.isErasedGenericClassMember()
        ) {
            val returnType = resolveCall(expression).returnType
            if (returnType is DotNetIlReturnType.Value) return returnType.type
        }
        return typeMapper.toDotNetIlValueType(expression.type)
    }

    fun nextLabel(prefix: String): String = methodContext.nextLabel(prefix)

    fun emitBranch(instruction: String, targetLabel: String, pops: Int = 0) {
        methodContext.emitBranch(instruction, targetLabel, pops)
    }

    fun emitGoto(targetLabel: String) {
        methodContext.emitGoto(targetLabel)
    }

    fun emitLabel(label: String) {
        methodContext.emitLabel(label)
    }

    /**
     * Emits a parameterless exception construction followed by `throw` for an intrinsic. In
     * value position the intrinsic has Kotlin type `Nothing`, so the dead consumer instructions
     * still need one phantom stack value, exactly like an [IrThrow] emitted by [emitExpression].
     * A statement-position throw has no consumer and therefore records no phantom value.
     */
    fun emitParameterlessExceptionThrow(exceptionTypeRef: String, valuePosition: Boolean) {
        methodContext.emit("newobj instance void $exceptionTypeRef::.ctor()", pushes = 1)
        methodContext.emitThrow()
        if (valuePosition) {
            methodContext.notePhantomValueAfterThrow()
        }
    }

    /** Loads the canonical object used when Kotlin Unit occupies a real CLR value slot. */
    fun emitRuntimeUnitInstance() {
        methodContext.emit(DotNetRuntimeTypes.unitInstanceLoadInstruction, pushes = 1)
    }

    fun emitExpression(expression: IrExpression?, expectedType: DotNetIlValueType) {
        if (expression.isNullDefaultArgumentPlaceholder()) {
            emitDefaultArgumentPlaceholder(expectedType)
            return
        }
        if (
            expression != null &&
            expression.type.isNullableNothing() &&
            expectedType != DotNetRuntimeTypes.nothingType
        ) {
            // The null literal needs no physical Kotlin.Nothing view. Emitting it directly in the
            // requested slot avoids a redundant `castclass` for references and a throwaway
            // carrier value for Nullable<T>. Non-constant Nothing? expressions still have to be
            // evaluated through their declared carrier because they may have side effects.
            if (expression.isNullConst()) {
                when {
                    expectedType is DotNetIlValueType.NullableValue -> emitEmptyNullable(expectedType)
                    expectedType.isDotNetReferenceShaped() -> methodContext.emit("ldnull", pushes = 1)
                    else -> dotNetUnsupported(
                        "nullable Nothing cannot be widened to ${expectedType.nameInSignature}"
                    )
                }
                return
            }
            // `Nothing?` has the precise physical carrier Kotlin.Nothing, but its sole legal
            // value must still widen to every nullable Kotlin type. Evaluate the source first
            // (it may be a side-effecting call), then materialize the requested CLR view. This
            // is bottom-type semantics, not a claim that Kotlin.Nothing is a CLR subtype of
            // every reference type.
            emitExpression(expression, DotNetRuntimeTypes.nothingType)
            if (methodContext.isTerminated) return
            when {
                expectedType is DotNetIlValueType.NullableValue -> {
                    methodContext.emit("pop", pops = 1)
                    emitEmptyNullable(expectedType)
                }
                expectedType.isDotNetReferenceShaped() -> {
                    if (!DotNetRuntimeTypes.nothingType.isDotNetAssignableTo(expectedType)) {
                        methodContext.emit(
                            "castclass ${expectedType.nameInSignature}",
                            pops = 1,
                            pushes = 1,
                        )
                    }
                }
                else -> dotNetUnsupported(
                    "nullable Nothing cannot be widened to ${expectedType.nameInSignature}"
                )
            }
            return
        }
        // Widening-coercion interception, the hybrid nullability model's conversion layer (JVM
        // precedent: the JVM backend coerces at codegen time through StackValue — boxing is
        // never an IR node — and Roslyn converts `T -> T?` / `-> object` at every use site the
        // same way). When the expression's own mapped type differs from the expected one, it is
        // emitted AT ITS OWN TYPE first and then, when needed, a single conversion instruction
        // widens it: `newobj Nullable<T>::.ctor` for `T -> T?`, `box` for `T -> Any?` and
        // `T? -> Any?` (the CLR collapses the latter to boxed-T-or-null, probe-verified,
        // boxprobe_s3). Instruction-free reference widenings just recurse at the natural type.
        // Narrowings generally exist as explicit IMPLICIT_CAST/`!!` shapes (see
        // emitTypeOperatorCall). A frontend-proven smartcast can instead narrow the type of a
        // bare IrGetValue while its physical local retains the declared wider type; emitGetValue
        // materializes that checked CLR view change.
        if (expression != null) {
            val naturalType = mappedNaturalType(expression)
            // FIR may infer a bounded out-projected array as the transient common type of exact
            // value/reference vectors. CLR value vectors cannot materialize that covariance.
            // When the consumer already asks for the selected Array<*>/System.Array view, emit
            // the child expression directly at that erased boundary so every branch retains its
            // exact vector. This does not admit the bounded projection in a signature or local.
            val eraseTransientArrayProjection =
                expectedType is DotNetIlValueType.ErasedGenericArray &&
                        expression.type.isDotNetOutProjectedGenericArray()
            if (naturalType != null && naturalType != expectedType && !eraseTransientArrayProjection) {
                val kFunctionArity = expression.type.dotNetKFunctionExecutionArityOrNull()
                if (kFunctionArity != null && DotNetRuntimeTypes.isFixedFunctionType(expectedType, kFunctionArity)) {
                    // KFunctionN is a logical subtype of FunctionN, while the erased CLR views
                    // are sibling interfaces on the same generated object. Materialize that
                    // source-level widening as a checked interface view change.
                    emitExpression(expression, naturalType)
                    if (methodContext.isTerminated) return
                    methodContext.emit("castclass ${expectedType.nameInSignature}", pops = 1, pushes = 1)
                    return
                }
                if (naturalType.isDotNetAssignableTo(expectedType)) {
                    emitExpression(expression, naturalType)
                    return
                }
                val coercion = dotNetWideningCoercionOrNull(naturalType, expectedType, coreLibraryReference)
                if (coercion != null) {
                    emitExpression(expression, naturalType)
                    if (methodContext.isTerminated) return
                    methodContext.emit(coercion, pops = 1, pushes = 1)
                    return
                }
            }
        }
        when (expression) {
            null -> dotNetUnsupported("missing ${expectedType.nameInSignature} expression value")
            is IrConst -> emitConstant(expression, expectedType)
            is IrSetValue, is IrSetField -> {
                // An assignment has logical type Unit, but a surrounding Common expression may
                // request a wider reference view (most commonly Any/object after branch type
                // approximation). Execute the effect and materialize the Unit singleton; the
                // singleton already has every instruction-free CLR reference widening that Unit
                // has. This is the same value-position rule used for Unit-returning calls.
                if (!DotNetRuntimeTypes.unitType.isDotNetAssignableTo(expectedType)) {
                    dotNetUnsupported(
                        "Unit effect expression cannot produce ${expectedType.nameInSignature}: ${expression.render()}"
                    )
                }
                statementScopeEmitter.emitUnitEffectExpression(expression)
            }
            is IrGetObjectValue -> {
                if (!expression.type.isUnit()) {
                    dotNetUnsupported("unsupported object expression ${expression.render()}")
                }
                emitRuntimeUnitInstance()
            }
            is IrGetValue -> emitGetValue(expression, expectedType)
            is IrGetField -> emitGetField(expression, expectedType)
            is IrClassReference -> emitClassReference(expression, expectedType)
            is IrGetClass -> emitGetClass(expression, expectedType)
            is IrConstructorCall -> emitConstructorCall(expression, expectedType)
            is IrWhen -> emitWhenExpression(expression, expectedType)
            // `IrThrow` has type `kotlin.Nothing` and satisfies any expected type vacuously: the
            // value never materializes. The phantom stack value keeps the tracker balanced for
            // the dead instructions the caller emits after the throw (probe-verified legal).
            is IrThrow -> {
                emitThrow(expression)
                methodContext.notePhantomValueAfterThrow()
            }
            is IrTry -> statementScopeEmitter.emitTryExpression(expression, expectedType)
            is IrTypeOperatorCall -> emitTypeOperatorCall(expression, expectedType)
            is IrCall -> {
                val intrinsic = intrinsicMethods.getIntrinsic(expression.symbol)
                if (intrinsic == null || !intrinsic.tryEmitAsExpression(expression, this, expectedType)) {
                    emitCallExpression(expression, expectedType)
                }
            }
            // The safe-call/elvis desugaring: `IrBlock { val tmp = <receiver>; IrWhen }`.
            // Statement emission lives on the method codegen, hence the hook (like IrTry above).
            is IrContainerExpression -> statementScopeEmitter.emitBlockExpression(expression, expectedType)
            else -> dotNetUnsupported(
                "unsupported ${expectedType.nameInSignature} expression ${expression.javaClass.simpleName}: ${expression.render()}"
            )
        }
    }

    /**
     * Static and dynamic class literals both produce the same Kotlin-owned erased KClass view.
     * The logical `KClass<T>` argument remains exclusively in IR/KLIB; the runtime object carries
     * a System.Type only as physical evidence plus the smallest classifier kind needed when one
     * CLR Type cannot express Kotlin identity (Array, Number, CharSequence, and exceptions).
     */
    private fun emitClassReference(expression: IrClassReference, expectedType: DotNetIlValueType) {
        val resultType = DotNetIlValueType.UserClass(DotNetKClassRuntime.kClassClassInfo)
        if (!resultType.isDotNetAssignableTo(expectedType)) {
            dotNetUnsupported(
                "class literal produces ${resultType.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
        val simpleType = expression.classType as? IrSimpleType
            ?: dotNetUnsupported("class literal of unsupported type ${expression.classType.render()}")
        if (simpleType.classifier is IrTypeParameterSymbol) {
            dotNetUnsupported("reified type-parameter class literals are not supported yet")
        }
        val irClass = (simpleType.classifier as? IrClassSymbol)?.owner
            ?: dotNetUnsupported("class literal has no class declaration: ${expression.classType.render()}")
        val classifier = staticKClassClassifier(expression.classType, irClass)
        emitSystemTypeOrNull(classifier.clrTypeRef)
        emitNullableString(classifier.simpleName)
        emitNullableString(classifier.qualifiedName)
        methodContext.emit("ldc.i4 ${classifier.kind.abiValue}", pushes = 1)
        methodContext.emit("ldc.i4 ${classifier.classifierId}", pushes = 1)
        methodContext.emit(
            DotNetKClassRuntime.createCallInstruction(coreLibraryReference),
            pops = 5,
            pushes = 1,
        )
    }

    private fun emitGetClass(expression: IrGetClass, expectedType: DotNetIlValueType) {
        val resultType = DotNetIlValueType.UserClass(DotNetKClassRuntime.kClassClassInfo)
        if (!resultType.isDotNetAssignableTo(expectedType)) {
            dotNetUnsupported(
                "dynamic class literal produces ${resultType.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
        // Object widening performs the required boxing for scalar and nullable-value receivers.
        // It also guarantees exactly one receiver evaluation before the runtime classification.
        emitExpression(expression.argument, DotNetIlValueType.Object)
        if (methodContext.isTerminated) return
        methodContext.emit(
            DotNetKClassRuntime.getClassCallInstruction(coreLibraryReference),
            pops = 1,
            pushes = 1,
        )
    }

    private data class StaticKClassClassifier(
        val clrTypeRef: String?,
        val simpleName: String?,
        val qualifiedName: String?,
        val kind: DotNetKClassClassifierKind,
        val classifierId: Int = 0,
    )

    private fun staticKClassClassifier(classType: IrType, irClass: IrClass): StaticKClassClassifier {
        val sourceSimpleName = irClass.name.asString().takeUnless { irClass.isAnonymousObject }
        val sourceQualifiedName = irClass.fqNameWhenAvailable?.asString()
            ?.takeUnless { irClass.isAnonymousObject || irClass.isOriginallyLocalDeclaration }
        val mappedException = DotNetMappedExceptions.mappedEntry(irClass.fqNameWhenAvailable)
        if (mappedException != null) {
            return StaticKClassClassifier(
                clrTypeRef = mappedException.constructorTypeRef(coreLibraryReference),
                simpleName = sourceSimpleName,
                qualifiedName = sourceQualifiedName,
                kind = DotNetKClassClassifierKind.EXCEPTION,
                classifierId = mappedException.classifierTypeId.abiValue,
            )
        }
        if (classType.isDotNetCharSequenceType()) {
            return StaticKClassClassifier(
                clrTypeRef = null,
                simpleName = sourceSimpleName,
                qualifiedName = sourceQualifiedName,
                kind = DotNetKClassClassifierKind.CHAR_SEQUENCE,
            )
        }
        if (irClass.fqNameWhenAvailable?.asString() == "kotlin.Number") {
            return StaticKClassClassifier(
                clrTypeRef = null,
                simpleName = sourceSimpleName,
                qualifiedName = sourceQualifiedName,
                kind = DotNetKClassClassifierKind.NUMBER,
            )
        }
        if (classType.isNothing()) {
            return StaticKClassClassifier(
                clrTypeRef = DotNetRuntimeTypes.nothingType.ilTypeRef,
                simpleName = sourceSimpleName,
                qualifiedName = sourceQualifiedName,
                kind = DotNetKClassClassifierKind.NOTHING,
            )
        }
        if (classType.isUnit()) {
            return StaticKClassClassifier(
                clrTypeRef = DotNetRuntimeTypes.unitType.ilTypeRef,
                simpleName = sourceSimpleName,
                qualifiedName = sourceQualifiedName,
                kind = DotNetKClassClassifierKind.EXACT,
            )
        }
        if (irClass.fqNameWhenAvailable?.asString() == "kotlin.Array") {
            return StaticKClassClassifier(
                clrTypeRef = "${coreLibraryReference}System.Array",
                simpleName = sourceSimpleName,
                qualifiedName = sourceQualifiedName,
                kind = DotNetKClassClassifierKind.GENERIC_ARRAY,
            )
        }
        typeMapper.genericClassInfoOrNull(irClass)?.let { genericClass ->
            return StaticKClassClassifier(
                clrTypeRef = genericClass.classInfo.ilTypeRef,
                simpleName = sourceSimpleName,
                qualifiedName = sourceQualifiedName,
                kind = DotNetKClassClassifierKind.EXACT,
            )
        }
        val mappedType = typeMapper.toDotNetIlValueType(classType)
            ?: dotNetUnsupported("class literal has no CLR evidence type: ${classType.render()}")
        val clrTypeRef = when (mappedType) {
            DotNetIlValueType.String -> "${coreLibraryReference}System.String"
            DotNetIlValueType.Object -> "${coreLibraryReference}System.Object"
            is DotNetIlValueType.UserClass -> mappedType.ilTypeRef
            is DotNetIlValueType.MappedClass -> mappedType.ilTypeRef
            is DotNetIlValueType.PrimitiveArray -> mappedType.abi.wrapperTypeRef
            is DotNetIlValueType.GenericArray,
            is DotNetIlValueType.ErasedGenericArray,
                -> "${coreLibraryReference}System.Array"
            is DotNetIlValueType.GenericInstance -> mappedType.classInfo.ilTypeRef
            else -> mappedType.dotNetBoxedCorelibRefOrNull(coreLibraryReference)
                ?: dotNetUnsupported("class literal has unsupported CLR evidence ${mappedType.nameInSignature}")
        }
        val kind = if (mappedType is DotNetIlValueType.GenericInstance) {
            DotNetKClassClassifierKind.OPEN_GENERIC
        } else {
            DotNetKClassClassifierKind.EXACT
        }
        return StaticKClassClassifier(
            clrTypeRef = clrTypeRef,
            simpleName = sourceSimpleName,
            qualifiedName = sourceQualifiedName,
            kind = kind,
        )
    }

    private fun emitSystemTypeOrNull(clrTypeRef: String?) {
        if (clrTypeRef == null) {
            methodContext.emit("ldnull", pushes = 1)
            return
        }
        methodContext.emit("ldtoken $clrTypeRef", pushes = 1)
        methodContext.emit(
            "call class ${coreLibraryReference}System.Type " +
                    "${coreLibraryReference}System.Type::GetTypeFromHandle(" +
                    "valuetype ${coreLibraryReference}System.RuntimeTypeHandle)",
            pops = 1,
            pushes = 1,
        )
    }

    private fun emitNullableString(value: String?) {
        methodContext.emit(value?.let { "ldstr ${it.toIlStringLiteral()}" } ?: "ldnull", pushes = 1)
    }

    /**
     * Spills the [type]-typed value on top of the evaluation stack into a fresh synthetic local
     * and returns its slot. The nullable-primitive emission shapes use it to obtain the HOME
     * ADDRESS every `Nullable<T>` instance-member call requires: calling `get_HasValue`/
     * `GetValueOrDefault` on an unspilled stack value assembles cleanly but is a FATAL,
     * uncatchable CLR error (0x80131506, probe-verified `boxprobe_s2`) — the spill is
     * unconditional by design and costs no extra stack depth (value 1 slot → address 1 slot).
     */
    fun spillToSyntheticLocal(type: DotNetIlValueType, namePrefix: String): DotNetIlSlot.Local {
        val slot = methodContext.declareSyntheticLocal(type, namePrefix)
        methodContext.emit(storeLocalInstruction(slot.index), pops = 1)
        return slot
    }

    /**
     * `!!`/IMPLICIT_NOTNULL on a [nullable primitive][DotNetIlValueType.NullableValue] value on
     * top of the stack: spill (mandatory home address, see [spillToSyntheticLocal]), branch past
     * the throw on `get_HasValue`, throw the mapped Kotlin NPE (`System.NullReferenceException`,
     * see [DotNetMappedExceptions] — parameterless ctor, JVM parity: `Intrinsics.checkNotNull`'s
     * NPE carries no message), then extract with `GetValueOrDefault` — never `get_Value`, whose
     * InvalidOperationException would surface as the WRONG Kotlin exception (ClassCastException
     * territory via the InvalidCastException mapping is wrong too; hence branch-first). Also the
     * unwrap shape of a `T? -> T` smartcast IMPLICIT_CAST — JVM precedent: the JVM emits
     * CHECKCAST + `intValue()`, whose null receiver throws the same NPE. Net effect: pop the
     * `Nullable<T>`, push the plain `T`.
     */
    fun emitNullableUnwrapOrThrowNpe(type: DotNetIlValueType.NullableValue) {
        val slot = spillToSyntheticLocal(type, "<notNull>")
        val okLabel = methodContext.nextLabel("notNull")
        methodContext.emit(loadLocalAddressInstruction(slot.index), pushes = 1)
        methodContext.emit(type.hasValueInstruction, pops = 1, pushes = 1)
        methodContext.emitBranch("brtrue", okLabel, pops = 1)
        emitThrowNullPointerException()
        methodContext.emitLabel(okLabel)
        methodContext.emit(loadLocalAddressInstruction(slot.index), pushes = 1)
        methodContext.emit(type.getValueOrDefaultInstruction, pops = 1, pushes = 1)
    }

    /**
     * `!!`/IMPLICIT_NOTNULL on a reference value on top of the stack: `dup`/`brtrue` past a
     * throw of the mapped Kotlin NPE; the non-null value flows through unchanged (JVM precedent:
     * the checkNotNull intrinsic shape). Works with operands already below on the stack — no
     * protected region is involved.
     */
    fun emitReferenceNotNullOrThrowNpe() {
        val okLabel = methodContext.nextLabel("notNull")
        methodContext.emit("dup", pops = 1, pushes = 2)
        methodContext.emitBranch("brtrue", okLabel, pops = 1)
        methodContext.emit("pop", pops = 1)
        emitThrowNullPointerException()
        methodContext.emitLabel(okLabel)
    }

    /**
     * Throws the CLR type `kotlin.NullPointerException` maps to (probe-verified spelling and
     * catchability, `boxprobe_s4`), so a failing `!!` stays catchable as
     * `catch (e: NullPointerException)` through the existing exception registry.
     */
    private fun emitThrowNullPointerException() {
        methodContext.emit(
            "newobj instance void ${coreLibraryReference}System.NullReferenceException::.ctor()",
            pushes = 1,
        )
        methodContext.emitThrow()
    }

    /**
     * Produces the empty (`null`) `Nullable<T>` value on the stack: `initobj` through the
     * address of a fresh synthetic local, then a load of the zero-initialized value — the
     * probe-verified empty-value producer for every position, incl. returns and arguments
     * (`boxprobe_s1`). A value type has no `ldnull`.
     */
    private fun emitEmptyNullable(type: DotNetIlValueType.NullableValue) {
        val slot = methodContext.declareSyntheticLocal(type, "<null>")
        methodContext.emit(loadLocalAddressInstruction(slot.index), pushes = 1)
        methodContext.emit(type.initInstruction, pops = 1)
        methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
    }

    private fun IrExpression?.isNullDefaultArgumentPlaceholder(): Boolean {
        val composite = this as? IrContainerExpression ?: return false
        if (composite.origin != IrStatementOrigin.DEFAULT_VALUE) return false
        val constant = composite.statements.singleOrNull() as? IrConst ?: return false
        return constant.value == null
    }

    /**
     * Emits the ignored value carried beside a set default-argument mask bit. Common default
     * lowering represents every reference-shaped placeholder as a null constant, including an
     * open `T` that may become a CLR value type after substitution. The mask-owning stub replaces
     * this value before source code can observe it, so emit the parameter's physical default:
     * null for references, zero for known primitives, and `initobj` for open or nullable values.
     */
    private fun emitDefaultArgumentPlaceholder(type: DotNetIlValueType) {
        when (type) {
            DotNetIlValueType.Boolean,
            DotNetIlValueType.Int8,
            DotNetIlValueType.Int16,
            DotNetIlValueType.Int32,
            DotNetIlValueType.Char,
            -> methodContext.emit("ldc.i4 0", pushes = 1)
            DotNetIlValueType.Int64 -> methodContext.emit("ldc.i8 0", pushes = 1)
            DotNetIlValueType.Float32 -> methodContext.emit("ldc.r4 0.0", pushes = 1)
            DotNetIlValueType.Float64 -> methodContext.emit("ldc.r8 0.0", pushes = 1)
            is DotNetIlValueType.NullableValue -> emitEmptyNullable(type)
            is DotNetIlValueType.TypeParameter -> {
                val slot = methodContext.declareSyntheticLocal(type, "<default>")
                methodContext.emit(loadLocalAddressInstruction(slot.index), pushes = 1)
                methodContext.emit("initobj ${type.nameInSignature}", pops = 1)
                methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
            }
            DotNetIlValueType.String,
            DotNetIlValueType.Object,
            is DotNetIlValueType.UserClass,
            is DotNetIlValueType.MappedClass,
            is DotNetIlValueType.GenericInstance,
            is DotNetIlValueType.PrimitiveArray,
            is DotNetIlValueType.GenericArray,
            is DotNetIlValueType.ErasedGenericArray,
            -> methodContext.emit("ldnull", pushes = 1)
        }
    }

    fun emitBranchIfFalse(condition: IrExpression, targetLabel: String) {
        emitExpression(condition, DotNetIlValueType.Boolean)
        methodContext.emitBranch("brfalse", targetLabel, pops = 1)
    }

    /**
     * A type operator in value position. Supported operators:
     * - [IrTypeOperator.IMPLICIT_CAST] as a pure reference upcast — the operand's mapped type is
     *   [assignable][isDotNetAssignableTo] to the mapped cast type (`Derived` where a base-chain
     *   ancestor is expected, the trivial same-type cast — which covers every reference
     *   nullability change, `C?`/`C` mapping to the same IL type — or a reference widening to
     *   `object`) — the bare operand with NO instruction: CLR reference widening needs no
     *   `castclass` (probe-verified, `inheritprobe_s1`, `nullprobe_s8`). The JVM backend's
     *   analogue is `IrTypeOperatorLowering`/codegen dropping implicit casts between reference
     *   types — only CHECKCAST-requiring operators emit code there.
     * - IMPLICIT_CAST as a [widening coercion][dotNetWideningCoercionOrNull] of the hybrid
     *   nullability model: `T -> T?` (`newobj Nullable<T>`), `T -> Any?` (`box T`) and
     *   `T? -> Any?` (`box Nullable<T>`, CLR-collapsed to boxed-T-or-null, boxprobe_s3) —
     *   the operand plus one conversion instruction, exactly like the coercion interception in
     *   [emitExpression] (Roslyn precedent: C# converts `int?` at `object` boundaries with the
     *   same single instruction).
     * - IMPLICIT_CAST as the `T? -> T` smartcast unwrap: [emitNullableUnwrapOrThrowNpe] — JVM
     *   precedent: the same cast emits CHECKCAST + `intValue()` there, throwing NPE on a null
     *   that an unsound smartcast let through.
     * - [IrTypeOperator.IMPLICIT_NOTNULL]: the `!!` shape — a HasValue-branch + mapped-NPE throw
     *   on nullable primitives, a `dup`/`brtrue` null check on references (JVM precedent: the
     *   checkNotNull intrinsic shape).
     * - [IrTypeOperator.INSTANCEOF]/[IrTypeOperator.NOT_INSTANCEOF]: box/widen the operand to
     *   object, use CLR `isinst`, then compare the resulting reference with null. This covers
     *   reference types, concrete boxed primitives, and open `!n`/`!!n` parameters. A nullable
     *   target accepts null first; for an open nullable parameter, `box default(!n)` determines
     *   at runtime whether the current instantiation can represent null. A boxed non-empty
     *   `Nullable<T>` has the identity of `T`, so its non-null test uses the element token.
     * - IMPLICIT_CAST from a reference-shaped value to such a class: CLR `castclass`. Fir2ir
     *   emits this checked downcast after a successful smartcast test, including generated data
     *   class `equals` bodies.
     * - [IrTypeOperator.CAST]/[IrTypeOperator.SAFE_CAST] to a Kotlin-owned generic
     *   interface: box/widen the operand to object and cast/test only its non-generic erased
     *   identity. Logical arguments, projections, and stars are deliberately absent from the
     *   CLR check, following JVM/Native erasure. A non-null `as` additionally rejects null with
     *   the mapped Kotlin NPE; `as?` uses `isinst` and therefore returns null on either failure.
     * - CAST/SAFE_CAST and INSTANCEOF/NOT_INSTANCEOF against an ordinary Kotlin-owned generic
     *   class: use its one non-generic physical class with ordinary `castclass`/`isinst`.
     *   Logical arguments remain absent from the check, exactly as on JVM/Native.
     * - CAST/SAFE_CAST to a physically exact non-generic reference carrier (`Any`, `String`, a
     *   non-generic Kotlin class/interface or interface admitted by the current CLR importer, a
     *   primitive-array wrapper, or a concrete CLR vector): the same single-evaluation
     *   `castclass`/`isinst` shape. This is kept
     *   deliberately narrower than [isDotNetReferenceShaped]: imported CLR generic instances
     *   remain reified, while mapped exception relationships require their classifier.
     * - CAST/SAFE_CAST to one of the eight Common primitive scalars: test/unbox only the exact
     *   CLR box selected by the scalar ABI. A checked nullable cast uses `unbox.any Nullable<T>`;
     *   a safe cast first changes a wrong object to null with `isinst System.<T>`, then uses the
     *   same nullable unbox to produce some-T or empty. No cast performs numeric conversion.
     * - CAST to an open type parameter: widen/box the operand to `object`, then use CLR
     *   `unbox.any !n`/`!!n`. This is the single instruction that recovers either a value or
     *   reference instantiation and is the direct CLR counterpart of a non-reified unchecked
     *   generic cast. SAFE_CAST remains separate because `unbox.any` throws on a wrong value.
     * - Explicit casts and runtime tests against `CharSequence` use the runtime's classified
     *   string-or-capability boundary. The physical object carrier alone never admits a value;
     *   successful casts preserve the original reference.
     * Everything else — explicit casts to reified generic CLR shapes and value-type tests —
     * stays rejected loudly until its own audited model exists.
     */
    private fun emitTypeOperatorCall(expression: IrTypeOperatorCall, expectedType: DotNetIlValueType) {
        val operandType = mappedNaturalType(expression.argument)
            ?: dotNetUnsupported("implicit cast of a value of unsupported type ${expression.argument.type.render()}")
        val castType = typeMapper.toDotNetIlValueType(expression.typeOperand)
            ?: dotNetUnsupported("implicit cast to unsupported type ${expression.typeOperand.render()}")
        if (expression.operator == IrTypeOperator.IMPLICIT_CAST && expression.argument.type.isNullableNothing()) {
            // Binary inlining can make an inline function's `return null` branch explicit as
            // Nothing? followed by an IMPLICIT_CAST to its substituted nullable result. Reuse the
            // ordinary bottom-type path so reference results receive null and Nullable<V>
            // results receive their existing empty carrier; no runtime cast is required.
            emitExpression(expression.argument, expectedType)
            return
        }
        if (expression.operator == IrTypeOperator.CAST || expression.operator == IrTypeOperator.SAFE_CAST) {
            if (expression.operator == IrTypeOperator.CAST && castType is DotNetIlValueType.TypeParameter) {
                emitExpression(expression.argument, DotNetIlValueType.Object)
                if (methodContext.isTerminated) return
                methodContext.emit("unbox.any ${castType.nameInSignature}", pops = 1, pushes = 1)
                emitCastResultCoercion(castType, expectedType, "generic cast")
                return
            }
            val scalarCastType =
                (castType as? DotNetIlValueType.NullableValue)?.elementType ?: castType
            val boxedScalarTypeRef =
                scalarCastType.dotNetBoxedCorelibRefOrNull(coreLibraryReference)
            if (boxedScalarTypeRef != null) {
                val scalarResultType = if (expression.operator == IrTypeOperator.SAFE_CAST) {
                    typeMapper.toDotNetIlValueType(expression.type)
                        ?: dotNetUnsupported(
                            "safe scalar cast has unsupported result type ${expression.type.render()}"
                        )
                } else {
                    castType
                }
                emitExpression(expression.argument, DotNetIlValueType.Object)
                if (methodContext.isTerminated) return
                if (expression.operator == IrTypeOperator.SAFE_CAST) {
                    if (scalarResultType !is DotNetIlValueType.NullableValue ||
                        scalarResultType.elementType != scalarCastType
                    ) {
                        dotNetUnsupported(
                            "safe scalar cast has inconsistent physical result " +
                                    scalarResultType.nameInSignature
                        )
                    }
                    methodContext.emit("isinst $boxedScalarTypeRef", pops = 1, pushes = 1)
                    methodContext.emit(
                        "unbox.any ${scalarResultType.nameInSignature}",
                        pops = 1,
                        pushes = 1,
                    )
                } else {
                    val narrowing = castType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference)
                        ?: error(
                            "Internal .NET backend error: scalar cast has no object narrowing for " +
                                    castType.nameInSignature
                    )
                    methodContext.emit(narrowing, pops = 1, pushes = 1)
                }
                emitCastResultCoercion(scalarResultType, expectedType, "scalar cast")
                return
            }
            if (expression.typeOperand.isDotNetCharSequenceType()) {
                if (castType != DotNetIlValueType.Object || expectedType != DotNetIlValueType.Object) {
                    dotNetUnsupported(
                        "classified CharSequence cast has inconsistent physical result " +
                                "${castType.nameInSignature} where ${expectedType.nameInSignature} is expected"
                    )
                }
                emitExpression(expression.argument, DotNetIlValueType.Object)
                if (methodContext.isTerminated) return
                methodContext.emit(
                    if (expression.operator == IrTypeOperator.SAFE_CAST) {
                        DotNetRuntimeLibraryHelpers.safeCharSequenceCastCallInstruction
                    } else {
                        DotNetRuntimeLibraryHelpers.checkCharSequenceCastCallInstruction
                    },
                    pops = 1,
                    pushes = 1,
                )
                if (expression.operator == IrTypeOperator.CAST && !expression.typeOperand.isMarkedNullable()) {
                    emitReferenceNotNullOrThrowNpe()
                }
                return
            }
            if (castType is DotNetIlValueType.ErasedGenericArray) {
                if (expectedType != castType) {
                    dotNetUnsupported(
                        "classified Array<*> cast has inconsistent physical result " +
                                "${castType.nameInSignature} where ${expectedType.nameInSignature} is expected"
                    )
                }
                emitExpression(expression.argument, DotNetIlValueType.Object)
                if (methodContext.isTerminated) return
                methodContext.emit(
                    if (expression.operator == IrTypeOperator.SAFE_CAST) {
                        DotNetRuntimeLibraryHelpers.safeGenericArrayCastCallInstruction(coreLibraryReference)
                    } else {
                        DotNetRuntimeLibraryHelpers.checkGenericArrayCastCallInstruction(coreLibraryReference)
                    },
                    pops = 1,
                    pushes = 1,
                )
                if (expression.operator == IrTypeOperator.CAST && !expression.typeOperand.isMarkedNullable()) {
                    emitReferenceNotNullOrThrowNpe()
                }
                return
            }
            val isErasedGenericInterfaceCast =
                typeMapper.isErasedGenericInterfaceType(expression.typeOperand) &&
                        castType is DotNetIlValueType.UserClass
            val isPhysicallyExactReferenceCast = when (castType) {
                DotNetIlValueType.Object,
                DotNetIlValueType.String,
                is DotNetIlValueType.UserClass,
                is DotNetIlValueType.PrimitiveArray,
                is DotNetIlValueType.GenericArray,
                    -> true
                else -> false
            }
            if (!isErasedGenericInterfaceCast && !isPhysicallyExactReferenceCast) {
                dotNetUnsupported("type operator ${expression.operator} is not supported")
            }
            if (!castType.isDotNetAssignableTo(expectedType)) {
                dotNetUnsupported(
                    "type operator ${expression.operator} produces ${castType.nameInSignature} " +
                            "where ${expectedType.nameInSignature} is expected"
                )
            }
            emitExpression(expression.argument, DotNetIlValueType.Object)
            if (methodContext.isTerminated) return
            if (expression.operator == IrTypeOperator.SAFE_CAST) {
                methodContext.emit("isinst ${castType.nameInSignature}", pops = 1, pushes = 1)
            } else {
                methodContext.emit("castclass ${castType.nameInSignature}", pops = 1, pushes = 1)
                if (!expression.typeOperand.isMarkedNullable()) {
                    emitReferenceNotNullOrThrowNpe()
                }
            }
            return
        }
        if (expression.operator == IrTypeOperator.INSTANCEOF || expression.operator == IrTypeOperator.NOT_INSTANCEOF) {
            if (expectedType != DotNetIlValueType.Boolean) {
                dotNetUnsupported(
                    "runtime type test against ${castType.nameInSignature} is not supported"
                )
            }
            emitRuntimeTypeTest(expression, castType)
            return
        }
        if (expression.operator != IrTypeOperator.IMPLICIT_CAST && expression.operator != IrTypeOperator.IMPLICIT_NOTNULL) {
            dotNetUnsupported("type operator ${expression.operator} is not supported")
        }
        if (expression.operator == IrTypeOperator.IMPLICIT_NOTNULL) {
            emitExpression(expression.argument, operandType)
            if (methodContext.isTerminated) return
            when {
                operandType is DotNetIlValueType.NullableValue && castType == operandType.elementType ->
                    emitNullableUnwrapOrThrowNpe(operandType)
                operandType.isDotNetReferenceShaped() && operandType.isDotNetAssignableTo(castType) ->
                    emitReferenceNotNullOrThrowNpe()
                else -> dotNetUnsupported(
                    "implicit not-null assertion from ${operandType.nameInSignature} " +
                            "to ${castType.nameInSignature} is not supported"
                )
            }
        } else {
            val kFunctionArity = expression.argument.type.dotNetKFunctionExecutionArityOrNull()
            when {
                kFunctionArity != null &&
                        kFunctionArity == expression.typeOperand.dotNetFunctionExecutionArityOrNull() -> {
                    // KFunctionN is physically the non-generic KFunction reflection view, while
                    // execution remains exclusively on the erased FunctionN interface. The same
                    // generated object implements both interfaces. This checked cross-interface
                    // view change is the CLR counterpart of JVM's KFunction-to-Function CHECKCAST;
                    // it does not introduce a second callable execution ABI.
                    emitExpression(expression.argument, operandType)
                    if (methodContext.isTerminated) return
                    methodContext.emit("castclass ${castType.nameInSignature}", pops = 1, pushes = 1)
                }
                operandType.isDotNetAssignableTo(castType) -> emitExpression(expression.argument, operandType)
                // A frontend-proven reference smartcast may target either a non-generic class or
                // a genuinely instantiated CLR generic/interface capability. Both are ordinary
                // CLR reference view changes and therefore use the same checked instruction.
                operandType.isDotNetReferenceShaped() &&
                        castType.isDotNetReferenceShaped() &&
                        castType.isDotNetAssignableTo(operandType) -> {
                    emitExpression(expression.argument, operandType)
                    if (methodContext.isTerminated) return
                    methodContext.emit("castclass ${castType.nameInSignature}", pops = 1, pushes = 1)
                }
                else -> {
                    val coercion = dotNetWideningCoercionOrNull(operandType, castType, coreLibraryReference)
                    when {
                        coercion != null -> {
                            emitExpression(expression.argument, operandType)
                            if (methodContext.isTerminated) return
                            methodContext.emit(coercion, pops = 1, pushes = 1)
                        }
                        operandType is DotNetIlValueType.NullableValue && castType == operandType.elementType -> {
                            emitExpression(expression.argument, operandType)
                            if (methodContext.isTerminated) return
                            emitNullableUnwrapOrThrowNpe(operandType)
                        }
                        operandType == DotNetIlValueType.Object -> {
                            val narrowing = castType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference)
                                ?: dotNetUnsupported(
                                    "implicit cast from object to ${castType.nameInSignature} is not supported"
                                )
                            // `unbox.any !n` is the single CLR operation that correctly recovers
                            // an unconstrained generic parameter from an erased object slot: it
                            // unboxes value instantiations and acts as a checked reference cast
                            // for reference instantiations. Canonical generic-interface bridges
                            // require precisely this operation for erased input parameters.
                            emitExpression(expression.argument, operandType)
                            if (methodContext.isTerminated) return
                            methodContext.emit(narrowing, pops = 1, pushes = 1)
                        }
                        operandType is DotNetIlValueType.TypeParameter -> {
                            val narrowing = castType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference)
                                ?: dotNetUnsupported(
                                    "implicit smartcast from ${operandType.nameInSignature} to " +
                                            "${castType.nameInSignature} is not supported"
                                )
                            // FIR inserts this cast only after a successful runtime type test.
                            // Box the unknown value/reference instantiation at the object boundary,
                            // then recover the proven target (for example `T is Char`).
                            emitExpression(expression.argument, operandType)
                            if (methodContext.isTerminated) return
                            methodContext.emit("box ${operandType.nameInSignature}", pops = 1, pushes = 1)
                            methodContext.emit(narrowing, pops = 1, pushes = 1)
                        }
                        else -> dotNetUnsupported(
                            "implicit cast from ${operandType.nameInSignature} to ${castType.nameInSignature} " +
                                    "is not a reference upcast and is not supported"
                        )
                    }
                }
            }
        }
        if (methodContext.isTerminated) return
        if (!castType.isDotNetAssignableTo(expectedType)) {
            val outerCoercion = dotNetWideningCoercionOrNull(castType, expectedType, coreLibraryReference)
                ?: dotNetUnsupported(
                    "implicit cast produces ${castType.nameInSignature} " +
                            "where ${expectedType.nameInSignature} is expected"
                )
            methodContext.emit(outerCoercion, pops = 1, pushes = 1)
        }
    }

    private fun emitCastResultCoercion(
        castType: DotNetIlValueType,
        expectedType: DotNetIlValueType,
        operationDescription: String,
    ) {
        if (castType.isDotNetAssignableTo(expectedType)) return
        val outerCoercion = dotNetWideningCoercionOrNull(
            castType,
            expectedType,
            coreLibraryReference,
        ) ?: dotNetUnsupported(
            "$operationDescription produces ${castType.nameInSignature} " +
                    "where ${expectedType.nameInSignature} is expected"
        )
        methodContext.emit(outerCoercion, pops = 1, pushes = 1)
    }

    private fun emitRuntimeTypeTest(
        expression: IrTypeOperatorCall,
        castType: DotNetIlValueType,
    ) {
        val positive = expression.operator == IrTypeOperator.INSTANCEOF
        val matchesNullInstruction = if (positive) "ceq" else "cgt.un"
        val matchesNonNullInstruction = if (positive) "cgt.un" else "ceq"

        val exceptionEntry = DotNetMappedExceptions.mappedEntry(expression.typeOperand.classFqName)
        val isClassifiedCharSequence = expression.typeOperand.isDotNetCharSequenceType()
        val runtimeTestType = if (castType is DotNetIlValueType.NullableValue) castType.elementType else castType
        if (exceptionEntry == null && !isClassifiedCharSequence && !expression.typeOperand.isNullableNothing()) {
            when (runtimeTestType) {
                DotNetIlValueType.Boolean,
                DotNetIlValueType.Int8,
                DotNetIlValueType.Int16,
                DotNetIlValueType.Int32,
                DotNetIlValueType.Int64,
                DotNetIlValueType.Float32,
                DotNetIlValueType.Float64,
                DotNetIlValueType.Char,
                DotNetIlValueType.String,
                DotNetIlValueType.Object,
                is DotNetIlValueType.UserClass,
                is DotNetIlValueType.PrimitiveArray,
                is DotNetIlValueType.GenericArray,
                is DotNetIlValueType.ErasedGenericArray,
                is DotNetIlValueType.TypeParameter,
                    -> Unit
                is DotNetIlValueType.GenericInstance -> dotNetUnsupported(
                    "runtime type test against closed CLR generic instance " +
                            "${runtimeTestType.nameInSignature} would make Kotlin type arguments " +
                            "part of runtime identity"
                )
                is DotNetIlValueType.MappedClass,
                is DotNetIlValueType.NullableValue,
                    -> dotNetUnsupported(
                        "runtime type test against ${runtimeTestType.nameInSignature} has no selected " +
                                "exact carrier or classifier"
                    )
            }
        }

        emitExpression(expression.argument, DotNetIlValueType.Object)
        if (methodContext.isTerminated) return
        if (expression.typeOperand.isNullableNothing()) {
            methodContext.emit("ldnull", pushes = 1)
            methodContext.emit(matchesNullInstruction, pops = 2, pushes = 1)
            return
        }

        val nullableJoinLabel = if (expression.typeOperand.isNullable()) {
            val nonNullLabel = methodContext.nextLabel("nullableTypeTestNonNull")
            val joinLabel = methodContext.nextLabel("nullableTypeTestJoin")
            methodContext.emit("dup", pops = 1, pushes = 2)
            methodContext.emitBranch("brtrue", nonNullLabel, pops = 1)
            methodContext.emit("pop", pops = 1)
            if (castType is DotNetIlValueType.TypeParameter) {
                val defaultSlot = methodContext.declareSyntheticLocal(castType, "<typeTestDefault>")
                methodContext.emit(loadLocalAddressInstruction(defaultSlot.index), pushes = 1)
                methodContext.emit("initobj ${castType.nameInSignature}", pops = 1)
                methodContext.emit(loadLocalInstruction(defaultSlot.index), pushes = 1)
                methodContext.emit("box ${castType.nameInSignature}", pops = 1, pushes = 1)
                methodContext.emit("ldnull", pushes = 1)
                methodContext.emit(matchesNullInstruction, pops = 2, pushes = 1)
            } else {
                methodContext.emit(if (positive) "ldc.i4.1" else "ldc.i4.0", pushes = 1)
            }
            methodContext.emitGoto(joinLabel)
            methodContext.emitLabel(nonNullLabel)
            joinLabel
        } else {
            null
        }
        if (exceptionEntry != null) {
            // Every logical exception test goes through the one runtime classifier, including
            // exact classes. `isinst System.Exception` is only the physical admission step for
            // an arbitrary object and preserves the original reference on success.
            methodContext.emit(
                "isinst ${DotNetMappedExceptions.exceptionTypeRef(coreLibraryReference)}",
                pops = 1,
                pushes = 1,
            )
            methodContext.emit("ldc.i4 ${exceptionEntry.classifierTypeId.abiValue}", pushes = 1)
            methodContext.emit(
                DotNetRuntimeLibrary.exceptionClassifierCallInstruction(coreLibraryReference),
                pops = 2,
                pushes = 1,
            )
            if (!positive) {
                methodContext.emit("ldc.i4.0", pushes = 1)
                methodContext.emit("ceq", pops = 2, pushes = 1)
            }
            nullableJoinLabel?.let(methodContext::emitLabel)
            return
        }
        if (isClassifiedCharSequence) {
            methodContext.emit(
                DotNetRuntimeLibraryHelpers.isCharSequenceCallInstruction,
                pops = 1,
                pushes = 1,
            )
            if (!positive) {
                methodContext.emit("ldc.i4.0", pushes = 1)
                methodContext.emit("ceq", pops = 2, pushes = 1)
            }
            nullableJoinLabel?.let(methodContext::emitLabel)
            return
        }
        if (runtimeTestType is DotNetIlValueType.ErasedGenericArray) {
            methodContext.emit(
                DotNetRuntimeLibraryHelpers.isGenericArrayCallInstruction,
                pops = 1,
                pushes = 1,
            )
            if (!positive) {
                methodContext.emit("ldc.i4.0", pushes = 1)
                methodContext.emit("ceq", pops = 2, pushes = 1)
            }
            nullableJoinLabel?.let(methodContext::emitLabel)
            return
        }
        methodContext.emit("isinst ${runtimeTestType.nameInSignature}", pops = 1, pushes = 1)
        methodContext.emit("ldnull", pushes = 1)
        methodContext.emit(matchesNonNullInstruction, pops = 2, pushes = 1)
        nullableJoinLabel?.let(methodContext::emitLabel)
    }

    private fun IrType.dotNetKFunctionExecutionArityOrNull(): Int? =
        (this as? IrSimpleType)?.classifier?.owner
            ?.let { it as? IrClass }
            ?.dotNetFixedKFunctionArityOrNull()

    private fun IrType.dotNetFunctionExecutionArityOrNull(): Int? =
        (this as? IrSimpleType)?.classifier?.owner
            ?.let { it as? IrClass }
            ?.dotNetFixedFunctionArityOrNull()

    /**
     * Emits [expression] as a non-null string suitable for printing or concatenation: constants
     * are rendered through their string representation, nullable strings are coalesced to the
     * `"null"` literal, and non-string values are converted with Kotlin `toString` semantics
     * ([emitBooleanToString] keeps Kotlin's lowercase `"true"`/`"false"` rendering; `Int`/`Long`
     * values go through [emitBoxedInvariantToString], the invariant-culture rendering; `Char`
     * uses the static culture-free `Char::ToString(char)`; `Double` goes through
     * [emitDoubleValueToString], the shared Kotlin-parity rendering helper). Reference-shaped
     * values and open type parameters use the runtime's null-safe `StringValueOf(object)`, the
     * CLR counterpart of the JVM backend's `String.valueOf(Object)` path.
     */
    fun emitStringValueExpression(expression: IrExpression?) {
        when {
            expression == null -> methodContext.emit("ldstr ${"null".toIlStringLiteral()}", pushes = 1)
            // Double constants deliberately skip the compile-time toString fast path: the host
            // Kotlin rendering can differ from what the DoubleToString runtime helper produces
            // (digit-count divergences documented on the helper), and constant vs. non-constant
            // values must print identically.
            // Float constants are excluded for the same reason, with the opposite outcome: Float
            // is a deferred type, so instead of silently printing the host rendering the constant
            // falls through to emitExpression below and fails as unsupported, exactly like every
            // non-constant Float use (fail-hard design rule).
            expression is IrConst && expression.value !is Double && expression.value !is Float ->
                methodContext.emit("ldstr ${expression.value.toString().toIlStringLiteral()}", pushes = 1)
            else -> when (val valueType = typeMapper.toDotNetIlValueType(expression.type)) {
                DotNetIlValueType.Boolean,
                DotNetIlValueType.Int8,
                DotNetIlValueType.Int16,
                DotNetIlValueType.Int32,
                DotNetIlValueType.Int64,
                DotNetIlValueType.Float32,
                DotNetIlValueType.Float64,
                DotNetIlValueType.Char,
                    -> {
                    emitExpression(expression, valueType)
                    emitPrimitiveValueToString(valueType)
                }
                // A nullable primitive renders through a HasValue branch selecting the "null"
                // literal or the existing per-type rendering of the extracted value (Kotlin
                // semantics: `null` prints as "null"). The spill-then-address discipline is
                // mandatory (boxprobe_s2); the composed shape is probe-verified per type
                // (boxprobe_s7).
                is DotNetIlValueType.NullableValue -> {
                    emitExpression(expression, valueType)
                    val slot = spillToSyntheticLocal(valueType, "<str>")
                    val notNullLabel = methodContext.nextLabel("strValueNotNull")
                    val endLabel = methodContext.nextLabel("strValueEnd")
                    methodContext.emit(loadLocalAddressInstruction(slot.index), pushes = 1)
                    methodContext.emit(valueType.hasValueInstruction, pops = 1, pushes = 1)
                    methodContext.emitBranch("brtrue", notNullLabel, pops = 1)
                    methodContext.emit("ldstr ${"null".toIlStringLiteral()}", pushes = 1)
                    methodContext.emitGoto(endLabel)
                    methodContext.emitLabel(notNullLabel)
                    methodContext.emit(loadLocalAddressInstruction(slot.index), pushes = 1)
                    methodContext.emit(valueType.getValueOrDefaultInstruction, pops = 1, pushes = 1)
                    emitPrimitiveValueToString(valueType.elementType)
                    methodContext.emitLabel(endLabel)
                }
                DotNetIlValueType.Object,
                is DotNetIlValueType.UserClass,
                is DotNetIlValueType.GenericInstance,
                is DotNetIlValueType.PrimitiveArray,
                is DotNetIlValueType.GenericArray,
                is DotNetIlValueType.ErasedGenericArray,
                is DotNetIlValueType.MappedClass,
                is DotNetIlValueType.TypeParameter,
                    -> {
                    // Every reference shape widens instruction-free; a reified open T is boxed.
                    // The helper returns "null" for a null reference and otherwise dispatches
                    // System.Object::ToString virtually, including to Kotlin overrides.
                    emitExpression(expression, DotNetIlValueType.Object)
                    methodContext.emit(
                        DotNetRuntimeLibraryHelpers.stringValueOfCallInstruction,
                        pops = 1,
                        pushes = 1,
                    )
                }
                // A `null` mapping (unsupported type) also lands here so that emitExpression
                // reports the standard unsupported-construct diagnostic.
                DotNetIlValueType.String, null -> {
                    emitExpression(expression, DotNetIlValueType.String)
                    if (expression.type.isDotNetNullableStringType()) {
                        emitNullStringAsStringLiteral()
                    }
                }
            }
        }
    }

    /**
     * Converts the plain primitive value of [valueType] on top of the stack to its Kotlin
     * `toString()` rendering: the per-type shapes documented on [emitBooleanToString],
     * [emitBoxedInvariantToString] and [emitDoubleValueToString]; `Char` uses the static,
     * culture-free `Char::ToString(char)` — unlike Int32/Int64 no box is needed, mscorlib has a
     * static ToString overload for char (there is no static `Int32::ToString(int32)`), so the
     * int32-shaped stack value is passed directly. Net stack effect: pop 1, push 1.
     */
    private fun emitPrimitiveValueToString(valueType: DotNetIlValueType) {
        when (valueType) {
            DotNetIlValueType.Boolean -> emitBooleanToString()
            DotNetIlValueType.Int8 -> emitBoxedInvariantToString("${coreLibraryReference}System.SByte")
            DotNetIlValueType.Int16 -> emitBoxedInvariantToString("${coreLibraryReference}System.Int16")
            DotNetIlValueType.Int32 -> emitBoxedInvariantToString("${coreLibraryReference}System.Int32")
            DotNetIlValueType.Int64 -> emitBoxedInvariantToString("${coreLibraryReference}System.Int64")
            DotNetIlValueType.Float32 -> emitFloatValueToString()
            DotNetIlValueType.Float64 -> emitDoubleValueToString()
            DotNetIlValueType.Char ->
                methodContext.emit(
                    "call string ${coreLibraryReference}System.Char::ToString(char)",
                    pops = 1,
                    pushes = 1,
                )
            else -> error("Internal .NET backend error: no primitive string rendering for ${valueType.nameInSignature}")
        }
    }

    /**
     * Converts the `bool` on top of the stack to Kotlin's string rendering. `Boolean.ToString()`
     * must NOT be used here: it yields `"True"`/`"False"` while Kotlin prints `"true"`/`"false"`.
     * Net stack effect: pop 1, push 1.
     */
    private fun emitBooleanToString() {
        val trueLabel = methodContext.nextLabel("boolStrTrue")
        val endLabel = methodContext.nextLabel("boolStrEnd")
        methodContext.emitBranch("brtrue", trueLabel, pops = 1)
        methodContext.emit("ldstr ${"false".toIlStringLiteral()}", pushes = 1)
        methodContext.emitGoto(endLabel)
        methodContext.emitLabel(trueLabel)
        methodContext.emit("ldstr ${"true".toIlStringLiteral()}", pushes = 1)
        methodContext.emitLabel(endLabel)
    }

    /**
     * Converts the integer on top of the stack to its Kotlin `toString()` rendering: box to
     * [boxedType] and call `IFormattable::ToString(null, InvariantCulture)` (`null` format is
     * the default `"G"` rendering). Plain `Object::ToString()` (and `Console::WriteLine(int32)`)
     * must NOT be used here: integer default formatting honors the *current* culture's
     * `NumberFormat.NegativeSign`, so `(-5).toString()` can render as `"!5"` on a machine whose
     * regional settings customize the sign, while Kotlin's `toString` is culture-independent
     * (verified on the targeted .NET Framework 4 runtime with a customized `NegativeSign`).
     * Net stack effect: pop 1, push 1.
     */
    private fun emitBoxedInvariantToString(boxedType: String) {
        methodContext.emit("box $boxedType", pops = 1, pushes = 1)
        methodContext.emit("ldnull", pushes = 1)
        methodContext.emit(
            "call class ${coreLibraryReference}System.Globalization.CultureInfo " +
                    "${coreLibraryReference}System.Globalization.CultureInfo::get_InvariantCulture()",
            pushes = 1,
        )
        methodContext.emit(
            "callvirt instance string ${coreLibraryReference}System.IFormattable::ToString(" +
                    "string, class ${coreLibraryReference}System.IFormatProvider)",
            pops = 3,
            pushes = 1,
        )
    }

    /**
     * Converts the `float64` value on top of the stack to a string with Kotlin
     * `Double.toString()` shapes (`1.0`, `1.0E20`, `NaN`, `Infinity`, `-0.0`) by calling the
     * shared [DotNetRuntimeLibraryHelpers] implementation in Kotlin.Runtime.
     * See that helper's documentation for the rendering algorithm and the consciously accepted
     * divergences from the JVM rendering.
     */
    private fun emitDoubleValueToString() {
        methodContext.emit(DotNetRuntimeLibraryHelpers.doubleToStringCallInstruction, pops = 1, pushes = 1)
    }

    /** Converts `float32` through the profile-stable Kotlin runtime formatter. */
    private fun emitFloatValueToString() {
        methodContext.emit(DotNetRuntimeLibraryHelpers.floatToStringCallInstruction, pops = 1, pushes = 1)
    }

    /**
     * Emits the arguments and the `call`/`callvirt` instruction for a call to a top-level
     * Kotlin function or to an instance member/accessor of a user class. For an instance callee
     * `call.arguments[0]` is the receiver: it is emitted against the this-type kept at
     * `parameterTypes[0]` and popped by the call like every other argument, so the plain
     * argument zip covers both shapes. Throws [DotNetIlUnsupportedException] when the callee is
     * not available (not compilable, already skipped, or not declared in this module).
     *
     * A call through a FAKE OVERRIDE (an inherited member referenced via the derived class) is
     * resolved to the real declaration first (JVM precedent: `MethodSignatureMapper` maps calls
     * through `findSuperDeclaration`), so the emitted member reference names the DECLARING
     * class; the CLR accepts that operand token with a derived-typed receiver for both `call`
     * and `callvirt` (probe-verified, `inheritprobe_s1`). For INTERFACE members the same
     * resolution is mandatory rather than lenient: the `callvirt` operand MUST name the
     * interface that DECLARES the member — naming a sub-interface that merely inherits it is a
     * runtime MissingMethodException (probe-verified, `ifaceprobe_s6`). Dispatch: a
     * [virtual callee][isDotNetVirtual] uses `callvirt` — runtime dispatch on the receiver's
     * class — unless the call is `super`-qualified, which is exactly the JVM's
     * invokevirtual/invokespecial split; a `super` call and every final member keep the plain
     * non-virtual `call` (see [DotNetIlFunctionInfo.renderCallInstruction]). A
     * source `super<I>` call is rewritten by profile-aware interface-default lowering to exactly
     * `I`'s static helper. Portable helpers own the moved body; modern helpers issue the exact
     * nonvirtual DIM call. Seeing an interface super qualifier here without the compiler-owned
     * exact-call origin therefore means lowering failed and is rejected below.
     */
    fun emitCall(call: IrCall): DotNetIlReturnType {
        val resolved = resolveCall(call)
        val constrainedReceiverType = resolved.receiverType as? DotNetIlValueType.TypeParameter
        if (constrainedReceiverType != null) {
            val expectedReceiverType = resolved.parameterTypes.firstOrNull()
                ?: dotNetUnsupported("call to '${resolved.calleeName}' has an unsupported receiver shape")
            if (!constrainedReceiverType.isConstrainedTo(expectedReceiverType)) {
                dotNetUnsupported(
                    "call to '${resolved.calleeName}' is outside the declared bounds of " +
                            "type parameter ${constrainedReceiverType.nameInSignature}"
                )
            }
            emitTypeParameterReceiverArguments(
                call.arguments,
                resolved.parameterTypes,
                "'${resolved.calleeName}'",
                constrainedReceiverType,
                resolved.virtual,
            )
        } else {
            emitArguments(call.arguments, resolved.parameterTypes, "'${resolved.calleeName}'")
        }
        if (constrainedReceiverType != null && resolved.virtual) {
            // `constrained.` is a prefix and must be immediately adjacent to its `callvirt`.
            methodContext.emit("constrained. ${constrainedReceiverType.nameInSignature}")
        }
        methodContext.emit(
            resolved.info.renderCallInstruction(
                resolved.info.physicalMethodName ?: resolved.callee.dotNetIlMethodName(),
                virtual = resolved.virtual,
                ownerToken = resolved.ownerToken,
                methodInstantiation = resolved.methodInstantiation,
            ),
            pops = resolved.info.signature.parameterTypes.size,
            pushes = if (resolved.info.signature.returnType is DotNetIlReturnType.Value) 1 else 0,
        )
        return resolved.returnType
    }

    /** Uses an optional typed capability in statement position, then discards any produced value. */
    fun tryEmitCapabilityCallForDiscard(call: IrCall): Boolean {
        val logicalResultType = if (call.type.isUnit()) null else typeMapper.toDotNetIlValueType(call.type) ?: return false
        val emitted = emitGenericInterfaceCapabilityCallOrNull(call, logicalResultType) ||
                (logicalResultType != null && emitCallableCapabilityCallOrNull(call, logicalResultType))
        if (!emitted) return false
        if (logicalResultType != null) methodContext.emit("pop", pops = 1)
        return true
    }

    /**
     * Resolves both the member-reference token and its substituted value signature. Keeping this
     * separate from instruction emission lets [mappedNaturalType] use exactly the same generic
     * owner/method substitution when an enclosing IR cast asks what a call really produces.
     */
    private fun resolveCall(call: IrCall): ResolvedCall {
        call.superQualifierSymbol?.owner?.let { superQualifier ->
            if (superQualifier.isInterface && call.origin != DOTNET_INTERFACE_DEFAULT_EXACT_CALL) {
                dotNetUnsupported(
                    "'super<${superQualifier.name.asString()}>' call escaped profile-aware interface-default lowering"
                )
            }
        }
        // resolveFakeOverride ignores ABSTRACT real declarations, so a fake override whose only
        // real declarations are abstract interface members (a super-interface member referenced
        // through a sub-interface-typed receiver, ifaceprobe_s6) falls back to the
        // maybe-abstract resolution — the operand must name the DECLARING interface. When the
        // abstract member is inherited from several unrelated super-interfaces at once, any of
        // them is a correct operand: the implementing class's single member fills every
        // same-signature interface slot (probe-verified, ifaceprobe_s9).
        val callee = call.symbol.owner.let { it.resolveFakeOverride() ?: it.resolveFakeOverrideMaybeAbstract() ?: it }
        val calleeName = callee.name.asString()
        val info = availableFunctions[callee] ?: typeMapper.referencedFunctionInfoOrNull(callee)
            ?: dotNetUnsupported(
                "call to unsupported function '$calleeName' on " +
                        ((callee.parent as? IrClass)?.fqNameWhenAvailable?.asString() ?: callee.parent.render()) +
                        " (origin ${callee.origin}; intrinsic registered=" +
                        "${intrinsicMethods.getIntrinsic(call.symbol) != null}; " +
                        "arguments=${call.arguments.size}; result=${call.type.render()})"
            )
        info.owner.assemblyName?.let(typeMapper::recordAssemblyReference)
        // A generic FUNCTION call, top-level or member, carries its instantiation on the method token —
        // `call !!0 'FileKt'::'id'<string>(!!0)`, signature slots verbatim from the declaration
        // (probe-verified, genprobe_s1; `!!0` is itself a legal instantiation argument at
        // generic→generic call sites; a member can combine it with an independently instantiated
        // generic owner, genmemberprobe_s1) — never erased: an unmappable type argument fails the
        // call site loudly.
        val methodInstantiation = if (callee.typeParameters.isNotEmpty()) {
            if (call.typeArguments.size != callee.typeParameters.size) {
                dotNetUnsupported("call to '$calleeName' has an unsupported type-argument shape")
            }
            call.typeArguments.map { argumentType ->
                argumentType?.let { typeMapper.toDotNetIlValueType(it) }
                    ?: dotNetUnsupported(
                        "call to '$calleeName' instantiates a type parameter with an unsupported type argument"
                    )
            }
        } else emptyList()
        val receiverType = if (info.isInstance) {
            val receiver = call.arguments.firstOrNull()
                ?: dotNetUnsupported("call to '$calleeName' has an unsupported argument shape")
            typeMapper.toDotNetIlValueType(receiver.type)
                ?: dotNetUnsupported(
                    "call to '$calleeName' through a receiver of unsupported type ${receiver.type.render()}"
                )
        } else {
            null
        }
        // A member of a genuinely generic CLR owner (an imported CLR generic or a typed
        // interface capability) uses the receiver's instantiated declaring-owner token while
        // signature slots stay open per CLR member-ref rules. Kotlin-owned ordinary generic
        // classes have an arity-zero owner and therefore never enter this branch.
        var ownerToken = info.owner.ilTypeRef
        var classInstantiation = emptyList<DotNetIlValueType>()
        if (info.isInstance && info.owner.typeParameterCount > 0) {
            val ownerView = receiverType!!.dotNetViewAsGenericOwner(info.owner)
                ?: dotNetUnsupported(
                    "call to '$calleeName' through a receiver that is not an instantiation of its declaring class"
                )
            ownerToken = ownerView.nameInSignature
            classInstantiation = ownerView.arguments
        } else if (
            !info.isInstance &&
            info.owner.typeParameterCount > 0 &&
            callee.isOriginallyLocalDeclaration &&
            callee.parent is IrClass
        ) {
            // A lifted local function is static even when its metadata owner is a generic class.
            // CLR member references must still instantiate that owner (`Owner<!0>::local`), or
            // the runtime rejects the call as an open containing type. Prefer an explicit
            // captured-owner argument; a direct call from another method of the same owner uses
            // the owner's open `!n` instantiation (localfunprobe_s2).
            val capturedOwnerView = call.arguments.asSequence()
                .filterNotNull()
                .mapNotNull { argument -> typeMapper.toDotNetIlValueType(argument.type) }
                .mapNotNull { argumentType -> argumentType.dotNetViewAsGenericOwner(info.owner) }
                .firstOrNull()
            val ownerView = capturedOwnerView ?: if (currentOwner == info.owner) {
                DotNetIlValueType.GenericInstance(
                    info.owner,
                    List(info.owner.typeParameterCount) { index ->
                        DotNetIlValueType.TypeParameter(index, isMethodParameter = false)
                    },
                )
            } else {
                null
            } ?: dotNetUnsupported(
                "call to lifted local function '$calleeName' cannot determine the generic owner instantiation"
            )
            ownerToken = ownerView.nameInSignature
            classInstantiation = ownerView.arguments
        }
        // Argument VALUES flow at the substituted types (the CLR's reification: `Box<Int>`
        // really takes an `int32`), while the member-ref operand keeps the open ones.
        val parameterTypes = info.signature.parameterTypes
            .mapIndexed { index, parameterType ->
                try {
                    parameterType.substituteDotNetTypeParameters(classInstantiation, methodInstantiation)
                } catch (failure: IllegalStateException) {
                    throw IllegalStateException(
                        "Internal .NET backend error: cannot substitute parameter $index of '$calleeName' " +
                                "on ${info.owner.ilTypeRef}; parameter=${parameterType.nameInSignature}, " +
                                "ownerArguments=${classInstantiation.map { it.nameInSignature }}, " +
                                "methodArguments=${methodInstantiation.map { it.nameInSignature }}",
                        failure,
                    )
                }
            }
        val virtual = call.superQualifierSymbol == null && callee.isDotNetVirtual()
        return ResolvedCall(
            callee = callee,
            calleeName = calleeName,
            info = info,
            methodInstantiation = methodInstantiation,
            receiverType = receiverType,
            ownerToken = ownerToken,
            parameterTypes = parameterTypes,
            virtual = virtual,
            returnType = info.signature.returnType.substituteDotNetTypeParameters(
                classInstantiation,
                methodInstantiation,
            ),
        )
    }

    private data class ResolvedCall(
        val callee: IrSimpleFunction,
        val calleeName: String,
        val info: DotNetIlFunctionInfo,
        val methodInstantiation: List<DotNetIlValueType>,
        val receiverType: DotNetIlValueType?,
        val ownerToken: String,
        val parameterTypes: List<DotNetIlValueType>,
        val virtual: Boolean,
        val returnType: DotNetIlReturnType,
    )

    /**
     * Emits a call whose dispatch receiver is a constrained `!n`/`!!n`. Virtual/interface calls
     * need the receiver's managed address followed by the `constrained.` prefix; non-virtual
     * class members instead take the boxed receiver accepted by an ordinary instance `call`.
     * Receiver and arguments are evaluated into locals before any call operands are reloaded:
     * this preserves source order and keeps the evaluation stack empty if an argument contains a
     * CLR protected region. Both shapes remain valid if an external CLR caller supplies a value
     * type for an interface-only constraint (genconstraintprobe_s1/_s2).
     */
    private fun emitTypeParameterReceiverArguments(
        arguments: List<IrExpression?>,
        parameterTypes: List<DotNetIlValueType>,
        calleeDescription: String,
        receiverType: DotNetIlValueType.TypeParameter,
        virtual: Boolean,
    ) {
        if (arguments.size != parameterTypes.size || arguments.isEmpty()) {
            dotNetUnsupported("call to $calleeDescription has an unsupported argument shape")
        }
        val receiver = arguments[0]
            ?: dotNetUnsupported("call to $calleeDescription has a missing dispatch receiver")
        emitExpression(receiver, receiverType)
        val receiverSlot = spillToSyntheticLocal(receiverType, "<constrainedReceiver>")
        val argumentSlots = arguments.drop(1).indices.map { index ->
            val argument = arguments[index + 1]
                ?: dotNetUnsupported("call to $calleeDescription relies on default argument values")
            val parameterType = parameterTypes[index + 1]
            emitExpression(argument, parameterType)
            spillToSyntheticLocal(parameterType, "<constrainedArgument>")
        }
        if (virtual) {
            methodContext.emit(loadLocalAddressInstruction(receiverSlot.index), pushes = 1)
        } else {
            methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
            methodContext.emit("box ${receiverType.nameInSignature}", pops = 1, pushes = 1)
        }
        for (argumentSlot in argumentSlots) {
            methodContext.emit(loadLocalInstruction(argumentSlot.index), pushes = 1)
        }
    }

    /**
     * Emits the argument expressions of a call in order, each against its mapped parameter type.
     * [calleeDescription] names the callee inside the diagnostics, matching the historical
     * `call to 'f' ...` message shapes.
     */
    fun emitArguments(
        arguments: List<IrExpression?>,
        parameterTypes: List<DotNetIlValueType>,
        calleeDescription: String,
    ) {
        if (arguments.size != parameterTypes.size) {
            dotNetUnsupported("call to $calleeDescription has an unsupported argument shape")
        }
        for ([argument, parameterType] in arguments.zip(parameterTypes)) {
            if (argument == null) {
                dotNetUnsupported("call to $calleeDescription relies on default argument values")
            }
            emitExpression(argument, parameterType)
        }
    }

    /**
     * `throw e` -> the exception reference, then IL `throw` (JVM precedent: `IrThrow` maps 1:1
     * onto the platform throw instruction, no lowering). A value is throwable exactly when its
     * physical CLR type widens to the universal `System.Exception` carrier. That includes mapped
     * built-ins, Kotlin-owned user subclasses, and imported exception subclasses; accepting only
     * [DotNetIlValueType.MappedClass] here would make `throw MyException()` fail even though its
     * emitted metadata has an ordinary CLR exception base chain. A source rethrow (`throw e`
     * inside a catch handler) is the same shape — a load of the bound local followed by `throw`,
     * which preserves object identity; the IL `rethrow` instruction is never emitted (Kotlin has
     * no bare rethrow statement).
     */
    fun emitThrow(expression: IrThrow) {
        val thrownType = typeMapper.toDotNetIlValueType(expression.value.type)
        val exceptionCarrier = DotNetIlValueType.MappedClass(
            DotNetMappedExceptions.exceptionTypeRef(typeMapper.coreLibrary.reference)
        )
        if (thrownType?.isDotNetAssignableTo(exceptionCarrier) != true) {
            dotNetUnsupported(
                "throw of type ${expression.value.type.render()} whose CLR representation is not " +
                        "assignable to System.Exception is not supported"
            )
        }
        emitExpression(expression.value, exceptionCarrier)
        methodContext.emitThrow()
    }

    /**
     * `Point(1, 2)` → arguments then `newobj instance void 'Point'::.ctor(int32, int32)`
     * (probe-verified; `newobj` pops the arguments and pushes the new instance, calling the
     * constructor with the freshly allocated `this` as argument 0).
     *
     * An imported generic CLR construction carries the full instantiation on its owner token and
     * emits arguments against substituted parameter types. A Kotlin-owned `Box<String>(v)` does
     * not: it constructs the declaration-erased `Box` owner and adapts owner-parameter values at
     * its object/upper-bound slots. Both routes are resolved through the live type mapper, so an
     * unsupported or evicted carrier still fails loudly.
     */
    private fun emitConstructorCall(call: IrConstructorCall, expectedType: DotNetIlValueType) {
        val constructor = call.symbol.owner
        val irClass = constructor.constructedClass
        val constructedType = typeMapper.toDotNetIlValueType(call.type)
        if (constructedType is DotNetIlValueType.PrimitiveArray ||
            constructedType is DotNetIlValueType.GenericArray
        ) {
            val intrinsic = intrinsicMethods.getIntrinsic(call.symbol)
            if (intrinsic != null && intrinsic.tryEmitConstructorAsExpression(call, this, expectedType)) return
            dotNetUnsupported(
                "array constructor '${irClass.name.asString()}' has an unsupported argument shape"
            )
        }
        when (val entry = irClass.fqNameWhenAvailable?.let(DotNetMappedExceptions.entries::get)) {
            is DotNetMappedExceptions.Entry.Mapped -> {
                emitMappedExceptionConstructorCall(call, entry, expectedType)
                return
            }
            is DotNetMappedExceptions.Entry.Rejected -> dotNetUnsupported(entry.reason)
            null -> {}
        }
        val genericClassInfo = typeMapper.genericClassInfoOrNull(irClass)
        val constructorTypeMapper = typeMapper
        val classInfo = genericClassInfo?.classInfo ?: constructorTypeMapper.classInfoOrNull(irClass)
            ?: dotNetUnsupported("constructor call of unsupported class '${irClass.name.asString()}'")
        val [producedType, ownerToken, classInstantiation] = if (
            genericClassInfo != null || irClass.typeParameters.isEmpty()
        ) {
            Triple(DotNetIlValueType.UserClass(classInfo) as DotNetIlValueType, classInfo.ilTypeRef, emptyList<DotNetIlValueType>())
        } else {
            // Source generic calls carry the instantiation on call.type. Common local-declaration
            // lowering instead leaves the generated class type bare and appends captured outer
            // type parameters to the constructor call's typeArguments. Both are authoritative IR
            // encodings of the same constructed CLR owner.
            val argumentsFromCall = call.typeArguments.map { argument ->
                argument?.let(constructorTypeMapper::toDotNetIlValueType)
            }
            val instanceType = (constructorTypeMapper.toDotNetIlValueType(call.type) as? DotNetIlValueType.GenericInstance)
                ?: argumentsFromCall
                    .takeIf { arguments ->
                        arguments.size == irClass.typeParameters.size && arguments.all { it != null }
                    }
                    ?.let { arguments ->
                        DotNetIlValueType.GenericInstance(classInfo, arguments.filterNotNull())
                    }
                ?: dotNetUnsupported(
                    "constructor call of generic class '${irClass.name.asString()}' with unsupported " +
                            "instantiation ${call.type.render()} and type arguments " +
                            call.typeArguments.joinToString(prefix = "<", postfix = ">") { it?.render() ?: "_" }
                )
            Triple(instanceType as DotNetIlValueType, instanceType.nameInSignature, instanceType.arguments)
        }
        // Assignability, not equality: `val b: Base = Derived(...)` is a pure reference upcast
        // needing no IL instruction (probe-verified, inheritprobe_s1), the same widening every
        // other value producer already goes through.
        if (!producedType.isDotNetAssignableTo(expectedType)) {
            dotNetUnsupported(
                "constructor call of '${irClass.name.asString()}' produces ${producedType.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
        val parameterTypes = constructor.dotNetSignature(constructorTypeMapper).parameterTypes
        val substitutedParameterTypes = parameterTypes.map { it.substituteDotNetTypeParameters(classInstantiation) }
        emitArguments(call.arguments, substitutedParameterTypes, "constructor of '${irClass.name.asString()}'")
        methodContext.emit(
            "newobj ${classInfo.renderConstructorReference(parameterTypes, ownerToken)}",
            pops = parameterTypes.size,
            pushes = 1,
        )
    }

    /**
     * A constructor call of a [mapped exception type][DotNetMappedExceptions]:
     * `IllegalStateException(msg)` -> arguments, then a `newobj` of the corelib
     * `System.InvalidOperationException::.ctor(string)` — every emitted `.ctor` overload
     * is ilasm-probe-verified (assembled and executed). The overload is
     * checked against the registry's whitelist: `()` and `(String?)` exist on every mapped CLR
     * type, `(String?, Throwable?)` maps where
     * [hasMessageCauseCtor][DotNetMappedExceptions.Entry.Mapped.hasMessageCauseCtor] is set (a
     * mirror of the Kotlin stdlib's declared constructor surface; every BCL mapping has the CLR
     * `(string, Exception)` overload, probe-verified, while runtime mappings provide their exact
     * flagged surface). Cause-only `(Throwable?)` maps where
     * [hasCauseCtor][DotNetMappedExceptions.Entry.Mapped.hasCauseCtor] is set.
     */
    private fun emitMappedExceptionConstructorCall(
        call: IrConstructorCall,
        entry: DotNetMappedExceptions.Entry.Mapped,
        expectedType: DotNetIlValueType,
    ) {
        val constructor = call.symbol.owner
        val className = constructor.constructedClass.name.asString()
        val carrierClrTypeRef = entry.carrierTypeRef(coreLibraryReference)
        val constructorClrTypeRef = entry.constructorTypeRef(coreLibraryReference)
        val producedType = DotNetIlValueType.MappedClass(carrierClrTypeRef)
        if (!producedType.isDotNetAssignableTo(expectedType)) {
            dotNetUnsupported(
                "constructor call of '$className' produces ${producedType.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
        val parameterTypes = constructor.parameters.map { parameter ->
            typeMapper.toDotNetIlValueType(parameter.type)
                ?: dotNetUnsupported(
                    "parameter '${parameter.name.asString()}' has unsupported type ${parameter.type.render()}"
                )
        }
        entry.checkConstructorShape(className, parameterTypes, coreLibraryReference)
        emitArguments(call.arguments, parameterTypes, "constructor of '$className'")
        methodContext.emit(
            "newobj instance void $constructorClrTypeRef::.ctor(${parameterTypes.joinToString(", ") { it.nameInSignature }})",
            pops = parameterTypes.size,
            pushes = 1,
        )
        // Several logical Kotlin exception classes deliberately share a broad CLR carrier. Keep
        // the exact constructor identity on the original object in the same weak runtime state
        // that owns suppressed exceptions, so dynamic `value::class` can distinguish (notably)
        // Throwable() from Exception() without a wrapper or Exception.Data mutation.
        methodContext.emit("dup", pops = 1, pushes = 2)
        methodContext.emit("ldc.i4 ${entry.classifierTypeId.abiValue}", pushes = 1)
        methodContext.emit(
            DotNetThrowableRuntime.setExactTypeIdCallInstruction(coreLibraryReference),
            pops = 2,
        )
    }

    /**
     * A field read: for an instance field the receiver, then `ldfld <type> 'C'::'name'`; for a
     * static field — the facade field of a top-level property, or the `INSTANCE` field of an
     * `object` class — a bare `ldsfld <type> 'C'::'name'`; all spellings probe-verified
     * (`statprobe_s1`/`_s2`, `objprobe_s1`/`_s2` — the bare `ldsfld` of INSTANCE is also the
     * first-active-use `.cctor` trigger).
     */
    private fun emitGetField(expression: IrGetField, expectedType: DotNetIlValueType) {
        val field = expression.symbol.owner
        val [classInfo, declaredFieldType, isStatic] = resolveFieldAccess(field)
        if (classInfo.typeParameterCount > 0) {
            // A field on a genuinely generic CLR owner keeps its declared open field type while
            // the owner token carries the receiver instantiation. Kotlin-owned generic-class
            // fields have an arity-zero erased owner and are handled by the ordinary branch.
            val [ownerView, receiver, receiverType] = resolveGenericFieldOwner(expression.receiver, field, isStatic)
            val fieldType = declaredFieldType.substituteDotNetTypeParameters(ownerView.arguments)
            if (!fieldType.isDotNetAssignableTo(expectedType)) {
                dotNetUnsupported(
                    "field '${field.name.asString()}' has type ${fieldType.nameInSignature} " +
                            "where ${expectedType.nameInSignature} is expected"
                )
            }
            emitExpression(receiver, receiverType)
            methodContext.emit(
                "ldfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString(), ownerView.nameInSignature)}",
                pops = 1,
                pushes = 1,
            )
            return
        }
        if (!declaredFieldType.isDotNetAssignableTo(expectedType)) {
            if (declaredFieldType == DotNetIlValueType.Object) {
                if (isStatic) {
                    methodContext.emit(
                        "ldsfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString())}",
                        pushes = 1,
                    )
                } else {
                    emitFieldReceiver(expression.receiver, field, classInfo)
                    methodContext.emit(
                        "ldfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString())}",
                        pops = 1,
                        pushes = 1,
                    )
                }
                emitErasedCarrierAs(expectedType, "field '${field.name.asString()}'")
                return
            }
            dotNetUnsupported(
                "field '${field.name.asString()}' has type ${declaredFieldType.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
        if (isStatic) {
            methodContext.emit("ldsfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString())}", pushes = 1)
            return
        }
        emitFieldReceiver(expression.receiver, field, classInfo)
        methodContext.emit(
            "ldfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString())}",
            pops = 1,
            pushes = 1,
        )
    }

    /**
     * A field store: receiver, value, then `stfld <type> 'C'::'name'` for instance fields, or
     * value then `stsfld <type> 'FileKt'::'name'` for static facade fields (both probe-verified).
     * Reaches codegen from `DEFAULT_PROPERTY_ACCESSOR` setter bodies, from the field
     * initializations [InitializersLowering][org.jetbrains.kotlin.backend.common.lower.InitializersLowering]
     * merged into constructor bodies, and from the top-level initializations
     * [DotNetStaticInitializersLowering][org.jetbrains.kotlin.backend.dotnet.lower.DotNetStaticInitializersLowering]
     * moved into the file `<clinit>`; user-written property writes are accessor calls instead.
     */
    fun emitSetField(expression: IrSetField) {
        val field = expression.symbol.owner
        val [classInfo, declaredFieldType, isStatic] = resolveFieldAccess(field)
        if (classInfo.typeParameterCount > 0) {
            // The store counterpart of the generic-owner `ldfld` above: open field-type slot,
            // instantiated owner token, value emitted at the substituted type (genprobe_s2/_s3).
            val [ownerView, receiver, receiverType] = resolveGenericFieldOwner(expression.receiver, field, isStatic)
            val fieldType = declaredFieldType.substituteDotNetTypeParameters(ownerView.arguments)
            emitExpression(receiver, receiverType)
            emitExpression(expression.value, fieldType)
            methodContext.emit(
                "stfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString(), ownerView.nameInSignature)}",
                pops = 2,
            )
            return
        }
        if (isStatic) {
            emitExpression(expression.value, declaredFieldType)
            methodContext.emit("stsfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString())}", pops = 1)
            return
        }
        emitFieldReceiver(expression.receiver, field, classInfo)
        emitExpression(expression.value, declaredFieldType)
        methodContext.emit("stfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString())}", pops = 2)
    }

    /**
     * The instantiated owner view of a field access on a genuinely generic CLR class: the
     * receiver's mapped type walked up to the field's declaring class. Kotlin-owned generic-class
     * fields use their arity-zero erased owner and never enter this path. Static fields on the
     * remaining reified model are rejected defensively.
     */
    private fun resolveGenericFieldOwner(
        receiver: IrExpression?,
        field: IrField,
        isStatic: Boolean,
    ): Triple<DotNetIlValueType.GenericInstance, IrExpression, DotNetIlValueType> {
        val fieldName = field.name.asString()
        if (isStatic) {
            dotNetUnsupported("static field '$fieldName' on a generic CLR class is not supported")
        }
        if (receiver == null) {
            dotNetUnsupported("receiverless access to instance field '$fieldName' is not supported")
        }
        val receiverType = typeMapper.toDotNetIlValueType(receiver.type)
            ?: dotNetUnsupported("access to field '$fieldName' through a receiver of unsupported type ${receiver.type.render()}")
        val ownerInfo = (field.parent as? IrClass)?.let(typeMapper::classInfoOrNull)
            ?: dotNetUnsupported("access to a field of unsupported class")
        val ownerView = receiverType.dotNetViewAsGenericOwner(ownerInfo)
            ?: dotNetUnsupported(
                "access to field '$fieldName' through ${receiver.type.render()} " +
                        "(${receiverType.nameInSignature}) cannot recover generic CLR owner " +
                        "${ownerInfo.ilTypeRef} with arity ${ownerInfo.typeParameterCount}"
            )
        return Triple(ownerView, receiver, receiverType)
    }

    /**
     * Resolves the owning IL class, the IL type, and the staticness of a field access. A
     * class-parented field is an instance field of a user class or a static field of one (the
     * `INSTANCE` field of an `object`), following [IrField.isStatic]; a file-parented field is
     * the static facade field of a top-level property. Every lookup goes through the
     * emission-scoped state, so field access to a class the emitter removed (or a field of a
     * type outside the supported set) aborts the surrounding render. The backing field of a
     * `const val` is never accessed on either owner shape: it is a CLR `literal` field without
     * storage (`ldsfld` would fail at runtime), and every read of the property is inlined by
     * the frontend.
     */
    private fun resolveFieldAccess(field: IrField): Triple<DotNetIlClassInfo, DotNetIlValueType, Boolean> {
        val fieldName = field.name.asString()
        if (field.correspondingPropertySymbol?.owner?.isConst == true) {
            dotNetUnsupported(
                "access to the backing field of const property '$fieldName' is not supported " +
                        "(const reads are inlined by the frontend)"
            )
        }
        val [classInfo, isStatic] = when (val parent = field.parent) {
            is IrClass -> {
                val classInfo = typeMapper.externalObjectInstanceOwnerInfoOrNull(field)
                    ?: typeMapper.classInfoOrNull(parent)
                    ?: dotNetUnsupported("access to a field of unsupported class '${parent.name.asString()}'")
                classInfo to field.isStatic
            }
            is IrFile -> {
                val classInfo = facadeClassInfoByFile[parent]
                    ?: dotNetUnsupported("access to top-level field '$fieldName' outside the compiled module is not supported")
                classInfo to true
            }
            else -> dotNetUnsupported("access to non-member field '$fieldName' is not supported")
        }
        val fieldType = typeMapper.toDotNetIlValueType(field.type)
            ?: dotNetUnsupported("field '$fieldName' has unsupported type ${field.type.render()}")
        return Triple(classInfo, fieldType, isStatic)
    }

    private fun emitFieldReceiver(receiver: IrExpression?, field: IrField, classInfo: DotNetIlClassInfo) {
        if (receiver == null) {
            dotNetUnsupported("receiverless access to instance field '${field.name.asString()}' is not supported")
        }
        emitExpression(receiver, DotNetIlValueType.UserClass(classInfo))
    }

    private fun emitCallExpression(call: IrCall, expectedType: DotNetIlValueType) {
        if (emitGenericInterfaceCapabilityCallOrNull(call, expectedType)) return
        if (emitCallableCapabilityCallOrNull(call, expectedType)) return
        val returnType = emitCall(call)
        val producedType = (returnType as? DotNetIlReturnType.Value)?.type
        if (
            producedType != null &&
            !producedType.isDotNetAssignableTo(expectedType) &&
            (call.symbol.owner.isDotNetErasedObjectResult() ||
                    call.symbol.owner.isErasedGenericInterfaceMember() ||
                    call.symbol.owner.isErasedGenericClassMember() ||
                    call.symbol.owner.hasErasedNullableTypeParameterResult())
        ) {
            // The stable physical carrier of an erased Kotlin type parameter is usually
            // object, but an upper bound such as `T : Marked` deliberately uses Marked.
            // Both require the same use-site narrowing when KLIB proves a more precise
            // logical construction (`Constrained<Token>.current(): Token`).
            emitErasedCarrierAs(expectedType, "${call.symbol.owner.name.asString()} result")
        } else if (producedType != null && !producedType.isDotNetAssignableTo(expectedType)) {
            val coercion = dotNetWideningCoercionOrNull(producedType, expectedType, coreLibraryReference)
                ?: dotNetUnsupported(
                    "call to '${call.symbol.owner.name.asString()}' produces ${returnType.nameInSignature} " +
                            "where ${expectedType.nameInSignature} is expected"
                )
            methodContext.emit(coercion, pops = 1, pushes = 1)
        } else if (producedType == null) {
            dotNetUnsupported(
                "call to '${call.symbol.owner.name.asString()}' produces ${returnType.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
    }

    private fun IrSimpleFunction.isErasedGenericInterfaceMember(): Boolean =
        (parent as? IrClass)?.let(typeMapper::isErasedGenericInterface) == true ||
                allOverridden().any { overridden ->
                    (overridden.parent as? IrClass)?.let(typeMapper::isErasedGenericInterface) == true
                }

    private fun IrSimpleFunction.isErasedGenericClassMember(): Boolean =
        (parent as? IrClass)?.let(typeMapper::isErasedGenericClass) == true ||
                allOverridden().any { overridden ->
                    (overridden.parent as? IrClass)?.let(typeMapper::isErasedGenericClass) == true
                }

    /** Whether this logical result is an open `T?` represented by the stable object carrier. */
    private fun IrSimpleFunction.hasErasedNullableTypeParameterResult(): Boolean {
        fun IrType.isNullableTypeParameter(): Boolean {
            val simpleType = this as? IrSimpleType ?: return false
            return simpleType.isMarkedNullable() && simpleType.classifier is IrTypeParameterSymbol
        }
        if (returnType.isNullableTypeParameter()) return true
        return allOverridden().any { overridden -> overridden.returnType.isNullableTypeParameter() }
    }

    /**
     * Uses an independently mapped typed host capability when the receiver can name it, then
     * falls back to the erased Kotlin slot. Ordinary Kotlin-owned generic interfaces have no
     * typed capability and take the erased route immediately. Receiver and arguments are each
     * evaluated exactly once; no adapter or identity substitution is involved.
     */
    private fun emitGenericInterfaceCapabilityCallOrNull(
        call: IrCall,
        expectedType: DotNetIlValueType?,
    ): Boolean {
        val callee = call.symbol.owner.let { it.resolveFakeOverride() ?: it.resolveFakeOverrideMaybeAbstract() ?: it }
        val interfaceClass = callee.parent as? IrClass ?: return false
        if (!typeMapper.isErasedGenericInterface(interfaceClass)) return false
        val receiver = call.arguments.firstOrNull() ?: return false
        val receiverType = receiver.type as? IrSimpleType ?: return false
        if ((receiverType.classifier as? IrClassSymbol)?.owner != interfaceClass) return false

        fun methodInstantiation(mapper: DotNetIlTypeMapper): List<DotNetIlValueType>? {
            if (callee.typeParameters.isEmpty()) return emptyList()
            if (call.typeArguments.size != callee.typeParameters.size) return null
            return call.typeArguments.map { argument ->
                argument?.let(mapper::toDotNetIlValueType) ?: return null
            }
        }
        val canonicalClassInfo = canonicalGenericSignatureTypeMapper.classInfoOrNull(interfaceClass)
            ?: return false
        val canonicalReceiverType = canonicalGenericSignatureTypeMapper.toDotNetIlValueType(receiver.type)
            ?: return false
        val canonicalMethodInstantiation = methodInstantiation(canonicalGenericSignatureTypeMapper)
            ?: return false
        val canonicalSignature = callee.dotNetSignature(canonicalGenericSignatureTypeMapper)
        val canonicalPhysicalMethodName =
            canonicalGenericSignatureTypeMapper.referencedFunctionInfoOrNull(callee)?.physicalMethodName
                ?: callee.dotNetGenericInterfaceCanonicalMethodName()
        val canonicalInfo = DotNetIlFunctionInfo(
            canonicalClassInfo,
            canonicalSignature,
            canonicalPhysicalMethodName,
        )
        val canonicalParameterTypes = canonicalSignature.parameterTypes.map { parameterType ->
            parameterType.substituteDotNetTypeParameters(emptyList(), canonicalMethodInstantiation)
        }
        val canonical = ResolvedCall(
            callee = callee,
            calleeName = callee.name.asString(),
            info = canonicalInfo,
            methodInstantiation = canonicalMethodInstantiation,
            receiverType = canonicalReceiverType,
            ownerToken = canonicalClassInfo.ilTypeRef,
            parameterTypes = canonicalParameterTypes,
            virtual = true,
            returnType = canonicalSignature.returnType.substituteDotNetTypeParameters(
                emptyList(),
                canonicalMethodInstantiation,
            ),
        )
        val memberView = typeMapper.genericInterfaceMemberView(callee, interfaceClass)
        val capabilitySignatureMapper = when (memberView) {
            DotNetGenericInterfaceMemberView.DECLARED -> declaredGenericSignatureTypeMapper
            DotNetGenericInterfaceMemberView.EXACT -> exactGenericSignatureTypeMapper
        }
        val capabilityReceiverType = try {
            typeMapper.genericInterfaceCapabilityTypeOrNull(
                receiver.type,
                memberView.physicalView,
            )
        } catch (_: DotNetIlUnsupportedException) {
            null
        } ?: return false
        val capabilityMethodInstantiation = methodInstantiation(capabilitySignatureMapper) ?: return false
        val capabilitySignature = callee.dotNetSignature(capabilitySignatureMapper)
        val capabilityParameterTypes = capabilitySignature.parameterTypes.map { parameterType ->
            parameterType.substituteDotNetTypeParameters(
                capabilityReceiverType.arguments,
                capabilityMethodInstantiation,
            )
        }
        if (call.arguments.size != capabilityParameterTypes.size || call.arguments.size != canonical.parameterTypes.size) {
            return false
        }
        val capabilityReturnType = capabilitySignature.returnType.substituteDotNetTypeParameters(
            capabilityReceiverType.arguments,
            capabilityMethodInstantiation,
        )
        val capabilityResultCoercion: DotNetOptionalInstruction?
        val canonicalResultInstruction: String?
        if (expectedType == null) {
            if (capabilityReturnType != DotNetIlReturnType.Void || canonical.returnType != DotNetIlReturnType.Void) {
                return false
            }
            capabilityResultCoercion = null
            canonicalResultInstruction = null
        } else {
            val capabilityResultType = (capabilityReturnType as? DotNetIlReturnType.Value)?.type ?: return false
            val canonicalResultType = (canonical.returnType as? DotNetIlReturnType.Value)?.type ?: return false
            capabilityResultCoercion = wideningCoercionOrNull(capabilityResultType, expectedType) ?: return false
            canonicalResultInstruction = when {
                canonicalResultType.isDotNetAssignableTo(expectedType) -> null
                canonicalResultType == DotNetIlValueType.Object ->
                    expectedType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference) ?: return false
                else -> wideningCoercionOrNull(canonicalResultType, expectedType)?.instruction ?: return false
            }
        }

        emitExpression(receiver, canonicalReceiverType)
        val receiverSlot = spillToSyntheticLocal(canonicalReceiverType, "<genericInterfaceReceiver>")
        val capabilitySlot = methodContext.declareSyntheticLocal(
            capabilityReceiverType,
            "<genericInterfaceCapability>",
        )
        val resultSlot = expectedType?.let { resultType ->
            methodContext.declareSyntheticLocal(resultType, "<genericInterfaceResult>")
        }
        val fallbackLabel = methodContext.nextLabel("genericInterfaceErasedFallback")
        val joinLabel = methodContext.nextLabel("genericInterfaceJoin")

        methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
        methodContext.emit("isinst ${capabilityReceiverType.nameInSignature}", pops = 1, pushes = 1)
        methodContext.emit(storeLocalInstruction(capabilitySlot.index), pops = 1)
        methodContext.emit(loadLocalInstruction(capabilitySlot.index), pushes = 1)
        methodContext.emitBranch("brfalse", fallbackLabel, pops = 1)

        methodContext.emit(loadLocalInstruction(capabilitySlot.index), pushes = 1)
        emitArguments(
            call.arguments.drop(1),
            capabilityParameterTypes.drop(1),
            "typed generic-interface call to '${callee.name.asString()}'",
        )
        val capabilityInfo = DotNetIlFunctionInfo(capabilityReceiverType.classInfo, capabilitySignature)
        methodContext.emit(
            capabilityInfo.renderCallInstruction(
                typeMapper.genericInterfaceTypedMethodName(callee),
                virtual = true,
                ownerToken = capabilityReceiverType.nameInSignature,
                methodInstantiation = capabilityMethodInstantiation,
            ),
            pops = capabilitySignature.parameterTypes.size,
            pushes = if (capabilityReturnType is DotNetIlReturnType.Value) 1 else 0,
        )
        capabilityResultCoercion?.instruction?.let { instruction ->
            methodContext.emit(instruction, pops = 1, pushes = 1)
        }
        resultSlot?.let { slot -> methodContext.emit(storeLocalInstruction(slot.index), pops = 1) }
        methodContext.emitGoto(joinLabel)

        methodContext.emitLabel(fallbackLabel)
        methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
        emitArguments(
            call.arguments.drop(1),
            canonical.parameterTypes.drop(1),
            "canonical generic-interface call to '${callee.name.asString()}'",
        )
        methodContext.emit(
            canonical.info.renderCallInstruction(
                canonical.info.physicalMethodName ?: callee.dotNetIlMethodName(),
                virtual = true,
                ownerToken = canonical.ownerToken,
                methodInstantiation = canonical.methodInstantiation,
            ),
            pops = canonical.info.signature.parameterTypes.size,
            pushes = if (canonical.returnType is DotNetIlReturnType.Value) 1 else 0,
        )
        canonicalResultInstruction?.let { instruction ->
            methodContext.emit(instruction, pops = 1, pushes = 1)
        }
        resultSlot?.let { slot -> methodContext.emit(storeLocalInstruction(slot.index), pops = 1) }

        methodContext.emitLabel(joinLabel)
        resultSlot?.let { slot -> methodContext.emit(loadLocalInstruction(slot.index), pushes = 1) }
        return true
    }

    /**
     * Calls an optional execution capability of a Kotlin FunctionN, otherwise falls back to the
     * stable object-shaped Invoke slot. An object-result call with a concrete primitive argument
     * tries the benchmarked TypedArgumentsFunctionN view first; other calls begin with the exact
     * call-site shape. For an immutable local alias, the initializer chain can retain a narrower
     * source function type after Kotlin variance widened the local's view; that original exact
     * shape follows and its arguments/result are widened explicitly.
     *
     * The receiver and every argument are evaluated once into locals before any runtime test,
     * so every branch preserves source order and side effects. An older module or an explicit
     * user implementation simply fails every `isinst` and takes the erased path.
     */
    private fun emitCallableCapabilityCallOrNull(call: IrCall, expectedType: DotNetIlValueType): Boolean {
        if (!call.symbol.owner.isDotNetErasedCallableInvoke()) return false
        val receiver = call.arguments.firstOrNull() ?: return false
        val receiverType = receiver.type as? IrSimpleType ?: return false
        val receiverClass = (receiverType.classifier as? IrClassSymbol)?.owner ?: return false
        val arity = receiverClass.dotNetFixedFunctionArityOrNull() ?: return false
        if (call.arguments.size != arity + 1 || receiverType.arguments.size != arity + 1) return false
        val callArguments = (0 until arity).map { index ->
            call.arguments[index + 1] ?: return false
        }

        val logicalIrTypes = receiverType.arguments.map { argument ->
            (argument as? IrTypeProjection)?.type ?: return false
        }
        // A default FunctionN<P..., R> view introduced only as a KFunction execution cast still
        // contains the interface's own uninstantiated type parameters. It is not an exact static
        // shape; the erased path remains correct until that lowering preserves its arguments.
        if (logicalIrTypes.any { type ->
                val classifier = (type as? IrSimpleType)?.classifier
                classifier is IrTypeParameterSymbol &&
                        classifier.owner.parent == receiverClass
            }
        ) {
            return false
        }

        val logicalTypes = logicalIrTypes.map { type ->
            typeMapper.toDotNetIlValueType(type) ?: return false
        }
        val resultType = logicalTypes.last()
        if (resultType != expectedType && !resultType.isDotNetAssignableTo(expectedType)) return false
        val callSiteShape = exactCallableInvocationShapeOrNull(logicalTypes, logicalTypes) ?: return false
        val invocationShapes = mutableListOf(callSiteShape)
        receiver.callableProvenanceTypeOrNull(arity)
            ?.dotNetExactCallableLogicalTypesOrNull(arity)
            ?.toDotNetIlValueTypesOrNull()
            ?.let { provenanceTypes -> exactCallableInvocationShapeOrNull(logicalTypes, provenanceTypes) }
            ?.takeUnless { provenanceShape ->
                invocationShapes.any { existingShape ->
                    provenanceShape.exactType.isDotNetAssignableTo(existingShape.exactType)
                }
            }
            ?.let(invocationShapes::add)
        val typedArgumentsType = typedArgumentsCallableInvocationTypeOrNull(logicalTypes)
        val erasedReceiverType = DotNetRuntimeTypes.fixedFunctionType(arity)

        emitExpression(receiver, erasedReceiverType)
        val receiverSlot = spillToSyntheticLocal(erasedReceiverType, "<callableReceiver>")
        val argumentSlots = callArguments.mapIndexed { index, argument ->
            val parameterType = logicalTypes[index]
            emitExpression(argument, parameterType)
            spillToSyntheticLocal(parameterType, "<callableArgument>")
        }

        val fallbackLabel = methodContext.nextLabel("callableErasedFallback")
        val joinLabel = methodContext.nextLabel("callableExactEnd")
        var resultSlot: DotNetIlSlot.Local? = null
        if (typedArgumentsType != null) {
            methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
            methodContext.emit("isinst ${typedArgumentsType.nameInSignature}", pops = 1, pushes = 1)
            val typedReceiverSlot = spillToSyntheticLocal(typedArgumentsType, "<typedArgumentsCallable>")
            val typedResultSlot = methodContext.declareSyntheticLocal(resultType, "<callableResult>")
            resultSlot = typedResultSlot
            val firstExactLabel = methodContext.nextLabel("callableExactFallback")
            methodContext.emit(loadLocalInstruction(typedReceiverSlot.index), pushes = 1)
            methodContext.emitBranch("brfalse", firstExactLabel, pops = 1)
            methodContext.emit(loadLocalInstruction(typedReceiverSlot.index), pushes = 1)
            argumentSlots.forEach { slot ->
                methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
            }
            methodContext.emit(
                DotNetRuntimeTypes.typedArgumentsInvokeCallInstruction(typedArgumentsType),
                pops = arity + 1,
                pushes = 1,
            )
            methodContext.emit(storeLocalInstruction(typedResultSlot.index), pops = 1)
            methodContext.emitGoto(joinLabel)
            methodContext.emitLabel(firstExactLabel)
        }
        invocationShapes.forEachIndexed { shapeIndex, shape ->
            methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
            methodContext.emit("isinst ${shape.exactType.nameInSignature}", pops = 1, pushes = 1)
            val exactReceiverSlot = spillToSyntheticLocal(shape.exactType, "<exactCallable>")
            val exactResultSlot = resultSlot
                ?: methodContext.declareSyntheticLocal(resultType, "<callableResult>").also { resultSlot = it }
            val nextShapeLabel = if (shapeIndex == invocationShapes.lastIndex) {
                fallbackLabel
            } else {
                methodContext.nextLabel("callableExactShapeFallback")
            }
            methodContext.emit(loadLocalInstruction(exactReceiverSlot.index), pushes = 1)
            methodContext.emitBranch("brfalse", nextShapeLabel, pops = 1)

            methodContext.emit(loadLocalInstruction(exactReceiverSlot.index), pushes = 1)
            argumentSlots.forEachIndexed { index, slot ->
                methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
                shape.argumentCoercions[index]?.let { instruction ->
                    methodContext.emit(instruction, pops = 1, pushes = 1)
                }
            }
            methodContext.emit(
                DotNetRuntimeTypes.exactInvokeCallInstruction(shape.exactType),
                pops = arity + 1,
                pushes = 1,
            )
            shape.resultCoercion?.let { instruction ->
                methodContext.emit(instruction, pops = 1, pushes = 1)
            }
            methodContext.emit(storeLocalInstruction(exactResultSlot.index), pops = 1)
            methodContext.emitGoto(joinLabel)
            if (nextShapeLabel != fallbackLabel) {
                methodContext.emitLabel(nextShapeLabel)
            }
        }
        val callableResultSlot = resultSlot
            ?: error("Internal .NET backend error: callable capability invocation has no result slot")

        methodContext.emitLabel(fallbackLabel)
        methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
        argumentSlots.zip(logicalTypes.take(arity)).forEach { entry ->
            val slot = entry.first
            val parameterType = entry.second
            methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
            if (!parameterType.isDotNetAssignableTo(DotNetIlValueType.Object)) {
                val coercion = dotNetWideningCoercionOrNull(
                    parameterType,
                    DotNetIlValueType.Object,
                    coreLibraryReference,
                )
                    ?: dotNetUnsupported(
                        "callable argument cannot be converted from ${parameterType.nameInSignature} to object"
                    )
                methodContext.emit(coercion, pops = 1, pushes = 1)
            }
        }
        methodContext.emit(
            DotNetRuntimeTypes.erasedInvokeCallInstruction(arity),
            pops = arity + 1,
            pushes = 1,
        )
        emitErasedCarrierAs(resultType, "callable result")
        methodContext.emit(storeLocalInstruction(callableResultSlot.index), pops = 1)

        methodContext.emitLabel(joinLabel)
        methodContext.emit(loadLocalInstruction(callableResultSlot.index), pushes = 1)
        return true
    }

    /**
     * The partial capability is worth probing only for the evidenced CLR variance hole: an
     * object-shaped result and at least one concrete primitive-shaped argument. Exact primitive
     * results keep the existing ExactFunctionN-first path; Unit remains erased-only.
     */
    private fun typedArgumentsCallableInvocationTypeOrNull(
        logicalTypes: List<DotNetIlValueType>,
    ): DotNetIlValueType.GenericInstance? {
        val arity = logicalTypes.size - 1
        if (arity !in 1..2 || logicalTypes.last() != DotNetIlValueType.Object) return null
        val parameterTypes = logicalTypes.take(arity)
        if (parameterTypes.none { it.isDotNetConcretePrimitiveShape() }) return null
        return DotNetRuntimeTypes.typedArgumentsFunctionType(parameterTypes)
    }

    private fun DotNetIlValueType.isDotNetConcretePrimitiveShape(): Boolean =
        isSupportedPrimitiveArrayElement() || this is DotNetIlValueType.NullableValue

    private fun exactCallableInvocationShapeOrNull(
        callSiteTypes: List<DotNetIlValueType>,
        capabilityTypes: List<DotNetIlValueType>,
    ): DotNetExactCallableInvocationShape? {
        if (callSiteTypes.size != capabilityTypes.size) return null
        val exactType = DotNetRuntimeTypes.exactFunctionType(capabilityTypes) ?: return null
        val arity = capabilityTypes.size - 1
        val argumentCoercions = (0 until arity).map { index ->
            val coercion = wideningCoercionOrNull(callSiteTypes[index], capabilityTypes[index]) ?: return null
            coercion.instruction
        }
        val resultCoercion = wideningCoercionOrNull(capabilityTypes.last(), callSiteTypes.last()) ?: return null
        return DotNetExactCallableInvocationShape(exactType, argumentCoercions, resultCoercion.instruction)
    }

    private fun List<IrType>.toDotNetIlValueTypesOrNull(): List<DotNetIlValueType>? = map { type ->
        typeMapper.toDotNetIlValueType(type) ?: return null
    }

    /**
     * `null` means the widening is impossible; a present wrapper may contain no instruction for
     * an assignable CLR shape. Keeping those cases distinct lets a candidate exact interface be
     * rejected before any IL is emitted.
     */
    private fun wideningCoercionOrNull(
        from: DotNetIlValueType,
        to: DotNetIlValueType,
    ): DotNetOptionalInstruction? = when {
        from.isDotNetAssignableTo(to) -> DotNetOptionalInstruction(null)
        else -> dotNetWideningCoercionOrNull(from, to, coreLibraryReference)?.let(::DotNetOptionalInstruction)
    }

    /** The deepest fixed FunctionN/KFunctionN type retained by an immutable initializer chain. */
    private fun IrExpression.callableProvenanceTypeOrNull(
        arity: Int,
        visitedVariables: MutableSet<IrVariable> = hashSetOf(),
    ): IrSimpleType? {
        val localCandidate = (type as? IrSimpleType)?.takeIf { candidate ->
            candidate.dotNetCallableArityOrNull() == arity
        }
        val nested = when (this) {
            is IrGetValue -> {
                val variable = symbol.owner as? IrVariable
                if (variable == null || variable.isVar || !visitedVariables.add(variable)) null
                else variable.initializer?.callableProvenanceTypeOrNull(arity, visitedVariables)
            }
            is IrTypeOperatorCall -> argument.callableProvenanceTypeOrNull(arity, visitedVariables)
            else -> null
        }
        return nested ?: localCandidate
    }

    private fun IrSimpleType.dotNetCallableArityOrNull(): Int? {
        val irClass = (classifier as? IrClassSymbol)?.owner ?: return null
        return irClass.dotNetFixedFunctionArityOrNull() ?: irClass.dotNetFixedKFunctionArityOrNull()
    }

    private fun IrSimpleType.dotNetExactCallableLogicalTypesOrNull(arity: Int): List<IrType>? {
        val receiverClass = (classifier as? IrClassSymbol)?.owner ?: return null
        if (dotNetCallableArityOrNull() != arity || arguments.size != arity + 1) return null
        val logicalTypes = arguments.map { argument ->
            (argument as? IrTypeProjection)?.type ?: return null
        }
        if (logicalTypes.any { type ->
                val classifier = (type as? IrSimpleType)?.classifier
                classifier is IrTypeParameterSymbol && classifier.owner.parent == receiverClass
            }
        ) {
            return null
        }
        return logicalTypes
    }

    private data class DotNetExactCallableInvocationShape(
        val exactType: DotNetIlValueType.GenericInstance,
        val argumentCoercions: List<String?>,
        val resultCoercion: String?,
    )

    private data class DotNetOptionalInstruction(
        val instruction: String?,
    )

    /** Narrows an erased runtime slot result from its stable carrier to the logical Kotlin type. */
    private fun emitErasedCarrierAs(expectedType: DotNetIlValueType, description: String) {
        if (expectedType == DotNetIlValueType.Object) return
        val instruction = expectedType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference)
            ?: dotNetUnsupported(
                "erased $description cannot be narrowed to ${expectedType.nameInSignature}"
            )
        methodContext.emit(instruction, pops = 1, pushes = 1)
    }

    private fun emitConstant(expression: IrConst, expectedType: DotNetIlValueType) {
        when (expectedType) {
            DotNetIlValueType.Boolean -> {
                val value = expression.value as? Boolean
                    ?: dotNetUnsupported("unsupported bool constant: ${expression.value}")
                methodContext.emit("ldc.i4.${if (value) "1" else "0"}", pushes = 1)
            }
            DotNetIlValueType.Int8 -> {
                val value = expression.value as? Byte
                    ?: dotNetUnsupported("unsupported int8 constant: ${expression.value}")
                methodContext.emit("ldc.i4 ${value.toInt()}", pushes = 1)
            }
            DotNetIlValueType.Int16 -> {
                val value = expression.value as? Short
                    ?: dotNetUnsupported("unsupported int16 constant: ${expression.value}")
                methodContext.emit("ldc.i4 ${value.toInt()}", pushes = 1)
            }
            DotNetIlValueType.Int32 -> {
                val value = expression.value as? Int
                    ?: dotNetUnsupported("unsupported int32 constant: ${expression.value}")
                methodContext.emit("ldc.i4 $value", pushes = 1)
            }
            DotNetIlValueType.Int64 -> {
                val value = expression.value as? Long
                    ?: dotNetUnsupported("unsupported int64 constant: ${expression.value}")
                // ilasm accepts the full signed range directly, including Long.MIN_VALUE.
                methodContext.emit("ldc.i8 $value", pushes = 1)
            }
            DotNetIlValueType.Float32 -> {
                val value = expression.value as? Float
                    ?: dotNetUnsupported("unsupported float32 constant: ${expression.value}")
                methodContext.emit("ldc.r4 ${value.toIlFloat32Literal()}", pushes = 1)
            }
            DotNetIlValueType.Float64 -> {
                val value = expression.value as? Double
                    ?: dotNetUnsupported("unsupported float64 constant: ${expression.value}")
                methodContext.emit("ldc.r8 ${value.toIlFloat64Literal()}", pushes = 1)
            }
            DotNetIlValueType.Char -> {
                val value = expression.value as? Char
                    ?: dotNetUnsupported("unsupported char constant: ${expression.value}")
                // Like the JVM backend, a char constant is its UTF-16 code unit on the int stack.
                methodContext.emit("ldc.i4 ${value.code}", pushes = 1)
            }
            DotNetIlValueType.String -> when (val value = expression.value) {
                null -> methodContext.emit("ldnull", pushes = 1)
                is String -> methodContext.emit("ldstr ${value.toIlStringLiteral()}", pushes = 1)
                else -> dotNetUnsupported("unsupported string constant: $value")
            }
            // The only class-typed constant is `null` (class references have no other literals).
            is DotNetIlValueType.UserClass -> when (expression.value) {
                null -> methodContext.emit("ldnull", pushes = 1)
                else -> dotNetUnsupported("unsupported ${expectedType.nameInSignature} constant: ${expression.value}")
            }
            is DotNetIlValueType.MappedClass -> when (expression.value) {
                null -> methodContext.emit("ldnull", pushes = 1)
                else -> dotNetUnsupported("unsupported ${expectedType.nameInSignature} constant: ${expression.value}")
            }
            // A constant in a Nullable<T> position: `null` is the empty value (`initobj` through
            // an addressed temp — a value type has no ldnull); any other constant is the element
            // constant wrapped by the Nullable ctor (both spellings probe-verified, boxprobe_s1).
            // Non-null constants normally arrive pre-wrapped by the coercion interception in
            // emitExpression (the constant's own type is the plain primitive); this arm covers
            // constants whose IR type is already the nullable one.
            is DotNetIlValueType.NullableValue -> when (expression.value) {
                null -> emitEmptyNullable(expectedType)
                else -> {
                    emitConstant(expression, expectedType.elementType)
                    methodContext.emit(expectedType.ctorInstruction, pops = 1, pushes = 1)
                }
            }
            // An object-typed (`Any?`) constant: only `null` lands here — reference constants
            // (strings) are emitted at their own type by the interception in emitExpression
            // (free widening), and primitive constants arrive boxed by the same interception.
            DotNetIlValueType.Object -> when (expression.value) {
                null -> methodContext.emit("ldnull", pushes = 1)
                else -> dotNetUnsupported("unsupported ${expectedType.nameInSignature} constant: ${expression.value}")
            }
            // A genuinely instantiated CLR generic is an ordinary reference type: `null` is its
            // only constant, like UserClass above.
            is DotNetIlValueType.GenericInstance -> when (expression.value) {
                null -> methodContext.emit("ldnull", pushes = 1)
                else -> dotNetUnsupported("unsupported ${expectedType.nameInSignature} constant: ${expression.value}")
            }
            // A primitive array is an ordinary CLR reference: its only literal is null.
            is DotNetIlValueType.PrimitiveArray -> when (expression.value) {
                null -> methodContext.emit("ldnull", pushes = 1)
                else -> dotNetUnsupported("unsupported ${expectedType.nameInSignature} constant: ${expression.value}")
            }
            // A generic array is likewise an ordinary CLR reference with only the null literal.
            is DotNetIlValueType.GenericArray -> when (expression.value) {
                null -> methodContext.emit("ldnull", pushes = 1)
                else -> dotNetUnsupported("unsupported ${expectedType.nameInSignature} constant: ${expression.value}")
            }
            is DotNetIlValueType.ErasedGenericArray -> when (expression.value) {
                null -> methodContext.emit("ldnull", pushes = 1)
                else -> dotNetUnsupported("unsupported ${expectedType.nameInSignature} constant: ${expression.value}")
            }
            // No constant has a type-parameter type (`T?`/null is rejected at the type mapper
            // and every value constant maps to its concrete type first); defensive.
            is DotNetIlValueType.TypeParameter ->
                dotNetUnsupported("constant in a type-parameter-typed position is not supported")
        }
    }

    private fun emitGetValue(expression: IrGetValue, expectedType: DotNetIlValueType) {
        val slot = methodContext.reference(expression.symbol)
        val slotType = slot.type
        if (!slotType.isDotNetAssignableTo(expectedType)) {
            if (slotType == DotNetIlValueType.Object && methodContext.isErasedRuntimeParameter(expression.symbol)) {
                val instruction = expectedType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference)
                    ?: dotNetUnsupported(
                        "erased runtime parameter '${expression.symbol.owner.name.asString()}' cannot be converted " +
                                "from object to ${expectedType.nameInSignature}"
                    )
                emitLoadSlot(slot)
                methodContext.emit(instruction, pops = 1, pushes = 1)
                return
            }
            // A canonical generic-interface result can be stored in an object local while a
            // concrete consumer later requires the enclosing function's open T. Materialize
            // precisely that erased local-read boundary with `unbox.any !n/!!n`: ECMA-335 defines
            // it for both reference and value substitutions. Restricting the rule to local slots
            // and open type-parameter consumers prevents an object-to-arbitrary-type escape hatch.
            // A corrupt value therefore still fails at first use.
            if (slotType == DotNetIlValueType.Object &&
                expectedType is DotNetIlValueType.TypeParameter &&
                expression.symbol.owner is IrVariable
            ) {
                emitLoadSlot(slot)
                methodContext.emit(
                    "unbox.any ${expectedType.nameInSignature}",
                    pops = 1,
                    pushes = 1,
                )
                return
            }
            // A NARROWED read of a nullable-primitive slot: the frontend types a null-test-
            // narrowed access at the element type WITHOUT a cast node — the elvis/safe-call
            // temporary in its non-null branch is the canonical shape (`tmp0_elvis_lhs` read as
            // `Int` from an `Int?` slot). The value is loaded as the Nullable it is and unwrapped
            // with the same checked extraction as the IMPLICIT_CAST smartcast unwrap (JVM
            // precedent: the narrowed read is an unboxing `intValue()` there, NPE on null).
            if (slotType is DotNetIlValueType.NullableValue &&
                slotType.elementType.isDotNetAssignableTo(expectedType)
            ) {
                emitLoadSlot(slot)
                emitNullableUnwrapOrThrowNpe(slotType)
                return
            }
            // A REFERENCE smartcast can likewise narrow a bare local read without an
            // IrTypeOperatorCall. The frontend proof changes the IrGetValue type, but the CLR
            // local necessarily keeps its declared wider type. A checked `castclass` is the
            // verifier-visible form of that proof (and preserves Kotlin's failure mode if an
            // unsound upstream smartcast ever reaches this backend).
            if (slotType.isDotNetReferenceShaped() &&
                expectedType.isDotNetReferenceShaped() &&
                expectedType.isDotNetAssignableTo(slotType)
            ) {
                emitLoadSlot(slot)
                methodContext.emit("castclass ${expectedType.nameInSignature}", pops = 1, pushes = 1)
                return
            }
            dotNetUnsupported(
                "value '${expression.symbol.owner.name.asString()}' has type ${slotType.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
        emitLoadSlot(slot)
    }

    private fun emitLoadSlot(slot: DotNetIlSlot) {
        when (slot) {
            is DotNetIlSlot.Parameter -> methodContext.emit(loadArgumentInstruction(slot.index), pushes = 1)
            is DotNetIlSlot.Local -> methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
        }
    }

    private fun emitWhenExpression(expression: IrWhen, expectedType: DotNetIlValueType) {
        val endLabel = methodContext.nextLabel("whenEnd")
        var hasElse = false

        for (branch in expression.branches) {
            if (branch.condition.isFalseConst()) continue

            if (branch.condition.isTrueConst()) {
                emitExpression(branch.result, expectedType)
                hasElse = true
                break
            }

            val nextBranchLabel = methodContext.nextLabel("whenNext")
            emitBranchIfFalse(branch.condition, nextBranchLabel)
            emitExpression(branch.result, expectedType)
            methodContext.emitGoto(endLabel)
            methodContext.emitLabel(nextBranchLabel)
        }

        if (!hasElse) {
            dotNetUnsupported("when expression without an else branch")
        }

        methodContext.emitLabel(endLabel)
    }

    private fun emitNullStringAsStringLiteral() {
        val notNullLabel = methodContext.nextLabel("stringValueNotNull")
        methodContext.emit("dup", pops = 1, pushes = 2)
        methodContext.emitBranch("brtrue", notNullLabel, pops = 1)
        methodContext.emit("pop", pops = 1)
        methodContext.emit("ldstr ${"null".toIlStringLiteral()}", pushes = 1)
        methodContext.emitLabel(notNullLabel)
    }
}

internal fun loadArgumentInstruction(index: Int): String =
    if (index in 0..3) "ldarg.$index" else "ldarg $index"

/** Stores a generated mutable parameter, currently the masked-default stub's resolved value. */
internal fun storeArgumentInstruction(index: Int): String = "starg $index"

internal fun loadLocalInstruction(index: Int): String =
    if (index in 0..3) "ldloc.$index" else "ldloc $index"

/**
 * Loads the ADDRESS of a local slot — the home address a `Nullable<T>` instance-member call
 * requires (see [DotNetIlValueType.NullableValue]). `ldloca` has no short `.N` forms; the plain
 * numeric-operand spelling is probe-verified (`nullprobe_s8`).
 */
internal fun loadLocalAddressInstruction(index: Int): String = "ldloca $index"

internal fun storeLocalInstruction(index: Int): String =
    if (index in 0..3) "stloc.$index" else "stloc $index"

/**
 * The single IL instruction of a WIDENING conversion of the hybrid nullability model, or null
 * when no such conversion exists (instruction-free widenings live in [isDotNetAssignableTo];
 * narrowings only exist as explicit cast/`!!` shapes). Each pops 1, pushes 1:
 * - `T -> T?`: `newobj Nullable<T>::.ctor(!0)` (boxprobe_s1);
 * - `T? -> Any?`: `box Nullable<T>` — the CLR collapses the result to boxed-`T`-or-null
 *   (boxprobe_s3, all five instantiations incl. the empty->null case nullprobe_s8);
 * - `T -> Any?` for plain primitives: `box <boxed T>` (nullprobe_s8).
 * - a built-in scalar/String carrier to Common Comparable's canonical `System.IComparable`
 *   view: exact primitive boxing or a checked same-object String interface view;
 * - constrained `!n`/`!!n -> bound/object`: `box !n`/`!!n`; this is a no-allocation identity
 *   conversion for reference instantiations and remains correct for an external value-type
 *   implementation of an interface bound (genconstraintprobe_s2).
 * Roslyn precedent: C# performs exactly these conversions implicitly at typed/object boundaries;
 * JVM precedent: the JVM backend's StackValue boxing coercions.
 * A `Kotlin.Nothing -> R` reference cast is the physical realization of Kotlin bottom-type
 * widening for exact-capability results; it is deliberately not modeled as CLR assignability.
 */
internal fun dotNetWideningCoercionOrNull(
    from: DotNetIlValueType,
    to: DotNetIlValueType,
    coreLibraryReference: String,
): String? = when {
    to is DotNetIlValueType.NullableValue && from == to.elementType -> to.ctorInstruction
    from == DotNetRuntimeTypes.nothingType && to.isDotNetReferenceShaped() ->
        "castclass ${to.nameInSignature}"
    to == DotNetIlValueType.Object && from is DotNetIlValueType.NullableValue -> from.boxInstruction
    from is DotNetIlValueType.TypeParameter && (to == DotNetIlValueType.Object || from.isConstrainedTo(to)) ->
        "box ${from.nameInSignature}"
    to.isDotNetCanonicalComparable(coreLibraryReference) && from == DotNetIlValueType.String ->
        "castclass ${to.nameInSignature}"
    to.isDotNetCanonicalComparable(coreLibraryReference) ->
        from.dotNetBoxedCorelibRefOrNull(coreLibraryReference)?.let { "box $it" }
    to == DotNetIlValueType.Object -> from.dotNetBoxedCorelibRefOrNull(coreLibraryReference)?.let { "box $it" }
    else -> null
}

private fun DotNetIlValueType.isDotNetCanonicalComparable(coreLibraryReference: String): Boolean =
    this is DotNetIlValueType.UserClass &&
            classInfo.ilClassName == "System.IComparable" &&
            classInfo.assemblyName == coreLibraryReference.removePrefix("[").removeSuffix("]")

/** The CLR conversion from an erased runtime object slot to one supported logical value type. */
internal fun DotNetIlValueType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference: String): String? {
    dotNetBoxedCorelibRefOrNull(coreLibraryReference)?.let { return "unbox.any $it" }
    return when (this) {
        DotNetIlValueType.Object -> null
        DotNetIlValueType.String -> "castclass ${coreLibraryReference}System.String"
        is DotNetIlValueType.NullableValue,
        is DotNetIlValueType.TypeParameter,
            -> "unbox.any $nameInSignature"
        is DotNetIlValueType.UserClass -> "castclass ${classInfo.ilTypeRef}"
        is DotNetIlValueType.MappedClass -> "castclass $ilTypeRef"
        is DotNetIlValueType.GenericInstance,
        is DotNetIlValueType.PrimitiveArray,
        is DotNetIlValueType.GenericArray,
        is DotNetIlValueType.ErasedGenericArray,
            -> "castclass $nameInSignature"
        else -> null
    }
}
