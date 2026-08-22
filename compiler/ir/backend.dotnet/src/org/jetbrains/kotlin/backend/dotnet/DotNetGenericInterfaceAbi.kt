/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.AbstractIrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.defaultType as typeParameterDefaultType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.types.Variance

/**
 * The physical CLR identity and any independently truthful mapped host capabilities of one
 * logical Kotlin generic interface.
 *
 * An erased Kotlin-owned interface has only [canonicalClassInfo]. Explicit host mappings such as
 * Common `Comparable<T>` may add independently truthful declared/exact capabilities. A reified
 * Kotlin-owned interface instead uses its natural CLR `I<T>` as both canonical and declared view
 * and may add one producer-recorded invariant exact-input sibling. Its separate semantic
 * capability remains authoritative for Kotlin views which CLR variance cannot name.
 */
internal data class DotNetGenericInterfaceInfo(
    val canonicalClassInfo: DotNetIlClassInfo,
    val declaredClassInfo: DotNetIlClassInfo? = null,
    val exactClassInfo: DotNetIlClassInfo? = null,
) {
    val mostSpecificCapabilityView: DotNetGenericInterfaceView?
        get() = when {
            exactClassInfo != null -> DotNetGenericInterfaceView.EXACT
            declaredClassInfo != null -> DotNetGenericInterfaceView.DECLARED
            else -> null
        }

    val mostSpecificCapabilityClassInfo: DotNetIlClassInfo?
        get() = exactClassInfo ?: declaredClassInfo

    fun classInfo(view: DotNetGenericInterfaceView): DotNetIlClassInfo? = when (view) {
        DotNetGenericInterfaceView.CANONICAL -> canonicalClassInfo
        DotNetGenericInterfaceView.DECLARED -> declaredClassInfo
        DotNetGenericInterfaceView.EXACT -> exactClassInfo
    }
}

internal enum class DotNetGenericInterfaceView {
    CANONICAL,
    DECLARED,
    EXACT,
}

/** Which explicitly mapped host capability owns a member in addition to its erased Kotlin slot. */
internal enum class DotNetGenericInterfaceMemberView {
    DECLARED,
    EXACT,
}

internal val DotNetGenericInterfaceMemberView.physicalView: DotNetGenericInterfaceView
    get() = when (this) {
        DotNetGenericInterfaceMemberView.DECLARED -> DotNetGenericInterfaceView.DECLARED
        DotNetGenericInterfaceMemberView.EXACT -> DotNetGenericInterfaceView.EXACT
    }

/**
 * Every Kotlin-owned generic interface needs a canonical identity, including an invariant one:
 * use-site projections and stars can change its logical view without changing object identity.
 *
 * Ownership is deliberately established by the emitter (local declarations) or the physical
 * declaration index embedded in an external assembly before this predicate is acted on. It must
 * not be used to rewrite an imported CLR interface.
 */
internal val IrClass.isDotNetGenericInterfaceDeclaration: Boolean
    get() = isInterface &&
            typeParameters.isNotEmpty() &&
            dotNetImportedClrSourceOrNull() == null


/**
 * Marks a copied method parameter whose Kotlin bound depends on an interface owner parameter.
 * The CLR constraint is erased from executable metadata because Kotlin projections can widen its
 * call set independently of the runtime CLR instantiation; the IR bound remains available while
 * compiling the single semantic body.
 */
internal val DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER")

internal fun IrType.isDotNetOwnerDependentConstraint(interfaceClass: IrClass): Boolean {
    val simpleType = this as? IrSimpleType ?: return false
    val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
    if (parameter?.parent == interfaceClass) return true
    return simpleType.arguments.any { argument ->
        (argument as? IrTypeProjection)?.type?.isDotNetOwnerDependentConstraint(interfaceClass) == true
    }
}

/**
 * Returns the owner-relative bound for each method parameter, or `null` when the erased CLR slot
 * would need a representation-changing generic adapter. A direct `R : T` can be adapted by
 * instantiating the implementation at `T` and casting at the bridge boundary. A nested occurrence
 * is equally safe when every carrier that observes it has one erased Kotlin runtime identity;
 * `Box<R>` and `Box<T>` then have the same physical signature. A native CLR generic carrier still
 * observes `R` and therefore remains unsupported at this boundary.
 */
internal fun IrSimpleFunction.dotNetDirectOwnerRelativeMethodBoundsOrNull(
    interfaceClass: IrClass,
    isPhysicallyErasedCarrier: (IrClass) -> Boolean = { false },
): List<IrType?>? {
    fun IrType.referencesPhysically(parameter: IrTypeParameterSymbol): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        if (simpleType.classifier == parameter) return true
        val carrier = (simpleType.classifier as? IrClassSymbol)?.owner
        if (carrier?.let(isPhysicallyErasedCarrier) == true) return false
        return simpleType.arguments.any { argument ->
            (argument as? IrTypeProjection)?.type?.referencesPhysically(parameter) == true
        }
    }

    fun IrType.isDirect(parameter: IrTypeParameterSymbol): Boolean =
        (this as? IrSimpleType)?.classifier == parameter

    val signatureTypes = sequenceOf(returnType) +
            parameters.asSequence()
                .filter { it.kind != IrParameterKind.DispatchReceiver }
                .map { it.type }
    return typeParameters.map { parameter ->
        val ownerRelativeBounds = parameter.superTypes.filter { bound ->
            bound.isDotNetOwnerDependentConstraint(interfaceClass)
        }
        if (ownerRelativeBounds.isEmpty()) return@map null
        val ownerBound = parameter.superTypes.singleOrNull() ?: return null
        val boundParameter = (ownerBound as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
        if (boundParameter?.owner?.parent != interfaceClass) return null
        if (signatureTypes.any { type ->
                type.referencesPhysically(parameter.symbol) && !type.isDirect(parameter.symbol)
            }
        ) {
            return null
        }
        if (typeParameters.asSequence()
                .filterNot { it == parameter }
                .flatMap { it.superTypes.asSequence() }
                .any { bound -> bound.referencesPhysically(parameter.symbol) }
        ) {
            return null
        }
        ownerBound
    }
}

/** Whether this contributor resolves to the selected intersection signature in [intersectionOwner]. */
internal fun IrSimpleFunction.hasDotNetResolvedIntersectionSignature(
    intersectionOwner: IrClass,
    signatureSource: IrSimpleFunction,
    includeReturnType: Boolean,
): Boolean {
    val memberOwner = parent as? IrClass ?: return false
    val ownerSubstitutor = AbstractIrTypeSubstitutor.forSuperClass(
        memberOwner.symbol,
        intersectionOwner.defaultType,
    ) ?: return false
    if (typeParameters.size != signatureSource.typeParameters.size) return false
    if (typeParameters.zip(signatureSource.typeParameters).any { pair ->
            pair.first.isReified != pair.second.isReified
        }
    ) {
        return false
    }
    val methodSubstitution = typeParameters.zip(signatureSource.typeParameters).associate { pair ->
        pair.first.symbol to pair.second.typeParameterDefaultType
    }
    val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
    fun resolvedType(type: IrType): IrType =
        methodSubstitutor.substitute(ownerSubstitutor.substitute(type))

    if (includeReturnType && resolvedType(returnType) != signatureSource.returnType) return false
    val memberParameters = parameters.filter { it.kind != IrParameterKind.DispatchReceiver }
    val signatureParameters = signatureSource.parameters.filter {
        it.kind != IrParameterKind.DispatchReceiver
    }
    if (memberParameters.size != signatureParameters.size ||
        memberParameters.zip(signatureParameters).any { pair ->
            resolvedType(pair.first.type) != pair.second.type
        }
    ) {
        return false
    }
    return typeParameters.zip(signatureSource.typeParameters).all { pair ->
        pair.first.superTypes.map(::resolvedType) == pair.second.superTypes
    }
}

internal fun IrType.isDotNetVariantOwnerDependentConstraint(interfaceClass: IrClass): Boolean {
    val simpleType = this as? IrSimpleType ?: return false
    val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
    if (parameter?.parent == interfaceClass && parameter.variance != Variance.INVARIANT) return true
    return simpleType.arguments.any { argument ->
        (argument as? IrTypeProjection)?.type?.isDotNetVariantOwnerDependentConstraint(interfaceClass) == true
    }
}
/**
 * Chooses the member's single typed home. A declaration-variance-safe signature belongs to the
 * public same-name generic view; a signature made legal only by `@UnsafeVariance` (or by an
 * invariant nested occurrence) belongs to the invariant exact capability. A method type
 * parameter constrained by a variant owner parameter is exact-only as well: CoreCLR rejects
 * that GenericParamConstraint on a variant interface at type-load time.
 */
internal fun IrSimpleFunction.dotNetGenericInterfaceMemberView(
    interfaceClass: IrClass,
    isErasedGenericInterface: (IrClass) -> Boolean,
): DotNetGenericInterfaceMemberView {
    require(interfaceClass.isDotNetGenericInterfaceDeclaration)
    fun IrType.isLegalAt(polarity: TypePolarity): Boolean = isClrLegalAtDeclaredVariance(
        interfaceClass,
        polarity,
        isErasedGenericInterface,
        preserveCurrentGenericInterface = false,
    )
    val safeReturn = returnType.isLegalAt(TypePolarity.OUT)
    val safeParameters = parameters
        .asSequence()
        .filter { it.kind != IrParameterKind.DispatchReceiver }
        .all { parameter -> parameter.type.isLegalAt(TypePolarity.IN) }
    val safeConstraints = typeParameters.all { typeParameter ->
        typeParameter.superTypes.none { it.isDotNetVariantOwnerDependentConstraint(interfaceClass) }
    }
    return if (safeReturn && safeParameters && safeConstraints) {
        DotNetGenericInterfaceMemberView.DECLARED
    } else {
        DotNetGenericInterfaceMemberView.EXACT
    }
}

/**
 * All typed CLR views that must contain this accessor. An accessor normally has one typed home.
 * The only duplication is a complete exact property: when either accessor requires the exact
 * capability, its declaration-safe sibling is repeated there so C# observes one coherent
 * read/write property instead of a setter-only member hiding an inherited getter.
 */
internal fun IrSimpleFunction.dotNetGenericInterfaceMemberViews(
    interfaceClass: IrClass,
    isErasedGenericInterface: (IrClass) -> Boolean,
): List<DotNetGenericInterfaceMemberView> {
    val primaryView = dotNetGenericInterfaceMemberView(interfaceClass, isErasedGenericInterface)
    if (primaryView == DotNetGenericInterfaceMemberView.EXACT) {
        return listOf(DotNetGenericInterfaceMemberView.EXACT)
    }
    val property = correspondingPropertySymbol?.owner ?: return listOf(primaryView)
    val requiresCompleteExactProperty = listOfNotNull(property.getter, property.setter).any { accessor ->
        accessor.dotNetGenericInterfaceMemberView(interfaceClass, isErasedGenericInterface) ==
                DotNetGenericInterfaceMemberView.EXACT
    }
    return if (requiresCompleteExactProperty) {
        listOf(DotNetGenericInterfaceMemberView.DECLARED, DotNetGenericInterfaceMemberView.EXACT)
    } else {
        listOf(DotNetGenericInterfaceMemberView.DECLARED)
    }
}

/** Whether this direct typed super-interface edge is legal on the declaration-variant CLR view. */
internal fun IrType.isDotNetClrLegalDeclaredSupertype(
    owner: IrClass,
    isErasedGenericInterface: (IrClass) -> Boolean,
): Boolean = isClrLegalAtDeclaredVariance(
    owner,
    TypePolarity.OUT,
    isErasedGenericInterface,
    preserveCurrentGenericInterface = true,
)

private enum class TypePolarity {
    OUT,
    IN,
    BOTH;

    fun through(variance: Variance): TypePolarity = when {
        this == BOTH || variance == Variance.INVARIANT -> BOTH
        variance == Variance.OUT_VARIANCE -> this
        else -> if (this == OUT) IN else OUT
    }
}

/**
 * ECMA-335 variance validity for one member-signature position. This is intentionally structural:
 * it handles any number and mixture of declaration parameters, nested declaration-site variance,
 * and Kotlin use-site projections. An invariant nested carrier requires both polarities, which is
 * precisely why a covariant parameter hidden inside such a carrier must move to the invariant
 * exact capability rather than being emitted as invalid CLR metadata.
 */
private fun IrType.isClrLegalAtDeclaredVariance(
    owner: IrClass,
    polarity: TypePolarity,
    isErasedGenericInterface: (IrClass) -> Boolean,
    preserveCurrentGenericInterface: Boolean,
): Boolean {
    val simpleType = this as? IrSimpleType ?: return true
    val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
    if (parameter?.parent == owner) {
        return when (polarity) {
            TypePolarity.OUT -> parameter.variance != Variance.IN_VARIANCE
            TypePolarity.IN -> parameter.variance != Variance.OUT_VARIANCE
            TypePolarity.BOTH -> parameter.variance == Variance.INVARIANT
        }
    }

    val classifier = (simpleType.classifier as? IrClassSymbol)?.owner ?: return true
    if (!preserveCurrentGenericInterface && isErasedGenericInterface(classifier)) {
        // A Kotlin-owned generic interface nested in a typed member or supertype argument maps to
        // its non-generic canonical identity. Its logical arguments are absent from the physical
        // signature, so they cannot make that signature illegal under CLR variance. A direct
        // declared-superinterface edge is the exception: its outer construction remains typed.
        return true
    }
    return simpleType.arguments.withIndex().all { indexedArgument ->
        val index = indexedArgument.index
        val argument = indexedArgument.value
        val projection = argument as? IrTypeProjection ?: return@all true
        // ECMA-335 permits variance only on interfaces and delegates. Kotlin also permits
        // declaration-site variance on classes, but a class occurrence is physically invariant
        // on the CLR regardless of its Kotlin modifier. Treating a `class Box<out T>` as a
        // covariant CLR carrier would let an illegal use of the owner's `out T` leak onto the
        // declared capability metadata.
        val declarationVariance = if (classifier.isInterface) {
            classifier.typeParameters.getOrNull(index)?.variance ?: Variance.INVARIANT
        } else {
            Variance.INVARIANT
        }
        val effectiveVariance = if (projection.variance == Variance.INVARIANT) {
            declarationVariance
        } else {
            projection.variance
        }
        projection.type.isClrLegalAtDeclaredVariance(
            owner,
            polarity.through(effectiveVariance),
            isErasedGenericInterface,
            preserveCurrentGenericInterface = false,
        )
    }
}
