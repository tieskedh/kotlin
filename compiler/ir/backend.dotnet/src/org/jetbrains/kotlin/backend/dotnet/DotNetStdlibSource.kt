package org.jetbrains.kotlin.backend.dotnet

private data class DotNetStdlibSourceResource(
    val fileName: String,
    val path: String,
    val isCommon: Boolean = false,
)

// Match the ordinary-source producer's relative-path order: FIR file order affects deterministic
// declaration order in the emitted stdlib IL.
private val DOTNET_STDLIB_SOURCE_RESOURCES = listOf(
    DotNetStdlibSourceResource(
        "DotNetStdlibIo.kt",
        "dotnet/src/kotlin/io/DotNetStdlibIo.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetStdlibCollections.kt",
        "dotnet/src/kotlin/collections/DotNetStdlibCollections.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetAbstractMutableCollection.kt",
        "dotnet/src/kotlin/collections/DotNetAbstractMutableCollection.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetAbstractMutableMap.kt",
        "dotnet/src/kotlin/collections/DotNetAbstractMutableMap.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetAbstractMutableSet.kt",
        "dotnet/src/kotlin/collections/DotNetAbstractMutableSet.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetAbstractMutableList.kt",
        "dotnet/src/kotlin/collections/DotNetAbstractMutableList.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetArrayList.kt",
        "dotnet/src/kotlin/collections/DotNetArrayList.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetHashMap.kt",
        "dotnet/src/kotlin/collections/DotNetHashMap.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetHashSet.kt",
        "dotnet/src/kotlin/collections/DotNetHashSet.kt",
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapMapsActuals.kt",
        "dotnet/src/generated/_DotNetBootstrapMapsActuals.kt",
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapSetsActuals.kt",
        "dotnet/src/generated/_DotNetBootstrapSetsActuals.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetStringBuilder.kt",
        "dotnet/src/kotlin/text/DotNetStringBuilder.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetStdlibKotlin.kt",
        "dotnet/src/kotlin/DotNetStdlibKotlin.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetLibrary.kt",
        "dotnet/src/kotlin/DotNetLibrary.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetEnum.kt",
        "dotnet/src/kotlin/DotNetEnum.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetEnumEntriesSerializationProxy.kt",
        "dotnet/src/kotlin/enums/DotNetEnumEntriesSerializationProxy.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetEnumEntries.kt",
        "dotnet/src/kotlin/enums/DotNetEnumEntries.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetSerializationUtil.kt",
        "dotnet/src/kotlin/internal/DotNetSerializationUtil.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetStdlibCancellation.kt",
        "dotnet/src/kotlin/coroutines/cancellation/DotNetStdlibCancellation.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetCoroutineImpl.kt",
        "dotnet/src/kotlin/coroutines/DotNetCoroutineImpl.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetSafeContinuation.kt",
        "dotnet/src/kotlin/coroutines/DotNetSafeContinuation.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetCoroutinesIntrinsics.kt",
        "dotnet/src/kotlin/coroutines/DotNetCoroutinesIntrinsics.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetCoroutineCompilerIntrinsics.kt",
        "dotnet/src/kotlin/coroutines/DotNetCoroutineCompilerIntrinsics.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetVolatileMarker.kt",
        "dotnet/src/kotlin/concurrent/DotNetVolatileMarker.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "DotNetExceptions.kt",
        "dotnet/src/kotlin/DotNetExceptions.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetKClass.kt",
        "dotnet/src/kotlin/reflect/DotNetKClass.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetKAnnotatedElement.kt",
        "dotnet/src/kotlin/reflect/DotNetKAnnotatedElement.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetKCallable.kt",
        "dotnet/src/kotlin/reflect/DotNetKCallable.kt",
    ),
    DotNetStdlibSourceResource(
        "KVisibility.kt",
        "jvm/src/kotlin/reflect/KVisibility.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetKFunction.kt",
        "dotnet/src/kotlin/reflect/DotNetKFunction.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetKProperty.kt",
        "dotnet/src/kotlin/reflect/DotNetKProperty.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetKParameter.kt",
        "dotnet/src/kotlin/reflect/DotNetKParameter.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetKType.kt",
        "dotnet/src/kotlin/reflect/DotNetKType.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetKTypes.kt",
        "dotnet/src/kotlin/reflect/DotNetKTypes.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetKClasses.kt",
        "dotnet/src/kotlin/reflect/DotNetKClasses.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetThrowNoWhenBranchMatchedException.kt",
        "dotnet/src/kotlin/internal/DotNetThrowNoWhenBranchMatchedException.kt",
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapCollections.kt",
        "dotnet/common/src/generated/_DotNetBootstrapCollections.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapMaps.kt",
        "dotnet/common/src/generated/_DotNetBootstrapMaps.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapSets.kt",
        "dotnet/common/src/generated/_DotNetBootstrapSets.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapRanges.kt",
        "dotnet/common/src/generated/_DotNetBootstrapRanges.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapCollectionFactories.kt",
        "dotnet/common/src/generated/_DotNetBootstrapCollectionFactories.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapAppendable.kt",
        "dotnet/common/src/generated/_DotNetBootstrapAppendable.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapStringBuilder.kt",
        "dotnet/common/src/generated/_DotNetBootstrapStringBuilder.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapStrings.kt",
        "dotnet/common/src/generated/_DotNetBootstrapStrings.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapKotlin.kt",
        "dotnet/common/src/generated/_DotNetBootstrapKotlin.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapEnum.kt",
        "dotnet/common/src/generated/_DotNetBootstrapEnum.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapEnumEntries.kt",
        "dotnet/common/src/generated/_DotNetBootstrapEnumEntries.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapJsName.kt",
        "dotnet/common/src/generated/_DotNetBootstrapJsName.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapExperimentalTypeInference.kt",
        "dotnet/common/src/generated/_DotNetBootstrapExperimentalTypeInference.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapOverloadResolutionByLambdaReturnType.kt",
        "dotnet/common/src/generated/_DotNetBootstrapOverloadResolutionByLambdaReturnType.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapMutableCollections.kt",
        "dotnet/common/src/generated/_DotNetBootstrapMutableCollections.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapPreconditions.kt",
        "dotnet/common/src/generated/_DotNetBootstrapPreconditions.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Exceptions.kt",
        "common-non-jvm/src/kotlin/Exceptions.kt",
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapOutOfMemoryError.kt",
        "dotnet/common/src/generated/_DotNetBootstrapOutOfMemoryError.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapScalarBounds.kt",
        "dotnet/common/src/generated/_DotNetBootstrapScalarBounds.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "SharedVariableBox.kt",
        "common-non-jvm/src/kotlin/internal/SharedVariableBox.kt",
    ),
    DotNetStdlibSourceResource(
        "SyntheticConstructorMarker.kt",
        "common-non-jvm/src/kotlin/internal/SyntheticConstructorMarker.kt",
    ),
    DotNetStdlibSourceResource(
        "ThrowHelpers.kt",
        "common-non-jvm/src/kotlin/internal/ThrowHelpers.kt",
    ),
    DotNetStdlibSourceResource(
        "ExceptionsH.kt",
        "common/src/kotlin/ExceptionsH.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "ioH.kt",
        "common/src/kotlin/ioH.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "JvmAnnotationsH.kt",
        "common/src/kotlin/JvmAnnotationsH.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Multiplatform.kt",
        "src/kotlin/annotations/Multiplatform.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Annotations.kt",
        "src/kotlin/internal/Annotations.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "AnnotationsBuiltin.kt",
        "src/kotlin/internal/AnnotationsBuiltin.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "WasExperimental.kt",
        "src/kotlin/annotations/WasExperimental.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "ExperimentalContextParameters.kt",
        "src/kotlin/contextParameters/ExperimentalContextParameters.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "serializationUtil.kt",
        "src/kotlin/internal/serializationUtil.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "progressionUtil.kt",
        "src/kotlin/internal/progressionUtil.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KClass.kt",
        "src/kotlin/reflect/KClass.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KCallable.kt",
        "src/kotlin/reflect/KCallable.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KFunction.kt",
        "src/kotlin/reflect/KFunction.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KProperty.kt",
        "src/kotlin/reflect/KProperty.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KClasses.kt",
        "src/kotlin/reflect/KClasses.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KClassifier.kt",
        "src/kotlin/reflect/KClassifier.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KType.kt",
        "src/kotlin/reflect/KType.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KTypeParameter.kt",
        "src/kotlin/reflect/KTypeParameter.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KTypeProjection.kt",
        "src/kotlin/reflect/KTypeProjection.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KVariance.kt",
        "src/kotlin/reflect/KVariance.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "typeOf.kt",
        "src/kotlin/reflect/typeOf.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KTypeImpl.kt",
        "common-non-jvm/src/kotlin/reflect/KTypeImpl.kt",
    ),
    DotNetStdlibSourceResource(
        "KTypeParameterBase.kt",
        "common-non-jvm/src/kotlin/reflect/KTypeParameterBase.kt",
    ),
    DotNetStdlibSourceResource(
        "throwNoWhenBranchMatchedException.kt",
        "src/kotlin/internal/throwNoWhenBranchMatchedException.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Tuples.kt",
        "src/kotlin/util/Tuples.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "HashCode.kt",
        "src/kotlin/util/HashCode.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Result.kt",
        "src/kotlin/util/Result.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Continuation.kt",
        "src/kotlin/coroutines/Continuation.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "ContinuationInterceptor.kt",
        "src/kotlin/coroutines/ContinuationInterceptor.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "CoroutineContext.kt",
        "src/kotlin/coroutines/CoroutineContext.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "CoroutineContextImpl.kt",
        "src/kotlin/coroutines/CoroutineContextImpl.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "CoroutinesH.kt",
        "src/kotlin/coroutines/CoroutinesH.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "CoroutinesIntrinsicsH.kt",
        "src/kotlin/coroutines/CoroutinesIntrinsicsH.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Intrinsics.kt",
        "src/kotlin/coroutines/intrinsics/Intrinsics.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "AbstractCollection.kt",
        "src/kotlin/collections/AbstractCollection.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "AbstractMap.kt",
        "src/kotlin/collections/AbstractMap.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "AbstractSet.kt",
        "src/kotlin/collections/AbstractSet.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "AbstractList.kt",
        "src/kotlin/collections/AbstractList.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "IndexedValue.kt",
        "src/kotlin/collections/IndexedValue.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Iterables.kt",
        "src/kotlin/collections/Iterables.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Iterators.kt",
        "src/kotlin/collections/Iterators.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "PrimitiveIterators.kt",
        "src/kotlin/collections/PrimitiveIterators.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Range.kt",
        "src/kotlin/ranges/Range.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Ranges.kt",
        "src/kotlin/ranges/Ranges.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Progressions.kt",
        "src/kotlin/ranges/Progressions.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "ProgressionIterators.kt",
        "src/kotlin/ranges/ProgressionIterators.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "PrimitiveRanges.kt",
        "src/kotlin/ranges/PrimitiveRanges.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "AbstractMutableCollection.kt",
        "common/src/kotlin/collections/AbstractMutableCollection.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "AbstractMutableMap.kt",
        "common/src/kotlin/collections/AbstractMutableMap.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "AbstractMutableSet.kt",
        "common/src/kotlin/collections/AbstractMutableSet.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "AbstractMutableList.kt",
        "common/src/kotlin/collections/AbstractMutableList.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "ArrayList.kt",
        "common/src/kotlin/collections/ArrayList.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "HashMap.kt",
        "common/src/kotlin/collections/HashMap.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "HashSet.kt",
        "common/src/kotlin/collections/HashSet.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "LinkedHashMap.kt",
        "common/src/kotlin/collections/LinkedHashMap.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "LinkedHashSet.kt",
        "common/src/kotlin/collections/LinkedHashSet.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "ContractBuilder.kt",
        "src/kotlin/contracts/ContractBuilder.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Effect.kt",
        "src/kotlin/contracts/Effect.kt",
        isCommon = true,
    ),
).sortedBy(DotNetStdlibSourceResource::path)

/**
 * Canonical Kotlin/.NET standard-library source files.
 *
 * Repository stdlib production compiles the ordinary files under `libraries/stdlib/dotnet/src`
 * directly. The backend JAR also carries those same files as resources for the temporary
 * no-installed-stdlib bootstrap path used by compiler tests and standalone compiler invocations.
 * This map is therefore a read-only packaged-source view, not a second source implementation.
 */
val DOTNET_STDLIB_SOURCES: Map<String, String> =
    DOTNET_STDLIB_SOURCE_RESOURCES.associate { resource ->
        resource.fileName to readDotNetStdlibSourceResource(resource.path)
    }

/** Package-relative source paths used to keep direct and fallback FIR file ordering identical. */
val DOTNET_STDLIB_SOURCE_PATHS: Map<String, String> =
    DOTNET_STDLIB_SOURCE_RESOURCES.associate { resource ->
        resource.fileName to resource.path
    }

/** Exact Common source files compiled as the shared module of the temporary target product. */
val DOTNET_STDLIB_COMMON_SOURCE_NAMES: Set<String> =
    DOTNET_STDLIB_SOURCE_RESOURCES
        .filter(DotNetStdlibSourceResource::isCommon)
        .mapTo(linkedSetOf(), DotNetStdlibSourceResource::fileName)

private fun readDotNetStdlibSourceResource(path: String): String {
    val resourcePath = "/$DOTNET_STDLIB_RESOURCE_ROOT/$path"
    return checkNotNull(DotNetStdlibSourceResource::class.java.getResourceAsStream(resourcePath)) {
        "Kotlin/.NET stdlib source resource '$resourcePath' is missing from the compiler distribution"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

private const val DOTNET_STDLIB_RESOURCE_ROOT = "kotlin-dotnet-stdlib"
