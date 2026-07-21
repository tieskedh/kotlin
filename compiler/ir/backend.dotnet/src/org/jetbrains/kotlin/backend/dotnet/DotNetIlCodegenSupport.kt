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
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.render

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
    val isErasedCallableInvoke = isDotNetErasedCallableInvoke()
    val isErasedPropertyAccess = isDotNetErasedPropertyAccess()
    val ilReturnType = if (isErasedCallableInvoke || (isErasedPropertyAccess && name.asString() == "get")) {
        DotNetIlReturnType.Value(DotNetIlValueType.Object)
    } else {
        typeMapper.toDotNetIlReturnType(returnType)
            ?: dotNetUnsupported("return type ${returnType.render()} is not supported")
    }
    // A member function's dispatch receiver is parameters[0]; its type (the owning user class)
    // stays in the mapped parameter list so argument zipping and call-site pop counts stay
    // uniform, while `hasThis` makes signature rendering and slot numbering treat it as the
    // implicit CLR argument 0 (see DotNetIlMethodSignature).
    val hasThis = parameters.firstOrNull()?.kind == IrParameterKind.DispatchReceiver
    val parameterTypes = if (isErasedCallableInvoke || isErasedPropertyAccess) {
        parameters.map { parameter ->
            if (parameter.kind == IrParameterKind.DispatchReceiver) {
                typeMapper.toDotNetIlValueType(parameter.type)
                    ?: dotNetUnsupported(
                        "callable dispatch receiver '${parameter.name.asString()}' has unsupported " +
                                "type ${parameter.type.render()}"
                    )
            } else {
                DotNetIlValueType.Object
            }
        }
    } else {
        parameters.dotNetParameterTypes(typeMapper)
    }
    return DotNetIlMethodSignature(ilReturnType, parameterTypes, hasThis)
}

/**
 * The IL method name of a function: property accessors get the CLR-conventional `get_x`/`set_x`
 * derived from the property name (the JVM backend derives `getX`/`setX` the same way in
 * `MethodSignatureMapper`; the underscore spelling is what `.property` metadata conventionally
 * binds to, probe-verified). Kotlin Any's three virtuals use their existing System.Object slot
 * names; everything else keeps its Kotlin name. The result is still rendered through
 * [toIlIdentifier] wherever it is printed.
 */
internal fun IrSimpleFunction.dotNetIlMethodName(): String {
    if (isDotNetErasedCallableInvoke()) return "Invoke"
    if (isDotNetErasedPropertyAccess()) return if (name.asString() == "get") "Get" else "Set"
    dotNetAnyMethodOrNull()?.let { return it.clrName }
    val property = correspondingPropertySymbol?.owner ?: return name.asString()
    val prefix = if (isGetter) "get_" else "set_"
    return prefix + property.name.asString()
}

/** The Kotlin Any member and the CLR System.Object virtual slot that physically represents it. */
internal enum class DotNetAnyMethod(
    val kotlinName: String,
    val clrName: String,
) {
    EQUALS("equals", "Equals"),
    HASH_CODE("hashCode", "GetHashCode"),
    TO_STRING("toString", "ToString"),
}

/**
 * The [DotNetAnyMethod] overridden by this declaration, or null for an unrelated member.
 *
 * The JVM backend maps kotlin.Any itself to java.lang.Object. DotNet follows that precedent with
 * System.Object, whose method names differ from Kotlin's. This pipeline's builtin symbols do not
 * carry usable IdSignatures, so the relationship is found with the same type-based
 * [allOverridden] walk used by the class-shape gate: either the function is declared directly on
 * kotlin.Any, or one of its transitive overridden declarations is. The frontend has already
 * checked the three Kotlin signatures, so the name is sufficient after that ownership check.
 */
internal fun IrSimpleFunction.dotNetAnyMethodOrNull(): DotNetAnyMethod? {
    val declaredOnAny = (parent as? IrClass)?.defaultType?.isAny() == true
    val overridesAny = allOverridden().any { overridden ->
        (overridden.parent as? IrClass)?.defaultType?.isAny() == true
    }
    if (declaredOnAny || overridesAny) {
        return DotNetAnyMethod.entries.singleOrNull { it.kotlinName == name.asString() }
    }

    // An implementation reached through a built-in interface can override that interface's
    // fake Any member without allOverridden() retaining the terminal kotlin.Any declaration.
    // Recognize the three exact instance shapes on concrete owners. In particular, count
    // extension and context receivers as physical arguments: a member extension merely named
    // hashCode/toString/equals is unrelated to System.Object even when its regular parameters
    // happen to resemble an Any member.
    val owner = parent as? IrClass ?: return null
    if (owner.isInterface) return null
    if (parameters.firstOrNull()?.kind != IrParameterKind.DispatchReceiver) return null
    val nonDispatchParameters = parameters.drop(1)
    return when (name.asString()) {
        "equals" -> DotNetAnyMethod.EQUALS.takeIf {
            returnType.isBoolean() &&
                    nonDispatchParameters.singleOrNull()?.let { parameter ->
                        parameter.kind == IrParameterKind.Regular && parameter.type.isNullableAny()
                    } == true
        }
        "hashCode" -> DotNetAnyMethod.HASH_CODE.takeIf {
            returnType.isInt() && nonDispatchParameters.isEmpty()
        }
        "toString" -> DotNetAnyMethod.TO_STRING.takeIf {
            returnType.isString() && nonDispatchParameters.isEmpty()
        }
        else -> null
    }
}

/** Whether this is a fixed-arity callable member physically emitted as erased CLR `Invoke`. */
internal fun IrSimpleFunction.isDotNetErasedCallableInvoke(): Boolean {
    if (name.asString() != "invoke") return false
    if ((parent as? IrClass)?.dotNetFixedFunctionArityOrNull() != null) return true
    return allOverridden().any { overridden ->
        (overridden.parent as? IrClass)?.dotNetFixedFunctionArityOrNull() != null
    }
}

/** Whether this is a fixed-arity KProperty get/set member with an erased physical CLR slot. */
internal fun IrSimpleFunction.isDotNetErasedPropertyAccess(): Boolean {
    if (name.asString() !in setOf("get", "set")) return false
    if ((parent as? IrClass)?.dotNetFixedPropertyArityOrNull() != null) return true
    return allOverridden().any { overridden ->
        (overridden.parent as? IrClass)?.dotNetFixedPropertyArityOrNull() != null
    }
}

/** Whether this function's physical CLR result is object while its Kotlin result stays logical. */
internal fun IrSimpleFunction.isDotNetErasedObjectResult(): Boolean =
    isDotNetErasedCallableInvoke() ||
            (isDotNetErasedPropertyAccess() && name.asString() == "get")

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
private enum class DotNetGenericInterfaceMapping(
    val physicalView: DotNetGenericInterfaceView,
    val canonicalizeNestedInterfaces: Boolean,
) {
    CANONICAL(DotNetGenericInterfaceView.CANONICAL, false),
    DECLARED(DotNetGenericInterfaceView.DECLARED, false),
    EXACT(DotNetGenericInterfaceView.EXACT, false),
    DECLARED_SIGNATURE(DotNetGenericInterfaceView.DECLARED, true),
    EXACT_SIGNATURE(DotNetGenericInterfaceView.EXACT, true),
}

internal class DotNetIlTypeMapper private constructor(
    private val availableClasses: Map<IrClass, DotNetIlClassInfo>,
    val coreLibrary: DotNetCoreLibraryProfile,
    private val externalDeclarations: DotNetExternalDeclarations,
    private val genericInterfaces: Map<IrClass, DotNetGenericInterfaceInfo>,
    private val genericInterfaceMapping: DotNetGenericInterfaceMapping,
    private val assemblyReferenceSink: (String) -> Unit,
) {
    constructor(
        availableClasses: Map<IrClass, DotNetIlClassInfo>,
        coreLibrary: DotNetCoreLibraryProfile = DEFAULT_EXECUTABLE_CORE_LIBRARY,
        externalDeclarations: DotNetExternalDeclarations = DotNetExternalDeclarations(emptyList()),
        genericInterfaces: Map<IrClass, DotNetGenericInterfaceInfo> = emptyMap(),
        assemblyReferenceSink: (String) -> Unit = {},
    ) : this(
        availableClasses,
        coreLibrary,
        externalDeclarations,
        genericInterfaces,
        DotNetGenericInterfaceMapping.CANONICAL,
        assemblyReferenceSink,
    )

    private fun withGenericInterfaceMapping(mapping: DotNetGenericInterfaceMapping): DotNetIlTypeMapper =
        DotNetIlTypeMapper(
            availableClasses,
            coreLibrary,
            externalDeclarations,
            genericInterfaces,
            mapping,
            assemblyReferenceSink,
        )

    private fun canonicalGenericInterfaceView(): DotNetIlTypeMapper =
        withGenericInterfaceMapping(DotNetGenericInterfaceMapping.CANONICAL)

    fun declaredGenericInterfaceView(): DotNetIlTypeMapper =
        withGenericInterfaceMapping(DotNetGenericInterfaceMapping.DECLARED)

    fun exactGenericInterfaceView(): DotNetIlTypeMapper =
        withGenericInterfaceMapping(DotNetGenericInterfaceMapping.EXACT)

    fun canonicalGenericInterfaceSignatureView(): DotNetIlTypeMapper =
        canonicalGenericInterfaceView()

    /**
     * A typed capability retains the declaring interface's `!n` parameter space, but a generic
     * Kotlin interface occurring inside one of its member types remains the canonical identity.
     * The logical contract may return a canonical-only provider, so recursively promising the
     * nested declared/exact capability would be unsound.
     */
    fun declaredGenericInterfaceSignatureView(): DotNetIlTypeMapper =
        withGenericInterfaceMapping(DotNetGenericInterfaceMapping.DECLARED_SIGNATURE)

    fun exactGenericInterfaceSignatureView(): DotNetIlTypeMapper =
        withGenericInterfaceMapping(DotNetGenericInterfaceMapping.EXACT_SIGNATURE)

    fun genericInterfaceSignatureView(view: DotNetGenericInterfaceMemberView): DotNetIlTypeMapper =
        when (view) {
            DotNetGenericInterfaceMemberView.DECLARED -> declaredGenericInterfaceSignatureView()
            DotNetGenericInterfaceMemberView.EXACT -> exactGenericInterfaceSignatureView()
        }

    fun isSplitGenericInterface(irClass: IrClass): Boolean =
        genericInterfaces.containsKey(irClass) ||
                DotNetRuntimeTypes.genericInterfaceInfoFor(irClass) != null ||
                externalDeclarations.declaredClassInfoOrNull(irClass) != null

    fun isSplitGenericInterfaceType(type: IrType): Boolean {
        val simpleType = type as? IrSimpleType ?: return false
        val irClass = (simpleType.classifier as? IrClassSymbol)?.owner ?: return false
        return isSplitGenericInterface(irClass)
    }

    fun genericInterfaceMemberView(
        member: IrSimpleFunction,
        interfaceClass: IrClass,
    ): DotNetGenericInterfaceMemberView =
        member.dotNetGenericInterfaceMemberView(interfaceClass, ::isSplitGenericInterface)

    fun genericInterfaceMemberViews(
        member: IrSimpleFunction,
        interfaceClass: IrClass,
    ): List<DotNetGenericInterfaceMemberView> =
        member.dotNetGenericInterfaceMemberViews(interfaceClass, ::isSplitGenericInterface)

    fun isClrLegalDeclaredGenericInterfaceSupertype(type: IrType, owner: IrClass): Boolean =
        type.isDotNetClrLegalDeclaredSupertype(owner, ::isSplitGenericInterface)

    fun genericInterfaceInfoOrNull(irClass: IrClass): DotNetGenericInterfaceInfo? =
        (genericInterfaces[irClass]
            ?: DotNetRuntimeTypes.genericInterfaceInfoFor(irClass)
            ?: run {
                val declared = externalDeclarations.declaredClassInfoOrNull(irClass) ?: return null
                val canonical = externalDeclarations.classInfoOrNull(irClass, canonicalGenericInterfaceView())
                    ?: return null
                DotNetGenericInterfaceInfo(canonical, declared, externalDeclarations.exactClassInfoOrNull(irClass))
            }).also(::recordAssemblyReferences)

    fun genericInterfaceTypedMethodName(member: IrSimpleFunction): String =
        DotNetRuntimeTypes.genericInterfaceTypedMethodNameOrNull(member) ?: member.dotNetIlMethodName()

    /**
     * Maps the OUTERMOST interface to one typed capability while mapping each logical type
     * argument by its universally guaranteed carrier. In particular,
     * `Producer<Producer<T>>` probes `Producer<Producer>` rather than recursively promising a
     * `Producer<T>` capability for the produced value.
     */
    fun genericInterfaceCapabilityTypeOrNull(
        type: IrType,
        view: DotNetGenericInterfaceView,
    ): DotNetIlValueType.GenericInstance? {
        if (view == DotNetGenericInterfaceView.CANONICAL) return null
        val simpleType = type as? IrSimpleType ?: return null
        val irClass = (simpleType.classifier as? IrClassSymbol)?.owner ?: return null
        val info = genericInterfaceInfoOrNull(irClass) ?: return null
        if (simpleType.arguments.size != info.declaredClassInfo.typeParameterCount) return null
        val classInfo = info.classInfo(view) ?: return null
        val carrierMapper = when (view) {
            DotNetGenericInterfaceView.DECLARED -> declaredGenericInterfaceSignatureView()
            DotNetGenericInterfaceView.EXACT -> exactGenericInterfaceSignatureView()
            DotNetGenericInterfaceView.CANONICAL -> error("handled above")
        }
        val arguments = simpleType.arguments.map { argument ->
            val projection = argument as? IrTypeProjection ?: return null
            if (projection.variance != Variance.INVARIANT) return null
            carrierMapper.toDotNetIlValueType(projection.type) ?: return null
        }
        return DotNetIlValueType.GenericInstance(classInfo, arguments)
    }

    /**
     * The class info of [irClass] while it is still available, or null once (or if) the emitter
     * removed it — member references (`newobj`, `this(...)` delegations, `ldfld`/`stfld`) go
     * through this lookup so a removed class fails its users instead of leaving stale IL text.
     */
    fun classInfoOrNull(irClass: IrClass): DotNetIlClassInfo? {
        val runtimeGenericInfo = DotNetRuntimeTypes.genericInterfaceInfoFor(irClass)
        return (when (genericInterfaceMapping.physicalView) {
            DotNetGenericInterfaceView.CANONICAL ->
                genericInterfaces[irClass]?.canonicalClassInfo
                    ?: runtimeGenericInfo?.canonicalClassInfo
                    ?: availableClasses[irClass]
            DotNetGenericInterfaceView.DECLARED ->
                genericInterfaces[irClass]?.declaredClassInfo
                    ?: runtimeGenericInfo?.declaredClassInfo
                    ?: externalDeclarations.declaredClassInfoOrNull(irClass)
                    ?: availableClasses[irClass]
            DotNetGenericInterfaceView.EXACT ->
                genericInterfaces[irClass]?.mostSpecificCapabilityClassInfo
                    ?: runtimeGenericInfo?.mostSpecificCapabilityClassInfo
                    ?: externalDeclarations.exactClassInfoOrNull(irClass)
                    ?: externalDeclarations.declaredClassInfoOrNull(irClass)
                    ?: availableClasses[irClass]
        }
            ?: DotNetRuntimeTypes.classInfoFor(irClass)
            ?: DotNetStdlibLibrary.publicImplementationClassInfoOrNull(irClass)
            ?: externalDeclarations.classInfoOrNull(irClass, this)).also { classInfo ->
            classInfo?.let(::recordAssemblyReference)
        }
    }

    fun referencedFunctionInfoOrNull(function: IrSimpleFunction): DotNetIlFunctionInfo? =
        (DotNetRuntimeTypes.genericInterfaceFunctionInfoOrNull(function, this)
            ?: DotNetStdlibLibrary.implementationFunctionInfoOrNull(function, this)
            ?: externalDeclarations.functionInfoOrNull(function, this)).also { functionInfo ->
            functionInfo?.owner?.let(::recordAssemblyReference)
        }

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
    fun toDotNetIlValueType(type: IrType): DotNetIlValueType? =
        mapDotNetIlValueType(type).also { valueType ->
            valueType?.let(::recordAssemblyReferences)
        }

    private fun mapDotNetIlValueType(type: IrType): DotNetIlValueType? {
        DotNetRuntimeTypes.mapCompilerRuntimeType(type)?.let { return it }
        if (
            genericInterfaceMapping.physicalView == DotNetGenericInterfaceView.CANONICAL &&
            type.referencesErasedInterfaceParameter()
        ) {
            val topClass = ((type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
            if (topClass == null || !isSplitGenericInterface(topClass)) {
                // A reified carrier such as Holder<T>, Array<T>, or T? has no single closed CLR
                // instantiation once T belongs to a canonical non-generic interface. Object is
                // the only identity-preserving universal carrier at this boundary.
                return DotNetIlValueType.Object
            }
        }
        return when {
            type.isBoolean() -> DotNetIlValueType.Boolean
            type.isInt() -> DotNetIlValueType.Int32
            type.isLong() -> DotNetIlValueType.Int64
            type.isDouble() -> DotNetIlValueType.Float64
            type.isChar() -> DotNetIlValueType.Char
            type.isDotNetStringType() -> DotNetIlValueType.String
            type.isAny() || type.isNullableAny() -> DotNetIlValueType.Object
            type.isSupportedDotNetPrimitiveArray() -> toPrimitiveArrayType(type)
            type.isDotNetGenericArray() -> toGenericArrayTypeOrNull(type)
            // Both nullable and non-null bottom types were already mapped above to the same
            // runtime-owned reference carrier, mirroring the JVM's java.lang.Void mapping.
            // Kotlin nullability metadata retains the distinction; codegen performs the legal
            // Nothing? -> arbitrary nullable-type coercion without claiming CLR assignability.
            else -> toNullablePrimitiveTypeOrNull(type)
                ?: toMappedExceptionTypeOrNull(type)
                ?: toUserClassTypeOrNull(type)
                ?: toTypeParameterTypeOrNull(type)
        }
    }

    /** Maps a specialized Kotlin array to its canonical Kotlin.Runtime wrapper reference. */
    private fun toPrimitiveArrayType(type: IrType): DotNetIlValueType.PrimitiveArray {
        val entry = DotNetPrimitiveArrays.entry(type.classFqName)
            ?: error("Internal .NET backend error: unsupported primitive-array classifier ${type.render()}")
        return DotNetIlValueType.PrimitiveArray(entry.elementType)
    }

    /**
     * Maps invariant Kotlin `Array<E>` to a CLR vector while preserving it as the distinct
     * [DotNetIlValueType.GenericArray] structural kind. Concrete primitive elements are legal and
     * retain the natural CLR vector (`Array<Int>` -> `int32[]`) because specialized primitive
     * arrays now have distinct Kotlin.Runtime wrapper types. An OPEN type parameter remains valid
     * (`!n[]`/`!!n[]`) and substitutes reified CLR element types. Projections are never mapped to
     * CLR covariance.
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
        val elementType = toDotNetIlValueType(elementIrType) ?: return null
        if (elementType is DotNetIlValueType.NullableValue) {
            dotNetUnsupported(
                "generic array type ${type.render()} has a nullable primitive element; " +
                        "nullable value-type array elements are not supported yet"
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
        return DotNetIlValueType.NullableValue(elementType, coreLibrary.reference)
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
            is DotNetMappedExceptions.Entry.Mapped ->
                DotNetIlValueType.MappedClass(entry.carrierTypeRef(coreLibrary.reference))
            is DotNetMappedExceptions.Entry.Rejected -> dotNetUnsupported(entry.reason)
            null -> null
        }

    /**
     * A class-like type maps while its user class is available or when it is a registered runtime
     * classifier; `C?` maps to the same
     * `class 'C'` as `C` (the classifier lookup ignores nullability, like `string`). A GENERIC
     * generic class or non-split CLR interface maps to a full
     * [instantiation][DotNetIlValueType.GenericInstance] (a real CLR reified-generic shape), with
     * each type argument mapped recursively through this same mapper. An argument mentioning an
     * evicted class therefore fails the whole instantiation (the normal fixpoint-eviction rule).
     * Use-site variance projections (`Box<out T>`) and star projections remain unsupported for
     * those reified shapes because ECMA-335 has no use-site variance. Kotlin-owned generic
     * interfaces take the earlier split-interface arm instead: their canonical storage identity
     * is non-generic, so projections and stars do not alter its CLR type and remain Kotlin
     * metadata rather than CLR generic conversions. Declaration-site variance is used only by
     * the optional declared capability; generic classes stay structurally invariant.
     */
    private fun toUserClassTypeOrNull(type: IrType): DotNetIlValueType? {
        if (type !is IrSimpleType) return null
        val irClass = (type.classifier as? IrClassSymbol)?.owner ?: return null
        if (
            isSplitGenericInterface(irClass) &&
            (genericInterfaceMapping.physicalView == DotNetGenericInterfaceView.CANONICAL ||
                    genericInterfaceMapping.canonicalizeNestedInterfaces)
        ) {
            // The first arm is Kotlin's universal storage identity. The second is the carrier of
            // a member on a typed outer capability: type parameters remain typed elsewhere in
            // that signature, but a nested Kotlin interface is not itself a guaranteed typed
            // capability.
            val canonical = genericInterfaceInfoOrNull(irClass)?.canonicalClassInfo
                ?: classInfoOrNull(irClass)
                ?: return null
            return DotNetIlValueType.UserClass(canonical)
        }
        val classInfo = classInfoOrNull(irClass) ?: return null
        if (classInfo.typeParameterCount == 0) {
            return DotNetIlValueType.UserClass(classInfo)
        }
        if (type.arguments.size != classInfo.typeParameterCount) return null
        val arguments = (0 until classInfo.typeParameterCount).map { index ->
            val argument = type.arguments[index]
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
    private fun toTypeParameterTypeOrNull(type: IrType): DotNetIlValueType? {
        if (type !is IrSimpleType) return null
        val typeParameter = (type.classifier as? IrTypeParameterSymbol)?.owner ?: return null
        val parameterName = typeParameter.name.asString()
        if (type.isMarkedNullable()) {
            dotNetUnsupported(
                "nullable type-parameter type '$parameterName?' has no uniform CLR representation " +
                        "and is not supported by the current generic-constraints model"
            )
        }
        val parentGenericInterface = (typeParameter.parent as? IrClass)
            ?.takeIf(::isSplitGenericInterface)
        if (
            genericInterfaceMapping.physicalView == DotNetGenericInterfaceView.CANONICAL &&
            parentGenericInterface != null
        ) {
            return DotNetIlValueType.Object
        }
        return DotNetIlValueType.TypeParameter(
            typeParameter.index,
            isMethodParameter = typeParameter.parent is IrFunction,
            upperBounds = typeParameter.dotNetConstraintTypes(this, forMetadata = false).flatMap { constraint ->
                when (constraint) {
                    is DotNetIlValueType.UserClass -> listOf(constraint)
                    // A CLR `R : T` constraint is represented directly in metadata. For the
                    // backend's structural member model, R also inherits T's effective concrete
                    // class/interface bounds; the positional T token itself is not a class owner.
                    is DotNetIlValueType.TypeParameter -> constraint.upperBounds
                    else -> emptyList()
                }
            },
        )
    }

    private fun IrType.referencesErasedInterfaceParameter(): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        val parameterOwner = (simpleType.classifier as? IrTypeParameterSymbol)?.owner?.parent as? IrClass
        if (parameterOwner != null && isSplitGenericInterface(parameterOwner)) return true
        return simpleType.arguments.any { argument ->
            (argument as? IrTypeProjection)?.type?.referencesErasedInterfaceParameter() == true
        }
    }

    private fun recordAssemblyReference(classInfo: DotNetIlClassInfo) {
        classInfo.assemblyName?.let(assemblyReferenceSink)
    }

    fun recordAssemblyReference(assemblyName: String) {
        assemblyReferenceSink(assemblyName)
    }

    private fun recordAssemblyReferences(info: DotNetGenericInterfaceInfo) {
        recordAssemblyReference(info.canonicalClassInfo)
        recordAssemblyReference(info.declaredClassInfo)
        info.exactClassInfo?.let(::recordAssemblyReference)
    }

    private fun recordAssemblyReferences(type: DotNetIlValueType) {
        when (type) {
            is DotNetIlValueType.UserClass -> recordAssemblyReference(type.classInfo)
            is DotNetIlValueType.GenericInstance -> {
                recordAssemblyReference(type.classInfo)
                type.arguments.forEach(::recordAssemblyReferences)
            }
            is DotNetIlValueType.GenericArray -> recordAssemblyReferences(type.elementType)
            is DotNetIlValueType.PrimitiveArray -> recordAssemblyReferences(type.elementType)
            is DotNetIlValueType.NullableValue -> recordAssemblyReferences(type.elementType)
            is DotNetIlValueType.TypeParameter -> type.upperBounds.forEach(::recordAssemblyReferences)
            DotNetIlValueType.Boolean,
            DotNetIlValueType.Char,
            DotNetIlValueType.Float64,
            DotNetIlValueType.Int32,
            DotNetIlValueType.Int64,
            is DotNetIlValueType.MappedClass,
            DotNetIlValueType.Object,
            DotNetIlValueType.String,
                -> {}
        }
    }
}

/**
 * Maps the supported constraints of this parameter to their physical CLR types.
 * `Any?` is the unconstrained Kotlin default and contributes no metadata. The historical
 * function-only `String` bound keeps its pre-stage-1 slot erosion and is likewise omitted here.
 * A bound on another type parameter is normally retained as its positional `!n`/`!!n` TypeSpec.
 * The exception is a method bound which depends on a type parameter of a split Kotlin generic
 * interface. Such a relationship remains part of the logical Kotlin signature, but is omitted
 * from every executable CLR view of that member. A CLR variant interface containing `R : T`
 * load-fails when `T` is variant, while retaining the constraint only on an invariant exact DIM
 * rejects valid Kotlin calls through a widened declaration-site-variance view. Portable closed
 * value-type views cannot express the substituted relationship either. The exact view still
 * keeps the strongly typed parameters and result; only the incompatible physical constraint is
 * weakened. A future C# export facade may publish a convenience constraint, but that facade must
 * not become Kotlin's virtual dispatch slot. Other constraints remain direct, non-null, and
 * non-generic; accepting a mapped or external type without a complete member model would publish
 * metadata the backend cannot use.
 * A class constraint is sorted before interface constraints, matching the ECMA/Roslyn canonical
 * order regardless of source `where`-clause order. Type-parameter constraints retain their source
 * position after concrete constraints; GenericParamConstraint row order has no semantic effect.
 * [forMetadata] is false only while deriving codegen's structural upper-bound model. That model
 * retains logical Kotlin bounds needed by the canonical body. Metadata rendering passes true and
 * applies the physical erasure above.
 *
 */
internal fun IrTypeParameter.dotNetConstraintTypes(
    typeMapper: DotNetIlTypeMapper,
    forMetadata: Boolean = true,
): List<DotNetIlValueType> {
    val mappedBounds = superTypes
        .filterNot { it.isNullableAny() || it.isString() || it.isNullableString() }
        .mapIndexedNotNull { index, bound ->
            val simpleBound = bound as? IrSimpleType
                ?: dotNetUnsupported(
                    "type parameter '${name.asString()}' has an unsupported constraint ${bound.render()}; " +
                            "constraints must be non-null type parameters or non-generic module-local classes/interfaces"
                )
            if (simpleBound.isMarkedNullable() || simpleBound.arguments.isNotEmpty()) {
                dotNetUnsupported(
                    "type parameter '${name.asString()}' has an unsupported constraint ${bound.render()}; " +
                            "constraints must be non-null type parameters or non-generic module-local classes/interfaces"
                )
            }
            val boundParameter = (simpleBound.classifier as? IrTypeParameterSymbol)?.owner
            if (
                forMetadata && boundParameter != null &&
                (origin == DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER ||
                        (boundParameter.parent as? IrClass)?.let(typeMapper::isSplitGenericInterface) == true)
            ) {
                return@mapIndexedNotNull null
            }
            when (val classifier = simpleBound.classifier) {
                is IrClassSymbol -> {
                    val mappedBound = typeMapper.toDotNetIlValueType(bound) as? DotNetIlValueType.UserClass
                        ?: dotNetUnsupported(
                            "type parameter '${name.asString()}' of " +
                                    when (val owner = parent) {
                                        is IrSimpleFunction ->
                                            "method '${owner.name.asString()}'"
                                        is IrClass -> "class '${owner.name.asString()}'"
                                        else -> owner.toString()
                                    } +
                                    " has an unsupported constraint ${bound.render()}; only classes and " +
                                    "interfaces emitted in this module can be constraints"
                        )
                    Triple(if (classifier.owner.isInterface) 1 else 0, index, mappedBound)
                }
                is IrTypeParameterSymbol -> when (val mappedBound = typeMapper.toDotNetIlValueType(bound)) {
                    is DotNetIlValueType.TypeParameter -> Triple(2, index, mappedBound)
                    // The erased canonical view of a split interface has no owner-generic slot
                    // with which to express `R : T`. Its `T` maps to object, so this physical view
                    // widens the constraint. Metadata on the typed views and helper is handled by
                    // the owner-dependent rule above.
                    DotNetIlValueType.Object -> null
                    else -> dotNetUnsupported(
                        "type parameter '${name.asString()}' has an unsupported constraint ${bound.render()}; " +
                                "the bound type parameter has no CLR generic-parameter representation"
                    )
                }
                else -> dotNetUnsupported(
                    "type parameter '${name.asString()}' has an unsupported constraint ${bound.render()}; " +
                            "constraints must name a type parameter or a module-local class/interface"
                )
            }
        }
    return mappedBounds.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
}
/**
 * The formal `<...>` list shared by generic classes, interfaces, and methods.
 * Interface declaration-site variance prefixes the existing constraint/name canon with `+` or
 * `-`; the gates guarantee every class/method parameter reaching here is invariant.
 */
internal fun List<IrTypeParameter>.renderDotNetIlGenericParameters(
    typeMapper: DotNetIlTypeMapper,
    varianceOverrides: List<Variance>? = null,
): String? = takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">") { typeParameter ->
    require(varianceOverrides == null || varianceOverrides.size == size)
    val physicalVariance = varianceOverrides?.get(typeParameter.index) ?: typeParameter.variance
    val variancePrefix = when (physicalVariance) {
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
 * unconstrained (`Any?`) or has direct non-null type-parameter or non-generic class/interface bounds.
 * [allowDeclarationSiteVariance] is true only for generic interfaces, the sole Kotlin
 * declaration kind in this backend that has a direct CLR `+`/`-` metadata representation;
 * classes and functions remain invariant. [dotNetConstraintTypes] performs the live
 * module-local mapping later, once the class registry exists. Everything else is rejected loudly
 * at the declaration (never erased):
 * - `reified` requires the inlining model this backend does not have;
 * - declaration-site variance (`out`/`in`) is rejected outside interfaces because ECMA-335
 *   (II.10.1.7) allows variance only on interfaces and delegates; emitting a Kotlin class's
 *   variance as invariant would silently change assignability;
 * - nullable and generic-instantiation constraints stay outside this stage; direct bounds on
 *   another owner or method type parameter are represented by CLR VAR/MVAR TypeSpecs. ONE
 *   pre-existing exception, enabled by
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
                            simpleType.arguments.isNotEmpty() ||
                            (simpleType.classifier !is IrClassSymbol &&
                                    simpleType.classifier !is IrTypeParameterSymbol)
                }
            }
        }
        if (unsupportedBound != null) {
            dotNetUnsupported(
                "$ownerDescription constrains type parameter '$parameterName' with unsupported type " +
                        "${unsupportedBound.render()}; constraints must be non-null type parameters or " +
                        "non-generic classes/interfaces"
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
 * `callvirt` — probe-verified, `inheritprobe_s2`, `ifaceprobe_s1`), for abstract members, and for
 * `open` members of open/abstract/sealed classes (new declarations introduce a fresh `newslot`
 * slot). An `open` member of a FINAL class is deliberately NOT virtual: nothing can ever
 * override it, so it keeps the final-class model's plain non-virtual `call` (the JVM has no such
 * distinction — everything
 * non-private is virtual bytecode-side and the JIT devirtualizes; the CLR makes virtualness a
 * declaration-site property, so the backend decides it here, following what Roslyn emits for
 * C# `virtual`/`override` members).
 */
internal fun IrSimpleFunction.isDotNetVirtual(): Boolean {
    if (parameters.firstOrNull()?.kind != IrParameterKind.DispatchReceiver) return false
    if ((parent as? IrClass)?.isInterface == true) return true
    if (dotNetAnyMethodOrNull() != null) return true
    if (overriddenSymbols.isNotEmpty()) return true
    val ownerModality = (parent as? IrClass)?.modality
    return (modality == Modality.OPEN || modality == Modality.ABSTRACT) &&
            (ownerModality == Modality.OPEN ||
                    ownerModality == Modality.ABSTRACT ||
                    ownerModality == Modality.SEALED)
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
