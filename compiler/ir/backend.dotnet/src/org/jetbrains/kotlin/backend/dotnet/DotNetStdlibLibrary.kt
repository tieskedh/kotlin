package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import java.io.File

/**
 * The first physical Kotlin/.NET target-stdlib boundary.
 *
 * Like `Kotlin.Runtime`, ABI major 1 is unsigned and keeps AssemblyVersion 1.0.0.0. The bootstrap
 * compiler rebuilds this assembly beside every executable because Kotlin metadata import and
 * standalone target-stdlib compilation are not available yet.
 */
internal object DotNetStdlibLibrary {
    const val ASSEMBLY_NAME = "Kotlin.Stdlib"
    const val ASSEMBLY_FILE_NAME = "$ASSEMBLY_NAME.dll"
    const val ASSEMBLY_IL_FILE_NAME = "$ASSEMBLY_NAME.il"
    const val ASSEMBLY_VERSION_IL = "1:0:0:0"
    const val ARRAY_ITERATOR_IL_NAME = "Kotlin.Collections.ArrayIterator`1"

    fun hasImplementation(module: IrModuleFragment): Boolean =
        module.files.any { file ->
            file.declarations.filterIsInstance<IrClass>().any { it.isDotNetStdlibImplementation }
        }

    /** Writes deterministic IL beside the program and assembles the target-specific stdlib PE. */
    fun assembleNextTo(
        executableOutput: File,
        ilText: String,
        target: DotNetTarget,
        messageCollector: MessageCollector,
    ): File? {
        val outputDirectory = executableOutput.parentFile ?: File(".")
        outputDirectory.mkdirs()
        val ilFile = outputDirectory.resolve(ASSEMBLY_IL_FILE_NAME)
        val output = outputDirectory.resolve(ASSEMBLY_FILE_NAME)
        output.delete()
        ilFile.writeBytes(UTF8_BOM + ilText.toByteArray(Charsets.UTF_8))
        return output.takeIf { DotNetIlAssembler.assembleLibrary(ilFile, output, target, messageCollector) }
    }

    /** Constructs the closed generic stdlib iterator over a vector already on the IL stack. */
    fun arrayIteratorConstructorInstruction(elementType: DotNetIlValueType): String =
        "newobj instance void class [$ASSEMBLY_NAME]" +
                ARRAY_ITERATOR_IL_NAME.toIlIdentifier() +
                "<${elementType.nameInSignature}>::.ctor(!0[])"

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}

/** Marker for implementation source injected by [DOTNET_STDLIB_SOURCES], never a user class. */
internal val IrClass.isDotNetStdlibImplementation: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.collections.ArrayIterator" &&
            ((parent as? IrFile)?.fileEntry?.name?.replace('\\', '/')?.endsWith("/DotNetStdlibCollections.kt") == true ||
                    (parent as? IrFile)?.fileEntry?.name == "DotNetStdlibCollections.kt")

/** Controls whether an emitter owns user declarations or physical target-stdlib implementations. */
enum class DotNetIlEmissionScope {
    USER,
    STDLIB;

    internal fun owns(irClass: IrClass): Boolean = when (this) {
        USER -> !irClass.isDotNetStdlibImplementation
        STDLIB -> irClass.isDotNetStdlibImplementation
    }
}
