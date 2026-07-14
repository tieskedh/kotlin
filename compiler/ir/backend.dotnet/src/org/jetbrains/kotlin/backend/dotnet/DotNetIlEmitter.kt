package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_STATIC_INITIALIZER
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
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
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.resolveFakeOverride

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
        val moduleTopLevelClasses = topLevelClassesByFile.values.flatten().toHashSet()
        for (irClass in topLevelClassesByFile.values.flatten()) {
            if (DotNetMappedExceptions.isExceptionStdlibDeclaration(irClass)) continue
            try {
                checkClassShapeSupported(irClass, moduleTopLevelClasses)
                // A generic class's IL name carries the CLS `` `n `` arity suffix INSIDE the
                // quoted identifier ('demo.Box`1' — outside the quotes is an ilasm syntax error,
                // probe-verified genprobe_s2/_s2c). The suffix is CLS convention rather than a
                // CLR requirement (genprobe_s2b) but is emitted for Roslyn/interop parity, which
                // also guarantees a plain `Box` and a generic `Box<T>` can never collide in IL.
                val arity = irClass.typeParameters.size
                val ilClassName = irClass.fqNameWhenAvailable!!.asString() + if (arity > 0) "`$arity" else ""
                val classInfo = DotNetIlClassInfo(ilClassName, typeParameterCount = arity)
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
        // Base-chain and interface linking pass, deliberately AFTER every registration: a base
        // class or interface may be declared after its user (forward references are legal IL —
        // probe-verified, inheritprobe_s1 — and legal Kotlin), so the links cannot be built
        // inside the gate loop. The links feed [isDotNetAssignableTo]'s upcast walk only; the
        // `extends` and `implements` lines are re-resolved from the LIVE map every render round
        // (see [renderUserClass]), so a base or interface that failed the gate (its entry is
        // absent here, leaving the link out) or is evicted later evicts its derived
        // classes/implementers/sub-interfaces through the fixpoint rather than through these
        // links. The base link is the full SUPERTYPE token — an instantiated generic base
        // (`class D : Box<Int>()`) must widen to exactly `Box<Int>`, never another
        // instantiation — mapped through the type mapper; a base whose instantiation mentions
        // an unmappable or unavailable type simply leaves the link out (the render's live
        // re-resolution owns the eviction and its carried reason).
        for ([irClass, classInfo] in availableClasses) {
            if (irClass.isCompanion) continue
            classInfo.baseType = irClass.dotNetBaseSuperTypeOrNull()?.let { baseSuperType ->
                try {
                    typeMapper.toDotNetIlValueType(baseSuperType)
                } catch (_: DotNetIlUnsupportedException) {
                    null
                }
            }
            classInfo.interfaces = irClass.dotNetDirectInterfaces().mapNotNull { availableClasses[it] }
        }
        // Static facade-field references (`ldsfld`/`stsfld` of top-level property backing
        // fields) resolve their owning IL class through this map, the facade counterpart of
        // [DotNetIlTypeMapper.classInfoOrNull].
        val facadeClassInfoByFile = files.associateWith { DotNetIlClassInfo(fileClassNames.getValue(it)) }

        val availableFunctions = LinkedHashMap<IrSimpleFunction, DotNetIlFunctionInfo>()
        val skipReasons = LinkedHashMap<IrSimpleFunction, String>()
        for ([file, functions] in topLevelFunctionsByFile) {
            val facadeClassInfo = facadeClassInfoByFile.getValue(file)
            for (function in functions) {
                if (intrinsicMethods.getIntrinsic(function.symbol)?.excludesDeclarationFromCodegen == true) continue
                try {
                    // Generic top-level functions are stage-1 supported (real CLR generic
                    // methods, `!!n`-indexed — no monomorphization or erasure machinery); the
                    // gate rejects the unsupported flavors (inline/reified, variance,
                    // constraints) loudly before the signature maps.
                    function.checkDotNetGenericFunctionSupported()
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
        for ([file, properties] in topLevelPropertiesByFile) {
            val facadeClassInfo = facadeClassInfoByFile.getValue(file)
            for (property in properties) {
                if (property.isExcludedFromCodegen(intrinsicMethods)) continue
                val name = property.name.asString()
                val accessors = listOfNotNull(property.getter, property.setter)
                when {
                    property.isDelegated -> propertySkipReasons[property] = "delegated property '$name' is not supported"
                    property.isLateinit -> propertySkipReasons[property] = "lateinit property '$name' is not supported"
                    // Generic (extension) properties remain outside the supported declaration
                    // model even though their accessors would be generic IL methods.
                    accessors.any { it.typeParameters.isNotEmpty() } ->
                        propertySkipReasons[property] = "generic property '$name' is not supported yet"
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
        for ([irClass, classInfo] in availableClasses.entries.toList()) {
            // Already evicted as the partner of an earlier pair failure in this snapshot.
            if (irClass !in availableClasses) continue
            try {
                val membersByIlIdentity = hashMapOf<String, IrSimpleFunction>()
                for (member in irClass.dotNetMemberFunctions()) {
                    val signature = member.dotNetSignature(typeMapper)
                    checkOverrideKeepsIlReturnType(member, signature, typeMapper)
                    // CLR method identity includes the generic ARITY (see
                    // dotNetIlGenericAritySuffix); generic MEMBER functions are currently
                    // shape-gate-rejected, so the suffix here is forward-consistency with the
                    // facade gate below.
                    val ilIdentity =
                        "${member.dotNetIlMethodName()}${member.dotNetIlGenericAritySuffix()}(${signature.renderParameterTypes()})"
                    membersByIlIdentity.put(ilIdentity, member)?.let { clashing ->
                        dotNetUnsupported(
                            "member '${member.name.asString()}' clashes with '${clashing.name.asString()}': " +
                                    "both map to the same IL method '$ilIdentity'"
                        )
                    }
                    availableFunctions[member] = DotNetIlFunctionInfo(classInfo, signature)
                }
                for (member in irClass.dotNetMemberFakeOverrides()) {
                    checkInheritedInterfaceImplKeepsIlReturnType(member, typeMapper)
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

        // Facade IL method-identity gate: the file facade's analogue of the class member
        // pre-pass above. All top-level functions and property accessors of one file render
        // into ONE facade class, so accessor mangling and IL type erasure make the same clashes
        // possible there: `fun get_x()` vs the getter of `val x`, `g(String)`/`g(String?)`
        // (reference nullability erases to the same IL `string`), and `h(Any)`/`h(Any?)` (both
        // map to `object`) — each yields two identical IL method declarations, which ilasm
        // rejects as a duplicate method declaration (probe-verified on the modern ilasm 10.0.9,
        // like the class-member gate; the JVM frontend's analogue is
        // PLATFORM_DECLARATION_CLASH). Granularity follows the facade rules rather than the
        // whole-class rule: EVERY callable of a clashing identity is evicted (keeping one half
        // would be an arbitrary pick between legal Kotlin overloads) — a plain function
        // per-function, an accessor with its whole property, and a backing-field-bearing
        // property with the file's whole property group (its initializer can no longer run).
        // Like the class gate, the return type is deliberately not part of the identity key.
        // Pinned by ilText/facadeMethodClash.kt.
        for (file in files) {
            val facadeCallables = mutableListOf<IrSimpleFunction>()
            for (declaration in file.declarations) {
                when (declaration) {
                    is IrSimpleFunction -> if (declaration in availableFunctions) facadeCallables += declaration
                    is IrProperty ->
                        listOfNotNull(declaration.getter, declaration.setter)
                            .filterTo(facadeCallables) { it in availableFunctions }
                    else -> {}
                }
            }
            val callablesByIlIdentity = hashMapOf<String, IrSimpleFunction>()
            for (callable in facadeCallables) {
                // Already evicted as the partner of an earlier clash in this file.
                val functionInfo = availableFunctions[callable] ?: continue
                // The generic-arity marker keeps a generic `fun <T> f(x: Int)` distinct from a
                // plain `fun f(x: Int)` — CLR method identity includes the arity (the Roslyn
                // overload rule), so both are legal IL methods on one facade.
                val ilIdentity =
                    "${callable.dotNetIlMethodName()}${callable.dotNetIlGenericAritySuffix()}(${functionInfo.signature.renderParameterTypes()})"
                val clashing = callablesByIlIdentity.putIfAbsent(ilIdentity, callable) ?: continue
                for ([loser, winner] in listOf(callable to clashing, clashing to callable)) {
                    val reason = "top-level '${loser.diagnosticName()}' clashes with '${winner.diagnosticName()}': " +
                            "both map to the same IL method '$ilIdentity'"
                    val property = loser.correspondingPropertySymbol?.owner
                    if (property == null) {
                        availableFunctions.remove(loser)
                        skipReasons.putIfAbsent(loser, reason)
                    } else {
                        evictTopLevelProperty(property, reason)
                        if (property.backingField != null) {
                            failFilePropertyGroup(file, reason)
                        }
                    }
                }
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
                        classSkipReasons = classSkipReasons,
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
            for ([file, properties] in topLevelPropertiesByFile) {
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

        for ([irClass, reason] in classSkipReasons) {
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Class '${irClass.diagnosticName()}' is not supported by the .NET backend and was skipped: $reason"
            )
        }
        for ([function, reason] in skipReasons) {
            if (function == entryPoint) continue
            messageCollector.report(
                CompilerMessageSeverity.WARNING,
                "Function '${function.diagnosticName()}' is not supported by the .NET backend and was skipped: $reason"
            )
        }
        for ([property, reason] in propertySkipReasons) {
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
     * The shape gate of the class model (JVM precedent: the runtime has real classes, so
     * there is no vtable/class lowering machinery and unsupported shapes are simply rejected):
     * top-level, non-generic plain classes — `final` or, since the inheritance model, `open` —
     * and plain (always final) `object` declarations are compilable (an `object` goes through
     * the same constraint chain as a class — [DotNetObjectClassLowering][org.jetbrains.kotlin.backend.dotnet.lower.DotNetObjectClassLowering]
     * already turned its singleton nature into ordinary class machinery). A plain class may
     * have EXACTLY ONE class supertype when it resolves to another top-level class of
     * [moduleTopLevelClasses] (real CLR inheritance; whether that base itself compiles is
     * deliberately NOT checked here — the render re-resolves it every fixpoint round, so a
     * failing base cascades to its derived classes with a carried reason, see
     * [renderUserClass]) and any number of interface supertypes when each resolves to a
     * top-level interface of the module (real CLR interface types — see
     * [checkInterfaceShapeSupported] for the interface half of the gate; the `implements` list
     * is re-resolved live like the base). Interface slots satisfied through inherited members
     * must resolve to VIRTUAL members (`ifaceprobe_s5a`/`_s5b` — the non-virtual shape
     * load-poisons the type) whose mapped IL signature matches the slot exactly, return type
     * included (`ifaceprobe_s10`; the covariant half runs in the member pre-pass,
     * [checkInheritedInterfaceImplKeepsIlReturnType], because it needs the type mapper).
     * Interface delegation (`by`) is rejected whole-class in both source spellings.
     * `abstract`/`sealed` classes, exception
     * supertypes, out-of-module or non-top-level bases, and overrides of `kotlin.Any` members
     * stay rejected. Objects and companions stay on the sole-supertype-`kotlin.Any`,
     * final-only model. The single supported
     * NESTED shape is the companion object of a top-level class ([isValidatedCompanion] marks
     * the recursive call): it is emitted as a real CLR nested type and validated with the same
     * constraint chain — sole supertype `kotlin.Any`, final, non-generic, no nested classes of
     * its own, not data. Each violation throws [DotNetIlUnsupportedException]; the granularity
     * is always the whole class — for a class with a companion, always the whole PAIR.
     */
    private fun checkClassShapeSupported(
        irClass: IrClass,
        moduleTopLevelClasses: Set<IrClass>,
        isValidatedCompanion: Boolean = false,
    ) {
        val name = irClass.diagnosticName()
        when (irClass.kind) {
            ClassKind.INTERFACE -> {
                checkInterfaceShapeSupported(irClass, moduleTopLevelClasses)
                return
            }
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
        // Modality maps 1:1 onto CLR metadata like the JVM's ACC_FINAL: FINAL keeps `sealed`,
        // OPEN drops it (probe-verified, inheritprobe_s1). ABSTRACT and SEALED classes need an
        // abstract-member/instantiability model that does not exist yet. The singleton shapes
        // are final in Kotlin anyway; the branch is defensive.
        when (irClass.modality) {
            Modality.FINAL -> {}
            Modality.OPEN ->
                if (isValidatedCompanion || irClass.kind == ClassKind.OBJECT) {
                    dotNetUnsupported("non-final object '$name' is not supported")
                }
            Modality.ABSTRACT ->
                dotNetUnsupported("abstract class '$name' is not supported (no abstract-class model)")
            Modality.SEALED ->
                dotNetUnsupported("sealed class '$name' is not supported (no abstract-class model)")
        }
        // Generic TOP-LEVEL PLAIN classes use real CLR reified generics
        // (Roslyn shape — `.class ... 'C`n'<T>`, `!n` member signatures, instantiation tokens in
        // every operand position; probe series genprobe), no erasure or lowering machinery. The
        // gate scopes the stage: invariant non-reified parameters, optionally constrained by
        // supported module-local classes/interfaces, no nested
        // declarations (the companion/object machinery is untouched by this slice), no interface
        // supertypes, and no generic-extends-generic chains (untested override/pre-pass
        // interactions — the IL shape itself is probed, genprobe_s7, so a later slice can widen).
        // Kotlin objects and companions cannot be generic; the branch is defensive.
        if (irClass.typeParameters.isNotEmpty()) {
            if (irClass.kind == ClassKind.OBJECT || isValidatedCompanion) {
                dotNetUnsupported("generic object '$name' is not supported")
            }
            checkDotNetTypeParametersSupported(irClass.typeParameters, "class '$name'")
            if (irClass.declarations.any { it is IrClass }) {
                dotNetUnsupported(
                    "generic class '$name' contains nested declarations (companion or nested objects); " +
                            "nested declarations in generic classes are not supported yet"
                )
            }
            for (superType in irClass.superTypes.filterNot { it.isAny() }) {
                val superClass = ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                if (superClass?.isInterface == true) {
                    dotNetUnsupported(
                        "generic class '$name' implements an interface; " +
                                "interface supertypes of generic classes are not supported yet"
                    )
                }
                if (superClass != null && superClass.typeParameters.isNotEmpty()) {
                    dotNetUnsupported(
                        "generic class '$name' extends a generic base class; " +
                                "generic-to-generic inheritance is not supported yet"
                    )
                }
            }
        }
        val superTypesExceptAny = irClass.superTypes.filterNot { it.isAny() }
        if (superTypesExceptAny.isNotEmpty()) {
            // Exception supertypes get a message naming the real gap: the supertype itself is
            // supported (type-mapped, see DotNetMappedExceptions), subclassing it is not — a
            // mapped exception is a corelib type, not a module class the inheritance model can
            // extend.
            if (irClass.superTypes.any { it.classFqName in DotNetMappedExceptions.entries }) {
                dotNetUnsupported(
                    "class '$name' extends an exception class; " +
                            "user-defined exception classes are not supported until the inheritance model exists"
                )
            }
            // The singleton shapes stay on the sole-supertype-Any model: `object O : Base()` is
            // legal Kotlin, but chaining the singleton machinery to a base class is out of the
            // inheritance slice's scope.
            if (isValidatedCompanion || irClass.kind == ClassKind.OBJECT) {
                val kindWord = if (isValidatedCompanion) "companion object" else "object"
                dotNetUnsupported("$kindWord '$name' with a supertype other than kotlin.Any is not supported")
            }
            val superClasses = superTypesExceptAny.map { superType ->
                ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                    ?: dotNetUnsupported("class '$name' with a supertype other than kotlin.Any is not supported")
            }
            // A class may implement any number of module-local top-level interfaces next to its
            // (at most one) base class; whether each interface itself compiles is deliberately
            // NOT checked here — the render re-resolves the `implements` list every fixpoint
            // round, so an evicted interface cascades whole-class with a carried reason, exactly
            // like an evicted base class.
            for (superInterface in superClasses.filter { it.isInterface }) {
                if (superInterface !in moduleTopLevelClasses) {
                    dotNetUnsupported(
                        "class '$name' implements '${superInterface.diagnosticName()}', which is not a top-level " +
                                "interface of the compiled module; only module-local interfaces are supported"
                    )
                }
            }
            val properSuperClasses = superClasses.filterNot { it.isInterface }
            if (properSuperClasses.size > 1) {
                dotNetUnsupported("internal: class '$name' has more than one class supertype")
            }
            val superClass = properSuperClasses.singleOrNull()
            if (superClass != null && superClass !in moduleTopLevelClasses) {
                dotNetUnsupported(
                    "class '$name' extends '${superClass.diagnosticName()}', which is not a top-level class " +
                            "of the compiled module; only module-local base classes are supported"
                )
            }
        }
        // Overriding a kotlin.Any member (toString/equals/hashCode) needs a virtual-slot
        // relationship with System.Object's members — an Any model that does not exist yet
        // (the same gap that keeps data classes and general `==` rejected). Declared overrides
        // are rejected whole-class; fake overrides stay exempt — they are skipped at render and
        // calls to them stay rejected at the availableFunctions miss. The override chain is
        // walked with the TYPE-based isAny (an FqName comparison, like the supertype checks
        // above): the ir.util findOverriddenMethodOfAny shortcut relies on IrClass.isAny,
        // an IdSignature comparison, and this pipeline's symbols carry no signatures, so
        // it never matches here.
        for (member in irClass.dotNetMemberFunctions()) {
            // Stage-1 generics supports generic top-level FUNCTIONS and generic CLASSES; a
            // generic MEMBER function (its own <T> next to the class's) is a separate,
            // unexercised combination and stays rejected, whole-class.
            if (member.typeParameters.isNotEmpty()) {
                dotNetUnsupported(
                    "member '${member.name.asString()}' of class '$name' is generic; " +
                            "generic member functions are not supported yet"
                )
            }
            if (member.allOverridden().any { (it.parent as? IrClass)?.defaultType?.isAny() == true }) {
                dotNetUnsupported(
                    "member '${member.name.asString()}' of class '$name' overrides a member of kotlin.Any; " +
                            "kotlin.Any member overrides are not supported (no Any model)"
                )
            }
        }
        // Interface delegation (`class C(...) : I by d`): the frontend synthesizes forwarding
        // members (origin DELEGATED_MEMBER) over a delegate value — for a plain constructor
        // parameter additionally a loose synthetic field (origin DELEGATE). Neither shape is
        // part of the interface model yet (no probe, no golden, no box coverage), and the two
        // cosmetically different source spellings (`val` parameter vs plain parameter) must not
        // diverge in support, so ALL `by`-delegation is rejected here, whole-class, with a real
        // user-facing message (the plain-parameter form would otherwise trip renderUserClass's
        // internal propertyless-field invariant).
        for (declaration in irClass.declarations) {
            val isDelegationArtifact = when (declaration) {
                is IrField -> declaration.origin == IrDeclarationOrigin.DELEGATE
                is IrSimpleFunction -> declaration.origin == IrDeclarationOrigin.DELEGATED_MEMBER
                is IrProperty -> declaration.origin == IrDeclarationOrigin.DELEGATED_MEMBER
                else -> false
            }
            if (isDelegationArtifact) {
                dotNetUnsupported(
                    "class '$name' implements an interface by delegation ('by'); " +
                            "interface delegation is not supported yet"
                )
            }
        }
        // Interface slots bound through INHERITED members (the Kotlin fake-override shape:
        // a base-class member satisfies an interface the derived class declares) work on the
        // CLR only when the inherited member is VIRTUAL (probe-verified, ifaceprobe_s5a); an
        // inherited NON-virtual member assembles cleanly but load-poisons the type — every use
        // throws TypeLoadException at JIT time (ifaceprobe_s5b) — so the shape is gated here,
        // whole-class. The inherited member's mapped IL signature must ALSO match the interface
        // slot's exactly, return type included — the covariant-return variant load-poisons the
        // type the same way (ifaceprobe_s10) — but that comparison needs the type mapper, which
        // does not exist yet at gate time, so it lives in the member pre-pass
        // (checkInheritedInterfaceImplKeepsIlReturnType). Declared overrides need no virtualness
        // check: every Kotlin `override` is emitted virtual (see isDotNetVirtual). A fake
        // override resolving into an interface (a default interface method) passes this gate and
        // is instead evicted at render when its interface is (interface members with bodies are
        // interface-gate-rejected).
        for (member in irClass.dotNetMemberFakeOverrides()) {
            if (member.allOverridden().none { (it.parent as? IrClass)?.isInterface == true }) continue
            val implementation = member.resolveFakeOverride()
                ?: dotNetUnsupported(
                    "member '${member.name.asString()}' of class '$name' implements an interface member " +
                            "without any inherited implementation"
                )
            if (!implementation.isDotNetVirtual()) {
                val implementationOwner = (implementation.parent as? IrClass)?.diagnosticName() ?: "?"
                dotNetUnsupported(
                    "member '${member.name.asString()}' of class '$name' implements an interface member through " +
                            "the non-virtual inherited member '$implementationOwner.${implementation.name.asString()}'; " +
                            "the CLR binds interface slots only to virtual members — the non-virtual shape assembles " +
                            "but load-poisons the type with TypeLoadException (probe ifaceprobe_s5b) — so this shape " +
                            "is not supported"
                )
            }
        }
        for (declaration in irClass.declarations) {
            if (declaration is IrClass) {
                // The companion object of a top-level class (Kotlin allows at most one) is the
                // single supported nested shape, recursively validated with the same constraint
                // chain; the recursion is depth-one because a companion cannot itself contain a
                // companion (the frontend rejects it). Every other nested class — including any
                // nested class of the companion — stays rejected, whole-class.
                if (!isValidatedCompanion && declaration.isCompanion && declaration.kind == ClassKind.OBJECT) {
                    checkClassShapeSupported(declaration, moduleTopLevelClasses, isValidatedCompanion = true)
                    continue
                }
                val kindWord = if (declaration.kind == ClassKind.OBJECT) "object" else "class"
                dotNetUnsupported("$kindWord '${declaration.diagnosticName()}' is not top-level; nested/inner/local classes are not supported")
            }
        }
    }

    /**
     * The interface half of the shape gate (probe series `ifaceprobe_s1`–`_s10`; JVM precedent:
     * the CLR has real interface types, so like classes there is no vtable/interface lowering —
     * a Kotlin `interface` becomes a CLR `.class interface abstract`): a top-level, non-generic
     * plain interface whose members are ALL abstract (abstract functions and abstract `val`/`var`
     * properties) is compilable, and it may extend any number of module-local top-level
     * interfaces (`ifaceprobe_s6`: interface inheritance is the same `implements` list; whether
     * each super-interface itself compiles is re-resolved live every render round, so an evicted
     * interface cascades to its sub-interfaces). A `sealed interface` is deliberately ACCEPTED
     * and emitted as a plain interface — unlike a sealed CLASS (which needs the missing
     * abstract-class model), interface sealedness is pure frontend-enforced metadata with no
     * CLR counterpart needed (JVM precedent: the JVM backend emits an ordinary interface too),
     * and the exhaustive `when` it enables is `is`-gated by the type-operator rejection anyway;
     * pinned by ilText/interfaceEqualityWidening.kt. Everything else stays rejected, whole-interface:
     * `fun interface` (no SAM-conversion model), generic interfaces, non-top-level interfaces,
     * out-of-module super-interfaces, members WITH bodies — default methods and accessors with
     * bodies — (the CLR itself supports Default Interface Methods, probe-verified
     * `ifaceprobe_s8`, but this backend has no DIM model yet, so the limitation is
     * backend-scope), private interface members, abstract redeclarations of super-interface
     * members (an unprobed double-slot shape), overrides of `kotlin.Any` members (the same
     * no-Any-model gap as on classes), and nested declarations including companion objects.
     */
    private fun checkInterfaceShapeSupported(irClass: IrClass, moduleTopLevelClasses: Set<IrClass>) {
        val name = irClass.diagnosticName()
        if (irClass.isFun) {
            dotNetUnsupported("fun interface '$name' is not supported (no SAM-conversion model)")
        }
        if (irClass.parent !is IrFile) {
            dotNetUnsupported("interface '$name' is not top-level; nested/local interfaces are not supported")
        }
        if (irClass.isExpect) dotNetUnsupported("expect interface '$name' is not supported")
        if (irClass.typeParameters.isNotEmpty()) {
            dotNetUnsupported("generic interface '$name' is not supported yet")
        }
        for (superType in irClass.superTypes) {
            if (superType.isAny()) continue
            val superInterface = ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                ?: dotNetUnsupported("interface '$name' has an unsupported supertype")
            if (!superInterface.isInterface || superInterface !in moduleTopLevelClasses) {
                dotNetUnsupported(
                    "interface '$name' extends '${superInterface.diagnosticName()}', which is not a top-level " +
                            "interface of the compiled module; only module-local super-interfaces are supported"
                )
            }
        }
        for (declaration in irClass.declarations) {
            when (declaration) {
                is IrClass -> {
                    val kindWord = when {
                        declaration.isCompanion -> "companion object"
                        declaration.kind == ClassKind.OBJECT -> "object"
                        declaration.kind == ClassKind.INTERFACE -> "interface"
                        else -> "class"
                    }
                    dotNetUnsupported(
                        "interface '$name' contains nested $kindWord '${declaration.name.asString()}'; " +
                                "nested declarations in interfaces are not supported"
                    )
                }
                is IrSimpleFunction -> checkInterfaceMemberSupported(
                    declaration, name, "member '${declaration.name.asString()}'"
                )
                is IrProperty -> {
                    if (declaration.isFakeOverride) continue
                    val propertyName = declaration.name.asString()
                    if (declaration.isDelegated) {
                        dotNetUnsupported("delegated property '$propertyName' of interface '$name' is not supported")
                    }
                    if (declaration.backingField != null) {
                        dotNetUnsupported(
                            "property '$propertyName' of interface '$name' has an initializer or backing field; " +
                                    "interface property state is not supported"
                        )
                    }
                    declaration.getter?.let {
                        checkInterfaceMemberSupported(it, name, "getter of property '$propertyName'")
                    }
                    declaration.setter?.let {
                        checkInterfaceMemberSupported(it, name, "setter of property '$propertyName'")
                    }
                }
                else -> dotNetUnsupported(
                    "unsupported member of interface '$name': ${declaration.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * One interface member (a function or a property accessor) of the interface shape gate; see
     * [checkInterfaceShapeSupported] for the model and the probe citations.
     */
    private fun checkInterfaceMemberSupported(member: IrSimpleFunction, interfaceName: String, description: String) {
        if (member.isFakeOverride) return
        if (member.typeParameters.isNotEmpty()) {
            dotNetUnsupported(
                "$description of interface '$interfaceName' is generic; generic interface members are not supported"
            )
        }
        if (member.visibility == DescriptorVisibilities.PRIVATE) {
            dotNetUnsupported("private $description of interface '$interfaceName' is not supported")
        }
        if (member.body != null || member.modality != Modality.ABSTRACT) {
            dotNetUnsupported(
                "$description of interface '$interfaceName' has a body; interface members with bodies are not yet " +
                        "supported (the CLR supports Default Interface Methods — a future backend slice, not a " +
                        "platform limitation)"
            )
        }
        if (member.allOverridden().any { (it.parent as? IrClass)?.defaultType?.isAny() == true }) {
            dotNetUnsupported(
                "$description of interface '$interfaceName' overrides a member of kotlin.Any; " +
                        "kotlin.Any member overrides are not supported (no Any model)"
            )
        }
        if (member.overriddenSymbols.isNotEmpty()) {
            dotNetUnsupported(
                "$description of interface '$interfaceName' redeclares a super-interface member; abstract " +
                        "redeclarations are not supported (the redeclaration would occupy a second, unprobed " +
                        "interface slot)"
            )
        }
    }

    /**
     * Rejects a covariant-return override, whole-class via the member pre-pass: ECMA-335
     * implicit slot matching (II.15.4.2.3) includes the RETURN type, so an override whose
     * mapped return differs from the overridden slot's — `virtual` without `newslot` binds by
     * name-and-signature, not by intent — would silently land in a FRESH slot and base-typed
     * `callvirt` would run the BASE implementation (probe-verified: the exact emitted shape
     * assembles without any ilasm diagnostic and misdispatches on CoreCLR). Roslyn supports C#
     * covariant returns only through explicit `.override` + `PreserveBaseOverrides` machinery
     * this backend does not emit, so the shape is rejected loudly instead — never wrong IL.
     * Kotlin covariance that maps to the SAME IL type (`String?` overridden by `String`, both
     * `string`) keeps the slot and stays supported, which is why the comparison runs on MAPPED
     * types; the whole [allOverridden] chain is compared so a leaf-vs-root mismatch is caught
     * even when the intermediate class matches one side. An overridden declaration whose own
     * return does not map is skipped here: its class fails its own pre-pass, and the eviction
     * reaches this class through the render's base re-resolution with a carried reason. This
     * check only sees DECLARED members; the interface-mapping variant of the same failure mode
     * on INHERITED members is [checkInheritedInterfaceImplKeepsIlReturnType]'s.
     */
    private fun checkOverrideKeepsIlReturnType(
        member: IrSimpleFunction,
        signature: DotNetIlMethodSignature,
        typeMapper: DotNetIlTypeMapper,
    ) {
        if (member.overriddenSymbols.isEmpty()) return
        val memberClass = member.parent as? IrClass
        for (overridden in member.allOverridden()) {
            val overriddenReturnType = typeMapper.toDotNetIlReturnType(overridden.returnType) ?: continue
            val overriddenClass = overridden.parent as? IrClass
            // An overridden member of a GENERIC base declares its return against the base's type
            // parameters (`fun describe(): T` maps to `!0`); CLR slot matching for the derived
            // override then runs against the SUBSTITUTED signature — the override MUST be spelled
            // with the substituted type (`int32`), which lands in the base slot (probe-verified,
            // genprobe_s5 for returns, genprobe_s8 for parameters), while the open `!0` spelling
            // assembles warning-free and silently splits the slot (the s5b poison shape this
            // backend never emits: derived members are mapped from their own concrete Kotlin
            // types). So the comparison substitutes the derived class's instantiation of the
            // base before comparing — otherwise every substituted override would falsely trip
            // the covariant-return rejection.
            val substitutedReturnType =
                if (memberClass != null && overriddenClass != null && overriddenClass.typeParameters.isNotEmpty()) {
                    val classArguments = memberClass.dotNetClassArgumentsFor(overriddenClass, typeMapper) ?: continue
                    overriddenReturnType.substituteDotNetTypeParameters(classArguments)
                } else overriddenReturnType
            if (substitutedReturnType != signature.returnType) {
                val overriddenOwner = overriddenClass?.diagnosticName() ?: "?"
                dotNetUnsupported(
                    "member '${member.name.asString()}' overrides '$overriddenOwner.${overridden.name.asString()}' " +
                            "with a different IL return type (${signature.returnType.nameInSignature} vs " +
                            "${substitutedReturnType.nameInSignature}); covariant-return overrides are not supported " +
                            "(the override would not reuse the base virtual slot)"
                )
            }
        }
    }

    /**
     * The IL type arguments [this] class's inheritance chain supplies for [target]'s type
     * parameters: the composed substitution of every base-supertype hop from this class up to
     * [target] (`IntBox : Box<Int>` yields `[int32]` for `Box`'s `T`; chains through
     * intermediate non-generic classes compose). Starts from this class's own OPEN instantiation
     * so the walk stays correct if generic-extends-generic chains are ever admitted. Null when
     * [target] is not on the base chain or a hop does not map — the chain is then broken in a
     * way another gate already owns (an evicted or unregistered base cascades through the
     * render's live re-resolution), so callers skip rather than double-report.
     */
    private fun IrClass.dotNetClassArgumentsFor(target: IrClass, typeMapper: DotNetIlTypeMapper): List<DotNetIlValueType>? {
        var current: IrClass = this
        var currentArguments: List<DotNetIlValueType> =
            typeParameters.indices.map { DotNetIlValueType.TypeParameter(it, isMethodParameter = false) }
        while (current != target) {
            val baseSuperType = current.dotNetBaseSuperTypeOrNull() ?: return null
            val baseClass = (baseSuperType.classifier as? IrClassSymbol)?.owner ?: return null
            currentArguments = baseSuperType.arguments.map { argument ->
                val projection = (argument as? IrTypeProjection)?.takeIf { it.variance == Variance.INVARIANT }
                    ?: return null
                val mapped = typeMapper.toDotNetIlValueType(projection.type) ?: return null
                mapped.substituteDotNetTypeParameters(currentArguments)
            }
            current = baseClass
        }
        return currentArguments
    }

    /**
     * Rejects an interface slot filled by an INHERITED member whose mapped IL return type
     * differs from the interface member's, whole-class via the member pre-pass — the
     * fake-override complement of [checkOverrideKeepsIlReturnType], which only sees declared
     * members: ECMA-335 implicit interface mapping matches candidate methods by name and FULL
     * signature INCLUDING the return type, and this backend emits no `.override` arrows, so in
     * the Kotlin-legal shape `class Combo : Factory(), Maker` — the inherited
     * `Factory.make(): Bottom` meant to satisfy `Maker.make(): Top` — the `Maker::make` slot
     * has no implementation at all. ilasm assembles the shape without any diagnostic and EVERY
     * use of the class throws TypeLoadException at first JIT of a using method (probe-verified,
     * `ifaceprobe_s10`, both the function and the property-accessor variants; the JVM supports
     * the shape because its backend generates bridge methods, which this backend has no
     * analogue of). The check is scoped to fake overrides that override at least one
     * interface-parented member: the `kotlin.Any` fake overrides present on every class must
     * not be signature-mapped (`equals(Any?)` has no IL mapping), and a return mismatch against
     * a BASE-CLASS member cannot survive to a fake override (the declaring class's own pre-pass
     * ran [checkOverrideKeepsIlReturnType] over the declared chain). Kotlin covariance mapping
     * to the SAME IL type (`String?` implemented by an inherited `String` member) stays
     * supported — the comparison runs on MAPPED types — and an overridden interface member
     * whose own return does not map is skipped here: its interface fails its own pre-pass and
     * the eviction cascades through the render's `implements` re-resolution with a carried
     * reason.
     */
    private fun checkInheritedInterfaceImplKeepsIlReturnType(
        member: IrSimpleFunction,
        typeMapper: DotNetIlTypeMapper,
    ) {
        val overriddenInterfaceMembers = member.allOverridden()
            .filter { (it.parent as? IrClass)?.isInterface == true }
        if (overriddenInterfaceMembers.isEmpty()) return
        // The fake override's own return type equals the inherited implementation's; when it
        // does not map, the implementation's declaring class fails its own pre-pass and this
        // class falls through the base-chain cascade with a carried reason instead.
        val memberReturnType = typeMapper.toDotNetIlReturnType(member.returnType) ?: return
        for (overridden in overriddenInterfaceMembers) {
            val overriddenReturnType = typeMapper.toDotNetIlReturnType(overridden.returnType) ?: continue
            if (overriddenReturnType != memberReturnType) {
                val interfaceName = (overridden.parent as? IrClass)?.diagnosticName() ?: "?"
                dotNetUnsupported(
                    "member '${member.name.asString()}' implements interface member " +
                            "'$interfaceName.${overridden.name.asString()}' through an inherited member with a " +
                            "different IL return type (${memberReturnType.nameInSignature} vs " +
                            "${overriddenReturnType.nameInSignature}); ECMA-335 interface mapping matches the " +
                            "full signature including the return type, so the interface slot would have no " +
                            "implementation and every use of the class would throw TypeLoadException " +
                            "(probe ifaceprobe_s10)"
                )
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
     * whole class from the module (fail-loud, never partial emission). Fake overrides are
     * skipped like on the JVM: calls through an INHERITED member resolve to the declaring
     * class's real declaration at the call site
     * ([DotNetIlExpressionCodegen.emitCall][DotNetIlExpressionCodegen.emitCall]), while the
     * `kotlin.Any` fake overrides (`equals`/`hashCode`/`toString`) resolve to nothing emitted
     * and stay rejected.
     *
     * A class of the INHERITANCE model renders `extends <base>` instead of `System.Object` and,
     * when `open`, drops `sealed` (both probe-verified, `inheritprobe_s1`); its `open` members
     * and overrides carry virtual flags (see
     * [DotNetIlMethodCodegen]'s `dotNetVirtualFlags`). The base is re-resolved from the live
     * class map at the top of every render, so an evicted base cascades down the chain.
     *
     * A Kotlin INTERFACE renders through this same path as a `.class interface abstract` with no
     * `extends` line, an `implements` list naming its direct super-interfaces, abstract member
     * methods with empty bodies and ordinary `.property` blocks over the abstract accessors
     * (all spellings probe-verified, `ifaceprobe_s1`/`_s2`/`_s6`); a class implementing
     * interfaces adds the `implements` list after its `extends` line (`ifaceprobe_s3`). The
     * `implements` list is re-resolved live exactly like the base, so an evicted interface
     * cascades to every implementer and sub-interface.
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
        classSkipReasons: Map<IrClass, String>,
    ): RenderedClass {
        val name = irClass.diagnosticName()
        // The base class of the inheritance model is re-resolved through the LIVE map every
        // fixpoint round: a base that failed its own shape gate or was evicted (member
        // pre-pass or an earlier render round) must take every derived class down the chain
        // with it — a derived class whose base does not exist cannot keep its `extends` line —
        // each warned with a reason carrying the base's own reason, the inheritance
        // counterpart of the companion pair warnings.
        val baseClassRef = irClass.dotNetBaseSuperTypeOrNull()?.let { baseSuperType ->
            val baseClass = (baseSuperType.classifier as IrClassSymbol).owner
            val baseClassInfo = typeMapper.classInfoOrNull(baseClass) ?: dotNetUnsupported(
                "its base class '${baseClass.diagnosticName()}' could not be compiled: " +
                        (classSkipReasons[baseClass] ?: "the base class is not available in this module")
            )
            if (baseClass.typeParameters.isEmpty()) {
                // The established non-generic spelling: `extends 'demo.Base'`.
                baseClassInfo.ilTypeRef
            } else {
                // An instantiated generic base: `extends class 'demo.Box`1'<int32>` (probe-
                // verified, genprobe_s5). Re-mapped through the LIVE type mapper every render
                // round like the base itself, so an instantiation mentioning an evicted class
                // fails the derived class here with a carried reason (the type-argument arm of
                // the base-eviction cascade).
                val baseType = typeMapper.toDotNetIlValueType(baseSuperType) as? DotNetIlValueType.GenericInstance
                    ?: dotNetUnsupported(
                        "its base class instantiation '${baseSuperType.render()}' could not be compiled: " +
                                "a type argument is not available in this module"
                    )
                baseType.nameInSignature
            }
        }
        // The `implements` list is re-resolved through the LIVE map every render round exactly
        // like the base class above: an evicted interface takes every implementing class and
        // every sub-interface down with it, each warned with a reason carrying the interface's
        // own reason (the interface arm of the inheritance cascade).
        val interfaceInfos = irClass.dotNetDirectInterfaces().map { superInterface ->
            typeMapper.classInfoOrNull(superInterface) ?: dotNetUnsupported(
                "its ${if (irClass.isInterface) "extended" else "implemented"} interface " +
                        "'${superInterface.diagnosticName()}' could not be compiled: " +
                        (classSkipReasons[superInterface] ?: "the interface is not available in this module")
            )
        }
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
                            classSkipReasons = classSkipReasons,
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
                // An open class drops `sealed` (the CLR metadata form of Kotlin's modality, like
                // the JVM's ACC_FINAL); companions and objects never reach here as open — the
                // shape gate keeps them final-only.
                isOpen = irClass.modality == Modality.OPEN,
                baseClassRef = baseClassRef,
                isInterface = irClass.isInterface,
                interfaceRefs = interfaceInfos.map { it.ilTypeRef },
                // The formal type-parameter list of a generic class: `<'T'>`, or the stage-2
                // constrained `<(class 'Base', class 'Mark') 'T'>`, between the class name and
                // `extends` (genprobe_s8, genconstraintprobe_s1).
                genericParameters = irClass.typeParameters.renderDotNetIlGenericParameters(typeMapper),
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
            // The frontend forbids `const val` of nullable, class and generic types, so these
            // arms are defensive; a CLR `literal` field of such a type is unprobed anyway.
            is DotNetIlValueType.UserClass, is DotNetIlValueType.MappedClass,
            is DotNetIlValueType.NullableValue, DotNetIlValueType.Object,
            is DotNetIlValueType.GenericInstance, is DotNetIlValueType.TypeParameter,
            is DotNetIlValueType.PrimitiveArray,
                -> unsupportedValue()
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
     * The fake-override member functions of a user class (inherited members re-materialized on
     * the class, including property accessors), for the shape gate's inherited-interface-
     * implementation check ([ifaceprobe_s5b][checkClassShapeSupported]) — the complement of
     * [dotNetMemberFunctions], which skips exactly these.
     */
    private fun IrClass.dotNetMemberFakeOverrides(): List<IrSimpleFunction> =
        declarations.flatMap { declaration ->
            when (declaration) {
                is IrSimpleFunction -> if (declaration.isFakeOverride) listOf(declaration) else emptyList()
                is IrProperty ->
                    listOfNotNull(declaration.getter, declaration.setter).filter { it.isFakeOverride }
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
