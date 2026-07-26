/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

using System.Collections.Immutable;

namespace Kotlin.DotNet.CSharpAuthoring.Manifest;

internal enum KotlinInterfaceView
{
    Canonical,
    Declared,
    Exact,
}

internal enum KotlinSlotRole
{
    Canonical,
    Erased,
    Declared,
    Exact,
    Helper,
}

internal enum KotlinMemberKind
{
    Method,
    PropertyGetter,
    PropertySetter,
}

internal enum KotlinDefaultKind
{
    Abstract,
    PortableHelper,
    DimWithHelper,
}

internal enum KotlinWrongShapeFallback
{
    False,
    Null,
    MinusOne,
    Argument,
}

internal sealed class KotlinCSharpManifest
{
    internal KotlinCSharpManifest(
        int schemaVersion,
        string assemblyName,
        string targetProfile,
        string logicalIdentityScheme,
        ImmutableArray<KotlinInterfaceContract> interfaces)
    {
        SchemaVersion = schemaVersion;
        AssemblyName = assemblyName;
        TargetProfile = targetProfile;
        LogicalIdentityScheme = logicalIdentityScheme;
        Interfaces = interfaces;
    }

    internal int SchemaVersion { get; }
    internal string AssemblyName { get; }
    internal string TargetProfile { get; }
    internal string LogicalIdentityScheme { get; }
    internal ImmutableArray<KotlinInterfaceContract> Interfaces { get; }
}

internal sealed class KotlinInterfaceContract
{
    internal KotlinInterfaceContract(
        string logicalKey,
        ImmutableArray<string> canonicalOwnerPath,
        ImmutableArray<string> declaredOwnerPath,
        ImmutableArray<string> exactOwnerPath,
        ImmutableArray<KotlinTypeParameter> typeParameters,
        bool sourceAuthoringSupported,
        ImmutableArray<string> unsupportedReasons,
        ImmutableArray<KotlinMemberContract> members,
        ImmutableArray<KotlinIntersectionContract> intersections)
    {
        LogicalKey = logicalKey;
        CanonicalOwnerPath = canonicalOwnerPath;
        DeclaredOwnerPath = declaredOwnerPath;
        ExactOwnerPath = exactOwnerPath;
        TypeParameters = typeParameters;
        SourceAuthoringSupported = sourceAuthoringSupported;
        UnsupportedReasons = unsupportedReasons;
        Members = members;
        Intersections = intersections;
    }

    internal string LogicalKey { get; }
    internal ImmutableArray<string> CanonicalOwnerPath { get; }
    internal ImmutableArray<string> DeclaredOwnerPath { get; }
    internal ImmutableArray<string> ExactOwnerPath { get; }
    internal ImmutableArray<KotlinTypeParameter> TypeParameters { get; }
    internal bool SourceAuthoringSupported { get; }
    internal ImmutableArray<string> UnsupportedReasons { get; }
    internal ImmutableArray<KotlinMemberContract> Members { get; }
    internal ImmutableArray<KotlinIntersectionContract> Intersections { get; }
}

internal sealed class KotlinTypeParameter
{
    internal KotlinTypeParameter(string name, string variance)
    {
        Name = name;
        Variance = variance;
    }

    internal string Name { get; }
    internal string Variance { get; }
}

internal sealed class KotlinMemberContract
{
    internal KotlinMemberContract(
        string logicalKey,
        KotlinMemberKind kind,
        string sourceName,
        KotlinInterfaceView authoringView,
        KotlinDefaultKind defaultKind,
        KotlinInterfaceView? semanticBodyView,
        KotlinWrongShapePolicy? wrongShapePolicy,
        ImmutableArray<KotlinErasedOwnerRelativeConstraint> erasedOwnerRelativeConstraints,
        ImmutableArray<string> overriddenLogicalMemberKeys,
        ImmutableArray<KotlinMethodLocator> slots)
    {
        LogicalKey = logicalKey;
        Kind = kind;
        SourceName = sourceName;
        AuthoringView = authoringView;
        DefaultKind = defaultKind;
        SemanticBodyView = semanticBodyView;
        WrongShapePolicy = wrongShapePolicy;
        ErasedOwnerRelativeConstraints = erasedOwnerRelativeConstraints;
        OverriddenLogicalMemberKeys = overriddenLogicalMemberKeys;
        Slots = slots;
    }

    internal string LogicalKey { get; }
    internal KotlinMemberKind Kind { get; }
    internal string SourceName { get; }
    internal KotlinInterfaceView AuthoringView { get; }
    internal KotlinDefaultKind DefaultKind { get; }
    internal KotlinInterfaceView? SemanticBodyView { get; }
    internal KotlinWrongShapePolicy? WrongShapePolicy { get; }
    internal ImmutableArray<KotlinErasedOwnerRelativeConstraint> ErasedOwnerRelativeConstraints { get; }
    internal ImmutableArray<string> OverriddenLogicalMemberKeys { get; }
    internal ImmutableArray<KotlinMethodLocator> Slots { get; }
}

internal sealed class KotlinIntersectionContract
{
    internal KotlinIntersectionContract(
        string logicalKey,
        KotlinMemberKind kind,
        string sourceName,
        KotlinInterfaceView authoringView,
        ImmutableArray<string> contributingLogicalMemberKeys,
        ImmutableArray<KotlinErasedOwnerRelativeConstraint> erasedOwnerRelativeConstraints,
        ImmutableArray<KotlinMethodLocator> slots)
    {
        LogicalKey = logicalKey;
        Kind = kind;
        SourceName = sourceName;
        AuthoringView = authoringView;
        ContributingLogicalMemberKeys = contributingLogicalMemberKeys;
        ErasedOwnerRelativeConstraints = erasedOwnerRelativeConstraints;
        Slots = slots;
    }

    internal string LogicalKey { get; }
    internal KotlinMemberKind Kind { get; }
    internal string SourceName { get; }
    internal KotlinInterfaceView AuthoringView { get; }
    internal ImmutableArray<string> ContributingLogicalMemberKeys { get; }
    internal ImmutableArray<KotlinErasedOwnerRelativeConstraint> ErasedOwnerRelativeConstraints { get; }
    internal ImmutableArray<KotlinMethodLocator> Slots { get; }
}

internal sealed class KotlinMethodLocator
{
    internal KotlinMethodLocator(
        KotlinSlotRole role,
        ImmutableArray<string> ownerPath,
        string methodName,
        string? propertyName,
        int genericArity,
        string returnType,
        ImmutableArray<string> parameterTypes)
    {
        Role = role;
        OwnerPath = ownerPath;
        MethodName = methodName;
        PropertyName = propertyName;
        GenericArity = genericArity;
        ReturnType = returnType;
        ParameterTypes = parameterTypes;
    }

    internal KotlinSlotRole Role { get; }
    internal ImmutableArray<string> OwnerPath { get; }
    internal string MethodName { get; }
    internal string? PropertyName { get; }
    internal int GenericArity { get; }
    internal string ReturnType { get; }
    internal ImmutableArray<string> ParameterTypes { get; }
}

internal sealed class KotlinWrongShapePolicy
{
    internal KotlinWrongShapePolicy(
        int checkedParameterCount,
        KotlinWrongShapeFallback fallback,
        int? fallbackParameterIndex)
    {
        CheckedParameterCount = checkedParameterCount;
        Fallback = fallback;
        FallbackParameterIndex = fallbackParameterIndex;
    }

    internal int CheckedParameterCount { get; }
    internal KotlinWrongShapeFallback Fallback { get; }
    internal int? FallbackParameterIndex { get; }
}

internal sealed class KotlinErasedOwnerRelativeConstraint
{
    internal KotlinErasedOwnerRelativeConstraint(int methodTypeParameterIndex, int ownerTypeParameterIndex)
    {
        MethodTypeParameterIndex = methodTypeParameterIndex;
        OwnerTypeParameterIndex = ownerTypeParameterIndex;
    }

    internal int MethodTypeParameterIndex { get; }
    internal int OwnerTypeParameterIndex { get; }
}

internal sealed class KotlinManifestReference
{
    internal KotlinManifestReference(
        Microsoft.CodeAnalysis.IAssemblySymbol assembly,
        KotlinCSharpManifest manifest)
    {
        Assembly = assembly;
        Manifest = manifest;
    }

    internal Microsoft.CodeAnalysis.IAssemblySymbol Assembly { get; }
    internal KotlinCSharpManifest Manifest { get; }
}

internal sealed class KotlinManifestProblem
{
    internal KotlinManifestProblem(
        Microsoft.CodeAnalysis.IAssemblySymbol assembly,
        bool versionMismatch,
        string message)
    {
        Assembly = assembly;
        VersionMismatch = versionMismatch;
        Message = message;
    }

    internal Microsoft.CodeAnalysis.IAssemblySymbol Assembly { get; }
    internal bool VersionMismatch { get; }
    internal string Message { get; }
}

internal sealed class KotlinManifestSet
{
    internal KotlinManifestSet(
        ImmutableArray<KotlinManifestReference> references,
        ImmutableArray<KotlinManifestProblem> problems)
    {
        References = references;
        Problems = problems;
    }

    internal ImmutableArray<KotlinManifestReference> References { get; }
    internal ImmutableArray<KotlinManifestProblem> Problems { get; }
}
