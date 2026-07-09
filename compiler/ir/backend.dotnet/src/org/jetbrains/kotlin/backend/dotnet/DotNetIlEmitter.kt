package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_STATIC_INITIALIZER
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeAlias
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.util.companionObject
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
     * Every top-level function whose signature maps to IL types, every top-level property (as
     * static facade fields plus accessors, with the initializers running in the facade's
     * `.cctor` — see [DotNetStaticInitializersLowering][org.jetbrains.kotlin.backend.dotnet.lower.DotNetStaticInitializersLowering])
     * and every top-level class that passes the [shape gate][checkClassShapeSupported] is a
     * candidate; candidates are rendered to a fixpoint, so a function calling an unsupported
     * function is itself skipped, and removing a class (any unrenderable member removes the
     * whole class — a class with, say, an unrenderable constructor must not remain
     * referenceable) cascades through the [DotNetIlTypeMapper] to every declaration whose types
     * mention it. A failing top-level property evicts the whole property, and a failure
     * involving any backing-field-bearing property of a file evicts the file's whole property
     * group (fields, accessors, `.property` blocks and the `.cctor`) — declaration-order
     * initialization cannot be partially preserved. Skipped declarations are reported as
     * warnings; remaining unsupported top-level declaration kinds are warned by a closing sweep
     * (typealiases are deliberately ignored without a warning). Returns null after reporting an
     * error when the module cannot be emitted at all (unsupported or ambiguous main, or an
     * executable was requested without a main function).
     */
    fun emit(moduleFragment: IrModuleFragment): String? {
        val intrinsicMethods = DotNetIlIntrinsicMethods(irBuiltIns)
        val files = moduleFragment.files.toList()
        val topLevelClassesByFile = files.associateWith { file ->
            file.declarations.filterIsInstance<IrClass>()
        }
        val fileClassNames = buildFileClassNames(files, topLevelClassesByFile)
        val topLevelPropertiesByFile = files.associateWith { file ->
            file.declarations.filterIsInstance<IrProperty>()
        }
        // The synthetic per-file `<clinit>` (see DotNetStaticInitializersLowering) is pulled out
        // of the ordinary function surface: it must never be a call target, a main candidate, or
        // a named method render — it is rendered separately as the facade's `.cctor`.
        val staticInitializersByFile = files.mapNotNull { file ->
            file.declarations.filterIsInstance<IrSimpleFunction>()
                .singleOrNull { it.origin == DOTNET_STATIC_INITIALIZER }
                ?.let { file to it }
        }.toMap()
        val topLevelFunctionsByFile = files.associateWith { file ->
            file.declarations.filterIsInstance<IrSimpleFunction>().filter { it.origin != DOTNET_STATIC_INITIALIZER }
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
                val classInfo = DotNetIlClassInfo(irClass.fqNameWhenAvailable!!.asString())
                availableClasses[irClass] = classInfo
                // The companion object (validated by the shape gate above) is a separate
                // availableClasses entry — it needs its own identity for type mapping and member
                // resolution — carrying the nested IL name (see DotNetIlClassInfo.ilTypeRef).
                // Registration is strictly paired with eviction: every site that removes one
                // half of the (class, companion) pair removes the other too (evictClassPair).
                irClass.companionObject()?.let { companion ->
                    availableClasses[companion] = DotNetIlClassInfo(companion.name.asString(), enclosingClass = classInfo)
                }
            } catch (e: DotNetIlUnsupportedException) {
                classSkipReasons[irClass] = e.reason
            }
        }
        val typeMapper = DotNetIlTypeMapper(availableClasses)
        // Static facade-field references (`ldsfld`/`stsfld` of top-level property backing
        // fields) resolve their owning IL class through this map, the facade counterpart of
        // [DotNetIlTypeMapper.classInfoOrNull].
        val facadeClassInfoByFile = files.associateWith { DotNetIlClassInfo(fileClassNames.getValue(it)) }

        val availableFunctions = LinkedHashMap<IrSimpleFunction, DotNetIlFunctionInfo>()
        val skipReasons = LinkedHashMap<IrSimpleFunction, String>()
        for ((file, functions) in topLevelFunctionsByFile) {
            val facadeClassInfo = facadeClassInfoByFile.getValue(file)
            for (function in functions) {
                if (intrinsicMethods.getIntrinsic(function.symbol)?.excludesDeclarationFromCodegen == true) continue
                try {
                    availableFunctions[function] = DotNetIlFunctionInfo(facadeClassInfo, function.dotNetSignature(typeMapper))
                } catch (e: DotNetIlUnsupportedException) {
                    skipReasons[function] = e.reason
                }
            }
        }
        // Top-level property pre-pass. Delegated and lateinit properties are rejected with
        // specific reasons (out of scope). `const val` renders as a CLR `literal` field — the
        // ConstantValue-attribute analogue of the JVM backend's `constantValue()` exclusion in
        // StaticInitializersLowering — with no accessors and no `.cctor` entry; every read is
        // inlined by the frontend, so an exotic surviving accessor call fails loudly via the
        // availableFunctions miss. Everything else pre-registers its accessors so call sites
        // resolve like any other top-level function (`dotNetSignature` already yields the static
        // shape: a top-level accessor has no dispatch receiver). A property whose accessor has
        // an intrinsic marked [DotNetIlIntrinsicMethod.excludesDeclarationFromCodegen] (the
        // injected `val Char.code`) is excluded from codegen entirely, like `println`.
        val propertySkipReasons = LinkedHashMap<IrProperty, String>()
        val constFieldLines = LinkedHashMap<IrProperty, String>()
        for ((file, properties) in topLevelPropertiesByFile) {
            val facadeClassInfo = facadeClassInfoByFile.getValue(file)
            for (property in properties) {
                if (property.isExcludedFromCodegen(intrinsicMethods)) continue
                val name = property.name.asString()
                val accessors = listOfNotNull(property.getter, property.setter)
                when {
                    property.isDelegated -> propertySkipReasons[property] = "delegated property '$name' is not supported"
                    property.isLateinit -> propertySkipReasons[property] = "lateinit property '$name' is not supported"
                    property.isConst -> try {
                        constFieldLines[property] = renderConstField(property, typeMapper)
                    } catch (e: DotNetIlUnsupportedException) {
                        propertySkipReasons[property] = e.reason
                    }
                    else -> try {
                        for (accessor in accessors) {
                            availableFunctions[accessor] = DotNetIlFunctionInfo(facadeClassInfo, accessor.dotNetSignature(typeMapper))
                        }
                    } catch (e: DotNetIlUnsupportedException) {
                        accessors.forEach(availableFunctions::remove)
                        propertySkipReasons[property] = e.reason
                    }
                }
            }
        }

        // Linked whole-pair eviction, the companion extension of the whole-class rejection rule:
        // a top-level class and its companion object are separate availableClasses entries, but
        // a PARTIAL pair would violate the whole-class rule in both directions — the singleton
        // field on the enclosing class is typed as the companion and the enclosing `.cctor`
        // `newobj`s it, while companion members resolve through the pair's nested IL name — so
        // every eviction site removes both entries and both member sets, each warned with a
        // reason carrying the original one. [failedClass] must be the half whose member or
        // shape actually failed — the partner's warning points at it — so the render fixpoint
        // re-tags a failure surfacing out of the companion's recursive render with the
        // companion (see DotNetIlUnsupportedClassException).
        fun evictClassPair(failedClass: IrClass, reason: String) {
            val enclosing = failedClass.parent as? IrClass ?: failedClass
            for (partner in listOfNotNull(enclosing, enclosing.companionObject())) {
                availableClasses.remove(partner)
                // The members go with the class: a call site must not resolve to a member of a
                // class that no longer exists in the module.
                partner.dotNetMemberFunctions().forEach(availableFunctions::remove)
                val partnerReason = when {
                    partner === failedClass -> reason
                    partner === enclosing ->
                        "its companion object '${failedClass.diagnosticName()}' could not be compiled: $reason"
                    else -> "its enclosing class '${enclosing.diagnosticName()}' could not be compiled: $reason"
                }
                classSkipReasons.putIfAbsent(partner, partnerReason)
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
        //
        // The same pass gates CLR FIELD-identity clashes: DotNetObjectClassLowering synthesizes
        // the static `INSTANCE` singleton field with the object's own type, so the backing field
        // of a user property named `INSTANCE` mapping to the same IL type (`val INSTANCE: A? =
        // null` — nullability erases) collides in both name and field signature — staticness and
        // visibility are attribute flags, not part of the identity — which ilasm rejects as a
        // duplicate field declaration (probe-verified on the modern ilasm 10.0.9, fieldprobe). A
        // differently-typed field of the same name is a legal CLR shape (same probe) and stays
        // supported, so the identity key is name plus mapped IL type. Stated deviation from the
        // JVM backend, which RENAMES the clashing private backing field (`RenameFieldsLowering`
        // yields `INSTANCE$1`): this backend has no field-renaming machinery, so the clash is
        // rejected whole-class like the method-identity clash above. The companion's singleton
        // field participates in the ENCLOSING class's gate the same way (the lowering parents
        // it there): a user field named after the companion whose type maps to the companion
        // clashes, evicting the whole (class, companion) pair.
        for ((irClass, classInfo) in availableClasses.entries.toList()) {
            // Already evicted as the partner of an earlier pair failure in this snapshot.
            if (irClass !in availableClasses) continue
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
                    availableFunctions[member] = DotNetIlFunctionInfo(classInfo, signature)
                }
                val fieldsByIlIdentity = hashMapOf<String, IrField>()
                for (field in irClass.dotNetMemberFields()) {
                    // An unmappable field type is not this gate's failure: the render path
                    // rejects the class with its specific unsupported-type message.
                    val fieldType = typeMapper.toDotNetIlValueType(field.type) ?: continue
                    val ilIdentity = "${fieldType.nameInSignature} ${field.name.asString().toIlIdentifier()}"
                    fieldsByIlIdentity.put(ilIdentity, field)?.let { clashing ->
                        dotNetUnsupported(
                            "${field.dotNetFieldDescription()} clashes with ${clashing.dotNetFieldDescription()}: " +
                                    "both map to the same IL field '$ilIdentity'"
                        )
                    }
                }
            } catch (e: DotNetIlUnsupportedException) {
                evictClassPair(irClass, e.reason)
            }
        }

        val renderedClasses = LinkedHashMap<IrClass, RenderedClass>()
        val renderedMethods = LinkedHashMap<IrSimpleFunction, DotNetIlRenderedMethod>()
        val renderedStaticInitializers = LinkedHashMap<IrFile, DotNetIlRenderedMethod>()
        val staticFieldLines = LinkedHashMap<IrFile, Map<IrProperty, String>>()
        val failedInitializerFiles = hashSetOf<IrFile>()

        // Evicts one top-level property: its accessors leave the callable surface (and the
        // per-function skip channel — the property channel owns the warning) and the property is
        // reported with [reason], unless an earlier, more specific reason already stands.
        fun evictTopLevelProperty(property: IrProperty, reason: String) {
            for (accessor in listOfNotNull(property.getter, property.setter)) {
                availableFunctions.remove(accessor)
                skipReasons.remove(accessor)
            }
            propertySkipReasons.putIfAbsent(property, reason)
        }

        // The failing-initializer granularity is the whole per-file property group: declaration
        // -order init interleaving cannot be partially preserved, so a failure anywhere in a
        // file's `<clinit>` (or in any backing-field-bearing property of the file) removes ALL
        // backing-field-bearing top-level properties of that file together — fields, accessors,
        // `.property` blocks and the `.cctor` — the facade-stateful analogue of the whole-class
        // rejection granularity. Accessor-only properties are untouched: they fail per-function
        // like any function.
        fun failFilePropertyGroup(file: IrFile, originalReason: String) {
            if (!failedInitializerFiles.add(file)) return
            val fileName = file.fileEntry.name.substringAfterLast('/').substringAfterLast('\\')
            val sharedReason = "top-level property initializers of file '$fileName' could not be compiled: $originalReason"
            for (property in topLevelPropertiesByFile.getValue(file)) {
                if (property.isConst || property.isExcludedFromCodegen(intrinsicMethods)) continue
                if (property.backingField == null) continue
                evictTopLevelProperty(property, sharedReason)
            }
        }

        do {
            renderedClasses.clear()
            renderedMethods.clear()
            renderedStaticInitializers.clear()
            staticFieldLines.clear()
            var anyDeclarationRemoved = false
            for (irClass in availableClasses.keys.toList()) {
                // Already evicted as the partner of an earlier pair failure in this round.
                if (irClass !in availableClasses) continue
                // A companion renders recursively INSIDE its enclosing class's render (as a
                // nested `.class` block), never as a top-level class of its own.
                if (irClass.isCompanion) continue
                try {
                    renderedClasses[irClass] = renderUserClass(
                        classInfo = availableClasses.getValue(irClass),
                        irClass = irClass,
                        availableFunctions = availableFunctions,
                        intrinsicMethods = intrinsicMethods,
                        typeMapper = typeMapper,
                        facadeClassInfoByFile = facadeClassInfoByFile,
                    )
                } catch (e: DotNetIlUnsupportedException) {
                    // A companion failure surfaces from INSIDE the enclosing class's render
                    // (the companion renders only recursively), tagged with the companion so
                    // the pair warnings name the half that actually failed — the same
                    // attribution the member pre-pass gets for free by iterating the
                    // companion as its own entry.
                    val failedClass = (e as? DotNetIlUnsupportedClassException)?.irClass ?: irClass
                    evictClassPair(failedClass, e.reason)
                    anyDeclarationRemoved = true
                }
            }
            for (function in availableFunctions.keys.toList()) {
                // Member functions render inside renderUserClass above; this loop owns the
                // top-level functions of the file facades and the accessors of top-level
                // properties (also file-parented).
                if (function.parent !is IrFile) continue
                try {
                    // The signature is re-derived so that a class removed in an earlier round
                    // fails this function right here, before any stale `class 'C'` reference of
                    // a nonexistent class could reach the emitted signature text.
                    val functionInfo = DotNetIlFunctionInfo(
                        availableFunctions.getValue(function).owner,
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
                        facadeClassInfoByFile = facadeClassInfoByFile,
                    ).render()
                } catch (e: DotNetIlUnsupportedException) {
                    availableFunctions.remove(function)
                    skipReasons[function] = e.reason
                    anyDeclarationRemoved = true
                }
            }
            // Property reconciliation: an accessor that failed in the loop above evicts its
            // whole property (a property is never emitted partially), and when that property
            // carries a backing field its initializer can no longer run, which fails the file's
            // whole property group (see failFilePropertyGroup).
            for ((file, properties) in topLevelPropertiesByFile) {
                for (property in properties) {
                    if (property in propertySkipReasons || property.isConst) continue
                    if (property.isExcludedFromCodegen(intrinsicMethods)) continue
                    val failedAccessor = listOfNotNull(property.getter, property.setter).firstOrNull { it in skipReasons }
                        ?: continue
                    val reason = skipReasons.getValue(failedAccessor)
                    evictTopLevelProperty(property, reason)
                    anyDeclarationRemoved = true
                    if (property.backingField != null) {
                        failFilePropertyGroup(file, reason)
                    }
                }
            }
            // Facade statics: the static backing fields and the `.cctor` of each file re-render
            // every round like everything else — a class removed in round N can be referenced by
            // a field type or an initializer, and must fail the group right here.
            for (file in files) {
                if (file in failedInitializerFiles) continue
                val staticInitializer = staticInitializersByFile[file]
                val fieldProperties = topLevelPropertiesByFile.getValue(file).filter { property ->
                    property !in propertySkipReasons && !property.isConst &&
                            !property.isExcludedFromCodegen(intrinsicMethods) && property.backingField != null
                }
                if (staticInitializer == null && fieldProperties.isEmpty()) continue
                try {
                    val fieldLines = fieldProperties.associateWith { property ->
                        renderField(property.backingField!!, typeMapper, isStatic = true)
                    }
                    val renderedInitializer = staticInitializer?.let { initializer ->
                        val facadeClassInfo = facadeClassInfoByFile.getValue(file)
                        DotNetIlMethodCodegen(
                            function = initializer,
                            functionInfo = DotNetIlFunctionInfo(facadeClassInfo, initializer.dotNetSignature(typeMapper)),
                            isEntryPoint = false,
                            availableFunctions = availableFunctions,
                            intrinsicMethods = intrinsicMethods,
                            typeMapper = typeMapper,
                            facadeClassInfoByFile = facadeClassInfoByFile,
                        ).render()
                    }
                    staticFieldLines[file] = fieldLines
                    renderedInitializer?.let { renderedStaticInitializers[file] = it }
                } catch (e: DotNetIlUnsupportedException) {
                    failFilePropertyGroup(file, e.reason)
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
        for ((property, reason) in propertySkipReasons) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Property '${property.diagnosticName()}' is not supported by the .NET backend and was skipped: $reason"
            )
        }
        // Silent-drop closure: every top-level declaration kind is either gathered above
        // (classes, functions, properties — including the intrinsic-excluded and the injected
        // stdlib declarations, which this sweep therefore never warns about), a typealias
        // (deliberately ignored WITHOUT a warning — the JVM backend emits no bytecode for
        // typealiases either), or warned here by kind, so no declaration is ever dropped
        // silently again.
        for (file in files) {
            for (declaration in file.declarations) {
                when (declaration) {
                    is IrClass, is IrSimpleFunction, is IrProperty, is IrTypeAlias -> {}
                    else -> {
                        val name = (declaration as? IrDeclarationWithName)?.diagnosticName()
                            ?: declaration.javaClass.simpleName
                        messageCollector.report(
                            CompilerMessageSeverity.WARNING,
                            "Declaration '$name' is not supported by the .NET backend and was skipped: " +
                                    "unsupported top-level declaration kind ${declaration.javaClass.simpleName}"
                        )
                    }
                }
            }
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
                // Facade members in declaration order, the `.cctor` first: static backing
                // fields (const `literal` fields interleaved in declaration order), then the
                // methods — top-level functions and property accessors — then the static
                // `.property` blocks (no block for an extension property: its accessors take a
                // receiver parameter, and a CLR property with parameters is an indexer, which is
                // out of scope — the accessors stay callable as plain static methods).
                val facadeMethods = mutableListOf<DotNetIlRenderedMethod>()
                renderedStaticInitializers[file]?.let { facadeMethods += it }
                for (declaration in file.declarations) {
                    when (declaration) {
                        is IrSimpleFunction -> renderedMethods[declaration]?.let { facadeMethods += it }
                        is IrProperty -> {
                            declaration.getter?.let { getter -> renderedMethods[getter]?.let { facadeMethods += it } }
                            declaration.setter?.let { setter -> renderedMethods[setter]?.let { facadeMethods += it } }
                        }
                        else -> {}
                    }
                }
                val facadeFields = mutableListOf<String>()
                val facadePropertyBlocks = mutableListOf<String>()
                val fieldLines = staticFieldLines[file].orEmpty()
                for (property in topLevelPropertiesByFile.getValue(file)) {
                    if (property in propertySkipReasons || property.isExcludedFromCodegen(intrinsicMethods)) continue
                    if (property.isConst) {
                        constFieldLines[property]?.let { facadeFields += it }
                        continue
                    }
                    fieldLines[property]?.let { facadeFields += it }
                    val getter = property.getter
                    val setter = property.setter
                    if ((getter != null || setter != null) && !property.isDotNetExtensionProperty()) {
                        facadePropertyBlocks += renderPropertyBlock(property, getter, setter, availableFunctions, isStatic = true)
                    }
                }
                if (facadeMethods.isEmpty() && facadeFields.isEmpty() && facadePropertyBlocks.isEmpty()) continue
                facadeMethods.flatMapTo(requiredHelpers) { it.requiredRuntimeHelpers }
                DotNetIlClassCodegen(
                    fileClassNames.getValue(file),
                    facadeMethods.map { it.ilText },
                    facadeFields,
                    facadePropertyBlocks,
                    hasClassInitializer = renderedStaticInitializers.containsKey(file),
                ).generate(this)
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
     * only top-level, final, non-generic plain classes and plain `object` declarations whose
     * sole supertype is `kotlin.Any` are compilable (an `object` goes through the same
     * constraint chain as a class — [DotNetObjectClassLowering][org.jetbrains.kotlin.backend.dotnet.lower.DotNetObjectClassLowering]
     * already turned its singleton nature into ordinary class machinery). The single supported
     * NESTED shape is the companion object of a top-level class ([isValidatedCompanion] marks
     * the recursive call): it is emitted as a real CLR nested type and validated with the same
     * constraint chain — sole supertype `kotlin.Any`, final, non-generic, no nested classes of
     * its own, not data. Each violation throws [DotNetIlUnsupportedException]; the granularity
     * is always the whole class — for a class with a companion, always the whole PAIR.
     */
    private fun checkClassShapeSupported(irClass: IrClass, isValidatedCompanion: Boolean = false) {
        val name = irClass.diagnosticName()
        when (irClass.kind) {
            ClassKind.INTERFACE ->
                if (irClass.isFun) dotNetUnsupported("fun interface '$name' is not supported")
                else dotNetUnsupported("interface '$name' is not supported")
            ClassKind.ENUM_CLASS, ClassKind.ENUM_ENTRY -> dotNetUnsupported("enum class '$name' is not supported")
            ClassKind.ANNOTATION_CLASS -> dotNetUnsupported("annotation class '$name' is not supported")
            ClassKind.CLASS, ClassKind.OBJECT -> Unit
        }
        if (!isValidatedCompanion && irClass.parent !is IrFile) {
            dotNetUnsupported("class '$name' is not top-level; nested/inner/local classes are not supported")
        }
        if (irClass.isData) {
            // `data object` lands here too: its generated toString/equals/hashCode overrides
            // need an Any model just like a data class's members.
            val kindWord = if (irClass.kind == ClassKind.OBJECT) "data object" else "data class"
            dotNetUnsupported("$kindWord '$name' is not supported")
        }
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
                // The companion object of a top-level class (Kotlin allows at most one) is the
                // single supported nested shape, recursively validated with the same constraint
                // chain; the recursion is depth-one because a companion cannot itself contain a
                // companion (the frontend rejects it). Every other nested class — including any
                // nested class of the companion — stays rejected, whole-class.
                if (!isValidatedCompanion && declaration.isCompanion && declaration.kind == ClassKind.OBJECT) {
                    checkClassShapeSupported(declaration, isValidatedCompanion = true)
                    continue
                }
                val kindWord = if (declaration.kind == ClassKind.OBJECT) "object" else "class"
                dotNetUnsupported("$kindWord '${declaration.diagnosticName()}' is not top-level; nested/inner/local classes are not supported")
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
     *
     * An `object` class renders through the same path with three additions: its `INSTANCE`
     * field (a loose [IrField] with origin [IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE])
     * renders public-static, its class-parented `<clinit>` renders as the class's `.cctor` —
     * first among the methods for deterministic goldens, never registered in
     * [availableFunctions] — and sets `hasClassInitializer` (dropping `beforefieldinit`, the
     * same first-active-use parity argument as the facades), and a member `const val` renders
     * as a `literal` field on the class with no accessors, exactly like the facade const shape.
     *
     * A class WITH A COMPANION renders its companion recursively through this same function as
     * a real CLR nested `.class` block inside its own body (spelling probe-verified,
     * objprobe_s6), while carrying the companion's pieces of the object machinery itself: the
     * singleton field [DotNetObjectClassLowering][org.jetbrains.kotlin.backend.dotnet.lower.DotNetObjectClassLowering]
     * parented HERE (named after the companion, typed as the companion) and the class-parented
     * `<clinit>` doing the `newobj`/`stsfld` — so the ENCLOSING class drops `beforefieldinit`
     * and touching the companion initializes the enclosing class (Kotlin/JVM parity,
     * objprobe_s8), while the companion itself has no `.cctor` and keeps `beforefieldinit`.
     */
    private fun renderUserClass(
        classInfo: DotNetIlClassInfo,
        irClass: IrClass,
        availableFunctions: MutableMap<IrSimpleFunction, DotNetIlFunctionInfo>,
        intrinsicMethods: DotNetIlIntrinsicMethods,
        typeMapper: DotNetIlTypeMapper,
        facadeClassInfoByFile: Map<IrFile, DotNetIlClassInfo>,
    ): RenderedClass {
        val name = irClass.diagnosticName()
        val renderedNestedClasses = mutableListOf<String>()
        val renderedFields = mutableListOf<String>()
        val renderedMethods = mutableListOf<String>()
        val renderedProperties = mutableListOf<String>()
        val requiredHelpers = linkedSetOf<DotNetIlRuntimeHelper>()
        var hasClassInitializer = false

        fun renderMemberFunction(member: IrSimpleFunction) {
            val memberInfo = DotNetIlFunctionInfo(classInfo, member.dotNetSignature(typeMapper))
            availableFunctions[member] = memberInfo
            val rendered = DotNetIlMethodCodegen(
                function = member,
                functionInfo = memberInfo,
                isEntryPoint = false,
                availableFunctions = availableFunctions,
                intrinsicMethods = intrinsicMethods,
                typeMapper = typeMapper,
                facadeClassInfoByFile = facadeClassInfoByFile,
            ).render()
            renderedMethods += rendered.ilText
            requiredHelpers += rendered.requiredRuntimeHelpers
        }

        for (declaration in irClass.declarations) {
            when (declaration) {
                is IrConstructor -> {
                    val rendered = DotNetIlMethodCodegen(
                        function = declaration,
                        functionInfo = DotNetIlFunctionInfo(classInfo, declaration.dotNetSignature(typeMapper)),
                        isEntryPoint = false,
                        availableFunctions = availableFunctions,
                        intrinsicMethods = intrinsicMethods,
                        typeMapper = typeMapper,
                        facadeClassInfoByFile = facadeClassInfoByFile,
                    ).render()
                    renderedMethods += rendered.ilText
                    requiredHelpers += rendered.requiredRuntimeHelpers
                }
                is IrAnonymousInitializer ->
                    dotNetUnsupported("internal: init block of class '$name' survived InitializersLowering")
                is IrClass -> {
                    // Only the companion object reaches here: the shape gate rejected every
                    // other nested class, and registration is paired, so a missing class info
                    // is an internal inconsistency rather than a reachable user shape. The
                    // companion renders recursively as a real CLR nested `.class` block inside
                    // this class's body (probe-verified, objprobe_s6), indented two columns
                    // like every other member.
                    val companionInfo = typeMapper.classInfoOrNull(declaration)
                        ?: dotNetUnsupported("internal: nested class '${declaration.name.asString()}' of class '$name' has no registered class info")
                    val rendered = try {
                        renderUserClass(
                            classInfo = companionInfo,
                            irClass = declaration,
                            availableFunctions = availableFunctions,
                            intrinsicMethods = intrinsicMethods,
                            typeMapper = typeMapper,
                            facadeClassInfoByFile = facadeClassInfoByFile,
                        )
                    } catch (e: DotNetIlUnsupportedException) {
                        // Attribute the failure to the companion itself: this render is the
                        // companion's ONLY render (it never renders as a top-level class), so
                        // without the tag the fixpoint catch would see the enclosing class as
                        // the failing half of the pair and invert the two eviction warnings.
                        throw DotNetIlUnsupportedClassException(declaration, e.reason)
                    }
                    renderedNestedClasses += rendered.ilText.trimEnd('\n').prependIndent("  ") + "\n"
                    requiredHelpers += rendered.requiredRuntimeHelpers
                }
                is IrField -> {
                    // The only loose fields of a supported class are the singleton fields
                    // DotNetObjectClassLowering synthesized: INSTANCE on an `object`, or the
                    // field named after the companion on a companion-bearing class; anything
                    // else has no defined render and must fail the class rather than emit
                    // guesswork.
                    if (declaration.origin != IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE) {
                        dotNetUnsupported(
                            "internal: unexpected propertyless field '${declaration.name.asString()}' in class '$name'"
                        )
                    }
                    renderedFields += renderObjectInstanceField(declaration, typeMapper)
                }
                is IrSimpleFunction -> when {
                    declaration.origin == DOTNET_STATIC_INITIALIZER -> {
                        // The class-parented `<clinit>` renders as the class's `.cctor`, first
                        // among the methods (the sweep appended it last) so goldens stay
                        // deterministic; it never enters availableFunctions — like the facade
                        // `.cctor`, it must not be a call target or a named method.
                        val rendered = DotNetIlMethodCodegen(
                            function = declaration,
                            functionInfo = DotNetIlFunctionInfo(classInfo, declaration.dotNetSignature(typeMapper)),
                            isEntryPoint = false,
                            availableFunctions = availableFunctions,
                            intrinsicMethods = intrinsicMethods,
                            typeMapper = typeMapper,
                            facadeClassInfoByFile = facadeClassInfoByFile,
                        ).render()
                        renderedMethods.add(0, rendered.ilText)
                        requiredHelpers += rendered.requiredRuntimeHelpers
                        hasClassInitializer = true
                    }
                    !declaration.isFakeOverride -> renderMemberFunction(declaration)
                    else -> {}
                }
                is IrProperty -> if (!declaration.isFakeOverride) {
                    if (declaration.isConst) {
                        // A member `const val` is the same CLR `literal` field as the facade
                        // shape: no accessors, no `.property` block, no `.cctor` entry
                        // (coexistence with a `.cctor` and `initonly` probe-verified,
                        // objprobe_s9a); an exotic surviving accessor call fails loudly via
                        // the availableFunctions miss.
                        renderedFields += renderConstField(declaration, typeMapper)
                    } else {
                        declaration.backingField?.let { renderedFields += renderField(it, typeMapper) }
                        val getter = declaration.getter?.takeUnless { it.isFakeOverride }
                        val setter = declaration.setter?.takeUnless { it.isFakeOverride }
                        getter?.let(::renderMemberFunction)
                        setter?.let(::renderMemberFunction)
                        if (getter != null || setter != null) {
                            renderedProperties += renderPropertyBlock(declaration, getter, setter, availableFunctions)
                        }
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
                hasClassInitializer = hasClassInitializer,
                isNested = classInfo.isNested,
                renderedNestedClasses = renderedNestedClasses,
            ).generate(this)
        }
        return RenderedClass(ilText, requiredHelpers)
    }

    /**
     * The `.property` metadata block of one property, binding its accessor methods so the CLR
     * (reflection, debuggers, other .NET languages) sees a real property — the getter-only
     * variant for `val`; all spellings ilasm-probe-verified. A member property is `instance`; a
     * top-level property ([isStatic]) drops the keyword and binds static accessors
     * (`statprobe_s1`). The property's IL type is its getter's return type (or the setter's
     * value-parameter type for the theoretical setter-only shape).
     */
    private fun renderPropertyBlock(
        property: IrProperty,
        getter: IrSimpleFunction?,
        setter: IrSimpleFunction?,
        availableFunctions: Map<IrSimpleFunction, DotNetIlFunctionInfo>,
        isStatic: Boolean = false,
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
            val instance = if (isStatic) "" else "instance "
            appendLine("  .property $instance${propertyType.nameInSignature} ${propertyName.toIlIdentifier()}()")
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
     * One `.field` line: the instance backing field of a member property, or, with [isStatic],
     * the static backing field of a top-level property on its file facade. Backing fields are
     * always `private` (the JVM `final` analogue `initonly` is deliberately omitted on both
     * shapes — a pure metadata nicety with no semantic need, and a `var`'s `stsfld` from the
     * static setter must stay legal); both spellings are ilasm-probe-verified
     * (`statprobe_s1`/`_s2` for the static shape, including a user-class-typed static field).
     */
    private fun renderField(field: IrField, typeMapper: DotNetIlTypeMapper, isStatic: Boolean = false): String {
        val fieldType = typeMapper.toDotNetIlValueType(field.type)
            ?: dotNetUnsupported("field '${field.name.asString()}' has unsupported type ${field.type.render()}")
        val static = if (isStatic) "static " else ""
        return ".field private $static${fieldType.nameInSignature} ${field.name.asString().toIlIdentifier()}"
    }

    /**
     * The `.field public static initonly class 'C' 'INSTANCE'` line of an `object` class — the
     * CLR spelling of the JVM's `public static final INSTANCE` (the one public field of the
     * model; spelling probe-verified, `objprobe_s1`, including the `newobj` of the private
     * `.ctor` from the same class's `.cctor`) — or, on a companion-bearing class, the
     * `.field public static initonly class 'C'/'Companion' 'Companion'` singleton field of its
     * companion (nested type-ref spelling in field position probe-verified, `objprobe_s6`,
     * including a same-named FIELD coexisting with the nested TYPE). Unlike [renderField]'s
     * backing fields, `initonly` is kept here for JVM-`final` parity of intent — nothing ever
     * stores to the singleton field outside the `.cctor` — even though CoreCLR 10 does not
     * enforce it at runtime (an outside `stsfld` succeeded, `objprobe_s3`: `initonly` is
     * declarative metadata only).
     */
    private fun renderObjectInstanceField(field: IrField, typeMapper: DotNetIlTypeMapper): String {
        val fieldType = typeMapper.toDotNetIlValueType(field.type)
            ?: dotNetUnsupported("field '${field.name.asString()}' has unsupported type ${field.type.render()}")
        return ".field public static initonly ${fieldType.nameInSignature} ${field.name.asString().toIlIdentifier()}"
    }

    /**
     * The CLR `literal` field of a `const val` — the ConstantValue-attribute analogue the JVM
     * emits (JVM precedent: `StaticInitializersLowering` excludes fields with a `constantValue()`
     * from `<clinit>`, and `JvmPropertiesLowering` generates no accessors for const properties).
     * A `literal` field has no storage and never appears in the `.cctor`; every read of the
     * property is inlined by the frontend. All literal spellings are ilasm-probe-verified
     * (`statprobe_s1`/`_s3`: int32, int64 incl. MIN_VALUE, bool, char, float64 decimal and
     * raw-bit forms, string incl. the `bytearray` fallback).
     */
    private fun renderConstField(property: IrProperty, typeMapper: DotNetIlTypeMapper): String {
        val name = property.name.asString()
        val field = property.backingField
            ?: dotNetUnsupported("internal: const property '$name' has no backing field")
        val fieldType = typeMapper.toDotNetIlValueType(field.type)
            ?: dotNetUnsupported("const property '$name' has unsupported type ${field.type.render()}")
        val constant = field.initializer?.expression as? IrConst
            ?: dotNetUnsupported("internal: const property '$name' has no constant initializer")
        return ".field public static literal ${fieldType.nameInSignature} " +
                "${field.name.asString().toIlIdentifier()} = ${renderConstFieldInitializer(constant, fieldType, name)}"
    }

    /** The field-initializer literal of a [const field][renderConstField]; spellings probe-verified. */
    private fun renderConstFieldInitializer(constant: IrConst, fieldType: DotNetIlValueType, propertyName: String): String {
        fun unsupportedValue(): Nothing = dotNetUnsupported(
            "const property '$propertyName' has an unsupported ${fieldType.nameInSignature} value: ${constant.value}"
        )
        return when (fieldType) {
            DotNetIlValueType.Boolean -> "bool(${constant.value as? Boolean ?: unsupportedValue()})"
            DotNetIlValueType.Int32 -> "int32(${constant.value as? Int ?: unsupportedValue()})"
            DotNetIlValueType.Int64 -> "int64(${constant.value as? Long ?: unsupportedValue()})"
            DotNetIlValueType.Char -> "char(0x%04X)".format((constant.value as? Char ?: unsupportedValue()).code)
            DotNetIlValueType.Float64 -> {
                // toIlFloat64Literal yields either a bare decimal (wrapped here) or the raw-bit
                // `float64(0x...)` form, which doubles as a field-initializer spelling.
                val literal = (constant.value as? Double ?: unsupportedValue()).toIlFloat64Literal()
                if (literal.startsWith("float64(")) literal else "float64($literal)"
            }
            DotNetIlValueType.String -> (constant.value as? String ?: unsupportedValue()).toIlStringLiteral()
            is DotNetIlValueType.UserClass, is DotNetIlValueType.MappedClass -> unsupportedValue()
        }
    }

    /**
     * Whether an accessor of [this] property is registered with an intrinsic that
     * [excludes the declaration from codegen][DotNetIlIntrinsicMethod.excludesDeclarationFromCodegen]
     * (the injected `val Char.code`): the property is then neither emitted nor warned about —
     * every use site is intercepted by the intrinsic registry.
     */
    private fun IrProperty.isExcludedFromCodegen(intrinsicMethods: DotNetIlIntrinsicMethods): Boolean =
        listOfNotNull(getter, setter).any { accessor ->
            intrinsicMethods.getIntrinsic(accessor.symbol)?.excludesDeclarationFromCodegen == true
        }

    /**
     * Whether [this] is an extension property: its accessors carry the extension receiver as a
     * regular IL parameter, and a CLR `.property` block with parameters is an indexer — out of
     * scope — so extension properties get plain static accessor methods and no `.property` block.
     */
    private fun IrProperty.isDotNetExtensionProperty(): Boolean =
        listOfNotNull(getter, setter).any { accessor ->
            accessor.parameters.any { it.kind == IrParameterKind.ExtensionReceiver }
        }

    /**
     * The member functions of a user class that codegen renders and call sites resolve through
     * [DotNetIlFunctionInfo]: declared instance methods plus property accessors, minus fake
     * overrides (JVM precedent; calls to them stay rejected). Constructors are not functions in
     * this sense — they resolve through [DotNetIlClassInfo] instead. Two synthetic-surface
     * exclusions mirror the facade side: the class-parented `<clinit>` (origin
     * [DOTNET_STATIC_INITIALIZER]) renders as the `.cctor` and must never be call-resolvable or
     * clash-checked, and the accessors of a `const val` are never emitted (the property is a
     * `literal` field; every read is inlined by the frontend, and an exotic surviving accessor
     * call fails loudly via the availableFunctions miss).
     */
    private fun IrClass.dotNetMemberFunctions(): List<IrSimpleFunction> =
        declarations.flatMap { declaration ->
            when (declaration) {
                is IrSimpleFunction ->
                    if (declaration.isFakeOverride || declaration.origin == DOTNET_STATIC_INITIALIZER) emptyList()
                    else listOf(declaration)
                is IrProperty ->
                    if (declaration.isFakeOverride || declaration.isConst) emptyList()
                    else listOfNotNull(declaration.getter, declaration.setter).filterNot { it.isFakeOverride }
                else -> emptyList()
            }
        }

    /**
     * The IL fields a user class renders, in declaration order, for the member pre-pass's
     * field-identity clash gate: the loose singleton field — `INSTANCE` on an `object`, or the
     * field named after the companion that [DotNetObjectClassLowering][org.jetbrains.kotlin.backend.dotnet.lower.DotNetObjectClassLowering]
     * parents to a companion-bearing class (so a user field named `Companion` with the
     * companion's type clashes, whole-pair) — plus the backing fields of the declared
     * properties, including `const val`s, whose `literal` fields share the class's field
     * namespace like any other field.
     */
    private fun IrClass.dotNetMemberFields(): List<IrField> =
        declarations.flatMap { declaration ->
            when (declaration) {
                is IrField -> listOf(declaration)
                is IrProperty ->
                    if (declaration.isFakeOverride) emptyList()
                    else listOfNotNull(declaration.backingField)
                else -> emptyList()
            }
        }

    /**
     * How one IL field is described in the field-identity clash diagnostic: the synthesized
     * singleton field is named as such — the user never declared it — while a backing field is
     * attributed to its property.
     */
    private fun IrField.dotNetFieldDescription(): String = when {
        origin == IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE -> "the synthesized '${name.asString()}' singleton field"
        correspondingPropertySymbol != null -> "the backing field of property '${name.asString()}'"
        else -> "field '${name.asString()}'"
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
     * A [DotNetIlUnsupportedException] tagged with the class whose render actually failed. A
     * companion object renders only recursively INSIDE its enclosing class's [renderUserClass],
     * so a companion member failure would otherwise surface at the render-fixpoint catch as a
     * failure of the ENCLOSING class and invert the attribution of the two pair-eviction
     * warnings (the shape gate and the member pre-pass need no tag — they iterate the companion
     * as its own entry).
     */
    private class DotNetIlUnsupportedClassException(
        val irClass: IrClass,
        reason: String,
    ) : DotNetIlUnsupportedException(reason)

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
