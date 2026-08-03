package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isPublishedApi
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
    const val ARRAY_AS_LIST_IL_NAME = "Kotlin.Collections.ArrayAsList`1"
    const val ARRAY_AS_LIST_ITERATOR_IL_NAME = "Kotlin.Collections.ArrayAsListIterator`1"
    const val ARRAY_ITERATOR_IL_NAME = "Kotlin.Collections.ArrayIterator`1"
    const val ARRAY_ITERABLE_IL_NAME = "Kotlin.Collections.ArrayIterable`1"
    const val ERASED_ARRAY_ITERATOR_IL_NAME = "Kotlin.Collections.ErasedArrayIterator"
    const val ERASED_ARRAY_ITERABLE_IL_NAME = "Kotlin.Collections.ErasedArrayIterable"
    const val EMPTY_ITERATOR_IL_NAME = "Kotlin.Collections.EmptyIterator"
    const val EMPTY_LIST_IL_NAME = "Kotlin.Collections.EmptyList"
    const val RANDOM_ACCESS_IL_NAME = "Kotlin.Collections.RandomAccess"
    const val SERIALIZABLE_IL_NAME = "Kotlin.Io.Serializable"
    const val READ_AFTER_EOF_EXCEPTION_IL_NAME = "Kotlin.Io.ReadAfterEOFException"
    const val COLLECTIONS_FACADE_IL_NAME = "Kotlin.Collections.CollectionsKt"
    const val IO_FACADE_IL_NAME = "Kotlin.Io.ConsoleKt"
    const val THROW_NO_WHEN_BRANCH_MATCHED_FACADE_IL_NAME =
        "kotlin.internal.DotNetThrowNoWhenBranchMatchedExceptionKt"
    const val EXCEPTIONS_FACADE_IL_NAME = "Kotlin.DotNetExceptionsKt"
    const val ARRAY_ITERATOR_FACTORY_NAME = "dotNetArrayIterator"
    const val ARRAY_ITERABLE_FACTORY_NAME = "dotNetArrayIterable"
    const val ERASED_ARRAY_ITERATOR_FACTORY_NAME = "dotNetErasedArrayIterator"
    const val ERASED_ARRAY_ITERABLE_FACTORY_NAME = "dotNetErasedArrayIterable"

    private val implementationClassIlNames = mapOf(
        "kotlin.collections.ArrayAsList" to ARRAY_AS_LIST_IL_NAME,
        "kotlin.collections.ArrayAsListIterator" to ARRAY_AS_LIST_ITERATOR_IL_NAME,
        "kotlin.collections.ArrayIterator" to ARRAY_ITERATOR_IL_NAME,
        "kotlin.collections.ArrayIterable" to ARRAY_ITERABLE_IL_NAME,
        "kotlin.collections.ErasedArrayIterator" to ERASED_ARRAY_ITERATOR_IL_NAME,
        "kotlin.collections.ErasedArrayIterable" to ERASED_ARRAY_ITERABLE_IL_NAME,
        "kotlin.collections.EmptyIterator" to EMPTY_ITERATOR_IL_NAME,
        "kotlin.collections.EmptyList" to EMPTY_LIST_IL_NAME,
        "kotlin.collections.RandomAccess" to RANDOM_ACCESS_IL_NAME,
        "kotlin.io.Serializable" to SERIALIZABLE_IL_NAME,
        "kotlin.io.ReadAfterEOFException" to READ_AFTER_EOF_EXCEPTION_IL_NAME,
        "kotlin.SuppressedExceptionList" to "Kotlin.SuppressedExceptionList",
        "kotlin.SuppressedExceptionIterator" to "Kotlin.SuppressedExceptionIterator",
        "kotlin.internal.SharedVariableBox" to "Kotlin.Internal.SharedVariableBox`1",
        "kotlin.internal.SharedVariableBoxBoolean" to "Kotlin.Internal.SharedVariableBoxBoolean",
        "kotlin.internal.SharedVariableBoxByte" to "Kotlin.Internal.SharedVariableBoxByte",
        "kotlin.internal.SharedVariableBoxShort" to "Kotlin.Internal.SharedVariableBoxShort",
        "kotlin.internal.SharedVariableBoxInt" to "Kotlin.Internal.SharedVariableBoxInt",
        "kotlin.internal.SharedVariableBoxLong" to "Kotlin.Internal.SharedVariableBoxLong",
        "kotlin.internal.SharedVariableBoxFloat" to "Kotlin.Internal.SharedVariableBoxFloat",
        "kotlin.internal.SharedVariableBoxDouble" to "Kotlin.Internal.SharedVariableBoxDouble",
        "kotlin.internal.SharedVariableBoxChar" to "Kotlin.Internal.SharedVariableBoxChar",
        "kotlin.internal.SyntheticConstructorMarker" to "Kotlin.Internal.SyntheticConstructorMarker",
    )
    private val implementationFunctionFacadeIlNames = mapOf(
        "kotlin.collections.all" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.any" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.average" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.asList" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.arrayOfNulls" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.checkCountOverflow" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.checkIndexOverflow" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.collectionToArray" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.collectionToArrayCommonImpl" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.contains" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.count" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.elementAtOrNull" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.emptyList" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.first" to COLLECTIONS_FACADE_IL_NAME,
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
        "kotlin.collections.none" to COLLECTIONS_FACADE_IL_NAME,
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
        "kotlin.collections.terminateCollectionToArray" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.throwCountOverflow" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.throwIndexOverflow" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.$ARRAY_ITERATOR_FACTORY_NAME" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.$ARRAY_ITERABLE_FACTORY_NAME" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.$ERASED_ARRAY_ITERATOR_FACTORY_NAME" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.$ERASED_ARRAY_ITERABLE_FACTORY_NAME" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.io.readln" to IO_FACADE_IL_NAME,
        "kotlin.io.readlnOrNull" to IO_FACADE_IL_NAME,
        "kotlin.internal.throwNoWhenBranchMatchedException" to THROW_NO_WHEN_BRANCH_MATCHED_FACADE_IL_NAME,
        "kotlin.stackTraceToString" to EXCEPTIONS_FACADE_IL_NAME,
        "kotlin.printStackTrace" to EXCEPTIONS_FACADE_IL_NAME,
        "kotlin.addSuppressed" to EXCEPTIONS_FACADE_IL_NAME,
    )
    private val implementationPropertyFacadeIlNames = mapOf(
        "kotlin.collections.lastIndex" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.suppressedExceptions" to EXCEPTIONS_FACADE_IL_NAME,
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
    fun arrayIteratorFactoryCallInstruction(elementType: DotNetIlValueType): String =
        ARRAY_ITERATOR_FACTORY_INFO.renderStdlibCall(ARRAY_ITERATOR_FACTORY_NAME, elementType)

    /** Calls the stdlib-owned Iterable factory for a vector already on the IL stack. */
    fun arrayIterableFactoryCallInstruction(elementType: DotNetIlValueType): String =
        ARRAY_ITERABLE_FACTORY_INFO.renderStdlibCall(ARRAY_ITERABLE_FACTORY_NAME, elementType)

    fun erasedArrayIteratorFactoryCallInstruction(coreLibraryReference: String): String =
        "call class [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]${"Kotlin.Collections.Iterator".toIlIdentifier()} " +
                "[$ASSEMBLY_NAME]${COLLECTIONS_FACADE_IL_NAME.toIlIdentifier()}::" +
                "${ERASED_ARRAY_ITERATOR_FACTORY_NAME.toIlIdentifier()}(" +
                "class ${coreLibraryReference}System.Array)"

    fun erasedArrayIterableFactoryCallInstruction(coreLibraryReference: String): String =
        "call class [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]${"Kotlin.Collections.Iterable".toIlIdentifier()} " +
                "[$ASSEMBLY_NAME]${COLLECTIONS_FACADE_IL_NAME.toIlIdentifier()}::" +
                "${ERASED_ARRAY_ITERABLE_FACTORY_NAME.toIlIdentifier()}(" +
                "class ${coreLibraryReference}System.Array)"

    fun implementationClassIlName(irClass: IrClass): String? {
        if ((irClass.parent as? IrFile)?.isDotNetStdlibImplementationSource != true) return null
        return irClass.fqNameWhenAvailable?.asString()?.let(implementationClassIlNames::get)
    }

    /** Public target-stdlib declarations referenced while bootstrap sources remain same-module. */
    fun publicImplementationClassInfoOrNull(irClass: IrClass): DotNetIlClassInfo? {
        if (irClass.visibility != DescriptorVisibilities.PUBLIC && !irClass.isPublishedApi()) return null
        val ilName = implementationClassIlName(irClass) ?: return null
        return DotNetIlClassInfo(
            ilName,
            typeParameterVariances = irClass.typeParameters.map { it.variance },
            assemblyName = ASSEMBLY_NAME,
        )
    }

    fun implementationFunctionFacadeIlName(function: IrSimpleFunction): String? {
        if ((function.parent as? IrFile)?.isDotNetStdlibImplementationSource != true) return null
        return function.fqNameWhenAvailable?.asString()?.let(implementationFunctionFacadeIlNames::get)
            ?: function.correspondingPropertySymbol?.owner?.let(::implementationPropertyFacadeIlName)
    }

    fun implementationPropertyFacadeIlName(property: IrProperty): String? {
        if ((property.parent as? IrFile)?.isDotNetStdlibImplementationSource != true) return null
        return property.fqNameWhenAvailable?.asString()?.let(implementationPropertyFacadeIlNames::get)
    }

    /** Selects a pinned Common-generator spelling for a bounded erased stdlib overload family. */
    fun implementationPlatformMethodNameOrNull(function: IrSimpleFunction): String? {
        val functionFqName = function.fqNameWhenAvailable?.asString() ?: return null
        val platformNames = signedIterableNumericPlatformNames[functionFqName] ?: return null
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
        val elementFqName = elementType.classFqName?.asString()
        return platformNames[elementFqName]
            ?: dotNetUnsupported(
                "Common Iterable.$logicalName element '${elementType.render()}' has no pinned CLR method name"
            )
    }

    /** Crosses from a bootstrap user assembly to an ordinary function emitted in Kotlin.Stdlib. */
    fun implementationFunctionInfoOrNull(
        function: IrSimpleFunction,
        typeMapper: DotNetIlTypeMapper,
    ): DotNetIlFunctionInfo? {
        val owner = (function.parent as? IrClass)?.let(::publicImplementationClassInfoOrNull)
            ?: implementationFunctionFacadeIlName(function)?.let { facadeName ->
                DotNetIlClassInfo(facadeName, assemblyName = ASSEMBLY_NAME)
            }
            ?: return null
        return DotNetIlFunctionInfo(
            owner = owner,
            signature = function.dotNetSignature(typeMapper),
            physicalMethodName = function.dotNetAbiMethodName(
                isSplitGenericClass = typeMapper::isSplitGenericClass,
            ),
        )
    }

    /** Calls an open generic `Iterable<T>/List<T> -> T` stdlib method at its exact element type. */
    fun collectionElementFunctionCallInstruction(
        functionName: String,
        receiverType: DotNetIlValueType,
        elementType: DotNetIlValueType,
    ): String {
        val functionInfo = when (receiverType) {
            DotNetRuntimeTypes.iterableType -> ITERABLE_ELEMENT_FUNCTION_INFO
            DotNetRuntimeTypes.listType -> LIST_ELEMENT_FUNCTION_INFO
            else -> error("Internal .NET backend error: unsupported stdlib element receiver $receiverType")
        }
        return functionInfo.renderCallInstruction(
            methodName = functionName,
            ownerToken = "[$ASSEMBLY_NAME]${functionInfo.owner.ilTypeRef}",
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
    ): String = renderCallInstruction(
        methodName = methodName,
        ownerToken = "[$ASSEMBLY_NAME]${owner.ilTypeRef}",
        methodInstantiation = listOf(elementType),
    )

    private data class ImplementationSource(
        val packageFqName: String,
        val facadeIlName: String? = null,
    )

    private val implementationSources = mapOf(
        "DotNetStdlibCollections.kt" to ImplementationSource(
            packageFqName = "kotlin.collections",
            facadeIlName = COLLECTIONS_FACADE_IL_NAME,
        ),
        "_DotNetBootstrapCollections.kt" to ImplementationSource(
            packageFqName = "kotlin.collections",
            facadeIlName = COLLECTIONS_FACADE_IL_NAME,
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
        "SharedVariableBox.kt" to ImplementationSource(packageFqName = "kotlin.internal"),
        "SyntheticConstructorMarker.kt" to ImplementationSource(packageFqName = "kotlin.internal"),
    )
    private val resolutionOnlySources = mapOf(
        "Annotations.kt" to "kotlin.internal",
        "JvmAnnotationsH.kt" to "kotlin.jvm",
        "Multiplatform.kt" to "kotlin",
    )

    internal fun isImplementationSource(file: IrFile): Boolean =
        implementationSources[file.implementationSourceFileName]
            ?.packageFqName == file.packageFqName.asString()

    internal fun isResolutionOnlySource(file: IrFile): Boolean =
        resolutionOnlySources[file.implementationSourceFileName] == file.packageFqName.asString()
}

private val IrFile.implementationSourceFileName: String
    get() = fileEntry.name.replace('\\', '/').substringAfterLast('/')

/** Temporary same-module source files whose implementations are partitioned into the stdlib. */
internal val IrFile.isDotNetStdlibImplementationSource: Boolean
    get() = DotNetStdlibLibrary.isImplementationSource(this)

/** Target-bootstrap declarations needed for frontend/KLIB resolution but never physical IL. */
internal val IrClass.isDotNetResolutionOnlyStdlibDeclaration: Boolean
    get() = DotNetMappedExceptions.isExceptionStdlibDeclaration(this) ||
            (parent as? IrFile)?.let(DotNetStdlibLibrary::isResolutionOnlySource) == true

/** Marker for a product or fallback stdlib implementation declaration, never a user class. */
internal val IrClass.isDotNetStdlibImplementation: Boolean
    get() = DotNetStdlibLibrary.implementationClassIlName(this) != null

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
