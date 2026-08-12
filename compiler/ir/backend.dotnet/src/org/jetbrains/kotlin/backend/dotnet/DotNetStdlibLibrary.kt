package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isOriginallyLocalDeclaration
import org.jetbrains.kotlin.ir.util.isPublishedApi
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.render
import java.io.File

/**
 * The first physical Kotlin/.NET target-stdlib boundary.
 *
 * Like `Kotlin.Runtime`, the current pre-publication artifact uses the unsigned candidate
 * AssemblyVersion 1.0.0.0 consistently across profiles. This is not a published ABI freeze. The
 * bootstrap compiler still rebuilds this assembly beside ordinary executables. The explicit
 * stdlib product mode emits this self-describing assembly from one frontend/IR run; a separate
 * consumer may then import it without injected implementation sources.
 */
internal object DotNetStdlibLibrary {
    const val ASSEMBLY_NAME = DotNetStdlibArtifact.ASSEMBLY_NAME
    const val ASSEMBLY_FILE_NAME = DotNetStdlibArtifact.ASSEMBLY_FILE_NAME
    const val ASSEMBLY_IL_FILE_NAME = "$ASSEMBLY_NAME.il"
    const val ASSEMBLY_VERSION = DotNetStdlibArtifact.ASSEMBLY_VERSION
    const val ASSEMBLY_VERSION_IL = "1:0:0:0"
    const val ARRAY_AS_LIST_IL_NAME = "Kotlin.Collections.ArrayAsList"
    const val ABSTRACT_COLLECTION_IL_NAME = "Kotlin.Collections.AbstractCollection"
    const val ABSTRACT_LIST_IL_NAME = "Kotlin.Collections.AbstractList"
    const val ABSTRACT_MAP_IL_NAME = "Kotlin.Collections.AbstractMap"
    const val ABSTRACT_SET_IL_NAME = "Kotlin.Collections.AbstractSet"
    const val ABSTRACT_MUTABLE_COLLECTION_IL_NAME = "Kotlin.Collections.AbstractMutableCollection"
    const val ABSTRACT_MUTABLE_LIST_IL_NAME = "Kotlin.Collections.AbstractMutableList"
    const val ABSTRACT_MUTABLE_MAP_IL_NAME = "Kotlin.Collections.AbstractMutableMap"
    const val ABSTRACT_MUTABLE_SET_IL_NAME = "Kotlin.Collections.AbstractMutableSet"
    const val ARRAY_LIST_IL_NAME = "Kotlin.Collections.ArrayList"
    const val HASH_MAP_IL_NAME = "Kotlin.Collections.HashMap"
    const val HASH_SET_IL_NAME = "Kotlin.Collections.HashSet"
    const val ARRAY_ITERATOR_IL_NAME = "Kotlin.Collections.ArrayIterator"
    const val ARRAY_ITERABLE_IL_NAME = "Kotlin.Collections.ArrayIterable"
    const val ERASED_ARRAY_ITERATOR_IL_NAME = "Kotlin.Collections.ErasedArrayIterator"
    const val ERASED_ARRAY_ITERABLE_IL_NAME = "Kotlin.Collections.ErasedArrayIterable"
    const val EMPTY_ITERATOR_IL_NAME = "Kotlin.Collections.EmptyIterator"
    const val EMPTY_LIST_IL_NAME = "Kotlin.Collections.EmptyList"
    const val RANDOM_ACCESS_IL_NAME = "Kotlin.Collections.RandomAccess"
    const val SERIALIZABLE_IL_NAME = "Kotlin.Io.Serializable"
    const val READ_AFTER_EOF_EXCEPTION_IL_NAME = "Kotlin.Io.ReadAfterEOFException"
    const val COLLECTIONS_FACADE_IL_NAME = "Kotlin.Collections.CollectionsKt"
    const val COMPARISONS_FACADE_IL_NAME = "Kotlin.Comparisons.ComparisonsKt"
    const val MAPS_FACADE_IL_NAME = "Kotlin.Collections.MapsKt"
    const val SETS_FACADE_IL_NAME = "Kotlin.Collections.SetsKt"
    const val RANGES_FACADE_IL_NAME = "Kotlin.Ranges.RangesKt"
    const val TUPLES_FACADE_IL_NAME = "Kotlin.TuplesKt"
    const val TEXT_FACADE_IL_NAME = "Kotlin.Text.StringsKt"
    const val STANDARD_FACADE_IL_NAME = "Kotlin.StandardKt"
    const val LIBRARY_FACADE_IL_NAME = "Kotlin.LibraryKt"
    const val CONTRACTS_FACADE_IL_NAME = "Kotlin.Contracts.ContractBuilderKt"
    const val RESULT_FACADE_IL_NAME = "Kotlin.ResultKt"
    const val CONTINUATION_FACADE_IL_NAME = "Kotlin.Coroutines.ContinuationKt"
    const val COROUTINE_CONTEXT_FACADE_IL_NAME = "Kotlin.Coroutines.CoroutineContextImplKt"
    const val COROUTINE_INTRINSICS_FACADE_IL_NAME = "Kotlin.Coroutines.Intrinsics.IntrinsicsKt"
    const val DOTNET_COROUTINE_INTRINSICS_FACADE_IL_NAME =
        "Kotlin.Coroutines.Intrinsics.DotNetCoroutinesIntrinsicsKt"
    const val DOTNET_COROUTINE_COMPILER_INTRINSICS_FACADE_IL_NAME =
        "Kotlin.Dotnet.Internal.DotNetCoroutineCompilerIntrinsicsKt"
    const val IO_FACADE_IL_NAME = "Kotlin.Io.ConsoleKt"
    const val ENUM_ENTRIES_FACADE_IL_NAME = "Kotlin.Enums.EnumEntriesKt"
    const val LATEINIT_FACADE_IL_NAME = "Kotlin.LateinitKt"
    const val THROW_HELPERS_FACADE_IL_NAME = "Kotlin.Internal.ThrowHelpersKt"
    const val THROW_NO_WHEN_BRANCH_MATCHED_FACADE_IL_NAME =
        "kotlin.internal.DotNetThrowNoWhenBranchMatchedExceptionKt"
    const val SERIALIZATION_UTIL_FACADE_IL_NAME = "Kotlin.Internal.SerializationUtilKt"
    const val PROGRESSION_UTIL_FACADE_IL_NAME = "Kotlin.Internal.ProgressionUtilKt"
    const val EXCEPTIONS_FACADE_IL_NAME = "Kotlin.DotNetExceptionsKt"
    const val KCLASSES_FACADE_IL_NAME = "Kotlin.Reflection.KClasses"
    const val KPARAMETERS_FACADE_IL_NAME = "Kotlin.Reflection.KParameters"
    const val KTYPE_INTRINSICS_FACADE_IL_NAME = "Kotlin.Reflection.TypeOfIntrinsics"
    const val MEMBER_REFLECTION_CATALOG_FACADE_IL_NAME =
        "Kotlin.Reflection.DotNetMemberReflectionCatalog"
    const val MEMBER_REFLECTION_CATALOG_FUNCTION_FQ_NAME =
        "kotlin.reflect.dotNetGetStdlibMembersV1"
    const val MEMBER_REFLECTION_CATALOG_FUNCTION_NAME = "dotNetGetStdlibMembersV1"
    const val ARRAY_ITERATOR_FACTORY_NAME = "dotNetArrayIterator"
    const val ARRAY_ITERABLE_FACTORY_NAME = "dotNetArrayIterable"
    const val ERASED_ARRAY_ITERATOR_FACTORY_NAME = "dotNetErasedArrayIterator"
    const val ERASED_ARRAY_ITERABLE_FACTORY_NAME = "dotNetErasedArrayIterable"

    private val implementationClassIlNames = mapOf(
        "kotlin.reflect.KTypeParameter" to "Kotlin.Reflection.KTypeParameter",
        "kotlin.reflect.KTypeProjection" to "Kotlin.Reflection.KTypeProjection",
        "kotlin.reflect.KVariance" to "Kotlin.Reflection.KVariance",
        "kotlin.reflect.KTypeImpl" to "Kotlin.Reflection.KTypeImpl",
        "kotlin.reflect.KTypeParameterBase" to "Kotlin.Reflection.KTypeParameterBase",
        "kotlin.reflect.DotNetKTypeParameter" to "Kotlin.Reflection.DotNetKTypeParameter",
        "kotlin.reflect.KParameter" to "Kotlin.Reflection.KParameter",
        "kotlin.reflect.KParameter.Kind" to "Kotlin.Reflection.KParameter/Kind",
        "kotlin.reflect.DotNetKParameter" to "Kotlin.Reflection.DotNetKParameter",
        "kotlin.Pair" to "Kotlin.Pair",
        "kotlin.Triple" to "Kotlin.Triple",
        "kotlin.enums.EnumEntries" to "Kotlin.Enums.EnumEntries",
        "kotlin.enums.EnumEntriesList" to "Kotlin.Enums.EnumEntriesList",
        "kotlin.enums.EnumEntriesSerializationProxy" to "Kotlin.Enums.EnumEntriesSerializationProxy",
        "kotlin.collections.ArrayAsList" to ARRAY_AS_LIST_IL_NAME,
        "kotlin.collections.AbstractCollection" to ABSTRACT_COLLECTION_IL_NAME,
        "kotlin.collections.AbstractList" to ABSTRACT_LIST_IL_NAME,
        "kotlin.collections.AbstractMap" to ABSTRACT_MAP_IL_NAME,
        "kotlin.collections.AbstractSet" to ABSTRACT_SET_IL_NAME,
        "kotlin.collections.AbstractMutableCollection" to ABSTRACT_MUTABLE_COLLECTION_IL_NAME,
        "kotlin.collections.AbstractMutableList" to ABSTRACT_MUTABLE_LIST_IL_NAME,
        "kotlin.collections.AbstractMutableMap" to ABSTRACT_MUTABLE_MAP_IL_NAME,
        "kotlin.collections.AbstractMutableSet" to ABSTRACT_MUTABLE_SET_IL_NAME,
        "kotlin.collections.ArrayList" to ARRAY_LIST_IL_NAME,
        "kotlin.collections.HashMap" to HASH_MAP_IL_NAME,
        "kotlin.collections.HashSet" to HASH_SET_IL_NAME,
        "kotlin.collections.HashMapKeys" to "Kotlin.Collections.HashMapKeys",
        "kotlin.collections.HashMapValues" to "Kotlin.Collections.HashMapValues",
        "kotlin.collections.HashMapEntrySetBase" to "Kotlin.Collections.HashMapEntrySetBase",
        "kotlin.collections.HashMapEntrySet" to "Kotlin.Collections.HashMapEntrySet",
        "kotlin.collections.EmptyMap" to "Kotlin.Collections.EmptyMap",
        "kotlin.collections.EmptySet" to "Kotlin.Collections.EmptySet",
        "kotlin.collections.DetachedMapEntry" to "Kotlin.Collections.DetachedMapEntry",
        "kotlin.collections.ArrayIterator" to ARRAY_ITERATOR_IL_NAME,
        "kotlin.collections.BooleanArrayIterator" to "Kotlin.Collections.BooleanArrayIterator",
        "kotlin.collections.ByteArrayIterator" to "Kotlin.Collections.ByteArrayIterator",
        "kotlin.collections.ShortArrayIterator" to "Kotlin.Collections.ShortArrayIterator",
        "kotlin.collections.IntArrayIterator" to "Kotlin.Collections.IntArrayIterator",
        "kotlin.collections.LongArrayIterator" to "Kotlin.Collections.LongArrayIterator",
        "kotlin.collections.FloatArrayIterator" to "Kotlin.Collections.FloatArrayIterator",
        "kotlin.collections.DoubleArrayIterator" to "Kotlin.Collections.DoubleArrayIterator",
        "kotlin.collections.CharArrayIterator" to "Kotlin.Collections.CharArrayIterator",
        "kotlin.collections.ArrayIterable" to ARRAY_ITERABLE_IL_NAME,
        "kotlin.collections.ErasedArrayIterator" to ERASED_ARRAY_ITERATOR_IL_NAME,
        "kotlin.collections.ErasedArrayIterable" to ERASED_ARRAY_ITERABLE_IL_NAME,
        "kotlin.collections.EmptyIterator" to EMPTY_ITERATOR_IL_NAME,
        "kotlin.collections.EmptyList" to EMPTY_LIST_IL_NAME,
        "kotlin.collections.IndexedValue" to "Kotlin.Collections.IndexedValue",
        "kotlin.collections.IndexingIterable" to "Kotlin.Collections.IndexingIterable",
        "kotlin.collections.IndexingIterator" to "Kotlin.Collections.IndexingIterator",
        "kotlin.collections.ByteIterator" to "Kotlin.Collections.ByteIterator",
        "kotlin.collections.CharIterator" to "Kotlin.Collections.CharIterator",
        "kotlin.collections.ShortIterator" to "Kotlin.Collections.ShortIterator",
        "kotlin.collections.IntIterator" to "Kotlin.Collections.IntIterator",
        "kotlin.collections.LongIterator" to "Kotlin.Collections.LongIterator",
        "kotlin.collections.FloatIterator" to "Kotlin.Collections.FloatIterator",
        "kotlin.collections.DoubleIterator" to "Kotlin.Collections.DoubleIterator",
        "kotlin.collections.BooleanIterator" to "Kotlin.Collections.BooleanIterator",
        "kotlin.collections.RandomAccess" to RANDOM_ACCESS_IL_NAME,
        "kotlin.ranges.ClosedRange" to "Kotlin.Ranges.ClosedRange",
        "kotlin.ranges.OpenEndRange" to "Kotlin.Ranges.OpenEndRange",
        "kotlin.ranges.ClosedFloatingPointRange" to "Kotlin.Ranges.ClosedFloatingPointRange",
        "kotlin.ranges.ComparableRange" to "Kotlin.Ranges.ComparableRange",
        "kotlin.ranges.ComparableOpenEndRange" to "Kotlin.Ranges.ComparableOpenEndRange",
        "kotlin.ranges.ClosedDoubleRange" to "Kotlin.Ranges.ClosedDoubleRange",
        "kotlin.ranges.OpenEndDoubleRange" to "Kotlin.Ranges.OpenEndDoubleRange",
        "kotlin.ranges.ClosedFloatRange" to "Kotlin.Ranges.ClosedFloatRange",
        "kotlin.ranges.OpenEndFloatRange" to "Kotlin.Ranges.OpenEndFloatRange",
        "kotlin.ranges.CharProgression" to "Kotlin.Ranges.CharProgression",
        "kotlin.ranges.IntProgression" to "Kotlin.Ranges.IntProgression",
        "kotlin.ranges.LongProgression" to "Kotlin.Ranges.LongProgression",
        "kotlin.ranges.CharProgressionIterator" to "Kotlin.Ranges.CharProgressionIterator",
        "kotlin.ranges.IntProgressionIterator" to "Kotlin.Ranges.IntProgressionIterator",
        "kotlin.ranges.LongProgressionIterator" to "Kotlin.Ranges.LongProgressionIterator",
        "kotlin.ranges.CharRange" to "Kotlin.Ranges.CharRange",
        "kotlin.ranges.IntRange" to "Kotlin.Ranges.IntRange",
        "kotlin.ranges.LongRange" to "Kotlin.Ranges.LongRange",
        "kotlin.io.Serializable" to SERIALIZABLE_IL_NAME,
        "kotlin.io.ReadAfterEOFException" to READ_AFTER_EOF_EXCEPTION_IL_NAME,
        "kotlin.text.Appendable" to "Kotlin.Text.Appendable",
        "kotlin.text.StringBuilder" to "Kotlin.Text.StringBuilder",
        "kotlin.contracts.ExperimentalContracts" to "Kotlin.Contracts.ExperimentalContracts",
        "kotlin.contracts.ExperimentalExtendedContracts" to "Kotlin.Contracts.ExperimentalExtendedContracts",
        "kotlin.contracts.ContractBuilder" to "Kotlin.Contracts.ContractBuilder",
        "kotlin.contracts.InvocationKind" to "Kotlin.Contracts.InvocationKind",
        "kotlin.contracts.Effect" to "Kotlin.Contracts.Effect",
        "kotlin.contracts.ConditionalEffect" to "Kotlin.Contracts.ConditionalEffect",
        "kotlin.contracts.SimpleEffect" to "Kotlin.Contracts.SimpleEffect",
        "kotlin.contracts.Returns" to "Kotlin.Contracts.Returns",
        "kotlin.contracts.ReturnsNotNull" to "Kotlin.Contracts.ReturnsNotNull",
        "kotlin.contracts.CallsInPlace" to "Kotlin.Contracts.CallsInPlace",
        "kotlin.contracts.HoldsIn" to "Kotlin.Contracts.HoldsIn",
        "kotlin.experimental.ExperimentalTypeInference" to "Kotlin.Experimental.ExperimentalTypeInference",
        "kotlin.OverloadResolutionByLambdaReturnType" to "Kotlin.OverloadResolutionByLambdaReturnType",
        "kotlin.NotImplementedError" to "Kotlin.NotImplementedError",
        "kotlin.SuppressedExceptionList" to "Kotlin.SuppressedExceptionList",
        "kotlin.SuppressedExceptionIterator" to "Kotlin.SuppressedExceptionIterator",
        "kotlin.internal.SharedVariableBox" to "Kotlin.Internal.SharedVariableBox",
        "kotlin.internal.SharedVariableBoxBoolean" to "Kotlin.Internal.SharedVariableBoxBoolean",
        "kotlin.internal.SharedVariableBoxByte" to "Kotlin.Internal.SharedVariableBoxByte",
        "kotlin.internal.SharedVariableBoxShort" to "Kotlin.Internal.SharedVariableBoxShort",
        "kotlin.internal.SharedVariableBoxInt" to "Kotlin.Internal.SharedVariableBoxInt",
        "kotlin.internal.SharedVariableBoxLong" to "Kotlin.Internal.SharedVariableBoxLong",
        "kotlin.internal.SharedVariableBoxFloat" to "Kotlin.Internal.SharedVariableBoxFloat",
        "kotlin.internal.SharedVariableBoxDouble" to "Kotlin.Internal.SharedVariableBoxDouble",
        "kotlin.internal.SharedVariableBoxChar" to "Kotlin.Internal.SharedVariableBoxChar",
        "kotlin.internal.SyntheticConstructorMarker" to "Kotlin.Internal.SyntheticConstructorMarker",
        "kotlin.Result" to "Kotlin.Result",
        "kotlin.Comparator" to "Kotlin.Comparator",
        "kotlin.coroutines.Continuation" to "Kotlin.Coroutines.Continuation",
        "kotlin.coroutines.RestrictsSuspension" to "Kotlin.Coroutines.RestrictsSuspension",
        "kotlin.coroutines.CoroutineContext" to "Kotlin.Coroutines.CoroutineContext",
        "kotlin.coroutines.ContinuationInterceptor" to "Kotlin.Coroutines.ContinuationInterceptor",
        "kotlin.coroutines.AbstractCoroutineContextElement" to "Kotlin.Coroutines.AbstractCoroutineContextElement",
        "kotlin.coroutines.AbstractCoroutineContextKey" to "Kotlin.Coroutines.AbstractCoroutineContextKey",
        "kotlin.coroutines.EmptyCoroutineContext" to "Kotlin.Coroutines.EmptyCoroutineContext",
        "kotlin.coroutines.CombinedContext" to "Kotlin.Coroutines.CombinedContext",
        "kotlin.coroutines.SafeContinuation" to "Kotlin.Coroutines.SafeContinuation",
        "kotlin.coroutines.DotNetCoroutineImpl" to "Kotlin.Coroutines.DotNetCoroutineImpl",
        "kotlin.coroutines.intrinsics.CoroutineSingletons" to "Kotlin.Coroutines.Intrinsics.CoroutineSingletons",
    )
    private val implementationFunctionFacadeIlNames = mapOf(
        "kotlin.enumValues" to LIBRARY_FACADE_IL_NAME,
        "kotlin.enumValueOf" to LIBRARY_FACADE_IL_NAME,
        "kotlin.dotNetEnumValuesIntrinsic" to LIBRARY_FACADE_IL_NAME,
        "kotlin.dotNetEnumValueOfIntrinsic" to LIBRARY_FACADE_IL_NAME,
        "kotlin.enums.enumEntries" to ENUM_ENTRIES_FACADE_IL_NAME,
        "kotlin.enums.enumEntriesIntrinsic" to ENUM_ENTRIES_FACADE_IL_NAME,
        "kotlin.collections.all" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.any" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.asIterable" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.average" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.asList" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.asArrayList" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.listOf" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.mutableListOf" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.arrayListOf" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.listOfNotNull" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.buildList" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.buildListInternal" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.isNotEmpty" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.isNullOrEmpty" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.orEmpty" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.ifEmpty" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.arrayOfNulls" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.checkCountOverflow" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.checkIndexOverflow" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.collectionToArray" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.collectionToArrayCommonImpl" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.component1" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.component2" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.component3" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.component4" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.component5" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.contains" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.containsAll" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.count" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.elementAt" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.elementAtOrNull" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.emptyList" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.first" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.find" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.findLast" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.firstNotNullOf" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.firstNotNullOfOrNull" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.firstOrNull" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.fold" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.foldIndexed" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.foldRight" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.foldRightIndexed" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.forEach" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.forEachIndexed" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.getOrNull" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.indexOf" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.indexOfFirst" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.indexOfLast" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.last" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.lastIndexOf" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.lastOrNull" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.joinTo" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.joinToString" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.none" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.onEach" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.onEachIndexed" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.removeAll" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.retainAll" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.filterInPlace" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.objectArray" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.clearObjectRange" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.copyObjectRange" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.objectRangeHashCode" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.objectRangeEquals" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.objectRangeToString" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.reduce" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.reduceIndexed" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.reduceIndexedOrNull" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.reduceOrNull" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.reduceRight" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.reduceRightIndexed" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.reduceRightIndexedOrNull" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.reduceRightOrNull" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.requireNoNulls" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.single" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.singleOrNull" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.sum" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.sumBy" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.sumByDouble" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.sumOf" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.terminateCollectionToArray" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.throwCountOverflow" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.throwIndexOverflow" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.$ARRAY_ITERATOR_FACTORY_NAME" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.$ARRAY_ITERABLE_FACTORY_NAME" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.$ERASED_ARRAY_ITERATOR_FACTORY_NAME" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.$ERASED_ARRAY_ITERABLE_FACTORY_NAME" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.text.append" to TEXT_FACADE_IL_NAME,
        "kotlin.text.appendElement" to TEXT_FACADE_IL_NAME,
        "kotlin.text.appendLine" to TEXT_FACADE_IL_NAME,
        "kotlin.text.appendRange" to TEXT_FACADE_IL_NAME,
        "kotlin.text.clear" to TEXT_FACADE_IL_NAME,
        "kotlin.text.deleteAt" to TEXT_FACADE_IL_NAME,
        "kotlin.text.deleteRange" to TEXT_FACADE_IL_NAME,
        "kotlin.text.insert" to TEXT_FACADE_IL_NAME,
        "kotlin.text.insertRange" to TEXT_FACADE_IL_NAME,
        "kotlin.text.set" to TEXT_FACADE_IL_NAME,
        "kotlin.text.setRange" to TEXT_FACADE_IL_NAME,
        "kotlin.text.toCharArray" to TEXT_FACADE_IL_NAME,
        "kotlin.text.buildString" to TEXT_FACADE_IL_NAME,
        "kotlin.TODO" to STANDARD_FACADE_IL_NAME,
        "kotlin.run" to STANDARD_FACADE_IL_NAME,
        "kotlin.with" to STANDARD_FACADE_IL_NAME,
        "kotlin.apply" to STANDARD_FACADE_IL_NAME,
        "kotlin.also" to STANDARD_FACADE_IL_NAME,
        "kotlin.let" to STANDARD_FACADE_IL_NAME,
        "kotlin.takeIf" to STANDARD_FACADE_IL_NAME,
        "kotlin.takeUnless" to STANDARD_FACADE_IL_NAME,
        "kotlin.check" to STANDARD_FACADE_IL_NAME,
        "kotlin.contracts.contract" to CONTRACTS_FACADE_IL_NAME,
        "kotlin.internal.throwUninitializedPropertyAccessException" to THROW_HELPERS_FACADE_IL_NAME,
        "kotlin.internal.throwUnsupportedOperationException" to THROW_HELPERS_FACADE_IL_NAME,
        "kotlin.internal.staticInitializationFailure" to THROW_HELPERS_FACADE_IL_NAME,
        "kotlin.io.readln" to IO_FACADE_IL_NAME,
        "kotlin.io.readlnOrNull" to IO_FACADE_IL_NAME,
        "kotlin.internal.throwNoWhenBranchMatchedException" to THROW_NO_WHEN_BRANCH_MATCHED_FACADE_IL_NAME,
        "kotlin.internal.throwReadObjectNotSupported" to SERIALIZATION_UTIL_FACADE_IL_NAME,
        "kotlin.internal.wrapAsDeserializationException" to SERIALIZATION_UTIL_FACADE_IL_NAME,
        "kotlin.stackTraceToString" to EXCEPTIONS_FACADE_IL_NAME,
        "kotlin.printStackTrace" to EXCEPTIONS_FACADE_IL_NAME,
        "kotlin.addSuppressed" to EXCEPTIONS_FACADE_IL_NAME,
        "kotlin.reflect.cast" to KCLASSES_FACADE_IL_NAME,
        "kotlin.reflect.safeCast" to KCLASSES_FACADE_IL_NAME,
        "kotlin.createFailure" to RESULT_FACADE_IL_NAME,
        "kotlin.throwOnFailure" to RESULT_FACADE_IL_NAME,
        "kotlin.runCatching" to RESULT_FACADE_IL_NAME,
        "kotlin.getOrThrow" to RESULT_FACADE_IL_NAME,
        "kotlin.getOrElse" to RESULT_FACADE_IL_NAME,
        "kotlin.getOrDefault" to RESULT_FACADE_IL_NAME,
        "kotlin.fold" to RESULT_FACADE_IL_NAME,
        "kotlin.map" to RESULT_FACADE_IL_NAME,
        "kotlin.mapCatching" to RESULT_FACADE_IL_NAME,
        "kotlin.recover" to RESULT_FACADE_IL_NAME,
        "kotlin.recoverCatching" to RESULT_FACADE_IL_NAME,
        "kotlin.onFailure" to RESULT_FACADE_IL_NAME,
        "kotlin.onSuccess" to RESULT_FACADE_IL_NAME,
        "kotlin.coroutines.resume" to CONTINUATION_FACADE_IL_NAME,
        "kotlin.coroutines.resumeWithException" to CONTINUATION_FACADE_IL_NAME,
        "kotlin.coroutines.Continuation" to CONTINUATION_FACADE_IL_NAME,
        "kotlin.coroutines.createCoroutine" to CONTINUATION_FACADE_IL_NAME,
        "kotlin.coroutines.startCoroutine" to CONTINUATION_FACADE_IL_NAME,
        "kotlin.coroutines.suspendCoroutine" to CONTINUATION_FACADE_IL_NAME,
        "kotlin.coroutines.getPolymorphicElement" to COROUTINE_CONTEXT_FACADE_IL_NAME,
        "kotlin.coroutines.minusPolymorphicKey" to COROUTINE_CONTEXT_FACADE_IL_NAME,
        "kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn" to COROUTINE_INTRINSICS_FACADE_IL_NAME,
        "kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn" to
                DOTNET_COROUTINE_INTRINSICS_FACADE_IL_NAME,
        "kotlin.coroutines.intrinsics.createCoroutineUnintercepted" to
                DOTNET_COROUTINE_INTRINSICS_FACADE_IL_NAME,
        "kotlin.coroutines.intrinsics.intercepted" to DOTNET_COROUTINE_INTRINSICS_FACADE_IL_NAME,
        "kotlin.dotnet.internal.getContinuation" to DOTNET_COROUTINE_COMPILER_INTRINSICS_FACADE_IL_NAME,
        "kotlin.dotnet.internal.returnIfSuspended" to DOTNET_COROUTINE_COMPILER_INTRINSICS_FACADE_IL_NAME,
        "kotlin.dotnet.internal.getCoroutineContext" to DOTNET_COROUTINE_COMPILER_INTRINSICS_FACADE_IL_NAME,
        "kotlin.dotnet.internal.suspendCoroutineUninterceptedOrReturnDotNet" to
                DOTNET_COROUTINE_COMPILER_INTRINSICS_FACADE_IL_NAME,
    )
    private val implementationPropertyFacadeIlNames = mapOf(
        "kotlin.code" to STANDARD_FACADE_IL_NAME,
        "kotlin.collections.indices" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.lastIndex" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.suppressedExceptions" to EXCEPTIONS_FACADE_IL_NAME,
        "kotlin.reflect.qualifiedOrSimpleName" to KCLASSES_FACADE_IL_NAME,
        "kotlin.coroutines.coroutineContext" to CONTINUATION_FACADE_IL_NAME,
        "kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED" to COROUTINE_INTRINSICS_FACADE_IL_NAME,
        "kotlin.isInitialized" to LATEINIT_FACADE_IL_NAME,
    )

    fun hasImplementation(module: IrModuleFragment): Boolean =
        module.files.any(::hasImplementation)

    fun hasImplementation(file: IrFile): Boolean =
        file.isDotNetStdlibImplementationSource && file.declarations.any { declaration ->
            when (declaration) {
                is IrClass -> declaration.isDotNetStdlibImplementation
                is IrProperty -> declaration.isDotNetStdlibImplementation
                is IrSimpleFunction -> declaration.isDotNetStdlibImplementation
                else -> false
            }
        }

    fun implementationFileFacadeIlName(file: IrFile): String? =
        implementationSources[file.implementationSourceFileName]
            ?.takeIf { source -> source.packageFqName == file.packageFqName.asString() }
            ?.facadeIlName

    /** Writes IL for the selected profile and assembles the corresponding library PE. */
    fun assembleIn(
        outputDirectory: File,
        ilText: String,
        target: DotNetTarget,
        messageCollector: MessageCollector,
        managedResources: Map<String, ByteArray> = emptyMap(),
    ): File? {
        outputDirectory.mkdirs()
        val ilFile = outputDirectory.resolve(ASSEMBLY_IL_FILE_NAME)
        val output = outputDirectory.resolve(ASSEMBLY_FILE_NAME)
        output.delete()
        ilFile.writeBytes(UTF8_BOM + ilText.toByteArray(Charsets.UTF_8))
        return output.takeIf {
            DotNetIlAssembler.assembleLibrary(
                ilFile,
                output,
                target,
                messageCollector,
                managedResources,
            )
        }
    }

    /** Bootstrap compatibility path while ordinary executable builds still rebuild the stdlib. */
    fun assembleNextTo(
        executableOutput: File,
        ilText: String,
        target: DotNetTarget,
        messageCollector: MessageCollector,
        managedResources: Map<String, ByteArray> = emptyMap(),
    ): File? = assembleIn(
        executableOutput.parentFile ?: File("."),
        ilText,
        target,
        messageCollector,
        managedResources,
    )

    /** Calls the stdlib-owned iterator factory for a vector already on the IL stack. */
    fun arrayIteratorFactoryCallInstruction(
        elementType: DotNetIlValueType,
        assemblyName: String? = ASSEMBLY_NAME,
    ): String = ARRAY_ITERATOR_FACTORY_INFO.renderStdlibCall(ARRAY_ITERATOR_FACTORY_NAME, elementType, assemblyName)

    /** Calls the exact primitive-array iterator factory without exposing the private iterator. */
    fun primitiveArrayIteratorFactoryCallInstruction(
        arrayType: DotNetIlValueType.PrimitiveArray,
        iteratorType: DotNetIlValueType,
        assemblyName: String? = ASSEMBLY_NAME,
    ): String {
        val methodName = "dotNet${arrayType.abi.wrapperSimpleName}Iterator"
        val functionInfo = DotNetIlFunctionInfo(
            owner = DotNetIlClassInfo(COLLECTIONS_FACADE_IL_NAME),
            signature = DotNetIlMethodSignature(
                returnType = DotNetIlReturnType.Value(iteratorType),
                parameterTypes = listOf(arrayType),
            ),
        )
        return functionInfo.renderCallInstruction(
            methodName = methodName,
            ownerToken = "${assemblyName.ilAssemblyQualifier()}${functionInfo.owner.ilTypeRef}",
        )
    }

    /** Calls the stdlib-owned Iterable factory for a vector already on the IL stack. */
    fun arrayIterableFactoryCallInstruction(
        elementType: DotNetIlValueType,
        assemblyName: String? = ASSEMBLY_NAME,
    ): String = ARRAY_ITERABLE_FACTORY_INFO.renderStdlibCall(ARRAY_ITERABLE_FACTORY_NAME, elementType, assemblyName)

    fun erasedArrayIteratorFactoryCallInstruction(
        coreLibraryReference: String,
        assemblyName: String? = ASSEMBLY_NAME,
    ): String =
        "call class [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]${"Kotlin.Collections.Iterator".toIlIdentifier()} " +
                "${assemblyName.ilAssemblyQualifier()}${COLLECTIONS_FACADE_IL_NAME.toIlIdentifier()}::" +
                "${ERASED_ARRAY_ITERATOR_FACTORY_NAME.toIlIdentifier()}(" +
                "class ${coreLibraryReference}System.Array)"

    fun erasedArrayIterableFactoryCallInstruction(
        coreLibraryReference: String,
        assemblyName: String? = ASSEMBLY_NAME,
    ): String =
        "call class [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]${"Kotlin.Collections.Iterable".toIlIdentifier()} " +
                "${assemblyName.ilAssemblyQualifier()}${COLLECTIONS_FACADE_IL_NAME.toIlIdentifier()}::" +
                "${ERASED_ARRAY_ITERABLE_FACTORY_NAME.toIlIdentifier()}(" +
                "class ${coreLibraryReference}System.Array)"

    fun implementationClassIlName(irClass: IrClass): String? {
        if (irClass.fileOrNull?.isDotNetStdlibImplementationSource != true) return null
        return irClass.fqNameWhenAvailable?.asString()?.let(implementationClassIlNames::get)
    }

    /** Public target-stdlib declarations referenced while bootstrap sources remain same-module. */
    fun publicImplementationClassInfoOrNull(
        irClass: IrClass,
        assemblyName: String? = ASSEMBLY_NAME,
    ): DotNetIlClassInfo? {
        if (irClass.visibility != DescriptorVisibilities.PUBLIC && !irClass.isPublishedApi()) return null
        val ilName = implementationClassIlName(irClass) ?: return null
        return DotNetIlClassInfo(
            ilName,
            typeParameterVariances = if (!irClass.isInterface && irClass.typeParameters.isNotEmpty()) {
                emptyList()
            } else {
                irClass.typeParameters.map { it.variance }
            },
            assemblyName = assemblyName,
        )
    }

    /** The single erased owner of a public generic stdlib class in bootstrap USER emission. */
    fun publicGenericImplementationClassInfoOrNull(
        irClass: IrClass,
        assemblyName: String? = ASSEMBLY_NAME,
    ): DotNetGenericClassInfo? {
        if (irClass.isInterface || irClass.typeParameters.isEmpty()) return null
        val ilName = implementationClassIlName(irClass) ?: return null
        return DotNetGenericClassInfo(
            classInfo = DotNetIlClassInfo(
                ilClassName = ilName,
                assemblyName = assemblyName,
            ),
        )
    }

    fun implementationFunctionFacadeIlName(function: IrSimpleFunction): String? {
        val file = function.parent as? IrFile ?: return null
        if (!file.isDotNetStdlibImplementationSource) return null
        // The admitted source shard, not a second per-function list, owns its complete physical
        // implementation closure. This is essential for exact Common projections: private and
        // internal helpers must be emitted beside the public functions which call them. Shards
        // without one facade retain the narrow legacy map below for independently owned members.
        implementationFileFacadeIlName(file)?.let { return it }
        val functionFqName = function.fqNameWhenAvailable?.asString()
        return functionFqName?.let(implementationFunctionFacadeIlNames::get)
            ?: functionFqName
                ?.takeIf { function.origin == IrDeclarationOrigin.FUNCTION_FOR_DEFAULT_PARAMETER }
                ?.removeSuffix("\$default")
                ?.let(implementationFunctionFacadeIlNames::get)
            ?: function.correspondingPropertySymbol?.owner?.let(::implementationPropertyFacadeIlName)
    }

    fun implementationPropertyFacadeIlName(property: IrProperty): String? {
        if ((property.parent as? IrFile)?.isDotNetStdlibImplementationSource != true) return null
        return property.fqNameWhenAvailable?.asString()?.let(implementationPropertyFacadeIlNames::get)
    }

    /** Selects a pinned Common-generator spelling for a bounded erased stdlib overload family. */
    fun implementationPlatformMethodNameOrNull(function: IrSimpleFunction): String? {
        stringBuilderPlatformMethodNameOrNull(function)?.let { return it }
        rangePlatformMethodNameOrNull(function)?.let { return it }
        val functionFqName = function.fqNameWhenAvailable?.asString() ?: return null
        val elementPlatformNames = signedIterableNumericPlatformNames[functionFqName]
        val selectorPlatformNames = signedIterableSelectorSumPlatformNames[functionFqName]
        if (elementPlatformNames == null && selectorPlatformNames == null) return null
        check(elementPlatformNames == null || selectorPlatformNames == null) {
            "Internal .NET backend error: ambiguous stdlib platform-name projection for $functionFqName"
        }
        val logicalName = functionFqName.substringAfterLast('.')
        if (implementationFunctionFacadeIlName(function) == null) return null
        val receiverType = function.parameters
            .singleOrNull { parameter -> parameter.kind == IrParameterKind.ExtensionReceiver }
            ?.type as? IrSimpleType
            ?: dotNetUnsupported(
                "Common Iterable.$logicalName has no single simple extension receiver: " +
                        function.parameters.joinToString { parameter ->
                            "${parameter.kind}:${parameter.type.render()}"
                        }
            )
        val receiverFqName = receiverType.classFqName?.asString()
        if (receiverFqName != "kotlin.collections.Iterable") {
            dotNetUnsupported("Common Iterable.$logicalName has unexpected receiver '${receiverType.render()}'")
        }
        val elementType = (receiverType.arguments.singleOrNull() as? IrTypeProjection)?.type
            ?: dotNetUnsupported(
                "Common Iterable.$logicalName receiver '${receiverType.render()}' has no exact element type"
            )
        if (elementPlatformNames != null) {
            val elementFqName = elementType.classFqName?.asString()
            return elementPlatformNames[elementFqName]
                ?: dotNetUnsupported(
                    "Common Iterable.$logicalName element '${elementType.render()}' has no pinned CLR method name"
                )
        }

        val selectorType = function.parameters
            .singleOrNull { parameter -> parameter.kind == IrParameterKind.Regular }
            ?.type as? IrSimpleType
            ?: dotNetUnsupported(
                "Common Iterable.$logicalName has no single simple selector: " +
                        function.parameters.joinToString { parameter ->
                            "${parameter.kind}:${parameter.type.render()}"
                        }
            )
        if (selectorType.classFqName?.asString() != "kotlin.Function1") {
            dotNetUnsupported(
                "Common Iterable.$logicalName has unexpected selector '${selectorType.render()}'"
            )
        }
        val selectorResultType = (selectorType.arguments.getOrNull(1) as? IrTypeProjection)?.type
            ?: dotNetUnsupported(
                "Common Iterable.$logicalName selector '${selectorType.render()}' has no exact result type"
            )
        if (selectorResultType != function.returnType) {
            dotNetUnsupported(
                "Common Iterable.$logicalName selector result '${selectorResultType.render()}' " +
                        "differs from return '${function.returnType.render()}'"
            )
        }
        val selectorResultFqName = selectorResultType.classFqName?.asString()
        return checkNotNull(selectorPlatformNames)[selectorResultFqName]
            ?: dotNetUnsupported(
                "Common Iterable.$logicalName selector result '${selectorResultType.render()}' " +
                        "has no pinned CLR method name"
            )
    }

    /** Preserves the Common generator's `@JvmName` disambiguation after generic range erasure. */
    private fun rangePlatformMethodNameOrNull(function: IrSimpleFunction): String? {
        if (function.fqNameWhenAvailable?.asString() != "kotlin.ranges.contains") return null
        if (implementationFunctionFacadeIlName(function) != RANGES_FACADE_IL_NAME) return null
        val receiverType = function.parameters
            .singleOrNull { parameter -> parameter.kind == IrParameterKind.ExtensionReceiver }
            ?.type as? IrSimpleType
            ?: return null
        val rangeFqNames = setOf(
            "kotlin.ranges.ClosedRange",
            "kotlin.ranges.OpenEndRange",
        )
        val receiverRangeFqName = receiverType.classFqName?.asString()
            ?: (receiverType.classifier as? IrTypeParameterSymbol)
                ?.owner
                ?.superTypes
                ?.mapNotNull { bound -> bound.classFqName?.asString() }
                ?.singleOrNull { boundFqName -> boundFqName in rangeFqNames }
            ?: return null
        if (receiverRangeFqName !in rangeFqNames) return null
        if (receiverType.classifier is IrTypeParameterSymbol) {
            val elementType = function.parameters
                .singleOrNull { parameter -> parameter.kind == IrParameterKind.Regular }
                ?.type as? IrSimpleType
                ?: dotNetUnsupported(
                    "Common generic range contains has no single simple element parameter"
                )
            val elementParameter = elementType.classifier as? IrTypeParameterSymbol
                ?: dotNetUnsupported(
                    "Common generic range contains element '${elementType.render()}' is not a type parameter"
                )
            val hasComparableBound = elementParameter.owner.superTypes.any { bound ->
                bound.classFqName?.asString() == "kotlin.Comparable"
            }
            val receiverName = when (receiverRangeFqName) {
                "kotlin.ranges.ClosedRange" -> "closedRange"
                "kotlin.ranges.OpenEndRange" -> "openEndRange"
                else -> error("Internal .NET backend error: unrecognized range bound $receiverRangeFqName")
            }
            val elementBoundName = if (hasComparableBound) "Comparable" else "Any"
            return "${receiverName}ContainsNullable$elementBoundName"
        }
        val rangeElementFqName = (receiverType.arguments.singleOrNull() as? IrTypeProjection)
            ?.type
            ?.classFqName
            ?.asString()
            ?: dotNetUnsupported(
                "Common range contains receiver '${receiverType.render()}' has no exact element type"
            )
        return rangeContainsPlatformNames[rangeElementFqName]
            ?: dotNetUnsupported(
                "Common range contains element '${receiverType.render()}' has no pinned CLR method name"
            )
    }

    /**
     * CLR carries classified `CharSequence` as `object`, so the Common StringBuilder `Any?`
     * overloads would otherwise collide with their `CharSequence?` siblings. Keep the interface
     * slot's source spelling and pin only the Kotlin-owned Any overloads to stable C#-spellable
     * physical names. This is an explicit stdlib ABI projection, not collision-order naming.
     */
    private fun stringBuilderPlatformMethodNameOrNull(function: IrSimpleFunction): String? {
        val regularParameterFqNames = function.parameters
            .filter { parameter -> parameter.kind == IrParameterKind.Regular }
            .map { parameter -> parameter.type.classFqName?.asString() }
        val owner = function.parent as? IrClass
        if (owner?.fqNameWhenAvailable?.asString() == "kotlin.text.StringBuilder") {
            return when {
                function.name.asString() == "append" &&
                        regularParameterFqNames == listOf("kotlin.Any") -> "appendAny"
                function.name.asString() == "insert" &&
                        regularParameterFqNames == listOf("kotlin.Int", "kotlin.Any") -> "insertAny"
                else -> null
            }
        }
        if (
            function.fqNameWhenAvailable?.asString() == "kotlin.text.appendLine" &&
            implementationFunctionFacadeIlName(function) == TEXT_FACADE_IL_NAME &&
            function.parameters.singleOrNull { parameter ->
                parameter.kind == IrParameterKind.ExtensionReceiver
            }?.type?.classFqName?.asString() == "kotlin.text.StringBuilder" &&
            regularParameterFqNames == listOf("kotlin.Any")
        ) {
            return "appendLineAny"
        }
        return null
    }

    /** Crosses from a bootstrap user assembly to an ordinary function emitted in Kotlin.Stdlib. */
    fun implementationFunctionInfoOrNull(
        function: IrSimpleFunction,
        typeMapper: DotNetIlTypeMapper,
        assemblyName: String? = ASSEMBLY_NAME,
    ): DotNetIlFunctionInfo? {
        val containingClass = function.parent as? IrClass
        if (containingClass != null && typeMapper.isErasedGenericInterface(containingClass)) {
            val owner = typeMapper.genericInterfaceInfoOrNull(containingClass)?.canonicalClassInfo
                ?: publicImplementationClassInfoOrNull(containingClass, assemblyName)
                ?: return null
            return DotNetIlFunctionInfo(
                owner = owner,
                signature = function.dotNetSignature(typeMapper.canonicalGenericInterfaceSignatureView()),
                physicalMethodName = function.dotNetGenericInterfaceCanonicalMethodName(),
            )
        }
        val genericClassInfo = containingClass?.let(typeMapper::genericClassInfoOrNull)
        val owner = genericClassInfo?.classInfo
            ?: containingClass?.let { irClass -> publicImplementationClassInfoOrNull(irClass, assemblyName) }
            ?: implementationFunctionFacadeIlName(function)?.let { facadeName ->
                DotNetIlClassInfo(facadeName, assemblyName = assemblyName)
            }
            ?: return null
        return DotNetIlFunctionInfo(
            owner = owner,
            signature = function.dotNetSignature(typeMapper),
            physicalMethodName = function.dotNetAbiMethodName(
                isErasedGenericClass = typeMapper::isErasedGenericClass,
            ),
        )
    }

    /** Calls an open generic `Iterable<T>/List<T> -> T` stdlib method at its exact element type. */
    fun collectionElementFunctionCallInstruction(
        functionName: String,
        receiverType: DotNetIlValueType,
        elementType: DotNetIlValueType,
        assemblyName: String? = ASSEMBLY_NAME,
    ): String {
        val functionInfo = when (receiverType) {
            DotNetRuntimeTypes.iterableType -> ITERABLE_ELEMENT_FUNCTION_INFO
            DotNetRuntimeTypes.listType -> LIST_ELEMENT_FUNCTION_INFO
            else -> error("Internal .NET backend error: unsupported stdlib element receiver $receiverType")
        }
        return functionInfo.renderCallInstruction(
            methodName = functionName,
            ownerToken = "${assemblyName.ilAssemblyQualifier()}${functionInfo.owner.ilTypeRef}",
            methodInstantiation = listOf(elementType),
        )
    }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val ITERABLE_ELEMENT_FUNCTION_INFO = DotNetIlFunctionInfo(
        owner = DotNetIlClassInfo(COLLECTIONS_FACADE_IL_NAME),
        signature = DotNetIlMethodSignature(
            returnType = DotNetIlReturnType.Value(
                DotNetIlValueType.TypeParameter(index = 0, isMethodParameter = true),
            ),
            parameterTypes = listOf(DotNetRuntimeTypes.iterableType),
        ),
    )
    private val LIST_ELEMENT_FUNCTION_INFO = DotNetIlFunctionInfo(
        owner = DotNetIlClassInfo(COLLECTIONS_FACADE_IL_NAME),
        signature = DotNetIlMethodSignature(
            returnType = DotNetIlReturnType.Value(
                DotNetIlValueType.TypeParameter(index = 0, isMethodParameter = true),
            ),
            parameterTypes = listOf(DotNetRuntimeTypes.listType),
        ),
    )
    private val ARRAY_FACTORY_PARAMETER_TYPE = DotNetIlValueType.TypeParameter(
        index = 0,
        isMethodParameter = true,
    )
    private val ARRAY_ITERATOR_FACTORY_INFO = arrayFactoryInfo(DotNetRuntimeTypes.iteratorType)
    private val ARRAY_ITERABLE_FACTORY_INFO = arrayFactoryInfo(DotNetRuntimeTypes.iterableType)
    private val signedIterableNumericPlatformNames = mapOf(
        "kotlin.collections.sum" to mapOf(
            "kotlin.Byte" to "sumOfByte",
            "kotlin.Short" to "sumOfShort",
            "kotlin.Int" to "sumOfInt",
            "kotlin.Long" to "sumOfLong",
            "kotlin.Float" to "sumOfFloat",
            "kotlin.Double" to "sumOfDouble",
        ),
        "kotlin.collections.average" to mapOf(
            "kotlin.Byte" to "averageOfByte",
            "kotlin.Short" to "averageOfShort",
            "kotlin.Int" to "averageOfInt",
            "kotlin.Long" to "averageOfLong",
            "kotlin.Float" to "averageOfFloat",
            "kotlin.Double" to "averageOfDouble",
        ),
    )
    private val signedIterableSelectorSumPlatformNames = mapOf(
        "kotlin.collections.sumOf" to mapOf(
            "kotlin.Double" to "sumOfDouble",
            "kotlin.Int" to "sumOfInt",
            "kotlin.Long" to "sumOfLong",
        ),
    )
    private val rangeContainsPlatformNames = mapOf(
        "kotlin.Byte" to "byteRangeContains",
        "kotlin.Short" to "shortRangeContains",
        "kotlin.Int" to "intRangeContains",
        "kotlin.Long" to "longRangeContains",
        "kotlin.Float" to "floatRangeContains",
        "kotlin.Double" to "doubleRangeContains",
    )

    private fun arrayFactoryInfo(returnType: DotNetIlValueType): DotNetIlFunctionInfo =
        DotNetIlFunctionInfo(
            owner = DotNetIlClassInfo(COLLECTIONS_FACADE_IL_NAME),
            signature = DotNetIlMethodSignature(
                returnType = DotNetIlReturnType.Value(returnType),
                parameterTypes = listOf(DotNetIlValueType.GenericArray(ARRAY_FACTORY_PARAMETER_TYPE)),
            ),
        )

    private fun DotNetIlFunctionInfo.renderStdlibCall(
        methodName: String,
        elementType: DotNetIlValueType,
        assemblyName: String?,
    ): String = renderCallInstruction(
        methodName = methodName,
        ownerToken = "${assemblyName.ilAssemblyQualifier()}${owner.ilTypeRef}",
        methodInstantiation = listOf(elementType),
    )

    private fun String?.ilAssemblyQualifier(): String = this?.let { "[$it]" }.orEmpty()

    private data class ImplementationSource(
        val packageFqName: String,
        val facadeIlName: String? = null,
    )

    private val implementationSources = mapOf(
        "_DotNetBootstrapEnumEntries.kt" to ImplementationSource(
            packageFqName = "kotlin.enums",
            facadeIlName = ENUM_ENTRIES_FACADE_IL_NAME,
        ),
        "DotNetEnumEntriesSerializationProxy.kt" to ImplementationSource(packageFqName = "kotlin.enums"),
        "DotNetEnumEntries.kt" to ImplementationSource(
            packageFqName = "kotlin.enums",
            facadeIlName = ENUM_ENTRIES_FACADE_IL_NAME,
        ),
        "DotNetStdlibCollections.kt" to ImplementationSource(
            packageFqName = "kotlin.collections",
            facadeIlName = COLLECTIONS_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapCollections.kt" to ImplementationSource(
            packageFqName = "kotlin.collections",
            facadeIlName = COLLECTIONS_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapComparisons.kt" to ImplementationSource(
            packageFqName = "kotlin.comparisons",
            facadeIlName = COMPARISONS_FACADE_IL_NAME,
        ),
        "Comparisons.kt" to ImplementationSource(
            packageFqName = "kotlin.comparisons",
            facadeIlName = COMPARISONS_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapMaps.kt" to ImplementationSource(
            packageFqName = "kotlin.collections",
            facadeIlName = MAPS_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapSets.kt" to ImplementationSource(
            packageFqName = "kotlin.collections",
            facadeIlName = SETS_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapCollectionFactories.kt" to ImplementationSource(
            packageFqName = "kotlin.collections",
            facadeIlName = COLLECTIONS_FACADE_IL_NAME,
        ),
        "IndexedValue.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "Iterables.kt" to ImplementationSource(
            packageFqName = "kotlin.collections",
            facadeIlName = COLLECTIONS_FACADE_IL_NAME,
        ),
        "Iterators.kt" to ImplementationSource(
            packageFqName = "kotlin.collections",
            facadeIlName = COLLECTIONS_FACADE_IL_NAME,
        ),
        "PrimitiveIterators.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "Range.kt" to ImplementationSource(packageFqName = "kotlin.ranges"),
        "Ranges.kt" to ImplementationSource(
            packageFqName = "kotlin.ranges",
            facadeIlName = RANGES_FACADE_IL_NAME,
        ),
        "Progressions.kt" to ImplementationSource(packageFqName = "kotlin.ranges"),
        "ProgressionIterators.kt" to ImplementationSource(packageFqName = "kotlin.ranges"),
        "PrimitiveRanges.kt" to ImplementationSource(packageFqName = "kotlin.ranges"),
        "_DotNetBootstrapRanges.kt" to ImplementationSource(
            packageFqName = "kotlin.ranges",
            facadeIlName = RANGES_FACADE_IL_NAME,
        ),
        "AbstractCollection.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "AbstractMap.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "AbstractSet.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "AbstractList.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "AbstractMutableCollection.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "AbstractMutableMap.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "AbstractMutableSet.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "AbstractMutableList.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "ArrayList.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "HashMap.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "HashSet.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "LinkedHashMap.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "LinkedHashSet.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "DotNetAbstractMutableCollection.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "DotNetAbstractMutableMap.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "DotNetAbstractMutableSet.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "DotNetAbstractMutableList.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "DotNetArrayList.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "DotNetHashMap.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "DotNetHashSet.kt" to ImplementationSource(packageFqName = "kotlin.collections"),
        "_DotNetBootstrapMapsActuals.kt" to ImplementationSource(
            packageFqName = "kotlin.collections",
            facadeIlName = MAPS_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapSetsActuals.kt" to ImplementationSource(
            packageFqName = "kotlin.collections",
            facadeIlName = SETS_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapMutableCollections.kt" to ImplementationSource(
            packageFqName = "kotlin.collections",
            facadeIlName = COLLECTIONS_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapAppendable.kt" to ImplementationSource(
            packageFqName = "kotlin.text",
            facadeIlName = TEXT_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapStringBuilder.kt" to ImplementationSource(
            packageFqName = "kotlin.text",
            facadeIlName = TEXT_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapStrings.kt" to ImplementationSource(
            packageFqName = "kotlin.text",
            facadeIlName = TEXT_FACADE_IL_NAME,
        ),
        "DotNetStringBuilder.kt" to ImplementationSource(
            packageFqName = "kotlin.text",
            facadeIlName = TEXT_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapKotlin.kt" to ImplementationSource(
            packageFqName = "kotlin",
            facadeIlName = STANDARD_FACADE_IL_NAME,
        ),
        // The Common Char.code getter and the .NET minOf actual share Kotlin.StandardKt. FIR
        // actualization may retain either a Common expect or this .NET actual as owner, so both
        // source names must designate that facade and neither may leak into the following user
        // assembly.
        "DotNetStdlibKotlin.kt" to ImplementationSource(
            packageFqName = "kotlin",
            facadeIlName = STANDARD_FACADE_IL_NAME,
        ),
        "DotNetComparator.kt" to ImplementationSource(packageFqName = "kotlin"),
        "Comparator.kt" to ImplementationSource(packageFqName = "kotlin"),
        "DotNetLibrary.kt" to ImplementationSource(
            packageFqName = "kotlin",
            facadeIlName = LIBRARY_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapScalarBounds.kt" to ImplementationSource(
            packageFqName = "kotlin",
            facadeIlName = STANDARD_FACADE_IL_NAME,
        ),
        "Tuples.kt" to ImplementationSource(
            packageFqName = "kotlin",
            facadeIlName = TUPLES_FACADE_IL_NAME,
        ),
        "HashCode.kt" to ImplementationSource(
            packageFqName = "kotlin",
            facadeIlName = STANDARD_FACADE_IL_NAME,
        ),
        "Result.kt" to ImplementationSource(
            packageFqName = "kotlin",
            facadeIlName = RESULT_FACADE_IL_NAME,
        ),
        "Continuation.kt" to ImplementationSource(
            packageFqName = "kotlin.coroutines",
            facadeIlName = CONTINUATION_FACADE_IL_NAME,
        ),
        "ContinuationInterceptor.kt" to ImplementationSource(packageFqName = "kotlin.coroutines"),
        "CoroutineContext.kt" to ImplementationSource(packageFqName = "kotlin.coroutines"),
        "CoroutineContextImpl.kt" to ImplementationSource(
            packageFqName = "kotlin.coroutines",
            facadeIlName = COROUTINE_CONTEXT_FACADE_IL_NAME,
        ),
        "Intrinsics.kt" to ImplementationSource(
            packageFqName = "kotlin.coroutines.intrinsics",
            facadeIlName = COROUTINE_INTRINSICS_FACADE_IL_NAME,
        ),
        "DotNetCoroutineImpl.kt" to ImplementationSource(packageFqName = "kotlin.coroutines"),
        "DotNetSafeContinuation.kt" to ImplementationSource(packageFqName = "kotlin.coroutines"),
        "DotNetCoroutinesIntrinsics.kt" to ImplementationSource(
            packageFqName = "kotlin.coroutines.intrinsics",
            facadeIlName = DOTNET_COROUTINE_INTRINSICS_FACADE_IL_NAME,
        ),
        "DotNetCoroutineCompilerIntrinsics.kt" to ImplementationSource(
            packageFqName = "kotlin.dotnet.internal",
            facadeIlName = DOTNET_COROUTINE_COMPILER_INTRINSICS_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapExperimentalTypeInference.kt" to ImplementationSource(
            packageFqName = "kotlin.experimental",
        ),
        "_DotNetBootstrapOverloadResolutionByLambdaReturnType.kt" to ImplementationSource(
            packageFqName = "kotlin",
        ),
        "ContractBuilder.kt" to ImplementationSource(
            packageFqName = "kotlin.contracts",
            facadeIlName = CONTRACTS_FACADE_IL_NAME,
        ),
        "Effect.kt" to ImplementationSource(packageFqName = "kotlin.contracts"),
        "_DotNetBootstrapPreconditions.kt" to ImplementationSource(
            packageFqName = "kotlin",
            facadeIlName = STANDARD_FACADE_IL_NAME,
        ),
        "DotNetStdlibIo.kt" to ImplementationSource(
            packageFqName = "kotlin.io",
            facadeIlName = IO_FACADE_IL_NAME,
        ),
        // Like ExceptionsH.kt, FIR actualization retains the Common expect declarations as the
        // canonical IR owners while attaching the .NET bodies.
        "ioH.kt" to ImplementationSource(
            packageFqName = "kotlin.io",
            facadeIlName = IO_FACADE_IL_NAME,
        ),
        "DotNetExceptions.kt" to ImplementationSource(
            packageFqName = "kotlin",
            facadeIlName = EXCEPTIONS_FACADE_IL_NAME,
        ),
        // FIR actualization keeps the Common expect declaration as the canonical IR owner while
        // attaching the .NET actual body. It is therefore this filename that the stdlib emitter
        // sees for the four public Throwable operations.
        "ExceptionsH.kt" to ImplementationSource(
            packageFqName = "kotlin",
            facadeIlName = EXCEPTIONS_FACADE_IL_NAME,
        ),
        "DotNetThrowNoWhenBranchMatchedException.kt" to ImplementationSource(
            packageFqName = "kotlin.internal",
            facadeIlName = THROW_NO_WHEN_BRANCH_MATCHED_FACADE_IL_NAME,
        ),
        "DotNetSerializationUtil.kt" to ImplementationSource(
            packageFqName = "kotlin.internal",
            facadeIlName = SERIALIZATION_UTIL_FACADE_IL_NAME,
        ),
        "progressionUtil.kt" to ImplementationSource(
            packageFqName = "kotlin.internal",
            facadeIlName = PROGRESSION_UTIL_FACADE_IL_NAME,
        ),
        "SharedVariableBox.kt" to ImplementationSource(packageFqName = "kotlin.internal"),
        "SyntheticConstructorMarker.kt" to ImplementationSource(packageFqName = "kotlin.internal"),
        "ThrowHelpers.kt" to ImplementationSource(
            packageFqName = "kotlin.internal",
            facadeIlName = THROW_HELPERS_FACADE_IL_NAME,
        ),
        "Lateinit.kt" to ImplementationSource(
            packageFqName = "kotlin",
            facadeIlName = LATEINIT_FACADE_IL_NAME,
        ),
        "KClasses.kt" to ImplementationSource(
            packageFqName = "kotlin.reflect",
            facadeIlName = KCLASSES_FACADE_IL_NAME,
        ),
        "DotNetKClasses.kt" to ImplementationSource(
            packageFqName = "kotlin.reflect",
            facadeIlName = KCLASSES_FACADE_IL_NAME,
        ),
        "KTypeParameter.kt" to ImplementationSource(packageFqName = "kotlin.reflect"),
        "KTypeProjection.kt" to ImplementationSource(packageFqName = "kotlin.reflect"),
        "KVariance.kt" to ImplementationSource(packageFqName = "kotlin.reflect"),
        "KTypeImpl.kt" to ImplementationSource(packageFqName = "kotlin.reflect"),
        "KTypeParameterBase.kt" to ImplementationSource(packageFqName = "kotlin.reflect"),
        "typeOf.kt" to ImplementationSource(
            packageFqName = "kotlin.reflect",
            facadeIlName = KTYPE_INTRINSICS_FACADE_IL_NAME,
        ),
        "DotNetKTypes.kt" to ImplementationSource(
            packageFqName = "kotlin.reflect",
            facadeIlName = KTYPE_INTRINSICS_FACADE_IL_NAME,
        ),
        "DotNetKParameter.kt" to ImplementationSource(
            packageFqName = "kotlin.reflect",
            facadeIlName = KPARAMETERS_FACADE_IL_NAME,
        ),
        "DotNetMemberReflectionCatalog.kt" to ImplementationSource(
            packageFqName = "kotlin.reflect",
            facadeIlName = MEMBER_REFLECTION_CATALOG_FACADE_IL_NAME,
        ),
        "ReturnValue.kt" to ImplementationSource(packageFqName = "kotlin"),
    )
    private val resolutionOnlySources = mapOf(
        // FIR actualization may retain either declaration as kotlin.Enum's IR owner. Both are
        // logical/KLIB authority; Kotlin.Runtime supplies the one physical erased base.
        "_DotNetBootstrapEnum.kt" to "kotlin",
        "DotNetEnum.kt" to "kotlin",
        "Annotations.kt" to "kotlin.internal",
        "AnnotationsBuiltin.kt" to "kotlin.internal",
        "WasExperimental.kt" to "kotlin",
        "ExperimentalContextParameters.kt" to "kotlin",
        "JvmAnnotationsH.kt" to "kotlin.jvm",
        "Multiplatform.kt" to "kotlin",
        "KClass.kt" to "kotlin.reflect",
        "KDeclarationContainer.kt" to "kotlin.reflect",
        "KCallable.kt" to "kotlin.reflect",
        "KVisibility.kt" to "kotlin.reflect",
        "KFunction.kt" to "kotlin.reflect",
        "KProperty.kt" to "kotlin.reflect",
        "KClassifier.kt" to "kotlin.reflect",
        "DotNetKClass.kt" to "kotlin.reflect",
        "DotNetKAnnotatedElement.kt" to "kotlin.reflect",
        "DotNetKCallable.kt" to "kotlin.reflect",
        "DotNetKFunction.kt" to "kotlin.reflect",
        "DotNetKProperty.kt" to "kotlin.reflect",
        "KType.kt" to "kotlin.reflect",
        "DotNetKType.kt" to "kotlin.reflect",
        "_DotNetBootstrapJsName.kt" to "kotlin.js",
        "DotNetVolatileMarker.kt" to "kotlin.concurrent",
        "CoroutinesH.kt" to "kotlin.coroutines",
        "CoroutinesIntrinsicsH.kt" to "kotlin.coroutines.intrinsics",
    )
    private val resolutionOnlyDeclarations = setOf(
        // Common owns the logical generic declaration and EnumEntriesList implementation. Runtime
        // owns the one physical erased interface because Runtime-owned enum classes expose entries
        // and the artifact dependency must remain Runtime <- Stdlib.
        "kotlin.enums.EnumEntries",
    )

    internal fun isImplementationSource(file: IrFile): Boolean =
        implementationSources[file.implementationSourceFileName]
            ?.packageFqName == file.packageFqName.asString()

    internal fun isResolutionOnlySource(file: IrFile): Boolean =
        resolutionOnlySources[file.implementationSourceFileName] == file.packageFqName.asString()

    internal fun isResolutionOnlyDeclaration(irClass: IrClass): Boolean =
        irClass.fqNameWhenAvailable?.asString() in resolutionOnlyDeclarations
}

private val IrFile.implementationSourceFileName: String
    get() = fileEntry.name.replace('\\', '/').substringAfterLast('/')

/** Temporary same-module source files whose implementations are partitioned into the stdlib. */
internal val IrFile.isDotNetStdlibImplementationSource: Boolean
    get() = DotNetStdlibLibrary.isImplementationSource(this)

/** Target-bootstrap declarations needed for frontend/KLIB resolution but never physical IL. */
internal val IrClass.isDotNetResolutionOnlyStdlibDeclaration: Boolean
    get() = DotNetMappedExceptions.isExceptionStdlibDeclaration(this) ||
            DotNetStdlibLibrary.isResolutionOnlyDeclaration(this) ||
            (parent as? IrFile)?.let(DotNetStdlibLibrary::isResolutionOnlySource) == true

/** Marker for a product or fallback stdlib implementation declaration, never a user class. */
internal val IrClass.isDotNetStdlibImplementation: Boolean
    get() = fileOrNull?.isDotNetStdlibImplementationSource == true

/** Marker for executable top-level stdlib source, distinct from resolution-only external stubs. */
internal val IrSimpleFunction.isDotNetStdlibImplementation: Boolean
    get() = DotNetStdlibLibrary.implementationFunctionFacadeIlName(this) != null

/** Marker for executable top-level stdlib properties, kept explicit as the bootstrap grows. */
internal val IrProperty.isDotNetStdlibImplementation: Boolean
    get() = DotNetStdlibLibrary.implementationPropertyFacadeIlName(this) != null

/** Controls whether an emitter owns user declarations or physical target-stdlib implementations. */
enum class DotNetIlEmissionScope {
    USER,
    STDLIB;

    internal fun owns(irClass: IrClass): Boolean = when (this) {
        USER -> !irClass.isDotNetStdlibImplementation
        STDLIB -> irClass.isDotNetStdlibImplementation
    }

    internal fun owns(function: IrSimpleFunction): Boolean = when (this) {
        USER -> !function.isDotNetStdlibImplementation
        STDLIB -> function.isDotNetStdlibImplementation
    }

    internal fun owns(property: IrProperty): Boolean = when (this) {
        USER -> !property.isDotNetStdlibImplementation
        STDLIB -> property.isDotNetStdlibImplementation
    }
}
