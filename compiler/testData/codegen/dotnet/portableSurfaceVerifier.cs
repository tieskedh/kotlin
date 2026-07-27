// Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
// Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Runtime.Loader;
using System.Security.Cryptography;
using System.Text;

internal static class Program
{
    private const string CSharpManifestResourceName = "Kotlin.CSharpImplementationManifest";
    private const string CSharpManifestLogicalIdentityScheme =
        "kotlin-public-id-signature-legacy-v1";
    private const int CSharpManifestHeaderSize = 48;
    private const int MaximumCSharpManifestPayloadBytes = 4 * 1024 * 1024;
    private static readonly byte[] CSharpManifestMagic = Encoding.ASCII.GetBytes("KDNCSM01");
    private static readonly Encoding StrictUtf8 = new UTF8Encoding(false, true);

    private sealed class PairLoadContext : AssemblyLoadContext
    {
        private readonly string directory;

        internal PairLoadContext(string directory)
            : base(isCollectible: true)
        {
            this.directory = directory;
        }

        protected override Assembly Load(AssemblyName assemblyName)
        {
            string candidate = Path.Combine(directory, assemblyName.Name + ".dll");
            return File.Exists(candidate) ? LoadFromAssemblyPath(candidate) : null;
        }
    }

    private sealed class SurfaceItem
    {
        internal SurfaceItem(string key, int access, bool isMethod, bool isAbstract, bool isFinal)
        {
            Key = key;
            Access = access;
            IsMethod = isMethod;
            IsAbstract = isAbstract;
            IsFinal = isFinal;
        }

        internal string Key { get; }
        internal int Access { get; }
        internal bool IsMethod { get; }
        internal bool IsAbstract { get; }
        internal bool IsFinal { get; }
    }

    private sealed class ResourceItem
    {
        internal ResourceItem(
            string key,
            ManifestResourceAttributes attributes,
            bool isEmbedded,
            CSharpManifestContract manifest)
        {
            Key = key;
            Attributes = attributes;
            IsEmbedded = isEmbedded;
            Manifest = manifest;
        }

        internal string Key { get; }
        internal ManifestResourceAttributes Attributes { get; }
        internal bool IsEmbedded { get; }
        internal CSharpManifestContract Manifest { get; }
    }

    private sealed class CSharpManifestContract
    {
        internal CSharpManifestContract(
            int schemaVersion,
            string assemblyName,
            string targetProfile,
            string logicalIdentityScheme,
            HashSet<string> logicalDeclarations,
            List<CSharpManifestSlot> slots)
        {
            SchemaVersion = schemaVersion;
            AssemblyName = assemblyName;
            TargetProfile = targetProfile;
            LogicalIdentityScheme = logicalIdentityScheme;
            LogicalDeclarations = logicalDeclarations;
            Slots = slots;
        }

        internal int SchemaVersion { get; }
        internal string AssemblyName { get; }
        internal string TargetProfile { get; }
        internal string LogicalIdentityScheme { get; }
        internal HashSet<string> LogicalDeclarations { get; }
        internal List<CSharpManifestSlot> Slots { get; }
    }

    private sealed class CSharpManifestSlot
    {
        internal CSharpManifestSlot(
            string assemblyName,
            string logicalKey,
            string role,
            string ownerPath,
            string methodName,
            int genericArity,
            string returnType,
            string[] parameterTypes,
            string defaultKind,
            string semanticBodyView)
        {
            AssemblyName = assemblyName;
            LogicalKey = logicalKey;
            Role = role;
            OwnerPath = ownerPath;
            MethodName = methodName;
            GenericArity = genericArity;
            ReturnType = returnType;
            ParameterTypes = parameterTypes;
            DefaultKind = defaultKind;
            SemanticBodyView = semanticBodyView;
        }

        internal string AssemblyName { get; }
        internal string LogicalKey { get; }
        internal string Role { get; }
        internal string OwnerPath { get; }
        internal string MethodName { get; }
        internal int GenericArity { get; }
        internal string ReturnType { get; }
        internal string[] ParameterTypes { get; }
        internal string DefaultKind { get; }
        internal string SemanticBodyView { get; }

        internal string SemanticKey =>
            AssemblyName + "|" + LogicalKey + "|" + Role;

        internal string LocatorKey =>
            OwnerPath + "|" + MethodName + "|" +
            GenericArity.ToString(CultureInfo.InvariantCulture) + "|" +
            NormalizeManifestType(ReturnType, AssemblyName) + "|(" +
            string.Join(
                ",",
                ParameterTypes.Select(type => NormalizeManifestType(type, AssemblyName))) +
            ")";
    }

    private static int Main(string[] args)
    {
        if (args.Length != 4)
        {
            Console.Error.WriteLine(
                "usage: verifier <portable-runtime> <platform-runtime> <portable-stdlib> <platform-stdlib>");
            return 2;
        }

        PairLoadContext portableContext = new PairLoadContext(Path.GetDirectoryName(Path.GetFullPath(args[0])));
        PairLoadContext platformContext = new PairLoadContext(Path.GetDirectoryName(Path.GetFullPath(args[1])));
        try
        {
            Assembly[] portableAssemblies =
            {
                portableContext.LoadFromAssemblyPath(Path.GetFullPath(args[0])),
                portableContext.LoadFromAssemblyPath(Path.GetFullPath(args[2])),
            };
            Assembly[] platformAssemblies =
            {
                platformContext.LoadFromAssemblyPath(Path.GetFullPath(args[1])),
                platformContext.LoadFromAssemblyPath(Path.GetFullPath(args[3])),
            };
            Dictionary<string, SurfaceItem> portable = Capture(portableAssemblies);
            Dictionary<string, SurfaceItem> platform = Capture(platformAssemblies);
            Dictionary<string, ResourceItem> portableResources =
                CaptureResources(args[0], args[2]);
            Dictionary<string, ResourceItem> platformResources =
                CaptureResources(args[1], args[3]);
            if (portable.Count == 0)
                throw new InvalidOperationException("The portable CLR surface is empty.");
            if (!portableResources.Values.Any(resource => resource.Manifest != null))
                throw new InvalidOperationException(
                    "The portable pair has no embedded C# implementation manifest.");

            List<string> differences = Compare(portable, platform);
            differences.AddRange(CompareResources(portableResources, platformResources));
            HashSet<string> portableSatisfactions =
                CaptureSlotSatisfactions(
                    portableAssemblies,
                    portableResources,
                    differences,
                    "PORTABLE");
            HashSet<string> platformSatisfactions =
                CaptureSlotSatisfactions(
                    platformAssemblies,
                    platformResources,
                    differences,
                    "PLATFORM");
            differences.AddRange(CompareSlotContracts(portableResources, platformResources));
            differences.AddRange(
                CompareSlotSatisfactions(portableSatisfactions, platformSatisfactions));
            if (differences.Count != 0)
            {
                foreach (string difference in differences)
                    Console.Error.WriteLine(difference);
                return 1;
            }

            Console.WriteLine(
                "OK " + (
                    portable.Count +
                    portableResources.Count +
                    portableSatisfactions.Count)
                    .ToString(CultureInfo.InvariantCulture) +
                " SLOTS " +
                portableSatisfactions.Count.ToString(CultureInfo.InvariantCulture));
            return 0;
        }
        catch (Exception failure)
        {
            Console.Error.WriteLine(failure);
            return 3;
        }
        finally
        {
            portableContext.Unload();
            platformContext.Unload();
        }
    }

    private static Dictionary<string, SurfaceItem> Capture(params Assembly[] assemblies)
    {
        var result = new Dictionary<string, SurfaceItem>(StringComparer.Ordinal);
        foreach (Assembly assembly in assemblies.OrderBy(value => value.GetName().Name, StringComparer.Ordinal))
        {
            AddAttributes(
                result,
                "ASSEMBLY:" + assembly.GetName().Name,
                assembly.CustomAttributes.Where(attribute =>
                    attribute.AttributeType.FullName !=
                    "System.Runtime.Versioning.TargetFrameworkAttribute"),
                access: 3);
            foreach (Type type in assembly.GetTypes().OrderBy(TypeIdentity, StringComparer.Ordinal))
            {
                int typeAccess = TypeAccess(type);
                if (typeAccess == 0)
                    continue;

                string owner = TypeIdentity(type);
                TypeAttributes typeShape = type.Attributes & ~TypeAttributes.VisibilityMask;
                string typeKey =
                    "TYPE|" + owner + "|" + ((int)typeShape).ToString(CultureInfo.InvariantCulture);
                Add(result, new SurfaceItem(
                    typeKey,
                    typeAccess,
                    isMethod: false,
                    isAbstract: false,
                    isFinal: false));
                AddAttributes(result, typeKey, type.CustomAttributes, typeAccess);

                if (type.BaseType != null)
                    AddFact(result, "BASE|" + owner + "|" + TypeIdentity(type.BaseType), typeAccess);
                foreach (Type implemented in type.GetInterfaces().OrderBy(TypeIdentity, StringComparer.Ordinal))
                    AddFact(result, "INTERFACE|" + owner + "|" + TypeIdentity(implemented), typeAccess);
                AddGenericParameters(result, "TYPE_PARAMETER|" + owner, type.GetGenericArguments(), typeAccess);

                const BindingFlags declaredMembers = BindingFlags.Public | BindingFlags.NonPublic |
                    BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly;
                foreach (ConstructorInfo constructor in type.GetConstructors(declaredMembers))
                    AddMethod(result, owner, constructor);
                foreach (MethodInfo method in type.GetMethods(declaredMembers))
                    AddMethod(result, owner, method);
                foreach (FieldInfo field in type.GetFields(declaredMembers))
                    AddField(result, owner, field);
                foreach (PropertyInfo property in type.GetProperties(declaredMembers))
                    AddProperty(result, owner, property);
                foreach (EventInfo eventInfo in type.GetEvents(declaredMembers))
                    AddEvent(result, owner, eventInfo);
            }
        }
        return result;
    }

    private static Dictionary<string, ResourceItem> CaptureResources(params string[] assemblyPaths)
    {
        var result = new Dictionary<string, ResourceItem>(StringComparer.Ordinal);
        foreach (string assemblyPath in assemblyPaths)
        {
            using (FileStream stream = File.OpenRead(Path.GetFullPath(assemblyPath)))
            using (var peReader = new PEReader(stream))
            {
                if (!peReader.HasMetadata)
                    throw new BadImageFormatException(
                        "Assembly has no CLR metadata: " + assemblyPath);
                MetadataReader metadata = peReader.GetMetadataReader();
                if (!metadata.IsAssembly)
                    throw new BadImageFormatException(
                        "Managed module is not an assembly: " + assemblyPath);
                string assemblyName = metadata.GetString(
                    metadata.GetAssemblyDefinition().Name);
                foreach (ManifestResourceHandle handle in metadata.ManifestResources)
                {
                    ManifestResource resource = metadata.GetManifestResource(handle);
                    string resourceName = metadata.GetString(resource.Name);
                    string key = assemblyName + "|" + resourceName;
                    bool isEmbedded = resource.Implementation.IsNil;
                    byte[] bytes = isEmbedded
                        ? ReadEmbeddedResource(peReader, resource, resourceName)
                        : null;
                    CSharpManifestContract manifest =
                        resourceName == CSharpManifestResourceName
                            ? DecodeCSharpManifest(bytes ??
                                throw new BadImageFormatException(
                                    "The C# implementation manifest is not embedded."))
                            : null;
                    if (manifest != null &&
                        !string.Equals(
                            manifest.AssemblyName,
                            assemblyName,
                            StringComparison.Ordinal))
                        throw new BadImageFormatException(
                            "The C# implementation manifest names another assembly.");
                    if (result.ContainsKey(key))
                        throw new BadImageFormatException(
                            "Duplicate managed resource: " + key);
                    result.Add(
                        key,
                        new ResourceItem(
                            key,
                            resource.Attributes,
                            isEmbedded,
                            manifest));
                }
            }
        }
        return result;
    }

    private static byte[] ReadEmbeddedResource(
        PEReader peReader,
        ManifestResource resource,
        string resourceName)
    {
        if (peReader.PEHeaders.CorHeader == null)
            throw new BadImageFormatException(
                "Managed resource has no CLR resource directory: " + resourceName);
        DirectoryEntry directory = peReader.PEHeaders.CorHeader.ResourcesDirectory;
        int offset = checked((int)resource.Offset);
        if (directory.RelativeVirtualAddress == 0 ||
            directory.Size < sizeof(int) ||
            offset < 0 ||
            offset > directory.Size - sizeof(int))
            throw new BadImageFormatException(
                "Managed resource has an invalid location: " + resourceName);
        PEMemoryBlock block = peReader.GetSectionData(directory.RelativeVirtualAddress);
        if (block.Length < directory.Size)
            throw new BadImageFormatException(
                "Managed resource extends beyond its PE section: " + resourceName);
        BlobReader reader = block.GetReader(offset, directory.Size - offset);
        int resourceSize = reader.ReadInt32();
        if (resourceSize < 0 || resourceSize > reader.RemainingBytes)
            throw new BadImageFormatException(
                "Managed resource has an invalid size: " + resourceName);
        return reader.ReadBytes(resourceSize);
    }

    private static CSharpManifestContract DecodeCSharpManifest(byte[] resource)
    {
        if (resource.Length < CSharpManifestHeaderSize)
            throw new BadImageFormatException(
                "The C# implementation manifest resource is truncated.");
        for (int index = 0; index < CSharpManifestMagic.Length; index++)
        {
            if (resource[index] != CSharpManifestMagic[index])
                throw new BadImageFormatException(
                    "The C# implementation manifest has invalid magic.");
        }
        int schemaVersion = ReadInt32LittleEndian(resource, 8);
        int payloadSize = ReadInt32LittleEndian(resource, 12);
        if (payloadSize < 0 ||
            payloadSize > MaximumCSharpManifestPayloadBytes ||
            resource.Length != CSharpManifestHeaderSize + payloadSize)
            throw new BadImageFormatException(
                "The C# implementation manifest has an invalid payload size.");
        var payload = new byte[payloadSize];
        Buffer.BlockCopy(resource, CSharpManifestHeaderSize, payload, 0, payloadSize);
        using (SHA256 sha256 = SHA256.Create())
        {
            byte[] digest = sha256.ComputeHash(payload);
            for (int index = 0; index < digest.Length; index++)
            {
                if (resource[16 + index] != digest[index])
                    throw new BadImageFormatException(
                        "The C# implementation manifest payload hash does not match.");
            }
        }

        string[] records = StrictUtf8.GetString(payload)
            .Split(new[] { '\n' }, StringSplitOptions.RemoveEmptyEntries);
        string[] header = records
            .Where(record => record.StartsWith("H\t", StringComparison.Ordinal))
            .Select(record => record.Split('\t'))
            .Single();
        if (header.Length != 5)
            throw new BadImageFormatException(
                "The C# implementation manifest header has invalid arity.");
        int payloadSchema = int.Parse(DecodeManifestField(header[1]), CultureInfo.InvariantCulture);
        if (payloadSchema != schemaVersion)
            throw new BadImageFormatException(
                "The C# implementation manifest schemas do not match.");
        string assemblyName = DecodeManifestField(header[2]);
        string targetProfile = DecodeManifestField(header[3]);
        if (targetProfile != "net48" &&
            targetProfile != "netstandard2.0" &&
            targetProfile != "net10.0")
            throw new BadImageFormatException(
                "The C# implementation manifest has an unknown profile.");
        string logicalIdentityScheme = DecodeManifestField(header[4]);
        if (!string.Equals(
                logicalIdentityScheme,
                CSharpManifestLogicalIdentityScheme,
                StringComparison.Ordinal))
            throw new BadImageFormatException(
                "The C# implementation manifest has an unknown logical-identity scheme.");
        var logicalDeclarations = new HashSet<string>(StringComparer.Ordinal);
        var defaultKinds = new Dictionary<string, string>(StringComparer.Ordinal);
        var semanticBodyViews = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (string record in records)
        {
            string[] fields = record.Split('\t');
            string logicalKey = null;
            if (fields[0] == "I" && fields.Length == 8)
                logicalKey = DecodeManifestField(fields[1]);
            else if (fields[0] == "M" && fields.Length == 13)
            {
                logicalKey = DecodeManifestField(fields[2]);
                defaultKinds.Add(logicalKey, DecodeManifestField(fields[6]));
                semanticBodyViews.Add(
                    logicalKey,
                    DecodeNullableManifestField(fields[7]));
            }
            else if (fields[0] == "X" && fields.Length == 8)
                logicalKey = DecodeManifestField(fields[2]);
            if (logicalKey != null && !logicalDeclarations.Add(logicalKey))
                throw new BadImageFormatException(
                    "Duplicate C# implementation logical declaration: " + logicalKey);
        }
        var slots = new List<CSharpManifestSlot>();
        foreach (string record in records)
        {
            string[] fields = record.Split('\t');
            if ((fields[0] != "S" && fields[0] != "Y") || fields.Length != 9)
                continue;
            string logicalKey = DecodeManifestField(fields[1]);
            string role = DecodeManifestField(fields[2]);
            string ownerPath = DecodeManifestField(fields[3]);
            string methodName = DecodeManifestField(fields[4]);
            int genericArity = int.Parse(
                DecodeManifestField(fields[6]),
                CultureInfo.InvariantCulture);
            string returnType = DecodeManifestField(fields[7]);
            string parameters = DecodeManifestField(fields[8]);
            slots.Add(
                new CSharpManifestSlot(
                    assemblyName,
                    logicalKey,
                    role,
                    ownerPath,
                    methodName,
                    genericArity,
                    returnType,
                    parameters.Length == 0
                        ? Array.Empty<string>()
                        : parameters.Split('\0'),
                    defaultKinds.TryGetValue(logicalKey, out string defaultKind)
                        ? defaultKind
                        : null,
                    semanticBodyViews.TryGetValue(logicalKey, out string semanticBodyView)
                        ? semanticBodyView
                        : null));
        }
        foreach (IGrouping<string, CSharpManifestSlot> duplicate in slots
            .GroupBy(slot => slot.SemanticKey, StringComparer.Ordinal)
            .Where(group => group.Count() != 1))
            throw new BadImageFormatException(
                "Duplicate C# implementation semantic slot: " + duplicate.Key);
        return new CSharpManifestContract(
            schemaVersion,
            assemblyName,
            targetProfile,
            logicalIdentityScheme,
            logicalDeclarations,
            slots);
    }

    private static string DecodeManifestField(string encoded)
    {
        if (encoded == "~")
            throw new BadImageFormatException(
                "A required C# implementation manifest field is null.");
        string base64 = encoded.Replace('-', '+').Replace('_', '/');
        base64 = base64.PadRight(base64.Length + ((4 - base64.Length % 4) % 4), '=');
        return StrictUtf8.GetString(Convert.FromBase64String(base64));
    }

    private static string DecodeNullableManifestField(string encoded) =>
        encoded == "~" ? null : DecodeManifestField(encoded);

    private static int ReadInt32LittleEndian(byte[] bytes, int offset) =>
        bytes[offset] |
        (bytes[offset + 1] << 8) |
        (bytes[offset + 2] << 16) |
        (bytes[offset + 3] << 24);

    private static List<string> CompareResources(
        Dictionary<string, ResourceItem> portable,
        Dictionary<string, ResourceItem> platform)
    {
        var differences = new List<string>();
        foreach (ResourceItem required in portable.Values
            .OrderBy(resource => resource.Key, StringComparer.Ordinal))
        {
            if ((required.Attributes & ManifestResourceAttributes.Public) == 0)
                continue;
            if (!platform.TryGetValue(required.Key, out ResourceItem actual))
            {
                differences.Add("MISSING RESOURCE|" + required.Key);
                continue;
            }
            if ((required.Attributes & ManifestResourceAttributes.Public) != 0 &&
                (actual.Attributes & ManifestResourceAttributes.Public) == 0)
                differences.Add("NARROWED RESOURCE|" + required.Key);
            if (required.IsEmbedded && !actual.IsEmbedded)
                differences.Add("EXTERNALIZED RESOURCE|" + required.Key);
            if (required.Manifest == null)
                continue;
            if (actual.Manifest == null)
            {
                differences.Add("MISSING MANIFEST CONTRACT|" + required.Key);
                continue;
            }
            if (required.Manifest.SchemaVersion != actual.Manifest.SchemaVersion)
                differences.Add("CHANGED MANIFEST SCHEMA|" + required.Key);
            if (!string.Equals(
                    required.Manifest.AssemblyName,
                    actual.Manifest.AssemblyName,
                    StringComparison.Ordinal))
                differences.Add("CHANGED MANIFEST ASSEMBLY|" + required.Key);
            if (!string.Equals(
                    required.Manifest.LogicalIdentityScheme,
                    actual.Manifest.LogicalIdentityScheme,
                    StringComparison.Ordinal))
                differences.Add("CHANGED MANIFEST IDENTITY SCHEME|" + required.Key);
            foreach (string logicalDeclaration in required.Manifest.LogicalDeclarations
                .OrderBy(value => value, StringComparer.Ordinal))
            {
                if (!actual.Manifest.LogicalDeclarations.Contains(logicalDeclaration))
                    differences.Add(
                        "MISSING MANIFEST DECLARATION|" + required.Key + "|" +
                        logicalDeclaration);
            }
        }
        return differences;
    }

    private static List<string> CompareSlotContracts(
        Dictionary<string, ResourceItem> portable,
        Dictionary<string, ResourceItem> platform)
    {
        var differences = new List<string>();
        foreach (ResourceItem requiredResource in portable.Values
            .Where(resource => resource.Manifest != null)
            .OrderBy(resource => resource.Key, StringComparer.Ordinal))
        {
            if (!platform.TryGetValue(requiredResource.Key, out ResourceItem actualResource) ||
                actualResource.Manifest == null)
                continue;
            Dictionary<string, CSharpManifestSlot> actualSlots = actualResource.Manifest.Slots
                .ToDictionary(slot => slot.SemanticKey, StringComparer.Ordinal);
            foreach (CSharpManifestSlot required in requiredResource.Manifest.Slots
                .OrderBy(slot => slot.SemanticKey, StringComparer.Ordinal))
            {
                if (!actualSlots.TryGetValue(required.SemanticKey, out CSharpManifestSlot actual))
                {
                    differences.Add("MISSING SEMANTIC SLOT|" + required.SemanticKey);
                    continue;
                }
                if (!string.Equals(
                        required.LocatorKey,
                        actual.LocatorKey,
                        StringComparison.Ordinal))
                    differences.Add(
                        "CHANGED SEMANTIC SLOT|" + required.SemanticKey +
                        "|portable=" + required.LocatorKey +
                        "|platform=" + actual.LocatorKey);
            }
        }
        return differences;
    }

    private static HashSet<string> CaptureSlotSatisfactions(
        Assembly[] assemblies,
        Dictionary<string, ResourceItem> resources,
        List<string> differences,
        string side)
    {
        Dictionary<string, CSharpManifestSlot> slotsByMethod =
            ResolveManifestSlots(assemblies, resources, differences, side);
        var result = new HashSet<string>(StringComparer.Ordinal);
        foreach (Assembly assembly in assemblies.OrderBy(
            value => value.GetName().Name,
            StringComparer.Ordinal))
        {
            foreach (Type type in assembly.GetTypes().OrderBy(TypeIdentity, StringComparer.Ordinal))
            {
                if (type.IsInterface)
                    continue;
                bool externallyConsumable = TypeAccess(type) != 0;
                foreach (Type implemented in type.GetInterfaces()
                    .OrderBy(TypeIdentity, StringComparer.Ordinal))
                {
                    InterfaceMapping mapping;
                    try
                    {
                        mapping = type.GetInterfaceMap(implemented);
                    }
                    catch (Exception failure)
                    {
                        if (!type.IsAbstract)
                            differences.Add(
                                "UNRESOLVED INTERFACE MAP|" + side + "|" +
                                TypeIdentity(type) + "|" + TypeIdentity(implemented) +
                                "|" + failure.GetType().FullName);
                        continue;
                    }
                    for (int index = 0; index < mapping.InterfaceMethods.Length; index++)
                    {
                        MethodInfo declaration = mapping.InterfaceMethods[index];
                        if (!slotsByMethod.TryGetValue(
                                MethodDefinitionKey(declaration),
                                out CSharpManifestSlot slot) ||
                            slot.Role == "HELPER")
                            continue;
                        MethodInfo target = mapping.TargetMethods[index];
                        string signature = ConstructedMethodShape(declaration);
                        string key =
                            TypeIdentity(type) + "|" + slot.SemanticKey + "|" + signature;
                        if (target == null || target.IsAbstract)
                        {
                            if (!type.IsAbstract)
                                differences.Add(
                                    "MISSING SLOT SATISFIER|" + side + "|" + key);
                            continue;
                        }
                        if (!externallyConsumable)
                            continue;
                        if (!result.Add(key))
                            differences.Add(
                                "AMBIGUOUS SLOT SATISFIER|" + side + "|" + key);
                    }
                }
            }
        }
        return result;
    }

    private static Dictionary<string, CSharpManifestSlot> ResolveManifestSlots(
        Assembly[] assemblies,
        Dictionary<string, ResourceItem> resources,
        List<string> differences,
        string side)
    {
        Dictionary<string, Assembly> assembliesByName = assemblies.ToDictionary(
            assembly => assembly.GetName().Name,
            StringComparer.Ordinal);
        var result = new Dictionary<string, CSharpManifestSlot>(StringComparer.Ordinal);
        foreach (CSharpManifestContract manifest in resources.Values
            .Where(resource => resource.Manifest != null)
            .Select(resource => resource.Manifest)
            .OrderBy(value => value.AssemblyName, StringComparer.Ordinal))
        {
            if (!assembliesByName.TryGetValue(manifest.AssemblyName, out Assembly assembly))
            {
                differences.Add(
                    "MISSING MANIFEST ASSEMBLY|" + side + "|" + manifest.AssemblyName);
                continue;
            }
            foreach (CSharpManifestSlot slot in manifest.Slots
                .OrderBy(value => value.SemanticKey, StringComparer.Ordinal))
            {
                MethodInfo[] candidates = assembly.GetTypes()
                    .Where(type =>
                        string.Equals(TypePath(type), slot.OwnerPath, StringComparison.Ordinal))
                    .SelectMany(type => type.GetMethods(
                        BindingFlags.Public |
                        BindingFlags.NonPublic |
                        BindingFlags.Instance |
                        BindingFlags.Static |
                        BindingFlags.DeclaredOnly))
                    .Where(method => ManifestSlotMatches(method, slot))
                    .ToArray();
                if (candidates.Length == 0)
                {
                    differences.Add(
                        "UNRESOLVED MANIFEST SLOT|" + side + "|" +
                        slot.SemanticKey + "|" + slot.LocatorKey);
                    continue;
                }
                if (candidates.Length != 1)
                {
                    differences.Add(
                        "AMBIGUOUS MANIFEST SLOT|" + side + "|" +
                        slot.SemanticKey + "|" + slot.LocatorKey);
                    continue;
                }
                MethodInfo method = candidates[0];
                if ((slot.DefaultKind == "PORTABLE_HELPER" ||
                        slot.DefaultKind == "DIM_WITH_HELPER") &&
                    slot.Role == "HELPER" &&
                    method.IsAbstract)
                    differences.Add(
                        "MISSING HELPER BODY|" + side + "|" + slot.SemanticKey);
                if (slot.DefaultKind == "DIM_WITH_HELPER" &&
                    slot.SemanticBodyView == null)
                    differences.Add(
                        "MISSING SEMANTIC BODY VIEW|" + side + "|" + slot.SemanticKey);
                if (slot.DefaultKind == "DIM_WITH_HELPER" &&
                    string.Equals(
                        slot.Role,
                        slot.SemanticBodyView,
                        StringComparison.Ordinal) &&
                    string.Equals(manifest.TargetProfile, "net10.0", StringComparison.Ordinal) &&
                    method.IsAbstract)
                    differences.Add(
                        "MISSING DIM BODY|" + side + "|" + slot.SemanticKey);
                string methodKey = MethodDefinitionKey(method);
                if (result.TryGetValue(methodKey, out CSharpManifestSlot previous))
                    differences.Add(
                        "AMBIGUOUS LOGICAL METHOD|" + side + "|" + methodKey +
                        "|" + previous.SemanticKey + "|" + slot.SemanticKey);
                else
                    result.Add(methodKey, slot);
            }
        }
        return result;
    }

    private static bool ManifestSlotMatches(MethodInfo method, CSharpManifestSlot slot)
    {
        if (!string.Equals(method.Name, slot.MethodName, StringComparison.Ordinal))
            return false;
        if (method.GetGenericArguments().Length != slot.GenericArity)
            return false;
        string assemblyName = method.Module.Assembly.GetName().Name;
        if (!string.Equals(
                RenderIlType(method.ReturnType, assemblyName),
                NormalizeManifestType(slot.ReturnType, assemblyName),
                StringComparison.Ordinal))
            return false;
        ParameterInfo[] parameters = method.GetParameters();
        if (parameters.Length != slot.ParameterTypes.Length)
            return false;
        for (int index = 0; index < parameters.Length; index++)
        {
            if (!string.Equals(
                    RenderIlType(parameters[index].ParameterType, assemblyName),
                    NormalizeManifestType(slot.ParameterTypes[index], assemblyName),
                    StringComparison.Ordinal))
                return false;
        }
        return true;
    }

    private static string NormalizeManifestType(string type, string currentAssembly) =>
        type.Replace("[" + currentAssembly + "]", "");

    private static string RenderIlType(Type type, string currentAssembly)
    {
        if (type.IsGenericParameter)
            return (type.DeclaringMethod == null ? "!" : "!!") +
                type.GenericParameterPosition.ToString(CultureInfo.InvariantCulture);
        if (type.IsByRef)
            return RenderIlType(type.GetElementType(), currentAssembly) + "&";
        if (type.IsPointer)
            return RenderIlType(type.GetElementType(), currentAssembly) + "*";
        if (type.IsArray)
        {
            if (type.GetArrayRank() == 1)
                return RenderIlType(type.GetElementType(), currentAssembly) + "[]";
            return RenderIlType(type.GetElementType(), currentAssembly) + "[" +
                new string(',', type.GetArrayRank() - 1) + "]";
        }
        if (type == typeof(void))
            return "void";
        if (type == typeof(bool))
            return "bool";
        if (type == typeof(byte))
            return "uint8";
        if (type == typeof(sbyte))
            return "int8";
        if (type == typeof(char))
            return "char";
        if (type == typeof(short))
            return "int16";
        if (type == typeof(ushort))
            return "uint16";
        if (type == typeof(int))
            return "int32";
        if (type == typeof(uint))
            return "uint32";
        if (type == typeof(long))
            return "int64";
        if (type == typeof(ulong))
            return "uint64";
        if (type == typeof(float))
            return "float32";
        if (type == typeof(double))
            return "float64";
        if (type == typeof(string))
            return "string";
        if (type == typeof(object))
            return "object";
        if (type == typeof(IntPtr))
            return "native int";
        if (type == typeof(UIntPtr))
            return "native uint";
        Type definition = type.IsGenericType ? type.GetGenericTypeDefinition() : type;
        string assemblyName = definition.Assembly.GetName().Name;
        string scope = string.Equals(
            assemblyName,
            currentAssembly,
            StringComparison.Ordinal)
                ? ""
                : "[" + assemblyName + "]";
        string result =
            (definition.IsValueType ? "valuetype " : "class ") +
            scope +
            string.Join(
                "/",
                TypePath(definition).Split('\0').Select(IlIdentifier));
        if (type.IsGenericType && !type.IsGenericTypeDefinition)
            result += "<" + string.Join(
                ",",
                type.GetGenericArguments().Select(argument =>
                    RenderIlType(argument, currentAssembly))) + ">";
        return result;
    }

    private static string IlIdentifier(string value) =>
        "'" + value.Replace("\\", "\\\\").Replace("'", "\\'") + "'";

    private static string TypePath(Type type)
    {
        if (type.DeclaringType != null)
            return TypePath(type.DeclaringType) + "\0" + type.Name;
        return string.IsNullOrEmpty(type.Namespace)
            ? type.Name
            : type.Namespace + "." + type.Name;
    }

    private static string MethodDefinitionKey(MethodInfo method) =>
        method.Module.Assembly.GetName().Name + "|" +
        method.MetadataToken.ToString(CultureInfo.InvariantCulture);

    private static string ConstructedMethodShape(MethodInfo method) =>
        TypeIdentity(method.DeclaringType) + "|" + method.Name + "|" +
        method.GetGenericArguments().Length.ToString(CultureInfo.InvariantCulture) +
        "|(" + string.Join(
            ",",
            method.GetParameters().Select(parameter =>
                TypeIdentity(parameter.ParameterType))) + ")->" +
        TypeIdentity(method.ReturnType);

    private static List<string> CompareSlotSatisfactions(
        HashSet<string> portable,
        HashSet<string> platform)
    {
        var differences = new List<string>();
        foreach (string required in portable.OrderBy(value => value, StringComparer.Ordinal))
        {
            if (!platform.Contains(required))
                differences.Add("MISSING SLOT SATISFACTION|" + required);
        }
        return differences;
    }

    private static List<string> Compare(
        Dictionary<string, SurfaceItem> portable,
        Dictionary<string, SurfaceItem> platform)
    {
        var differences = new List<string>();
        foreach (SurfaceItem required in portable.Values.OrderBy(item => item.Key, StringComparer.Ordinal))
        {
            if (!platform.TryGetValue(required.Key, out SurfaceItem actual))
            {
                differences.Add("MISSING " + required.Key);
                continue;
            }
            if (actual.Access < required.Access)
                differences.Add("NARROWED " + required.Key);
            if (required.IsMethod && !required.IsAbstract && actual.IsAbstract)
                differences.Add("ABSTRACTED " + required.Key);
            if (required.IsMethod && !required.IsFinal && actual.IsFinal)
                differences.Add("SEALED " + required.Key);
        }
        return differences;
    }

    private static void AddMethod(Dictionary<string, SurfaceItem> surface, string owner, MethodBase method)
    {
        int access = MethodAccess(method);
        if (access == 0)
            return;

        MethodAttributes shape = method.Attributes & (
            MethodAttributes.Static |
            MethodAttributes.Virtual |
            MethodAttributes.NewSlot |
            MethodAttributes.HideBySig |
            MethodAttributes.SpecialName |
            MethodAttributes.RTSpecialName |
            MethodAttributes.PinvokeImpl);
        string genericShape = GenericParameterShape(method.IsGenericMethod
            ? method.GetGenericArguments()
            : Type.EmptyTypes);
        string parameters = string.Join(",", method.GetParameters().Select(ParameterShape));
        string result = method is MethodInfo methodInfo ? ParameterShape(methodInfo.ReturnParameter) : "void";
        string key = "METHOD|" + owner + "|" + method.Name + "|" +
            ((int)shape).ToString(CultureInfo.InvariantCulture) + "|" +
            ((int)method.CallingConvention).ToString(CultureInfo.InvariantCulture) + "|" +
            genericShape + "|(" + parameters + ")->" + result;
        Add(surface, new SurfaceItem(key, access, isMethod: true, method.IsAbstract, method.IsFinal));
        AddAttributes(surface, key, method.CustomAttributes, access);
        ParameterInfo[] methodParameters = method.GetParameters();
        for (int index = 0; index < methodParameters.Length; index++)
            AddAttributes(surface, key + "|PARAMETER:" + index, methodParameters[index].CustomAttributes, access);
        if (method is MethodInfo returnMethod)
            AddAttributes(surface, key + "|RETURN", returnMethod.ReturnParameter.CustomAttributes, access);
        if (method.IsGenericMethod)
        {
            Type[] genericParameters = method.GetGenericArguments();
            for (int index = 0; index < genericParameters.Length; index++)
                AddAttributes(surface, key + "|TYPE_PARAMETER:" + index, genericParameters[index].CustomAttributes, access);
        }
    }

    private static void AddField(Dictionary<string, SurfaceItem> surface, string owner, FieldInfo field)
    {
        int access = FieldAccess(field);
        if (access == 0)
            return;
        FieldAttributes shape = field.Attributes & ~FieldAttributes.FieldAccessMask;
        string constant = field.IsLiteral ? "|" + ConstantShape(field.GetRawConstantValue()) : "";
        string key = "FIELD|" + owner + "|" + field.Name + "|" +
                ((int)shape).ToString(CultureInfo.InvariantCulture) + "|" +
                TypeIdentity(field.FieldType) + CustomModifiers(field) + constant;
        AddFact(surface, key, access);
        AddAttributes(surface, key, field.CustomAttributes, access);
    }

    private static void AddProperty(Dictionary<string, SurfaceItem> surface, string owner, PropertyInfo property)
    {
        int access = new[] { property.GetMethod, property.SetMethod }
            .Where(method => method != null)
            .Select(MethodAccess)
            .DefaultIfEmpty(0)
            .Max();
        if (access == 0)
            return;
        string indices = string.Join(",", property.GetIndexParameters().Select(ParameterShape));
        string key = "PROPERTY|" + owner + "|" + property.Name + "|" +
                ((int)property.Attributes).ToString(CultureInfo.InvariantCulture) + "|(" + indices + ")->" +
                TypeIdentity(property.PropertyType) + CustomModifiers(property);
        AddFact(surface, key, access);
        AddAttributes(surface, key, property.CustomAttributes, access);
    }

    private static void AddEvent(Dictionary<string, SurfaceItem> surface, string owner, EventInfo eventInfo)
    {
        int access = new[] { eventInfo.AddMethod, eventInfo.RemoveMethod, eventInfo.RaiseMethod }
            .Where(method => method != null)
            .Select(MethodAccess)
            .DefaultIfEmpty(0)
            .Max();
        if (access == 0)
            return;
        string key = "EVENT|" + owner + "|" + eventInfo.Name + "|" +
                ((int)eventInfo.Attributes).ToString(CultureInfo.InvariantCulture) + "|" +
                TypeIdentity(eventInfo.EventHandlerType);
        AddFact(surface, key, access);
        AddAttributes(surface, key, eventInfo.CustomAttributes, access);
    }

    private static void AddGenericParameters(
        Dictionary<string, SurfaceItem> surface,
        string owner,
        Type[] parameters,
        int access)
    {
        for (int index = 0; index < parameters.Length; index++)
        {
            string key = owner + "|" + index.ToString(CultureInfo.InvariantCulture) + "|" +
                GenericParameterShape(parameters[index]);
            AddFact(surface, key, access);
            AddAttributes(surface, key, parameters[index].CustomAttributes, access);
        }
    }

    private static string GenericParameterShape(Type parameter)
    {
        string constraints = string.Join(",", parameter.GetGenericParameterConstraints()
            .Select(TypeIdentity)
            .OrderBy(value => value, StringComparer.Ordinal));
        return ((int)parameter.GenericParameterAttributes).ToString(CultureInfo.InvariantCulture) +
            "[" + constraints + "]";
    }

    private static string GenericParameterShape(Type[] parameters) =>
        string.Join(";", parameters.Select(GenericParameterShape));

    private static string ParameterShape(ParameterInfo parameter)
    {
        string constant = (parameter.Attributes & ParameterAttributes.HasDefault) != 0
            ? "=" + ConstantShape(parameter.RawDefaultValue)
            : "";
        return ((int)parameter.Attributes).ToString(CultureInfo.InvariantCulture) + ":" +
            TypeIdentity(parameter.ParameterType) + CustomModifiers(parameter) + constant;
    }

    private static string ConstantShape(object value)
    {
        if (value == null)
            return "null";
        return value.GetType().FullName + ":" + Convert.ToString(value, CultureInfo.InvariantCulture);
    }

    private static string CustomModifiers(ParameterInfo parameter) =>
        CustomModifiers(parameter.GetRequiredCustomModifiers(), parameter.GetOptionalCustomModifiers());

    private static string CustomModifiers(FieldInfo field) =>
        CustomModifiers(field.GetRequiredCustomModifiers(), field.GetOptionalCustomModifiers());

    private static string CustomModifiers(PropertyInfo property) =>
        CustomModifiers(property.GetRequiredCustomModifiers(), property.GetOptionalCustomModifiers());

    private static string CustomModifiers(Type[] required, Type[] optional) =>
        "|req[" + string.Join(",", required.Select(TypeIdentity)) + "]|opt[" +
        string.Join(",", optional.Select(TypeIdentity)) + "]";

    private static void AddAttributes(
        Dictionary<string, SurfaceItem> surface,
        string owner,
        IEnumerable<CustomAttributeData> attributes,
        int access)
    {
        foreach (IGrouping<string, string> group in attributes
            .Select(CustomAttributeShape)
            .GroupBy(value => value, StringComparer.Ordinal)
            .OrderBy(value => value.Key, StringComparer.Ordinal))
        {
            AddFact(
                surface,
                "ATTRIBUTE|" + owner + "|" + group.Count().ToString(CultureInfo.InvariantCulture) +
                    "|" + group.Key,
                access);
        }
    }

    private static string CustomAttributeShape(CustomAttributeData attribute)
    {
        string constructorArguments = string.Join(",", attribute.ConstructorArguments.Select(AttributeArgumentShape));
        string namedArguments = string.Join(",", attribute.NamedArguments
            .OrderBy(argument => argument.MemberName, StringComparer.Ordinal)
            .ThenBy(argument => argument.IsField)
            .Select(argument => (argument.IsField ? "field:" : "property:") +
                Escape(argument.MemberName) + "=" + AttributeArgumentShape(argument.TypedValue)));
        return TypeIdentity(attribute.AttributeType) + "(" + constructorArguments + "){" + namedArguments + "}";
    }

    private static string AttributeArgumentShape(CustomAttributeTypedArgument argument)
    {
        string value;
        if (argument.Value == null)
        {
            value = "null";
        }
        else if (argument.Value is Type type)
        {
            value = TypeIdentity(type);
        }
        else if (argument.Value is IEnumerable<CustomAttributeTypedArgument> values)
        {
            value = "[" + string.Join(",", values.Select(AttributeArgumentShape)) + "]";
        }
        else
        {
            value = Escape(Convert.ToString(argument.Value, CultureInfo.InvariantCulture));
        }
        return TypeIdentity(argument.ArgumentType) + "=" + value;
    }

    private static string Escape(string value) =>
        value.Replace("\\", "\\\\").Replace("|", "\\|").Replace(",", "\\,");

    private static string TypeIdentity(Type type)
    {
        if (type == null)
            return "<null>";
        if (type.IsGenericParameter)
            return (type.DeclaringMethod == null ? "!" : "!!") +
                type.GenericParameterPosition.ToString(CultureInfo.InvariantCulture);
        if (type.IsByRef)
            return TypeIdentity(type.GetElementType()) + "&";
        if (type.IsPointer)
            return TypeIdentity(type.GetElementType()) + "*";
        if (type.IsArray)
            return TypeIdentity(type.GetElementType()) + "[" + new string(',', type.GetArrayRank() - 1) + "]";
        if (type.IsGenericType)
        {
            Type definition = type.GetGenericTypeDefinition();
            return NamedTypeIdentity(definition) + "<" +
                string.Join(",", type.GetGenericArguments().Select(TypeIdentity)) + ">";
        }
        return NamedTypeIdentity(type);
    }

    private static string NamedTypeIdentity(Type type) =>
        type.Assembly.GetName().Name + ":" + type.FullName;

    private static int TypeAccess(Type type)
    {
        int ownAccess;
        if (!type.IsNested)
            ownAccess = type.IsPublic ? 3 : 0;
        else if (type.IsNestedPublic)
            ownAccess = 3;
        else if (type.IsNestedFamily || type.IsNestedFamORAssem)
            ownAccess = 2;
        else
            ownAccess = 0;
        if (ownAccess == 0 || type.DeclaringType == null)
            return ownAccess;
        return Math.Min(ownAccess, TypeAccess(type.DeclaringType));
    }

    private static int MethodAccess(MethodBase method)
    {
        if (method.IsPublic)
            return 3;
        if (method.IsFamily || method.IsFamilyOrAssembly)
            return 2;
        return 0;
    }

    private static int FieldAccess(FieldInfo field)
    {
        if (field.IsPublic)
            return 3;
        if (field.IsFamily || field.IsFamilyOrAssembly)
            return 2;
        return 0;
    }

    private static void AddFact(Dictionary<string, SurfaceItem> surface, string key, int access) =>
        Add(surface, new SurfaceItem(key, access, isMethod: false, isAbstract: false, isFinal: false));

    private static void Add(Dictionary<string, SurfaceItem> surface, SurfaceItem item)
    {
        if (surface.ContainsKey(item.Key))
            throw new InvalidOperationException("Duplicate normalized surface item: " + item.Key);
        surface.Add(item.Key, item);
    }
}
