package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.render

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
     * Every top-level function whose signature maps to IL types and every top-level class that
     * passes the [shape gate][checkClassShapeSupported] is a candidate; candidates are rendered
     * to a fixpoint, so a function calling an unsupported function is itself skipped, and
     * removing a class (any unrenderable member removes the whole class — a class with, say, an
     * unrenderable constructor must not remain referenceable) cascades through the
     * [DotNetIlTypeMapper] to every declaration whose types mention it. Skipped declarations are
     * reported as warnings. Returns null after reporting an error when the module cannot be
     * emitted at all (unsupported or ambiguous main, or an executable was requested without a
     * main function).
     */
    fun emit(moduleFragment: IrModuleFragment): String? {
        val intrinsicMethods = DotNetIlIntrinsicMethods(irBuiltIns)
        val files = moduleFragment.files.toList()
        val topLevelClassesByFile = files.associateWith { file ->
            file.declarations.filterIsInstance<IrClass>()
        }
        val fileClassNames = buildFileClassNames(files, topLevelClassesByFile)
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

        // Class pre-pass: the shape gate. Everything outside the final-class model is rejected
        // whole-class here, so the type mapper only ever sees supported classes. The injected
        // exception declarations (see DotNetMappedExceptions) are excluded up front — the
        // class-level parallel of an intrinsic's excludesDeclarationFromCodegen: they exist for
        // frontend resolution only and must be neither emitted nor skip-warned.
        val availableClasses = LinkedHashMap<IrClass, DotNetIlClassInfo>()
        val classSkipReasons = LinkedHashMap<IrClass, String>()
        for (irClass in topLevelClassesByFile.values.flatten()) {
            if (DotNetMappedExceptions.isExceptionStdlibDeclaration(irClass)) continue
            try {
                checkClassShapeSupported(irClass)
                availableClasses[irClass] = DotNetIlClassInfo(irClass.fqNameWhenAvailable!!.asString())
            } catch (e: DotNetIlUnsupportedException) {
                classSkipReasons[irClass] = e.reason
            }
        }
        val typeMapper = DotNetIlTypeMapper(availableClasses)

        val availableFunctions = LinkedHashMap<IrSimpleFunction, DotNetIlFunctionInfo>()
        val skipReasons = LinkedHashMap<IrSimpleFunction, String>()
        for ((file, functions) in topLevelFunctionsByFile) {
            val className = fileClassNames.getValue(file)
            for (function in functions) {
                if (intrinsicMethods.getIntrinsic(function.symbol)?.excludesDeclarationFromCodegen == true) continue
                try {
                    availableFunctions[function] = DotNetIlFunctionInfo(className, function.dotNetSignature(typeMapper))
                } catch (e: DotNetIlUnsupportedException) {
                    skipReasons[function] = e.reason
                }
            }
        }
        // Member pre-pass: the instance methods and property accessors of every available class
        // become call-resolvable before any body is rendered, so that a round-one caller finds a
        // member of a class rendered later in the same round. A member signature failure removes
        // the whole class — the same granularity as every other member failure. The same pass
        // gates CLR method-identity clashes: accessor mangling can make a user-declared
        // `fun get_x(): Int` collide with the getter of `val x` — the same IL name and parameter
        // list — which ilasm rejects as a duplicate method declaration (probe-verified on the
        // modern ilasm 10.0.9) and which would make every call site ambiguous. The JVM frontend
        // reports the analogous accidental `getX` clash as PLATFORM_DECLARATION_CLASH; this
        // backend has no target-specific frontend checkers yet, so the clash is rejected here,
        // whole-class. The return type is deliberately not part of the identity key:
        // return-type-only overloads are CLS-forbidden and unverified against ilasm, so they are
        // conservatively treated as the same clash.
        for ((irClass, classInfo) in availableClasses.entries.toList()) {
            try {
                val membersByIlIdentity = hashMapOf<String, IrSimpleFunction>()
                for (member in irClass.dotNetMemberFunctions()) {
                    val signature = member.dotNetSignature(typeMapper)
                    val ilIdentity = "${member.dotNetIlMethodName()}(${signature.renderParameterTypes()})"
                    membersByIlIdentity.put(ilIdentity, member)?.let { clashing ->
                        dotNetUnsupported(
                            "member '${member.name.asString()}' clashes with '${clashing.name.asString()}': " +
                                    "both map to the same IL method '$ilIdentity'"
                        )
                    }
                    availableFunctions[member] = DotNetIlFunctionInfo(classInfo.ilClassName, signature)
                }
            } catch (e: DotNetIlUnsupportedException) {
                availableClasses.remove(irClass)
                classSkipReasons[irClass] = e.reason
                irClass.dotNetMemberFunctions().forEach(availableFunctions::remove)
            }
        }

        val renderedClasses = LinkedHashMap<IrClass, RenderedClass>()
        val renderedMethods = LinkedHashMap<IrSimpleFunction, DotNetIlRenderedMethod>()
        do {
            renderedClasses.clear()
            renderedMethods.clear()
            var anyDeclarationRemoved = false
            for (irClass in availableClasses.keys.toList()) {
                try {
                    renderedClasses[irClass] = renderUserClass(
                        classInfo = availableClasses.getValue(irClass),
                        irClass = irClass,
                        availableFunctions = availableFunctions,
                        intrinsicMethods = intrinsicMethods,
                        typeMapper = typeMapper,
                    )
                } catch (e: DotNetIlUnsupportedException) {
                    availableClasses.remove(irClass)
                    // The members go with the class: a call site must not resolve to a member
                    // of a class that no longer exists in the module.
                    irClass.dotNetMemberFunctions().forEach(availableFunctions::remove)
                    classSkipReasons[irClass] = e.reason
                    anyDeclarationRemoved = true
                }
            }
            for (function in availableFunctions.keys.toList()) {
                // Member functions render inside renderUserClass above; this loop owns only the
                // top-level functions of the file facades.
                if (function.parent !is IrFile) continue
                try {
                    // The signature is re-derived so that a class removed in an earlier round
                    // fails this function right here, before any stale `class 'C'` reference of
                    // a nonexistent class could reach the emitted signature text.
                    val functionInfo = DotNetIlFunctionInfo(
                        availableFunctions.getValue(function).className,
                        function.dotNetSignature(typeMapper),
                    )
                    availableFunctions[function] = functionInfo
                    renderedMethods[function] = DotNetIlMethodCodegen(
                        function = function,
                        functionInfo = functionInfo,
                        isEntryPoint = function == entryPoint,
                        availableFunctions = availableFunctions,
                        intrinsicMethods = intrinsicMethods,
                        typeMapper = typeMapper,
                    ).render()
                } catch (e: DotNetIlUnsupportedException) {
                    availableFunctions.remove(function)
                    skipReasons[function] = e.reason
                    anyDeclarationRemoved = true
                }
            }
        } while (anyDeclarationRemoved)

        for ((irClass, reason) in classSkipReasons) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Class '${irClass.diagnosticName()}' is not supported by the .NET backend and was skipped: $reason"
            )
        }
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
            for (file in files) {
                // Per file: user classes first, then the file facade (the deterministic order
                // the goldens freeze).
                for (irClass in topLevelClassesByFile.getValue(file)) {
                    renderedClasses[irClass]?.let { rendered ->
                        requiredHelpers += rendered.requiredRuntimeHelpers
                        append(rendered.ilText)
                    }
                }
                val methods = topLevelFunctionsByFile.getValue(file).mapNotNull { renderedMethods[it] }
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
     * The shape gate of the final-class model (JVM precedent: the runtime has real classes, so
     * there is no vtable/class lowering machinery and unsupported shapes are simply rejected):
     * only top-level, final, non-generic plain classes whose sole supertype is `kotlin.Any` are
     * compilable. Each violation throws [DotNetIlUnsupportedException]; the granularity is
     * always the whole class.
     */
    private fun checkClassShapeSupported(irClass: IrClass) {
        val name = irClass.diagnosticName()
        when (irClass.kind) {
            ClassKind.INTERFACE ->
                if (irClass.isFun) dotNetUnsupported("fun interface '$name' is not supported")
                else dotNetUnsupported("interface '$name' is not supported")
            ClassKind.OBJECT -> dotNetUnsupported("object declaration '$name' is not supported")
            ClassKind.ENUM_CLASS, ClassKind.ENUM_ENTRY -> dotNetUnsupported("enum class '$name' is not supported")
            ClassKind.ANNOTATION_CLASS -> dotNetUnsupported("annotation class '$name' is not supported")
            ClassKind.CLASS -> Unit
        }
        if (irClass.parent !is IrFile) {
            dotNetUnsupported("class '$name' is not top-level; nested/inner/local classes are not supported")
        }
        if (irClass.isData) dotNetUnsupported("data class '$name' is not supported")
        if (irClass.isInner) dotNetUnsupported("inner class '$name' is not supported")
        if (irClass.isValue) dotNetUnsupported("value class '$name' is not supported")
        if (irClass.isExpect) dotNetUnsupported("expect class '$name' is not supported")
        if (irClass.modality != Modality.FINAL) {
            dotNetUnsupported("non-final class '$name' is not supported (inheritance model not implemented)")
        }
        if (irClass.typeParameters.isNotEmpty()) {
            dotNetUnsupported("generic class '$name' is not supported yet")
        }
        if (irClass.superTypes.any { !it.isAny() }) {
            // Exception supertypes get a message naming the real gap: the supertype itself is
            // supported (type-mapped, see DotNetMappedExceptions), subclassing it is not.
            if (irClass.superTypes.any { it.classFqName in DotNetMappedExceptions.entries }) {
                dotNetUnsupported(
                    "class '$name' extends an exception class; " +
                            "user-defined exception classes are not supported until the inheritance model exists"
                )
            }
            dotNetUnsupported("class '$name' with a supertype other than kotlin.Any is not supported")
        }
        for (declaration in irClass.declarations) {
            if (declaration is IrClass) {
                if (declaration.isCompanion) dotNetUnsupported("companion object in class '$name' is not supported")
                dotNetUnsupported("class '${declaration.diagnosticName()}' is not top-level; nested/inner/local classes are not supported")
            }
        }
    }

    /**
     * Renders one top-level user class: backing fields (state, `private` per the JVM-facade
     * precedent of private field + accessors as the public surface), constructor bodies with
     * the initializer code [DotNetInitializersLowering][org.jetbrains.kotlin.backend.dotnet.lower.DotNetInitializersLowering]
     * merged into them, instance member functions, property accessors (`get_x`/`set_x`, plain
     * instance methods carrying `specialname`), and the `.property` metadata blocks binding the
     * accessors together — the CLR has first-class properties, so no accessor lowering is needed
     * (stated deviation from the JVM's PropertiesLowering). Each member's [DotNetIlFunctionInfo]
     * is re-derived into [availableFunctions] every fixpoint round for the same reason the
     * top-level loop re-derives: a class removed in an earlier round must fail its users here
     * rather than leave stale IL text. Any member failure aborts the render, which removes the
     * whole class from the module (fail-loud, never partial emission). Fake overrides (the `Any`
     * members `equals`/`hashCode`/`toString`) are skipped like on the JVM; calls to them stay
     * rejected.
     */
    private fun renderUserClass(
        classInfo: DotNetIlClassInfo,
        irClass: IrClass,
        availableFunctions: MutableMap<IrSimpleFunction, DotNetIlFunctionInfo>,
        intrinsicMethods: DotNetIlIntrinsicMethods,
        typeMapper: DotNetIlTypeMapper,
    ): RenderedClass {
        val name = irClass.diagnosticName()
        val renderedFields = mutableListOf<String>()
        val renderedMethods = mutableListOf<String>()
        val renderedProperties = mutableListOf<String>()
        val requiredHelpers = linkedSetOf<DotNetIlRuntimeHelper>()

        fun renderMemberFunction(member: IrSimpleFunction) {
            val memberInfo = DotNetIlFunctionInfo(classInfo.ilClassName, member.dotNetSignature(typeMapper))
            availableFunctions[member] = memberInfo
            val rendered = DotNetIlMethodCodegen(
                function = member,
                functionInfo = memberInfo,
                isEntryPoint = false,
                availableFunctions = availableFunctions,
                intrinsicMethods = intrinsicMethods,
                typeMapper = typeMapper,
            ).render()
            renderedMethods += rendered.ilText
            requiredHelpers += rendered.requiredRuntimeHelpers
        }

        for (declaration in irClass.declarations) {
            when (declaration) {
                is IrConstructor -> {
                    val rendered = DotNetIlMethodCodegen(
                        function = declaration,
                        functionInfo = DotNetIlFunctionInfo(classInfo.ilClassName, declaration.dotNetSignature(typeMapper)),
                        isEntryPoint = false,
                        availableFunctions = availableFunctions,
                        intrinsicMethods = intrinsicMethods,
                        typeMapper = typeMapper,
                    ).render()
                    renderedMethods += rendered.ilText
                    requiredHelpers += rendered.requiredRuntimeHelpers
                }
                is IrAnonymousInitializer ->
                    dotNetUnsupported("internal: init block of class '$name' survived InitializersLowering")
                is IrSimpleFunction -> if (!declaration.isFakeOverride) {
                    renderMemberFunction(declaration)
                }
                is IrProperty -> if (!declaration.isFakeOverride) {
                    declaration.backingField?.let { renderedFields += renderField(it, typeMapper) }
                    val getter = declaration.getter?.takeUnless { it.isFakeOverride }
                    val setter = declaration.setter?.takeUnless { it.isFakeOverride }
                    getter?.let(::renderMemberFunction)
                    setter?.let(::renderMemberFunction)
                    if (getter != null || setter != null) {
                        renderedProperties += renderPropertyBlock(declaration, getter, setter, availableFunctions)
                    }
                }
                else -> dotNetUnsupported("unsupported member of class '$name': ${declaration.javaClass.simpleName}")
            }
        }
        val ilText = buildString {
            DotNetIlClassCodegen(
                classInfo.ilClassName,
                renderedMethods,
                renderedFields,
                renderedProperties,
                isStaticHolder = false,
            ).generate(this)
        }
        return RenderedClass(ilText, requiredHelpers)
    }

    /**
     * The `.property` metadata block of one member property, binding its accessor methods so the
     * CLR (reflection, debuggers, other .NET languages) sees a real property — the getter-only
     * variant for `val`; all spellings ilasm-probe-verified. The property's IL type is its
     * getter's return type (or the setter's value-parameter type for the theoretical
     * setter-only shape).
     */
    private fun renderPropertyBlock(
        property: IrProperty,
        getter: IrSimpleFunction?,
        setter: IrSimpleFunction?,
        availableFunctions: Map<IrSimpleFunction, DotNetIlFunctionInfo>,
    ): String {
        val getterInfo = getter?.let(availableFunctions::getValue)
        val setterInfo = setter?.let(availableFunctions::getValue)
        val propertyName = property.name.asString()
        val propertyType = when {
            getterInfo != null -> (getterInfo.signature.returnType as? DotNetIlReturnType.Value)?.type
                ?: dotNetUnsupported("getter of property '$propertyName' returns void")
            else -> setterInfo!!.signature.parameterTypes.last()
        }
        return buildString {
            appendLine("  .property instance ${propertyType.nameInSignature} ${propertyName.toIlIdentifier()}()")
            appendLine("  {")
            if (getter != null && getterInfo != null) {
                appendLine("    .get ${getterInfo.renderMethodReference(getter.dotNetIlMethodName())}")
            }
            if (setter != null && setterInfo != null) {
                appendLine("    .set ${setterInfo.renderMethodReference(setter.dotNetIlMethodName())}")
            }
            appendLine("  }")
        }
    }

    /**
     * One `.field` line of a user class. Backing fields are always `private` (the JVM `final`
     * analogue `initonly` is deliberately omitted — a pure metadata nicety with no semantic
     * need); the spelling is ilasm-probe-verified, including private-field access from the
     * declaring class's own methods.
     */
    private fun renderField(field: IrField, typeMapper: DotNetIlTypeMapper): String {
        val fieldType = typeMapper.toDotNetIlValueType(field.type)
            ?: dotNetUnsupported("field '${field.name.asString()}' has unsupported type ${field.type.render()}")
        return ".field private ${fieldType.nameInSignature} ${field.name.asString().toIlIdentifier()}"
    }

    /**
     * The member functions of a user class that codegen renders and call sites resolve through
     * [DotNetIlFunctionInfo]: declared instance methods plus property accessors, minus fake
     * overrides (JVM precedent; calls to them stay rejected). Constructors are not functions in
     * this sense — they resolve through [DotNetIlClassInfo] instead.
     */
    private fun IrClass.dotNetMemberFunctions(): List<IrSimpleFunction> =
        declarations.flatMap { declaration ->
            when (declaration) {
                is IrSimpleFunction -> if (declaration.isFakeOverride) emptyList() else listOf(declaration)
                is IrProperty ->
                    if (declaration.isFakeOverride) emptyList()
                    else listOfNotNull(declaration.getter, declaration.setter).filterNot { it.isFakeOverride }
                else -> emptyList()
            }
        }

    /**
     * A successfully rendered user class: its IL text plus the [runtime helpers][DotNetIlRuntimeHelper]
     * its member bodies called, the class-level counterpart of [DotNetIlRenderedMethod].
     */
    private class RenderedClass(
        val ilText: String,
        val requiredRuntimeHelpers: Set<DotNetIlRuntimeHelper>,
    )

    /**
     * Precomputes the file class name for every file: the package-qualified dotted name
     * (`pkg.fileKt`) rendered later as a single quoted identifier, with a numeric suffix
     * deduplicating collisions. The used-name pool is seeded with the IL names of all top-level
     * user classes — Kotlin-declared and therefore not renamable — so a facade name dodges a
     * user class that occupies it (`pkg.Foo.kt`'s facade vs a user class `pkg.FooKt`).
     *
     * The seeding is deliberately pre-gate: it runs before the shape gate and the render
     * fixpoint, so a declared class reserves its name even when it is later skipped and absent
     * from the output (an `open class FooKt` still renames Foo.kt's facade to `FooKt1`). Facade
     * names thereby depend only on what the module *declares*, never on which classes happen to
     * survive — a skipped class cannot rename every facade-qualified call site of the module as
     * a side effect, keeping goldens and diagnostics stable across support changes.
     */
    private fun buildFileClassNames(
        files: List<IrFile>,
        topLevelClassesByFile: Map<IrFile, List<IrClass>>,
    ): Map<IrFile, String> {
        val usedNames = hashSetOf<String>()
        topLevelClassesByFile.values.flatten()
            // The injected exception declarations never become IL classes (they are type-mapped
            // to CLR types, see DotNetMappedExceptions), so they reserve no facade name either.
            .filterNot(DotNetMappedExceptions::isExceptionStdlibDeclaration)
            .mapTo(usedNames) { it.fqNameWhenAvailable!!.asString() }
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

    private fun IrDeclarationWithName.diagnosticName(): String =
        fqNameWhenAvailable?.asString() ?: name.asString()

    private fun StringBuilder.appendHeader() {
        appendLine(".assembly extern $CORE_LIB {}")
        appendLine(".assembly ${assemblyName.toIlIdentifier()} {}")
        appendLine(".module ${moduleFileName.toIlIdentifier()}")
        appendLine()
    }
}
