/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli

import org.jetbrains.kotlin.backend.dotnet.DotNetDefaultArgumentDispatcher
import org.jetbrains.kotlin.backend.dotnet.DotNetCompanionInitialization
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpDefaultKind
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpErasedOwnerRelativeConstraint
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpImplementationManifest
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpImplementationManifestCodec
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpInterfaceContract
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpInterfaceView
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpIntersectionContract
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpMemberContract
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpMemberKind
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpMethodLocator
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpSlotRole
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpTypeParameter
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpTypeParameterVariance
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpWrongShapeFallback
import org.jetbrains.kotlin.backend.dotnet.DotNetCSharpWrongShapePolicy
import org.jetbrains.kotlin.backend.dotnet.DotNetIlAssembler
import org.jetbrains.kotlin.backend.dotnet.DotNetInterfaceDefaultBodyPlacement
import org.jetbrains.kotlin.backend.dotnet.DotNetInterfaceDefaultPromotionView
import org.jetbrains.kotlin.backend.dotnet.DotNetFriendAssemblyIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetInterfaceDefaultImplementation
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryAbiCodec
import org.jetbrains.kotlin.backend.dotnet.DotNetModernCSharpToolchain
import org.jetbrains.kotlin.backend.dotnet.DotNetObjectInstance
import org.jetbrains.kotlin.backend.dotnet.DotNetPhysicalDeclaration
import org.jetbrains.kotlin.backend.dotnet.DotNetPortablePhysicalAbiDifference
import org.jetbrains.kotlin.backend.dotnet.DotNetTarget
import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2DotNetCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.cliArgument
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.dotnet.K2DotNetCompiler
import org.jetbrains.kotlin.cli.metadata.KotlinMetadataCompiler
import org.jetbrains.kotlin.library.KLIB_PROPERTY_MANUALLY_ALTERED_LANGUAGE_FEATURES
import org.jetbrains.kotlin.library.KLIB_PROPERTY_METADATA_FLAGS
import org.jetbrains.kotlin.library.KLIB_PROPERTY_NEW_COMPANION_INITIALIZATION
import org.jetbrains.kotlin.test.TestCaseWithTmpdir
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipFile

class DotNetLibraryIntegrationTest : TestCaseWithTmpdir() {
    @Test
    fun testGenericInterfacePhysicalViewsRoundTrip() {
        val declarations = mapOf(
            "C:sample/Producer" to DotNetPhysicalDeclaration.Class(
                ownerPath = listOf("sample.Producer"),
                declaredOwnerPath = listOf("sample.Producer`1"),
                exactOwnerPath = listOf("sample.Producer\$Exact`1"),
            ),
            "C:sample/Consumer" to DotNetPhysicalDeclaration.Class(
                ownerPath = listOf("sample.Consumer"),
                declaredOwnerPath = listOf("sample.Consumer`1"),
            ),
            "C:sample/Counter" to DotNetPhysicalDeclaration.Class(
                ownerPath = listOf("sample.Counter"),
                companionInitialization = DotNetCompanionInitialization(
                    ownerPath = listOf("sample.Counter", "<CompanionStatics>"),
                    methodName = "<EnsureCompanionInitialized>",
                ),
                objectInstance = DotNetObjectInstance(
                    ownerPath = listOf("sample.Counter", "<CompanionStatics>"),
                    fieldName = "INSTANCE",
                ),
            ),
            "F:sample/increment" to DotNetPhysicalDeclaration.Function(
                ownerPath = listOf("sample.LibraryKt"),
                methodName = "Increment",
                isInstance = false,
            ),
            "F:sample/abstractWithDefaults" to DotNetPhysicalDeclaration.Function(
                ownerPath = listOf("sample.Contract"),
                methodName = "abstractWithDefaults",
                isInstance = true,
                defaultArgumentDispatcher = DotNetDefaultArgumentDispatcher(
                    ownerPath = listOf("sample.Contract", "__KotlinDefaultImpls"),
                    methodName = "abstractWithDefaults\$default",
                ),
            ),
            "F:sample/defaultWithDefaults" to DotNetPhysicalDeclaration.Function(
                ownerPath = listOf("sample.Contract"),
                methodName = "defaultWithDefaults",
                isInstance = true,
                interfaceDefaultImplementation = DotNetInterfaceDefaultImplementation(
                    bodyPlacement = DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY,
                    helperOwnerPath = listOf("sample.Contract", "__KotlinDefaultImpls"),
                    helperMethodName = "defaultWithDefaults",
                ),
                defaultArgumentDispatcher = DotNetDefaultArgumentDispatcher(
                    ownerPath = listOf("sample.Contract", "__KotlinDefaultImpls"),
                    methodName = "defaultWithDefaults\$default",
                ),
            ),
            "B:C:sample/Contract:F:sample/defaultWithDefaults:CANONICAL" to
                    DotNetPhysicalDeclaration.GenericInterfaceViewBridge(
                        ownerPath = listOf("sample.Contract"),
                        ownerLogicalKey = "C:sample/Contract",
                        inheritedLogicalMemberKey = "F:sample/defaultWithDefaults",
                        physicalView = DotNetInterfaceDefaultPromotionView.CANONICAL,
                        implementationMethodName = "<GenericInterfaceCanonicalBridge-defaultWithDefaults>",
                    ),
            "I:C:sample/Intersection:DECLARED:4b2bc8eaf1471267b878d9c25980804d" to
                    DotNetPhysicalDeclaration.GenericInterfaceIntersectionSlot(
                        ownerPath = listOf("sample.Intersection`1"),
                        ownerLogicalKey = "C:sample/Intersection",
                        contributingLogicalMemberKeys = listOf("F:sample/left", "F:sample/right"),
                        physicalView = DotNetInterfaceDefaultPromotionView.DECLARED,
                        methodName = "read",
                    ),
            "R:C:sample/Contract:F:sample/baseValue" to
                    DotNetPhysicalDeclaration.CovariantReturnBridge(
                        ownerPath = listOf("sample.Contract"),
                        ownerLogicalKey = "C:sample/Contract",
                        inheritedLogicalMemberKey = "F:sample/baseValue",
                        implementationMethodName = "<CovariantReturnBridge-baseValue>",
                    ),
            "W:C:sample/Consumer:F:sample/defaultWithDefaults:CANONICAL" to
                    DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder(
                        ownerPath = listOf("sample.Consumer"),
                        ownerLogicalKey = "C:sample/Consumer",
                        inheritedLogicalMemberKey = "F:sample/defaultWithDefaults",
                        physicalView = DotNetInterfaceDefaultPromotionView.CANONICAL,
                        implementationMethodName = "<InterfaceDefaultForwarder-defaultWithDefaults>",
                    ),
        )
        val properties = Properties().apply {
            setProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY, DotNetLibraryAbiCodec.ABI_VERSION)
            putAll(DotNetLibraryAbiCodec.encode(declarations))
        }

        assertEquals("14", properties.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY))
        assertEquals(declarations, DotNetLibraryAbiCodec.decode(properties))
        assertEquals(
            "be089ff358019a018b5e1ce2af85aedd",
            DotNetLibraryAbiCodec.logicalIdentityDigest("F:sample/foo|-123456789[0]"),
        )
        assertEquals(
            "faa734fbe159d9bc030a0dd498584bc5",
            DotNetLibraryAbiCodec.logicalIdentityDigest("C:kotlin.collections/List"),
        )
        val friendIdentities = setOf(
            DotNetFriendAssemblyIdentity("Unsigned.Consumer"),
            DotNetFriendAssemblyIdentity("Signed.Consumer", "00112233445566778899AABBCCDDEEFF"),
        )
        assertEquals(
            friendIdentities,
            DotNetLibraryAbiCodec.decodeFriendAssemblies(
                DotNetLibraryAbiCodec.encodeFriendAssemblies(friendIdentities)
            ),
        )
    }

    @Test
    fun testDllManifestGeneratesCSharpImplementorsWithoutKlib() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ilasm is not available",
        )
        val csharpToolchain = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(
            csharpToolchain != null,
            "Modern Roslyn and the net10 reference pack are not available",
        )
        val modernCSharp = checkNotNull(csharpToolchain)
        val csharpAuthoringTooling = buildCSharpAuthoringTooling(modernCSharp)
        val readerDirectory = File(tmpdir, "csharp-implementation-manifest-reader").apply { mkdirs() }
        val bootstrapSource = readerDirectory.resolve("bootstrap.kt").apply { writeText("fun main() {}") }
        compileInProcess(
            K2DotNetCompiler(),
            bootstrapSource.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "ManifestReaderBootstrap",
            K2DotNetCompilerArguments::destination.cliArgument,
            readerDirectory.resolve("ManifestReaderBootstrap.dll").path,
        )
        val runtimeAssembly = readerDirectory.resolve("Kotlin.Runtime.dll")
        assertTrue(runtimeAssembly.isFile)

        val readerSource = readerDirectory.resolve("reader.cs").apply {
            writeText(
                """
                using System;
                using System.Collections.Immutable;
                using System.IO;
                using System.Linq;
                using System.Reflection;
                using System.Reflection.Metadata;
                using System.Reflection.PortableExecutable;
                using System.Text;

                public static class Program
                {
                    private static string Encode(string value)
                    {
                        return Convert.ToBase64String(Encoding.UTF8.GetBytes(value));
                    }

                    public static int Main(string[] args)
                    {
                        Assembly assembly = Assembly.LoadFrom(args[0]);
                        foreach (CustomAttributeData attribute in assembly.GetCustomAttributesData())
                        {
                            if (attribute.AttributeType.FullName !=
                                "System.Reflection.AssemblyMetadataAttribute")
                                continue;
                            string key = (string)attribute.ConstructorArguments[0].Value;
                            if (!key.StartsWith(
                                    "${DotNetCSharpImplementationManifestCodec.ASSEMBLY_METADATA_KEY}",
                                    StringComparison.Ordinal))
                                continue;
                            string value = (string)attribute.ConstructorArguments[1].Value;
                            Console.WriteLine(
                                "A|" + Encode(key) + "|" + Encode(value));
                        }

                        using (FileStream stream = File.OpenRead(args[0]))
                        using (PEReader pe = new PEReader(stream))
                        {
                            MetadataReader metadata = pe.GetMetadataReader();
                            TypeProvider provider = new TypeProvider(metadata);
                            foreach (CustomAttributeHandle attributeHandle
                                in metadata.GetAssemblyDefinition().GetCustomAttributes())
                            {
                                CustomAttribute attribute =
                                    metadata.GetCustomAttribute(attributeHandle);
                                TypeIdentity owner = AttributeOwner(
                                    metadata,
                                    provider,
                                    attribute.Constructor);
                                Console.WriteLine(
                                    "C|" +
                                    Encode(owner.AssemblyName) + "|" +
                                    Encode(owner.Path) + "|" +
                                    Encode(Convert.ToBase64String(
                                        metadata.GetBlobBytes(attribute.Value))));
                            }
                            foreach (TypeDefinitionHandle typeHandle in metadata.TypeDefinitions)
                            {
                                TypeDefinition type = metadata.GetTypeDefinition(typeHandle);
                                TypeIdentity typeIdentity =
                                    provider.FromDefinition(typeHandle);
                                Console.WriteLine(
                                    "D|" +
                                    Encode(typeIdentity.Path) + "|" +
                                    Encode(((int)(
                                        type.Attributes &
                                        TypeAttributes.VisibilityMask)).ToString()));
                                foreach (MethodImplementationHandle implementationHandle
                                    in type.GetMethodImplementations())
                                {
                                    MethodImplementation implementation =
                                        metadata.GetMethodImplementation(implementationHandle);
                                    MethodIdentity body = MethodIdentity.Read(
                                        metadata,
                                        provider,
                                        implementation.MethodBody);
                                    MethodIdentity declaration = MethodIdentity.Read(
                                        metadata,
                                        provider,
                                        implementation.MethodDeclaration);
                                    Console.WriteLine(
                                        "P|" +
                                        Encode(body.Owner.Path) + "|" +
                                        Encode(body.Name) + "|" +
                                        Encode(body.IsConcrete.ToString()) + "|" +
                                        Encode(declaration.Owner.AssemblyName) + "|" +
                                        Encode(declaration.Owner.Path) + "|" +
                                        Encode(declaration.Name) + "|" +
                                        Encode(declaration.GenericArity.ToString()) + "|" +
                                        Encode(declaration.ReturnType) + "|" +
                                        Encode(string.Join(
                                            "\u0001",
                                            declaration.ParameterTypes)));
                                }
                                TypeIdentity methodOwner = provider.FromDefinition(typeHandle);
                                foreach (MethodDefinitionHandle methodHandle in type.GetMethods())
                                {
                                    MethodDefinition method = metadata.GetMethodDefinition(methodHandle);
                                    foreach (GenericParameterHandle parameterHandle
                                        in method.GetGenericParameters())
                                    {
                                        GenericParameter parameter =
                                            metadata.GetGenericParameter(parameterHandle);
                                        StringBuilder constraints = new StringBuilder();
                                        foreach (GenericParameterConstraintHandle constraintHandle
                                            in parameter.GetConstraints())
                                        {
                                            GenericParameterConstraint constraint =
                                                metadata.GetGenericParameterConstraint(constraintHandle);
                                            TypeIdentity constraintType =
                                                provider.FromTypeHandle(constraint.Type);
                                            if (constraints.Length != 0)
                                                constraints.Append("\u0001");
                                            constraints.Append(constraintType.AssemblyName);
                                            constraints.Append("\0");
                                            constraints.Append(constraintType.Path);
                                        }
                                        Console.WriteLine(
                                            "G|" +
                                            Encode(methodOwner.Path) + "|" +
                                            Encode(metadata.GetString(method.Name)) + "|" +
                                            Encode(parameter.Index.ToString()) + "|" +
                                            Encode(((int)parameter.Attributes).ToString()) + "|" +
                                            Encode(constraints.ToString()));
                                    }
                                }
                            }
                        }
                        return 0;
                    }

                    private static TypeIdentity AttributeOwner(
                        MetadataReader metadata,
                        TypeProvider provider,
                        EntityHandle constructor)
                    {
                        if (constructor.Kind == HandleKind.MethodDefinition)
                        {
                            MethodDefinition method = metadata.GetMethodDefinition(
                                (MethodDefinitionHandle)constructor);
                            return provider.FromDefinition(method.GetDeclaringType());
                        }
                        if (constructor.Kind == HandleKind.MemberReference)
                        {
                            MemberReference member = metadata.GetMemberReference(
                                (MemberReferenceHandle)constructor);
                            return provider.FromMemberParent(member.Parent);
                        }
                        throw new BadImageFormatException(
                            "Unsupported custom attribute constructor " +
                            constructor.Kind);
                    }
                }

                internal sealed class MethodIdentity
                {
                    internal readonly TypeIdentity Owner;
                    internal readonly string Name;
                    internal readonly int GenericArity;
                    internal readonly string ReturnType;
                    internal readonly ImmutableArray<string> ParameterTypes;
                    internal readonly bool IsConcrete;

                    private MethodIdentity(
                        TypeIdentity owner,
                        string name,
                        int genericArity,
                        string returnType,
                        ImmutableArray<string> parameterTypes,
                        bool isConcrete)
                    {
                        Owner = owner;
                        Name = name;
                        GenericArity = genericArity;
                        ReturnType = returnType;
                        ParameterTypes = parameterTypes;
                        IsConcrete = isConcrete;
                    }

                    internal static MethodIdentity Read(
                        MetadataReader metadata,
                        TypeProvider provider,
                        EntityHandle handle)
                    {
                        if (handle.Kind == HandleKind.MethodDefinition)
                        {
                            MethodDefinition method = metadata.GetMethodDefinition(
                                (MethodDefinitionHandle)handle);
                            MethodSignature<TypeIdentity> signature =
                                method.DecodeSignature(provider, null);
                            return new MethodIdentity(
                                provider.FromDefinition(method.GetDeclaringType()),
                                metadata.GetString(method.Name),
                                signature.GenericParameterCount,
                                signature.ReturnType.Display,
                                signature.ParameterTypes
                                    .Select(type => type.Display)
                                    .ToImmutableArray(),
                                method.RelativeVirtualAddress != 0 &&
                                    (method.Attributes & MethodAttributes.Abstract) == 0);
                        }
                        if (handle.Kind == HandleKind.MemberReference)
                        {
                            MemberReference member = metadata.GetMemberReference(
                                (MemberReferenceHandle)handle);
                            MethodSignature<TypeIdentity> signature =
                                member.DecodeMethodSignature(provider, null);
                            return new MethodIdentity(
                                provider.FromMemberParent(member.Parent),
                                metadata.GetString(member.Name),
                                signature.GenericParameterCount,
                                signature.ReturnType.Display,
                                signature.ParameterTypes
                                    .Select(type => type.Display)
                                    .ToImmutableArray(),
                                false);
                        }
                        if (handle.Kind == HandleKind.MethodSpecification)
                        {
                            MethodSpecification specification = metadata.GetMethodSpecification(
                                (MethodSpecificationHandle)handle);
                            return Read(metadata, provider, specification.Method);
                        }
                        throw new BadImageFormatException(
                            "Unsupported MethodImpl method handle " + handle.Kind);
                    }
                }

                internal sealed class TypeIdentity
                {
                    internal readonly string AssemblyName;
                    internal readonly string Path;
                    internal readonly string Display;

                    internal TypeIdentity(string assemblyName, string path, string display)
                    {
                        AssemblyName = assemblyName;
                        Path = path;
                        Display = display;
                    }

                    internal TypeIdentity WithDisplay(string display)
                    {
                        return new TypeIdentity(AssemblyName, Path, display);
                    }
                }

                internal sealed class TypeProvider :
                    ISignatureTypeProvider<TypeIdentity, object>
                {
                    private readonly MetadataReader metadata;
                    private readonly string currentAssembly;

                    internal TypeProvider(MetadataReader metadata)
                    {
                        this.metadata = metadata;
                        currentAssembly = metadata.GetString(
                            metadata.GetAssemblyDefinition().Name);
                    }

                    internal TypeIdentity FromDefinition(TypeDefinitionHandle handle)
                    {
                        TypeDefinition type = metadata.GetTypeDefinition(handle);
                        string name = metadata.GetString(type.Name);
                        TypeDefinitionHandle declaring = type.GetDeclaringType();
                        string path;
                        if (!declaring.IsNil)
                        {
                            path = FromDefinition(declaring).Path + "\0" + name;
                        }
                        else
                        {
                            string namespaceName = metadata.GetString(type.Namespace);
                            path = namespaceName.Length == 0
                                ? name
                                : namespaceName + "." + name;
                        }
                        return new TypeIdentity(currentAssembly, path, path);
                    }

                    private TypeIdentity FromReference(TypeReferenceHandle handle)
                    {
                        TypeReference type = metadata.GetTypeReference(handle);
                        string name = metadata.GetString(type.Name);
                        EntityHandle scope = type.ResolutionScope;
                        if (scope.Kind == HandleKind.TypeReference)
                        {
                            TypeIdentity declaring = FromReference((TypeReferenceHandle)scope);
                            string path = declaring.Path + "\0" + name;
                            return new TypeIdentity(
                                declaring.AssemblyName,
                                path,
                                path);
                        }
                        string namespaceName = metadata.GetString(type.Namespace);
                        string topLevelPath = namespaceName.Length == 0
                            ? name
                            : namespaceName + "." + name;
                        return new TypeIdentity(
                            AssemblyName(scope),
                            topLevelPath,
                            topLevelPath);
                    }

                    private string AssemblyName(EntityHandle scope)
                    {
                        if (scope.Kind == HandleKind.AssemblyReference)
                        {
                            return metadata.GetString(
                                metadata.GetAssemblyReference(
                                    (AssemblyReferenceHandle)scope).Name);
                        }
                        if (scope.Kind == HandleKind.TypeReference)
                            return FromReference((TypeReferenceHandle)scope).AssemblyName;
                        return currentAssembly;
                    }

                    internal TypeIdentity FromMemberParent(EntityHandle handle)
                    {
                        if (handle.Kind == HandleKind.TypeDefinition)
                            return FromDefinition((TypeDefinitionHandle)handle);
                        if (handle.Kind == HandleKind.TypeReference)
                            return FromReference((TypeReferenceHandle)handle);
                        if (handle.Kind == HandleKind.TypeSpecification)
                        {
                            return metadata.GetTypeSpecification(
                                (TypeSpecificationHandle)handle).DecodeSignature(this, null);
                        }
                        throw new BadImageFormatException(
                            "Unsupported MemberRef parent " + handle.Kind);
                    }

                    internal TypeIdentity FromTypeHandle(EntityHandle handle)
                    {
                        if (handle.Kind == HandleKind.TypeDefinition)
                            return FromDefinition((TypeDefinitionHandle)handle);
                        if (handle.Kind == HandleKind.TypeReference)
                            return FromReference((TypeReferenceHandle)handle);
                        if (handle.Kind == HandleKind.TypeSpecification)
                        {
                            return metadata.GetTypeSpecification(
                                (TypeSpecificationHandle)handle).DecodeSignature(this, null);
                        }
                        throw new BadImageFormatException(
                            "Unsupported type handle " + handle.Kind);
                    }

                    public TypeIdentity GetArrayType(
                        TypeIdentity elementType,
                        ArrayShape shape)
                    {
                        return elementType.WithDisplay(elementType.Display + "[*]");
                    }

                    public TypeIdentity GetByReferenceType(TypeIdentity elementType)
                    {
                        return elementType.WithDisplay(elementType.Display + "&");
                    }

                    public TypeIdentity GetFunctionPointerType(
                        MethodSignature<TypeIdentity> signature)
                    {
                        return new TypeIdentity(
                            currentAssembly,
                            "",
                            "methodptr");
                    }

                    public TypeIdentity GetGenericInstantiation(
                        TypeIdentity genericType,
                        ImmutableArray<TypeIdentity> typeArguments)
                    {
                        StringBuilder display = new StringBuilder(genericType.Display);
                        display.Append("<");
                        for (int index = 0; index < typeArguments.Length; index++)
                        {
                            if (index != 0)
                                display.Append(",");
                            display.Append(typeArguments[index].Display);
                        }
                        display.Append(">");
                        return genericType.WithDisplay(display.ToString());
                    }

                    public TypeIdentity GetGenericMethodParameter(
                        object genericContext,
                        int index)
                    {
                        return new TypeIdentity(currentAssembly, "", "!!" + index);
                    }

                    public TypeIdentity GetGenericTypeParameter(
                        object genericContext,
                        int index)
                    {
                        return new TypeIdentity(currentAssembly, "", "!" + index);
                    }

                    public TypeIdentity GetModifiedType(
                        TypeIdentity modifier,
                        TypeIdentity unmodifiedType,
                        bool isRequired)
                    {
                        return unmodifiedType;
                    }

                    public TypeIdentity GetPinnedType(TypeIdentity elementType)
                    {
                        return elementType;
                    }

                    public TypeIdentity GetPointerType(TypeIdentity elementType)
                    {
                        return elementType.WithDisplay(elementType.Display + "*");
                    }

                    public TypeIdentity GetPrimitiveType(PrimitiveTypeCode typeCode)
                    {
                        string signature;
                        switch (typeCode)
                        {
                            case PrimitiveTypeCode.Boolean:
                                signature = "bool";
                                break;
                            case PrimitiveTypeCode.Byte:
                                signature = "uint8";
                                break;
                            case PrimitiveTypeCode.SByte:
                                signature = "int8";
                                break;
                            case PrimitiveTypeCode.Char:
                                signature = "char";
                                break;
                            case PrimitiveTypeCode.Int16:
                                signature = "int16";
                                break;
                            case PrimitiveTypeCode.UInt16:
                                signature = "uint16";
                                break;
                            case PrimitiveTypeCode.Int32:
                                signature = "int32";
                                break;
                            case PrimitiveTypeCode.UInt32:
                                signature = "uint32";
                                break;
                            case PrimitiveTypeCode.Int64:
                                signature = "int64";
                                break;
                            case PrimitiveTypeCode.UInt64:
                                signature = "uint64";
                                break;
                            case PrimitiveTypeCode.Single:
                                signature = "float32";
                                break;
                            case PrimitiveTypeCode.Double:
                                signature = "float64";
                                break;
                            case PrimitiveTypeCode.String:
                                signature = "string";
                                break;
                            case PrimitiveTypeCode.Object:
                                signature = "object";
                                break;
                            case PrimitiveTypeCode.IntPtr:
                                signature = "native int";
                                break;
                            case PrimitiveTypeCode.UIntPtr:
                                signature = "native uint";
                                break;
                            case PrimitiveTypeCode.TypedReference:
                                signature = "typedref";
                                break;
                            case PrimitiveTypeCode.Void:
                                signature = "void";
                                break;
                            default:
                                throw new BadImageFormatException(
                                    "Unsupported primitive type " + typeCode);
                        }
                        return new TypeIdentity(
                            currentAssembly,
                            "",
                            signature);
                    }

                    public TypeIdentity GetSZArrayType(TypeIdentity elementType)
                    {
                        return elementType.WithDisplay(elementType.Display + "[]");
                    }

                    public TypeIdentity GetTypeFromDefinition(
                        MetadataReader reader,
                        TypeDefinitionHandle handle,
                        byte rawTypeKind)
                    {
                        return SignatureType(
                            FromDefinition(handle),
                            rawTypeKind);
                    }

                    public TypeIdentity GetTypeFromReference(
                        MetadataReader reader,
                        TypeReferenceHandle handle,
                        byte rawTypeKind)
                    {
                        return SignatureType(
                            FromReference(handle),
                            rawTypeKind);
                    }

                    public TypeIdentity GetTypeFromSpecification(
                        MetadataReader reader,
                        object genericContext,
                        TypeSpecificationHandle handle,
                        byte rawTypeKind)
                    {
                        return reader.GetTypeSpecification(handle)
                            .DecodeSignature(this, genericContext);
                    }

                    private TypeIdentity SignatureType(
                        TypeIdentity type,
                        byte rawTypeKind)
                    {
                        string prefix;
                        if (rawTypeKind == 0x11)
                        {
                            prefix = "valuetype ";
                        }
                        else if (rawTypeKind == 0x12)
                        {
                            prefix = "class ";
                        }
                        else
                        {
                            throw new BadImageFormatException(
                                "Unsupported raw type kind " + rawTypeKind);
                        }
                        string assembly = type.AssemblyName == currentAssembly
                            ? ""
                            : "[" + type.AssemblyName + "]";
                        string path = string.Join(
                            "/",
                            type.Path.Split('\0').Select(Identifier));
                        return type.WithDisplay(prefix + assembly + path);
                    }

                    private static string Identifier(string value)
                    {
                        return "'" +
                            value.Replace("\\", "\\\\").Replace("'", "\\'") +
                            "'";
                    }
                }
                """.trimIndent()
            )
        }
        val readerAssembly = readerDirectory.resolve("ManifestReader.dll")
        val readerCompile = runModernCSharpCompiler(
            modernCSharp,
            readerSource,
            readerAssembly,
            target = "exe",
        )
        assertEquals(0, readerCompile.exitCode, readerCompile.output)
        readerDirectory.resolve("ManifestReader.runtimeconfig.json").writeText(net10RuntimeConfig())
        val runtimeManifest = readCSharpImplementationManifestFromDll(
            modernCSharp,
            readerAssembly,
            readerDirectory,
            runtimeAssembly,
        )
        assertEquals("Kotlin.Runtime", runtimeManifest.assemblyName)
        assertEquals("net10.0", runtimeManifest.targetProfile)
        assertEquals(
            DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
            runtimeManifest.logicalIdentityScheme,
        )
        assertEquals(5, runtimeManifest.interfaces.size)
        assertTrue(runtimeManifest.interfaces.all { contract ->
            contract.logicalKey.startsWith("C:") &&
                    !contract.logicalKey.startsWith("runtime:") &&
                    contract.members.all { member ->
                        member.logicalKey.startsWith("F:") &&
                                !member.logicalKey.startsWith("runtime:")
                    }
        })
        val runtimeCollectionContract = runtimeManifest.interfaces.single { contract ->
            contract.canonicalOwnerPath.last() == "Kotlin.Collections.Collection"
        }
        val runtimeListContract = runtimeManifest.interfaces.single { contract ->
            contract.canonicalOwnerPath.last() == "Kotlin.Collections.List"
        }
        val runtimeIterableContract = runtimeManifest.interfaces.single { contract ->
            contract.canonicalOwnerPath.last() == "Kotlin.Collections.Iterable"
        }
        assertTrue(runtimeCollectionContract.sourceAuthoringSupported)
        assertTrue(runtimeListContract.sourceAuthoringSupported)
        assertEquals(
            DotNetCSharpWrongShapeFallback.FALSE,
            runtimeCollectionContract.members.single { member ->
                member.sourceName == "contains"
            }.wrongShapePolicy?.fallback,
        )
        assertEquals(
            DotNetCSharpWrongShapeFallback.MINUS_ONE,
            runtimeListContract.members.single { member ->
                member.sourceName == "indexOf"
            }.wrongShapePolicy?.fallback,
        )
        val runtimeContractsByProfile = DotNetTarget.entries.associateWith { target ->
            val assembly = if (target == DotNetTarget.NET10_0) {
                runtimeAssembly
            } else {
                val profileDirectory =
                    File(tmpdir, "runtime-manifest-${target.flagValue}").apply { mkdirs() }
                checkNotNull(
                    DotNetIlAssembler.assembleRuntimeWithManifestForTests(
                        profileDirectory,
                        target,
                        runtimeManifest.copy(targetProfile = target.flagValue),
                        MessageCollector.NONE,
                    )
                )
            }
            val profileReaderDirectory = checkNotNull(assembly.parentFile)
            val profileReader = if (profileReaderDirectory == readerDirectory) {
                readerAssembly
            } else {
                readerAssembly.copyTo(
                    profileReaderDirectory.resolve(readerAssembly.name),
                    overwrite = true,
                ).also {
                    readerDirectory.resolve("ManifestReader.runtimeconfig.json").copyTo(
                        profileReaderDirectory.resolve("ManifestReader.runtimeconfig.json"),
                        overwrite = true,
                    )
                }
            }
            readCSharpImplementationManifestFromDll(
                modernCSharp,
                profileReader,
                profileReaderDirectory,
                assembly,
            ).also { manifest ->
                assertEquals(target.flagValue, manifest.targetProfile)
                assertEquals(
                    DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
                    manifest.logicalIdentityScheme,
                )
            }
        }
        val modernRuntimeContracts = runtimeContractsByProfile
            .getValue(DotNetTarget.NET10_0)
            .interfaces
        for (portableTarget in listOf(DotNetTarget.NET48, DotNetTarget.NETSTANDARD_2_0)) {
            assertEquals(
                modernRuntimeContracts,
                runtimeContractsByProfile.getValue(portableTarget).interfaces,
            )
        }

        val parentDeclarationText = """
            public interface ManifestMarker

            public interface OwnerBound<out T> {
                public fun <R : @UnsafeVariance T> retain(value: R): R
            }

            public interface OwnerBoundLeft<out T> {
                public fun <R : @UnsafeVariance T> retainBoth(value: R): R
            }

            public interface OwnerBoundRight<out T> {
                public fun <R : @UnsafeVariance T> retainBoth(value: R): R
            }

            public interface OrdinaryParent {
                public val displayName: String
                public fun fallbackName(): String = displayName
            }

            public interface ShapeRoot<out T> {
                public val value: T
                public fun fallback(): T = value
            }

            public interface ShapeParent<out T> : ShapeRoot<T> {
                public var label: String
            }

            public interface ShapeSibling<out T> : ShapeRoot<T> {
                public val secondary: T
            }

            public interface IntersectionLeft<out T> {
                public fun overlap(): T
            }

            public interface IntersectionRight<out T> {
                public fun overlap(): T
            }

            public interface MutableLeft<out T> {
                public var merged: @UnsafeVariance T
            }

            public interface MutableRight<out T> {
                public var merged: @UnsafeVariance T
            }
        """.trimIndent()
        val childDeclarationText = """
            public interface OrdinaryShape : OrdinaryParent {
                public var count: Int
                public fun format(prefix: String): String
            }

            public interface BarrierShape<out T> : Collection<T> {
                override fun contains(element: @UnsafeVariance T): Boolean
            }

            public interface SearchBarrier<out T> : List<T> {
                override fun indexOf(element: @UnsafeVariance T): Int
            }

            internal interface FriendShape {
                public val code: Int
                public fun fallbackCode(): Int = code
            }

            internal class FriendContainer {
                public interface NestedShape {
                    public val nestedCode: Int
                }

                public interface NestedGeneric<out T> {
                    public val nestedValue: T
                }

                private interface HiddenShape {
                    public val hiddenCode: Int
                }
            }

            @PublishedApi
            internal interface PublishedInternalShape {
                public val compilerCode: Int
            }

            public interface Shape<out T> : ShapeParent<T>, ShapeSibling<T> {
                public fun <R : ManifestMarker> map(input: R): T
                public fun accepts(input: @UnsafeVariance T): Boolean
            }

            private object Marker : ManifestMarker

            public interface ResolvedIntersection<out T> :
                IntersectionLeft<T>, IntersectionRight<T>

            public interface ResolvedMutable<out T> :
                MutableLeft<T>, MutableRight<T>

            public interface ResolvedOwnerBound<out T> :
                OwnerBoundLeft<T>, OwnerBoundRight<T>

            public fun verifyOrdinary(value: OrdinaryShape): Int {
                if (value.displayName != "ordinary") return 1
                if (value.count != 3) return 2
                if (value.format("value:") != "value:ordinary") return 3
                if (value.fallbackName() != "ordinary") return 4
                value.count = 7
                return if (value.count == 7) 0 else 5
            }

            public fun verifyIntersection(value: ResolvedIntersection<String>): Int {
                val left: IntersectionLeft<String> = value
                val right: IntersectionRight<String> = value
                return if (
                    value.overlap() == "intersection" &&
                    left.overlap() == "intersection" &&
                    right.overlap() == "intersection"
                ) 0 else 1
            }

            public fun verifyMutable(value: ResolvedMutable<String>): Int {
                val left: MutableLeft<String> = value
                val right: MutableRight<String> = value
                if (value.merged != "mutable" || left.merged != "mutable" ||
                    right.merged != "mutable"
                ) return 1
                left.merged = "left"
                if (value.merged != "left" || right.merged != "left") return 2
                right.merged = "right"
                return if (value.merged == "right" && left.merged == "right") 0 else 3
            }

            public fun verifyBarrier(value: BarrierShape<String>): Int {
                if (!value.contains("typed")) return 1
                val wide: Collection<Any?> = value
                if (!wide.contains("typed")) return 2
                if (wide.contains(42) || wide.contains(null)) return 3
                return 0
            }

            public fun verifySearchBarrier(value: SearchBarrier<String>): Int {
                if (value.indexOf("typed") != 0) return 1
                val wide: List<Any?> = value
                if (wide.indexOf("typed") != 0) return 2
                if (wide.indexOf(42) != -1 || wide.indexOf(null) != -1) return 3
                return 0
            }

            internal fun verifyFriend(value: FriendShape): Int =
                if (value.code == 41 && value.fallbackCode() == 41) 0 else 1

            internal fun verifyNestedFriend(value: FriendContainer.NestedShape): Int =
                if (value.nestedCode == 42) 0 else 1

            internal fun verifyNestedGeneric(
                value: FriendContainer.NestedGeneric<String>
            ): Int = if (value.nestedValue == "nested") 0 else 1

            public fun verify(value: Shape<String>): Int {
                if (value.value != "typed") return 1
                if (value.secondary != "secondary") return 2
                if (value.map(Marker) != "typed") return 3
                if (!value.accepts("typed")) return 4
                if (value.fallback() != "typed") return 5
                if (value.label != "initial") return 6
                value.label = "changed"
                val wide: Shape<Any?> = value
                if (wide.value != "typed" || wide.secondary != "secondary") return 7
                if (wide.map(Marker) != "typed") return 8
                if (wide.fallback() != "typed") return 9
                if (wide.label != "changed") return 10
                wide.label = "wide"
                if (value.label != "wide") return 11
                try {
                    wide.accepts(42)
                    return 12
                } catch (_: ClassCastException) {
                    return 0
                }
            }

            public fun verifyInt(value: Shape<Int>): Int {
                if (value.value != 42) return 1
                if (value.secondary != 43) return 2
                if (value.map(Marker) != 42) return 3
                if (!value.accepts(42)) return 4
                if (value.fallback() != 42) return 5
                val wide: Shape<Any?> = value
                if (wide.value != 42 || wide.secondary != 43) return 6
                if (wide.map(Marker) != 42 || wide.fallback() != 42) return 7
                try {
                    wide.accepts("wrong")
                    return 8
                } catch (_: ClassCastException) {
                    return 0
                }
            }

            public fun verifyOwnerBound(value: OwnerBound<ManifestMarker>): Int =
                if (value.retain(Marker) === Marker) 0 else 1

            public fun verifyResolvedOwnerBound(
                value: ResolvedOwnerBound<ManifestMarker>
            ): Int {
                val left: OwnerBoundLeft<ManifestMarker> = value
                val right: OwnerBoundRight<ManifestMarker> = value
                if (value.retainBoth(Marker) !== Marker) return 1
                if (left.retainBoth(Marker) !== Marker) return 2
                return if (right.retainBoth(Marker) === Marker) 0 else 3
            }
        """.trimIndent()

        data class ManifestScenario(
            val name: String,
            val childProfile: String,
            val parentProfile: String = childProfile,
            val externalParent: Boolean = true,
        )

        val scenarios = listOf(
            ManifestScenario("net48", "net48", externalParent = false),
            ManifestScenario("netstandard2.0", "netstandard2.0"),
            ManifestScenario("net10.0", "net10.0"),
            ManifestScenario(
                name = "net10.0-promoted-portable-parent",
                childProfile = "net10.0",
                parentProfile = "netstandard2.0",
            ),
        )
        val contractsByProfile = linkedMapOf<String, List<DotNetCSharpInterfaceContract>>()
        for (scenario in scenarios) {
            val targetProfile = scenario.childProfile
            val profileDirectory = File(tmpdir, "csharp-implementation-${scenario.name}").apply { mkdirs() }
            val externalParent = scenario.externalParent
            val parentModuleName = when {
                !externalParent -> "Manifest.Parent.Portable"
                scenario.parentProfile == "net10.0" -> "Manifest.Parent.Modern"
                scenario.childProfile == "net10.0" -> "Manifest.Parent.CrossProfile"
                else -> "Manifest.Parent.Portable"
            }
            val parentMetadata = if (externalParent) {
                val parentSource = profileDirectory.resolve("parent.kt").apply {
                    writeText("package manifest\n\n$parentDeclarationText")
                }
                compileInProcess(
                    K2DotNetCompiler(),
                    parentSource.path,
                    K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                    K2DotNetCompilerArguments::dotNetTarget.cliArgument, scenario.parentProfile,
                    K2DotNetCompilerArguments::moduleName.cliArgument, parentModuleName,
                    K2DotNetCompilerArguments::destination.cliArgument, profileDirectory.path,
                )
                profileDirectory.resolve("$parentModuleName.klib")
            } else {
                null
            }
            val source = profileDirectory.resolve("api.kt").apply {
                val declarations = if (externalParent) {
                    childDeclarationText
                } else {
                    "$parentDeclarationText\n\n$childDeclarationText"
                }
                writeText("package manifest\n\n$declarations")
            }
            val moduleName = when (scenario.name) {
                "net10.0" -> "Manifest.Modern"
                "net10.0-promoted-portable-parent" -> "Manifest.Promoted"
                else -> "Manifest.Portable"
            }
            if (parentMetadata == null) {
                compileInProcess(
                    K2DotNetCompiler(),
                    source.path,
                    K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                    K2DotNetCompilerArguments::dotNetTarget.cliArgument, targetProfile,
                    K2DotNetCompilerArguments::moduleName.cliArgument, moduleName,
                    "${K2DotNetCompilerArguments::dotNetFriendAssemblies.cliArgument}=GeneratedShape",
                    K2DotNetCompilerArguments::destination.cliArgument, profileDirectory.path,
                )
            } else {
                compileInProcess(
                    K2DotNetCompiler(),
                    source.path,
                    K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                    K2DotNetCompilerArguments::classpath.cliArgument, parentMetadata.path,
                    K2DotNetCompilerArguments::dotNetTarget.cliArgument, targetProfile,
                    K2DotNetCompilerArguments::moduleName.cliArgument, moduleName,
                    "${K2DotNetCompilerArguments::dotNetFriendAssemblies.cliArgument}=GeneratedShape",
                    K2DotNetCompilerArguments::destination.cliArgument, profileDirectory.path,
                )
            }
            val producerAssembly = profileDirectory.resolve("$moduleName.dll")
            val producerKlib = profileDirectory.resolve("$moduleName.klib")
            assertTrue(producerAssembly.isFile && producerKlib.isFile)
            assertTrue(producerKlib.delete()) { "The no-KLIB manifest test could not remove $producerKlib" }
            val parentAssembly = parentMetadata?.let { metadata ->
                assertTrue(metadata.isFile)
                assertTrue(metadata.delete()) { "The no-KLIB manifest test could not remove $metadata" }
                profileDirectory.resolve("$parentModuleName.dll").also { assembly ->
                    assertTrue(assembly.isFile)
                }
            }

            val manifest = readCSharpImplementationManifestFromDll(
                modernCSharp,
                readerAssembly,
                readerDirectory,
                producerAssembly,
            )
            assertEquals(moduleName, manifest.assemblyName)
            assertEquals(targetProfile, manifest.targetProfile)
            assertEquals(
                DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
                manifest.logicalIdentityScheme,
            )
            val parentManifest = parentAssembly?.let { assembly ->
                readCSharpImplementationManifestFromDll(
                    modernCSharp,
                    readerAssembly,
                    readerDirectory,
                    assembly,
                )
            } ?: manifest

            val contract = manifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.Shape"
            }
            val markerContract = parentManifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.ManifestMarker"
            }
            val ordinaryParentContract = parentManifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.OrdinaryParent"
            }
            val ownerBoundContract = parentManifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.OwnerBound"
            }
            val ownerBoundLeftContract = parentManifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.OwnerBoundLeft"
            }
            val ownerBoundRightContract = parentManifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.OwnerBoundRight"
            }
            val ordinaryContract = manifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.OrdinaryShape"
            }
            val barrierContract = manifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.BarrierShape"
            }
            val searchBarrierContract = manifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.SearchBarrier"
            }
            val friendContract = manifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.FriendShape"
            }
            val nestedFriendContract = manifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "NestedShape"
            }
            val nestedGenericFriendContract =
                manifest.interfaces.single { interfaceContract ->
                    interfaceContract.canonicalOwnerPath.last() ==
                            "NestedGeneric"
                }
            assertTrue(manifest.interfaces.none { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "HiddenShape" ||
                        interfaceContract.canonicalOwnerPath.last() ==
                        "manifest.PublishedInternalShape"
            })
            val assemblyAttributes =
                readCSharpPhysicalAssemblyAttributesFromDll(
                    modernCSharp,
                    readerAssembly,
                    readerDirectory,
                    producerAssembly,
                )
            val friendAttribute = assemblyAttributes.single { attribute ->
                attribute.ownerPath ==
                        listOf(
                            "System.Runtime.CompilerServices." +
                                    "InternalsVisibleToAttribute"
                        )
            }
            val friendAssemblyName = "GeneratedShape"
            val expectedFriendBlob =
                byteArrayOf(
                    0x01,
                    0x00,
                    friendAssemblyName.length.toByte(),
                ) +
                        friendAssemblyName.toByteArray(Charsets.UTF_8) +
                        byteArrayOf(0x00, 0x00)
            assertTrue(friendAttribute.value.contentEquals(expectedFriendBlob)) {
                "Unexpected InternalsVisibleTo blob for $moduleName: " +
                        friendAttribute.value.joinToString(" ") { value ->
                            "%02x".format(value)
                        }
            }

            val physicalTypes = readCSharpPhysicalTypeDefinitionsFromDll(
                modernCSharp,
                readerAssembly,
                readerDirectory,
                producerAssembly,
            ).associateBy(CSharpPhysicalTypeDefinition::ownerPath)
            assertEquals(
                0,
                physicalTypes.getValue(friendContract.canonicalOwnerPath).visibility,
                "An ordinary internal interface must be a non-public top-level TypeDef",
            )
            val friendContainerPath =
                nestedFriendContract.canonicalOwnerPath.dropLast(1)
            assertEquals(
                0,
                physicalTypes.getValue(friendContainerPath).visibility,
                "The containing internal class must be a non-public top-level TypeDef",
            )
            assertEquals(
                2,
                physicalTypes.getValue(
                    nestedFriendContract.canonicalOwnerPath
                ).visibility,
                "A public nested interface must remain NestedPublic inside its internal owner",
            )
            val nestedGenericOwners = buildSet {
                add(nestedGenericFriendContract.canonicalOwnerPath)
                nestedGenericFriendContract.declaredOwnerPath?.let(::add)
                nestedGenericFriendContract.exactOwnerPath?.let(::add)
            }
            assertTrue(nestedGenericOwners.isNotEmpty())
            nestedGenericOwners.forEach { ownerPath ->
                assertEquals(
                    2,
                    physicalTypes.getValue(ownerPath).visibility,
                    "Every nested generic view must remain NestedPublic inside its internal owner",
                )
            }
            assertEquals(
                3,
                physicalTypes.getValue(
                    friendContainerPath + "HiddenShape"
                ).visibility,
                "A private nested interface must remain NestedPrivate",
            )
            assertEquals(
                1,
                physicalTypes.getValue(
                    listOf("manifest.PublishedInternalShape")
                ).visibility,
                "@PublishedApi internal compiler ABI must remain physically public",
            )
            val rootContract = parentManifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.ShapeRoot"
            }
            val parentContract = parentManifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.ShapeParent"
            }
            val siblingContract = parentManifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.ShapeSibling"
            }
            val leftContract = parentManifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.IntersectionLeft"
            }
            val rightContract = parentManifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.IntersectionRight"
            }
            val mutableLeftContract = parentManifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.MutableLeft"
            }
            val mutableRightContract = parentManifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.MutableRight"
            }
            val resolvedIntersection = manifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.ResolvedIntersection"
            }
            val resolvedMutable = manifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.ResolvedMutable"
            }
            val resolvedOwnerBound = manifest.interfaces.single { interfaceContract ->
                interfaceContract.canonicalOwnerPath.last() == "manifest.ResolvedOwnerBound"
            }
            assertTrue(contract.sourceAuthoringSupported, contract.unsupportedReasons.joinToString())
            assertTrue(markerContract.sourceAuthoringSupported)
            assertTrue(ordinaryParentContract.sourceAuthoringSupported)
            assertTrue(ownerBoundContract.sourceAuthoringSupported)
            assertTrue(ownerBoundLeftContract.sourceAuthoringSupported)
            assertTrue(ownerBoundRightContract.sourceAuthoringSupported)
            assertTrue(ordinaryContract.sourceAuthoringSupported)
            assertTrue(barrierContract.sourceAuthoringSupported)
            assertTrue(barrierContract.unsupportedReasons.isEmpty())
            assertTrue(searchBarrierContract.sourceAuthoringSupported)
            assertTrue(searchBarrierContract.unsupportedReasons.isEmpty())
            assertTrue(friendContract.sourceAuthoringSupported)
            assertTrue(friendContract.unsupportedReasons.isEmpty())
            assertTrue(nestedFriendContract.sourceAuthoringSupported)
            assertTrue(nestedFriendContract.unsupportedReasons.isEmpty())
            assertTrue(nestedGenericFriendContract.sourceAuthoringSupported)
            assertTrue(nestedGenericFriendContract.unsupportedReasons.isEmpty())
            assertTrue(rootContract.sourceAuthoringSupported, rootContract.unsupportedReasons.joinToString())
            assertTrue(parentContract.sourceAuthoringSupported, parentContract.unsupportedReasons.joinToString())
            assertTrue(siblingContract.sourceAuthoringSupported, siblingContract.unsupportedReasons.joinToString())
            assertTrue(leftContract.sourceAuthoringSupported, leftContract.unsupportedReasons.joinToString())
            assertTrue(rightContract.sourceAuthoringSupported, rightContract.unsupportedReasons.joinToString())
            assertTrue(
                resolvedIntersection.sourceAuthoringSupported,
                resolvedIntersection.unsupportedReasons.joinToString(),
            )
            assertTrue(mutableLeftContract.sourceAuthoringSupported)
            assertTrue(mutableRightContract.sourceAuthoringSupported)
            assertTrue(resolvedMutable.sourceAuthoringSupported)
            assertTrue(resolvedOwnerBound.sourceAuthoringSupported)
            val intersection = resolvedIntersection.intersections.single()
            assertEquals("overlap", intersection.sourceName)
            assertEquals(DotNetCSharpMemberKind.METHOD, intersection.kind)
            assertEquals(DotNetCSharpInterfaceView.DECLARED, intersection.authoringView)
            assertEquals(
                setOf(
                    leftContract.members.single().logicalKey,
                    rightContract.members.single().logicalKey,
                ),
                intersection.contributingLogicalMemberKeys.toSet(),
            )
            assertEquals(
                listOf(DotNetCSharpSlotRole.DECLARED),
                intersection.slots.map { slot -> slot.role },
            )
            val mutableGetter = resolvedMutable.intersections.single { candidate ->
                candidate.kind == DotNetCSharpMemberKind.PROPERTY_GETTER
            }
            val mutableSetter = resolvedMutable.intersections.single { candidate ->
                candidate.kind == DotNetCSharpMemberKind.PROPERTY_SETTER
            }
            assertEquals(DotNetCSharpInterfaceView.EXACT, mutableGetter.authoringView)
            assertEquals(DotNetCSharpInterfaceView.EXACT, mutableSetter.authoringView)
            assertEquals(
                listOf(DotNetCSharpSlotRole.DECLARED, DotNetCSharpSlotRole.EXACT),
                mutableGetter.slots.map { slot -> slot.role }.sorted(),
            )
            assertEquals(
                listOf(DotNetCSharpSlotRole.EXACT),
                mutableSetter.slots.map { slot -> slot.role },
            )
            assertEquals(
                mutableGetter.slots.single { slot -> slot.role == DotNetCSharpSlotRole.EXACT }
                    .propertyName,
                mutableSetter.slots.single().propertyName,
            )
            assertEquals(if (externalParent) 10 else 22, manifest.interfaces.size)
            assertEquals(if (externalParent) 12 else 22, parentManifest.interfaces.size)
            if (scenario.name in setOf("net48", "netstandard2.0", "net10.0")) {
                contractsByProfile[scenario.name] =
                    listOf(
                        markerContract,
                        ownerBoundContract,
                        ownerBoundLeftContract,
                        ownerBoundRightContract,
                        ordinaryParentContract,
                        ordinaryContract,
                        barrierContract,
                        searchBarrierContract,
                        friendContract,
                        nestedFriendContract,
                        nestedGenericFriendContract,
                        rootContract,
                        parentContract,
                        siblingContract,
                        leftContract,
                        rightContract,
                        mutableLeftContract,
                        mutableRightContract,
                        contract,
                        resolvedIntersection,
                        resolvedMutable,
                        resolvedOwnerBound,
                    )
                        .sortedBy(DotNetCSharpInterfaceContract::logicalKey)
            }
            assertEquals(listOf("T"), contract.typeParameters.map { it.name })
            assertTrue(markerContract.typeParameters.isEmpty())
            assertEquals(listOf("T"), ownerBoundContract.typeParameters.map { it.name })
            assertTrue(ordinaryContract.typeParameters.isEmpty())
            assertEquals(null, markerContract.declaredOwnerPath)
            assertEquals(null, markerContract.exactOwnerPath)
            assertTrue(markerContract.members.isEmpty())
            assertEquals(null, ordinaryParentContract.declaredOwnerPath)
            assertEquals(null, ordinaryParentContract.exactOwnerPath)
            assertEquals(null, ordinaryContract.declaredOwnerPath)
            assertEquals(null, ordinaryContract.exactOwnerPath)
            assertEquals(listOf("manifest.Shape`1"), contract.declaredOwnerPath)
            assertEquals(listOf("manifest.Shape__KotlinExact`1"), contract.exactOwnerPath)
            assertEquals(listOf("manifest.ShapeRoot`1"), rootContract.declaredOwnerPath)
            assertEquals(listOf("manifest.ShapeParent`1"), parentContract.declaredOwnerPath)
            assertEquals(listOf("manifest.ShapeSibling`1"), siblingContract.declaredOwnerPath)
            assertEquals(null, rootContract.exactOwnerPath)
            assertEquals(null, parentContract.exactOwnerPath)
            assertEquals(null, siblingContract.exactOwnerPath)

            val stdlibAssembly = profileDirectory.resolve("Kotlin.Stdlib.dll")
            if (stdlibAssembly.isFile) {
                val stdlibManifest = readCSharpImplementationManifestFromDll(
                    modernCSharp,
                    readerAssembly,
                    readerDirectory,
                    stdlibAssembly,
                )
                assertTrue(stdlibManifest.interfaces.none { interfaceContract ->
                    interfaceContract.canonicalOwnerPath.lastOrNull() == "manifest.Shape"
                })
            }

            val value = rootContract.members.single { member ->
                member.sourceName == "value" && member.kind == DotNetCSharpMemberKind.PROPERTY_GETTER
            }
            val labelGetter = parentContract.members.single { member ->
                member.sourceName == "label" && member.kind == DotNetCSharpMemberKind.PROPERTY_GETTER
            }
            val labelSetter = parentContract.members.single { member ->
                member.sourceName == "label" && member.kind == DotNetCSharpMemberKind.PROPERTY_SETTER
            }
            val secondary = siblingContract.members.single { member ->
                member.sourceName == "secondary" &&
                        member.kind == DotNetCSharpMemberKind.PROPERTY_GETTER
            }
            val map = contract.members.single { member -> member.sourceName == "map" }
            val accepts = contract.members.single { member -> member.sourceName == "accepts" }
            val retain = ownerBoundContract.members.single { member ->
                member.sourceName == "retain"
            }
            val retainBoth = resolvedOwnerBound.intersections.single { intersectionContract ->
                intersectionContract.sourceName == "retainBoth"
            }
            val barrierContains = barrierContract.members.single { member ->
                member.sourceName == "contains"
            }
            val barrierIndexOf = searchBarrierContract.members.single { member ->
                member.sourceName == "indexOf"
            }
            val fallback = rootContract.members.single { member -> member.sourceName == "fallback" }
            val ordinaryDisplayName = ordinaryParentContract.members.single { member ->
                member.sourceName == "displayName" &&
                        member.kind == DotNetCSharpMemberKind.PROPERTY_GETTER
            }
            val ordinaryFallback = ordinaryParentContract.members.single { member ->
                member.sourceName == "fallbackName"
            }
            val ordinaryCountGetter = ordinaryContract.members.single { member ->
                member.sourceName == "count" &&
                        member.kind == DotNetCSharpMemberKind.PROPERTY_GETTER
            }
            val ordinaryCountSetter = ordinaryContract.members.single { member ->
                member.sourceName == "count" &&
                        member.kind == DotNetCSharpMemberKind.PROPERTY_SETTER
            }
            val ordinaryFormat = ordinaryContract.members.single { member ->
                member.sourceName == "format"
            }
            val ordinaryMembers = listOf(
                ordinaryDisplayName,
                ordinaryFallback,
                ordinaryCountGetter,
                ordinaryCountSetter,
                ordinaryFormat,
            )
            assertTrue(ordinaryMembers.all { member ->
                member.authoringView == DotNetCSharpInterfaceView.CANONICAL &&
                        member.slots.count { slot ->
                            slot.role == DotNetCSharpSlotRole.CANONICAL
                        } == 1 &&
                        member.slots.none { slot ->
                            slot.role == DotNetCSharpSlotRole.ERASED ||
                                    slot.role == DotNetCSharpSlotRole.DECLARED ||
                                    slot.role == DotNetCSharpSlotRole.EXACT
                        }
            })
            assertEquals(
                ordinaryCountGetter.slots.single {
                    it.role == DotNetCSharpSlotRole.CANONICAL
                }.propertyName,
                ordinaryCountSetter.slots.single {
                    it.role == DotNetCSharpSlotRole.CANONICAL
                }.propertyName,
            )
            assertEquals(DotNetCSharpInterfaceView.DECLARED, value.authoringView)
            assertEquals(DotNetCSharpInterfaceView.DECLARED, labelGetter.authoringView)
            assertEquals(DotNetCSharpInterfaceView.DECLARED, labelSetter.authoringView)
            assertEquals(DotNetCSharpInterfaceView.DECLARED, secondary.authoringView)
            assertEquals(DotNetCSharpInterfaceView.DECLARED, map.authoringView)
            assertEquals(DotNetCSharpInterfaceView.EXACT, accepts.authoringView)
            assertEquals(DotNetCSharpInterfaceView.EXACT, retain.authoringView)
            assertEquals(
                listOf(DotNetCSharpErasedOwnerRelativeConstraint(0, 0)),
                retain.erasedOwnerRelativeConstraints,
            )
            assertEquals(DotNetCSharpInterfaceView.EXACT, retainBoth.authoringView)
            assertEquals(
                listOf(DotNetCSharpErasedOwnerRelativeConstraint(0, 0)),
                retainBoth.erasedOwnerRelativeConstraints,
            )
            assertEquals(null, accepts.wrongShapePolicy)
            assertEquals(
                DotNetCSharpWrongShapeFallback.FALSE,
                checkNotNull(barrierContains.wrongShapePolicy).fallback,
            )
            assertEquals(1, barrierContains.wrongShapePolicy?.checkedParameterCount)
            assertEquals(null, barrierContains.wrongShapePolicy?.fallbackParameterIndex)
            assertEquals(
                DotNetCSharpWrongShapeFallback.MINUS_ONE,
                checkNotNull(barrierIndexOf.wrongShapePolicy).fallback,
            )
            assertEquals(1, barrierIndexOf.wrongShapePolicy?.checkedParameterCount)
            assertEquals(null, barrierIndexOf.wrongShapePolicy?.fallbackParameterIndex)
            assertEquals(
                labelGetter.slots.single { it.role == DotNetCSharpSlotRole.ERASED }.propertyName,
                labelSetter.slots.single { it.role == DotNetCSharpSlotRole.ERASED }.propertyName,
            )
            assertEquals(1, map.slots.single { it.role == DotNetCSharpSlotRole.DECLARED }.genericArity)
            assertEquals(
                listOf(DotNetCSharpSlotRole.ERASED, DotNetCSharpSlotRole.EXACT),
                accepts.slots.map { it.role }.sorted(),
            )
            val genericParameters = readCSharpPhysicalGenericParametersFromDll(
                modernCSharp,
                readerAssembly,
                readerDirectory,
                producerAssembly,
            )
            val mapParameters = map.slots
                .filter { slot ->
                    slot.role == DotNetCSharpSlotRole.ERASED ||
                            slot.role == DotNetCSharpSlotRole.DECLARED
                }
                .map { slot ->
                    genericParameters.singleOrNull { parameter ->
                        parameter.ownerPath == slot.ownerPath &&
                                parameter.methodName == slot.methodName &&
                                parameter.index == 0
                    } ?: error(
                        "Missing physical generic parameter for constrained map slot $slot:\n" +
                                genericParameters.joinToString("\n")
                    )
                }
            assertTrue(mapParameters.all { parameter -> parameter.attributes == 0 })
            val ownerBoundGenericParameters = parentAssembly?.let { assembly ->
                readCSharpPhysicalGenericParametersFromDll(
                    modernCSharp,
                    readerAssembly,
                    readerDirectory,
                    assembly,
                )
            } ?: genericParameters
            val retainParameters = retain.slots
                .filter { slot ->
                    slot.role == DotNetCSharpSlotRole.ERASED ||
                            slot.role == DotNetCSharpSlotRole.EXACT
                }
                .map { slot ->
                    ownerBoundGenericParameters.singleOrNull { parameter ->
                        parameter.ownerPath == slot.ownerPath &&
                                parameter.methodName == slot.methodName &&
                                parameter.index == 0
                    } ?: error(
                        "Missing physical generic parameter for erased owner-relative slot $slot:\n" +
                                ownerBoundGenericParameters.joinToString("\n")
                    )
                }
            assertTrue(retainParameters.all { parameter ->
                parameter.constraints.isEmpty()
            }) {
                "A tooling guidance record must not reconstruct the erased constraint in CLR metadata"
            }
            val retainBothParameters = retainBoth.slots.map { slot ->
                genericParameters.singleOrNull { parameter ->
                    parameter.ownerPath == slot.ownerPath &&
                            parameter.methodName == slot.methodName &&
                            parameter.index == 0
                } ?: error(
                    "Missing physical generic parameter for erased owner-relative intersection $slot:\n" +
                            genericParameters.joinToString("\n")
                )
            }
            assertTrue(retainBothParameters.all { parameter ->
                parameter.constraints.isEmpty()
            }) {
                "Intersection tooling guidance must not reconstruct the erased CLR constraint"
            }
            val mapConstraint = mapParameters
                .flatMap { parameter -> parameter.constraints }
                .distinct()
                .single()
            assertEquals(listOf("manifest.ManifestMarker"), mapConstraint.ownerPath)
            assertEquals(
                if (externalParent) parentModuleName else moduleName,
                mapConstraint.assemblyName,
            )
            val mapConstraintType = mapConstraint.ownerPath.joinToString(".") { component ->
                component.substringBefore('`')
            }
            val helper = fallback.slots.single { it.role == DotNetCSharpSlotRole.HELPER }
            assertEquals("__KotlinDefaultImpls", helper.ownerPath.last())
            if (parentManifest.targetProfile == "net10.0") {
                assertEquals(DotNetCSharpDefaultKind.DIM_WITH_HELPER, fallback.defaultKind)
                assertEquals(DotNetCSharpInterfaceView.DECLARED, fallback.semanticBodyView)
                assertEquals(
                    DotNetCSharpDefaultKind.DIM_WITH_HELPER,
                    ordinaryFallback.defaultKind,
                )
                assertEquals(
                    DotNetCSharpInterfaceView.CANONICAL,
                    ordinaryFallback.semanticBodyView,
                )
            } else {
                assertEquals(DotNetCSharpDefaultKind.PORTABLE_HELPER, fallback.defaultKind)
                assertEquals(null, fallback.semanticBodyView)
                assertEquals(
                    DotNetCSharpDefaultKind.PORTABLE_HELPER,
                    ordinaryFallback.defaultKind,
                )
                assertEquals(null, ordinaryFallback.semanticBodyView)
            }

            val methodImpls = readCSharpPhysicalMethodImplsFromDll(
                modernCSharp,
                readerAssembly,
                readerDirectory,
                producerAssembly,
            )
            val promotedDim = hasEffectivePromotedDim(
                contract,
                parentManifest,
                fallback,
                methodImpls,
            )
            val ordinaryPromotedDim = hasEffectivePromotedDim(
                ordinaryContract,
                parentManifest,
                ordinaryFallback,
                methodImpls,
            )
            if (scenario.name == "net10.0-promoted-portable-parent") {
                assertTrue(promotedDim) {
                    "The child DLL does not expose a complete CLR MethodImpl promotion bundle:\n" +
                            methodImpls.joinToString("\n")
                }
                assertTrue(ordinaryPromotedDim) {
                    "The ordinary child DLL does not expose its promoted CLR DIM:\n" +
                            methodImpls.joinToString("\n")
                }
                val fallbackSlots = fallback.slots.filter { slot ->
                    slot.role != DotNetCSharpSlotRole.HELPER
                }
                var tampered = false
                val wrongReturnMethodImpls = methodImpls.map { implementation ->
                    val matchesLocator = fallbackSlots.any { slot ->
                        implementation.declarationAssemblyName.equals(
                            parentManifest.assemblyName,
                            ignoreCase = true,
                        ) &&
                                implementation.declarationOwnerPath == slot.ownerPath &&
                                implementation.declarationMethodName == slot.methodName &&
                                implementation.declarationGenericArity == slot.genericArity
                    }
                    if (!tampered && matchesLocator) {
                        tampered = true
                        implementation.copy(
                            declarationReturnType =
                                implementation.declarationReturnType + "[]"
                        )
                    } else {
                        implementation
                    }
                }
                assertTrue(tampered) {
                    "No promoted MethodImpl was available for signature-integrity testing"
                }
                assertFalse(
                    hasEffectivePromotedDim(
                        contract,
                        parentManifest,
                        fallback,
                        wrongReturnMethodImpls,
                    )
                ) {
                    "A MethodImpl with the wrong return signature satisfied the promotion contract"
                }
            } else {
                assertFalse(promotedDim) {
                    "Only the cross-profile scenario should require a child-owned promotion:\n" +
                            methodImpls.joinToString("\n")
                }
                assertFalse(ordinaryPromotedDim) {
                    "Only the cross-profile ordinary child should require a promoted DIM:\n" +
                            methodImpls.joinToString("\n")
                }
            }
            val generatedSource = profileDirectory.resolve("generated.cs").apply {
                writeText(
                    generateShapeImplementation(
                        contract,
                        rootContract,
                        parentContract,
                        siblingContract,
                        leftContract,
                        rightContract,
                        resolvedIntersection,
                        intersection,
                        mutableLeftContract,
                        mutableRightContract,
                        resolvedMutable,
                        mutableGetter,
                        mutableSetter,
                        mapConstraintType,
                        ordinaryParentContract,
                        ordinaryContract,
                        barrierContract,
                        searchBarrierContract,
                        friendContract,
                        nestedFriendContract,
                        runtimeIterableContract,
                        runtimeCollectionContract,
                        runtimeListContract,
                        inheritedDefaultHasEffectiveDim =
                            fallback.defaultKind == DotNetCSharpDefaultKind.DIM_WITH_HELPER ||
                                    promotedDim,
                        ordinaryDefaultHasEffectiveDim =
                            ordinaryFallback.defaultKind ==
                                    DotNetCSharpDefaultKind.DIM_WITH_HELPER ||
                                    ordinaryPromotedDim,
                    )
                )
            }
            if (scenario.name == "net10.0-promoted-portable-parent") {
                assertFalse("__KotlinDefaultImpls" in generatedSource.readText()) {
                    "A physically promoted DIM must suppress generated helper forwarders"
                }
            }
            val generatedAssembly = profileDirectory.resolve("GeneratedShape.dll")
            val generatedCompile = if (parentAssembly == null) {
                runModernCSharpCompiler(
                    modernCSharp,
                    generatedSource,
                    generatedAssembly,
                    producerAssembly,
                    runtimeAssembly,
                    target = "exe",
                )
            } else {
                runModernCSharpCompiler(
                    modernCSharp,
                    generatedSource,
                    generatedAssembly,
                    producerAssembly,
                    parentAssembly,
                    runtimeAssembly,
                    target = "exe",
                )
            }
            assertEquals(0, generatedCompile.exitCode, generatedCompile.output)
            runtimeAssembly.copyTo(profileDirectory.resolve(runtimeAssembly.name), overwrite = true)
            profileDirectory.resolve("GeneratedShape.runtimeconfig.json").writeText(net10RuntimeConfig())
            runDotNet(
                modernCSharp.dotNetHost,
                generatedAssembly,
                profileDirectory,
                "Manifest-generated C# implementation failed for ${scenario.name}",
            )

            val friendType = friendContract.canonicalOwnerPath.joinToString(".") { component ->
                component.substringBefore('`')
            }
            val unauthorizedSource = profileDirectory.resolve("unauthorized.cs").apply {
                writeText(
                    """
                    internal static class UnauthorizedReference
                    {
                        internal static $friendType Value;
                    }
                    """.trimIndent()
                )
            }
            val unauthorizedCompile = runModernCSharpCompiler(
                modernCSharp,
                unauthorizedSource,
                profileDirectory.resolve("UnauthorizedShape.dll"),
                producerAssembly,
                runtimeAssembly,
            )
            assertTrue(unauthorizedCompile.exitCode != 0) {
                "An unauthorized C# assembly consumed internal contract '$friendType'"
            }
            assertTrue("CS0122" in unauthorizedCompile.output) { unauthorizedCompile.output }

            val nestedFriendType =
                nestedFriendContract.canonicalOwnerPath.joinToString(".") {
                    component -> component.substringBefore('`')
                }
            val unauthorizedNestedSource =
                profileDirectory.resolve("unauthorized-nested.cs").apply {
                    writeText(
                        """
                        internal static class UnauthorizedNestedReference
                        {
                            internal static $nestedFriendType Value;
                        }
                        """.trimIndent()
                    )
                }
            val unauthorizedNestedCompile = runModernCSharpCompiler(
                modernCSharp,
                unauthorizedNestedSource,
                profileDirectory.resolve("UnauthorizedNestedShape.dll"),
                producerAssembly,
                runtimeAssembly,
            )
            assertTrue(unauthorizedNestedCompile.exitCode != 0) {
                "An unauthorized C# assembly consumed nested internal contract " +
                        "'$nestedFriendType'"
            }
            assertTrue("CS0122" in unauthorizedNestedCompile.output) {
                unauthorizedNestedCompile.output
            }

            val baseListProbe = profileDirectory.resolve("base-list-probe.cs").apply {
                writeText(
                    """
                    public sealed partial class BaseListProbe : manifest.OrdinaryShape
                    {
                        public string DisplayName { get { return "ordinary"; } }
                        public int Count { get; set; } = 3;
                        public string Format(string prefix) { return prefix + DisplayName; }
                    }

                    internal sealed partial class FriendBaseListProbe : manifest.FriendShape
                    {
                        internal int Code { get { return 41; } }
                    }

                    internal sealed partial class NestedFriendBaseListProbe :
                        manifest.FriendContainer.NestedShape
                    {
                        internal int NestedCode { get { return 42; } }
                    }

                    internal sealed partial class NestedGenericFriendBaseListProbe :
                        manifest.FriendContainer.NestedGeneric<string>
                    {
                        internal string NestedValue { get { return "nested"; } }
                    }

                    public sealed partial class GenericBaseListProbe<T> : manifest.Shape<T>
                        where T : class
                    {
                        public GenericBaseListProbe(T value, T secondary)
                        {
                            Value = value;
                            Secondary = secondary;
                        }

                        public T Value { get; }
                        public T Secondary { get; }
                        public string Label { get; set; } = "initial";

                        public T Map<R>(R input)
                            where R : manifest.ManifestMarker
                        {
                            return Value;
                        }

                        public bool Accepts(T input)
                        {
                            return object.Equals(input, Value);
                        }
                    }

                    public sealed partial class IntGenericBaseListProbe :
                        manifest.Shape<int>
                    {
                        public int Value { get { return 42; } }
                        public int Secondary { get { return 43; } }
                        public string Label { get; set; } = "initial";

                        public int Map<R>(R input)
                            where R : manifest.ManifestMarker
                        {
                            return Value;
                        }

                        public bool Accepts(int input)
                        {
                            return input == Value;
                        }
                    }

                    public sealed partial class OwnerBoundBaseListProbe<T> :
                        manifest.OwnerBound<T>
                    {
                        public R Retain<R>(R value)
                        {
                            return value;
                        }
                    }

                    public sealed partial class IntersectionBaseListProbe :
                        manifest.ResolvedIntersection<string>
                    {
                        public string Overlap()
                        {
                            return "intersection";
                        }
                    }

                    public sealed partial class MutableIntersectionBaseListProbe :
                        manifest.ResolvedMutable<string>
                    {
                        public string Merged { get; set; } = "mutable";
                    }

                    public sealed partial class OwnerBoundIntersectionBaseListProbe<T> :
                        manifest.ResolvedOwnerBound<T>
                    {
                        public R RetainBoth<R>(R value)
                        {
                            return value;
                        }
                    }

                    public sealed partial class BarrierBaseListProbe :
                        manifest.BarrierShape<string>
                    {
                        public int Size { get { return 1; } }
                        public bool IsEmpty() { return false; }
                        public Kotlin.Collections.Iterator Iterator() { return null; }
                        public bool ContainsAll(Kotlin.Collections.Collection elements)
                        {
                            return false;
                        }
                        public bool Contains(string element)
                        {
                            return element == "typed";
                        }
                    }

                    public sealed partial class SearchBarrierBaseListProbe :
                        manifest.SearchBarrier<string>
                    {
                        public int Size { get { return 1; } }
                        public bool IsEmpty() { return false; }
                        public Kotlin.Collections.Iterator Iterator() { return null; }
                        public bool ContainsAll(Kotlin.Collections.Collection elements)
                        {
                            return false;
                        }
                        public bool Contains(string element)
                        {
                            return element == "typed";
                        }
                        public string Get(int index)
                        {
                            return index == 0 ? "typed" : null;
                        }
                        public int IndexOf(string element)
                        {
                            return Contains(element) ? 0 : -1;
                        }
                        public int LastIndexOf(string element)
                        {
                            return IndexOf(element);
                        }
                        public Kotlin.Collections.ListIterator ListIterator()
                        {
                            return null;
                        }
                        public Kotlin.Collections.ListIterator ListIterator(int index)
                        {
                            return null;
                        }
                        public Kotlin.Collections.List SubList(int fromIndex, int toIndex)
                        {
                            return null;
                        }
                    }

                    public static class BaseListProgram
                    {
                        public static int Main()
                        {
                            int result = manifest.apiKt.verifyOrdinary(new BaseListProbe());
                            if (result != 0)
                                throw new System.Exception(
                                    "Generated base-list implementation failed: " + result);
                            int friendResult =
                                manifest.apiKt.verifyFriend(new FriendBaseListProbe());
                            if (friendResult != 0)
                                throw new System.Exception(
                                    "Generated friend implementation failed: " + friendResult);
                            int nestedFriendResult =
                                manifest.apiKt.verifyNestedFriend(
                                    new NestedFriendBaseListProbe());
                            if (nestedFriendResult != 0)
                                throw new System.Exception(
                                    "Generated nested friend implementation failed: " +
                                    nestedFriendResult);
                            int nestedGenericFriendResult =
                                manifest.apiKt.verifyNestedGeneric(
                                    new NestedGenericFriendBaseListProbe());
                            if (nestedGenericFriendResult != 0)
                                throw new System.Exception(
                                    "Generated nested generic friend implementation failed: " +
                                    nestedGenericFriendResult);
                            int genericResult =
                                manifest.apiKt.verify(
                                    new GenericBaseListProbe<string>(
                                        "typed",
                                        "secondary"));
                            if (genericResult != 0)
                                throw new System.Exception(
                                    "Generated generic implementation failed: " + genericResult);
                            int intResult =
                                manifest.apiKt.verifyInt(new IntGenericBaseListProbe());
                            if (intResult != 0)
                                throw new System.Exception(
                                    "Generated value-type implementation failed: " + intResult);
                            int ownerBoundResult =
                                manifest.apiKt.verifyOwnerBound(
                                    new OwnerBoundBaseListProbe<manifest.ManifestMarker>());
                            if (ownerBoundResult != 0)
                                throw new System.Exception(
                                    "Generated owner-bound implementation failed: " +
                                    ownerBoundResult);
                            int intersectionResult =
                                manifest.apiKt.verifyIntersection(
                                    new IntersectionBaseListProbe());
                            if (intersectionResult != 0)
                                throw new System.Exception(
                                    "Generated intersection failed: " +
                                    intersectionResult);
                            int mutableIntersectionResult =
                                manifest.apiKt.verifyMutable(
                                    new MutableIntersectionBaseListProbe());
                            if (mutableIntersectionResult != 0)
                                throw new System.Exception(
                                    "Generated mutable intersection failed: " +
                                    mutableIntersectionResult);
                            int ownerBoundIntersectionResult =
                                manifest.apiKt.verifyResolvedOwnerBound(
                                    new OwnerBoundIntersectionBaseListProbe<
                                        manifest.ManifestMarker>());
                            if (ownerBoundIntersectionResult != 0)
                                throw new System.Exception(
                                    "Generated owner-bound intersection failed: " +
                                    ownerBoundIntersectionResult);
                            int barrierResult =
                                manifest.apiKt.verifyBarrier(new BarrierBaseListProbe());
                            if (barrierResult != 0)
                                throw new System.Exception(
                                    "Generated collection barrier failed: " +
                                    barrierResult);
                            int searchBarrierResult =
                                manifest.apiKt.verifySearchBarrier(
                                    new SearchBarrierBaseListProbe());
                            if (searchBarrierResult != 0)
                                throw new System.Exception(
                                    "Generated list barrier failed: " +
                                    searchBarrierResult);
                            return 0;
                        }
                    }
                    """.trimIndent()
                )
            }
            val generatedFiles =
                profileDirectory.resolve("roslyn-generated").apply { mkdirs() }
            val baseListCompileReferences = buildList {
                add(producerAssembly)
                parentAssembly?.let(::add)
                add(runtimeAssembly)
            }
            val baseListCompile = runModernCSharpCompiler(
                modernCSharp,
                baseListProbe,
                profileDirectory.resolve("GeneratedShape.dll"),
                *baseListCompileReferences.toTypedArray(),
                target = "exe",
                analyzers = listOf(csharpAuthoringTooling),
                generatedFilesDirectory = generatedFiles,
            )
            assertEquals(0, baseListCompile.exitCode, baseListCompile.output)
            val generatedAuthoringSources = generatedFiles.walkTopDown().filter { file ->
                file.isFile &&
                        file.name.endsWith(".KotlinInterfaceImplementation.g.cs")
            }.toList()
            assertTrue(generatedAuthoringSources.isNotEmpty()) {
                "The production Kotlin C# generator did not recognize a real Kotlin base list"
            }
            val generatedAuthoringText =
                generatedAuthoringSources.joinToString("\n", transform = File::readText)
            assertTrue("this.DisplayName" in generatedAuthoringText) {
                generatedAuthoringText
            }
            assertTrue("this.Count" in generatedAuthoringText) {
                generatedAuthoringText
            }
            assertTrue("this.Format" in generatedAuthoringText) {
                generatedAuthoringText
            }
            assertTrue(
                checkNotNull(contract.exactOwnerPath)
                    .last()
                    .substringBefore('`') in generatedAuthoringText
            ) {
                "The generated partial did not add the exact generic view:\n" +
                        generatedAuthoringText
            }
            assertTrue("this.Map<R>" in generatedAuthoringText) {
                generatedAuthoringText
            }
            assertTrue("this.Retain<R>" in generatedAuthoringText) {
                generatedAuthoringText
            }
            assertTrue("this.Overlap()" in generatedAuthoringText) {
                "Intersection adapters did not converge on the C# source body:\n" +
                        generatedAuthoringText
            }
            assertTrue("this.Merged" in generatedAuthoringText) {
                "Mutable intersection adapters did not share the C# property:\n" +
                        generatedAuthoringText
            }
            assertTrue("this.RetainBoth<R>" in generatedAuthoringText) {
                "Generic intersection adapters did not share the C# method:\n" +
                        generatedAuthoringText
            }
            assertTrue(
                checkNotNull(resolvedMutable.exactOwnerPath)
                    .last()
                    .substringBefore('`') in generatedAuthoringText
            ) {
                "The generated partial did not add the mutable exact view:\n" +
                        generatedAuthoringText
            }
            assertTrue("(object)" in generatedAuthoringText) {
                "The erased value-type result was not boxed:\n$generatedAuthoringText"
            }
            assertTrue("(int)" in generatedAuthoringText) {
                "The erased value-type argument was not unboxed:\n$generatedAuthoringText"
            }
            assertTrue("p0 is string" in generatedAuthoringText) {
                "The erased collection barriers do not check their typed input:\n" +
                        generatedAuthoringText
            }
            assertTrue("return false" in generatedAuthoringText) {
                "The Collection.contains wrong-shape fallback was not generated:\n" +
                        generatedAuthoringText
            }
            assertTrue("return -1" in generatedAuthoringText) {
                "The List.indexOf wrong-shape fallback was not generated:\n" +
                        generatedAuthoringText
            }
            assertTrue("KDNCS009" in baseListCompile.output) {
                "The analyzer did not explain the erased R : T boundary:\n" +
                        baseListCompile.output
            }
            val shouldForwardPortableDefault =
                targetProfile != "net10.0"
            assertEquals(
                shouldForwardPortableDefault,
                "__KotlinDefaultImpls" in generatedAuthoringText,
                generatedAuthoringText,
            )
            profileDirectory.resolve("GeneratedShape.runtimeconfig.json")
                .writeText(net10RuntimeConfig())
            runDotNet(
                modernCSharp.dotNetHost,
                profileDirectory.resolve("GeneratedShape.dll"),
                profileDirectory,
                "Production base-list C# implementation failed for ${scenario.name}",
            )
        }

        val modern = contractsByProfile.getValue("net10.0")
        for (portableProfile in listOf("net48", "netstandard2.0")) {
            val portable = contractsByProfile.getValue(portableProfile)
            assertEquals(
                portable.map { contract ->
                    Triple(
                        contract.logicalKey,
                        contract.members.map { it.logicalKey },
                        contract.intersections.map { intersection ->
                            intersection.logicalKey to intersection.contributingLogicalMemberKeys
                        },
                    )
                },
                modern.map { contract ->
                    Triple(
                        contract.logicalKey,
                        contract.members.map { it.logicalKey },
                        contract.intersections.map { intersection ->
                            intersection.logicalKey to intersection.contributingLogicalMemberKeys
                        },
                    )
                },
            )
            assertEquals(
                portable.flatMap { contract ->
                    contract.members.mapNotNull { member ->
                        member.slots.singleOrNull { it.role == DotNetCSharpSlotRole.HELPER }
                    }
                },
                modern.flatMap { contract ->
                    contract.members.mapNotNull { member ->
                        member.slots.singleOrNull { it.role == DotNetCSharpSlotRole.HELPER }
                    }
                },
            )
        }
    }

    @Test
    fun testCSharpAuthoringAnalyzerDiagnosesInvalidBaseListContracts() {
        val csharpToolchain = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(
            csharpToolchain != null,
            "Modern Roslyn and the net10 reference pack are not available",
        )
        val modernCSharp = checkNotNull(csharpToolchain)
        val tooling = buildCSharpAuthoringTooling(modernCSharp)
        val directory = File(tmpdir, "csharp-authoring-diagnostics").apply { mkdirs() }
        val manifest = DotNetCSharpImplementationManifest(
            schemaVersion = DotNetCSharpImplementationManifestCodec.CURRENT_SCHEMA_VERSION,
            assemblyName = "AnalyzerProducer",
            targetProfile = "net10.0",
            logicalIdentityScheme = DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
            interfaces = listOf(
                DotNetCSharpInterfaceContract(
                    logicalKey = "C:diagnostics/Shape",
                    canonicalOwnerPath = listOf("diagnostics.Shape"),
                    declaredOwnerPath = listOf("diagnostics.Shape`1"),
                    exactOwnerPath = null,
                    typeParameters = listOf(
                        DotNetCSharpTypeParameter(
                            "T",
                            DotNetCSharpTypeParameterVariance.INVARIANT,
                        )
                    ),
                    sourceAuthoringSupported = true,
                    unsupportedReasons = emptyList(),
                    members = listOf(
                        DotNetCSharpMemberContract(
                            logicalKey = "F:diagnostics/Shape.accept",
                            kind = DotNetCSharpMemberKind.METHOD,
                            sourceName = "accept",
                            authoringView = DotNetCSharpInterfaceView.DECLARED,
                            defaultKind = DotNetCSharpDefaultKind.ABSTRACT,
                            semanticBodyView = null,
                            wrongShapePolicy = null,
                            slots = listOf(
                                DotNetCSharpMethodLocator(
                                    role = DotNetCSharpSlotRole.ERASED,
                                    ownerPath = listOf("diagnostics.Shape"),
                                    methodName = "Accept",
                                    propertyName = null,
                                    genericArity = 0,
                                    returnType = "void",
                                    parameterTypes = listOf("object"),
                                ),
                                DotNetCSharpMethodLocator(
                                    role = DotNetCSharpSlotRole.DECLARED,
                                    ownerPath = listOf("diagnostics.Shape`1"),
                                    methodName = "Accept",
                                    propertyName = null,
                                    genericArity = 0,
                                    returnType = "void",
                                    parameterTypes = listOf("!0"),
                                ),
                            ),
                        )
                    ),
                    intersections = emptyList(),
                ),
                DotNetCSharpInterfaceContract(
                    logicalKey = "C:diagnostics/Friend",
                    canonicalOwnerPath = listOf("diagnostics.Friend"),
                    declaredOwnerPath = null,
                    exactOwnerPath = null,
                    typeParameters = emptyList(),
                    sourceAuthoringSupported = true,
                    unsupportedReasons = emptyList(),
                    members = listOf(
                        DotNetCSharpMemberContract(
                            logicalKey = "F:diagnostics/Friend.code",
                            kind = DotNetCSharpMemberKind.PROPERTY_GETTER,
                            sourceName = "code",
                            authoringView = DotNetCSharpInterfaceView.CANONICAL,
                            defaultKind = DotNetCSharpDefaultKind.ABSTRACT,
                            semanticBodyView = null,
                            wrongShapePolicy = null,
                            slots = listOf(
                                DotNetCSharpMethodLocator(
                                    role = DotNetCSharpSlotRole.CANONICAL,
                                    ownerPath = listOf("diagnostics.Friend"),
                                    methodName = "get_code",
                                    propertyName = "code",
                                    genericArity = 0,
                                    returnType = "int32",
                                    parameterTypes = emptyList(),
                                )
                            ),
                        )
                    ),
                    intersections = emptyList(),
                ),
                DotNetCSharpInterfaceContract(
                    logicalKey = "C:diagnostics/Ordinary",
                    canonicalOwnerPath = listOf("diagnostics.Ordinary"),
                    declaredOwnerPath = null,
                    exactOwnerPath = null,
                    typeParameters = emptyList(),
                    sourceAuthoringSupported = true,
                    unsupportedReasons = emptyList(),
                    members = listOf(
                        DotNetCSharpMemberContract(
                            logicalKey = "F:diagnostics/Ordinary.compute",
                            kind = DotNetCSharpMemberKind.METHOD,
                            sourceName = "compute",
                            authoringView = DotNetCSharpInterfaceView.CANONICAL,
                            defaultKind = DotNetCSharpDefaultKind.ABSTRACT,
                            semanticBodyView = null,
                            wrongShapePolicy = null,
                            slots = listOf(
                                DotNetCSharpMethodLocator(
                                    role = DotNetCSharpSlotRole.CANONICAL,
                                    ownerPath = listOf("diagnostics.Ordinary"),
                                    methodName = "Compute",
                                    propertyName = null,
                                    genericArity = 0,
                                    returnType = "int32",
                                    parameterTypes = listOf("int32"),
                                )
                            ),
                        )
                    ),
                    intersections = emptyList(),
                ),
                DotNetCSharpInterfaceContract(
                    logicalKey = "C:diagnostics/Overloaded",
                    canonicalOwnerPath = listOf("diagnostics.Overloaded"),
                    declaredOwnerPath = null,
                    exactOwnerPath = null,
                    typeParameters = emptyList(),
                    sourceAuthoringSupported = true,
                    unsupportedReasons = emptyList(),
                    members = listOf(
                        DotNetCSharpMemberContract(
                            logicalKey = "F:diagnostics/Overloaded.computeInt",
                            kind = DotNetCSharpMemberKind.METHOD,
                            sourceName = "compute",
                            authoringView = DotNetCSharpInterfaceView.CANONICAL,
                            defaultKind = DotNetCSharpDefaultKind.ABSTRACT,
                            semanticBodyView = null,
                            wrongShapePolicy = null,
                            slots = listOf(
                                DotNetCSharpMethodLocator(
                                    role = DotNetCSharpSlotRole.CANONICAL,
                                    ownerPath = listOf("diagnostics.Overloaded"),
                                    methodName = "Compute",
                                    propertyName = null,
                                    genericArity = 0,
                                    returnType = "int32",
                                    parameterTypes = listOf("int32"),
                                )
                            ),
                        ),
                        DotNetCSharpMemberContract(
                            logicalKey = "F:diagnostics/Overloaded.computeString",
                            kind = DotNetCSharpMemberKind.METHOD,
                            sourceName = "compute",
                            authoringView = DotNetCSharpInterfaceView.CANONICAL,
                            defaultKind = DotNetCSharpDefaultKind.ABSTRACT,
                            semanticBodyView = null,
                            wrongShapePolicy = null,
                            slots = listOf(
                                DotNetCSharpMethodLocator(
                                    role = DotNetCSharpSlotRole.CANONICAL,
                                    ownerPath = listOf("diagnostics.Overloaded"),
                                    methodName = "Compute",
                                    propertyName = null,
                                    genericArity = 0,
                                    returnType = "int32",
                                    parameterTypes = listOf("string"),
                                )
                            ),
                        ),
                        DotNetCSharpMemberContract(
                            logicalKey = "F:diagnostics/Overloaded.computeNullableInt",
                            kind = DotNetCSharpMemberKind.METHOD,
                            sourceName = "compute",
                            authoringView = DotNetCSharpInterfaceView.CANONICAL,
                            defaultKind = DotNetCSharpDefaultKind.ABSTRACT,
                            semanticBodyView = null,
                            wrongShapePolicy = null,
                            slots = listOf(
                                DotNetCSharpMethodLocator(
                                    role = DotNetCSharpSlotRole.CANONICAL,
                                    ownerPath = listOf("diagnostics.Overloaded"),
                                    methodName = "Compute",
                                    propertyName = null,
                                    genericArity = 0,
                                    returnType =
                                        "valuetype [mscorlib]System.Nullable`1<int32>",
                                    parameterTypes = listOf(
                                        "valuetype [mscorlib]System.Nullable`1<int32>"
                                    ),
                                )
                            ),
                        ),
                    ),
                    intersections = emptyList(),
                ),
                DotNetCSharpInterfaceContract(
                    logicalKey = "C:diagnostics/StaleParameter",
                    canonicalOwnerPath = listOf("diagnostics.StaleParameter"),
                    declaredOwnerPath = null,
                    exactOwnerPath = null,
                    typeParameters = emptyList(),
                    sourceAuthoringSupported = true,
                    unsupportedReasons = emptyList(),
                    members = listOf(
                        DotNetCSharpMemberContract(
                            logicalKey = "F:diagnostics/StaleParameter.compute",
                            kind = DotNetCSharpMemberKind.METHOD,
                            sourceName = "compute",
                            authoringView = DotNetCSharpInterfaceView.CANONICAL,
                            defaultKind = DotNetCSharpDefaultKind.ABSTRACT,
                            semanticBodyView = null,
                            wrongShapePolicy = null,
                            slots = listOf(
                                DotNetCSharpMethodLocator(
                                    role = DotNetCSharpSlotRole.CANONICAL,
                                    ownerPath = listOf("diagnostics.StaleParameter"),
                                    methodName = "Compute",
                                    propertyName = null,
                                    genericArity = 0,
                                    returnType = "int32",
                                    parameterTypes = listOf("string"),
                                )
                            ),
                        )
                    ),
                    intersections = emptyList(),
                ),
                DotNetCSharpInterfaceContract(
                    logicalKey = "C:diagnostics/StaleReturn",
                    canonicalOwnerPath = listOf("diagnostics.StaleReturn"),
                    declaredOwnerPath = null,
                    exactOwnerPath = null,
                    typeParameters = emptyList(),
                    sourceAuthoringSupported = true,
                    unsupportedReasons = emptyList(),
                    members = listOf(
                        DotNetCSharpMemberContract(
                            logicalKey = "F:diagnostics/StaleReturn.compute",
                            kind = DotNetCSharpMemberKind.METHOD,
                            sourceName = "compute",
                            authoringView = DotNetCSharpInterfaceView.CANONICAL,
                            defaultKind = DotNetCSharpDefaultKind.ABSTRACT,
                            semanticBodyView = null,
                            wrongShapePolicy = null,
                            slots = listOf(
                                DotNetCSharpMethodLocator(
                                    role = DotNetCSharpSlotRole.CANONICAL,
                                    ownerPath = listOf("diagnostics.StaleReturn"),
                                    methodName = "Compute",
                                    propertyName = null,
                                    genericArity = 0,
                                    returnType = "string",
                                    parameterTypes = listOf("int32"),
                                )
                            ),
                        )
                    ),
                    intersections = emptyList(),
                ),
                DotNetCSharpInterfaceContract(
                    logicalKey = "C:diagnostics/NullBarrier",
                    canonicalOwnerPath = listOf("diagnostics.NullBarrier"),
                    declaredOwnerPath = listOf("diagnostics.NullBarrier`1"),
                    exactOwnerPath = null,
                    typeParameters = listOf(
                        DotNetCSharpTypeParameter(
                            "T",
                            DotNetCSharpTypeParameterVariance.INVARIANT,
                        )
                    ),
                    sourceAuthoringSupported = true,
                    unsupportedReasons = emptyList(),
                    members = listOf(
                        DotNetCSharpMemberContract(
                            logicalKey = "F:diagnostics/NullBarrier.lookup",
                            kind = DotNetCSharpMemberKind.METHOD,
                            sourceName = "lookup",
                            authoringView = DotNetCSharpInterfaceView.DECLARED,
                            defaultKind = DotNetCSharpDefaultKind.ABSTRACT,
                            semanticBodyView = null,
                            wrongShapePolicy = DotNetCSharpWrongShapePolicy(
                                checkedParameterCount = 1,
                                fallback = DotNetCSharpWrongShapeFallback.NULL,
                                fallbackParameterIndex = null,
                            ),
                            slots = listOf(
                                DotNetCSharpMethodLocator(
                                    role = DotNetCSharpSlotRole.ERASED,
                                    ownerPath = listOf("diagnostics.NullBarrier"),
                                    methodName = "Lookup",
                                    propertyName = null,
                                    genericArity = 0,
                                    returnType = "object",
                                    parameterTypes = listOf("object"),
                                ),
                                DotNetCSharpMethodLocator(
                                    role = DotNetCSharpSlotRole.DECLARED,
                                    ownerPath = listOf("diagnostics.NullBarrier`1"),
                                    methodName = "Lookup",
                                    propertyName = null,
                                    genericArity = 0,
                                    returnType = "!0",
                                    parameterTypes = listOf("!0"),
                                ),
                            ),
                        )
                    ),
                    intersections = emptyList(),
                ),
                DotNetCSharpInterfaceContract(
                    logicalKey = "C:diagnostics/ArgumentBarrier",
                    canonicalOwnerPath = listOf("diagnostics.ArgumentBarrier"),
                    declaredOwnerPath = listOf("diagnostics.ArgumentBarrier`1"),
                    exactOwnerPath = null,
                    typeParameters = listOf(
                        DotNetCSharpTypeParameter(
                            "T",
                            DotNetCSharpTypeParameterVariance.INVARIANT,
                        )
                    ),
                    sourceAuthoringSupported = true,
                    unsupportedReasons = emptyList(),
                    members = listOf(
                        DotNetCSharpMemberContract(
                            logicalKey = "F:diagnostics/ArgumentBarrier.lookup",
                            kind = DotNetCSharpMemberKind.METHOD,
                            sourceName = "lookup",
                            authoringView = DotNetCSharpInterfaceView.DECLARED,
                            defaultKind = DotNetCSharpDefaultKind.ABSTRACT,
                            semanticBodyView = null,
                            wrongShapePolicy = DotNetCSharpWrongShapePolicy(
                                checkedParameterCount = 1,
                                fallback = DotNetCSharpWrongShapeFallback.ARGUMENT,
                                fallbackParameterIndex = 1,
                            ),
                            slots = listOf(
                                DotNetCSharpMethodLocator(
                                    role = DotNetCSharpSlotRole.ERASED,
                                    ownerPath = listOf("diagnostics.ArgumentBarrier"),
                                    methodName = "Lookup",
                                    propertyName = null,
                                    genericArity = 0,
                                    returnType = "object",
                                    parameterTypes = listOf("object", "object"),
                                ),
                                DotNetCSharpMethodLocator(
                                    role = DotNetCSharpSlotRole.DECLARED,
                                    ownerPath = listOf("diagnostics.ArgumentBarrier`1"),
                                    methodName = "Lookup",
                                    propertyName = null,
                                    genericArity = 0,
                                    returnType = "!0",
                                    parameterTypes = listOf("!0", "!0"),
                                ),
                            ),
                        )
                    ),
                    intersections = emptyList(),
                ),
            ),
        )
        val assemblyMetadata = DotNetCSharpImplementationManifestCodec
            .encodeAssemblyMetadata(manifest)
            .joinToString("\n") { entry ->
                """[assembly: System.Reflection.AssemblyMetadata("${entry.first}", "${entry.second}")]"""
            }
        val producerSource = directory.resolve("producer.cs").apply {
            writeText(
                """
                $assemblyMetadata

                namespace diagnostics
                {
                    public interface Shape
                    {
                        void Accept(object value);
                    }

                    public interface Shape<T> : Shape
                    {
                        void Accept(T value);
                    }

                    internal interface Friend
                    {
                        int code { get; }
                    }

                    public interface Ordinary
                    {
                        int Compute(int value);
                    }

                    public interface Overloaded
                    {
                        int Compute(int value);
                        int Compute(string value);
                        int? Compute(int? value);
                    }

                    public interface StaleParameter
                    {
                        int Compute(int value);
                    }

                    public interface StaleReturn
                    {
                        int Compute(int value);
                    }

                    public interface NullBarrier
                    {
                        object Lookup(object key);
                    }

                    public interface NullBarrier<T> : NullBarrier
                    {
                        T Lookup(T key);
                    }

                    public interface ArgumentBarrier
                    {
                        object Lookup(object key, object fallback);
                    }

                    public interface ArgumentBarrier<T> : ArgumentBarrier
                    {
                        T Lookup(T key, T fallback);
                    }
                }
                """.trimIndent()
            )
        }
        val producer = directory.resolve("AnalyzerProducer.dll")
        val producerCompile = runModernCSharpCompiler(
            modernCSharp,
            producerSource,
            producer,
        )
        assertEquals(0, producerCompile.exitCode, producerCompile.output)

        fun compileDiagnostic(name: String, sourceText: String): CSharpCompilerResult {
            val source = directory.resolve("$name.cs").apply { writeText(sourceText) }
            return runModernCSharpCompiler(
                modernCSharp,
                source,
                directory.resolve("$name.dll"),
                producer,
                analyzers = listOf(tooling),
            )
        }

        val validGeneratedDirectory = directory.resolve("valid-generated").apply { mkdirs() }
        val validSource = directory.resolve("valid.cs").apply {
            writeText(
                """
                public sealed partial class Valid : diagnostics.Shape<string>
                {
                    public void Accept(string value) {}
                    public void Accept(object value) {}
                }

                public sealed partial class ValidOverloaded :
                    diagnostics.Overloaded
                {
                    public int Compute(int value)
                    {
                        return value + 1;
                    }

                    public int Compute(string value)
                    {
                        return value.Length;
                    }

                    public int? Compute(int? value)
                    {
                        return value.HasValue ? value.Value + 1 : null;
                    }
                }

                public static partial class GenericContainer<T> where T : class
                {
                    public sealed partial class Nested :
                        diagnostics.Shape<T>
                    {
                        public T Last { get; private set; } = null!;

                        public void Accept(T value)
                        {
                            Last = value;
                        }
                    }
                }

                public partial record class RecordContainer
                {
                    public sealed partial record class Nested :
                        diagnostics.Ordinary
                    {
                        public int Compute(int value)
                        {
                            return value + 2;
                        }
                    }
                }

                namespace collision
                {
                    public sealed partial class A_B : diagnostics.Ordinary
                    {
                        public int Compute(int value) { return value + 3; }
                    }
                }

                namespace collision.A
                {
                    public sealed partial class B : diagnostics.Ordinary
                    {
                        public int Compute(int value) { return value + 4; }
                    }
                }

                public sealed partial class ValidNullBarrier :
                    diagnostics.NullBarrier<string>
                {
                    public string Lookup(string key)
                    {
                        return "body:" + key;
                    }
                }

                public sealed partial class ValidArgumentBarrier :
                    diagnostics.ArgumentBarrier<string>
                {
                    public string Lookup(string key, string fallback)
                    {
                        return "body:" + key;
                    }
                }

                public static class Program
                {
                    public static int Main()
                    {
                        var nullBarrier = new ValidNullBarrier();
                        if (((diagnostics.NullBarrier)nullBarrier).Lookup(42) != null)
                            return 1;
                        if (nullBarrier.Lookup("typed") != "body:typed")
                            return 2;

                        var argumentBarrier = new ValidArgumentBarrier();
                        object fallback = new object();
                        object wrong = ((diagnostics.ArgumentBarrier)argumentBarrier)
                            .Lookup(42, fallback);
                        if (!object.ReferenceEquals(wrong, fallback))
                            return 3;
                        if (argumentBarrier.Lookup("typed", "fallback") !=
                                "body:typed")
                            return 4;
                        var overloaded = new ValidOverloaded();
                        if (overloaded.Compute(4) != 5)
                            return 5;
                        if (overloaded.Compute("typed") != 5)
                            return 6;
                        if (overloaded.Compute((int?)4) != 5)
                            return 7;
                        if (overloaded.Compute((int?)null) != null)
                            return 8;
                        var nested =
                            new GenericContainer<string>.Nested();
                        ((diagnostics.Shape)nested).Accept("nested");
                        if (nested.Last != "nested")
                            return 9;
                        var nestedRecord = new RecordContainer.Nested();
                        if (((diagnostics.Ordinary)nestedRecord).Compute(40) != 42)
                            return 10;
                        if (new collision.A_B().Compute(39) != 42)
                            return 11;
                        if (new collision.A.B().Compute(38) != 42)
                            return 12;
                        return 0;
                    }
                }
                """.trimIndent()
            )
        }
        val validAssembly = directory.resolve("Valid.dll")
        val validCompile = runModernCSharpCompiler(
            modernCSharp,
            validSource,
            validAssembly,
            producer,
            target = "exe",
            analyzers = listOf(tooling),
            generatedFilesDirectory = validGeneratedDirectory,
        )
        assertEquals(0, validCompile.exitCode, validCompile.output)
        val validGeneratedSources =
            validGeneratedDirectory.walkTopDown().filter { file ->
                file.isFile && file.name.endsWith(".KotlinInterfaceImplementation.g.cs")
            }.toList()
        assertTrue(validGeneratedSources.isNotEmpty()) {
            "A valid generic Kotlin base list did not activate the source generator"
        }
        val validGeneratedText =
            validGeneratedSources.joinToString("\n", transform = File::readText)
        assertTrue("return null" in validGeneratedText) {
            "The null wrong-shape fallback was not generated:\n$validGeneratedText"
        }
        assertTrue("return p1" in validGeneratedText) {
            "The argument wrong-shape fallback was not generated:\n$validGeneratedText"
        }
        directory.resolve("Valid.runtimeconfig.json").writeText(net10RuntimeConfig())
        runDotNet(
            modernCSharp.dotNetHost,
            validAssembly,
            directory,
            "Synthetic C# wrong-shape fallback execution failed",
        )

        val missingPartial = compileDiagnostic(
            "MissingPartial",
            """
            public sealed class MissingPartial : diagnostics.Shape<string>
            {
                public void Accept(string value) {}
                public void Accept(object value) {}
            }
            """.trimIndent(),
        )
        assertTrue(missingPartial.exitCode != 0)
        assertTrue("KDNCS001" in missingPartial.output) { missingPartial.output }

        val missingContainingPartial = compileDiagnostic(
            "MissingContainingPartial",
            """
            public class MissingContainingPartial
            {
                public sealed partial class Nested : diagnostics.Ordinary
                {
                    public int Compute(int value) { return value; }
                }
            }
            """.trimIndent(),
        )
        assertTrue(missingContainingPartial.exitCode != 0)
        assertTrue("KDNCS011" in missingContainingPartial.output) {
            missingContainingPartial.output
        }

        val valueTypeImplementor = compileDiagnostic(
            "ValueTypeImplementor",
            """
            public partial record struct ValueTypeImplementor :
                diagnostics.Ordinary
            {
                public int Compute(int value) { return value; }
            }
            """.trimIndent(),
        )
        assertTrue(valueTypeImplementor.exitCode != 0)
        assertTrue("KDNCS010" in valueTypeImplementor.output) {
            valueTypeImplementor.output
        }

        val inaccessibleFriend = compileDiagnostic(
            "UnavailableFriend",
            """
            internal sealed partial class UnavailableFriend : diagnostics.Friend
            {
                public int code { get { return 1; } }
            }
            """.trimIndent(),
        )
        assertTrue(inaccessibleFriend.exitCode != 0)
        assertTrue("KDNCS002" in inaccessibleFriend.output) { inaccessibleFriend.output }

        val conflict = compileDiagnostic(
            "ConflictingMember",
            """
            public sealed partial class ConflictingMember : diagnostics.Shape<string>
            {
                public void Accept(string value) {}
                public void Accept(object value) {}
                void diagnostics.Shape<string>.Accept(string value) {}
            }
            """.trimIndent(),
        )
        assertTrue(conflict.exitCode != 0)
        assertTrue("KDNCS003" in conflict.output) { conflict.output }

        val unsupportedSubstitution = compileDiagnostic(
            "UnsupportedSubstitution",
            """
            public sealed partial class UnsupportedSubstitution : diagnostics.Shape<dynamic>
            {
                public void Accept(dynamic value) {}
            }
            """.trimIndent(),
        )
        assertTrue(unsupportedSubstitution.exitCode != 0)
        assertTrue("KDNCS004" in unsupportedSubstitution.output) {
            unsupportedSubstitution.output
        }

        val missingSourceMember = compileDiagnostic(
            "MissingSourceMember",
            """
            public sealed partial class MissingSourceMember : diagnostics.Ordinary
            {
            }
            """.trimIndent(),
        )
        assertTrue(missingSourceMember.exitCode != 0)
        assertTrue("KDNCS008" in missingSourceMember.output) {
            missingSourceMember.output
        }

        val staleParameter = compileDiagnostic(
            "StaleParameter",
            """
            public sealed partial class StaleParameter :
                diagnostics.StaleParameter
            {
                public int Compute(int value) { return value; }
            }
            """.trimIndent(),
        )
        assertTrue(staleParameter.exitCode != 0)
        assertTrue("KDNCS006" in staleParameter.output) {
            staleParameter.output
        }

        val staleReturn = compileDiagnostic(
            "StaleReturn",
            """
            public sealed partial class StaleReturn : diagnostics.StaleReturn
            {
                public int Compute(int value) { return value; }
            }
            """.trimIndent(),
        )
        assertTrue(staleReturn.exitCode != 0)
        assertTrue("KDNCS006" in staleReturn.output) {
            staleReturn.output
        }

        val staleSource = directory.resolve("stale.cs").apply {
            writeText(
                """
                [assembly: System.Reflection.AssemblyMetadata(
                    "${DotNetCSharpImplementationManifestCodec.ASSEMBLY_METADATA_KEY}",
                    "999:1:${"0".repeat(64)}")]
                public interface Stale {}
                """.trimIndent()
            )
        }
        val staleAssembly = directory.resolve("Stale.dll")
        val staleCompile = runModernCSharpCompiler(
            modernCSharp,
            staleSource,
            staleAssembly,
        )
        assertEquals(0, staleCompile.exitCode, staleCompile.output)
        val versionConsumer = directory.resolve("version-consumer.cs").apply {
            writeText("public sealed class VersionConsumer {}")
        }
        val versionMismatch = runModernCSharpCompiler(
            modernCSharp,
            versionConsumer,
            directory.resolve("VersionConsumer.dll"),
            staleAssembly,
            analyzers = listOf(tooling),
        )
        assertTrue(versionMismatch.exitCode != 0)
        assertTrue("KDNCS005" in versionMismatch.output) { versionMismatch.output }

        val malformedSource = directory.resolve("malformed.cs").apply {
            writeText(
                """
                [assembly: System.Reflection.AssemblyMetadata(
                    "${DotNetCSharpImplementationManifestCodec.ASSEMBLY_METADATA_KEY}",
                    "${DotNetCSharpImplementationManifestCodec.CURRENT_SCHEMA_VERSION}:1:${"0".repeat(64)}")]
                public interface Malformed {}
                """.trimIndent()
            )
        }
        val malformedAssembly = directory.resolve("Malformed.dll")
        val malformedCompile = runModernCSharpCompiler(
            modernCSharp,
            malformedSource,
            malformedAssembly,
        )
        assertEquals(0, malformedCompile.exitCode, malformedCompile.output)
        val malformedConsumer = runModernCSharpCompiler(
            modernCSharp,
            versionConsumer,
            directory.resolve("MalformedConsumer.dll"),
            malformedAssembly,
            analyzers = listOf(tooling),
        )
        assertTrue(malformedConsumer.exitCode != 0)
        assertTrue("KDNCS006" in malformedConsumer.output) { malformedConsumer.output }
    }

    @Test
    fun testCSharpImplementationManifestCarrierRejectsCorruption() {
        val manifest = DotNetCSharpImplementationManifest(
            schemaVersion = DotNetCSharpImplementationManifestCodec.CURRENT_SCHEMA_VERSION,
            assemblyName = "Manifest.Empty",
            targetProfile = "netstandard2.0",
            logicalIdentityScheme = DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
            interfaces = emptyList(),
        )
        val metadata = DotNetCSharpImplementationManifestCodec.encodeAssemblyMetadata(manifest)
        assertEquals(
            manifest,
            DotNetCSharpImplementationManifestCodec.decodeAssemblyMetadata(metadata),
        )
        val missingChunk = metadata.filterNot { entry ->
            entry.first.endsWith(".0000")
        }
        assertThrows(IllegalStateException::class.java) {
            DotNetCSharpImplementationManifestCodec.decodeAssemblyMetadata(missingChunk)
        }
        val corruptChunk = metadata.map { entry ->
            if (entry.first.endsWith(".0000")) {
                entry.first to entry.second.replaceRange(0, 1, if (entry.second[0] == 'A') "B" else "A")
            } else {
                entry
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DotNetCSharpImplementationManifestCodec.decodeAssemblyMetadata(corruptChunk)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DotNetCSharpImplementationManifestCodec.decodeAssemblyMetadata(metadata + metadata.last())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DotNetCSharpImplementationManifestCodec.encodeAssemblyMetadata(
                manifest.copy(logicalIdentityScheme = "runtime-member-names-v1")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DotNetCSharpImplementationManifestCodec.encodeAssemblyMetadata(
                manifest.copy(
                    interfaces = listOf(
                        DotNetCSharpInterfaceContract(
                            logicalKey = "runtime:Kotlin.Collections.Collection",
                            canonicalOwnerPath = listOf("Kotlin.Collections.Collection"),
                            declaredOwnerPath = null,
                            exactOwnerPath = null,
                            typeParameters = emptyList(),
                            sourceAuthoringSupported = true,
                            unsupportedReasons = emptyList(),
                            members = emptyList(),
                            intersections = emptyList(),
                        )
                    )
                )
            )
        }
        val invalidConstraintContract = DotNetCSharpInterfaceContract(
            logicalKey = "C:sample/Owner",
            canonicalOwnerPath = listOf("sample.Owner"),
            declaredOwnerPath = listOf("sample.Owner`1"),
            exactOwnerPath = listOf("sample.Owner__KotlinExact`1"),
            typeParameters = listOf(
                DotNetCSharpTypeParameter("T", DotNetCSharpTypeParameterVariance.OUT)
            ),
            sourceAuthoringSupported = true,
            unsupportedReasons = emptyList(),
            members = listOf(
                DotNetCSharpMemberContract(
                    logicalKey = "F:sample/Owner.retain",
                    kind = DotNetCSharpMemberKind.METHOD,
                    sourceName = "retain",
                    authoringView = DotNetCSharpInterfaceView.EXACT,
                    defaultKind = DotNetCSharpDefaultKind.ABSTRACT,
                    semanticBodyView = null,
                    wrongShapePolicy = null,
                    erasedOwnerRelativeConstraints = listOf(
                        DotNetCSharpErasedOwnerRelativeConstraint(
                            methodTypeParameterIndex = 1,
                            ownerTypeParameterIndex = 0,
                        )
                    ),
                    slots = listOf(
                        DotNetCSharpMethodLocator(
                            role = DotNetCSharpSlotRole.ERASED,
                            ownerPath = listOf("sample.Owner"),
                            methodName = "retain__KotlinErased",
                            propertyName = null,
                            genericArity = 1,
                            returnType = "!!0",
                            parameterTypes = listOf("!!0"),
                        ),
                        DotNetCSharpMethodLocator(
                            role = DotNetCSharpSlotRole.EXACT,
                            ownerPath = listOf("sample.Owner__KotlinExact`1"),
                            methodName = "retain",
                            propertyName = null,
                            genericArity = 1,
                            returnType = "!!0",
                            parameterTypes = listOf("!!0"),
                        ),
                    ),
                )
            ),
            intersections = emptyList(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            DotNetCSharpImplementationManifestCodec.encodeAssemblyMetadata(
                manifest.copy(interfaces = listOf(invalidConstraintContract))
            )
        }
    }

    @Test
    fun testPortablePhysicalAbiComparisonRejectsMissingAndChangedBindings() {
        val portable = linkedMapOf(
            "C:sample/Box" to DotNetPhysicalDeclaration.Class(listOf("sample.Box")),
            "F:sample/read" to DotNetPhysicalDeclaration.Function(
                ownerPath = listOf("sample.LibraryKt"),
                methodName = "read",
                isInstance = false,
            ),
            "F:sample/withDefaults" to DotNetPhysicalDeclaration.Function(
                ownerPath = listOf("sample.Contract"),
                methodName = "withDefaults",
                isInstance = true,
                defaultArgumentDispatcher = DotNetDefaultArgumentDispatcher(
                    ownerPath = listOf("sample.Contract", "__KotlinDefaultImpls"),
                    methodName = "withDefaults\$default",
                ),
            ),
            "W:C:sample/Box:F:sample/withDefaults:CANONICAL" to
                    DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder(
                        ownerPath = listOf("sample.Box"),
                        ownerLogicalKey = "C:sample/Box",
                        inheritedLogicalMemberKey = "F:sample/withDefaults",
                        physicalView = DotNetInterfaceDefaultPromotionView.CANONICAL,
                        implementationMethodName = "<InterfaceDefaultForwarder-withDefaults>",
                    ),
            "R:C:sample/Box:F:sample/read" to DotNetPhysicalDeclaration.CovariantReturnBridge(
                ownerPath = listOf("sample.Box"),
                ownerLogicalKey = "C:sample/Box",
                inheritedLogicalMemberKey = "F:sample/read",
                implementationMethodName = "<CovariantReturnBridge-read>",
            ),
        )
        val compatiblePlatform = portable.filterValues {
            it !is DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder &&
                    it !is DotNetPhysicalDeclaration.CovariantReturnBridge
        } + (
                "F:sample/runtimeOnly" to DotNetPhysicalDeclaration.Function(
                    ownerPath = listOf("sample.PlatformKt"),
                    methodName = "runtimeOnly",
                    isInstance = false,
                )
                )
        assertTrue(DotNetLibraryAbiCodec.portablePhysicalAbiDifferences(portable, compatiblePlatform).isEmpty())


        val changedFunction = DotNetPhysicalDeclaration.Function(
            ownerPath = listOf("sample.ChangedKt"),
            methodName = "read",
            isInstance = false,
        )
        val changedDispatcher = DotNetPhysicalDeclaration.Function(
            ownerPath = listOf("sample.Contract"),
            methodName = "withDefaults",
            isInstance = true,
            defaultArgumentDispatcher = DotNetDefaultArgumentDispatcher(
                ownerPath = listOf("sample.Contract", "<ChangedDefaultImpls>"),
                methodName = "withDefaults\$default",
            ),
        )
        val differences = DotNetLibraryAbiCodec.portablePhysicalAbiDifferences(
            portable,
            mapOf(
                "F:sample/read" to changedFunction,
                "F:sample/withDefaults" to changedDispatcher,
            ),
        )
        assertEquals(
            listOf(
                DotNetPortablePhysicalAbiDifference(
                    logicalKey = "C:sample/Box",
                    portableDeclaration = portable.getValue("C:sample/Box"),
                    platformDeclaration = null,
                ),
                DotNetPortablePhysicalAbiDifference(
                    logicalKey = "F:sample/read",
                    portableDeclaration = portable.getValue("F:sample/read"),
                    platformDeclaration = changedFunction,
                ),
                DotNetPortablePhysicalAbiDifference(
                    logicalKey = "F:sample/withDefaults",
                    portableDeclaration = portable.getValue("F:sample/withDefaults"),
                    platformDeclaration = changedDispatcher,
                ),
            ),
            differences,
        )
    }

    @Test
    fun testGenericInterfacesAcrossLibraryBoundary() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val librarySource = File(tmpdir, "generic-interface-library.kt").apply {
            writeText(
                """
                package cross

                interface Producer<out T> {
                    fun produce(): T
                }

                interface Consumer<in T> {
                    fun consume(value: T)
                }

                interface VariantCell<out T> {
                    var value: @UnsafeVariance T
                }

                class LibraryProducer(private val result: Int) : Producer<Int> {
                    override fun produce(): Int = result
                }

                class LibraryCell(override var value: Int) : VariantCell<Int>

                class LibraryIterator(private var next: Int) : Iterator<Int> {
                    override fun hasNext(): Boolean = next < 10

                    override fun next(): Int {
                        val result = next
                        next = next + 1
                        return result
                    }
                }

                class LibraryIterable(private val start: Int) : Iterable<Int> {
                    override fun iterator(): Iterator<Int> = LibraryIterator(start)
                }

                class LibraryCollectionIterator(private val value: Int) : Iterator<Int> {
                    private var available: Boolean = true

                    override fun hasNext(): Boolean = available

                    override fun next(): Int {
                        if (!available) throw NoSuchElementException()
                        available = false
                        return value
                    }
                }

                class LibraryCollection(private val value: Int) : Collection<Int> {
                    override val size: Int get() = 1

                    override fun isEmpty(): Boolean = false

                    override fun contains(element: Int): Boolean = element == value

                    override fun iterator(): Iterator<Int> = LibraryCollectionIterator(value)

                    override fun containsAll(elements: Collection<Int>): Boolean {
                        val iterator = elements.iterator()
                        while (iterator.hasNext()) {
                            if (!contains(iterator.next())) return false
                        }
                        return true
                    }
                }

                class LibraryListIterator(
                    private val value: Int,
                    private val size: Int,
                    private var position: Int,
                ) : ListIterator<Int> {
                    override fun hasNext(): Boolean = position < size

                    override fun next(): Int {
                        if (!hasNext()) throw NoSuchElementException()
                        position = position + 1
                        return value
                    }

                    override fun hasPrevious(): Boolean = position > 0

                    override fun previous(): Int {
                        if (!hasPrevious()) throw NoSuchElementException()
                        position = position - 1
                        return value
                    }

                    override fun nextIndex(): Int = position

                    override fun previousIndex(): Int = position - 1
                }

                class LibraryList private constructor(
                    private val value: Int,
                    private val fromIndex: Int,
                    private val toIndex: Int,
                ) : List<Int> {
                    constructor(value: Int) : this(value, 0, 1)

                    override val size: Int get() = toIndex - fromIndex

                    override fun isEmpty(): Boolean = size == 0

                    override fun contains(element: Int): Boolean = size == 1 && element == value

                    override fun iterator(): Iterator<Int> = listIterator()

                    override fun containsAll(elements: Collection<Int>): Boolean {
                        val iterator = elements.iterator()
                        while (iterator.hasNext()) {
                            if (!contains(iterator.next())) return false
                        }
                        return true
                    }

                    override fun get(index: Int): Int {
                        if (index < 0 || index >= size) throw IndexOutOfBoundsException()
                        return value
                    }

                    override fun indexOf(element: Int): Int = if (contains(element)) 0 else -1

                    override fun lastIndexOf(element: Int): Int = indexOf(element)

                    override fun listIterator(): ListIterator<Int> = listIterator(0)

                    override fun listIterator(index: Int): ListIterator<Int> {
                        if (index < 0 || index > size) throw IndexOutOfBoundsException()
                        return LibraryListIterator(value, size, index)
                    }

                    override fun subList(fromIndex: Int, toIndex: Int): List<Int> {
                        if (fromIndex < 0 || toIndex > size) throw IndexOutOfBoundsException()
                        if (fromIndex > toIndex) throw IllegalArgumentException()
                        return LibraryList(value, this.fromIndex + fromIndex, this.fromIndex + toIndex)
                    }
                }

                fun libraryProducer(): Producer<Int> = LibraryProducer(41)

                fun libraryCell(): VariantCell<Int> = LibraryCell(1)

                fun libraryIterator(): Iterator<Int> = LibraryIterator(8)

                fun libraryIterable(): Iterable<Int> = LibraryIterable(8)

                fun libraryCollection(): Collection<Int> = LibraryCollection(41)

                fun libraryListIterator(): ListIterator<Int> = LibraryListIterator(41, 1, 0)

                fun libraryList(): List<Int> = LibraryList(41)

                fun consumeSeven(consumer: Consumer<Int>) {
                    consumer.consume(7)
                }

                fun increment(cell: VariantCell<Int>) {
                    cell.value = cell.value + 1
                }

                fun readAsAny(producer: Producer<Any>): Any = producer.produce()
                """.trimIndent()
            )
        }
        val libraryDirectory = File(tmpdir, "generic-interface-library")
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Cross.Library",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("Cross.Library.klib")
        val libraryIl = libraryDirectory.resolve("Cross.Library.il").readText()
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.Iterator', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<int32>" in libraryIl
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.Iterator-next-" in libraryIl)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.Iterator-next-" in libraryIl)
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.Iterable', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Iterable`1'<int32>" in libraryIl
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.Iterable-iterator-" in libraryIl)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.Iterable-iterator-" in libraryIl)
        assertTrue(
            "static class [Kotlin.Runtime]'Kotlin.Collections.Iterator' 'libraryIterator'()" in libraryIl
        )
        assertTrue(
            "static class [Kotlin.Runtime]'Kotlin.Collections.Iterable' 'libraryIterable'()" in libraryIl
        )
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.Collection', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Collection__KotlinExact`1'<int32>" in libraryIl
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.Collection-contains-" in libraryIl)
        assertTrue("<GenericInterfaceExactBridge-kotlin.collections.Collection-contains-" in libraryIl)
        assertTrue("::'ContainsErased'(object" in libraryIl)
        assertTrue(
            "static class [Kotlin.Runtime]'Kotlin.Collections.Collection' 'libraryCollection'()" in libraryIl
        )
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.ListIterator', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.ListIterator`1'<int32>" in libraryIl
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.ListIterator-previous-" in libraryIl)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.ListIterator-previous-" in libraryIl)
        assertTrue(
            "static class [Kotlin.Runtime]'Kotlin.Collections.ListIterator' 'libraryListIterator'()" in libraryIl
        )
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.List', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.List__KotlinExact`1'<int32>" in libraryIl
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.List-get-" in libraryIl)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.List-get-" in libraryIl)
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.List-indexOf-" in libraryIl)
        assertTrue("<GenericInterfaceExactBridge-kotlin.collections.List-indexOf-" in libraryIl)
        assertTrue("::'IndexOfErased'(object" in libraryIl)
        assertTrue("::'GetListIterator'(" in libraryIl)
        assertTrue(
            "static class [Kotlin.Runtime]'Kotlin.Collections.List' 'libraryList'()" in libraryIl
        )
        val manifest = metadataLibrary.readKlibManifest()
        val declarationIndex = DotNetLibraryAbiCodec.decode(manifest)
        val physicalDeclarations = declarationIndex.values
            .filterIsInstance<DotNetPhysicalDeclaration.Class>()
        val producer = physicalDeclarations.single { it.ownerPath.last() == "cross.Producer" }
        assertTrue(producer.declaredOwnerPath?.last() == "cross.Producer`1")
        assertTrue(producer.exactOwnerPath == null)
        val variantCell = physicalDeclarations.single { it.ownerPath.last() == "cross.VariantCell" }
        assertTrue(variantCell.declaredOwnerPath?.last() == "cross.VariantCell`1")
        assertTrue(variantCell.exactOwnerPath?.last() == "cross.VariantCell__KotlinExact`1")

        val consumerDirectory = libraryDirectory.resolve("consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import cross.*

                class LocalProducer : Producer<Int> {
                    override fun produce(): Int = 42
                }

                class LocalCell(override var value: Int) : VariantCell<Int>

                class LocalAnyConsumer : Consumer<Any> {
                    var seen: String = ""

                    override fun consume(value: Any) {
                        seen = value.toString()
                    }
                }

                fun main() {
                    val libraryExact = libraryProducer()
                    val libraryWide: Producer<Any> = libraryExact
                    if (libraryExact !== libraryWide) throw Error("library producer identity")
                    if (libraryExact.produce() != 41) throw Error("library exact call")
                    if (libraryWide.produce() != 41) throw Error("library erased fallback")

                    val erasedLibraryProducer: Any = libraryExact
                    @Suppress("UNCHECKED_CAST")
                    val castLibraryWide = erasedLibraryProducer as Producer<Any>
                    if (castLibraryWide !== libraryExact || castLibraryWide.produce() != 41) {
                        throw Error("cross-module canonical hard cast")
                    }
                    @Suppress("UNCHECKED_CAST")
                    val safeLibraryMismatch = erasedLibraryProducer as? Producer<String>
                    val safeLibraryIdentity: Any? = safeLibraryMismatch
                    if (safeLibraryMismatch == null || safeLibraryIdentity !== libraryExact) {
                        throw Error("cross-module canonical safe cast")
                    }
                    if (LocalAnyConsumer() as? Producer<*> != null) {
                        throw Error("cross-module safe cast mismatch")
                    }

                    val localExact: Producer<Int> = LocalProducer()
                    val localWide: Producer<Any> = localExact
                    if (localExact !== localWide) throw Error("local producer identity")
                    if (localExact.produce() != 42 || localWide.produce() != 42) {
                        throw Error("external-interface implementation")
                    }

                    val iteratorExact = libraryIterator()
                    val iteratorWide: Iterator<Any> = iteratorExact
                    if (iteratorExact !== iteratorWide) throw Error("library iterator identity")
                    if (iteratorExact.next() != 8 || iteratorWide.next() != 9) {
                        throw Error("library iterator exact/fallback")
                    }

                    val iterableExact = libraryIterable()
                    val iterableWide: Iterable<Any> = iterableExact
                    if (iterableExact !== iterableWide) throw Error("library iterable identity")
                    if (iterableExact.iterator().next() != 8 || iterableWide.iterator().next() != 8) {
                        throw Error("library iterable exact/fallback")
                    }

                    val collectionExact = libraryCollection()
                    val collectionWide: Collection<Any?> = collectionExact
                    if (collectionExact !== collectionWide) throw Error("library collection identity")
                    if (!collectionExact.contains(41) || !collectionWide.contains(41)) {
                        throw Error("library collection exact/fallback")
                    }
                    if (collectionWide.contains("wrong") || collectionWide.contains(null)) {
                        throw Error("library collection wrong-shape barrier")
                    }
                    if (collectionExact.size != 1 || collectionExact.isEmpty()) {
                        throw Error("library collection declared calls")
                    }
                    if (!collectionExact.containsAll(libraryCollection())) {
                        throw Error("library collection containsAll")
                    }
                    if (collectionExact.iterator().next() != 41) {
                        throw Error("library collection iterator")
                    }

                    val listIteratorExact = libraryListIterator()
                    val listIteratorWide: ListIterator<Any?> = listIteratorExact
                    if (listIteratorExact !== listIteratorWide) throw Error("library list iterator identity")
                    if (listIteratorExact.next() != 41 || listIteratorWide.previous() != 41) {
                        throw Error("library list iterator exact/fallback")
                    }

                    val listExact = libraryList()
                    val listWide: List<Any?> = listExact
                    if (listExact !== listWide) throw Error("library list identity")
                    if (listExact.get(0) != 41 || listWide.get(0) != 41) {
                        throw Error("library list exact/fallback get")
                    }
                    if (!listExact.contains(41) || listWide.indexOf(41) != 0 || listWide.lastIndexOf(41) != 0) {
                        throw Error("library list exact/fallback search")
                    }
                    if (listWide.contains("wrong") || listWide.indexOf("wrong") != -1 ||
                        listWide.lastIndexOf(null) != -1
                    ) {
                        throw Error("library list wrong-shape barrier")
                    }
                    if (listExact.listIterator().next() != 41 ||
                        listExact.listIterator(1).previous() != 41 ||
                        listWide.subList(0, 1).get(0) != 41
                    ) {
                        throw Error("library list nested canonical results")
                    }
                    val listAsCollection: Collection<Int> = listExact
                    if (!listAsCollection.contains(41)) throw Error("library list exact Collection super-view")

                    val localCell = LocalCell(5)
                    increment(localCell)
                    if (localCell.value != 6) throw Error("consumer exact implementation")

                    val remoteCell = libraryCell()
                    remoteCell.value = 2
                    increment(remoteCell)
                    val remoteWide: VariantCell<Any> = remoteCell
                    if (remoteCell !== remoteWide || remoteWide.value != 3) {
                        throw Error("exact property identity/fallback")
                    }
                    remoteWide.value = 4
                    if (remoteCell.value != 4) throw Error("erased setter fallback")

                    val sink = LocalAnyConsumer()
                    consumeSeven(sink)
                    if (sink.seen != "7") throw Error("contravariant callback")
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("CrossConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CrossConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )
        runDotNet(
            dotnetHost,
            consumerAssembly,
            consumerDirectory,
            "Generic-interface cross-module consumer failed",
        )

        val producerSlot = declarationIndex.values
            .filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single {
                it.ownerPath.last() == "cross.Producer" &&
                        it.methodName.startsWith("produce__KotlinErased__")
            }
        val readAsAny = declarationIndex.values
            .filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { it.methodName == "readAsAny" }
        val rawConsumerIl = libraryDirectory.resolve("CanonicalOnlyConsumer.il").apply {
            writeText(
                """
                .assembly extern mscorlib {}
                .assembly extern Cross.Library
                {
                  .ver 1:0:0:0
                }
                .assembly extern Kotlin.Runtime
                {
                  .ver 1:0:0:0
                }
                .assembly CanonicalOnlyConsumer {}
                .module CanonicalOnlyConsumer.dll

                .class private auto ansi sealed beforefieldinit 'CanonicalOnlyProducer'
                       extends [mscorlib]System.Object
                       implements [Cross.Library]'cross.Producer'
                {
                  .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
                  {
                    .maxstack 1
                    ldarg.0
                    call instance void [mscorlib]System.Object::.ctor()
                    ret
                  }

                  .method private hidebysig newslot virtual final instance object '${producerSlot.methodName}'() cil managed
                  {
                    .override method instance object [Cross.Library]'cross.Producer'::'${producerSlot.methodName}'()
                    .maxstack 1
                    ldc.i4.s 73
                    box [mscorlib]System.Int32
                    ret
                  }
                }

                .method public static void Main() cil managed
                {
                  .entrypoint
                  .maxstack 3
                  .locals init (
                    class [Cross.Library]'cross.LibraryIterator' V_0,
                    class [Cross.Library]'cross.LibraryCollection' V_1,
                    class [Cross.Library]'cross.LibraryCollection' V_2,
                    class [Cross.Library]'cross.LibraryListIterator' V_3,
                    class [Cross.Library]'cross.LibraryList' V_4
                  )
                  newobj instance void 'CanonicalOnlyProducer'::.ctor()
                  call object [Cross.Library]'${readAsAny.ownerPath.single()}'::'${readAsAny.methodName}'(
                      class [Cross.Library]'cross.Producer'
                  )
                  unbox.any [mscorlib]System.Int32
                  ldc.i4.s 73
                  bne.un IL_failure
                  ldc.i4.8
                  newobj instance void [Cross.Library]'cross.LibraryIterator'::.ctor(int32)
                  stloc.0
                  ldloc.0
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<int32>::'Next'()
                  ldc.i4.8
                  bne.un IL_failure
                  ldloc.0
                  callvirt instance object [Kotlin.Runtime]'Kotlin.Collections.Iterator'::'Next'()
                  unbox.any [mscorlib]System.Int32
                  ldc.i4.s 9
                  bne.un IL_failure
                  ldc.i4.s 41
                  newobj instance void [Cross.Library]'cross.LibraryCollection'::.ctor(int32)
                  stloc.1
                  ldc.i4.s 41
                  newobj instance void [Cross.Library]'cross.LibraryCollection'::.ctor(int32)
                  stloc.2
                  ldloc.1
                  ldc.i4.s 41
                  callvirt instance bool class [Kotlin.Runtime]'Kotlin.Collections.Collection__KotlinExact`1'<int32>::'Contains'(!0)
                  brfalse IL_failure
                  ldloc.1
                  ldc.i4.s 41
                  box [mscorlib]System.Int32
                  callvirt instance bool [Kotlin.Runtime]'Kotlin.Collections.Collection'::'ContainsErased'(object)
                  brfalse IL_failure
                  ldloc.1
                  ldstr "wrong"
                  callvirt instance bool [Kotlin.Runtime]'Kotlin.Collections.Collection'::'ContainsErased'(object)
                  brtrue IL_failure
                  ldloc.1
                  ldnull
                  callvirt instance bool [Kotlin.Runtime]'Kotlin.Collections.Collection'::'ContainsErased'(object)
                  brtrue IL_failure
                  ldloc.1
                  callvirt instance int32 class [Kotlin.Runtime]'Kotlin.Collections.Collection`1'<int32>::'get_Size'()
                  ldc.i4.1
                  bne.un IL_failure
                  ldloc.1
                  callvirt instance bool class [Kotlin.Runtime]'Kotlin.Collections.Collection`1'<int32>::'IsEmpty'()
                  brtrue IL_failure
                  ldloc.1
                  ldloc.2
                  callvirt instance bool class [Kotlin.Runtime]'Kotlin.Collections.Collection`1'<int32>::'ContainsAll'(
                    class [Kotlin.Runtime]'Kotlin.Collections.Collection'
                  )
                  brfalse IL_failure
                  ldloc.1
                  callvirt instance class [Kotlin.Runtime]'Kotlin.Collections.Iterator' class [Kotlin.Runtime]'Kotlin.Collections.Collection`1'<int32>::'GetIterator'()
                  castclass class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<int32>
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<int32>::'Next'()
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldloc.1
                  castclass class [Kotlin.Runtime]'Kotlin.Collections.Iterable`1'<int32>
                  callvirt instance class [Kotlin.Runtime]'Kotlin.Collections.Iterator' class [Kotlin.Runtime]'Kotlin.Collections.Iterable`1'<int32>::'GetIterator'()
                  castclass class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<int32>
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<int32>::'Next'()
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldc.i4.s 41
                  ldc.i4.1
                  ldc.i4.0
                  newobj instance void [Cross.Library]'cross.LibraryListIterator'::.ctor(int32, int32, int32)
                  stloc.3
                  ldloc.3
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.ListIterator`1'<int32>::'Next'()
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldloc.3
                  callvirt instance object [Kotlin.Runtime]'Kotlin.Collections.ListIterator'::'Previous'()
                  unbox.any [mscorlib]System.Int32
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldc.i4.s 41
                  newobj instance void [Cross.Library]'cross.LibraryList'::.ctor(int32)
                  stloc.s 4
                  ldloc.s 4
                  ldc.i4.0
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.List`1'<int32>::'Get'(int32)
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldloc.s 4
                  ldc.i4.0
                  callvirt instance object [Kotlin.Runtime]'Kotlin.Collections.List'::'Get'(int32)
                  unbox.any [mscorlib]System.Int32
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldloc.s 4
                  ldc.i4.s 41
                  callvirt instance int32 class [Kotlin.Runtime]'Kotlin.Collections.List__KotlinExact`1'<int32>::'IndexOf'(!0)
                  brtrue IL_failure
                  ldloc.s 4
                  ldstr "wrong"
                  callvirt instance int32 [Kotlin.Runtime]'Kotlin.Collections.List'::'IndexOfErased'(object)
                  ldc.i4.m1
                  bne.un IL_failure
                  ldloc.s 4
                  ldnull
                  callvirt instance int32 [Kotlin.Runtime]'Kotlin.Collections.List'::'LastIndexOfErased'(object)
                  ldc.i4.m1
                  bne.un IL_failure
                  ldloc.s 4
                  callvirt instance class [Kotlin.Runtime]'Kotlin.Collections.ListIterator' class [Kotlin.Runtime]'Kotlin.Collections.List`1'<int32>::'GetListIterator'()
                  castclass class [Kotlin.Runtime]'Kotlin.Collections.ListIterator`1'<int32>
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.ListIterator`1'<int32>::'Next'()
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldloc.s 4
                  ldc.i4.0
                  ldc.i4.1
                  callvirt instance class [Kotlin.Runtime]'Kotlin.Collections.List' class [Kotlin.Runtime]'Kotlin.Collections.List`1'<int32>::'SubList'(int32, int32)
                  castclass class [Kotlin.Runtime]'Kotlin.Collections.List`1'<int32>
                  ldc.i4.0
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.List`1'<int32>::'Get'(int32)
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldloc.s 4
                  ldc.i4.s 41
                  callvirt instance bool class [Kotlin.Runtime]'Kotlin.Collections.List__KotlinExact`1'<int32>::'Contains'(!0)
                  brfalse IL_failure
                  ldloc.s 4
                  ldstr "wrong"
                  callvirt instance bool [Kotlin.Runtime]'Kotlin.Collections.List'::'ContainsErased'(object)
                  brtrue IL_failure
                  ldloc.s 4
                  ldc.i4.s 41
                  callvirt instance bool class [Kotlin.Runtime]'Kotlin.Collections.Collection__KotlinExact`1'<int32>::'Contains'(!0)
                  brtrue IL_success
                IL_failure:
                  ldstr "Canonical-only generic-interface fallback returned an unexpected result."
                  newobj instance void [mscorlib]System.Exception::.ctor(string)
                  throw
                IL_success:
                  ret
                }
                """.trimIndent()
            )
        }
        val rawConsumerAssembly = libraryDirectory.resolve("CanonicalOnlyConsumer.dll")
        assertTrue(
            DotNetIlAssembler.assembleExecutable(
                rawConsumerIl,
                rawConsumerAssembly,
                DotNetTarget.NET10_0,
                MessageCollector.NONE,
            )
        )
        consumerDirectory.resolve("Kotlin.Runtime.dll")
            .copyTo(libraryDirectory.resolve("Kotlin.Runtime.dll"), overwrite = true)
        runDotNet(
            dotnetHost,
            rawConsumerAssembly,
            libraryDirectory,
            "Canonical-only generic-interface consumer failed",
        )
    }

    @Test
    fun testHighArityGenericInterfaceAbiHasNoFixedMask() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val csharpCompiler = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(csharpCompiler != null, ".NET Framework C# compiler is not available")
        val frameworkNetStandardFacade = findFrameworkNetStandardFacade()
        requireOrAssumeToolchain(
            frameworkNetStandardFacade != null,
            ".NET Framework netstandard 2.0 facade is not available",
        )
        val dotnetHost = modernDotNetHostOrSkip()

        val parameterNames = (0..64).map { index -> "T${index.toString().padStart(2, '0')}" }
        val parameterDeclarations = parameterNames.joinToString { parameter -> "out $parameter" }
        val exactArguments = parameterNames.mapIndexed { index, _ ->
            if (index == parameterNames.lastIndex) "Int" else "String"
        }.joinToString()
        val widenedArguments = parameterNames.joinToString { "Any?" }

        val libraryDirectory = File(tmpdir, "wide-generic-interface-library")
        val librarySource = File(tmpdir, "wideLibrary.kt").apply {
            writeText(
                """
                package wide

                public interface Wide<$parameterDeclarations> {
                    public fun first(): T00
                    public fun last(): T64
                    public fun acceptsLast(value: @UnsafeVariance T64): Boolean
                }

                public class WideImpl : Wide<$exactArguments> {
                    override fun first(): String = "first"
                    override fun last(): Int = 64
                    override fun acceptsLast(value: Int): Boolean = value == 64
                }

                public fun newWide(): Wide<$exactArguments> = WideImpl()

                public fun widen(value: Wide<$exactArguments>): Wide<$widenedArguments> = value

                public fun sameAfterWiden(value: Wide<$exactArguments>): Boolean = widen(value) === value

                public fun readWide(value: Wide<$widenedArguments>): String =
                    value.first().toString() + ":" + value.last().toString()

                public fun acceptWide(value: Wide<$widenedArguments>, candidate: Any?): Boolean =
                    value.acceptsLast(candidate)

                public interface Quad<in I, out O, X, out N> {
                    public fun run(input: I, state: X): O
                    public fun nullable(): N
                    public fun acceptsOutput(value: @UnsafeVariance O): Boolean
                }

                public class QuadImpl : Quad<Any, Int, String, Int?> {
                    override fun run(input: Any, state: String): Int = 4
                    override fun nullable(): Int? = 7
                    override fun acceptsOutput(value: Int): Boolean = value == 4
                }

                public fun newQuad(): Quad<Any, Int, String, Int?> = QuadImpl()

                public fun widenQuad(value: Quad<Any, Int, String, Int?>): Quad<Int, Any, String, Any?> = value

                public fun <I, O, X, N> passQuad(value: Quad<I, O, X, N>): Quad<I, O, X, N> = value

                public fun sameQuad(value: Quad<Any, Int, String, Int?>): Boolean =
                    widenQuad(value) === value && passQuad(value) === value

                public fun readQuad(value: Quad<Int, Any, String, Any?>): String =
                    value.run(12, "ab").toString() + ":" + (value.nullable()?.toString() ?: "null")

                public fun acceptQuad(value: Quad<Int, Any, String, Any?>, candidate: Any): Boolean =
                    value.acceptsOutput(candidate)

                public interface IntersectionMarker

                public class IntersectionMarkerImpl : IntersectionMarker

                public interface OwnerBoundLeft<T> {
                    public fun <R : T> retain(value: R): R
                }

                public interface OwnerBoundRight<T> {
                    public fun <R : T> retain(value: R): R
                }

                public interface OwnerBoundIntersection<T> :
                    OwnerBoundLeft<T>, OwnerBoundRight<T>

                public class OwnerBoundImpl : OwnerBoundIntersection<IntersectionMarker> {
                    override fun <R : IntersectionMarker> retain(value: R): R = value
                }

                public fun newOwnerBound(): OwnerBoundIntersection<IntersectionMarker> = OwnerBoundImpl()

                public fun exerciseOwnerBound(
                    value: OwnerBoundIntersection<IntersectionMarker>,
                    marker: IntersectionMarkerImpl,
                ): Boolean {
                    val left: OwnerBoundLeft<IntersectionMarker> = value
                    val right: OwnerBoundRight<IntersectionMarker> = value
                    return left.retain(marker) === marker && right.retain(marker) === marker &&
                        value.retain(marker) === marker && left === value && right === value
                }

                public interface VariantOwnerBoundLeft<out T> {
                    public fun <R : @UnsafeVariance T> retainVariant(value: R): R
                }

                public interface VariantOwnerBoundRight<out T> {
                    public fun <R : @UnsafeVariance T> retainVariant(value: R): R
                }

                public interface VariantOwnerBoundIntersection<out T> :
                    VariantOwnerBoundLeft<T>, VariantOwnerBoundRight<T>

                public class VariantOwnerBoundImpl :
                    VariantOwnerBoundIntersection<IntersectionMarker> {
                    override fun <R : IntersectionMarker> retainVariant(value: R): R = value
                }

                public fun newVariantOwnerBound(): VariantOwnerBoundIntersection<IntersectionMarker> =
                    VariantOwnerBoundImpl()

                public fun exerciseVariantOwnerBound(
                    value: VariantOwnerBoundIntersection<IntersectionMarker>,
                    marker: IntersectionMarkerImpl,
                ): Boolean {
                    val left: VariantOwnerBoundLeft<IntersectionMarker> = value
                    val right: VariantOwnerBoundRight<IntersectionMarker> = value
                    return left.retainVariant(marker) === marker &&
                        right.retainVariant(marker) === marker &&
                        value.retainVariant(marker) === marker && left === value && right === value
                }

                public interface CovariantWide<out T> {
                    public fun covariantResult(): Any?
                }

                public interface CovariantNarrow<out T> {
                    public fun covariantResult(): T
                }

                public interface CovariantIntersection<out T> :
                    CovariantWide<T>, CovariantNarrow<T>

                public class CovariantIntersectionImpl(
                    private val marker: IntersectionMarkerImpl,
                ) : CovariantIntersection<IntersectionMarker> {
                    override fun covariantResult(): IntersectionMarkerImpl = marker
                }

                public fun newCovariantIntersection(
                    marker: IntersectionMarkerImpl,
                ): CovariantIntersection<IntersectionMarker> = CovariantIntersectionImpl(marker)

                public fun exerciseCovariantIntersection(
                    value: CovariantIntersection<IntersectionMarker>,
                    marker: IntersectionMarkerImpl,
                ): Boolean {
                    val wide: CovariantWide<IntersectionMarker> = value
                    val narrow: CovariantNarrow<IntersectionMarker> = value
                    return wide.covariantResult() === marker &&
                        narrow.covariantResult() === marker &&
                        value.covariantResult() === marker
                }

                public interface IntersectionLeft<out T> {
                    public val label: T
                    public fun read(): T
                    public fun readAt(index: Int): T
                    public fun <R : IntersectionMarker> readGeneric(value: R): T
                }

                public interface IntersectionRight<out T> {
                    public val label: T
                    public fun read(): T
                    public fun readAt(index: Int): T
                    public fun <R : IntersectionMarker> readGeneric(value: R): T
                }

                public interface Intersection<out T> : IntersectionLeft<T>, IntersectionRight<T>

                public class IntersectionImpl : Intersection<Int> {
                    override val label: Int = 79
                    override fun read(): Int = 73
                    override fun readAt(index: Int): Int = 73 + index
                    override fun <R : IntersectionMarker> readGeneric(value: R): Int = 73
                }

                public fun newIntersection(): Intersection<Int> = IntersectionImpl()

                public fun sameIntersection(value: Intersection<Int>): Boolean {
                    val left: IntersectionLeft<Int> = value
                    val right: IntersectionRight<Int> = value
                    return left === value && right === value
                }

                public fun readIntersection(value: Intersection<Int>): Int {
                    val left: IntersectionLeft<Int> = value
                    val right: IntersectionRight<Int> = value
                    val marker = IntersectionMarkerImpl()
                    return left.label + right.label + value.label +
                        left.readAt(1) + right.readAt(2) + value.readAt(3) +
                        left.readGeneric(marker) + right.readGeneric(marker) + value.readGeneric(marker)
                }

                public interface MutableIntersectionLeft<T> {
                    public var mutableLabel: T
                }

                public interface MutableIntersectionRight<T> {
                    public var mutableLabel: T
                }

                public interface MutableIntersection<T> :
                    MutableIntersectionLeft<T>, MutableIntersectionRight<T>

                public class MutableIntersectionImpl : MutableIntersection<String> {
                    override var mutableLabel: String = "initial"
                }

                public fun newMutableIntersection(): MutableIntersection<String> = MutableIntersectionImpl()

                public fun exerciseMutableIntersection(value: MutableIntersection<String>): Boolean {
                    val left: MutableIntersectionLeft<String> = value
                    val right: MutableIntersectionRight<String> = value
                    left.mutableLabel = "producer"
                    return right.mutableLabel == "producer" && value.mutableLabel == "producer" &&
                        left === value && right === value
                }

                public interface SplitMutableIntersectionLeft<out T> {
                    public var splitLabel: @UnsafeVariance T
                }

                public interface SplitMutableIntersectionRight<out T> {
                    public var splitLabel: @UnsafeVariance T
                }

                public interface SplitMutableIntersection<out T> :
                    SplitMutableIntersectionLeft<T>, SplitMutableIntersectionRight<T>

                public class SplitMutableIntersectionImpl : SplitMutableIntersection<String> {
                    override var splitLabel: String = "split-initial"
                }

                public fun newSplitMutableIntersection(): SplitMutableIntersection<String> =
                    SplitMutableIntersectionImpl()

                public fun exerciseSplitMutableIntersection(
                    value: SplitMutableIntersection<String>,
                ): Boolean {
                    val left: SplitMutableIntersectionLeft<String> = value
                    val right: SplitMutableIntersectionRight<String> = value
                    value.splitLabel = "split-producer"
                    return left.splitLabel == "split-producer" &&
                        right.splitLabel == "split-producer" && left === value && right === value
                }

                public interface ExactIntersectionLeft<out T> {
                    public fun acceptsExact(value: @UnsafeVariance T): Boolean
                }

                public interface ExactIntersectionRight<out T> {
                    public fun acceptsExact(value: @UnsafeVariance T): Boolean
                }

                public interface ExactIntersection<out T> :
                    ExactIntersectionLeft<T>, ExactIntersectionRight<T>

                public class ExactIntersectionImpl : ExactIntersection<Int> {
                    override fun acceptsExact(value: Int): Boolean = value == 101
                }

                public fun newExactIntersection(): ExactIntersection<Int> = ExactIntersectionImpl()

                public fun readExactIntersection(value: ExactIntersection<Int>): Boolean {
                    val left: ExactIntersectionLeft<Int> = value
                    val right: ExactIntersectionRight<Int> = value
                    return left.acceptsExact(101) && right.acceptsExact(101) && value.acceptsExact(101)
                }

                public interface PermutedIntersectionLeft<in A, out B> {
                    public fun permute(value: A): B
                }

                public interface PermutedIntersectionRight<out R, in P> {
                    public fun permute(value: P): R
                }

                public interface PermutedIntersection<in A, out B> :
                    PermutedIntersectionLeft<A, B>, PermutedIntersectionRight<B, A>

                public class PermutedIntersectionImpl : PermutedIntersection<String, Int> {
                    override fun permute(value: String): Int = 83
                }

                public fun newPermutedIntersection(): PermutedIntersection<String, Int> =
                    PermutedIntersectionImpl()

                public fun readPermutedIntersection(
                    value: PermutedIntersection<String, Int>,
                    input: String,
                ): Int {
                    val left: PermutedIntersectionLeft<String, Int> = value
                    val right: PermutedIntersectionRight<Int, String> = value
                    return left.permute(input) + right.permute(input) + value.permute(input)
                }

                public interface IndirectIntersectionLeftBase<out T> {
                    public fun indirect(): T
                }

                public interface IndirectIntersectionLeft<out T> : IndirectIntersectionLeftBase<T>

                public interface IndirectIntersectionRightBase<out T> {
                    public fun indirect(): T
                }

                public interface IndirectIntersectionRight<out T> : IndirectIntersectionRightBase<T>

                public interface IndirectIntersection<out T> :
                    IndirectIntersectionLeft<T>, IndirectIntersectionRight<T>

                public class IndirectIntersectionImpl : IndirectIntersection<Int> {
                    override fun indirect(): Int = 89
                }

                public fun newIndirectIntersection(): IndirectIntersection<Int> = IndirectIntersectionImpl()

                public fun readIndirectIntersection(value: IndirectIntersection<Int>): Int {
                    val left: IndirectIntersectionLeftBase<Int> = value
                    val right: IndirectIntersectionRightBase<Int> = value
                    return left.indirect() + right.indirect() + value.indirect()
                }

                public interface IndirectIntersectionDescendant<out T> : IndirectIntersection<T>

                public class IndirectIntersectionDescendantImpl : IndirectIntersectionDescendant<Int> {
                    override fun indirect(): Int = 97
                }

                public fun newIndirectIntersectionDescendant(): IndirectIntersectionDescendant<Int> =
                    IndirectIntersectionDescendantImpl()
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Wide.Generic",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("Wide.Generic.klib")
        val libraryAssembly = libraryDirectory.resolve("Wide.Generic.dll")
        val libraryIl = libraryDirectory.resolve("Wide.Generic.il").readText()
        assertTrue("'wide.Wide`65'" in libraryIl) { libraryIl }
        assertTrue("'wide.Wide__KotlinExact`65'" in libraryIl) { libraryIl }
        assertTrue("'wide.Quad`4'<- 'I', + 'O', 'X', + 'N'>" in libraryIl) { libraryIl }
        assertTrue("'wide.Quad__KotlinExact`4'<'I', 'O', 'X', 'N'>" in libraryIl) { libraryIl }
        assertTrue(
            "implements 'wide.Intersection', class 'wide.IntersectionLeft`1'<!0>, " +
                    "class 'wide.IntersectionRight`1'<!0>" in libraryIl
        ) { libraryIl }
        assertTrue("<GenericInterfaceDeclaredBridge-wide.IntersectionLeft-read-" in libraryIl) { libraryIl }
        assertTrue("<GenericInterfaceDeclaredBridge-wide.IntersectionRight-read-" in libraryIl) { libraryIl }
        assertTrue("<GenericInterfaceDeclaredBridge-wide.IntersectionLeft-<get-label>-" in libraryIl) { libraryIl }
        assertTrue("<GenericInterfaceDeclaredBridge-wide.IntersectionRight-<get-label>-" in libraryIl) { libraryIl }
        assertTrue("<GenericInterfaceDeclaredBridge-wide.IntersectionLeft-readAt-" in libraryIl) { libraryIl }
        assertTrue("<GenericInterfaceDeclaredBridge-wide.IntersectionRight-readAt-" in libraryIl) { libraryIl }
        assertTrue("<GenericInterfaceDeclaredBridge-wide.IntersectionLeft-readGeneric-" in libraryIl) { libraryIl }
        assertTrue("<GenericInterfaceDeclaredBridge-wide.IntersectionRight-readGeneric-" in libraryIl) { libraryIl }
        assertTrue(
            ".override method instance !0 class 'wide.Intersection`1'<int32>::'read'()" in libraryIl
        ) { libraryIl }
        assertTrue(
            ".override method instance !0 class 'wide.Intersection`1'<int32>::'get_label'()" in libraryIl
        ) { libraryIl }
        assertTrue(".property instance !0 'label'()" in libraryIl) { libraryIl }
        assertTrue(
            ".override method instance !0 class 'wide.Intersection`1'<int32>::'readAt'(int32)" in libraryIl
        ) { libraryIl }
        assertTrue(
            ".override method instance !0 class 'wide.Intersection`1'<int32>::'readGeneric'<[1]>(!!0)" in libraryIl
        ) { libraryIl }
        assertTrue(
            "'readGeneric'<(class 'wide.IntersectionMarker') 'R'>(!!0 'value')" in libraryIl
        ) { libraryIl }
        val allIntersectionSlots = DotNetLibraryAbiCodec.decode(metadataLibrary.readKlibManifest())
            .values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericInterfaceIntersectionSlot>()
        val ownerBoundSlots = allIntersectionSlots.filter { slot ->
            slot.ownerPath == listOf("wide.OwnerBoundIntersection`1")
        }
        assertEquals(setOf("retain"), ownerBoundSlots.map { slot -> slot.methodName }.toSet())
        assertEquals(DotNetInterfaceDefaultPromotionView.DECLARED, ownerBoundSlots.single().physicalView)
        assertEquals(2, ownerBoundSlots.single().contributingLogicalMemberKeys.size)
        assertTrue(
            ".override method instance !!0 class 'wide.OwnerBoundIntersection`1'<" +
                    "class 'wide.IntersectionMarker'>::'retain'<[1]>(!!0)" in libraryIl
        ) { libraryIl }
        val variantOwnerBoundSlots = allIntersectionSlots.filter { slot ->
            slot.ownerPath == listOf("wide.VariantOwnerBoundIntersection__KotlinExact`1")
        }
        assertEquals(setOf("retainVariant"), variantOwnerBoundSlots.map { slot -> slot.methodName }.toSet())
        assertEquals(DotNetInterfaceDefaultPromotionView.EXACT, variantOwnerBoundSlots.single().physicalView)
        assertEquals(2, variantOwnerBoundSlots.single().contributingLogicalMemberKeys.size)
        assertTrue(allIntersectionSlots.none { slot ->
            slot.ownerPath == listOf("wide.VariantOwnerBoundIntersection`1")
        })
        assertTrue(
            ".override method instance !!0 class 'wide.VariantOwnerBoundIntersection__KotlinExact`1'<" +
                    "class 'wide.IntersectionMarker'>::'retainVariant'<[1]>(!!0)" in libraryIl
        ) { libraryIl }
        assertTrue("'retain'<(!0)" !in libraryIl && "'retainVariant'<(!0)" !in libraryIl) {
            "Owner-relative constraints must remain erased from split-interface CLR metadata:\n$libraryIl"
        }
        val covariantSlots = allIntersectionSlots.filter { slot ->
            slot.ownerPath == listOf("wide.CovariantIntersection`1")
        }
        assertEquals(setOf("covariantResult"), covariantSlots.map { slot -> slot.methodName }.toSet())
        assertEquals(DotNetInterfaceDefaultPromotionView.DECLARED, covariantSlots.single().physicalView)
        assertEquals(2, covariantSlots.single().contributingLogicalMemberKeys.size)
        assertTrue(
            ".override method instance !0 class 'wide.CovariantIntersection`1'<" +
                    "class 'wide.IntersectionMarker'>::'covariantResult'()" in libraryIl
        ) { libraryIl }
        val intersectionSlots = allIntersectionSlots.filter { slot ->
            slot.ownerPath == listOf("wide.Intersection`1")
        }
        assertEquals(
            setOf("get_label", "read", "readAt", "readGeneric"),
            intersectionSlots.map { slot -> slot.methodName }.toSet(),
        )
        intersectionSlots.forEach { intersectionSlot ->
            assertEquals(listOf("wide.Intersection`1"), intersectionSlot.ownerPath)
            assertEquals(DotNetInterfaceDefaultPromotionView.DECLARED, intersectionSlot.physicalView)
            assertEquals(2, intersectionSlot.contributingLogicalMemberKeys.size)
        }
        val mutableIntersectionSlots = allIntersectionSlots.filter { slot ->
            slot.ownerPath == listOf("wide.MutableIntersection`1")
        }
        assertEquals(
            setOf("get_mutableLabel", "set_mutableLabel"),
            mutableIntersectionSlots.map { slot -> slot.methodName }.toSet(),
        )
        mutableIntersectionSlots.forEach { slot ->
            assertEquals(DotNetInterfaceDefaultPromotionView.DECLARED, slot.physicalView)
            assertEquals(2, slot.contributingLogicalMemberKeys.size)
        }
        assertTrue(".property instance !0 'mutableLabel'()" in libraryIl) { libraryIl }
        assertTrue(
            ".override method instance !0 class 'wide.MutableIntersection`1'<string>::'get_mutableLabel'()" in
                    libraryIl
        ) { libraryIl }
        assertTrue(
            ".override method instance void class 'wide.MutableIntersection`1'<string>::" +
                    "'set_mutableLabel'(!0)" in libraryIl
        ) { libraryIl }
        val splitMutableIntersectionSlots = allIntersectionSlots.filter { slot ->
            slot.ownerPath.single() in setOf(
                "wide.SplitMutableIntersection`1",
                "wide.SplitMutableIntersection__KotlinExact`1",
            )
        }
        assertEquals(3, splitMutableIntersectionSlots.size, splitMutableIntersectionSlots.joinToString("\n"))
        assertEquals(
            setOf("get_splitLabel"),
            splitMutableIntersectionSlots
                .filter { slot -> slot.physicalView == DotNetInterfaceDefaultPromotionView.DECLARED }
                .mapTo(hashSetOf()) { slot -> slot.methodName },
        )
        assertEquals(
            setOf("get_splitLabel", "set_splitLabel"),
            splitMutableIntersectionSlots
                .filter { slot -> slot.physicalView == DotNetInterfaceDefaultPromotionView.EXACT }
                .mapTo(hashSetOf()) { slot -> slot.methodName },
        )
        assertTrue(splitMutableIntersectionSlots.all { slot ->
            slot.contributingLogicalMemberKeys.size == 2
        })
        val exactIntersectionSlots = allIntersectionSlots.filter { slot ->
            slot.ownerPath == listOf("wide.ExactIntersection__KotlinExact`1")
        }
        assertEquals(setOf("acceptsExact"), exactIntersectionSlots.map { slot -> slot.methodName }.toSet())
        assertEquals(DotNetInterfaceDefaultPromotionView.EXACT, exactIntersectionSlots.single().physicalView)
        assertEquals(2, exactIntersectionSlots.single().contributingLogicalMemberKeys.size)
        assertTrue(allIntersectionSlots.none { slot ->
            slot.ownerPath == listOf("wide.ExactIntersection`1")
        })
        assertTrue(
            ".override method instance bool class 'wide.ExactIntersection__KotlinExact`1'<int32>::" +
                    "'acceptsExact'(!0)" in libraryIl
        ) { libraryIl }
        val permutedIntersectionSlots = allIntersectionSlots.filter { slot ->
            slot.ownerPath == listOf("wide.PermutedIntersection`2")
        }
        assertEquals(setOf("permute"), permutedIntersectionSlots.map { slot -> slot.methodName }.toSet())
        assertEquals(2, permutedIntersectionSlots.single().contributingLogicalMemberKeys.size)
        assertTrue(
            ".override method instance !1 class 'wide.PermutedIntersection`2'<string, int32>::" +
                    "'permute'(!0)" in libraryIl
        ) { libraryIl }
        val indirectIntersectionSlots = allIntersectionSlots.filter { slot ->
            slot.ownerPath == listOf("wide.IndirectIntersection`1")
        }
        assertEquals(setOf("indirect"), indirectIntersectionSlots.map { slot -> slot.methodName }.toSet())
        assertEquals(2, indirectIntersectionSlots.single().contributingLogicalMemberKeys.size)
        assertTrue(
            ".override method instance !0 class 'wide.IndirectIntersection`1'<int32>::'indirect'()" in libraryIl
        ) { libraryIl }
        assertTrue(allIntersectionSlots.none { slot ->
            slot.ownerPath == listOf("wide.IndirectIntersectionDescendant`1")
        })

        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = libraryDirectory.resolve("consumer-${target.replace('.', '-')}").apply { mkdirs() }
            val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
                writeText(
                    """
                    package wideconsumer

                    import wide.*

                    class LocalRefinedIntersection : Intersection<Any> {
                        override val label: String = "property"
                        override fun read(): String = "local"
                        override fun readAt(index: Int): String = "local:${'$'}index"
                        override fun <R : IntersectionMarker> readGeneric(value: R): String = "generic"
                    }

                    class LocalOwnerBound<T : IntersectionMarker> : OwnerBoundIntersection<T> {
                        override fun <R : T> retain(value: R): R = value
                    }

                    class LocalVariantOwnerBound<T : IntersectionMarker> :
                        VariantOwnerBoundIntersection<T> {
                        override fun <R : T> retainVariant(value: R): R = value
                    }

                    class LocalCovariantIntersection(
                        private val marker: IntersectionMarkerImpl,
                    ) : CovariantIntersection<IntersectionMarker> {
                        override fun covariantResult(): IntersectionMarkerImpl = marker
                    }

                    class LocalMutableIntersection : MutableIntersection<String> {
                        override var mutableLabel: String = "local-initial"
                    }

                    class LocalSplitMutableIntersection : SplitMutableIntersection<String> {
                        override var splitLabel: String = "local-split-initial"
                    }

                    class LocalExactIntersection : ExactIntersection<Any> {
                        override fun acceptsExact(value: Any): Boolean = value == "exact"
                    }

                    class LocalPermutedIntersection : PermutedIntersection<Any, Any> {
                        override fun permute(value: Any): String = "permuted"
                    }

                    class LocalIndirectIntersection : IndirectIntersection<Any> {
                        override fun indirect(): String = "indirect"
                    }

                    fun main() {
                        val original = newWide()
                        val widened: Wide<$widenedArguments> = widen(original)
                        if (!sameAfterWiden(original) || widened !== original) {
                            throw Error("high-arity widening changed identity")
                        }
                        if (readWide(widened) != "first:64" || !acceptWide(widened, 64)) {
                            throw Error("high-index canonical fallback failed")
                        }
                        try {
                            acceptWide(widened, "wrong")
                            throw Error("unsafe high-index bridge accepted the wrong shape")
                        } catch (_: ClassCastException) {
                        }

                        val quad = newQuad()
                        val widenedQuad: Quad<Int, Any, String, Any?> = widenQuad(quad)
                        if (!sameQuad(quad) || widenedQuad !== quad || passQuad(quad) !== quad) {
                            throw Error("mixed four-parameter widening changed identity")
                        }
                        if (readQuad(widenedQuad) != "4:7" || !acceptQuad(widenedQuad, 4)) {
                            throw Error("mixed four-parameter fallback failed")
                        }
                        try {
                            acceptQuad(widenedQuad, "wrong")
                            throw Error("mixed exact bridge accepted the wrong shape")
                        } catch (_: ClassCastException) {
                        }

                        val intersection = newIntersection()
                        val left: IntersectionLeft<Int> = intersection
                        val right: IntersectionRight<Int> = intersection
                        if (!sameIntersection(intersection) || left !== intersection || right !== intersection) {
                            throw Error("intersection view changed identity")
                        }
                        if (left.read() != 73 || right.read() != 73 || readIntersection(intersection) != 681) {
                            throw Error("intersection slots did not share one implementation")
                        }

                        val refined: Intersection<Any> = LocalRefinedIntersection()
                        val refinedLeft: IntersectionLeft<Any> = refined
                        val refinedRight: IntersectionRight<Any> = refined
                        val marker = IntersectionMarkerImpl()
                        if (refined.label != "property" || refinedLeft.label != "property" ||
                            refinedRight.label != "property" || refined.read() != "local" ||
                            refinedLeft.read() != "local" ||
                            refinedRight.read() != "local" || refined.readAt(1) != "local:1" ||
                            refinedLeft.readAt(2) != "local:2" || refinedRight.readAt(3) != "local:3" ||
                            refined.readGeneric(marker) != "generic" ||
                            refinedLeft.readGeneric(marker) != "generic" ||
                            refinedRight.readGeneric(marker) != "generic"
                        ) {
                            throw Error("external intersection slot lost covariant refinement")
                        }

                        val ownerBound = newOwnerBound()
                        if (!exerciseOwnerBound(ownerBound, marker)) {
                            throw Error("producer owner-bound intersection dispatch failed")
                        }
                        val localOwnerBound: OwnerBoundIntersection<IntersectionMarker> =
                            LocalOwnerBound<IntersectionMarker>()
                        val localOwnerBoundLeft: OwnerBoundLeft<IntersectionMarker> = localOwnerBound
                        val localOwnerBoundRight: OwnerBoundRight<IntersectionMarker> = localOwnerBound
                        if (localOwnerBound.retain(marker) !== marker ||
                            localOwnerBoundLeft.retain(marker) !== marker ||
                            localOwnerBoundRight.retain(marker) !== marker
                        ) {
                            throw Error("external owner-bound intersection slots diverged")
                        }
                        val variantOwnerBound = newVariantOwnerBound()
                        if (!exerciseVariantOwnerBound(variantOwnerBound, marker)) {
                            throw Error("producer variant owner-bound intersection dispatch failed")
                        }
                        val localVariantOwnerBound: VariantOwnerBoundIntersection<IntersectionMarker> =
                            LocalVariantOwnerBound<IntersectionMarker>()
                        val localVariantOwnerBoundLeft: VariantOwnerBoundLeft<IntersectionMarker> =
                            localVariantOwnerBound
                        val localVariantOwnerBoundRight: VariantOwnerBoundRight<IntersectionMarker> =
                            localVariantOwnerBound
                        if (localVariantOwnerBound.retainVariant(marker) !== marker ||
                            localVariantOwnerBoundLeft.retainVariant(marker) !== marker ||
                            localVariantOwnerBoundRight.retainVariant(marker) !== marker
                        ) {
                            throw Error("external variant owner-bound intersection slots diverged")
                        }

                        val covariant = newCovariantIntersection(marker)
                        if (!exerciseCovariantIntersection(covariant, marker)) {
                            throw Error("producer covariant intersection dispatch failed")
                        }
                        val localCovariant: CovariantIntersection<IntersectionMarker> =
                            LocalCovariantIntersection(marker)
                        val localCovariantWide: CovariantWide<IntersectionMarker> = localCovariant
                        val localCovariantNarrow: CovariantNarrow<IntersectionMarker> = localCovariant
                        if (localCovariant.covariantResult() !== marker ||
                            localCovariantWide.covariantResult() !== marker ||
                            localCovariantNarrow.covariantResult() !== marker
                        ) {
                            throw Error("external covariant intersection slots diverged")
                        }

                        val mutable = newMutableIntersection()
                        if (!exerciseMutableIntersection(mutable) || mutable.mutableLabel != "producer") {
                            throw Error("producer mutable intersection dispatch failed")
                        }
                        val localMutable: MutableIntersection<String> = LocalMutableIntersection()
                        val localMutableLeft: MutableIntersectionLeft<String> = localMutable
                        val localMutableRight: MutableIntersectionRight<String> = localMutable
                        localMutable.mutableLabel = "consumer"
                        if (localMutableLeft.mutableLabel != "consumer" ||
                            localMutableRight.mutableLabel != "consumer"
                        ) {
                            throw Error("external mutable intersection slots diverged")
                        }

                        val splitMutable = newSplitMutableIntersection()
                        if (!exerciseSplitMutableIntersection(splitMutable) ||
                            splitMutable.splitLabel != "split-producer"
                        ) {
                            throw Error("producer split mutable intersection dispatch failed")
                        }
                        val localSplitMutable: SplitMutableIntersection<String> =
                            LocalSplitMutableIntersection()
                        val localSplitMutableLeft: SplitMutableIntersectionLeft<String> =
                            localSplitMutable
                        val localSplitMutableRight: SplitMutableIntersectionRight<String> =
                            localSplitMutable
                        localSplitMutable.splitLabel = "split-consumer"
                        if (localSplitMutableLeft.splitLabel != "split-consumer" ||
                            localSplitMutableRight.splitLabel != "split-consumer"
                        ) {
                            throw Error("external split mutable intersection slots diverged")
                        }

                        val exact = newExactIntersection()
                        if (!readExactIntersection(exact)) {
                            throw Error("producer exact intersection dispatch failed")
                        }
                        val localExact: ExactIntersection<Any> = LocalExactIntersection()
                        val localExactLeft: ExactIntersectionLeft<Any> = localExact
                        val localExactRight: ExactIntersectionRight<Any> = localExact
                        if (!localExact.acceptsExact("exact") || !localExactLeft.acceptsExact("exact") ||
                            !localExactRight.acceptsExact("exact")
                        ) {
                            throw Error("external exact intersection slots diverged")
                        }

                        val permuted = newPermutedIntersection()
                        if (readPermutedIntersection(permuted, "abcd") != 249) {
                            throw Error("producer permuted intersection dispatch failed")
                        }
                        val localPermuted: PermutedIntersection<Any, Any> = LocalPermutedIntersection()
                        val localPermutedLeft: PermutedIntersectionLeft<Any, Any> = localPermuted
                        val localPermutedRight: PermutedIntersectionRight<Any, Any> = localPermuted
                        if (localPermuted.permute(1) != "permuted" ||
                            localPermutedLeft.permute(2) != "permuted" ||
                            localPermutedRight.permute(3) != "permuted"
                        ) {
                            throw Error("external permuted intersection lost refined return")
                        }

                        val indirect = newIndirectIntersection()
                        if (readIndirectIntersection(indirect) != 267) {
                            throw Error("producer indirect intersection dispatch failed")
                        }
                        val localIndirect: IndirectIntersection<Any> = LocalIndirectIntersection()
                        val localIndirectLeft: IndirectIntersectionLeftBase<Any> = localIndirect
                        val localIndirectRight: IndirectIntersectionRightBase<Any> = localIndirect
                        if (localIndirect.indirect() != "indirect" ||
                            localIndirectLeft.indirect() != "indirect" ||
                            localIndirectRight.indirect() != "indirect"
                        ) {
                            throw Error("external indirect intersection lost refined return")
                        }
                        if (newIndirectIntersectionDescendant().indirect() != 97) {
                            throw Error("inherited selected intersection slot was not reused")
                        }
                    }
                    """.trimIndent()
                )
            }
            val application = consumerDirectory.resolve(
                if (target == "net48") "WideConsumer.exe" else "WideConsumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "WideConsumer",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )
            val consumerIl = consumerDirectory.resolve("WideConsumer.il").readText()
            assertTrue(
                ".override method instance !0 class [Wide.Generic]'wide.Intersection`1'<object>::'read'()" in
                        consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance !0 class [Wide.Generic]'wide.Intersection`1'<object>::'get_label'()" in
                        consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance !0 class [Wide.Generic]'wide.Intersection`1'<object>::'readAt'(int32)" in
                        consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance !0 class [Wide.Generic]'wide.Intersection`1'<object>::" +
                        "'readGeneric'<[1]>(!!0)" in consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance !!0 class [Wide.Generic]" +
                        "'wide.OwnerBoundIntersection`1'<!0>::'retain'<[1]>(!!0)" in consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance !!0 class [Wide.Generic]" +
                        "'wide.VariantOwnerBoundIntersection__KotlinExact`1'<!0>::" +
                        "'retainVariant'<[1]>(!!0)" in consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance !0 class [Wide.Generic]" +
                        "'wide.CovariantIntersection`1'<class [Wide.Generic]" +
                        "'wide.IntersectionMarker'>::'covariantResult'()" in consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance !0 class [Wide.Generic]'wide.MutableIntersection`1'<string>::" +
                        "'get_mutableLabel'()" in consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance void class [Wide.Generic]'wide.MutableIntersection`1'<string>::" +
                        "'set_mutableLabel'(!0)" in consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance !0 class [Wide.Generic]" +
                        "'wide.SplitMutableIntersection`1'<string>::'get_splitLabel'()" in consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance !0 class [Wide.Generic]" +
                        "'wide.SplitMutableIntersection__KotlinExact`1'<string>::'get_splitLabel'()" in consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance void class [Wide.Generic]" +
                        "'wide.SplitMutableIntersection__KotlinExact`1'<string>::'set_splitLabel'(!0)" in consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance bool class [Wide.Generic]" +
                        "'wide.ExactIntersection__KotlinExact`1'<object>::'acceptsExact'(!0)" in consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance !1 class [Wide.Generic]" +
                        "'wide.PermutedIntersection`2'<object, object>::'permute'(!0)" in consumerIl
            ) { consumerIl }
            assertTrue(
                ".override method instance !0 class [Wide.Generic]" +
                        "'wide.IndirectIntersection`1'<object>::'indirect'()" in consumerIl
            ) { consumerIl }

            if (target == "net10.0") {
                runDotNet(dotnetHost, application, consumerDirectory, "High-arity Kotlin consumer failed for $target")
            } else {
                val process = ProcessBuilder(application.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "High-arity Kotlin consumer failed for $target:\n$output")
            }

            libraryAssembly.copyTo(consumerDirectory.resolve(libraryAssembly.name), overwrite = true)
            val verifierSource = consumerDirectory.resolve("WideVerifier.cs").apply {
                writeText(
                    """
                    using System;
                    using System.Reflection;

                    public struct MarkerValue : wide.IntersectionMarker
                    {
                    }

                    public static class WideVerifier
                    {
                        private static void Require(bool condition, string message)
                        {
                            if (!condition) throw new Exception(message);
                        }

                        private static MethodInfo RequireMethod(Type facade, string name)
                        {
                            MethodInfo method = facade.GetMethod(name, BindingFlags.Public | BindingFlags.Static);
                            Require(method != null, name + " is unavailable");
                            return method;
                        }

                        public static int Main()
                        {
                            Assembly library = Assembly.LoadFrom("Wide.Generic.dll");
                            Type canonical = library.GetType("wide.Wide", true);
                            Type declared = library.GetType("wide.Wide`65", true);
                            Type exact = library.GetType("wide.Wide__KotlinExact`65", true);
                            Type implementation = library.GetType("wide.WideImpl", true);
                            Type quadCanonical = library.GetType("wide.Quad", true);
                            Type quadDeclared = library.GetType("wide.Quad`4", true);
                            Type quadExact = library.GetType("wide.Quad__KotlinExact`4", true);
                            Type quadImplementation = library.GetType("wide.QuadImpl", true);
                            Type intersectionCanonical = library.GetType("wide.Intersection", true);
                            Type facade = library.GetType("wide.wideLibraryKt", true);

                            Type[] declaredParameters = declared.GetGenericArguments();
                            Type[] exactParameters = exact.GetGenericArguments();
                            Require(declaredParameters.Length == 65, "declared arity");
                            Require(exactParameters.Length == 65, "exact arity");
                            Require(
                                (declaredParameters[0].GenericParameterAttributes &
                                    GenericParameterAttributes.VarianceMask) == GenericParameterAttributes.Covariant,
                                "first declared parameter variance");
                            Require(
                                (declaredParameters[64].GenericParameterAttributes &
                                    GenericParameterAttributes.VarianceMask) == GenericParameterAttributes.Covariant,
                                "last declared parameter variance");
                            Require(
                                (exactParameters[64].GenericParameterAttributes &
                                    GenericParameterAttributes.VarianceMask) == GenericParameterAttributes.None,
                                "exact parameter must be invariant");

                            bool hasExact = false;
                            foreach (Type implemented in implementation.GetInterfaces())
                            {
                                if (implemented.IsGenericType && implemented.GetGenericTypeDefinition() == exact)
                                    hasExact = true;
                            }
                            Require(hasExact, "implementation has no 65-parameter exact capability");

                            Type[] quadDeclaredParameters = quadDeclared.GetGenericArguments();
                            Type[] quadExactParameters = quadExact.GetGenericArguments();
                            GenericParameterAttributes varianceMask = GenericParameterAttributes.VarianceMask;
                            Require(quadDeclaredParameters.Length == 4, "quad declared arity");
                            Require(quadExactParameters.Length == 4, "quad exact arity");
                            Require(
                                (quadDeclaredParameters[0].GenericParameterAttributes & varianceMask) ==
                                    GenericParameterAttributes.Contravariant,
                                "quad input variance");
                            Require(
                                (quadDeclaredParameters[1].GenericParameterAttributes & varianceMask) ==
                                    GenericParameterAttributes.Covariant,
                                "quad output variance");
                            Require(
                                (quadDeclaredParameters[2].GenericParameterAttributes & varianceMask) ==
                                    GenericParameterAttributes.None,
                                "quad state variance");
                            Require(
                                (quadDeclaredParameters[3].GenericParameterAttributes & varianceMask) ==
                                    GenericParameterAttributes.Covariant,
                                "quad nullable variance");
                            for (int index = 0; index < quadExactParameters.Length; index++)
                            {
                                Require(
                                    (quadExactParameters[index].GenericParameterAttributes & varianceMask) ==
                                        GenericParameterAttributes.None,
                                    "quad exact parameter variance " + index);
                            }
                            bool hasQuadExact = false;
                            foreach (Type implemented in quadImplementation.GetInterfaces())
                            {
                                if (implemented.IsGenericType && implemented.GetGenericTypeDefinition() == quadExact)
                                    hasQuadExact = true;
                            }
                            Require(hasQuadExact, "quad implementation has no complete exact capability");
                            bool hasNullablePrimitiveExact = false;
                            foreach (Type implemented in quadImplementation.GetInterfaces())
                            {
                                if (!implemented.IsGenericType || implemented.GetGenericTypeDefinition() != quadExact)
                                    continue;
                                Type[] arguments = implemented.GetGenericArguments();
                                if (arguments[3] == typeof(Nullable<int>)) hasNullablePrimitiveExact = true;
                            }
                            Require(hasNullablePrimitiveExact, "quad nullable primitive exact argument");

                            MethodInfo create = RequireMethod(facade, "newWide");
                            MethodInfo same = RequireMethod(facade, "sameAfterWiden");
                            MethodInfo read = RequireMethod(facade, "readWide");
                            MethodInfo accept = RequireMethod(facade, "acceptWide");
                            object value = create.Invoke(null, null);
                            Require(canonical.IsInstanceOfType(value), "canonical identity");
                            Require((bool) same.Invoke(null, new object[] { value }), "widening identity");
                            Require((string) read.Invoke(null, new object[] { value }) == "first:64", "fallback result");
                            Require((bool) accept.Invoke(null, new object[] { value, 64 }), "fallback argument");
                            try
                            {
                                accept.Invoke(null, new object[] { value, "wrong" });
                                throw new Exception("wrong-shaped high-index argument was accepted");
                            }
                            catch (TargetInvocationException failure)
                            {
                                Require(failure.InnerException is InvalidCastException,
                                    "wrong-shaped high-index argument failure");
                            }

                            MethodInfo createQuad = RequireMethod(facade, "newQuad");
                            MethodInfo sameQuad = RequireMethod(facade, "sameQuad");
                            MethodInfo passQuad = RequireMethod(facade, "passQuad");
                            MethodInfo readQuad = RequireMethod(facade, "readQuad");
                            MethodInfo acceptQuad = RequireMethod(facade, "acceptQuad");
                            object quad = createQuad.Invoke(null, null);
                            Require(quadCanonical.IsInstanceOfType(quad), "quad canonical identity");
                            Require((bool) sameQuad.Invoke(null, new object[] { quad }), "quad widening identity");
                            MethodInfo closedPassQuad = passQuad.MakeGenericMethod(
                                typeof(object), typeof(int), typeof(string), typeof(Nullable<int>));
                            Require(Object.ReferenceEquals(quad, closedPassQuad.Invoke(null, new object[] { quad })),
                                "quad open pass-through identity");
                            Require((string) readQuad.Invoke(null, new object[] { quad }) == "4:7",
                                "quad mixed fallback result");
                            Require((bool) acceptQuad.Invoke(null, new object[] { quad, 4 }),
                                "quad exact fallback argument");
                            try
                            {
                                acceptQuad.Invoke(null, new object[] { quad, "wrong" });
                                throw new Exception("wrong-shaped quad argument was accepted");
                            }
                            catch (TargetInvocationException failure)
                            {
                                Require(failure.InnerException is InvalidCastException,
                                    "wrong-shaped quad argument failure");
                            }

                            MethodInfo createIntersection = RequireMethod(facade, "newIntersection");
                            MethodInfo sameIntersection = RequireMethod(facade, "sameIntersection");
                            MethodInfo readIntersection = RequireMethod(facade, "readIntersection");
                            object intersection = createIntersection.Invoke(null, null);
                            Require(intersectionCanonical.IsInstanceOfType(intersection),
                                "intersection canonical identity");
                            Require((bool) sameIntersection.Invoke(null, new object[] { intersection }),
                                "intersection parent identity");
                            wide.IntersectionLeft<int> left = (wide.IntersectionLeft<int>) intersection;
                            wide.IntersectionRight<int> right = (wide.IntersectionRight<int>) intersection;
                            wide.Intersection<int> derived = (wide.Intersection<int>) intersection;
                            wide.IntersectionMarkerImpl marker = new wide.IntersectionMarkerImpl();
                            Require(Object.ReferenceEquals(left, right) && Object.ReferenceEquals(left, derived),
                                "intersection C# identity");
                            Require(left.label == 79 && right.label == 79 && derived.label == 79,
                                "intersection property dispatch");
                            Require(left.read() == 73 && right.read() == 73 && derived.read() == 73 &&
                                left.readAt(1) == 74 && right.readAt(2) == 75 && derived.readAt(3) == 76,
                                "intersection parent dispatch");
                            Require(left.readGeneric(marker) == 73 && right.readGeneric(marker) == 73 &&
                                derived.readGeneric(marker) == 73, "intersection generic dispatch");
                            Require((int) readIntersection.Invoke(null, new object[] { intersection }) == 681,
                                "intersection Kotlin dispatch");

                            MethodInfo createOwnerBound = RequireMethod(facade, "newOwnerBound");
                            MethodInfo exerciseOwnerBound = RequireMethod(facade, "exerciseOwnerBound");
                            object ownerBoundObject = createOwnerBound.Invoke(null, null);
                            wide.OwnerBoundLeft<wide.IntersectionMarker> ownerBoundLeft =
                                (wide.OwnerBoundLeft<wide.IntersectionMarker>) ownerBoundObject;
                            wide.OwnerBoundRight<wide.IntersectionMarker> ownerBoundRight =
                                (wide.OwnerBoundRight<wide.IntersectionMarker>) ownerBoundObject;
                            wide.OwnerBoundIntersection<wide.IntersectionMarker> ownerBoundDerived =
                                (wide.OwnerBoundIntersection<wide.IntersectionMarker>) ownerBoundObject;
                            Require(Object.ReferenceEquals(ownerBoundLeft.retain(marker), marker) &&
                                Object.ReferenceEquals(ownerBoundRight.retain(marker), marker) &&
                                Object.ReferenceEquals(ownerBoundDerived.retain(marker), marker),
                                "owner-bound C# dispatch");
                            Require((bool) exerciseOwnerBound.Invoke(
                                null, new object[] { ownerBoundObject, marker }),
                                "owner-bound Kotlin dispatch");
                            MethodInfo ownerBoundMethod =
                                typeof(wide.OwnerBoundIntersection<wide.IntersectionMarker>).GetMethod("retain");
                            Require(ownerBoundMethod.GetGenericArguments()[0]
                                .GetGenericParameterConstraints().Length == 0,
                                "owner-bound physical constraint was not erased");
                            try {
                                ownerBoundDerived.retain<string>("wrong");
                                throw new Exception("owner-bound erased slot accepted an invalid value");
                            } catch (InvalidCastException) {
                            }
                            MarkerValue markerValue = new MarkerValue();
                            Require(ownerBoundDerived.retain<MarkerValue>(markerValue)
                                    .Equals(markerValue),
                                "value owner-bound C# dispatch");

                            MethodInfo createVariantOwnerBound =
                                RequireMethod(facade, "newVariantOwnerBound");
                            MethodInfo exerciseVariantOwnerBound =
                                RequireMethod(facade, "exerciseVariantOwnerBound");
                            object variantOwnerBoundObject = createVariantOwnerBound.Invoke(null, null);
                            wide.VariantOwnerBoundLeft__KotlinExact<wide.IntersectionMarker>
                                variantOwnerBoundLeft =
                                    (wide.VariantOwnerBoundLeft__KotlinExact<wide.IntersectionMarker>)
                                        variantOwnerBoundObject;
                            wide.VariantOwnerBoundRight__KotlinExact<wide.IntersectionMarker>
                                variantOwnerBoundRight =
                                    (wide.VariantOwnerBoundRight__KotlinExact<wide.IntersectionMarker>)
                                        variantOwnerBoundObject;
                            wide.VariantOwnerBoundIntersection__KotlinExact<wide.IntersectionMarker>
                                variantOwnerBoundDerived =
                                    (wide.VariantOwnerBoundIntersection__KotlinExact<wide.IntersectionMarker>)
                                        variantOwnerBoundObject;
                            Require(Object.ReferenceEquals(
                                    variantOwnerBoundLeft.retainVariant(marker), marker) &&
                                Object.ReferenceEquals(
                                    variantOwnerBoundRight.retainVariant(marker), marker) &&
                                Object.ReferenceEquals(
                                    variantOwnerBoundDerived.retainVariant(marker), marker),
                                "variant owner-bound C# dispatch");
                            Require((bool) exerciseVariantOwnerBound.Invoke(
                                null, new object[] { variantOwnerBoundObject, marker }),
                                "variant owner-bound Kotlin dispatch");
                            MethodInfo variantOwnerBoundMethod = typeof(
                                wide.VariantOwnerBoundIntersection__KotlinExact<wide.IntersectionMarker>)
                                    .GetMethod("retainVariant");
                            Require(variantOwnerBoundMethod.GetGenericArguments()[0]
                                .GetGenericParameterConstraints().Length == 0,
                                "variant owner-bound physical constraint was not erased");
                            try {
                                variantOwnerBoundDerived.retainVariant<string>("wrong");
                                throw new Exception(
                                    "variant owner-bound erased slot accepted an invalid value");
                            } catch (InvalidCastException) {
                            }

                            MethodInfo createCovariant =
                                RequireMethod(facade, "newCovariantIntersection");
                            MethodInfo exerciseCovariant =
                                RequireMethod(facade, "exerciseCovariantIntersection");
                            object covariantObject =
                                createCovariant.Invoke(null, new object[] { marker });
                            wide.CovariantWide<wide.IntersectionMarker> covariantWide =
                                (wide.CovariantWide<wide.IntersectionMarker>) covariantObject;
                            wide.CovariantNarrow<wide.IntersectionMarker> covariantNarrow =
                                (wide.CovariantNarrow<wide.IntersectionMarker>) covariantObject;
                            wide.CovariantIntersection<wide.IntersectionMarker> covariantDerived =
                                (wide.CovariantIntersection<wide.IntersectionMarker>) covariantObject;
                            Require(Object.ReferenceEquals(covariantWide.covariantResult(), marker) &&
                                Object.ReferenceEquals(covariantNarrow.covariantResult(), marker) &&
                                Object.ReferenceEquals(covariantDerived.covariantResult(), marker),
                                "covariant C# intersection dispatch");
                            Require((bool) exerciseCovariant.Invoke(
                                null, new object[] { covariantObject, marker }),
                                "covariant Kotlin intersection dispatch");

                            MethodInfo createMutable = RequireMethod(facade, "newMutableIntersection");
                            MethodInfo exerciseMutable = RequireMethod(facade, "exerciseMutableIntersection");
                            object mutable = createMutable.Invoke(null, null);
                            wide.MutableIntersectionLeft<string> mutableLeft =
                                (wide.MutableIntersectionLeft<string>) mutable;
                            wide.MutableIntersectionRight<string> mutableRight =
                                (wide.MutableIntersectionRight<string>) mutable;
                            wide.MutableIntersection<string> mutableDerived =
                                (wide.MutableIntersection<string>) mutable;
                            mutableDerived.mutableLabel = "csharp";
                            Require(mutableLeft.mutableLabel == "csharp" &&
                                mutableRight.mutableLabel == "csharp", "mutable C# property dispatch");
                            Require((bool) exerciseMutable.Invoke(null, new object[] { mutable }) &&
                                mutableDerived.mutableLabel == "producer", "mutable Kotlin dispatch");

                            MethodInfo createSplitMutable =
                                RequireMethod(facade, "newSplitMutableIntersection");
                            MethodInfo exerciseSplitMutable =
                                RequireMethod(facade, "exerciseSplitMutableIntersection");
                            object splitMutable = createSplitMutable.Invoke(null, null);
                            wide.SplitMutableIntersection<string> splitMutableDeclared =
                                (wide.SplitMutableIntersection<string>) splitMutable;
                            wide.SplitMutableIntersection__KotlinExact<string> splitMutableExact =
                                (wide.SplitMutableIntersection__KotlinExact<string>) splitMutable;
                            PropertyInfo splitDeclaredProperty = typeof(
                                wide.SplitMutableIntersection<string>).GetProperty("splitLabel");
                            PropertyInfo splitExactProperty = typeof(
                                wide.SplitMutableIntersection__KotlinExact<string>)
                                    .GetProperty("splitLabel");
                            Require(splitDeclaredProperty.CanRead && !splitDeclaredProperty.CanWrite,
                                "split mutable declared property shape");
                            Require(splitExactProperty.CanRead && splitExactProperty.CanWrite,
                                "split mutable exact property shape");
                            splitMutableExact.splitLabel = "split-csharp";
                            Require(splitMutableDeclared.splitLabel == "split-csharp",
                                "split mutable C# property dispatch");
                            Require((bool) exerciseSplitMutable.Invoke(
                                    null, new object[] { splitMutable }) &&
                                splitMutableDeclared.splitLabel == "split-producer",
                                "split mutable Kotlin property dispatch");

                            MethodInfo createExact = RequireMethod(facade, "newExactIntersection");
                            MethodInfo readExact = RequireMethod(facade, "readExactIntersection");
                            object exactIntersectionObject = createExact.Invoke(null, null);
                            wide.ExactIntersectionLeft__KotlinExact<int> exactLeft =
                                (wide.ExactIntersectionLeft__KotlinExact<int>) exactIntersectionObject;
                            wide.ExactIntersectionRight__KotlinExact<int> exactRight =
                                (wide.ExactIntersectionRight__KotlinExact<int>) exactIntersectionObject;
                            wide.ExactIntersection__KotlinExact<int> exactDerived =
                                (wide.ExactIntersection__KotlinExact<int>) exactIntersectionObject;
                            Require(exactLeft.acceptsExact(101) && exactRight.acceptsExact(101) &&
                                exactDerived.acceptsExact(101), "exact C# dispatch");
                            Require((bool) readExact.Invoke(null, new object[] { exactIntersectionObject }),
                                "exact Kotlin dispatch");

                            MethodInfo createPermuted = RequireMethod(facade, "newPermutedIntersection");
                            MethodInfo readPermuted = RequireMethod(facade, "readPermutedIntersection");
                            object permuted = createPermuted.Invoke(null, null);
                            wide.PermutedIntersectionLeft<string, int> permutedLeft =
                                (wide.PermutedIntersectionLeft<string, int>) permuted;
                            wide.PermutedIntersectionRight<int, string> permutedRight =
                                (wide.PermutedIntersectionRight<int, string>) permuted;
                            wide.PermutedIntersection<string, int> permutedDerived =
                                (wide.PermutedIntersection<string, int>) permuted;
                            Require(permutedLeft.permute("abcd") == 83 &&
                                permutedRight.permute("abcde") == 83 &&
                                permutedDerived.permute("abcdef") == 83,
                                "permuted C# dispatch");
                            Require((int) readPermuted.Invoke(null, new object[] { permuted, "abcd" }) == 249,
                                "permuted Kotlin dispatch");

                            MethodInfo createIndirect = RequireMethod(facade, "newIndirectIntersection");
                            MethodInfo readIndirect = RequireMethod(facade, "readIndirectIntersection");
                            object indirect = createIndirect.Invoke(null, null);
                            wide.IndirectIntersectionLeftBase<int> indirectLeft =
                                (wide.IndirectIntersectionLeftBase<int>) indirect;
                            wide.IndirectIntersectionRightBase<int> indirectRight =
                                (wide.IndirectIntersectionRightBase<int>) indirect;
                            wide.IndirectIntersection<int> indirectDerived =
                                (wide.IndirectIntersection<int>) indirect;
                            Require(indirectLeft.indirect() == 89 && indirectRight.indirect() == 89 &&
                                indirectDerived.indirect() == 89, "indirect C# dispatch");
                            Require((int) readIndirect.Invoke(null, new object[] { indirect }) == 267,
                                "indirect Kotlin dispatch");
                            MethodInfo createIndirectDescendant =
                                RequireMethod(facade, "newIndirectIntersectionDescendant");
                            wide.IndirectIntersectionDescendant<int> indirectDescendant =
                                (wide.IndirectIntersectionDescendant<int>)
                                    createIndirectDescendant.Invoke(null, null);
                            Require(indirectDescendant.indirect() == 97,
                                "inherited selected intersection slot");
                            return 0;
                        }
                    }
                    """.trimIndent()
                )
            }
            val verifier = consumerDirectory.resolve("WideVerifier.exe")
            val compileResult = runCSharpCompiler(
                checkNotNull(csharpCompiler),
                verifierSource,
                verifier,
                libraryAssembly,
                checkNotNull(frameworkNetStandardFacade),
                target = "exe",
            )
            assertEquals(0, compileResult.exitCode, compileResult.output)

            val verifierProcess = if (target == "net10.0") {
                consumerDirectory.resolve("WideConsumer.runtimeconfig.json")
                    .copyTo(consumerDirectory.resolve("WideVerifier.runtimeconfig.json"), overwrite = true)
                ProcessBuilder(dotnetHost.path, "exec", verifier.path)
            } else {
                ProcessBuilder(verifier.path)
            }.directory(consumerDirectory).redirectErrorStream(true).start()
            val verifierOutput = verifierProcess.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, verifierProcess.waitFor(), "High-arity C# verifier failed for $target:\n$verifierOutput")
        }
    }

    @Test
    fun testCanonicalOnlyGenericInterfaceProviderOnBothRuntimeProfiles() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val frameworkHost = DotNetIlAssembler.findFrameworkPowerShellHost()
        requireOrAssumeToolchain(frameworkHost != null, "Windows PowerShell CLR 4 host is not available")
        requireOrAssumeToolchain(
            findFrameworkNetStandardFacade() != null,
            ".NET Framework netstandard 2.0 facade is not available",
        )
        val dotnetHost = modernDotNetHostOrSkip()

        val producerDirectory = File(tmpdir, "canonical-only-provider-library")
        val producerSource = File(tmpdir, "canonicalProvider.kt").apply {
            writeText(
                """
                package canonicalprovider

                public interface Source<out T> {
                    public fun value(): T
                }

                public fun readAsAny(value: Source<Any?>): Any? = value.value()
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            producerSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Canonical.Provider",
            K2DotNetCompilerArguments::destination.cliArgument, producerDirectory.path,
        )

        val metadataLibrary = producerDirectory.resolve("Canonical.Provider.klib")
        val declarations = DotNetLibraryAbiCodec.decode(metadataLibrary.readKlibManifest()).values
        val sourceSlot = declarations.filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { declaration ->
                declaration.ownerPath.last() == "canonicalprovider.Source" &&
                        declaration.methodName.startsWith("value__KotlinErased__")
            }
        val reader = declarations.filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { declaration -> declaration.methodName == "readAsAny" }

        val rawProviderIl = File(tmpdir, "CanonicalOnlyProvider.il").apply {
            writeText(
                """
                .assembly extern mscorlib {}
                .assembly extern Canonical.Provider {}
                .assembly extern Kotlin.Runtime {}
                .assembly CanonicalOnlyProvider {}
                .module CanonicalOnlyProvider.exe

                .class private auto ansi sealed beforefieldinit 'CanonicalOnlySource'
                       extends [mscorlib]System.Object
                       implements [Canonical.Provider]'canonicalprovider.Source'
                {
                  .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
                  {
                    .maxstack 1
                    ldarg.0
                    call instance void [mscorlib]System.Object::.ctor()
                    ret
                  }

                  .method private hidebysig newslot virtual final instance object '${sourceSlot.methodName}'() cil managed
                  {
                    .override method instance object [Canonical.Provider]'canonicalprovider.Source'::'${sourceSlot.methodName}'()
                    .maxstack 1
                    ldc.i4.s 73
                    box [mscorlib]System.Int32
                    ret
                  }
                }

                .method public static void Main() cil managed
                {
                  .entrypoint
                  .maxstack 2
                  newobj instance void 'CanonicalOnlySource'::.ctor()
                  call object [Canonical.Provider]'${reader.ownerPath.single()}'::'${reader.methodName}'(
                    class [Canonical.Provider]'canonicalprovider.Source'
                  )
                  unbox.any [mscorlib]System.Int32
                  ldc.i4.s 73
                  beq IL_success
                  ldstr "Canonical-only fallback returned an unexpected value."
                  newobj instance void [mscorlib]System.Exception::.ctor(string)
                  throw
                IL_success:
                  ldstr "OK"
                  call void [mscorlib]System.Console::WriteLine(string)
                  ret
                }
                """.trimIndent()
            )
        }

        for (target in listOf(DotNetTarget.NET48, DotNetTarget.NET10_0)) {
            val executionDirectory = File(tmpdir, "canonical-only-provider-${target.flagValue}").apply { mkdirs() }
            producerDirectory.resolve("Canonical.Provider.dll")
                .copyTo(executionDirectory.resolve("Canonical.Provider.dll"))
            val runtime = DotNetIlAssembler.assembleRuntimeForTests(
                executionDirectory,
                target,
                MessageCollector.NONE,
            )
            assertTrue(runtime?.isFile == true) { "Failed to produce ${target.flagValue} Kotlin.Runtime.dll" }

            val application = executionDirectory.resolve(
                if (target == DotNetTarget.NET48) "CanonicalOnlyProvider.exe" else "CanonicalOnlyProvider.dll"
            )
            assertTrue(
                DotNetIlAssembler.assembleExecutable(
                    rawProviderIl,
                    application,
                    target,
                    MessageCollector.NONE,
                )
            ) { "Failed to assemble canonical-only provider for ${target.flagValue}" }

            if (target == DotNetTarget.NET48) {
                runAssemblerPairing(
                    frameworkExecutionCommand(checkNotNull(frameworkHost), application),
                    executionDirectory,
                    "Framework canonical-only generic-interface provider",
                )
            } else {
                runDotNet(
                    dotnetHost,
                    application,
                    executionDirectory,
                    "CoreCLR canonical-only generic-interface provider failed",
                )
            }
        }
    }

    @Test
    fun testForeignGenericInterfaceBarriers() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val frameworkCSharp = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(frameworkCSharp != null, ".NET Framework C# compiler is not available")
        val frameworkHost = DotNetIlAssembler.findFrameworkPowerShellHost()
        requireOrAssumeToolchain(frameworkHost != null, "Windows PowerShell CLR 4 host is not available")
        val frameworkNetStandardFacade = findFrameworkNetStandardFacade()
        requireOrAssumeToolchain(
            frameworkNetStandardFacade != null,
            ".NET Framework netstandard 2.0 facade is not available",
        )
        val modernCSharp = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(
            modernCSharp != null,
            "Modern Roslyn and the net10 reference pack are not available",
        )

        val reservedNameProbeDirectory = File(tmpdir, "foreign-reserved-name-probe").apply { mkdirs() }
        val reservedNameProbeSource = reservedNameProbeDirectory.resolve("barrier.kt").apply {
            writeText(
                """
                package barriers

                public interface ReservedMember<out T> {
                    public fun read(): T
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            reservedNameProbeSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Foreign.Barriers",
            K2DotNetCompilerArguments::destination.cliArgument, reservedNameProbeDirectory.path,
        )
        val reservedCanonicalMethodName = DotNetLibraryAbiCodec.decode(
            reservedNameProbeDirectory.resolve("Foreign.Barriers.klib").readKlibManifest()
        ).values.filterIsInstance<DotNetPhysicalDeclaration.Function>().single { declaration ->
            declaration.ownerPath.last() == "barriers.ReservedMember" &&
                    declaration.methodName.startsWith("read__KotlinErased__")
        }.methodName

        val producerDirectory = File(tmpdir, "foreign-barriers").apply { mkdirs() }
        val producerSource = producerDirectory.resolve("barrier.kt").apply {
            writeText(
                """
                package barriers

                public interface UnsafeSink<out T> {
                    public fun accepts(value: @UnsafeVariance T): Boolean
                }

                public interface ForeignShape<out T> {
                    public val value: T
                    public fun <R> map(input: R): T
                    public fun accepts(value: @UnsafeVariance T): Boolean
                }

                public interface ReservedMember<out T> {
                    public fun read(): T
                    public fun $reservedCanonicalMethodName(): Any?
                }

                public class ReservedImplementation : ReservedMember<String> {
                    public override fun read(): String = "semantic"
                    public override fun $reservedCanonicalMethodName(): Any? = "lookalike"
                }

                public fun verifyForeign(
                    collection: Collection<Int>,
                    unsafeSink: UnsafeSink<Int>,
                ): Int {
                    if (collection.size != 1 || collection.isEmpty()) return 1
                    if (!collection.contains(42)) return 2
                    val wideCollection: Collection<Any?> = collection
                    if (wideCollection.contains("wrong") || wideCollection.contains(null)) return 3

                    if (!unsafeSink.accepts(42)) return 4
                    val wideUnsafeSink: UnsafeSink<Any?> = unsafeSink
                    try {
                        wideUnsafeSink.accepts("wrong")
                        return 5
                    } catch (_: ClassCastException) {
                        // An ordinary user unsafe member retains normal cast-failure behavior.
                    }
                    return 0
                }

                public fun verifyForeignShape(shape: ForeignShape<String>): Int {
                    if (shape.value != "typed") return 1
                    if (shape.map(42) != "typed") return 2
                    if (!shape.accepts("typed")) return 3

                    val wide: ForeignShape<Any?> = shape
                    if (wide.value != "typed") return 4
                    if (wide.map("input") != "typed") return 5
                    try {
                        wide.accepts(42)
                        return 6
                    } catch (_: ClassCastException) {
                        // The foreign canonical adapter owns the ordinary narrowing failure.
                    }
                    return 0
                }

                public fun verifyReservedMember(value: ReservedImplementation): Int {
                    val wide: ReservedMember<Any?> = value
                    if (wide.read() != "semantic") return 1
                    if (value.$reservedCanonicalMethodName() != "lookalike") return 2
                    return 0
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            producerSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Foreign.Barriers",
            K2DotNetCompilerArguments::destination.cliArgument, producerDirectory.path,
        )

        val producerMetadata = producerDirectory.resolve("Foreign.Barriers.klib")
        val declarations = DotNetLibraryAbiCodec.decode(producerMetadata.readKlibManifest()).values
        val unsafeClass = declarations.filterIsInstance<DotNetPhysicalDeclaration.Class>()
            .single { declaration -> declaration.ownerPath.last() == "barriers.UnsafeSink" }
        assertEquals(listOf("barriers.UnsafeSink`1"), unsafeClass.declaredOwnerPath)
        assertEquals(listOf("barriers.UnsafeSink__KotlinExact`1"), unsafeClass.exactOwnerPath)
        val unsafeCanonicalSlot = declarations.filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { declaration ->
                declaration.ownerPath.last() == "barriers.UnsafeSink" &&
                        declaration.methodName.startsWith("accepts__KotlinErased__")
            }
        val foreignShapeClass = declarations.filterIsInstance<DotNetPhysicalDeclaration.Class>()
            .single { declaration -> declaration.ownerPath.last() == "barriers.ForeignShape" }
        assertEquals(listOf("barriers.ForeignShape`1"), foreignShapeClass.declaredOwnerPath)
        assertEquals(listOf("barriers.ForeignShape__KotlinExact`1"), foreignShapeClass.exactOwnerPath)
        val foreignShapeFunctions = declarations.filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .filter { declaration -> declaration.ownerPath.last() == "barriers.ForeignShape" }
        val foreignValueCanonicalSlot = foreignShapeFunctions.single { declaration ->
            declaration.methodName.startsWith("get_value__KotlinErased__")
        }
        val foreignMapCanonicalSlot = foreignShapeFunctions.single { declaration ->
            declaration.methodName.startsWith("map__KotlinErased__")
        }
        val foreignAcceptsCanonicalSlot = foreignShapeFunctions.single { declaration ->
            declaration.methodName.startsWith("accepts__KotlinErased__")
        }
        val verifier = declarations.filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { declaration -> declaration.methodName == "verifyForeign" }
        val shapeVerifier = declarations.filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { declaration -> declaration.methodName == "verifyForeignShape" }
        val reservedVerifier = declarations.filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { declaration -> declaration.methodName == "verifyReservedMember" }
        val reservedCanonicalSlots = declarations.filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .filter { declaration -> declaration.ownerPath.last() == "barriers.ReservedMember" }
        assertTrue(reservedCanonicalSlots.any { declaration ->
            declaration.methodName == reservedCanonicalMethodName
        }) { reservedCanonicalSlots.joinToString("\n") }
        assertTrue(reservedCanonicalSlots.any { declaration ->
            declaration.methodName.startsWith("${reservedCanonicalMethodName}__KotlinErased__")
        }) { reservedCanonicalSlots.joinToString("\n") }
        assertEquals(listOf("barriers.barrierKt"), verifier.ownerPath)
        assertEquals(verifier.ownerPath, shapeVerifier.ownerPath)
        assertEquals(verifier.ownerPath, reservedVerifier.ownerPath)

        val csharpSourceText = """
            using System;

            public sealed class ForeignCollection
                : Kotlin.Collections.Collection__KotlinExact<int>
            {
                public int Size { get { return 1; } }

                public bool IsEmpty() { return false; }

                public bool Contains(int element) { return element == 42; }

                public bool ContainsErased(object element)
                {
                    return element is int && Contains((int)element);
                }

                public Kotlin.Collections.Iterator GetIterator() { return null; }

                public bool ContainsAll(Kotlin.Collections.Collection elements) { return false; }
            }

            public sealed class ForeignUnsafeSink
                : barriers.UnsafeSink__KotlinExact<int>
            {
                public bool accepts(int value) { return value == 42; }

                public bool ${unsafeCanonicalSlot.methodName}(object value)
                {
                    return accepts((int)value);
                }
            }

            public sealed class ForeignShape
                : barriers.ForeignShape__KotlinExact<string>
            {
                public string value { get { return "typed"; } }

                public string map<R>(R input) { return value; }

                public bool accepts(string input) { return input == value; }

                object barriers.ForeignShape.${foreignValueCanonicalSlot.methodName.removePrefix("get_")}
                {
                    get { return value; }
                }

                public object ${foreignMapCanonicalSlot.methodName}<R>(R input)
                {
                    return map(input);
                }

                public bool ${foreignAcceptsCanonicalSlot.methodName}(object input)
                {
                    return accepts((string)input);
                }
            }

            public static class Program
            {
                public static void Main()
                {
                    int result = ${verifier.ownerPath.single()}.${verifier.methodName}(
                        new ForeignCollection(),
                        new ForeignUnsafeSink());
                    if (result != 0)
                        throw new Exception("foreign generic-interface barrier " + result);
                    int shapeResult = ${shapeVerifier.ownerPath.single()}.${shapeVerifier.methodName}(
                        new ForeignShape());
                    if (shapeResult != 0)
                        throw new Exception("foreign generic-interface member " + shapeResult);
                    barriers.ReservedImplementation reserved =
                        new barriers.ReservedImplementation();
                    int reservedResult =
                        ${reservedVerifier.ownerPath.single()}.${reservedVerifier.methodName}(reserved);
                    if (reservedResult != 0)
                        throw new Exception("reserved generic-interface member " + reservedResult);
                    if ((string)reserved.${reservedCanonicalMethodName}() != "lookalike")
                        throw new Exception("reserved source member");
                    barriers.ReservedMember canonicalReserved = reserved;
                    if ((string)canonicalReserved.${reservedCanonicalMethodName}() != "semantic")
                        throw new Exception("reserved canonical slot");
                    Console.WriteLine("OK");
                }
            }
        """.trimIndent()
        val producerAssembly = producerDirectory.resolve("Foreign.Barriers.dll")
        assertTrue(producerAssembly.isFile)
        val bootstrapSource = producerDirectory.resolve("bootstrap.kt").apply { writeText("fun main() {}") }

        val frameworkDirectory = producerDirectory.resolve("framework").apply { mkdirs() }
        compileInProcess(
            K2DotNetCompiler(),
            bootstrapSource.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net48",
            K2DotNetCompilerArguments::moduleName.cliArgument, "ForeignBarriersBootstrap",
            K2DotNetCompilerArguments::destination.cliArgument,
            frameworkDirectory.resolve("ForeignBarriersBootstrap.exe").path,
        )
        val frameworkRuntime = frameworkDirectory.resolve("Kotlin.Runtime.dll")
        assertTrue(frameworkRuntime.isFile)
        val frameworkSource = frameworkDirectory.resolve("consumer.cs").apply { writeText(csharpSourceText) }
        val frameworkApplication = frameworkDirectory.resolve("ForeignBarriers.exe")
        val frameworkCompile = runCSharpCompiler(
            checkNotNull(frameworkCSharp),
            frameworkSource,
            frameworkApplication,
            producerAssembly,
            frameworkRuntime,
            checkNotNull(frameworkNetStandardFacade),
            target = "exe",
        )
        assertEquals(0, frameworkCompile.exitCode, frameworkCompile.output)
        producerAssembly.copyTo(frameworkDirectory.resolve(producerAssembly.name), overwrite = true)
        runAssemblerPairing(
            frameworkExecutionCommand(checkNotNull(frameworkHost), frameworkApplication),
            frameworkDirectory,
            "Framework foreign generic-interface barriers",
        )

        val modernDirectory = producerDirectory.resolve("modern").apply { mkdirs() }
        compileInProcess(
            K2DotNetCompiler(),
            bootstrapSource.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "ForeignBarriersBootstrap",
            K2DotNetCompilerArguments::destination.cliArgument,
            modernDirectory.resolve("ForeignBarriersBootstrap.dll").path,
        )
        val modernRuntime = modernDirectory.resolve("Kotlin.Runtime.dll")
        assertTrue(modernRuntime.isFile)
        val modernSource = modernDirectory.resolve("consumer.cs").apply { writeText(csharpSourceText) }
        val modernApplication = modernDirectory.resolve("ForeignBarriers.dll")
        val modernCompile = runModernCSharpCompiler(
            checkNotNull(modernCSharp),
            modernSource,
            modernApplication,
            producerAssembly,
            modernRuntime,
            target = "exe",
        )
        assertEquals(0, modernCompile.exitCode, modernCompile.output)
        producerAssembly.copyTo(modernDirectory.resolve(producerAssembly.name), overwrite = true)
        modernDirectory.resolve("ForeignBarriers.runtimeconfig.json").writeText(
            """
            {
              "runtimeOptions": {
                "tfm": "net10.0",
                "framework": {
                  "name": "Microsoft.NETCore.App",
                  "version": "10.0.0"
                },
                "rollForward": "LatestMinor"
              }
            }
            """.trimIndent()
        )
        runAssemblerPairing(
            listOf(checkNotNull(modernCSharp).dotNetHost.path, "exec", modernApplication.path),
            modernDirectory,
            "CoreCLR foreign generic-interface barriers",
        )
    }

    @Test
    fun testGenericInterfaceDefaultsAcrossPortableAndNet10Assemblies() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val modernCSharp = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(
            modernCSharp != null,
            "Modern Roslyn and the net10 reference pack are not available",
        )

        val portableDirectory = File(tmpdir, "portable-generic-interface-default").apply { mkdirs() }
        val portableSource = portableDirectory.resolve("portable.kt").apply {
            writeText(
                """
                package genericdefaults

                public interface PortableGeneric<out T> {
                    public fun seed(): T
                    public fun value(): T = seed()
                    public fun <R : @UnsafeVariance T> echo(value: R): R = value
                    public fun same(value: @UnsafeVariance T): Boolean = seed() == value
                }

                public class PortableInt(private val current: Int) : PortableGeneric<Int> {
                    public override fun seed(): Int = current
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            portableSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Portable.GenericDefaults",
            K2DotNetCompilerArguments::destination.cliArgument, portableDirectory.path,
        )
        val portableMetadata = portableDirectory.resolve("Portable.GenericDefaults.klib")
        val portableIl = portableDirectory.resolve("Portable.GenericDefaults.il").readText()
        assertTrue("abstract virtual instance object 'value__KotlinErased__" in portableIl) { portableIl }
        assertTrue("/'__KotlinDefaultImpls'::'value'" in portableIl) { portableIl }
        assertTrue("<GenericInterfaceCanonicalBridge-" in portableIl) { portableIl }
        assertTrue("<GenericInterfaceDeclaredBridge-" in portableIl) { portableIl }
        assertTrue("<GenericInterfaceExactBridge-" in portableIl) { portableIl }

        val promotedDirectory = File(tmpdir, "promoted-generic-interface-default").apply { mkdirs() }
        val promotedSource = promotedDirectory.resolve("promoted.kt").apply {
            writeText(
                """
                package genericdefaults

                public interface PromotedGeneric<out T> : PortableGeneric<T>
                public interface PromotedLeft<out T> : PortableGeneric<T>
                public interface PromotedRight<out T> : PortableGeneric<T>
                public interface PromotedDiamond<out T> : PromotedLeft<T>, PromotedRight<T>
                public interface PromotedInt : PortableGeneric<Int>
                public interface OverriddenInt : PortableGeneric<Int> {
                    public override fun value(): Int = 91
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            promotedSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, portableMetadata.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Promoted.GenericDefaults",
            K2DotNetCompilerArguments::destination.cliArgument, promotedDirectory.path,
        )
        val promotedMetadata = promotedDirectory.resolve("Promoted.GenericDefaults.klib")
        val promotedDeclarations = DotNetLibraryAbiCodec.decode(promotedMetadata.readKlibManifest())
        val promotions = promotedDeclarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.InterfaceDefaultPromotion>()
        assertEquals(34, promotions.size, promotions.joinToString("\n"))
        for (owner in listOf("PromotedLeft", "PromotedRight", "PromotedDiamond")) {
            val ownerPromotions = promotions.filter { declaration ->
                declaration.ownerPath == listOf("genericdefaults.$owner")
            }
            assertEquals(6, ownerPromotions.size, ownerPromotions.joinToString("\n"))
            assertEquals(
                setOf(
                    DotNetInterfaceDefaultPromotionView.CANONICAL,
                    DotNetInterfaceDefaultPromotionView.DECLARED,
                    DotNetInterfaceDefaultPromotionView.EXACT,
                ),
                ownerPromotions.mapTo(hashSetOf()) { it.physicalView },
            )
        }
        val viewBridges = promotedDeclarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericInterfaceViewBridge>()
        assertEquals(12, viewBridges.size, viewBridges.joinToString("\n"))
        val overriddenValueBridges = viewBridges.filter { bridge ->
            bridge.ownerPath == listOf("genericdefaults.OverriddenInt") &&
                    "/PortableGeneric.value" in bridge.inheritedLogicalMemberKey
        }
        assertEquals(2, overriddenValueBridges.size, overriddenValueBridges.joinToString("\n"))
        assertEquals(
            setOf(
                DotNetInterfaceDefaultPromotionView.CANONICAL,
                DotNetInterfaceDefaultPromotionView.DECLARED,
            ),
            overriddenValueBridges.mapTo(hashSetOf()) { it.physicalView },
        )
        assertEquals(
            setOf(
                DotNetInterfaceDefaultPromotionView.CANONICAL,
                DotNetInterfaceDefaultPromotionView.DECLARED,
                DotNetInterfaceDefaultPromotionView.EXACT,
            ),
            promotions.mapTo(hashSetOf()) { it.physicalView },
        )
        val promotedIl = promotedDirectory.resolve("Promoted.GenericDefaults.il").readText()
        assertTrue("<GenericInterfaceDefaultPromotionCanonical-" in promotedIl) { promotedIl }
        assertTrue("<GenericInterfaceDefaultPromotionDeclared-" in promotedIl) { promotedIl }
        assertTrue("<GenericInterfaceDefaultPromotionExact-" in promotedIl) { promotedIl }
        assertTrue("[Portable.GenericDefaults]" in promotedIl) { promotedIl }
        assertTrue("/'__KotlinDefaultImpls'::'value'" in promotedIl) { promotedIl }
        assertTrue("<GenericInterfaceCanonicalBridge-genericdefaults.PortableGeneric-value-" in promotedIl) {
            "The closed override must explicitly map the inherited canonical slot:\n$promotedIl"
        }
        assertTrue("<InterfaceDefaultSlotBridge-genericdefaults.OverriddenInt-value-" !in promotedIl) {
            "An ordinary int32 bridge cannot implement the erased object slot:\n$promotedIl"
        }
        assertEquals(1, promotedIl.lineSequence().count { "ldc.i4 91" in it }) {
            "The Kotlin body must occur only in OverriddenInt.value; helpers and view adapters only forward:\n$promotedIl"
        }

        val closedImplementationDirectory =
            File(tmpdir, "closed-generic-interface-default-implementation").apply { mkdirs() }
        val closedImplementationSource = closedImplementationDirectory.resolve("closed.kt").apply {
            writeText(
                """
                package genericdefaults

                public class ClosedImplementation(private val current: Int) : PromotedInt {
                    public override fun seed(): Int = current
                }

                public class OverriddenImplementation(private val current: Int) : OverriddenInt {
                    public override fun seed(): Int = current
                }

                public class DiamondImplementation(private val current: Int) : PromotedDiamond<Int> {
                    public override fun seed(): Int = current
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            closedImplementationSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument,
            listOf(portableMetadata, promotedMetadata).joinToString(File.pathSeparator) { it.path },
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Closed.GenericDefaults",
            K2DotNetCompilerArguments::destination.cliArgument, closedImplementationDirectory.path,
        )
        val closedImplementationMetadata =
            closedImplementationDirectory.resolve("Closed.GenericDefaults.klib")
        val consumerDirectory = File(tmpdir, "generic-interface-default-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("main.kt").apply {
            writeText(
                """
                package genericdefaults

                private class ThroughPromotion(private val current: Int) : PromotedGeneric<Int> {
                    override fun seed(): Int = current
                }

                private class ThroughPortable(private val current: Int) : PortableGeneric<Int> {
                    override fun seed(): Int = current
                }

                fun main() {
                    val promoted: PortableGeneric<Int> = ThroughPromotion(41)
                    if (promoted.value() != 41) throw Error("promoted typed result")
                    if (promoted.echo(41) != 41) throw Error("promoted method generic")
                    if (!promoted.same(41)) throw Error("promoted exact argument")
                    val widened: PortableGeneric<Any> = promoted
                    if (widened.value() != 41) throw Error("promoted erased result")
                    if (!widened.same(41)) throw Error("promoted erased exact fallback")
                    if (widened.echo("widened echo") != "widened echo") throw Error("promoted widened method constraint")

                    val closedView: PromotedInt = ClosedImplementation(42)
                    val closed: PortableGeneric<Int> = closedView
                    if (closed.value() != 42) throw Error("closed promoted typed result")
                    if (closed.echo(42) != 42) throw Error("closed promoted method generic")
                    if (!closed.same(42)) throw Error("closed promoted exact argument")
                    if (closedView.value() != 42) throw Error("closed promoted derived view")

                    val overriddenView: OverriddenInt = OverriddenImplementation(45)
                    if (overriddenView.value() != 91) throw Error("closed interface override derived view")
                    val overridden: PortableGeneric<Int> = overriddenView
                    if (overridden.value() != 91) throw Error("closed interface override typed view")
                    if (!overridden.same(45)) throw Error("closed interface inherited exact view")
                    val widenedOverride: PortableGeneric<Any> = overriddenView
                    if (widenedOverride.value() != 91) throw Error("closed interface override widened view")

                    val diamondView: PromotedDiamond<Int> = DiamondImplementation(46)
                    val diamondLeft: PromotedLeft<Int> = diamondView
                    val diamondRight: PromotedRight<Int> = diamondView
                    val diamondBase: PortableGeneric<Int> = diamondView
                    if (diamondView.value() != 46) throw Error("diamond promoted derived result")
                    if (diamondLeft.value() != 46) throw Error("diamond promoted left result")
                    if (diamondRight.value() != 46) throw Error("diamond promoted right result")
                    if (diamondBase.value() != 46) throw Error("diamond promoted base result")
                    if (diamondView.echo(46) != 46) throw Error("diamond promoted method generic")
                    if (!diamondView.same(46)) throw Error("diamond promoted exact argument")
                    val widenedDiamond: PortableGeneric<Any> = diamondView
                    if (widenedDiamond.value() != 46) throw Error("diamond promoted widened result")
                    if (widenedDiamond.echo("diamond") != "diamond") {
                        throw Error("diamond promoted widened method constraint")
                    }

                    val portable: PortableGeneric<Int> = ThroughPortable(43)
                    if (portable.value() != 43) throw Error("portable class forwarder result")
                    if (portable.echo(43) != 43) throw Error("portable class method generic")
                    if (!portable.same(43)) throw Error("portable class exact argument")

                    val producer: PortableGeneric<Int> = PortableInt(44)
                    if (producer.value() != 44) throw Error("portable producer result")
                    if (!producer.same(44)) throw Error("portable producer exact argument")
                    val widenedProducer: PortableGeneric<Any> = producer
                    if (widenedProducer.echo("producer echo") != "producer echo") throw Error("producer widened method constraint")
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("GenericDefaultConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument,
            listOf(portableMetadata, promotedMetadata, closedImplementationMetadata)
                .joinToString(File.pathSeparator) { it.path },
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "GenericDefaultConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )
        val consumerIl = consumerDirectory.resolve("GenericDefaultConsumer.il").readText()
        assertTrue("[Promoted.GenericDefaults]" in consumerIl) { consumerIl }
        assertTrue("<GenericInterfaceDefaultForwarderTarget-" in consumerIl) {
            "The direct portable implementation still requires helper-backed bridges:\n$consumerIl"
        }
        val closedImplementationIl =
            closedImplementationDirectory.resolve("Closed.GenericDefaults.il").readText()
        assertTrue("<GenericInterfaceDefaultForwarderTarget-genericdefaults.ClosedImplementation-" !in closedImplementationIl) {
            "A closed non-generic promotion supplies DIMs and must suppress class forwarders:\n$closedImplementationIl"
        }
        assertTrue("<GenericInterfaceDefaultForwarderTarget-genericdefaults.OverriddenImplementation-" !in closedImplementationIl) {
            "The selected closed override DIM must suppress helper-backed class forwarders:\n$closedImplementationIl"
        }
        assertTrue("<GenericInterfaceCanonicalBridge-genericdefaults.PortableGeneric-value-" !in closedImplementationIl) {
            "The implementor must inherit OverriddenInt's value adapters instead of duplicating them:\n$closedImplementationIl"
        }
        assertTrue("<GenericInterfaceDefaultForwarderTarget-genericdefaults.DiamondImplementation-" !in closedImplementationIl) {
            "The selected diamond DIMs must suppress helper-backed class forwarders:\n$closedImplementationIl"
        }
        runDotNet(
            dotnetHost,
            consumerAssembly,
            consumerDirectory,
            "Generic interface defaults failed across portable and net10 assemblies",
        )

        val csharpDirectory = File(tmpdir, "generic-interface-default-csharp-consumer").apply { mkdirs() }
        val csharpSource = csharpDirectory.resolve("main.cs").apply {
            writeText(
                """
                using genericdefaults;

                public static class Program
                {
                    public static int Main()
                    {
                        var implementation = new DiamondImplementation(47);
                        PromotedDiamond<int> diamond = implementation;
                        PromotedLeft<int> left = implementation;
                        PromotedRight<int> right = implementation;
                        PortableGeneric<int> root = implementation;
                        PromotedDiamond__KotlinExact<int> exact = implementation;

                        if (diamond.value() != 47 || left.value() != 47 ||
                            right.value() != 47 || root.value() != 47)
                            return 1;
                        if (exact.echo<int>(48) != 48 || !exact.same(47))
                            return 2;
                        return 0;
                    }
                }
                """.trimIndent()
            )
        }
        val csharpAssembly = csharpDirectory.resolve("GenericDefaultCSharpConsumer.dll")
        val runtimeAssembly = consumerDirectory.resolve("Kotlin.Runtime.dll")
        val csharpCompile = runModernCSharpCompiler(
            checkNotNull(modernCSharp),
            csharpSource,
            csharpAssembly,
            portableDirectory.resolve("Portable.GenericDefaults.dll"),
            promotedDirectory.resolve("Promoted.GenericDefaults.dll"),
            closedImplementationDirectory.resolve("Closed.GenericDefaults.dll"),
            runtimeAssembly,
            target = "exe",
        )
        assertEquals(0, csharpCompile.exitCode, csharpCompile.output)
        listOf(
            portableDirectory.resolve("Portable.GenericDefaults.dll"),
            promotedDirectory.resolve("Promoted.GenericDefaults.dll"),
            closedImplementationDirectory.resolve("Closed.GenericDefaults.dll"),
            runtimeAssembly,
        ).forEach { dependency ->
            dependency.copyTo(csharpDirectory.resolve(dependency.name), overwrite = true)
        }
        csharpDirectory.resolve("GenericDefaultCSharpConsumer.runtimeconfig.json").writeText(
            """
            {
              "runtimeOptions": {
                "tfm": "net10.0",
                "framework": {
                  "name": "Microsoft.NETCore.App",
                  "version": "10.0.0"
                },
                "rollForward": "LatestMinor"
              }
            }
            """.trimIndent()
        )
        runDotNet(
            checkNotNull(modernCSharp).dotNetHost,
            csharpAssembly,
            csharpDirectory,
            "C# failed to consume the promoted generic-interface diamond",
        )
    }

    @Test
    fun testModernCSharpConsumesProfileAwareGenericInterfaceDefault() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val csharpToolchain = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(csharpToolchain != null, "Modern Roslyn and the net10 reference pack are not available")
        val modernCSharp = checkNotNull(csharpToolchain)

        val kotlinSourceText = """
            package genericdefaults

            public interface CSharpEcho<T> {
                public fun echo(value: T): T = value
            }

            public interface CSharpVariantEcho<out T> {
                public fun echo(value: @UnsafeVariance T): T = value
            }
        """.trimIndent()
        val portableDirectory = File(tmpdir, "csharp-portable-interface-default").apply { mkdirs() }
        val portableSource = portableDirectory.resolve("default.kt").apply { writeText(kotlinSourceText) }
        compileInProcess(
            K2DotNetCompiler(),
            portableSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CSharp.PortableDefaults",
            K2DotNetCompilerArguments::destination.cliArgument, portableDirectory.path,
        )

        val modernDirectory = File(tmpdir, "csharp-modern-interface-default").apply { mkdirs() }
        val modernSource = modernDirectory.resolve("default.kt").apply { writeText(kotlinSourceText) }
        compileInProcess(
            K2DotNetCompiler(),
            modernSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CSharp.ModernDefaults",
            K2DotNetCompilerArguments::destination.cliArgument, modernDirectory.path,
        )
        val runtimeBootstrap = modernDirectory.resolve("runtime-bootstrap.kt").apply {
            writeText("fun main() {}")
        }
        compileInProcess(
            K2DotNetCompiler(),
            runtimeBootstrap.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CSharpRuntimeBootstrap",
            K2DotNetCompilerArguments::destination.cliArgument,
            modernDirectory.resolve("CSharpRuntimeBootstrap.dll").path,
        )
        val runtimeAssembly = modernDirectory.resolve("Kotlin.Runtime.dll")
        assertTrue(runtimeAssembly.isFile) { "Runtime bootstrap did not install Kotlin.Runtime.dll" }

        val consumerText = """
            public sealed class EchoImplementation : genericdefaults.CSharpEcho<int>
            {
            }

            public sealed class VariantEchoImplementation
                : genericdefaults.CSharpVariantEcho__KotlinExact<int>
            {
            }

            public static class Program
            {
                public static int Main()
                {
                    genericdefaults.CSharpEcho<int> value = new EchoImplementation();
                    if (value.echo(73) != 73)
                        return 1;
                    genericdefaults.CSharpVariantEcho__KotlinExact<int> exact =
                        new VariantEchoImplementation();
                    return exact.echo(74) == 74 ? 0 : 2;
                }
            }
        """.trimIndent()
        val modernConsumerSource = modernDirectory.resolve("consumer.cs").apply { writeText(consumerText) }
        val modernConsumer = modernDirectory.resolve("ModernCSharpConsumer.dll")
        val modernCompile = runModernCSharpCompiler(
            modernCSharp,
            modernConsumerSource,
            modernConsumer,
            modernDirectory.resolve("CSharp.ModernDefaults.dll"),
            runtimeAssembly,
            target = "exe",
        )
        assertEquals(0, modernCompile.exitCode, modernCompile.output)
        modernDirectory.resolve("ModernCSharpConsumer.runtimeconfig.json").writeText(
            """
            {
              "runtimeOptions": {
                "tfm": "net10.0",
                "framework": {
                  "name": "Microsoft.NETCore.App",
                  "version": "10.0.0"
                },
                "rollForward": "LatestMinor"
              }
            }
            """.trimIndent()
        )
        runDotNet(
            modernCSharp.dotNetHost,
            modernConsumer,
            modernDirectory,
            "Modern C# failed to inherit the generic Kotlin DIM",
        )

        val portableConsumerSource = portableDirectory.resolve("consumer.cs").apply { writeText(consumerText) }
        val portableCompile = runModernCSharpCompiler(
            modernCSharp,
            portableConsumerSource,
            portableDirectory.resolve("PortableCSharpConsumer.dll"),
            portableDirectory.resolve("CSharp.PortableDefaults.dll"),
            runtimeAssembly,
            target = "exe",
        )
        assertTrue(portableCompile.exitCode != 0) {
            "A portable abstract interface slot must require a C# implementation:\n${portableCompile.output}"
        }
        assertTrue("CS0535" in portableCompile.output) {
            "Expected Roslyn's missing-interface-member diagnostic for the portable profile:\n${portableCompile.output}"
        }

        val frameworkCSharp = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(frameworkCSharp != null, ".NET Framework C# compiler is not available")
        val frameworkDirectory = File(tmpdir, "csharp-framework-interface-default").apply { mkdirs() }
        val frameworkSource = frameworkDirectory.resolve("default.kt").apply { writeText(kotlinSourceText) }
        compileInProcess(
            K2DotNetCompiler(),
            frameworkSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net48",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CSharp.FrameworkDefaults",
            K2DotNetCompilerArguments::destination.cliArgument, frameworkDirectory.path,
        )
        val frameworkBootstrap = frameworkDirectory.resolve("runtime-bootstrap.kt").apply {
            writeText("fun main() {}")
        }
        compileInProcess(
            K2DotNetCompiler(),
            frameworkBootstrap.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net48",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CSharpFrameworkRuntimeBootstrap",
            K2DotNetCompilerArguments::destination.cliArgument,
            frameworkDirectory.resolve("CSharpFrameworkRuntimeBootstrap.exe").path,
        )
        val frameworkConsumerSource = frameworkDirectory.resolve("consumer.cs").apply { writeText(consumerText) }
        val frameworkCompile = runCSharpCompiler(
            checkNotNull(frameworkCSharp),
            frameworkConsumerSource,
            frameworkDirectory.resolve("FrameworkCSharpConsumer.exe"),
            frameworkDirectory.resolve("CSharp.FrameworkDefaults.dll"),
            frameworkDirectory.resolve("Kotlin.Runtime.dll"),
            target = "exe",
        )
        assertTrue(frameworkCompile.exitCode != 0) {
            "A net48 abstract interface slot must require a C# implementation:\n${frameworkCompile.output}"
        }
        assertTrue("CS0535" in frameworkCompile.output) {
            "Expected the Framework compiler's missing-interface-member diagnostic:\n${frameworkCompile.output}"
        }
    }

    @Test
    fun testNet10PromotesPortableInterfaceDefaultAndSuppressesOnlyCoveredForwarders() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val dotnetHost = modernDotNetHostOrSkip()

        val portableDirectory = File(tmpdir, "portable-interface-default").apply { mkdirs() }
        val portableSource = portableDirectory.resolve("portable.kt").apply {
            writeText(
                """
                package defaults

                public interface PortableBase {
                    public fun value(): String = "portable"
                }

                public interface PortableChild : PortableBase

                public class PortableOwned : PortableChild
                public open class PortableOpen : PortableBase
                public open class PortableOpenChild : PortableOpen()
                public open class PortableExplicit : PortableBase {
                    public override fun value(): String = "class"
                }
                public class PortableQualified : PortableBase {
                    public override fun value(): String =
                        "portable-qualified:" + super<PortableBase>.value()
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            portableSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Portable.Defaults",
            K2DotNetCompilerArguments::destination.cliArgument, portableDirectory.path,
        )

        val portableMetadata = portableDirectory.resolve("Portable.Defaults.klib")
        val portableManifest = portableMetadata.readKlibManifest()
        val portableDeclarations = DotNetLibraryAbiCodec.decode(portableManifest)
        val portableDefault = portableDeclarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { declaration -> declaration.interfaceDefaultImplementation != null }
            .interfaceDefaultImplementation
        assertEquals(DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY, portableDefault?.bodyPlacement)
        assertEquals(
            2,
            portableDeclarations.values
                .filterIsInstance<DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder>()
                .size,
            portableDeclarations.values.joinToString(),
        )
        val portableIl = portableDirectory.resolve("Portable.Defaults.il").readText()
        assertEquals(
            2,
            Regex("<InterfaceDefaultForwarder-").findAll(portableIl).count(),
            portableIl,
        )
        assertTrue("abstract virtual instance string 'value'()" in portableIl) { portableIl }
        assertTrue("'PortableBase'/'__KotlinDefaultImpls'::'value'" !in portableIl) {
            "The portable helper owns the body; it must not call an unavailable DIM:\n$portableIl"
        }

        val derivedDirectory = File(tmpdir, "promoted-interface-default").apply { mkdirs() }
        val derivedSource = derivedDirectory.resolve("promoted.kt").apply {
            writeText(
                """
                package defaults

                public interface PromotedDefault : PortableChild
                public interface PromotedInherited : PromotedDefault
                public interface PromotedLeft : PortableChild
                public interface PromotedRight : PortableChild
                public interface PromotedDiamond : PromotedLeft, PromotedRight

                public interface ReabstractedDefault : PortableChild {
                    public override fun value(): String
                }

                public interface Net10Override : PortableBase {
                    public override fun value(): String = "net10"
                }

                public class Net10QualifiedPortable : PortableBase {
                    public override fun value(): String = super<PortableBase>.value()
                }

                public interface Net10Base {
                    public fun value(): String = "net10-base"
                }

                public interface Net10Child : Net10Base {
                    public override fun value(): String = "net10-child"
                    public fun exactBase(): String = super<Net10Base>.value()
                }

                public open class Net10PortableOpen : PortableBase
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            derivedSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, portableMetadata.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Promoted.Defaults",
            K2DotNetCompilerArguments::destination.cliArgument, derivedDirectory.path,
        )

        val derivedMetadata = derivedDirectory.resolve("Promoted.Defaults.klib")
        val derivedManifest = derivedMetadata.readKlibManifest()
        val derivedDeclarations = DotNetLibraryAbiCodec.decode(derivedManifest)
        val promotion = derivedDeclarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.InterfaceDefaultPromotion>()
            .filter { declaration -> declaration.implementationMethodName.contains("PromotedDefault") }
            .single()
        assertEquals(
            4,
            derivedDeclarations.values
                .filterIsInstance<DotNetPhysicalDeclaration.InterfaceDefaultPromotion>()
                .count(),
        )
        assertEquals(
            1,
            derivedDeclarations.values
                .filterIsInstance<DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder>()
                .size,
            derivedDeclarations.values.joinToString(),
        )
        assertEquals("Portable.Defaults", promotion.inheritedAssemblyName)
        assertEquals("value", promotion.inheritedMethodName)
        assertTrue(promotion.implementationMethodName.startsWith("<InterfaceDefaultPromotion-"))

        val derivedIl = derivedDirectory.resolve("Promoted.Defaults.il").readText()
        assertTrue("<InterfaceDefaultPromotion-" in derivedIl) { derivedIl }
        assertTrue(
            Regex("""\.override method instance string \[Portable\.Defaults].*::'value'\(\)""")
                .containsMatchIn(derivedIl)
        ) { derivedIl }
        assertTrue(
            Regex("""call string \[Portable\.Defaults].*/'__KotlinDefaultImpls'::'value'""")
                .containsMatchIn(derivedIl)
        ) { derivedIl }
        assertTrue("call instance string 'defaults.Net10Base'::'value'()" in derivedIl) {
            "The net10 helper must invoke its owning DIM nonvirtually:\n$derivedIl"
        }
        assertTrue("callvirt instance string 'defaults.Net10Base'::'value'()" !in derivedIl) {
            "The exact helper must not redispatch virtually:\n$derivedIl"
        }
        assertTrue("call string 'defaults.Net10Base'/'__KotlinDefaultImpls'::'value'" in derivedIl) {
            "Qualified super must route through the exact-call helper:\n$derivedIl"
        }
        assertEquals(
            1,
            Regex("<InterfaceDefaultForwarder-").findAll(derivedIl).count(),
            derivedIl,
        )

        val consumerDirectory = File(tmpdir, "promoted-interface-default-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("main.kt").apply {
            writeText(
                """
                package defaults

                private class ThroughPromotion : PromotedDefault
                private class ThroughInheritedPromotion : PromotedInherited
                private class ThroughPortableChild : PortableChild
                private class ThroughCompetingPromotions : PromotedLeft, PromotedRight
                private class ThroughResolvedDiamond : PromotedDiamond
                private class ThroughOverride : PromotedDefault {
                    override fun value(): String = "override"
                }

                private class ThroughReabstraction : ReabstractedDefault {
                    override fun value(): String = "reabstracted"
                }
                private class ThroughPortableBaseAndNet10Override : PortableOpenChild(), Net10Override
                private class ThroughExternalPortableBase : PortableOpenChild()
                private class ThroughExplicitBaseAndNet10Override : PortableExplicit(), Net10Override {
                    override fun value(): String = super<PortableExplicit>.value()
                }
                private class ThroughNet10PortableBaseAndNet10Override : Net10PortableOpen(), Net10Override
                private class ThroughNet10QualifiedSuper : Net10Child

                fun main() {
                    val portableOwnedAsBase: PortableBase = PortableOwned()
                    if (portableOwnedAsBase.value() != "portable") {
                        throw Error("portable producer forwarder dispatch")
                    }
                    if (PortableQualified().value() != "portable-qualified:portable") {
                        throw Error("portable qualified interface-super call")
                    }

                    if (Net10QualifiedPortable().value() != "portable") {
                        throw Error("net10 consumer qualified portable interface-super call")
                    }

                    val net10Qualified = ThroughNet10QualifiedSuper()
                    if (net10Qualified.value() != "net10-child") {
                        throw Error("ordinary net10 DIM dispatch")
                    }
                    if (net10Qualified.exactBase() != "net10-base") {
                        throw Error("exact net10 DIM super dispatch")
                    }

                    val promotedAsBase: PortableBase = ThroughPromotion()
                    if (promotedAsBase.value() != "portable") {
                        throw Error("promoted default dispatch")
                    }

                    val inheritedPromotionAsBase: PortableBase = ThroughInheritedPromotion()
                    if (inheritedPromotionAsBase.value() != "portable") {
                        throw Error("indirect promoted default through portable base")
                    }

                    val inheritedPromotionAsInterface: PromotedInherited = ThroughInheritedPromotion()
                    if (inheritedPromotionAsInterface.value() != "portable") {
                        throw Error("indirect promoted default through derived interface")
                    }

                    val overrideAsBase: PortableBase = ThroughOverride()
                    if (overrideAsBase.value() != "override") {
                        throw Error("base-typed class override dispatch")
                    }

                    val overrideAsPromoted: PromotedDefault = ThroughOverride()
                    if (overrideAsPromoted.value() != "override") {
                        throw Error("promoted-interface-typed class override dispatch")
                    }

                    val reabstractedAsBase: PortableBase = ThroughReabstraction()
                    if (reabstractedAsBase.value() != "reabstracted") {
                        throw Error("base-typed reabstracted dispatch")
                    }

                    val reabstractedAsInterface: ReabstractedDefault = ThroughReabstraction()
                    if (reabstractedAsInterface.value() != "reabstracted") {
                        throw Error("reabstracted-interface-typed dispatch")
                    }

                    val competingAsBase: PortableBase = ThroughCompetingPromotions()
                    if (competingAsBase.value() != "portable") {
                        throw Error("competing promotions through portable base")
                    }

                    val competingAsLeft: PromotedLeft = ThroughCompetingPromotions()
                    if (competingAsLeft.value() != "portable") {
                        throw Error("competing promotions through left interface")
                    }

                    val competingAsRight: PromotedRight = ThroughCompetingPromotions()
                    if (competingAsRight.value() != "portable") {
                        throw Error("competing promotions through right interface")
                    }

                    val diamondAsBase: PortableBase = ThroughResolvedDiamond()
                    if (diamondAsBase.value() != "portable") {
                        throw Error("resolved diamond promotion through portable base")
                    }

                    val diamondAsInterface: PromotedDiamond = ThroughResolvedDiamond()
                    if (diamondAsInterface.value() != "portable") {
                        throw Error("resolved diamond promotion through derived interface")
                    }

                    val portableAsBase: PortableBase = ThroughPortableChild()
                    if (portableAsBase.value() != "portable") {
                        throw Error("portable helper forwarder dispatch")
                    }

                    val inheritedForwarderAsBase: PortableBase = ThroughExternalPortableBase()
                    if (inheritedForwarderAsBase.value() != "portable") {
                        throw Error("inherited portable forwarder dispatch")
                    }

                    val maskedOverrideAsBase: PortableBase = ThroughPortableBaseAndNet10Override()
                    if (maskedOverrideAsBase.value() != "net10") {
                        throw Error("net10 default masked by portable base forwarder")
                    }

                    val maskedOverrideAsDerived: Net10Override = ThroughPortableBaseAndNet10Override()
                    if (maskedOverrideAsDerived.value() != "net10") {
                        throw Error("net10 default through derived interface")
                    }

                    val net10MaskedOverrideAsBase: PortableBase = ThroughNet10PortableBaseAndNet10Override()
                    if (net10MaskedOverrideAsBase.value() != "net10") {
                        throw Error("net10 default masked by net10-produced portable forwarder")
                    }

                    val net10MaskedOverrideAsDerived: Net10Override = ThroughNet10PortableBaseAndNet10Override()
                    if (net10MaskedOverrideAsDerived.value() != "net10") {
                        throw Error("net10 default through net10-produced base")
                    }

                    val explicitOverrideAsBase: PortableBase = ThroughExplicitBaseAndNet10Override()
                    if (explicitOverrideAsBase.value() != "class") {
                        throw Error("explicit class override through portable base")
                    }

                    val explicitOverrideAsDerived: Net10Override = ThroughExplicitBaseAndNet10Override()
                    if (explicitOverrideAsDerived.value() != "class") {
                        throw Error("explicit class override through derived interface")
                    }
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("PromotedDefaultConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument,
            listOf(portableMetadata, derivedMetadata).joinToString(File.pathSeparator) { it.path },
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "PromotedDefaultConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )

        val consumerIl = consumerDirectory.resolve("PromotedDefaultConsumer.il").readText()
        assertEquals(
            4,
            Regex("<InterfaceDefaultForwarder-").findAll(consumerIl).count(),
            consumerIl,
        )
        assertTrue("[Portable.Defaults]" in consumerIl) { consumerIl }
        assertTrue("[Promoted.Defaults]" in consumerIl) { consumerIl }
        runDotNet(
            dotnetHost,
            consumerAssembly,
            consumerDirectory,
            "Profile-aware cross-module interface-default dispatch failed",
        )
    }

    @Test
    fun testNet10PromotesPortableAccessorsAndDefaultArguments() {
        val dotnetHost = modernDotNetHostOrSkip()
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")

        val portableDirectory = File(tmpdir, "portable-interface-features").apply { mkdirs() }
        val portableSource = portableDirectory.resolve("portable.kt").apply {
            writeText(
                """
                package defaults

                public var producerState: String = ""

                public interface PortableFeatures {
                    public var observed: String
                        get() = producerState
                        set(value) {
                            producerState = value
                        }

                    public fun combine(first: String = "O", second: String = "K"): String =
                        first + second
                }

                public interface PortableAbstractDefaults {
                    public fun abstractCombine(first: String = "O", second: String = "K"): String
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            portableSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Portable.Features",
            K2DotNetCompilerArguments::destination.cliArgument, portableDirectory.path,
        )

        val portableMetadata = portableDirectory.resolve("Portable.Features.klib")
        val portableDeclarations = DotNetLibraryAbiCodec.decode(portableMetadata.readKlibManifest())
        assertTrue(
            portableDeclarations.keys.none { "__KotlinDefaultImpls" in it || "\$default" in it },
            portableDeclarations.keys.joinToString("\n"),
        )
        val portableFunctions = portableDeclarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .filter { it.defaultArgumentDispatcher != null }
        assertEquals(2, portableFunctions.size, portableFunctions.joinToString())
        assertTrue(
            portableFunctions.any {
                it.methodName == "abstractCombine" &&
                        it.interfaceDefaultImplementation == null
            }
        )

        val derivedDirectory = File(tmpdir, "promoted-interface-features").apply { mkdirs() }
        val derivedSource = derivedDirectory.resolve("promoted.kt").apply {
            writeText(
                """
                package defaults

                public interface PromotedFeatures : PortableFeatures
                public interface PromotedAbstractDefaults : PortableAbstractDefaults

                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            derivedSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, portableMetadata.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Promoted.Features",
            K2DotNetCompilerArguments::destination.cliArgument, derivedDirectory.path,
        )

        val derivedMetadata = derivedDirectory.resolve("Promoted.Features.klib")
        val promotions = DotNetLibraryAbiCodec.decode(derivedMetadata.readKlibManifest()).values
            .filterIsInstance<DotNetPhysicalDeclaration.InterfaceDefaultPromotion>()
        assertEquals(3, promotions.size, promotions.joinToString())
        assertTrue(promotions.all { it.inheritedAssemblyName == "Portable.Features" })

        val consumerDirectory = File(tmpdir, "promoted-interface-features-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("main.kt").apply {
            writeText(
                """
                package defaults

                private class ThroughPromotion : PromotedFeatures
                private class ThroughPortableInterface : PortableFeatures

                private class ThroughOverride : PromotedFeatures {
                    private var localState: String = ""

                    override var observed: String
                        get() = "override:" + localState
                        set(value) {
                            localState = value
                        }

                    override fun combine(first: String, second: String): String =
                        "override:" + first + ":" + second
                }

                private class ThroughAbstractOverride : PromotedAbstractDefaults {
                    override fun abstractCombine(first: String, second: String): String =
                        "abstract:" + first + ":" + second
                }

                fun main() {

                    val promoted: PortableFeatures = ThroughPromotion()
                    promoted.observed = "promoted"
                    if (promoted.observed != "promoted") {
                        throw Error("promoted property accessors")
                    }
                    if (promoted.combine() != "OK") {
                        throw Error("promoted default arguments")
                    }
                    if (promoted.combine(second = "!") != "O!") {
                        throw Error("promoted named default argument")
                    }

                    val portable: PortableFeatures = ThroughPortableInterface()
                    portable.observed = "portable"
                    if (portable.observed != "portable") {
                        throw Error("portable property forwarders")
                    }
                    if (portable.combine() != "OK") {
                        throw Error("portable default-argument forwarder")
                    }

                    val overridden: PortableFeatures = ThroughOverride()
                    overridden.observed = "consumer"
                    if (overridden.observed != "override:consumer") {
                        throw Error("property override dispatch")
                    }
                    if (overridden.combine() != "override:O:K") {
                        throw Error("default-argument helper bypassed override")
                    }

                    val abstractDefaults: PortableAbstractDefaults = ThroughAbstractOverride()
                    if (abstractDefaults.abstractCombine() != "abstract:O:K") {
                        throw Error("abstract interface default arguments")
                    }
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("PromotedFeaturesConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument,
            listOf(portableMetadata, derivedMetadata).joinToString(File.pathSeparator) { it.path },
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "PromotedFeaturesConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )

        val consumerIl = consumerDirectory.resolve("PromotedFeaturesConsumer.il").readText()
        assertEquals(
            3,
            Regex("<InterfaceDefaultForwarder-").findAll(consumerIl).count(),
            consumerIl,
        )
        assertTrue("combine\$default" in consumerIl) { consumerIl }
        runDotNet(
            dotnetHost,
            consumerAssembly,
            consumerDirectory,
            "Cross-module promoted accessors/default arguments failed",
        )
    }

    @Test
    fun testGenericExternalInterfaceDefaultDispatcherPreservesOwnerTypeContext() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ILAsm is not available")
        requireOrAssumeToolchain(DotNetIlAssembler.findFrameworkIlasm() != null, "Framework ILAsm is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val frameworkHost = DotNetIlAssembler.findFrameworkPowerShellHost()
        requireOrAssumeToolchain(frameworkHost != null, "Windows PowerShell CLR 4 host is not available")

        val producerDirectory = File(tmpdir, "generic-default-dispatcher-producer").apply { mkdirs() }
        val producerSource = producerDirectory.resolve("producer.kt").apply {
            writeText(
                """
                package genericdefaults

                public interface GenericDefaults<T> {
                    public fun choose(value: T, fallback: T, useFallback: Boolean = false): T =
                        if (useFallback) fallback else value
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            producerSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Generic.Defaults",
            K2DotNetCompilerArguments::destination.cliArgument, producerDirectory.path,
        )
        val producerMetadata = producerDirectory.resolve("Generic.Defaults.klib")
        val chooseDeclaration = DotNetLibraryAbiCodec.decode(producerMetadata.readKlibManifest()).values
            .filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { it.defaultArgumentDispatcher != null }
        assertTrue(chooseDeclaration.isInstance)

        val consumerSource = File(tmpdir, "generic-default-dispatcher-consumer.kt").apply {
            writeText(
                """
                package genericdefaults

                private class StringDefaults : GenericDefaults<String>

                fun main() {
                    val defaults: GenericDefaults<String> = StringDefaults()
                    if (defaults.choose("O", "bad") != "O") throw Error("ordinary default")
                    if (defaults.choose("bad", "K", true) != "K") throw Error("explicit argument")
                    println("OK")
                }
                """.trimIndent()
            )
        }
        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = File(tmpdir, "generic-default-dispatcher-$target").apply { mkdirs() }
            val consumerAssembly = consumerDirectory.resolve(
                if (target == "net48") "GenericDefaultsConsumer.exe" else "GenericDefaultsConsumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, producerMetadata.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "GenericDefaultsConsumer",
                K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
            )
            val consumerIl = consumerDirectory.resolve("GenericDefaultsConsumer.il").readText()
            assertTrue("[Generic.Defaults]" in consumerIl) { consumerIl }
            assertTrue("choose\$default" in consumerIl) { consumerIl }

            if (target == "net48") {
                runAssemblerPairing(
                    frameworkExecutionCommand(checkNotNull(frameworkHost), consumerAssembly),
                    consumerDirectory,
                    "Framework generic external default dispatcher",
                )
            } else {
                runDotNet(
                    dotnetHost,
                    consumerAssembly,
                    consumerDirectory,
                    "CoreCLR generic external default dispatcher failed",
                )
            }
        }
    }

    @Test
    fun testRejectsInterfaceSuperCallsWithOmittedDefaultArguments() {
        val source = File(tmpdir, "super-call-with-default-arguments.kt").apply {
            writeText(
                """
                interface Base {
                    fun value(prefix: String = "O", suffix: String = "K"): String = prefix + suffix
                }

                class Derived : Base {
                    fun invalid(): String = super<Base>.value()
                }
                """.trimIndent()
            )
        }

        for (useLightTree in listOf(false, true)) {
            val [diagnostics, exitCode] = AbstractCliTest.executeCompilerGrabOutput(
                K2DotNetCompiler(),
                listOf(
                    source.path,
                    "-Xuse-fir-lt=$useLightTree",
                    K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
                    K2DotNetCompilerArguments::destination.cliArgument,
                    File(tmpdir, "super-call-with-default-arguments-$useLightTree.il").path,
                )
            )
            assertEquals(ExitCode.COMPILATION_ERROR, exitCode, diagnostics)
            assertTrue("super-calls with default arguments are prohibited" in diagnostics) { diagnostics }
        }
    }

    @Test
    fun testProducesCompanionLanguageMetadataContract() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val source = File(tmpdir, "library.kt").apply {
            writeText(
                """
                package sample

                public fun marker(): Unit = Unit
                """.trimIndent()
            )
        }
        val outputDirectory = File(tmpdir, "companion-metadata-library")
        compileInProcess(
            K2DotNetCompiler(),
            source.path,
            "-Xcompanion-blocks",
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Companion.Metadata.Library",
            K2DotNetCompilerArguments::destination.cliArgument, outputDirectory.path,
        )

        val manifest = outputDirectory.resolve("Companion.Metadata.Library.klib").readKlibManifest()
        assertTrue(manifest.getProperty(KLIB_PROPERTY_METADATA_FLAGS)?.toIntOrNull() != null)
        assertEquals("true", manifest.getProperty(KLIB_PROPERTY_NEW_COMPANION_INITIALIZATION))
        assertTrue(
            manifest.getProperty(KLIB_PROPERTY_MANUALLY_ALTERED_LANGUAGE_FEATURES)
                .split(' ')
                .contains("+CompanionBlocks")
        )
    }

    @Test
    fun testCompanionExtensionsUseReceiverFreeCrossModuleAbi() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val csharpToolchain = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(csharpToolchain != null, "Modern Roslyn and the net10 reference pack are not available")
        val producerDirectory = File(tmpdir, "companion-extension-producer")
        val producerSource = File(tmpdir, "library.kt").apply {
            writeText(
                """
                package companionlib

                private var targetInitialized = false

                private fun initializeTarget(): Int {
                    targetInitialized = true
                    return 40
                }

                public class Target {
                    companion {
                        public val state: Int = initializeTarget()
                        public fun blockAnswer(delta: Int): Int = state + delta
                    }
                }

                public companion fun Target.answer(value: Int): Int = value + 1
                public companion fun Target.wasInitialized(): Boolean = targetInitialized
                public companion val Target.label: String
                    get() = "receiver-free"
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            producerSource.path,
            "-Xcompanion-blocks-and-extensions",
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Companion.Extension.Library",
            K2DotNetCompilerArguments::destination.cliArgument, producerDirectory.path,
        )

        val producerIl = producerDirectory.resolve("Companion.Extension.Library.il").readText()
        assertTrue("static int32 'answer'(int32 'value')" in producerIl) { producerIl }
        assertTrue("static bool 'wasInitialized'()" in producerIl) { producerIl }
        assertTrue("static string 'get_label'()" in producerIl) { producerIl }
        assertTrue(".property string 'label'" !in producerIl) { producerIl }
        assertTrue("static int32 'state'" in producerIl) { producerIl }
        assertTrue("static int32 'blockAnswer'(int32 'delta')" in producerIl) { producerIl }

        val consumerDirectory = File(tmpdir, "companion-extension-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import companionlib.Target
                import companionlib.answer
                import companionlib.label
                import companionlib.wasInitialized

                fun main() {
                    if (Target.answer(41) != 42) throw Error("answer")
                    if (Target.label != "receiver-free") throw Error("label")
                    if (Target.wasInitialized()) throw Error("extension initialized target")
                    if (Target.blockAnswer(2) != 42) throw Error("block")
                    if (!Target.wasInitialized()) throw Error("block did not initialize target")
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("CompanionExtensionConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            "-Xcompanion-blocks-and-extensions",
            K2DotNetCompilerArguments::classpath.cliArgument,
            producerDirectory.resolve("Companion.Extension.Library.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CompanionExtensionConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )

        val consumerIl = consumerDirectory.resolve("CompanionExtensionConsumer.il").readText()
        assertTrue(
            "call int32 [Companion.Extension.Library]'companionlib.libraryKt'::'answer'(int32)" in consumerIl
        ) { consumerIl }
        assertTrue(
            "call string [Companion.Extension.Library]'companionlib.libraryKt'::'get_label'()" in consumerIl
        ) { consumerIl }
        assertTrue(
            "call int32 [Companion.Extension.Library]'companionlib.Target'::'blockAnswer'(int32)" in consumerIl
        ) { consumerIl }
        runDotNet(
            modernDotNetHostOrSkip(),
            consumerAssembly,
            consumerDirectory,
            "Companion-extension cross-module consumer failed",
        )

        val csharpDirectory = File(tmpdir, "companion-static-csharp-consumer").apply { mkdirs() }
        val csharpSource = csharpDirectory.resolve("Program.cs").apply {
            writeText(
                """
                public static class Program
                {
                    public static int Main()
                    {
                        if (companionlib.Target.blockAnswer(2) != 42)
                            return 1;
                        return companionlib.Target.state == 40 ? 0 : 2;
                    }
                }
                """.trimIndent()
            )
        }
        val csharpAssembly = csharpDirectory.resolve("CompanionStaticCSharpConsumer.dll")
        val producerAssembly = producerDirectory.resolve("Companion.Extension.Library.dll")
        val runtimeAssembly = consumerDirectory.resolve("Kotlin.Runtime.dll")
        val csharpCompile = runModernCSharpCompiler(
            checkNotNull(csharpToolchain),
            csharpSource,
            csharpAssembly,
            producerAssembly,
            runtimeAssembly,
            target = "exe",
        )
        assertEquals(0, csharpCompile.exitCode, csharpCompile.output)
        producerAssembly.copyTo(csharpDirectory.resolve(producerAssembly.name), overwrite = true)
        runtimeAssembly.copyTo(csharpDirectory.resolve(runtimeAssembly.name), overwrite = true)
        csharpDirectory.resolve("CompanionStaticCSharpConsumer.runtimeconfig.json").writeText(
            """
            {
              "runtimeOptions": {
                "tfm": "net10.0",
                "framework": {
                  "name": "Microsoft.NETCore.App",
                  "version": "10.0.0"
                },
                "rollForward": "LatestMinor"
              }
            }
            """.trimIndent()
        )
        runDotNet(
            checkNotNull(csharpToolchain).dotNetHost,
            csharpAssembly,
            csharpDirectory,
            "C# companion-static consumer failed",
        )
    }

    @Test
    fun testCompanionStaticHoldersBindAcrossModules() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ilasm is not available",
        )
        val producerDirectory = File(tmpdir, "companion-holder-producer")
        val producerSource = File(tmpdir, "holder-library.kt").apply {
            writeText(
                """
                package companionholder

                public class GenericOwner<T> private constructor(public val value: Int) {
                    public fun reveal(): Int = secret()

                    companion {
                        public const val marker: Int = 7
                        public val answer: Int get() = 42
                        private fun secret(): Int = 11
                        public fun create(value: Int = 40): GenericOwner<String> = GenericOwner(value)
                        public fun <R> echo(value: R): R = value
                    }
                }

                public interface GenericInterface<T> {
                    companion {
                        public const val marker: Int = 9
                        public val answer: Int get() = 43
                        public fun <R> echo(value: R): R = value
                    }
                }

                public class DirectOwner {
                    public fun instanceValue(): Int = 1

                    companion {
                        public fun answer(value: Int = 45): Int = value
                    }
                }

                public fun topLevelAnswer(value: Int = 46): Int = value
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            producerSource.path,
            "-Xcompanion-blocks",
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Companion.Holder.Library",
            K2DotNetCompilerArguments::destination.cliArgument, producerDirectory.path,
        )

        val producerIl = producerDirectory.resolve("Companion.Holder.Library.il").readText()
        assertTrue("'companionholder.GenericOwner`1'/'<CompanionStatics>'" in producerIl) { producerIl }
        assertTrue("'companionholder.GenericInterface'/'<CompanionStatics>'" in producerIl) { producerIl }
        assertTrue("static !!0 'echo'<'R'>(!!0 'value')" in producerIl) { producerIl }
        assertTrue("'create\$default'" in producerIl) { producerIl }
        assertTrue("KotlinCompilerAbiAttribute" in producerIl) { producerIl }

        for (target in listOf("net48", "net10.0")) {
            val profileDirectory = File(tmpdir, "companion-holder-$target")
            compileInProcess(
                K2DotNetCompiler(),
                producerSource.path,
                "-Xcompanion-blocks",
                K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "Companion.Holder.Library",
                K2DotNetCompilerArguments::destination.cliArgument, profileDirectory.path,
            )
            val profileIl = profileDirectory.resolve("Companion.Holder.Library.il").readText()
            assertTrue("'companionholder.GenericOwner`1'/'<CompanionStatics>'" in profileIl) { profileIl }
            assertTrue("'companionholder.GenericInterface'/'<CompanionStatics>'" in profileIl) { profileIl }
            assertTrue("static !!0 'echo'<'R'>(!!0 'value')" in profileIl) { profileIl }
        }

        val consumerDirectory = File(tmpdir, "companion-holder-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import companionholder.GenericInterface
                import companionholder.GenericOwner
                import companionholder.DirectOwner
                import companionholder.topLevelAnswer

                fun main() {
                    val owner = GenericOwner.create()
                    if (owner.value != 40) throw Error("default")
                    if (owner.reveal() != 11) throw Error("private bridge")
                    if (GenericOwner.marker != 7) throw Error("class const")
                    if (GenericOwner.answer != 42) throw Error("class property")
                    if (GenericOwner.echo("OK") != "OK") throw Error("class generic method")
                    if (GenericInterface.marker != 9) throw Error("interface const")
                    if (GenericInterface.answer != 43) throw Error("interface property")
                    if (GenericInterface.echo(44) != 44) throw Error("interface generic method")
                    val directOwner = DirectOwner()
                    if (directOwner.instanceValue() != 1) throw Error("direct class record")
                    if (DirectOwner.answer() != 45) throw Error("direct class default")
                    if (topLevelAnswer() != 46) throw Error("top-level default")
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("CompanionHolderConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            "-Xcompanion-blocks",
            K2DotNetCompilerArguments::classpath.cliArgument,
            producerDirectory.resolve("Companion.Holder.Library.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CompanionHolderConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )

        val consumerIl = consumerDirectory.resolve("CompanionHolderConsumer.il").readText()
        val classHolder =
            "[Companion.Holder.Library]'companionholder.GenericOwner`1'/'<CompanionStatics>'"
        val interfaceHolder =
            "[Companion.Holder.Library]'companionholder.GenericInterface'/'<CompanionStatics>'"
        assertTrue("$classHolder::'create\$default'(int32, int32)" in consumerIl) { consumerIl }
        assertTrue("$classHolder::'get_answer'()" in consumerIl) { consumerIl }
        assertTrue("$classHolder::'echo'<string>(!!0)" in consumerIl) { consumerIl }
        assertTrue("$interfaceHolder::'get_answer'()" in consumerIl) { consumerIl }
        assertTrue("$interfaceHolder::'echo'<int32>(!!0)" in consumerIl) { consumerIl }
        runDotNet(
            modernDotNetHostOrSkip(),
            consumerAssembly,
            consumerDirectory,
            "Companion-holder cross-module consumer failed",
        )
    }

    @Test
    fun testCompanionInitializationGraphBindsAcrossModules() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ilasm is not available",
        )
        val producerSource = File(tmpdir, "companion-initialization-library.kt").apply {
            writeText(
                """
                package companioninit

                private var initializationOrder: String = ""

                public fun recordInitialization(tag: String): String {
                    initializationOrder += tag
                    return tag
                }

                public fun currentInitializationOrder(): String = initializationOrder

                public open class Parent {
                    companion {
                        public val state: String = recordInitialization("P")
                    }
                }

                public interface SelectedDefault {
                    companion {
                        public val state: String = recordInitialization("I")
                    }

                    public fun selected(): String = "selected"
                }

                public interface AbstractOnly {
                    companion {
                        public val state: String = recordInitialization("A")
                    }

                    public fun abstractMember(): String
                }

                public open class ProducerChild : Parent(), AbstractOnly, SelectedDefault {
                    companion {
                        public val state: String = recordInitialization("C")
                    }

                    override fun abstractMember(): String = "abstract"
                }

                public object ProducerSingleton {
                    public val state: String = recordInitialization("O")
                }

                public class GenericProducer<T> {
                    companion object {
                        public val state: String = recordInitialization("G")
                    }
                }

                public open class GenericPrivateState<T> {
                    companion {
                        private val state: String = recordInitialization("H")
                    }
                }

                public class PortableMixed<T> {
                    companion {
                        public val first: String = "first"
                    }

                    companion object {
                        public val second: String = "second"
                    }

                    companion {
                        public val third: String = "third"
                    }
                }

                public interface PortableInterfaceMixed {
                    companion {
                        public val first: String = "first"
                    }

                    companion object {
                        public val second: String = "second"
                    }

                    companion {
                        public val third: String = "third"
                    }
                }
                """.trimIndent()
            )
        }

        fun compileProducer(target: String, directory: File) {
            compileInProcess(
                K2DotNetCompiler(),
                producerSource.path,
                "-Xcompanion-blocks",
                K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "Companion.Initialization.Library",
                K2DotNetCompilerArguments::destination.cliArgument, directory.path,
            )
            val il = directory.resolve("Companion.Initialization.Library.il").readText()
            assertTrue("'<EnsureCompanionInitialized>'" in il) { il }
            assertTrue("KotlinCompilerAbiAttribute" in il) { il }
        }

        val producerDirectory = File(tmpdir, "companion-initialization-producer")
        compileProducer("netstandard2.0", producerDirectory)
        compileProducer("net48", File(tmpdir, "companion-initialization-net48"))
        compileProducer("net10.0", File(tmpdir, "companion-initialization-net10"))

        val consumerDirectory = File(tmpdir, "companion-initialization-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import companioninit.AbstractOnly
                import companioninit.GenericProducer
                import companioninit.GenericPrivateState
                import companioninit.ProducerChild
                import companioninit.ProducerSingleton
                import companioninit.currentInitializationOrder
                import companioninit.recordInitialization

                class ConsumerChild : ProducerChild() {
                    companion {
                        val state: String = recordInitialization("D")
                    }
                }


                class GenericPrivateConsumer : GenericPrivateState<String>() {
                    companion {
                        val state: String = recordInitialization("J")
                    }
                }

                fun main() {
                    if (ConsumerChild.state != "D") throw Error("consumer state")
                    if (currentInitializationOrder() != "PICD") {
                        throw Error("cross-module order=" + currentInitializationOrder())
                    }
                    if (AbstractOnly.state != "A") throw Error("abstract-only state")
                    if (currentInitializationOrder() != "PICDA") {
                        throw Error("abstract-only order=" + currentInitializationOrder())
                    }
                    ConsumerChild()
                    if (currentInitializationOrder() != "PICDA") throw Error("reinitialized")

                    if (ProducerSingleton.state != "O") throw Error("ordinary object state")
                    if (GenericProducer.state != "G") throw Error("generic companion state")
                    GenericProducer<String>()
                    GenericProducer<Int>()
                    if (currentInitializationOrder() != "PICDAOG") {
                        throw Error("object binding order=" + currentInitializationOrder())
                    }
                    if (GenericPrivateConsumer.state != "J") throw Error("private holder state")
                    if (currentInitializationOrder() != "PICDAOGHJ") {
                        throw Error("private holder order=" + currentInitializationOrder())
                    }
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("CompanionInitializationConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            "-Xcompanion-blocks",
            K2DotNetCompilerArguments::classpath.cliArgument,
            producerDirectory.resolve("Companion.Initialization.Library.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CompanionInitializationConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )

        val consumerIl = consumerDirectory.resolve("CompanionInitializationConsumer.il").readText()
        assertTrue(
            "call void [Companion.Initialization.Library]'companioninit.ProducerChild'::" +
                    "'<EnsureCompanionInitialized>'()" in consumerIl
        ) { consumerIl }
        assertTrue("ldsfld" in consumerIl && "'<CompanionStatics>'::'Companion'" in consumerIl) { consumerIl }
        assertTrue("'companioninit.ProducerSingleton'::'INSTANCE'" in consumerIl) { consumerIl }
        assertTrue(
            "call void [Companion.Initialization.Library]'companioninit.GenericPrivateState`1'/" +
                    "'<CompanionStatics>'::'<EnsureCompanionInitialized>'()" in consumerIl
        ) { consumerIl }
        runDotNet(
            modernDotNetHostOrSkip(),
            consumerAssembly,
            consumerDirectory,
            "Companion-initialization cross-module consumer failed",
        )
    }

    @Test
    fun testProducesPortableUserLibraryPair() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val source = File(tmpdir, "library.kt").apply {
            writeText(
                """
                package sample

                public fun increment(value: Int): Int = value + 1

                public class Counter(public val value: Int) {
                    public fun plus(delta: Int): Int = value + delta
                }
                """.trimIndent()
            )
        }
        val outputDirectory = File(tmpdir, "sample-library")
        compileInProcess(
            K2DotNetCompiler(),
            source.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Sample.Library",
            K2DotNetCompilerArguments::dotNetExports.cliArgument, "sample.increment=Increment",
            K2DotNetCompilerArguments::destination.cliArgument, outputDirectory.path,
        )

        val metadataLibrary = outputDirectory.resolve("Sample.Library.klib")
        val implementationLibrary = outputDirectory.resolve("Sample.Library.dll")
        assertTrue(metadataLibrary.isFile) { "Expected packed metadata KLIB at $metadataLibrary" }
        assertTrue(implementationLibrary.isFile) { "Expected CLR implementation at $implementationLibrary" }
        val manifest = metadataLibrary.readKlibManifest()
        assertTrue(manifest.getProperty("unique_name") == "Sample.Library")
        assertTrue(manifest.getProperty("dotnet_assembly_name") == "Sample.Library")
        assertTrue(manifest.getProperty("dotnet_assembly_version") == "1.0.0.0")
        assertTrue(manifest.getProperty("dotnet_assembly_file") == "Sample.Library.dll")
        assertEquals("netstandard2.0", manifest.getProperty("dotnet_library_tfm"))
        assertEquals(DotNetLibraryAbiCodec.ABI_VERSION, manifest.getProperty("dotnet_abi_version"))
        assertEquals("", manifest.getProperty(DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY))
        assertTrue(
            manifest.getProperty(DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY) ==
                    DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME
        )
        assertTrue(
            manifest.getProperty(DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY) ==
                    DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION
        )
        assertTrue(
            manifest.getProperty(DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY) ==
                    DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL.toString()
        )
        assertEquals(
            DotNetLibraryAbiCodec.implementationSha256(implementationLibrary),
            manifest.getProperty(DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY),
        )
        assertTrue(manifest.stringPropertyNames().any { it.startsWith("dotnet_decl_") })

        val il = outputDirectory.resolve("Sample.Library.il").readText()
        assertTrue(".assembly extern netstandard" in il)
        assertTrue("System.Runtime.Versioning.TargetFrameworkAttribute" in il)
        assertTrue(".ver 1:0:0:0" in il)
        assertTrue(".module 'Sample.Library.dll'" in il)
        assertTrue("'Increment'(int32 'value')" in il)
        assertTrue(".entrypoint" !in il)
        assertTrue("[mscorlib]" !in il)

        val dotnetHost = modernDotNetHostOrSkip()
        val consumerIl = outputDirectory.resolve("LibraryConsumer.il").apply {
            writeText(
                """
                .assembly extern mscorlib {}
                .assembly extern Sample.Library
                {
                  .ver 1:0:0:0
                }
                .assembly LibraryConsumer {}
                .module LibraryConsumer.dll

                .method public static void Main() cil managed
                {
                  .entrypoint
                  .maxstack 2
                  ldc.i4.s 41
                  call int32 [Sample.Library]'sample.libraryKt'::'Increment'(int32)
                  ldc.i4.s 42
                  beq.s IL_success
                  ldstr "Portable Kotlin library returned an unexpected result."
                  newobj instance void [mscorlib]System.Exception::.ctor(string)
                  throw
                IL_success:
                  ret
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = outputDirectory.resolve("LibraryConsumer.dll")
        assertTrue(
            DotNetIlAssembler.assembleExecutable(
                consumerIl,
                consumerAssembly,
                DotNetTarget.NET10_0,
                MessageCollector.NONE,
            )
        )
        runDotNet(dotnetHost, consumerAssembly, outputDirectory, "Portable library consumer failed")

        val kotlinConsumerDirectory = outputDirectory.resolve("kotlin-consumer").apply { mkdirs() }
        val kotlinConsumerSource = kotlinConsumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import sample.Counter
                import sample.increment

                fun main() {
                    val answer = increment(Counter(40).plus(1))
                    if (answer != 42) throw Error("Kotlin library returned ${'$'}answer")
                }
                """.trimIndent()
            )
        }
        val kotlinConsumerAssembly = kotlinConsumerDirectory.resolve("KotlinConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            kotlinConsumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "KotlinConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, kotlinConsumerAssembly.path,
        )
        assertTrue(kotlinConsumerDirectory.resolve("Sample.Library.dll").isFile) {
            "The external CLR implementation must be packaged beside an executable consumer"
        }
        val kotlinConsumerIl = kotlinConsumerDirectory.resolve("KotlinConsumer.il").readText()
        assertTrue(".assembly extern 'Sample.Library'" in kotlinConsumerIl)
        assertTrue("[Sample.Library]" in kotlinConsumerIl)
        runDotNet(
            dotnetHost,
            kotlinConsumerAssembly,
            kotlinConsumerDirectory,
            "Kotlin cross-module library consumer failed",
        )

        val unrelatedConsumerDirectory = outputDirectory.resolve("unrelated-consumer").apply { mkdirs() }
        val unrelatedConsumerSource = unrelatedConsumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package unrelated

                fun main() {
                    if (40 + 2 != 42) throw Error("arithmetic")
                }
                """.trimIndent()
            )
        }
        val unrelatedConsumerAssembly = unrelatedConsumerDirectory.resolve("UnrelatedConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            unrelatedConsumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "UnrelatedConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, unrelatedConsumerAssembly.path,
        )
        val unrelatedConsumerIl = unrelatedConsumerDirectory.resolve("UnrelatedConsumer.il").readText()
        assertTrue(".assembly extern 'Sample.Library'" !in unrelatedConsumerIl)
        assertTrue("[Sample.Library]" !in unrelatedConsumerIl)
        assertTrue(!unrelatedConsumerDirectory.resolve("Sample.Library.dll").exists()) {
            "An unused metadata classpath entry must not become a CLR runtime dependency"
        }
        runDotNet(
            dotnetHost,
            unrelatedConsumerAssembly,
            unrelatedConsumerDirectory,
            "Consumer with an unused Kotlin/.NET classpath library failed",
        )
    }

    @Test
    fun testTargetProfilesAreExplicitAndDependencyCompatible() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        requireOrAssumeToolchain(DotNetIlAssembler.findFrameworkIlasm() != null, ".NET Framework ilasm is not available")

        fun produceLibrary(target: String, assemblyName: String): File {
            val directory = File(tmpdir, assemblyName)
            val source = File(tmpdir, "$assemblyName.kt").apply {
                writeText("package profiles\n\npublic fun answer(): Int = 42")
            }
            compileInProcess(
                K2DotNetCompiler(),
                source.path,
                K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, assemblyName,
                K2DotNetCompilerArguments::destination.cliArgument, directory.path,
            )
            val metadata = directory.resolve("$assemblyName.klib")
            assertEquals(target, metadata.readKlibManifest().getProperty("dotnet_library_tfm"))
            val il = directory.resolve("$assemblyName.il").readText()
            assertTrue("System.Runtime.Versioning.TargetFrameworkAttribute" in il) { il }
            if (target == "netstandard2.0") {
                assertTrue(".assembly extern netstandard" in il) { il }
                assertTrue("[mscorlib]" !in il) { il }
            } else {
                assertTrue(".assembly extern mscorlib" in il) { il }
            }
            return metadata
        }

        val net48Library = produceLibrary("net48", "Profile.Net48")
        val portableLibrary = produceLibrary("netstandard2.0", "Profile.Standard")
        val net10Library = produceLibrary("net10.0", "Profile.Net10")
        val consumerSource = File(tmpdir, "profile-consumer.kt").apply {
            writeText("package consumer\n\npublic fun consume(): Int = profiles.answer()")
        }

        fun compileConsumer(target: String, dependency: File, outputName: String): Pair<String, ExitCode> =
            AbstractCliTest.executeCompilerGrabOutput(
                K2DotNetCompiler(),
                listOf(
                    consumerSource.path,
                    K2DotNetCompilerArguments::classpath.cliArgument, dependency.path,
                    K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                    K2DotNetCompilerArguments::moduleName.cliArgument, outputName,
                    K2DotNetCompilerArguments::destination.cliArgument, File(tmpdir, "$outputName.il").path,
                )
            )

        for (target in listOf("net48", "net10.0")) {
            val [diagnostics, exitCode] = compileConsumer(target, portableLibrary, "PortableOn-$target")
            assertEquals(ExitCode.OK, exitCode, diagnostics)
        }
        for (entry in listOf("net48" to net10Library, "net10.0" to net48Library)) {
            val target = entry.first
            val dependency = entry.second
            val [diagnostics, exitCode] = compileConsumer(target, dependency, "RejectedOn-$target")
            assertEquals(ExitCode.COMPILATION_ERROR, exitCode, diagnostics)
            assertTrue("is not compatible with Kotlin/.NET target '$target'" in diagnostics) { diagnostics }
        }

        val [executableDiagnostics, executableExitCode] = AbstractCliTest.executeCompilerGrabOutput(
            K2DotNetCompiler(),
            listOf(
                consumerSource.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
                K2DotNetCompilerArguments::destination.cliArgument, File(tmpdir, "InvalidStandardApp.il").path,
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, executableExitCode, executableDiagnostics)
        assertTrue("target profile 'netstandard2.0' is library-only" in executableDiagnostics) {
            executableDiagnostics
        }

        val [standardDiagnostics, standardExitCode] = AbstractCliTest.executeCompilerGrabOutput(
            K2DotNetCompiler(),
            listOf(
                consumerSource.path,
                K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
                K2DotNetCompilerArguments::classpath.cliArgument, net48Library.path,
                K2DotNetCompilerArguments::moduleName.cliArgument, "Rejected.Standard.Consumer",
                K2DotNetCompilerArguments::destination.cliArgument, File(tmpdir, "rejected-standard-consumer").path,
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, standardExitCode, standardDiagnostics)
        assertTrue("is not compatible with Kotlin/.NET target 'netstandard2.0'" in standardDiagnostics) {
            standardDiagnostics
        }
    }

    @Test
    fun testRuntimeStdlibVariantsArePortablePhysicalAbiSupersets() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        requireOrAssumeToolchain(DotNetIlAssembler.findFrameworkIlasm() != null, ".NET Framework ilasm is not available")
        val csharpToolchain = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(
            csharpToolchain != null,
            "Modern Roslyn and the net10 reference pack are not available",
        )

        val pairDirectories = listOf("netstandard2.0", "net48", "net10.0").associateWith { target ->
            produceBoundStdlibPair(target, "portable-abi-superset")
        }
        val runtimeAssemblies = DotNetTarget.entries.associateWith { target ->
            val outputDirectory = File(tmpdir, "portable-surface-runtime-${target.flagValue}")
            val runtime = DotNetIlAssembler.assembleRuntimeForTests(
                outputDirectory,
                target,
                MessageCollector.NONE,
            )
            assertTrue(runtime?.isFile == true) { "Failed to produce ${target.flagValue} Kotlin.Runtime.dll" }
            checkNotNull(runtime)
        }
        val surfaceVerifierSource = File(
            "compiler/testData/codegen/dotnet/portableSurfaceVerifier.cs"
        ).absoluteFile
        assertTrue(surfaceVerifierSource.isFile) { "Missing CLR surface verifier: $surfaceVerifierSource" }
        val surfaceVerifierDirectory = File(tmpdir, "portable-surface-verifier").apply { mkdirs() }
        val surfaceVerifier = surfaceVerifierDirectory.resolve("PortableSurfaceVerifier.dll")
        val surfaceVerifierCompile = runModernCSharpCompiler(
            checkNotNull(csharpToolchain),
            surfaceVerifierSource,
            surfaceVerifier,
            target = "exe",
        )
        assertEquals(0, surfaceVerifierCompile.exitCode, surfaceVerifierCompile.output)
        surfaceVerifierDirectory.resolve("PortableSurfaceVerifier.runtimeconfig.json").writeText(
            """
            {
              "runtimeOptions": {
                "tfm": "net10.0",
                "framework": {
                  "name": "Microsoft.NETCore.App",
                  "version": "10.0.0"
                },
                "rollForward": "LatestMinor"
              }
            }
            """.trimIndent()
        )
        val manifests = pairDirectories.mapValues { entry ->
            entry.value.resolve("Kotlin.Stdlib.klib").readKlibManifest()
        }
        val portableManifest = manifests.getValue("netstandard2.0")
        val portableDeclarations = DotNetLibraryAbiCodec.decode(portableManifest)
        val portableRuntimeSurface = portableManifest
            .getProperty(DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY)
            .toInt()
        assertTrue(portableDeclarations.isNotEmpty())

        for (target in listOf("net48", "net10.0")) {
            val platformManifest = manifests.getValue(target)
            assertEquals(
                portableManifest.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY),
                platformManifest.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY),
                "$target changed the physical-index schema",
            )
            assertEquals(
                portableManifest.getProperty(DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY),
                platformManifest.getProperty(DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY),
                "$target changed the Kotlin logical-identity scheme",
            )
            assertEquals(
                portableManifest.getProperty(DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY),
                platformManifest.getProperty(DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY),
                "$target changed the CLR physical-name grammar",
            )
            val platformRuntimeSurface = platformManifest
                .getProperty(DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY)
                .toInt()
            assertTrue(platformRuntimeSurface >= portableRuntimeSurface) {
                "$target requires runtime surface $platformRuntimeSurface below the portable floor $portableRuntimeSurface"
            }

            val differences = DotNetLibraryAbiCodec.portablePhysicalAbiDifferences(
                portableDeclarations,
                DotNetLibraryAbiCodec.decode(platformManifest),
            )
            assertTrue(differences.isEmpty()) {
                buildString {
                    appendLine("$target is not a physical ABI superset of netstandard2.0:")
                    differences.forEach { difference ->
                        append("  ")
                        append(difference.logicalKey)
                        append(": portable=")
                        append(difference.portableDeclaration)
                        append(", platform=")
                        appendLine(difference.platformDeclaration ?: "<missing>")
                    }
                }
            }

            val portableDirectory = pairDirectories.getValue("netstandard2.0")
            val platformDirectory = pairDirectories.getValue(target)
            val comparison = ProcessBuilder(
                checkNotNull(csharpToolchain).dotNetHost.path,
                "exec",
                surfaceVerifier.path,
                runtimeAssemblies.getValue(DotNetTarget.NETSTANDARD_2_0).path,
                runtimeAssemblies.getValue(checkNotNull(DotNetTarget.fromFlagValue(target))).path,
                portableDirectory.resolve("Kotlin.Stdlib.dll").path,
                platformDirectory.resolve("Kotlin.Stdlib.dll").path,
            ).directory(surfaceVerifierDirectory).redirectErrorStream(true).start()
            val comparisonOutput = comparison.inputStream.bufferedReader().use { it.readText() }
            assertEquals(
                0,
                comparison.waitFor(),
                "$target is not an externally consumable CLR metadata superset of netstandard2.0:\n" +
                        comparisonOutput,
            )
            assertTrue(Regex("OK [1-9][0-9]*").matches(comparisonOutput.trim())) { comparisonOutput }
        }

    }

    @Test
    fun testVarargLogicalIdentitySurvivesLibraryLowering() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val libraryDirectory = File(tmpdir, "vararg-library")
        val librarySource = File(tmpdir, "vararg-library.kt").apply {
            writeText(
                """
                package crossvararg

                public fun sum(vararg values: Int): Int = values[0] + values[1]
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CrossVararg.Library",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val consumerDirectory = File(tmpdir, "vararg-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import crossvararg.sum

                fun main() {
                    if (sum(20, 22) != 42) throw Error("cross-module vararg")
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("CrossVarargConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument,
            libraryDirectory.resolve("CrossVararg.Library.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CrossVarargConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )
        val consumerIl = consumerDirectory.resolve("CrossVarargConsumer.il").readText()
        assertTrue("[CrossVararg.Library]" in consumerIl)
        runDotNet(
            dotnetHost,
            consumerAssembly,
            consumerDirectory,
            "Cross-module vararg consumer failed",
        )
    }

    @Test
    fun testFriendAuthorizationAndPublishedCompilerAbiAcrossLibraryBoundary() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val csharpCompiler = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(csharpCompiler != null, ".NET Framework C# compiler is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val producerDirectory = File(tmpdir, "friend-producer")
        val producerSource = File(tmpdir, "friendProducer.kt").apply {
            writeText(
                """
                package friendship

                internal const val INTERNAL_CONST: Int = 19
                @PublishedApi internal const val PUBLISHED_CONST: Int = 20

                internal class InternalBox(internal val value: Int)

                @PublishedApi
                internal class PublishedBox(public val value: Int)

                internal fun internalAnswer(value: Int): Int = InternalBox(value).value + INTERNAL_CONST
                @PublishedApi internal fun publishedAnswer(value: Int): Int = value + PUBLISHED_CONST
                public fun publicAnswer(value: Int): Int = value + 2
                """.trimIndent()
            )
        }
        val longPublicKey =
            "0024000004800000940000000602000000240000525341310004000001000100" +
                    "8D56C76F9E8649383049F383C44BE0EC204181822A6C31CF5EB7EF486944D032" +
                    "188EA1D3920763712CCB12D75FB77E9811149E6148E5D32FBAAB37611C1878DD" +
                    "C19E20EF135D0CB2CFF2BFEC3D115810C3D9069638FE4BE215DBF795861920E5" +
                    "AB6F7DB2E2CEEF136AC23D5DD2BF031700AEC232F6C6B1C785B4305C123B37AB"
        compileInProcess(
            K2DotNetCompiler(),
            producerSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Friend.Producer",
            "${K2DotNetCompilerArguments::dotNetFriendAssemblies.cliArgument}=Friend.Consumer",
            "${K2DotNetCompilerArguments::dotNetFriendAssemblies.cliArgument}=" +
                    "Signed.Consumer, PublicKey=$longPublicKey",
            K2DotNetCompilerArguments::destination.cliArgument, producerDirectory.path,
        )

        val producerMetadata = producerDirectory.resolve("Friend.Producer.klib")
        val producerIl = producerDirectory.resolve("Friend.Producer.il").readText()
        val producerManifest = producerMetadata.readKlibManifest()
        assertEquals(
            setOf(
                DotNetFriendAssemblyIdentity("Friend.Consumer"),
                DotNetFriendAssemblyIdentity("Signed.Consumer", longPublicKey),
            ),
            DotNetLibraryAbiCodec.decodeFriendAssemblies(
                producerManifest.getProperty(DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY)
            ),
        )
        assertTrue("System.Runtime.CompilerServices.InternalsVisibleToAttribute" in producerIl) { producerIl }
        assertTrue(".method assembly hidebysig static int32 'internalAnswer'" in producerIl) { producerIl }
        assertTrue(".method public hidebysig static int32 'publishedAnswer'" in producerIl) { producerIl }
        assertTrue(".field assembly static literal int32 'INTERNAL_CONST'" in producerIl) { producerIl }
        assertTrue(".field public static literal int32 'PUBLISHED_CONST'" in producerIl) { producerIl }
        assertTrue(".class private auto ansi sealed beforefieldinit 'friendship.InternalBox'" in producerIl) { producerIl }
        assertTrue(".class public auto ansi sealed beforefieldinit 'friendship.PublishedBox'" in producerIl) { producerIl }
        assertTrue("'friendship.friendProducerKt'" in producerIl) { producerIl }
        assertTrue("KotlinCompilerAbiAttribute" in producerIl) { producerIl }
        assertTrue("System.ComponentModel.EditorBrowsableAttribute" in producerIl) { producerIl }

        val consumerDirectory = producerDirectory.resolve("authorized-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import friendship.InternalBox
                import friendship.PublishedBox
                import friendship.internalAnswer
                import friendship.publishedAnswer
                import friendship.publicAnswer

                fun main() {
                    val answer = internalAnswer(1) + InternalBox(1).value +
                            publishedAnswer(1) + PublishedBox(1).value + publicAnswer(1)
                    if (answer != 46) throw Error("friend result: ${'$'}answer")
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("Friend.Consumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument, producerMetadata.path,
            K2DotNetCompilerArguments::friendPaths.cliArgument, producerMetadata.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Friend.Consumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )
        runDotNet(
            dotnetHost,
            consumerAssembly,
            consumerDirectory,
            "Authorized Kotlin/.NET friend consumer failed",
        )

        val csharpDirectory = File(tmpdir, "friend-cs").apply { mkdirs() }
        val csharpSource = csharpDirectory.resolve("Program.cs").apply { writeText(
            """
            public static class Program
            {
                public static int Main()
                {
                    return friendship.friendProducerKt.internalAnswer(23) == 42 ? 0 : 1;
                }
            }
            """.trimIndent()
        ) }
        val frameworkProducerIl = csharpDirectory.resolve("Friend.Producer.il")
        compileInProcess(
            K2DotNetCompiler(),
            producerSource.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net48",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Friend.Producer",
            "${K2DotNetCompilerArguments::dotNetFriendAssemblies.cliArgument}=Friend.Consumer",
            K2DotNetCompilerArguments::destination.cliArgument, frameworkProducerIl.path,
        )
        val producerImplementation = csharpDirectory.resolve("Friend.Producer.dll")
        assertTrue(
            DotNetIlAssembler.assembleLibrary(
                frameworkProducerIl,
                producerImplementation,
                DotNetTarget.NET48,
                MessageCollector.NONE,
            )
        )
        val runtimeImplementation = consumerDirectory.resolve("Kotlin.Runtime.dll")
        val csharpExecutable = csharpDirectory.resolve("Friend.Consumer.exe")
        val csharpResult = runCSharpCompiler(
            checkNotNull(csharpCompiler),
            csharpSource,
            csharpExecutable,
            producerImplementation,
            runtimeImplementation,
            target = "exe",
        )
        assertEquals(0, csharpResult.exitCode, csharpResult.output)
        runtimeImplementation.copyTo(csharpDirectory.resolve(runtimeImplementation.name), overwrite = true)
        val csharpProcess = ProcessBuilder(csharpExecutable.path)
            .directory(csharpDirectory)
            .redirectErrorStream(true)
            .start()
        val csharpOutput = csharpProcess.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, csharpProcess.waitFor(), "Authorized C# friend consumer failed:\n$csharpOutput")

        val unauthorizedSource = File(tmpdir, "unauthorized-friend.kt").apply {
            writeText("package intruder\n\npublic fun answer(): Int = 42")
        }
        val [unauthorizedDiagnostics, unauthorizedExitCode] = AbstractCliTest.executeCompilerGrabOutput(
            K2DotNetCompiler(),
            listOf(
                unauthorizedSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, producerMetadata.path,
                K2DotNetCompilerArguments::friendPaths.cliArgument, producerMetadata.path,
                K2DotNetCompilerArguments::moduleName.cliArgument, "Unauthorized.Consumer",
                K2DotNetCompilerArguments::destination.cliArgument,
                File(tmpdir, "Unauthorized.Consumer.il").path,
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, unauthorizedExitCode, unauthorizedDiagnostics)
        assertTrue("does not authorize unsigned consumer assembly 'Unauthorized.Consumer'" in unauthorizedDiagnostics) {
            unauthorizedDiagnostics
        }

        val nonFriendSource = File(tmpdir, "non-friend.kt").apply {
            writeText(
                """
                package nonfriend

                import friendship.internalAnswer

                public fun forbidden(): Int = internalAnswer(23)
                """.trimIndent()
            )
        }
        val [nonFriendDiagnostics, nonFriendExitCode] = AbstractCliTest.executeCompilerGrabOutput(
            K2DotNetCompiler(),
            listOf(
                nonFriendSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, producerMetadata.path,
                K2DotNetCompilerArguments::moduleName.cliArgument, "Friend.Consumer",
                K2DotNetCompilerArguments::destination.cliArgument, File(tmpdir, "NonFriend.il").path,
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, nonFriendExitCode, nonFriendDiagnostics)
        assertTrue("internal" in nonFriendDiagnostics) { nonFriendDiagnostics }
    }

    @Test
    fun testProducesNet10StdlibPair() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        produceAndConsumeBoundStdlibPair("net10.0")
    }

    @Test
    fun testProducesNet48StdlibPair() {
        requireOrAssumeToolchain(DotNetIlAssembler.findFrameworkIlasm() != null, ".NET Framework ilasm is not available")
        produceAndConsumeBoundStdlibPair("net48")
    }

    @Test
    fun testPortableStdlibPairExecutesOnBothRuntimeProfiles() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        requireOrAssumeToolchain(DotNetIlAssembler.findFrameworkIlasm() != null, ".NET Framework ilasm is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val pairDirectory = produceBoundStdlibPair("netstandard2.0", "shared")
        consumeBoundStdlibPair(pairDirectory, "net48")
        consumeBoundStdlibPair(pairDirectory, "net10.0")
        consumeInstalledStdlibPair(pairDirectory, "net48", installedProfile = "netstandard2.0")
        consumeInstalledStdlibPair(pairDirectory, "net10.0", installedProfile = "netstandard2.0")
        executeBoundStdlibPair(pairDirectory, "net48", dotnetHost = null)
        executeBoundStdlibPair(pairDirectory, "net10.0", dotnetHost)
    }

    @Test
    fun testNet48AssemblerMatrix() {
        val frameworkIlasm = DotNetIlAssembler.findFrameworkIlasm()
        val modernIlasm = DotNetIlAssembler.findModernIlasm()
        requireOrAssumeToolchain(frameworkIlasm != null, ".NET Framework ILAsm is not available")
        requireOrAssumeToolchain(modernIlasm != null, "Modern ILAsm is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val frameworkHost = DotNetIlAssembler.findFrameworkPowerShellHost()
        requireOrAssumeToolchain(frameworkHost != null, "Windows PowerShell CLR 4 host is not available")

        val stdlibPair = produceBoundStdlibPair("net48", "assembler-matrix")
        val frameworkStdlib = stdlibPair.resolve("Kotlin.Stdlib.dll")
        val modernStdlib = File(tmpdir, "assembler-matrix-modern/Kotlin.Stdlib.dll")
        assertTrue(
            DotNetIlAssembler.assembleWithExplicitIlasm(
                checkNotNull(modernIlasm),
                stdlibPair.resolve("Kotlin.Stdlib.il"),
                modernStdlib,
                dll = true,
                messageCollector = MessageCollector.NONE,
            )
        )

        val applicationDirectory = File(tmpdir, "assembler-matrix-application").apply { mkdirs() }
        val applicationSource = applicationDirectory.resolve("main.kt").apply {
            writeText(
                """
                fun main() {
                    val values = Array<String>(2) { index -> if (index == 0) "O" else "K" }
                    val render = { values.asIterable().first() + values.asIterable().last() }
                    println(render())
                }
                """.trimIndent()
            )
        }
        val frameworkApplication = applicationDirectory.resolve("AssemblerMatrix.exe")
        compileInProcess(
            K2DotNetCompiler(),
            applicationSource.path,
            K2DotNetCompilerArguments::noStdlib.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, stdlibPair.resolve("Kotlin.Stdlib.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net48",
            K2DotNetCompilerArguments::moduleName.cliArgument, "AssemblerMatrix",
            K2DotNetCompilerArguments::destination.cliArgument, frameworkApplication.path,
        )
        val applicationIl = applicationDirectory.resolve("AssemblerMatrix.il")
        val modernFrameworkApplication = applicationDirectory.resolve("AssemblerMatrix-modern.exe")
        assertTrue(
            DotNetIlAssembler.assembleWithExplicitIlasm(
                checkNotNull(modernIlasm),
                applicationIl,
                modernFrameworkApplication,
                dll = false,
                messageCollector = MessageCollector.NONE,
            )
        )
        val modernCoreClrApplication = applicationDirectory.resolve("AssemblerMatrix-modern.dll")
        assertTrue(
            DotNetIlAssembler.assembleExecutable(
                applicationIl,
                modernCoreClrApplication,
                DotNetTarget.NET10_0,
                MessageCollector.NONE,
            )
        )

        val frameworkRuntime = applicationDirectory.resolve("Kotlin.Runtime.dll")
        assertTrue(frameworkRuntime.isFile)
        val modernRuntime = DotNetIlAssembler.assembleRuntimeWithExplicitIlasmForTests(
            File(tmpdir, "assembler-matrix-modern-runtime"),
            DotNetTarget.NET48,
            checkNotNull(modernIlasm),
            MessageCollector.NONE,
        )
        assertTrue(modernRuntime?.isFile == true)
        val coreClrRuntimeConfig = applicationDirectory.resolve("AssemblerMatrix-modern.runtimeconfig.json")
        assertTrue(coreClrRuntimeConfig.isFile)

        val applications = listOf(
            "f" to (frameworkApplication to frameworkApplication),
            "m" to (modernFrameworkApplication to modernCoreClrApplication),
        )
        val stdlibs = listOf(
            "f" to frameworkStdlib,
            "m" to modernStdlib,
        )
        val runtimes = listOf(
            "f" to frameworkRuntime,
            "m" to checkNotNull(modernRuntime),
        )
        for (application in applications) {
            val applicationAssembler = application.first
            val applicationFiles = application.second
            for (stdlibEntry in stdlibs) {
                val stdlibAssembler = stdlibEntry.first
                val stdlib = stdlibEntry.second
                for (runtimeEntry in runtimes) {
                    val runtimeAssembler = runtimeEntry.first
                    val runtime = runtimeEntry.second
                    val pairing = "$applicationAssembler-$stdlibAssembler-$runtimeAssembler"
                    val frameworkDirectory = File(tmpdir, "am-f-$pairing").apply { mkdirs() }
                    val frameworkExecutable = applicationFiles.first.copyTo(
                        frameworkDirectory.resolve("AssemblerMatrix.exe")
                    )
                    stdlib.copyTo(frameworkDirectory.resolve("Kotlin.Stdlib.dll"))
                    runtime.copyTo(frameworkDirectory.resolve("Kotlin.Runtime.dll"))
                    runAssemblerPairing(
                        frameworkExecutionCommand(checkNotNull(frameworkHost), frameworkExecutable),
                        frameworkDirectory,
                        "Framework host, $pairing",
                    )

                    val coreClrDirectory = File(tmpdir, "am-n-$pairing").apply { mkdirs() }
                    val coreClrExecutable = applicationFiles.second.copyTo(
                        coreClrDirectory.resolve("AssemblerMatrix.${applicationFiles.second.extension}")
                    )
                    coreClrRuntimeConfig.copyTo(coreClrDirectory.resolve("AssemblerMatrix.runtimeconfig.json"))
                    stdlib.copyTo(coreClrDirectory.resolve("Kotlin.Stdlib.dll"))
                    runtime.copyTo(coreClrDirectory.resolve("Kotlin.Runtime.dll"))
                    runAssemblerPairing(
                        listOf(dotnetHost.path, "exec", coreClrExecutable.path),
                        coreClrDirectory,
                        "CoreCLR host, $pairing",
                    )
                }
            }
        }
    }

    @Test
    fun testNet10AssemblerBoundary() {
        val frameworkIlasm = DotNetIlAssembler.findFrameworkIlasm()
        val modernIlasm = DotNetIlAssembler.findModernIlasm()
        requireOrAssumeToolchain(frameworkIlasm != null, ".NET Framework ILAsm is not available")
        requireOrAssumeToolchain(modernIlasm != null, "Modern ILAsm is not available")
        val dotnetHost = modernDotNetHostOrSkip()

        val stdlibPair = produceBoundStdlibPair("net10.0", "n10-boundary")
        val applicationDirectory = File(tmpdir, "n10-a").apply { mkdirs() }
        val applicationSource = applicationDirectory.resolve("main.kt").apply {
            writeText(
                """
                public interface Prefix {
                    public fun value(): String = "O"
                }

                public class DefaultPrefix : Prefix

                fun main() {
                    val prefix: Prefix = DefaultPrefix()
                    val suffix = Array<String>(1) { "K" }.asIterable().first()
                    println(prefix.value() + suffix)
                }
                """.trimIndent()
            )
        }
        val modernApplication = applicationDirectory.resolve("Net10Boundary.dll")
        compileInProcess(
            K2DotNetCompiler(),
            applicationSource.path,
            K2DotNetCompilerArguments::noStdlib.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, stdlibPair.resolve("Kotlin.Stdlib.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Net10Boundary",
            K2DotNetCompilerArguments::destination.cliArgument, modernApplication.path,
        )
        val applicationIl = applicationDirectory.resolve("Net10Boundary.il")
        val applicationIlText = applicationIl.readText()
        assertTrue("interface public abstract auto ansi 'Prefix'" in applicationIlText) { applicationIlText }
        assertTrue("virtual instance string 'value'() cil managed" in applicationIlText) { applicationIlText }
        assertFalse("abstract virtual instance string 'value'()" in applicationIlText) { applicationIlText }

        val rejectedFrameworkApplication = File(tmpdir, "n10-af/Net10Boundary.dll")
        assertFalse(
            DotNetIlAssembler.assembleWithExplicitIlasm(
                checkNotNull(frameworkIlasm),
                applicationIl,
                rejectedFrameworkApplication,
                dll = true,
                messageCollector = MessageCollector.NONE,
            )
        )
        assertFalse(rejectedFrameworkApplication.exists()) {
            "A failed legacy assembly attempt left a partial net10 binary: $rejectedFrameworkApplication"
        }

        val modernRuntime = applicationDirectory.resolve("Kotlin.Runtime.dll")
        assertTrue(modernRuntime.isFile)
        val modernStdlib = applicationDirectory.resolve("Kotlin.Stdlib.dll")
        assertTrue(modernStdlib.isFile)
        val runtimeConfig = applicationDirectory.resolve("Net10Boundary.runtimeconfig.json")
        assertTrue(runtimeConfig.isFile)
        runAssemblerPairing(
            listOf(dotnetHost.path, "exec", modernApplication.path),
            applicationDirectory,
            "CoreCLR net10 DIM writer boundary",
        )
    }

    @Test
    fun testLibraryPublicationFailsWhenADeclarationIsEvicted() {
        val validOverrideSource = File(tmpdir, "valid-inherited-generic-interface-override.kt").apply {
            writeText(
                """
                package sample

                public interface OverrideBase<out T> {
                    public val value: T
                }

                public interface OverrideDerived<out T> : OverrideBase<T> {
                    override val value: T
                }

                public interface IntersectionLeft<out T> {
                    public fun read(): T
                }

                public interface IntersectionRight<out T> {
                    public fun read(): T
                }

                public interface IntersectionDerived<out T> :
                    IntersectionLeft<T>, IntersectionRight<T>
                """.trimIndent()
            )
        }
        val validOverrideOutput = File(tmpdir, "valid-inherited-generic-interface-override.il")
        compileInProcess(
            K2DotNetCompiler(),
            validOverrideSource.path,
            K2DotNetCompilerArguments::moduleName.cliArgument, "Valid.Inherited.Override",
            K2DotNetCompilerArguments::destination.cliArgument, validOverrideOutput.path,
        )
        assertTrue(validOverrideOutput.isFile)

        fun assertPublicationFails(
            moduleName: String,
            sourceText: String,
            vararg expectedDiagnostics: String,
        ) {
            val source = File(tmpdir, "$moduleName.kt").apply { writeText(sourceText.trimIndent()) }
            val outputDirectory = File(tmpdir, moduleName)
            val [output, exitCode] = AbstractCliTest.executeCompilerGrabOutput(
                K2DotNetCompiler(),
                listOf(
                    source.path,
                    K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                    K2DotNetCompilerArguments::moduleName.cliArgument, moduleName,
                    K2DotNetCompilerArguments::destination.cliArgument, outputDirectory.path,
                )
            )

            assertEquals(ExitCode.COMPILATION_ERROR, exitCode, output)
            assertTrue("is not supported by the .NET backend and was skipped" in output) { output }
            expectedDiagnostics.forEach { diagnostic ->
                assertTrue(diagnostic in output) { "Missing '$diagnostic':\n$output" }
            }
            assertTrue(!outputDirectory.resolve("$moduleName.klib").exists())
            assertTrue(!outputDirectory.resolve("$moduleName.dll").exists())
        }

        assertPublicationFails(
            "Unsupported.Library",
            """
            package sample

            public fun unsupported(value: Float): Float = value
            """,
        )
        assertPublicationFails(
            "Generic.Interface.Clashes",
            """
            package sample

            public interface DeclaredAccessorClash<out T> {
                public val value: T
                public fun get_value(): T
            }

            public interface ExactAccessorClash<out T> {
                public var value: @UnsafeVariance T
                public fun set_value(value: @UnsafeVariance T)
            }

            public interface InheritedAccessorBase<out T> {
                public fun get_value(): T
            }

            public interface InheritedAccessorClash<out T> : InheritedAccessorBase<T> {
                public val value: T
            }

            public interface InheritedOnlyMethod<out T> {
                public fun get_item(): T
            }

            public interface InheritedOnlyProperty<out T> {
                public val item: T
            }

            public interface InheritedOnlyAccessorClash<out T> :
                InheritedOnlyMethod<T>, InheritedOnlyProperty<T>

            public class OwnerBoundBox<T>

            public interface NestedOwnerBoundLeft<T> {
                public fun <R : T> retainBox(value: OwnerBoundBox<R>): OwnerBoundBox<R>
            }

            public interface NestedOwnerBoundRight<T> {
                public fun <R : T> retainBox(value: OwnerBoundBox<R>): OwnerBoundBox<R>
            }

            public interface NestedOwnerBoundIntersection<T> :
                NestedOwnerBoundLeft<T>, NestedOwnerBoundRight<T>

            public interface ReservedOwner<out T> {
                public fun accept(value: @UnsafeVariance T)
            }

            public interface ReservedOwner__KotlinExact<T>
            """,
            "clash on its declared CLR capability",
            "clash on its exact CLR capability",
            "and inherited member 'get_value'",
            "inherited members '<get-item>' and 'get_item'",
            "but are distinct Kotlin members",
            "requires an owner-relative generic adapter beyond direct method-parameter uses",
            "maps to a duplicate canonical, declared, or exact IL type",
        )
        assertPublicationFails(
            "Generic.Interface.ErasedCallableOverloads",
            """
            package sample

            public interface ErasedCallableOverloads<T> {
                public fun apply(callback: (T) -> String): Int
                public fun apply(callback: (String) -> T): Int
            }
            """,
            "members 'apply' and 'apply'",
            "clash on its declared CLR capability",
            "both map to 'apply(class [Kotlin.Runtime]'Kotlin.Function1')'",
        )
        assertPublicationFails(
            "Generic.Interface.ReturnOnlyPhysicalClash",
            """
            package sample

            public interface ReturnOnlyPhysicalClash<T> {
                public fun choose(value: String): T
                public fun choose(value: String?): Any?
            }
            """,
            "members 'choose' and 'choose'",
            "clash on its declared CLR capability",
            "both map to 'choose(string)'",
        )
        assertPublicationFails(
            "Generic.Interface.InheritedCallableOverloads",
            """
            package sample

            public interface CallableLeft<T> {
                public fun apply(callback: (T) -> String): Int
            }

            public interface CallableRight<T> {
                public fun apply(callback: (String) -> T): Int
            }

            public interface InheritedCallableOverloads<T> :
                CallableLeft<T>, CallableRight<T>
            """,
            "inherited members 'apply' and 'apply'",
            "clash on its declared CLR capability",
            "no selected derived intersection slot covers both Kotlin members",
        )
        assertPublicationFails(
            "Generic.Interface.UnsupportedAnyConstraint",
            """
            package sample

            public interface UnsupportedAnyConstraint<T : Any> {
                public fun read(): T
            }
            """,
            "constrains type parameter 'T' with kotlin.Any",
            "no CLR reference-type constraint metadata",
        )

        val inheritedProducerDirectory = File(tmpdir, "inherited-callable-overload-producer")
        val inheritedProducerSource = File(tmpdir, "inherited-callable-overload-producer.kt").apply {
            writeText(
                """
                package inheritedoverloads

                public interface CallableLeft<T> {
                    public fun apply(callback: (T) -> String): Int
                }

                public interface CallableRight<T> {
                    public fun apply(callback: (String) -> T): Int
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            inheritedProducerSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::moduleName.cliArgument, "Inherited.Callable.Producer",
            K2DotNetCompilerArguments::destination.cliArgument, inheritedProducerDirectory.path,
        )
        val inheritedProducerMetadata =
            inheritedProducerDirectory.resolve("Inherited.Callable.Producer.klib")
        assertTrue(inheritedProducerMetadata.isFile)

        val inheritedConsumerSource = File(tmpdir, "inherited-callable-overload-consumer.kt").apply {
            writeText(
                """
                package inheritedoverloads

                public interface LeftBranch<T> : CallableLeft<T>
                public interface RightBranch<T> : CallableRight<T>

                public interface TransitiveInheritedCallableOverloads<T> :
                    LeftBranch<T>, RightBranch<T>
                """.trimIndent()
            )
        }
        val inheritedConsumerDirectory = File(tmpdir, "inherited-callable-overload-consumer")
        val [inheritedOutput, inheritedExitCode] = AbstractCliTest.executeCompilerGrabOutput(
            K2DotNetCompiler(),
            listOf(
                inheritedConsumerSource.path,
                K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                K2DotNetCompilerArguments::classpath.cliArgument, inheritedProducerMetadata.path,
                K2DotNetCompilerArguments::moduleName.cliArgument, "Inherited.Callable.Consumer",
                K2DotNetCompilerArguments::destination.cliArgument, inheritedConsumerDirectory.path,
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, inheritedExitCode, inheritedOutput)
        assertTrue("inherited members 'apply' and 'apply'" in inheritedOutput) { inheritedOutput }
        assertTrue("clash on its declared CLR capability" in inheritedOutput) { inheritedOutput }
        assertTrue("no selected derived intersection slot covers both Kotlin members" in inheritedOutput) {
            inheritedOutput
        }
        assertFalse(inheritedConsumerDirectory.resolve("Inherited.Callable.Consumer.klib").exists())
        assertFalse(inheritedConsumerDirectory.resolve("Inherited.Callable.Consumer.dll").exists())
    }

    @Test
    fun testKotlinVisibilityIsPreservedInClrMetadata() {
        val source = File(tmpdir, "visibility.kt").apply {
            writeText(
                """
                package surface

                private const val PRIVATE_CONST: Int = 1
                internal const val INTERNAL_CONST: Int = 2
                public const val PUBLIC_CONST: Int = 3

                private fun privateTop(): Int = PRIVATE_CONST
                internal fun internalTop(): Int = INTERNAL_CONST
                public fun publicTop(): Int = PUBLIC_CONST

                private class PrivateTop
                internal class InternalTop
                public open class PublicTop {
                    private fun privateMember(): Int = 1
                    internal fun internalMember(): Int = 2
                    protected fun protectedMember(): Int = 3
                    public fun publicMember(): Int = 4
                }

                public sealed class SealedTop
                """.trimIndent()
            )
        }
        val outputFile = File(tmpdir, "visibility.il")
        compileInProcess(
            K2DotNetCompiler(),
            source.path,
            K2DotNetCompilerArguments::moduleName.cliArgument, "Visibility",
            K2DotNetCompilerArguments::destination.cliArgument, outputFile.path,
        )

        val il = outputFile.readText()
        assertTrue(".class private auto ansi sealed beforefieldinit 'surface.PrivateTop'" in il) { il }
        assertTrue(".class private auto ansi sealed beforefieldinit 'surface.InternalTop'" in il) { il }
        assertTrue(".class public auto ansi beforefieldinit 'surface.PublicTop'" in il) { il }
        assertTrue(".class public abstract auto ansi beforefieldinit 'surface.SealedTop'" in il) { il }
        assertTrue(".method famandassem hidebysig specialname rtspecialname instance void .ctor()" in il) { il }
        assertTrue(".method assembly hidebysig static int32 'privateTop'()" in il) { il }
        assertTrue(".method assembly hidebysig static int32 'internalTop'()" in il) { il }
        assertTrue(".method public hidebysig static int32 'publicTop'()" in il) { il }
        assertTrue(".method private hidebysig instance int32 'privateMember'()" in il) { il }
        assertTrue(".method assembly hidebysig instance int32 'internalMember'()" in il) { il }
        assertTrue(".method family hidebysig instance int32 'protectedMember'()" in il) { il }
        assertTrue(".method public hidebysig instance int32 'publicMember'()" in il) { il }
        assertTrue(".field private static literal int32 'PRIVATE_CONST'" in il) { il }
        assertTrue(".field assembly static literal int32 'INTERNAL_CONST'" in il) { il }
        assertTrue(".field public static literal int32 'PUBLIC_CONST'" in il) { il }
    }

    @Test
    fun testCSharpCannotConsumeNonPublicKotlinSurface() {
        val frameworkIlasm = DotNetIlAssembler.findFrameworkIlasm()
        val csharpCompiler = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(frameworkIlasm != null, ".NET Framework ILAsm is not available")
        requireOrAssumeToolchain(csharpCompiler != null, ".NET Framework C# compiler is not available")
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Portable-library ILAsm is not available")

        val source = File(tmpdir, "visibilityConsumerLibrary.kt").apply {
            writeText(
                """
                package surface

                private const val PRIVATE_CONST: Int = 1
                internal const val INTERNAL_CONST: Int = 2
                public const val PUBLIC_CONST: Int = 3

                private fun privateTop(): Int = PRIVATE_CONST
                internal fun internalTop(): Int = INTERNAL_CONST
                public fun publicTop(): Int = PUBLIC_CONST
                public fun publicDefault(value: Int = 4): Int = value

                private class PrivateTop
                internal class InternalTop

                public open class PublicTop {
                    private fun privateMember(): Int = 1
                    internal fun internalMember(): Int = 2
                    protected fun protectedMember(): Int = 3
                    public fun publicMember(): Int = 4
                }

                public sealed class SealedTop
                """.trimIndent()
            )
        }
        val ilFile = File(tmpdir, "Visibility.Consumer.Library.il")
        compileInProcess(
            K2DotNetCompiler(),
            source.path,
            K2DotNetCompilerArguments::moduleName.cliArgument, "Visibility.Consumer.Library",
            K2DotNetCompilerArguments::destination.cliArgument, ilFile.path,
        )
        val implementation = File(tmpdir, "Visibility.Consumer.Library.dll")
        assertTrue(
            DotNetIlAssembler.assembleLibrary(
                ilFile,
                implementation,
                DotNetTarget.NET48,
                MessageCollector.NONE,
            )
        )
        val runtimeBootstrap = File(tmpdir, "runtimeBootstrap.kt").apply { writeText("fun main() {}") }
        compileInProcess(
            K2DotNetCompiler(),
            runtimeBootstrap.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net48",
            K2DotNetCompilerArguments::moduleName.cliArgument, "RuntimeBootstrap",
            K2DotNetCompilerArguments::destination.cliArgument, File(tmpdir, "RuntimeBootstrap.exe").path,
        )
        val runtime = File(tmpdir, "Kotlin.Runtime.dll")
        assertTrue(runtime.isFile) { "Executable compilation did not install Kotlin.Runtime.dll" }

        val publicConsumer = File(tmpdir, "PublicConsumer.cs").apply {
            writeText(
                """
                public sealed class PublicConsumer : surface.PublicTop
                {
                    public int Read()
                    {
                        return surface.visibilityConsumerLibraryKt.publicTop()
                            + surface.visibilityConsumerLibraryKt.PUBLIC_CONST
                            + protectedMember()
                            + publicMember();
                    }
                }
                """.trimIndent()
            )
        }
        val publicResult = runCSharpCompiler(
            checkNotNull(csharpCompiler),
            publicConsumer,
            File(tmpdir, "PublicConsumer.dll"),
            implementation,
            runtime,
        )
        assertEquals(0, publicResult.exitCode, publicResult.output)

        val forbiddenConsumer = File(tmpdir, "ForbiddenConsumer.cs").apply {
            writeText(
                """
                public class IllegalSealedSubclass : surface.SealedTop {}

                public sealed class ForbiddenConsumer
                {
                    public object PrivateType() { return new surface.PrivateTop(); }
                    public object InternalType() { return new surface.InternalTop(); }
                    public int PrivateTop() { return surface.visibilityConsumerLibraryKt.privateTop(); }
                    public int InternalTop() { return surface.visibilityConsumerLibraryKt.internalTop(); }
                    public int PrivateConst() { return surface.visibilityConsumerLibraryKt.PRIVATE_CONST; }
                    public int InternalConst() { return surface.visibilityConsumerLibraryKt.INTERNAL_CONST; }
                    public int PrivateMember(surface.PublicTop value) { return value.privateMember(); }
                    public int InternalMember(surface.PublicTop value) { return value.internalMember(); }
                }
                """.trimIndent()
            )
        }
        val forbiddenResult = runCSharpCompiler(
            checkNotNull(csharpCompiler),
            forbiddenConsumer,
            File(tmpdir, "ForbiddenConsumer.dll"),
            implementation,
            runtime,
        )
        assertTrue(forbiddenResult.exitCode != 0) { forbiddenResult.output }
        for (name in listOf(
            "SealedTop",
            "PrivateTop",
            "InternalTop",
            "privateTop",
            "internalTop",
            "PRIVATE_CONST",
            "INTERNAL_CONST",
            "privateMember",
            "internalMember",
        )) {
            assertTrue(name in forbiddenResult.output) {
                "Expected C# accessibility diagnostic for '$name':\n${forbiddenResult.output}"
            }
        }

        val reflectionVerifier = File(tmpdir, "VisibilityReflectionVerifier.cs").apply {
            writeText(
                """
                using System;
                using System.Reflection;

                public static class VisibilityReflectionVerifier
                {
                    private const BindingFlags DeclaredInstance =
                        BindingFlags.DeclaredOnly | BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;
                    private const BindingFlags DeclaredStatic =
                        BindingFlags.DeclaredOnly | BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic;

                    private static void Require(bool condition, string message)
                    {
                        if (!condition) throw new Exception(message);
                    }

                    private static bool HasAttribute(MemberInfo member, string fullName)
                    {
                        foreach (CustomAttributeData attribute in member.GetCustomAttributesData())
                            if (attribute.AttributeType.FullName == fullName) return true;
                        return false;
                    }

                    public static int Main()
                    {
                        Assembly assembly = typeof(surface.PublicTop).Assembly;
                        Type facade = assembly.GetType("surface.visibilityConsumerLibraryKt", true);
                        Type privateTop = assembly.GetType("surface.PrivateTop", true);
                        Type internalTop = assembly.GetType("surface.InternalTop", true);
                        Type publicTop = assembly.GetType("surface.PublicTop", true);
                        Type sealedTop = assembly.GetType("surface.SealedTop", true);

                        Require(!privateTop.IsPublic, "private top-level type became CLR-public");
                        Require(!internalTop.IsPublic, "internal top-level type became CLR-public");
                        Require(publicTop.IsPublic, "public top-level type is not CLR-public");

                        Require(facade.GetMethod("privateTop", DeclaredStatic).IsAssembly,
                            "file-private top-level function must be facade-internal");
                        Require(facade.GetMethod("internalTop", DeclaredStatic).IsAssembly,
                            "internal top-level function must be assembly-visible");
                        Require(facade.GetMethod("publicTop", DeclaredStatic).IsPublic,
                            "public top-level function must be CLR-public");

                        Require(publicTop.GetMethod("privateMember", DeclaredInstance).IsPrivate,
                            "private member must be CLR-private");
                        Require(publicTop.GetMethod("internalMember", DeclaredInstance).IsAssembly,
                            "internal member must be assembly-visible");
                        Require(publicTop.GetMethod("protectedMember", DeclaredInstance).IsFamily,
                            "protected member must be family-visible");
                        Require(publicTop.GetMethod("publicMember", DeclaredInstance).IsPublic,
                            "public member must be CLR-public");

                        Require(facade.GetField("PRIVATE_CONST", DeclaredStatic).IsPrivate,
                            "private const must be CLR-private");
                        Require(facade.GetField("INTERNAL_CONST", DeclaredStatic).IsAssembly,
                            "internal const must be assembly-visible");
                        Require(facade.GetField("PUBLIC_CONST", DeclaredStatic).IsPublic,
                            "public const must be CLR-public");

                        ConstructorInfo[] sealedConstructors = sealedTop.GetConstructors(DeclaredInstance);
                        Require(sealedConstructors.Length == 1 && sealedConstructors[0].IsFamilyAndAssembly,
                            "sealed constructor must be famandassem");

                        MethodInfo defaultBridge = facade.GetMethod("publicDefault${'$'}default", DeclaredStatic);
                        Require(defaultBridge != null && defaultBridge.IsPublic,
                            "cross-module default bridge must be CLR-public");
                        Require(HasAttribute(defaultBridge, "Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute"),
                            "default bridge is missing the compiler-ABI marker");
                        Require(HasAttribute(defaultBridge, "System.ComponentModel.EditorBrowsableAttribute"),
                            "default bridge is missing EditorBrowsable(Never)");

                        Type syntheticMarker = Assembly.LoadFrom("Kotlin.Runtime.dll")
                            .GetType("Kotlin.Runtime.Internal.SyntheticConstructorMarker", true);
                        Require(syntheticMarker.IsPublic, "synthetic constructor marker must be CLR-public");
                        Require(HasAttribute(syntheticMarker, "Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute"),
                            "synthetic constructor marker is missing the compiler-ABI marker");
                        Require(HasAttribute(syntheticMarker, "System.ComponentModel.EditorBrowsableAttribute"),
                            "synthetic constructor marker is missing EditorBrowsable(Never)");
                        return 0;
                    }
                }
                """.trimIndent()
            )
        }
        val reflectionExecutable = File(tmpdir, "VisibilityReflectionVerifier.exe")
        val reflectionCompile = runCSharpCompiler(
            checkNotNull(csharpCompiler),
            reflectionVerifier,
            reflectionExecutable,
            implementation,
            runtime,
            target = "exe",
        )
        assertEquals(0, reflectionCompile.exitCode, reflectionCompile.output)
        val reflectionProcess = ProcessBuilder(reflectionExecutable.path)
            .directory(tmpdir)
            .redirectErrorStream(true)
            .start()
        val reflectionOutput = reflectionProcess.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, reflectionProcess.waitFor(), reflectionOutput)
    }

    @Test
    fun testCompilerAbiMetadataResolvesOnNet10() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val csharpCompiler = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(csharpCompiler != null, ".NET Framework C# compiler is not available")

        val directory = File(tmpdir, "net10-compiler-abi-reflection").apply { mkdirs() }
        val source = directory.resolve("published.kt").apply {
            writeText(
                """
                package profileabi

                @PublishedApi
                internal class PublishedBox

                fun main() {
                    println("OK")
                }
                """.trimIndent()
            )
        }
        val application = directory.resolve("ProfileAbi.dll")
        compileInProcess(
            K2DotNetCompiler(),
            source.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "ProfileAbi",
            K2DotNetCompilerArguments::destination.cliArgument, application.path,
        )
        val applicationIl = directory.resolve("ProfileAbi.il").readText()
        assertTrue(".assembly extern System.Runtime" in applicationIl) { applicationIl }
        assertTrue(
            ".custom instance void [System.Runtime]System.ComponentModel.EditorBrowsableAttribute" in applicationIl
        ) { applicationIl }

        val verifierSource = directory.resolve("Verifier.cs").apply {
            writeText(
                """
                using System;
                using System.Reflection;

                public static class Verifier
                {
                    private static bool HasAttribute(MemberInfo member, string fullName)
                    {
                        foreach (CustomAttributeData attribute in member.GetCustomAttributesData())
                            if (attribute.AttributeType.FullName == fullName)
                                return true;
                        return false;
                    }

                    public static int Main()
                    {
                        Type marker = Assembly.LoadFrom("Kotlin.Runtime.dll")
                            .GetType("Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute", true);
                        if (!HasAttribute(marker, "System.ComponentModel.EditorBrowsableAttribute"))
                            return 1;

                        Type published = Assembly.LoadFrom("ProfileAbi.dll")
                            .GetType("profileabi.PublishedBox", true);
                        if (!HasAttribute(published, "Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute"))
                            return 2;
                        if (!HasAttribute(published, "System.ComponentModel.EditorBrowsableAttribute"))
                            return 3;
                        return 0;
                    }
                }
                """.trimIndent()
            )
        }
        val verifier = directory.resolve("Verifier.exe")
        val compileResult = runCSharpCompiler(
            checkNotNull(csharpCompiler),
            verifierSource,
            verifier,
            target = "exe",
        )
        assertEquals(0, compileResult.exitCode, compileResult.output)
        directory.resolve("ProfileAbi.runtimeconfig.json")
            .copyTo(directory.resolve("Verifier.runtimeconfig.json"), overwrite = true)

        val process = ProcessBuilder(dotnetHost.path, "exec", verifier.path)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
    }

    @Test
    fun testPrimitiveArrayWrappersAcrossPortableLibraryBoundary() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val csharpCompiler = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(csharpCompiler != null, ".NET Framework C# compiler is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val libraryDirectory = File(tmpdir, "primitive-array-wrapper-library")
        val librarySource = File(tmpdir, "primitiveArrayLibrary.kt").apply {
            writeText(
                """
                package primitivearrays

                public fun measure(values: IntArray): Int = values[0] + values[1]

                public fun measure(values: Array<Int>): Int = values[0] + values[1] + 10

                public fun makeSpecialized(): IntArray = intArrayOf(1, 2)

                public fun makeGeneric(): Array<Int> = arrayOf(1, 2)

                public fun identity(values: IntArray): IntArray = values

                private var remembered: IntArray? = null

                public fun sameIdentity(first: IntArray, second: IntArray): Boolean = first === second

                public fun rememberIdentity(values: IntArray): Boolean {
                    remembered = values
                    return true
                }

                public fun isRememberedIdentity(values: IntArray): Boolean = remembered === values

                public fun makeAndRemember(): IntArray {
                    val values = intArrayOf(20, 22)
                    remembered = values
                    return values
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "PrimitiveArray.Library",
            K2DotNetCompilerArguments::dotNetExports.cliArgument,
            "primitivearrays.identity(kotlin.IntArray)=RoundTripSpecialized",
            K2DotNetCompilerArguments::dotNetExports.cliArgument,
            "primitivearrays.makeSpecialized=MakeSpecialized",
            K2DotNetCompilerArguments::dotNetExports.cliArgument,
            "primitivearrays.sameIdentity(kotlin.IntArray,kotlin.IntArray)=SameSpecialized",
            K2DotNetCompilerArguments::dotNetExports.cliArgument,
            "primitivearrays.rememberIdentity(kotlin.IntArray)=RememberSpecialized",
            K2DotNetCompilerArguments::dotNetExports.cliArgument,
            "primitivearrays.isRememberedIdentity(kotlin.IntArray)=IsRememberedSpecialized",
            K2DotNetCompilerArguments::dotNetExports.cliArgument,
            "primitivearrays.makeAndRemember=MakeAndRemember",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("PrimitiveArray.Library.klib")
        val libraryIl = libraryDirectory.resolve("PrimitiveArray.Library.il").readText()
        assertTrue(
            "'measure'(class [Kotlin.Runtime]'Kotlin.IntArray' 'values')" in libraryIl
        ) { libraryIl }
        assertTrue("'measure'(int32[] 'values')" in libraryIl) { libraryIl }
        assertTrue(
            "class [Kotlin.Runtime]'Kotlin.IntArray' 'makeSpecialized'()" in libraryIl
        ) { libraryIl }
        assertTrue("int32[] 'makeGeneric'()" in libraryIl) { libraryIl }
        assertTrue("int32[] 'RoundTripSpecialized'(int32[] 'values')" in libraryIl) { libraryIl }
        assertTrue("int32[] 'MakeSpecialized'()" in libraryIl) { libraryIl }
        assertTrue("bool 'SameSpecialized'(int32[] 'first', int32[] 'second')" in libraryIl) { libraryIl }
        assertTrue("bool 'RememberSpecialized'(int32[] 'values')" in libraryIl) { libraryIl }
        assertTrue("bool 'IsRememberedSpecialized'(int32[] 'values')" in libraryIl) { libraryIl }
        assertTrue("int32[] 'MakeAndRemember'()" in libraryIl) { libraryIl }

        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = libraryDirectory.resolve("consumer-${target.replace('.', '-')}").apply { mkdirs() }
            val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
                writeText(
                    """
                    package primitivearrayconsumer

                    import primitivearrays.*

                    fun main() {
                        val specialized = makeSpecialized()
                        val generic = makeGeneric()
                        if (measure(specialized) != 3) throw Error("specialized overload")
                        if (measure(generic) != 13) throw Error("generic primitive substitution overload")
                        val specializedIdentity: Any = specialized
                        val genericIdentity: Any = generic
                        if (specializedIdentity === genericIdentity) throw Error("array identities collapsed")
                        specialized[0] = 40
                        generic[0] = 41
                        if (measure(specialized) != 42 || measure(generic) != 53) {
                            throw Error("cross-module mutation")
                        }
                    }
                    """.trimIndent()
                )
            }
            val application = consumerDirectory.resolve(
                if (target == "net48") "PrimitiveArrayConsumer.exe" else "PrimitiveArrayConsumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "PrimitiveArrayConsumer",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )
            if (target == "net10.0") {
                runDotNet(
                    dotnetHost,
                    application,
                    consumerDirectory,
                    "Primitive-array Kotlin consumer failed for $target",
                )
            } else {
                val process = ProcessBuilder(application.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Primitive-array Kotlin consumer failed for $target:\n$output")
            }

            val verifierSource = consumerDirectory.resolve("PrimitiveArrayVerifier.cs").apply {
                writeText(
                    """
                    using System;
                    using System.Reflection;
                    using System.Threading;

                    public static class PrimitiveArrayVerifier
                    {
                        private static void Require(bool condition, string message)
                        {
                            if (!condition) throw new Exception(message);
                        }

                        private static bool HasAttribute(MemberInfo member, string fullName)
                        {
                            foreach (object attribute in member.GetCustomAttributes(false))
                            {
                                if (attribute.GetType().FullName == fullName) return true;
                            }
                            return false;
                        }

                        public static int Main()
                        {
                            Assembly library = Assembly.LoadFrom("PrimitiveArray.Library.dll");
                            Type facade = library.GetType("primitivearrays.primitiveArrayLibraryKt", true);
                            MethodInfo specializedMeasure = facade.GetMethod(
                                "measure", new Type[] { Type.GetType("Kotlin.IntArray, Kotlin.Runtime", true) });
                            MethodInfo genericMeasure = facade.GetMethod("measure", new Type[] { typeof(int[]) });
                            MethodInfo makeSpecialized = facade.GetMethod("makeSpecialized", Type.EmptyTypes);
                            MethodInfo makeGeneric = facade.GetMethod("makeGeneric", Type.EmptyTypes);
                            MethodInfo roundTripExport = facade.GetMethod(
                                "RoundTripSpecialized", new Type[] { typeof(int[]) });
                            MethodInfo makeSpecializedExport = facade.GetMethod(
                                "MakeSpecialized", Type.EmptyTypes);
                            MethodInfo sameExport = facade.GetMethod(
                                "SameSpecialized", new Type[] { typeof(int[]), typeof(int[]) });
                            MethodInfo rememberExport = facade.GetMethod(
                                "RememberSpecialized", new Type[] { typeof(int[]) });
                            MethodInfo isRememberedExport = facade.GetMethod(
                                "IsRememberedSpecialized", new Type[] { typeof(int[]) });
                            MethodInfo makeAndRememberExport = facade.GetMethod(
                                "MakeAndRemember", Type.EmptyTypes);
                            Require(specializedMeasure != null, "specialized overload is not wrapper-shaped");
                            Require(genericMeasure != null, "Array<Int> overload is not int[]-shaped");
                            Require(makeSpecialized.ReturnType.FullName == "Kotlin.IntArray",
                                "specialized result leaked its vector storage");
                            Require(makeGeneric.ReturnType == typeof(int[]),
                                "generic substitution did not retain the natural CLR vector");
                            Require(roundTripExport != null && roundTripExport.ReturnType == typeof(int[]),
                                "explicit specialized-array export is not int[]-shaped");
                            Require(makeSpecializedExport != null &&
                                    makeSpecializedExport.ReturnType == typeof(int[]),
                                "explicit specialized-array result export is not int[]-shaped");
                            Require(sameExport != null && sameExport.ReturnType == typeof(bool),
                                "two-parameter identity export is not bool/int[]-shaped");
                            Require(rememberExport != null && isRememberedExport != null,
                                "cross-call identity exports are missing");
                            Require(makeAndRememberExport != null &&
                                    makeAndRememberExport.ReturnType == typeof(int[]),
                                "stored specialized-array result export is not int[]-shaped");

                            Type wrapperType = makeSpecialized.ReturnType;
                            Require(wrapperType.IsSealed, "primitive-array wrapper must be sealed");
                            FieldInfo storageField = wrapperType.GetField(
                                "_storage", BindingFlags.Instance | BindingFlags.NonPublic);
                            Require(storageField != null && storageField.IsPrivate && storageField.FieldType == typeof(int[]),
                                "wrapper storage layout is not private int[]");
                            FieldInfo internTable = wrapperType.GetField(
                                "_internedByStorage", BindingFlags.Static | BindingFlags.NonPublic);
                            Require(internTable != null && internTable.IsPrivate && internTable.IsInitOnly &&
                                    internTable.FieldType.IsGenericType &&
                                    internTable.FieldType.GetGenericTypeDefinition().FullName ==
                                        "System.Runtime.CompilerServices.ConditionalWeakTable`2",
                                "interop identity association is not runtime-owned weak interning");
                            ConstructorInfo storageConstructor = wrapperType.GetConstructor(new Type[] { typeof(int[]) });
                            MethodInfo getStorage = wrapperType.GetMethod("GetStorage", BindingFlags.Public | BindingFlags.Instance);
                            MethodInfo wrapStorageOrNull = wrapperType.GetMethod(
                                "WrapStorageOrNull", BindingFlags.Public | BindingFlags.Static);
                            Require(storageConstructor != null && getStorage != null && wrapStorageOrNull != null,
                                "cross-assembly primitive-array compiler ABI is missing");
                            Require(HasAttribute(storageConstructor,
                                    "Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute"),
                                "storage constructor is not marked compiler ABI");

                            object specialized = makeSpecialized.Invoke(null, null);
                            int[] generic = (int[]) makeGeneric.Invoke(null, null);
                            Require(!Object.ReferenceEquals(specialized, generic),
                                "specialized and generic array identities collapsed");
                            int[] liveStorage = (int[]) getStorage.Invoke(specialized, null);
                            liveStorage[0] = 40;
                            Require((int) specializedMeasure.Invoke(null, new object[] { specialized }) == 42,
                                "wrapper-to-storage mutation alias was lost");
                            generic[0] = 41;
                            Require((int) genericMeasure.Invoke(null, new object[] { generic }) == 53,
                                "natural generic vector mutation failed");

                            int[] exportedInput = new int[] { 20, 22 };
                            object exportedRoundTrip = roundTripExport.Invoke(
                                null, new object[] { exportedInput });
                            Require(Object.ReferenceEquals(exportedInput, exportedRoundTrip),
                                "explicit export copied or replaced primitive-array storage");
                            Require((bool) sameExport.Invoke(
                                    null, new object[] { exportedInput, exportedInput }),
                                "one CLR vector did not project to one Kotlin wrapper within a call");
                            Require(!(bool) sameExport.Invoke(
                                    null, new object[] { exportedInput, new int[] { 20, 22 } }),
                                "distinct CLR vectors collapsed to one Kotlin wrapper");
                            Require((bool) rememberExport.Invoke(
                                    null, new object[] { exportedInput }),
                                "identity remember export failed");
                            Require((bool) isRememberedExport.Invoke(
                                    null, new object[] { exportedInput }),
                                "one CLR vector did not retain Kotlin wrapper identity across calls");
                            int[] exportedResult = (int[]) makeSpecializedExport.Invoke(null, null);
                            Require(exportedResult.Length == 2 && exportedResult[0] == 1 && exportedResult[1] == 2,
                                "explicit primitive-array result export lost contents");
                            int[] rememberedResult = (int[]) makeAndRememberExport.Invoke(null, null);
                            Require((bool) isRememberedExport.Invoke(
                                    null, new object[] { rememberedResult }),
                                "outbound Kotlin wrapper was not recovered when its vector returned inbound");

                            int[] inboundStorage = new int[] { 20, 22 };
                            object inboundWrapper = storageConstructor.Invoke(new object[] { inboundStorage });
                            Require(Object.ReferenceEquals(inboundStorage, getStorage.Invoke(inboundWrapper, null)),
                                "compiler ABI wrapper construction copied storage silently");
                            Require((int) specializedMeasure.Invoke(null, new object[] { inboundWrapper }) == 42,
                                "inbound wrapper did not alias its supplied vector");

                            int[] concurrentStorage = new int[] { 42 };
                            object[] concurrentWrappers = new object[8];
                            Exception[] concurrentErrors = new Exception[8];
                            Thread[] threads = new Thread[8];
                            for (int index = 0; index < threads.Length; index++)
                            {
                                int slot = index;
                                threads[index] = new Thread(delegate()
                                {
                                    try
                                    {
                                        concurrentWrappers[slot] = wrapStorageOrNull.Invoke(
                                            null, new object[] { concurrentStorage });
                                    }
                                    catch (Exception error)
                                    {
                                        concurrentErrors[slot] = error;
                                    }
                                });
                                threads[index].Start();
                            }
                            for (int index = 0; index < threads.Length; index++) threads[index].Join();
                            for (int index = 0; index < threads.Length; index++)
                            {
                                Require(concurrentErrors[index] == null,
                                    "concurrent vector adaptation failed");
                                Require(Object.ReferenceEquals(concurrentWrappers[0], concurrentWrappers[index]),
                                    "concurrent first conversion created multiple Kotlin wrappers");
                            }
                            return 0;
                        }
                    }
                    """.trimIndent()
                )
            }
            val verifier = consumerDirectory.resolve("PrimitiveArrayVerifier.exe")
            val compileResult = runCSharpCompiler(
                checkNotNull(csharpCompiler),
                verifierSource,
                verifier,
                target = "exe",
            )
            assertEquals(0, compileResult.exitCode, compileResult.output)
            val verifierProcess = if (target == "net10.0") {
                consumerDirectory.resolve("PrimitiveArrayConsumer.runtimeconfig.json")
                    .copyTo(consumerDirectory.resolve("PrimitiveArrayVerifier.runtimeconfig.json"), overwrite = true)
                ProcessBuilder(dotnetHost.path, "exec", verifier.path)
            } else {
                ProcessBuilder(verifier.path)
            }.directory(consumerDirectory).redirectErrorStream(true).start()
            val verifierOutput = verifierProcess.inputStream.bufferedReader().use { it.readText() }
            assertEquals(
                0,
                verifierProcess.waitFor(),
                "Primitive-array C# verifier failed for $target:\n$verifierOutput",
            )
        }
    }

    @Test
    fun testOpenNullableTypeParametersAcrossPortableLibraryBoundary() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val dotnetHost = modernDotNetHostOrSkip()
        val modernCSharp = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(
            modernCSharp != null,
            "Modern Roslyn and the net10 reference pack are not available",
        )
        val libraryDirectory = File(tmpdir, "open-nullable-library")
        val librarySource = File(tmpdir, "openNullableLibrary.kt").apply {
            writeText(
                """
                package nullableabi

                public class NullableHolder<T>(public var value: T?)

                public interface NullableSource<T> {
                    public fun nullableValue(): T?
                }

                public class StoredNullableSource<T>(private val stored: T?) : NullableSource<T> {
                    override fun nullableValue(): T? = stored
                }

                public fun <T> echoNullable(value: T?): T? = value

                public fun <T> requireNullable(value: T?): T = value!!

                public fun <T> readNullable(source: NullableSource<T>): T? = source.nullableValue()

                public fun <T : String> echoStringBoundNullable(value: T?): T? = value

                public fun <T : String> requireStringBoundNullable(value: T?): T = value!!
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "OpenNullable.Library",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("OpenNullable.Library.klib")
        val libraryIl = libraryDirectory.resolve("OpenNullable.Library.il").readText()
        assertTrue(".field private object 'value'" in libraryIl) { libraryIl }
        assertTrue("static object 'echoNullable'<'T'>(object 'value')" in libraryIl) { libraryIl }
        assertTrue("static !!0 'requireNullable'<'T'>(object 'value')" in libraryIl) { libraryIl }
        assertTrue("static object 'echoStringBoundNullable'<'T'>(object 'value')" in libraryIl) { libraryIl }
        assertTrue("static string 'requireStringBoundNullable'<'T'>(object 'value')" in libraryIl) { libraryIl }
        assertTrue("unbox.any !!0" in libraryIl) { libraryIl }
        assertTrue("castclass string" in libraryIl) { libraryIl }
        assertTrue(".class interface public abstract auto ansi 'nullableabi.NullableSource`1'" in libraryIl) { libraryIl }
        assertTrue("instance object 'nullableValue'()" in libraryIl) { libraryIl }
        assertTrue(
            ".override method instance object class 'nullableabi.NullableSource`1'<!0>::'nullableValue'()" in libraryIl
        ) { libraryIl }

        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = libraryDirectory.resolve("consumer-${target.replace('.', '-')}").apply { mkdirs() }
            val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
                writeText(
                    """
                    package nullableconsumer

                    import nullableabi.*

                    fun main() {
                        val numbers = NullableHolder<Int>(null)
                        if (numbers.value != null) throw Error("primitive null field")
                        numbers.value = 41
                        if (numbers.value != 41) throw Error("primitive field recovery")
                        if (echoNullable<Int>(null) != null) throw Error("primitive null call")
                        if (echoNullable(42) != 42) throw Error("primitive call recovery")
                        if (requireNullable(43) != 43) throw Error("primitive non-null recovery")
                        val numberSource: NullableSource<Int> = StoredNullableSource(44)
                        if (numberSource.nullableValue() != 44) throw Error("primitive interface recovery")
                        if (readNullable(numberSource) != 44) throw Error("primitive generic interface call")

                        val strings = NullableHolder<String>(null)
                        strings.value = "reference"
                        if (strings.value != "reference") throw Error("reference field recovery")
                        if (echoNullable<String>(null) != null) throw Error("reference null call")
                        if (requireNullable("ok") != "ok") throw Error("reference non-null recovery")
                        val stringSource: NullableSource<String> = StoredNullableSource(null)
                        if (stringSource.nullableValue() != null) throw Error("reference interface recovery")
                        if (readNullable(stringSource) != null) throw Error("reference generic interface call")

                        if (echoStringBoundNullable<String>(null) != null) {
                            throw Error("string-bound null call")
                        }
                        if (echoStringBoundNullable("bounded-call") != "bounded-call") {
                            throw Error("string-bound call recovery")
                        }
                        if (requireStringBoundNullable("bounded-required") != "bounded-required") {
                            throw Error("string-bound non-null recovery")
                        }
                    }
                    """.trimIndent()
                )
            }
            val application = consumerDirectory.resolve(
                if (target == "net48") "OpenNullableConsumer.exe" else "OpenNullableConsumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "OpenNullableConsumer",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )
            if (target == "net10.0") {
                runDotNet(
                    dotnetHost,
                    application,
                    consumerDirectory,
                    "Open-nullable Kotlin consumer failed for $target",
                )
            } else {
                val process = ProcessBuilder(application.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Open-nullable Kotlin consumer failed for $target:\n$output")
            }

            if (target == "net10.0") {
                val csharpSource = consumerDirectory.resolve("consumer.cs").apply {
                    writeText(
                        """
                        public static class Program
                        {
                            public static int Main()
                            {
                                var holder = new nullableabi.NullableHolder<int>(41);
                                if ((int) holder.value != 41) return 1;
                                holder.value = null;
                                if (holder.value != null) return 2;

                                nullableabi.NullableSource<int> source =
                                    new nullableabi.StoredNullableSource<int>(42);
                                if ((int) source.nullableValue() != 42) return 3;
                                if ((int) nullableabi.openNullableLibraryKt.readNullable<int>(source) != 42) return 4;

                                nullableabi.NullableSource<string> empty =
                                    new nullableabi.StoredNullableSource<string>(null);
                                if (empty.nullableValue() != null) return 5;
                                if (nullableabi.openNullableLibraryKt.echoNullable<string>(null) != null) return 6;
                                if (nullableabi.openNullableLibraryKt.requireNullable<int>(43) != 43) return 7;

                                if (nullableabi.openNullableLibraryKt.echoStringBoundNullable<string>(null) != null)
                                    return 8;
                                if ((string) nullableabi.openNullableLibraryKt
                                    .echoStringBoundNullable<string>("bounded-call") != "bounded-call") return 9;
                                if (nullableabi.openNullableLibraryKt
                                    .requireStringBoundNullable<string>("bounded-required") != "bounded-required")
                                    return 10;
                                return 0;
                            }
                        }
                        """.trimIndent()
                    )
                }
                val csharpApplication = consumerDirectory.resolve("OpenNullableCSharpConsumer.dll")
                val csharpCompile = runModernCSharpCompiler(
                    checkNotNull(modernCSharp),
                    csharpSource,
                    csharpApplication,
                    libraryDirectory.resolve("OpenNullable.Library.dll"),
                    consumerDirectory.resolve("Kotlin.Runtime.dll"),
                    target = "exe",
                )
                assertEquals(0, csharpCompile.exitCode, csharpCompile.output)
                consumerDirectory.resolve("OpenNullableConsumer.runtimeconfig.json").copyTo(
                    consumerDirectory.resolve("OpenNullableCSharpConsumer.runtimeconfig.json"),
                    overwrite = true,
                )
                runDotNet(
                    dotnetHost,
                    csharpApplication,
                    consumerDirectory,
                    "Open-nullable C# consumer failed for $target",
                )
            }
        }
    }

    @Test
    fun testCovariantReturnsAcrossPortableLibraryBoundary() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val frameworkCSharp = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(frameworkCSharp != null, ".NET Framework C# compiler is not available")
        val frameworkNetStandardFacade = findFrameworkNetStandardFacade()
        requireOrAssumeToolchain(
            frameworkNetStandardFacade != null,
            ".NET Framework netstandard 2.0 facade is not available",
        )
        val modernCSharp = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(
            modernCSharp != null,
            "Modern Roslyn and the net10 reference pack are not available",
        )
        val dotnetHost = modernDotNetHostOrSkip()
        val libraryDirectory = File(tmpdir, "covariant-return-library")
        val librarySource = File(tmpdir, "covariantReturnLibrary.kt").apply {
            writeText(
                """
                package covarianceabi

                public open class Animal(public val tag: String)

                public open class Cat(tag: String) : Animal(tag)

                public open class Source {
                    public open fun make(): Animal = Animal("source-method")
                    public open val item: Animal get() = Animal("source-property")
                    public open fun <T> generic(value: T): Animal = Animal("source-generic")
                }

                public interface Maker {
                    public fun make(): Animal
                }

                public interface HasItem {
                    public val item: Animal
                }

                public interface DefaultMaker {
                    public fun defaultMake(): Animal = Animal("portable-default")
                }

                public interface ValueSource<out T> {
                    public fun value(): T
                }

                public class CatValueSource : ValueSource<Cat> {
                    override fun value(): Cat = Cat("variant-value")
                }

                public open class VariantReturnBase {
                    public open fun variant(): ValueSource<Animal> = CatValueSource()
                }

                public open class Factory {
                    public open fun make(): Cat = Cat("factory-method")
                }

                public open class ItemFactory {
                    public open val item: Cat get() = Cat("factory-property")
                }

                public abstract class AbstractCatSource : Source() {
                    public abstract override fun make(): Cat
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Covariance.Library",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("Covariance.Library.klib")
        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = libraryDirectory.resolve("consumer-${target.replace('.', '-')}").apply { mkdirs() }
            val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
                writeText(
                    """
                    package covarianceconsumer

                    import covarianceabi.*

                    public class Derived : Source() {
                        override fun make(): Cat = Cat("derived-method")
                        override val item: Cat get() = Cat("derived-property")
                        override fun <T> generic(value: T): Cat = Cat("derived-generic")
                    }

                    public class Combo : Factory(), Maker

                    public class ItemCombo : ItemFactory(), HasItem

                    public class VariantReturnDerived : VariantReturnBase() {
                        override fun variant(): ValueSource<Cat> = CatValueSource()
                    }

                    public interface RefinedDefaultMaker : DefaultMaker {
                        override fun defaultMake(): Cat = Cat("refined-default")
                    }

                    public class DefaultMakerImplementation : RefinedDefaultMaker

                    public class ConcreteAbstractSource : AbstractCatSource() {
                        override fun make(): Cat = Cat("abstract-method")
                    }

                    public open class Middle : Source() {
                        override fun make(): Cat = Cat("middle-method")
                    }

                    public class Siamese(tag: String) : Cat(tag)

                    public class Leaf : Middle() {
                        override fun make(): Siamese = Siamese("leaf-method")
                    }

                    fun main() {
                        val exact = Derived()
                        val exactMethod: Cat = exact.make()
                        val exactProperty: Cat = exact.item
                        val exactGeneric: Cat = exact.generic(42)
                        if (exactMethod.tag != "derived-method") throw Error("exact method")
                        if (exactProperty.tag != "derived-property") throw Error("exact property")
                        if (exactGeneric.tag != "derived-generic") throw Error("exact generic")

                        val base: Source = exact
                        if (base.make().tag != "derived-method") throw Error("base method")
                        if (base.item.tag != "derived-property") throw Error("base property")
                        if (base.generic("value").tag != "derived-generic") throw Error("base generic")

                        val maker: Maker = Combo()
                        if (maker.make().tag != "factory-method") throw Error("inherited interface method")
                        val hasItem: HasItem = ItemCombo()
                        if (hasItem.item.tag != "factory-property") throw Error("inherited interface property")

                        val variant: VariantReturnBase = VariantReturnDerived()
                        if (variant.variant().value().tag != "variant-value") {
                            throw Error("canonical variant return")
                        }

                        val defaultMaker: DefaultMaker = DefaultMakerImplementation()
                        if (defaultMaker.defaultMake().tag != "refined-default") {
                            throw Error("covariant interface default")
                        }

                        val abstractBase: Source = ConcreteAbstractSource()
                        if (abstractBase.make().tag != "abstract-method") throw Error("abstract refinement")

                        val leaf = Leaf()
                        val leafAsRoot: Source = leaf
                        val leafAsMiddle: Middle = leaf
                        if (leafAsRoot.make().tag != "leaf-method") throw Error("root leaf dispatch")
                        if (leafAsMiddle.make().tag != "leaf-method") throw Error("middle leaf dispatch")
                    }
                    """.trimIndent()
                )
            }
            val application = consumerDirectory.resolve(
                if (target == "net48") "Covariance.Consumer.exe" else "Covariance.Consumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "Covariance.Consumer",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )

            val consumerIl = consumerDirectory.resolve("Covariance.Consumer.il").readText()
            val bridgeBodies = Regex(
                "(?s)\\.method private[^\\n]*'<CovariantReturnBridge-[^']+'[^\\n]*\\n  \\{(.*?)\\n  \\}"
            ).findAll(consumerIl).map { match -> match.groupValues[1] }.toList()
            assertEquals(8, bridgeBodies.size, consumerIl)
            bridgeBodies.forEach { body ->
                assertTrue(".override method" in body) { body }
                assertTrue("callvirt instance" in body) { body }
                assertTrue("newobj" !in body) { "Covariant-return bridge copied a constructor body:\n$body" }
            }
            assertTrue("class [Covariance.Library]'covarianceabi.Animal'" in consumerIl) { consumerIl }
            assertTrue("class [Covariance.Library]'covarianceabi.Cat'" in consumerIl) { consumerIl }
            assertTrue("[Covariance.Library]'covarianceabi.Source'::'make'()" in consumerIl) { consumerIl }
            assertTrue("[Covariance.Library]'covarianceabi.Maker'::'make'()" in consumerIl) { consumerIl }
            assertTrue("<CovariantReturnBridge-covarianceabi.VariantReturnBase-variant-" !in consumerIl) {
                "A canonical split-interface return acquired an unnecessary ordinary bridge:\n$consumerIl"
            }

            if (target == "net10.0") {
                runDotNet(
                    dotnetHost,
                    application,
                    consumerDirectory,
                    "Covariant-return Kotlin consumer failed for $target",
                )
            } else {
                val process = ProcessBuilder(application.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Covariant-return Kotlin consumer failed for $target:\n$output")
            }

            val csharpSource = consumerDirectory.resolve("consumer.cs").apply {
                val foreignDefaultClass = if (target == "net10.0") {
                    """
                    public sealed class ForeignDefaultMaker : covarianceconsumer.RefinedDefaultMaker
                    {
                    }
                    """.trimIndent()
                } else {
                    ""
                }
                val foreignDefaultCall = if (target == "net10.0") {
                    """
                    covarianceabi.DefaultMaker foreignDefault = new ForeignDefaultMaker();
                    Require(foreignDefault.defaultMake().tag == "refined-default", "foreign DIM");
                    """.trimIndent()
                } else {
                    ""
                }
                writeText(
                    """
                    using System;
                    using System.Linq;
                    using System.Reflection;

                    $foreignDefaultClass

                    public static class Program
                    {
                        private static void Require(bool condition, string message)
                        {
                            if (!condition) throw new Exception(message);
                        }

                        public static int Main()
                        {
                            var exact = new covarianceconsumer.Derived();
                            covarianceabi.Cat exactMethod = exact.make();
                            covarianceabi.Cat exactProperty = exact.item;
                            covarianceabi.Cat exactGeneric = exact.generic<int>(42);
                            Require(exactMethod.tag == "derived-method", "exact method");
                            Require(exactProperty.tag == "derived-property", "exact property");
                            Require(exactGeneric.tag == "derived-generic", "exact generic");

                            covarianceabi.Source baseView = exact;
                            Require(baseView.make().tag == "derived-method", "base method");
                            Require(baseView.item.tag == "derived-property", "base property");
                            Require(baseView.generic<string>("value").tag == "derived-generic", "base generic");

                            covarianceabi.Maker maker = new covarianceconsumer.Combo();
                            Require(maker.make().tag == "factory-method", "interface method");
                            covarianceabi.HasItem hasItem = new covarianceconsumer.ItemCombo();
                            Require(hasItem.item.tag == "factory-property", "interface property");
                            covarianceabi.DefaultMaker defaultMaker =
                                new covarianceconsumer.DefaultMakerImplementation();
                            Require(defaultMaker.defaultMake().tag == "refined-default", "Kotlin default");
                            $foreignDefaultCall

                            covarianceabi.Source abstractView = new covarianceconsumer.ConcreteAbstractSource();
                            Require(abstractView.make().tag == "abstract-method", "abstract refinement");

                            var leaf = new covarianceconsumer.Leaf();
                            covarianceabi.Source rootView = leaf;
                            covarianceconsumer.Middle middleView = leaf;
                            Require(rootView.make().tag == "leaf-method", "root leaf dispatch");
                            Require(middleView.make().tag == "leaf-method", "middle leaf dispatch");

                            MethodInfo[] declared = typeof(covarianceconsumer.Derived).GetMethods(
                                BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic |
                                BindingFlags.DeclaredOnly);
                            MethodInfo precise = declared.Single(method =>
                                method.Name == "make" && method.IsPublic &&
                                method.ReturnType == typeof(covarianceabi.Cat));
                            Require(precise != null, "precise public C# method missing");
                            MethodInfo[] bridges = declared.Where(method =>
                                method.Name.StartsWith("<CovariantReturnBridge-", StringComparison.Ordinal)).ToArray();
                            Require(bridges.Length >= 3, "compiler bridges missing");
                            Require(bridges.All(method => method.IsPrivate), "compiler bridge leaked as public API");
                            return 0;
                        }
                    }
                    """.trimIndent()
                )
            }
            val csharpApplication = consumerDirectory.resolve(
                if (target == "net48") "Covariance.CSharpConsumer.exe" else "Covariance.CSharpConsumer.dll"
            )
            val csharpCompile = if (target == "net48") {
                runCSharpCompiler(
                    checkNotNull(frameworkCSharp),
                    csharpSource,
                    csharpApplication,
                    application,
                    libraryDirectory.resolve("Covariance.Library.dll"),
                    consumerDirectory.resolve("Kotlin.Runtime.dll"),
                    checkNotNull(frameworkNetStandardFacade),
                    target = "exe",
                )
            } else {
                runModernCSharpCompiler(
                    checkNotNull(modernCSharp),
                    csharpSource,
                    csharpApplication,
                    application,
                    libraryDirectory.resolve("Covariance.Library.dll"),
                    consumerDirectory.resolve("Kotlin.Runtime.dll"),
                    target = "exe",
                )
            }
            assertEquals(0, csharpCompile.exitCode, csharpCompile.output)
            if (target == "net10.0") {
                consumerDirectory.resolve("Covariance.Consumer.runtimeconfig.json").copyTo(
                    consumerDirectory.resolve("Covariance.CSharpConsumer.runtimeconfig.json"),
                    overwrite = true,
                )
                runDotNet(
                    dotnetHost,
                    csharpApplication,
                    consumerDirectory,
                    "Covariant-return C# consumer failed for $target",
                )
            } else {
                val process = ProcessBuilder(csharpApplication.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Covariant-return C# consumer failed for $target:\n$output")
            }
        }

        val refinedDirectory = libraryDirectory.resolve("refined-net10-library").apply { mkdirs() }
        val refinedSource = refinedDirectory.resolve("refined.kt").apply {
            writeText(
                """
                package covariancerefined

                import covarianceabi.*

                public interface RefinedDefaultMaker : DefaultMaker {
                    override fun defaultMake(): Cat = Cat("external-refined-default")
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            refinedSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Covariance.Refined",
            K2DotNetCompilerArguments::destination.cliArgument, refinedDirectory.path,
        )
        val refinedMetadata = refinedDirectory.resolve("Covariance.Refined.klib")
        val refinedBridgeRecords = DotNetLibraryAbiCodec.decode(refinedMetadata.readKlibManifest()).values
            .filterIsInstance<DotNetPhysicalDeclaration.CovariantReturnBridge>()
        assertEquals(1, refinedBridgeRecords.size, refinedBridgeRecords.joinToString("\n"))
        assertEquals(
            listOf("covariancerefined.RefinedDefaultMaker"),
            refinedBridgeRecords.single().ownerPath,
        )

        val downstreamDirectory = refinedDirectory.resolve("downstream").apply { mkdirs() }
        val downstreamSource = downstreamDirectory.resolve("downstream.kt").apply {
            writeText(
                """
                package covariancedownstream

                import covarianceabi.DefaultMaker
                import covariancerefined.RefinedDefaultMaker

                public class KotlinDefaultMaker : RefinedDefaultMaker

                fun main() {
                    val value: DefaultMaker = KotlinDefaultMaker()
                    if (value.defaultMake().tag != "external-refined-default") {
                        throw Error("external covariant DIM")
                    }
                }
                """.trimIndent()
            )
        }
        val downstreamApplication = downstreamDirectory.resolve("Covariance.Downstream.dll")
        val downstreamClasspath = listOf(refinedMetadata, metadataLibrary)
            .joinToString(File.pathSeparator) { it.path }
        compileInProcess(
            K2DotNetCompiler(),
            downstreamSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument, downstreamClasspath,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Covariance.Downstream",
            K2DotNetCompilerArguments::destination.cliArgument, downstreamApplication.path,
        )
        val downstreamIl = downstreamDirectory.resolve("Covariance.Downstream.il").readText()
        val downstreamClass = Regex(
            "(?s)\\.class public[^\\n]*'covariancedownstream.KotlinDefaultMaker'.*?^}",
            setOf(RegexOption.MULTILINE),
        ).find(downstreamIl)?.value ?: error("KotlinDefaultMaker class missing:\n$downstreamIl")
        assertTrue("<CovariantReturnBridge-" !in downstreamClass) {
            "A producer-recorded interface MethodImpl must suppress a downstream class bridge:\n$downstreamClass"
        }
        runDotNet(
            dotnetHost,
            downstreamApplication,
            downstreamDirectory,
            "Downstream Kotlin consumer of an external covariant DIM failed",
        )

        val downstreamCSharpSource = downstreamDirectory.resolve("downstream.cs").apply {
            writeText(
                """
                using System;

                public sealed class ForeignDefaultMaker : covariancerefined.RefinedDefaultMaker
                {
                }

                public static class Program
                {
                    public static int Main()
                    {
                        covarianceabi.DefaultMaker value = new ForeignDefaultMaker();
                        if (value.defaultMake().tag != "external-refined-default")
                            throw new Exception("external foreign covariant DIM");
                        return 0;
                    }
                }
                """.trimIndent()
            )
        }
        val downstreamCSharpApplication = downstreamDirectory.resolve("Covariance.Downstream.CSharp.dll")
        val downstreamCSharpCompile = runModernCSharpCompiler(
            checkNotNull(modernCSharp),
            downstreamCSharpSource,
            downstreamCSharpApplication,
            refinedDirectory.resolve("Covariance.Refined.dll"),
            libraryDirectory.resolve("Covariance.Library.dll"),
            downstreamDirectory.resolve("Kotlin.Runtime.dll"),
            target = "exe",
        )
        assertEquals(0, downstreamCSharpCompile.exitCode, downstreamCSharpCompile.output)
        downstreamDirectory.resolve("Covariance.Downstream.runtimeconfig.json").copyTo(
            downstreamDirectory.resolve("Covariance.Downstream.CSharp.runtimeconfig.json"),
            overwrite = true,
        )
        runDotNet(
            dotnetHost,
            downstreamCSharpApplication,
            downstreamDirectory,
            "Downstream C# consumer of an external covariant DIM failed",
        )
    }

    @Test
    fun testKotlinExceptionInheritanceAcrossPortableLibraryBoundary() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val dotnetHost = modernDotNetHostOrSkip()
        val libraryDirectory = File(tmpdir, "portable-exception-library")
        val librarySource = File(tmpdir, "portable-exception-library.kt").apply {
            writeText(
                """
                package crossfailure

                public open class LibraryRuntimeFailure(message: String) : RuntimeException(message)

                public class LibraryRuntimeChild(message: String) : LibraryRuntimeFailure(message)

                public class LibraryFatalFailure(message: String) : Error(message)

                public fun libraryRuntimeFailure(): Throwable = LibraryRuntimeChild("library-runtime")

                public fun libraryFatalFailure(): Throwable = LibraryFatalFailure("library-fatal")

                public fun classifySupplied(value: Throwable): Int = try {
                    throw value
                } catch (failure: LibraryRuntimeFailure) {
                    2
                } catch (failure: RuntimeException) {
                    3
                } catch (failure: Exception) {
                    1
                } catch (failure: Error) {
                    4
                } catch (failure: Throwable) {
                    0
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Exception.Library",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("Exception.Library.klib")
        val libraryIl = libraryDirectory.resolve("Exception.Library.il").readText()
        assertTrue(
            Regex(
                "'crossfailure\\.LibraryRuntimeFailure'\\s+extends " +
                        "\\[Kotlin\\.Runtime]'Kotlin\\.RuntimeException'"
            ).containsMatchIn(libraryIl)
        ) { libraryIl }
        assertTrue(
            Regex(
                "'crossfailure\\.LibraryRuntimeChild'\\s+extends " +
                        "'crossfailure\\.LibraryRuntimeFailure'"
            ).containsMatchIn(libraryIl)
        ) { libraryIl }
        assertTrue(
            Regex(
                "'crossfailure\\.LibraryFatalFailure'\\s+extends \\[Kotlin\\.Runtime]'Kotlin\\.Error'"
            ).containsMatchIn(libraryIl)
        ) { libraryIl }

        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = libraryDirectory.resolve("consumer-${target.replace('.', '-')}").apply { mkdirs() }
            val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
                writeText(
                    """
                    package exceptionconsumer

                    import crossfailure.*

                    private class ConsumerRuntimeChild(message: String) : LibraryRuntimeFailure(message)

                    private fun caughtAsLibraryType(value: Throwable): Boolean = try {
                        throw value
                    } catch (failure: LibraryRuntimeFailure) {
                        failure === value
                    } catch (failure: Throwable) {
                        false
                    }

                    private fun caughtAsRuntime(value: Throwable): Boolean = try {
                        throw value
                    } catch (failure: RuntimeException) {
                        failure === value
                    } catch (failure: Throwable) {
                        false
                    }

                    fun main() {
                        val libraryRuntime = libraryRuntimeFailure()
                        if (libraryRuntime !is LibraryRuntimeChild ||
                            libraryRuntime !is LibraryRuntimeFailure ||
                            libraryRuntime !is RuntimeException ||
                            libraryRuntime !is Exception ||
                            libraryRuntime is Error
                        ) {
                            throw Error("portable library runtime classification")
                        }
                        if (!caughtAsLibraryType(libraryRuntime) || !caughtAsRuntime(libraryRuntime)) {
                            throw Error("portable library runtime catch/identity")
                        }

                        val consumerRuntime: Throwable = ConsumerRuntimeChild("consumer-runtime")
                        if (consumerRuntime !is LibraryRuntimeFailure ||
                            consumerRuntime !is RuntimeException ||
                            !caughtAsLibraryType(consumerRuntime) ||
                            !caughtAsRuntime(consumerRuntime) ||
                            classifySupplied(consumerRuntime) != 2
                        ) {
                            throw Error("consumer subclass of portable exception")
                        }

                        if (classifySupplied(IllegalStateException("mapped-runtime")) != 3) {
                            throw Error("portable library mapped-runtime classification")
                        }
                        if (classifySupplied(Exception("plain")) != 1) {
                            throw Error("portable library plain-exception classification")
                        }
                        if (classifySupplied(Error("application-fatal")) != 4) {
                            throw Error("portable library application-error classification")
                        }

                        val libraryFatal = libraryFatalFailure()
                        if (libraryFatal !is LibraryFatalFailure ||
                            libraryFatal !is Error ||
                            libraryFatal is Exception ||
                            caughtAsRuntime(libraryFatal)
                        ) {
                            throw Error("portable library error classification")
                        }
                    }
                    """.trimIndent()
                )
            }
            val application = consumerDirectory.resolve(
                if (target == "net48") "ExceptionConsumer.exe" else "ExceptionConsumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "ExceptionConsumer",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )
            assertTrue(consumerDirectory.resolve("Exception.Library.dll").isFile) {
                "The portable exception implementation must be packaged beside its $target consumer"
            }
            val consumerIl = consumerDirectory.resolve("ExceptionConsumer.il").readText()
            assertTrue("[Exception.Library]'crossfailure.LibraryRuntimeFailure'" in consumerIl) { consumerIl }
            assertTrue("filter" in consumerIl) { consumerIl }

            if (target == "net10.0") {
                runDotNet(
                    dotnetHost,
                    application,
                    consumerDirectory,
                    "Portable exception consumer failed for $target",
                )
            } else {
                val process = ProcessBuilder(application.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Portable exception consumer failed for $target:\n$output")
            }
        }
    }

    @Test
    fun testExceptionOverloadAbi() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val dotnetHost = modernDotNetHostOrSkip()
        val libraryDirectory = File(tmpdir, "portable-exception-overload-library")
        val librarySource = File(tmpdir, "portable-exception-overload-library.kt").apply {
            writeText(
                """
                package exceptionoverload

                public fun classify(value: Throwable): Int = 1
                public fun classify(value: Exception): Int = 2
                public fun classify(value: RuntimeException): Int = 3
                public fun classify(value: Error): Int = 4

                public open class BaseClassifier {
                    public open fun classify(value: Throwable): Int = 11
                    public open fun classify(value: Exception): Int = 12
                    public open fun classify(value: RuntimeException): Int = 13
                    public open fun classify(value: Error): Int = 14
                }

                private class DerivedClassifier : BaseClassifier() {
                    override fun classify(value: Throwable): Int = 21
                    override fun classify(value: Exception): Int = 22
                    override fun classify(value: RuntimeException): Int = 23
                    override fun classify(value: Error): Int = 24
                }

                public interface Classifier {
                    public fun classify(value: Throwable): Int
                    public fun classify(value: Exception): Int
                    public fun classify(value: RuntimeException): Int
                    public fun classify(value: Error): Int
                }

                private class InterfaceClassifier : Classifier {
                    override fun classify(value: Throwable): Int = 31
                    override fun classify(value: Exception): Int = 32
                    override fun classify(value: RuntimeException): Int = 33
                    override fun classify(value: Error): Int = 34
                }

                public interface GenericClassifier<T> {
                    public fun classify(value: Throwable): Int
                    public fun classify(value: Exception): Int
                }

                private class GenericClassifierImpl : GenericClassifier<String> {
                    override fun classify(value: Throwable): Int = 51
                    override fun classify(value: Exception): Int = 52
                }

                public open class GenericBaseClassifier<T> {
                    public open fun select(value: T): Int = 61
                }

                private class ThrowableBaseClassifier : GenericBaseClassifier<Throwable>() {
                    override fun select(value: Throwable): Int = 62
                }

                public interface ThrowableSelector {
                    public fun select(value: Throwable): Int
                }

                private class CombinedClassifier : GenericBaseClassifier<Throwable>(), ThrowableSelector {
                    override fun select(value: Throwable): Int = 71
                }

                public class ExceptionBox<T>(public val value: T)

                public fun nested(value: ExceptionBox<Throwable>): Int = 41
                public fun nested(value: ExceptionBox<Exception>): Int = 42

                public fun derivedClassifier(): BaseClassifier = DerivedClassifier()
                public fun interfaceClassifier(): Classifier = InterfaceClassifier()
                public fun genericClassifier(): GenericClassifier<String> = GenericClassifierImpl()
                public fun genericBaseClassifier(): GenericBaseClassifier<Throwable> = ThrowableBaseClassifier()
                public fun combinedBaseClassifier(): GenericBaseClassifier<Throwable> = CombinedClassifier()
                public fun combinedInterfaceClassifier(): ThrowableSelector = CombinedClassifier()
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Exception.Overloads",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("Exception.Overloads.klib")
        val libraryIl = libraryDirectory.resolve("Exception.Overloads.il").readText()
        assertTrue(
            Regex("\\.method [^\\n]*'classify__KotlinException__[0-9a-f]{32}'")
                .findAll(libraryIl)
                .count() >= 24
        ) { libraryIl }
        assertEquals(
            2,
            Regex("\\.method [^\\n]*'nested__KotlinException__[0-9a-f]{32}'").findAll(libraryIl).count(),
            libraryIl,
        )
        assertTrue("'classify'(class [netstandard]System.Exception" !in libraryIl) { libraryIl }

        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = libraryDirectory.resolve("consumer-${target.replace('.', '-')}").apply { mkdirs() }
            val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
                writeText(
                    """
                    package exceptionoverloadconsumer

                    import exceptionoverload.*

                    fun main() {
                        val throwable: Throwable = Exception("throwable")
                        val exception: Exception = Exception("exception")
                        val runtime: RuntimeException = RuntimeException("runtime")
                        val error: Error = Error("error")

                        if (classify(throwable) != 1 || classify(exception) != 2 ||
                            classify(runtime) != 3 || classify(error) != 4
                        ) {
                            throw Error("top-level classified exception overload")
                        }

                        val member: BaseClassifier = derivedClassifier()
                        if (member.classify(throwable) != 21 || member.classify(exception) != 22 ||
                            member.classify(runtime) != 23 || member.classify(error) != 24
                        ) {
                            throw Error("virtual classified exception overload")
                        }

                        val interfaceValue: Classifier = interfaceClassifier()
                        if (interfaceValue.classify(throwable) != 31 || interfaceValue.classify(exception) != 32 ||
                            interfaceValue.classify(runtime) != 33 || interfaceValue.classify(error) != 34
                        ) {
                            throw Error("interface classified exception overload")
                        }

                        val genericValue: GenericClassifier<String> = genericClassifier()
                        if (genericValue.classify(throwable) != 51 || genericValue.classify(exception) != 52) {
                            throw Error("generic-interface classified exception overload")
                        }

                        val genericBase: GenericBaseClassifier<Throwable> = genericBaseClassifier()
                        if (genericBase.select(throwable) != 62) {
                            throw Error("generic-base classified exception override")
                        }

                        if (combinedBaseClassifier().select(throwable) != 71 ||
                            combinedInterfaceClassifier().select(throwable) != 71
                        ) {
                            throw Error("multi-slot classified exception override")
                        }

                        if (nested(ExceptionBox<Throwable>(throwable)) != 41 ||
                            nested(ExceptionBox<Exception>(exception)) != 42
                        ) {
                            throw Error("nested classified exception overload")
                        }
                    }
                    """.trimIndent()
                )
            }
            val application = consumerDirectory.resolve(
                if (target == "net48") "ExceptionOverloadConsumer.exe" else "ExceptionOverloadConsumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "ExceptionOverloadConsumer",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )
            assertTrue(consumerDirectory.resolve("Exception.Overloads.dll").isFile) {
                "The portable overload implementation must be packaged beside its $target consumer"
            }

            if (target == "net10.0") {
                runDotNet(
                    dotnetHost,
                    application,
                    consumerDirectory,
                    "Portable exception-overload consumer failed for $target",
                )
            } else {
                val process = ProcessBuilder(application.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Portable exception-overload consumer failed for $target:\n$output")
            }
        }
    }

    @Test
    fun testExceptionSignaturePositions() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val dotnetHost = modernDotNetHostOrSkip()
        val libraryDirectory = File(tmpdir, "portable-exception-signatures")
        val librarySource = File(tmpdir, "portable-exception-signatures.kt").apply {
            writeText(
                """
                package exceptionsignatures

                public class ExceptionBox<T>(public val value: T)

                public class ExceptionProperties(
                    public var throwable: Throwable,
                    public var exception: Exception,
                    public var runtime: RuntimeException,
                    public var error: Error,
                    public val boxedRuntime: ExceptionBox<RuntimeException>,
                )

                public fun throwableValue(): Throwable = Exception("throwable")
                public fun exceptionValue(): Exception = Exception("exception")
                public fun runtimeValue(): RuntimeException = IllegalStateException("runtime")
                public fun errorValue(): Error = Error("error")

                public fun properties(): ExceptionProperties = ExceptionProperties(
                    throwableValue(),
                    exceptionValue(),
                    runtimeValue(),
                    errorValue(),
                    ExceptionBox(runtimeValue()),
                )
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Exception.Signatures",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("Exception.Signatures.klib")
        val libraryIl = libraryDirectory.resolve("Exception.Signatures.il").readText()
        assertEquals(
            4,
            Regex("\\.property instance class \\[netstandard]System\\.Exception '(throwable|exception|runtime|error)'\\(\\)")
                .findAll(libraryIl)
                .count(),
            libraryIl,
        )
        assertEquals(
            4,
            Regex("\\.method [^\\n]*'set_(throwable|exception|runtime|error)__KotlinException__[0-9a-f]{32}'")
                .findAll(libraryIl)
                .count(),
            libraryIl,
        )

        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = libraryDirectory.resolve("consumer-${target.replace('.', '-')}").apply { mkdirs() }
            val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
                writeText(
                    """
                    package exceptionsignatureconsumer

                    import exceptionsignatures.*

                    private fun asThrowable(value: Throwable): Throwable = value

                    fun main() {
                        val throwable = throwableValue()
                        val exception = exceptionValue()
                        val runtime = runtimeValue()
                        val error = errorValue()
                        val runtimeAsThrowable = asThrowable(runtime)
                        val errorAsThrowable = asThrowable(error)
                        if (throwable !is Exception || throwable is RuntimeException ||
                            exception !is Exception || exception is RuntimeException ||
                            runtimeAsThrowable !is RuntimeException || runtimeAsThrowable is Error ||
                            errorAsThrowable !is Error || errorAsThrowable is Exception
                        ) {
                            throw Error("exception return classification")
                        }

                        val values = properties()
                        if (values.boxedRuntime.value !is RuntimeException) {
                            throw Error("nested generic exception return")
                        }

                        val newThrowable: Throwable = Error("new-throwable")
                        val newException: Exception = Exception("new-exception")
                        val newRuntime: RuntimeException = IllegalStateException("new-runtime")
                        val newError: Error = Error("new-error")
                        values.throwable = newThrowable
                        values.exception = newException
                        values.runtime = newRuntime
                        values.error = newError
                        if (values.throwable !== newThrowable || values.exception !== newException ||
                            values.runtime !== newRuntime || values.error !== newError
                        ) {
                            throw Error("exception property identity")
                        }
                    }
                    """.trimIndent()
                )
            }
            val application = consumerDirectory.resolve(
                if (target == "net48") "ExceptionSignatureConsumer.exe" else "ExceptionSignatureConsumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "ExceptionSignatureConsumer",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )
            assertTrue(consumerDirectory.resolve("Exception.Signatures.dll").isFile) {
                "The portable exception-signature implementation must be packaged beside its $target consumer"
            }

            if (target == "net10.0") {
                runDotNet(
                    dotnetHost,
                    application,
                    consumerDirectory,
                    "Portable exception-signature consumer failed for $target",
                )
            } else {
                val process = ProcessBuilder(application.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Portable exception-signature consumer failed for $target:\n$output")
            }
        }
    }

    @Test
    fun testExceptionArrayCallableAbi() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val dotnetHost = modernDotNetHostOrSkip()
        val libraryDirectory = File(tmpdir, "portable-exception-array-callable")
        val librarySource = File(tmpdir, "portable-exception-array-callable.kt").apply {
            writeText(
                """
                package exceptionarraycallable

                public fun arrayKind(values: Array<RuntimeException>): Int = 1
                public fun arrayKind(values: Array<Error>): Int = 2

                public fun callbackKind(callback: (RuntimeException) -> Throwable): Int = 3
                public fun callbackKind(callback: (Error) -> Throwable): Int = 4

                public fun invokeRuntime(
                    value: RuntimeException,
                    callback: (RuntimeException) -> Throwable,
                ): Throwable = callback(value)

                public fun invokeError(
                    value: Error,
                    callback: (Error) -> Throwable,
                ): Throwable = callback(value)
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Exception.ArrayCallable",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("Exception.ArrayCallable.klib")
        val libraryIl = libraryDirectory.resolve("Exception.ArrayCallable.il").readText()
        assertEquals(
            2,
            Regex("\\.method [^\\n]*'arrayKind__KotlinException__[0-9a-f]{32}'")
                .findAll(libraryIl)
                .count(),
            libraryIl,
        )
        assertEquals(
            2,
            Regex("\\.method [^\\n]*'callbackKind__KotlinException__[0-9a-f]{32}'")
                .findAll(libraryIl)
                .count(),
            libraryIl,
        )

        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = libraryDirectory.resolve("consumer-${target.replace('.', '-')}").apply { mkdirs() }
            val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
                writeText(
                    """
                    package exceptionarraycallableconsumer

                    import exceptionarraycallable.*

                    fun main() {
                        val runtime: RuntimeException = IllegalStateException("runtime")
                        val error: Error = Error("error")
                        val runtimes: Array<RuntimeException> = arrayOf(runtime)
                        val errors: Array<Error> = arrayOf(error)
                        if (arrayKind(runtimes) != 1 || arrayKind(errors) != 2) {
                            throw Error("exception array overload")
                        }

                        val runtimeCallback: (RuntimeException) -> Throwable = { it }
                        val errorCallback: (Error) -> Throwable = { it }
                        if (callbackKind(runtimeCallback) != 3 || callbackKind(errorCallback) != 4) {
                            throw Error("exception callable overload")
                        }
                        if (invokeRuntime(runtime, runtimeCallback) !== runtime ||
                            invokeError(error, errorCallback) !== error
                        ) {
                            throw Error("exception callable identity")
                        }
                    }
                    """.trimIndent()
                )
            }
            val application = consumerDirectory.resolve(
                if (target == "net48") "ExceptionArrayCallableConsumer.exe" else "ExceptionArrayCallableConsumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "ExceptionArrayCallableConsumer",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )
            assertTrue(consumerDirectory.resolve("Exception.ArrayCallable.dll").isFile) {
                "The portable exception array/callable implementation must be packaged beside its $target consumer"
            }

            if (target == "net10.0") {
                runDotNet(
                    dotnetHost,
                    application,
                    consumerDirectory,
                    "Portable exception array/callable consumer failed for $target",
                )
            } else {
                val process = ProcessBuilder(application.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Portable exception array/callable consumer failed for $target:\n$output")
            }
        }
    }

    @Test
    fun testCancellationExceptionAbi() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val csharpCompiler = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(csharpCompiler != null, ".NET Framework C# compiler is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val libraryDirectory = File(tmpdir, "portable-cancellation-library")
        val librarySource = File(tmpdir, "cancellationLibrary.kt").apply {
            writeText(
                """
                package cancellationabi

                import kotlin.coroutines.cancellation.CancellationException

                public fun kind(value: Throwable): Int =
                    if (value is CancellationException) 5
                    else if (value is IllegalStateException) 4
                    else if (value is RuntimeException) 3
                    else if (value is Exception) 2
                    else if (value is Error) 1
                    else 0

                public fun roundTrip(value: Throwable): Throwable = try {
                    throw value
                } catch (failure: CancellationException) {
                    failure
                } catch (failure: Throwable) {
                    failure
                }

                public fun newCancellation(message: String): Throwable = CancellationException(message)

                public fun newCancellation(message: String, cause: Throwable): Throwable =
                    CancellationException(message, cause)

                public fun cancellationAsIllegalState(): IllegalStateException = CancellationException("parent")

                public class OwnedCancellation(message: String) : CancellationException(message)

                public fun newOwnedCancellation(message: String): Throwable = OwnedCancellation(message)
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Exception.Cancellation",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("Exception.Cancellation.klib")
        val libraryIl = libraryDirectory.resolve("Exception.Cancellation.il").readText()
        assertTrue("[netstandard]System.OperationCanceledException" in libraryIl) { libraryIl }
        assertTrue("catch [netstandard]System.OperationCanceledException" in libraryIl) { libraryIl }
        assertTrue(
            Regex("class \\[netstandard]System\\.Exception 'cancellationAsIllegalState'\\(\\)")
                .containsMatchIn(libraryIl)
        ) { libraryIl }

        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = libraryDirectory.resolve("consumer-${target.replace('.', '-')}").apply { mkdirs() }
            val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
                writeText(
                    """
                    package cancellationconsumer

                    import cancellationabi.*
                    import kotlin.coroutines.cancellation.CancellationException

                    fun main() {
                        val cancellation = newCancellation("owned")
                        if (kind(cancellation) != 5 || cancellation !is CancellationException ||
                            cancellation !is IllegalStateException || cancellation !is RuntimeException ||
                            cancellation !is Exception || cancellation is Error ||
                            roundTrip(cancellation) !== cancellation
                        ) {
                            throw Error("Kotlin cancellation classification")
                        }
                        val parent: IllegalStateException = cancellationAsIllegalState()
                        if (parent !is CancellationException || kind(parent) != 5) {
                            throw Error("Kotlin cancellation parent edge")
                        }
                        if (kind(IllegalStateException("state")) != 4) {
                            throw Error("ordinary IllegalStateException became cancellation")
                        }
                        val cause = Exception("cause")
                        val withCause = newCancellation("wrapped", cause)
                        if (withCause !is CancellationException || withCause.cause !== cause) {
                            throw Error("Kotlin cancellation cause identity")
                        }
                        val subclass = newOwnedCancellation("subclass")
                        if (subclass !is OwnedCancellation || subclass !is CancellationException || kind(subclass) != 5) {
                            throw Error("Kotlin cancellation subclass identity")
                        }
                    }
                    """.trimIndent()
                )
            }
            val application = consumerDirectory.resolve(
                if (target == "net48") "CancellationConsumer.exe" else "CancellationConsumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "CancellationConsumer",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )

            if (target == "net10.0") {
                runDotNet(dotnetHost, application, consumerDirectory, "Kotlin cancellation consumer failed for $target")
            } else {
                val process = ProcessBuilder(application.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Kotlin cancellation consumer failed for $target:\n$output")
            }

            val verifierSource = consumerDirectory.resolve("CancellationVerifier.cs").apply {
                writeText(
                    """
                    using System;
                    using System.Reflection;
                    using System.Threading.Tasks;

                    public static class CancellationVerifier
                    {
                        private static void Require(bool condition, string message)
                        {
                            if (!condition) throw new Exception(message);
                        }

                        private static MethodInfo RequireMethod(Type facade, string logicalName, int parameterCount)
                        {
                            MethodInfo result = null;
                            foreach (MethodInfo candidate in facade.GetMethods(BindingFlags.Public | BindingFlags.Static))
                            {
                                if (candidate.Name != logicalName &&
                                    !candidate.Name.StartsWith(logicalName + "__KotlinException__")) continue;
                                if (candidate.GetParameters().Length != parameterCount) continue;
                                Require(result == null, logicalName + " is ambiguous");
                                result = candidate;
                            }
                            Require(result != null, logicalName + " is unavailable");
                            return result;
                        }

                        public static int Main()
                        {
                            Assembly library = Assembly.LoadFrom("Exception.Cancellation.dll");
                            Type facade = library.GetType("cancellationabi.cancellationLibraryKt", true);
                            Type ownedCancellationType = library.GetType("cancellationabi.OwnedCancellation", true);
                            MethodInfo kind = RequireMethod(facade, "kind", 1);
                            MethodInfo roundTrip = RequireMethod(facade, "roundTrip", 1);
                            MethodInfo create = RequireMethod(facade, "newCancellation", 1);
                            MethodInfo createWithCause = RequireMethod(facade, "newCancellation", 2);
                            MethodInfo createOwned = RequireMethod(facade, "newOwnedCancellation", 1);
                            MethodInfo asIllegalState = RequireMethod(facade, "cancellationAsIllegalState", 0);

                            OperationCanceledException cancellation = new OperationCanceledException("foreign");
                            TaskCanceledException taskCancellation = new TaskCanceledException("task");
                            InvalidOperationException state = new InvalidOperationException("state");
                            Require((int) kind.Invoke(null, new object[] { cancellation }) == 5,
                                "foreign OperationCanceledException classification");
                            Require((int) kind.Invoke(null, new object[] { taskCancellation }) == 5,
                                "foreign TaskCanceledException classification");
                            Require((int) kind.Invoke(null, new object[] { state }) == 4,
                                "InvalidOperationException classification");
                            Require(Object.ReferenceEquals(
                                    cancellation, roundTrip.Invoke(null, new object[] { cancellation })),
                                "foreign cancellation identity");

                            Exception owned = (Exception) create.Invoke(null, new object[] { "owned" });
                            Require(owned.GetType() == typeof(OperationCanceledException),
                                "Kotlin cancellation physical type");
                            Require(owned.Message == "owned", "Kotlin cancellation message");
                            Exception inner = new Exception("inner");
                            Exception withCause = (Exception) createWithCause.Invoke(
                                null, new object[] { "wrapped", inner });
                            Require(withCause.GetType() == typeof(OperationCanceledException),
                                "Kotlin cancellation cause physical type");
                            Require(Object.ReferenceEquals(withCause.InnerException, inner),
                                "Kotlin cancellation cause identity");
                            Require(ownedCancellationType.BaseType == typeof(OperationCanceledException),
                                "Kotlin cancellation subclass physical base");
                            Require(createOwned.Invoke(null, new object[] { "subclass" }).GetType() == ownedCancellationType,
                                "Kotlin cancellation subclass physical identity");
                            Require(asIllegalState.ReturnType == typeof(Exception),
                                "IllegalStateException carrier must admit cancellation");
                            Require(asIllegalState.Invoke(null, null) is OperationCanceledException,
                                "Kotlin cancellation logical parent return");
                            return 0;
                        }
                    }
                    """.trimIndent()
                )
            }
            val verifier = consumerDirectory.resolve("CancellationVerifier.exe")
            val compileResult = runCSharpCompiler(
                checkNotNull(csharpCompiler),
                verifierSource,
                verifier,
                target = "exe",
            )
            assertEquals(0, compileResult.exitCode, compileResult.output)

            val verifierProcess = if (target == "net10.0") {
                consumerDirectory.resolve("CancellationConsumer.runtimeconfig.json")
                    .copyTo(consumerDirectory.resolve("CancellationVerifier.runtimeconfig.json"), overwrite = true)
                ProcessBuilder(dotnetHost.path, "exec", verifier.path)
            } else {
                ProcessBuilder(verifier.path)
            }.directory(consumerDirectory).redirectErrorStream(true).start()
            val verifierOutput = verifierProcess.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, verifierProcess.waitFor(), "C# cancellation verifier failed for $target:\n$verifierOutput")
        }
    }

    @Test
    fun testForeignClrExceptionIdentityAndClassificationAcrossRuntimeProfiles() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val csharpCompiler = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(csharpCompiler != null, ".NET Framework C# compiler is not available")
        val dotnetHost = modernDotNetHostOrSkip()

        for (profile in listOf(
            "net48" to "ExceptionBoundary.exe",
            "net10.0" to "ExceptionBoundary.dll",
        )) {
            val target = profile.first
            val applicationName = profile.second
            val directory = File(tmpdir, "foreign-exception-${target.replace('.', '-')}").apply { mkdirs() }
            val source = directory.resolve("exceptionBoundary.kt").apply {
                writeText(
                    """
                    package exceptionboundary

                    public fun classification(value: Throwable): Int =
                        if (value is Error) 4
                        else if (value is RuntimeException) 3
                        else if (value is Exception) 1
                        else 0

                    public fun roundTrip(value: Throwable): Throwable = try {
                        throw value
                    } catch (failure: Exception) {
                        failure
                    } catch (failure: Error) {
                        failure
                    } catch (failure: Throwable) {
                        failure
                    }

                    public fun rethrow(value: Throwable): Nothing = try {
                        throw value
                    } catch (failure: Exception) {
                        throw failure
                    }

                    public open class KotlinRuntimeFailure(message: String) : RuntimeException(message)

                    public class KotlinRuntimeChild(message: String) : KotlinRuntimeFailure(message)

                    public class KotlinFatalFailure(message: String) : Error(message)

                    fun main() {}
                    """.trimIndent()
                )
            }
            val application = directory.resolve(applicationName)
            compileInProcess(
                K2DotNetCompiler(),
                source.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "ExceptionBoundary",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )
            val applicationIl = directory.resolve("ExceptionBoundary.il").readText()
            assertTrue("filter" in applicationIl) { applicationIl }
            assertTrue("IsKotlinExceptionInstance" in applicationIl) { applicationIl }

            val verifierSource = directory.resolve("ForeignExceptionVerifier.cs").apply {
                writeText(
                    """
                    using System;
                    using System.Reflection;

                    public sealed class ForeignException : Exception
                    {
                        public ForeignException(string message, Exception inner) : base(message, inner) {}
                    }

                    public static class ForeignExceptionVerifier
                    {
                        private static void Require(bool condition, string message)
                        {
                            if (!condition) throw new Exception(message);
                        }

                        private static MethodInfo RequireUniqueKotlinMethod(Type facade, string logicalName)
                        {
                            MethodInfo result = null;
                            foreach (MethodInfo candidate in facade.GetMethods(BindingFlags.Public | BindingFlags.Static))
                            {
                                if (candidate.Name != logicalName &&
                                    !candidate.Name.StartsWith(logicalName + "__KotlinException__")) continue;
                                Require(result == null, "Kotlin " + logicalName + " facade is ambiguous");
                                result = candidate;
                            }
                            Require(result != null, "Kotlin " + logicalName + " facade is not public");
                            return result;
                        }

                        private static void RequireClassifierTotal(Assembly runtimeAssembly, Exception[] values)
                        {
                            Type classifierType = runtimeAssembly.GetType(
                                "Kotlin.Runtime.Internal.ExceptionClassifier", true);
                            MethodInfo classifier = classifierType.GetMethod(
                                "IsKotlinExceptionInstance", BindingFlags.Public | BindingFlags.Static);
                            Require(classifier != null, "runtime exception classifier is not public compiler ABI");
                            int[] typeIds = {
                                Int32.MinValue, -1, 0,
                                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
                                15, Int32.MaxValue,
                            };
                            foreach (Exception value in values)
                            {
                                foreach (int typeId in typeIds)
                                {
                                    try
                                    {
                                        object result = classifier.Invoke(null, new object[] { value, typeId });
                                        Require(result is bool, "classifier returned a non-Boolean result");
                                    }
                                    catch (TargetInvocationException invocation)
                                    {
                                        throw new Exception(
                                            "exception classifier threw for id " + typeId,
                                            invocation.InnerException);
                                    }
                                }
                            }
                        }

                        public static int Main()
                        {
                            Assembly kotlinAssembly = Assembly.LoadFrom("${application.name}");
                            Type facade = kotlinAssembly.GetType("exceptionboundary.exceptionBoundaryKt", true);
                            MethodInfo classification = RequireUniqueKotlinMethod(facade, "classification");
                            MethodInfo roundTrip = RequireUniqueKotlinMethod(facade, "roundTrip");
                            MethodInfo rethrow = RequireUniqueKotlinMethod(facade, "rethrow");

                            Type kotlinRuntimeFailureType = kotlinAssembly.GetType(
                                "exceptionboundary.KotlinRuntimeFailure", true);
                            Type kotlinRuntimeChildType = kotlinAssembly.GetType(
                                "exceptionboundary.KotlinRuntimeChild", true);
                            Type kotlinFatalFailureType = kotlinAssembly.GetType(
                                "exceptionboundary.KotlinFatalFailure", true);
                            Require(kotlinRuntimeFailureType.BaseType.FullName == "Kotlin.RuntimeException",
                                "Kotlin RuntimeException subclass has untruthful CLR ancestry");
                            Require(kotlinRuntimeFailureType.BaseType.Assembly.GetName().Name == "Kotlin.Runtime",
                                "Kotlin RuntimeException subclass is not rooted in the runtime ABI");
                            Require(kotlinRuntimeChildType.BaseType == kotlinRuntimeFailureType,
                                "ordinary Kotlin exception inheritance was flattened");
                            Require(kotlinFatalFailureType.BaseType.FullName == "Kotlin.Error",
                                "Kotlin Error subclass has untruthful CLR ancestry");
                            Require(typeof(Exception).IsAssignableFrom(kotlinRuntimeChildType),
                                "Kotlin runtime exception is not a CLR System.Exception");
                            Require(typeof(Exception).IsAssignableFrom(kotlinFatalFailureType),
                                "Kotlin error is not a CLR System.Exception");

                            Exception kotlinRuntime = (Exception) Activator.CreateInstance(
                                kotlinRuntimeChildType, new object[] { "kotlin-runtime" });
                            Require((int) classification.Invoke(null, new object[] { kotlinRuntime }) == 3,
                                "Kotlin-owned runtime subclass lost its logical categories");
                            Require(Object.ReferenceEquals(
                                    kotlinRuntime, roundTrip.Invoke(null, new object[] { kotlinRuntime })),
                                "Kotlin-owned runtime subclass identity was replaced");

                            Exception kotlinFatal = (Exception) Activator.CreateInstance(
                                kotlinFatalFailureType, new object[] { "kotlin-fatal" });
                            Require((int) classification.Invoke(null, new object[] { kotlinFatal }) == 4,
                                "Kotlin-owned error subclass lost its logical category");
                            Require(Object.ReferenceEquals(
                                    kotlinFatal, roundTrip.Invoke(null, new object[] { kotlinFatal })),
                                "Kotlin-owned error subclass identity was replaced");

                            Exception inner = new Exception("inner");
                            ForeignException foreign = new ForeignException("foreign", inner);
                            object marker = new object();
                            foreign.Data["marker"] = marker;
                            int foreignClassification = (int) classification.Invoke(null, new object[] { foreign });
                            Exception returnedForeign = (Exception) roundTrip.Invoke(null, new object[] { foreign });
                            Require(foreignClassification == 1, "unknown CLR exception must be Kotlin Exception only");
                            Require(Object.ReferenceEquals(foreign, returnedForeign), "foreign exception identity was replaced");
                            Require(returnedForeign.GetType() == typeof(ForeignException), "foreign exact CLR type was lost");
                            Require(Object.ReferenceEquals(returnedForeign.InnerException, inner), "foreign inner exception was lost");
                            Require(Object.ReferenceEquals(returnedForeign.Data["marker"], marker), "foreign exception data was lost");
                            Require(returnedForeign.Message == "foreign", "foreign exception message was lost");
                            Require(!String.IsNullOrEmpty(returnedForeign.StackTrace) &&
                                    returnedForeign.StackTrace.Contains("roundTrip"),
                                "foreign exception did not retain its CLR stack trace after Kotlin catch/return");

                            Exception rethrownForeign = null;
                            try
                            {
                                rethrow.Invoke(null, new object[] { returnedForeign });
                                throw new Exception("Kotlin rethrow unexpectedly returned");
                            }
                            catch (TargetInvocationException invocation)
                            {
                                rethrownForeign = (Exception) invocation.InnerException;
                            }
                            Require(Object.ReferenceEquals(foreign, rethrownForeign),
                                "Kotlin catch/rethrow replaced the foreign exception object");
                            Require(rethrownForeign.GetType() == typeof(ForeignException),
                                "Kotlin catch/rethrow lost the foreign exact CLR type");
                            Require(Object.ReferenceEquals(rethrownForeign.InnerException, inner),
                                "Kotlin catch/rethrow lost the foreign inner exception");
                            Require(Object.ReferenceEquals(rethrownForeign.Data["marker"], marker),
                                "Kotlin catch/rethrow lost foreign exception data");
                            Require(rethrownForeign.Message == "foreign",
                                "Kotlin catch/rethrow lost the foreign exception message");
                            Require(!String.IsNullOrEmpty(rethrownForeign.StackTrace) &&
                                    rethrownForeign.StackTrace.Contains("rethrow"),
                                "Kotlin catch/rethrow did not expose the CLR rethrow site");

                            InvalidOperationException runtime = new InvalidOperationException("runtime");
                            int runtimeClassification = (int) classification.Invoke(null, new object[] { runtime });
                            Require(runtimeClassification == 3, "mapped CLR program fault must be Exception and RuntimeException");
                            Require(Object.ReferenceEquals(runtime, roundTrip.Invoke(null, new object[] { runtime })),
                                "mapped CLR exception identity was replaced");

                            OutOfMemoryException error = new OutOfMemoryException("error");
                            int errorClassification = (int) classification.Invoke(null, new object[] { error });
                            Require(errorClassification == 4, "CLR fatal error must be Error and not Exception");
                            Require(Object.ReferenceEquals(error, roundTrip.Invoke(null, new object[] { error })),
                                "CLR error identity was replaced");
                            RequireClassifierTotal(
                                kotlinRuntimeFailureType.BaseType.Assembly,
                                new Exception[] { null, foreign, runtime, error, kotlinRuntime, kotlinFatal });
                            return 0;
                        }
                    }
                    """.trimIndent()
                )
            }
            val verifier = directory.resolve("ForeignExceptionVerifier.exe")
            val compileResult = runCSharpCompiler(
                checkNotNull(csharpCompiler),
                verifierSource,
                verifier,
                target = "exe",
            )
            assertEquals(0, compileResult.exitCode, compileResult.output)

            val process = if (target == "net10.0") {
                directory.resolve("ExceptionBoundary.runtimeconfig.json")
                    .copyTo(directory.resolve("ForeignExceptionVerifier.runtimeconfig.json"), overwrite = true)
                ProcessBuilder(dotnetHost.path, "exec", verifier.path)
            } else {
                ProcessBuilder(verifier.path)
            }.directory(directory).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), "Foreign exception verifier failed for $target:\n$output")
        }
    }

    private fun produceAndConsumeBoundStdlibPair(target: String) {
        val firstPairDirectory = produceBoundStdlibPair(target, "first")
        val secondPairDirectory = produceBoundStdlibPair(target, "second")
        assertArrayEquals(
            firstPairDirectory.resolve("Kotlin.Stdlib.klib").readBytes(),
            secondPairDirectory.resolve("Kotlin.Stdlib.klib").readBytes(),
            "Packed stdlib metadata must be reproducible for target $target",
        )
        assertArrayEquals(
            firstPairDirectory.resolve("Kotlin.Stdlib.il").readBytes(),
            secondPairDirectory.resolve("Kotlin.Stdlib.il").readBytes(),
            "Compiler-owned stdlib IL must be reproducible for target $target",
        )
        assertArrayEquals(
            firstPairDirectory.resolve("Kotlin.Stdlib.dll").readBytes(),
            secondPairDirectory.resolve("Kotlin.Stdlib.dll").readBytes(),
            "Deterministic ILAsm output must be reproducible for target $target",
        )
        consumeBoundStdlibPair(firstPairDirectory, target)
        consumeInstalledStdlibPair(firstPairDirectory, target)
    }

    private fun produceBoundStdlibPair(target: String, run: String): File {
        val pairDirectory = File(tmpdir, "produced-$target-stdlib-pair-$run")
        compileInProcess(
            K2DotNetCompiler(),
            K2DotNetCompilerArguments::dotNetProduceStdlib.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
            K2DotNetCompilerArguments::destination.cliArgument, pairDirectory.path,
        )

        val metadataLibrary = pairDirectory.resolve("Kotlin.Stdlib.klib")
        val implementationLibrary = pairDirectory.resolve("Kotlin.Stdlib.dll")
        assertTrue(metadataLibrary.isFile) { "Expected packed metadata KLIB at $metadataLibrary" }
        assertTrue(implementationLibrary.isFile) { "Expected CLR implementation at $implementationLibrary" }
        val manifest = metadataLibrary.readKlibManifest()
        assertTrue(manifest.getProperty("unique_name") == "Kotlin.Stdlib")
        assertTrue(manifest.getProperty("dotnet_assembly_file") == "Kotlin.Stdlib.dll")
        assertEquals(target, manifest.getProperty("dotnet_library_tfm"))
        assertEquals(
            DotNetLibraryAbiCodec.implementationSha256(implementationLibrary),
            manifest.getProperty(DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY),
        )
        val il = pairDirectory.resolve("Kotlin.Stdlib.il").readText()
        val coreLibraryReference = if (target == "netstandard2.0") "[netstandard]" else "[mscorlib]"
        val coreLibraryAssembly = if (target == "netstandard2.0") "netstandard" else "mscorlib"
        assertTrue(".assembly extern $coreLibraryAssembly" in il)
        assertTrue("System.Runtime.Versioning.TargetFrameworkAttribute" in il)
        if (target == "netstandard2.0") assertTrue("[mscorlib]" !in il)
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.Iterator', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<!0>" in il
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.Iterator-next-" in il)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.Iterator-next-" in il)
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.Iterable', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Iterable`1'<!0>" in il
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.Iterable-iterator-" in il)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.Iterable-iterator-" in il)
        assertTrue(".class private auto ansi sealed beforefieldinit 'Kotlin.Collections.ArrayIterator`1'" in il)
        assertTrue(".class private auto ansi sealed beforefieldinit 'Kotlin.Collections.ArrayIterable`1'" in il)
        assertTrue(
            ".method public hidebysig static class [Kotlin.Runtime]'Kotlin.Collections.Iterator' " +
                    "'dotNetArrayIterator'<'T'>(!!0[] 'array')" in il
        )
        val compilerOnlyArrayIterator = il.substring(
            il.indexOf("'dotNetArrayIterator'<'T'>").also { assertTrue(it >= 0) },
            il.indexOf("  .method", il.indexOf("'dotNetArrayIterator'<'T'>") + 1)
                .takeIf { it >= 0 } ?: il.length,
        )
        assertTrue("KotlinCompilerAbiAttribute" in compilerOnlyArrayIterator) { compilerOnlyArrayIterator }
        assertTrue("EditorBrowsableAttribute" in compilerOnlyArrayIterator) { compilerOnlyArrayIterator }
        assertTrue(
            ".method public hidebysig static class [Kotlin.Runtime]'Kotlin.Collections.Iterable' " +
                    "'dotNetArrayIterable'<'T'>(!!0[] 'array')" in il
        )
        assertTrue(
            ".method public hidebysig static !!0 'first'<'T'>(" +
                    "class [Kotlin.Runtime]'Kotlin.Collections.List' '<this>')" in il
        )
        assertTrue(
            ".method public hidebysig static !!0 'last'<'T'>(" +
                    "class [Kotlin.Runtime]'Kotlin.Collections.List' '<this>')" in il
        )
        assertTrue(
            ".class private auto ansi sealed 'Kotlin.Collections.EmptyIterator'\n" +
                    "       extends ${coreLibraryReference}System.Object\n" +
                    "       implements [Kotlin.Runtime]'Kotlin.Collections.ListIterator', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.ListIterator`1'<class [Kotlin.Runtime]'Kotlin.Nothing'>" in il
        )
        assertTrue(
            ".class private auto ansi sealed 'Kotlin.Collections.EmptyList'\n" +
                    "       extends ${coreLibraryReference}System.Object\n" +
                    "       implements [Kotlin.Runtime]'Kotlin.Collections.List', 'Kotlin.Io.Serializable', " +
                    "'Kotlin.Collections.RandomAccess', class [Kotlin.Runtime]" +
                    "'Kotlin.Collections.List__KotlinExact`1'<class [Kotlin.Runtime]'Kotlin.Nothing'>" in il
        )
        assertTrue(".class interface public abstract auto ansi 'Kotlin.Collections.RandomAccess'" in il)
        assertTrue(".class interface private abstract auto ansi 'Kotlin.Io.Serializable'" in il)
        assertTrue("class [Kotlin.Runtime]'Kotlin.Nothing'" in il)
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.ListIterator-next-" in il)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.ListIterator-next-" in il)
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.List-get-" in il)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.List-get-" in il)
        assertTrue("<GenericInterfaceExactBridge-kotlin.collections.List-contains-" in il)
        assertTrue(
            ".method public hidebysig static class [Kotlin.Runtime]'Kotlin.Collections.List' " +
                    "'emptyList'<'T'>()" in il
        )
        return pairDirectory
    }

    private fun consumeBoundStdlibPair(pairDirectory: File, target: String) {
        val metadataLibrary = pairDirectory.resolve("Kotlin.Stdlib.klib")
        val consumerSource = pairDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                public fun <T> firstAndLast(values: Iterable<T>): T {
                    values.first()
                    return values.last()
                }

                public fun <T> firstAndLastList(values: List<T>): T {
                    values.first()
                    return values.last()
                }

                public fun firstArray(values: Array<String>): String = values.iterator().next()

                public fun firstArrayIterable(values: Array<String>): String = values.asIterable().first()

                public fun emptyInts(): List<Int> = emptyList()

                public fun emptyStrings(): List<String> = emptyList()

                public fun isRandomAccess(values: List<Int>): Boolean = values is RandomAccess
                """.trimIndent()
            )
        }
        val outputFile = pairDirectory.resolve("consumer.il")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::noStdlib.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
            K2DotNetCompilerArguments::moduleName.cliArgument, "Consumer",
            K2DotNetCompilerArguments::destination.cliArgument, outputFile.path,
        )
        val il = outputFile.readText()
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'first'" in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'last'" in il)
        assertTrue(
            "::'first'<!!0>(class [Kotlin.Runtime]'Kotlin.Collections.List')" in il
        )
        assertTrue(
            "::'last'<!!0>(class [Kotlin.Runtime]'Kotlin.Collections.List')" in il
        )
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'dotNetArrayIterator'<string>" in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'dotNetArrayIterable'<string>" in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.ArrayIterator`1'" !in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.ArrayIterable`1'" !in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'emptyList'<int32>" in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'emptyList'<string>" in il)
        assertTrue("isinst class [Kotlin.Stdlib]'Kotlin.Collections.RandomAccess'" in il)
    }

    private fun consumeInstalledStdlibPair(
        pairDirectory: File,
        target: String,
        installedProfile: String = target,
    ) {
        val kotlinHome = File(tmpdir, "kotlin-home-$target-$installedProfile")
        val installedDirectory = kotlinHome.resolve("lib/dotnet/$installedProfile").apply { mkdirs() }
        pairDirectory.resolve("Kotlin.Stdlib.klib").copyTo(installedDirectory.resolve("Kotlin.Stdlib.klib"))
        pairDirectory.resolve("Kotlin.Stdlib.dll").copyTo(installedDirectory.resolve("Kotlin.Stdlib.dll"))
        val consumerSource = File(tmpdir, "installed-consumer-$target.kt").apply {
            writeText(
                """
                package consumer

                public fun <T> installedFirstAndLast(values: Iterable<T>): T {
                    values.first()
                    return values.last()
                }

                public fun <T> installedFirstAndLastList(values: List<T>): T {
                    values.first()
                    return values.last()
                }

                public fun installedEmptyInts(): List<Int> = emptyList()

                public fun installedRandomAccess(values: List<Int>): Boolean = values is RandomAccess
                """.trimIndent()
            )
        }
        val outputFile = File(tmpdir, "installed-consumer-$target.il")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::kotlinHome.cliArgument, kotlinHome.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
            K2DotNetCompilerArguments::moduleName.cliArgument, "InstalledConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, outputFile.path,
        )
        val il = outputFile.readText()
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'first'" in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'last'" in il)
        assertTrue("::'first'<!!0>(class [Kotlin.Runtime]'Kotlin.Collections.List')" in il)
        assertTrue("::'last'<!!0>(class [Kotlin.Runtime]'Kotlin.Collections.List')" in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'emptyList'<int32>" in il)
        assertTrue("isinst class [Kotlin.Stdlib]'Kotlin.Collections.RandomAccess'" in il)
        assertTrue(".class public abstract sealed auto ansi beforefieldinit 'Kotlin.Collections.CollectionsKt'" !in il)

        val forbiddenKotlinPackageSource = File(tmpdir, "installed-forbidden-kotlin-package-$target.kt").apply {
            writeText(
                """
                package kotlin.user

                public fun mustNotCompile(): Int = 42
                """.trimIndent()
            )
        }
        val [diagnostics, exitCode] = AbstractCliTest.executeCompilerGrabOutput(
            K2DotNetCompiler(),
            listOf(
                forbiddenKotlinPackageSource.path,
                K2DotNetCompilerArguments::kotlinHome.cliArgument, kotlinHome.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::destination.cliArgument,
                File(tmpdir, "installed-forbidden-kotlin-package-$target.il").path,
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, exitCode, diagnostics)
        assertTrue("only the Kotlin standard library is allowed to use the 'kotlin' package" in diagnostics) { diagnostics }
    }

    private fun executeBoundStdlibPair(pairDirectory: File, target: String, dotnetHost: File?) {
        val directory = File(tmpdir, "portable-stdlib-execution-$target").apply { mkdirs() }
        val source = directory.resolve("main.kt").apply {
            writeText(
                """
                fun main() {
                    val values = Array<String>(2) { index -> if (index == 0) "O" else "K" }
                    println(values.asIterable().first() + values.asIterable().last())
                }
                """.trimIndent()
            )
        }
        val output = directory.resolve(if (target == "net48") "PortableStdlib.exe" else "PortableStdlib.dll")
        compileInProcess(
            K2DotNetCompiler(),
            source.path,
            K2DotNetCompilerArguments::noStdlib.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, pairDirectory.resolve("Kotlin.Stdlib.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
            K2DotNetCompilerArguments::moduleName.cliArgument, "PortableStdlib",
            K2DotNetCompilerArguments::destination.cliArgument, output.path,
        )
        assertTrue(directory.resolve("Kotlin.Stdlib.dll").isFile)
        assertTrue(directory.resolve("Kotlin.Runtime.dll").isFile)

        val command = if (target == "net48") {
            listOf(output.path)
        } else {
            listOf(checkNotNull(dotnetHost).path, "exec", output.path)
        }
        val process = ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val processOutput = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), processOutput)
        assertEquals("OK", processOutput.trim())
    }

    @Test
    fun testConsumesExternalStdlibMetadataPair() {
        val pairDirectory = File(tmpdir, "dotnet-stdlib-pair").apply { mkdirs() }
        val metadataSource = File(pairDirectory, "stdlib.kt").apply {
            writeText(
                """
                package kotlin.collections

                public fun <T> Iterable<T>.first(): T = iterator().next()
                """.trimIndent()
            )
        }
        val metadataLibrary = File(pairDirectory, "Kotlin.Stdlib.klib")
        compileInProcess(
            KotlinMetadataCompiler(),
            metadataSource.path,
            K2MetadataCompilerArguments::allowKotlinPackage.cliArgument,
            K2MetadataCompilerArguments::moduleName.cliArgument, "Kotlin.Stdlib",
            K2MetadataCompilerArguments::destination.cliArgument, metadataLibrary.path,
        )
        val physicalDeclarations = DotNetLibraryAbiCodec.encode(
            mapOf(
                "F:kotlin.collections/first|-4901127747075485546[0]" to DotNetPhysicalDeclaration.Function(
                    ownerPath = listOf("Kotlin.Collections.CollectionsKt"),
                    methodName = "first",
                    isInstance = false,
                )
            )
        )
        // IL-only compilation checks that the bound physical companion exists; executable tests
        // separately validate the real generated stdlib assembly.
        val implementationLibrary = File(pairDirectory, "Kotlin.Stdlib.dll").apply {
            writeBytes(byteArrayOf(0))
        }
        val dotNetManifestProperties = linkedMapOf(
            DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY to DotNetLibraryAbiCodec.ABI_VERSION,
            DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY to DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
            DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY to
                    DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION,
            DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY to
                    DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL.toString(),
            DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY to
                    DotNetLibraryAbiCodec.implementationSha256(implementationLibrary),
            DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY to "",
            "dotnet_assembly_name" to "Kotlin.Stdlib",
            "dotnet_assembly_version" to "1.0.0.0",
            "dotnet_assembly_culture" to "neutral",
            "dotnet_assembly_public_key_token" to "null",
            "dotnet_assembly_file" to "Kotlin.Stdlib.dll",
            "dotnet_library_tfm" to "netstandard2.0",
        ).apply { putAll(physicalDeclarations) }
        File(metadataLibrary, "default/manifest").appendText(
            dotNetManifestProperties.entries.joinToString(prefix = "\n", separator = "\n", postfix = "\n") { (key, value) ->
                "$key=$value"
            }
        )

        val consumerSource = File(pairDirectory, "consumer.kt").apply {
            writeText(
                """
                package consumer

                public fun <T> consume(values: Iterable<T>): T = values.first()
                """.trimIndent()
            )
        }
        val outputFile = File(pairDirectory, "consumer.il")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::noStdlib.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
            K2DotNetCompilerArguments::moduleName.cliArgument, "Consumer",
            K2DotNetCompilerArguments::destination.cliArgument, outputFile.path,
        )

        val il = outputFile.readText()
        assertTrue(
            "call !!0 [Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'first'<!!0>" in il,
        ) { "Expected a generic call through the external stdlib assembly:\n$il" }
        assertTrue(".class public abstract sealed auto ansi beforefieldinit 'Kotlin.Collections.CollectionsKt'" !in il) {
            "The external stdlib implementation must not be regenerated in the consumer:\n$il"
        }
    }

    @Test
    fun testRejectsStaleDotNetLibraryAbiSchema() {
        val metadataLibrary = createBoundMetadataLibrary(
            assemblyName = "Stale.Schema",
            propertyOverrides = mapOf(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY to "2"),
        )
        val diagnostics = compileAgainstRejectedLibrary(metadataLibrary)
        assertTrue("uses unsupported CLR ABI index version '2'" in diagnostics) { diagnostics }
    }

    @Test
    fun testRejectsMismatchedDotNetLibraryProfile() {
        val metadataLibrary = createBoundMetadataLibrary(
            assemblyName = "Wrong.Profile",
            propertyOverrides = mapOf(
                DotNetLibraryArtifact.METADATA_LIBRARY_TARGET_FRAMEWORK_PROPERTY to "net10.0"
            ),
        )
        val diagnostics = compileAgainstRejectedLibrary(metadataLibrary)
        assertTrue("is not compatible with Kotlin/.NET target 'net48'" in diagnostics) {
            diagnostics
        }
    }

    @Test
    fun testRejectsMismatchedDotNetImplementationAssembly() {
        val metadataLibrary = createBoundMetadataLibrary(
            assemblyName = "Wrong.Implementation",
            propertyOverrides = emptyMap(),
        )
        metadataLibrary.parentFile.resolve("Wrong.Implementation.dll").writeBytes(byteArrayOf(1))
        val diagnostics = compileAgainstRejectedLibrary(metadataLibrary)
        assertTrue("but its SHA-256 is" in diagnostics && "instead of" in diagnostics) { diagnostics }
    }

    @Test
    fun testRejectsUnsupportedRuntimeSurfaceLevel() {
        val unsupportedLevel = DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL + 1
        val metadataLibrary = createBoundMetadataLibrary(
            assemblyName = "Future.Runtime.Surface",
            propertyOverrides = mapOf(
                DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY to unsupportedLevel.toString()
            ),
        )
        val diagnostics = compileAgainstRejectedLibrary(metadataLibrary)
        assertTrue("requires unsupported Kotlin.Runtime surface level '$unsupportedLevel'" in diagnostics) {
            diagnostics
        }
    }

    private fun createBoundMetadataLibrary(
        assemblyName: String,
        propertyOverrides: Map<String, String>,
    ): File {
        val directory = File(tmpdir, assemblyName).apply { mkdirs() }
        val source = directory.resolve("library.kt").apply {
            writeText(
                """
                package fixture

                public fun published(): Int = 1
                """.trimIndent()
            )
        }
        val metadataLibrary = directory.resolve("$assemblyName.klib")
        compileInProcess(
            KotlinMetadataCompiler(),
            source.path,
            K2MetadataCompilerArguments::moduleName.cliArgument, assemblyName,
            K2MetadataCompilerArguments::destination.cliArgument, metadataLibrary.path,
        )
        val implementationLibrary = directory.resolve("$assemblyName.dll").apply {
            writeBytes(byteArrayOf(0))
        }
        val properties = linkedMapOf(
            DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY to DotNetLibraryAbiCodec.ABI_VERSION,
            DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY to DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
            DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY to
                    DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION,
            DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY to
                    DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL.toString(),
            DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY to
                    DotNetLibraryAbiCodec.implementationSha256(implementationLibrary),
            DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY to "",
            DotNetLibraryArtifact.METADATA_ASSEMBLY_NAME_PROPERTY to assemblyName,
            DotNetLibraryArtifact.METADATA_ASSEMBLY_VERSION_PROPERTY to "1.0.0.0",
            DotNetLibraryArtifact.METADATA_ASSEMBLY_CULTURE_PROPERTY to "neutral",
            DotNetLibraryArtifact.METADATA_ASSEMBLY_PUBLIC_KEY_TOKEN_PROPERTY to "null",
            DotNetLibraryArtifact.METADATA_ASSEMBLY_FILE_PROPERTY to "$assemblyName.dll",
            DotNetLibraryArtifact.METADATA_LIBRARY_TARGET_FRAMEWORK_PROPERTY to "netstandard2.0",
        ).apply { putAll(propertyOverrides) }
        File(metadataLibrary, "default/manifest").appendText(
            properties.entries.joinToString(prefix = "\n", separator = "\n", postfix = "\n") { (key, value) ->
                "$key=$value"
            }
        )
        return metadataLibrary
    }

    private fun compileAgainstRejectedLibrary(metadataLibrary: File): String {
        val source = File(tmpdir, "rejected-library-consumer-${metadataLibrary.nameWithoutExtension}.kt").apply {
            writeText("package consumer\n\npublic fun answer(): Int = 42")
        }
        val [diagnostics, exitCode] = AbstractCliTest.executeCompilerGrabOutput(
            K2DotNetCompiler(),
            listOf(
                source.path,
                K2DotNetCompilerArguments::noStdlib.cliArgument,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::destination.cliArgument,
                File(tmpdir, "rejected-library-consumer-${metadataLibrary.nameWithoutExtension}.il").path,
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, exitCode, diagnostics)
        return diagnostics
    }

    private data class CSharpCompilerResult(val exitCode: Int, val output: String)

    private data class CSharpPhysicalMethodImpl(
        val bodyOwnerPath: List<String>,
        val bodyMethodName: String,
        val bodyIsConcrete: Boolean,
        val declarationAssemblyName: String,
        val declarationOwnerPath: List<String>,
        val declarationMethodName: String,
        val declarationGenericArity: Int,
        val declarationReturnType: String,
        val declarationParameterTypes: List<String>,
    )

    private data class CSharpPhysicalTypeIdentity(
        val assemblyName: String,
        val ownerPath: List<String>,
    )

    private data class CSharpPhysicalCustomAttribute(
        val ownerAssemblyName: String,
        val ownerPath: List<String>,
        val value: ByteArray,
    )

    private data class CSharpPhysicalTypeDefinition(
        val ownerPath: List<String>,
        val visibility: Int,
    )

    private data class CSharpPhysicalGenericParameter(
        val ownerPath: List<String>,
        val methodName: String,
        val index: Int,
        val attributes: Int,
        val constraints: List<CSharpPhysicalTypeIdentity>,
    )

    private fun runCSharpImplementationManifestReader(
        toolchain: DotNetModernCSharpToolchain,
        readerAssembly: File,
        readerDirectory: File,
        producerAssembly: File,
    ): List<String> {
        val process = ProcessBuilder(
            toolchain.dotNetHost.path,
            "exec",
            readerAssembly.path,
            producerAssembly.absolutePath,
        )
            .directory(readerDirectory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), "Could not read manifest from $producerAssembly:\n$output")
        return output.lineSequence().filter(String::isNotBlank).toList()
    }

    private fun readCSharpImplementationManifestFromDll(
        toolchain: DotNetModernCSharpToolchain,
        readerAssembly: File,
        readerDirectory: File,
        producerAssembly: File,
    ): DotNetCSharpImplementationManifest {
        val metadata = runCSharpImplementationManifestReader(
            toolchain,
            readerAssembly,
            readerDirectory,
            producerAssembly,
        )
            .filter { line -> line.startsWith("A|") }
            .map { line ->
                val fields = line.split('|')
                require(fields.size == 3) { "Invalid manifest-reader attribute output: $line" }
                val key = Base64.getDecoder().decode(fields[1]).toString(Charsets.UTF_8)
                val value = Base64.getDecoder().decode(fields[2]).toString(Charsets.UTF_8)
                key to value
            }
        return DotNetCSharpImplementationManifestCodec.decodeAssemblyMetadata(metadata)
    }

    private fun readCSharpPhysicalMethodImplsFromDll(
        toolchain: DotNetModernCSharpToolchain,
        readerAssembly: File,
        readerDirectory: File,
        producerAssembly: File,
    ): List<CSharpPhysicalMethodImpl> =
        runCSharpImplementationManifestReader(
            toolchain,
            readerAssembly,
            readerDirectory,
            producerAssembly,
        )
            .filter { line -> line.startsWith("P|") }
            .map { line ->
                val fields = line.split('|')
                require(fields.size == 10) {
                    "Invalid manifest-reader MethodImpl output: $line"
                }
                val decoded = fields.drop(1).map { field ->
                    Base64.getDecoder().decode(field).toString(Charsets.UTF_8)
                }
                CSharpPhysicalMethodImpl(
                    bodyOwnerPath = decoded[0].split('\u0000'),
                    bodyMethodName = decoded[1],
                    bodyIsConcrete = decoded[2].equals("true", ignoreCase = true),
                    declarationAssemblyName = decoded[3],
                    declarationOwnerPath = decoded[4].split('\u0000'),
                    declarationMethodName = decoded[5],
                    declarationGenericArity = decoded[6].toInt(),
                    declarationReturnType = decoded[7],
                    declarationParameterTypes = decoded[8]
                        .takeIf(String::isNotEmpty)
                        ?.split('\u0001')
                        .orEmpty(),
                )
            }

    private fun readCSharpPhysicalAssemblyAttributesFromDll(
        toolchain: DotNetModernCSharpToolchain,
        readerAssembly: File,
        readerDirectory: File,
        producerAssembly: File,
    ): List<CSharpPhysicalCustomAttribute> =
        runCSharpImplementationManifestReader(
            toolchain,
            readerAssembly,
            readerDirectory,
            producerAssembly,
        )
            .filter { line -> line.startsWith("C|") }
            .map { line ->
                val fields = line.split('|')
                require(fields.size == 4) {
                    "Invalid manifest-reader custom-attribute output: $line"
                }
                val decoded = fields.drop(1).map { field ->
                    Base64.getDecoder().decode(field).toString(Charsets.UTF_8)
                }
                CSharpPhysicalCustomAttribute(
                    ownerAssemblyName = decoded[0],
                    ownerPath = decoded[1].split('\u0000'),
                    value = Base64.getDecoder().decode(decoded[2]),
                )
            }

    private fun readCSharpPhysicalTypeDefinitionsFromDll(
        toolchain: DotNetModernCSharpToolchain,
        readerAssembly: File,
        readerDirectory: File,
        producerAssembly: File,
    ): List<CSharpPhysicalTypeDefinition> =
        runCSharpImplementationManifestReader(
            toolchain,
            readerAssembly,
            readerDirectory,
            producerAssembly,
        )
            .filter { line -> line.startsWith("D|") }
            .map { line ->
                val fields = line.split('|')
                require(fields.size == 3) {
                    "Invalid manifest-reader TypeDef output: $line"
                }
                val decoded = fields.drop(1).map { field ->
                    Base64.getDecoder().decode(field).toString(Charsets.UTF_8)
                }
                CSharpPhysicalTypeDefinition(
                    ownerPath = decoded[0].split('\u0000'),
                    visibility = decoded[1].toInt(),
                )
            }

    private fun readCSharpPhysicalGenericParametersFromDll(
        toolchain: DotNetModernCSharpToolchain,
        readerAssembly: File,
        readerDirectory: File,
        producerAssembly: File,
    ): List<CSharpPhysicalGenericParameter> =
        runCSharpImplementationManifestReader(
            toolchain,
            readerAssembly,
            readerDirectory,
            producerAssembly,
        )
            .filter { line -> line.startsWith("G|") }
            .map { line ->
                val fields = line.split('|')
                require(fields.size == 6) {
                    "Invalid manifest-reader generic-parameter output: $line"
                }
                val decoded = fields.drop(1).map { field ->
                    Base64.getDecoder().decode(field).toString(Charsets.UTF_8)
                }
                CSharpPhysicalGenericParameter(
                    ownerPath = decoded[0].split('\u0000'),
                    methodName = decoded[1],
                    index = decoded[2].toInt(),
                    attributes = decoded[3].toInt(),
                    constraints = decoded[4]
                        .takeIf(String::isNotEmpty)
                        ?.split('\u0001')
                        ?.map { encodedConstraint ->
                            val separator = encodedConstraint.indexOf('\u0000')
                            require(separator > 0) {
                                "Invalid generic-parameter constraint '$encodedConstraint'"
                            }
                            CSharpPhysicalTypeIdentity(
                                assemblyName = encodedConstraint.substring(0, separator),
                                ownerPath = encodedConstraint.substring(separator + 1).split('\u0000'),
                            )
                        }
                        .orEmpty(),
                )
            }

    private fun hasEffectivePromotedDim(
        childContract: DotNetCSharpInterfaceContract,
        parentManifest: DotNetCSharpImplementationManifest,
        parentMember: DotNetCSharpMemberContract,
        methodImpls: List<CSharpPhysicalMethodImpl>,
    ): Boolean {
        val childOwners = buildSet {
            add(childContract.canonicalOwnerPath)
            childContract.declaredOwnerPath?.let(::add)
            childContract.exactOwnerPath?.let(::add)
        }
        val inheritedSlots = parentMember.slots.filter { slot ->
            slot.role != DotNetCSharpSlotRole.HELPER
        }
        return inheritedSlots.isNotEmpty() && inheritedSlots.all { slot ->
            methodImpls.any { implementation ->
                implementation.bodyIsConcrete &&
                        implementation.bodyOwnerPath in childOwners &&
                        implementation.declarationAssemblyName.equals(
                            parentManifest.assemblyName,
                            ignoreCase = true,
                        ) &&
                        implementation.declarationOwnerPath == slot.ownerPath &&
                        implementation.declarationMethodName == slot.methodName &&
                        implementation.declarationGenericArity == slot.genericArity &&
                        physicalSignatureEquals(
                            implementation.declarationReturnType,
                            slot.returnType,
                            parentManifest.assemblyName,
                        ) &&
                        implementation.declarationParameterTypes.size ==
                            slot.parameterTypes.size &&
                        implementation.declarationParameterTypes
                            .zip(slot.parameterTypes)
                            .all { types ->
                                physicalSignatureEquals(
                                    types.first,
                                    types.second,
                                    parentManifest.assemblyName,
                                )
                            }
            }
        }
    }

    private fun physicalSignatureEquals(
        actual: String,
        expected: String,
        localAssemblyName: String,
    ): Boolean {
        return normalizePhysicalSignature(actual, localAssemblyName) ==
                normalizePhysicalSignature(expected, localAssemblyName)
    }

    private fun normalizePhysicalSignature(
        signature: String,
        localAssemblyName: String,
    ): String {
        val localPrefix = "[$localAssemblyName]"
        val corePrefixes = listOf(
            "[mscorlib]",
            "[netstandard]",
            "[System.Runtime]",
            "[System.Private.CoreLib]",
        )
        var quoted = false
        var index = 0
        val result = buildString(signature.length) {
            while (index < signature.length) {
                if (!quoted && signature.startsWith(localPrefix, index)) {
                    index += localPrefix.length
                    continue
                }
                val corePrefix = if (quoted) {
                    null
                } else {
                    corePrefixes.firstOrNull { prefix ->
                        signature.startsWith(prefix, index) &&
                                signature.startsWith("System.", index + prefix.length)
                    }
                }
                if (corePrefix != null) {
                    append("[corelib]")
                    index += corePrefix.length
                    continue
                }
                val current = signature[index]
                if (current == '\'') {
                    quoted = !quoted
                    index++
                    continue
                }
                if (quoted && current == '\\' && index + 1 < signature.length) {
                    val next = signature[index + 1]
                    if (next == '\\' || next == '\'') {
                        append(next)
                        index += 2
                        continue
                    }
                }
                append(current)
                index++
            }
        }
        return if (quoted) signature else result
    }

    private fun generateShapeImplementation(
        contract: DotNetCSharpInterfaceContract,
        rootContract: DotNetCSharpInterfaceContract,
        parentContract: DotNetCSharpInterfaceContract,
        siblingContract: DotNetCSharpInterfaceContract,
        leftContract: DotNetCSharpInterfaceContract,
        rightContract: DotNetCSharpInterfaceContract,
        resolvedIntersectionContract: DotNetCSharpInterfaceContract,
        intersection: DotNetCSharpIntersectionContract,
        mutableLeftContract: DotNetCSharpInterfaceContract,
        mutableRightContract: DotNetCSharpInterfaceContract,
        resolvedMutableContract: DotNetCSharpInterfaceContract,
        mutableGetter: DotNetCSharpIntersectionContract,
        mutableSetter: DotNetCSharpIntersectionContract,
        mapConstraintType: String,
        ordinaryParentContract: DotNetCSharpInterfaceContract,
        ordinaryContract: DotNetCSharpInterfaceContract,
        barrierContract: DotNetCSharpInterfaceContract,
        searchBarrierContract: DotNetCSharpInterfaceContract,
        friendContract: DotNetCSharpInterfaceContract,
        nestedFriendContract: DotNetCSharpInterfaceContract,
        runtimeIterableContract: DotNetCSharpInterfaceContract,
        runtimeCollectionContract: DotNetCSharpInterfaceContract,
        runtimeListContract: DotNetCSharpInterfaceContract,
        inheritedDefaultHasEffectiveDim: Boolean =
            rootContract.members.single { member -> member.sourceName == "fallback" }
                .defaultKind == DotNetCSharpDefaultKind.DIM_WITH_HELPER,
        ordinaryDefaultHasEffectiveDim: Boolean =
            ordinaryParentContract.members.single { member ->
                member.sourceName == "fallbackName"
            }.defaultKind == DotNetCSharpDefaultKind.DIM_WITH_HELPER,
    ): String {
        fun DotNetCSharpInterfaceContract.member(
            name: String,
            kind: DotNetCSharpMemberKind? = null,
            parameterCount: Int? = null,
        ): DotNetCSharpMemberContract =
            members.single { candidate ->
                candidate.sourceName == name &&
                        (kind == null || candidate.kind == kind) &&
                        (parameterCount == null ||
                                candidate.slots.any { slot ->
                                    slot.parameterTypes.size == parameterCount
                                })
            }

        fun DotNetCSharpInterfaceContract.csharpOwner(path: List<String>): String =
            path.joinToString(".") { component -> component.substringBefore('`') }

        val rootCanonicalType = rootContract.csharpOwner(rootContract.canonicalOwnerPath)
        val parentCanonicalType = parentContract.csharpOwner(parentContract.canonicalOwnerPath)
        val siblingCanonicalType = siblingContract.csharpOwner(siblingContract.canonicalOwnerPath)
        val exactType = contract.csharpOwner(checkNotNull(contract.exactOwnerPath))
        val leftCanonicalType = leftContract.csharpOwner(leftContract.canonicalOwnerPath)
        val rightCanonicalType = rightContract.csharpOwner(rightContract.canonicalOwnerPath)
        val resolvedIntersectionType = resolvedIntersectionContract.csharpOwner(
            when (intersection.authoringView) {
                DotNetCSharpInterfaceView.CANONICAL ->
                    resolvedIntersectionContract.canonicalOwnerPath
                DotNetCSharpInterfaceView.DECLARED ->
                    checkNotNull(resolvedIntersectionContract.declaredOwnerPath)
                DotNetCSharpInterfaceView.EXACT ->
                    checkNotNull(resolvedIntersectionContract.exactOwnerPath)
            }
        )
        val mutableLeftCanonicalType =
            mutableLeftContract.csharpOwner(mutableLeftContract.canonicalOwnerPath)
        val mutableRightCanonicalType =
            mutableRightContract.csharpOwner(mutableRightContract.canonicalOwnerPath)
        val resolvedMutableDeclaredType =
            resolvedMutableContract.csharpOwner(
                checkNotNull(resolvedMutableContract.declaredOwnerPath)
            )
        val resolvedMutableExactType =
            resolvedMutableContract.csharpOwner(checkNotNull(resolvedMutableContract.exactOwnerPath))
        val value = rootContract.member("value", DotNetCSharpMemberKind.PROPERTY_GETTER)
        val labelGetter = parentContract.member("label", DotNetCSharpMemberKind.PROPERTY_GETTER)
        val labelSetter = parentContract.member("label", DotNetCSharpMemberKind.PROPERTY_SETTER)
        val secondary = siblingContract.member("secondary", DotNetCSharpMemberKind.PROPERTY_GETTER)
        val map = contract.member("map")
        val accepts = contract.member("accepts")
        val fallback = rootContract.member("fallback")
        val ordinaryParentType =
            ordinaryParentContract.csharpOwner(ordinaryParentContract.canonicalOwnerPath)
        val ordinaryType = ordinaryContract.csharpOwner(ordinaryContract.canonicalOwnerPath)
        val ordinaryDisplayName = ordinaryParentContract.member(
            "displayName",
            DotNetCSharpMemberKind.PROPERTY_GETTER,
        ).slots.single { slot -> slot.role == DotNetCSharpSlotRole.CANONICAL }
        val ordinaryFallback = ordinaryParentContract.member("fallbackName")
        val ordinaryFallbackCanonical = ordinaryFallback.slots.single { slot ->
            slot.role == DotNetCSharpSlotRole.CANONICAL
        }
        val ordinaryCountGetter = ordinaryContract.member(
            "count",
            DotNetCSharpMemberKind.PROPERTY_GETTER,
        ).slots.single { slot -> slot.role == DotNetCSharpSlotRole.CANONICAL }
        val ordinaryCountSetter = ordinaryContract.member(
            "count",
            DotNetCSharpMemberKind.PROPERTY_SETTER,
        ).slots.single { slot -> slot.role == DotNetCSharpSlotRole.CANONICAL }
        val ordinaryFormat = ordinaryContract.member("format")
            .slots
            .single { slot -> slot.role == DotNetCSharpSlotRole.CANONICAL }
        val friendType = friendContract.csharpOwner(friendContract.canonicalOwnerPath)
        val friendCode = friendContract.member(
            "code",
            DotNetCSharpMemberKind.PROPERTY_GETTER,
        ).slots.single { slot -> slot.role == DotNetCSharpSlotRole.CANONICAL }
        val friendFallback = friendContract.member("fallbackCode")
        val friendFallbackCanonical = friendFallback.slots.single { slot ->
            slot.role == DotNetCSharpSlotRole.CANONICAL
        }
        val nestedFriendType =
            nestedFriendContract.csharpOwner(nestedFriendContract.canonicalOwnerPath)
        val nestedFriendCode = nestedFriendContract.member(
            "nestedCode",
            DotNetCSharpMemberKind.PROPERTY_GETTER,
        ).slots.single { slot -> slot.role == DotNetCSharpSlotRole.CANONICAL }
        val barrierCanonicalType =
            barrierContract.csharpOwner(barrierContract.canonicalOwnerPath)
        val barrierExactType =
            barrierContract.csharpOwner(checkNotNull(barrierContract.exactOwnerPath))
        val barrierContains = barrierContract.member("contains")
        val barrierCanonicalContains = barrierContains.slots.single { slot ->
            slot.role == DotNetCSharpSlotRole.ERASED
        }
        val barrierExactContains = barrierContains.slots.single { slot ->
            slot.role == DotNetCSharpSlotRole.EXACT
        }
        val searchCanonicalType =
            searchBarrierContract.csharpOwner(searchBarrierContract.canonicalOwnerPath)
        val searchExactType =
            searchBarrierContract.csharpOwner(checkNotNull(searchBarrierContract.exactOwnerPath))
        val searchIndexOf = searchBarrierContract.member("indexOf")
        val searchCanonicalIndexOf = searchIndexOf.slots.single { slot ->
            slot.role == DotNetCSharpSlotRole.ERASED
        }
        val searchExactIndexOf = searchIndexOf.slots.single { slot ->
            slot.role == DotNetCSharpSlotRole.EXACT
        }
        val runtimeListCanonicalType =
            runtimeListContract.csharpOwner(runtimeListContract.canonicalOwnerPath)
        val runtimeListDeclaredType =
            runtimeListContract.csharpOwner(checkNotNull(runtimeListContract.declaredOwnerPath))
        val runtimeIterableIterator = runtimeIterableContract.member("iterator")
        val runtimeCollectionSize = runtimeCollectionContract.member(
            "size",
            DotNetCSharpMemberKind.PROPERTY_GETTER,
        )
        val runtimeCollectionIsEmpty = runtimeCollectionContract.member("isEmpty")
        val runtimeCollectionContains = runtimeCollectionContract.member("contains")
        val runtimeCollectionIterator = runtimeCollectionContract.member("iterator")
        val runtimeCollectionContainsAll = runtimeCollectionContract.member("containsAll")
        val runtimeListSize = runtimeListContract.member(
            "size",
            DotNetCSharpMemberKind.PROPERTY_GETTER,
        )
        val runtimeListIsEmpty = runtimeListContract.member("isEmpty")
        val runtimeListContains = runtimeListContract.member("contains")
        val runtimeListIterator = runtimeListContract.member("iterator")
        val runtimeListContainsAll = runtimeListContract.member("containsAll")
        val runtimeListGet = runtimeListContract.member("get")
        val runtimeListIndexOf = runtimeListContract.member("indexOf")
        val runtimeListLastIndexOf = runtimeListContract.member("lastIndexOf")
        val runtimeListIteratorWithoutIndex =
            runtimeListContract.member("listIterator", parameterCount = 0)
        val runtimeListIteratorWithIndex =
            runtimeListContract.member("listIterator", parameterCount = 1)
        val runtimeListSubList = runtimeListContract.member("subList")

        fun DotNetCSharpMemberContract.slot(role: DotNetCSharpSlotRole) =
            slots.single { slot -> slot.role == role }

        val collectionSizeProperty =
            checkNotNull(runtimeCollectionSize.slot(DotNetCSharpSlotRole.DECLARED).propertyName)
        val collectionIsEmptyMethod =
            runtimeCollectionIsEmpty.slot(DotNetCSharpSlotRole.DECLARED).methodName
        val collectionIteratorMethod =
            runtimeCollectionIterator.slot(DotNetCSharpSlotRole.DECLARED).methodName
        val collectionContainsAllMethod =
            runtimeCollectionContainsAll.slot(DotNetCSharpSlotRole.DECLARED).methodName
        val collectionContainsErasedMethod =
            runtimeCollectionContains.slot(DotNetCSharpSlotRole.ERASED).methodName
        val collectionContainsExactMethod =
            runtimeCollectionContains.slot(DotNetCSharpSlotRole.EXACT).methodName
        val listSizeProperty =
            checkNotNull(runtimeListSize.slot(DotNetCSharpSlotRole.DECLARED).propertyName)
        val listIsEmptyMethod =
            runtimeListIsEmpty.slot(DotNetCSharpSlotRole.DECLARED).methodName
        val listIteratorMethod =
            runtimeListIterator.slot(DotNetCSharpSlotRole.DECLARED).methodName
        val listContainsAllMethod =
            runtimeListContainsAll.slot(DotNetCSharpSlotRole.DECLARED).methodName
        val listContainsErasedMethod =
            runtimeListContains.slot(DotNetCSharpSlotRole.ERASED).methodName
        val listContainsExactMethod =
            runtimeListContains.slot(DotNetCSharpSlotRole.EXACT).methodName
        val listGetErasedMethod =
            runtimeListGet.slot(DotNetCSharpSlotRole.ERASED).methodName
        val listGetDeclaredMethod =
            runtimeListGet.slot(DotNetCSharpSlotRole.DECLARED).methodName
        val listIndexOfErasedMethod =
            runtimeListIndexOf.slot(DotNetCSharpSlotRole.ERASED).methodName
        val listIndexOfExactMethod =
            runtimeListIndexOf.slot(DotNetCSharpSlotRole.EXACT).methodName
        val listLastIndexOfErasedMethod =
            runtimeListLastIndexOf.slot(DotNetCSharpSlotRole.ERASED).methodName
        val listLastIndexOfExactMethod =
            runtimeListLastIndexOf.slot(DotNetCSharpSlotRole.EXACT).methodName
        val listIteratorWithoutIndexMethod =
            runtimeListIteratorWithoutIndex.slot(DotNetCSharpSlotRole.DECLARED).methodName
        val listIteratorWithIndexMethod =
            runtimeListIteratorWithIndex.slot(DotNetCSharpSlotRole.DECLARED).methodName
        val listSubListMethod =
            runtimeListSubList.slot(DotNetCSharpSlotRole.DECLARED).methodName
        require(
            runtimeIterableIterator.slot(DotNetCSharpSlotRole.DECLARED).methodName ==
                    collectionIteratorMethod &&
                    collectionIteratorMethod == listIteratorMethod &&
                    collectionSizeProperty == listSizeProperty &&
                    collectionIsEmptyMethod == listIsEmptyMethod &&
                    collectionContainsAllMethod == listContainsAllMethod &&
                    collectionContainsErasedMethod == listContainsErasedMethod &&
                    collectionContainsExactMethod == listContainsExactMethod
        ) {
            "The runtime collection inheritance graph needs distinct C# adapters"
        }
        require(ordinaryCountGetter.propertyName == ordinaryCountSetter.propertyName)
        val canonicalValue = value.slots.single { it.role == DotNetCSharpSlotRole.ERASED }
        val canonicalLabelGetter = labelGetter.slots.single { it.role == DotNetCSharpSlotRole.ERASED }
        val canonicalLabelSetter = labelSetter.slots.single { it.role == DotNetCSharpSlotRole.ERASED }
        val canonicalSecondary = secondary.slots.single { it.role == DotNetCSharpSlotRole.ERASED }
        val canonicalMap = map.slots.single { it.role == DotNetCSharpSlotRole.ERASED }
        val canonicalAccepts = accepts.slots.single { it.role == DotNetCSharpSlotRole.ERASED }
        val canonicalFallback = fallback.slots.single { it.role == DotNetCSharpSlotRole.ERASED }
        val leftOverlap = leftContract.member("overlap")
            .slots
            .single { it.role == DotNetCSharpSlotRole.ERASED }
        val rightOverlap = rightContract.member("overlap")
            .slots
            .single { it.role == DotNetCSharpSlotRole.ERASED }
        val typedIntersection = intersection.slots.single { slot ->
            slot.role == when (intersection.authoringView) {
                DotNetCSharpInterfaceView.CANONICAL -> DotNetCSharpSlotRole.CANONICAL
                DotNetCSharpInterfaceView.DECLARED -> DotNetCSharpSlotRole.DECLARED
                DotNetCSharpInterfaceView.EXACT -> DotNetCSharpSlotRole.EXACT
            }
        }
        val mutableDeclaredGetter = mutableGetter.slots.single { slot ->
            slot.role == DotNetCSharpSlotRole.DECLARED
        }
        val mutableExactGetter = mutableGetter.slots.single { slot ->
            slot.role == DotNetCSharpSlotRole.EXACT
        }
        val mutableExactSetter = mutableSetter.slots.single()
        require(mutableExactGetter.propertyName == mutableExactSetter.propertyName)
        val mutableLeftGetter = mutableLeftContract.member(
            "merged",
            DotNetCSharpMemberKind.PROPERTY_GETTER,
        ).slots.single { slot -> slot.role == DotNetCSharpSlotRole.ERASED }
        val mutableLeftSetter = mutableLeftContract.member(
            "merged",
            DotNetCSharpMemberKind.PROPERTY_SETTER,
        ).slots.single { slot -> slot.role == DotNetCSharpSlotRole.ERASED }
        val mutableRightGetter = mutableRightContract.member(
            "merged",
            DotNetCSharpMemberKind.PROPERTY_GETTER,
        ).slots.single { slot -> slot.role == DotNetCSharpSlotRole.ERASED }
        val mutableRightSetter = mutableRightContract.member(
            "merged",
            DotNetCSharpMemberKind.PROPERTY_SETTER,
        ).slots.single { slot -> slot.role == DotNetCSharpSlotRole.ERASED }
        require(mutableLeftGetter.propertyName == mutableLeftSetter.propertyName)
        require(mutableRightGetter.propertyName == mutableRightSetter.propertyName)
        require(canonicalLabelGetter.propertyName == canonicalLabelSetter.propertyName)
        val portableDefault =
            fallback.defaultKind == DotNetCSharpDefaultKind.PORTABLE_HELPER &&
                    !inheritedDefaultHasEffectiveDim
        val generatedTypedDefault = if (portableDefault) {
            val helper = fallback.slots.single { it.role == DotNetCSharpSlotRole.HELPER }
            require(helper.genericArity == contract.typeParameters.size)
            val helperOwner = contract.csharpOwner(helper.ownerPath)
            """
                public string ${fallback.sourceName}()
                {
                    return $helperOwner.${helper.methodName}<string>(this);
                }
            """.trimIndent()
        } else {
            ""
        }
        val generatedCanonicalDefault = if (portableDefault) {
            """
                public object ${canonicalFallback.methodName}()
                {
                    return ${fallback.sourceName}();
                }
            """.trimIndent()
        } else {
            ""
        }
        val generatedOrdinaryDefault = if (
            ordinaryFallback.defaultKind == DotNetCSharpDefaultKind.PORTABLE_HELPER &&
            !ordinaryDefaultHasEffectiveDim
        ) {
            val helper = ordinaryFallback.slots.single { slot ->
                slot.role == DotNetCSharpSlotRole.HELPER
            }
            require(helper.genericArity == 0)
            val helperOwner = ordinaryParentContract.csharpOwner(helper.ownerPath)
            """
                string $ordinaryParentType.${ordinaryFallbackCanonical.methodName}()
                {
                    return $helperOwner.${helper.methodName}(this);
                }
            """.trimIndent()
        } else {
            ""
        }
        val generatedFriendDefault = if (
            friendFallback.defaultKind == DotNetCSharpDefaultKind.PORTABLE_HELPER
        ) {
            val helper = friendFallback.slots.single { slot ->
                slot.role == DotNetCSharpSlotRole.HELPER
            }
            require(helper.genericArity == 0)
            val helperOwner = friendContract.csharpOwner(helper.ownerPath)
            """
                int $friendType.${friendFallbackCanonical.methodName}()
                {
                    return $helperOwner.${helper.methodName}(this);
                }
            """.trimIndent()
        } else {
            ""
        }
        return """
            public sealed partial class GeneratedShape
            {
                public string value { get { return "typed"; } }

                public string secondary { get { return "secondary"; } }

                public string label { get; set; } = "initial";

                public string map<R>(R input) where R : $mapConstraintType { return value; }

                public bool accepts(string input) { return input == value; }
            }

            public sealed partial class GeneratedShape : $exactType<string>
            {
                $generatedTypedDefault

                object $rootCanonicalType.${checkNotNull(canonicalValue.propertyName)}
                {
                    get { return value; }
                }

                object $siblingCanonicalType.${checkNotNull(canonicalSecondary.propertyName)}
                {
                    get { return secondary; }
                }

                string $parentCanonicalType.${checkNotNull(canonicalLabelGetter.propertyName)}
                {
                    get { return label; }
                    set { label = value; }
                }

                public object ${canonicalMap.methodName}<R>(R input) where R : $mapConstraintType
                {
                    return map(input);
                }

                public bool ${canonicalAccepts.methodName}(object input)
                {
                    return accepts((string)input);
                }

                $generatedCanonicalDefault
            }

            public sealed partial class GeneratedIntersection
            {
                public string ${intersection.sourceName}()
                {
                    return "intersection";
                }
            }

            public sealed partial class GeneratedIntersection : $resolvedIntersectionType<string>
            {
                string $resolvedIntersectionType<string>.${typedIntersection.methodName}()
                {
                    return ${intersection.sourceName}();
                }

                object $leftCanonicalType.${leftOverlap.methodName}()
                {
                    return ${intersection.sourceName}();
                }

                object $rightCanonicalType.${rightOverlap.methodName}()
                {
                    return ${intersection.sourceName}();
                }
            }

            public sealed partial class GeneratedMutableIntersection
            {
                public string merged { get; set; } = "mutable";
            }

            public sealed partial class GeneratedMutableIntersection :
                $resolvedMutableExactType<string>
            {
                string $resolvedMutableDeclaredType<string>.${checkNotNull(mutableDeclaredGetter.propertyName)}
                {
                    get { return merged; }
                }

                string $resolvedMutableExactType<string>.${checkNotNull(mutableExactGetter.propertyName)}
                {
                    get { return merged; }
                    set { merged = value; }
                }

                object $mutableLeftCanonicalType.${checkNotNull(mutableLeftGetter.propertyName)}
                {
                    get { return merged; }
                    set { merged = (string)value; }
                }

                object $mutableRightCanonicalType.${checkNotNull(mutableRightGetter.propertyName)}
                {
                    get { return merged; }
                    set { merged = (string)value; }
                }
            }

            public sealed partial class GeneratedOrdinary
            {
                public string DisplayName { get { return "ordinary"; } }

                public int Count { get; set; } = 3;

                public string Format(string prefix) { return prefix + DisplayName; }
            }

            public sealed partial class GeneratedOrdinary : $ordinaryType
            {
                string $ordinaryParentType.${checkNotNull(ordinaryDisplayName.propertyName)}
                {
                    get { return DisplayName; }
                }

                int $ordinaryType.${checkNotNull(ordinaryCountGetter.propertyName)}
                {
                    get { return Count; }
                    set { Count = value; }
                }

                string $ordinaryType.${ordinaryFormat.methodName}(string prefix)
                {
                    return Format(prefix);
                }

                $generatedOrdinaryDefault
            }

            internal sealed partial class GeneratedFriend
            {
                internal int Code { get { return 41; } }
            }

            internal sealed partial class GeneratedFriend : $friendType
            {
                int $friendType.${checkNotNull(friendCode.propertyName)}
                {
                    get { return Code; }
                }

                $generatedFriendDefault
            }

            internal sealed partial class GeneratedNestedFriend : $nestedFriendType
            {
                int $nestedFriendType.${checkNotNull(nestedFriendCode.propertyName)}
                {
                    get { return 42; }
                }
            }

            public sealed partial class GeneratedBarrier
            {
                public bool ContainsValue(string element) { return element == "typed"; }
            }

            public sealed partial class GeneratedBarrier : $barrierExactType<string>
            {
                public int $collectionSizeProperty { get { return 1; } }

                public bool $collectionIsEmptyMethod() { return false; }

                public Kotlin.Collections.Iterator $collectionIteratorMethod() { return null; }

                public bool $collectionContainsAllMethod(
                    Kotlin.Collections.Collection elements) { return false; }

                public bool $collectionContainsErasedMethod(object element)
                {
                    return element is string && ContainsValue((string)element);
                }

                public bool $collectionContainsExactMethod(string element)
                {
                    return ContainsValue(element);
                }

                bool $barrierCanonicalType.${barrierCanonicalContains.methodName}(object element)
                {
                    return element is string && ContainsValue((string)element);
                }

                bool $barrierExactType<string>.${barrierExactContains.methodName}(string element)
                {
                    return ContainsValue(element);
                }
            }

            public sealed partial class GeneratedSearchBarrier
            {
                public bool ContainsValue(string element) { return element == "typed"; }

                public int IndexOfValue(string element) { return ContainsValue(element) ? 0 : -1; }
            }

            public sealed partial class GeneratedSearchBarrier : $searchExactType<string>
            {
                public int $listSizeProperty { get { return 1; } }

                public bool $listIsEmptyMethod() { return false; }

                public Kotlin.Collections.Iterator $listIteratorMethod() { return null; }

                public bool $listContainsAllMethod(
                    Kotlin.Collections.Collection elements) { return false; }

                public bool $listContainsErasedMethod(object element)
                {
                    return element is string && ContainsValue((string)element);
                }

                public bool $listContainsExactMethod(string element)
                {
                    return ContainsValue(element);
                }

                object $runtimeListCanonicalType.$listGetErasedMethod(int index)
                {
                    return index == 0 ? "typed" : null;
                }

                string $runtimeListDeclaredType<string>.$listGetDeclaredMethod(int index)
                {
                    return index == 0 ? "typed" : null;
                }

                public int $listIndexOfErasedMethod(object element)
                {
                    return element is string ? IndexOfValue((string)element) : -1;
                }

                public int $listLastIndexOfErasedMethod(object element)
                {
                    return element is string ? IndexOfValue((string)element) : -1;
                }

                public int $listIndexOfExactMethod(string element)
                {
                    return IndexOfValue(element);
                }

                public int $listLastIndexOfExactMethod(string element)
                {
                    return IndexOfValue(element);
                }

                public Kotlin.Collections.ListIterator $listIteratorWithoutIndexMethod()
                {
                    return null;
                }

                public Kotlin.Collections.ListIterator $listIteratorWithIndexMethod(int index)
                {
                    return null;
                }

                public Kotlin.Collections.List $listSubListMethod(int fromIndex, int toIndex)
                {
                    return null;
                }

                int $searchCanonicalType.${searchCanonicalIndexOf.methodName}(object element)
                {
                    return element is string ? IndexOfValue((string)element) : -1;
                }

                int $searchExactType<string>.${searchExactIndexOf.methodName}(string element)
                {
                    return IndexOfValue(element);
                }
            }

            public static class Program
            {
                public static int Main()
                {
                    int result = manifest.apiKt.verify(new GeneratedShape());
                    if (result != 0)
                        throw new System.Exception("Kotlin verification failed: " + result);
                    int intersectionResult =
                        manifest.apiKt.verifyIntersection(new GeneratedIntersection());
                    if (intersectionResult != 0)
                        throw new System.Exception(
                            "Kotlin intersection verification failed: " + intersectionResult);
                    int mutableResult =
                        manifest.apiKt.verifyMutable(new GeneratedMutableIntersection());
                    if (mutableResult != 0)
                        throw new System.Exception(
                            "Kotlin mutable verification failed: " + mutableResult);
                    int ordinaryResult =
                        manifest.apiKt.verifyOrdinary(new GeneratedOrdinary());
                    if (ordinaryResult != 0)
                        throw new System.Exception(
                            "Kotlin ordinary verification failed: " + ordinaryResult);
                    int barrierResult =
                        manifest.apiKt.verifyBarrier(new GeneratedBarrier());
                    if (barrierResult != 0)
                        throw new System.Exception(
                            "Kotlin Collection barrier verification failed: " + barrierResult);
                    int searchBarrierResult =
                        manifest.apiKt.verifySearchBarrier(new GeneratedSearchBarrier());
                    if (searchBarrierResult != 0)
                        throw new System.Exception(
                            "Kotlin List barrier verification failed: " + searchBarrierResult);
                    int friendResult = manifest.apiKt.verifyFriend(new GeneratedFriend());
                    if (friendResult != 0)
                        throw new System.Exception(
                            "Kotlin friend verification failed: " + friendResult);
                    int nestedFriendResult =
                        manifest.apiKt.verifyNestedFriend(new GeneratedNestedFriend());
                    if (nestedFriendResult != 0)
                        throw new System.Exception(
                            "Kotlin nested-friend verification failed: " + nestedFriendResult);
                    return 0;
                }
            }
        """.trimIndent()
    }

    private fun net10RuntimeConfig(): String =
        """
        {
          "runtimeOptions": {
            "tfm": "net10.0",
            "framework": {
              "name": "Microsoft.NETCore.App",
              "version": "10.0.0"
            },
            "rollForward": "LatestMinor"
          }
        }
        """.trimIndent()

    private fun runCSharpCompiler(
        compiler: File,
        source: File,
        output: File,
        vararg references: File,
        target: String = "library",
    ): CSharpCompilerResult {
        output.delete()
        val arguments = buildList {
            add(compiler.path)
            add("/nologo")
            add("/target:$target")
            add("/out:${output.path}")
            references.forEach { add("/reference:${it.path}") }
            add(source.path)
        }
        val process = ProcessBuilder(arguments)
            .directory(tmpdir)
            .redirectErrorStream(true)
            .start()
        val compilerOutput = process.inputStream.bufferedReader().use { it.readText() }
        return CSharpCompilerResult(process.waitFor(), compilerOutput)
    }

    private fun runModernCSharpCompiler(
        toolchain: DotNetModernCSharpToolchain,
        source: File,
        output: File,
        vararg references: File,
        target: String = "library",
        analyzers: List<File> = emptyList(),
        generatedFilesDirectory: File? = null,
    ): CSharpCompilerResult {
        output.delete()
        val frameworkReferences = toolchain.referenceDirectory.listFiles { file ->
            file.isFile && file.extension.equals("dll", ignoreCase = true)
        }?.sortedBy(File::getName)
            ?: error("Modern C# reference directory is unreadable: ${toolchain.referenceDirectory}")
        val arguments = buildList {
            add(toolchain.dotNetHost.path)
            add(toolchain.compiler.path)
            add("/nologo")
            add("/noconfig")
            add("/nostdlib+")
            add("/deterministic+")
            add("/langversion:latest")
            add("/target:$target")
            add("/out:${output.path}")
            frameworkReferences.forEach { add("/reference:${it.path}") }
            references.forEach { reference ->
                assertTrue(reference.isFile) { "Missing C# reference: $reference" }
                add("/reference:${reference.path}")
            }
            analyzers.forEach { analyzer ->
                assertTrue(analyzer.isFile) { "Missing C# analyzer: $analyzer" }
                add("/analyzer:${analyzer.path}")
            }
            generatedFilesDirectory?.let { directory ->
                directory.mkdirs()
                add("/generatedfilesout:${directory.path}")
            }
            add(source.path)
        }
        val process = ProcessBuilder(arguments)
            .directory(tmpdir)
            .redirectErrorStream(true)
            .start()
        val compilerOutput = process.inputStream.bufferedReader().use { it.readText() }
        return CSharpCompilerResult(process.waitFor(), compilerOutput)
    }

    private fun buildCSharpAuthoringTooling(
        toolchain: DotNetModernCSharpToolchain,
    ): File {
        val project = File(
            "compiler/ir/backend.dotnet/csharp-authoring/" +
                    "Kotlin.DotNet.CSharpAuthoring/Kotlin.DotNet.CSharpAuthoring.csproj"
        ).absoluteFile
        assertTrue(project.isFile) { "Missing Kotlin C# authoring project: $project" }
        val process = ProcessBuilder(
            toolchain.dotNetHost.path,
            "build",
            project.path,
            "--configuration", "Release",
            "-p:RestoreLockedMode=true",
            "--nologo",
        )
            .directory(File(".").absoluteFile)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), "Could not build Kotlin C# authoring tooling:\n$output")
        return project.parentFile
            .resolve("bin/Release/netstandard2.0/Kotlin.DotNet.CSharpAuthoring.dll")
            .also { assembly ->
                assertTrue(assembly.isFile) {
                    "Kotlin C# authoring build produced no analyzer assembly:\n$output"
                }
            }
    }

    private fun compileInProcess(compiler: CLICompiler<*>, vararg args: String) {
        val [output, exitCode] = AbstractCliTest.executeCompilerGrabOutput(compiler, args.toList())
        if (exitCode != ExitCode.OK) error("Failed to compile: ${args.joinToString(" ")}\nOutput:\n$output")
    }

    private fun modernDotNetHostOrSkip(): File {
        val host = DotNetIlAssembler.findModernDotNetHost()
        requireOrAssumeToolchain(host != null, "Modern dotnet host is not available")
        return checkNotNull(host)
    }

    private fun findFrameworkNetStandardFacade(): File? {
        val windowsDirectory = System.getenv("WINDIR")?.let(::File)
        val programFilesX86 = System.getenv("ProgramFiles(x86)")?.let(::File)
        val candidates = listOfNotNull(
            programFilesX86?.resolve(
                "Reference Assemblies/Microsoft/Framework/.NETFramework/v4.8/Facades/netstandard.dll"
            ),
            windowsDirectory?.resolve(
                "Microsoft.NET/assembly/GAC_MSIL/netstandard/" +
                        "v4.0_2.0.0.0__cc7b13ffcd2ddd51/netstandard.dll"
            ),
        )
        return candidates.firstOrNull { candidate -> candidate.isFile }
    }

    private fun frameworkExecutionCommand(host: File, assembly: File): List<String> {
        val escapedAssemblyPath = assembly.absolutePath.replace("'", "''")
        val command = """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
                ${'$'}assembly = [Reflection.Assembly]::LoadFrom('$escapedAssemblyPath')
                ${'$'}entryPoint = ${'$'}assembly.EntryPoint
                if (${'$'}null -eq ${'$'}entryPoint) { throw 'Assembly has no managed entry point.' }
                if (${'$'}entryPoint.GetParameters().Count -eq 0) {
                    [void] ${'$'}entryPoint.Invoke(${'$'}null, ${'$'}null)
                } else {
                    [void] ${'$'}entryPoint.Invoke(${'$'}null, [object[]] @(,[string[]] @()))
                }
            } catch {
                [Console]::Error.WriteLine(${'$'}_.Exception.ToString())
                exit 1
            }
        """.trimIndent()
        return listOf(host.path, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command)
    }

    private fun requireOrAssumeToolchain(condition: Boolean, message: String) {
        if (dotNetToolchainIsRequired()) {
            assertTrue(condition) { "$message (KOTLIN_DOTNET_REQUIRE_TOOLCHAIN is enabled)" }
        } else {
            assumeTrue(condition, message)
        }
    }

    private fun dotNetToolchainIsRequired(): Boolean =
        System.getenv("KOTLIN_DOTNET_REQUIRE_TOOLCHAIN")?.let { value ->
            value == "1" || value.equals("true", ignoreCase = true)
        } == true

    private fun File.readKlibManifest(): Properties = ZipFile(this).use { archive ->
        Properties().apply {
            load(archive.getInputStream(archive.getEntry("default/manifest")))
        }
    }

    private fun runDotNet(
        dotnetHost: File,
        assembly: File,
        workingDirectory: File,
        failureMessage: String,
    ) {
        val process = ProcessBuilder(dotnetHost.path, "exec", assembly.path)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor() == 0) { "$failureMessage:\n$output" }
    }

    private fun runAssemblerPairing(command: List<String>, workingDirectory: File, description: String) {
        val process = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), "$description failed:\n$output")
        assertEquals("OK", output.trim(), "$description produced unexpected output")
    }
}
