package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.defaultArgumentsDispatchFunction
import org.jetbrains.kotlin.backend.common.lower.LocalDeclarationsLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_STATIC_INITIALIZER
import org.jetbrains.kotlin.backend.dotnet.lower.dotNetDefaultParameterIndices
import org.jetbrains.kotlin.backend.dotnet.lower.dotNetInventedLocalClassName
import org.jetbrains.kotlin.backend.dotnet.lower.dotNetLocalCaptureRejectionReason
import org.jetbrains.kotlin.backend.dotnet.lower.isDotNetCallableObject
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
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isAnonymousObject
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isKFunction
import org.jetbrains.kotlin.ir.util.isOriginallyLocalDeclaration
import org.jetbrains.kotlin.ir.util.isSuspendFunction
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.name.Name

class DotNetIlEmitter(
    private val messageCollector: MessageCollector,
    private val assemblyName: String,
    private val moduleFileName: String,
    private val producesExecutable: Boolean,
    private val irBuiltIns: IrBuiltIns,
    private val exports: List<DotNetExport> = emptyList(),
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

        // Export selection is deliberately external compiler configuration, not a Kotlin source
        // annotation or an automatic public overload policy. One FQ name must identify exactly
        // one real top-level declaration; overloaded groups require a future signature selector
        // rather than an arbitrary backend pick.
        val exportsByTarget = LinkedHashMap<IrSimpleFunction, DotNetExport>()
        var exportSelectionFailed = false
        for (export in exports) {
            val candidates = topLevelFunctionsByFile.values.flatten().filter { function ->
                !function.isOriginallyLocalDeclaration && function.fqNameWhenAvailable?.asString() == export.kotlinFqName
            }
            if (candidates.size != 1) {
                val reason = if (candidates.isEmpty()) "no top-level function was found" else "the name is overloaded"
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "Cannot export '${export.kotlinFqName}' as '${export.clrMethodName}': $reason."
                )
                exportSelectionFailed = true
                continue
            }
            val target = candidates.single()
            val previous = exportsByTarget.putIfAbsent(target, export)
            if (previous != null) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "Cannot export '${export.kotlinFqName}' more than once."
                )
                exportSelectionFailed = true
            }
        }
        if (exportSelectionFailed) return null
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

        // Class pre-pass: the shape gate. Everything outside the supported class model is
        // rejected whole-class here, so the type mapper only ever sees supported classes. The
        // injected exception declarations (see DotNetMappedExceptions) are excluded up front — the
        // class-level parallel of an intrinsic's excludesDeclarationFromCodegen: they exist for
        // frontend resolution only and must be neither emitted nor skip-warned.
        val availableClasses = LinkedHashMap<IrClass, DotNetIlClassInfo>()
        val classSkipReasons = LinkedHashMap<IrClass, String>()
        val usedIlTypeRefs = hashSetOf<String>()
        val moduleTopLevelClasses = topLevelClassesByFile.values.flatten().toHashSet()
        val moduleClasses = buildSet {
            fun collect(irClass: IrClass) {
                add(irClass)
                irClass.declarations.filterIsInstance<IrClass>().forEach(::collect)
            }
            moduleTopLevelClasses.forEach(::collect)
        }
        val moduleInterfaces = moduleClasses.filterTo(hashSetOf()) { it.isInterface }

        // A CLR nested type is registered independently for type/member resolution, while its
        // metadata block renders recursively inside the enclosing type. Kotlin named nested
        // declarations are static-style types (JVM precedent: ACC_STATIC unless a class is
        // `inner`), so a nested type owns only its own generic parameters. The CLR accepts exactly
        // that shape: the nested type's simple arity-suffixed name composes with the enclosing
        // type reference without inheriting the enclosing type's `!n` parameter space
        // (`nestedprobe_s1`, `nestedifaceprobe_s3`).
        //
        // Registration is deliberately per declaration rather than all-or-nothing for the
        // top-level metadata family. A rejected nested class can simply be omitted from its
        // otherwise valid enclosing class; only its own metadata subtree is structurally tied to
        // it. Its parent, siblings, and unrelated users remain available, while actual type/body
        // dependencies are removed later by the live-map render fixpoint.
        fun registerClassTree(irClass: IrClass, enclosingClassInfo: DotNetIlClassInfo? = null) {
            try {
                checkClassShapeSupported(irClass, moduleClasses, moduleInterfaces)
            } catch (e: DotNetIlUnsupportedException) {
                // A gate failure OF a companion invalidates its immediate owner: that owner
                // already contains the singleton field and `.cctor`. A separate declaration
                // nested below an otherwise valid companion remains an independent metadata
                // subtree and is omitted on its own.
                val companion = irClass.takeIf { it.isCompanion }
                val unavailableRoot = companion?.parent as? IrClass ?: irClass
                val rootReason = if (companion == null) {
                    e.reason
                } else {
                    "its companion object '${companion.diagnosticName()}' could not be compiled: ${e.reason}"
                }
                fun removeAndRecordUnavailableSubtree(unavailableClass: IrClass) {
                    availableClasses.remove(unavailableClass)
                    val unavailableReason = when {
                        unavailableClass === irClass -> e.reason
                        unavailableClass === unavailableRoot -> rootReason
                        else ->
                            "its enclosing class '${unavailableRoot.diagnosticName()}' could not be compiled: $rootReason"
                    }
                    classSkipReasons.putIfAbsent(
                        unavailableClass,
                        unavailableReason,
                    )
                    unavailableClass.declarations.filterIsInstance<IrClass>().forEach { child ->
                        removeAndRecordUnavailableSubtree(child)
                    }
                }
                removeAndRecordUnavailableSubtree(unavailableRoot)
                return
            }
            val aritySuffix = irClass.typeParameters.size.takeIf { it > 0 }?.let { "`$it" }.orEmpty()
            val baseName = irClass.dotNetInventedLocalClassName ?: if (enclosingClassInfo == null) {
                irClass.fqNameWhenAvailable!!.asString()
            } else {
                irClass.name.asString()
            }
            var collisionSuffix = 0
            var classInfo: DotNetIlClassInfo
            while (true) {
                val disambiguatedBaseName =
                    if (collisionSuffix == 0) baseName else "$baseName\$$collisionSuffix"
                classInfo = DotNetIlClassInfo(
                    disambiguatedBaseName + aritySuffix,
                    enclosingClass = enclosingClassInfo,
                    typeParameterVariances = irClass.typeParameters.map { it.variance },
                )
                if (usedIlTypeRefs.add(classInfo.ilTypeRef)) break
                if (!irClass.isOriginallyLocalDeclaration) {
                    classSkipReasons.putIfAbsent(
                        irClass,
                        "class '${irClass.diagnosticName()}' maps to duplicate IL type '${classInfo.ilTypeRef}'",
                    )
                    return
                }
                collisionSuffix++
            }
            availableClasses[irClass] = classInfo
            val nestedClasses = irClass.declarations.filterIsInstance<IrClass>()
                .sortedBy { it.isOriginallyLocalDeclaration }
            for (nestedClass in nestedClasses) {
                registerClassTree(nestedClass, classInfo)
                // A companion can promote its own failure to this owner. Do not register later
                // siblings under an owner that was removed.
                if (irClass !in availableClasses) return
            }
        }

        for (irClass in topLevelClassesByFile.values.flatten().sortedBy { it.isOriginallyLocalDeclaration }) {
            if (DotNetMappedExceptions.isExceptionStdlibDeclaration(irClass)) continue
            // A generic class's IL name carries the CLS `` `n `` arity suffix INSIDE the
            // quoted identifier ('demo.Box`1' — outside the quotes is an ilasm syntax error,
            // probe-verified genprobe_s2/_s2c). The suffix is CLS convention rather than a
            // CLR requirement (genprobe_s2b) but is emitted for Roslyn/interop parity, which
            // also guarantees a plain `Box` and a generic `Box<T>` can never collide in IL.
            registerClassTree(irClass)
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
        // links. The base link is the full SUPERTYPE token — closed (`D : Box<Int>`) or open and
        // composed (`D<A, B> : Box<B, A>`) — and must widen only to that exact instantiation. A
        // base whose instantiation mentions an unmappable or unavailable type simply leaves the
        // link out (the render's live re-resolution owns the eviction and its carried reason).
        for ([irClass, classInfo] in availableClasses) {
            if (irClass.isCompanion) continue
            classInfo.baseType = irClass.dotNetBaseSuperTypeOrNull()?.let { baseSuperType ->
                try {
                    typeMapper.toDotNetIlValueType(baseSuperType)
                } catch (_: DotNetIlUnsupportedException) {
                    null
                }
            }
            classInfo.interfaces = irClass.dotNetDirectInterfaceTypes().mapNotNull { interfaceType ->
                try {
                    typeMapper.toDotNetIlValueType(interfaceType)
                } catch (_: DotNetIlUnsupportedException) {
                    null
                }
            }
        }
        // Static facade-field references (`ldsfld`/`stsfld` of top-level property backing
        // fields) resolve their owning IL class through this map, the facade counterpart of
        // [DotNetIlTypeMapper.classInfoOrNull].
        val facadeClassInfoByFile = files.associateWith { DotNetIlClassInfo(fileClassNames.getValue(it)) }

        val availableFunctions = LinkedHashMap<IrSimpleFunction, DotNetIlFunctionInfo>()
        DotNetRuntimeTypes.registerCallableFunctions(irBuiltIns, typeMapper, availableFunctions)
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
                    function.checkDotNetFunctionShapeSupported()
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

        // A class failure removes the smallest metadata subtree that cannot exist without it.
        // An ordinary nested class owns its own block, so its enclosing class and siblings are
        // independent. A companion is the exception: its singleton field and `.cctor` live on
        // its immediate owner, so a failed companion takes that owner (and therefore the owner's
        // descendants) with it. The live type/function maps then remove real users through the
        // normal render fixpoint instead of assuming every metadata relative is a dependency.
        fun evictClassSubtree(failedClass: IrClass, reason: String) {
            val evictionRoot =
                if (failedClass.isCompanion) failedClass.parent as? IrClass ?: failedClass else failedClass
            val subtree = buildList {
                fun collect(irClass: IrClass) {
                    add(irClass)
                    irClass.declarations.filterIsInstance<IrClass>().forEach(::collect)
                }
                collect(evictionRoot)
            }
            val rootReason = if (failedClass === evictionRoot) {
                reason
            } else {
                "its companion object '${failedClass.diagnosticName()}' could not be compiled: $reason"
            }
            for (subtreeClass in subtree) {
                availableClasses.remove(subtreeClass)
                // The members go with the class: a call site must not resolve to a member of a
                // class that no longer exists in the module.
                subtreeClass.dotNetMemberFunctions().forEach(availableFunctions::remove)
                val subtreeReason = when {
                    subtreeClass === failedClass -> reason
                    subtreeClass === evictionRoot -> rootReason
                    else ->
                        "its enclosing class '${evictionRoot.diagnosticName()}' could not be compiled: $rootReason"
                }
                classSkipReasons.putIfAbsent(subtreeClass, subtreeReason)
            }
        }

        // Member pre-pass: the constructors, instance methods, and property accessors of every available class
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
        // clashes, evicting the whole owner subtree.
        for ([irClass, classInfo] in availableClasses.entries.toList()) {
            // Already evicted with the subtree of an earlier failure in this snapshot.
            if (irClass !in availableClasses) continue
            try {
                // CLR constructor identity is only the mapped parameter list. In particular,
                // reference nullability erases, so reject the class instead of letting one
                // source constructor or lowered default stub overwrite another in the maps.
                val constructorsByIlIdentity = hashMapOf<String, IrConstructor>()
                for (constructor in irClass.declarations.filterIsInstance<IrConstructor>()) {
                    val signature = constructor.dotNetSignature(typeMapper)
                    val ilIdentity = ".ctor(${signature.renderParameterTypes()})"
                    constructorsByIlIdentity.put(ilIdentity, constructor)?.let {
                        dotNetUnsupported(
                            "constructors of '${irClass.diagnosticName()}' clash: " +
                                    "both map to the same IL constructor '$ilIdentity'"
                        )
                    }
                }
                val membersByIlIdentity = hashMapOf<String, IrSimpleFunction>()
                for (member in irClass.dotNetMemberFunctions().sortedBy { it.isOriginallyLocalDeclaration }) {
                    val signature = member.dotNetSignature(typeMapper)
                    checkOverrideKeepsIlReturnType(member, signature, typeMapper)
                    // CLR method identity includes the generic ARITY (see
                    // dotNetIlGenericAritySuffix), for member methods as well as the facade gate
                    // below (genmemberprobe_s1).
                    val ilIdentity = member.reserveLocalFunctionIlIdentity(signature, membersByIlIdentity)
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
                evictClassSubtree(irClass, e.reason)
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
            facadeCallables.sortBy { it.isOriginallyLocalDeclaration }
            val callablesByIlIdentity = hashMapOf<String, IrSimpleFunction>()
            for (callable in facadeCallables) {
                // Already evicted as the partner of an earlier clash in this file.
                val functionInfo = availableFunctions[callable] ?: continue
                // The generic-arity marker keeps a generic `fun <T> f(x: Int)` distinct from a
                // plain `fun f(x: Int)` — CLR method identity includes the arity (the Roslyn
                // overload rule), so both are legal IL methods on one facade.
                val ilIdentity = callable.reserveLocalFunctionIlIdentity(functionInfo.signature, callablesByIlIdentity)
                val clashing = callablesByIlIdentity.putIfAbsent(ilIdentity, callable) ?: continue
                // A previous accessor clash may have failed the file's whole backing-property
                // group, removing accessors that were indexed earlier in this snapshot. Such a
                // declaration cannot participate in emitted IL anymore; replace the stale entry
                // with the current live callable instead of evicting it against a dead partner.
                if (clashing !in availableFunctions) {
                    callablesByIlIdentity[ilIdentity] = callable
                    continue
                }
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
                // Already evicted with the subtree of an earlier failure in this round.
                if (irClass !in availableClasses) continue
                // Every nested class renders recursively inside its enclosing class's metadata
                // block, never as a top-level declaration of its own.
                if (irClass.parent is IrClass) continue
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
                    // A nested failure surfaces from inside the top-level class's recursive
                    // render. The tag identifies the exact declaration that failed, matching
                    // the member pre-pass's per-class attribution.
                    val failedClass = (e as? DotNetIlUnsupportedClassException)?.irClass ?: irClass
                    evictClassSubtree(failedClass, e.reason)
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

        // Build exports only after the ordinary render fixpoint. A selected function is never
        // allowed to retain a stale signature or call a declaration evicted by a class/body
        // failure. Export failures are compilation errors because the user requested this
        // boundary explicitly; silently dropping the facade would be a false-success ABI.
        val renderedExports = LinkedHashMap<IrSimpleFunction, List<DotNetIlRenderedMethod>>()
        val exportedIlIdentities = hashSetOf<String>()
        var exportsUseNullableMetadata = false
        var exportFailed = false
        for ([target, export] in exportsByTarget) {
            val targetInfo = availableFunctions[target]
            if (targetInfo == null) {
                val reason = skipReasons[target] ?: "the selected function is not in the emitted callable surface"
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "Cannot export '${export.kotlinFqName}' as '${export.clrMethodName}': $reason."
                )
                exportFailed = true
                continue
            }
            try {
                val renderedExport = renderExport(target, export, targetInfo, typeMapper, availableFunctions)
                for (exportedMethod in renderedExport.methods) {
                    val exportedParameterTypes = exportedMethod.parameterTypes.joinToString(", ")
                    val collision = availableFunctions.entries.firstOrNull { [function, info] ->
                        info.owner.ilTypeRef == targetInfo.owner.ilTypeRef &&
                                function.typeParameters.isEmpty() &&
                                function.dotNetIlMethodName() == export.clrMethodName &&
                                info.signature.renderParameterTypes() == exportedParameterTypes
                    }
                    if (collision != null) {
                        dotNetUnsupported(
                            "CLR method '${export.clrMethodName}($exportedParameterTypes)' " +
                                    "already exists on facade ${targetInfo.owner.ilTypeRef}"
                        )
                    }
                    val exportIdentity = "${targetInfo.owner.ilTypeRef}::${export.clrMethodName}(" +
                            "$exportedParameterTypes)"
                    if (!exportedIlIdentities.add(exportIdentity)) {
                        dotNetUnsupported("another requested export maps to the same CLR method '$exportIdentity'")
                    }
                }
                renderedExports[target] = renderedExport.methods.map { it.method }
                exportsUseNullableMetadata = exportsUseNullableMetadata || renderedExport.usesNullableMetadata
            } catch (e: DotNetIlUnsupportedException) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "Cannot export '${export.kotlinFqName}' as '${export.clrMethodName}': ${e.reason}."
                )
                exportFailed = true
            }
        }
        if (exportFailed) return null
        if (exportsUseNullableMetadata && topLevelClassesByFile.values.flatten().any { irClass ->
                irClass.fqNameWhenAvailable?.asString() == DotNetNullableMetadata.ATTRIBUTE_FQ_NAME
            }
        ) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Cannot emit CLR exports: '${DotNetNullableMetadata.ATTRIBUTE_FQ_NAME}' is compiler-reserved."
            )
            return null
        }

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

        val moduleBody = buildString {
            for (file in files) {
                // Per file: user classes first, then the file facade (the deterministic order
                // the goldens freeze).
                for (irClass in topLevelClassesByFile.getValue(file)) {
                    renderedClasses[irClass]?.let { rendered -> append(rendered.ilText) }
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
                        is IrSimpleFunction -> {
                            renderedMethods[declaration]?.let { facadeMethods += it }
                            renderedExports[declaration]?.let { facadeMethods += it }
                        }
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
                DotNetIlClassCodegen(
                    fileClassNames.getValue(file),
                    facadeMethods.map { it.ilText },
                    facadeFields,
                    facadePropertyBlocks,
                    hasClassInitializer = renderedStaticInitializers.containsKey(file),
                ).generate(this)
            }
        }
        return buildString {
            // Executables retain the runtime-foundation AssemblyRef unconditionally. Raw library
            // IL acquires it once the final fixpoint output contains a physical runtime type;
            // derive that from the rendered body so an evicted callable cannot leave a stale ref.
            appendHeader(
                referencesRuntimeAssembly = producesExecutable ||
                        "[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" in moduleBody
            )
            if (exportsUseNullableMetadata) {
                append(DotNetNullableMetadata.attributeClassIl)
            }
            append(moduleBody)
        }
    }

    /**
     * The shape gate of the class model (JVM precedent: the runtime has real classes, so
     * there is no vtable/class lowering machinery and unsupported shapes are simply rejected):
     * top-level plain classes — non-generic or invariant reified-generic, `final`, `open`,
     * `abstract`, or `sealed` — and plain (always final) `object` declarations are compilable
     * (an `object` goes through
     * the same constraint chain as a class — [DotNetObjectClassLowering][org.jetbrains.kotlin.backend.dotnet.lower.DotNetObjectClassLowering]
     * already turned its singleton nature into ordinary class machinery). A plain class may
     * have EXACTLY ONE class supertype when it resolves to another recursively declared class of
     * [moduleClasses] (real CLR inheritance; whether that base itself compiles is
     * deliberately NOT checked here — the render re-resolves it every fixpoint round, so a
     * failing base cascades to its derived classes with a carried reason, see
     * [renderUserClass]) and any number of interface supertypes when each resolves to a
     * recursively declared interface of the module (real CLR interface types — see
     * [checkInterfaceShapeSupported] for the interface half of the gate; the `implements` list
     * is re-resolved live like the base). Interface slots satisfied through inherited members
     * must resolve to VIRTUAL members (`ifaceprobe_s5a`/`_s5b` — the non-virtual shape
     * load-poisons the type) whose mapped IL signature matches the slot exactly, return type
     * included (`ifaceprobe_s10`; the covariant half runs in the member pre-pass,
     * [checkInheritedInterfaceImplKeepsIlReturnType], because it needs the type mapper).
     * Interface delegation (`by`) uses FIR's ordinary forwarding members and private delegate
     * fields. Exception supertypes, out-of-module bases, and overrides of
     * `kotlin.Any` members stay rejected. Abstract and sealed plain classes map to CLR `abstract`;
     * Kotlin sealing is frontend-enforced like sealed interfaces. Objects and companions stay
     * on the sole-supertype-`kotlin.Any`, final-only model. A plain named class nested directly
     * in any supported named class, interface, object, or companion is also supported recursively
     * at every class modality, including under a generic outer and with its own independent
     * generic parameters. This
     * follows the JVM's static nested-class model (`ACC_STATIC` unless `isInner`) and maps directly
     * to a CLR nested type; the metadata and generic spelling is probe-verified by
     * `nestedprobe_s1`/`_s3`. Nested classes may be public, private, internal, or protected
     * (`nested public/private/assembly/family`).
     * A top-level or nested class may derive from a module-local nested class, including a
     * sibling, enclosing metadata parent, forward declaration, generic instantiation, or class
     * in another top-level family (`nestedprobe_s3`). Companion objects of non-generic ordinary
     * nested classes/interfaces and named objects in any supported metadata owner are supported
     * by the recursive static-initializer sweep (`nestedprobe_s4`, `nestedifaceprobe_s2`,
     * `nestedownerprobe_s1`). A
     * companion's singleton field lives on its immediate owner, so that owner must be non-generic;
     * a named object's `INSTANCE` lives on the non-generic object type itself and is safe below a
     * generic metadata parent. All-abstract interfaces may nest inside any supported named class,
     * interface, object, or companion, with the same independent generic parameter and visibility
     * model. Named classes and objects may likewise nest recursively in any supported class,
     * interface, object, or companion; all use the JVM static-nested model and the same recursive
     * metadata/initializer machinery (`nestedownerprobe_s1`–`_s2`). An inner class below a
     * non-generic outer is represented by the common/JVM explicit-outer model: a private
     * `this$0` field plus a leading constructor argument, with outer-`this` reads rewritten to
     * field chains (`innerprobe_s1`–`_s2`). Below a generic outer, explicit copied parameters make
     * that independent CLR space `Inner<own, outer...>` (`genericinner_s1`–`_s3`).
     * Each violation throws
     * [DotNetIlUnsupportedException]; registration drops that declaration's metadata subtree,
     * while its valid enclosing classes and siblings remain available.
     */
    private fun IrSimpleFunction.checkDotNetFunctionShapeSupported() {
        if (isOriginallyLocalDeclaration) {
            val functionName = name.asString()
            dotNetLocalCaptureRejectionReason?.let { reason ->
                dotNetUnsupported("local function '$functionName' $reason")
            }
            if (isSuspend) {
                dotNetUnsupported("local function '$functionName' is suspend; coroutine lowering is not available")
            }
            if (isInline) {
                dotNetUnsupported("local function '$functionName' is inline; inline lowering is not available")
            }
        }
        checkDotNetGenericFunctionSupported()
    }

    /**
     * Gives a lifted local function the user's metadata namespace without letting it evict a
     * source-declared method/accessor. Non-local callables are visited first; a colliding local
     * appends the smallest free `$n` suffix, and every call observes the same mutated IR name.
     */
    private fun IrSimpleFunction.reserveLocalFunctionIlIdentity(
        signature: DotNetIlMethodSignature,
        claimed: Map<String, IrSimpleFunction>,
    ): String {
        fun identity(): String =
            "${dotNetIlMethodName()}${dotNetIlGenericAritySuffix()}(${signature.renderParameterTypes()})"

        var ilIdentity = identity()
        if (!isOriginallyLocalDeclaration || ilIdentity !in claimed) return ilIdentity

        val baseName = name.asString()
        var suffix = 1
        do {
            name = Name.identifier("$baseName\$${suffix++}")
            ilIdentity = identity()
        } while (ilIdentity in claimed)
        return ilIdentity
    }

    private fun checkClassShapeSupported(
        irClass: IrClass,
        moduleClasses: Set<IrClass>,
        moduleInterfaces: Set<IrClass>,
    ) {
        val name = irClass.diagnosticName()
        val enclosingClass = irClass.parent as? IrClass
        val isValidatedCompanion =
            enclosingClass != null && irClass.isCompanion && irClass.kind == ClassKind.OBJECT
        val isNamedNestedClass = enclosingClass != null && irClass.kind == ClassKind.CLASS
        val isNamedNestedObject =
            enclosingClass != null && !irClass.isCompanion && irClass.kind == ClassKind.OBJECT
        when (irClass.kind) {
            ClassKind.INTERFACE -> {
                checkInterfaceShapeSupported(irClass, moduleInterfaces)
                return
            }
            ClassKind.ENUM_CLASS, ClassKind.ENUM_ENTRY -> dotNetUnsupported("enum class '$name' is not supported")
            ClassKind.ANNOTATION_CLASS -> dotNetUnsupported("annotation class '$name' is not supported")
            ClassKind.CLASS, ClassKind.OBJECT -> Unit
        }
        if (irClass.isOriginallyLocalDeclaration) {
            val localKind = when {
                irClass.isDotNetCallableObject -> "callable object"
                irClass.isAnonymousObject -> "anonymous object"
                else -> "local class"
            }
            if (irClass.dotNetInventedLocalClassName == null) {
                dotNetUnsupported("$localKind '$name' has no invented CLR metadata name")
            }
            irClass.dotNetLocalCaptureRejectionReason?.let { reason ->
                dotNetUnsupported("$localKind '$name' $reason")
            }
        } else if (irClass.isAnonymousObject) {
            dotNetUnsupported("anonymous object '$name' was not closure-converted")
        }
        if (enclosingClass == null && irClass.parent !is IrFile) {
            dotNetUnsupported("class '$name' is not top-level; nested/inner/local classes are not supported")
        }
        if (
            enclosingClass != null &&
            enclosingClass.kind != ClassKind.CLASS &&
            enclosingClass.kind != ClassKind.INTERFACE &&
            enclosingClass.kind != ClassKind.OBJECT
        ) {
            val kind = when {
                isValidatedCompanion -> "companion object"
                irClass.kind == ClassKind.OBJECT -> "object"
                else -> "class"
            }
            dotNetUnsupported(
                "$kind '$name' is nested inside unsupported declaration " +
                        "'${enclosingClass.diagnosticName()}'; nested declarations are supported only inside " +
                        "classes, interfaces, and objects"
            )
        }
        if (enclosingClass != null && !isValidatedCompanion && !isNamedNestedClass && !isNamedNestedObject) {
            val kind = if (irClass.kind == ClassKind.OBJECT) "object" else "class"
            dotNetUnsupported("nested $kind '$name' is not supported")
        }
        if (irClass.isData) {
            irClass.primaryConstructor
                ?: dotNetUnsupported("data class '$name' has no primary constructor")
        }
        if (irClass.isValue) dotNetUnsupported("value class '$name' is not supported")
        if (irClass.isExpect) dotNetUnsupported("expect class '$name' is not supported")
        // Modality maps directly onto CLR metadata like the JVM's class access flags: FINAL
        // keeps `sealed`, OPEN drops it (inheritprobe_s1), and ABSTRACT emits `abstract`
        // (abstractprobe_s1). A Kotlin SEALED class also emits ordinary CLR `abstract`: sealing
        // is frontend-enforced, the same JVM precedent as sealed interfaces. Singleton shapes
        // are final in Kotlin anyway; the OPEN branch is defensive.
        when (irClass.modality) {
            Modality.FINAL -> {}
            Modality.OPEN ->
                if (isValidatedCompanion || irClass.kind == ClassKind.OBJECT) {
                    dotNetUnsupported("non-final object '$name' is not supported")
                }
            Modality.ABSTRACT, Modality.SEALED -> {}
        }
        if (isNamedNestedClass || isNamedNestedObject) {
            when (irClass.visibility) {
                DescriptorVisibilities.PUBLIC,
                DescriptorVisibilities.PRIVATE,
                DescriptorVisibilities.INTERNAL,
                DescriptorVisibilities.PROTECTED,
                    -> {}
                else -> dotNetUnsupported(
                    "nested ${if (isNamedNestedObject) "object" else "class"} '$name' has " +
                            "unsupported visibility '${irClass.visibility}'"
                )
            }
        }
        // Generic plain classes use real CLR reified generics
        // (Roslyn shape — `.class ... 'C`n'<T>`, `!n` member signatures, instantiation tokens in
        // every operand position; probe series genprobe), no erasure or lowering machinery. The
        // gate scopes the model: invariant non-reified parameters, optionally constrained by
        // supported module-local classes/interfaces, and any supported generic/non-generic
        // class or interface supertype. A named nested class carries only its own parameters,
        // independently of a generic outer (`nestedprobe_s1`). A companion whose immediate
        // class/interface container is generic remains outside the model: its field lives on that
        // container and would be per constructed owner. A named object's `INSTANCE` field instead
        // lives on the non-generic object type itself, so it remains one singleton even when the
        // immediate metadata parent is generic (`nestedprobe_s4`, `nestedifaceprobe_s2`–`_s3`).
        // Generic base links retain their full open instantiation, so
        // constructor calls, owner lookup, and override substitution compose through arbitrary
        // module-local chains (probe-verified, geninheritprobe_s1).
        // Kotlin objects and companions cannot be generic; the branch is defensive.
        if (irClass.typeParameters.isNotEmpty()) {
            if (irClass.kind == ClassKind.OBJECT || isValidatedCompanion) {
                dotNetUnsupported("generic object '$name' is not supported")
            }
            checkDotNetTypeParametersSupported(irClass.typeParameters, "class '$name'")
            val nestedCompanion = irClass.declarations.filterIsInstance<IrClass>()
                .firstOrNull { it.kind == ClassKind.OBJECT && it.isCompanion }
            if (nestedCompanion != null) {
                dotNetUnsupported(
                    "generic class '$name' contains companion object '${nestedCompanion.name.asString()}'; " +
                            "a companion field on a generic CLR owner would be duplicated per constructed type"
                )
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
            // A class may implement any number of recursively declared module-local interfaces,
            // plus the supported Kotlin.Runtime callable interfaces, next to its (at most one)
            // base class; whether each module interface itself compiles is deliberately
            // NOT checked here — the render re-resolves the `implements` list every fixpoint
            // round, so an evicted interface cascades whole-class with a carried reason, exactly
            // like an evicted base class.
            for (superInterface in superClasses.filter { it.isInterface }) {
                if (superInterface !in moduleInterfaces &&
                    superInterface.dotNetFixedFunctionArityOrNull() == null &&
                    superInterface.dotNetFixedKFunctionArityOrNull() == null &&
                    superInterface.dotNetExactFunctionArity == null
                ) {
                    dotNetUnsupported(
                        "class '$name' implements '${superInterface.diagnosticName()}', which is not an " +
                                "interface of the compiled module or a supported Kotlin.Runtime callable interface"
                    )
                }
            }
            val properSuperClasses = superClasses.filterNot { it.isInterface }
            if (properSuperClasses.size > 1) {
                dotNetUnsupported("internal: class '$name' has more than one class supertype")
            }
            val superClass = properSuperClasses.singleOrNull()
            if (superClass != null && superClass !in moduleClasses) {
                dotNetUnsupported(
                    "class '$name' extends '${superClass.diagnosticName()}', which is not a class " +
                            "of the compiled module; only module-local base classes are supported"
                )
            }
        }
        // Kotlin Any is physically System.Object. dotNetIlMethodName maps declared overrides to
        // Equals/GetHashCode/ToString, and the ordinary override flags reuse those existing CLR
        // slots (no newslot). Data classes remain a separate feature gate below: accepting the
        // foundational slots does not imply every generated data-member body is supported.
        for (member in irClass.dotNetMemberFunctions()) {
            // A generic member method uses the same real CLR method parameter model as a
            // top-level generic function. On a generic owner, `!n` and `!!n` remain independent
            // positional spaces (probe-verified, genmemberprobe_s1). Any unsupported method
            // parameter shape rejects the whole owning class before registration.
            member.checkDotNetFunctionShapeSupported()
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
            if (implementation == null) {
                // An abstract class may carry an interface obligation only as a fake override:
                // no method is emitted on this owner, and a concrete subclass introduces the
                // implementing slot. Both CLR runtimes accept and dispatch this exact shape
                // (abstractprobe_s2). A concrete class still needs a real implementation.
                val abstractObligation = member.resolveFakeOverrideMaybeAbstract()
                if (
                    (irClass.modality == Modality.ABSTRACT || irClass.modality == Modality.SEALED) &&
                    abstractObligation?.modality == Modality.ABSTRACT
                ) {
                    continue
                }
                dotNetUnsupported(
                    "member '${member.name.asString()}' of class '$name' implements an interface member " +
                            "without any inherited implementation"
                )
            }
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
    }

    /**
     * The interface half of the shape gate (probe series `ifaceprobe_s1`–`_s10`; JVM precedent:
     * the CLR has real interface types, so like classes there is no vtable/interface lowering —
     * a Kotlin `interface` becomes a CLR `.class interface abstract`): a top-level or named
     * nested interface whose callable members are ALL abstract (abstract functions and abstract
     * `val`/`var` properties) is compilable, and it may extend any number of recursively declared
     * module-local interfaces (`ifaceprobe_s6`: interface inheritance is the same `implements`
     * list; whether
     * each super-interface itself compiles is re-resolved live every render round, so an evicted
     * interface cascades to its sub-interfaces). A `sealed interface` is deliberately ACCEPTED
     * and emitted as a plain interface. Like the sealed-class model, interface sealedness is
     * pure frontend-enforced metadata with no CLR counterpart needed (JVM precedent: the JVM
     * backend emits an ordinary interface too),
     * and the exhaustive `when` it enables is `is`-gated by the type-operator rejection anyway;
     * pinned by ilText/interfaceEqualityWidening.kt. A named nested interface may appear in any
     * supported named class, interface, object, or companion, owns only its own generic
     * parameters, and maps its visibility to `nested public/private/assembly/family`. Interfaces
     * may themselves contain static-style named classes, interfaces, objects, and companions.
     * Everything else stays rejected,
     * whole-interface: `fun interface` (no SAM-conversion model), local interfaces or interfaces
     * that do not have a supported named metadata parent,
     * out-of-module super-interfaces, members WITH bodies — default methods and accessors with
     * bodies — (modern CoreCLR supports Default Interface Methods, but Framework 4.8 ILAsm
     * rejects their bodies, `dimprobe_s1`), private interface members, overrides of
     * `kotlin.Any` members (the same
     * no-Any-model gap as on classes). A generic interface cannot own a companion because the CLR
     * static field would be duplicated per constructed owner; named objects own their own field
     * and remain safe.
     */
    private fun checkInterfaceShapeSupported(irClass: IrClass, moduleInterfaces: Set<IrClass>) {
        val name = irClass.diagnosticName()
        val enclosingClass = irClass.parent as? IrClass
        if (irClass.isFun) {
            dotNetUnsupported("fun interface '$name' is not supported (no SAM-conversion model)")
        }
        if (enclosingClass == null && irClass.parent !is IrFile) {
            dotNetUnsupported("interface '$name' is local or anonymous; only named interfaces are supported")
        }
        if (enclosingClass != null) {
            when (irClass.visibility) {
                DescriptorVisibilities.PUBLIC,
                DescriptorVisibilities.PRIVATE,
                DescriptorVisibilities.INTERNAL,
                DescriptorVisibilities.PROTECTED,
                    -> {}
                else -> dotNetUnsupported(
                    "nested interface '$name' has unsupported visibility '${irClass.visibility}'"
                )
            }
        }
        if (irClass.isExpect) dotNetUnsupported("expect interface '$name' is not supported")
        checkDotNetTypeParametersSupported(
            irClass.typeParameters,
            "interface '$name'",
            allowDeclarationSiteVariance = true,
        )
        if (irClass.typeParameters.isNotEmpty()) {
            val nestedCompanion = irClass.declarations.filterIsInstance<IrClass>()
                .firstOrNull { it.kind == ClassKind.OBJECT && it.isCompanion }
            if (nestedCompanion != null) {
                dotNetUnsupported(
                    "generic interface '$name' contains companion object '${nestedCompanion.name.asString()}'; " +
                            "a companion field on a generic CLR owner would be duplicated per constructed type"
                )
            }
        }
        for (superType in irClass.superTypes) {
            if (superType.isAny()) continue
            val superInterface = ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                ?: dotNetUnsupported("interface '$name' has an unsupported supertype")
            if (!superInterface.isInterface || superInterface !in moduleInterfaces) {
                dotNetUnsupported(
                    "interface '$name' extends '${superInterface.diagnosticName()}', which is not an interface " +
                            "of the compiled module; only module-local super-interfaces are supported"
                )
            }
        }
        for (declaration in irClass.declarations) {
            when (declaration) {
                // The recursive registration pass validates nested declarations independently.
                is IrClass -> {}
                is IrSimpleFunction ->
                    if (declaration.origin != DOTNET_STATIC_INITIALIZER) {
                        checkInterfaceMemberSupported(
                            declaration, name, "member '${declaration.name.asString()}'"
                        )
                    }
                is IrField ->
                    if (declaration.origin != IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE) {
                        dotNetUnsupported(
                            "unsupported field '${declaration.name.asString()}' of interface '$name'"
                        )
                    }
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
        // Abstract generic interface slots are ordinary CLR generic virtual methods. The same
        // gate as class/top-level methods owns reified, inline, and unsupported constraint shapes
        // (probe-verified together with a generic-class implementation, genmemberprobe_s1).
        member.checkDotNetFunctionShapeSupported()
        if (member.visibility == DescriptorVisibilities.PRIVATE) {
            dotNetUnsupported("private $description of interface '$interfaceName' is not supported")
        }
        if (member.body != null || member.modality != Modality.ABSTRACT) {
            dotNetUnsupported(
                "$description of interface '$interfaceName' has a body; interface members with bodies are not yet " +
                        "supported (Default Interface Methods require modern CoreCLR and are rejected by the " +
                        ".NET Framework 4.8 ILAsm compatibility floor)"
            )
        }
        if (member.allOverridden().any { (it.parent as? IrClass)?.defaultType?.isAny() == true }) {
            dotNetUnsupported(
                "$description of interface '$interfaceName' overrides a member of kotlin.Any; " +
                        "interface redeclarations of kotlin.Any members are not supported yet"
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
                    val classArguments = memberClass.dotNetTypeArgumentsFor(overriddenClass, typeMapper) ?: continue
                    overriddenReturnType.substituteDotNetTypeParameters(
                        classArguments,
                        overridden.dotNetOpenMethodTypeArguments(),
                    )
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
     * The IL type arguments [this] class supplies for the generic [target] through either its
     * base chain or its interface DAG. The emitter's already-linked structural supertype graph
     * performs every open-parameter substitution, so `IntBox : Box<Int>` yields `[int32]`,
     * `class C<A, B> : PairView<B, A>` yields `[!1, !0]`, and transitive generic-interface
     * inheritance composes without a second IR-level substitution implementation. Null means
     * another gate owns an unavailable/broken edge, so callers skip rather than double-report.
     */
    private fun IrClass.dotNetTypeArgumentsFor(
        target: IrClass,
        typeMapper: DotNetIlTypeMapper,
    ): List<DotNetIlValueType>? {
        val receiverType = typeMapper.toDotNetIlValueType(defaultType) ?: return null
        val targetInfo = typeMapper.classInfoOrNull(target) ?: return null
        return receiverType.dotNetViewAsGenericOwner(targetInfo)?.arguments
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
        val memberClass = member.parent as? IrClass
        for (overridden in overriddenInterfaceMembers) {
            val overriddenReturnType = typeMapper.toDotNetIlReturnType(overridden.returnType) ?: continue
            val interfaceClass = overridden.parent as? IrClass
            val substitutedReturnType =
                if (memberClass != null && interfaceClass != null && interfaceClass.typeParameters.isNotEmpty()) {
                    val interfaceArguments = memberClass.dotNetTypeArgumentsFor(interfaceClass, typeMapper) ?: continue
                    overriddenReturnType.substituteDotNetTypeParameters(
                        interfaceArguments,
                        overridden.dotNetOpenMethodTypeArguments(),
                    )
                } else overriddenReturnType
            if (substitutedReturnType != memberReturnType) {
                val interfaceName = interfaceClass?.diagnosticName() ?: "?"
                dotNetUnsupported(
                    "member '${member.name.asString()}' implements interface member " +
                            "'$interfaceName.${overridden.name.asString()}' through an inherited member with a " +
                            "different IL return type (${memberReturnType.nameInSignature} vs " +
                            "${substitutedReturnType.nameInSignature}); ECMA-335 interface mapping matches the " +
                            "full signature including the return type, so the interface slot would have no " +
                            "implementation and every use of the class would throw TypeLoadException " +
                            "(probe ifaceprobe_s10)"
                )
            }
        }
    }

    /**
     * Positional identity instantiation for a generic method's own parameters. Override checks
     * substitute the declaring OWNER's `!n` view while the compared method signature must keep
     * its independent `!!n` leaves open (`genmemberprobe_s1`).
     */
    private fun IrSimpleFunction.dotNetOpenMethodTypeArguments(): List<DotNetIlValueType> =
        typeParameters.indices.map { index ->
            DotNetIlValueType.TypeParameter(index, isMethodParameter = true)
        }

    /**
     * Renders one user class, either a top-level root or one of its recursively rendered metadata
     * children: backing fields (state, `private` per the JVM-facade
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
     * A class of the INHERITANCE model renders `extends <base>` instead of `System.Object`; an
     * `open` class drops `sealed` (`inheritprobe_s1`), while abstract/sealed Kotlin classes carry
     * CLR `abstract` (`abstractprobe_s1`). Its open/abstract members and overrides carry virtual
     * flags (see [DotNetIlMethodCodegen]'s `dotNetVirtualFlags`). The base is re-resolved from
     * the live class map at the top of every render, so an evicted base cascades down the chain.
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
        // counterpart of the nested class-subtree warnings.
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
                // An instantiated generic base: closed (`Box<int32>`, genprobe_s5) or open and
                // composed (`Mid<!1, !0>`, geninheritprobe_s1). Re-mapped through the LIVE type
                // mapper every render round like the base itself, so an instantiation mentioning
                // an evicted class fails the derived class here with a carried reason (the
                // type-argument arm of the base-eviction cascade).
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
        val interfaceTypes = irClass.dotNetDirectInterfaceTypes().map { superInterfaceType ->
            val superInterface = (superInterfaceType.classifier as IrClassSymbol).owner
            typeMapper.classInfoOrNull(superInterface) ?: dotNetUnsupported(
                "its ${if (irClass.isInterface) "extended" else "implemented"} interface " +
                        "'${superInterface.diagnosticName()}' could not be compiled: " +
                        (classSkipReasons[superInterface] ?: "the interface is not available in this module")
            )
            typeMapper.toDotNetIlValueType(superInterfaceType)
                ?: dotNetUnsupported(
                    "its ${if (irClass.isInterface) "extended" else "implemented"} interface " +
                            "instantiation '${superInterfaceType.render()}' could not be compiled: " +
                            "a type argument is not available in this module"
                )
        }
        val renderedNestedClasses = mutableListOf<String>()
        val renderedFields = mutableListOf<String>()
        val renderedMethods = mutableListOf<String>()
        val renderedProperties = mutableListOf<String>()
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
                }
                is IrAnonymousInitializer ->
                    dotNetUnsupported("internal: init block of class '$name' survived InitializersLowering")
                is IrClass -> {
                    // Every still-available nested declaration renders recursively as a real CLR
                    // nested `.class` block inside this class's body. A missing entry is expected
                    // after its own gate/render failure: omitting that child block does not make
                    // an otherwise independent metadata parent invalid.
                    val nestedClassInfo = typeMapper.classInfoOrNull(declaration) ?: continue
                    val rendered = try {
                        renderUserClass(
                            classInfo = nestedClassInfo,
                            irClass = declaration,
                            availableFunctions = availableFunctions,
                            intrinsicMethods = intrinsicMethods,
                            typeMapper = typeMapper,
                            facadeClassInfoByFile = facadeClassInfoByFile,
                            classSkipReasons = classSkipReasons,
                        )
                    } catch (e: DotNetIlUnsupportedException) {
                        // Attribute the failure to the nested declaration itself: this is its
                        // only render, so subtree eviction diagnostics must not blame the
                        // enclosing parent for a descendant's failure. Preserve a deeper tag while
                        // unwinding multi-level nesting instead of replacing it at each parent.
                        if (e is DotNetIlUnsupportedClassException) throw e
                        throw DotNetIlUnsupportedClassException(declaration, e.reason)
                    }
                    renderedNestedClasses += rendered.ilText.trimEnd('\n').prependIndent("  ") + "\n"
                }
                is IrField -> {
                    // JVM precedent: FIR's interface-delegation field is an ordinary private
                    // instance field whose initializer is merged into the constructor; its
                    // DELEGATED_MEMBER forwarding methods render through the normal function
                    // path below. Singleton fields keep their distinct public-static shape.
                    when (declaration.origin) {
                        IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE ->
                            renderedFields += renderObjectInstanceField(declaration, typeMapper)
                        IrDeclarationOrigin.DELEGATE,
                        IrDeclarationOrigin.FIELD_FOR_OUTER_THIS,
                        LocalDeclarationsLowering.DECLARATION_ORIGIN_FIELD_FOR_CAPTURED_VALUE,
                            -> {
                            if (declaration.isStatic) {
                                val fieldKind =
                                    when (declaration.origin) {
                                        IrDeclarationOrigin.FIELD_FOR_OUTER_THIS -> "outer-instance"
                                        LocalDeclarationsLowering.DECLARATION_ORIGIN_FIELD_FOR_CAPTURED_VALUE -> "captured-value"
                                        else -> "interface-delegate"
                                    }
                                dotNetUnsupported(
                                    "internal: $fieldKind field '${declaration.name.asString()}' " +
                                            "in class '$name' is unexpectedly static"
                                )
                            }
                            renderedFields += renderField(declaration, typeMapper)
                        }
                        else -> dotNetUnsupported(
                            "internal: unexpected propertyless field '${declaration.name.asString()}' in class '$name'"
                        )
                    }
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
                nestedVisibility = irClass.dotNetNestedTypeVisibility(),
                exported = !irClass.isOriginallyLocalDeclaration,
                renderedNestedClasses = renderedNestedClasses,
                // An open class drops `sealed` (the CLR metadata form of Kotlin's modality, like
                // the JVM's ACC_FINAL); companions and objects never reach here as open — the
                // shape gate keeps them final-only.
                isOpen = irClass.modality == Modality.OPEN,
                // Kotlin abstract and sealed classes are non-instantiable. Sealing remains a
                // frontend restriction, like the sealed-interface model (abstractprobe_s1).
                isAbstract = irClass.modality == Modality.ABSTRACT || irClass.modality == Modality.SEALED,
                baseClassRef = baseClassRef,
                isInterface = irClass.isInterface,
                interfaceRefs = interfaceTypes.map { interfaceType ->
                    when (interfaceType) {
                        is DotNetIlValueType.UserClass -> interfaceType.ilTypeRef
                        is DotNetIlValueType.GenericInstance -> interfaceType.nameInSignature
                        else -> error("Internal .NET backend error: non-class interface type $interfaceType")
                    }
                },
                // The formal type-parameter list of a generic class/interface: `<'T'>`, the
                // stage-2 constrained `<(class 'Base', class 'Mark') 'T'>`, or the interface
                // variance form `<+ 'T'>` / `<- 'T'>` (genprobe_s8, genconstraintprobe_s1,
                // genifaceprobe_s1).
                genericParameters = irClass.typeParameters.renderDotNetIlGenericParameters(typeMapper),
            ).generate(this)
        }
        return RenderedClass(ilText)
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
     * The accessibility flag of an ordinary named CLR nested class, interface, or object
     * (`nestedprobe_s1`, `nestedprobe_s4`, `nestedifaceprobe_s1`–`_s3`). Companions keep
     * the established public metadata shape; Kotlin controls their source visibility through
     * the enclosing declaration and the singleton field/accessors. The shape gate has already
     * rejected every ordinary named nested-type visibility outside these four source forms.
     */
    private fun IrClass.dotNetNestedTypeVisibility(): String = when {
        parent !is IrClass || isCompanion -> "public"
        isOriginallyLocalDeclaration -> "private"
        visibility == DescriptorVisibilities.PUBLIC -> "public"
        visibility == DescriptorVisibilities.PRIVATE -> "private"
        visibility == DescriptorVisibilities.INTERNAL -> "assembly"
        visibility == DescriptorVisibilities.PROTECTED -> "family"
        else -> error("Internal .NET backend error: unsupported nested visibility '$visibility'")
    }

    /**
     * One `.field` line: the instance backing field of a member property, FIR's loose private
     * interface-delegate field, the common inner-class lowering's private outer-instance field,
     * a local class's immutable captured-value field, or, with [isStatic], the static backing
     * field of a top-level property on its file facade.
     * These fields are always `private` (the JVM `final` analogue `initonly` is deliberately
     * omitted — a pure metadata nicety with no semantic need, and a `var`'s `stsfld` from the
     * static setter must stay legal); the delegation shape is ilasm-probe-verified by
     * `delegationprobe_s1`, the outer-instance shape by `innerprobe_s1`–`_s2`, the local capture
     * shape by `localprobe_s1`–`_s2`, and both backing-field spellings by `statprobe_s1`/`_s2`
     * (including a user-class-typed static field).
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
            is DotNetIlValueType.PrimitiveArray, is DotNetIlValueType.GenericArray,
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
     * companion's type clashes, whole-owner-subtree) — FIR's loose interface-delegate field,
     * the inner-class lowering's outer-instance field, local-class captured-value fields, plus
     * the backing fields of the declared properties, including `const val`s, whose `literal`
     * fields share the class's field namespace like any other field.
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
     * singleton and outer-instance fields are named as such — the user never declared them —
     * while a backing field is attributed to its property.
     */
    private fun IrField.dotNetFieldDescription(): String = when {
        origin == IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE -> "the synthesized '${name.asString()}' singleton field"
        origin == IrDeclarationOrigin.FIELD_FOR_OUTER_THIS -> "the synthesized outer-instance field"
        origin == LocalDeclarationsLowering.DECLARATION_ORIGIN_FIELD_FOR_CAPTURED_VALUE ->
            "the synthesized captured-value field '${name.asString()}'"
        correspondingPropertySymbol != null -> "the backing field of property '${name.asString()}'"
        else -> "field '${name.asString()}'"
    }

    /** A successfully rendered user class and its complete IL text. */
    private class RenderedClass(val ilText: String)

    /**
     * A [DotNetIlUnsupportedException] tagged with the nested class whose recursive render
     * actually failed. Without the tag, a descendant failure would surface at the top-level
     * render-fixpoint catch as a failure of the enclosing root and invert the subtree diagnostics.
     * An existing tag is preserved while unwinding arbitrary nesting depth. The shape gate and
     * member pre-pass need no tag because they iterate every registered class independently.
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

    /**
     * Renders one explicit CLR function export while leaving the Kotlin method unchanged.
     * Ordinary parameters and returns retain their mapped CLR shapes. Supported Function0/1/2
     * parameters become typed Func/Action parameters and are adapted back to the canonical erased
     * FunctionN before the Kotlin method is called; callable returns project in the other direction.
     */
    private fun renderExport(
        target: IrSimpleFunction,
        export: DotNetExport,
        targetInfo: DotNetIlFunctionInfo,
        typeMapper: DotNetIlTypeMapper,
        availableFunctions: Map<IrSimpleFunction, DotNetIlFunctionInfo>,
    ): DotNetRenderedExport {
        if (target.visibility != DescriptorVisibilities.PUBLIC) {
            dotNetUnsupported("the selected function is not public")
        }
        if (target.typeParameters.isNotEmpty()) {
            dotNetUnsupported("generic functions are not supported by this export slice")
        }
        if (target.isSuspend) dotNetUnsupported("suspend functions are outside this CLR export slice")
        val parameterBoundaries = target.parameters.map { parameter ->
            parameter.type.delegateBoundaryOrNull(typeMapper, "parameter '${parameter.name.asString()}'")
        }
        val returnBoundary = target.returnType.delegateBoundaryOrNull(typeMapper, "return type")
        val exportedParameterTypes = targetInfo.signature.parameterTypes.mapIndexed { index, type ->
            parameterBoundaries[index]?.delegateBoundary?.closedDelegateType ?: type.nameInSignature
        }
        val parameterNullabilityFlags = target.parameters.indices.map { index ->
            parameterBoundaries[index]?.nullabilityFlags
                ?: DotNetNullableMetadata.flags(
                    target.parameters[index].type,
                    targetInfo.signature.parameterTypes[index],
                )
        }
        val parameters = target.parameters.indices.joinToString(", ") { index ->
            "${exportedParameterTypes[index]} ${target.parameters[index].name.asString().toIlIdentifier()}"
        }
        val exportedReturnType = returnBoundary?.delegateBoundary?.closedDelegateType
            ?: targetInfo.signature.returnType.nameInSignature
        val returnNullabilityFlags = returnBoundary?.nullabilityFlags
            ?: when (val returnType = targetInfo.signature.returnType) {
                DotNetIlReturnType.Void -> emptyList()
                is DotNetIlReturnType.Value -> DotNetNullableMetadata.flags(target.returnType, returnType.type)
            }
        val usesNullableMetadata = returnNullabilityFlags.isNotEmpty() ||
                parameterNullabilityFlags.any { it.isNotEmpty() }
        val renderedMethods = mutableListOf<DotNetRenderedExportMethod>()
        renderedMethods += DotNetRenderedExportMethod(
            method = DotNetIlRenderedMethod(buildString {
                appendLine(
                    "  .method public hidebysig static $exportedReturnType " +
                            "${export.clrMethodName.toIlIdentifier()}($parameters) cil managed"
                )
                appendLine("  {")
                appendNullableAttribute(parameterIndex = 0, flags = returnNullabilityFlags)
                parameterNullabilityFlags.forEachIndexed { index, flags ->
                    appendNullableAttribute(parameterIndex = index + 1, flags = flags)
                }
                appendLine("    .maxstack ${maxOf(1, target.parameters.size)}")
                target.parameters.indices.forEach { index ->
                    appendLine("    ldarg $index")
                    parameterBoundaries[index]?.let { boundary ->
                        appendLine("    ${boundary.delegateBoundary.adaptationCallInstruction}")
                    }
                }
                appendLine("    ${targetInfo.renderCallInstruction(target.dotNetIlMethodName())}")
                returnBoundary?.let { boundary ->
                    appendLine("    ${boundary.delegateBoundary.projectionCallInstruction}")
                }
                appendLine("    ret")
                appendLine("  }")
            }),
            parameterTypes = exportedParameterTypes,
        )

        val defaultParameterIndices = target.dotNetDefaultParameterIndices.orEmpty().toSet()
        val trailingOverloadStarts = buildList {
            var firstOmitted = target.parameters.size
            while (firstOmitted > 0 && firstOmitted - 1 in defaultParameterIndices) {
                firstOmitted--
                add(firstOmitted)
            }
        }
        if (trailingOverloadStarts.isEmpty()) {
            return DotNetRenderedExport(renderedMethods, usesNullableMetadata)
        }

        val defaultStub = target.defaultArgumentsDispatchFunction as? IrSimpleFunction
            ?: dotNetUnsupported("default-argument export has no generated default dispatcher")
        val defaultStubInfo = availableFunctions[defaultStub]
            ?: dotNetUnsupported("the generated default dispatcher is not in the emitted callable surface")
        val maskParameters = defaultStub.parameters.drop(target.parameters.size)
        if (
            maskParameters.isEmpty() ||
            maskParameters.any { it.origin != IrDeclarationOrigin.MASK_FOR_DEFAULT_FUNCTION }
        ) {
            dotNetUnsupported("the generated default dispatcher has an unsupported mask/handler shape")
        }
        val defaultMaskBitByParameterIndex = target.parameters.indices
            .filter { index -> target.parameters[index].participatesInDefaultArgumentMask() }
            .withIndex()
            .associate { indexedParameter -> indexedParameter.value to indexedParameter.index }

        for (firstOmitted in trailingOverloadStarts) {
            val retainedParameterTypes = exportedParameterTypes.take(firstOmitted)
            val retainedParameters = target.parameters.indices.take(firstOmitted).joinToString(", ") { index ->
                "${retainedParameterTypes[index]} ${target.parameters[index].name.asString().toIlIdentifier()}"
            }
            val defaultMasks = IntArray(maskParameters.size)
            for (parameterIndex in firstOmitted until target.parameters.size) {
                val defaultBitIndex = defaultMaskBitByParameterIndex[parameterIndex]
                    ?: error("Internal .NET backend error: default parameter has no dispatcher mask bit")
                val maskIndex = defaultBitIndex / 32
                defaultMasks[maskIndex] = defaultMasks[maskIndex] or (1 shl (defaultBitIndex % 32))
            }
            val defaultValueLocals = (firstOmitted until target.parameters.size)
                .filter { index -> targetInfo.signature.parameterTypes[index] is DotNetIlValueType.NullableValue }
                .withIndex()
                .associate { indexedParameter -> indexedParameter.value to indexedParameter.index }
            renderedMethods += DotNetRenderedExportMethod(
                method = DotNetIlRenderedMethod(buildString {
                    appendLine(
                        "  .method public hidebysig static $exportedReturnType " +
                                "${export.clrMethodName.toIlIdentifier()}($retainedParameters) cil managed"
                    )
                    appendLine("  {")
                    appendNullableAttribute(parameterIndex = 0, flags = returnNullabilityFlags)
                    parameterNullabilityFlags.take(firstOmitted).forEachIndexed { index, flags ->
                        appendNullableAttribute(parameterIndex = index + 1, flags = flags)
                    }
                    if (defaultValueLocals.isNotEmpty()) {
                        appendLine("    .locals init (")
                        defaultValueLocals.entries.forEachIndexed { entryIndex, entry ->
                            val parameterIndex = entry.key
                            val localIndex = entry.value
                            val comma = if (entryIndex == defaultValueLocals.size - 1) "" else ","
                            val localType = targetInfo.signature.parameterTypes[parameterIndex].nameInSignature
                            appendLine("      [$localIndex] $localType '<default$localIndex>'$comma")
                        }
                        appendLine("    )")
                    }
                    appendLine("    .maxstack ${maxOf(1, defaultStub.parameters.size)}")
                    repeat(firstOmitted) { index ->
                        appendLine("    ldarg $index")
                        parameterBoundaries[index]?.let { boundary ->
                            appendLine("    ${boundary.delegateBoundary.adaptationCallInstruction}")
                        }
                    }
                    for (parameterIndex in firstOmitted until target.parameters.size) {
                        appendDefaultArgumentPlaceholder(
                            targetInfo.signature.parameterTypes[parameterIndex],
                            defaultValueLocals[parameterIndex],
                        )
                    }
                    defaultMasks.forEach { mask -> appendLine("    ldc.i4 $mask") }
                    appendLine("    ${defaultStubInfo.renderCallInstruction(defaultStub.dotNetIlMethodName())}")
                    returnBoundary?.let { boundary ->
                        appendLine("    ${boundary.delegateBoundary.projectionCallInstruction}")
                    }
                    appendLine("    ret")
                    appendLine("  }")
                }),
                parameterTypes = retainedParameterTypes,
            )
        }
        return DotNetRenderedExport(renderedMethods, usesNullableMetadata)
    }

    private fun IrType.delegateBoundaryOrNull(
        typeMapper: DotNetIlTypeMapper,
        position: String,
    ): DotNetExportedCallableBoundary? {
        val mappedCallableType = DotNetRuntimeTypes.mapCallableType(this)
        if (mappedCallableType == null && !isSuspendFunction() && !isKFunction()) return null
        if (isSuspendFunction()) {
            dotNetUnsupported("suspend callable $position is outside the CLR delegate boundary")
        }
        if (isKFunction()) {
            dotNetUnsupported("KFunction $position needs an explicit FunctionN execution view")
        }
        val callableType = this as? IrSimpleType
            ?: dotNetUnsupported("$position is not a supported Function0/1/2")
        if (!callableType.isFunction()) {
            dotNetUnsupported("callable marker $position has no fixed CLR delegate shape")
        }
        val logicalTypes = callableType.arguments.map { argument ->
            (argument as? IrTypeProjection)?.type
                ?: dotNetUnsupported("star-projected callable $position cannot be exported")
        }
        val arity = logicalTypes.size - 1
        if (arity !in 0..2) {
            dotNetUnsupported("callable arity $arity in $position is outside the supported Function0/1/2 boundary")
        }
        val callableParameterTypes = logicalTypes.dropLast(1).mapIndexed { index, type ->
            typeMapper.toDotNetIlValueType(type)
                ?: dotNetUnsupported(
                    "callable argument type ${index + 1} '${type.render()}' in $position cannot be mapped to CLR"
                )
        }
        val logicalResultType = logicalTypes.last()
        val callableResultType = if (logicalResultType.isUnit()) {
            null
        } else {
            typeMapper.toDotNetIlValueType(logicalResultType)
                ?: dotNetUnsupported(
                    "callable result type '${logicalResultType.render()}' in $position cannot be mapped to CLR"
                )
        }
        val physicalLogicalTypes = callableParameterTypes + listOfNotNull(callableResultType)
        val metadataLogicalTypes = logicalTypes.dropLast(1) +
                if (callableResultType == null) emptyList() else listOf(logicalResultType)
        return DotNetExportedCallableBoundary(
            delegateBoundary = DotNetRuntimeTypes.delegateBoundary(
                callableParameterTypes,
                callableResultType,
                nullable = callableType.isMarkedNullable(),
            ),
            nullabilityFlags = DotNetNullableMetadata.delegateFlags(
                callableType,
                metadataLogicalTypes,
                physicalLogicalTypes,
            ),
        )
    }

    private fun StringBuilder.appendNullableAttribute(parameterIndex: Int, flags: List<Int>) {
        if (flags.isEmpty()) return
        appendLine("    .param [$parameterIndex]")
        appendLine("    ${DotNetNullableMetadata.renderAttribute(flags)}")
    }

    private fun StringBuilder.appendDefaultArgumentPlaceholder(
        type: DotNetIlValueType,
        initializedLocalIndex: Int?,
    ) {
        val instruction = when (type) {
            DotNetIlValueType.Boolean,
            DotNetIlValueType.Int32,
            DotNetIlValueType.Char,
                -> "ldc.i4 0"

            DotNetIlValueType.Int64 -> "ldc.i8 0"
            DotNetIlValueType.Float64 -> "ldc.r8 0.0"
            is DotNetIlValueType.NullableValue -> {
                checkNotNull(initializedLocalIndex) { "nullable default placeholder needs an initialized local" }
                "ldloc $initializedLocalIndex"
            }
            is DotNetIlValueType.TypeParameter -> dotNetUnsupported(
                "open generic default placeholders are outside the non-generic CLR export boundary"
            )
            DotNetIlValueType.String,
            DotNetIlValueType.Object,
            is DotNetIlValueType.UserClass,
            is DotNetIlValueType.MappedClass,
            is DotNetIlValueType.GenericInstance,
            is DotNetIlValueType.PrimitiveArray,
            is DotNetIlValueType.GenericArray,
                -> "ldnull"
        }
        appendLine("    $instruction")
    }

    /** Mirrors the common masked-default factory's private parameter-bit participation rule. */
    private fun IrValueParameter.participatesInDefaultArgumentMask(): Boolean =
        kind != IrParameterKind.DispatchReceiver &&
                kind != IrParameterKind.ExtensionReceiver &&
                origin != IrDeclarationOrigin.MOVED_DISPATCH_RECEIVER &&
                origin != IrDeclarationOrigin.MOVED_EXTENSION_RECEIVER

    private data class DotNetRenderedExport(
        val methods: List<DotNetRenderedExportMethod>,
        val usesNullableMetadata: Boolean,
    )

    private data class DotNetRenderedExportMethod(
        val method: DotNetIlRenderedMethod,
        val parameterTypes: List<String>,
    )

    private data class DotNetExportedCallableBoundary(
        val delegateBoundary: DotNetDelegateBoundary,
        val nullabilityFlags: List<Int>,
    )

    private fun StringBuilder.appendHeader(referencesRuntimeAssembly: Boolean) {
        appendLine(".assembly extern $CORE_LIB {}")
        if (referencesRuntimeAssembly) {
            appendLine(".assembly extern ${DotNetRuntimeLibrary.ASSEMBLY_NAME}")
            appendLine("{")
            appendLine("  .ver ${DotNetRuntimeLibrary.ASSEMBLY_VERSION_IL}")
            appendLine("}")
        }
        appendLine(".assembly ${assemblyName.toIlIdentifier()} {}")
        appendLine(".module ${moduleFileName.toIlIdentifier()}")
        appendLine()
    }
}
