package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFalseConst
import org.jetbrains.kotlin.ir.util.isTrueConst
import org.jetbrains.kotlin.ir.util.render

/**
 * Emits an [IrTry] in value position. Implemented by [DotNetIlMethodCodegen]: a `try` branch
 * body contains arbitrary statements, and statement emission lives on the method codegen, so a
 * value-position `try` dispatches back through this hook — the reverse of the method codegen
 * delegating value emission to [DotNetIlExpressionCodegen].
 */
internal fun interface DotNetIlTryExpressionEmitter {
    fun emitTryExpression(expression: IrTry, expectedType: DotNetIlValueType)
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
    private val tryExpressionEmitter: DotNetIlTryExpressionEmitter,
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

    fun emitExpression(expression: IrExpression?, expectedType: DotNetIlValueType) {
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
            is IrTry -> tryExpressionEmitter.emitTryExpression(expression, expectedType)
            is IrCall -> {
                val intrinsic = intrinsicMethods.getIntrinsic(expression.symbol)
                if (intrinsic == null || !intrinsic.tryEmitAsExpression(expression, this, expectedType)) {
                    emitCallExpression(expression, expectedType)
                }
            }
            else -> dotNetUnsupported("unsupported ${expectedType.nameInSignature} expression ${expression.javaClass.simpleName}")
        }
    }

    fun emitBranchIfFalse(condition: IrExpression, targetLabel: String) {
        emitExpression(condition, DotNetIlValueType.Boolean)
        methodContext.emitBranch("brfalse", targetLabel, pops = 1)
    }

    /**
     * Emits [expression] as a non-null string suitable for printing or concatenation: constants
     * are rendered through their string representation, nullable strings are coalesced to the
     * `"null"` literal, and non-string values are converted with Kotlin `toString` semantics
     * ([emitBooleanToString] keeps Kotlin's lowercase `"true"`/`"false"` rendering; `Int`/`Long`
     * values go through [emitBoxedInvariantToString], the invariant-culture rendering; `Char`
     * uses the static culture-free `Char::ToString(char)`; `Double` goes through
     * [emitDoubleToString], the shared Kotlin-parity rendering helper).
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
            else -> when (typeMapper.toDotNetIlValueType(expression.type)) {
                DotNetIlValueType.Boolean -> {
                    emitExpression(expression, DotNetIlValueType.Boolean)
                    emitBooleanToString()
                }
                DotNetIlValueType.Int32 -> {
                    emitExpression(expression, DotNetIlValueType.Int32)
                    emitBoxedInvariantToString("${CORE_LIB_REF}System.Int32")
                }
                DotNetIlValueType.Int64 -> {
                    emitExpression(expression, DotNetIlValueType.Int64)
                    emitBoxedInvariantToString("${CORE_LIB_REF}System.Int64")
                }
                DotNetIlValueType.Float64 -> emitDoubleToString(expression)
                DotNetIlValueType.Char -> {
                    // The static Char::ToString(char) renders the single UTF-16 code unit,
                    // culture-independent; identical to Kotlin's `Char.toString()`. Unlike
                    // Int32/Int64 above, no box is needed: mscorlib has a static ToString
                    // overload for char (there is no static Int32::ToString(int32)), so the
                    // int32-shaped stack value is passed directly.
                    emitExpression(expression, DotNetIlValueType.Char)
                    methodContext.emit("call string ${CORE_LIB_REF}System.Char::ToString(char)", pops = 1, pushes = 1)
                }
                is DotNetIlValueType.UserClass ->
                    dotNetUnsupported("string conversion of class instances is not supported yet (no Any.toString model)")
                is DotNetIlValueType.MappedClass ->
                    dotNetUnsupported("string conversion of an exception type is not supported yet (no Any.toString model)")
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
     * Converts the `float64` value produced by [expression] to a string with Kotlin
     * `Double.toString()` shapes (`1.0`, `1.0E20`, `NaN`, `Infinity`, `-0.0`) by calling the
     * shared [DotNetIlRuntimeHelper.DoubleToString] runtime helper, emitted once per module.
     * See that helper's documentation for the rendering algorithm and the consciously accepted
     * divergences from the JVM rendering.
     */
    private fun emitDoubleToString(expression: IrExpression) {
        emitExpression(expression, DotNetIlValueType.Float64)
        methodContext.requireRuntimeHelper(DotNetIlRuntimeHelper.DoubleToString)
        methodContext.emit(DotNetIlRuntimeHelper.DoubleToString.callInstruction, pops = 1, pushes = 1)
    }

    /**
     * Emits the arguments and the `call` instruction for a call to a top-level Kotlin function
     * or to an instance member/accessor of a user class. For an instance callee
     * `call.arguments[0]` is the receiver: it is emitted against the this-type kept at
     * `parameterTypes[0]` and popped by the call like every other argument, so the plain
     * argument zip covers both shapes. Throws [DotNetIlUnsupportedException] when the callee is
     * not available (not compilable, already skipped, or not declared in this module).
     */
    fun emitCall(call: IrCall): DotNetIlReturnType {
        val callee = call.symbol.owner
        val calleeName = callee.name.asString()
        val info = availableFunctions[callee]
            ?: dotNetUnsupported("call to unsupported function '$calleeName'")
        emitArguments(call.arguments, info.signature.parameterTypes, "'$calleeName'")
        methodContext.emit(
            info.renderCallInstruction(callee.dotNetIlMethodName()),
            pops = info.signature.parameterTypes.size,
            pushes = if (info.signature.returnType is DotNetIlReturnType.Value) 1 else 0,
        )
        return info.signature.returnType
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
        for ((argument, parameterType) in arguments.zip(parameterTypes)) {
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
     * constructor with the freshly allocated `this` as argument 0). Generic instantiations are
     * rejected loudly, never erased.
     */
    private fun emitConstructorCall(call: IrConstructorCall, expectedType: DotNetIlValueType) {
        if (call.typeArguments.isNotEmpty()) {
            dotNetUnsupported("generic class types are not supported yet")
        }
        val constructor = call.symbol.owner
        val irClass = constructor.constructedClass
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
        val producedType = DotNetIlValueType.UserClass(classInfo.ilClassName)
        if (producedType != expectedType) {
            dotNetUnsupported(
                "constructor call of '${irClass.name.asString()}' produces ${producedType.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
        val parameterTypes = constructor.dotNetSignature(typeMapper).parameterTypes
        emitArguments(call.arguments, parameterTypes, "constructor of '${irClass.name.asString()}'")
        methodContext.emit(
            "newobj ${classInfo.renderConstructorReference(parameterTypes)}",
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
     * mirror of the Kotlin stdlib's declared constructor surface — the CLR `(string, Exception)`
     * overload itself exists on every mapped type, probe-verified), and the cause-only
     * `(Throwable?)` constructor has no CLR overload on any target.
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
            parameterTypes == listOf(causeType) -> dotNetUnsupported(
                "constructor '$className(cause)' has no CLR overload; construct with (message) or (message, cause)"
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

    /** An instance field read: receiver, then `ldfld <type> 'C'::'name'` (probe-verified). */
    private fun emitGetField(expression: IrGetField, expectedType: DotNetIlValueType) {
        val field = expression.symbol.owner
        val (classInfo, fieldType) = resolveFieldAccess(field)
        if (!fieldType.isDotNetAssignableTo(expectedType)) {
            dotNetUnsupported(
                "field '${field.name.asString()}' has type ${fieldType.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
        emitFieldReceiver(expression.receiver, field, classInfo)
        methodContext.emit(
            "ldfld ${classInfo.renderFieldReference(fieldType, field.name.asString())}",
            pops = 1,
            pushes = 1,
        )
    }

    /**
     * An instance field store: receiver, value, then `stfld <type> 'C'::'name'` (probe-verified).
     * Reaches codegen from `DEFAULT_PROPERTY_ACCESSOR` setter bodies and from the field
     * initializations [InitializersLowering][org.jetbrains.kotlin.backend.common.lower.InitializersLowering]
     * merged into constructor bodies; user-written property writes are accessor calls instead.
     */
    fun emitSetField(expression: IrSetField) {
        val field = expression.symbol.owner
        val (classInfo, fieldType) = resolveFieldAccess(field)
        emitFieldReceiver(expression.receiver, field, classInfo)
        emitExpression(expression.value, fieldType)
        methodContext.emit("stfld ${classInfo.renderFieldReference(fieldType, field.name.asString())}", pops = 2)
    }

    /**
     * Resolves the owning class and the IL type of an instance field. Both lookups go through
     * the emission-scoped state, so field access to a class the emitter removed (or a field of a
     * type outside the supported set) aborts the surrounding render.
     */
    private fun resolveFieldAccess(field: IrField): Pair<DotNetIlClassInfo, DotNetIlValueType> {
        val irClass = field.parent as? IrClass
            ?: dotNetUnsupported("access to non-member field '${field.name.asString()}' is not supported")
        val classInfo = typeMapper.classInfoOrNull(irClass)
            ?: dotNetUnsupported("access to a field of unsupported class '${irClass.name.asString()}'")
        val fieldType = typeMapper.toDotNetIlValueType(field.type)
            ?: dotNetUnsupported("field '${field.name.asString()}' has unsupported type ${field.type.render()}")
        return classInfo to fieldType
    }

    private fun emitFieldReceiver(receiver: IrExpression?, field: IrField, classInfo: DotNetIlClassInfo) {
        if (receiver == null) {
            dotNetUnsupported("static field '${field.name.asString()}' is not supported")
        }
        emitExpression(receiver, DotNetIlValueType.UserClass(classInfo.ilClassName))
    }

    private fun emitCallExpression(call: IrCall, expectedType: DotNetIlValueType) {
        val returnType = emitCall(call)
        if ((returnType as? DotNetIlReturnType.Value)?.type?.isDotNetAssignableTo(expectedType) != true) {
            dotNetUnsupported(
                "call to '${call.symbol.owner.name.asString()}' produces ${returnType.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
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
        }
    }

    private fun emitGetValue(expression: IrGetValue, expectedType: DotNetIlValueType) {
        val slot = methodContext.reference(expression.symbol)
        if (!slot.type.isDotNetAssignableTo(expectedType)) {
            dotNetUnsupported(
                "value '${expression.symbol.owner.name.asString()}' has type ${slot.type.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
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

internal fun storeLocalInstruction(index: Int): String =
    if (index in 0..3) "stloc.$index" else "stloc $index"
