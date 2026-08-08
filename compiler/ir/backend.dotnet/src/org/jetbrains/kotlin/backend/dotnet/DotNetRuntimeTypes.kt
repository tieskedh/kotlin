/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.invokeFun
import org.jetbrains.kotlin.ir.util.isKFunction
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.Variance

/**
 * Kotlin-owned erased runtime identities evaluated by this POC.
 *
 * Common IR speaks in synthetic `kotlin.Function$arity` and `kotlin.reflect.KFunction$arity`
 * classifiers. This registry maps fixed execution arities 0..3 to erased Kotlin-owned CLR
 * interfaces and every supported KFunction arity to one orthogonal, non-generic reflection view.
 * FunctionN uses object-shaped Invoke slots, following the JVM executable descriptor rather than
 * CLR generic variance: Kotlin's logical type arguments remain in IR/metadata, while every legal
 * function-type variance conversion is the same object reference at runtime. It deliberately
 * does not model the JVM's unrelated high-arity `FunctionN` fallback. CLR delegates remain an
 * interop concern and never appear in Kotlin-to-Kotlin signatures.
 * KProperty0/1/2 and their mutable variants use the same erased-identity rule and inherit the
 * matching FunctionN execution view; their Get/Set slots are Kotlin-owned runtime contracts.
 *
 * Kotlin read-only and mutable Iterable, Iterator, Collection, and List families use one
 * declaration-erased interface each.
 * Their object-shaped slots preserve identity across Kotlin projections and value/reference
 * constructions without a second CLR-generic capability ABI. The five currently supported
 * primitive Iterator subclasses still alias the erased Iterator identity until their ordinary
 * stdlib classes are produced. CLR collection interfaces remain explicit interop concerns.
 */
internal object DotNetRuntimeTypes {
    val DEFAULT_CONSTRUCTOR_MARKER_FQ_NAME = FqName("kotlin.runtime.internal.DefaultConstructorMarker")
    val SYNTHETIC_CONSTRUCTOR_MARKER_FQ_NAME = FqName("kotlin.runtime.internal.SyntheticConstructorMarker")
    private val KFUNCTION_DECLARATION_PROPERTIES =
        setOf("isInline", "isExternal", "isOperator", "isInfix", "isSuspend")
    private val KFUNCTION_DECLARATION_GETTERS =
        KFUNCTION_DECLARATION_PROPERTIES.mapTo(linkedSetOf()) { property -> "get_$property" }

    private val unitClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Unit",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
    val unitType = DotNetIlValueType.UserClass(unitClass)

    private val nothingClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Nothing",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
    val nothingType = DotNetIlValueType.UserClass(nothingClass)

    private val enumClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Enum",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
    private val enumGenericClass = DotNetGenericClassInfo(enumClass)
    private val enumCompanionClass = DotNetIlClassInfo(
        ilClassName = "Companion",
        enclosingClass = enumClass,
    )
    private val enumCompanionStaticsClass = DotNetIlClassInfo(
        ilClassName = "<CompanionStatics>",
        enclosingClass = enumClass,
    )

    private val charSequenceClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.CharSequence",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
    val charSequenceImplementationType = DotNetIlValueType.UserClass(charSequenceClass)

    private val functionBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.Function",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private fun runtimeInterface(canonicalName: String): DotNetGenericInterfaceInfo =
        DotNetGenericInterfaceInfo(
            canonicalClassInfo = DotNetIlClassInfo(
                ilClassName = canonicalName,
                assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
            ),
        )

    private val iteratorGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.Iterator")
    private val iteratorBase = iteratorGenericInterfaceInfo.canonicalClassInfo
    val iteratorType = DotNetIlValueType.UserClass(iteratorBase)

    private val listIteratorGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.ListIterator")
    private val listIteratorBase = listIteratorGenericInterfaceInfo.canonicalClassInfo
    private val listIteratorType = DotNetIlValueType.UserClass(listIteratorBase)

    private val mutableIteratorGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.MutableIterator")
    private val mutableIteratorBase = mutableIteratorGenericInterfaceInfo.canonicalClassInfo
    val mutableIteratorType = DotNetIlValueType.UserClass(mutableIteratorBase)

    private val mutableListIteratorGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.MutableListIterator")
    private val mutableListIteratorBase = mutableListIteratorGenericInterfaceInfo.canonicalClassInfo
    val mutableListIteratorType = DotNetIlValueType.UserClass(mutableListIteratorBase)

    private val iterableGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.Iterable")
    private val iterableBase = iterableGenericInterfaceInfo.canonicalClassInfo
    val iterableType = DotNetIlValueType.UserClass(iterableBase)

    private val mutableIterableGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.MutableIterable")
    private val mutableIterableBase = mutableIterableGenericInterfaceInfo.canonicalClassInfo
    val mutableIterableType = DotNetIlValueType.UserClass(mutableIterableBase)

    private val collectionGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.Collection")
    private val collectionBase = collectionGenericInterfaceInfo.canonicalClassInfo
    private val collectionType = DotNetIlValueType.UserClass(collectionBase)

    private val mutableCollectionGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.MutableCollection")
    private val mutableCollectionBase = mutableCollectionGenericInterfaceInfo.canonicalClassInfo
    val mutableCollectionType = DotNetIlValueType.UserClass(mutableCollectionBase)

    private val listGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.List")
    private val listBase = listGenericInterfaceInfo.canonicalClassInfo
    val listType = DotNetIlValueType.UserClass(listBase)

    private val mutableListGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.MutableList")
    private val mutableListBase = mutableListGenericInterfaceInfo.canonicalClassInfo
    val mutableListType = DotNetIlValueType.UserClass(mutableListBase)

    private val setGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.Set")
    private val setBase = setGenericInterfaceInfo.canonicalClassInfo
    val setType = DotNetIlValueType.UserClass(setBase)

    private val mutableSetGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.MutableSet")
    private val mutableSetBase = mutableSetGenericInterfaceInfo.canonicalClassInfo
    val mutableSetType = DotNetIlValueType.UserClass(mutableSetBase)

    private val mapGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.Map")
    private val mapBase = mapGenericInterfaceInfo.canonicalClassInfo
    val mapType = DotNetIlValueType.UserClass(mapBase)

    private val mapEntryGenericInterfaceInfo = DotNetGenericInterfaceInfo(
        canonicalClassInfo = DotNetIlClassInfo(
            ilClassName = "Entry",
            enclosingClass = mapBase,
        ),
    )
    private val mapEntryBase = mapEntryGenericInterfaceInfo.canonicalClassInfo
    val mapEntryType = DotNetIlValueType.UserClass(mapEntryBase)

    private val mutableMapGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.MutableMap")
    private val mutableMapBase = mutableMapGenericInterfaceInfo.canonicalClassInfo
    val mutableMapType = DotNetIlValueType.UserClass(mutableMapBase)

    private val mutableMapEntryGenericInterfaceInfo = DotNetGenericInterfaceInfo(
        canonicalClassInfo = DotNetIlClassInfo(
            ilClassName = "MutableEntry",
            enclosingClass = mutableMapBase,
        ),
    )
    private val mutableMapEntryBase = mutableMapEntryGenericInterfaceInfo.canonicalClassInfo
    val mutableMapEntryType = DotNetIlValueType.UserClass(mutableMapEntryBase)

    init {
        listIteratorBase.interfaces = listOf(iteratorType)
        mutableIteratorBase.interfaces = listOf(iteratorType)
        mutableListIteratorBase.interfaces = listOf(listIteratorType, mutableIteratorType)
        mutableIterableBase.interfaces = listOf(iterableType)
        collectionBase.interfaces = listOf(iterableType)
        mutableCollectionBase.interfaces = listOf(collectionType, mutableIterableType)
        listBase.interfaces = listOf(collectionType)
        mutableListBase.interfaces = listOf(listType, mutableCollectionType)
        setBase.interfaces = listOf(collectionType)
        mutableSetBase.interfaces = listOf(setType, mutableCollectionType)
        mutableMapBase.interfaces = listOf(mapType)
        mutableMapEntryBase.interfaces = listOf(mapEntryType)
    }

    private data class RuntimeGenericInterfaceMethodNames(
        val canonical: String,
        val property: String? = null,
    )

    private data class RuntimeGenericInterfaceDescriptor(
        val info: DotNetGenericInterfaceInfo,
        val methods: Map<String, RuntimeGenericInterfaceMethodNames>,
    )

    private val iteratorMethods = mapOf(
        "hasNext" to RuntimeGenericInterfaceMethodNames("HasNext"),
        "next" to RuntimeGenericInterfaceMethodNames("Next"),
    )
    private val listIteratorMethods = iteratorMethods + mapOf(
        "hasPrevious" to RuntimeGenericInterfaceMethodNames("HasPrevious"),
        "previous" to RuntimeGenericInterfaceMethodNames("Previous"),
        "nextIndex" to RuntimeGenericInterfaceMethodNames("NextIndex"),
        "previousIndex" to RuntimeGenericInterfaceMethodNames("PreviousIndex"),
    )
    private val mutableIteratorMethods = mapOf(
        "remove" to RuntimeGenericInterfaceMethodNames("Remove"),
    )
    private val mutableListIteratorMethods = mapOf(
        "hasNext" to RuntimeGenericInterfaceMethodNames("HasNext"),
        "next" to RuntimeGenericInterfaceMethodNames("Next"),
        "remove" to RuntimeGenericInterfaceMethodNames("Remove"),
        "set" to RuntimeGenericInterfaceMethodNames("Set"),
        "add" to RuntimeGenericInterfaceMethodNames("Add"),
    )
    private val iterableMethods = mapOf(
        "iterator" to RuntimeGenericInterfaceMethodNames("GetIterator"),
    )
    private val mutableIterableMethods = iterableMethods
    private val collectionMethods = iterableMethods + mapOf(
        "get_size" to RuntimeGenericInterfaceMethodNames(
            canonical = "get_Size",
            property = "Size",
        ),
        "isEmpty" to RuntimeGenericInterfaceMethodNames("IsEmpty"),
        "contains" to RuntimeGenericInterfaceMethodNames(
            canonical = "ContainsErased",
        ),
        "containsAll" to RuntimeGenericInterfaceMethodNames("ContainsAll"),
    )
    private val mutableCollectionMethods = mutableIterableMethods + mapOf(
        "add" to RuntimeGenericInterfaceMethodNames("Add"),
        "remove" to RuntimeGenericInterfaceMethodNames("RemoveErased"),
        "addAll" to RuntimeGenericInterfaceMethodNames("AddAll"),
        "removeAll" to RuntimeGenericInterfaceMethodNames("RemoveAll"),
        "retainAll" to RuntimeGenericInterfaceMethodNames("RetainAll"),
        "clear" to RuntimeGenericInterfaceMethodNames("Clear"),
    )
    private val listMethods = collectionMethods + mapOf(
        "get" to RuntimeGenericInterfaceMethodNames("Get"),
        "indexOf" to RuntimeGenericInterfaceMethodNames(
            canonical = "IndexOfErased",
        ),
        "lastIndexOf" to RuntimeGenericInterfaceMethodNames(
            canonical = "LastIndexOfErased",
        ),
        "listIterator" to RuntimeGenericInterfaceMethodNames("GetListIterator"),
        "subList" to RuntimeGenericInterfaceMethodNames("SubList"),
    )
    private val mutableListMethods = mapOf(
        "add" to RuntimeGenericInterfaceMethodNames("Add"),
        "remove" to RuntimeGenericInterfaceMethodNames("RemoveErased"),
        "addAll" to RuntimeGenericInterfaceMethodNames("AddAll"),
        "removeAll" to RuntimeGenericInterfaceMethodNames("RemoveAll"),
        "retainAll" to RuntimeGenericInterfaceMethodNames("RetainAll"),
        "clear" to RuntimeGenericInterfaceMethodNames("Clear"),
        "set" to RuntimeGenericInterfaceMethodNames("Set"),
        "removeAt" to RuntimeGenericInterfaceMethodNames("RemoveAt"),
        "listIterator" to RuntimeGenericInterfaceMethodNames("GetListIterator"),
        "subList" to RuntimeGenericInterfaceMethodNames("SubList"),
    )
    private val setMethods = collectionMethods
    private val mutableSetMethods = mutableCollectionMethods
    private val mapMethods = mapOf(
        "get_size" to RuntimeGenericInterfaceMethodNames(
            canonical = "get_Size",
            property = "Size",
        ),
        "isEmpty" to RuntimeGenericInterfaceMethodNames("IsEmpty"),
        "containsKey" to RuntimeGenericInterfaceMethodNames("ContainsKeyErased"),
        "containsValue" to RuntimeGenericInterfaceMethodNames("ContainsValueErased"),
        "get" to RuntimeGenericInterfaceMethodNames("GetErased"),
        "get_keys" to RuntimeGenericInterfaceMethodNames(
            canonical = "get_Keys",
            property = "Keys",
        ),
        "get_values" to RuntimeGenericInterfaceMethodNames(
            canonical = "get_Values",
            property = "Values",
        ),
        "get_entries" to RuntimeGenericInterfaceMethodNames(
            canonical = "get_Entries",
            property = "Entries",
        ),
    )
    private val mutableMapMethods = mapOf(
        "put" to RuntimeGenericInterfaceMethodNames("PutErased"),
        "remove" to RuntimeGenericInterfaceMethodNames("RemoveKeyErased"),
        "putAll" to RuntimeGenericInterfaceMethodNames("PutAll"),
        "clear" to RuntimeGenericInterfaceMethodNames("Clear"),
        "get_keys" to RuntimeGenericInterfaceMethodNames(
            canonical = "get_Keys",
            property = "Keys",
        ),
        "get_values" to RuntimeGenericInterfaceMethodNames(
            canonical = "get_Values",
            property = "Values",
        ),
        "get_entries" to RuntimeGenericInterfaceMethodNames(
            canonical = "get_Entries",
            property = "Entries",
        ),
    )
    private val mapEntryMethods = mapOf(
        "get_key" to RuntimeGenericInterfaceMethodNames(
            canonical = "get_Key",
            property = "Key",
        ),
        "get_value" to RuntimeGenericInterfaceMethodNames(
            canonical = "get_Value",
            property = "Value",
        ),
    )
    private val mutableMapEntryMethods = mapOf(
        "setValue" to RuntimeGenericInterfaceMethodNames("SetValue"),
    )

    private val genericInterfaceDescriptorsByFqName = mapOf(
        "kotlin.collections.Iterator" to RuntimeGenericInterfaceDescriptor(
            info = iteratorGenericInterfaceInfo,
            methods = iteratorMethods,
        ),
        "kotlin.collections.ListIterator" to RuntimeGenericInterfaceDescriptor(
            info = listIteratorGenericInterfaceInfo,
            methods = listIteratorMethods,
        ),
        "kotlin.collections.MutableIterator" to RuntimeGenericInterfaceDescriptor(
            info = mutableIteratorGenericInterfaceInfo,
            methods = mutableIteratorMethods,
        ),
        "kotlin.collections.MutableListIterator" to RuntimeGenericInterfaceDescriptor(
            info = mutableListIteratorGenericInterfaceInfo,
            methods = mutableListIteratorMethods,
        ),
        "kotlin.collections.Iterable" to RuntimeGenericInterfaceDescriptor(
            info = iterableGenericInterfaceInfo,
            methods = iterableMethods,
        ),
        "kotlin.collections.MutableIterable" to RuntimeGenericInterfaceDescriptor(
            info = mutableIterableGenericInterfaceInfo,
            methods = mutableIterableMethods,
        ),
        "kotlin.collections.Collection" to RuntimeGenericInterfaceDescriptor(
            info = collectionGenericInterfaceInfo,
            methods = collectionMethods,
        ),
        "kotlin.collections.MutableCollection" to RuntimeGenericInterfaceDescriptor(
            info = mutableCollectionGenericInterfaceInfo,
            methods = mutableCollectionMethods,
        ),
        "kotlin.collections.List" to RuntimeGenericInterfaceDescriptor(
            info = listGenericInterfaceInfo,
            methods = listMethods,
        ),
        "kotlin.collections.MutableList" to RuntimeGenericInterfaceDescriptor(
            info = mutableListGenericInterfaceInfo,
            methods = mutableListMethods,
        ),
        "kotlin.collections.Set" to RuntimeGenericInterfaceDescriptor(
            info = setGenericInterfaceInfo,
            methods = setMethods,
        ),
        "kotlin.collections.MutableSet" to RuntimeGenericInterfaceDescriptor(
            info = mutableSetGenericInterfaceInfo,
            methods = mutableSetMethods,
        ),
        "kotlin.collections.Map" to RuntimeGenericInterfaceDescriptor(
            info = mapGenericInterfaceInfo,
            methods = mapMethods,
        ),
        "kotlin.collections.Map.Entry" to RuntimeGenericInterfaceDescriptor(
            info = mapEntryGenericInterfaceInfo,
            methods = mapEntryMethods,
        ),
        "kotlin.collections.MutableMap" to RuntimeGenericInterfaceDescriptor(
            info = mutableMapGenericInterfaceInfo,
            methods = mutableMapMethods,
        ),
        "kotlin.collections.MutableMap.MutableEntry" to RuntimeGenericInterfaceDescriptor(
            info = mutableMapEntryGenericInterfaceInfo,
            methods = mutableMapEntryMethods,
        ),
    )

    private val kCallableBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.KCallable",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val kClassifierBase = DotNetKClassRuntime.kClassifierClassInfo
    private val kAnnotatedElementBase = DotNetKClassRuntime.kAnnotatedElementClassInfo
    private val kClassBase = DotNetKClassRuntime.kClassClassInfo
    private val kTypeBase = DotNetKClassRuntime.kTypeClassInfo

    private val kFunctionBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.KFunction",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val kPropertyBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.KProperty",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val kMutablePropertyBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.KMutableProperty",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val fixedPropertyClasses = List(3) { arity ->
        DotNetIlClassInfo(
            ilClassName = "Kotlin.KProperty$arity",
            assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
        )
    }

    private val fixedMutablePropertyClasses = List(3) { arity ->
        DotNetIlClassInfo(
            ilClassName = "Kotlin.KMutableProperty$arity",
            assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
        )
    }

    private val propertyReferenceFactory = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.PropertyReferenceFactory",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val callableAnnotationFactory = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.CallableAnnotationFactory",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val functionReferenceBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.FunctionReferenceBase",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val mutableRefClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.MutableRef",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val defaultConstructorMarkerClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.DefaultConstructorMarker",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val syntheticConstructorMarkerClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.SyntheticConstructorMarker",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val fixedFunctionClasses = listOf(
        functionClassInfo(arity = 0),
        functionClassInfo(arity = 1),
        functionClassInfo(arity = 2),
        functionClassInfo(arity = 3),
    )

    private val exactFunctionClasses = List(4) { arity ->
        DotNetIlClassInfo(
            ilClassName = "Kotlin.Runtime.Internal.ExactFunction$arity`${arity + 1}",
            typeParameterVariances = List(arity) { Variance.IN_VARIANCE } + Variance.OUT_VARIANCE,
            assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
        )
    }

    private val typedArgumentsFunctionClasses = (1..2).associateWith { arity ->
        DotNetIlClassInfo(
            ilClassName = "Kotlin.Runtime.Internal.TypedArgumentsFunction$arity`$arity",
            typeParameterVariances = List(arity) { Variance.IN_VARIANCE },
            assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
        )
    }

    init {
        listIteratorBase.interfaces = listOf(iteratorType)
        collectionBase.interfaces = listOf(iterableType)
        listBase.interfaces = listOf(collectionType)
        kClassBase.interfaces = listOf(
            DotNetIlValueType.UserClass(kClassifierBase),
            DotNetIlValueType.UserClass(kAnnotatedElementBase),
        )
        kCallableBase.interfaces = listOf(DotNetIlValueType.UserClass(kAnnotatedElementBase))
        kFunctionBase.interfaces = listOf(
            DotNetIlValueType.UserClass(kCallableBase),
            DotNetIlValueType.UserClass(functionBase),
        )
        fixedFunctionClasses.forEach { classInfo ->
            classInfo.interfaces = listOf(DotNetIlValueType.UserClass(functionBase))
        }
        kPropertyBase.interfaces = listOf(DotNetIlValueType.UserClass(kCallableBase))
        kMutablePropertyBase.interfaces = listOf(DotNetIlValueType.UserClass(kPropertyBase))
        fixedPropertyClasses.forEachIndexed { arity, classInfo ->
            classInfo.interfaces = listOf(
                DotNetIlValueType.UserClass(kPropertyBase),
                DotNetIlValueType.UserClass(fixedFunctionClasses[arity]),
            )
        }
        fixedMutablePropertyClasses.forEachIndexed { arity, classInfo ->
            classInfo.interfaces = listOf(
                DotNetIlValueType.UserClass(fixedPropertyClasses[arity]),
                DotNetIlValueType.UserClass(kMutablePropertyBase),
            )
        }
    }

    fun classInfoFor(
        irClass: IrClass,
        classifierInfo: DotNetClassifierInfo = DotNetClassifierInfo.derive(irClass),
    ): DotNetIlClassInfo? {
        genericInterfaceInfoFor(irClass, classifierInfo)?.let { return it.canonicalClassInfo }
        return when {
            classifierInfo.isCharSequence -> charSequenceClass
            irClass.isDotNetMutableRefStub == true -> mutableRefClass
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.ENUM -> enumClass
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.ENUM_COMPANION -> enumCompanionClass
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.ENUM_COMPANION_STATICS -> enumCompanionStaticsClass
            irClass.isDotNetFunctionReferenceBase == true -> functionReferenceBase
            irClass.dotNetExactFunctionArity != null -> exactFunctionClasses[irClass.dotNetExactFunctionArity!!]
            irClass.dotNetTypedArgumentsFunctionArity != null ->
                typedArgumentsFunctionClasses[irClass.dotNetTypedArgumentsFunctionArity!!]
            irClass.isDotNetPropertyReferenceFactory == true -> propertyReferenceFactory
            irClass.isDotNetCallableAnnotationFactory == true -> callableAnnotationFactory
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_CLASSIFIER -> kClassifierBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_ANNOTATED_ELEMENT -> kAnnotatedElementBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_CLASS -> kClassBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_TYPE -> kTypeBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_CALLABLE -> kCallableBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_FUNCTION || classifierInfo.fixedKFunctionArity != null ->
                kFunctionBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_PROPERTY -> kPropertyBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_MUTABLE_PROPERTY -> kMutablePropertyBase
            classifierInfo.fixedKPropertyArity != null -> fixedPropertyClasses[classifierInfo.fixedKPropertyArity]
            classifierInfo.fixedKMutablePropertyArity != null ->
                fixedMutablePropertyClasses[classifierInfo.fixedKMutablePropertyArity]
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.FUNCTION -> functionBase
            else -> classifierInfo.fixedFunctionArity?.let(fixedFunctionClasses::get)
        }
    }

    /** The compiler-owned mutable cell follows the same one-owner erasure rule as captures. */
    fun erasedGenericClassInfoFor(
        irClass: IrClass,
        classifierInfo: DotNetClassifierInfo = DotNetClassifierInfo.derive(irClass),
    ): DotNetGenericClassInfo? = when {
        irClass.isDotNetMutableRefStub == true -> DotNetGenericClassInfo(mutableRefClass)
        classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.ENUM -> enumGenericClass
        else -> null
    }

    fun genericInterfaceInfoFor(
        irClass: IrClass,
        classifierInfo: DotNetClassifierInfo = DotNetClassifierInfo.derive(irClass),
    ): DotNetGenericInterfaceInfo? = genericInterfaceDescriptorFor(irClass, classifierInfo)?.info

    /** Runtime-owned erased interfaces plus profile-mapped Common interfaces handled by codegen. */
    fun hasBuiltInGenericInterfaceMapping(
        irClass: IrClass,
        classifierInfo: DotNetClassifierInfo = DotNetClassifierInfo.derive(irClass),
    ): Boolean = genericInterfaceDescriptorFor(irClass, classifierInfo) != null || classifierInfo.isComparable

    /** Any interface whose physical owner is supplied by Kotlin.Runtime rather than this module. */
    fun hasBuiltInInterfaceMapping(
        irClass: IrClass,
        classifierInfo: DotNetClassifierInfo = DotNetClassifierInfo.derive(irClass),
    ): Boolean = hasBuiltInGenericInterfaceMapping(irClass, classifierInfo) ||
            irClass.isInterface && classInfoFor(irClass, classifierInfo) != null

    /**
     * Runtime-owned Kotlin interfaces whose complete physical implementation contract is emitted
     * in Kotlin.Runtime's C# authoring manifest.
     */
    fun supportsCSharpSourceAuthoring(irClass: IrClass): Boolean =
        DotNetClassifierInfo.derive(irClass).isCharSequence

    /** The non-generic implementation capability for the classified CharSequence carrier. */
    fun charSequenceImplementationClassInfo(irClass: IrClass): DotNetIlClassInfo? =
        charSequenceClass.takeIf { DotNetClassifierInfo.derive(irClass).isCharSequence }

    /** Stable runtime spellings for one built-in canonical slot and its typed capability. */
    private fun genericInterfaceMethodNamesOrNull(
        function: IrSimpleFunction,
    ): RuntimeGenericInterfaceMethodNames? {
        val interfaceClass = function.parent as? IrClass ?: return null
        val descriptor = genericInterfaceDescriptorFor(interfaceClass) ?: return null
        return descriptor.methods[function.dotNetIlMethodName()]
    }

    fun genericInterfaceCanonicalMethodNameOrNull(function: IrSimpleFunction): String? =
        genericInterfaceMethodNamesOrNull(function)?.canonical

    fun genericInterfaceTypedMethodNameOrNull(function: IrSimpleFunction): String? =
        null

    fun genericInterfacePropertyNameOrNull(function: IrSimpleFunction): String? =
        genericInterfaceMethodNamesOrNull(function)?.property

    fun genericInterfaceFunctionInfoOrNull(
        function: IrSimpleFunction,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlFunctionInfo? {
        val interfaceClass = function.parent as? IrClass ?: return null
        val descriptor = genericInterfaceDescriptorFor(interfaceClass, typeMapper.classifierInfo(interfaceClass)) ?: return null
        val physicalMethodName = descriptor.methods[function.dotNetIlMethodName()]?.canonical ?: return null
        return DotNetIlFunctionInfo(
            descriptor.info.canonicalClassInfo,
            function.dotNetSignature(typeMapper),
            physicalMethodName,
        )
    }

    /**
     * The source/KLIB Enum declaration remains logical authority, while Runtime owns its one
     * physical erased class. Keep every ordinary member bound to that Runtime owner; otherwise
     * callers would fall through to the old Stdlib implementation lookup and declaration
     * eviction would spread through every enum-dependent product declaration.
     */
    fun enumFunctionInfoOrNull(
        function: IrSimpleFunction,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlFunctionInfo? {
        val owner = function.parent as? IrClass ?: return null
        val physicalOwner = when (typeMapper.classifierInfo(owner).runtimeKind) {
            DotNetRuntimeClassifierKind.ENUM -> enumClass
            DotNetRuntimeClassifierKind.ENUM_COMPANION_STATICS ->
                enumCompanionStaticsClass.takeIf { function.name.asString() == "<EnsureInitialized>" }
            else -> null
        } ?: return null
        return DotNetIlFunctionInfo(
            owner = physicalOwner,
            signature = function.dotNetSignature(typeMapper),
            physicalMethodName = function.dotNetAbiMethodName(
                isErasedGenericClass = typeMapper::isErasedGenericClass,
            ),
        )
    }

    /**
     * Runtime-owned reflection declarations are resolution views of an already emitted physical
     * contract. In particular, the platform actual KCallable source must not make its generic
     * owner look like a newly declared Kotlin interface whose canonical slot needs ABI mangling.
     */
    fun reflectionFunctionInfoOrNull(
        function: IrSimpleFunction,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlFunctionInfo? {
        val owner = function.parent as? IrClass ?: return null
        val ownerInfo = typeMapper.classifierInfo(owner)
        return when {
            owner.isDotNetFunctionReferenceBase == true &&
                    function.dotNetIlMethodName() in
                    setOf(
                        "GetReturnType",
                        "GetParameters",
                        "GetTypeParameters",
                        "CallErased",
                        "CallByErased",
                        "CallDefaultErased",
                        "EmptyVarargAt",
                    ) + KFUNCTION_DECLARATION_GETTERS ->
                DotNetIlFunctionInfo(
                    owner = functionReferenceBase,
                    signature = function.dotNetSignature(typeMapper),
                    physicalMethodName = function.name.asString(),
                )
            ownerInfo.runtimeKind == DotNetRuntimeClassifierKind.K_CALLABLE &&
                    function.dotNetIlMethodName() in
                    setOf("get_name", "get_returnType", "get_parameters", "get_typeParameters", "Call", "CallBy") ->
                DotNetIlFunctionInfo(
                    owner = kCallableBase,
                    signature = function.dotNetSignature(typeMapper),
                    physicalMethodName = function.dotNetIlMethodName(),
                )
            ownerInfo.runtimeKind == DotNetRuntimeClassifierKind.K_FUNCTION &&
                    function.dotNetIlMethodName() in KFUNCTION_DECLARATION_GETTERS ->
                DotNetIlFunctionInfo(
                    owner = kFunctionBase,
                    signature = function.dotNetSignature(typeMapper),
                    physicalMethodName = function.dotNetIlMethodName(),
                )
            else -> null
        }
    }

    private fun genericInterfaceDescriptorFor(
        irClass: IrClass,
        classifierInfo: DotNetClassifierInfo = DotNetClassifierInfo.derive(irClass),
    ): RuntimeGenericInterfaceDescriptor? {
        val descriptor = classifierInfo.fqNameString
            ?.let(genericInterfaceDescriptorsByFqName::get)
            ?: return null
        return descriptor.takeIf {
            irClass.typeParameters.size == 1 ||
                    irClass.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB
        }
    }

    fun mapCallableType(
        type: IrType,
        classifierInfo: DotNetClassifierInfo? = null,
    ): DotNetIlValueType.UserClass? {
        val simpleType = type as? IrSimpleType ?: return null
        val irClass = simpleType.classifier.owner as? IrClass ?: return null
        val info = classifierInfo ?: DotNetClassifierInfo.derive(irClass)
        if (irClass.isDotNetMutableRefStub == true && simpleType.arguments.size == 1) {
            return DotNetIlValueType.UserClass(mutableRefClass)
        }
        val classInfo = when {
            info.runtimeKind == DotNetRuntimeClassifierKind.FUNCTION -> {
                if (simpleType.arguments.size != 1) return null
                functionBase
            }
            info.runtimeKind == DotNetRuntimeClassifierKind.K_CALLABLE -> {
                if (simpleType.arguments.size != 1) return null
                kCallableBase
            }
            info.runtimeKind == DotNetRuntimeClassifierKind.K_FUNCTION -> {
                if (simpleType.arguments.size != 1) return null
                kFunctionBase
            }
            info.runtimeKind == DotNetRuntimeClassifierKind.K_PROPERTY -> {
                if (simpleType.arguments.size != 1) return null
                kPropertyBase
            }
            info.runtimeKind == DotNetRuntimeClassifierKind.K_MUTABLE_PROPERTY -> {
                if (simpleType.arguments.size != 1) return null
                kMutablePropertyBase
            }
            else -> {
                val functionArity = info.fixedFunctionArity
                if (functionArity != null) {
                    if (simpleType.arguments.size != functionArity + 1) return null
                    fixedFunctionClasses[functionArity]
                } else {
                    val kFunctionArity = info.fixedKFunctionArity
                    if (kFunctionArity != null) {
                        if (simpleType.arguments.size != kFunctionArity + 1) return null
                        kFunctionBase
                    } else {
                        val propertyArity = info.fixedKPropertyArity
                        if (propertyArity != null) {
                            if (simpleType.arguments.size != propertyArity + 1) return null
                            fixedPropertyClasses[propertyArity]
                        } else {
                            val mutablePropertyArity = info.fixedKMutablePropertyArity ?: return null
                            if (simpleType.arguments.size != mutablePropertyArity + 1) return null
                            fixedMutablePropertyClasses[mutablePropertyArity]
                        }
                    }
                }
            }
        }
        // Projections, including Function<*>, affect only Kotlin's logical view. A marker or
        // fixed-arity value still has the same erased physical interface and reference identity.
        return DotNetIlValueType.UserClass(classInfo)
    }

    fun mapCompilerRuntimeType(
        type: IrType,
        classifierInfo: DotNetClassifierInfo? = null,
    ): DotNetIlValueType.UserClass? {
        val simpleType = type as? IrSimpleType ?: return null
        val irClass = simpleType.classifier.owner as? IrClass ?: return null
        val info = classifierInfo ?: DotNetClassifierInfo.derive(irClass)
        if (info.builtinKind == DotNetBuiltinClassifierKind.NOTHING) return nothingType
        if (info.runtimeKind == DotNetRuntimeClassifierKind.K_CLASSIFIER && simpleType.arguments.isEmpty()) {
            return DotNetIlValueType.UserClass(kClassifierBase)
        }
        if (info.runtimeKind == DotNetRuntimeClassifierKind.K_ANNOTATED_ELEMENT && simpleType.arguments.isEmpty()) {
            return DotNetIlValueType.UserClass(kAnnotatedElementBase)
        }
        if (info.runtimeKind == DotNetRuntimeClassifierKind.K_CLASS && simpleType.arguments.size == 1) {
            // KClass's type argument remains authoritative in IR/KLIB. Runtime equality and
            // instance checks use the declaration-erased Kotlin classifier carried by KClassImpl.
            return DotNetIlValueType.UserClass(kClassBase)
        }
        if (info.runtimeKind == DotNetRuntimeClassifierKind.K_TYPE && simpleType.arguments.isEmpty()) {
            return DotNetIlValueType.UserClass(kTypeBase)
        }
        if (info.fqName == DEFAULT_CONSTRUCTOR_MARKER_FQ_NAME && simpleType.arguments.isEmpty()) {
            return DotNetIlValueType.UserClass(defaultConstructorMarkerClass)
        }
        if (info.fqName == SYNTHETIC_CONSTRUCTOR_MARKER_FQ_NAME && simpleType.arguments.isEmpty()) {
            return DotNetIlValueType.UserClass(syntheticConstructorMarkerClass)
        }
        return mapCallableType(type, info)
    }

    fun registerCallableFunctions(
        irBuiltIns: IrBuiltIns,
        propertyReferenceFactoryFunctions: List<IrSimpleFunction>,
        callableAnnotationFactoryFunctions: List<IrSimpleFunction>,
        typeMapper: DotNetIlTypeMapper,
        availableFunctions: MutableMap<IrSimpleFunction, DotNetIlFunctionInfo>,
    ) {
        val enumBase = irBuiltIns.enumClass.owner
        val enumMembers = buildList {
            enumBase.properties.mapNotNullTo(this) { property -> property.getter }
            addAll(enumBase.functions)
        }
        for (member in enumMembers) {
            val functionInfo = enumFunctionInfoOrNull(member, typeMapper)
                ?: error("Internal .NET backend error: kotlin.Enum member '${member.name}' has no Runtime binding")
            availableFunctions[member] = functionInfo
        }
        for (arity in fixedFunctionClasses.indices) {
            val invoke = irBuiltIns.functionN(arity).invokeFun
                ?: error("Internal .NET backend error: kotlin.Function$arity has no invoke member")
            availableFunctions[invoke] = DotNetIlFunctionInfo(
                fixedFunctionClasses[arity],
                invoke.dotNetSignature(typeMapper),
            )
        }
        for (propertyName in listOf("name", "returnType", "parameters", "typeParameters")) {
            val property = irBuiltIns.kCallableClass.owner.properties
                .singleOrNull { it.name.asString() == propertyName }
                ?: continue
            val getter = property.getter
                ?: error("Internal .NET backend error: kotlin.reflect.KCallable.$propertyName has no getter")
            availableFunctions[getter] = DotNetIlFunctionInfo(
                kCallableBase,
                getter.dotNetSignature(typeMapper),
            )
        }
        irBuiltIns.kCallableClass.owner.functions
            .filter { function ->
                val methodName = function.name.asString()
                methodName == "call" || methodName == "callBy"
            }
            .forEach { call ->
                availableFunctions[call] = DotNetIlFunctionInfo(
                    kCallableBase,
                    call.dotNetSignature(typeMapper),
                )
            }
        irBuiltIns.kFunctionClass.owner.properties
            .filter { property -> property.name.asString() in KFUNCTION_DECLARATION_PROPERTIES }
            .forEach { property ->
                val getter = property.getter
                    ?: error("Internal .NET backend error: kotlin.reflect.KFunction.${property.name} has no getter")
                availableFunctions[getter] = DotNetIlFunctionInfo(
                    kFunctionBase,
                    getter.dotNetSignature(typeMapper),
                )
            }
        val kClass = irBuiltIns.kClassClass.owner
        for (propertyName in listOf("simpleName", "qualifiedName")) {
            val getter = kClass.properties.single { it.name.asString() == propertyName }.getter
                ?: error("Internal .NET backend error: kotlin.reflect.KClass.$propertyName has no getter")
            availableFunctions[getter] = DotNetIlFunctionInfo(
                kClassBase,
                getter.dotNetSignature(typeMapper),
            )
        }
        val kAnnotatedElement = kClass.superTypes
            .mapNotNull { type -> type.classOrNull?.owner }
            .singleOrNull { superClass ->
                typeMapper.classifierInfo(superClass).runtimeKind == DotNetRuntimeClassifierKind.K_ANNOTATED_ELEMENT
            }
        // -no-stdlib compilation retains the compiler's minimal Common KClass floor and does not
        // load platform stdlib extensions. Register this physical member only when the .NET
        // KAnnotatedElement declaration is actually present; if present, its shape is mandatory.
        if (kAnnotatedElement != null) {
            val annotationsGetter = kAnnotatedElement.properties
                .single { property -> property.name.asString() == "annotations" }
                .getter
                ?: error("Internal .NET backend error: kotlin.reflect.KAnnotatedElement.annotations has no getter")
            availableFunctions[annotationsGetter] = DotNetIlFunctionInfo(
                kAnnotatedElementBase,
                annotationsGetter.dotNetSignature(typeMapper),
            )
        }
        val isInstance = kClass.functions.single { function -> function.name.asString() == "isInstance" }
        availableFunctions[isInstance] = DotNetIlFunctionInfo(
            kClassBase,
            isInstance.dotNetSignature(typeMapper),
        )
        val kType = irBuiltIns.kTypeClass.owner
        for (propertyName in listOf("classifier", "arguments", "isMarkedNullable")) {
            val getter = kType.properties.single { property -> property.name.asString() == propertyName }.getter
                ?: error("Internal .NET backend error: kotlin.reflect.KType.$propertyName has no getter")
            availableFunctions[getter] = DotNetIlFunctionInfo(
                kTypeBase,
                getter.dotNetSignature(typeMapper),
            )
        }
        for (arity in fixedPropertyClasses.indices) {
            val get = irBuiltIns.getKPropertyClass(mutable = false, arity).owner.functions
                .single { it.name.asString() == "get" }
            availableFunctions[get] = DotNetIlFunctionInfo(
                fixedPropertyClasses[arity],
                get.dotNetSignature(typeMapper),
            )
            val set = irBuiltIns.getKPropertyClass(mutable = true, arity).owner.functions
                .single { it.name.asString() == "set" }
            availableFunctions[set] = DotNetIlFunctionInfo(
                fixedMutablePropertyClasses[arity],
                set.dotNetSignature(typeMapper),
            )
        }
        propertyReferenceFactoryFunctions.forEach { factory ->
            availableFunctions[factory] = DotNetIlFunctionInfo(
                propertyReferenceFactory,
                factory.dotNetSignature(typeMapper),
            )
        }
        callableAnnotationFactoryFunctions.forEach { factory ->
            availableFunctions[factory] = DotNetIlFunctionInfo(
                callableAnnotationFactory,
                factory.dotNetSignature(typeMapper),
            )
        }
    }

    val unitInstanceLoadInstruction: String
        get() = "ldsfld ${unitType.nameInSignature} " +
                "[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]'Kotlin.Unit'::INSTANCE"

    fun isFixedFunctionType(type: DotNetIlValueType, arity: Int): Boolean =
        arity in fixedFunctionClasses.indices && type == DotNetIlValueType.UserClass(fixedFunctionClasses[arity])

    fun fixedFunctionType(arity: Int): DotNetIlValueType.UserClass =
        DotNetIlValueType.UserClass(fixedFunctionClasses[arity])

    /** Closed optional execution capability for the logical parameter types followed by result. */
    fun exactFunctionType(argumentTypes: List<DotNetIlValueType>): DotNetIlValueType.GenericInstance? {
        val arity = argumentTypes.size - 1
        if (arity !in exactFunctionClasses.indices) return null
        return DotNetIlValueType.GenericInstance(exactFunctionClasses[arity], argumentTypes)
    }

    /** Member-reference spelling of ExactFunctionN.InvokeExact on one closed owner view. */
    fun exactInvokeCallInstruction(exactType: DotNetIlValueType.GenericInstance): String {
        val arity = exactType.arguments.size - 1
        val parameterTypes = (0 until arity).joinToString(", ") { "!$it" }
        return "callvirt instance !$arity ${exactType.nameInSignature}::'InvokeExact'($parameterTypes)"
    }

    /** Closed optional execution capability for exact parameters and an erased result. */
    fun typedArgumentsFunctionType(parameterTypes: List<DotNetIlValueType>): DotNetIlValueType.GenericInstance? {
        val classInfo = typedArgumentsFunctionClasses[parameterTypes.size] ?: return null
        return DotNetIlValueType.GenericInstance(classInfo, parameterTypes)
    }

    /** Member-reference spelling of TypedArgumentsFunctionN.InvokeTyped. */
    fun typedArgumentsInvokeCallInstruction(typedArgumentsType: DotNetIlValueType.GenericInstance): String {
        val parameterTypes = typedArgumentsType.arguments.indices.joinToString(", ") { "!$it" }
        return "callvirt instance object ${typedArgumentsType.nameInSignature}::'InvokeTyped'($parameterTypes)"
    }

    /** Member-reference spelling of the stable physically erased FunctionN.Invoke slot. */
    fun erasedInvokeCallInstruction(arity: Int): String {
        val parameterTypes = List(arity) { "object" }.joinToString(", ")
        return "callvirt instance object ${fixedFunctionClasses[arity].ilTypeRef}::'Invoke'($parameterTypes)"
    }

    /**
     * Both directions of one explicit CLR delegate boundary. A Unit result selects Action;
     * every other result selects Func. The open `!!n` signatures in the member references are
     * the declaration signatures of the generic runtime helpers, while [closedDelegateType] is
     * the concrete parameter or return type of the generated facade method.
     */
    fun delegateBoundary(
        parameterTypes: List<DotNetIlValueType>,
        resultType: DotNetIlValueType?,
        nullable: Boolean,
        coreLibraryReference: String,
    ): DotNetDelegateBoundary {
        val arity = parameterTypes.size
        require(arity in 0..2) { "unsupported callable export arity $arity" }
        val returnsUnit = resultType == null
        val typeArguments = parameterTypes + listOfNotNull(resultType)
        val family = if (returnsUnit) "Action" else "Func"
        val closedDelegateType = renderDelegateType(
            family,
            typeArguments.map { it.nameInSignature },
            coreLibraryReference,
        )
        val openDelegateType = renderDelegateType(
            family,
            typeArguments.indices.map { "!!$it" },
            coreLibraryReference,
        )
        val instantiation = typeArguments.takeIf { it.isNotEmpty() }
            ?.joinToString(", ", "<", ">") { it.nameInSignature }
            .orEmpty()
        val helperOwner = "[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "Kotlin.Runtime.Internal.DelegateProjection".toIlIdentifier()
        val nullablePrefix = if (nullable) "Nullable" else ""
        val projectionCall = "call $openDelegateType " +
                "$helperOwner::${"To$nullablePrefix$family$arity".toIlIdentifier()}$instantiation(" +
                "${fixedFunctionType(arity).nameInSignature})"
        val adaptationCall = "call ${fixedFunctionType(arity).nameInSignature} " +
                "$helperOwner::${"From$nullablePrefix$family$arity".toIlIdentifier()}$instantiation($openDelegateType)"
        return DotNetDelegateBoundary(closedDelegateType, projectionCall, adaptationCall)
    }

    private fun renderDelegateType(
        family: String,
        arguments: List<String>,
        coreLibraryReference: String,
    ): String {
        val genericSuffix = arguments.takeIf { it.isNotEmpty() }
            ?.joinToString(", ", "`${arguments.size}<", ">")
            .orEmpty()
        return "class ${coreLibraryReference}System.$family$genericSuffix"
    }

    private fun functionClassInfo(arity: Int): DotNetIlClassInfo = DotNetIlClassInfo(
        ilClassName = "Kotlin.Function$arity",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
}

internal data class DotNetDelegateBoundary(
    val closedDelegateType: String,
    val projectionCallInstruction: String,
    val adaptationCallInstruction: String,
)

private val IrClass.isDotNetFunctionBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.Function" && typeParameters.size == 1

private val IrClass.isDotNetKCallableBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.reflect.KCallable" && typeParameters.size == 1

private val IrClass.isDotNetKClassifierBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.reflect.KClassifier" && typeParameters.isEmpty()

private val IrClass.isDotNetKAnnotatedElementBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.reflect.KAnnotatedElement" && typeParameters.isEmpty()

private val IrClass.isDotNetKClassBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.reflect.KClass" && typeParameters.size == 1

private val IrClass.isDotNetKTypeBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.reflect.KType" && typeParameters.isEmpty()

private val IrClass.isDotNetKFunctionBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.reflect.KFunction" && typeParameters.size == 1

private val IrClass.isDotNetKPropertyBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.reflect.KProperty" && typeParameters.size == 1

private val IrClass.isDotNetKMutablePropertyBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.reflect.KMutableProperty" && typeParameters.size == 1

internal fun IrClass.dotNetFixedFunctionArityOrNull(): Int? {
    val fqName = fqNameWhenAvailable?.asString() ?: return null
    val arity = fqName.removePrefix("kotlin.Function").toIntOrNull() ?: return null
    return arity.takeIf { it in 0..3 && typeParameters.size == it + 1 }
}

internal fun IrClass.dotNetFixedKFunctionArityOrNull(): Int? {
    if (!symbol.isKFunction()) return null
    val arity = name.asString().removePrefix("KFunction").toIntOrNull() ?: return null
    // Unlike kotlin.FunctionN, the synthetic KFunctionN classifiers exposed by the common
    // built-ins do not reliably carry their logical type parameters on the IrClass itself.
    // The instantiated IrSimpleType still carries, and mapCallableType validates, arity + 1
    // arguments. Class identity therefore comes from the canonical built-in FQ name here.
    return arity.takeIf { it in 0..3 }
}

internal fun IrClass.dotNetFixedKPropertyArityOrNull(): Int? {
    val fqName = fqNameWhenAvailable?.asString() ?: return null
    val arity = fqName.removePrefix("kotlin.reflect.KProperty").toIntOrNull() ?: return null
    return arity.takeIf { it in 0..2 }
}

internal fun IrClass.dotNetFixedKMutablePropertyArityOrNull(): Int? {
    val fqName = fqNameWhenAvailable?.asString() ?: return null
    val arity = fqName.removePrefix("kotlin.reflect.KMutableProperty").toIntOrNull() ?: return null
    return arity.takeIf { it in 0..2 }
}

internal fun IrClass.dotNetFixedPropertyArityOrNull(): Int? =
    dotNetFixedKPropertyArityOrNull() ?: dotNetFixedKMutablePropertyArityOrNull()
