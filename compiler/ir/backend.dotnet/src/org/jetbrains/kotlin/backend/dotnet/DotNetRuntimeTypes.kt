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
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.invokeFun
import org.jetbrains.kotlin.ir.util.isKFunction
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
 * Kotlin Iterable, Iterator, and the five currently supported primitive Iterator subclasses map
 * to non-generic Kotlin-owned execution interfaces. Iterator's object-shaped Next slot preserves
 * reference identity across Kotlin's covariant views, including value-element instantiations that
 * CLR generic variance cannot convert. Iterable returns that same erased Iterator identity. The
 * compiler narrows each result back to the logical Kotlin element type. CLR IEnumerable and
 * IEnumerator remain explicit interop concerns.
 */
internal object DotNetRuntimeTypes {
    val DEFAULT_CONSTRUCTOR_MARKER_FQ_NAME = FqName("kotlin.runtime.internal.DefaultConstructorMarker")

    private val unitClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Unit",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
    val unitType = DotNetIlValueType.UserClass(unitClass)

    private val functionBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.Function",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val iteratorBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.Collections.Iterator",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
    val iteratorType = DotNetIlValueType.UserClass(iteratorBase)

    private val iterableBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.Collections.Iterable",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
    val iterableType = DotNetIlValueType.UserClass(iterableBase)

    private val kCallableBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.KCallable",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

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

    private val functionReferenceBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.FunctionReferenceBase",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val mutableRefClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.MutableRef`1",
        typeParameterVariances = listOf(Variance.INVARIANT),
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val defaultConstructorMarkerClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.DefaultConstructorMarker",
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

    fun classInfoFor(irClass: IrClass): DotNetIlClassInfo? = when {
        irClass.isDotNetMutableRefStub == true -> mutableRefClass
        irClass.isDotNetFunctionReferenceBase == true -> functionReferenceBase
        irClass.dotNetExactFunctionArity != null -> exactFunctionClasses[irClass.dotNetExactFunctionArity!!]
        irClass.dotNetTypedArgumentsFunctionArity != null ->
            typedArgumentsFunctionClasses[irClass.dotNetTypedArgumentsFunctionArity!!]
        irClass.isDotNetIteratorBase || irClass.isDotNetSupportedPrimitiveIterator -> iteratorBase
        irClass.isDotNetIterableBase -> iterableBase
        irClass.isDotNetPropertyReferenceFactory == true -> propertyReferenceFactory
        irClass.isDotNetKCallableBase -> kCallableBase
        irClass.isDotNetKFunctionBase || irClass.dotNetFixedKFunctionArityOrNull() != null -> kFunctionBase
        irClass.isDotNetKPropertyBase -> kPropertyBase
        irClass.isDotNetKMutablePropertyBase -> kMutablePropertyBase
        irClass.dotNetFixedKPropertyArityOrNull() != null ->
            fixedPropertyClasses[irClass.dotNetFixedKPropertyArityOrNull()!!]
        irClass.dotNetFixedKMutablePropertyArityOrNull() != null ->
            fixedMutablePropertyClasses[irClass.dotNetFixedKMutablePropertyArityOrNull()!!]
        irClass.isDotNetFunctionBase -> functionBase
        else -> irClass.dotNetFixedFunctionArityOrNull()?.let(fixedFunctionClasses::get)
    }

    fun mapCallableType(type: IrType): DotNetIlValueType.UserClass? {
        val simpleType = type as? IrSimpleType ?: return null
        val irClass = simpleType.classifier.owner as? IrClass ?: return null
        val classInfo = when {
            irClass.isDotNetFunctionBase -> {
                if (simpleType.arguments.size != 1) return null
                functionBase
            }
            irClass.isDotNetKCallableBase -> {
                if (simpleType.arguments.size != 1) return null
                kCallableBase
            }
            irClass.isDotNetKFunctionBase -> {
                if (simpleType.arguments.size != 1) return null
                kFunctionBase
            }
            irClass.isDotNetKPropertyBase -> {
                if (simpleType.arguments.size != 1) return null
                kPropertyBase
            }
            irClass.isDotNetKMutablePropertyBase -> {
                if (simpleType.arguments.size != 1) return null
                kMutablePropertyBase
            }
            else -> {
                val functionArity = irClass.dotNetFixedFunctionArityOrNull()
                if (functionArity != null) {
                    if (simpleType.arguments.size != functionArity + 1) return null
                    fixedFunctionClasses[functionArity]
                } else {
                    val kFunctionArity = irClass.dotNetFixedKFunctionArityOrNull()
                    if (kFunctionArity != null) {
                        if (simpleType.arguments.size != kFunctionArity + 1) return null
                        kFunctionBase
                    } else {
                        val propertyArity = irClass.dotNetFixedKPropertyArityOrNull()
                        if (propertyArity != null) {
                            if (simpleType.arguments.size != propertyArity + 1) return null
                            fixedPropertyClasses[propertyArity]
                        } else {
                            val mutablePropertyArity = irClass.dotNetFixedKMutablePropertyArityOrNull() ?: return null
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

    fun mapCompilerRuntimeType(type: IrType): DotNetIlValueType.UserClass? {
        val simpleType = type as? IrSimpleType ?: return null
        val irClass = simpleType.classifier.owner as? IrClass ?: return null
        if (irClass.fqNameWhenAvailable == DEFAULT_CONSTRUCTOR_MARKER_FQ_NAME && simpleType.arguments.isEmpty()) {
            return DotNetIlValueType.UserClass(defaultConstructorMarkerClass)
        }
        if (irClass.isDotNetIteratorBase && simpleType.arguments.size == 1) return iteratorType
        if (irClass.isDotNetSupportedPrimitiveIterator && simpleType.arguments.isEmpty()) return iteratorType
        if (irClass.isDotNetIterableBase && simpleType.arguments.size == 1) return iterableType
        return mapCallableType(type)
    }

    fun registerCallableFunctions(
        irBuiltIns: IrBuiltIns,
        propertyReferenceFactoryFunctions: List<IrSimpleFunction>,
        typeMapper: DotNetIlTypeMapper,
        availableFunctions: MutableMap<IrSimpleFunction, DotNetIlFunctionInfo>,
    ) {
        for (arity in fixedFunctionClasses.indices) {
            val invoke = irBuiltIns.functionN(arity).invokeFun
                ?: error("Internal .NET backend error: kotlin.Function$arity has no invoke member")
            availableFunctions[invoke] = DotNetIlFunctionInfo(
                fixedFunctionClasses[arity],
                invoke.dotNetSignature(typeMapper),
            )
        }
        val nameGetter = irBuiltIns.kCallableClass.owner.properties
            .single { it.name.asString() == "name" }
            .getter
            ?: error("Internal .NET backend error: kotlin.reflect.KCallable.name has no getter")
        availableFunctions[nameGetter] = DotNetIlFunctionInfo(
            kCallableBase,
            nameGetter.dotNetSignature(typeMapper),
        )
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

private val IrClass.isDotNetKFunctionBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.reflect.KFunction" && typeParameters.size == 1

private val IrClass.isDotNetKPropertyBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.reflect.KProperty" && typeParameters.size == 1

private val IrClass.isDotNetKMutablePropertyBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.reflect.KMutableProperty" && typeParameters.size == 1

internal val IrClass.isDotNetIteratorBase: Boolean
    // Metadata-KLIB classifiers may be represented by a minimal external IR class whose own
    // type-parameter list is not populated. Validate the closed use-site arity in
    // mapCompilerRuntimeType instead; source declarations retain the defensive arity check.
    get() = fqNameWhenAvailable?.asString() == "kotlin.collections.Iterator" &&
            (typeParameters.size == 1 || origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB)

internal val IrClass.isDotNetIterableBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.collections.Iterable" &&
            (typeParameters.size == 1 || origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB)

internal val IrClass.isDotNetSupportedPrimitiveIterator: Boolean
    get() = fqNameWhenAvailable?.asString() in DOTNET_SUPPORTED_PRIMITIVE_ITERATOR_FQ_NAMES && typeParameters.isEmpty()

private val DOTNET_SUPPORTED_PRIMITIVE_ITERATOR_FQ_NAMES = setOf(
    "kotlin.collections.IntIterator",
    "kotlin.collections.LongIterator",
    "kotlin.collections.DoubleIterator",
    "kotlin.collections.BooleanIterator",
    "kotlin.collections.CharIterator",
)

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
