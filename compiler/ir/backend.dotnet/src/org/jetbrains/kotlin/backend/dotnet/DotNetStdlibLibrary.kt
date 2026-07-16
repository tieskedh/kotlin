package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import java.io.File

/**
 * The first physical Kotlin/.NET target-stdlib boundary.
 *
 * Like `Kotlin.Runtime`, ABI major 1 is unsigned and keeps AssemblyVersion 1.0.0.0. The bootstrap
 * compiler still rebuilds this assembly beside ordinary executables. The explicit stdlib product
 * mode emits this assembly and its bound metadata KLIB from one frontend/IR run; a separate
 * consumer may then import that pair without injected implementation sources.
 */
internal object DotNetStdlibLibrary {
    const val ASSEMBLY_NAME = DotNetStdlibArtifact.ASSEMBLY_NAME
    const val ASSEMBLY_FILE_NAME = DotNetStdlibArtifact.ASSEMBLY_FILE_NAME
    const val ASSEMBLY_IL_FILE_NAME = "$ASSEMBLY_NAME.il"
    const val ASSEMBLY_VERSION = DotNetStdlibArtifact.ASSEMBLY_VERSION
    const val ASSEMBLY_VERSION_IL = "1:0:0:0"
    const val ARRAY_ITERATOR_IL_NAME = "Kotlin.Collections.ArrayIterator`1"
    const val ARRAY_ITERABLE_IL_NAME = "Kotlin.Collections.ArrayIterable`1"
    const val COLLECTIONS_FACADE_IL_NAME = "Kotlin.Collections.CollectionsKt"

    private val implementationClassIlNames = mapOf(
        "kotlin.collections.ArrayIterator" to ARRAY_ITERATOR_IL_NAME,
        "kotlin.collections.ArrayIterable" to ARRAY_ITERABLE_IL_NAME,
    )
    private val implementationFunctionFacadeIlNames = mapOf(
        "kotlin.collections.first" to COLLECTIONS_FACADE_IL_NAME,
        "kotlin.collections.last" to COLLECTIONS_FACADE_IL_NAME,
    )

    fun hasImplementation(module: IrModuleFragment): Boolean =
        module.files.any { file ->
            file.declarations.any { declaration ->
                when (declaration) {
                    is IrClass -> declaration.isDotNetStdlibImplementation
                    is IrSimpleFunction -> declaration.isDotNetStdlibImplementation
                    else -> false
                }
            }
        }

    fun implementationFileFacadeIlName(file: IrFile): String? =
        COLLECTIONS_FACADE_IL_NAME.takeIf { file.isDotNetStdlibImplementationSource }

    /** Writes deterministic IL in [outputDirectory] and assembles the target-specific stdlib PE. */
    fun assembleIn(
        outputDirectory: File,
        ilText: String,
        target: DotNetTarget,
        messageCollector: MessageCollector,
    ): File? {
        outputDirectory.mkdirs()
        val ilFile = outputDirectory.resolve(ASSEMBLY_IL_FILE_NAME)
        val output = outputDirectory.resolve(ASSEMBLY_FILE_NAME)
        output.delete()
        ilFile.writeBytes(UTF8_BOM + ilText.toByteArray(Charsets.UTF_8))
        return output.takeIf { DotNetIlAssembler.assembleLibrary(ilFile, output, target, messageCollector) }
    }

    /** Bootstrap compatibility path while ordinary executable builds still rebuild the stdlib. */
    fun assembleNextTo(
        executableOutput: File,
        ilText: String,
        target: DotNetTarget,
        messageCollector: MessageCollector,
    ): File? = assembleIn(executableOutput.parentFile ?: File("."), ilText, target, messageCollector)

    /** Constructs the closed generic stdlib iterator over a vector already on the IL stack. */
    fun arrayIteratorConstructorInstruction(elementType: DotNetIlValueType): String =
        "newobj instance void class [$ASSEMBLY_NAME]" +
                ARRAY_ITERATOR_IL_NAME.toIlIdentifier() +
                "<${elementType.nameInSignature}>::.ctor(!0[])"

    /** Constructs the closed generic stdlib Iterable view over a vector already on the IL stack. */
    fun arrayIterableConstructorInstruction(elementType: DotNetIlValueType): String =
        "newobj instance void class [$ASSEMBLY_NAME]" +
                ARRAY_ITERABLE_IL_NAME.toIlIdentifier() +
                "<${elementType.nameInSignature}>::.ctor(!0[])"

    fun implementationClassIlName(irClass: IrClass): String? {
        if ((irClass.parent as? IrFile)?.isDotNetStdlibImplementationSource != true) return null
        return irClass.fqNameWhenAvailable?.asString()?.let(implementationClassIlNames::get)
    }

    fun implementationFunctionFacadeIlName(function: IrSimpleFunction): String? {
        if ((function.parent as? IrFile)?.isDotNetStdlibImplementationSource != true) return null
        return function.fqNameWhenAvailable?.asString()?.let(implementationFunctionFacadeIlNames::get)
    }

    /** Calls an open generic `Iterable<T> -> T` stdlib method at its exact element type. */
    fun iterableElementFunctionCallInstruction(
        functionName: String,
        elementType: DotNetIlValueType,
    ): String =
        ITERABLE_ELEMENT_FUNCTION_INFO.renderCallInstruction(
            methodName = functionName,
            ownerToken = "[$ASSEMBLY_NAME]${ITERABLE_ELEMENT_FUNCTION_INFO.owner.ilTypeRef}",
            methodInstantiation = listOf(elementType),
        )

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

    internal const val IMPLEMENTATION_SOURCE_FILE_NAME = "DotNetStdlibCollections.kt"
}

/** The temporary same-module source file whose implementations are partitioned into the stdlib. */
internal val IrFile.isDotNetStdlibImplementationSource: Boolean
    get() = fileEntry.name.replace('\\', '/').substringAfterLast('/') ==
            DotNetStdlibLibrary.IMPLEMENTATION_SOURCE_FILE_NAME

/** Marker for implementation source injected by [DOTNET_STDLIB_SOURCES], never a user class. */
internal val IrClass.isDotNetStdlibImplementation: Boolean
    get() = DotNetStdlibLibrary.implementationClassIlName(this) != null

/** Marker for executable top-level stdlib source, distinct from resolution-only external stubs. */
internal val IrSimpleFunction.isDotNetStdlibImplementation: Boolean
    get() = DotNetStdlibLibrary.implementationFunctionFacadeIlName(this) != null

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
}
