package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.DotNetTarget
import java.io.File

/**
 * The first physical Kotlin/.NET runtime boundary.
 *
 * The assembly boundary was established before its first public ABI candidate types. It now owns
 * the fixed, physically erased Function0/1/2/3 interfaces, the orthogonal KCallable/KFunction
 * reflection view, erased KProperty0/1/2 identities, the split Iterator/ListIterator/Iterable/
 * Collection/List execution interfaces,
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
    ): File? = assembleRuntime(
        outputDirectory.resolve("runtime-manifest-conformance-placeholder"),
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
            coreLibrary.appendAssemblyMetadataAttributeTo(
                this,
                DotNetLibraryAbiCodec.RUNTIME_SURFACE_METADATA_KEY,
                DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL.toString(),
            )
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
          }

          .class interface public abstract auto ansi KMutableProperty
                 implements Kotlin.KProperty
          {
          }

          .class interface public abstract auto ansi KProperty0
                 implements Kotlin.KProperty, Kotlin.Function0
          {
            .method public hidebysig newslot abstract virtual instance object Get() cil managed
            {
            }
          }

          .class interface public abstract auto ansi KProperty1
                 implements Kotlin.KProperty, Kotlin.Function1
          {
            .method public hidebysig newslot abstract virtual instance object Get(object receiver) cil managed
            {
            }
          }

          .class interface public abstract auto ansi KProperty2
                 implements Kotlin.KProperty, Kotlin.Function2
          {
            .method public hidebysig newslot abstract virtual instance object Get(object receiver1, object receiver2) cil managed
            {
            }
          }

          .class interface public abstract auto ansi KMutableProperty0
                 implements Kotlin.KProperty0, Kotlin.KMutableProperty
          {
            .method public hidebysig newslot abstract virtual instance void Set(object 'value') cil managed
            {
            }
          }

          .class interface public abstract auto ansi KMutableProperty1
                 implements Kotlin.KProperty1, Kotlin.KMutableProperty
          {
            .method public hidebysig newslot abstract virtual instance void Set(object receiver, object 'value') cil managed
            {
            }
          }

          .class interface public abstract auto ansi KMutableProperty2
                 implements Kotlin.KProperty2, Kotlin.KMutableProperty
          {
            .method public hidebysig newslot abstract virtual instance void Set(object receiver1, object receiver2, object 'value') cil managed
            {
            }
          }

          .class interface public abstract auto ansi Function0
                 implements Kotlin.Function
          {
            .method public hidebysig newslot abstract virtual instance object Invoke() cil managed
            {
            }
          }

          .class interface public abstract auto ansi Function1
                 implements Kotlin.Function
          {
            .method public hidebysig newslot abstract virtual instance object Invoke(object p1) cil managed
            {
            }
          }

          .class interface public abstract auto ansi Function2
                 implements Kotlin.Function
          {
            .method public hidebysig newslot abstract virtual instance object Invoke(object p1, object p2) cil managed
            {
            }
          }

          .class interface public abstract auto ansi Function3
                 implements Kotlin.Function
          {
            .method public hidebysig newslot abstract virtual instance object Invoke(object p1, object p2, object p3) cil managed
            {
            }
          }

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

          .class interface public abstract auto ansi MutableIterator
                 implements Kotlin.Collections.Iterator
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

          .class interface public abstract auto ansi Iterable
          {
            .method public hidebysig newslot abstract virtual instance class Kotlin.Collections.Iterator GetIterator() cil managed
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

            .method public hidebysig newslot abstract virtual instance bool ContainsAll(class Kotlin.Collections.Collection elements) cil managed
            {
            }

            .property instance int32 Size()
            {
              .get instance int32 Kotlin.Collections.Collection::get_Size()
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

            .method public hidebysig newslot abstract virtual instance bool ContainsAll(class Kotlin.Collections.Collection elements) cil managed
            {
            }

            .property instance int32 Size()
            {
              .get instance int32 Kotlin.Collections.Set::get_Size()
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
    """.trimIndent() + "\n" + DotNetRuntimeLibraryHelpers.ilText(
        coreLibraryReference,
        coreLibrary.editorBrowsableReference,
    )
}

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
