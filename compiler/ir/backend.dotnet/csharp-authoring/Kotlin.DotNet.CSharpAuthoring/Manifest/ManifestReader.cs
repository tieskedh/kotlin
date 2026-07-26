/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Globalization;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using Microsoft.CodeAnalysis;

namespace Kotlin.DotNet.CSharpAuthoring.Manifest;

internal static class ManifestReader
{
    internal const int CurrentSchemaVersion = 7;
    internal const string AssemblyMetadataKey = "Kotlin.CSharpImplementationManifest";

    private const string LogicalIdentityScheme = "kotlin-public-id-signature-legacy-v1";
    private const int MaximumChunkCount = 1_024;
    private const int MaximumEncodedPayloadCharacters = 8 * 1_024 * 1_024;
    private const int MaximumDecodedPayloadBytes = 4 * 1_024 * 1_024;
    private const int MaximumRecordCount = 50_000;
    private const int MaximumFieldCharacters = 65_536;
    private const char ListSeparator = '\0';
    private const char TypeParameterSeparator = '\u0001';
    private static readonly Encoding StrictUtf8 = new UTF8Encoding(
        encoderShouldEmitUTF8Identifier: false,
        throwOnInvalidBytes: true);

    internal static KotlinManifestSet Read(Compilation compilation)
    {
        var references = ImmutableArray.CreateBuilder<KotlinManifestReference>();
        var problems = ImmutableArray.CreateBuilder<KotlinManifestProblem>();
        foreach (MetadataReference reference in compilation.References)
        {
            if (!(compilation.GetAssemblyOrModuleSymbol(reference) is IAssemblySymbol assembly))
                continue;

            ImmutableArray<KeyValuePair<string, string>> metadata;
            try
            {
                metadata = ReadAssemblyMetadata(assembly);
            }
            catch (ManifestFormatException failure)
            {
                problems.Add(new KotlinManifestProblem(assembly, false, failure.Message));
                continue;
            }

            if (!metadata.Any(entry =>
                    string.Equals(entry.Key, AssemblyMetadataKey, StringComparison.Ordinal)))
                continue;

            try
            {
                KotlinCSharpManifest manifest = DecodeAssemblyMetadata(metadata);
                if (!string.Equals(
                        manifest.AssemblyName,
                        assembly.Identity.Name,
                        StringComparison.OrdinalIgnoreCase))
                    throw new ManifestFormatException(
                        $"Manifest assembly name '{manifest.AssemblyName}' does not match " +
                        $"CLR assembly '{assembly.Identity.Name}'.");
                references.Add(new KotlinManifestReference(assembly, manifest));
            }
            catch (ManifestVersionException failure)
            {
                problems.Add(new KotlinManifestProblem(assembly, true, failure.Message));
            }
            catch (ManifestFormatException failure)
            {
                problems.Add(new KotlinManifestProblem(assembly, false, failure.Message));
            }
        }

        return new KotlinManifestSet(references.ToImmutable(), problems.ToImmutable());
    }

    private static ImmutableArray<KeyValuePair<string, string>> ReadAssemblyMetadata(
        IAssemblySymbol assembly)
    {
        var result = ImmutableArray.CreateBuilder<KeyValuePair<string, string>>();
        foreach (AttributeData attribute in assembly.GetAttributes())
        {
            if (!string.Equals(
                    attribute.AttributeClass?.ToDisplayString(),
                    "System.Reflection.AssemblyMetadataAttribute",
                    StringComparison.Ordinal))
                continue;
            if (attribute.ConstructorArguments.Length != 2 ||
                !(attribute.ConstructorArguments[0].Value is string key) ||
                !(attribute.ConstructorArguments[1].Value is string value))
                throw new ManifestFormatException(
                    $"Assembly '{assembly.Identity.Name}' has a malformed AssemblyMetadataAttribute.");
            if (key == AssemblyMetadataKey ||
                key.StartsWith(AssemblyMetadataKey + ".", StringComparison.Ordinal))
                result.Add(new KeyValuePair<string, string>(key, value));
        }

        return result.ToImmutable();
    }

    private static KotlinCSharpManifest DecodeAssemblyMetadata(
        ImmutableArray<KeyValuePair<string, string>> metadata)
    {
        var byKey = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (KeyValuePair<string, string> entry in metadata)
        {
            if (byKey.ContainsKey(entry.Key))
                throw new ManifestFormatException(
                    $"Duplicate C# implementation assembly metadata key '{entry.Key}'.");
            byKey.Add(entry.Key, entry.Value);
        }

        if (!byKey.TryGetValue(AssemblyMetadataKey, out string marker))
            throw new ManifestFormatException("Assembly has no C# implementation manifest.");
        byKey.Remove(AssemblyMetadataKey);

        string[] markerFields = marker.Split(':');
        if (markerFields.Length != 3)
            throw new ManifestFormatException("C# implementation manifest marker is malformed.");
        if (!int.TryParse(
                markerFields[0],
                NumberStyles.None,
                CultureInfo.InvariantCulture,
                out int schemaVersion))
            throw new ManifestFormatException(
                "C# implementation manifest marker has no numeric schema.");
        if (schemaVersion != CurrentSchemaVersion)
            throw new ManifestVersionException(
                $"Assembly manifest schema {schemaVersion} is incompatible with " +
                $"Kotlin C# tooling schema {CurrentSchemaVersion}.");
        if (!int.TryParse(
                markerFields[1],
                NumberStyles.None,
                CultureInfo.InvariantCulture,
                out int chunkCount))
            throw new ManifestFormatException(
                "C# implementation manifest marker has no numeric chunk count.");
        if (chunkCount < 1 || chunkCount > MaximumChunkCount)
            throw new ManifestFormatException(
                $"C# implementation manifest chunk count {chunkCount} exceeds the supported limit.");
        if (markerFields[2].Length != 64 || markerFields[2].Any(character =>
                !((character >= '0' && character <= '9') ||
                  (character >= 'a' && character <= 'f'))))
            throw new ManifestFormatException(
                "C# implementation manifest marker has an invalid SHA-256 digest.");

        var payload = new StringBuilder();
        for (int index = 0; index < chunkCount; index++)
        {
            string key = AssemblyMetadataKey + "." +
                index.ToString("D4", CultureInfo.InvariantCulture);
            if (!byKey.TryGetValue(key, out string chunk))
                throw new ManifestFormatException(
                    $"C# implementation manifest is missing chunk '{key}'.");
            byKey.Remove(key);
            if (payload.Length + chunk.Length > MaximumEncodedPayloadCharacters)
                throw new ManifestFormatException(
                    "C# implementation manifest payload exceeds the supported size.");
            payload.Append(chunk);
        }

        if (byKey.Count != 0)
            throw new ManifestFormatException(
                "C# implementation manifest has unexpected chunks: " +
                string.Join(", ", byKey.Keys.OrderBy(key => key, StringComparer.Ordinal)));

        byte[] bytes;
        try
        {
            bytes = Convert.FromBase64String(payload.ToString());
        }
        catch (FormatException failure)
        {
            throw new ManifestFormatException(
                "C# implementation manifest payload is not base64.", failure);
        }

        if (bytes.Length > MaximumDecodedPayloadBytes)
            throw new ManifestFormatException(
                "C# implementation manifest payload exceeds the supported decoded size.");
        if (!string.Equals(Sha256Hex(bytes), markerFields[2], StringComparison.Ordinal))
            throw new ManifestFormatException(
                "C# implementation manifest payload hash does not match its marker.");

        string encoded;
        try
        {
            encoded = StrictUtf8.GetString(bytes);
        }
        catch (DecoderFallbackException failure)
        {
            throw new ManifestFormatException(
                "C# implementation manifest payload is not valid UTF-8.", failure);
        }
        KotlinCSharpManifest manifest = Decode(encoded);
        if (manifest.SchemaVersion != schemaVersion)
            throw new ManifestFormatException(
                "C# implementation manifest marker and payload schemas differ.");
        return manifest;
    }

    private static KotlinCSharpManifest Decode(string encoded)
    {
        string[] lines = encoded.Split(new[] { '\n' }, StringSplitOptions.RemoveEmptyEntries);
        if (lines.Length > MaximumRecordCount)
            throw new ManifestFormatException(
                $"C# implementation manifest contains more than {MaximumRecordCount} records.");

        var records = lines.Select(DecodeRecord).ToImmutableArray();
        Record[] headers = records.Where(record => record.Tag == "H").ToArray();
        if (headers.Length != 1)
            throw new ManifestFormatException(
                "C# implementation manifest must contain exactly one header.");
        Record header = headers[0];
        RequireArity(header, 4, "header");
        int schemaVersion = ParseInt(RequireField(header, 0, "schema version"), "schema version");
        if (schemaVersion != CurrentSchemaVersion)
            throw new ManifestVersionException(
                $"Assembly manifest schema {schemaVersion} is incompatible with " +
                $"Kotlin C# tooling schema {CurrentSchemaVersion}.");
        string assemblyName = RequireField(header, 1, "assembly name");
        string targetProfile = RequireField(header, 2, "target profile");
        if (targetProfile != "net48" &&
            targetProfile != "netstandard2.0" &&
            targetProfile != "net10.0")
            throw new ManifestFormatException(
                $"C# implementation manifest has unknown target profile '{targetProfile}'.");
        string logicalIdentityScheme = RequireField(header, 3, "logical identity scheme");
        if (!string.Equals(
                logicalIdentityScheme,
                LogicalIdentityScheme,
                StringComparison.Ordinal))
            throw new ManifestVersionException(
                $"Unsupported C# implementation logical identity scheme " +
                $"'{logicalIdentityScheme}'.");

        var interfaces = new Dictionary<string, PendingInterface>(StringComparer.Ordinal);
        foreach (Record record in records.Where(record => record.Tag == "I"))
        {
            RequireArity(record, 7, "interface");
            string logicalKey = RequireKotlinKey(
                RequireField(record, 0, "interface logical key"), "C:", "interface");
            if (interfaces.ContainsKey(logicalKey))
                throw new ManifestFormatException(
                    $"Duplicate C# implementation interface '{logicalKey}'.");
            ImmutableArray<KotlinTypeParameter> typeParameters =
                DecodeList(RequireField(record, 4, "interface type parameters"))
                    .Select(parameter =>
                    {
                        string[] components = parameter.Split(TypeParameterSeparator);
                        if (components.Length != 2 ||
                            (components[1] != "INVARIANT" &&
                             components[1] != "IN" &&
                             components[1] != "OUT"))
                            throw new ManifestFormatException(
                                $"C# implementation interface '{logicalKey}' has an invalid " +
                                "type parameter.");
                        return new KotlinTypeParameter(components[0], components[1]);
                    })
                    .ToImmutableArray();
            if (!bool.TryParse(
                    RequireField(record, 5, "source-authoring status"),
                    out bool sourceAuthoringSupported))
                throw new ManifestFormatException(
                    $"C# implementation interface '{logicalKey}' has an invalid support status.");
            interfaces.Add(
                logicalKey,
                new PendingInterface(
                    logicalKey,
                    DecodeList(RequireField(record, 1, "canonical owner")),
                    DecodeOptionalList(record.Fields[2]),
                    DecodeOptionalList(record.Fields[3]),
                    typeParameters,
                    sourceAuthoringSupported,
                    DecodeList(RequireField(record, 6, "unsupported reasons"))));
        }

        var members = new Dictionary<string, PendingMember>(StringComparer.Ordinal);
        foreach (Record record in records.Where(record => record.Tag == "M"))
        {
            RequireArity(record, 12, "member");
            string interfaceKey = RequireField(record, 0, "member interface key");
            string logicalKey = RequireKotlinKey(
                RequireField(record, 1, "member logical key"), "F:", "member");
            if (members.ContainsKey(logicalKey))
                throw new ManifestFormatException(
                    $"Duplicate C# implementation member '{logicalKey}'.");
            KotlinWrongShapePolicy? policy = null;
            if (record.Fields[7] != null)
            {
                policy = new KotlinWrongShapePolicy(
                    ParseInt(record.Fields[7]!, "wrong-shape checked parameter count"),
                    ParseEnum<KotlinWrongShapeFallback>(
                        record.Fields[8], "wrong-shape fallback"),
                    record.Fields[9] == null
                        ? (int?)null
                        : ParseInt(record.Fields[9]!, "wrong-shape fallback parameter"));
            }
            else if (record.Fields[8] != null || record.Fields[9] != null)
            {
                throw new ManifestFormatException(
                    $"C# implementation member '{logicalKey}' has an incomplete wrong-shape policy.");
            }

            members.Add(
                logicalKey,
                new PendingMember(
                    interfaceKey,
                    logicalKey,
                    ParseEnum<KotlinMemberKind>(record.Fields[2], "member kind"),
                    RequireField(record, 3, "member source name"),
                    ParseEnum<KotlinInterfaceView>(record.Fields[4], "authoring view"),
                    ParseEnum<KotlinDefaultKind>(record.Fields[5], "default kind"),
                    record.Fields[6] == null
                        ? (KotlinInterfaceView?)null
                        : ParseEnum<KotlinInterfaceView>(
                            record.Fields[6], "semantic body view"),
                    policy,
                    DecodeConstraints(
                        RequireField(record, 10, "erased owner-relative constraints"),
                        logicalKey),
                    DecodeLogicalKeys(
                        RequireField(record, 11, "overridden logical members"),
                        logicalKey)));
        }

        var slotsByMember =
            new Dictionary<string, List<KotlinMethodLocator>>(StringComparer.Ordinal);
        foreach (Record record in records.Where(record => record.Tag == "S"))
        {
            RequireArity(record, 8, "slot");
            string memberKey = RequireField(record, 0, "slot member key");
            KotlinMethodLocator locator = DecodeLocator(record);
            AddUniqueSlot(slotsByMember, memberKey, locator, "member");
        }

        foreach (PendingMember pending in members.Values)
        {
            if (!interfaces.TryGetValue(pending.InterfaceKey, out PendingInterface owner))
                throw new ManifestFormatException(
                    $"C# implementation member '{pending.LogicalKey}' names an unknown interface.");
            ImmutableArray<KotlinMethodLocator> slots =
                TakeSlots(slotsByMember, pending.LogicalKey);
            ValidateMember(pending, slots, targetProfile);
            owner.Members.Add(new KotlinMemberContract(
                pending.LogicalKey,
                pending.Kind,
                pending.SourceName,
                pending.AuthoringView,
                pending.DefaultKind,
                pending.SemanticBodyView,
                pending.WrongShapePolicy,
                pending.ErasedOwnerRelativeConstraints,
                pending.OverriddenLogicalMemberKeys,
                slots));
        }
        if (slotsByMember.Count != 0)
            throw new ManifestFormatException(
                "C# implementation manifest contains slots for unknown members.");

        var intersections =
            new Dictionary<string, PendingIntersection>(StringComparer.Ordinal);
        foreach (Record record in records.Where(record => record.Tag == "X"))
        {
            RequireArity(record, 7, "intersection");
            string logicalKey = RequireField(record, 1, "intersection logical key");
            if (!logicalKey.StartsWith("X:", StringComparison.Ordinal) ||
                logicalKey.Length <= 2)
                throw new ManifestFormatException(
                    $"C# implementation intersection '{logicalKey}' has no physical identity.");
            if (intersections.ContainsKey(logicalKey))
                throw new ManifestFormatException(
                    $"Duplicate C# implementation intersection '{logicalKey}'.");
            ImmutableArray<string> contributors =
                DecodeList(RequireField(record, 5, "intersection contributors"));
            if (contributors.Length < 2 ||
                !contributors.SequenceEqual(
                    contributors.Distinct(StringComparer.Ordinal)
                        .OrderBy(key => key, StringComparer.Ordinal)))
                throw new ManifestFormatException(
                    $"C# implementation intersection '{logicalKey}' has invalid contributors.");
            foreach (string contributor in contributors)
                RequireKotlinKey(contributor, "F:", "intersection contributor");
            intersections.Add(
                logicalKey,
                new PendingIntersection(
                    RequireField(record, 0, "intersection interface key"),
                    logicalKey,
                    ParseEnum<KotlinMemberKind>(record.Fields[2], "intersection kind"),
                    RequireField(record, 3, "intersection source name"),
                    ParseEnum<KotlinInterfaceView>(record.Fields[4], "intersection authoring view"),
                    contributors,
                    DecodeConstraints(
                        RequireField(record, 6, "erased owner-relative constraints"),
                        logicalKey)));
        }

        var slotsByIntersection =
            new Dictionary<string, List<KotlinMethodLocator>>(StringComparer.Ordinal);
        foreach (Record record in records.Where(record => record.Tag == "Y"))
        {
            RequireArity(record, 8, "intersection slot");
            string intersectionKey = RequireField(record, 0, "intersection slot key");
            KotlinMethodLocator locator = DecodeLocator(record);
            if (locator.Role != KotlinSlotRole.Declared &&
                locator.Role != KotlinSlotRole.Exact)
                throw new ManifestFormatException(
                    $"C# implementation intersection '{intersectionKey}' has a non-typed slot.");
            AddUniqueSlot(
                slotsByIntersection, intersectionKey, locator, "intersection");
        }

        foreach (PendingIntersection pending in intersections.Values)
        {
            if (!interfaces.TryGetValue(pending.InterfaceKey, out PendingInterface owner))
                throw new ManifestFormatException(
                    $"C# implementation intersection '{pending.LogicalKey}' names an unknown interface.");
            ImmutableArray<KotlinMethodLocator> slots =
                TakeSlots(slotsByIntersection, pending.LogicalKey);
            if (!slots.Any(slot => ViewForRole(slot.Role) == pending.AuthoringView))
                throw new ManifestFormatException(
                    $"C# implementation intersection '{pending.LogicalKey}' has no authoring-view slot.");
            owner.Intersections.Add(new KotlinIntersectionContract(
                pending.LogicalKey,
                pending.Kind,
                pending.SourceName,
                pending.AuthoringView,
                pending.ContributingLogicalMemberKeys,
                pending.ErasedOwnerRelativeConstraints,
                slots));
        }
        if (slotsByIntersection.Count != 0)
            throw new ManifestFormatException(
                "C# implementation manifest contains slots for unknown intersections.");

        ImmutableArray<KotlinInterfaceContract> result = interfaces.Values
            .OrderBy(contract => contract.LogicalKey, StringComparer.Ordinal)
            .Select(contract => contract.ToContract())
            .ToImmutableArray();
        foreach (KotlinInterfaceContract contract in result)
            ValidateInterface(contract);
        return new KotlinCSharpManifest(
            schemaVersion,
            assemblyName,
            targetProfile,
            logicalIdentityScheme,
            result);
    }

    private static void ValidateInterface(KotlinInterfaceContract contract)
    {
        if (contract.CanonicalOwnerPath.IsDefaultOrEmpty)
            throw new ManifestFormatException(
                $"C# implementation interface '{contract.LogicalKey}' has an empty canonical owner.");
        if (contract.TypeParameters.IsEmpty)
        {
            if (!contract.DeclaredOwnerPath.IsDefaultOrEmpty ||
                !contract.ExactOwnerPath.IsDefaultOrEmpty)
                throw new ManifestFormatException(
                    $"Non-generic C# implementation interface '{contract.LogicalKey}' has a split owner.");
            if (contract.Members.Any(member =>
                    member.AuthoringView != KotlinInterfaceView.Canonical ||
                    member.Slots.Count(slot => slot.Role == KotlinSlotRole.Canonical) != 1 ||
                    member.Slots.Any(slot =>
                        slot.Role == KotlinSlotRole.Erased ||
                        slot.Role == KotlinSlotRole.Declared ||
                        slot.Role == KotlinSlotRole.Exact)))
                throw new ManifestFormatException(
                    $"Non-generic C# implementation interface '{contract.LogicalKey}' has split member views.");
        }
        else
        {
            if (contract.DeclaredOwnerPath.IsDefaultOrEmpty)
                throw new ManifestFormatException(
                    $"Generic C# implementation interface '{contract.LogicalKey}' has no declared owner.");
            if (contract.Members.Any(member =>
                    !member.Slots.Any(slot => slot.Role == KotlinSlotRole.Erased) ||
                    member.Slots.Any(slot => slot.Role == KotlinSlotRole.Canonical)))
                throw new ManifestFormatException(
                    $"Generic C# implementation interface '{contract.LogicalKey}' has a non-erased canonical slot.");
        }
        if (contract.SourceAuthoringSupported != contract.UnsupportedReasons.IsEmpty)
            throw new ManifestFormatException(
                $"C# implementation interface '{contract.LogicalKey}' has inconsistent support status.");
        foreach (KotlinMemberContract member in contract.Members)
            ValidateConstraints(
                contract,
                member.AuthoringView,
                member.ErasedOwnerRelativeConstraints,
                member.Slots,
                $"member '{member.LogicalKey}'");
        foreach (KotlinIntersectionContract intersection in contract.Intersections)
            ValidateConstraints(
                contract,
                intersection.AuthoringView,
                intersection.ErasedOwnerRelativeConstraints,
                intersection.Slots,
                $"intersection '{intersection.LogicalKey}'");
    }

    private static void ValidateMember(
        PendingMember member,
        ImmutableArray<KotlinMethodLocator> slots,
        string targetProfile)
    {
        if (!slots.Any(slot =>
                slot.Role == KotlinSlotRole.Canonical ||
                slot.Role == KotlinSlotRole.Erased))
            throw new ManifestFormatException(
                $"C# implementation member '{member.LogicalKey}' has no canonical identity slot.");
        if (!slots.Any(slot => ViewForRole(slot.Role) == member.AuthoringView))
            throw new ManifestFormatException(
                $"C# implementation member '{member.LogicalKey}' has no authoring-view slot.");
        if (member.WrongShapePolicy != null)
        {
            KotlinMethodLocator canonical =
                slots.Single(slot => slot.Role == KotlinSlotRole.Erased);
            KotlinWrongShapePolicy policy = member.WrongShapePolicy;
            if (policy.CheckedParameterCount < 1 ||
                policy.CheckedParameterCount > canonical.ParameterTypes.Length)
                throw new ManifestFormatException(
                    $"C# implementation member '{member.LogicalKey}' has an invalid wrong-shape check count.");
            if (policy.Fallback == KotlinWrongShapeFallback.Argument)
            {
                if (policy.FallbackParameterIndex == null ||
                    policy.FallbackParameterIndex < policy.CheckedParameterCount ||
                    policy.FallbackParameterIndex >= canonical.ParameterTypes.Length)
                    throw new ManifestFormatException(
                        $"C# implementation member '{member.LogicalKey}' has an invalid fallback parameter.");
            }
            else if (policy.FallbackParameterIndex != null)
            {
                throw new ManifestFormatException(
                    $"C# implementation member '{member.LogicalKey}' has an unexpected fallback parameter.");
            }
        }

        switch (member.DefaultKind)
        {
            case KotlinDefaultKind.Abstract:
                if (member.SemanticBodyView != null ||
                    slots.Any(slot => slot.Role == KotlinSlotRole.Helper))
                    throw new ManifestFormatException(
                        $"Abstract C# implementation member '{member.LogicalKey}' has default-body metadata.");
                break;
            case KotlinDefaultKind.PortableHelper:
                if (targetProfile == "net10.0" ||
                    member.SemanticBodyView != null ||
                    !slots.Any(slot => slot.Role == KotlinSlotRole.Helper))
                    throw new ManifestFormatException(
                        $"Portable C# implementation member '{member.LogicalKey}' has inconsistent body metadata.");
                break;
            case KotlinDefaultKind.DimWithHelper:
                if (targetProfile != "net10.0" ||
                    member.SemanticBodyView == null ||
                    !slots.Any(slot => slot.Role == KotlinSlotRole.Helper))
                    throw new ManifestFormatException(
                        $"DIM C# implementation member '{member.LogicalKey}' has inconsistent body metadata.");
                break;
            default:
                throw new ManifestFormatException(
                    $"C# implementation member '{member.LogicalKey}' has an unknown default kind.");
        }
    }

    private static ImmutableArray<string> DecodeLogicalKeys(
        string encoded,
        string memberKey)
    {
        ImmutableArray<string> result = DecodeList(encoded);
        string[] normalized = result
            .Distinct(StringComparer.Ordinal)
            .OrderBy(key => key, StringComparer.Ordinal)
            .ToArray();
        if (result.Length != normalized.Length ||
            !result.SequenceEqual(normalized, StringComparer.Ordinal) ||
            result.Any(key =>
                !key.StartsWith("F:", StringComparison.Ordinal) ||
                key.Length <= 2 ||
                string.Equals(key, memberKey, StringComparison.Ordinal)))
        {
            throw new ManifestFormatException(
                $"C# implementation member '{memberKey}' has invalid overridden members.");
        }
        return result;
    }

    private static void ValidateConstraints(
        KotlinInterfaceContract contract,
        KotlinInterfaceView authoringView,
        ImmutableArray<KotlinErasedOwnerRelativeConstraint> constraints,
        ImmutableArray<KotlinMethodLocator> slots,
        string description)
    {
        if (constraints.IsEmpty)
            return;
        if (contract.TypeParameters.IsEmpty)
            throw new ManifestFormatException(
                $"C# implementation {description} has an owner-relative constraint on a non-generic interface.");
        for (int index = 0; index < constraints.Length; index++)
        {
            KotlinErasedOwnerRelativeConstraint current = constraints[index];
            if (index != 0)
            {
                KotlinErasedOwnerRelativeConstraint previous = constraints[index - 1];
                if (previous.MethodTypeParameterIndex > current.MethodTypeParameterIndex ||
                    (previous.MethodTypeParameterIndex == current.MethodTypeParameterIndex &&
                     previous.OwnerTypeParameterIndex >= current.OwnerTypeParameterIndex))
                    throw new ManifestFormatException(
                        $"C# implementation {description} has duplicate or unordered erased constraints.");
            }
        }

        KotlinSlotRole authoringRole = RoleForView(authoringView);
        KotlinMethodLocator[] authoringSlots =
            slots.Where(slot => slot.Role == authoringRole).ToArray();
        if (authoringSlots.Length != 1)
            throw new ManifestFormatException(
                $"C# implementation {description} has no unique authoring-view slot.");
        KotlinMethodLocator authoringSlot = authoringSlots[0];
        foreach (KotlinErasedOwnerRelativeConstraint constraint in constraints)
        {
            if (constraint.MethodTypeParameterIndex < 0 ||
                constraint.MethodTypeParameterIndex >= authoringSlot.GenericArity ||
                constraint.OwnerTypeParameterIndex < 0 ||
                constraint.OwnerTypeParameterIndex >= contract.TypeParameters.Length)
                throw new ManifestFormatException(
                    $"C# implementation {description} has an invalid erased constraint index.");
        }
    }

    private static KotlinMethodLocator DecodeLocator(Record record)
    {
        return new KotlinMethodLocator(
            ParseEnum<KotlinSlotRole>(record.Fields[1], "slot role"),
            DecodeList(RequireField(record, 2, "slot owner")),
            RequireField(record, 3, "slot method name"),
            record.Fields[4],
            ParseInt(RequireField(record, 5, "slot generic arity"), "slot generic arity"),
            RequireField(record, 6, "slot return type"),
            DecodeList(RequireField(record, 7, "slot parameter types")));
    }

    private static void AddUniqueSlot(
        Dictionary<string, List<KotlinMethodLocator>> slotsByKey,
        string key,
        KotlinMethodLocator locator,
        string description)
    {
        if (!slotsByKey.TryGetValue(key, out List<KotlinMethodLocator> slots))
        {
            slots = new List<KotlinMethodLocator>();
            slotsByKey.Add(key, slots);
        }
        if (slots.Any(slot => slot.Role == locator.Role))
            throw new ManifestFormatException(
                $"Duplicate {locator.Role.ToString().ToLowerInvariant()} slot for " +
                $"C# implementation {description} '{key}'.");
        slots.Add(locator);
    }

    private static ImmutableArray<KotlinMethodLocator> TakeSlots(
        Dictionary<string, List<KotlinMethodLocator>> slotsByKey,
        string key)
    {
        if (!slotsByKey.TryGetValue(key, out List<KotlinMethodLocator> slots))
            return ImmutableArray<KotlinMethodLocator>.Empty;
        slotsByKey.Remove(key);
        return slots.OrderBy(slot => slot.Role).ToImmutableArray();
    }

    private static ImmutableArray<KotlinErasedOwnerRelativeConstraint> DecodeConstraints(
        string encoded,
        string recordKey)
    {
        return DecodeList(encoded).Select(constraint =>
        {
            string[] components = constraint.Split(TypeParameterSeparator);
            if (components.Length != 2)
                throw new ManifestFormatException(
                    $"C# implementation record '{recordKey}' has an invalid erased constraint.");
            return new KotlinErasedOwnerRelativeConstraint(
                ParseInt(components[0], "method type-parameter index"),
                ParseInt(components[1], "owner type-parameter index"));
        }).ToImmutableArray();
    }

    private static Record DecodeRecord(string line)
    {
        string[] components = line.Split('\t');
        if (components.Length == 0 ||
            (components[0] != "H" &&
             components[0] != "I" &&
             components[0] != "M" &&
             components[0] != "S" &&
             components[0] != "X" &&
             components[0] != "Y"))
            throw new ManifestFormatException(
                $"Unknown C# implementation manifest record '{components.FirstOrDefault()}'.");
        var fields = ImmutableArray.CreateBuilder<string?>(components.Length - 1);
        for (int index = 1; index < components.Length; index++)
        {
            if (components[index] == "~")
            {
                fields.Add(null);
                continue;
            }
            byte[] bytes;
            try
            {
                string field = components[index]
                    .Replace('-', '+')
                    .Replace('_', '/');
                field = field.PadRight(field.Length + ((4 - field.Length % 4) % 4), '=');
                bytes = Convert.FromBase64String(field);
            }
            catch (FormatException failure)
            {
                throw new ManifestFormatException(
                    "C# implementation manifest record contains invalid base64url.", failure);
            }
            string decoded;
            try
            {
                decoded = StrictUtf8.GetString(bytes);
            }
            catch (DecoderFallbackException failure)
            {
                throw new ManifestFormatException(
                    "C# implementation manifest field is not valid UTF-8.", failure);
            }
            if (decoded.Length > MaximumFieldCharacters)
                throw new ManifestFormatException(
                    "C# implementation manifest field exceeds the supported size.");
            fields.Add(decoded);
        }
        return new Record(components[0], fields.ToImmutable());
    }

    private static ImmutableArray<string> DecodeList(string encoded)
    {
        return encoded.Length == 0
            ? ImmutableArray<string>.Empty
            : encoded.Split(ListSeparator).ToImmutableArray();
    }

    private static ImmutableArray<string> DecodeOptionalList(string? encoded)
    {
        return encoded == null ? default : DecodeList(encoded);
    }

    private static void RequireArity(Record record, int arity, string description)
    {
        if (record.Fields.Length != arity)
            throw new ManifestFormatException(
                $"C# implementation manifest {description} record has invalid arity.");
    }

    private static string RequireField(Record record, int index, string description)
    {
        return record.Fields[index] ??
            throw new ManifestFormatException(
                $"C# implementation manifest record has no {description}.");
    }

    private static string RequireKotlinKey(
        string value,
        string prefix,
        string description)
    {
        if (!value.StartsWith(prefix, StringComparison.Ordinal) ||
            value.Length <= prefix.Length)
            throw new ManifestFormatException(
                $"C# implementation {description} '{value}' is not a Kotlin declaration identity.");
        return value;
    }

    private static int ParseInt(string value, string description)
    {
        if (!int.TryParse(
                value,
                NumberStyles.Integer,
                CultureInfo.InvariantCulture,
                out int result))
            throw new ManifestFormatException(
                $"C# implementation manifest has invalid {description} '{value}'.");
        return result;
    }

    private static T ParseEnum<T>(string? value, string description) where T : struct
    {
        if (value == null ||
            !Enum.TryParse(ToPascalCase(value), ignoreCase: false, out T result))
            throw new ManifestFormatException(
                $"C# implementation manifest has invalid {description} '{value}'.");
        return result;
    }

    private static string ToPascalCase(string value)
    {
        var result = new StringBuilder(value.Length);
        bool uppercase = true;
        foreach (char character in value)
        {
            if (character == '_')
            {
                uppercase = true;
                continue;
            }
            result.Append(uppercase ? char.ToUpperInvariant(character) : char.ToLowerInvariant(character));
            uppercase = false;
        }
        return result.ToString();
    }

    private static KotlinInterfaceView ViewForRole(KotlinSlotRole role)
    {
        switch (role)
        {
            case KotlinSlotRole.Canonical:
            case KotlinSlotRole.Erased:
                return KotlinInterfaceView.Canonical;
            case KotlinSlotRole.Declared:
                return KotlinInterfaceView.Declared;
            case KotlinSlotRole.Exact:
                return KotlinInterfaceView.Exact;
            default:
                throw new ManifestFormatException("A helper slot has no C# authoring view.");
        }
    }

    private static KotlinSlotRole RoleForView(KotlinInterfaceView view)
    {
        switch (view)
        {
            case KotlinInterfaceView.Canonical:
                return KotlinSlotRole.Canonical;
            case KotlinInterfaceView.Declared:
                return KotlinSlotRole.Declared;
            case KotlinInterfaceView.Exact:
                return KotlinSlotRole.Exact;
            default:
                throw new ManifestFormatException("Unknown C# authoring view.");
        }
    }

    private static string Sha256Hex(byte[] bytes)
    {
        using (SHA256 sha256 = SHA256.Create())
        {
            byte[] digest = sha256.ComputeHash(bytes);
            var result = new StringBuilder(digest.Length * 2);
            foreach (byte value in digest)
                result.Append(value.ToString("x2", CultureInfo.InvariantCulture));
            return result.ToString();
        }
    }

    private sealed class Record
    {
        internal Record(string tag, ImmutableArray<string?> fields)
        {
            Tag = tag;
            Fields = fields;
        }

        internal string Tag { get; }
        internal ImmutableArray<string?> Fields { get; }
    }

    private sealed class PendingInterface
    {
        internal PendingInterface(
            string logicalKey,
            ImmutableArray<string> canonicalOwnerPath,
            ImmutableArray<string> declaredOwnerPath,
            ImmutableArray<string> exactOwnerPath,
            ImmutableArray<KotlinTypeParameter> typeParameters,
            bool sourceAuthoringSupported,
            ImmutableArray<string> unsupportedReasons)
        {
            LogicalKey = logicalKey;
            CanonicalOwnerPath = canonicalOwnerPath;
            DeclaredOwnerPath = declaredOwnerPath;
            ExactOwnerPath = exactOwnerPath;
            TypeParameters = typeParameters;
            SourceAuthoringSupported = sourceAuthoringSupported;
            UnsupportedReasons = unsupportedReasons;
        }

        internal string LogicalKey { get; }
        internal ImmutableArray<string> CanonicalOwnerPath { get; }
        internal ImmutableArray<string> DeclaredOwnerPath { get; }
        internal ImmutableArray<string> ExactOwnerPath { get; }
        internal ImmutableArray<KotlinTypeParameter> TypeParameters { get; }
        internal bool SourceAuthoringSupported { get; }
        internal ImmutableArray<string> UnsupportedReasons { get; }
        internal List<KotlinMemberContract> Members { get; } = new List<KotlinMemberContract>();
        internal List<KotlinIntersectionContract> Intersections { get; } =
            new List<KotlinIntersectionContract>();

        internal KotlinInterfaceContract ToContract()
        {
            return new KotlinInterfaceContract(
                LogicalKey,
                CanonicalOwnerPath,
                DeclaredOwnerPath,
                ExactOwnerPath,
                TypeParameters,
                SourceAuthoringSupported,
                UnsupportedReasons,
                Members.OrderBy(member => member.LogicalKey, StringComparer.Ordinal)
                    .ToImmutableArray(),
                Intersections.OrderBy(intersection => intersection.LogicalKey, StringComparer.Ordinal)
                    .ToImmutableArray());
        }
    }

    private sealed class PendingMember
    {
        internal PendingMember(
            string interfaceKey,
            string logicalKey,
            KotlinMemberKind kind,
            string sourceName,
            KotlinInterfaceView authoringView,
            KotlinDefaultKind defaultKind,
            KotlinInterfaceView? semanticBodyView,
            KotlinWrongShapePolicy? wrongShapePolicy,
            ImmutableArray<KotlinErasedOwnerRelativeConstraint> erasedOwnerRelativeConstraints,
            ImmutableArray<string> overriddenLogicalMemberKeys)
        {
            InterfaceKey = interfaceKey;
            LogicalKey = logicalKey;
            Kind = kind;
            SourceName = sourceName;
            AuthoringView = authoringView;
            DefaultKind = defaultKind;
            SemanticBodyView = semanticBodyView;
            WrongShapePolicy = wrongShapePolicy;
            ErasedOwnerRelativeConstraints = erasedOwnerRelativeConstraints;
            OverriddenLogicalMemberKeys = overriddenLogicalMemberKeys;
        }

        internal string InterfaceKey { get; }
        internal string LogicalKey { get; }
        internal KotlinMemberKind Kind { get; }
        internal string SourceName { get; }
        internal KotlinInterfaceView AuthoringView { get; }
        internal KotlinDefaultKind DefaultKind { get; }
        internal KotlinInterfaceView? SemanticBodyView { get; }
        internal KotlinWrongShapePolicy? WrongShapePolicy { get; }
        internal ImmutableArray<KotlinErasedOwnerRelativeConstraint> ErasedOwnerRelativeConstraints { get; }
        internal ImmutableArray<string> OverriddenLogicalMemberKeys { get; }
    }

    private sealed class PendingIntersection
    {
        internal PendingIntersection(
            string interfaceKey,
            string logicalKey,
            KotlinMemberKind kind,
            string sourceName,
            KotlinInterfaceView authoringView,
            ImmutableArray<string> contributingLogicalMemberKeys,
            ImmutableArray<KotlinErasedOwnerRelativeConstraint> erasedOwnerRelativeConstraints)
        {
            InterfaceKey = interfaceKey;
            LogicalKey = logicalKey;
            Kind = kind;
            SourceName = sourceName;
            AuthoringView = authoringView;
            ContributingLogicalMemberKeys = contributingLogicalMemberKeys;
            ErasedOwnerRelativeConstraints = erasedOwnerRelativeConstraints;
        }

        internal string InterfaceKey { get; }
        internal string LogicalKey { get; }
        internal KotlinMemberKind Kind { get; }
        internal string SourceName { get; }
        internal KotlinInterfaceView AuthoringView { get; }
        internal ImmutableArray<string> ContributingLogicalMemberKeys { get; }
        internal ImmutableArray<KotlinErasedOwnerRelativeConstraint> ErasedOwnerRelativeConstraints { get; }
    }
}

internal sealed class ManifestFormatException : Exception
{
    internal ManifestFormatException(string message)
        : base(message)
    {
    }

    internal ManifestFormatException(string message, Exception innerException)
        : base(message, innerException)
    {
    }
}

internal sealed class ManifestVersionException : Exception
{
    internal ManifestVersionException(string message)
        : base(message)
    {
    }
}
