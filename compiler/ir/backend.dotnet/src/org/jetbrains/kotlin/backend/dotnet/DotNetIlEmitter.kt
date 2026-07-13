package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

class DotNetIlEmitter(
    private val messageCollector: MessageCollector,
    private val assemblyName: String,
) {
    fun emit(moduleFragment: IrModuleFragment): String {
        val files = moduleFragment.files.toList()
        val mainFunction = files.asSequence()
            .flatMap { file -> file.declarations.asSequence().filterIsInstance<IrSimpleFunction>().map { file to it } }
            .firstOrNull { (_, function) -> function.name.asString() == "main" }

        if (mainFunction == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "No top-level main function found; generated .NET IL contains an empty entry point."
            )
        }

        return buildString {
            appendHeader()
            val file = mainFunction?.first ?: files.firstOrNull()
            val className = file?.dotNetFileClassName() ?: "${assemblyName.toDotNetIdentifier()}Kt"
            appendLine(".class public abstract sealed auto ansi beforefieldinit $className")
            appendLine("       extends [mscorlib]System.Object")
            appendLine("{")
            appendMain(mainFunction?.second)
            appendLine("}")
        }
    }

    private fun StringBuilder.appendHeader() {
        val escapedAssemblyName = assemblyName.escapeIlString()
        appendLine(".assembly extern mscorlib {}")
        appendLine(".assembly '$escapedAssemblyName' {}")
        appendLine(".module '$escapedAssemblyName.exe'")
        appendLine()
    }

    private fun StringBuilder.appendMain(function: IrSimpleFunction?) {
        appendLine("  .method public hidebysig static void main() cil managed")
        appendLine("  {")
        appendLine("    .entrypoint")
        appendLine("    .maxstack 8")
        if (function != null) {
            emitBody(function)
        }
        appendLine("    ret")
        appendLine("  }")
    }

    private fun StringBuilder.emitBody(function: IrSimpleFunction) {
        val body = function.body as? IrBlockBody
        if (body == null) {
            appendLine("    // Unsupported main body shape: ${function.body?.javaClass?.simpleName ?: "null"}")
            return
        }

        for (statement in body.statements) {
            val expression = (statement as? IrReturn)?.value ?: statement as? IrExpression
            when {
                expression is IrCall && expression.isPrintlnCall() -> emitPrintln(expression)
                expression != null -> appendLine("    // Unsupported statement: ${expression.javaClass.simpleName}")
            }
        }
    }

    private fun StringBuilder.emitPrintln(call: IrCall) {
        when (val argument = call.arguments.firstOrNull()) {
            is IrConst -> {
                appendLine("    ldstr \"${argument.value.toString().escapeIlString()}\"")
                appendLine("    call void [mscorlib]System.Console::WriteLine(string)")
            }
            null -> {
                appendLine("    call void [mscorlib]System.Console::WriteLine()")
            }
            else -> appendLine("    // Unsupported println argument: ${argument.javaClass.simpleName}")
        }
    }

    private fun IrCall.isPrintlnCall(): Boolean {
        val functionName = symbol.owner.name.asString()
        val fqName = symbol.owner.fqNameWhenAvailable?.asString()
        return functionName == "println" && fqName?.startsWith("kotlin.io.") == true
    }

    private fun IrFile.dotNetFileClassName(): String {
        val fileName = fileEntry.name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        return "${fileName.toDotNetIdentifier()}Kt"
    }

    private fun String.toDotNetIdentifier(): String {
        val sanitized = buildString {
            for (char in this@toDotNetIdentifier) {
                append(if (char.isLetterOrDigit() || char == '_') char else '_')
            }
        }
        return sanitized.ifEmpty { "KotlinModule" }.let {
            if (it.first().isDigit()) "_$it" else it
        }
    }

    private fun String.escapeIlString(): String = buildString {
        for (char in this@escapeIlString) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
