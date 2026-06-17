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
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

class DotNetIlEmitter(
    private val messageCollector: MessageCollector,
    private val assemblyName: String,
) {
    fun emit(moduleFragment: IrModuleFragment): String {
        val files = moduleFragment.files.toList()
        val topLevelFunctionsByFile = files.associateWith { file ->
            file.declarations.filterIsInstance<IrSimpleFunction>()
        }
        val mainFunction = topLevelFunctionsByFile.asSequence()
            .flatMap { (file, functions) -> functions.asSequence().map { file to it } }
            .firstOrNull { (_, function) -> function.name.asString() == "main" }

        if (mainFunction == null) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "No top-level main function found; generated .NET IL contains an empty entry point."
            )
        }

        return buildString {
            appendHeader()
            if (mainFunction == null) {
                val className = files.firstOrNull()?.dotNetFileClassName() ?: "${assemblyName.toDotNetIdentifier()}Kt"
                appendLine(".class public abstract sealed auto ansi beforefieldinit $className")
                appendLine("       extends [mscorlib]System.Object")
                appendLine("{")
                appendMain(null, emptyMap())
                appendLine("}")
                return@buildString
            }

            val functionClassNames = buildMap {
                for ((file, functions) in topLevelFunctionsByFile) {
                    val className = file.dotNetFileClassName()
                    for (function in functions) {
                        if (function.isMain() || function.isSupportedStringFunction()) {
                            put(function, className)
                        }
                    }
                }
            }

            for ((file, functions) in topLevelFunctionsByFile) {
                val supportedFunctions = functions.filter { functionClassNames.containsKey(it) }
                if (supportedFunctions.isEmpty()) continue

                val className = file.dotNetFileClassName()
                appendLine(".class public abstract sealed auto ansi beforefieldinit $className")
                appendLine("       extends [mscorlib]System.Object")
                appendLine("{")
                for (function in supportedFunctions) {
                    if (function.isMain()) {
                        appendMain(function, functionClassNames)
                    } else {
                        appendStringFunction(function, functionClassNames)
                    }
                }
                appendLine("}")
            }
        }
    }

    private fun StringBuilder.appendHeader() {
        val escapedAssemblyName = assemblyName.escapeIlString()
        appendLine(".assembly extern mscorlib {}")
        appendLine(".assembly '$escapedAssemblyName' {}")
        appendLine(".module '$escapedAssemblyName.exe'")
        appendLine()
    }

    private fun StringBuilder.appendMain(
        function: IrSimpleFunction?,
        functionClassNames: Map<IrSimpleFunction, String>,
    ) {
        appendLine("  .method public hidebysig static void main() cil managed")
        appendLine("  {")
        appendLine("    .entrypoint")
        appendLine("    .maxstack 8")
        if (function != null) {
            emitBody(function, functionClassNames)
        }
        appendLine("    ret")
        appendLine("  }")
    }

    private fun StringBuilder.appendStringFunction(
        function: IrSimpleFunction,
        functionClassNames: Map<IrSimpleFunction, String>,
    ) {
        val methodName = function.name.asString().toDotNetMethodName()
        val parameterSymbols = function.parameters.map { it.symbol }
        val parameters = function.parameters.joinToString(", ") { "string ${it.name.asString().toDotNetIdentifier()}" }
        appendLine("  .method public hidebysig static string $methodName($parameters) cil managed")
        appendLine("  {")
        appendLine("    .maxstack 8")
        emitStringExpression(function.stringReturnExpression(), functionClassNames, parameterSymbols)
        appendLine("    ret")
        appendLine("  }")
    }

    private fun StringBuilder.emitBody(
        function: IrSimpleFunction,
        functionClassNames: Map<IrSimpleFunction, String>,
    ) {
        val body = function.body as? IrBlockBody
        if (body == null) {
            appendLine("    // Unsupported main body shape: ${function.body?.javaClass?.simpleName ?: "null"}")
            return
        }

        for (statement in body.statements) {
            val expression = (statement as? IrReturn)?.value ?: statement as? IrExpression
            when {
                expression is IrCall && expression.isPrintlnCall() -> emitPrintln(
                    expression,
                    functionClassNames,
                    emptyList(),
                )
                expression != null -> appendLine("    // Unsupported statement: ${expression.javaClass.simpleName}")
            }
        }
    }

    private fun StringBuilder.emitPrintln(
        call: IrCall,
        functionClassNames: Map<IrSimpleFunction, String>,
        parameterSymbols: List<IrValueSymbol>,
    ) {
        when (val argument = call.arguments.firstOrNull()) {
            is IrConst -> {
                appendLine("    ldstr \"${argument.value.toString().escapeIlString()}\"")
                appendLine("    call void [mscorlib]System.Console::WriteLine(string)")
            }
            is IrCall -> {
                emitStringExpression(argument, functionClassNames, parameterSymbols)
                appendLine("    call void [mscorlib]System.Console::WriteLine(string)")
            }
            is IrStringConcatenation -> {
                emitStringExpression(argument, functionClassNames, parameterSymbols)
                appendLine("    call void [mscorlib]System.Console::WriteLine(string)")
            }
            null -> {
                appendLine("    call void [mscorlib]System.Console::WriteLine()")
            }
            else -> appendLine("    // Unsupported println argument: ${argument.javaClass.simpleName}")
        }
    }

    private fun StringBuilder.emitStringExpression(
        expression: IrExpression?,
        functionClassNames: Map<IrSimpleFunction, String>,
        parameterSymbols: List<IrValueSymbol>,
    ) {
        when (expression) {
            is IrConst -> appendLine("    ldstr \"${expression.value.toString().escapeIlString()}\"")
            is IrGetValue -> {
                val parameterIndex = parameterSymbols.indexOf(expression.symbol)
                if (parameterIndex >= 0) {
                    appendLine("    ldarg.$parameterIndex")
                } else {
                    appendLine("    // Unsupported value reference: ${expression.symbol.owner.name.asString()}")
                    appendLine("    ldstr \"\"")
                }
            }
            is IrCall -> {
                val callee = expression.symbol.owner
                val className = functionClassNames[callee]
                if (className != null) {
                    for (argument in expression.arguments) {
                        if (argument != null) {
                            emitStringExpression(argument, functionClassNames, parameterSymbols)
                        }
                    }
                    val methodName = callee.name.asString().toDotNetMethodName()
                    appendLine("    call string $className::$methodName(${callee.stringParametersSignature()})")
                } else {
                    appendLine("    // Unsupported string call: ${callee.name.asString()}")
                    appendLine("    ldstr \"\"")
                }
            }
            is IrStringConcatenation -> {
                emitStringConcatenation(expression, functionClassNames, parameterSymbols)
            }
            else -> {
                appendLine("    // Unsupported string expression: ${expression?.javaClass?.simpleName ?: "null"}")
                appendLine("    ldstr \"\"")
            }
        }
    }

    private fun StringBuilder.emitStringConcatenation(
        expression: IrStringConcatenation,
        functionClassNames: Map<IrSimpleFunction, String>,
        parameterSymbols: List<IrValueSymbol>,
    ) {
        emitStringConcatenation(expression.arguments, functionClassNames, parameterSymbols)
    }

    private fun StringBuilder.emitStringConcatenation(
        arguments: List<IrExpression>,
        functionClassNames: Map<IrSimpleFunction, String>,
        parameterSymbols: List<IrValueSymbol>,
    ) {
        if (arguments.isEmpty()) {
            appendLine("    ldstr \"\"")
            return
        }

        emitStringExpression(arguments.first(), functionClassNames, parameterSymbols)
        for (argument in arguments.drop(1)) {
            emitStringExpression(argument, functionClassNames, parameterSymbols)
            appendLine("    call string [mscorlib]System.String::Concat(string, string)")
        }
    }

    private fun IrSimpleFunction.isMain(): Boolean {
        return name.asString() == "main"
    }

    private fun IrSimpleFunction.isSupportedStringFunction(): Boolean {
        return returnType.isDotNetStringType() &&
                parameters.all { it.type.isDotNetStringType() } &&
                stringReturnExpression()?.isSupportedStringExpression() == true
    }

    private fun IrSimpleFunction.stringReturnExpression(): IrExpression? {
        return when (val body = body) {
            is IrExpressionBody -> body.expression
            is IrBlockBody -> body.statements.asSequence()
                .mapNotNull { (it as? IrReturn)?.value ?: it as? IrExpression }
                .singleOrNull()
            else -> null
        }
    }

    private fun IrExpression.isSupportedStringExpression(): Boolean {
        return when (this) {
            is IrConst -> value is String
            is IrGetValue -> type.isDotNetStringType()
            is IrCall -> symbol.owner.isSupportedStringFunction() &&
                    arguments.filterNotNull().all { it.isSupportedStringExpression() }
            is IrStringConcatenation -> arguments.all { it.isSupportedStringExpression() }
            else -> false
        }
    }

    private fun IrSimpleFunction.stringParametersSignature(): String {
        return parameters.joinToString(", ") { "string" }
    }

    private fun IrType.isDotNetStringType(): Boolean {
        if (isString()) return true
        val typeParameter = ((this as? IrSimpleType)?.classifier as? IrTypeParameterSymbol)?.owner ?: return false
        return typeParameter.superTypes.any { it.isString() }
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

    private fun String.toDotNetMethodName(): String {
        val identifier = toDotNetIdentifier()
        return if (identifier in IL_RESERVED_METHOD_NAMES) "'$identifier'" else identifier
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

    private companion object {
        val IL_RESERVED_METHOD_NAMES = setOf("box")
    }
}
