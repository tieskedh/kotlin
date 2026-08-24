package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_INTERFACE_DEFAULT_EXACT_CALL
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_VALUE_CLASS_UNBOX_HELPER
import org.jetbrains.kotlin.backend.dotnet.serialization.DotNetIrMangler
import org.jetbrains.kotlin.builtins.functions.BuiltInFunctionArity
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrBreakContinue
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetClass
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isNullableNothing
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.erasedUpperBound
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isFalseConst
import org.jetbrains.kotlin.ir.util.isAnonymousObject
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isNullConst
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isOriginallyLocalDeclaration
import org.jetbrains.kotlin.ir.util.isKSuspendFunction
import org.jetbrains.kotlin.ir.util.isTrueConst
import org.jetbrains.kotlin.ir.util.isSuspendFunction
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.FqName

private val DOTNET_VOLATILE_MARKER_FQ_NAME = FqName("kotlin.concurrent.Volatile")

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

    /** A branch value which may transfer control instead of producing a physical value. */
    fun emitControlFlowValueExpression(expression: IrExpression, expectedType: DotNetIlValueType)

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
    private val genericOwnerCapabilitySlots: Map<IrSimpleFunction, IrSimpleFunction> = emptyMap(),
) {
    internal val coreLibraryProfile: DotNetCoreLibraryProfile
        get() = typeMapper.coreLibrary
    internal val coreLibraryReference = typeMapper.coreLibrary.reference
    internal val stdlibAssemblyName: String?
        get() = typeMapper.stdlibAssemblyName
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

    private fun emitWideningCoercion(coercion: DotNetIlWideningCoercion) {
        coercion.instructions.forEach { instruction ->
            methodContext.emit(instruction, pops = 1, pushes = 1)
        }
    }

    fun recordAssemblyReference(assemblyName: String) {
        typeMapper.recordAssemblyReference(assemblyName)
    }

    /**
     * Maps [type] through the emission-scoped [DotNetIlTypeMapper]; null when the type has no IL
     * mapping. Exposed so intrinsics can dispatch on operand and parameter types.
     */
    fun toDotNetIlValueType(type: IrType): DotNetIlValueType? = typeMapper.toDotNetIlValueType(type)

    /** Maps an element/argument whose identity is reified by a CLR generic construction. */
    fun toDotNetIlGenericArgumentType(type: IrType): DotNetIlValueType? =
        typeMapper.toDotNetIlGenericArgumentType(type)

    fun permitsErasedGenericArrayElementWrite(receiver: IrExpression, value: IrExpression): Boolean {
        if (typeMapper.permitsErasedGenericArrayElementWrite(receiver.type)) return true
        val nullableElementType = receiver.type.dotNetInvariantArrayElementTypeOrNull()
                as? IrSimpleType ?: return false
        val elementParameter = nullableElementType.classifier as? IrTypeParameterSymbol ?: return false
        val variable = (receiver as? IrGetValue)?.symbol?.owner as? IrVariable ?: return false
        if (variable.isVar || variable.initializer == null) return false
        val valueType = value.type as? IrSimpleType ?: return false
        return methodContext.reference(variable.symbol).type is DotNetIlValueType.ErasedGenericArray &&
                valueType.classifier == elementParameter && !valueType.isMarkedNullable()
    }

    /** The physical vector view of an expression, including a proven local carrier override. */
    fun genericArrayPhysicalType(expression: IrExpression): DotNetIlValueType? {
        if (!expression.type.isDotNetGenericArray()) return null
        if (expression is IrGetValue) {
            val slotType = methodContext.reference(expression.symbol).type
            if (slotType is DotNetIlValueType.GenericArray ||
                slotType is DotNetIlValueType.ErasedGenericArray
            ) {
                return slotType
            }
        }
        return typeMapper.toDotNetIlValueType(expression.type)
    }

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
    fun mappedNaturalType(expression: IrExpression): DotNetIlValueType? {
        if (expression is IrTypeOperatorCall && expression.operator == IrTypeOperator.REINTERPRET_CAST) {
            val sourceValueClassCarrier =
                typeMapper.genericOwnerValueClassCarrierTypeOrNull(expression.argument.type)
            val targetValueClassCarrier =
                typeMapper.genericOwnerValueClassCarrierTypeOrNull(expression.typeOperand)
            val operandCarrier = mappedNaturalType(expression.argument)
            if (sourceValueClassCarrier != null &&
                operandCarrier == sourceValueClassCarrier
            ) {
                return sourceValueClassCarrier
            }
            if (targetValueClassCarrier != null && operandCarrier == targetValueClassCarrier) {
                return targetValueClassCarrier
            }
            val valueClass = expression.typeOperand.dotNetValueClassOrNull()
            if (valueClass != null &&
                expression.typeOperand.referencesTypeParameterOf(valueClass) &&
                mappedNaturalType(expression.argument) == DotNetIlValueType.Object
            ) {
                // Common's nominal generic-value-class getter reinterprets its backing value as
                // the logical value class. That owner intentionally has no CLR GenericParams,
                // so an invariant underlying C<T> is stored as object, never as fabricated
                // C<object>. A concrete IC<Int> or a helper-owned !!T does not reference the
                // value-class declaration parameter and therefore keeps its exact C<int>/C<!!T>
                // carrier through the ordinary path.
                return DotNetIlValueType.Object
            }
        }
        if (expression is IrTypeOperatorCall &&
            (expression.operator == IrTypeOperator.CAST ||
                    expression.operator == IrTypeOperator.SAFE_CAST)
        ) {
            typeMapper.genericOwnerNaturalRuntimeClassifierInfoOrNull(expression.typeOperand)
                ?.let { return DotNetIlValueType.Object }
        }
        if (expression is IrTypeOperatorCall &&
            (expression.operator == IrTypeOperator.IMPLICIT_CAST ||
                    expression.operator == IrTypeOperator.IMPLICIT_NOTNULL) &&
            (expression.argument.readsGenericOwnerForeignDispatchDeclaration() ||
                    mappedNaturalType(expression.argument) == DotNetIlValueType.Object) &&
            typeMapper.genericOwnerNaturalRuntimeClassifierInfoOrNull(expression.typeOperand) != null
        ) {
            // A successful generic-owner cast retains the original object. Reporting the logical
            // GenericInstance here would make a following implicit cast reconstruct I<X> before
            // the semantic member dispatcher can select the actual natural construction.
            return DotNetIlValueType.Object
        }
        if (expression is IrTypeOperatorCall &&
            (expression.operator == IrTypeOperator.IMPLICIT_CAST ||
                    expression.operator == IrTypeOperator.IMPLICIT_NOTNULL)
        ) {
            val operandType = mappedNaturalType(expression.argument)
            val logicalResultType = typeMapper.toDotNetIlValueType(expression.typeOperand)
            if (operandType != null && logicalResultType != null &&
                typeMapper.isGenericOwnerCapabilityViewOf(operandType, logicalResultType)
            ) {
                // FIR smartcasts and `!!` refine the logical C<T> view, but a value produced by
                // a semantic hook remains the same non-generic capability until an exact typed
                // boundary is actually required. This keeps a following semantic member call
                // on that capability rather than inventing a constructed C<T> carrier.
                return operandType
            }
        }
        // A provenance-selected local/parameter carrier is the value actually loaded by ldloc /
        // ldarg. This used to be consulted only for array overrides; generic-owner capability
        // slots require the same general rule or an enclosing FIR IMPLICIT_CAST reconstructs the
        // logical C<X> and emits an invalid cast from the physical semantic interface.
        if (expression is IrGetValue && typeMapper.isGenericOwnerCapabilityDeclaration(expression.symbol.owner)) {
            return methodContext.reference(expression.symbol).type
        }
        if (expression is IrGetValue) {
            val slotType = methodContext.reference(expression.symbol).type
            val logicalType = typeMapper.toDotNetIlValueType(expression.type)
            if ((logicalType != null &&
                    typeMapper.isGenericOwnerNestedConstructionCarrierOf(slotType, logicalType)) ||
                (slotType == DotNetIlValueType.Object &&
                        typeMapper.isNestedGenericOwnerConstruction(expression.type))
            ) {
                // An immutable local may retain the Box<object> actually returned by an open
                // nested construction. Its logical Box<Producer<X>> view must not reconstruct
                // an invariant sibling construction before the member receiver is resolved.
                return slotType
            }
        }
        if (expression is IrGetValue && expression.type.isDotNetGenericArray()) {
            val slotType = methodContext.reference(expression.symbol).type
            if (slotType is DotNetIlValueType.GenericArray ||
                slotType is DotNetIlValueType.ErasedGenericArray
            ) return slotType
        }
        if (expression is IrGetField &&
            typeMapper.isGenericOwnerCapabilityDeclaration(expression.symbol.owner)
        ) {
            // Star/projected producer storage may carry either a Kotlin capability or an
            // ordinary foreign I<T> object. The field token records that common physical
            // carrier; reconstructing the logical semantic interface here would insert an
            // invalid cast before the two-level dispatcher gets a chance to inspect it.
            return typeMapper.toDotNetIlFieldType(expression.symbol.owner)
        }
        if (expression is IrGetField) {
            val semanticGetter = expression.symbol.owner.correspondingPropertySymbol?.owner?.getter
                ?.let(genericOwnerCapabilitySlots::get)
            val semanticReceiver = expression.receiver?.let(::genericOwnerCapabilityNaturalTypeOrNull)
            if (semanticGetter != null && semanticReceiver != null) {
                val returnType = availableFunctions[semanticGetter]?.signature?.returnType
                if (returnType is DotNetIlReturnType.Value) return returnType.type
            }
        }
        // Generic-owner state may deliberately use an object carrier while its Kotlin field
        // remains typed as T. The field token, not the logical IrGetField type, is what `ldfld`
        // actually produces; typed callers perform their checked recovery at the member-entry
        // boundary, whereas an object-domain semantic body must be able to return it unchanged.
        if (expression is IrGetField && typeMapper.isGenericOwnerObjectStateField(expression.symbol.owner)) {
            return typeMapper.toDotNetIlFieldType(expression.symbol.owner)
        }
        if (expression is IrGetField) {
            val physicalFieldType = typeMapper.toDotNetIlFieldType(expression.symbol.owner)
            if (physicalFieldType is DotNetIlValueType.TypeParameter) {
                // A semantic hook rewrites the logical occurrence T to Any?, but the producer's
                // proven state is still a true !T field. Report what ldfld actually pushes so
                // the ordinary widening layer boxes it at this object-domain use site.
                return physicalFieldType
            }
        }
        if (expression is IrCall) {
            genericOwnerObjectCarrierCallReturnTypeOrNull(expression)?.let { return it }
            val intrinsic = intrinsicMethods.getIntrinsic(expression.symbol)
            intrinsic?.naturalReturnType(expression, this)?.let { return it }
            if (
                intrinsic == null &&
                !expression.symbol.owner.isDotNetErasedObjectResult() &&
                !expression.symbol.owner.isErasedGenericInterfaceMember() &&
                !expression.symbol.owner.isErasedGenericClassMember()
            ) {
                val returnType = resolveCall(expression).returnType
                if (returnType is DotNetIlReturnType.Value) return returnType.type
            }
        }
        return typeMapper.toDotNetIlValueType(expression.type)
    }

    /** The physical semantic carrier only when it is the view of this logical C<T> result. */
    fun genericOwnerCapabilityNaturalTypeOrNull(expression: IrExpression): DotNetIlValueType? {
        val naturalType = mappedNaturalType(expression) ?: return null
        val runtimeClassifier = typeMapper.genericOwnerRuntimeClassifierTypeOrNull(expression.type)
        if (naturalType == runtimeClassifier) return naturalType
        if (naturalType == DotNetIlValueType.Object && runtimeClassifier != null) {
            // A semantic generic-owner value can come either from a classifier-derived cast or
            // from a construction whose enclosing owner selected object for this nested logical
            // argument (Box<Producer<Any?>> -> Box<object>). Null/identity checks are
            // type-agnostic and must observe that object directly rather than reconstructing the
            // logical constructed interface. A consumer which genuinely needs I<X> performs its
            // own compatibility check or enters the semantic dispatcher instead.
            return naturalType
        }
        val logicalType = typeMapper.toDotNetIlValueType(expression.type) ?: return null
        return naturalType.takeIf { typeMapper.isGenericOwnerCapabilityViewOf(it, logicalType) }
    }

    fun genericOwnerNestedConstructionCarrierTypeOrNull(
        expression: IrExpression,
        logicalType: IrType,
    ): DotNetIlValueType? {
        if (!typeMapper.isGenericOwnerRehearsalEnabled()) return null
        val physicalType = mappedNaturalType(expression) ?: return null
        val mappedLogicalType = typeMapper.toDotNetIlValueType(logicalType) ?: return null
        val openProducerReturn = (expression as? IrCall)?.symbol?.owner?.returnType
            ?.let(typeMapper::isOpenNestedGenericOwnerConstruction) == true
        if (physicalType == DotNetIlValueType.Object && openProducerReturn) return physicalType
        return physicalType.takeIf { candidate ->
            typeMapper.isGenericOwnerNestedConstructionCarrierOf(candidate, mappedLogicalType)
        }
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

    /** Emits the Common synthetic `illegalArgumentException(message)` throw with exact Kotlin identity. */
    fun emitIllegalArgumentExceptionThrow(message: IrExpression, valuePosition: Boolean) {
        val entry = checkNotNull(
            DotNetMappedExceptions.mappedEntry(FqName("kotlin.IllegalArgumentException"))
        ) { "IllegalArgumentException has no .NET exception mapping" }
        emitExpression(message, DotNetIlValueType.String)
        methodContext.emit(
            "newobj instance void ${entry.constructorTypeRef(coreLibraryReference)}::.ctor(string)",
            pops = 1,
            pushes = 1,
        )
        methodContext.emit("dup", pops = 1, pushes = 2)
        methodContext.emit("ldc.i4 ${entry.classifierTypeId.abiValue}", pushes = 1)
        methodContext.emit(
            DotNetThrowableRuntime.setExactTypeIdCallInstruction(coreLibraryReference),
            pops = 2,
        )
        methodContext.emitThrow()
        if (valuePosition) methodContext.notePhantomValueAfterThrow()
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
            // FIR may infer a bounded out-projected array as the transient common type of exact
            // value/reference vectors. CLR value vectors cannot materialize that covariance.
            // When the consumer already asks for the selected Array<*>/System.Array view, emit
            // the child expression directly at that erased boundary so every branch retains its
            // exact vector. The same physical rule covers a compiler-selected local Array<T?>
            // carrier over an already-created exact vector; writes remain restricted to values
            // with the original logical T type. Neither shape is admitted in ABI.
            val eraseTransientArrayProjection =
                expectedType is DotNetIlValueType.ErasedGenericArray &&
                        (expression.type.isDotNetOutProjectedGenericArray() ||
                                expression.type.isDotNetInvariantOpenNullableGenericArray())
            val naturalType = if (eraseTransientArrayProjection) expectedType else mappedNaturalType(expression)
            if (naturalType != null && naturalType != expectedType && !eraseTransientArrayProjection) {
                if (typeMapper.isGenericOwnerRehearsalEnabled() &&
                    naturalType == DotNetIlValueType.Object &&
                    expectedType.isDotNetReferenceShaped()
                ) {
                    val logicalType = typeMapper.toDotNetIlValueType(expression.type)
                    if (logicalType != null &&
                        typeMapper.isGenericOwnerCapabilityViewOf(expectedType, logicalType)
                    ) {
                        // A nested unstable construction such as Box<Consumer<Int>> stores the
                        // original sibling-capability object in Box<object>. The declaration and
                        // call planner select a capability only at the input-bearing member use;
                        // recover precisely that selected view without fabricating Consumer<int>
                        // or turning identity/null consumers into semantic calls.
                        emitExpression(expression, DotNetIlValueType.Object)
                        if (methodContext.isTerminated) return
                        methodContext.emit(
                            "castclass ${expectedType.nameInSignature}",
                            pops = 1,
                            pushes = 1,
                        )
                        return
                    }
                }
                val kFunctionArity = expression.type.dotNetKFunctionExecutionArityOrNull()
                if (kFunctionArity != null &&
                    expectedType == DotNetRuntimeTypes.functionExecutionType(kFunctionArity)
                ) {
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
                    emitWideningCoercion(coercion)
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
            // Bottom-typed control transfers can occur under arbitrary shared-IR value shapes
            // (for example an elvis arm in a call argument or a compiler temporary). Their
            // physical branch/leave and dead-path stack bookkeeping belong to method scope.
            is IrReturn, is IrBreakContinue ->
                statementScopeEmitter.emitControlFlowValueExpression(expression, expectedType)
            is IrTypeOperatorCall -> emitTypeOperatorCall(expression, expectedType)
            is IrVararg -> emitVarargLiteral(expression, expectedType)
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

    private fun emitVarargLiteral(expression: IrVararg, expectedType: DotNetIlValueType) {
        val elements = expression.elements.mapIndexed { index, element ->
            when (element) {
                is IrSpreadElement -> dotNetUnsupported(
                    "spread element at index $index in a residual IR vararg is not supported"
                )
                is IrExpression -> element
                else -> error("Internal .NET backend error: unknown IrVarargElement ${element.javaClass.simpleName}")
            }
        }
        val literalType = when (expectedType) {
            is DotNetIlValueType.PrimitiveArray -> expectedType
            is DotNetIlValueType.GenericArray -> {
                val elementType = toDotNetIlGenericArgumentType(expression.varargElementType)
                    ?: dotNetUnsupported(
                        "residual IR vararg has unsupported element type ${expression.varargElementType.render()}"
                    )
                DotNetIlValueType.GenericArray(elementType)
            }
            else -> dotNetUnsupported(
                "residual IR vararg cannot produce ${expectedType.nameInSignature}"
            )
        }
        if (!literalType.isDotNetAssignableTo(expectedType)) {
            dotNetUnsupported(
                "residual IR vararg produces ${literalType.nameInSignature}, not ${expectedType.nameInSignature}"
            )
        }
        when (literalType) {
            is DotNetIlValueType.PrimitiveArray -> emitArrayLiteralElements(
                elements,
                this,
                literalType.storageType,
                literalType.elementType,
                literalType.newStorageInstruction,
                literalType.storageType.storeElementInstruction,
                literalType.wrapStorageInstruction,
            )
            is DotNetIlValueType.GenericArray -> emitArrayLiteralElements(
                elements,
                this,
                literalType,
                literalType.elementType,
                literalType.newArrayInstruction,
                literalType.storeElementInstruction,
            )
            else -> error("Internal .NET backend error: residual vararg literal type is not an array")
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
        classifier.logicalKey?.let { logicalKey ->
            emitNullableString(classifier.simpleName)
            emitNullableString(classifier.qualifiedName)
            emitNullableString(logicalKey)
            methodContext.emit(
                DotNetKClassRuntime.createLogicalCallInstruction(),
                pops = 3,
                pushes = 1,
            )
            return
        }
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
        val logicalKey: String? = null,
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
        if (typeMapper.genericOwnerRuntimeClassifierTypeOrNull(classType) != null) {
            val canonicalOwner = typeMapper.classInfoOrNull(irClass)
                ?: dotNetUnsupported(
                    "reified generic-owner class literal lost its canonical CLR TypeDef: ${classType.render()}"
                )
            require(canonicalOwner.typeParameterCount == irClass.typeParameters.size) {
                "reified generic-owner class literal for '${irClass.render()}' has CLR arity " +
                        "${canonicalOwner.typeParameterCount}; expected ${irClass.typeParameters.size}"
            }
            // A Kotlin `C::class` denotes the classifier, not one use-site projection. Keep the
            // non-generic semantic capability for runtime `is`/cast checks, but give KClass the
            // canonical open C<T> TypeDef so producer-owned annotations/member factories remain
            // discoverable across separate compilation.
            return StaticKClassClassifier(
                clrTypeRef = canonicalOwner.ilTypeRef,
                simpleName = sourceSimpleName,
                qualifiedName = sourceQualifiedName,
                kind = DotNetKClassClassifierKind.OPEN_GENERIC,
            )
        }
        if (irClass.dotNetImportedClrSourceOrNull() != null) {
            val importedClass = typeMapper.classInfoOrNull(irClass)
                ?: dotNetUnsupported(
                    "imported class literal lost its retained CLR TypeDef: ${classType.render()}"
                )
            if (importedClass.typeParameterCount > 0) {
                return StaticKClassClassifier(
                    clrTypeRef = importedClass.ilTypeRef,
                    simpleName = sourceSimpleName,
                    qualifiedName = sourceQualifiedName,
                    kind = DotNetKClassClassifierKind.OPEN_GENERIC,
                )
            }
        }
        val mappedType = typeMapper.toDotNetIlValueType(classType)
            ?: return StaticKClassClassifier(
                clrTypeRef = null,
                simpleName = sourceSimpleName,
                qualifiedName = sourceQualifiedName,
                kind = DotNetKClassClassifierKind.LOGICAL,
                logicalKey = with(DotNetIrMangler) {
                    irClass.mangleString(compatibleMode = false)
                },
            )
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
     * Atomically replaces an object-valued instance [field] when its current value is
     * reference-identical to [expected], leaving the Boolean success result on the stack.
     *
     * The source-order receiver/expected/update evaluations are spilled before reloading them in
     * `Interlocked.CompareExchange(ref location, value, comparand)` order. This is the physical
     * CLR primitive behind Kotlin's lock-free `SafeContinuation`; ordinary observations of its
     * field remain volatile reads, so no monitor, wrapper, or second state carrier is involved.
     */
    fun emitObjectFieldCompareExchange(
        receiver: IrExpression,
        field: IrField,
        expected: IrExpression,
        update: IrExpression,
    ) {
        val [classInfo, fieldType, isStatic] = resolveFieldAccess(field)
        if (isStatic || classInfo.typeParameterCount != 0 || fieldType != DotNetIlValueType.Object) {
            dotNetUnsupported(
                "Interlocked.CompareExchange requires an object-valued field on an erased instance owner; " +
                        "'${field.name.asString()}' is ${fieldType.nameInSignature} on " +
                        "${classInfo.ilTypeRef} (static=$isStatic, arity=${classInfo.typeParameterCount})"
            )
        }

        val receiverType = DotNetIlValueType.UserClass(classInfo)
        emitExpression(receiver, receiverType)
        val receiverSlot = spillToSyntheticLocal(receiverType, "<compareExchangeReceiver>")
        emitExpression(expected, DotNetIlValueType.Object)
        val expectedSlot = spillToSyntheticLocal(DotNetIlValueType.Object, "<compareExchangeExpected>")
        emitExpression(update, DotNetIlValueType.Object)
        val updateSlot = spillToSyntheticLocal(DotNetIlValueType.Object, "<compareExchangeUpdate>")

        methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
        methodContext.emit(
            "ldflda ${classInfo.renderFieldReference(fieldType, field.name.asString())}",
            pops = 1,
            pushes = 1,
        )
        methodContext.emit(loadLocalInstruction(updateSlot.index), pushes = 1)
        methodContext.emit(loadLocalInstruction(expectedSlot.index), pushes = 1)
        methodContext.emit(
            "call object ${coreLibraryReference}System.Threading.Interlocked::" +
                    "CompareExchange(object&, object, object)",
            pops = 3,
            pushes = 1,
        )
        methodContext.emit(loadLocalInstruction(expectedSlot.index), pushes = 1)
        methodContext.emit("ceq", pops = 2, pushes = 1)
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
     * `!!`/IMPLICIT_NOTNULL on an open CLR generic parameter cannot branch on the unboxed slot:
     * one legal instantiation may be a value type and another a nullable reference. Preserve the
     * original slot in a local, box only the probe (`box !n` keeps references and turns an empty
     * Nullable value into null), apply the ordinary reference check, discard the probe, and reload
     * the unchanged generic value. The check is therefore representation-independent and does not
     * manufacture a second generic carrier.
     */
    private fun emitTypeParameterNotNullOrThrowNpe(type: DotNetIlValueType.TypeParameter) {
        val slot = spillToSyntheticLocal(type, "<genericNotNull>")
        methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
        methodContext.emit("box ${type.nameInSignature}", pops = 1, pushes = 1)
        emitReferenceNotNullOrThrowNpe()
        methodContext.emit("pop", pops = 1)
        methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
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

    /** Throws the CLR exception mapped to Kotlin [ClassCastException]. */
    private fun emitThrowClassCastException() {
        methodContext.emit(
            "newobj instance void ${coreLibraryReference}System.InvalidCastException::.ctor()",
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
     * - [IrTypeOperator.IMPLICIT_COERCION_TO_UNIT]: evaluate the operand for its effects, discard
     *   its value, and materialize the Kotlin Unit singleton. This is the same target-neutral
     *   shape used by JS (`argument; Unit`) and Wasm (`generateAsStatement; getUnit`); on CIL the
     *   statement-scope hook also handles control flow and void-returning calls without leaving
     *   a stale evaluation-stack value behind.
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
     *   generic cast. SAFE_CAST tests the parameter's Kotlin erased upper bound instead: a
     *   non-reified `as? T` cannot test the substituted T, and JVM likewise checks only that
     *   erased classifier (or performs no check for an Any-bound parameter).
     * - Explicit casts and runtime tests against `CharSequence` use the runtime's classified
     *   string-or-capability boundary. The physical object carrier alone never admits a value;
     *   successful casts preserve the original reference.
     * Everything else — explicit casts to reified generic CLR shapes and value-type tests —
     * stays rejected loudly until its own audited model exists.
     */
    private fun emitTypeOperatorCall(expression: IrTypeOperatorCall, expectedType: DotNetIlValueType) {
        if (expression.operator == IrTypeOperator.SAM_CONVERSION) {
            dotNetUnsupported("SAM conversion survived .NET single-abstract-method lowering")
        }
        if (expression.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) {
            if (!DotNetRuntimeTypes.unitType.isDotNetAssignableTo(expectedType)) {
                dotNetUnsupported(
                    "implicit coercion to Unit cannot produce ${expectedType.nameInSignature}"
                )
            }
            statementScopeEmitter.emitUnitEffectExpression(expression.argument)
            return
        }
        val operandType = mappedNaturalType(expression.argument)
            ?: dotNetUnsupported("implicit cast of a value of unsupported type ${expression.argument.type.render()}")
        if ((expression.operator == IrTypeOperator.CAST ||
                    expression.operator == IrTypeOperator.IMPLICIT_CAST) &&
            expression.typeOperand.isDotNetInvariantOpenNullableGenericArray() &&
            expectedType is DotNetIlValueType.ErasedGenericArray
        ) {
            // Common collection code uses an unchecked local Array<T> -> Array<T?> view only
            // while filling an existing exact vector. Retain that vector through System.Array;
            // no public/open-nullable signature or new nullable vector is created here.
            emitExpression(expression.argument, operandType)
            return
        }
        val boxedValueClassCastType = expression.typeOperand.dotNetValueClassOrNull()?.let {
            typeMapper.toDotNetIlBoxedValueClassType(expression.typeOperand)
        }
        val valueClassRuntimeOperator = expression.operator == IrTypeOperator.CAST ||
                expression.operator == IrTypeOperator.SAFE_CAST ||
                expression.operator == IrTypeOperator.INSTANCEOF ||
                expression.operator == IrTypeOperator.NOT_INSTANCEOF ||
                // The value-usage lowering passes a frontend-proven IMPLICIT_CAST to the
                // producer unbox helper. Its parameter is the nominal value-class owner, so the
                // inner operation must produce that owner. An ordinary exact use instead asks
                // for the carrier and therefore does not enter this branch.
                (expression.operator == IrTypeOperator.IMPLICIT_CAST &&
                        boxedValueClassCastType != null && expectedType == boxedValueClassCastType) ||
                (expression.operator == IrTypeOperator.IMPLICIT_NOTNULL &&
                        expression.argument.type.dotNetValueClassOrNull() != null &&
                        expression.argument.type.dotNetUnboxedValueClassTypeOrNull() == null)
        val valueClassOwnerCarrierReinterpretType =
            if (expression.operator != IrTypeOperator.REINTERPRET_CAST) {
                null
            } else if (expression.typeOperand.dotNetValueClassOrNull()?.let { valueClass ->
                    expression.typeOperand.referencesTypeParameterOf(valueClass)
                } == true && operandType == DotNetIlValueType.Object
            ) {
                DotNetIlValueType.Object
            } else if (typeMapper.genericOwnerValueClassCarrierTypeOrNull(expression.argument.type) == operandType ||
                typeMapper.genericOwnerValueClassCarrierTypeOrNull(expression.typeOperand) == operandType
            ) {
                operandType
            } else {
                null
            }
        val castType = if (valueClassOwnerCarrierReinterpretType != null) {
            valueClassOwnerCarrierReinterpretType
        } else if (valueClassRuntimeOperator && boxedValueClassCastType != null) {
            boxedValueClassCastType
        } else {
            typeMapper.toDotNetIlValueType(expression.typeOperand)
        }
            ?: dotNetUnsupported("implicit cast to unsupported type ${expression.typeOperand.render()}")
        if (expression.operator == IrTypeOperator.IMPLICIT_CAST &&
            expectedType == DotNetIlValueType.Object &&
            operandType == DotNetIlValueType.Object &&
            typeMapper.genericOwnerNaturalRuntimeClassifierInfoOrNull(expression.typeOperand) != null
        ) {
            // FIR's logical smartcast is used here only to test the cast-derived receiver for
            // null. The preceding Kotlin-compatible generic-owner cast already produced the
            // original object, and a real member or typed ABI use performs its own semantic
            // dispatch or checked recovery.
            emitExpression(expression.argument, DotNetIlValueType.Object)
            return
        }
        if (expression.operator == IrTypeOperator.IMPLICIT_CAST &&
            expectedType.isDotNetReferenceShaped() &&
            typeMapper.isGenericOwnerCapabilityViewOf(expectedType, castType)
        ) {
            // A frontend smartcast following `candidate is C<*>` may still spell its logical
            // result as C<T>. In a semantic body the proven physical fact is only C's
            // classifier capability. Materialize precisely that proof instead of inventing the
            // current hook construction C<!T>.
            emitExpression(expression.argument, DotNetIlValueType.Object)
            if (methodContext.isTerminated) return
            if (expectedType != DotNetIlValueType.Object) {
                methodContext.emit("castclass ${expectedType.nameInSignature}", pops = 1, pushes = 1)
            }
            return
        }
        if (expression.operator == IrTypeOperator.IMPLICIT_CAST &&
            operandType == expectedType &&
            typeMapper.isGenericOwnerCapabilityViewOf(operandType, castType)
        ) {
            // FIR may expose the closed logical C<X> view of an open generic result even when
            // its only stable physical carrier is C's semantic capability (notably `<T> C<T?>`).
            // Keep that capability end-to-end. Casting C<object> to C<Nullable<int>> would be an
            // invalid attempt to reconstruct a CLR instantiation which the producer never made.
            emitExpression(expression.argument, operandType)
            return
        }
        if (expression.operator == IrTypeOperator.IMPLICIT_NOTNULL &&
            operandType == expectedType &&
            typeMapper.isGenericOwnerCapabilityViewOf(operandType, castType)
        ) {
            // `!!` changes nullability, not the physical generic-owner view. Preserve the
            // semantic capability and perform the ordinary Kotlin reference null check.
            emitExpression(expression.argument, operandType)
            if (methodContext.isTerminated) return
            emitReferenceNotNullOrThrowNpe()
            return
        }
        if (expression.operator == IrTypeOperator.REINTERPRET_CAST) {
            if (operandType != castType || castType != expectedType) {
                dotNetUnsupported(
                    "reinterpret cast changes physical carrier from ${operandType.nameInSignature} " +
                            "to ${castType.nameInSignature} where ${expectedType.nameInSignature} is expected " +
                            "(${expression.argument.type.render()} as ${expression.typeOperand.render()})"
                )
            }
            // Shared value-class declaration lowering uses REINTERPRET_CAST only to change the
            // logical IR type of an already exact underlying value. Equal mapped carriers prove
            // that the CLR evaluation stack needs no instruction; every unequal shape remains a
            // located failure instead of becoming an unchecked physical conversion.
            emitExpression(expression.argument, operandType)
            return
        }
        if (expression.operator == IrTypeOperator.IMPLICIT_CAST &&
            expression.argument.type.isDotNetGenericArray() &&
            expression.typeOperand.isDotNetGenericArray() &&
            (expectedType is DotNetIlValueType.ErasedGenericArray || expectedType == operandType)
        ) {
            // FIR can materialize an invariant-looking IMPLICIT_CAST after type substitution,
            // even though the consuming declaration is the authoritative read-only
            // `Array<out T>` boundary. One important shape is an inlined
            // `MutableCollection<in T>.plusAssign(Array<T>)`: erasing the collection owner can
            // make the transient cast read `Array<Int> -> Array<Any?>`. CLR cannot widen
            // `int32[]` to `object[]`, but every exact SZ vector already is the selected
            // identity-preserving System.Array carrier. Emit the original vector directly at
            // that outer carrier. An exact expected type can additionally come from the
            // provenance-preserving compiler temporary selected by DotNetIlMethodCodegen; in
            // that case the original vector is already precisely the local's physical type.
            // Every other invariant destination takes the normal path below and therefore
            // cannot acquire this widening.
            emitExpression(expression.argument, operandType)
            return
        }
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
                if (!expression.typeOperand.isNullable()) {
                    // `unbox.any !T` correctly recovers both value and reference substitutions,
                    // but a reference substitution still accepts null. Kotlin's non-null T
                    // contract is additional evidence that CLR GenericParam metadata cannot
                    // express, so probe the recovered value without changing its carrier.
                    emitTypeParameterNotNullOrThrowNpe(castType)
                }
                emitCastResultCoercion(castType, expectedType, "generic cast")
                return
            }
            if (expression.operator == IrTypeOperator.SAFE_CAST && castType is DotNetIlValueType.TypeParameter) {
                val erasedBound = expression.typeOperand.erasedUpperBound.symbol.defaultType
                val erasedBoundType = typeMapper.toDotNetIlValueType(erasedBound)
                    ?: dotNetUnsupported(
                        "safe generic cast has unsupported erased upper bound " +
                                erasedBound.render()
                    )
                if (!erasedBoundType.isDotNetReferenceShaped()) {
                    dotNetUnsupported(
                        "safe generic cast has non-reference erased upper bound " +
                                erasedBoundType.nameInSignature
                    )
                }
                if (!erasedBoundType.isDotNetAssignableTo(expectedType)) {
                    dotNetUnsupported(
                        "safe generic cast produces ${erasedBoundType.nameInSignature} " +
                                "where ${expectedType.nameInSignature} is expected"
                    )
                }
                emitExpression(expression.argument, DotNetIlValueType.Object)
                if (methodContext.isTerminated) return
                if (erasedBoundType != DotNetIlValueType.Object) {
                    methodContext.emit("isinst ${erasedBoundType.nameInSignature}", pops = 1, pushes = 1)
                }
                return
            }
            val capabilityType =
                typeMapper.genericOwnerRuntimeClassifierTypeOrNull(expression.typeOperand)
            val naturalClassifier =
                typeMapper.genericOwnerNaturalRuntimeClassifierInfoOrNull(expression.typeOperand)
            if (capabilityType != null && naturalClassifier != null) {
                if (expectedType != DotNetIlValueType.Object) {
                    dotNetUnsupported(
                        "generic-owner cast produces object " +
                                "where ${expectedType.nameInSignature} is expected"
                    )
                }
                emitExpression(expression.argument, DotNetIlValueType.Object)
                if (methodContext.isTerminated) return
                val receiverSlot = spillToSyntheticLocal(
                    DotNetIlValueType.Object,
                    "<genericInterfaceCastReceiver>",
                )
                val requestedConstruction =
                    reifiedGenericInterfaceRequestedConstructionOrNull(
                        expression.typeOperand,
                        castType,
                    )
                fun emitMatch() {
                    if (requestedConstruction != null) {
                        emitReifiedGenericInterfaceConstructionMatch(
                            receiverSlot.index,
                            requestedConstruction,
                        )
                    } else {
                        emitReifiedGenericInterfaceClassifierMatch(
                            receiverSlot.index,
                            capabilityType,
                            naturalClassifier,
                        )
                    }
                }
                if (expression.operator == IrTypeOperator.SAFE_CAST) {
                    emitMatch()
                    val failedLabel = methodContext.nextLabel("genericInterfaceSafeCastFailed")
                    val joinLabel = methodContext.nextLabel("genericInterfaceSafeCastJoin")
                    methodContext.emitBranch("brfalse", failedLabel, pops = 1)
                    methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
                    methodContext.emitGoto(joinLabel)
                    methodContext.emitLabel(failedLabel)
                    methodContext.emit("ldnull", pushes = 1)
                    methodContext.emitLabel(joinLabel)
                } else {
                    val nonNullLabel = methodContext.nextLabel("genericInterfaceCastNonNull")
                    val succeededLabel = methodContext.nextLabel("genericInterfaceCastSucceeded")
                    methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
                    if (expression.typeOperand.isNullable()) {
                        methodContext.emitBranch("brfalse", succeededLabel, pops = 1)
                    } else {
                        methodContext.emitBranch("brtrue", nonNullLabel, pops = 1)
                        emitThrowNullPointerException()
                        methodContext.emitLabel(nonNullLabel)
                    }
                    emitMatch()
                    methodContext.emitBranch("brtrue", succeededLabel, pops = 1)
                    emitThrowClassCastException()
                    methodContext.emitLabel(succeededLabel)
                    methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
                }
                return
            }
            val bigFunctionArity = expression.typeOperand.dotNetBigCallableArityOrNull()
            if (bigFunctionArity != null) {
                if (!castType.isDotNetAssignableTo(expectedType)) {
                    dotNetUnsupported(
                        "big-arity function cast has inconsistent physical result " +
                                "${castType.nameInSignature} where ${expectedType.nameInSignature} is expected"
                    )
                }
                emitExpression(expression.argument, DotNetIlValueType.Object)
                if (methodContext.isTerminated) return
                methodContext.emit("ldc.i4 $bigFunctionArity", pushes = 1)
                methodContext.emit(
                    if (expression.operator == IrTypeOperator.SAFE_CAST) {
                        DotNetRuntimeLibraryHelpers.safeFunctionCastCallInstruction
                    } else {
                        DotNetRuntimeLibraryHelpers.checkFunctionCastCallInstruction
                    },
                    pops = 2,
                    pushes = 1,
                )
                methodContext.emit(
                    if (expression.operator == IrTypeOperator.SAFE_CAST) {
                        "isinst ${castType.nameInSignature}"
                    } else {
                        "castclass ${castType.nameInSignature}"
                    },
                    pops = 1,
                    pushes = 1,
                )
                if (expression.operator == IrTypeOperator.CAST && !expression.typeOperand.isNullable()) {
                    emitReferenceNotNullOrThrowNpe()
                }
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
                if (expression.operator == IrTypeOperator.CAST && !expression.typeOperand.isNullable()) {
                    emitReferenceNotNullOrThrowNpe()
                }
                return
            }
            if (expression.typeOperand.isDotNetNumberType()) {
                if (castType != DotNetIlValueType.Object || expectedType != DotNetIlValueType.Object) {
                    dotNetUnsupported(
                        "classified Number cast has inconsistent physical result " +
                                "${castType.nameInSignature} where ${expectedType.nameInSignature} is expected"
                    )
                }
                emitExpression(expression.argument, DotNetIlValueType.Object)
                if (methodContext.isTerminated) return
                methodContext.emit(
                    if (expression.operator == IrTypeOperator.SAFE_CAST) {
                        DotNetRuntimeLibraryHelpers.safeNumberCastCallInstruction
                    } else {
                        DotNetRuntimeLibraryHelpers.checkNumberCastCallInstruction
                    },
                    pops = 1,
                    pushes = 1,
                )
                if (expression.operator == IrTypeOperator.CAST && !expression.typeOperand.isNullable()) {
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
                if (expression.operator == IrTypeOperator.CAST && !expression.typeOperand.isNullable()) {
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
                // Kotlin generic-owner casts were handled above through their Kotlin-aware
                // construction predicate and object carrier. A remaining imported CLR generic
                // retains its ordinary constructed throwing-cast behavior.
                is DotNetIlValueType.GenericInstance -> expression.operator == IrTypeOperator.CAST
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
                if (!expression.typeOperand.isNullable()) {
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
            val capabilityType =
                typeMapper.genericOwnerRuntimeClassifierTypeOrNull(expression.typeOperand)
            val naturalClassifier =
                typeMapper.genericOwnerNaturalRuntimeClassifierInfoOrNull(expression.typeOperand)
            if (capabilityType != null && naturalClassifier != null) {
                emitReifiedGenericInterfaceRuntimeTypeTest(
                    expression,
                    capabilityType,
                    naturalClassifier,
                    reifiedGenericInterfaceRequestedConstructionOrNull(
                        expression.typeOperand,
                        castType,
                    ),
                )
                return
            }
            val runtimeClassifierType = capabilityType ?: castType
            emitRuntimeTypeTest(expression, runtimeClassifierType)
            return
        }
        if (expression.operator != IrTypeOperator.IMPLICIT_CAST && expression.operator != IrTypeOperator.IMPLICIT_NOTNULL) {
            dotNetUnsupported("type operator ${expression.operator} is not supported")
        }
        if (expression.operator == IrTypeOperator.IMPLICIT_NOTNULL) {
            emitExpression(expression.argument, operandType)
            if (methodContext.isTerminated) return
            when {
                operandType == castType && operandType.isSupportedPrimitiveArrayElement() -> Unit
                operandType is DotNetIlValueType.NullableValue && castType == operandType.elementType ->
                    emitNullableUnwrapOrThrowNpe(operandType)
                operandType is DotNetIlValueType.TypeParameter && operandType == castType ->
                    emitTypeParameterNotNullOrThrowNpe(operandType)
                operandType.isDotNetReferenceShaped() && operandType.isDotNetAssignableTo(castType) ->
                    emitReferenceNotNullOrThrowNpe()
                else -> dotNetUnsupported(
                    "implicit not-null assertion from ${operandType.nameInSignature} " +
                            "to ${castType.nameInSignature} is not supported"
                )
            }
        } else {
            val kFunctionArity = expression.argument.type.dotNetKFunctionExecutionArityOrNull()
            val targetsBigArityStub = expression.typeOperand.classOrNull?.owner
                ?.isDotNetBigArityFunctionN == true
            when {
                operandType is DotNetIlValueType.GenericArray &&
                        castType is DotNetIlValueType.PrimitiveArray &&
                        operandType.elementType == castType.elementType -> {
                    emitExpression(expression.argument, operandType)
                    if (methodContext.isTerminated) return
                    methodContext.emit(
                        castType.abi.wrapStorageOrNullCallInstruction,
                        pops = 1,
                        pushes = 1,
                    )
                    if (!expression.typeOperand.isNullable()) {
                        emitReferenceNotNullOrThrowNpe()
                    }
                }
                operandType is DotNetIlValueType.PrimitiveArray &&
                        castType is DotNetIlValueType.GenericArray &&
                        operandType.elementType == castType.elementType -> {
                    emitExpression(expression.argument, operandType)
                    if (methodContext.isTerminated) return
                    methodContext.emit(
                        operandType.abi.projectStorageOrNullCallInstruction,
                        pops = 1,
                        pushes = 1,
                    )
                }
                kFunctionArity != null &&
                        (kFunctionArity == expression.typeOperand.dotNetFunctionExecutionArityOrNull() ||
                                targetsBigArityStub && kFunctionArity >= BuiltInFunctionArity.BIG_ARITY) -> {
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
                            emitWideningCoercion(coercion)
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
                        operandType.isDotNetReferenceShaped() &&
                                castType.isSupportedPrimitiveArrayElement() -> {
                            val narrowing = castType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference)
                                ?: dotNetUnsupported(
                                    "implicit cast from ${operandType.nameInSignature} to " +
                                            "${castType.nameInSignature} is not supported"
                                )
                            // A substituted generic return can arrive through its physical
                            // reference-shaped upper bound. `Sequence<T>.min/max` with `T = Int`
                            // is the canonical case: the imported logical call is Int, while the
                            // erased owner exposes the value transiently as IComparable. FIR's
                            // IMPLICIT_CAST proves the substitution; the CLR still needs an
                            // explicit unbox.any to recover the value-type instantiation.
                            emitExpression(expression.argument, operandType)
                            if (methodContext.isTerminated) return
                            methodContext.emit(narrowing, pops = 1, pushes = 1)
                        }
                        operandType.isDotNetReferenceShaped() &&
                                castType is DotNetIlValueType.NullableValue &&
                                castType.elementType.isSupportedPrimitiveArrayElement() -> {
                            val narrowing = castType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference)
                                ?: dotNetUnsupported(
                                    "implicit cast from ${operandType.nameInSignature} to " +
                                            "${castType.nameInSignature} is not supported"
                                )
                            // The nullable counterpart of the substituted-generic return above.
                            // A generic `R?` is physically exposed through its reference-shaped
                            // upper bound: for `R = Int`, the CLR boundary contains either a boxed
                            // Int or null. FIR's IMPLICIT_CAST proves the substitution, and
                            // `unbox.any Nullable<Int>` recovers both representations exactly.
                            emitExpression(expression.argument, operandType)
                            if (methodContext.isTerminated) return
                            methodContext.emit(narrowing, pops = 1, pushes = 1)
                        }
                        castType is DotNetIlValueType.TypeParameter &&
                                castType.isConstrainedTo(operandType) -> {
                            // Shared inline bodies such as `C.onEach { ... }: C`, where
                            // `C : Iterable<T>`, can expose the same receiver through its erased
                            // upper-bound view before returning it as `C`. JVM needs no operation
                            // because both views have one erased token. CLR must recover the open
                            // method parameter: `unbox.any !!n` checks a reference instantiation
                            // and also recovers a boxed value-type implementation. The frontend's
                            // IMPLICIT_CAST proves the value is the original `C`; the physical
                            // constraint proves only that the intervening bound view is truthful.
                            val narrowing = castType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference)
                                ?: dotNetUnsupported(
                                    "implicit cast from bound ${operandType.nameInSignature} to " +
                                            "${castType.nameInSignature} is not supported"
                                )
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
                                    "is not a reference upcast and is not supported " +
                                    "(${expression.argument.type.render()} -> ${expression.typeOperand.render()})"
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
            emitWideningCoercion(outerCoercion)
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
        emitWideningCoercion(outerCoercion)
    }

    /** Emits the declaration-erased classifier match for one already-spilled object. */
    private fun emitReifiedGenericInterfaceClassifierMatch(
        receiverLocalIndex: Int,
        capabilityType: DotNetIlValueType.UserClass,
        naturalClassifier: DotNetIlClassInfo,
    ) {
        naturalClassifier.assemblyName?.let(typeMapper::recordAssemblyReference)
        val foreignLabel = methodContext.nextLabel("genericInterfaceClassifierForeign")
        val joinLabel = methodContext.nextLabel("genericInterfaceClassifierJoin")
        methodContext.emit(loadLocalInstruction(receiverLocalIndex), pushes = 1)
        methodContext.emit("isinst ${capabilityType.nameInSignature}", pops = 1, pushes = 1)
        methodContext.emitBranch("brfalse", foreignLabel, pops = 1)
        methodContext.emit("ldc.i4.1", pushes = 1)
        methodContext.emitGoto(joinLabel)
        methodContext.emitLabel(foreignLabel)
        methodContext.emit(loadLocalInstruction(receiverLocalIndex), pushes = 1)
        emitSystemTypeOrNull(naturalClassifier.ilTypeRef)
        methodContext.emit(
            DotNetGenericInterfaceRuntime.isOpenGenericInterfaceInstanceCallInstruction(
                coreLibraryReference,
            ),
            pops = 2,
            pushes = 1,
        )
        methodContext.emitLabel(joinLabel)
    }

    /**
     * Recovers the requested natural CLR construction independently of the current method's
     * physical signature view. A semantic method maps its logical `I<A>` operand to `object`, but
     * that carrier decision must not erase the warning-bearing runtime check for `as I<B>`.
     * Stars and projections intentionally return null and therefore retain classifier-only RTTI.
     */
    private fun reifiedGenericInterfaceRequestedConstructionOrNull(
        typeOperand: IrType,
        mappedType: DotNetIlValueType,
    ): DotNetIlValueType.GenericInstance? {
        val declared = typeMapper.genericInterfaceCapabilityTypeOrNull(
            typeOperand,
            DotNetGenericInterfaceView.DECLARED,
        )
        return declared ?: mappedType as? DotNetIlValueType.GenericInstance
    }

    /** Emits the shared Kotlin-aware construction predicate for casts and runtime type tests. */
    private fun emitReifiedGenericInterfaceConstructionMatch(
        receiverLocalIndex: Int,
        requestedConstruction: DotNetIlValueType.GenericInstance,
    ) {
        requestedConstruction.classInfo.assemblyName?.let(typeMapper::recordAssemblyReference)
        methodContext.emit(loadLocalInstruction(receiverLocalIndex), pushes = 1)
        emitSystemTypeOrNull(requestedConstruction.nameInSignature)
        methodContext.emit(
            DotNetGenericInterfaceRuntime
                .isCompatibleGenericOwnerInstanceCallInstruction(coreLibraryReference),
            pops = 2,
            pushes = 1,
        )
    }

    private fun emitReifiedGenericInterfaceRuntimeTypeTest(
        expression: IrTypeOperatorCall,
        capabilityType: DotNetIlValueType.UserClass,
        naturalClassifier: DotNetIlClassInfo,
        requestedConstruction: DotNetIlValueType.GenericInstance?,
    ) {
        emitExpression(expression.argument, DotNetIlValueType.Object)
        if (methodContext.isTerminated) return
        val receiverSlot = spillToSyntheticLocal(
            DotNetIlValueType.Object,
            "<genericInterfaceClassifierReceiver>",
        )
        val resultJoin = if (expression.typeOperand.isNullable()) {
            val nonNullLabel = methodContext.nextLabel("nullableGenericInterfaceClassifierNonNull")
            val joinLabel = methodContext.nextLabel("nullableGenericInterfaceClassifierJoin")
            methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
            methodContext.emitBranch("brtrue", nonNullLabel, pops = 1)
            methodContext.emit("ldc.i4.1", pushes = 1)
            methodContext.emitGoto(joinLabel)
            methodContext.emitLabel(nonNullLabel)
            joinLabel
        } else {
            null
        }
        if (requestedConstruction != null) {
            emitReifiedGenericInterfaceConstructionMatch(
                receiverSlot.index,
                requestedConstruction,
            )
        } else {
            emitReifiedGenericInterfaceClassifierMatch(
                receiverSlot.index,
                capabilityType,
                naturalClassifier,
            )
        }
        resultJoin?.let(methodContext::emitLabel)
        if (expression.operator == IrTypeOperator.NOT_INSTANCEOF) {
            methodContext.emit("ldc.i4.0", pushes = 1)
            methodContext.emit("ceq", pops = 2, pushes = 1)
        }
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
        val isClassifiedNumber = expression.typeOperand.isDotNetNumberType()
        val classifiedBigFunctionArity = expression.typeOperand.dotNetBigCallableArityOrNull()
        val runtimeTestType = if (castType is DotNetIlValueType.NullableValue) castType.elementType else castType
        if (exceptionEntry == null && !isClassifiedCharSequence && !isClassifiedNumber &&
            classifiedBigFunctionArity == null &&
            !expression.typeOperand.isNullableNothing()
        ) {
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
        if (isClassifiedNumber) {
            methodContext.emit(
                DotNetRuntimeLibraryHelpers.isNumberCallInstruction,
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
        if (classifiedBigFunctionArity != null) {
            if (runtimeTestType != DotNetRuntimeTypes.bigArityFunctionType()) {
                // KFunction/KSuspendFunction adds an orthogonal reflection identity to the same
                // FunctionN execution object. Filter that capability first so a plain lambda of
                // the right arity cannot satisfy a reflective function test.
                methodContext.emit(
                    "isinst ${runtimeTestType.nameInSignature}",
                    pops = 1,
                    pushes = 1,
                )
            }
            methodContext.emit("ldc.i4 $classifiedBigFunctionArity", pushes = 1)
            methodContext.emit(
                DotNetRuntimeLibraryHelpers.isFunctionOfArityCallInstruction,
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
        (this as? IrSimpleType)?.let { simpleType ->
            (simpleType.classifier.owner as? IrClass)?.let { owner ->
                val classifierInfo = typeMapper.classifierInfo(owner)
                classifierInfo.fixedKFunctionArity ?: classifierInfo.bigKFunctionArity
            }
                ?: simpleType.arguments.size.takeIf {
                    isKSuspendFunction() && it >= 1
                }
        }

    private fun IrType.dotNetFunctionExecutionArityOrNull(): Int? =
        (this as? IrSimpleType)?.let { simpleType ->
            (simpleType.classifier.owner as? IrClass)?.let { owner ->
                val classifierInfo = typeMapper.classifierInfo(owner)
                classifierInfo.fixedFunctionArity ?: classifierInfo.bigFunctionArity
            }
                ?: simpleType.arguments.size.takeIf {
                    isSuspendFunction() && it >= 1
                }
        }

    private fun IrType.dotNetBigCallableArityOrNull(): Int? =
        (this as? IrSimpleType)?.let { simpleType ->
            (simpleType.classifier.owner as? IrClass)?.let(typeMapper::classifierInfo)?.let { info ->
                info.bigFunctionArity ?: info.bigKFunctionArity
            } ?: simpleType.arguments.size.takeIf {
                (isSuspendFunction() || isKSuspendFunction()) && it >= BuiltInFunctionArity.BIG_ARITY
            }
        }

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
        val emitted = emitReifiedGenericInterfaceForeignDispatchCallOrNull(call, logicalResultType) ||
                emitReifiedGenericInterfaceObjectCarrierCapabilityCallOrNull(call, logicalResultType) ||
                emitGenericInterfaceCapabilityCallOrNull(call, logicalResultType) ||
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
        val genericOwnerCallTarget = typeMapper.genericOwnerCapabilityCallTarget(call)
        val callee = genericOwnerCallTarget
            ?: call.symbol.owner.let { it.resolveFakeOverride() ?: it.resolveFakeOverrideMaybeAbstract() ?: it }
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
                argumentType?.let { typeMapper.toDotNetIlGenericArgumentType(it) }
                    ?: dotNetUnsupported(
                        "call to '$calleeName' instantiates a type parameter with an unsupported type argument"
                    )
            }
        } else emptyList()
        val receiverType = if (info.isInstance) {
            val receiver = call.arguments.firstOrNull()
                ?: dotNetUnsupported("call to '$calleeName' has an unsupported argument shape")
            if (genericOwnerCallTarget != null) {
                mappedNaturalType(receiver)
            } else {
                val logicalReceiverType = typeMapper.toDotNetIlValueType(receiver.type)
                    ?: dotNetUnsupported(
                        "call to '$calleeName' through a receiver of unsupported type ${receiver.type.render()}"
                    )
                val naturalReceiverType = mappedNaturalType(receiver)
                naturalReceiverType?.takeIf { candidate ->
                    (candidate == DotNetIlValueType.Object &&
                            typeMapper.isNestedGenericOwnerConstruction(receiver.type)) ||
                            typeMapper.isGenericOwnerNestedConstructionCarrierOf(
                                candidate,
                                logicalReceiverType,
                            )
                } ?: logicalReceiverType
            }
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
                    "call to '$calleeName' through ${receiverType.nameInSignature} is not an " +
                            "instantiation of declaring class ${info.owner.ilTypeRef}`${info.owner.typeParameterCount}"
                )
            ownerToken = ownerView.nameInSignature
            classInstantiation = ownerView.arguments
        } else if (
            !info.isInstance &&
            info.owner.typeParameterCount > 0 &&
            callee.parent is IrClass
        ) {
            // A lifted local function and a JVM-shaped `$default` helper are static even when
            // their metadata owner is a generic class. CLR member references must still
            // instantiate that owner (`Owner<!0>::helper`), or the runtime rejects the call as
            // an open containing type. Prefer an explicit owner-shaped argument (the moved
            // receiver of a default helper is authoritative); a direct call from another method
            // of the same owner uses the owner's open `!n` instantiation (localfunprobe_s2).
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
                "call to static function '$calleeName' cannot determine the generic owner instantiation"
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
     *
     * A nested `try` introduces a CLR protected region, which must begin with an empty evaluation
     * stack. In that case every receiver/argument is first evaluated and stored in a local, then
     * reloaded in call order. This is the CIL equivalent of the JVM operand-stack freedom: source
     * evaluation order and exception timing stay unchanged without carrying an older operand
     * across an EH boundary.
     */
    fun emitArguments(
        arguments: List<IrExpression?>,
        parameterTypes: List<DotNetIlValueType>,
        calleeDescription: String,
    ) {
        if (arguments.size != parameterTypes.size) {
            dotNetUnsupported("call to $calleeDescription has an unsupported argument shape")
        }
        val actualArguments = arguments.map { argument ->
            argument ?: dotNetUnsupported("call to $calleeDescription relies on default argument values")
        }
        if (actualArguments.none { it.containsTryExpression() }) {
            for (indexedArgument in actualArguments.zip(parameterTypes).withIndex()) {
                val index = indexedArgument.index
                val argument = indexedArgument.value.first
                val parameterType = indexedArgument.value.second
                try {
                    emitExpression(argument, parameterType)
                } catch (failure: DotNetIlUnsupportedException) {
                    dotNetUnsupported(
                        "argument $index of $calleeDescription (${argument.type.render()} -> " +
                                "${parameterType.nameInSignature}) is not supported: ${failure.reason}"
                    )
                }
            }
            return
        }
        if (methodContext.stackDepth != 0) {
            dotNetUnsupported(
                "call to $calleeDescription containing a protected expression has older evaluation-stack operands"
            )
        }
        val slots = actualArguments.zip(parameterTypes).map { [argument, parameterType] ->
            emitExpression(argument, parameterType)
            spillToSyntheticLocal(parameterType, "<protectedArgument>")
        }
        for (slot in slots) {
            methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
        }
    }

    /** True when evaluating this expression itself enters a CLR exception-handling region. */
    private fun IrExpression.containsTryExpression(): Boolean {
        if (this is IrTry) return true
        var found = false
        acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (!found) element.acceptChildrenVoid(this)
            }

            override fun visitTry(aTry: IrTry) {
                found = true
            }
        })
        return found
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
        // Constructor kind belongs to the declaration being instantiated, not to the contextual
        // value representation of the call. A value class whose exact carrier is Array<E> maps
        // `call.type` to that vector, but `new V(array)` still constructs V's one nominal box
        // owner. Looking only at `constructedType` accidentally routed that box helper through
        // the Kotlin Array constructor intrinsic.
        if ((call.type.isSupportedDotNetPrimitiveArray() || call.type.isDotNetGenericArray()) &&
            (constructedType is DotNetIlValueType.PrimitiveArray ||
                    constructedType is DotNetIlValueType.GenericArray)
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
                argument?.let(constructorTypeMapper::toDotNetIlGenericArgumentType)
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
        if (field.correspondingPropertySymbol?.owner?.isConst == true) {
            val literal = field.initializer?.expression as? IrConst
                ?: dotNetUnsupported(
                    "const field '${field.name.asString()}' has no retained literal initializer"
                )
            // A CLR literal field has metadata but no storage, so ldsfld is invalid. Normally FIR
            // has already inlined the value; the one intentional survivor is the private body of
            // a Kotlin property-reference getter. Emit the retained Kotlin literal directly, as
            // the JVM property-reference implementation does, without adding a physical accessor.
            emitExpression(literal, expectedType)
            return
        }
        val semanticReceiver = expression.receiver?.let(::genericOwnerCapabilityNaturalTypeOrNull)
        val semanticGetter = field.correspondingPropertySymbol?.owner?.getter
            ?.let(genericOwnerCapabilitySlots::get)
        if (semanticReceiver is DotNetIlValueType.UserClass && semanticGetter != null) {
            val getterInfo = availableFunctions[semanticGetter]
                ?: dotNetUnsupported(
                    "generic-owner semantic field read '${field.name.asString()}' lost its capability getter"
                )
            val receiver = checkNotNull(expression.receiver)
            emitExpression(receiver, semanticReceiver)
            methodContext.emit(
                getterInfo.renderCallInstruction(
                    getterInfo.physicalMethodName ?: semanticGetter.dotNetIlMethodName(),
                    virtual = true,
                ),
                pops = getterInfo.signature.parameterTypes.size,
                pushes = 1,
            )
            val producedType = (getterInfo.signature.returnType as? DotNetIlReturnType.Value)?.type
                ?: dotNetUnsupported(
                    "generic-owner semantic getter for '${field.name.asString()}' has no value result"
                )
            if (!producedType.isDotNetAssignableTo(expectedType)) {
                if (producedType != DotNetIlValueType.Object) {
                    dotNetUnsupported(
                        "generic-owner semantic getter for '${field.name.asString()}' produces " +
                                "${producedType.nameInSignature} where ${expectedType.nameInSignature} is expected"
                    )
                }
                emitErasedCarrierAs(expectedType, "semantic field '${field.name.asString()}'")
            }
            return
        }
        val [classInfo, declaredFieldType, isStatic] = resolveFieldAccess(field)
        if (classInfo.typeParameterCount > 0) {
            // A field on a genuinely generic CLR owner keeps its declared open field type while
            // the owner token carries the receiver instantiation. Kotlin-owned generic-class
            // fields have an arity-zero erased owner and are handled by the ordinary branch.
            val [ownerView, receiver, receiverType] = resolveGenericFieldOwner(expression.receiver, field, isStatic)
            val fieldType = declaredFieldType.substituteDotNetTypeParameters(ownerView.arguments)
            if (!fieldType.isDotNetAssignableTo(expectedType)) {
                if (declaredFieldType != DotNetIlValueType.Object) {
                    dotNetUnsupported(
                        "field '${field.name.asString()}' has type ${fieldType.nameInSignature} " +
                                "where ${expectedType.nameInSignature} is expected"
                    )
                }
                emitExpression(receiver, receiverType)
                emitVolatilePrefix(field, declaredFieldType)
                methodContext.emit(
                    "ldfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString(), ownerView.nameInSignature)}",
                    pops = 1,
                    pushes = 1,
                )
                emitErasedCarrierAs(expectedType, "field '${field.name.asString()}'")
                return
            }
            emitExpression(receiver, receiverType)
            emitVolatilePrefix(field, fieldType)
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
                    emitVolatilePrefix(field, declaredFieldType)
                    methodContext.emit(
                        "ldsfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString())}",
                        pushes = 1,
                    )
                } else {
                    emitFieldReceiver(expression.receiver, field, classInfo)
                    emitVolatilePrefix(field, declaredFieldType)
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
            emitVolatilePrefix(field, declaredFieldType)
            methodContext.emit("ldsfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString())}", pushes = 1)
            return
        }
        emitFieldReceiver(expression.receiver, field, classInfo)
        emitVolatilePrefix(field, declaredFieldType)
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
            emitVolatilePrefix(field, fieldType)
            methodContext.emit(
                "stfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString(), ownerView.nameInSignature)}",
                pops = 2,
            )
            return
        }
        if (isStatic) {
            emitExpression(expression.value, declaredFieldType)
            emitVolatilePrefix(field, declaredFieldType)
            methodContext.emit("stsfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString())}", pops = 1)
            return
        }
        emitFieldReceiver(expression.receiver, field, classInfo)
        emitExpression(expression.value, declaredFieldType)
        emitVolatilePrefix(field, declaredFieldType)
        methodContext.emit("stfld ${classInfo.renderFieldReference(declaredFieldType, field.name.asString())}", pops = 2)
    }

    private fun emitVolatilePrefix(field: IrField, fieldType: DotNetIlValueType) {
        if (!field.hasAnnotation(DOTNET_VOLATILE_MARKER_FQ_NAME)) return
        val isReferenceCarrier = when (fieldType) {
            DotNetIlValueType.String,
            DotNetIlValueType.Object,
            is DotNetIlValueType.PrimitiveArray,
            is DotNetIlValueType.GenericArray,
            is DotNetIlValueType.ErasedGenericArray,
            is DotNetIlValueType.UserClass,
            is DotNetIlValueType.MappedClass,
            is DotNetIlValueType.GenericInstance,
            -> true
            else -> false
        }
        if (!isReferenceCarrier) {
            dotNetUnsupported(
                "volatile field '${field.name.asString()}' uses unsupported CLR carrier " +
                        fieldType.nameInSignature
            )
        }
        methodContext.emit("volatile.")
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
     * `const val` is handled before this lookup by [emitGetField]: it is a CLR `literal` field
     * without storage (`ldsfld` would fail at runtime), so any intentionally surviving read emits
     * the retained Kotlin literal instead.
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
        val fieldType = typeMapper.toDotNetIlFieldType(field)
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
        if (emitReifiedGenericInterfaceForeignDispatchCallOrNull(call, expectedType)) return
        if (emitReifiedGenericInterfaceObjectCarrierCapabilityCallOrNull(call, expectedType)) return
        if (emitGenericInterfaceCapabilityCallOrNull(call, expectedType)) return
        if (emitCallableCapabilityCallOrNull(call, expectedType)) return
        val returnType = emitCall(call)
        val producedType = (returnType as? DotNetIlReturnType.Value)?.type
        if (
            producedType != null &&
            !producedType.isDotNetAssignableTo(expectedType) &&
            emitGenericOwnerValueClassResultUnboxOrNull(call, producedType, expectedType)
        ) {
            return
        }
        if (
            producedType != null &&
            !producedType.isDotNetAssignableTo(expectedType) &&
            (typeMapper.genericOwnerCapabilityCallTarget(call) != null ||
                    typeMapper.isGenericOwnerForeignDispatchDeclaration(call.symbol.owner) ||
                    call.symbol.owner.isDotNetErasedObjectResult() ||
                    call.symbol.owner.isErasedGenericInterfaceMember() ||
                    call.symbol.owner.isErasedGenericClassMember() ||
                    call.symbol.owner.hasErasedNullableTypeParameterResult() ||
                    call.symbol.owner.returnType.isDotNetInvariantOpenNullableGenericArray())
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
            emitWideningCoercion(coercion)
        } else if (
            producedType == null &&
            call.type.isUnit() &&
            DotNetRuntimeTypes.unitType.isDotNetAssignableTo(expectedType)
        ) {
            // A Kotlin Unit call is physically void in its direct CLR slot. When Common IR keeps
            // it in value position (notably inside the explicit coroutine state machine), invoke
            // that void method first and then materialize the one Unit object. JVM codegen makes
            // the same value/void distinction at the use site; no callee ABI is widened.
            emitRuntimeUnitInstance()
        } else if (producedType == null) {
            dotNetUnsupported(
                "call to '${call.symbol.owner.name.asString()}' produces ${returnType.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
    }

    /**
     * Recovers the ordinary carrier of a value class returned through a reified owner parameter.
     * `BoxT<Str?>.boxed` physically returns the nominal `Str` box (the truthful CLR generic
     * argument), while Kotlin immediately observes the nullable String carrier. Keep null
     * unchanged and invoke the compiler-owned unbox ABI only for a non-null nominal value.
     */
    private fun emitGenericOwnerValueClassResultUnboxOrNull(
        call: IrCall,
        producedType: DotNetIlValueType,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val valueClass = call.type.dotNetValueClassOrNull() ?: return false
        val boxedType = typeMapper.toDotNetIlBoxedValueClassType(call.type) ?: return false
        if (producedType != boxedType || typeMapper.toDotNetIlValueType(call.type) != expectedType) return false
        val helper = valueClass.declarations.filterIsInstance<IrSimpleFunction>()
            .singleOrNull { function -> function.origin == DOTNET_VALUE_CLASS_UNBOX_HELPER }
            ?: return false
        val helperInfo = availableFunctions[helper] ?: typeMapper.referencedFunctionInfoOrNull(helper)
            ?: return false
        if (helperInfo.signature.parameterTypes.singleOrNull() != producedType ||
            helperInfo.signature.returnType != DotNetIlReturnType.Value(expectedType)
        ) {
            return false
        }
        val simpleType = call.type as? IrSimpleType ?: return false
        val methodInstantiation = if (helper.typeParameters.isEmpty()) {
            emptyList()
        } else {
            if (simpleType.arguments.size != helper.typeParameters.size) return false
            simpleType.arguments.map { argument ->
                val projection = argument as? IrTypeProjection ?: return false
                if (projection.variance != org.jetbrains.kotlin.types.Variance.INVARIANT) return false
                typeMapper.toDotNetIlGenericArgumentType(projection.type) ?: return false
            }
        }
        helperInfo.owner.assemblyName?.let(typeMapper::recordAssemblyReference)
        val emitUnboxCall = {
            methodContext.emit(
                helperInfo.renderCallInstruction(
                    helperInfo.physicalMethodName ?: helper.dotNetIlMethodName(),
                    methodInstantiation = methodInstantiation,
                ),
                pops = 1,
                pushes = 1,
            )
        }
        if (!simpleType.isMarkedNullable()) {
            emitUnboxCall()
            return true
        }
        if (!expectedType.isDotNetReferenceShaped()) return false
        val nonNullLabel = methodContext.nextLabel("genericOwnerValueClassNonNull")
        val doneLabel = methodContext.nextLabel("genericOwnerValueClassDone")
        methodContext.emit("dup", pops = 1, pushes = 2)
        methodContext.emitBranch("brtrue", nonNullLabel, pops = 1)
        methodContext.emit("pop", pops = 1)
        methodContext.emit("ldnull", pushes = 1)
        methodContext.emitGoto(doneLabel)
        methodContext.emitLabel(nonNullLabel)
        emitUnboxCall()
        methodContext.emitLabel(doneLabel)
        return true
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

    private fun reifiedGenericInterfaceFixedBarrierPolicy(
        source: IrSimpleFunction,
        usesRuntimeFalseBarrier: Boolean,
    ): DotNetCSharpWrongShapePolicy? {
        if (usesRuntimeFalseBarrier) {
            return DotNetCSharpWrongShapePolicy(
                checkedParameterCount = 1,
                fallback = DotNetCSharpWrongShapeFallback.FALSE,
                fallbackParameterIndex = null,
            )
        }
        return (typeMapper.genericOwnerWrongShapePolicy(source)
            ?: source.allOverridden().firstNotNullOfOrNull(typeMapper::genericOwnerWrongShapePolicy))
            ?.takeIf { policy ->
                policy.checkedParameterCount == 1 && policy.fallbackParameterIndex == null &&
                        policy.fallback != DotNetCSharpWrongShapeFallback.ARGUMENT
            }
    }

    private fun DotNetCSharpWrongShapePolicy.resultTypeOrNull(): DotNetIlValueType? =
        when (fallback) {
            DotNetCSharpWrongShapeFallback.FALSE -> DotNetIlValueType.Boolean
            DotNetCSharpWrongShapeFallback.MINUS_ONE -> DotNetIlValueType.Int32
            DotNetCSharpWrongShapeFallback.NULL -> DotNetIlValueType.Object
            DotNetCSharpWrongShapeFallback.ARGUMENT -> null
        }

    private fun emitReifiedGenericInterfaceFixedBarrier(
        policy: DotNetCSharpWrongShapePolicy,
    ) {
        when (policy.fallback) {
            DotNetCSharpWrongShapeFallback.FALSE -> {
                methodContext.emit("ldc.i4.0", pushes = 1)
                methodContext.emit(
                    "box ${coreLibraryReference}System.Boolean",
                    pops = 1,
                    pushes = 1,
                )
            }
            DotNetCSharpWrongShapeFallback.MINUS_ONE -> {
                methodContext.emit("ldc.i4.m1", pushes = 1)
                methodContext.emit(
                    "box ${coreLibraryReference}System.Int32",
                    pops = 1,
                    pushes = 1,
                )
            }
            DotNetCSharpWrongShapeFallback.NULL ->
                methodContext.emit("ldnull", pushes = 1)
            DotNetCSharpWrongShapeFallback.ARGUMENT ->
                error("Internal .NET backend error: argument fallback is not a fixed barrier")
        }
    }

    /**
     * Keeps the direct semantic capability as the fast path while admitting an ordinary foreign
     * implementation of the natural `I<T>`. The runtime selects exactly one constructed natural
     * interface or rejects an ambiguous multi-construction object. The admitted foreign shapes
     * are deliberately bounded to a value-result or Unit member with declaration-independent
     * inputs, one declaration-invariant owner-dependent input among independent inputs, the exact
     * sibling's one-natural-input Boolean member, one relative nested input, or an
     * upstream-authorized one-T-input fixed barrier. Name plus complete arity keeps the admitted
     * overloads unambiguous; broader signatures remain outside this path.
     */
    private fun emitReifiedGenericInterfaceForeignDispatchCallOrNull(
        call: IrCall,
        expectedType: DotNetIlValueType?,
    ): Boolean {
        if (call.arguments.isEmpty()) return false
        val source = call.symbol.owner.let {
            it.resolveFakeOverride() ?: it.resolveFakeOverrideMaybeAbstract() ?: it
        }
        val sourceOwner = source.parent as? IrClass ?: return false
        val receiver = call.arguments.first() ?: return false
        val regularArguments = call.arguments.drop(1).map { argument -> argument ?: return false }
        val semanticSlot = typeMapper.genericOwnerForeignDispatchCallTarget(call)
            ?: genericOwnerCapabilitySlotOrNull(source)?.takeIf {
                (source.parent as? IrClass)?.isInterface == true &&
                        mappedNaturalType(receiver) == DotNetIlValueType.Object
            }
            ?: return false
        val runtimeSemanticOwner = (semanticSlot.parent as? IrClass)?.takeIf { owner ->
            typeMapper.runtimeReifiedGenericInterfaceSemanticSlotOrNull(semanticSlot) != null
        }
        val semanticInfo = availableFunctions[semanticSlot]
            ?: typeMapper.referencedFunctionInfoOrNull(semanticSlot)
            ?: return false
        val naturalInfo = if (runtimeSemanticOwner != null) {
            val memberView = typeMapper.genericInterfaceMemberView(semanticSlot, runtimeSemanticOwner)
            typeMapper.genericInterfaceCapabilityFunctionInfoOrNull(semanticSlot, memberView)
        } else {
            availableFunctions[source] ?: typeMapper.referencedFunctionInfoOrNull(source)
        } ?: return false
        val naturalOwnerParameterCount = sourceOwner.typeParameters.size
        if (semanticInfo.owner.typeParameterCount != 0 ||
            naturalOwnerParameterCount == 0 ||
            naturalInfo.owner.typeParameterCount != naturalOwnerParameterCount ||
            semanticInfo.signature.parameterTypes.size != call.arguments.size ||
            naturalInfo.signature.parameterTypes.size != call.arguments.size
        ) {
            return false
        }
        fun DotNetIlValueType.isNaturalOwnerParameter(): Boolean =
            this is DotNetIlValueType.TypeParameter && !isMethodParameter &&
                    index in 0 until naturalOwnerParameterCount
        val naturalResultType =
            (naturalInfo.signature.returnType as? DotNetIlReturnType.Value)?.type
        val semanticResultType =
            (semanticInfo.signature.returnType as? DotNetIlReturnType.Value)?.type
        val interfaceInfo = typeMapper.genericInterfaceInfoOrNull(sourceOwner)
        val naturalInterfaceOwner = interfaceInfo?.declaredClassInfo
            ?: interfaceInfo?.canonicalClassInfo
        val exactInputType = naturalInfo.signature.parameterTypes.getOrNull(1)
            as? DotNetIlValueType.GenericInstance
        val usesRuntimeTypeArgumentFalseBarrier =
            DotNetRuntimeTypes.genericInterfaceUsesForeignTypeArgumentFalseBarrier(source)
        val usesRuntimeCollectionContainsAllFallback =
            DotNetRuntimeTypes.genericInterfaceUsesForeignCollectionContainsAllFallback(source)
        val relativeGenericInputIndex =
            DotNetRuntimeTypes.genericInterfaceRelativeGenericInputParameterIndex(source)
        fun DotNetIlValueType.referencesNaturalOwnerParameter(): Boolean = when (this) {
            is DotNetIlValueType.TypeParameter -> !isMethodParameter &&
                    index in 0 until naturalOwnerParameterCount
            is DotNetIlValueType.GenericInstance -> arguments.any {
                it.referencesNaturalOwnerParameter()
            }
            is DotNetIlValueType.GenericArray -> elementType.referencesNaturalOwnerParameter()
            is DotNetIlValueType.NullableValue -> elementType.referencesNaturalOwnerParameter()
            else -> false
        }
        fun hasDeclarationIndependentInput(index: Int): Boolean {
            val semanticParameter = semanticInfo.signature.parameterTypes[index + 1]
            val naturalParameter = naturalInfo.signature.parameterTypes[index + 1]
            return semanticParameter == naturalParameter &&
                    !naturalParameter.referencesNaturalOwnerParameter()
        }
        fun hasOnlyDeclarationIndependentOtherInputs(selectedIndex: Int): Boolean =
            regularArguments.indices.all { index ->
                index == selectedIndex || hasDeclarationIndependentInput(index)
            }
        val hasDeclarationIndependentInputs =
            regularArguments.indices.all(::hasDeclarationIndependentInput)
        val invariantInputIndex = regularArguments.indices.filter { index ->
            semanticInfo.signature.parameterTypes[index + 1] == DotNetIlValueType.Object &&
                    naturalInfo.signature.parameterTypes[index + 1].isNaturalOwnerParameter()
        }.singleOrNull()
        val hasInvariantOwnerInput = invariantInputIndex != null &&
                sourceOwner.typeParameters.singleOrNull()?.variance ==
                    org.jetbrains.kotlin.types.Variance.INVARIANT &&
                hasOnlyDeclarationIndependentOtherInputs(invariantInputIndex)
        val isProducer = hasDeclarationIndependentInputs && naturalResultType != null &&
                semanticResultType != null &&
                (semanticResultType == DotNetIlValueType.Object ||
                        semanticResultType == naturalResultType ||
                        semanticResultType.isDotNetAssignableTo(DotNetIlValueType.Object))
        val isDeclarationIndependentUnit = hasDeclarationIndependentInputs &&
                semanticInfo.signature.returnType == DotNetIlReturnType.Void &&
                naturalInfo.signature.returnType == DotNetIlReturnType.Void
        val isInvariantInputUnit = hasInvariantOwnerInput &&
                semanticInfo.signature.returnType == DotNetIlReturnType.Void &&
                naturalInfo.signature.returnType == DotNetIlReturnType.Void
        val isInvariantInputValue = hasInvariantOwnerInput &&
                naturalResultType != null && semanticResultType != null &&
                (
                    semanticResultType == DotNetIlValueType.Object &&
                            naturalResultType.isNaturalOwnerParameter() ||
                            semanticResultType == naturalResultType &&
                            !naturalResultType.referencesNaturalOwnerParameter()
                )
        val isExactInputBoolean = regularArguments.size == 1 &&
                interfaceInfo?.exactClassInfo?.ilTypeRef == naturalInfo.owner.ilTypeRef &&
                naturalInterfaceOwner != null &&
                (exactInputType?.classInfo?.ilTypeRef == naturalInterfaceOwner.ilTypeRef ||
                        usesRuntimeCollectionContainsAllFallback) &&
                exactInputType?.arguments?.singleOrNull()?.isNaturalOwnerParameter() == true &&
                semanticInfo.signature.parameterTypes[1] == DotNetIlValueType.Object &&
                semanticInfo.signature.returnType ==
                    DotNetIlReturnType.Value(DotNetIlValueType.Boolean) &&
                naturalInfo.signature.returnType ==
                    DotNetIlReturnType.Value(DotNetIlValueType.Boolean)
        val relativeGenericInputType = relativeGenericInputIndex?.let { index ->
            val naturalParameter = naturalInfo.signature.parameterTypes.getOrNull(index + 1)
                as? DotNetIlValueType.GenericInstance
                ?: return false
            val argument = regularArguments.getOrNull(index) ?: return false
            mappedNaturalType(argument)
                ?.dotNetViewAsGenericOwner(naturalParameter.classInfo)
                ?.arguments
                ?.singleOrNull()
                ?: return false
        }
        val isRelativeGenericInputBoolean = relativeGenericInputType != null &&
                sourceOwner.typeParameters.singleOrNull()?.variance ==
                    org.jetbrains.kotlin.types.Variance.INVARIANT &&
                semanticInfo.signature.parameterTypes[
                    checkNotNull(relativeGenericInputIndex) + 1
                ] == DotNetIlValueType.Object &&
                hasOnlyDeclarationIndependentOtherInputs(relativeGenericInputIndex) &&
                semanticInfo.signature.returnType ==
                    DotNetIlReturnType.Value(DotNetIlValueType.Boolean) &&
                naturalInfo.signature.returnType ==
                    DotNetIlReturnType.Value(DotNetIlValueType.Boolean)
        val fixedBarrierPolicy = reifiedGenericInterfaceFixedBarrierPolicy(
            source,
            usesRuntimeTypeArgumentFalseBarrier,
        )
        val fixedBarrierResultType = fixedBarrierPolicy?.resultTypeOrNull()
        val isFixedBarrier = regularArguments.size == 1 &&
                interfaceInfo?.exactClassInfo?.ilTypeRef == naturalInfo.owner.ilTypeRef &&
                naturalInterfaceOwner != null &&
                naturalInfo.signature.parameterTypes[1].isNaturalOwnerParameter() &&
                semanticInfo.signature.parameterTypes[1] == DotNetIlValueType.Object &&
                fixedBarrierPolicy != null && fixedBarrierResultType != null &&
                (fixedBarrierPolicy.fallback == DotNetCSharpWrongShapeFallback.NULL &&
                        (semanticResultType?.isDotNetReferenceShaped() == true &&
                                naturalResultType?.isDotNetReferenceShaped() == true) ||
                        semanticInfo.signature.returnType ==
                            DotNetIlReturnType.Value(fixedBarrierResultType) &&
                        naturalInfo.signature.returnType ==
                            DotNetIlReturnType.Value(fixedBarrierResultType))
        if (!isProducer && !isDeclarationIndependentUnit && !isInvariantInputUnit &&
            !isInvariantInputValue &&
            !isExactInputBoolean && !isRelativeGenericInputBoolean && !isFixedBarrier
        ) {
            return false
        }
        val hasValueResult = isProducer || isExactInputBoolean ||
                isInvariantInputValue || isRelativeGenericInputBoolean || isFixedBarrier
        val resultNarrowing = if (hasValueResult) {
            val resultType = expectedType ?: return false
            when (resultType) {
                DotNetIlValueType.Object -> null
                else -> resultType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference)
                    ?: return false
            }
        } else {
            if (expectedType != null &&
                !DotNetRuntimeTypes.unitType.isDotNetAssignableTo(expectedType)
            ) {
                return false
            }
            null
        }
        val foreignSelectionOwner = if (isExactInputBoolean || isFixedBarrier) {
            checkNotNull(naturalInterfaceOwner)
        } else {
            naturalInfo.owner
        }
        semanticInfo.owner.assemblyName?.let(typeMapper::recordAssemblyReference)
        naturalInfo.owner.assemblyName?.let(typeMapper::recordAssemblyReference)
        foreignSelectionOwner.assemblyName?.let(typeMapper::recordAssemblyReference)
        if (usesRuntimeCollectionContainsAllFallback) {
            exactInputType?.classInfo?.assemblyName?.let(typeMapper::recordAssemblyReference)
        }
        emitReifiedGenericInterfaceForeignReceiver(
            receiver,
            foreignSelectionOwner,
        )
        val receiverSlot = spillToSyntheticLocal(
            DotNetIlValueType.Object,
            "<reifiedGenericInterfaceForeignReceiver>",
        )
        val regularArgumentSlots = regularArguments.mapIndexed { index, argument ->
            val parameterType = semanticInfo.signature.parameterTypes[index + 1]
            emitExpression(argument, parameterType)
            spillToSyntheticLocal(
                parameterType,
                "<reifiedGenericInterfaceForeignArgument$index>",
            )
        }
        val capabilityType = DotNetIlValueType.UserClass(semanticInfo.owner)
        val capabilitySlot = methodContext.declareSyntheticLocal(
            capabilityType,
            "<reifiedGenericInterfaceCapability>",
        )
        val resultSlot = if (hasValueResult) {
            methodContext.declareSyntheticLocal(
                DotNetIlValueType.Object,
                "<reifiedGenericInterfaceForeignResult>",
            )
        } else {
            null
        }
        val foreignLabel = methodContext.nextLabel("reifiedGenericInterfaceForeign")
        val joinLabel = methodContext.nextLabel("reifiedGenericInterfaceJoin")

        methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
        methodContext.emit("isinst ${capabilityType.nameInSignature}", pops = 1, pushes = 1)
        methodContext.emit(storeLocalInstruction(capabilitySlot.index), pops = 1)
        methodContext.emit(loadLocalInstruction(capabilitySlot.index), pushes = 1)
        methodContext.emitBranch("brfalse", foreignLabel, pops = 1)
        methodContext.emit(loadLocalInstruction(capabilitySlot.index), pushes = 1)
        regularArgumentSlots.forEach { slot ->
            methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
        }
        methodContext.emit(
            semanticInfo.renderCallInstruction(
                semanticInfo.physicalMethodName ?: semanticSlot.dotNetIlMethodName(),
                virtual = true,
                ownerToken = semanticInfo.owner.ilTypeRef,
            ),
            pops = semanticInfo.signature.parameterTypes.size,
            pushes = if (hasValueResult) 1 else 0,
        )
        if (hasValueResult && semanticResultType != DotNetIlValueType.Object &&
            !checkNotNull(semanticResultType).isDotNetAssignableTo(DotNetIlValueType.Object)
        ) {
            // Both branches join through the runtime helper's object result. Preserve that one
            // stack shape when a declaration-independent query returns an unboxed CLR value.
            methodContext.emit(
                "box ${checkNotNull(semanticResultType).nameInSignature}",
                pops = 1,
                pushes = 1,
            )
        }
        resultSlot?.let { slot ->
            methodContext.emit(storeLocalInstruction(slot.index), pops = 1)
        }
        methodContext.emitGoto(joinLabel)

        methodContext.emitLabel(foreignLabel)
        methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
        emitSystemTypeOrNull(foreignSelectionOwner.ilTypeRef)
        if (usesRuntimeCollectionContainsAllFallback) {
            emitSystemTypeOrNull(checkNotNull(exactInputType).classInfo.ilTypeRef)
        } else if (isRelativeGenericInputBoolean) {
            emitSystemTypeOrNull(checkNotNull(relativeGenericInputType).nameInSignature)
        }
        methodContext.emit(
            "ldstr ${(naturalInfo.physicalMethodName ?: source.dotNetIlMethodName()).toIlStringLiteral()}",
            pushes = 1,
        )
        if (regularArgumentSlots.isEmpty()) {
            methodContext.emit("ldnull", pushes = 1)
        } else {
            methodContext.emit("ldc.i4.${regularArgumentSlots.size}", pushes = 1)
            methodContext.emit("newarr ${coreLibraryReference}System.Object", pops = 1, pushes = 1)
            regularArgumentSlots.forEachIndexed { index, slot ->
                methodContext.emit("dup", pops = 1, pushes = 2)
                methodContext.emit("ldc.i4.$index", pushes = 1)
                methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
                methodContext.emit("stelem.ref", pops = 3)
            }
        }
        if (isFixedBarrier) {
            emitReifiedGenericInterfaceFixedBarrier(checkNotNull(fixedBarrierPolicy))
        }
        methodContext.emit(
            when {
                usesRuntimeCollectionContainsAllFallback ->
                    DotNetGenericInterfaceRuntime.invokeUniqueCollectionContainsAllCallInstruction(
                        coreLibraryReference
                    )
                isRelativeGenericInputBoolean ->
                    DotNetGenericInterfaceRuntime
                        .invokeUniqueRelativeGenericInputCallInstruction(coreLibraryReference)
                isExactInputBoolean ->
                    DotNetGenericInterfaceRuntime.invokeUniqueConcreteUnaryMemberCallInstruction(
                        coreLibraryReference
                    )
                isFixedBarrier ->
                    DotNetGenericInterfaceRuntime
                        .invokeUniqueTypeArgumentUnaryMemberWithBarrierCallInstruction(
                            coreLibraryReference
                        )
                else -> DotNetGenericInterfaceRuntime.invokeUniqueMemberCallInstruction(
                    coreLibraryReference
                )
            },
            pops = if (usesRuntimeCollectionContainsAllFallback ||
                isRelativeGenericInputBoolean || isFixedBarrier
            ) 5 else 4,
            pushes = 1,
        )
        if (resultSlot != null) {
            methodContext.emit(storeLocalInstruction(resultSlot.index), pops = 1)
        } else {
            methodContext.emit("pop", pops = 1)
        }

        methodContext.emitLabel(joinLabel)
        if (resultSlot != null) {
            methodContext.emit(loadLocalInstruction(resultSlot.index), pushes = 1)
            resultNarrowing?.let { instruction ->
                methodContext.emit(instruction, pops = 1, pushes = 1)
            }
        } else if (expectedType != null) {
            emitRuntimeUnitInstance()
        }
        return true
    }

    private fun genericOwnerCapabilitySlotOrNull(source: IrSimpleFunction): IrSimpleFunction? =
        genericOwnerCapabilitySlots[source]
            ?: typeMapper.runtimeReifiedGenericInterfaceSemanticSlotOrNull(source)
            ?: source.allOverridden().firstNotNullOfOrNull { overridden ->
                genericOwnerCapabilitySlots[overridden]
                    ?: typeMapper.runtimeReifiedGenericInterfaceSemanticSlotOrNull(overridden)
            }

    private fun genericOwnerObjectCarrierCallReturnTypeOrNull(
        call: IrCall,
    ): DotNetIlValueType? {
        val source = call.symbol.owner.let {
            it.resolveFakeOverride() ?: it.resolveFakeOverrideMaybeAbstract() ?: it
        }
        val semanticSlot = genericOwnerCapabilitySlotOrNull(source) ?: return null
        val receiver = call.arguments.firstOrNull() ?: return null
        if (mappedNaturalType(receiver) != DotNetIlValueType.Object) return null
        val semanticInfo = availableFunctions[semanticSlot]
            ?: typeMapper.referencedFunctionInfoOrNull(semanticSlot)
            ?: return null
        val semanticReturnType = (semanticInfo.signature.returnType as? DotNetIlReturnType.Value)?.type
            ?: return null
        val runtimeSemanticOwner = (semanticSlot.parent as? IrClass)?.takeIf { owner ->
            typeMapper.runtimeReifiedGenericInterfaceSemanticSlotOrNull(semanticSlot) != null &&
                    typeMapper.isRuntimeReifiedGenericInterface(owner)
        }
        if (runtimeSemanticOwner != null) {
            val memberView = typeMapper.genericInterfaceMemberView(semanticSlot, runtimeSemanticOwner)
            val naturalReturnType = typeMapper.genericInterfaceCapabilityFunctionInfoOrNull(
                semanticSlot,
                memberView,
            )?.signature?.returnType.let { it as? DotNetIlReturnType.Value }?.type
            if (naturalReturnType != null && naturalReturnType != semanticReturnType &&
                naturalReturnType.isDotNetReferenceShaped() && semanticReturnType.isDotNetReferenceShaped()
            ) {
                // The capability branch returns the canonical reference while a natural-only
                // foreign implementation returns I<T>. Their common identity-preserving carrier
                // is object; the next actual member use performs capability-or-natural dispatch.
                return DotNetIlValueType.Object
            }
        }
        return semanticReturnType
    }

    /**
     * Enters a generic owner semantic slot only when the receiver expression physically comes
     * from an object-carried open nested construction. Ordinary closed receivers remain on their
     * natural CLR MethodDef. Covariant no-input interfaces take the capability-or-foreign
     * dispatcher above; input-bearing foreign interfaces outside the bounded invariant cell
     * remain unclaimed, while a Kotlin class instance always inherits its compiler capability on
     * the same object.
     */
    private fun emitReifiedGenericInterfaceObjectCarrierCapabilityCallOrNull(
        call: IrCall,
        expectedType: DotNetIlValueType?,
    ): Boolean {
        if (typeMapper.genericOwnerCapabilityCallTarget(call) != null ||
            typeMapper.genericOwnerForeignDispatchCallTarget(call) != null
        ) {
            return false
        }
        val source = call.symbol.owner.let {
            it.resolveFakeOverride() ?: it.resolveFakeOverrideMaybeAbstract() ?: it
        }
        val semanticSlot = genericOwnerCapabilitySlotOrNull(source) ?: return false
        val receiver = call.arguments.firstOrNull() ?: return false
        if (mappedNaturalType(receiver) != DotNetIlValueType.Object) return false
        val semanticInfo = availableFunctions[semanticSlot]
            ?: typeMapper.referencedFunctionInfoOrNull(semanticSlot)
            ?: return false
        if (semanticInfo.owner.typeParameterCount != 0 ||
            semanticInfo.signature.parameterTypes.size != call.arguments.size
        ) {
            return false
        }
        val semanticReturnType = semanticInfo.signature.returnType
        val resultCoercion = when (semanticReturnType) {
            DotNetIlReturnType.Void -> {
                if (expectedType != null &&
                    !DotNetRuntimeTypes.unitType.isDotNetAssignableTo(expectedType)
                ) {
                    return false
                }
                null
            }
            is DotNetIlReturnType.Value -> {
                val resultType = expectedType ?: return false
                when {
                    semanticReturnType.type.isDotNetAssignableTo(resultType) -> null
                    semanticReturnType.type == DotNetIlValueType.Object ->
                        resultType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference)
                    else -> return false
                }
            }
        }
        semanticInfo.owner.assemblyName?.let(typeMapper::recordAssemblyReference)
        emitArguments(
            call.arguments,
            semanticInfo.signature.parameterTypes,
            "nested object-carrier call to '${source.name.asString()}'",
        )
        methodContext.emit(
            semanticInfo.renderCallInstruction(
                semanticInfo.physicalMethodName ?: semanticSlot.dotNetIlMethodName(),
                virtual = true,
                ownerToken = semanticInfo.owner.ilTypeRef,
            ),
            pops = semanticInfo.signature.parameterTypes.size,
            pushes = if (semanticReturnType is DotNetIlReturnType.Value) 1 else 0,
        )
        resultCoercion?.let { instruction ->
            methodContext.emit(instruction, pops = 1, pushes = 1)
        }
        if (semanticReturnType == DotNetIlReturnType.Void && expectedType != null) {
            emitRuntimeUnitInstance()
        }
        return true
    }

    /**
     * Preserves the original object carrier after a classifier-erased FIR smartcast. The call
     * route is admitted only after the reified-interface lowering has proved that semantic
     * dispatch may need an ordinary foreign I<T> implementation. Re-emitting FIR's logical
     * `Any? -> I<*>` IMPLICIT_CAST here would reconstruct the hidden capability and reject that
     * implementation before the capability-or-natural dispatcher can inspect it.
     */
    private fun emitReifiedGenericInterfaceForeignReceiver(
        receiver: IrExpression,
        naturalOwner: DotNetIlClassInfo,
    ) {
        val classifierSmartCastArgument = (receiver as? IrTypeOperatorCall)
            ?.takeIf { expression ->
                expression.operator == IrTypeOperator.IMPLICIT_CAST &&
                        typeMapper.genericOwnerNaturalRuntimeClassifierInfoOrNull(
                            expression.typeOperand,
                        )?.ilTypeRef == naturalOwner.ilTypeRef
            }
            ?.argument
        emitExpression(
            classifierSmartCastArgument ?: receiver,
            DotNetIlValueType.Object,
        )
    }

    private fun IrExpression.readsGenericOwnerForeignDispatchDeclaration(): Boolean = when (this) {
        is IrGetValue -> typeMapper.isGenericOwnerForeignDispatchDeclaration(symbol.owner)
        is IrGetField -> typeMapper.isGenericOwnerForeignDispatchDeclaration(symbol.owner)
        is IrCall -> typeMapper.isGenericOwnerForeignDispatchDeclaration(symbol.owner) ||
                typeMapper.genericOwnerForeignDispatchCallTarget(this) != null
        is IrTypeOperatorCall -> when (operator) {
            IrTypeOperator.CAST,
            IrTypeOperator.SAFE_CAST,
                -> typeMapper.genericOwnerNaturalRuntimeClassifierInfoOrNull(typeOperand) != null
            IrTypeOperator.IMPLICIT_CAST,
            IrTypeOperator.IMPLICIT_NOTNULL,
                -> argument.readsGenericOwnerForeignDispatchDeclaration()
            else -> false
        }
        else -> false
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
                argument?.let(mapper::toDotNetIlGenericArgumentType) ?: return null
            }
        }

        fun relativeGenericMethodInstantiation(
            info: DotNetIlFunctionInfo,
        ): List<DotNetIlValueType>? {
            val inputIndex =
                DotNetRuntimeTypes.genericInterfaceRelativeGenericInputParameterIndex(callee)
                    ?: return null
            val physicalIndex = inputIndex + if (info.signature.hasThis) 1 else 0
            val inputOwner = (info.signature.parameterTypes.getOrNull(physicalIndex)
                    as? DotNetIlValueType.GenericInstance)?.classInfo
                ?: return null
            val argument = call.arguments.getOrNull(inputIndex + 1) ?: return null
            val argumentView = mappedNaturalType(argument)
                ?.dotNetViewAsGenericOwner(inputOwner)
                ?: return null
            return listOf(argumentView.arguments.singleOrNull() ?: return null)
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
        val usesRuntimeTypeArgumentFalseBarrier =
            DotNetRuntimeTypes.genericInterfaceUsesForeignTypeArgumentFalseBarrier(callee)
        val usesRuntimeCollectionContainsAllFallback =
            DotNetRuntimeTypes.genericInterfaceUsesForeignCollectionContainsAllFallback(callee)
        val fixedBarrierPolicy = reifiedGenericInterfaceFixedBarrierPolicy(
            callee,
            usesRuntimeTypeArgumentFalseBarrier,
        )
        val runtimeForeignNaturalOwner = typeMapper.genericInterfaceInfoOrNull(interfaceClass)
            ?.declaredClassInfo
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
        val capabilityInfo = typeMapper.genericInterfaceCapabilityFunctionInfoOrNull(
            callee,
            memberView,
        ) ?: return false
        val capabilitySignature = capabilityInfo.signature
        val usesRelativeGenericInput =
            DotNetRuntimeTypes.genericInterfaceRelativeGenericInputParameterIndex(callee) != null
        val capabilityMethodInstantiation = if (usesRelativeGenericInput) {
            relativeGenericMethodInstantiation(capabilityInfo)
        } else {
            methodInstantiation(capabilitySignatureMapper)
        } ?: return false
        val runtimeCollectionParameterOwner = capabilitySignature.parameterTypes.getOrNull(1)
            .let { it as? DotNetIlValueType.GenericInstance }
            ?.classInfo
        val hasRuntimeForeignInputFallback = runtimeForeignNaturalOwner != null &&
                (fixedBarrierPolicy != null ||
                        (usesRuntimeCollectionContainsAllFallback &&
                                runtimeCollectionParameterOwner != null) ||
                        usesRelativeGenericInput)
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
        val capabilityResultCoercion: DotNetOptionalCoercion?
        val canonicalResultCoercion: DotNetIlWideningCoercion?
        if (expectedType == null) {
            if (capabilityReturnType != DotNetIlReturnType.Void || canonical.returnType != DotNetIlReturnType.Void) {
                return false
            }
            capabilityResultCoercion = null
            canonicalResultCoercion = null
        } else {
            val capabilityResultType = (capabilityReturnType as? DotNetIlReturnType.Value)?.type ?: return false
            val canonicalResultType = (canonical.returnType as? DotNetIlReturnType.Value)?.type ?: return false
            capabilityResultCoercion = wideningCoercionOrNull(capabilityResultType, expectedType) ?: return false
            canonicalResultCoercion = when {
                canonicalResultType.isDotNetAssignableTo(expectedType) -> null
                canonicalResultType == DotNetIlValueType.Object ->
                    expectedType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference)
                        ?.let(::DotNetIlWideningCoercion) ?: return false
                else -> wideningCoercionOrNull(canonicalResultType, expectedType)?.coercion ?: return false
            }
        }

        // The ordinary rehearsal boundary may already be the natural I<T>, while the fallback
        // sibling is the arity-zero semantic interface. Spill through object so neither view is
        // asserted before its own `isinst`; the receiver is still evaluated exactly once.
        emitExpression(receiver, DotNetIlValueType.Object)
        val receiverSlot = spillToSyntheticLocal(
            DotNetIlValueType.Object,
            "<genericInterfaceReceiver>",
        )
        val capabilitySlot = methodContext.declareSyntheticLocal(
            capabilityReceiverType,
            "<genericInterfaceCapability>",
        )
        val canonicalSlot = methodContext.declareSyntheticLocal(
            canonicalReceiverType,
            "<genericInterfaceCanonical>",
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
        capabilityResultCoercion?.coercion?.let(::emitWideningCoercion)
        resultSlot?.let { slot -> methodContext.emit(storeLocalInstruction(slot.index), pops = 1) }
        methodContext.emitGoto(joinLabel)

        methodContext.emitLabel(fallbackLabel)
        methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
        methodContext.emit("isinst ${canonicalReceiverType.nameInSignature}", pops = 1, pushes = 1)
        methodContext.emit(storeLocalInstruction(canonicalSlot.index), pops = 1)
        methodContext.emit(loadLocalInstruction(canonicalSlot.index), pushes = 1)
        val missingCanonicalLabel = methodContext.nextLabel("genericInterfaceMissingCanonical")
        methodContext.emitBranch("brfalse", missingCanonicalLabel, pops = 1)
        methodContext.emit(loadLocalInstruction(canonicalSlot.index), pushes = 1)
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
        canonicalResultCoercion?.let(::emitWideningCoercion)
        resultSlot?.let { slot -> methodContext.emit(storeLocalInstruction(slot.index), pops = 1) }
        methodContext.emitGoto(joinLabel)

        methodContext.emitLabel(missingCanonicalLabel)
        if (hasRuntimeForeignInputFallback) {
            methodContext.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
            emitSystemTypeOrNull(checkNotNull(runtimeForeignNaturalOwner).ilTypeRef)
            if (usesRuntimeCollectionContainsAllFallback) {
                emitSystemTypeOrNull(checkNotNull(runtimeCollectionParameterOwner).ilTypeRef)
            } else if (usesRelativeGenericInput) {
                emitSystemTypeOrNull(capabilityMethodInstantiation.single().nameInSignature)
            }
            methodContext.emit(
                "ldstr ${typeMapper.genericInterfaceTypedMethodName(callee).toIlStringLiteral()}",
                pushes = 1,
            )
            val regularArguments = call.arguments.drop(1)
            methodContext.emit("ldc.i4.${regularArguments.size}", pushes = 1)
            methodContext.emit("newarr ${coreLibraryReference}System.Object", pops = 1, pushes = 1)
            regularArguments.forEachIndexed { index, argument ->
                methodContext.emit("dup", pops = 1, pushes = 2)
                methodContext.emit("ldc.i4.$index", pushes = 1)
                emitExpression(argument, DotNetIlValueType.Object)
                methodContext.emit("stelem.ref", pops = 3)
            }
            if (!usesRuntimeCollectionContainsAllFallback) {
                emitReifiedGenericInterfaceFixedBarrier(checkNotNull(fixedBarrierPolicy))
            }
            methodContext.emit(
                if (usesRuntimeCollectionContainsAllFallback) {
                    DotNetGenericInterfaceRuntime.invokeUniqueCollectionContainsAllCallInstruction(
                        coreLibraryReference
                    )
                } else if (usesRelativeGenericInput) {
                    DotNetGenericInterfaceRuntime
                        .invokeUniqueRelativeGenericInputCallInstruction(coreLibraryReference)
                } else {
                    DotNetGenericInterfaceRuntime
                        .invokeUniqueTypeArgumentUnaryMemberWithBarrierCallInstruction(
                            coreLibraryReference
                        )
                },
                pops = 5,
                pushes = 1,
            )
            val foreignResultType = expectedType ?: error(
                "Internal .NET backend error: Runtime input fallback lost its result"
            )
            if (foreignResultType != DotNetIlValueType.Object) {
                methodContext.emit(
                    checkNotNull(foreignResultType.dotNetObjectNarrowingInstructionOrNull(coreLibraryReference)),
                    pops = 1,
                    pushes = 1,
                )
            }
            resultSlot?.let { slot -> methodContext.emit(storeLocalInstruction(slot.index), pops = 1) }
            methodContext.emitGoto(joinLabel)
        } else {
            emitThrowClassCastException()
        }

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
            // ExactFunctionN is a CLR-generic capability. Its constructed arguments therefore
            // use the nominal value-class TypeDef at every value-class boundary, just like an
            // ordinary generic method instantiation; the surrounding IR autoboxing calls own
            // the carrier transitions. Mapping these as ordinary value positions would ask a
            // nominal box helper to produce the underlying carrier.
            typeMapper.toDotNetIlGenericArgumentType(type) ?: return false
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
                shape.argumentCoercions[index]?.let(::emitWideningCoercion)
            }
            methodContext.emit(
                DotNetRuntimeTypes.exactInvokeCallInstruction(shape.exactType),
                pops = arity + 1,
                pushes = 1,
            )
            shape.resultCoercion?.let(::emitWideningCoercion)
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
                emitWideningCoercion(coercion)
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
            coercion.coercion
        }
        val resultCoercion = wideningCoercionOrNull(capabilityTypes.last(), callSiteTypes.last()) ?: return null
        return DotNetExactCallableInvocationShape(exactType, argumentCoercions, resultCoercion.coercion)
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
    ): DotNetOptionalCoercion? = when {
        from.isDotNetAssignableTo(to) -> DotNetOptionalCoercion(null)
        else -> dotNetWideningCoercionOrNull(from, to, coreLibraryReference)
            ?.let { coercion -> DotNetOptionalCoercion(coercion) }
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
        val argumentCoercions: List<DotNetIlWideningCoercion?>,
        val resultCoercion: DotNetIlWideningCoercion?,
    )

    private data class DotNetOptionalCoercion(
        val coercion: DotNetIlWideningCoercion?,
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
            // concrete consumer later requires the enclosing function's open T. A null-tested
            // `T?` parameter has the same physical object slot; FIR may retain `T?` on the bare
            // read while the typed consumer records the frontend-proven non-null `T` expectation.
            // Materialize precisely those proven erased reads with
            // `unbox.any !n/!!n`: ECMA-335 defines it for reference and value substitutions.
            // Restricting the rule to locals or the exact nullable-parameter smartcast shape
            // prevents an object-to-arbitrary-type escape hatch. A corrupt value still fails at
            // first logical use.
            if (slotType == DotNetIlValueType.Object &&
                expectedType is DotNetIlValueType.TypeParameter &&
                (expression.symbol.owner is IrVariable || expression.isNullableTypeParameterRecovery(expectedType))
            ) {
                emitLoadSlot(slot)
                methodContext.emit(
                    "unbox.any ${expectedType.nameInSignature}",
                    pops = 1,
                    pushes = 1,
                )
                return
            }
            // A method relationship such as `C : R` is a real CLR GenericParamConstraint. FIR's
            // typed consumer can therefore request R from a bare C slot without an explicit IR
            // cast. The verifier-compatible widening is `box C; unbox.any R`: reference
            // substitutions retain identity, while a value substitution is recovered as an
            // actual R value rather than leaving an object reference in an R-typed slot.
            if (slotType is DotNetIlValueType.TypeParameter &&
                expectedType is DotNetIlValueType.TypeParameter &&
                slotType.isConstrainedTo(expectedType)
            ) {
                emitLoadSlot(slot)
                emitWideningCoercion(
                    checkNotNull(
                        dotNetWideningCoercionOrNull(
                            slotType,
                            expectedType,
                            coreLibraryReference,
                        )
                    ) {
                        "A relative generic constraint has no CLR widening coercion"
                    }
                )
                return
            }
            // Keep an exact natural C<T> local on the ordinary CLR path, but permit a later
            // Kotlin-variant view to select the same object's non-generic capability. The
            // lowering records this only for immutable aliases whose producer is a Kotlin class
            // emitted with both InterfaceImpl rows; arbitrary foreign I<T> objects do not gain
            // this escape hatch.
            if (slotType.isDotNetReferenceShaped() &&
                expectedType.isDotNetReferenceShaped() &&
                typeMapper.isGenericOwnerCapabilityBearingDeclaration(expression.symbol.owner) &&
                typeMapper.isGenericOwnerCapabilityViewOf(expectedType, slotType)
            ) {
                emitLoadSlot(slot)
                methodContext.emit("castclass ${expectedType.nameInSignature}", pops = 1, pushes = 1)
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
            // A final nullable-primitive method bound keeps a genuine CLR method token: both
            // `R = Int` and `R = Int?` are valid substitutions, so neither a primitive slot nor a
            // CLR constraint is truthful. After a Kotlin null check, FIR nevertheless reads the
            // same R value at its proven non-null primitive upper bound. Box the open token first
            // (Nullable<T> becomes boxed-T-or-null), then recover the exact primitive. This is the
            // generic equivalent of the NullableValue branch above and fails at the narrowed use
            // if an unsound null ever reaches it.
            if (slotType is DotNetIlValueType.TypeParameter &&
                expression.isNullablePrimitiveTypeParameterNarrowing(expectedType)
            ) {
                emitLoadSlot(slot)
                methodContext.emit(
                    "box ${slotType.nameInSignature}",
                    pops = 1,
                    pushes = 1,
                )
                methodContext.emit(
                    "unbox.any ${expectedType.nameInSignature}",
                    pops = 1,
                    pushes = 1,
                )
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
                        "where ${expectedType.nameInSignature} is expected " +
                        "(declared ${expression.symbol.owner.type.render()}, read ${expression.type.render()})"
            )
        }
        emitLoadSlot(slot)
    }

    private fun IrGetValue.isNullableTypeParameterRecovery(expectedType: DotNetIlValueType.TypeParameter): Boolean {
        if (symbol.owner !is IrValueParameter) return false
        val declaredType = symbol.owner.type as? IrSimpleType ?: return false
        val declaredParameter = declaredType.classifier as? IrTypeParameterSymbol ?: return false
        val readParameter = (type as? IrSimpleType)?.classifier as? IrTypeParameterSymbol ?: return false
        return declaredParameter == readParameter &&
                declaredType.isMarkedNullable() &&
                typeMapper.toDotNetIlValueType(declaredParameter.owner.defaultType) == expectedType
    }

    private fun IrGetValue.isNullablePrimitiveTypeParameterNarrowing(
        expectedType: DotNetIlValueType,
    ): Boolean {
        val declaredType = symbol.owner.type as? IrSimpleType ?: return false
        val declaredParameter = declaredType.classifier as? IrTypeParameterSymbol ?: return false
        val readParameter = (type as? IrSimpleType)?.classifier as? IrTypeParameterSymbol ?: return false
        if (declaredParameter != readParameter) return false
        val nullablePrimitiveBound = declaredParameter.owner.superTypes.singleOrNull()
            ?.takeIf { bound -> bound.isPrimitiveType(nullable = true) }
            ?: return false
        return typeMapper.toDotNetIlValueType(nullablePrimitiveBound.makeNotNull()) == expectedType
    }

    private fun emitLoadSlot(slot: DotNetIlSlot) {
        when (slot) {
            is DotNetIlSlot.Parameter -> methodContext.emit(loadArgumentInstruction(slot.index), pushes = 1)
            is DotNetIlSlot.Local -> methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
        }
    }

    private fun emitWhenExpression(expression: IrWhen, expectedType: DotNetIlValueType) {
        val entryStackDepth = methodContext.stackDepth
        val endLabel = methodContext.nextLabel("whenEnd")
        var hasElse = false

        for (branch in expression.branches) {
            if (branch.condition.isFalseConst()) continue

            if (branch.condition.isTrueConst()) {
                statementScopeEmitter.emitControlFlowValueExpression(branch.result, expectedType)
                hasElse = true
                break
            }

            val nextBranchLabel = methodContext.nextLabel("whenNext")
            emitBranchIfFalse(branch.condition, nextBranchLabel)
            statementScopeEmitter.emitControlFlowValueExpression(branch.result, expectedType)
            methodContext.emitGoto(endLabel)
            methodContext.emitLabel(nextBranchLabel)
        }

        if (!hasElse) {
            dotNetUnsupported("when expression without an else branch")
        }

        if (methodContext.isLabelReferenced(endLabel) || !methodContext.isTerminated) {
            methodContext.emitLabel(endLabel)
        } else {
            // Every reachable arm transferred control (most commonly the sole else arm threw).
            // An unreferenced dead join would incorrectly turn the method back into fall-through
            // and make its consumer pop a return value from an empty stack. Preserve termination
            // and only retain the phantom value needed by an enclosing dead consumer.
            methodContext.notePhantomValueAtTerminatedExpression(entryStackDepth)
        }
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
 * The IL instruction sequence of a WIDENING conversion of the hybrid nullability model, or null
 * when no such conversion exists (instruction-free widenings live in [isDotNetAssignableTo];
 * narrowings only exist as explicit cast/`!!` shapes). Every instruction pops 1 and pushes 1:
 * - `T -> T?`: `newobj Nullable<T>::.ctor(!0)` (boxprobe_s1);
 * - `T? -> Any?`: `box Nullable<T>` — the CLR collapses the result to boxed-`T`-or-null
 *   (boxprobe_s3, all five instantiations incl. the empty->null case nullprobe_s8);
 * - `T -> Any?` for plain primitives: `box <boxed T>` (nullprobe_s8).
 * - a built-in scalar/String carrier to Common Comparable's canonical `System.IComparable`
 *   view: exact primitive boxing or a checked same-object String interface view;
 * - constrained `!n`/`!!n -> reference bound/object`: `box !n`/`!!n`; this is a no-allocation
 *   identity conversion for reference instantiations and remains correct for an external
 *   value-type implementation of an interface bound (genconstraintprobe_s2);
 * - relative method constraint `C : R`: `box C; unbox.any R`. `R` may itself be instantiated
 *   with a value type, so leaving only the boxed object on an `R` slot is invalid CIL. Roslyn uses
 *   this verifier-safe pair; reference substitutions preserve identity and value substitutions
 *   are recovered exactly.
 * Roslyn precedent: C# performs exactly these conversions implicitly at typed/object boundaries;
 * JVM precedent: the JVM backend's StackValue boxing coercions.
 * A `Kotlin.Nothing -> R` reference cast is the physical realization of Kotlin bottom-type
 * widening for exact-capability results; it is deliberately not modeled as CLR assignability.
 */
internal fun dotNetWideningCoercionOrNull(
    from: DotNetIlValueType,
    to: DotNetIlValueType,
    coreLibraryReference: String,
): DotNetIlWideningCoercion? = when {
    to is DotNetIlValueType.NullableValue && from == to.elementType ->
        DotNetIlWideningCoercion(to.ctorInstruction)
    from == DotNetRuntimeTypes.nothingType && to.isDotNetReferenceShaped() ->
        DotNetIlWideningCoercion("castclass ${to.nameInSignature}")
    to == DotNetIlValueType.Object && from is DotNetIlValueType.NullableValue ->
        DotNetIlWideningCoercion(from.boxInstruction)
    from is DotNetIlValueType.TypeParameter &&
            to is DotNetIlValueType.TypeParameter &&
            from.isConstrainedTo(to) -> DotNetIlWideningCoercion(
        "box ${from.nameInSignature}",
        "unbox.any ${to.nameInSignature}",
    )
    from is DotNetIlValueType.TypeParameter &&
            (to == DotNetIlValueType.Object || from.isConstrainedTo(to)) ->
        DotNetIlWideningCoercion("box ${from.nameInSignature}")
    to.isDotNetCanonicalComparable(coreLibraryReference) && from == DotNetIlValueType.String ->
        DotNetIlWideningCoercion("castclass ${to.nameInSignature}")
    to.isDotNetCanonicalComparable(coreLibraryReference) ->
        from.dotNetBoxedCorelibRefOrNull(coreLibraryReference)?.let { boxed ->
            DotNetIlWideningCoercion("box $boxed")
        }
    to == DotNetIlValueType.Object ->
        from.dotNetBoxedCorelibRefOrNull(coreLibraryReference)?.let { boxed ->
            DotNetIlWideningCoercion("box $boxed")
        }
    else -> null
}

internal data class DotNetIlWideningCoercion(
    val instructions: List<String>,
) {
    constructor(vararg instructions: String) : this(instructions.toList())

    init {
        require(instructions.isNotEmpty()) { "A widening coercion must contain at least one IL instruction" }
    }
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
