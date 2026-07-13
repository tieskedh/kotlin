package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.util.isGetter
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.types.isNullableNothing
import org.jetbrains.kotlin.ir.types.isNullableString
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.render

/**
 * The core library assembly name declared in the module header (`.assembly extern`) and targeted
 * by every emitted IL member reference. Kept as a single constant (with the derived
 * [CORE_LIB_REF] prefix) so retargeting the backend to a different corelib — e.g. modern .NET's
 * `System.Runtime` — is a one-line change instead of a scatter-shot edit.
 */
internal const val CORE_LIB = "mscorlib"

/** The bracketed resolution-scope prefix of corelib type references in emitted IL. */
internal const val CORE_LIB_REF = "[$CORE_LIB]"

/**
 * Thrown while rendering a single function into IL when a construct the prototype .NET backend
 * cannot compile is encountered. The emitter catches it, discards the partial render, skips the
 * function, and reports [reason] as a diagnostic.
 */
internal open class DotNetIlUnsupportedException(val reason: String) : RuntimeException(reason)

internal fun dotNetUnsupported(reason: String): Nothing =
    throw DotNetIlUnsupportedException(reason)

internal fun IrSimpleFunction.dotNetSignature(typeMapper: DotNetIlTypeMapper): DotNetIlMethodSignature {
    val ilReturnType = typeMapper.toDotNetIlReturnType(returnType)
        ?: dotNetUnsupported("return type ${returnType.render()} is not supported")
    // A member function's dispatch receiver is parameters[0]; its type (the owning user class)
    // stays in the mapped parameter list so argument zipping and call-site pop counts stay
    // uniform, while `hasThis` makes signature rendering and slot numbering treat it as the
    // implicit CLR argument 0 (see DotNetIlMethodSignature).
    val hasThis = parameters.firstOrNull()?.kind == IrParameterKind.DispatchReceiver
    return DotNetIlMethodSignature(ilReturnType, parameters.dotNetParameterTypes(typeMapper), hasThis)
}

/**
 * The IL method name of a function: property accessors get the CLR-conventional `get_x`/`set_x`
 * derived from the property name (the JVM backend derives `getX`/`setX` the same way in
 * `MethodSignatureMapper`; the underscore spelling is what `.property` metadata conventionally
 * binds to, probe-verified), everything else keeps its Kotlin name. The result is still rendered
 * through [toIlIdentifier] wherever it is printed.
 */
internal fun IrSimpleFunction.dotNetIlMethodName(): String {
    val property = correspondingPropertySymbol?.owner ?: return name.asString()
    val prefix = if (isGetter) "get_" else "set_"
    return prefix + property.name.asString()
}

/**
 * The IL signature of a constructor: CLR constructors always return `void`, and an
 * `IrConstructor.parameters` list carries no dispatch receiver, so the printed parameter list is
 * exactly the declared one (the implicit `this` is argument slot 0 by CLR instance-method
 * numbering, handled by [DotNetIlMethodContext]).
 */
internal fun IrConstructor.dotNetSignature(typeMapper: DotNetIlTypeMapper): DotNetIlMethodSignature =
    DotNetIlMethodSignature(DotNetIlReturnType.Void, parameters.dotNetParameterTypes(typeMapper))

private fun List<IrValueParameter>.dotNetParameterTypes(typeMapper: DotNetIlTypeMapper): List<DotNetIlValueType> =
    map { parameter ->
        typeMapper.toDotNetIlValueType(parameter.type)
            ?: dotNetUnsupported("parameter '${parameter.name.asString()}' has unsupported type ${parameter.type.render()}")
    }

/**
 * Emission-scoped IR-to-IL type mapping. One instance is created per [DotNetIlEmitter.emit] call
 * — the emitter is re-entrant, so there is no global class registry: a user-class type maps to
 * IL only while its [IrClass] is present in [availableClasses], the emitter's live map, so
 * removing an unsupported class during the emission fixpoint automatically cascades to every
 * declaration whose types reference it.
 */
internal class DotNetIlTypeMapper(
    private val availableClasses: Map<IrClass, DotNetIlClassInfo>,
) {
    /**
     * The class info of [irClass] while it is still available, or null once (or if) the emitter
     * removed it — member references (`newobj`, `this(...)` delegations, `ldfld`/`stfld`) go
     * through this lookup so a removed class fails its users instead of leaving stale IL text.
     */
    fun classInfoOrNull(irClass: IrClass): DotNetIlClassInfo? = availableClasses[irClass]

    /** Maps [type] in return position; CLR `void` is the return encoding of Kotlin `Unit`. */
    fun toDotNetIlReturnType(type: IrType): DotNetIlReturnType? {
        if (type.isUnit()) return DotNetIlReturnType.Void
        return DotNetIlReturnType.Value(toDotNetIlValueType(type) ?: return null)
    }

    /**
     * Maps [type] in value position (parameter, local, field, evaluation stack), or null when
     * the type has no IL mapping, so that callers report their own located diagnostic.
     *
     * Nullability follows the HYBRID representation (see AGENTS.md "Nullability model"): a
     * nullable REFERENCE type maps to the same IL type as its non-null flavor (CLR reference
     * types are structurally nullable — the `String?`/`C?`/exception arms below are shared with
     * the non-null lookups), while a concrete nullable PRIMITIVE (`Int?` etc.) maps to
     * `System.Nullable<T>` ([DotNetIlValueType.NullableValue], Roslyn precedent). The `is*`
     * primitive predicates are NOT-NULL-only by construction, so the nullable-primitive arm is
     * separate. `kotlin.Any`/`Any?` map to CLR `object` as a storage type
     * ([DotNetIlValueType.Object]). Generic `T?` (a nullable type-parameter type) deliberately
     * stays unmapped: its ABI is a future generics problem and must not force concrete nullable
     * primitives into `object` (the whole point of the hybrid split).
     */
    fun toDotNetIlValueType(type: IrType): DotNetIlValueType? = when {
        type.isBoolean() -> DotNetIlValueType.Boolean
        type.isInt() -> DotNetIlValueType.Int32
        type.isLong() -> DotNetIlValueType.Int64
        type.isDouble() -> DotNetIlValueType.Float64
        type.isChar() -> DotNetIlValueType.Char
        type.isDotNetStringType() -> DotNetIlValueType.String
        type.isAny() || type.isNullableAny() -> DotNetIlValueType.Object
        // `Nothing?` — the type of the null literal, and of values the frontend narrowed to
        // definitely-null (e.g. a when-subject temporary initialized from a known-null value) —
        // is reference-shaped storage whose only value is `ldnull`. Plain `Nothing` (no values
        // at all) deliberately stays unmapped.
        type.isNullableNothing() -> DotNetIlValueType.Object
        else -> toNullablePrimitiveTypeOrNull(type)
            ?: toMappedExceptionTypeOrNull(type)
            ?: toUserClassTypeOrNull(type)
    }

    /**
     * The [NullableValue][DotNetIlValueType.NullableValue] mapping of a concrete nullable
     * primitive type, or null when [type] is not one. Matches by classifier FqName plus the
     * nullability marker — the positive-space complement of the not-null `isInt()` family used
     * above (`Int?` fails `isInt()` because `isNotNullClassType` requires `!isMarkedNullable`).
     */
    private fun toNullablePrimitiveTypeOrNull(type: IrType): DotNetIlValueType.NullableValue? {
        if (type !is IrSimpleType || !type.isMarkedNullable()) return null
        val elementType = when (type.classFqName) {
            StandardNames.FqNames._boolean.toSafe() -> DotNetIlValueType.Boolean
            StandardNames.FqNames._int.toSafe() -> DotNetIlValueType.Int32
            StandardNames.FqNames._long.toSafe() -> DotNetIlValueType.Int64
            StandardNames.FqNames._double.toSafe() -> DotNetIlValueType.Float64
            StandardNames.FqNames._char.toSafe() -> DotNetIlValueType.Char
            else -> return null
        }
        return DotNetIlValueType.NullableValue(elementType)
    }

    /**
     * A built-in exception type maps through the curated [DotNetMappedExceptions] registry —
     * before the user-class lookup, because the injected exception declarations are excluded
     * from the class model entirely. `T?` maps to the same reference type (like [UserClass][DotNetIlValueType.UserClass]
     * and `string`). A [rejected][DotNetMappedExceptions.Entry.Rejected] entry fails loudly with
     * its per-type reason instead of falling through to a generic diagnostic.
     */
    private fun toMappedExceptionTypeOrNull(type: IrType): DotNetIlValueType.MappedClass? =
        when (val entry = type.classFqName?.let(DotNetMappedExceptions.entries::get)) {
            is DotNetMappedExceptions.Entry.Mapped -> DotNetIlValueType.MappedClass(entry.clrTypeRef)
            is DotNetMappedExceptions.Entry.Rejected -> dotNetUnsupported(entry.reason)
            null -> null
        }

    /**
     * A user-class type maps only while its class is available; `C?` maps to the same
     * `class 'C'` as `C` (the classifier lookup ignores nullability, like `string`). Generic
     * user-class types are rejected loudly — never erased — keeping the structural mapping
     * ready for CLR reified generics.
     */
    private fun toUserClassTypeOrNull(type: IrType): DotNetIlValueType.UserClass? {
        if (type !is IrSimpleType) return null
        val irClass = (type.classifier as? IrClassSymbol)?.owner ?: return null
        val classInfo = availableClasses[irClass] ?: return null
        if (type.arguments.isNotEmpty()) {
            dotNetUnsupported("generic class types are not supported yet")
        }
        return DotNetIlValueType.UserClass(classInfo)
    }
}

/**
 * The base class of [this] class within the inheritance model, or null when no supertype is a
 * proper class (sole supertype `kotlin.Any`, or only interface supertypes). Interface supertypes
 * are skipped — they belong to [dotNetDirectInterfaces] — and the shape gate guarantees at most
 * one proper-class supertype on gate-passing classes.
 */
internal fun IrClass.dotNetBaseClassOrNull(): IrClass? =
    superTypes.firstNotNullOfOrNull { superType ->
        if (superType.isAny()) null
        else ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.takeUnless { it.isInterface }
    }

/**
 * The directly implemented interfaces of [this] class (for an interface: its directly extended
 * super-interfaces), in supertype-list order. The interface-model counterpart of
 * [dotNetBaseClassOrNull]: the emitter's link pre-pass feeds them into
 * [DotNetIlClassInfo.interfaces] for the assignability walk, and the render re-resolves them
 * through the live class map every fixpoint round for the `implements` line.
 */
internal fun IrClass.dotNetDirectInterfaces(): List<IrClass> =
    superTypes.mapNotNull { superType ->
        ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.takeIf { it.isInterface }
    }

/**
 * Whether [this] member occupies (or introduces) a CLR virtual slot, i.e. whether its
 * declaration carries `virtual` flags and non-`super` call sites use `callvirt` — the two must
 * agree, so both consult this single predicate. True exactly for interface members (the CLR
 * makes every interface slot virtual: a non-virtual implementation load-poisons the type with
 * TypeLoadException — probe-verified, `ifaceprobe_s1b`), for instance members that override
 * something (every Kotlin `override` is virtual in IL — of a base-class member OR of an
 * interface member, including a `final override`, which keeps dispatching correctly under
 * `callvirt` — probe-verified, `inheritprobe_s2`, `ifaceprobe_s1`) and for `open`
 * members of `open` classes (which introduce a fresh `newslot` slot). An `open` member of a
 * FINAL class is deliberately NOT virtual: nothing can ever override it, so it keeps the
 * final-class model's plain non-virtual `call` (the JVM has no such distinction — everything
 * non-private is virtual bytecode-side and the JIT devirtualizes; the CLR makes virtualness a
 * declaration-site property, so the backend decides it here, following what Roslyn emits for
 * C# `virtual`/`override` members).
 */
internal fun IrSimpleFunction.isDotNetVirtual(): Boolean {
    if (parameters.firstOrNull()?.kind != IrParameterKind.DispatchReceiver) return false
    if ((parent as? IrClass)?.isInterface == true) return true
    if (overriddenSymbols.isNotEmpty()) return true
    return modality == Modality.OPEN && (parent as? IrClass)?.modality == Modality.OPEN
}

internal fun IrType.isDotNetNullableStringType(): Boolean {
    if (isNullableString()) return true
    val typeParameter = ((this as? IrSimpleType)?.classifier as? IrTypeParameterSymbol)?.owner ?: return false
    return typeParameter.superTypes.any { it.isNullableString() }
}

private fun IrType.isDotNetStringType(): Boolean {
    if (isString() || isNullableString()) return true
    val typeParameter = ((this as? IrSimpleType)?.classifier as? IrTypeParameterSymbol)?.owner ?: return false
    return typeParameter.superTypes.any { it.isString() || it.isNullableString() }
}

/**
 * Renders a `kotlin.Double` constant as an `ldc.r8` operand.
 *
 * Finite values use [Double.toString], the shortest decimal representation that round-trips
 * (same contract the JVM backend relies on for `.class` file constants); it always contains a
 * `.` or an exponent, and the exponent marker is lowercased to the ilasm-validated `1.0e300`
 * shape. ilasm's decimal parsing was verified bit-exact against the host down to the subnormal
 * range (`4.9e-324`) and up to `Double.MAX_VALUE`; as a belt-and-braces guard the decimal text
 * is still parsed back on the host and any value it does not reproduce bit-for-bit falls back
 * to the raw form. NaN, the infinities and negative zero always use ilasm's raw-bit
 * `float64(0x...)` operand syntax (also validated empirically): the first two have no decimal
 * form at all — the raw form additionally pins the exact NaN payload — and `-0.0` is emitted
 * raw so the constant's sign bit never depends on ilasm's handling of a `-0.0` literal.
 */
internal fun Double.toIlFloat64Literal(): String {
    val rawBitsLiteral = "float64(0x%016X)".format(toRawBits())
    if (isNaN() || isInfinite() || (this == 0.0 && toRawBits() != 0L)) return rawBitsLiteral
    val decimalLiteral = toString().lowercase()
    return if (decimalLiteral.toDouble().toRawBits() == toRawBits()) decimalLiteral else rawBitsLiteral
}

/**
 * Renders a user-derived name as a single-quoted ILAsm identifier, escaping backslashes and
 * single quotes. Quoting every identifier sidesteps the hundreds of ILAsm keywords and keeps
 * raw Kotlin names (including overload-unfriendly or exotic ones) intact.
 */
internal fun String.toIlIdentifier(): String = buildString {
    append('\'')
    for (char in this@toIlIdentifier) {
        when (char) {
            '\\' -> append("\\\\")
            '\'' -> append("\\'")
            else -> append(char)
        }
    }
    append('\'')
}

/**
 * Renders a string constant as an `ldstr` operand: a double-quoted ILAsm QSTRING, or the
 * `bytearray` form for strings a QSTRING cannot represent.
 *
 * Non-ASCII characters are kept raw: the .il file is written as UTF-8 with a BOM (see
 * [DotNetBackend]), which makes ilasm decode multi-byte sequences correctly. Control characters
 * are rendered with octal escapes, except NUL: ilasm silently truncates a QSTRING at an embedded
 * `\000`, so strings containing NUL fall back to the `bytearray` (UTF-16LE code units) form.
 */
internal fun String.toIlStringLiteral(): String {
    if (NUL_CHAR in this) return toIlByteArrayLiteral()
    return buildString {
        append('"')
        for (char in this@toIlStringLiteral) {
            when {
                char == '\\' -> append("\\\\")
                char == '"' -> append("\\\"")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' || char == DEL_CHAR -> append("\\" + "%03o".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}

private const val NUL_CHAR = '\u0000'
private const val DEL_CHAR = '\u007F'

private fun String.toIlByteArrayLiteral(): String =
    map { it.code }
        .flatMap { code -> listOf(code and 0xFF, (code shr 8) and 0xFF) }
        .joinToString(separator = " ", prefix = "bytearray (", postfix = ")") { "%02X".format(it) }
