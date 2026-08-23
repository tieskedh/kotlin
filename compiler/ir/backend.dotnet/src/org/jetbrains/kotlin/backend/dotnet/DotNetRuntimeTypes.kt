/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.builtins.functions.BuiltInFunctionArity
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
import org.jetbrains.kotlin.ir.util.isKSuspendFunction
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isSuspendFunction
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.Variance

/**
 * Kotlin-owned erased runtime identities evaluated by this POC.
 *
 * Common IR speaks in synthetic `kotlin.Function$arity` and `kotlin.reflect.KFunction$arity`
 * classifiers. This registry maps fixed execution arities 0..22 to erased Kotlin-owned CLR
 * interfaces, big logical arities to one erased FunctionN execution capability, and every
 * supported KFunction arity to one orthogonal, non-generic reflection view.
 * FunctionN uses object-shaped Invoke slots, following the JVM executable descriptor rather than
 * CLR generic variance: Kotlin's logical type arguments remain in IR/metadata, while every legal
 * function-type variance conversion is the same object reference at runtime. FunctionN follows
 * the Common/JVM big-arity boundary but remains a capability on that same object. CLR delegates
 * remain an interop concern and never appear in Kotlin-to-Kotlin signatures.
 * KProperty0/1/2 and their mutable variants use the same erased-identity rule and inherit the
 * matching FunctionN execution view; their Get/Set slots are Kotlin-owned runtime contracts.
 *
 * The generic-owner rehearsal adds natural CLR-generic views for the complete read-only
 * Iterator/Iterable/Collection/Set/ListIterator/List closure. Exact siblings own input members
 * which cannot legally appear on a covariant CLR interface, while the accepted arity-zero
 * identities remain declaration-semantic capabilities. Mutable collection families retain their
 * declaration-erased mappings until their complete dependency gate is selected. The five
 * currently supported primitive Iterator subclasses still alias the erased Iterator identity
 * until their ordinary stdlib classes are produced. CLR collection interfaces remain explicit
 * interop concerns.
 */
internal object DotNetRuntimeTypes {
    val DEFAULT_CONSTRUCTOR_MARKER_FQ_NAME = FqName("kotlin.runtime.internal.DefaultConstructorMarker")
    val SYNTHETIC_CONSTRUCTOR_MARKER_FQ_NAME = FqName("kotlin.runtime.internal.SyntheticConstructorMarker")
    val FUNCTION_ADAPTER_FQ_NAME = FqName("kotlin.runtime.internal.FunctionAdapter")
    private val KFUNCTION_DECLARATION_PROPERTIES =
        setOf("isInline", "isExternal", "isOperator", "isInfix", "isSuspend")
    private val KFUNCTION_DECLARATION_GETTERS =
        KFUNCTION_DECLARATION_PROPERTIES.mapTo(linkedSetOf()) { property -> "get_$property" }
    private val KCALLABLE_DECLARATION_PROPERTIES =
        setOf("visibility", "isFinal", "isOpen", "isAbstract")
    private val KCALLABLE_DECLARATION_GETTERS =
        KCALLABLE_DECLARATION_PROPERTIES.mapTo(linkedSetOf()) { property -> "get_$property" }

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

    private val numberClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Number",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
    val numberImplementationType = DotNetIlValueType.UserClass(numberClass)

    private val functionBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.Function",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
    private val functionAdapterClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.FunctionAdapter",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private fun runtimeInterface(
        canonicalName: String,
        hasRehearsalDeclaredView: Boolean = false,
        hasRehearsalExactView: Boolean = false,
        usesDeclaredViewByDefaultInRehearsal: Boolean = false,
    ): DotNetGenericInterfaceInfo =
        DotNetGenericInterfaceInfo(
            canonicalClassInfo = DotNetIlClassInfo(
                ilClassName = canonicalName,
                assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
            ),
            declaredClassInfo = if (hasRehearsalDeclaredView) {
                DotNetIlClassInfo(
                    ilClassName = "$canonicalName`1",
                    typeParameterVariances = listOf(Variance.OUT_VARIANCE),
                    assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
                )
            } else {
                null
            },
            exactClassInfo = if (hasRehearsalExactView) {
                DotNetIlClassInfo(
                    ilClassName = dotNetExactGenericInterfaceName(canonicalName, 1),
                    typeParameterVariances = listOf(Variance.INVARIANT),
                    assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
                )
            } else {
                null
            },
            isDeclaredViewStableInTypedSignatures = hasRehearsalDeclaredView,
            usesDeclaredViewByDefaultInRehearsal = usesDeclaredViewByDefaultInRehearsal,
        )

    private val iteratorGenericInterfaceInfo =
        runtimeInterface(
            "Kotlin.Collections.Iterator",
            hasRehearsalDeclaredView = true,
            usesDeclaredViewByDefaultInRehearsal = true,
        )
    private val iteratorBase = iteratorGenericInterfaceInfo.canonicalClassInfo
    val iteratorType = DotNetIlValueType.UserClass(iteratorBase)

    private val listIteratorGenericInterfaceInfo =
        runtimeInterface(
            "Kotlin.Collections.ListIterator",
            hasRehearsalDeclaredView = true,
            usesDeclaredViewByDefaultInRehearsal = true,
        )
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
        runtimeInterface(
            "Kotlin.Collections.Iterable",
            hasRehearsalDeclaredView = true,
            usesDeclaredViewByDefaultInRehearsal = true,
        )
    private val iterableBase = iterableGenericInterfaceInfo.canonicalClassInfo
    val iterableType = DotNetIlValueType.UserClass(iterableBase)

    private val mutableIterableGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.MutableIterable")
    private val mutableIterableBase = mutableIterableGenericInterfaceInfo.canonicalClassInfo
    val mutableIterableType = DotNetIlValueType.UserClass(mutableIterableBase)

    private val collectionGenericInterfaceInfo =
        runtimeInterface(
            "Kotlin.Collections.Collection",
            hasRehearsalDeclaredView = true,
            hasRehearsalExactView = true,
            usesDeclaredViewByDefaultInRehearsal = true,
        )
    private val collectionBase = collectionGenericInterfaceInfo.canonicalClassInfo
    private val collectionType = DotNetIlValueType.UserClass(collectionBase)

    private val mutableCollectionGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.MutableCollection")
    private val mutableCollectionBase = mutableCollectionGenericInterfaceInfo.canonicalClassInfo
    val mutableCollectionType = DotNetIlValueType.UserClass(mutableCollectionBase)

    private val listGenericInterfaceInfo =
        runtimeInterface(
            "Kotlin.Collections.List",
            hasRehearsalDeclaredView = true,
            hasRehearsalExactView = true,
            usesDeclaredViewByDefaultInRehearsal = true,
        )
    private val listBase = listGenericInterfaceInfo.canonicalClassInfo
    val listType = DotNetIlValueType.UserClass(listBase)

    private val enumEntriesGenericInterfaceInfo =
        runtimeInterface("Kotlin.Enums.EnumEntries")
    private val enumEntriesBase = enumEntriesGenericInterfaceInfo.canonicalClassInfo

    private val mutableListGenericInterfaceInfo =
        runtimeInterface("Kotlin.Collections.MutableList")
    private val mutableListBase = mutableListGenericInterfaceInfo.canonicalClassInfo
    val mutableListType = DotNetIlValueType.UserClass(mutableListBase)

    private val setGenericInterfaceInfo =
        runtimeInterface(
            "Kotlin.Collections.Set",
            hasRehearsalDeclaredView = true,
            hasRehearsalExactView = true,
            usesDeclaredViewByDefaultInRehearsal = true,
        )
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

    private fun openRuntimeInterfaceType(
        info: DotNetGenericInterfaceInfo,
        view: DotNetGenericInterfaceView,
    ): DotNetIlValueType.GenericInstance = DotNetIlValueType.GenericInstance(
        checkNotNull(info.classInfo(view)),
        listOf(DotNetIlValueType.TypeParameter(index = 0, isMethodParameter = false)),
    )

    init {
        listIteratorBase.interfaces = listOf(iteratorType)
        mutableIteratorBase.interfaces = listOf(iteratorType)
        mutableListIteratorBase.interfaces = listOf(listIteratorType, mutableIteratorType)
        mutableIterableBase.interfaces = listOf(iterableType)
        collectionBase.interfaces = listOf(iterableType)
        mutableCollectionBase.interfaces = listOf(collectionType, mutableIterableType)
        listBase.interfaces = listOf(collectionType)
        enumEntriesBase.interfaces = listOf(listType)
        mutableListBase.interfaces = listOf(listType, mutableCollectionType)
        setBase.interfaces = listOf(collectionType)
        mutableSetBase.interfaces = listOf(setType, mutableCollectionType)
        mutableMapBase.interfaces = listOf(mapType)
        mutableMapEntryBase.interfaces = listOf(mapEntryType)

        val declaredIterable = openRuntimeInterfaceType(
            iterableGenericInterfaceInfo,
            DotNetGenericInterfaceView.DECLARED,
        )
        val declaredIterator = openRuntimeInterfaceType(
            iteratorGenericInterfaceInfo,
            DotNetGenericInterfaceView.DECLARED,
        )
        val declaredListIterator = openRuntimeInterfaceType(
            listIteratorGenericInterfaceInfo,
            DotNetGenericInterfaceView.DECLARED,
        )
        val declaredCollection = openRuntimeInterfaceType(
            collectionGenericInterfaceInfo,
            DotNetGenericInterfaceView.DECLARED,
        )
        val exactCollection = openRuntimeInterfaceType(
            collectionGenericInterfaceInfo,
            DotNetGenericInterfaceView.EXACT,
        )
        val declaredSet = openRuntimeInterfaceType(
            setGenericInterfaceInfo,
            DotNetGenericInterfaceView.DECLARED,
        )
        val declaredList = openRuntimeInterfaceType(
            listGenericInterfaceInfo,
            DotNetGenericInterfaceView.DECLARED,
        )
        listIteratorGenericInterfaceInfo.declaredClassInfo!!.interfaces = listOf(declaredIterator)
        collectionGenericInterfaceInfo.declaredClassInfo!!.interfaces = listOf(declaredIterable)
        collectionGenericInterfaceInfo.exactClassInfo!!.interfaces = listOf(
            declaredCollection,
            declaredIterable,
        )
        setGenericInterfaceInfo.declaredClassInfo!!.interfaces = listOf(declaredCollection)
        setGenericInterfaceInfo.exactClassInfo!!.interfaces = listOf(
            declaredSet,
            exactCollection,
        )
        listGenericInterfaceInfo.declaredClassInfo!!.interfaces = listOf(declaredCollection)
        listGenericInterfaceInfo.exactClassInfo!!.interfaces = listOf(
            declaredList,
            exactCollection,
        )
    }

    private data class RuntimeGenericInterfaceMethodNames(
        val canonical: String,
        val typed: String? = null,
        val property: String? = null,
        val canonicalObjectParameterIndices: Set<Int> = emptySet(),
        val foreignTypeArgumentFalseBarrier: Boolean = false,
        val foreignCollectionContainsAllFallback: Boolean = false,
    )

    private data class RuntimeGenericInterfaceDescriptor(
        val info: DotNetGenericInterfaceInfo,
        val methods: Map<String, RuntimeGenericInterfaceMethodNames>,
    )

    private val iteratorMethods = mapOf(
        "hasNext" to RuntimeGenericInterfaceMethodNames("HasNext", typed = "HasNext"),
        "next" to RuntimeGenericInterfaceMethodNames("Next", typed = "Next"),
    )
    private val listIteratorMethods = iteratorMethods + mapOf(
        "hasPrevious" to RuntimeGenericInterfaceMethodNames("HasPrevious", typed = "HasPrevious"),
        "previous" to RuntimeGenericInterfaceMethodNames("Previous", typed = "Previous"),
        "nextIndex" to RuntimeGenericInterfaceMethodNames("NextIndex", typed = "NextIndex"),
        "previousIndex" to RuntimeGenericInterfaceMethodNames("PreviousIndex", typed = "PreviousIndex"),
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
        "iterator" to RuntimeGenericInterfaceMethodNames("GetIterator", typed = "GetIterator"),
    )
    private val mutableIterableMethods = iterableMethods
    private val collectionMethods = iterableMethods + mapOf(
        "get_size" to RuntimeGenericInterfaceMethodNames(
            canonical = "get_Size",
            typed = "get_Size",
            property = "Size",
        ),
        "isEmpty" to RuntimeGenericInterfaceMethodNames("IsEmpty", typed = "IsEmpty"),
        "contains" to RuntimeGenericInterfaceMethodNames(
            canonical = "ContainsErased",
            typed = "Contains",
            foreignTypeArgumentFalseBarrier = true,
        ),
        "containsAll" to RuntimeGenericInterfaceMethodNames(
            canonical = "ContainsAll",
            typed = "ContainsAll",
            canonicalObjectParameterIndices = setOf(0),
            foreignCollectionContainsAllFallback = true,
        ),
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
        // Canonical List owns its historical Collection-shaped slot in addition to the inherited
        // Collection(object) semantic slot. The exact sibling uses natural Collection<T>.
        "containsAll" to RuntimeGenericInterfaceMethodNames(
            canonical = "ContainsAll",
            typed = "ContainsAll",
            foreignCollectionContainsAllFallback = true,
        ),
        "get" to RuntimeGenericInterfaceMethodNames("Get", typed = "Get"),
        "indexOf" to RuntimeGenericInterfaceMethodNames(
            canonical = "IndexOfErased",
            typed = "IndexOf",
        ),
        "lastIndexOf" to RuntimeGenericInterfaceMethodNames(
            canonical = "LastIndexOfErased",
            typed = "LastIndexOf",
        ),
        "listIterator" to RuntimeGenericInterfaceMethodNames(
            "GetListIterator",
            typed = "GetListIterator",
        ),
        "subList" to RuntimeGenericInterfaceMethodNames("SubList", typed = "SubList"),
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
        "kotlin.enums.EnumEntries" to RuntimeGenericInterfaceDescriptor(
            info = enumEntriesGenericInterfaceInfo,
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

    private val kVisibilityClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.KVisibility",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    ).apply {
        // The physical Runtime enum is handwritten rather than emitted from this module, so its
        // ordinary Kotlin.Enum upcast must be present in the codegen type graph explicitly.
        baseType = DotNetIlValueType.UserClass(enumClass)
    }

    private val kClassifierBase = DotNetKClassRuntime.kClassifierClassInfo
    private val kAnnotatedElementBase = DotNetKClassRuntime.kAnnotatedElementClassInfo
    private val kDeclarationContainerBase = DotNetKClassRuntime.kDeclarationContainerClassInfo
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

    private val kPropertyAccessorBase = DotNetIlClassInfo(
        ilClassName = "Accessor",
        enclosingClass = kPropertyBase,
    )

    private val kPropertyGetterBase = DotNetIlClassInfo(
        ilClassName = "Getter",
        enclosingClass = kPropertyBase,
    )

    private val kMutablePropertyBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.KMutableProperty",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val kMutablePropertySetterBase = DotNetIlClassInfo(
        ilClassName = "Setter",
        enclosingClass = kMutablePropertyBase,
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

    private val fixedPropertyGetterClasses = List(3) { arity ->
        DotNetIlClassInfo(
            ilClassName = "Getter",
            enclosingClass = fixedPropertyClasses[arity],
        )
    }

    private val fixedMutablePropertySetterClasses = List(3) { arity ->
        DotNetIlClassInfo(
            ilClassName = "Setter",
            enclosingClass = fixedMutablePropertyClasses[arity],
        )
    }

    private val propertyReferenceFactory = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.PropertyReferenceFactory",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val memberReferenceFactory = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.MemberReferenceFactory",
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

    private val fixedFunctionClasses = List(BuiltInFunctionArity.BIG_ARITY, ::functionClassInfo)

    private val bigArityFunctionClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.FunctionN",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
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
            DotNetIlValueType.UserClass(kDeclarationContainerBase),
            DotNetIlValueType.UserClass(kAnnotatedElementBase),
            DotNetIlValueType.UserClass(kClassifierBase),
        )
        kTypeBase.interfaces = listOf(DotNetIlValueType.UserClass(kAnnotatedElementBase))
        kCallableBase.interfaces = listOf(DotNetIlValueType.UserClass(kAnnotatedElementBase))
        kFunctionBase.interfaces = listOf(
            DotNetIlValueType.UserClass(kCallableBase),
            DotNetIlValueType.UserClass(functionBase),
        )
        fixedFunctionClasses.forEach { classInfo ->
            classInfo.interfaces = listOf(DotNetIlValueType.UserClass(functionBase))
        }
        bigArityFunctionClass.interfaces = listOf(DotNetIlValueType.UserClass(functionBase))
        kPropertyBase.interfaces = listOf(DotNetIlValueType.UserClass(kCallableBase))
        kPropertyGetterBase.interfaces = listOf(
            DotNetIlValueType.UserClass(kPropertyAccessorBase),
            DotNetIlValueType.UserClass(kFunctionBase),
        )
        kMutablePropertyBase.interfaces = listOf(DotNetIlValueType.UserClass(kPropertyBase))
        kMutablePropertySetterBase.interfaces = listOf(
            DotNetIlValueType.UserClass(kPropertyAccessorBase),
            DotNetIlValueType.UserClass(kFunctionBase),
        )
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
        fixedPropertyGetterClasses.forEachIndexed { arity, classInfo ->
            classInfo.interfaces = listOf(
                DotNetIlValueType.UserClass(kPropertyGetterBase),
                DotNetIlValueType.UserClass(fixedFunctionClasses[arity]),
            )
        }
        fixedMutablePropertySetterClasses.forEachIndexed { arity, classInfo ->
            classInfo.interfaces = listOf(
                DotNetIlValueType.UserClass(kMutablePropertySetterBase),
                DotNetIlValueType.UserClass(fixedFunctionClasses[arity + 1]),
            )
        }
    }

    fun classInfoFor(
        irClass: IrClass,
        classifierInfo: DotNetClassifierInfo = DotNetClassifierInfo.derive(irClass),
    ): DotNetIlClassInfo? {
        genericInterfaceInfoFor(irClass, classifierInfo)?.let { return it.canonicalClassInfo }
        propertyAccessorClassInfoOrNull(classifierInfo.fqNameString, irClass.typeParameters.size)
            ?.let { return it }
        return when {
            classifierInfo.isCharSequence -> charSequenceClass
            classifierInfo.builtinKind == DotNetBuiltinClassifierKind.NUMBER -> numberClass
            irClass.isDotNetMutableRefStub == true -> mutableRefClass
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.ENUM -> enumClass
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.ENUM_COMPANION -> enumCompanionClass
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.ENUM_COMPANION_STATICS -> enumCompanionStaticsClass
            irClass.isDotNetFunctionReferenceBase == true -> functionReferenceBase
            irClass.isDotNetFunctionAdapter == true -> functionAdapterClass
            irClass.dotNetExactFunctionArity != null -> exactFunctionClasses[irClass.dotNetExactFunctionArity!!]
            irClass.dotNetTypedArgumentsFunctionArity != null ->
                typedArgumentsFunctionClasses[irClass.dotNetTypedArgumentsFunctionArity!!]
            irClass.isDotNetBigArityFunctionN == true -> bigArityFunctionClass
            irClass.isDotNetPropertyReferenceFactory == true -> propertyReferenceFactory
            irClass.isDotNetMemberReferenceFactory == true -> memberReferenceFactory
            irClass.isDotNetCallableAnnotationFactory == true -> callableAnnotationFactory
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_CLASSIFIER -> kClassifierBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_ANNOTATED_ELEMENT -> kAnnotatedElementBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_DECLARATION_CONTAINER ->
                kDeclarationContainerBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_CLASS -> kClassBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_TYPE -> kTypeBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_CALLABLE -> kCallableBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_VISIBILITY -> kVisibilityClass
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_FUNCTION ||
                    classifierInfo.fixedKFunctionArity != null || classifierInfo.bigKFunctionArity != null ->
                kFunctionBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_PROPERTY -> kPropertyBase
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_MUTABLE_PROPERTY -> kMutablePropertyBase
            classifierInfo.fixedKPropertyArity != null -> fixedPropertyClasses[classifierInfo.fixedKPropertyArity]
            classifierInfo.fixedKMutablePropertyArity != null ->
                fixedMutablePropertyClasses[classifierInfo.fixedKMutablePropertyArity]
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.FUNCTION -> functionBase
            classifierInfo.bigFunctionArity != null -> bigArityFunctionClass
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
        includeRehearsalDeclaredViews: Boolean = false,
    ): DotNetGenericInterfaceInfo? {
        val info = genericInterfaceDescriptorFor(irClass, classifierInfo)?.info ?: return null
        return if (includeRehearsalDeclaredViews || info.declaredClassInfo == null) {
            info
        } else {
            info.copy(
                declaredClassInfo = null,
                exactClassInfo = null,
                isDeclaredViewStableInTypedSignatures = false,
                usesDeclaredViewByDefaultInRehearsal = false,
            )
        }
    }

    /** Runtime families whose natural constructed view is the ordinary rehearsal carrier. */
    fun usesDeclaredViewByDefaultInRehearsal(irClass: IrClass): Boolean =
        genericInterfaceDescriptorFor(irClass)?.info?.usesDeclaredViewByDefaultInRehearsal == true

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

    /** KProperty interfaces use one dedicated erased runtime owner, not the split-interface ABI. */
    fun hasBuiltInPropertyInterfaceMapping(
        irClass: IrClass,
        classifierInfo: DotNetClassifierInfo = DotNetClassifierInfo.derive(irClass),
    ): Boolean = classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_PROPERTY ||
            classifierInfo.runtimeKind == DotNetRuntimeClassifierKind.K_MUTABLE_PROPERTY ||
            classifierInfo.fixedKPropertyArity != null ||
            classifierInfo.fixedKMutablePropertyArity != null

    /**
     * Runtime-owned Kotlin interfaces whose complete physical implementation contract is emitted
     * in Kotlin.Runtime's C# authoring manifest.
     */
    fun supportsCSharpSourceAuthoring(irClass: IrClass): Boolean =
        DotNetClassifierInfo.derive(irClass).isCharSequence

    /** Runtime interfaces whose ordinary CLR TypeDef fully exposes inherited C# obligations. */
    fun supportsCSharpInheritedSourceAuthoring(irClass: IrClass): Boolean {
        if (supportsCSharpSourceAuthoring(irClass)) return true
        val info = genericInterfaceDescriptorFor(irClass)?.info ?: return false
        return info.declaredClassInfo == null && info.exactClassInfo == null
    }

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
        genericInterfaceMethodNamesOrNull(function)?.typed

    fun genericInterfacePropertyNameOrNull(function: IrSimpleFunction): String? =
        genericInterfaceMethodNamesOrNull(function)?.property

    /** Zero-based regular parameters whose canonical Runtime MethodDef uses `object`. */
    fun genericInterfaceCanonicalObjectParameterIndices(function: IrSimpleFunction): Set<Int> =
        genericInterfaceMethodNamesOrNull(function)?.canonicalObjectParameterIndices.orEmpty()

    /** A natural-only foreign implementation must reject an incompatible candidate with `false`. */
    fun genericInterfaceUsesForeignTypeArgumentFalseBarrier(function: IrSimpleFunction): Boolean =
        genericInterfaceMethodNamesOrNull(function)?.foreignTypeArgumentFalseBarrier == true

    /** `containsAll` needs an element-wise fallback when two natural constructions cannot unify. */
    fun genericInterfaceUsesForeignCollectionContainsAllFallback(function: IrSimpleFunction): Boolean =
        genericInterfaceMethodNamesOrNull(function)?.foreignCollectionContainsAllFallback == true

    fun genericInterfaceFunctionInfoOrNull(
        function: IrSimpleFunction,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlFunctionInfo? {
        val interfaceClass = function.parent as? IrClass ?: return null
        val descriptor = genericInterfaceDescriptorFor(interfaceClass, typeMapper.classifierInfo(interfaceClass)) ?: return null
        val physicalMethodName = descriptor.methods[function.dotNetIlMethodName()]?.canonical ?: return null
        val mappedSignature = function.dotNetSignature(typeMapper.canonicalGenericInterfaceSignatureView())
        val objectParameters = descriptor.methods[function.dotNetIlMethodName()]
            ?.canonicalObjectParameterIndices
            .orEmpty()
        val parameterOffset = if (mappedSignature.hasThis) 1 else 0
        val canonicalSignature = if (objectParameters.isEmpty()) {
            mappedSignature
        } else {
            mappedSignature.copy(
                parameterTypes = mappedSignature.parameterTypes.mapIndexed { index, type ->
                    if (index - parameterOffset in objectParameters) DotNetIlValueType.Object else type
                },
            )
        }
        return DotNetIlFunctionInfo(
            descriptor.info.canonicalClassInfo,
            canonicalSignature,
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
            owner.isDotNetBigArityFunctionN == true &&
                    function.dotNetIlMethodName() in setOf("Invoke", "get_arity") ->
                DotNetIlFunctionInfo(
                    owner = bigArityFunctionClass,
                    signature = function.dotNetSignature(typeMapper),
                    physicalMethodName = function.dotNetIlMethodName(),
                )
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
                    ) + KFUNCTION_DECLARATION_GETTERS + KCALLABLE_DECLARATION_GETTERS ->
                DotNetIlFunctionInfo(
                    owner = functionReferenceBase,
                    signature = function.dotNetSignature(typeMapper),
                    physicalMethodName = function.name.asString(),
                )
            ownerInfo.runtimeKind == DotNetRuntimeClassifierKind.K_CALLABLE &&
                    function.dotNetIlMethodName() in
                    setOf(
                        "get_name",
                        "get_returnType",
                        "get_parameters",
                        "get_typeParameters",
                        "get_visibility",
                        "get_isFinal",
                        "get_isOpen",
                        "get_isAbstract",
                        "Call",
                        "CallBy",
                    ) ->
                DotNetIlFunctionInfo(
                    owner = kCallableBase,
                    signature = function.dotNetSignature(typeMapper),
                    physicalMethodName = function.dotNetIlMethodName(),
                )
            ownerInfo.runtimeKind == DotNetRuntimeClassifierKind.K_VISIBILITY &&
                    function.dotNetIlMethodName() in
                    setOf("values", "valueOf", "get_entries", "<EnsureInitialized>") ->
                DotNetIlFunctionInfo(
                    owner = kVisibilityClass,
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

    private fun propertyAccessorClassInfoOrNull(
        fqName: String?,
        typeArgumentCount: Int,
    ): DotNetIlClassInfo? {
        val descriptor = when (fqName) {
            "kotlin.reflect.KProperty.Accessor" -> kPropertyAccessorBase to 1
            "kotlin.reflect.KProperty.Getter" -> kPropertyGetterBase to 1
            "kotlin.reflect.KMutableProperty.Setter" -> kMutablePropertySetterBase to 1
            "kotlin.reflect.KProperty0.Getter" -> fixedPropertyGetterClasses[0] to 1
            "kotlin.reflect.KProperty1.Getter" -> fixedPropertyGetterClasses[1] to 2
            "kotlin.reflect.KProperty2.Getter" -> fixedPropertyGetterClasses[2] to 3
            "kotlin.reflect.KMutableProperty0.Setter" -> fixedMutablePropertySetterClasses[0] to 1
            "kotlin.reflect.KMutableProperty1.Setter" -> fixedMutablePropertySetterClasses[1] to 2
            "kotlin.reflect.KMutableProperty2.Setter" -> fixedMutablePropertySetterClasses[2] to 3
            else -> return null
        }
        return descriptor.first.takeIf { typeArgumentCount == descriptor.second }
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
        propertyAccessorClassInfoOrNull(info.fqNameString, simpleType.arguments.size)?.let { classInfo ->
            return DotNetIlValueType.UserClass(classInfo)
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
                } else if (info.bigFunctionArity != null) {
                    if (simpleType.arguments.size != info.bigFunctionArity + 1) return null
                    bigArityFunctionClass
                } else {
                    val kFunctionArity = info.fixedKFunctionArity
                    if (kFunctionArity != null) {
                        if (simpleType.arguments.size != kFunctionArity + 1) return null
                        kFunctionBase
                    } else if (info.bigKFunctionArity != null) {
                        if (simpleType.arguments.size != info.bigKFunctionArity + 1) return null
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
        if (info.runtimeKind == DotNetRuntimeClassifierKind.K_DECLARATION_CONTAINER && simpleType.arguments.isEmpty()) {
            return DotNetIlValueType.UserClass(kDeclarationContainerBase)
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
        if (
            (irClass.isDotNetFunctionAdapter == true || info.fqName == FUNCTION_ADAPTER_FQ_NAME) &&
            simpleType.arguments.isEmpty()
        ) {
            return DotNetIlValueType.UserClass(functionAdapterClass)
        }
        if (type.isSuspendFunction()) {
            // Match AbstractTypeMapper's JVM ABI: SuspendFunctionN<P..., R> is physically the
            // executable FunctionN+1<P..., Continuation<R>, Any?> view. Function interfaces are
            // declaration-erased on .NET, so only that extra continuation arity affects the CLR
            // carrier; the complete suspend signature remains authoritative in IR and KLIB.
            val suspendArity = simpleType.arguments.size - 1
            val executionArity = suspendArity + 1
            return DotNetIlValueType.UserClass(
                fixedFunctionClasses.getOrNull(executionArity) ?: bigArityFunctionClass
            )
        }
        if (type.isKSuspendFunction()) {
            // KSuspendFunctionN retains the orthogonal KFunction reflection identity. Its
            // executable FunctionN+1 capability is added by continuation lowering and appears
            // as a separate InterfaceImpl edge on the same generated object.
            return DotNetIlValueType.UserClass(kFunctionBase)
        }
        return mapCallableType(type, info)
    }

    fun registerCallableFunctions(
        irBuiltIns: IrBuiltIns,
        functionAdapter: DotNetFunctionAdapterSymbols,
        propertyReferenceFactoryFunctions: List<IrSimpleFunction>,
        memberReferenceFactoryFunctions: List<IrSimpleFunction>,
        callableAnnotationFactoryFunctions: List<IrSimpleFunction>,
        typeMapper: DotNetIlTypeMapper,
        availableFunctions: MutableMap<IrSimpleFunction, DotNetIlFunctionInfo>,
    ) {
        availableFunctions[functionAdapter.getFunctionDelegate] = DotNetIlFunctionInfo(
            functionAdapterClass,
            functionAdapter.getFunctionDelegate.dotNetSignature(typeMapper),
        )
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
        for (propertyName in listOf(
            "name",
            "returnType",
            "parameters",
            "typeParameters",
            "visibility",
            "isFinal",
            "isOpen",
            "isAbstract",
        )) {
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
        val kProperty = irBuiltIns.kPropertyClass.owner
        for (propertyName in listOf("isLateinit", "isConst", "getter")) {
            val getter = kProperty.properties
                .singleOrNull { property -> property.name.asString() == propertyName }
                ?.getter
                ?: continue
            availableFunctions[getter] = DotNetIlFunctionInfo(
                kPropertyBase,
                getter.dotNetSignature(typeMapper),
            )
        }
        kProperty.declarations.filterIsInstance<IrClass>()
            .singleOrNull { nested -> nested.name.asString() == "Accessor" }
            ?.properties
            ?.singleOrNull { property -> property.name.asString() == "property" }
            ?.getter
            ?.let { getter ->
                availableFunctions[getter] = DotNetIlFunctionInfo(
                    kPropertyAccessorBase,
                    getter.dotNetSignature(typeMapper),
                )
            }
        val kMutableProperty = irBuiltIns.kMutableProperty0Class.owner.superTypes
            .mapNotNull { type -> type.classOrNull?.owner }
            .single { superClass ->
                superClass.fqNameWhenAvailable?.asString() == "kotlin.reflect.KMutableProperty"
            }
        kMutableProperty.properties
            .singleOrNull { property -> property.name.asString() == "setter" }
            ?.getter
            ?.let { getter ->
                availableFunctions[getter] = DotNetIlFunctionInfo(
                    kMutablePropertyBase,
                    getter.dotNetSignature(typeMapper),
                )
            }
        for (arity in 0..2) {
            irBuiltIns.getKPropertyClass(mutable = false, n = arity).owner.properties
                .singleOrNull { property -> property.name.asString() == "getter" }
                ?.getter
                ?.let { getter ->
                    availableFunctions[getter] = DotNetIlFunctionInfo(
                        fixedPropertyClasses[arity],
                        getter.dotNetSignature(typeMapper),
                    )
                }
            irBuiltIns.getKPropertyClass(mutable = true, n = arity).owner.properties
                .singleOrNull { property -> property.name.asString() == "setter" }
                ?.getter
                ?.let { getter ->
                    availableFunctions[getter] = DotNetIlFunctionInfo(
                        fixedMutablePropertyClasses[arity],
                        getter.dotNetSignature(typeMapper),
                    )
                }
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
        val kDeclarationContainer = kClass.superTypes
            .mapNotNull { type -> type.classOrNull?.owner }
            .singleOrNull { superClass ->
                typeMapper.classifierInfo(superClass).runtimeKind ==
                        DotNetRuntimeClassifierKind.K_DECLARATION_CONTAINER
        }
        if (kDeclarationContainer != null) {
            val kClassMembersGetter = kClass.properties
                .single { property -> property.name.asString() == "members" }
                .getter
                ?: error("Internal .NET backend error: kotlin.reflect.KClass.members has no getter")
            availableFunctions[kClassMembersGetter] = DotNetIlFunctionInfo(
                kClassBase,
                kClassMembersGetter.dotNetSignature(typeMapper),
            )
            val membersGetter = kDeclarationContainer.properties
                .single { property -> property.name.asString() == "members" }
                .getter
                ?: error("Internal .NET backend error: kotlin.reflect.KDeclarationContainer.members has no getter")
            availableFunctions[membersGetter] = DotNetIlFunctionInfo(
                kDeclarationContainerBase,
                membersGetter.dotNetSignature(typeMapper),
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
        memberReferenceFactoryFunctions.forEach { factory ->
            availableFunctions[factory] = DotNetIlFunctionInfo(
                memberReferenceFactory,
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

    fun bigArityFunctionType(): DotNetIlValueType.UserClass =
        DotNetIlValueType.UserClass(bigArityFunctionClass)

    fun functionExecutionType(arity: Int): DotNetIlValueType.UserClass =
        fixedFunctionClasses.getOrNull(arity)
            ?.let(DotNetIlValueType::UserClass)
            ?: DotNetIlValueType.UserClass(bigArityFunctionClass)

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
    return arity.takeIf {
        it in 0 until BuiltInFunctionArity.BIG_ARITY && typeParameters.size == it + 1
    }
}

internal fun IrClass.dotNetFixedKFunctionArityOrNull(): Int? {
    if (!symbol.isKFunction()) return null
    val arity = name.asString().removePrefix("KFunction").toIntOrNull() ?: return null
    // Unlike kotlin.FunctionN, the synthetic KFunctionN classifiers exposed by the common
    // built-ins do not reliably carry their logical type parameters on the IrClass itself.
    // The instantiated IrSimpleType still carries, and mapCallableType validates, arity + 1
    // arguments. Class identity therefore comes from the canonical built-in FQ name here.
    return arity.takeIf { it in 0 until BuiltInFunctionArity.BIG_ARITY }
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
