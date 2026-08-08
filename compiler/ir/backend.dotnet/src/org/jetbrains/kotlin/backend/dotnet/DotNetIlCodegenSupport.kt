package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.dotnet.serialization.DotNetIrMangler
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isGetter
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.types.isNullableString
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.erasedUpperBound
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssembly

/** Whether this is one of the primitive-array classifiers whose scalar element type is supported. */
internal fun IrType.isSupportedDotNetPrimitiveArray(): Boolean = when (classFqName?.asString()) {
    "kotlin.BooleanArray",
    "kotlin.ByteArray",
    "kotlin.ShortArray",
    "kotlin.IntArray",
    "kotlin.LongArray",
    "kotlin.FloatArray",
    "kotlin.DoubleArray",
    "kotlin.CharArray",
        -> true
    else -> false
}

/** Whether this is Kotlin's invariant generic array classifier (`Array<E>`). */
internal fun IrType.isDotNetGenericArray(): Boolean =
    classFqName?.asString() == "kotlin.Array"

/** Whether this is the logical Common `CharSequence` classifier (nullable or non-null). */
internal fun IrType.isDotNetCharSequenceType(): Boolean =
    classFqName == StandardNames.FqNames.charSequence.toSafe()

/** Whether this declaration is the Common `CharSequence` interface. */
internal fun IrClass.isDotNetCharSequenceClass(): Boolean =
    fqNameWhenAvailable == StandardNames.FqNames.charSequence.toSafe()

/** Whether this declaration is Common Kotlin's contravariant `Comparable<T>` interface. */
internal fun IrClass.isDotNetComparableClass(): Boolean =
    fqNameWhenAvailable?.asString() == "kotlin.Comparable"

/** Whether this type is an instantiation of Common Kotlin's `Comparable<T>`. */
internal fun IrType.isDotNetComparableType(): Boolean =
    classFqName?.asString() == "kotlin.Comparable"

/** The recursive Common bound used by `T : Comparable<T>`. */
private fun IrType.isDotNetComparableSelfBound(typeParameter: IrTypeParameter): Boolean {
    val simpleType = this as? IrSimpleType ?: return false
    if (simpleType.isMarkedNullable() || !isDotNetComparableType()) return false
    val argument = simpleType.arguments.singleOrNull() as? IrTypeProjection ?: return false
    return ((argument.type as? IrSimpleType)?.classifier as? IrTypeParameterSymbol)?.owner == typeParameter
}

/**
 * Whether this Common annotation declaration can be the target's single concrete runtime value.
 * CLR custom-attribute projection is a separate, narrower question: Kotlin-only values remain
 * authoritative in KLIB and must not prevent ordinary runtime construction of the declaration.
 */
internal fun IrClass.isSupportedDotNetAnnotationClass(): Boolean =
    isAnnotationClass && !isExpect && primaryConstructor != null

/** The exact invariant element type used by the indexed-loop lowering, or null for projections. */
internal fun IrType.dotNetInvariantArrayElementTypeOrNull(): IrType? {
    if (!isDotNetGenericArray()) return null
    val argument = (this as? IrSimpleType)?.arguments?.singleOrNull() as? IrTypeProjection ?: return null
    return argument.type.takeIf { argument.variance == Variance.INVARIANT }
}

/** Whether this is a bounded output projection such as `Array<out Comparable<*>>`. */
internal fun IrType.isDotNetOutProjectedGenericArray(): Boolean {
    if (!isDotNetGenericArray()) return false
    val argument = (this as? IrSimpleType)?.arguments?.singleOrNull() as? IrTypeProjection ?: return false
    return argument.variance == Variance.OUT_VARIANCE
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
    val isErasedCallableCall = isDotNetKCallableInvocation()
    val isErasedPropertyAccess = isDotNetErasedPropertyAccess()
    val ilReturnType = if (
        isErasedCallableInvoke || isErasedCallableCall ||
        (isErasedPropertyAccess && name.asString() == "get")
    ) {
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
    if (isDotNetKCallableInvocation()) {
        return when (name.asString()) {
            "call" -> "Call"
            "callBy" -> "CallBy"
            else -> error("Internal .NET backend error: unknown KCallable invocation '$name'")
        }
    }
    if (isDotNetErasedPropertyAccess()) return if (name.asString() == "get") "Get" else "Set"
    dotNetAnyMethodOrNull()?.let { return it.clrName }
    val property = correspondingPropertySymbol?.owner ?: return name.asString()
    val prefix = if (isGetter) "get_" else "set_"
    return prefix + property.name.asString()
}

/**
 * The stable Kotlin ABI name used when distinct logical exception types share one CLR signature
 * carrier. The suffix is derived from Kotlin's owner-independent callable signature. An override
 * derives it from the selected logical slot declaration, so substituting `T = Throwable` does not
 * detach an override from an unmangled `f(T)` base slot. Unrelated overloads remain distinct. The
 * rule is applied whenever a non-dispatch parameter contains a classified exception category,
 * not only after a collision is observed: adding a later overload must not rename an already
 * published method.
 *
 * Nested type arguments participate because `Box<Throwable>` and `Box<Exception>` can erase to
 * the same closed CLR signature just as direct parameters do. C#-facing source names belong to
 * explicit export facades when this non-injective representation requires distinct projections.
 */
internal fun IrSimpleFunction.dotNetExceptionCarrierMethodNameOrNull(
    baseMethodName: String = dotNetIlMethodName(),
): String? {
    fun IrType.containsSharedExceptionCarrier(): Boolean {
        if (DotNetMappedExceptions.hasSharedSignatureCarrier(classFqName)) return true
        return (this as? IrSimpleType)?.arguments.orEmpty().any { argument ->
            (argument as? IrTypeProjection)?.type?.containsSharedExceptionCarrier() == true
        }
    }

    fun IrSimpleFunction.logicalExceptionSignatureOrNull(): String? {
        val requiresLogicalName = parameters.any { parameter ->
            parameter.kind != IrParameterKind.DispatchReceiver &&
                    parameter.type.containsSharedExceptionCarrier()
        }
        if (!requiresLogicalName) return null
        return with(DotNetIrMangler) {
            this@logicalExceptionSignatureOrNull.signatureString(compatibleMode = false)
        }
    }

    fun IrSimpleFunction.selectedSlotRoots(): List<IrSimpleFunction> {
        val overridden = overriddenSymbols.map { it.owner }
        if (overridden.isEmpty()) return listOf(this)
        val classSlots = overridden.filter { overriddenMember ->
            (overriddenMember.parent as? IrClass)?.isInterface == false
        }
        val selectedSlots = classSlots.ifEmpty { overridden }
        return selectedSlots.flatMap { it.selectedSlotRoots() }
    }

    val slotSignatures = selectedSlotRoots()
        .map { it.logicalExceptionSignatureOrNull() }
        .distinct()
    val logicalSignature = if (slotSignatures.size == 1) {
        slotSignatures.single()
    } else {
        logicalExceptionSignatureOrNull()
    } ?: return null
    return "${baseMethodName}__KotlinException__${DotNetLibraryAbiCodec.logicalIdentityDigest(logicalSignature)}"
}

/**
 * Extends the stable exception-carrier naming rule to an ordinary generic class whose Kotlin
 * type arguments are intentionally absent from the canonical CLR signature. `Box<Int>` and
 * `Box<String>` both map to the non-generic `Box` interface, so parameter overloads would
 * otherwise collapse. The complete Kotlin signature supplies the stable discriminator.
 *
 * Existing exception-carrier names remain byte-for-byte authoritative. This second suffix is
 * selected only when no classified exception already requires the older ABI spelling.
 */
internal fun IrSimpleFunction.dotNetErasedCarrierMethodNameOrNull(
    isErasedGenericClass: (IrClass) -> Boolean,
    baseMethodName: String = dotNetIlMethodName(),
): String? {
    dotNetExceptionCarrierMethodNameOrNull(baseMethodName)?.let { return it }

    fun IrType.containsErasedGenericClass(): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        val classifier = (simpleType.classifier as? IrClassSymbol)?.owner
        if (classifier?.let(isErasedGenericClass) == true) return true
        return simpleType.arguments.any { argument ->
            (argument as? IrTypeProjection)?.type?.containsErasedGenericClass() == true
        }
    }

    fun IrSimpleFunction.logicalErasedSignatureOrNull(): String? {
        val requiresLogicalName = parameters.any { parameter ->
            parameter.kind != IrParameterKind.DispatchReceiver &&
                    parameter.type.containsErasedGenericClass()
        }
        if (!requiresLogicalName) return null
        return with(DotNetIrMangler) {
            this@logicalErasedSignatureOrNull.signatureString(compatibleMode = false)
        }
    }

    fun IrSimpleFunction.selectedSlotRoots(): List<IrSimpleFunction> {
        val overridden = overriddenSymbols.map { it.owner }
        if (overridden.isEmpty()) return listOf(this)
        val classSlots = overridden.filter { overriddenMember ->
            (overriddenMember.parent as? IrClass)?.isInterface == false
        }
        val selectedSlots = classSlots.ifEmpty { overridden }
        return selectedSlots.flatMap { it.selectedSlotRoots() }
    }

    val slotSignatures = selectedSlotRoots()
        .map { it.logicalErasedSignatureOrNull() }
        .distinct()
    val logicalSignature = if (slotSignatures.size == 1) {
        slotSignatures.single()
    } else {
        logicalErasedSignatureOrNull()
    } ?: return null
    return "${baseMethodName}__KotlinErased__${DotNetLibraryAbiCodec.logicalIdentityDigest(logicalSignature)}"
}

/**
 * The selected physical CLR name, including a bounded Common-stdlib platform name or the stable
 * logical erased-carrier ABI name when representation requires it. An explicit [baseMethodName]
 * is a caller-owned slot/capability spelling and therefore bypasses stdlib top-level name
 * selection.
 */
internal fun IrSimpleFunction.dotNetAbiMethodName(
    baseMethodName: String? = null,
    isErasedGenericClass: (IrClass) -> Boolean = { false },
): String = dotNetAbiMethodNameOrNull(baseMethodName, isErasedGenericClass) ?: dotNetIlMethodName()

/** A non-default ABI spelling, or null when the ordinary Kotlin/CLR method name is sufficient. */
internal fun IrSimpleFunction.dotNetAbiMethodNameOrNull(
    baseMethodName: String? = null,
    isErasedGenericClass: (IrClass) -> Boolean = { false },
): String? {
    val selectedBaseMethodName = baseMethodName
        ?: DotNetStdlibLibrary.implementationPlatformMethodNameOrNull(this)
    val physicalBaseMethodName = selectedBaseMethodName ?: dotNetIlMethodName()
    return dotNetErasedCarrierMethodNameOrNull(isErasedGenericClass, physicalBaseMethodName)
        ?: selectedBaseMethodName
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

/** Whether this is a KCallable invocation whose covariant logical result uses one object CLR slot. */
internal fun IrSimpleFunction.isDotNetKCallableInvocation(): Boolean {
    val methodName = name.asString()
    if (methodName != "call" && methodName != "callBy") return false
    fun IrSimpleFunction.hasKCallableOwner(): Boolean =
        (parent as? IrClass)?.fqNameWhenAvailable?.asString() == "kotlin.reflect.KCallable"
    return hasKCallableOwner() || allOverridden().any(IrSimpleFunction::hasKCallableOwner)
}

/** Whether this is a fixed-arity KProperty get/set member with an erased physical CLR slot. */
internal fun IrSimpleFunction.isDotNetErasedPropertyAccess(): Boolean {
    val methodName = name.asString()
    if (methodName != "get" && methodName != "set") return false
    if ((parent as? IrClass)?.dotNetFixedPropertyArityOrNull() != null) return true
    return allOverridden().any { overridden ->
        (overridden.parent as? IrClass)?.dotNetFixedPropertyArityOrNull() != null
    }
}

/** Whether this function's physical CLR result is object while its Kotlin result stays logical. */
internal fun IrSimpleFunction.isDotNetErasedObjectResult(): Boolean =
    isDotNetErasedCallableInvoke() ||
            isDotNetKCallableInvocation() ||
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
        typeMapper.toDotNetIlParameterType(parameter)
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

/**
 * Common `Comparable<T>` has two truthful BCL views on every supported profile. The CLR's
 * generic interface does not inherit its non-generic predecessor, so generated implementers
 * name both views and the generic-interface bridge lowering fills both slots on the same object.
 */
private fun dotNetComparableInterfaceInfo(
    coreLibrary: DotNetCoreLibraryProfile,
): DotNetGenericInterfaceInfo = DotNetGenericInterfaceInfo(
    canonicalClassInfo = DotNetIlClassInfo(
        ilClassName = "System.IComparable",
        assemblyName = coreLibrary.assemblyName,
    ),
    declaredClassInfo = DotNetIlClassInfo(
        ilClassName = "System.IComparable`1",
        typeParameterVariances = listOf(Variance.IN_VARIANCE),
        assemblyName = coreLibrary.assemblyName,
    ),
    exactClassInfo = null,
)

internal class DotNetIlTypeMapper private constructor(
    private val availableClasses: Map<IrClass, DotNetIlClassInfo>,
    val coreLibrary: DotNetCoreLibraryProfile,
    private val externalDeclarations: DotNetExternalDeclarations,
    private val importedClrDeclarations: DotNetClrImportedDeclarations,
    private val genericInterfaces: Map<IrClass, DotNetGenericInterfaceInfo>,
    private val genericClasses: Map<IrClass, DotNetGenericClassInfo>,
    private val comparableInterfaceInfo: DotNetGenericInterfaceInfo,
    private val genericInterfaceMapping: DotNetGenericInterfaceMapping,
    private val classifierInfoCache: DotNetClassifierInfoCache,
    private val stdlibClasses: MutableMap<IrClass, DotNetIlClassInfo>,
    private val stdlibGenericClasses: MutableMap<IrClass, DotNetGenericClassInfo>,
    private val stdlibClassLinksInProgress: MutableSet<IrClass>,
    val stdlibAssemblyName: String?,
    private val assemblyReferenceSink: (String) -> Unit,
) {
    constructor(
        availableClasses: Map<IrClass, DotNetIlClassInfo>,
        coreLibrary: DotNetCoreLibraryProfile = DEFAULT_EXECUTABLE_CORE_LIBRARY,
        externalDeclarations: DotNetExternalDeclarations = DotNetExternalDeclarations(emptyList()),
        genericInterfaces: Map<IrClass, DotNetGenericInterfaceInfo> = emptyMap(),
        genericClasses: Map<IrClass, DotNetGenericClassInfo> = emptyMap(),
        stdlibAssemblyName: String? = DotNetStdlibLibrary.ASSEMBLY_NAME,
        assemblyReferenceSink: (String) -> Unit = {},
        foreignAssemblyReferenceSink: (DotNetClrClasspathAssembly.WithoutCarrier) -> Unit = {},
    ) : this(
        availableClasses,
        coreLibrary,
        externalDeclarations,
        DotNetClrImportedDeclarations(foreignAssemblyReferenceSink),
        genericInterfaces,
        genericClasses,
        dotNetComparableInterfaceInfo(coreLibrary),
        DotNetGenericInterfaceMapping.CANONICAL,
        DotNetClassifierInfoCache(),
        mutableMapOf(),
        mutableMapOf(),
        mutableSetOf(),
        stdlibAssemblyName,
        assemblyReferenceSink,
    )

    private fun withGenericInterfaceMapping(mapping: DotNetGenericInterfaceMapping): DotNetIlTypeMapper =
        DotNetIlTypeMapper(
            availableClasses,
            coreLibrary,
            externalDeclarations,
            importedClrDeclarations,
            genericInterfaces,
            genericClasses,
            comparableInterfaceInfo,
            mapping,
            classifierInfoCache,
            stdlibClasses,
            stdlibGenericClasses,
            stdlibClassLinksInProgress,
            stdlibAssemblyName,
            assemblyReferenceSink,
        )

    internal fun classifierInfo(irClass: IrClass): DotNetClassifierInfo = classifierInfoCache[irClass]

    private fun IrType.classifierInfoOrNull(): DotNetClassifierInfo? =
        ((this as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.let(classifierInfoCache::get)

    private fun IrType.isDotNetGenericArray(): Boolean =
        classifierInfoOrNull()?.builtinKind == DotNetBuiltinClassifierKind.ARRAY

    private fun IrType.isSupportedDotNetPrimitiveArray(): Boolean = when (classifierInfoOrNull()?.builtinKind) {
        DotNetBuiltinClassifierKind.BOOLEAN_ARRAY,
        DotNetBuiltinClassifierKind.BYTE_ARRAY,
        DotNetBuiltinClassifierKind.SHORT_ARRAY,
        DotNetBuiltinClassifierKind.INT_ARRAY,
        DotNetBuiltinClassifierKind.LONG_ARRAY,
        DotNetBuiltinClassifierKind.FLOAT_ARRAY,
        DotNetBuiltinClassifierKind.DOUBLE_ARRAY,
        DotNetBuiltinClassifierKind.CHAR_ARRAY,
            -> true
        else -> false
    }

    private fun IrType.isDotNetCharSequenceType(): Boolean =
        classifierInfoOrNull()?.isCharSequence == true

    /**
     * Direct String identity is a cached classifier fact. A type parameter keeps the existing
     * Common-bound rule, but its direct bounds use the same cache instead of signature predicates.
     */
    private fun IrType.isDotNetStringType(classifierInfo: DotNetClassifierInfo?): Boolean {
        if (classifierInfo?.builtinKind == DotNetBuiltinClassifierKind.STRING) return true
        val typeParameter = ((this as? IrSimpleType)?.classifier as? IrTypeParameterSymbol)?.owner ?: return false
        return typeParameter.superTypes.any { bound ->
            bound.classifierInfoOrNull()?.builtinKind == DotNetBuiltinClassifierKind.STRING
        }
    }

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

    fun isErasedGenericInterface(irClass: IrClass): Boolean =
        irClass.isInterface && (genericInterfaces.containsKey(irClass) ||
                DotNetRuntimeTypes.hasBuiltInGenericInterfaceMapping(irClass, classifierInfo(irClass)) ||
                externalDeclarations.hasGenericInterface(irClass))

    fun isErasedGenericClass(irClass: IrClass): Boolean =
        genericClasses.containsKey(irClass) ||
                DotNetRuntimeTypes.erasedGenericClassInfoFor(irClass, classifierInfo(irClass)) != null ||
                stdlibGenericClassInfoOrNull(irClass) != null ||
                externalDeclarations.hasGenericClass(irClass)

    fun isErasedGenericClassType(type: IrType): Boolean {
        val simpleType = type as? IrSimpleType ?: return false
        val irClass = (simpleType.classifier as? IrClassSymbol)?.owner ?: return false
        return isErasedGenericClass(irClass)
    }

    /**
     * Whether a declaration from the module currently being emitted survived its codegen gate.
     *
     * This is intentionally distinct from [classInfoOrNull], which may reconstruct physical
     * information for resolution-only stdlib declarations and external libraries. A local class
     * omitted by the live emission set must never be resurrected through those fallback paths.
     */
    fun isLocallyEmittableClass(irClass: IrClass): Boolean = irClass in availableClasses

    /**
     * Whether a `System.Array` carrier still has a source-legal element-write contract.
     * `Array<T>` on an erased owner does; `Array<*>` does not. Both share one physical IL type,
     * so authoritative IR retains this distinction.
     */
    fun permitsErasedGenericArrayElementWrite(type: IrType): Boolean {
        val simpleType = type as? IrSimpleType ?: return false
        val projection = simpleType.arguments.singleOrNull() as? IrTypeProjection ?: return false
        return projection.variance == Variance.INVARIANT &&
                type.referencesErasedOwnerParameterForCurrentView()
    }

    fun genericClassInfoOrNull(irClass: IrClass): DotNetGenericClassInfo? =
        (genericClasses[irClass]
            ?: DotNetRuntimeTypes.erasedGenericClassInfoFor(irClass, classifierInfo(irClass))
            // A loaded library's recorded physical ABI is authoritative for owner paths, method
            // names, and graph links. The stdlib reconstruction exists only for same-module
            // bootstrap sources, where no external physical record can exist yet.
            ?: externalDeclarations.genericClassInfoOrNull(irClass, this)
            ?: stdlibGenericClassInfoOrNull(irClass))
            ?.also(::recordAssemblyReferences)

    /** Same-module bootstrap lookup for one erased stdlib generic-class owner. */
    private fun stdlibGenericClassInfoOrNull(irClass: IrClass): DotNetGenericClassInfo? {
        stdlibGenericClasses[irClass]?.let { return it }
        val info = DotNetStdlibLibrary.publicGenericImplementationClassInfoOrNull(irClass, stdlibAssemblyName) ?: return null
        stdlibGenericClasses[irClass] = info
        if (stdlibClassLinksInProgress.add(irClass)) {
            try {
                val baseType = irClass.dotNetBaseSuperTypeOrNull()
                info.classInfo.baseType = baseType?.let(::toDotNetIlValueType)
                info.classInfo.interfaces = irClass.dotNetDirectInterfaceTypes()
                    .mapNotNull(::toDotNetIlImplementedInterfaceType)
                    .distinct()
            } finally {
                stdlibClassLinksInProgress.remove(irClass)
            }
        }
        return info
    }

    /** Same-module or fallback class graph for a public non-generic stdlib implementation. */
    private fun stdlibClassInfoOrNull(irClass: IrClass): DotNetIlClassInfo? {
        stdlibClasses[irClass]?.let { return it }
        val info = DotNetStdlibLibrary.publicImplementationClassInfoOrNull(irClass, stdlibAssemblyName) ?: return null
        stdlibClasses[irClass] = info
        if (stdlibClassLinksInProgress.add(irClass)) {
            try {
                info.baseType = irClass.dotNetBaseSuperTypeOrNull()?.let(::toDotNetIlValueType)
                info.interfaces = irClass.dotNetDirectInterfaceTypes()
                    .mapNotNull(::toDotNetIlImplementedInterfaceType)
                    .distinct()
            } finally {
                stdlibClassLinksInProgress.remove(irClass)
            }
        }
        return info
    }

    fun isErasedGenericInterfaceType(type: IrType): Boolean {
        val simpleType = type as? IrSimpleType ?: return false
        val irClass = (simpleType.classifier as? IrClassSymbol)?.owner ?: return false
        return isErasedGenericInterface(irClass)
    }

    fun genericInterfaceMemberView(
        member: IrSimpleFunction,
        interfaceClass: IrClass,
    ): DotNetGenericInterfaceMemberView =
        member.dotNetGenericInterfaceMemberView(interfaceClass, ::isErasedGenericInterface)

    fun genericInterfaceMemberViews(
        member: IrSimpleFunction,
        interfaceClass: IrClass,
    ): List<DotNetGenericInterfaceMemberView> =
        member.dotNetGenericInterfaceMemberViews(interfaceClass, ::isErasedGenericInterface)

    fun isClrLegalDeclaredGenericInterfaceSupertype(type: IrType, owner: IrClass): Boolean =
        type.isDotNetClrLegalDeclaredSupertype(owner, ::isErasedGenericInterface)

    fun genericInterfaceInfoOrNull(irClass: IrClass): DotNetGenericInterfaceInfo? =
        if (!irClass.isInterface) null else (genericInterfaces[irClass]
            ?: comparableInterfaceInfo.takeIf { classifierInfo(irClass).isComparable }
            ?: DotNetRuntimeTypes.genericInterfaceInfoFor(irClass, classifierInfo(irClass))
            ?: run {
                val canonical = externalDeclarations.classInfoOrNull(irClass, canonicalGenericInterfaceView())
                    ?: return null
                DotNetGenericInterfaceInfo(canonical)
            }).also(::recordAssemblyReferences)

    fun externalObjectInstanceOwnerInfoOrNull(field: IrField): DotNetIlClassInfo? {
        if (field.origin != IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE) return null
        val singleton = field.type.classOrNull?.owner ?: return null
        val binding = externalDeclarations.objectInstanceOrNull(singleton) ?: return null
        return externalDeclarations.objectInstanceOwnerInfo(binding).also(::recordAssemblyReference)
    }

    fun genericInterfaceTypedMethodName(member: IrSimpleFunction): String =
        member.dotNetAbiMethodName(
            if ((member.parent as? IrClass)?.let(::classifierInfo)?.isComparable == true) {
                "CompareTo"
            } else {
                DotNetRuntimeTypes.genericInterfaceTypedMethodNameOrNull(member) ?: member.dotNetIlMethodName()
            },
            ::isErasedGenericClass,
        )

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
        val declaredClassInfo = info.declaredClassInfo ?: return null
        if (simpleType.arguments.size != declaredClassInfo.typeParameterCount) return null
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
        val classifierInfo = classifierInfo(irClass)
        val runtimeGenericInfo = DotNetRuntimeTypes.genericInterfaceInfoFor(irClass, classifierInfo)
        val mappedComparableInfo = comparableInterfaceInfo.takeIf { classifierInfo.isComparable }
        val genericClassInfo = genericClassInfoOrNull(irClass)
        return (genericClassInfo?.classInfo ?: when (genericInterfaceMapping.physicalView) {
            DotNetGenericInterfaceView.CANONICAL ->
                genericInterfaces[irClass]?.canonicalClassInfo
                    ?: mappedComparableInfo?.canonicalClassInfo
                    ?: runtimeGenericInfo?.canonicalClassInfo
                    ?: availableClasses[irClass]
            DotNetGenericInterfaceView.DECLARED ->
                genericInterfaces[irClass]?.declaredClassInfo
                    ?: mappedComparableInfo?.declaredClassInfo
                    ?: runtimeGenericInfo?.declaredClassInfo
                    ?: externalDeclarations.declaredClassInfoOrNull(irClass)
                    ?: availableClasses[irClass]
            DotNetGenericInterfaceView.EXACT ->
                genericInterfaces[irClass]?.mostSpecificCapabilityClassInfo
                    ?: mappedComparableInfo?.mostSpecificCapabilityClassInfo
                    ?: runtimeGenericInfo?.mostSpecificCapabilityClassInfo
                    ?: externalDeclarations.exactClassInfoOrNull(irClass)
                    ?: externalDeclarations.declaredClassInfoOrNull(irClass)
                    ?: availableClasses[irClass]
        }
            ?: DotNetRuntimeTypes.classInfoFor(irClass, classifierInfo)
            ?: externalDeclarations.classInfoOrNull(irClass, this)
            ?: stdlibClassInfoOrNull(irClass)
            ?: importedClrDeclarations.classInfoOrNull(irClass)).also { classInfo ->
            classInfo?.let(::recordAssemblyReference)
        }
    }

    fun referencedFunctionInfoOrNull(function: IrSimpleFunction): DotNetIlFunctionInfo? {
        val localStdlibFunction = {
            DotNetStdlibLibrary.implementationFunctionInfoOrNull(function, this, stdlibAssemblyName)
        }
        val libraryFunction = if (stdlibAssemblyName == null) {
            localStdlibFunction() ?: externalDeclarations.functionInfoOrNull(function, this)
        } else {
            externalDeclarations.functionInfoOrNull(function, this) ?: localStdlibFunction()
        }
        return (DotNetRuntimeTypes.enumFunctionInfoOrNull(function, this)
            ?: DotNetRuntimeTypes.reflectionFunctionInfoOrNull(function, this)
            ?: comparableFunctionInfoOrNull(function)
            ?: DotNetRuntimeTypes.genericInterfaceFunctionInfoOrNull(function, this)
            ?: libraryFunction
            ?: importedClrDeclarations.functionInfoOrNull(function)).also { functionInfo ->
            functionInfo?.owner?.let(::recordAssemblyReference)
        }
    }

    private fun comparableFunctionInfoOrNull(function: IrSimpleFunction): DotNetIlFunctionInfo? {
        val owner = function.parent as? IrClass ?: return null
        if (!classifierInfo(owner).isComparable || function.name.asString() != "compareTo") return null
        return DotNetIlFunctionInfo(
            owner = comparableInterfaceInfo.canonicalClassInfo,
            signature = function.dotNetSignature(canonicalGenericInterfaceSignatureView()),
            physicalMethodName = "CompareTo",
        )
    }

    /** Maps [type] in return position; CLR `void` is the return encoding of Kotlin `Unit`. */
    fun toDotNetIlReturnType(type: IrType): DotNetIlReturnType? {
        val simpleType = type as? IrSimpleType
        if (
            simpleType?.isMarkedNullable() == false &&
            simpleType.classifierInfoOrNull()?.builtinKind == DotNetBuiltinClassifierKind.UNIT
        ) {
            return DotNetIlReturnType.Void
        }
        return DotNetIlReturnType.Value(toDotNetIlValueType(type) ?: return null)
    }

    /**
     * Maps one logical Kotlin parameter to its authoritative physical CLR parameter type.
     *
     * A reference `vararg E` is written in Kotlin IR as the source-level output projection
     * `Array<out E>`, but [org.jetbrains.kotlin.backend.dotnet.lower.DotNetVarargLowering] gives
     * the declaration and every call an invariant exact vector before CIL emission. Physical
     * library metadata is also collected from pre-lowering declarations, so signature mapping
     * must recognize the vararg marker itself instead of depending on that mutation having
     * happened already. This keeps a separately compiled call to `vararg T` on the same `T[]`
     * signature the producer emits, while an ordinary `Array<out T>` parameter continues to use
     * its read-only `System.Array` view.
     */
    fun toDotNetIlParameterType(parameter: IrValueParameter): DotNetIlValueType? {
        val varargElementType = parameter.varargElementType
        if (varargElementType == null || !parameter.type.isDotNetGenericArray()) {
            return toDotNetIlValueType(parameter.type)
        }
        if (varargElementType.isOpenNullableTypeParameter()) {
            dotNetUnsupported(
                "vararg parameter '${parameter.name.asString()}' contains open nullable type parameter " +
                        "'${varargElementType.render()}'; the boxed-or-null carrier is supported only " +
                        "as a direct value slot until nested invariant-carrier adapters are defined"
            )
        }
        val elementType = toDotNetIlValueType(varargElementType) ?: return null
        return DotNetIlValueType.GenericArray(elementType)
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
     * ([DotNetIlValueType.Object]). Generic `T?` (a nullable type-parameter type) uses the
     * declaration-stable boxed-or-null `object` carrier selected by
     * `adr-hybrid-generic-nullability-and-covariant-returns.md`. This local erasure does not alter
     * concrete nullable primitives or non-null reified `T` slots.
     */
    fun toDotNetIlValueType(type: IrType): DotNetIlValueType? =
        mapDotNetIlValueType(type).also { valueType ->
            valueType?.let(::recordAssemblyReferences)
        }

    /**
     * Maps a declared interface supertype to the CLR interface a generated type implements.
     * Most interfaces use their ordinary value mapping. `CharSequence` is the deliberate
     * exception: values use the classified object carrier so `System.String` remains admissible,
     * while an authored implementation must name the runtime capability in its InterfaceImpl row.
     */
    fun toDotNetIlImplementedInterfaceType(type: IrSimpleType): DotNetIlValueType? {
        val irClass = (type.classifier as? IrClassSymbol)?.owner ?: return null
        return if (classifierInfo(irClass).isCharSequence) {
            DotNetRuntimeTypes.charSequenceImplementationType.also(::recordAssemblyReferences)
        } else {
            toDotNetIlValueType(type)
        }
    }

    private fun mapDotNetIlValueType(type: IrType): DotNetIlValueType? {
        val simpleType = type as? IrSimpleType
        val topClassifierInfo = simpleType
            ?.classifier
            ?.let { it as? IrClassSymbol }
            ?.owner
            ?.let(::classifierInfo)
        val isMarkedNullable = simpleType?.isMarkedNullable() == true
        // `void` is legal only in a CLR method's return slot. Every other occurrence of
        // Kotlin `Unit` is the ordinary singleton reference value, matching the JVM's
        // `kotlin.Unit` parameter/field representation and the expression codegen contract.
        if (!isMarkedNullable && topClassifierInfo?.builtinKind == DotNetBuiltinClassifierKind.UNIT) {
            return DotNetRuntimeTypes.unitType
        }
        DotNetRuntimeTypes.mapCompilerRuntimeType(type, topClassifierInfo)?.let { return it }
        if (type.referencesErasedOwnerParameterForCurrentView()) {
            if (type.isDotNetGenericArray()) {
                // An erased class owner cannot choose one CLR vector element type for Array<T>.
                // Preserve the actual vector object through the classified System.Array carrier;
                // indexed operations already recover Kotlin reads/writes through its runtime path.
                return DotNetIlValueType.ErasedGenericArray(coreLibrary.reference)
            }
            val simpleType = type as? IrSimpleType
            val directParameterOwner = (simpleType?.classifier as? IrTypeParameterSymbol)
                ?.owner
                ?.parent as? IrClass
            val isDirectErasedClassParameter =
                directParameterOwner?.let(::isErasedGenericClass) == true
            val topClass = (simpleType?.classifier as? IrClassSymbol)?.owner
            if (!isDirectErasedClassParameter &&
                (topClass == null || (!isErasedGenericInterface(topClass) && !isErasedGenericClass(topClass)))
            ) {
                // A reified carrier such as Holder<T> or T? has no single closed CLR
                // instantiation once T belongs to a canonical non-generic interface. Object is
                // the only identity-preserving universal carrier at this boundary.
                return DotNetIlValueType.Object
            }
        }
        if (!isMarkedNullable) {
            when (topClassifierInfo?.builtinKind) {
                DotNetBuiltinClassifierKind.BOOLEAN -> return DotNetIlValueType.Boolean
                DotNetBuiltinClassifierKind.BYTE -> return DotNetIlValueType.Int8
                DotNetBuiltinClassifierKind.SHORT -> return DotNetIlValueType.Int16
                DotNetBuiltinClassifierKind.INT -> return DotNetIlValueType.Int32
                DotNetBuiltinClassifierKind.LONG -> return DotNetIlValueType.Int64
                DotNetBuiltinClassifierKind.FLOAT -> return DotNetIlValueType.Float32
                DotNetBuiltinClassifierKind.DOUBLE -> return DotNetIlValueType.Float64
                DotNetBuiltinClassifierKind.CHAR -> return DotNetIlValueType.Char
                else -> {}
            }
        }
        return when {
            // An outer nullable occurrence of every open parameter uses one declaration-stable
            // boxed-or-null carrier. This must precede the String-bound shortcut: T : String
            // narrows non-null T to string, but T? still has the uniform open-nullable ABI.
            type.isOpenNullableTypeParameter() -> DotNetIlValueType.Object
            type.isDotNetStringType(topClassifierInfo) -> DotNetIlValueType.String
            // The CLR has no root that contains exactly Kotlin's six numeric primitive boxes.
            // Number therefore uses the same classified-object carrier already selected by
            // KClass/RTTI; exact scalar values remain unmodified and keep their own box identity.
            topClassifierInfo?.builtinKind == DotNetBuiltinClassifierKind.NUMBER -> DotNetIlValueType.Object
            // System.String is sealed and cannot implement a Kotlin-owned interface. As on JS,
            // the logical interface therefore uses an object carrier plus runtime classification;
            // this arm is only the direct CharSequence classifier, never an arbitrary subtype or
            // a type parameter bounded by it (those retain their own physical token).
            type.isDotNetCharSequenceType() -> DotNetIlValueType.Object
            topClassifierInfo?.builtinKind == DotNetBuiltinClassifierKind.ANNOTATION ->
                DotNetIlValueType.MappedClass("${coreLibrary.reference}System.Attribute")
            topClassifierInfo?.builtinKind == DotNetBuiltinClassifierKind.ANY -> DotNetIlValueType.Object
            type.isSupportedDotNetPrimitiveArray() -> toPrimitiveArrayType(type)
            type.isDotNetGenericArray() -> toGenericArrayTypeOrNull(type)
            // Both nullable and non-null bottom types were already mapped above to the same
            // runtime-owned reference carrier, mirroring the JVM's java.lang.Void mapping.
            // Kotlin nullability metadata retains the distinction; codegen performs the legal
            // Nothing? -> arbitrary nullable-type coercion without claiming CLR assignability.
            else -> toNullablePrimitiveTypeOrNull(type, topClassifierInfo)
                ?: toMappedExceptionTypeOrNull(topClassifierInfo)
                ?: toUserClassTypeOrNull(type)
                ?: toTypeParameterTypeOrNull(type)
        }
    }

    /** Maps a specialized Kotlin array to its canonical Kotlin.Runtime wrapper reference. */
    private fun toPrimitiveArrayType(type: IrType): DotNetIlValueType.PrimitiveArray {
        val entry = DotNetPrimitiveArrays.entry(type.classifierInfoOrNull()?.fqName)
            ?: error("Internal .NET backend error: unsupported primitive-array classifier ${type.render()}")
        return DotNetIlValueType.PrimitiveArray(entry.elementType)
    }

    /**
     * Maps Kotlin `Array<E>` to a CLR vector while preserving it as the distinct
     * [DotNetIlValueType.GenericArray] structural kind. Concrete primitive elements are legal and
     * retain the natural CLR vector (`Array<Int>` -> `int32[]`) because specialized primitive
     * arrays now have distinct Kotlin.Runtime wrapper types. An OPEN type parameter remains valid
     * (`!n[]`/`!!n[]`) and substitutes reified CLR element types. A Kotlin `out` projection uses
     * the classified [DotNetIlValueType.ErasedGenericArray] `System.Array` view because CLR
     * vector covariance cannot represent value-element or arbitrary method-generic widenings;
     * KLIB retains its stronger bounded read type. A star projection uses the same physical view
     * with the fixed logical `Any?` read result. `in` retains a typed write contract and remains
     * rejected.
     */
    private fun toGenericArrayTypeOrNull(type: IrType): DotNetIlValueType? {
        val simpleType = type as? IrSimpleType
            ?: dotNetUnsupported("generic array type ${type.render()} has an unsupported shape")
        val argument = simpleType.arguments.singleOrNull()
            ?: dotNetUnsupported("generic array type ${type.render()} must have exactly one element type")
        val projection = argument as? IrTypeProjection
            ?: return DotNetIlValueType.ErasedGenericArray(coreLibrary.reference)
        if (projection.variance == Variance.IN_VARIANCE) {
            dotNetUnsupported(
                "generic array type ${type.render()} has an input projection; " +
                        "a CLR vector cannot represent its read-as-Any/write-as-element contract"
            )
        }
        val elementIrType = projection.type
        if (elementIrType.isOpenNullableTypeParameter()) {
            dotNetUnsupported(
                "generic array type ${type.render()} contains open nullable type parameter " +
                        "'${elementIrType.render()}'; the boxed-or-null carrier is supported only " +
                        "as a direct value slot until nested invariant-carrier adapters are defined"
            )
        }
        if (projection.variance == Variance.OUT_VARIANCE) {
            return DotNetIlValueType.ErasedGenericArray(coreLibrary.reference)
        }
        val elementType = toDotNetIlValueType(elementIrType) ?: return null
        return DotNetIlValueType.GenericArray(elementType)
    }

    /**
     * The [NullableValue][DotNetIlValueType.NullableValue] mapping of a concrete nullable
     * primitive type, or null when [type] is not one. Matches by classifier FqName plus the
     * nullability marker — the positive-space complement of the not-null `isInt()` family used
     * above (`Int?` fails `isInt()` because `isNotNullClassType` requires `!isMarkedNullable`).
     */
    private fun toNullablePrimitiveTypeOrNull(
        type: IrType,
        classifierInfo: DotNetClassifierInfo?,
    ): DotNetIlValueType.NullableValue? {
        if (type !is IrSimpleType || !type.isMarkedNullable()) return null
        val elementType = when (classifierInfo?.builtinKind) {
            DotNetBuiltinClassifierKind.BOOLEAN -> DotNetIlValueType.Boolean
            DotNetBuiltinClassifierKind.BYTE -> DotNetIlValueType.Int8
            DotNetBuiltinClassifierKind.SHORT -> DotNetIlValueType.Int16
            DotNetBuiltinClassifierKind.INT -> DotNetIlValueType.Int32
            DotNetBuiltinClassifierKind.LONG -> DotNetIlValueType.Int64
            DotNetBuiltinClassifierKind.FLOAT -> DotNetIlValueType.Float32
            DotNetBuiltinClassifierKind.DOUBLE -> DotNetIlValueType.Float64
            DotNetBuiltinClassifierKind.CHAR -> DotNetIlValueType.Char
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
    private fun toMappedExceptionTypeOrNull(
        classifierInfo: DotNetClassifierInfo?,
    ): DotNetIlValueType.MappedClass? =
        when (val entry = classifierInfo?.fqName?.let(DotNetMappedExceptions.entries::get)) {
            is DotNetMappedExceptions.Entry.Mapped ->
                DotNetIlValueType.MappedClass(entry.carrierTypeRef(coreLibrary.reference))
            is DotNetMappedExceptions.Entry.Rejected -> dotNetUnsupported(entry.reason)
            null -> null
        }

    /**
     * A class-like type maps while its user class is available or when it is a registered runtime
     * classifier; `C?` maps to the same
     * `class 'C'` as `C` (the classifier lookup ignores nullability, like `string`). A
     * Kotlin-owned generic class takes the erased-owner arm below. An imported generic class or
     * non-split CLR interface maps to a full
     * [instantiation][DotNetIlValueType.GenericInstance] (a real CLR reified-generic shape), with
     * each type argument mapped recursively through this same mapper. An argument mentioning an
     * evicted class therefore fails the whole instantiation (the normal fixpoint-eviction rule).
     * Use-site variance projections (`Box<out T>`) and star projections remain unsupported for
     * those foreign reified shapes because ECMA-335 has no use-site variance. Kotlin-owned
     * generic interfaces take the earlier erased-interface arm instead: their runtime storage identity
     * is non-generic, so projections and stars do not alter its CLR type and remain Kotlin
     * metadata rather than CLR generic conversions. Declaration-site variance is used only by
     * the optional declared interface capability.
     */
    private fun toUserClassTypeOrNull(type: IrType): DotNetIlValueType? {
        if (type !is IrSimpleType) return null
        val irClass = (type.classifier as? IrClassSymbol)?.owner ?: return null
        val genericClassInfo = genericClassInfoOrNull(irClass)
        if (genericClassInfo != null) return DotNetIlValueType.UserClass(genericClassInfo.classInfo)
        if (
            isErasedGenericInterface(irClass) &&
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
            if (projection.type.isOpenNullableTypeParameter()) {
                dotNetUnsupported(
                    "generic type '${type.render()}' contains open nullable type parameter " +
                            "'${projection.type.render()}'; the boxed-or-null carrier is supported " +
                            "only as a direct value slot until nested invariant-carrier adapters are defined"
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
     * positional and open. A nullable type-parameter occurrence uses `object`: one frozen
     * boxed-or-null carrier is required because a CLR signature cannot alternate between
     * `Nullable<T>` and a reference after substitution. Constraints outside
     * [dotNetConstraintTypes] remain rejected instead of erased.
     * A non-null `T` whose bound is `String`/`String?` never reaches this arm: the string-concat
     * lowering's receiver mapping ([isDotNetStringType]) maps it to IL `string`. Its nullable
     * occurrence reaches the boxed-or-null branch before that shortcut.
     */
    private fun toTypeParameterTypeOrNull(type: IrType): DotNetIlValueType? {
        if (type !is IrSimpleType) return null
        val typeParameter = (type.classifier as? IrTypeParameterSymbol)?.owner ?: return null
        if (type.isMarkedNullable()) {
            return DotNetIlValueType.Object
        }
        val parentGenericInterface = (typeParameter.parent as? IrClass)
            ?.takeIf(::isErasedGenericInterface)
        val parentGenericClass = (typeParameter.parent as? IrClass)
            ?.takeIf(::isErasedGenericClass)
        if (
            genericInterfaceMapping.physicalView == DotNetGenericInterfaceView.CANONICAL &&
            parentGenericInterface != null
        ) {
            return DotNetIlValueType.Object
        }
        if (parentGenericClass != null) {
            val erasedUpperBound = type.erasedUpperBound
            return if (erasedUpperBound == parentGenericClass || erasedUpperBound.defaultType == type) {
                DotNetIlValueType.Object
            } else {
                toDotNetIlValueType(erasedUpperBound.defaultType) ?: DotNetIlValueType.Object
            }
        }
        val constraintTypes = typeParameter.dotNetConstraintTypes(this, forMetadata = false)
        return DotNetIlValueType.TypeParameter(
            typeParameter.index,
            isMethodParameter = typeParameter.parent is IrFunction,
            upperBounds = constraintTypes.flatMap { constraint ->
                when (constraint) {
                    is DotNetIlValueType.UserClass -> listOf(constraint)
                    // A CLR `R : T` constraint is represented directly in metadata. For the
                    // backend's structural member model, R also inherits T's effective concrete
                    // class/interface bounds; the positional T token itself is not a class owner.
                    is DotNetIlValueType.TypeParameter -> constraint.upperBounds
                    else -> emptyList()
                }
            },
            relativeUpperBounds = constraintTypes
                .filterIsInstance<DotNetIlValueType.TypeParameter>()
                .flatMap { constraint -> listOf(constraint.identity) + constraint.relativeUpperBounds }
                .toSet(),
        )
    }

    private fun IrType.isOpenNullableTypeParameter(): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        return simpleType.isMarkedNullable() && simpleType.classifier is IrTypeParameterSymbol
    }

    private fun IrType.referencesErasedOwnerParameterForCurrentView(): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        val parameterOwner = (simpleType.classifier as? IrTypeParameterSymbol)?.owner?.parent as? IrClass
        if (parameterOwner != null) {
            if (isErasedGenericClass(parameterOwner)) return true
            if (
                genericInterfaceMapping.physicalView == DotNetGenericInterfaceView.CANONICAL &&
                isErasedGenericInterface(parameterOwner)
            ) {
                return true
            }
        }
        return simpleType.arguments.any { argument ->
            (argument as? IrTypeProjection)?.type?.referencesErasedOwnerParameterForCurrentView() == true
        }
    }

    private fun recordAssemblyReference(classInfo: DotNetIlClassInfo) {
        check(stdlibAssemblyName != null || classInfo.assemblyName != DotNetStdlibLibrary.ASSEMBLY_NAME) {
            "Internal .NET backend error: local Kotlin.Stdlib emission resolved " +
                    "'${classInfo.ilClassName}' through an external Kotlin.Stdlib assembly scope"
        }
        classInfo.assemblyName?.let(assemblyReferenceSink)
    }

    fun recordAssemblyReference(assemblyName: String) {
        check(stdlibAssemblyName != null || assemblyName != DotNetStdlibLibrary.ASSEMBLY_NAME) {
            "Internal .NET backend error: local Kotlin.Stdlib emission requested an external Kotlin.Stdlib scope"
        }
        assemblyReferenceSink(assemblyName)
    }

    private fun recordAssemblyReferences(info: DotNetGenericInterfaceInfo) {
        recordAssemblyReference(info.canonicalClassInfo)
        info.declaredClassInfo?.let(::recordAssemblyReference)
        info.exactClassInfo?.let(::recordAssemblyReference)
    }

    private fun recordAssemblyReferences(info: DotNetGenericClassInfo) {
        recordAssemblyReference(info.classInfo)
    }

    private fun recordAssemblyReferences(type: DotNetIlValueType) {
        when (type) {
            is DotNetIlValueType.UserClass -> recordAssemblyReference(type.classInfo)
            is DotNetIlValueType.GenericInstance -> {
                recordAssemblyReference(type.classInfo)
                type.arguments.forEach(::recordAssemblyReferences)
            }
            is DotNetIlValueType.GenericArray -> recordAssemblyReferences(type.elementType)
            is DotNetIlValueType.ErasedGenericArray -> Unit
            is DotNetIlValueType.PrimitiveArray -> recordAssemblyReferences(type.elementType)
            is DotNetIlValueType.NullableValue -> recordAssemblyReferences(type.elementType)
            is DotNetIlValueType.TypeParameter -> type.upperBounds.forEach(::recordAssemblyReferences)
            DotNetIlValueType.Boolean,
            DotNetIlValueType.Char,
            DotNetIlValueType.Float32,
            DotNetIlValueType.Float64,
            DotNetIlValueType.Int8,
            DotNetIlValueType.Int16,
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
 * `Any?` is the unconstrained Kotlin default and contributes no metadata. An explicit `Any`
 * bound is also omitted physically: Kotlin admits both non-null references and value types for
 * `T : Any`, while CLR's `class` and `valuetype` flags each reject half of that set. KLIB retains
 * the logical non-null bound; future Roslyn `notnull` metadata is an additive warning view. The
 * historical function-only `String` bound keeps its pre-stage-1 slot erosion and is likewise omitted here.
 * A logical `CharSequence` bound is also omitted: constraining the CLR parameter to the runtime
 * capability interface would reject the legal Kotlin substitution `T = String`, because sealed
 * `System.String` cannot implement that interface. KLIB retains the authoritative bound and
 * member operations classify the boxed/widened value at runtime.
 * A non-null bound on another type parameter is normally retained as its positional `!n`/`!!n`
 * TypeSpec. A nullable type-parameter bound (`X : Y?`) is omitted physically: spelling it as
 * `X : Y` would reject legal nullable value-type substitutions such as `X = Int?`, `Y = Int`.
 * KLIB retains the exact nullable relationship and Kotlin callers remain checked by FIR.
 * The exceptions are method bounds which depend on a type parameter of a split Kotlin generic
 * interface or of a declaration-erased Kotlin class. Such a relationship remains part of the
 * logical Kotlin signature, but the erased physical owner has no CLR `T` token with which to
 * encode it. It is therefore omitted from that owner's executable metadata while the structural
 * codegen model retains the available erased carrier. A CLR variant interface containing `R : T`
 * load-fails when `T` is variant, while retaining the constraint only on an invariant exact DIM
 * rejects valid Kotlin calls through a widened declaration-site-variance view. Portable closed
 * value-type views cannot express the substituted relationship either. The exact view still
 * keeps the strongly typed parameters and result; only the incompatible physical constraint is
 * weakened. A bound whose classifier is a declaration-erased Kotlin class or interface retains
 * that one non-generic physical owner as a necessarily true CLR constraint; KLIB keeps its exact
 * type arguments and recursive relationship. This is weaker but truthful (`E : Enum<E>` becomes
 * physical `E : Enum`, and `C : Iterable<T>` becomes `C : Iterable`) and never reintroduces a
 * closed CLR generic identity. A future C# export facade may publish a convenience constraint,
 * but that facade must not become Kotlin's virtual dispatch slot. Other constraints remain direct
 * and non-null; accepting a mapped or external type without a complete member model would publish
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
        .filterNot {
            it.isNullableAny() || it.isAny() || it.isString() || it.isNullableString() ||
                    it.isDotNetCharSequenceType()
        }
        .mapIndexedNotNull { index, bound ->
            val simpleBound = bound as? IrSimpleType
                ?: dotNetUnsupported(
                    "type parameter '${name.asString()}' has an unsupported constraint ${bound.render()}; " +
                            "constraints must be non-null type parameters, non-generic module-local " +
                            "classes/interfaces, or declaration-erased Kotlin classifiers"
                )
            val isComparableSelfBound = bound.isDotNetComparableSelfBound(this)
            val boundClass = (simpleBound.classifier as? IrClassSymbol)?.owner
            val erasedGenericClassBound = boundClass?.let(typeMapper::isErasedGenericClass) == true
            val erasedGenericInterfaceBound = boundClass?.let(typeMapper::isErasedGenericInterface) == true
            val erasedGenericClassifierBound = erasedGenericClassBound || erasedGenericInterfaceBound
            val nullableBoundParameter = (simpleBound.classifier as? IrTypeParameterSymbol)
                ?.takeIf { simpleBound.isMarkedNullable() }
            if (nullableBoundParameter != null) {
                // There is no truthful CLR GenericParamConstraint for `X : Y?`: `Y` is too
                // strong for nullable value substitutions and the boxed-or-null `object` carrier
                // is not a constraint. The logical graph remains authoritative in KLIB/KType.
                return@mapIndexedNotNull null
            }
            if (
                simpleBound.isMarkedNullable() ||
                (simpleBound.arguments.isNotEmpty() && !isComparableSelfBound && !erasedGenericClassifierBound)
            ) {
                dotNetUnsupported(
                    "type parameter '${name.asString()}' has an unsupported constraint ${bound.render()}; " +
                            "constraints must be non-null type parameters, non-generic module-local " +
                            "classes/interfaces, or declaration-erased Kotlin classifiers"
                )
            }
            if (isComparableSelfBound) {
                val mappedBound = typeMapper.toDotNetIlValueType(bound) as? DotNetIlValueType.UserClass
                    ?: dotNetUnsupported(
                        "type parameter '${name.asString()}' has no canonical CLR Comparable constraint"
                    )
                return@mapIndexedNotNull Triple(1, index, mappedBound)
            }
            if (erasedGenericClassifierBound) {
                val classifier = simpleBound.classifier as IrClassSymbol
                val constraintMapper = if (erasedGenericInterfaceBound) {
                    typeMapper.canonicalGenericInterfaceSignatureView()
                } else {
                    typeMapper
                }
                val mappedBound = constraintMapper.toDotNetIlValueType(bound) as? DotNetIlValueType.UserClass
                    ?: dotNetUnsupported(
                        "type parameter '${name.asString()}' has no erased CLR classifier constraint for ${bound.render()}"
                    )
                return@mapIndexedNotNull Triple(if (classifier.owner.isInterface) 1 else 0, index, mappedBound)
            }
            val boundParameter = (simpleBound.classifier as? IrTypeParameterSymbol)?.owner
            val erasedClassOwner = (boundParameter?.parent as? IrClass)
                ?.takeIf(typeMapper::isErasedGenericClass)
            if (erasedClassOwner != null) {
                if (forMetadata) return@mapIndexedNotNull null
                val carrier = typeMapper.toDotNetIlValueType(bound) ?: DotNetIlValueType.Object
                return@mapIndexedNotNull Triple(2, index, carrier)
            }
            if (
                forMetadata && boundParameter != null &&
                (origin == DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER ||
                        (boundParameter.parent as? IrClass)?.let { owner ->
                            typeMapper.isErasedGenericInterface(owner) || typeMapper.isErasedGenericClass(owner)
                        } == true)
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
                    // An erased Kotlin interface has no owner-generic CLR slot
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
 * The shared generic type-parameter gate: a supported parameter is either a reified parameter of
 * an inline function whose physical body has already become the deterministic throwing stub, or
 * unconstrained (`Any?`), logically non-null but physically unconstrained (`Any`), or has direct
 * non-null type-parameter, non-generic class/interface, or declaration-erased Kotlin classifier
 * bounds.
 * [allowDeclarationSiteVariance] is true for generic interfaces, which preserve it directly in
 * CLR metadata, and for erased Kotlin-owned generic classes, whose variance remains in KLIB and
 * compiler assignability without producing CLR class variance. Functions remain invariant.
 * [dotNetConstraintTypes] performs the live
 * module-local mapping later, once the class registry exists. Everything else is rejected loudly
 * at the declaration (never erased):
 * - `reified` is accepted only for an inline function; classes and independently callable
 *   non-inline methods never acquire reified semantics from CLR generic dispatch;
 * - declaration-site variance (`out`/`in`) is rejected unless the owner has either the direct
 *   interface representation or the erased Kotlin class representation above;
 * - nullable and generic-instantiation constraints stay outside this stage; direct bounds on
 *   another owner or method type parameter are represented by CLR VAR/MVAR TypeSpecs. ONE
 *   pre-existing exception, enabled by
 *   [allowStringBounds]: a `T` bounded by `String`/`String?` predates this slice (the
 *   string-concat lowering's receiver mapping sends every use of such a `T` to IL `string`,
 *   see [isDotNetStringType]) and stays supported on FUNCTIONS for compatibility — the
 *   function still declares its real `<T>` arity and call sites still carry the instantiation
 *   (no erasure of the token; only the SLOT type is the bound's `string`). A direct
 *   `CharSequence` bound is supported on every generic owner but omitted from physical CLR
 *   constraints: the real parameter token remains in every slot, while KLIB and the classified
 *   operation boundary enforce the logical bound.
 */
internal fun checkDotNetTypeParametersSupported(
    typeParameters: List<IrTypeParameter>,
    ownerDescription: String,
    allowStringBounds: Boolean = false,
    allowDeclarationSiteVariance: Boolean = false,
    allowReified: Boolean = false,
) {
    for (typeParameter in typeParameters) {
        val parameterName = typeParameter.name.asString()
        if (typeParameter.isReified && !allowReified) {
            dotNetUnsupported(
                "$ownerDescription has a reified type parameter '$parameterName'; " +
                        "reified type parameters require an inline function"
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
                // No CLR runtime flag represents Kotlin's union of non-null references and value
                // types. Preserve the bound in KLIB and retain an unconstrained physical token.
                superType.isAny() -> false
                superType.isString() || superType.isNullableString() -> !allowStringBounds
                superType.isDotNetCharSequenceType() -> false
                superType.isDotNetComparableSelfBound(typeParameter) -> false
                else -> {
                    val simpleType = superType as? IrSimpleType
                    simpleType == null ||
                            (simpleType.isMarkedNullable() && simpleType.classifier !is IrTypeParameterSymbol) ||
                            (simpleType.arguments.isNotEmpty() &&
                                    !simpleType.isPotentialErasedKotlinClassifierBound()) ||
                            (simpleType.classifier !is IrClassSymbol &&
                                    simpleType.classifier !is IrTypeParameterSymbol)
                }
            }
        }
        if (unsupportedBound != null) {
            dotNetUnsupported(
                "$ownerDescription constrains type parameter '$parameterName' with unsupported type " +
                        "${unsupportedBound.render()}; constraints must be non-null type parameters or " +
                        "non-generic classes/interfaces or declaration-erased Kotlin classifiers"
            )
        }
    }
}

/**
 * Shape-gate candidate for a Kotlin-owned generic class/interface bound. The live mapper later
 * proves that this classifier has the accepted erased ABI; foreign CLR generic instances
 * therefore still fail instead of being silently erased.
 */
private fun IrSimpleType.isPotentialErasedKotlinClassifierBound(): Boolean {
    if (classifier !is IrClassSymbol) return false
    // A projected declaration may already have had its owner's physical parameters erased before
    // this early shape gate runs. Arguments on the logical bound are the stable evidence
    // (`Enum<E>`, `Iterable<T>`); the live mapper still has to prove that the classifier is a
    // registered erased Kotlin class/interface, so a foreign CLR generic cannot pass accidentally.
    return arguments.isNotEmpty()
}

/**
 * The generic-method gate, run over top-level functions during gathering and over member
 * functions by their owning class/interface shape gate. Ordinary generic functions retain their
 * callable CLR generic method. A reified inline function retains only an assembly-visible CLR
 * generic throwing stub after call-site substitution. Non-generic functions pass untouched.
 */
internal fun IrSimpleFunction.checkDotNetGenericFunctionSupported() {
    if (typeParameters.isEmpty()) return
    val functionName = name.asString()
    checkDotNetTypeParametersSupported(
        typeParameters,
        "function '$functionName'",
        allowStringBounds = true,
        allowReified = isInline,
    )
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
            ?.takeIf {
                val owner = ((it.classifier as? IrClassSymbol)?.owner)
                owner?.isInterface == true && !owner.isDotNetAnnotationBaseClass()
            }
    }

/** `kotlin.Annotation` is logical KLIB identity and the physical CLR System.Attribute base. */
internal fun IrClass.isDotNetAnnotationBaseClass(): Boolean =
    fqNameWhenAvailable?.asString() == "kotlin.Annotation"

internal fun IrType.isDotNetAnnotationBaseType(): Boolean =
    ((this as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.isDotNetAnnotationBaseClass() == true

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
    // An abstract MethodDef is necessarily a CLR virtual slot. Keep this independent of the
    // logical owner's modality: a Kotlin enum is source-final in serialized KLIB while its
    // abstract entry contract is physically implemented by private entry subclasses.
    if (modality == Modality.ABSTRACT) return true
    val ownerModality = (parent as? IrClass)?.modality
    return modality == Modality.OPEN &&
            (ownerModality == Modality.OPEN ||
                    ownerModality == Modality.ABSTRACT ||
                    ownerModality == Modality.SEALED)
}

internal fun IrType.isDotNetNullableStringType(): Boolean {
    if (isNullableString()) return true
    val typeParameter = ((this as? IrSimpleType)?.classifier as? IrTypeParameterSymbol)?.owner ?: return false
    return typeParameter.superTypes.any { it.isNullableString() }
}

internal fun IrType.isDotNetStringType(): Boolean {
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
 * Renders a `kotlin.Float` constant as an `ldc.r4` operand. Decimal finite values use the host
 * Kotlin/JVM shortest representation and fall back to ILAsm's exact raw-bit spelling when the
 * host parser does not reproduce the same `float32`. NaN, infinities, and negative zero are
 * always raw so their payload/sign never depends on ILAsm decimal parsing.
 */
internal fun Float.toIlFloat32Literal(): String {
    val rawBitsLiteral = "float32(0x%08X)".format(toRawBits())
    if (isNaN() || isInfinite() || (this == 0.0f && toRawBits() != 0)) return rawBitsLiteral
    val decimalLiteral = toString().lowercase()
    return if (decimalLiteral.toFloat().toRawBits() == toRawBits()) decimalLiteral else rawBitsLiteral
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
