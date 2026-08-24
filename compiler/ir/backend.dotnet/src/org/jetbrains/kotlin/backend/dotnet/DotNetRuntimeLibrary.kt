package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.builtins.functions.BuiltInFunctionArity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.DotNetTarget
import java.io.File

/**
 * The first physical Kotlin/.NET runtime boundary.
 *
 * The assembly boundary was established before its first public ABI candidate types. It now owns
 * the physically erased Function0..22 interfaces, the big-arity FunctionN capability, the
 * orthogonal KCallable/KFunction
 * reflection view, erased KProperty0/1/2 identities, the split Iterator/ListIterator/Iterable/
 * Collection/List execution interfaces,
 * the abstract subclass arm and classified operation boundary for Common `Number`,
 * the singleton Unit value required when a callable result crosses the object-shaped invocation
 * boundary, and Kotlin-owned exception identities that have no faithful BCL type. Compiler
 * support shared by generated modules, including the constructor-default ABI marker, optional
 * ExactFunctionN and TypedArgumentsFunctionN execution capabilities, the structural function-
 * reference implementation base, property-reference wrappers, and explicit-export delegate
 * projection, lives below the reserved `Kotlin.Runtime.Internal` namespace. Ordinary Kotlin
 * library implementations do not live here; the first such implementation is the generic array
 * iterator emitted into `Kotlin.Stdlib.dll`.
 * One .NET Standard 2.0 library profile is assembled with modern ILAsm, independently of the
 * executable target. Framework ILAsm remains a source-compatibility check, not the canonical
 * portable-library writer, because it injects an `mscorlib` AssemblyRef into its output PE.
 */
internal object DotNetRuntimeLibrary {
    const val ASSEMBLY_NAME = DotNetPlatformAssemblyIdentity.RUNTIME_ASSEMBLY_NAME
    const val ASSEMBLY_FILE_NAME = "$ASSEMBLY_NAME.dll"
    const val ASSEMBLY_VERSION_IL = "1:0:0:0"

    val noWhenBranchMatchedExceptionTypeRef: String =
        "[$ASSEMBLY_NAME]${"Kotlin.NoWhenBranchMatchedException".toIlIdentifier()}"

    val runtimeExceptionTypeRef: String =
        "[$ASSEMBLY_NAME]${"Kotlin.RuntimeException".toIlIdentifier()}"

    val noSuchElementExceptionTypeRef: String =
        "[$ASSEMBLY_NAME]${"Kotlin.NoSuchElementException".toIlIdentifier()}"

    val negativeArraySizeExceptionTypeRef: String =
        "[$ASSEMBLY_NAME]${"Kotlin.NegativeArraySizeException".toIlIdentifier()}"

    val numberFormatExceptionTypeRef: String =
        "[$ASSEMBLY_NAME]${"Kotlin.NumberFormatException".toIlIdentifier()}"

    val errorTypeRef: String =
        "[$ASSEMBLY_NAME]${"Kotlin.Error".toIlIdentifier()}"

    val exceptionInInitializerErrorTypeRef: String =
        "[$ASSEMBLY_NAME]${"Kotlin.ExceptionInInitializerError".toIlIdentifier()}"

    val noClassDefFoundErrorTypeRef: String =
        "[$ASSEMBLY_NAME]${"Kotlin.NoClassDefFoundError".toIlIdentifier()}"

    val kotlinNothingValueExceptionTypeRef: String =
        "[$ASSEMBLY_NAME]${"Kotlin.KotlinNothingValueException".toIlIdentifier()}"

    val concurrentModificationExceptionTypeRef: String =
        "[$ASSEMBLY_NAME]${"Kotlin.ConcurrentModificationException".toIlIdentifier()}"

    val assertionErrorTypeRef: String =
        "[$ASSEMBLY_NAME]${"Kotlin.AssertionError".toIlIdentifier()}"

    val uninitializedPropertyAccessExceptionTypeRef: String =
        "[$ASSEMBLY_NAME]${"Kotlin.UninitializedPropertyAccessException".toIlIdentifier()}"

    val exceptionClassifierTypeRef: String =
        "[$ASSEMBLY_NAME]${"Kotlin.Runtime.Internal.ExceptionClassifier".toIlIdentifier()}"

    fun exceptionClassifierCallInstruction(coreLibraryReference: String): String =
        "call bool $exceptionClassifierTypeRef::'IsKotlinExceptionInstance'(" +
                "class ${coreLibraryReference}System.Exception, int32)"

    fun assembleNextTo(
        executableOutput: File,
        target: DotNetTarget,
        cSharpImplementationManifest: DotNetCSharpImplementationManifest,
        messageCollector: MessageCollector,
    ): File? = assembleRuntime(
        executableOutput,
        target,
        cSharpImplementationManifest,
    ) { ilFile, output, managedResources ->
        DotNetIlAssembler.assembleLibrary(
            ilFile,
            output,
            target,
            messageCollector,
            managedResources,
        )
    }

    /**
     * Low-level runtime representation fixture. Production runtime artifacts always receive the
     * built-in-derived C# implementation manifest through [assembleNextTo].
     */
    @TestOnly
    fun assembleWithoutManifestForTests(
        outputDirectory: File,
        target: DotNetTarget,
        messageCollector: MessageCollector,
    ): File? = assembleRuntime(
        outputDirectory.resolve("runtime-conformance-placeholder"),
        target,
        cSharpImplementationManifest = null,
    ) { ilFile, output, managedResources ->
        DotNetIlAssembler.assembleLibrary(
            ilFile,
            output,
            target,
            messageCollector,
            managedResources,
        )
    }

    @TestOnly
    fun assembleWithManifestForTests(
        outputDirectory: File,
        target: DotNetTarget,
        cSharpImplementationManifest: DotNetCSharpImplementationManifest,
        messageCollector: MessageCollector,
        runtimeSurfaceMetadataValues: List<String>,
    ): File? = assembleRuntime(
        outputDirectory.resolve("runtime-manifest-conformance-placeholder"),
        target,
        cSharpImplementationManifest,
        runtimeSurfaceMetadataValues,
    ) { ilFile, output, managedResources ->
        DotNetIlAssembler.assembleLibrary(
            ilFile,
            output,
            target,
            messageCollector,
            managedResources,
        )
    }

    @TestOnly
    fun assembleWithExplicitIlasmForTests(
        outputDirectory: File,
        target: DotNetTarget,
        ilasm: File,
        messageCollector: MessageCollector,
    ): File? = assembleRuntime(
        outputDirectory.resolve("runtime-explicit-writer-placeholder"),
        target,
        cSharpImplementationManifest = null,
    ) { ilFile, output, managedResources ->
        DotNetIlAssembler.assembleWithExplicitIlasm(
            ilasm,
            ilFile,
            output,
            dll = true,
            messageCollector = messageCollector,
            managedResources = managedResources,
        )
    }

    private fun assembleRuntime(
        outputAnchor: File,
        target: DotNetTarget,
        cSharpImplementationManifest: DotNetCSharpImplementationManifest?,
        runtimeSurfaceMetadataValues: List<String> = listOf(
            DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL.toString()
        ),
        assemble: (ilFile: File, output: File, managedResources: Map<String, ByteArray>) -> Boolean,
    ): File? {
        val outputDirectory = outputAnchor.parentFile ?: File(".")
        outputDirectory.mkdirs()
        val output = outputDirectory.resolve(ASSEMBLY_FILE_NAME)
        output.delete()
        val ilFile = File.createTempFile("Kotlin.Runtime-", ".il", outputDirectory)
        val managedResources = cSharpImplementationManifest?.let { manifest ->
            require(manifest.assemblyName == ASSEMBLY_NAME) {
                "Kotlin.Runtime C# implementation manifest names '${manifest.assemblyName}'"
            }
            require(manifest.targetProfile == target.description) {
                "Kotlin.Runtime C# implementation manifest targets '${manifest.targetProfile}', " +
                        "not '${target.description}'"
            }
            require(manifest.logicalIdentityScheme == DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME)
            mapOf(
                DotNetCSharpImplementationManifestCodec.MANAGED_RESOURCE_NAME to
                        DotNetCSharpImplementationManifestCodec.encodeManagedResource(manifest)
            )
        }.orEmpty()
        return try {
            // ILAsm decodes BOM-less input as ANSI; keep the runtime source on the same UTF-8+BOM
            // path as generated program IL even though its current text is ASCII-only.
            ilFile.writeBytes(
                UTF8_BOM + ilText(
                    target,
                    hasCSharpImplementationManifest = managedResources.isNotEmpty(),
                    runtimeSurfaceMetadataValues,
                ).toByteArray(Charsets.UTF_8)
            )
            output.takeIf { assemble(ilFile, output, managedResources) }
        } finally {
            ilFile.delete()
        }
    }

    private fun ilText(
        target: DotNetTarget,
        hasCSharpImplementationManifest: Boolean,
        runtimeSurfaceMetadataValues: List<String>,
    ): String {
        val coreLibrary = target.coreLibrary
        val coreLibraryReference = coreLibrary.reference
        val assemblyReferenceIl = buildString {
            coreLibrary.appendAssemblyReferenceTo(this)
            coreLibrary.appendEditorBrowsableAssemblyReferenceTo(this)
        }.trimEnd().prependIndent("        ")
        val targetFrameworkAttributeIl = buildString {
            coreLibrary.appendTargetFrameworkAttributeTo(this)
        }.trimEnd().prependIndent("        ")
        val runtimeSurfaceAttributeIl = buildString {
            runtimeSurfaceMetadataValues.forEach { value ->
                coreLibrary.appendAssemblyMetadataAttributeTo(
                    this,
                    DotNetLibraryAbiCodec.RUNTIME_SURFACE_METADATA_KEY,
                    value,
                )
            }
        }.trimEnd().prependIndent("        ")
        val compilerAbiAttributeTypeIl =
            DotNetCompilerAbi.attributeTypeIl(
                coreLibraryReference,
                coreLibrary.editorBrowsableReference,
            ).prependIndent("        ")
        val compilerAbiUseAttributesIl = listOf(
            DotNetCompilerAbi.markerAttributeIl(runtimeAssemblyReference = ""),
            DotNetCompilerAbi.editorBrowsableNeverAttributeIl(coreLibrary.editorBrowsableReference),
        ).joinToString("\n            ")
        val primitiveArrayTypesIl = DotNetPrimitiveArrays.runtimeTypesIl(
            coreLibraryReference,
            coreLibrary.editorBrowsableReference,
        )
        val kClassTypesIl = DotNetKClassRuntime.kotlinTypesIl(coreLibraryReference)
        val throwableExceptionTypesIl = DotNetThrowableRuntime.exceptionTypesIl(coreLibraryReference)
        val fixedFunctionTypesIl = fixedFunctionTypesIl()
        return """
$assemblyReferenceIl
        .assembly Kotlin.Runtime
        {
          .ver $ASSEMBLY_VERSION_IL
$targetFrameworkAttributeIl
$runtimeSurfaceAttributeIl
        }
        .module Kotlin.Runtime.dll
${if (hasCSharpImplementationManifest) """
        .mresource public ${DotNetCSharpImplementationManifestCodec.MANAGED_RESOURCE_NAME}
        {
        }
""".trimEnd() else ""}

$compilerAbiAttributeTypeIl

        .namespace Kotlin.Runtime
        {
          .class public abstract sealed auto ansi beforefieldinit RuntimeInfo
                 extends ${coreLibraryReference}System.Object
          {
          }
        }

        .namespace Kotlin
        {
$primitiveArrayTypesIl

$kClassTypesIl

          .class public auto ansi beforefieldinit RuntimeException
                 extends ${coreLibraryReference}System.Exception
          {
            .field private string '_message'

            .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 2
              ldarg.0
              call instance void ${coreLibraryReference}System.Exception::.ctor()
              ldarg.0
              ldnull
              stfld string Kotlin.RuntimeException::'_message'
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(string 'message') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              call instance void ${coreLibraryReference}System.Exception::.ctor(string)
              ldarg.0
              ldarg.1
              stfld string Kotlin.RuntimeException::'_message'
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(string 'message', class ${coreLibraryReference}System.Exception 'cause') cil managed
            {
              .maxstack 3
              ldarg.0
              ldarg.1
              ldarg.2
              call instance void ${coreLibraryReference}System.Exception::.ctor(string, class ${coreLibraryReference}System.Exception)
              ldarg.0
              ldarg.1
              stfld string Kotlin.RuntimeException::'_message'
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(class ${coreLibraryReference}System.Exception 'cause') cil managed
            {
              .maxstack 3
              .locals init ([0] string 'message')
              ldarg.1
              brtrue.s IL_causeNotNull
              ldnull
              br.s IL_messageReady
        IL_causeNotNull:
              ldarg.1
              callvirt instance string ${coreLibraryReference}System.Object::ToString()
        IL_messageReady:
              stloc.0
              ldarg.0
              ldloc.0
              ldarg.1
              call instance void ${coreLibraryReference}System.Exception::.ctor(string, class ${coreLibraryReference}System.Exception)
              ldarg.0
              ldloc.0
              stfld string Kotlin.RuntimeException::'_message'
              ret
            }

            .method public hidebysig specialname virtual instance string 'get_Message'() cil managed
            {
              .maxstack 1
              ldarg.0
              ldfld string Kotlin.RuntimeException::'_message'
              ret
            }

            .property instance string Message()
            {
              .get instance string Kotlin.RuntimeException::'get_Message'()
            }
          }

          // Physical bottom-type carrier. It is a reference type so it can close CLR generics,
          // but ordinary managed code cannot construct a value. This is the CLR counterpart of
          // the JVM backend's java.lang.Void mapping for non-null kotlin.Nothing.
          .class public sealed auto ansi Nothing
                 extends ${coreLibraryReference}System.Object
          {
            .method private hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 1
              ldarg.0
              call instance void ${coreLibraryReference}System.Object::.ctor()
              ret
            }
          }

          .class public auto ansi beforefieldinit KotlinNothingValueException
                 extends Kotlin.RuntimeException
          {
            .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 1
              ldarg.0
              call instance void Kotlin.RuntimeException::.ctor()
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(string 'message') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              call instance void Kotlin.RuntimeException::.ctor(string)
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(string 'message', class ${coreLibraryReference}System.Exception 'cause') cil managed
            {
              .maxstack 3
              ldarg.0
              ldarg.1
              ldarg.2
              call instance void Kotlin.RuntimeException::.ctor(string, class ${coreLibraryReference}System.Exception)
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(class ${coreLibraryReference}System.Exception 'cause') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              call instance void Kotlin.RuntimeException::.ctor(class ${coreLibraryReference}System.Exception)
              ret
            }
          }

          .class public auto ansi beforefieldinit NoWhenBranchMatchedException
                 extends Kotlin.RuntimeException
          {
            .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 1
              ldarg.0
              call instance void Kotlin.RuntimeException::.ctor()
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(string 'message') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              call instance void Kotlin.RuntimeException::.ctor(string)
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(string 'message', class ${coreLibraryReference}System.Exception 'cause') cil managed
            {
              .maxstack 3
              ldarg.0
              ldarg.1
              ldarg.2
              call instance void Kotlin.RuntimeException::.ctor(string, class ${coreLibraryReference}System.Exception)
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(class ${coreLibraryReference}System.Exception 'cause') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              call instance void Kotlin.RuntimeException::.ctor(class ${coreLibraryReference}System.Exception)
              ret
            }
          }

          .class public auto ansi beforefieldinit NoSuchElementException
                 extends Kotlin.RuntimeException
          {
            .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 1
              ldarg.0
              call instance void Kotlin.RuntimeException::.ctor()
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(string 'message') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              call instance void Kotlin.RuntimeException::.ctor(string)
              ret
            }
          }

          .class public auto ansi beforefieldinit NegativeArraySizeException
                 extends Kotlin.RuntimeException
          {
            .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 1
              ldarg.0
              call instance void Kotlin.RuntimeException::.ctor()
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(string 'message') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              call instance void Kotlin.RuntimeException::.ctor(string)
              ret
            }
          }

          .class public auto ansi beforefieldinit NumberFormatException
                 extends ${coreLibraryReference}System.ArgumentException
          {
            .field private string '_message'

            .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 2
              ldarg.0
              call instance void ${coreLibraryReference}System.ArgumentException::.ctor()
              ldarg.0
              ldnull
              stfld string Kotlin.NumberFormatException::'_message'
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(string 'message') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              call instance void ${coreLibraryReference}System.ArgumentException::.ctor(string)
              ldarg.0
              ldarg.1
              stfld string Kotlin.NumberFormatException::'_message'
              ret
            }

            .method public hidebysig specialname virtual instance string 'get_Message'() cil managed
            {
              .maxstack 1
              ldarg.0
              ldfld string Kotlin.NumberFormatException::'_message'
              ret
            }

            .property instance string Message()
            {
              .get instance string Kotlin.NumberFormatException::'get_Message'()
            }
          }

          .class public auto ansi beforefieldinit Error
                 extends ${coreLibraryReference}System.Exception
          {
            .field private string '_message'

            .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 2
              ldarg.0
              call instance void ${coreLibraryReference}System.Exception::.ctor()
              ldarg.0
              ldnull
              stfld string Kotlin.Error::'_message'
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(string 'message') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              call instance void ${coreLibraryReference}System.Exception::.ctor(string)
              ldarg.0
              ldarg.1
              stfld string Kotlin.Error::'_message'
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(string 'message', class ${coreLibraryReference}System.Exception 'cause') cil managed
            {
              .maxstack 3
              ldarg.0
              ldarg.1
              ldarg.2
              call instance void ${coreLibraryReference}System.Exception::.ctor(string, class ${coreLibraryReference}System.Exception)
              ldarg.0
              ldarg.1
              stfld string Kotlin.Error::'_message'
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(class ${coreLibraryReference}System.Exception 'cause') cil managed
            {
              .maxstack 3
              .locals init ([0] string 'message')
              ldarg.1
              brtrue.s IL_errorCauseNotNull
              ldnull
              br.s IL_errorMessageReady
        IL_errorCauseNotNull:
              ldarg.1
              callvirt instance string ${coreLibraryReference}System.Object::ToString()
        IL_errorMessageReady:
              stloc.0
              ldarg.0
              ldloc.0
              ldarg.1
              call instance void ${coreLibraryReference}System.Exception::.ctor(string, class ${coreLibraryReference}System.Exception)
              ldarg.0
              ldloc.0
              stfld string Kotlin.Error::'_message'
              ret
            }

            .method public hidebysig specialname virtual instance string 'get_Message'() cil managed
            {
              .maxstack 1
              ldarg.0
              ldfld string Kotlin.Error::'_message'
              ret
            }

            .property instance string Message()
            {
              .get instance string Kotlin.Error::'get_Message'()
            }
          }

$throwableExceptionTypesIl

          // Internal Kotlin initialization errors need exact runtime identities: generated
          // catch filters and the static-initialization runtime service refer to these public
          // metadata types across assemblies, while Kotlin source keeps both declarations
          // internal. They are compiler ABI, not C# user API.
          .class public auto ansi beforefieldinit ExceptionInInitializerError
                 extends Kotlin.Error
          {
            $compilerAbiUseAttributesIl

            .method public hidebysig specialname rtspecialname instance void .ctor(
                class ${coreLibraryReference}System.Exception 'cause') cil managed
            {
              .maxstack 3
              ldarg.0
              ldnull
              ldarg.1
              call instance void Kotlin.Error::.ctor(
                  string, class ${coreLibraryReference}System.Exception)
              ret
            }
          }

          .class public auto ansi beforefieldinit NoClassDefFoundError
                 extends Kotlin.Error
          {
            $compilerAbiUseAttributesIl

            .method public hidebysig specialname rtspecialname instance void .ctor(
                string 'message') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              call instance void Kotlin.Error::.ctor(string)
              ret
            }
          }

          // Kotlin built-in numeric values retain their original CLR boxes. Kotlin-written
          // Number subclasses use this abstract arm of the classified Number carrier instead;
          // broad logical Number signatures remain object-shaped and dispatch through runtime
          // helpers that recognize either arm without wrapping.
          .class public abstract auto ansi beforefieldinit Number
                 extends ${coreLibraryReference}System.Object
          {
            .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 1
              ldarg.0
              call instance void ${coreLibraryReference}System.Object::.ctor()
              ret
            }

            .method public hidebysig newslot abstract virtual instance float64 toDouble() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance float32 toFloat() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance int64 toLong() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance int32 toInt() cil managed
            {
            }

            .method public hidebysig newslot virtual instance char toChar() cil managed
            {
              .maxstack 1
              ldarg.0
              callvirt instance int32 Kotlin.Number::toInt()
              conv.u2
              ret
            }

            .method public hidebysig newslot abstract virtual instance int16 toShort() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance int8 toByte() cil managed
            {
            }
          }

          // Kotlin enums are reference objects on every Kotlin target. Runtime owns the one
          // erased base so Stdlib and user enum classes depend downward without making this
          // assembly reference Kotlin.Stdlib.
          .class public abstract auto ansi beforefieldinit Enum
                 extends ${coreLibraryReference}System.Object
                 implements ${coreLibraryReference}System.IComparable
          {
            .field private string 'name'
            .field private int32 'ordinal'

            .method public hidebysig specialname rtspecialname instance void .ctor(
                string 'name', int32 'ordinal') cil managed
            {
              .maxstack 2
              call void Kotlin.Enum/'<CompanionStatics>'::'<EnsureInitialized>'()
              ldarg.0
              call instance void ${coreLibraryReference}System.Object::.ctor()
              ldarg.0
              ldarg.1
              stfld string Kotlin.Enum::'name'
              ldarg.0
              ldarg.2
              stfld int32 Kotlin.Enum::'ordinal'
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(
                string 'name', int32 'ordinal', int32 '${'$'}mask0',
                class Kotlin.Runtime.Internal.DefaultConstructorMarker '${'$'}marker') cil managed
            {
              $compilerAbiUseAttributesIl
              .maxstack 3
              call void Kotlin.Enum/'<CompanionStatics>'::'<EnsureInitialized>'()
              ldarg.3
              ldc.i4.1
              and
              brfalse ENUM_NAME_READY
              ldstr ""
              starg 1
        ENUM_NAME_READY:
              ldarg.3
              ldc.i4.2
              and
              brfalse ENUM_ORDINAL_READY
              ldc.i4.m1
              starg 2
        ENUM_ORDINAL_READY:
              ldarg.0
              ldarg.1
              ldarg.2
              call instance void Kotlin.Enum::.ctor(string, int32)
              ret
            }

            .method public hidebysig specialname instance string get_name() cil managed
            {
              .maxstack 1
              ldarg.0
              ldfld string Kotlin.Enum::'name'
              ret
            }

            .method public hidebysig specialname instance int32 get_ordinal() cil managed
            {
              .maxstack 1
              ldarg.0
              ldfld int32 Kotlin.Enum::'ordinal'
              ret
            }

            .property instance string name()
            {
              .get instance string Kotlin.Enum::get_name()
            }

            .property instance int32 ordinal()
            {
              .get instance int32 Kotlin.Enum::get_ordinal()
            }

            .method public hidebysig newslot virtual final instance int32 compareTo(object 'other') cil managed
            {
              .maxstack 2
              .locals init (
                [0] class Kotlin.Enum 'otherEnum',
                [1] int32 'right',
                [2] int32 'left'
              )
              ldarg.1
              castclass Kotlin.Enum
              stloc.0
              ldarg.0
              call instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Object::GetType()
              ldloc.0
              callvirt instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Object::GetType()
              ceq
              brtrue ENUM_COMPARE_TYPE_READY
              ldarg.0
              call class ${coreLibraryReference}System.Type Kotlin.Enum::'<GetDeclaringClass>'(class Kotlin.Enum)
              ldloc.0
              call class ${coreLibraryReference}System.Type Kotlin.Enum::'<GetDeclaringClass>'(class Kotlin.Enum)
              ceq
              brtrue ENUM_COMPARE_TYPE_READY
              newobj instance void ${coreLibraryReference}System.InvalidCastException::.ctor()
              throw
        ENUM_COMPARE_TYPE_READY:
              ldarg.0
              ldfld int32 Kotlin.Enum::'ordinal'
              ldloc.0
              ldfld int32 Kotlin.Enum::'ordinal'
              stloc.1
              stloc.2
              ldloc.2
              ldloc.1
              clt
              brtrue ENUM_COMPARE_LESS
              ldloc.2
              ldloc.1
              cgt
              brtrue ENUM_COMPARE_GREATER
              ldc.i4.0
              br ENUM_COMPARE_END
        ENUM_COMPARE_LESS:
              ldc.i4.m1
              br ENUM_COMPARE_END
        ENUM_COMPARE_GREATER:
              ldc.i4.1
        ENUM_COMPARE_END:
              ret
            }

            // Mirrors java.lang.Enum.getDeclaringClass for the only two legal physical
            // shapes: the enum class itself and one private entry-body subclass beneath it.
            .method private hidebysig static class ${coreLibraryReference}System.Type
                '<GetDeclaringClass>'(class Kotlin.Enum 'value') cil managed
            {
              .maxstack 2
              .locals init (
                [0] class ${coreLibraryReference}System.Type 'runtimeType',
                [1] class ${coreLibraryReference}System.Type 'baseType'
              )
              ldarg.0
              callvirt instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Object::GetType()
              stloc.0
              ldloc.0
              callvirt instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::get_BaseType()
              stloc.1
              ldloc.1
              ldtoken Kotlin.Enum
              call class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::GetTypeFromHandle(
                  valuetype ${coreLibraryReference}System.RuntimeTypeHandle)
              ceq
              brfalse ENUM_DECLARING_CLASS_IS_BASE
              ldloc.0
              ret
        ENUM_DECLARING_CLASS_IS_BASE:
              ldloc.1
              ret
            }

            .method private hidebysig newslot virtual final instance int32
                '<GenericInterfaceCanonicalBridge-kotlin.Comparable-compareTo-0f838f77390826ba738a927e521071fe>'(
                    object 'other') cil managed
            {
              .override method instance int32 ${coreLibraryReference}System.IComparable::CompareTo(object)
              .maxstack 2
              ldarg.0
              ldarg.1
              callvirt instance int32 Kotlin.Enum::compareTo(object)
              ret
            }

            .method public hidebysig virtual final instance bool Equals(object 'other') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              ceq
              ret
            }

            .method public hidebysig virtual final instance int32 GetHashCode() cil managed
            {
              .maxstack 1
              ldarg.0
              call instance int32 ${coreLibraryReference}System.Object::GetHashCode()
              ret
            }

            .method public hidebysig virtual instance string ToString() cil managed
            {
              .maxstack 1
              ldarg.0
              ldfld string Kotlin.Enum::'name'
              ret
            }

            .class nested public auto ansi sealed beforefieldinit Companion
                   extends ${coreLibraryReference}System.Object
            {
              .method private hidebysig specialname rtspecialname instance void .ctor() cil managed
              {
                .maxstack 1
                ldarg.0
                call instance void ${coreLibraryReference}System.Object::.ctor()
                ret
              }

              .method assembly hidebysig specialname rtspecialname instance void .ctor(
                  class Kotlin.Runtime.Internal.SyntheticConstructorMarker 'marker') cil managed
              {
                .maxstack 1
                ldarg.0
                call instance void Kotlin.Enum/Companion::.ctor()
                ret
              }
            }

            .class nested public abstract sealed auto ansi '<CompanionStatics>'
                   extends ${coreLibraryReference}System.Object
            {
              $compilerAbiUseAttributesIl
              .field public static initonly class Kotlin.Enum/Companion Companion
              .field private static object '<static-initialization-failure>'

              .method private hidebysig specialname rtspecialname static void .cctor() cil managed
              {
                .maxstack 1
                .locals init (
                  [0] class ${coreLibraryReference}System.Exception 'reason'
                )
                .try {
                  ldnull
                  newobj instance void Kotlin.Enum/Companion::.ctor(
                      class Kotlin.Runtime.Internal.SyntheticConstructorMarker)
                  stsfld class Kotlin.Enum/Companion Kotlin.Enum/'<CompanionStatics>'::Companion
                  leave ENUM_CCTOR_END
                }
                catch ${coreLibraryReference}System.Exception {
                  stloc.0
                  ldloc.0
                  call object Kotlin.Runtime.Internal.StaticInitialization::'Capture'(
                      class ${coreLibraryReference}System.Exception)
                  stsfld object Kotlin.Enum/'<CompanionStatics>'::'<static-initialization-failure>'
                  leave ENUM_CCTOR_END
                }
        ENUM_CCTOR_END:
                ret
              }

              .method public hidebysig static void '<EnsureInitialized>'() cil managed
              {
                $compilerAbiUseAttributesIl
                .maxstack 2
                ldsfld object Kotlin.Enum/'<CompanionStatics>'::'<static-initialization-failure>'
                ldnull
                ceq
                ldc.i4.0
                ceq
                brfalse ENUM_INITIALIZED
                ldsfld object Kotlin.Enum/'<CompanionStatics>'::'<static-initialization-failure>'
                call class ${coreLibraryReference}System.Exception Kotlin.Runtime.Internal.StaticInitialization::'Observe'(object)
                ldstr "kotlin.Enum"
                call void Kotlin.Runtime.Internal.StaticInitialization::'Throw'(
                    class ${coreLibraryReference}System.Exception, string)
                newobj instance void Kotlin.KotlinNothingValueException::.ctor()
                throw
        ENUM_INITIALIZED:
                ret
              }
            }
          }

          .class interface public abstract auto ansi Function
          {
          }

          .class interface public abstract auto ansi KCallable
                 implements Kotlin.KAnnotatedElement
          {
            .method public hidebysig specialname newslot abstract virtual instance string 'get_name'() cil managed
            {
            }
            .property instance string name()
            {
              .get instance string Kotlin.KCallable::'get_name'()
            }

            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.KType 'get_returnType'() cil managed
            {
            }
            .property instance class Kotlin.KType returnType()
            {
              .get instance class Kotlin.KType Kotlin.KCallable::'get_returnType'()
            }

            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.Collections.List 'get_typeParameters'() cil managed
            {
            }
            .property instance class Kotlin.Collections.List typeParameters()
            {
              .get instance class Kotlin.Collections.List Kotlin.KCallable::'get_typeParameters'()
            }

            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.Collections.List 'get_parameters'() cil managed
            {
            }
            .property instance class Kotlin.Collections.List parameters()
            {
              .get instance class Kotlin.Collections.List Kotlin.KCallable::'get_parameters'()
            }

            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.KVisibility 'get_visibility'() cil managed
            {
            }
            .property instance class Kotlin.KVisibility visibility()
            {
              .get instance class Kotlin.KVisibility Kotlin.KCallable::'get_visibility'()
            }

            .method public hidebysig specialname newslot abstract virtual instance bool 'get_isFinal'() cil managed
            {
            }
            .property instance bool isFinal()
            {
              .get instance bool Kotlin.KCallable::'get_isFinal'()
            }

            .method public hidebysig specialname newslot abstract virtual instance bool 'get_isOpen'() cil managed
            {
            }
            .property instance bool isOpen()
            {
              .get instance bool Kotlin.KCallable::'get_isOpen'()
            }

            .method public hidebysig specialname newslot abstract virtual instance bool 'get_isAbstract'() cil managed
            {
            }
            .property instance bool isAbstract()
            {
              .get instance bool Kotlin.KCallable::'get_isAbstract'()
            }

            .method public hidebysig newslot abstract virtual instance object Call(object[] 'args') cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance object CallBy(class Kotlin.Collections.Map 'args') cil managed
            {
            }
          }

          .class interface public abstract auto ansi KFunction
                 implements Kotlin.KCallable, Kotlin.Function
          {
            .method public hidebysig specialname newslot abstract virtual instance bool 'get_isInline'() cil managed
            {
            }
            .property instance bool isInline()
            {
              .get instance bool Kotlin.KFunction::'get_isInline'()
            }

            .method public hidebysig specialname newslot abstract virtual instance bool 'get_isExternal'() cil managed
            {
            }
            .property instance bool isExternal()
            {
              .get instance bool Kotlin.KFunction::'get_isExternal'()
            }

            .method public hidebysig specialname newslot abstract virtual instance bool 'get_isOperator'() cil managed
            {
            }
            .property instance bool isOperator()
            {
              .get instance bool Kotlin.KFunction::'get_isOperator'()
            }

            .method public hidebysig specialname newslot abstract virtual instance bool 'get_isInfix'() cil managed
            {
            }
            .property instance bool isInfix()
            {
              .get instance bool Kotlin.KFunction::'get_isInfix'()
            }

            .method public hidebysig specialname newslot abstract virtual instance bool 'get_isSuspend'() cil managed
            {
            }
            .property instance bool isSuspend()
            {
              .get instance bool Kotlin.KFunction::'get_isSuspend'()
            }
          }

          .class interface public abstract auto ansi KProperty
                 implements Kotlin.KCallable
          {
            .method public hidebysig specialname newslot abstract virtual instance bool get_isLateinit() cil managed
            {
            }
            .method public hidebysig specialname newslot abstract virtual instance bool get_isConst() cil managed
            {
            }
            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.KProperty/Getter get_getter() cil managed
            {
            }
            .property instance bool isLateinit()
            {
              .get instance bool Kotlin.KProperty::get_isLateinit()
            }
            .property instance bool isConst()
            {
              .get instance bool Kotlin.KProperty::get_isConst()
            }
            .property instance class Kotlin.KProperty/Getter getter()
            {
              .get instance class Kotlin.KProperty/Getter Kotlin.KProperty::get_getter()
            }

            .class nested public interface abstract auto ansi Accessor
            {
              .method public hidebysig specialname newslot abstract virtual instance class Kotlin.KProperty get_property() cil managed
              {
              }
              .property instance class Kotlin.KProperty 'property'()
              {
                .get instance class Kotlin.KProperty Kotlin.KProperty/Accessor::get_property()
              }
            }

            .class nested public interface abstract auto ansi Getter
                   implements Kotlin.KProperty/Accessor, Kotlin.KFunction
            {
            }
          }

          .class interface public abstract auto ansi KMutableProperty
                 implements Kotlin.KProperty
          {
            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.KMutableProperty/Setter get_setter() cil managed
            {
            }
            .property instance class Kotlin.KMutableProperty/Setter setter()
            {
              .get instance class Kotlin.KMutableProperty/Setter Kotlin.KMutableProperty::get_setter()
            }

            .class nested public interface abstract auto ansi Setter
                   implements Kotlin.KProperty/Accessor, Kotlin.KFunction
            {
            }
          }

          .class interface public abstract auto ansi KProperty0
                 implements Kotlin.KProperty, Kotlin.Function0
          {
            .method public hidebysig newslot abstract virtual instance object Get() cil managed
            {
            }
            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.KProperty0/Getter get_getter() cil managed
            {
            }
            .property instance class Kotlin.KProperty0/Getter getter()
            {
              .get instance class Kotlin.KProperty0/Getter Kotlin.KProperty0::get_getter()
            }

            .class nested public interface abstract auto ansi Getter
                   implements Kotlin.KProperty/Getter, Kotlin.Function0
            {
            }
          }

          .class interface public abstract auto ansi KProperty1
                 implements Kotlin.KProperty, Kotlin.Function1
          {
            .method public hidebysig newslot abstract virtual instance object Get(object receiver) cil managed
            {
            }
            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.KProperty1/Getter get_getter() cil managed
            {
            }
            .property instance class Kotlin.KProperty1/Getter getter()
            {
              .get instance class Kotlin.KProperty1/Getter Kotlin.KProperty1::get_getter()
            }

            .class nested public interface abstract auto ansi Getter
                   implements Kotlin.KProperty/Getter, Kotlin.Function1
            {
            }
          }

          .class interface public abstract auto ansi KProperty2
                 implements Kotlin.KProperty, Kotlin.Function2
          {
            .method public hidebysig newslot abstract virtual instance object Get(object receiver1, object receiver2) cil managed
            {
            }
            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.KProperty2/Getter get_getter() cil managed
            {
            }
            .property instance class Kotlin.KProperty2/Getter getter()
            {
              .get instance class Kotlin.KProperty2/Getter Kotlin.KProperty2::get_getter()
            }

            .class nested public interface abstract auto ansi Getter
                   implements Kotlin.KProperty/Getter, Kotlin.Function2
            {
            }
          }

          .class interface public abstract auto ansi KMutableProperty0
                 implements Kotlin.KProperty0, Kotlin.KMutableProperty
          {
            .method public hidebysig newslot abstract virtual instance void Set(object 'value') cil managed
            {
            }
            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.KMutableProperty0/Setter get_setter() cil managed
            {
            }
            .property instance class Kotlin.KMutableProperty0/Setter setter()
            {
              .get instance class Kotlin.KMutableProperty0/Setter Kotlin.KMutableProperty0::get_setter()
            }

            .class nested public interface abstract auto ansi Setter
                   implements Kotlin.KMutableProperty/Setter, Kotlin.Function1
            {
            }
          }

          .class interface public abstract auto ansi KMutableProperty1
                 implements Kotlin.KProperty1, Kotlin.KMutableProperty
          {
            .method public hidebysig newslot abstract virtual instance void Set(object receiver, object 'value') cil managed
            {
            }
            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.KMutableProperty1/Setter get_setter() cil managed
            {
            }
            .property instance class Kotlin.KMutableProperty1/Setter setter()
            {
              .get instance class Kotlin.KMutableProperty1/Setter Kotlin.KMutableProperty1::get_setter()
            }

            .class nested public interface abstract auto ansi Setter
                   implements Kotlin.KMutableProperty/Setter, Kotlin.Function2
            {
            }
          }

          .class interface public abstract auto ansi KMutableProperty2
                 implements Kotlin.KProperty2, Kotlin.KMutableProperty
          {
            .method public hidebysig newslot abstract virtual instance void Set(object receiver1, object receiver2, object 'value') cil managed
            {
            }
            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.KMutableProperty2/Setter get_setter() cil managed
            {
            }
            .property instance class Kotlin.KMutableProperty2/Setter setter()
            {
              .get instance class Kotlin.KMutableProperty2/Setter Kotlin.KMutableProperty2::get_setter()
            }

            .class nested public interface abstract auto ansi Setter
                   implements Kotlin.KMutableProperty/Setter, Kotlin.Function3
            {
            }
          }

$fixedFunctionTypesIl

          // Capability arm of the classified Kotlin CharSequence carrier. System.String is the
          // other arm and deliberately cannot implement this interface; logical CharSequence
          // signatures therefore remain object-shaped and dispatch through runtime helpers.
          .class interface public abstract auto ansi CharSequence
          {
            .method public hidebysig specialname newslot abstract virtual instance int32 get_length() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance char get(int32 index) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance object subSequence(
                int32 startIndex, int32 endIndex) cil managed
            {
            }

            .property instance int32 length()
            {
              .get instance int32 Kotlin.CharSequence::get_length()
            }
          }

          .class public sealed auto ansi Unit extends ${coreLibraryReference}System.Object
          {
            .field public static initonly class Kotlin.Unit INSTANCE

            .method private hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 1
              ldarg.0
              call instance void ${coreLibraryReference}System.Object::.ctor()
              ret
            }

            .method private hidebysig specialname rtspecialname static void .cctor() cil managed
            {
              .maxstack 1
              newobj instance void Kotlin.Unit::.ctor()
              stsfld class Kotlin.Unit Kotlin.Unit::INSTANCE
              ret
            }
          }
        }

        .namespace Kotlin.Collections
        {
          .class interface public abstract auto ansi Iterator
          {
            .method public hidebysig newslot abstract virtual instance bool HasNext() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance object Next() cil managed
            {
            }
          }

          // Additive natural CLR view selected by the generic-owner rehearsal. The erased
          // Iterator above remains the Kotlin semantic capability; Kotlin implementations carry
          // both MethodImpl bundles on one object.
          .class interface public abstract auto ansi 'Iterator`1'<+ T>
          {
            .method public hidebysig newslot abstract virtual instance bool HasNext() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance !T Next() cil managed
            {
            }
          }

          .class interface public abstract auto ansi ListIterator
                 implements Kotlin.Collections.Iterator
          {
            .method public hidebysig newslot abstract virtual instance bool HasNext() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance object Next() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool HasPrevious() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance object Previous() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance int32 NextIndex() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance int32 PreviousIndex() cil managed
            {
            }
          }

          .class interface public abstract auto ansi 'ListIterator`1'<+ T>
                 implements class 'Kotlin.Collections.Iterator`1'<!T>
          {
            .method public hidebysig newslot abstract virtual instance bool HasNext() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance !T Next() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool HasPrevious() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance !T Previous() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance int32 NextIndex() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance int32 PreviousIndex() cil managed
            {
            }
          }

          .class interface public abstract auto ansi MutableIterator
                 implements Kotlin.Collections.Iterator
          {
            .method public hidebysig newslot abstract virtual instance void Remove() cil managed
            {
            }
          }

          // remove() is independent of T, so the complete natural MutableIterator remains
          // covariant and composes directly with the natural Iterator<T> foundation.
          .class interface public abstract auto ansi 'MutableIterator`1'<+ T>
                 implements class 'Kotlin.Collections.Iterator`1'<!T>
          {
            .method public hidebysig newslot abstract virtual instance void Remove() cil managed
            {
            }
          }

          .class interface public abstract auto ansi MutableListIterator
                 implements Kotlin.Collections.ListIterator,
                            Kotlin.Collections.MutableIterator
          {
            .method public hidebysig newslot abstract virtual instance bool HasNext() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance object Next() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Remove() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Set(object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Add(object element) cil managed
            {
            }
          }

          // MutableListIterator is declaration-invariant, so its natural CLR construction can
          // own both typed reads and typed mutation inputs without an exact sibling.
          .class interface public abstract auto ansi 'MutableListIterator`1'<T>
                 implements class 'Kotlin.Collections.ListIterator`1'<!T>,
                            class 'Kotlin.Collections.MutableIterator`1'<!T>
          {
            .method public hidebysig newslot abstract virtual instance bool HasNext() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance !T Next() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Remove() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Set(!T element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Add(!T element) cil managed
            {
            }
          }

          .class interface public abstract auto ansi Iterable
          {
            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.Iterator GetIterator() cil managed
            {
            }
          }

          .class interface public abstract auto ansi 'Iterable`1'<+ T>
          {
            .method public hidebysig newslot abstract virtual instance class 'Kotlin.Collections.Iterator`1'<!T> GetIterator() cil managed
            {
            }
          }

          .class interface public abstract auto ansi MutableIterable
                 implements Kotlin.Collections.Iterable
          {
            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.MutableIterator GetIterator() cil managed
            {
            }
          }

          // This narrower result is a second ordinary CLR interface slot beside the inherited
          // Iterable<T>.GetIterator() slot. Kotlin implementations provide both MethodImpls.
          .class interface public abstract auto ansi 'MutableIterable`1'<+ T>
                 implements class 'Kotlin.Collections.Iterable`1'<!T>
          {
            .method public hidebysig newslot abstract virtual instance class 'Kotlin.Collections.MutableIterator`1'<!T> GetIterator() cil managed
            {
            }
          }

          .class interface public abstract auto ansi Collection
                 implements Kotlin.Collections.Iterable
          {
            .method public hidebysig specialname newslot abstract virtual instance int32 get_Size() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool IsEmpty() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool ContainsErased(object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.Iterator GetIterator() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool ContainsAll(object elements) cil managed
            {
            }

            .property instance int32 Size()
            {
              .get instance int32 Kotlin.Collections.Collection::get_Size()
            }
          }

          // Natural and exact-input CLR views selected as one atomic family by the generic-owner
          // rehearsal. The erased Collection above remains its declaration-semantic capability.
          .class interface public abstract auto ansi 'Collection`1'<+ T>
                 implements class 'Kotlin.Collections.Iterable`1'<!T>
          {
            .method public hidebysig specialname newslot abstract virtual instance int32 get_Size() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool IsEmpty() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class 'Kotlin.Collections.Iterator`1'<!T> GetIterator() cil managed
            {
            }

            .property instance int32 Size()
            {
              .get instance int32 'Kotlin.Collections.Collection`1'::get_Size()
            }
          }

          .class interface public abstract auto ansi 'Collection__KotlinExact`1'<T>
                 implements class 'Kotlin.Collections.Collection`1'<!T>,
                            class 'Kotlin.Collections.Iterable`1'<!T>
          {
            .method public hidebysig newslot abstract virtual instance bool Contains(!T element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool ContainsAll(
                class 'Kotlin.Collections.Collection`1'<!T> elements) cil managed
            {
            }
          }

          .class interface public abstract auto ansi MutableCollection
                 implements Kotlin.Collections.Collection,
                            Kotlin.Collections.MutableIterable
          {
            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.MutableIterator GetIterator() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool Add(object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RemoveErased(object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool AddAll(class Kotlin.Collections.Collection elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RemoveAll(class Kotlin.Collections.Collection elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RetainAll(class Kotlin.Collections.Collection elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Clear() cil managed
            {
            }
          }

          // The invariant natural owner keeps element operations typed. Bulk collection inputs
          // use a physical method parameter U : T: unlike CLR interface variance, that relative
          // constraint also admits Kotlin's Collection<int> -> Collection<object> widening.
          .class interface public abstract auto ansi 'MutableCollection`1'<T>
                 implements class 'Kotlin.Collections.Collection`1'<!T>,
                            class 'Kotlin.Collections.MutableIterable`1'<!T>
          {
            .method public hidebysig newslot abstract virtual instance class 'Kotlin.Collections.MutableIterator`1'<!T> GetIterator() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool Add(!T element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool Remove(!T element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool AddAll<(!T) U>(
                class 'Kotlin.Collections.Collection`1'<!!0> elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RemoveAll<(!T) U>(
                class 'Kotlin.Collections.Collection`1'<!!0> elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RetainAll<(!T) U>(
                class 'Kotlin.Collections.Collection`1'<!!0> elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Clear() cil managed
            {
            }
          }

          .class interface public abstract auto ansi List
                 implements Kotlin.Collections.Collection
          {
            .method public hidebysig specialname newslot abstract virtual instance int32 get_Size() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool IsEmpty() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool ContainsErased(object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.Iterator GetIterator() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool ContainsAll(class Kotlin.Collections.Collection elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance object Get(int32 index) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance int32 IndexOfErased(object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance int32 LastIndexOfErased(object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.ListIterator GetListIterator() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.ListIterator GetListIterator(int32 index) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.List SubList(int32 fromIndex, int32 toIndex) cil managed
            {
            }

            .property instance int32 Size()
            {
              .get instance int32 Kotlin.Collections.List::get_Size()
            }
          }

          .class interface public abstract auto ansi 'List`1'<+ T>
                 implements class 'Kotlin.Collections.Collection`1'<!T>
          {
            .method public hidebysig specialname newslot abstract virtual instance int32 get_Size() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool IsEmpty() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class 'Kotlin.Collections.Iterator`1'<!T> GetIterator() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance !T Get(int32 index) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class 'Kotlin.Collections.ListIterator`1'<!T> GetListIterator() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class 'Kotlin.Collections.ListIterator`1'<!T> GetListIterator(int32 index) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class 'Kotlin.Collections.List`1'<!T> SubList(int32 fromIndex, int32 toIndex) cil managed
            {
            }

            .property instance int32 Size()
            {
              .get instance int32 'Kotlin.Collections.List`1'::get_Size()
            }
          }

          .class interface public abstract auto ansi 'List__KotlinExact`1'<T>
                 implements class 'Kotlin.Collections.List`1'<!T>,
                            class 'Kotlin.Collections.Collection__KotlinExact`1'<!T>
          {
            .method public hidebysig newslot abstract virtual instance bool Contains(!T element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool ContainsAll(
                class 'Kotlin.Collections.Collection`1'<!T> elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance int32 IndexOf(!T element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance int32 LastIndexOf(!T element) cil managed
            {
            }
          }

          .class interface public abstract auto ansi MutableList
                 implements Kotlin.Collections.List,
                            Kotlin.Collections.MutableCollection
          {
            .method public hidebysig newslot abstract virtual instance bool Add(object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RemoveErased(object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool AddAll(class Kotlin.Collections.Collection elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool AddAll(int32 index, class Kotlin.Collections.Collection elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RemoveAll(class Kotlin.Collections.Collection elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RetainAll(class Kotlin.Collections.Collection elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Clear() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance object Set(int32 index, object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Add(int32 index, object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance object RemoveAt(int32 index) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.MutableListIterator GetListIterator() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.MutableListIterator GetListIterator(int32 index) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.MutableList SubList(int32 fromIndex, int32 toIndex) cil managed
            {
            }
          }

          // MutableList is invariant, so its positional inputs and results stay on one natural
          // construction. Both bulk overloads use the same relative U : T input; the indexed
          // form proves that the nested collection need not occupy physical parameter zero.
          .class interface public abstract auto ansi 'MutableList`1'<T>
                 implements class 'Kotlin.Collections.List`1'<!T>,
                            class 'Kotlin.Collections.MutableCollection`1'<!T>
          {
            .method public hidebysig newslot abstract virtual instance bool Add(!T element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool Remove(!T element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool AddAll<(!T) U>(
                class 'Kotlin.Collections.Collection`1'<!!0> elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool AddAll<(!T) U>(
                int32 index,
                class 'Kotlin.Collections.Collection`1'<!!0> elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RemoveAll<(!T) U>(
                class 'Kotlin.Collections.Collection`1'<!!0> elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RetainAll<(!T) U>(
                class 'Kotlin.Collections.Collection`1'<!!0> elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Clear() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance !T Set(int32 index, !T element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Add(int32 index, !T element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance !T RemoveAt(int32 index) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class 'Kotlin.Collections.MutableListIterator`1'<!T> GetListIterator() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class 'Kotlin.Collections.MutableListIterator`1'<!T> GetListIterator(int32 index) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class 'Kotlin.Collections.MutableList`1'<!T> SubList(int32 fromIndex, int32 toIndex) cil managed
            {
            }
          }

          .class interface public abstract auto ansi Set
                 implements Kotlin.Collections.Collection
          {
            .method public hidebysig specialname newslot abstract virtual instance int32 get_Size() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool IsEmpty() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool ContainsErased(object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.Iterator GetIterator() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool ContainsAll(object elements) cil managed
            {
            }

            .property instance int32 Size()
            {
              .get instance int32 Kotlin.Collections.Set::get_Size()
            }
          }

          .class interface public abstract auto ansi 'Set`1'<+ T>
                 implements class 'Kotlin.Collections.Collection`1'<!T>
          {
            .method public hidebysig specialname newslot abstract virtual instance int32 get_Size() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool IsEmpty() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance class 'Kotlin.Collections.Iterator`1'<!T> GetIterator() cil managed
            {
            }

            .property instance int32 Size()
            {
              .get instance int32 'Kotlin.Collections.Set`1'::get_Size()
            }
          }

          .class interface public abstract auto ansi 'Set__KotlinExact`1'<T>
                 implements class 'Kotlin.Collections.Set`1'<!T>,
                            class 'Kotlin.Collections.Collection__KotlinExact`1'<!T>
          {
            .method public hidebysig newslot abstract virtual instance bool Contains(!T element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool ContainsAll(
                class 'Kotlin.Collections.Collection`1'<!T> elements) cil managed
            {
            }
          }

          .class interface public abstract auto ansi MutableSet
                 implements Kotlin.Collections.Set,
                            Kotlin.Collections.MutableCollection
          {
            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.MutableIterator GetIterator() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool Add(object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RemoveErased(object element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool AddAll(class Kotlin.Collections.Collection elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RemoveAll(class Kotlin.Collections.Collection elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RetainAll(class Kotlin.Collections.Collection elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Clear() cil managed
            {
            }
          }

          // MutableSet redeclares the complete mutation family over the invariant natural
          // construction. Its two natural parents form the first mutable collection diamond;
          // relative bulk inputs retain the same U : T slot grammar as MutableCollection.
          .class interface public abstract auto ansi 'MutableSet`1'<T>
                 implements class 'Kotlin.Collections.Set`1'<!T>,
                            class 'Kotlin.Collections.MutableCollection`1'<!T>
          {
            .method public hidebysig newslot abstract virtual instance class 'Kotlin.Collections.MutableIterator`1'<!T> GetIterator() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool Add(!T element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool Remove(!T element) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool AddAll<(!T) U>(
                class 'Kotlin.Collections.Collection`1'<!!0> elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RemoveAll<(!T) U>(
                class 'Kotlin.Collections.Collection`1'<!!0> elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool RetainAll<(!T) U>(
                class 'Kotlin.Collections.Collection`1'<!!0> elements) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Clear() cil managed
            {
            }
          }

          .class interface public abstract auto ansi Map
          {
            .class nested public interface abstract auto ansi Entry
            {
              .method public hidebysig specialname newslot abstract virtual instance object get_Key() cil managed
              {
              }

              .method public hidebysig specialname newslot abstract virtual instance object get_Value() cil managed
              {
              }

              .property instance object Key()
              {
                .get instance object Kotlin.Collections.Map/'Entry'::get_Key()
              }

              .property instance object Value()
              {
                .get instance object Kotlin.Collections.Map/'Entry'::get_Value()
              }
            }

            // Additive natural CLR view for the first multiple-owner-parameter Runtime family.
            // The erased nested Entry above remains Kotlin's declaration-semantic capability.
            .class nested public interface abstract auto ansi 'Entry`2'<+ K, + V>
            {
              .method public hidebysig specialname newslot abstract virtual instance !K get_Key() cil managed
              {
              }

              .method public hidebysig specialname newslot abstract virtual instance !V get_Value() cil managed
              {
              }

              .property instance !K Key()
              {
                .get instance !K Kotlin.Collections.Map/'Entry`2'::get_Key()
              }

              .property instance !V Value()
              {
                .get instance !V Kotlin.Collections.Map/'Entry`2'::get_Value()
              }
            }

            .method public hidebysig specialname newslot abstract virtual instance int32 get_Size() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool IsEmpty() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool ContainsKeyErased(object key) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool ContainsValueErased(object 'value') cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance object GetErased(object key) cil managed
            {
            }

            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.Collections.Set get_Keys() cil managed
            {
            }

            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.Collections.Collection get_Values() cil managed
            {
            }

            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.Collections.Set get_Entries() cil managed
            {
            }

            .property instance int32 Size()
            {
              .get instance int32 Kotlin.Collections.Map::get_Size()
            }

            .property instance class Kotlin.Collections.Set Keys()
            {
              .get instance class Kotlin.Collections.Set Kotlin.Collections.Map::get_Keys()
            }

            .property instance class Kotlin.Collections.Collection Values()
            {
              .get instance class Kotlin.Collections.Collection Kotlin.Collections.Map::get_Values()
            }

            .property instance class Kotlin.Collections.Set Entries()
            {
              .get instance class Kotlin.Collections.Set Kotlin.Collections.Map::get_Entries()
            }
          }

          // Mixed-variance natural Map keeps every CLR-representable slot typed. The nullable
          // V? lookup result remains object because one unconstrained CLR V cannot alternate
          // between a reference and Nullable<V>. Only the covariant-V input lives on the
          // invariant exact sibling below.
          .class interface public abstract auto ansi 'Map`2'<K, + V>
          {
            .method public hidebysig specialname newslot abstract virtual instance int32 get_Size() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool IsEmpty() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool ContainsKey(!K key) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance object Get(!K key) cil managed
            {
            }

            .method public hidebysig specialname newslot abstract virtual instance
                class 'Kotlin.Collections.Set`1'<!K> get_Keys() cil managed
            {
            }

            .method public hidebysig specialname newslot abstract virtual instance
                class 'Kotlin.Collections.Collection`1'<!V> get_Values() cil managed
            {
            }

            .method public hidebysig specialname newslot abstract virtual instance
                class 'Kotlin.Collections.Set`1'<class Kotlin.Collections.Map/'Entry`2'<!K, !V>>
                get_Entries() cil managed
            {
            }

            .property instance int32 Size()
            {
              .get instance int32 'Kotlin.Collections.Map`2'::get_Size()
            }

            .property instance class 'Kotlin.Collections.Set`1'<!K> Keys()
            {
              .get instance class 'Kotlin.Collections.Set`1'<!K>
                'Kotlin.Collections.Map`2'::get_Keys()
            }

            .property instance class 'Kotlin.Collections.Collection`1'<!V> Values()
            {
              .get instance class 'Kotlin.Collections.Collection`1'<!V>
                'Kotlin.Collections.Map`2'::get_Values()
            }

            .property instance
                class 'Kotlin.Collections.Set`1'<class Kotlin.Collections.Map/'Entry`2'<!K, !V>>
                Entries()
            {
              .get instance
                class 'Kotlin.Collections.Set`1'<class Kotlin.Collections.Map/'Entry`2'<!K, !V>>
                'Kotlin.Collections.Map`2'::get_Entries()
            }
          }

          .class interface public abstract auto ansi 'Map__KotlinExact`2'<K, V>
                 implements class 'Kotlin.Collections.Map`2'<!K, !V>
          {
            .method public hidebysig newslot abstract virtual instance bool ContainsValue(!V 'value') cil managed
            {
            }
          }

          .class interface public abstract auto ansi MutableMap
                 implements Kotlin.Collections.Map
          {
            .class nested public interface abstract auto ansi MutableEntry
                   implements Kotlin.Collections.Map/'Entry'
            {
              .method public hidebysig newslot abstract virtual instance object SetValue(object newValue) cil managed
              {
              }
            }

            // Additive invariant natural CLR child over the selected Map.Entry<K,V> root.
            // The erased nested MutableEntry above remains the Kotlin semantic capability.
            .class nested public interface abstract auto ansi 'MutableEntry`2'<K, V>
                   implements class Kotlin.Collections.Map/'Entry`2'<!K, !V>
            {
              .method public hidebysig newslot abstract virtual instance !V SetValue(!V newValue) cil managed
              {
              }
            }

            .method public hidebysig newslot abstract virtual instance object PutErased(object key, object 'value') cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance object RemoveKeyErased(object key) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void PutAll(class Kotlin.Collections.Map from) cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance void Clear() cil managed
            {
            }

            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.Collections.MutableSet get_Keys() cil managed
            {
            }

            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.Collections.MutableCollection get_Values() cil managed
            {
            }

            .method public hidebysig specialname newslot abstract virtual instance class Kotlin.Collections.MutableSet get_Entries() cil managed
            {
            }

            .property instance class Kotlin.Collections.MutableSet Keys()
            {
              .get instance class Kotlin.Collections.MutableSet Kotlin.Collections.MutableMap::get_Keys()
            }

            .property instance class Kotlin.Collections.MutableCollection Values()
            {
              .get instance class Kotlin.Collections.MutableCollection Kotlin.Collections.MutableMap::get_Values()
            }

            .property instance class Kotlin.Collections.MutableSet Entries()
            {
              .get instance class Kotlin.Collections.MutableSet Kotlin.Collections.MutableMap::get_Entries()
            }
          }

        }
    """.trimIndent() + "\n" + DotNetKVisibilityRuntime.ilText(
        coreLibraryReference,
        compilerAbiUseAttributesIl,
    ) + "\n" + DotNetRuntimeLibraryHelpers.ilText(
        coreLibraryReference,
        coreLibrary.editorBrowsableReference,
    )
    }

    private fun fixedFunctionTypesIl(): String = buildString {
        append((0 until BuiltInFunctionArity.BIG_ARITY).joinToString("\n\n") { arity ->
            val parameters = (1..arity).joinToString(", ") { index -> "object p$index" }
            """
          .class interface public abstract auto ansi Function$arity
                 implements Kotlin.Function
          {
            .method public hidebysig newslot abstract virtual instance object Invoke($parameters) cil managed
            {
            }
            }
            """.trimIndent()
        })
        append("\n\n")
        append(
            """
          .class interface public abstract auto ansi FunctionN
                 implements Kotlin.Function
          {
            .method public hidebysig newslot abstract virtual instance object Invoke(object[] 'args') cil managed
            {
            }

            .method public hidebysig specialname newslot abstract virtual instance int32 get_arity() cil managed
            {
            }

            .property instance int32 arity()
            {
              .get instance int32 Kotlin.FunctionN::get_arity()
            }
          }
            """.trimIndent()
        )
    }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
