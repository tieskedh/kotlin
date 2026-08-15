package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.config.MessageCollectorAccess
import org.jetbrains.kotlin.config.dotNetAssemblyName
import org.jetbrains.kotlin.config.dotNetOutput
import org.jetbrains.kotlin.config.dotNetProducesLibrary
import org.jetbrains.kotlin.config.dotNetProducesStdlib
import org.jetbrains.kotlin.config.dotNetTarget
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.config.perfManager
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.load.dotnet.DotNetExactContractProjection
import org.jetbrains.kotlin.util.PhaseType
import org.jetbrains.kotlin.util.tryMeasurePhaseTime
import java.io.File

object DotNetBackend {
    fun compile(
        irModuleFragment: IrModuleFragment,
        irBuiltIns: IrBuiltIns,
        symbolTable: SymbolTable,
        configuration: CompilerConfiguration,
        kotlinMetadataResourceFactory: ((Map<String, DotNetPhysicalDeclaration>) -> ByteArray)? = null,
        exactContractProjections: Map<IrSimpleFunction, DotNetExactContractProjection> = emptyMap(),
    ): DotNetBackendOutput {
        // The .NET backend has no IrDiagnosticReporter-based reporting yet; it deliberately talks
        // to the message collector directly, like DotNetIlEmitter and DotNetIlAssembler.
        @OptIn(MessageCollectorAccess::class)
        val messageCollector = configuration.messageCollector
        val output = configuration.dotNetOutput ?: error("Missing .NET output")
        val target = configuration.dotNetTarget
        val assemblyName = configuration.dotNetAssemblyName ?: output.nameWithoutExtension
        val producesStdlib = configuration.dotNetProducesStdlib
        val producesLibrary = configuration.dotNetProducesLibrary
        val producedLibraryArtifact = configuration.dotNetProducedLibraryArtifact
        val externalLibraries = configuration.dotNetExternalLibraries
        val publishedEmissionScope = if (producesStdlib) DotNetIlEmissionScope.STDLIB else DotNetIlEmissionScope.USER
        for (entry in exactContractProjections.entries) {
            entry.key.installDotNetExactContractProjection(entry.value)
        }
        val hasBootstrapStdlib = DotNetStdlibLibrary.hasImplementation(irModuleFragment)
        fun collectPreLoweringDeclarationKeys(scope: DotNetIlEmissionScope): Map<org.jetbrains.kotlin.ir.declarations.IrDeclaration, String> {
            val preLoweringIntrinsics = DotNetIlIntrinsicMethods(irBuiltIns, scope)
            return collectDotNetMetadataLinkageKeys(
                irModuleFragment,
                scope,
            ) { function ->
                preLoweringIntrinsics.getIntrinsic(function.symbol)?.excludesDeclarationFromCodegen == true ||
                        (function.isInline && function.typeParameters.any { typeParameter -> typeParameter.isReified })
            }
        }
        val preLoweringDeclarationKeys = if (producesStdlib || producesLibrary) {
            collectPreLoweringDeclarationKeys(publishedEmissionScope)
        } else {
            emptyMap()
        }
        // A bootstrap stdlib and the following user assembly form two physical producers even
        // during one compiler invocation. Capture the stdlib's logical keys before lowering so
        // its completed physical index can bind the user emission exactly like an installed DLL.
        val preLoweringStdlibDeclarationKeys = when {
            !hasBootstrapStdlib -> emptyMap()
            producesStdlib -> preLoweringDeclarationKeys
            else -> collectPreLoweringDeclarationKeys(DotNetIlEmissionScope.STDLIB)
        }
        val expectedMetadataLinkageKeys = preLoweringDeclarationKeys.values.toSet()
        var genericOwnerPrototypes: List<DotNetGenericOwnerPrototypeSnapshot> = emptyList()
        var genericOwnerCallRoutes: List<DotNetGenericOwnerCallRouteSnapshot> = emptyList()
        fun result(file: File, declarations: Map<String, DotNetPhysicalDeclaration> = emptyMap()) =
            DotNetBackendOutput(file, declarations, genericOwnerPrototypes, genericOwnerCallRoutes)
        fun validateMetadataLinkage(declarations: Map<String, DotNetPhysicalDeclaration>): Boolean {
            val missing = expectedMetadataLinkageKeys - declarations.keys
            if (missing.isEmpty()) return true
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "The .NET physical declaration index does not cover the pre-lowering Kotlin metadata ABI: " +
                        missing.sorted().joinToString(),
            )
            return false
        }
        val emitsExecutable = producedLibraryArtifact == null && !output.isDirectory && when (target) {
            DotNetTarget.NET48 -> output.extension.equals("exe", ignoreCase = true)
            DotNetTarget.NETSTANDARD_2_0 -> false
            // Modern .NET has no directly runnable ilasm .exe story on this pipeline: the runnable
            // artifact is a .dll launched by the signed `dotnet` host, so both spellings of an
            // executable request produce one.
            DotNetTarget.NET10_0 -> output.extension.equals("exe", ignoreCase = true) || output.extension.equals("dll", ignoreCase = true)
        }
        val binaryOutput = if (emitsExecutable && target == DotNetTarget.NET10_0 && output.extension.equals("exe", ignoreCase = true)) {
            // An .exe was requested but the net10.0 profile produces host-launched .dll files.
            // Renaming silently would leave the user looking for a file that never appears, so the
            // actual artifact is reported explicitly.
            output.siblingWithExtension("dll").also {
                messageCollector.report(
                    CompilerMessageSeverity.INFO,
                    "The 'net10.0' target produces a .dll started via 'dotnet exec' instead of a standalone .exe; writing '${it.path}'."
                )
            }
        } else {
            output
        }
        val ilTarget = when {
            producedLibraryArtifact != null -> output.resolve(producedLibraryArtifact.assemblyIlFileName)
            output.isDirectory -> output.resolve("$assemblyName.il")
            emitsExecutable -> binaryOutput.siblingWithExtension("il")
            else -> output
        }
        ilTarget.parentFile?.mkdirs()

        val reservedAssembly = if (producesStdlib) null else listOf(
            DotNetRuntimeLibrary.ASSEMBLY_NAME to DotNetRuntimeLibrary.ASSEMBLY_FILE_NAME,
            DotNetStdlibLibrary.ASSEMBLY_NAME to DotNetStdlibLibrary.ASSEMBLY_FILE_NAME,
        ).firstOrNull { reserved ->
            DotNetPlatformAssemblyIdentity.canonicalNameOrNull(assemblyName) == reserved.first ||
                    (emitsExecutable && binaryOutput.name.equals(reserved.second, ignoreCase = true))
        }
        if (reservedAssembly != null) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "'${reservedAssembly.first}' is reserved for a Kotlin/.NET platform assembly; " +
                        "choose a different module name and output file."
            )
            if (emitsExecutable) binaryOutput.delete()
            ilTarget.delete()
            return result(if (emitsExecutable) binaryOutput else ilTarget)
        }
        val collidingExternalLibrary = externalLibraries.firstOrNull {
            it.artifact.assemblyName.equals(assemblyName, ignoreCase = true)
        }
        if (collidingExternalLibrary != null) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Output assembly '$assemblyName' collides with external Kotlin/.NET library " +
                        "'${collidingExternalLibrary.assemblyFile.path}'."
            )
            if (emitsExecutable) binaryOutput.delete()
            ilTarget.delete()
            return result(if (emitsExecutable) binaryOutput else ilTarget)
        }
        if (emitsExecutable) {
            // Clear module-specific artifacts before lowering. A lowering/emission/runtime/ILAsm
            // failure must never leave a previous successful program looking current. The shared
            // runtime is intentionally not removed: another valid program in the directory may
            // still depend on it.
            binaryOutput.delete()
            if (target == DotNetTarget.NET10_0) binaryOutput.runtimeConfigFile().delete()
        } else if (producedLibraryArtifact != null) {
            output.resolve(producedLibraryArtifact.assemblyFileName).delete()
            ilTarget.delete()
        }

        val context = DotNetBackendContext(
            irBuiltIns,
            configuration,
            symbolTable,
            irModuleFragment,
            preLoweringDeclarationKeys,
        )
        val runtimeCSharpImplementationManifest =
            collectDotNetRuntimeCSharpImplementationManifest(context, target)
        try {
            configuration.perfManager.tryMeasurePhaseTime(PhaseType.IrLowering) {
                DotNetLoweringPhases.lower(irModuleFragment, context)
            }
        } catch (e: DotNetIlUnsupportedException) {
            // A lowering rejected the module up front (e.g. the local-class guard of
            // DotNetInitializersLowering). Unlike codegen-time rejections there is no function
            // granularity to skip at, so the whole compilation fails with one loud diagnostic
            // instead of an internal assertion crash further down.
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "The module is not supported by the .NET backend: ${e.reason}"
            )
            ilTarget.delete()
            return result(ilTarget)
        }
        genericOwnerPrototypes = context.genericOwnerArchitecturePlans.values
            .map { plan -> plan.toPrototypeSnapshot(preLoweringDeclarationKeys) }
            .sortedBy(DotNetGenericOwnerPrototypeSnapshot::ownerName)
        genericOwnerCallRoutes = context.genericOwnerCallRoutes
            .map(DotNetGenericOwnerCallRoutePlan::toCallRouteSnapshot)
            .sortedBy(DotNetGenericOwnerCallRouteSnapshot::callSiteIndex)

        return configuration.perfManager.tryMeasurePhaseTime(PhaseType.Backend) {
            val stdlibEmission = if (hasBootstrapStdlib) {
                DotNetIlEmitter(
                    messageCollector = messageCollector,
                    assemblyName = DotNetStdlibLibrary.ASSEMBLY_NAME,
                    moduleFileName = DotNetStdlibLibrary.ASSEMBLY_FILE_NAME,
                    producesExecutable = false,
                    irBuiltIns = irBuiltIns,
                    functionAdapterSymbols = context.functionAdapterSymbols,
                    propertyReferenceFactoryFunctions = context.propertyReferenceSymbols.implementedFactories(),
                    memberReferenceFactoryFunctions = context.memberReferenceSymbols.implementedFunctions(),
                    callableAnnotationFactoryFunctions = context.callableAnnotationSymbols.implementedFunctions(),
                    emissionScope = DotNetIlEmissionScope.STDLIB,
                    coreLibrary = target.coreLibrary,
                    failOnDeclarationEviction = true,
                    preLoweringDeclarationKeys = preLoweringStdlibDeclarationKeys,
                    interfaceDefaultImplementations = context.interfaceDefaultImplementations,
                    defaultArgumentDispatchers = context.defaultArgumentDispatchers,
                    covariantReturnBridges = context.covariantReturnBridges,
                    staticInitializations = context.staticInitializations,
                    staticInitializationFailures = context.staticInitializationFailures,
                    objectInstanceFields = context.objectInstanceFields,
                    enumEntryFields = context.enumEntryFields,
                    valueClassBoxingHelpers = context.valueClassBoxingHelpers,
                    cSharpImplementationManifestTarget = target,
                    hasKotlinMetadataResource = producesStdlib && kotlinMetadataResourceFactory != null,
                ).emit(irModuleFragment) ?: return result(ilTarget)
            } else {
                null
            }
            val stdlibIlText = stdlibEmission?.ilText

            if (producesStdlib) {
                if (stdlibIlText == null) {
                    messageCollector.report(
                        CompilerMessageSeverity.ERROR,
                        "The explicit stdlib build did not contain compiler-owned stdlib implementations."
                    )
                    return result(output.resolve(DotNetStdlibLibrary.ASSEMBLY_FILE_NAME))
                }
                if (!validateMetadataLinkage(stdlibEmission.declarations)) {
                    return result(output.resolve(DotNetStdlibLibrary.ASSEMBLY_FILE_NAME))
                }
                val stdlibOutput = output.resolve(DotNetStdlibLibrary.ASSEMBLY_FILE_NAME)
                val runtimeOutput = output.resolve(DotNetRuntimeLibrary.ASSEMBLY_FILE_NAME)
                runtimeOutput.delete()
                val assembledStdlib = DotNetStdlibLibrary.assembleIn(
                    output,
                    stdlibIlText,
                    target,
                    messageCollector,
                    stdlibEmission.managedResources.withKotlinMetadata(
                        stdlibEmission.declarations,
                        kotlinMetadataResourceFactory,
                    ),
                )
                if (assembledStdlib == null) return result(stdlibOutput)
                if (
                    DotNetRuntimeLibrary.assembleNextTo(
                        assembledStdlib,
                        target,
                        runtimeCSharpImplementationManifest,
                        messageCollector,
                    ) == null
                ) {
                    assembledStdlib.delete()
                    return result(stdlibOutput)
                }
                return result(assembledStdlib, stdlibEmission.declarations)
            }

            // Treat a stdlib emitted from injected bootstrap sources exactly like a separately
            // loaded producer for the following user-assembly emission. Its completed declaration
            // index is authoritative for erased member names, owner paths, and initialization ABI;
            // recomputing those facts from the already-lowered IR can diverge from the physical DLL.
            val userExternalLibraries = if (stdlibEmission != null) {
                externalLibraries.filterNot { library ->
                    DotNetPlatformAssemblyIdentity.isStdlib(library.artifact.assemblyName)
                } + DotNetExternalLibrary(
                    artifact = DotNetLibraryArtifact(
                        DotNetStdlibLibrary.ASSEMBLY_NAME,
                        target.description,
                        DotNetStdlibLibrary.ASSEMBLY_VERSION,
                    ),
                    assemblyFile = output.resolve(DotNetStdlibLibrary.ASSEMBLY_FILE_NAME),
                    declarations = stdlibEmission.declarations,
                    friendAssemblies = emptySet(),
                )
            } else {
                externalLibraries
            }

            val emitter = DotNetIlEmitter(
                messageCollector = messageCollector,
                assemblyName = assemblyName,
                moduleFileName = when {
                    emitsExecutable -> binaryOutput.name
                    producesLibrary -> checkNotNull(producedLibraryArtifact).assemblyFileName
                    else -> ilTarget.name
                },
                producesExecutable = emitsExecutable,
                irBuiltIns = irBuiltIns,
                functionAdapterSymbols = context.functionAdapterSymbols,
                propertyReferenceFactoryFunctions = context.propertyReferenceSymbols.implementedFactories(),
                memberReferenceFactoryFunctions = context.memberReferenceSymbols.implementedFunctions(),
                callableAnnotationFactoryFunctions = context.callableAnnotationSymbols.implementedFunctions(),
                exports = configuration.dotNetExports,
                propertyExports = configuration.dotNetPropertyExports,
                coreLibrary = target.coreLibrary,
                assemblyVersionIl = if (producesLibrary) checkNotNull(producedLibraryArtifact).assemblyVersionIl else null,
                externalLibraries = userExternalLibraries,
                failOnDeclarationEviction = producesLibrary,
                compilesAgainstStdlib = (producesLibrary || emitsExecutable) &&
                        (stdlibEmission != null || configuration.dotNetExternalStdlib != null),
                preLoweringDeclarationKeys = preLoweringDeclarationKeys,
                friendAssemblies = configuration.dotNetFriendAssemblies,
                interfaceDefaultImplementations = context.interfaceDefaultImplementations,
                defaultArgumentDispatchers = context.defaultArgumentDispatchers,
                externalInterfaceDefaultHelpers = context.externalInterfaceDefaultHelpers,
                externalDefaultArgumentDispatchers = context.externalDefaultArgumentDispatchers,
                staticInitializations = context.staticInitializations,
                staticInitializationFailures = context.staticInitializationFailures,
                objectInstanceFields = context.objectInstanceFields,
                enumEntryFields = context.enumEntryFields,
                valueClassBoxingHelpers = context.valueClassBoxingHelpers,
                externalStaticInitializations = context.externalStaticInitializations,
                interfaceDefaultPromotions = context.interfaceDefaultPromotions,
                genericInterfaceViewBridges = context.genericInterfaceViewBridges,
                covariantReturnBridges = context.covariantReturnBridges,
                interfaceDefaultClassForwarders = context.interfaceDefaultClassForwarders,
                cSharpImplementationManifestTarget = target.takeIf { producesLibrary },
                hasKotlinMetadataResource = producesLibrary && kotlinMetadataResourceFactory != null,
                genericOwnerCallRouteTraceHooks = configuration.dotNetGenericOwnerCallRouteTraceHooks,
                genericOwnerCallRouteTraceSiteCount = context.genericOwnerCallRoutes.size.takeIf {
                    configuration.dotNetGenericOwnerCallRouteTraceHooks != null
                },
            )
            val emission = emitter.emit(irModuleFragment)
            if (emission == null) {
                // Emission failed (the error is in the message collector). Remove any stale output of
                // a previous successful compilation so callers never see outdated content.
                ilTarget.delete()
                return result(ilTarget)
            }
            if (producesLibrary && !validateMetadataLinkage(emission.declarations)) {
                ilTarget.delete()
                return result(ilTarget)
            }
            val ilText = emission.ilText
            fun referencesAssembly(name: String): Boolean =
                emission.referencedAssemblies.any { referenced -> referenced.equals(name, ignoreCase = true) }

            if (referencesAssembly(DotNetStdlibLibrary.ASSEMBLY_NAME) && stdlibIlText == null) {
                val externalStdlib = configuration.dotNetExternalStdlib
                if (externalStdlib == null) {
                    messageCollector.report(
                        CompilerMessageSeverity.ERROR,
                        "The generated module requires '${DotNetStdlibLibrary.ASSEMBLY_NAME}', but neither " +
                                "injected implementation source nor a self-describing CLR DLL was supplied. " +
                                "Compile without -no-stdlib or add the target stdlib DLL to the classpath."
                    )
                    ilTarget.delete()
                    return result(ilTarget)
                }
                if (!externalStdlib.assemblyFile.isFile) {
                    messageCollector.report(
                        CompilerMessageSeverity.ERROR,
                        "The Kotlin/.NET standard-library assembly '${externalStdlib.assemblyFile.path}' is missing."
                    )
                    ilTarget.delete()
                    return result(ilTarget)
                }
                if (emitsExecutable) {
                    val packagedStdlib = (binaryOutput.parentFile ?: File("."))
                        .resolve(DotNetStdlibLibrary.ASSEMBLY_FILE_NAME)
                    if (externalStdlib.assemblyFile.canonicalFile != packagedStdlib.canonicalFile) {
                        externalStdlib.assemblyFile.copyTo(packagedStdlib, overwrite = true)
                    }
                }
            }
            for (library in userExternalLibraries) {
                // Kotlin.Stdlib has a dedicated installation/packaging path above. It still belongs
                // to externalLibraries for ordinary declaration binding, but must not be copied or
                // validated a second time as an arbitrary user library here.
                if (DotNetPlatformAssemblyIdentity.isStdlib(library.artifact.assemblyName)) continue
                if (!referencesAssembly(library.artifact.assemblyName)) continue
                if (!library.assemblyFile.isFile) {
                    messageCollector.report(
                        CompilerMessageSeverity.ERROR,
                        "The Kotlin/.NET library assembly '${library.assemblyFile.path}' is missing."
                    )
                    ilTarget.delete()
                    return result(ilTarget)
                }
                if (emitsExecutable) {
                    val packagedLibrary = (binaryOutput.parentFile ?: File("."))
                        .resolve(library.artifact.assemblyFileName)
                    if (library.assemblyFile.canonicalFile != packagedLibrary.canonicalFile) {
                        library.assemblyFile.copyTo(packagedLibrary, overwrite = true)
                    }
                }
            }
            for (foreignAssembly in emission.referencedForeignAssemblies) {
                val identity = foreignAssembly.metadata.identity
                if (identity.name.equals(assemblyName, ignoreCase = true)) {
                    messageCollector.report(
                        CompilerMessageSeverity.ERROR,
                        "Output assembly '$assemblyName' collides with referenced foreign CLR assembly " +
                                "'${foreignAssembly.assemblyFile.path}'."
                    )
                    ilTarget.delete()
                    return result(if (emitsExecutable) binaryOutput else ilTarget)
                }
                if (!foreignAssembly.assemblyFile.isFile) {
                    messageCollector.report(
                        CompilerMessageSeverity.ERROR,
                        "The referenced foreign CLR assembly '${foreignAssembly.assemblyFile.path}' is missing."
                    )
                    ilTarget.delete()
                    return result(if (emitsExecutable) binaryOutput else ilTarget)
                }
                if (emitsExecutable) {
                    val dependencyFileName = "${identity.name}.dll"
                    if (
                        identity.name.isEmpty() ||
                        File(dependencyFileName).name != dependencyFileName ||
                        dependencyFileName.any { character -> character in "<>:\"/\\|?*" }
                    ) {
                        messageCollector.report(
                            CompilerMessageSeverity.ERROR,
                            "Foreign CLR assembly name '${identity.name}' cannot be packaged as a safe dependency file."
                        )
                        ilTarget.delete()
                        return result(binaryOutput)
                    }
                    val packagedAssembly = (binaryOutput.parentFile ?: File(".")).resolve(dependencyFileName)
                    if (foreignAssembly.assemblyFile.canonicalFile != packagedAssembly.canonicalFile) {
                        foreignAssembly.assemblyFile.copyTo(packagedAssembly, overwrite = true)
                    }
                }
            }
            // ilasm decodes a BOM-less file as ANSI, mangling every multi-byte UTF-8 sequence (e.g. in
            // string literals), so the .il file must be written as UTF-8 *with* a BOM.
            ilTarget.writeBytes(UTF8_BOM + ilText.toByteArray(Charsets.UTF_8))

            if (producesLibrary) {
                val assemblyOutput = output.resolve(checkNotNull(producedLibraryArtifact).assemblyFileName)
                DotNetIlAssembler.assembleLibrary(
                    ilTarget,
                    assemblyOutput,
                    target,
                    messageCollector,
                    emission.managedResources.withKotlinMetadata(
                        emission.declarations,
                        kotlinMetadataResourceFactory,
                    ),
                )
                return result(assemblyOutput, emission.declarations)
            }

            if (emitsExecutable) {
                val externalRuntime = configuration.dotNetExternalStdlib?.runtimeAssemblyFile
                if (externalRuntime == null) {
                    if (
                        DotNetRuntimeLibrary.assembleNextTo(
                            binaryOutput,
                            target,
                            runtimeCSharpImplementationManifest,
                            messageCollector,
                        ) == null
                    ) {
                        return result(binaryOutput)
                    }
                } else {
                    if (!externalRuntime.isFile) {
                        messageCollector.report(
                            CompilerMessageSeverity.ERROR,
                            "The Kotlin/.NET runtime assembly '${externalRuntime.path}' is missing.",
                        )
                        return result(binaryOutput)
                    }
                    val packagedRuntime = (binaryOutput.parentFile ?: File("."))
                        .resolve(DotNetRuntimeLibrary.ASSEMBLY_FILE_NAME)
                    if (externalRuntime.canonicalFile != packagedRuntime.canonicalFile) {
                        externalRuntime.copyTo(packagedRuntime, overwrite = true)
                    }
                }
                if (stdlibIlText != null &&
                    DotNetStdlibLibrary.assembleNextTo(
                        binaryOutput,
                        stdlibIlText,
                        target,
                        messageCollector,
                        checkNotNull(stdlibEmission).managedResources,
                    ) == null
                ) {
                    return result(binaryOutput)
                }
                DotNetIlAssembler.assembleExecutable(
                    ilTarget,
                    binaryOutput,
                    target,
                    messageCollector,
                    emission.managedResources,
                )
                return result(binaryOutput)
            }

            result(ilTarget)
        }
    }

    private fun File.siblingWithExtension(extension: String): File {
        return (parentFile ?: File(".")).resolve("$nameWithoutExtension.$extension")
    }

    private fun File.runtimeConfigFile(): File =
        (parentFile ?: File(".")).resolve("$nameWithoutExtension.runtimeconfig.json")

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private fun Map<String, ByteArray>.withKotlinMetadata(
        declarations: Map<String, DotNetPhysicalDeclaration>,
        resourceFactory: ((Map<String, DotNetPhysicalDeclaration>) -> ByteArray)?,
    ): Map<String, ByteArray> {
        if (resourceFactory == null) return this
        check(DotNetKotlinMetadataResource.MANAGED_RESOURCE_NAME !in this) {
            "The Kotlin metadata resource name collides with another managed resource"
        }
        val metadataResource =
            DotNetKotlinMetadataResource.MANAGED_RESOURCE_NAME to resourceFactory(declarations)
        return this + metadataResource
    }
}

data class DotNetBackendOutput(
    val file: File,
    val declarations: Map<String, DotNetPhysicalDeclaration>,
    val genericOwnerPrototypes: List<DotNetGenericOwnerPrototypeSnapshot>,
    val genericOwnerCallRoutes: List<DotNetGenericOwnerCallRouteSnapshot>,
)
