package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFalseConst
import org.jetbrains.kotlin.ir.util.isInterface
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
    fun emit(instruction: String, pops: Int = 0, pushes: Int = 0) {
        methodContext.emit(instruction, pops, pushes)
    }

    /**
     * Maps [type] through the emission-scoped [DotNetIlTypeMapper]; null when the type has no IL
     * mapping. Exposed so intrinsics can dispatch on operand and parameter types.
     */
    fun toDotNetIlValueType(type: IrType): DotNetIlValueType? = typeMapper.toDotNetIlValueType(type)

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
        // Widening-coercion interception, the hybrid nullability model's conversion layer (JVM
        // precedent: the JVM backend coerces at codegen time through StackValue — boxing is
        // never an IR node — and Roslyn converts `T -> T?` / `-> object` at every use site the
        // same way). When the expression's own mapped type differs from the expected one, it is
        // emitted AT ITS OWN TYPE first and then, when needed, a single conversion instruction
        // widens it: `newobj Nullable<T>::.ctor` for `T -> T?`, `box` for `T -> Any?` and
        // `T? -> Any?` (the CLR collapses the latter to boxed-T-or-null, probe-verified,
        // boxprobe_s3). Instruction-free reference widenings just recurse at the natural type.
        // Narrowings never coerce here — they exist only as explicit IMPLICIT_CAST/`!!` shapes
        // (see emitTypeOperatorCall) — so anything else falls through to the per-producer
        // assignability rejections below.
        if (expression != null) {
            val naturalType = typeMapper.toDotNetIlValueType(expression.type)
            if (naturalType != null && naturalType != expectedType) {
                val kFunctionArity = expression.type.dotNetKFunctionExecutionArityOrNull()
                if (kFunctionArity != null && DotNetRuntimeTypes.isFixedFunctionType(expectedType, kFunctionArity)) {
                    // KFunctionN is a logical subtype of FunctionN, while the erased CLR views
                    // are sibling interfaces on the same generated object. Materialize that
                    // source-level widening as a checked interface view change.
                    emitExpression(expression, naturalType)
                    methodContext.emit("castclass ${expectedType.nameInSignature}", pops = 1, pushes = 1)
                    return
                }
                if (naturalType.isDotNetAssignableTo(expectedType)) {
                    emitExpression(expression, naturalType)
                    return
                }
                val coercion = dotNetWideningCoercionOrNull(naturalType, expectedType)
                if (coercion != null) {
                    emitExpression(expression, naturalType)
                    methodContext.emit(coercion, pops = 1, pushes = 1)
                    return
                }
            }
        }
        when (expression) {
            null -> dotNetUnsupported("missing ${expectedType.nameInSignature} expression value")
            is IrConst -> emitConstant(expression, expectedType)
            is IrGetValue -> emitGetValue(expression, expectedType)
            is IrGetField -> emitGetField(expression, expectedType)
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
            else -> dotNetUnsupported("unsupported ${expectedType.nameInSignature} expression ${expression.javaClass.simpleName}")
        }
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
        methodContext.emit("newobj instance void ${CORE_LIB_REF}System.NullReferenceException::.ctor()", pushes = 1)
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
     * Everything else — `as` (CAST), `as?` (SAFE_CAST), `is` (INSTANCEOF), and any IMPLICIT_CAST
     * outside the shapes above (e.g. the `Any? -> C` downcast a positive `is` smartcast would
     * produce) — stays rejected loudly until a downcast/type-test model exists.
     */
    private fun emitTypeOperatorCall(expression: IrTypeOperatorCall, expectedType: DotNetIlValueType) {
        if (expression.operator != IrTypeOperator.IMPLICIT_CAST && expression.operator != IrTypeOperator.IMPLICIT_NOTNULL) {
            dotNetUnsupported("type operator ${expression.operator} is not supported")
        }
        val operandType = typeMapper.toDotNetIlValueType(expression.argument.type)
            ?: dotNetUnsupported("implicit cast of a value of unsupported type ${expression.argument.type.render()}")
        val castType = typeMapper.toDotNetIlValueType(expression.typeOperand)
            ?: dotNetUnsupported("implicit cast to unsupported type ${expression.typeOperand.render()}")
        if (expression.operator == IrTypeOperator.IMPLICIT_NOTNULL) {
            emitExpression(expression.argument, operandType)
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
                    methodContext.emit("castclass ${castType.nameInSignature}", pops = 1, pushes = 1)
                }
                operandType.isDotNetAssignableTo(castType) -> emitExpression(expression.argument, operandType)
                else -> {
                    val coercion = dotNetWideningCoercionOrNull(operandType, castType)
                    when {
                        coercion != null -> {
                            emitExpression(expression.argument, operandType)
                            methodContext.emit(coercion, pops = 1, pushes = 1)
                        }
                        operandType is DotNetIlValueType.NullableValue && castType == operandType.elementType -> {
                            emitExpression(expression.argument, operandType)
                            emitNullableUnwrapOrThrowNpe(operandType)
                        }
                        else -> dotNetUnsupported(
                            "implicit cast from ${operandType.nameInSignature} to ${castType.nameInSignature} " +
                                    "is not a reference upcast and is not supported"
                        )
                    }
                }
            }
        }
        if (!castType.isDotNetAssignableTo(expectedType)) {
            val outerCoercion = dotNetWideningCoercionOrNull(castType, expectedType)
                ?: dotNetUnsupported(
                    "implicit cast produces ${castType.nameInSignature} " +
                            "where ${expectedType.nameInSignature} is expected"
                )
            methodContext.emit(outerCoercion, pops = 1, pushes = 1)
        }
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
                DotNetIlValueType.Int32,
                DotNetIlValueType.Int64,
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
                    methodContext.emitBranch("br", endLabel)
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
            DotNetIlValueType.Int32 -> emitBoxedInvariantToString("${CORE_LIB_REF}System.Int32")
            DotNetIlValueType.Int64 -> emitBoxedInvariantToString("${CORE_LIB_REF}System.Int64")
            DotNetIlValueType.Float64 -> emitDoubleValueToString()
            DotNetIlValueType.Char ->
                methodContext.emit("call string ${CORE_LIB_REF}System.Char::ToString(char)", pops = 1, pushes = 1)
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
            "call class ${CORE_LIB_REF}System.Globalization.CultureInfo ${CORE_LIB_REF}System.Globalization.CultureInfo::get_InvariantCulture()",
            pushes = 1,
        )
        methodContext.emit(
            "callvirt instance string ${CORE_LIB_REF}System.IFormattable::ToString(string, class ${CORE_LIB_REF}System.IFormatProvider)",
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
     * `super<I>`-qualified call to an interface member is rejected up front: its non-virtual
     * `call` would need a callable interface implementation — the Default Interface Methods
     * model this backend does not have.
     */
    fun emitCall(call: IrCall): DotNetIlReturnType {
        call.superQualifierSymbol?.owner?.let { superQualifier ->
            if (superQualifier.isInterface) {
                dotNetUnsupported(
                    "'super<${superQualifier.name.asString()}>' call to an interface member is not supported " +
                            "(requires Default Interface Methods, which are outside the .NET Framework 4.8 " +
                            "compatibility floor)"
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
        val info = availableFunctions[callee]
            ?: dotNetUnsupported("call to unsupported function '$calleeName'")
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
        // A member of a GENERIC class is called with the operand token naming the receiver's
        // instantiated view of the DECLARING class — `class 'Box`1'<string>` externally, the
        // open `class 'Box`1'<!0>` for `this`-dispatch inside the class's own bodies, and the
        // instantiated BASE view for inherited members and super-calls through a derived
        // receiver (probe-verified: genprobe_s2/_s3/_s5/_s7) — while the signature slots stay
        // open per CLR member-ref rules.
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
            .map { it.substituteDotNetTypeParameters(classInstantiation, methodInstantiation) }
        val virtual = call.superQualifierSymbol == null && callee.isDotNetVirtual()
        val constrainedReceiverType = receiverType as? DotNetIlValueType.TypeParameter
        if (constrainedReceiverType != null) {
            val expectedReceiverType = parameterTypes.firstOrNull()
                ?: dotNetUnsupported("call to '$calleeName' has an unsupported receiver shape")
            if (!constrainedReceiverType.isConstrainedTo(expectedReceiverType)) {
                dotNetUnsupported(
                    "call to '$calleeName' is outside the declared bounds of " +
                            "type parameter ${constrainedReceiverType.nameInSignature}"
                )
            }
            emitTypeParameterReceiverArguments(
                call.arguments, parameterTypes, "'$calleeName'", constrainedReceiverType, virtual,
            )
        } else {
            emitArguments(call.arguments, parameterTypes, "'$calleeName'")
        }
        if (constrainedReceiverType != null && virtual) {
            // `constrained.` is a prefix and must be immediately adjacent to its `callvirt`.
            methodContext.emit("constrained. ${constrainedReceiverType.nameInSignature}")
        }
        methodContext.emit(
            info.renderCallInstruction(
                callee.dotNetIlMethodName(),
                virtual = virtual,
                ownerToken = ownerToken,
                methodInstantiation = methodInstantiation,
            ),
            pops = info.signature.parameterTypes.size,
            pushes = if (info.signature.returnType is DotNetIlReturnType.Value) 1 else 0,
        )
        return info.signature.returnType.substituteDotNetTypeParameters(classInstantiation, methodInstantiation)
    }

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
     * onto the platform throw instruction, no lowering). Only values of a
     * [mapped exception type][DotNetIlValueType.MappedClass] can be thrown. A rethrow (`throw e`
     * inside a catch handler) is the same shape — a load of the bound local followed by `throw`,
     * which preserves object identity; the IL `rethrow` instruction is never emitted (Kotlin has
     * no bare rethrow statement; the stack-trace-restart delta is irrelevant until stack traces
     * are surfaced).
     */
    fun emitThrow(expression: IrThrow) {
        val thrownType = typeMapper.toDotNetIlValueType(expression.value.type)
        if (thrownType !is DotNetIlValueType.MappedClass) {
            dotNetUnsupported(
                "throw of a non-exception-mapped type ${expression.value.type.render()} is not supported"
            )
        }
        emitExpression(expression.value, thrownType)
        methodContext.emitThrow()
    }

    /**
     * `Point(1, 2)` → arguments then `newobj instance void 'Point'::.ctor(int32, int32)`
     * (probe-verified; `newobj` pops the arguments and pushes the new instance, calling the
     * constructor with the freshly allocated `this` as argument 0).
     *
     * A GENERIC instantiation (`Box<String>(v)`, arguments inferred or explicit) carries the
     * full instantiation on the owner token while the parameter slots stay open —
     * `newobj instance void class 'demo.Box`1'<string>::.ctor(!0)` (probe-verified,
     * genprobe_s2; nested instantiations genprobe_s3; Nullable arguments genprobe_s4) — and the
     * argument values are emitted against the SUBSTITUTED parameter types (real reification:
     * `Box<Int>` really stores an `int32`, zero box/unbox, genprobe_s3). The instantiation is
     * mapped through the live type mapper, so a type argument mentioning an evicted class fails
     * the call site loudly — never erased.
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
        val classInfo = typeMapper.classInfoOrNull(irClass)
            ?: dotNetUnsupported("constructor call of unsupported class '${irClass.name.asString()}'")
        val [producedType, ownerToken, classInstantiation] = if (irClass.typeParameters.isEmpty()) {
            Triple(DotNetIlValueType.UserClass(classInfo) as DotNetIlValueType, classInfo.ilTypeRef, emptyList<DotNetIlValueType>())
        } else {
            // Source generic calls carry the instantiation on call.type. Common local-declaration
            // lowering instead leaves the generated class type bare and appends captured outer
            // type parameters to the constructor call's typeArguments. Both are authoritative IR
            // encodings of the same constructed CLR owner.
            val argumentsFromCall = call.typeArguments.map { argument ->
                argument?.let(typeMapper::toDotNetIlValueType)
            }
            val instanceType = (typeMapper.toDotNetIlValueType(call.type) as? DotNetIlValueType.GenericInstance)
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
        val parameterTypes = constructor.dotNetSignature(typeMapper).parameterTypes
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
        val producedType = DotNetIlValueType.MappedClass(entry.clrTypeRef)
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
        val causeType: DotNetIlValueType = DotNetIlValueType.MappedClass(DotNetMappedExceptions.EXCEPTION_TYPE_REF)
        when {
            parameterTypes.isEmpty() -> {}
            parameterTypes == listOf(DotNetIlValueType.String) -> {}
            parameterTypes == listOf(DotNetIlValueType.String, causeType) && entry.hasMessageCauseCtor -> {}
            parameterTypes == listOf(causeType) && entry.hasCauseCtor -> {}
            parameterTypes == listOf(causeType) -> dotNetUnsupported(
                "constructor '$className(cause)' has no mapped CLR overload; " +
                        "construct with (message) or (message, cause)"
            )
            else -> dotNetUnsupported(
                "constructor of '$className' has no matching overload on the mapped CLR type '${entry.clrTypeRef}'"
            )
        }
        emitArguments(call.arguments, parameterTypes, "constructor of '$className'")
        methodContext.emit(
            "newobj instance void ${entry.clrTypeRef}::.ctor(${parameterTypes.joinToString(", ") { it.nameInSignature }})",
            pops = parameterTypes.size,
            pushes = 1,
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
            // A field of a GENERIC class: the operand keeps the DECLARED (open) field type while
            // the owner token carries the receiver's instantiation — `ldfld !0 class
            // 'Box`1'<string>::'value'` (probe-verified, genprobe_s2/_s3); the VALUE that lands
            // on the stack has the substituted type.
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
     * The instantiated owner view of a field access on a GENERIC class: the receiver's mapped
     * type walked up to the field's declaring class (usually the open self-instantiation —
     * backing-field accesses live in the class's own accessors and constructors). Static fields
     * cannot exist on a generic class of this model (objects and companions are non-generic and
     * `const val` reads are inlined), so that flavor is rejected defensively.
     */
    private fun resolveGenericFieldOwner(
        receiver: IrExpression?,
        field: IrField,
        isStatic: Boolean,
    ): Triple<DotNetIlValueType.GenericInstance, IrExpression, DotNetIlValueType> {
        val fieldName = field.name.asString()
        if (isStatic) {
            dotNetUnsupported("static field '$fieldName' on a generic class is not supported")
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
                "access to field '$fieldName' through a receiver that is not an instantiation of its declaring class"
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
                val classInfo = typeMapper.classInfoOrNull(parent)
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
        val returnType = emitCall(call)
        val producedType = (returnType as? DotNetIlReturnType.Value)?.type
        if (
            producedType == DotNetIlValueType.Object &&
            call.symbol.owner.isDotNetErasedCallableInvoke()
        ) {
            emitErasedCallableObjectAs(expectedType)
        } else if (producedType?.isDotNetAssignableTo(expectedType) != true) {
            dotNetUnsupported(
                "call to '${call.symbol.owner.name.asString()}' produces ${returnType.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
    }

    /** Narrows an erased callable result from object to the logical Kotlin call result type. */
    private fun emitErasedCallableObjectAs(expectedType: DotNetIlValueType) {
        if (expectedType == DotNetIlValueType.Object) return
        val instruction = expectedType.dotNetObjectNarrowingInstructionOrNull()
            ?: dotNetUnsupported(
                "erased callable result cannot be converted from object to ${expectedType.nameInSignature}"
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
            // An instantiated generic class is an ordinary reference type: `null` is its only
            // constant (`val b: Box<String>? = null`), like UserClass above.
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
            if (slotType == DotNetIlValueType.Object && methodContext.isErasedCallableParameter(expression.symbol)) {
                val instruction = expectedType.dotNetObjectNarrowingInstructionOrNull()
                    ?: dotNetUnsupported(
                        "erased callable parameter '${expression.symbol.owner.name.asString()}' cannot be converted " +
                                "from object to ${expectedType.nameInSignature}"
                    )
                emitLoadSlot(slot)
                methodContext.emit(instruction, pops = 1, pushes = 1)
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
            methodContext.emitBranch("br", endLabel)
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
 * - constrained `!n`/`!!n -> bound/object`: `box !n`/`!!n`; this is a no-allocation identity
 *   conversion for reference instantiations and remains correct for an external value-type
 *   implementation of an interface bound (genconstraintprobe_s2).
 * Roslyn precedent: C# performs exactly these conversions implicitly at typed/object boundaries;
 * JVM precedent: the JVM backend's StackValue boxing coercions.
 */
internal fun dotNetWideningCoercionOrNull(from: DotNetIlValueType, to: DotNetIlValueType): String? = when {
    to is DotNetIlValueType.NullableValue && from == to.elementType -> to.ctorInstruction
    to == DotNetIlValueType.Object && from is DotNetIlValueType.NullableValue -> from.boxInstruction
    from is DotNetIlValueType.TypeParameter && (to == DotNetIlValueType.Object || from.isConstrainedTo(to)) ->
        "box ${from.nameInSignature}"
    to == DotNetIlValueType.Object -> from.dotNetBoxedCorelibRefOrNull()?.let { "box $it" }
    else -> null
}

/** The CLR conversion from an erased callable object slot to one supported logical value type. */
private fun DotNetIlValueType.dotNetObjectNarrowingInstructionOrNull(): String? {
    dotNetBoxedCorelibRefOrNull()?.let { return "unbox.any $it" }
    return when (this) {
        DotNetIlValueType.Object -> null
        DotNetIlValueType.String -> "castclass ${CORE_LIB_REF}System.String"
        is DotNetIlValueType.NullableValue,
        is DotNetIlValueType.TypeParameter,
            -> "unbox.any $nameInSignature"
        is DotNetIlValueType.UserClass -> "castclass ${classInfo.ilTypeRef}"
        is DotNetIlValueType.MappedClass -> "castclass $ilTypeRef"
        is DotNetIlValueType.GenericInstance,
        is DotNetIlValueType.PrimitiveArray,
        is DotNetIlValueType.GenericArray,
            -> "castclass $nameInSignature"
        else -> null
    }
}
