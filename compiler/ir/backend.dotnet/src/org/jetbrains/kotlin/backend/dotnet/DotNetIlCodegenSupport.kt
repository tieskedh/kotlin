package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.util.isGetter
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.types.Variance
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

/** Whether this is one of the primitive-array classifiers whose scalar element type is supported. */
internal fun IrType.isSupportedDotNetPrimitiveArray(): Boolean = when (classFqName?.asString()) {
    "kotlin.BooleanArray",
    "kotlin.IntArray",
    "kotlin.LongArray",
    "kotlin.DoubleArray",
    "kotlin.CharArray",
        -> true
    else -> false
}

/** Whether this is Kotlin's invariant generic array classifier (`Array<E>`). */
internal fun IrType.isDotNetGenericArray(): Boolean =
    classFqName?.asString() == "kotlin.Array"

/** The exact invariant element type used by the indexed-loop lowering, or null for projections. */
internal fun IrType.dotNetInvariantArrayElementTypeOrNull(): IrType? {
    if (!isDotNetGenericArray()) return null
    val argument = (this as? IrSimpleType)?.arguments?.singleOrNull() as? IrTypeProjection ?: return null
    return argument.type.takeIf { argument.variance == Variance.INVARIANT }
}

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
        type.isSupportedDotNetPrimitiveArray() -> toPrimitiveArrayType(type)
        type.isDotNetGenericArray() -> toGenericArrayTypeOrNull(type)
        // `Nothing?` — the type of the null literal, and of values the frontend narrowed to
        // definitely-null (e.g. a when-subject temporary initialized from a known-null value) —
        // is reference-shaped storage whose only value is `ldnull`. Plain `Nothing` (no values
        // at all) deliberately stays unmapped.
        type.isNullableNothing() -> DotNetIlValueType.Object
        else -> toNullablePrimitiveTypeOrNull(type)
            ?: toMappedExceptionTypeOrNull(type)
            ?: toUserClassTypeOrNull(type)
            ?: toTypeParameterTypeOrNull(type)
    }

    /**
     * Maps the supported Kotlin primitive-array classifiers to CLR zero-based vectors. Matching
     * by FqName deliberately includes nullable array types: CLR vectors are reference-shaped,
     * so `IntArray` and `IntArray?` have the same IL signature, just like `String`/`String?`.
     */
    private fun toPrimitiveArrayType(type: IrType): DotNetIlValueType.PrimitiveArray {
        val elementType = when (type.classFqName?.asString()) {
            "kotlin.BooleanArray" -> DotNetIlValueType.Boolean
            "kotlin.IntArray" -> DotNetIlValueType.Int32
            "kotlin.LongArray" -> DotNetIlValueType.Int64
            "kotlin.DoubleArray" -> DotNetIlValueType.Float64
            "kotlin.CharArray" -> DotNetIlValueType.Char
            else -> error("Internal .NET backend error: unsupported primitive-array classifier ${type.render()}")
        }
        return DotNetIlValueType.PrimitiveArray(elementType)
    }

    /**
     * Maps invariant Kotlin `Array<E>` to a CLR vector while preserving it as the distinct
     * [DotNetIlValueType.GenericArray] structural kind. Concrete primitive elements are rejected:
     * CLR would give `Array<Int>` and `IntArray` the same `int32[]` signature and collapse legal
     * Kotlin overloads. An OPEN type parameter remains valid (`!n[]`/`!!n[]`) because CLR generic
     * arity and token identity keep its declaration distinct, including value-type
     * instantiations. Projections are never mapped to CLR covariance; nested arrays remain a
     * separate ABI slice.
     */
    private fun toGenericArrayTypeOrNull(type: IrType): DotNetIlValueType.GenericArray? {
        val simpleType = type as? IrSimpleType
            ?: dotNetUnsupported("generic array type ${type.render()} has an unsupported shape")
        val argument = simpleType.arguments.singleOrNull()
            ?: dotNetUnsupported("generic array type ${type.render()} must have exactly one element type")
        val projection = argument as? IrTypeProjection
            ?: dotNetUnsupported("star-projected generic array type ${type.render()} is not supported")
        if (projection.variance != Variance.INVARIANT) {
            dotNetUnsupported(
                "generic array type ${type.render()} has a use-site projection; " +
                        "generic arrays are invariant in the supported .NET model"
            )
        }
        val elementIrType = projection.type
        if (elementIrType.isDotNetGenericArray() || elementIrType.isSupportedDotNetPrimitiveArray()) {
            dotNetUnsupported(
                "generic array type ${type.render()} is nested or contains an array element; " +
                        "jagged arrays are not supported yet"
            )
        }
        val elementType = toDotNetIlValueType(elementIrType) ?: return null
        if (elementType.isSupportedPrimitiveArrayElement()) {
            dotNetUnsupported(
                "generic array type ${type.render()} has a concrete primitive element; " +
                        "its CLR vector would collide with the corresponding primitive-array type"
            )
        }
        if (elementType is DotNetIlValueType.NullableValue) {
            dotNetUnsupported(
                "generic array type ${type.render()} has a nullable primitive element; " +
                        "nullable value-type array elements are not supported yet"
            )
        }
        if (elementType is DotNetIlValueType.PrimitiveArray || elementType is DotNetIlValueType.GenericArray) {
            dotNetUnsupported(
                "generic array type ${type.render()} contains an array element; jagged arrays are not supported yet"
            )
        }
        return DotNetIlValueType.GenericArray(elementType)
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
     * `class 'C'` as `C` (the classifier lookup ignores nullability, like `string`). A GENERIC
     * user-class or interface type maps to a full [instantiation][DotNetIlValueType.GenericInstance]
     * (real CLR reified generics, the Roslyn shape — never erasure), with
     * each type argument mapped recursively through this same mapper, so an argument mentioning
     * an evicted class fails the whole instantiation (null, cascading like any other
     * unavailable type — the fixpoint eviction rule). Use-site variance projections
     * (`Box<out T>`) and star projections are rejected loudly: ECMA-335 has no use-site variance.
     * Declaration-site variance is recorded on generic interface class info and interpreted by
     * [isDotNetAssignableTo]; generic classes stay structurally invariant.
     */
    private fun toUserClassTypeOrNull(type: IrType): DotNetIlValueType? {
        if (type !is IrSimpleType) return null
        val irClass = (type.classifier as? IrClassSymbol)?.owner ?: return null
        val classInfo = availableClasses[irClass] ?: return null
        if (irClass.typeParameters.isEmpty()) {
            return DotNetIlValueType.UserClass(classInfo)
        }
        if (type.arguments.size != irClass.typeParameters.size) return null
        val arguments = type.arguments.map { argument ->
            val projection = argument as? IrTypeProjection
                ?: dotNetUnsupported(
                    "star-projected generic type '${irClass.name.asString()}<*>' is not supported"
                )
            if (projection.variance != Variance.INVARIANT) {
                dotNetUnsupported(
                    "use-site variance projection in '${type.render()}' is not supported " +
                            "(ECMA-335 has no use-site variance; only declaration-site interface " +
                            "variance is represented)"
                )
            }
            toDotNetIlValueType(projection.type) ?: return null
        }
        return DotNetIlValueType.GenericInstance(classInfo, arguments)
    }

    /**
     * A reference to a type parameter of the enclosing generic declaration maps positionally to
     * the CLR `!n` (class) / `!!n` (method) token. Stage 2 additionally carries every supported
     * module-local class/interface bound on the structural token; the rendered slot remains
     * positional and open. Two loud rejections remain deliberate design points:
     * - `T?` (a nullable type-parameter type) has NO uniform CLR representation — `T` may
     *   instantiate to a value type needing `Nullable<T>` and to a reference type needing
     *   nothing — so any declaration mentioning it is rejected (the deferred ABI problem the
     *   hybrid nullability model documents; interface-only CLR constraints can still admit a
     *   value type, so the stage-2 constraint subset does not make this uniform);
     * - constraints outside [dotNetConstraintTypes] are rejected instead of erased.
     * A `T` whose bound is `String`/`String?` never reaches this arm: the string-concat
     * lowering's receiver mapping ([isDotNetStringType]) runs earlier in the dispatch chain and
     * maps it to IL `string` (the pre-existing behavior).
     */
    private fun toTypeParameterTypeOrNull(type: IrType): DotNetIlValueType.TypeParameter? {
        if (type !is IrSimpleType) return null
        val typeParameter = (type.classifier as? IrTypeParameterSymbol)?.owner ?: return null
        val parameterName = typeParameter.name.asString()
        if (type.isMarkedNullable()) {
            dotNetUnsupported(
                "nullable type-parameter type '$parameterName?' has no uniform CLR representation " +
                        "and is not supported by the current generic-constraints model"
            )
        }
        return DotNetIlValueType.TypeParameter(
            typeParameter.index,
            isMethodParameter = typeParameter.parent is IrFunction,
            upperBounds = typeParameter.dotNetConstraintTypes(this),
        )
    }
}

/**
 * Maps the stage-2 constraints of this parameter to live module-local class/interface types.
 * `Any?` is the unconstrained Kotlin default and contributes no metadata. The historical
 * function-only `String` bound keeps its pre-stage-1 slot erosion and is likewise omitted here.
 * Every new constraint is deliberately direct, non-null, and non-generic; accepting a mapped or
 * external type without a complete member model would publish metadata the backend cannot use.
 * A class constraint is sorted before interface constraints, matching the ECMA/Roslyn canonical
 * order regardless of source `where`-clause order.
 */
internal fun IrTypeParameter.dotNetConstraintTypes(
    typeMapper: DotNetIlTypeMapper,
): List<DotNetIlValueType.UserClass> {
    val mappedBounds = superTypes
        .filterNot { it.isNullableAny() || it.isString() || it.isNullableString() }
        .map { bound ->
            val simpleBound = bound as? IrSimpleType
                ?: dotNetUnsupported(
                    "type parameter '${name.asString()}' has an unsupported constraint ${bound.render()}; " +
                            "constraints must be non-null, non-generic module-local classes or interfaces"
                )
            if (simpleBound.isMarkedNullable() || simpleBound.arguments.isNotEmpty()) {
                dotNetUnsupported(
                    "type parameter '${name.asString()}' has an unsupported constraint ${bound.render()}; " +
                            "constraints must be non-null, non-generic module-local classes or interfaces"
                )
            }
            val boundClass = (simpleBound.classifier as? IrClassSymbol)?.owner
                ?: dotNetUnsupported(
                    "type parameter '${name.asString()}' has an unsupported constraint ${bound.render()}; " +
                            "constraints on other type parameters are not supported"
                )
            val mappedBound = typeMapper.toDotNetIlValueType(bound) as? DotNetIlValueType.UserClass
                ?: dotNetUnsupported(
                    "type parameter '${name.asString()}' has an unsupported constraint ${bound.render()}; " +
                            "only classes and interfaces emitted in this module can be constraints"
                )
            boundClass to mappedBound
        }
    return mappedBounds.sortedBy { it.first.isInterface }.map { it.second }
}

/**
 * The formal `<...>` list shared by generic classes, interfaces, and methods.
 * Interface declaration-site variance prefixes the existing constraint/name canon with `+` or
 * `-`; the gates guarantee every class/method parameter reaching here is invariant.
 */
internal fun List<IrTypeParameter>.renderDotNetIlGenericParameters(
    typeMapper: DotNetIlTypeMapper,
): String? = takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">") { typeParameter ->
    val variancePrefix = when (typeParameter.variance) {
        Variance.OUT_VARIANCE -> "+ "
        Variance.IN_VARIANCE -> "- "
        Variance.INVARIANT -> ""
    }
    val constraintPrefix = typeParameter.dotNetConstraintTypes(typeMapper)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ", "(", ") ") { it.nameInSignature }
        .orEmpty()
    variancePrefix + constraintPrefix + typeParameter.name.asString().toIlIdentifier()
}

/**
 * The base-class SUPERTYPE of [this] class within the inheritance model as the declared
 * [IrSimpleType] — carrying a closed or open instantiation of a generic base
 * (`class D : Box<Int>()`, `class D<T> : Box<T>()`), which the classifier-only
 * [dotNetBaseClassOrNull] cannot — or null when no supertype is a proper
 * class (sole supertype `kotlin.Any`, or only interface supertypes). Interface supertypes are
 * skipped — they belong to [dotNetDirectInterfaceTypes] — and the shape gate guarantees at most one
 * proper-class supertype on gate-passing classes.
 */
internal fun IrClass.dotNetBaseSuperTypeOrNull(): IrSimpleType? =
    superTypes.firstNotNullOfOrNull { superType ->
        if (superType.isAny()) null
        else (superType as? IrSimpleType)
            ?.takeIf { ((it.classifier as? IrClassSymbol)?.owner)?.isInterface == false }
    }

/** The classifier of [dotNetBaseSuperTypeOrNull], for sites that only need the class link. */
internal fun IrClass.dotNetBaseClassOrNull(): IrClass? =
    (dotNetBaseSuperTypeOrNull()?.classifier as? IrClassSymbol)?.owner

/**
 * The shared generic type-parameter gate: a supported parameter is non-reified and is either
 * unconstrained (`Any?`) or has direct non-null, non-generic class/interface bounds.
 * [allowDeclarationSiteVariance] is true only for generic interfaces, the sole Kotlin
 * declaration kind in this backend that has a direct CLR `+`/`-` metadata representation;
 * classes and functions remain invariant. [dotNetConstraintTypes] performs the live
 * module-local mapping later, once the class registry exists. Everything else is rejected loudly
 * at the declaration (never erased):
 * - `reified` requires the inlining model this backend does not have;
 * - declaration-site variance (`out`/`in`) is rejected outside interfaces because ECMA-335
 *   (II.10.1.7) allows variance only on interfaces and delegates; emitting a Kotlin class's
 *   variance as invariant would silently change assignability;
 * - constraints with nullable, generic-instantiation, or type-parameter bounds stay outside
 *   stage 2 — with ONE pre-existing exception, enabled by
 *   [allowStringBounds]: a `T` bounded by `String`/`String?` predates this slice (the
 *   string-concat lowering's receiver mapping sends every use of such a `T` to IL `string`,
 *   see [isDotNetStringType]) and stays supported on FUNCTIONS for compatibility — the
 *   function still declares its real `<T>` arity and call sites still carry the instantiation
 *   (no erasure of the token; only the SLOT type is the bound's `string`).
 */
internal fun checkDotNetTypeParametersSupported(
    typeParameters: List<IrTypeParameter>,
    ownerDescription: String,
    allowStringBounds: Boolean = false,
    allowDeclarationSiteVariance: Boolean = false,
) {
    for (typeParameter in typeParameters) {
        val parameterName = typeParameter.name.asString()
        if (typeParameter.isReified) {
            dotNetUnsupported(
                "$ownerDescription has a reified type parameter '$parameterName'; " +
                        "reified type parameters are not supported (no inlining model)"
            )
        }
        if (!allowDeclarationSiteVariance && typeParameter.variance != Variance.INVARIANT) {
            dotNetUnsupported(
                "$ownerDescription declares '${typeParameter.variance.label}' variance on type parameter " +
                        "'$parameterName'; declaration-site variance is not supported (ECMA-335 allows variance " +
                        "only on interfaces and delegates; generic interfaces preserve it directly)"
            )
        }
        val unsupportedBound = typeParameter.superTypes.firstOrNull { superType ->
            when {
                superType.isNullableAny() -> false
                superType.isString() || superType.isNullableString() -> !allowStringBounds
                else -> {
                    val simpleType = superType as? IrSimpleType
                    simpleType == null || simpleType.isMarkedNullable() ||
                            simpleType.arguments.isNotEmpty() || simpleType.classifier !is IrClassSymbol
                }
            }
        }
        if (unsupportedBound != null) {
            dotNetUnsupported(
                "$ownerDescription constrains type parameter '$parameterName' with unsupported type " +
                        "${unsupportedBound.render()}; constraints must be non-null, non-generic classes or interfaces"
            )
        }
    }
}

/**
 * The generic-method gate, run over top-level functions during gathering and over member
 * functions by their owning class/interface shape gate. A generic function must additionally be
 * non-inline (inline implies the missing inlining model, and `reified` — rejected by the shared
 * gate — is only expressible on inline functions). Non-generic functions pass untouched.
 */
internal fun IrSimpleFunction.checkDotNetGenericFunctionSupported() {
    if (typeParameters.isEmpty()) return
    val functionName = name.asString()
    if (isInline) {
        dotNetUnsupported(
            "generic function '$functionName' is inline; inline generic functions are not supported (no inlining model)"
        )
    }
    checkDotNetTypeParametersSupported(typeParameters, "function '$functionName'", allowStringBounds = true)
}

/**
 * The generic-arity marker of an IL method-identity key: CLR method identity includes the
 * generic ARITY (`f` and ``f`1`` are distinct methods, the Roslyn overload rule), so the
 * member/facade identity gates append it — without it a generic `fun <T> f(x: Int)` would
 * falsely clash with a plain `fun f(x: Int)`. Two same-arity generic functions whose parameters
 * differ only in the type-parameter NAME still clash correctly: `!!n` identity is positional.
 */
internal fun IrSimpleFunction.dotNetIlGenericAritySuffix(): String =
    if (typeParameters.isEmpty()) "" else "`${typeParameters.size}"

/**
 * The directly implemented interfaces of [this] class (for an interface: its directly extended
 * super-interfaces), in supertype-list order. The interface-model counterpart of
 * [dotNetBaseClassOrNull]: the emitter's link pre-pass feeds them into
 * [DotNetIlClassInfo.interfaces] for the assignability walk, and the render re-resolves them
 * through the live class map every fixpoint round for the `implements` line.
 */
internal fun IrClass.dotNetDirectInterfaceTypes(): List<IrSimpleType> =
    superTypes.mapNotNull { superType ->
        (superType as? IrSimpleType)
            ?.takeIf { ((it.classifier as? IrClassSymbol)?.owner)?.isInterface == true }
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
