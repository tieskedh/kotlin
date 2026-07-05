package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

class DotNetIlEmitter(
    private val messageCollector: MessageCollector,
    private val assemblyName: String,
    private val moduleFileName: String,
    private val producesExecutable: Boolean,
    private val irBuiltIns: IrBuiltIns,
) {
    /**
     * Renders the module to IL text.
     *
     * Every top-level function whose signature maps to IL types is a candidate; candidates are
     * rendered to a fixpoint, so a function calling an unsupported function is itself skipped.
     * Skipped functions are reported as warnings. Returns null after reporting an error when the
     * module cannot be emitted at all (unsupported or ambiguous main, or an executable was
     * requested without a main function).
     */
    fun emit(moduleFragment: IrModuleFragment): String? {
        val intrinsicMethods = DotNetIlIntrinsicMethods(irBuiltIns)
        val files = moduleFragment.files.toList()
        val fileClassNames = buildFileClassNames(files)
        val topLevelFunctionsByFile = files.associateWith { file ->
            file.declarations.filterIsInstance<IrSimpleFunction>()
        }

        val mainFunctions = DotNetMainFunctionDetector().getMainFunctions(moduleFragment)
        if (mainFunctions.size > 1) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Ambiguous main: found ${mainFunctions.size} top-level main functions."
            )
            return null
        }
        val entryPoint = mainFunctions.singleOrNull()
        if (entryPoint == null && producesExecutable) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "No top-level main function found; a .NET executable requires one."
            )
            return null
        }

        val availableFunctions = LinkedHashMap<IrSimpleFunction, DotNetIlFunctionInfo>()
        val skipReasons = LinkedHashMap<IrSimpleFunction, String>()
        for ((file, functions) in topLevelFunctionsByFile) {
            val className = fileClassNames.getValue(file)
            for (function in functions) {
                if (intrinsicMethods.getIntrinsic(function.symbol)?.excludesDeclarationFromCodegen == true) continue
                try {
                    availableFunctions[function] = DotNetIlFunctionInfo(className, function.dotNetSignature())
                } catch (e: DotNetIlUnsupportedException) {
                    skipReasons[function] = e.reason
                }
            }
        }

        val renderedMethods = LinkedHashMap<IrSimpleFunction, DotNetIlRenderedMethod>()
        do {
            renderedMethods.clear()
            var anyFunctionRemoved = false
            for (function in availableFunctions.keys.toList()) {
                try {
                    renderedMethods[function] = DotNetIlMethodCodegen(
                        function = function,
                        functionInfo = availableFunctions.getValue(function),
                        isEntryPoint = function == entryPoint,
                        availableFunctions = availableFunctions,
                        intrinsicMethods = intrinsicMethods,
                    ).render()
                } catch (e: DotNetIlUnsupportedException) {
                    availableFunctions.remove(function)
                    skipReasons[function] = e.reason
                    anyFunctionRemoved = true
                }
            }
        } while (anyFunctionRemoved)

        for ((function, reason) in skipReasons) {
            if (function == entryPoint) continue
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Function '${function.diagnosticName()}' is not supported by the .NET backend and was skipped: $reason"
            )
        }
        if (entryPoint != null && entryPoint !in availableFunctions) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "The main function is not supported by the .NET backend: ${skipReasons[entryPoint]}"
            )
            return null
        }

        return buildString {
            appendHeader()
            val requiredHelpers = linkedSetOf<DotNetIlRuntimeHelper>()
            for ((file, functions) in topLevelFunctionsByFile) {
                val methods = functions.mapNotNull { renderedMethods[it] }
                if (methods.isEmpty()) continue
                methods.flatMapTo(requiredHelpers) { it.requiredRuntimeHelpers }
                DotNetIlClassCodegen(fileClassNames.getValue(file), methods.map { it.ilText }).generate(this)
            }
            // The shared runtime helper class (see DotNetIlRuntimeHelper) comes last, and only
            // when some emitted method actually called one of its helpers.
            if (requiredHelpers.isNotEmpty()) {
                DotNetIlClassCodegen(
                    DOTNET_RUNTIME_HELPER_CLASS_NAME,
                    requiredHelpers.map { it.methodIlText },
                    exported = false,
                ).generate(this)
            }
        }
    }

    /**
     * Precomputes the file class name for every file: the package-qualified dotted name
     * (`pkg.fileKt`) rendered later as a single quoted identifier, with a numeric suffix
     * deduplicating files that share both package and file name.
     */
    private fun buildFileClassNames(files: List<IrFile>): Map<IrFile, String> {
        val usedNames = hashSetOf<String>()
        return files.associateWith { file ->
            val fileName = file.fileEntry.name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
            val packageFqName = file.packageFqName
            val baseName = if (packageFqName.isRoot) "${fileName}Kt" else "${packageFqName.asString()}.${fileName}Kt"
            var className = baseName
            var suffix = 1
            while (!usedNames.add(className)) {
                className = "$baseName${suffix++}"
            }
            className
        }
    }

    private fun IrSimpleFunction.diagnosticName(): String =
        fqNameWhenAvailable?.asString() ?: name.asString()

    private fun StringBuilder.appendHeader() {
        appendLine(".assembly extern $CORE_LIB {}")
        appendLine(".assembly ${assemblyName.toIlIdentifier()} {}")
        appendLine(".module ${moduleFileName.toIlIdentifier()}")
        appendLine()
    }
}
