/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.dotnet.dotNetImportedClrTypeAuthorityOrNull
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericClassDeclaration
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.classTypeArgumentsCount
import org.jetbrains.kotlin.ir.expressions.getClassTypeArgument
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.types.Variance

/**
 * Whether a constructor with an owner-dependent semantic parameter must use the producer's
 * recorded physical MethodDef rather than remap its logical KLIB signature in this emission.
 *
 * Source backing is deliberately irrelevant here. Bootstrap Runtime/Stdlib declarations remain
 * source-backed while a separately emitted user assembly consumes their recorded ABI. Current
 * emitter ownership is the boundary. Imported CLR declarations retain their own MethodDef
 * authority and therefore never require a Kotlin producer record.
 */
internal fun IrClass.requiresProducerRecordedGenericOwnerConstructorMethodDef(
    currentModuleClasses: Set<IrClass>,
    hasSemanticCarrierParameter: Boolean,
): Boolean =
    hasSemanticCarrierParameter &&
            isDotNetGenericClassDeclaration &&
            this !in currentModuleClasses &&
            dotNetImportedClrTypeAuthorityOrNull() == null

/**
 * One constructor parameter whose physical type depends on the semantic owner.
 *
 * [determiningParameters] can be empty: a parameter may directly capture an enclosing owner
 * parameter without mentioning a type parameter of the class being allocated. Such a mixed
 * capture is still an exact-construction input obligation even though it does not determine the
 * allocated TypeSpec itself.
 *
 * This is structural information only. In particular, [requiredOwnerParameters] does not prove
 * that the corresponding call argument has those carriers. A value-provenance consumer must
 * independently establish that fact before preserving the constructed type.
 */
internal data class DotNetGenericOwnerExactConstructorDeterminingUse(
    val parameterIndex: Int,
    val determiningParameters: Set<IrTypeParameterSymbol>,
    val substitutedParameterType: IrType,
    val requiredOwnerParameters: Set<IrTypeParameterSymbol>,
) {
    init {
        require(parameterIndex >= 0 && requiredOwnerParameters.isNotEmpty()
        ) {
            "an exact generic-owner constructor use requires one owner-dependent physical input"
        }
    }
}

/**
 * The single invariant construction described by one [IrConstructorCall].
 *
 * FIR normally writes a constructed generic type to `IrConstructorCall.type`. Common local-
 * declaration lowering can instead leave that type bare and append the captured class arguments
 * to the call's class-type-argument vector. Both encodings describe the same CLR TypeSpec. When
 * both are present they must agree; neither one is allowed to override the other.
 *
 * [determiningParameters] are parameters of [constructedClass], while
 * [ownerParameterDependencies] are parameters physically captured by the semantic owner whose
 * body is being analysed. The latter are dependencies, not evidence. A later analysis must prove
 * every relevant argument in [determiningUses] before this construction can remain exact.
 */
internal data class DotNetGenericOwnerExactConstructorUse(
    val constructedClass: IrClass,
    val constructedType: IrSimpleType,
    val substitutions: Map<IrTypeParameterSymbol, IrType>,
    val determiningParameters: Set<IrTypeParameterSymbol>,
    val determiningUses: List<DotNetGenericOwnerExactConstructorDeterminingUse>,
    val ownerParameterDependencies: Set<IrTypeParameterSymbol>,
) {
    init {
        require(constructedClass.typeParameters.isNotEmpty() &&
                substitutions.keys == constructedClass.typeParameters.mapTo(linkedSetOf()) { it.symbol } &&
                determiningParameters.isNotEmpty() &&
                determiningParameters.all(substitutions::containsKey) &&
                ownerParameterDependencies.isNotEmpty() &&
                determiningUses.all { use ->
                    use.determiningParameters.all(determiningParameters::contains)
                }
        ) {
            "an exact generic-owner constructor use requires one complete invariant substitution"
        }
    }
}

/**
 * The complete invariant class construction encoded by one [IrConstructorCall].
 *
 * This fact deliberately says nothing about where the call occurs. In particular, a logical
 * argument which names an enclosing class parameter is not thereby a verifier-visible CLR
 * GenericParam. Construction-site admission must separately prove that every such parameter has
 * a physical binder in the MethodDef/TypeDef which contains the `newobj` instruction.
 */
internal data class DotNetGenericOwnerInvariantConstructorUse(
    val constructedClass: IrClass,
    val constructedType: IrSimpleType,
    val substitutions: Map<IrTypeParameterSymbol, IrType>,
)

/** Parameters of [owner] whose physical binders can occur in its semantic body. */
internal fun IrType.dotNetGenericOwnerParameterDependencies(
    owner: IrClass,
): Set<IrTypeParameterSymbol> {
    val ownerParameters = owner.dotNetPhysicallyCapturedTypeParameters()
    if (ownerParameters.isEmpty()) return emptySet()
    return buildSet { collectDotNetTypeParameterDependencies(ownerParameters, this) }
}

/**
 * Resolves the exact class instantiation named by this constructor call without using a logical
 * destination type as physical evidence.
 *
 * This function deliberately does not decide whether any call argument is exact. It only exposes
 * the complete dependency vector which the semantic-body and physical-value analyses must prove.
 * It also does not admit a CLR TypeDef or constructor MethodDef; callers must join this result
 * with their already-selected declaration authority.
 */
internal fun IrConstructorCall.dotNetExactGenericOwnerConstructorUseOrNull(
    semanticOwner: IrClass,
): DotNetGenericOwnerExactConstructorUse? {
    val constructor = symbol.owner
    val construction = dotNetInvariantGenericOwnerConstructorUseOrNull() ?: return null
    val constructedClass = construction.constructedClass
    val classArguments = constructedClass.typeParameters.map { parameter ->
        construction.substitutions.getValue(parameter.symbol)
    }
    if (classArguments.any { argument ->
            argument.hasUnsupportedDotNetExactGenericOwnerDependency(semanticOwner)
        }
    ) {
        return null
    }

    val substitutions = construction.substitutions
    val dependenciesByParameter = substitutions.mapValues { entry ->
        entry.value.dotNetGenericOwnerParameterDependencies(semanticOwner)
    }
    val determiningParameters = dependenciesByParameter.entries
        .filterTo(linkedSetOf()) { entry -> entry.value.isNotEmpty() }
        .mapTo(linkedSetOf()) { entry -> entry.key }
    if (determiningParameters.isEmpty()) return null

    val substitutor = IrTypeSubstitutor(substitutions, allowEmptySubstitution = true)
    val determiningUses = mutableListOf<DotNetGenericOwnerExactConstructorDeterminingUse>()
    for (parameter in constructor.parameters) {
        val usedParameters = determiningParameters.filterTo(linkedSetOf()) { candidate ->
            parameter.type.referencesTypeParameter(candidate)
        }
        val substitutedType = substitutor.substitute(parameter.type)
        val requiredOwnerParameters = substitutedType
            .dotNetGenericOwnerParameterDependencies(semanticOwner)
        if (requiredOwnerParameters.isEmpty()) continue
        if (arguments.getOrNull(parameter.indexInParameters) == null ||
            substitutedType.hasUnsupportedDotNetExactGenericOwnerDependency(semanticOwner)
        ) {
            return null
        }
        determiningUses += DotNetGenericOwnerExactConstructorDeterminingUse(
            parameterIndex = parameter.indexInParameters,
            determiningParameters = usedParameters,
            substitutedParameterType = substitutedType,
            requiredOwnerParameters = requiredOwnerParameters,
        )
    }

    val constructedType = construction.constructedType
    val ownerDependencies = constructedType.dotNetGenericOwnerParameterDependencies(semanticOwner)
    if (ownerDependencies.isEmpty()) return null
    return DotNetGenericOwnerExactConstructorUse(
        constructedClass = constructedClass,
        constructedType = constructedType,
        substitutions = substitutions,
        determiningParameters = determiningParameters,
        determiningUses = determiningUses,
        ownerParameterDependencies = ownerDependencies,
    )
}

/**
 * Resolves the two Common-IR encodings of a constructed class TypeSpec into one invariant fact.
 * A disagreement is not repaired by preferring either encoding: it means no construction
 * authority is available.
 */
internal fun IrConstructorCall.dotNetInvariantGenericOwnerConstructorUseOrNull():
        DotNetGenericOwnerInvariantConstructorUse? {
    val constructedClass = symbol.owner.parent as? IrClass ?: return null
    val arity = constructedClass.typeParameters.size
    if (arity == 0) return null

    val resultEncoding = resultTypeArgumentEncoding(constructedClass, arity)
    val callEncoding = callTypeArgumentEncoding(arity)
    if (resultEncoding === ConstructorTypeArgumentEncoding.Invalid ||
        callEncoding === ConstructorTypeArgumentEncoding.Invalid
    ) {
        return null
    }
    val resultArguments = (resultEncoding as? ConstructorTypeArgumentEncoding.Present)?.arguments
    val callArguments = (callEncoding as? ConstructorTypeArgumentEncoding.Present)?.arguments
    if (resultArguments != null && callArguments != null &&
        resultArguments.indices.any { index ->
            !resultArguments[index].sameInvariantConstructorTypeAs(callArguments[index])
        }
    ) {
        return null
    }
    val classArguments = resultArguments ?: callArguments ?: return null
    val substitutions = constructedClass.typeParameters.indices.associateTo(linkedMapOf()) { index ->
        constructedClass.typeParameters[index].symbol to classArguments[index]
    }
    return DotNetGenericOwnerInvariantConstructorUse(
        constructedClass = constructedClass,
        constructedType = constructedClass.symbol.typeWith(classArguments),
        substitutions = substitutions,
    )
}

/** Every type parameter which contributes to this logical type. This is dependency, not proof. */
internal fun IrType.dotNetTypeParameterDependencies(): Set<IrTypeParameterSymbol> =
    buildSet { collectDotNetTypeParameterDependencies(destination = this) }

private sealed interface ConstructorTypeArgumentEncoding {
    data object Absent : ConstructorTypeArgumentEncoding
    data object Invalid : ConstructorTypeArgumentEncoding
    data class Present(val arguments: List<IrType>) : ConstructorTypeArgumentEncoding
}

private fun IrConstructorCall.resultTypeArgumentEncoding(
    constructedClass: IrClass,
    arity: Int,
): ConstructorTypeArgumentEncoding {
    val simple = type as? IrSimpleType ?: return ConstructorTypeArgumentEncoding.Absent
    if (simple.classifier != constructedClass.symbol) return ConstructorTypeArgumentEncoding.Absent
    if (simple.arguments.isEmpty()) return ConstructorTypeArgumentEncoding.Absent
    if (simple.nullability == SimpleTypeNullability.MARKED_NULLABLE ||
        simple.arguments.size != arity
    ) {
        return ConstructorTypeArgumentEncoding.Invalid
    }
    val arguments = simple.arguments.map { argument ->
        val projection = argument as? IrTypeProjection
            ?: return ConstructorTypeArgumentEncoding.Invalid
        if (projection.variance != Variance.INVARIANT) {
            return ConstructorTypeArgumentEncoding.Invalid
        }
        projection.type
    }
    return ConstructorTypeArgumentEncoding.Present(arguments)
}

private fun IrConstructorCall.callTypeArgumentEncoding(
    arity: Int,
): ConstructorTypeArgumentEncoding {
    val count = classTypeArgumentsCount
    if (count == 0) return ConstructorTypeArgumentEncoding.Absent
    if (count != arity) return ConstructorTypeArgumentEncoding.Invalid
    val arguments = (0 until count).map { index ->
        getClassTypeArgument(index) ?: return ConstructorTypeArgumentEncoding.Invalid
    }
    return ConstructorTypeArgumentEncoding.Present(arguments)
}

private fun IrClass.dotNetPhysicallyCapturedTypeParameters(): Set<IrTypeParameterSymbol> =
    buildSet {
        var current: IrClass? = this@dotNetPhysicallyCapturedTypeParameters
        while (current != null) {
            current.typeParameters.mapTo(this) { parameter -> parameter.symbol }
            current = if (current.isInner) current.parent as? IrClass else null
        }
    }

private fun IrType.collectDotNetTypeParameterDependencies(
    candidates: Set<IrTypeParameterSymbol>,
    destination: MutableSet<IrTypeParameterSymbol>,
) {
    val simple = this as? IrSimpleType ?: return
    val parameter = simple.classifier as? IrTypeParameterSymbol
    if (parameter != null && parameter in candidates) destination += parameter
    simple.arguments.forEach { argument ->
        (argument as? IrTypeProjection)?.type
            ?.collectDotNetTypeParameterDependencies(candidates, destination)
    }
}

private fun IrType.collectDotNetTypeParameterDependencies(
    destination: MutableSet<IrTypeParameterSymbol>,
) {
    val simple = this as? IrSimpleType ?: return
    (simple.classifier as? IrTypeParameterSymbol)?.let(destination::add)
    simple.arguments.forEach { argument ->
        (argument as? IrTypeProjection)?.type
            ?.collectDotNetTypeParameterDependencies(destination)
    }
}

private fun IrType.referencesTypeParameter(parameter: IrTypeParameterSymbol): Boolean {
    val simple = this as? IrSimpleType ?: return false
    if (simple.classifier == parameter) return true
    return simple.arguments.any { argument ->
        (argument as? IrTypeProjection)?.type?.referencesTypeParameter(parameter) == true
    }
}

/**
 * First exact-constructor grammar: no star/projected physical arguments, no `!T?` carrier, and no
 * owner-dependent value-class wrapper whose generic physical payload has not been recorded.
 */
internal fun IrType.hasUnsupportedDotNetExactGenericOwnerDependency(owner: IrClass): Boolean {
    val simple = this as? IrSimpleType ?: return true
    val dependencies = dotNetGenericOwnerParameterDependencies(owner)
    val parameter = simple.classifier as? IrTypeParameterSymbol
    if (parameter != null && parameter in dependencies &&
        simple.nullability == SimpleTypeNullability.MARKED_NULLABLE
    ) {
        return true
    }
    val classifier = (simple.classifier as? IrClassSymbol)?.owner
    if (classifier?.isValue == true && dependencies.isNotEmpty()) return true
    return simple.arguments.any { argument ->
        val projection = argument as? IrTypeProjection ?: return@any true
        projection.variance != Variance.INVARIANT ||
                projection.type.hasUnsupportedDotNetExactGenericOwnerDependency(owner)
    }
}

/**
 * Whether this type cannot be used as an exact CLR TypeSpec argument by the first construction-
 * site grammar. This is intentionally independent of any particular semantic owner: every open
 * parameter in the type must later be matched to a verifier-visible binder at the allocation
 * site. Projected/star arguments, `T?`, and value-class-dependent carriers remain fail-closed.
 */
internal fun IrType.hasUnsupportedDotNetInvariantConstructorArgument(): Boolean {
    val simple = this as? IrSimpleType ?: return true
    val parameter = simple.classifier as? IrTypeParameterSymbol
    if (parameter != null && simple.nullability == SimpleTypeNullability.MARKED_NULLABLE) {
        return true
    }
    val classifier = (simple.classifier as? IrClassSymbol)?.owner
    if (classifier?.isValue == true && dotNetTypeParameterDependencies().isNotEmpty()) return true
    return simple.arguments.any { argument ->
        val projection = argument as? IrTypeProjection ?: return@any true
        projection.variance != Variance.INVARIANT ||
                projection.type.hasUnsupportedDotNetInvariantConstructorArgument()
    }
}

/** Equality of the two independent invariant constructor-type encodings. */
private fun IrType.sameInvariantConstructorTypeAs(other: IrType): Boolean {
    val left = this as? IrSimpleType ?: return false
    val right = other as? IrSimpleType ?: return false
    if (left.classifier != right.classifier || left.nullability != right.nullability ||
        left.arguments.size != right.arguments.size
    ) {
        return false
    }
    return left.arguments.indices.all { index ->
        val leftProjection = left.arguments[index] as? IrTypeProjection ?: return@all false
        val rightProjection = right.arguments[index] as? IrTypeProjection ?: return@all false
        leftProjection.variance == Variance.INVARIANT &&
                rightProjection.variance == Variance.INVARIANT &&
                leftProjection.type.sameInvariantConstructorTypeAs(rightProjection.type)
    }
}
