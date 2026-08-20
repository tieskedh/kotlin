/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using Kotlin.DotNet.CSharpAuthoring.Manifest;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;

namespace Kotlin.DotNet.CSharpAuthoring;

internal sealed class AuthoringContract
{
    internal AuthoringContract(
        Compilation compilation,
        INamedTypeSymbol implementationType,
        TypeDeclarationSyntax declaration,
        ImmutableArray<BoundKotlinInterface> interfaces)
    {
        Compilation = compilation;
        ImplementationType = implementationType;
        Declaration = declaration;
        Interfaces = interfaces;
    }

    internal Compilation Compilation { get; }
    internal INamedTypeSymbol ImplementationType { get; }
    internal TypeDeclarationSyntax Declaration { get; }
    internal ImmutableArray<BoundKotlinInterface> Interfaces { get; }
}

internal sealed class BoundKotlinInterface
{
    internal BoundKotlinInterface(
        KotlinManifestReference reference,
        KotlinInterfaceContract contract,
        INamedTypeSymbol interfaceType,
        bool isAuthoredRoot)
    {
        Reference = reference;
        Contract = contract;
        InterfaceType = interfaceType;
        IsAuthoredRoot = isAuthoredRoot;
    }

    internal KotlinManifestReference Reference { get; }
    internal KotlinInterfaceContract Contract { get; }
    internal INamedTypeSymbol InterfaceType { get; }
    internal bool IsAuthoredRoot { get; }
}

internal static class AuthoringContractDiscovery
{
    internal static ImmutableArray<AuthoringContract> Discover(
        Compilation compilation,
        KotlinManifestSet manifests,
        Action<Diagnostic> reportDiagnostic)
    {
        var result = ImmutableArray.CreateBuilder<AuthoringContract>();
        var seenImplementationTypes = new HashSet<ISymbol>(SymbolEqualityComparer.Default);
        foreach (SyntaxTree tree in compilation.SyntaxTrees)
        {
            SemanticModel semanticModel = compilation.GetSemanticModel(tree);
            foreach (TypeDeclarationSyntax declaration in tree.GetRoot()
                         .DescendantNodes()
                         .OfType<TypeDeclarationSyntax>())
            {
                if (!(declaration is ClassDeclarationSyntax ||
                      declaration is RecordDeclarationSyntax))
                    continue;
                if (!(semanticModel.GetDeclaredSymbol(declaration) is INamedTypeSymbol implementation))
                    continue;
                if (!seenImplementationTypes.Add(implementation))
                    continue;

                ImmutableArray<string> inaccessibleBaseContracts =
                    FindInaccessibleBaseContracts(
                        compilation,
                        declaration,
                        manifests);
                if (!inaccessibleBaseContracts.IsEmpty)
                {
                    foreach (string contractName in inaccessibleBaseContracts)
                    {
                        reportDiagnostic(Diagnostic.Create(
                            Diagnostics.InaccessibleContract,
                            declaration.Identifier.GetLocation(),
                            contractName,
                            compilation.AssemblyName ?? "<unnamed>"));
                    }
                    continue;
                }

                ImmutableArray<BoundKotlinInterface> interfaces =
                    FindImplementedKotlinInterfaces(
                        implementation,
                        declaration,
                        semanticModel,
                        manifests);
                if (interfaces.IsEmpty)
                    continue;
                // An admitted declaration-invariant interface has no legal sibling view. Its
                // exact construction is the complete CLR contract, while Kotlin star output
                // dispatch can inspect an ordinary natural implementation at the operation.
                // Do not turn the optional capability fast path into a partial-source
                // obligation when this C# type implements only such interfaces.
                if (!interfaces.Any(RequiresGeneratedCapability))
                    continue;

                Location location = declaration.Identifier.GetLocation();
                if (implementation.TypeKind != TypeKind.Class)
                {
                    reportDiagnostic(Diagnostic.Create(
                        Diagnostics.UnsupportedToolingShape,
                        location,
                        interfaces[0].InterfaceType.ToDisplayString(),
                        "C# value-type implementors require a separate Kotlin boxing and identity contract"));
                    continue;
                }
                INamedTypeSymbol? fileLocalType =
                    ContainingTypeChain(implementation)
                        .FirstOrDefault(IsFileLocal);
                if (fileLocalType != null)
                {
                    reportDiagnostic(Diagnostic.Create(
                        Diagnostics.UnsupportedToolingShape,
                        location,
                        interfaces[0].InterfaceType.ToDisplayString(),
                        $"file-local type '{fileLocalType.ToDisplayString()}' cannot be augmented from generated source"));
                    continue;
                }
                if (!declaration.Modifiers.Any(SyntaxKind.PartialKeyword))
                {
                    reportDiagnostic(Diagnostic.Create(
                        Diagnostics.MissingPartial,
                        location,
                        implementation.ToDisplayString(),
                        interfaces[0].InterfaceType.ToDisplayString()));
                    continue;
                }
                INamedTypeSymbol? nonPartialContainer =
                    ContainingTypeChain(implementation.ContainingType)
                        .FirstOrDefault(type => !IsPartial(type));
                if (nonPartialContainer != null)
                {
                    reportDiagnostic(Diagnostic.Create(
                        Diagnostics.MissingContainingPartial,
                        nonPartialContainer.Locations.FirstOrDefault() ?? location,
                        nonPartialContainer.ToDisplayString(),
                        implementation.ToDisplayString()));
                    continue;
                }

                bool hasError = false;
                foreach (BoundKotlinInterface bound in interfaces)
                {
                    if (!IsContractAccessible(
                            compilation,
                            bound.InterfaceType.OriginalDefinition))
                    {
                        reportDiagnostic(Diagnostic.Create(
                            Diagnostics.InaccessibleContract,
                            location,
                            bound.InterfaceType.ToDisplayString(),
                            compilation.AssemblyName ?? "<unnamed>"));
                        hasError = true;
                    }
                    if (!bound.Contract.SourceAuthoringSupported)
                    {
                        reportDiagnostic(Diagnostic.Create(
                            Diagnostics.UnsupportedContract,
                            location,
                            bound.InterfaceType.ToDisplayString(),
                            string.Join("; ", bound.Contract.UnsupportedReasons)));
                        hasError = true;
                    }
                    if (!HasSupportedSubstitution(bound.InterfaceType, out string reason))
                    {
                        reportDiagnostic(Diagnostic.Create(
                            Diagnostics.UnsupportedSubstitution,
                            location,
                            bound.InterfaceType.ToDisplayString(),
                            reason));
                        hasError = true;
                    }
                    foreach (KotlinMemberContract member in bound.Contract.Members)
                    {
                        if (!member.ErasedOwnerRelativeConstraints.IsEmpty)
                        {
                            reportDiagnostic(Diagnostic.Create(
                                Diagnostics.ErasedOwnerConstraint,
                                location,
                                member.LogicalKey));
                        }
                    }
                    foreach (KotlinIntersectionContract intersection in
                             bound.Contract.Intersections)
                    {
                        if (!intersection.ErasedOwnerRelativeConstraints.IsEmpty)
                        {
                            reportDiagnostic(Diagnostic.Create(
                                Diagnostics.ErasedOwnerConstraint,
                                location,
                                intersection.LogicalKey));
                        }
                    }
                }
                foreach (ISymbol conflictingMember in FindConflictingMembers(
                             implementation,
                             interfaces))
                {
                    reportDiagnostic(Diagnostic.Create(
                        Diagnostics.ConflictingMember,
                        conflictingMember.Locations.FirstOrDefault() ?? location,
                        conflictingMember.ToDisplayString(),
                        ExplicitSlotDisplay(conflictingMember)));
                    hasError = true;
                }
                if (!hasError)
                    result.Add(new AuthoringContract(
                        compilation,
                        implementation,
                        declaration,
                        interfaces));
            }
        }
        return result.ToImmutable();
    }

    private static bool RequiresGeneratedCapability(BoundKotlinInterface bound)
    {
        KotlinInterfaceContract contract = bound.Contract;
        if (contract.TypeParameters.Length != 1 ||
            !string.Equals(
                contract.TypeParameters[0].Variance,
                "INVARIANT",
                StringComparison.Ordinal) ||
            (contract.Members.Length != 1 && contract.Members.Length != 2) ||
            !contract.Intersections.IsEmpty)
            return true;
        bool isMethodBundle = contract.Members.All(member =>
                member.Kind == KotlinMemberKind.Method) &&
            contract.Members.Select(member => member.SourceName)
                .Distinct(StringComparer.Ordinal).Count() == contract.Members.Length;
        bool isPropertyBundle = contract.Members.Length == 2 &&
            contract.Members.Count(member =>
                member.Kind == KotlinMemberKind.PropertyGetter) == 1 &&
            contract.Members.Count(member =>
                member.Kind == KotlinMemberKind.PropertySetter) == 1 &&
            contract.Members.Select(member => member.SourceName)
                .Distinct(StringComparer.Ordinal).Count() == 1;
        if (!isMethodBundle && !isPropertyBundle)
            return true;
        int producerCount = 0;
        int consumerCount = 0;
        string? naturalPropertyName = null;
        foreach (KotlinMemberContract member in contract.Members)
        {
            if (member.DefaultKind != KotlinDefaultKind.Abstract ||
                member.WrongShapePolicy != null ||
                !member.ErasedOwnerRelativeConstraints.IsEmpty ||
                !member.OverriddenLogicalMemberKeys.IsEmpty ||
                member.Slots.Length != 2)
                return true;
            KotlinMethodLocator? semantic = member.Slots
                .SingleOrDefault(slot => slot.Role == KotlinSlotRole.Erased);
            KotlinMethodLocator? natural = member.Slots
                .SingleOrDefault(slot => slot.Role == KotlinSlotRole.Declared);
            if (semantic == null || natural == null ||
                semantic.GenericArity != 0 || natural.GenericArity != 0 ||
                semantic.PropertyName != null ||
                (isMethodBundle && natural.PropertyName != null) ||
                (isPropertyBundle && natural.PropertyName == null))
                return true;
            if (isPropertyBundle)
            {
                naturalPropertyName ??= natural.PropertyName;
                if (!string.Equals(
                        naturalPropertyName,
                        natural.PropertyName,
                        StringComparison.Ordinal))
                    return true;
            }
            if (semantic.ReturnType == "object" &&
                semantic.ParameterTypes.IsEmpty &&
                natural.ReturnType == "!0" &&
                natural.ParameterTypes.IsEmpty)
            {
                if (isPropertyBundle &&
                    member.Kind != KotlinMemberKind.PropertyGetter)
                    return true;
                producerCount++;
                continue;
            }
            if (semantic.ReturnType == "void" &&
                semantic.ParameterTypes.SequenceEqual(new[] { "object" }) &&
                natural.ReturnType == "void" &&
                natural.ParameterTypes.SequenceEqual(new[] { "!0" }))
            {
                if (isPropertyBundle &&
                    member.Kind != KotlinMemberKind.PropertySetter)
                    return true;
                consumerCount++;
                continue;
            }
            return true;
        }
        return !(producerCount == 1 &&
            consumerCount == contract.Members.Length - 1);
    }

    private static ImmutableArray<BoundKotlinInterface> FindImplementedKotlinInterfaces(
        INamedTypeSymbol implementation,
        TypeDeclarationSyntax declaration,
        SemanticModel semanticModel,
        KotlinManifestSet manifests)
    {
        var result = ImmutableArray.CreateBuilder<BoundKotlinInterface>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var authoredRoots = new List<BoundKotlinInterface>();
        var authoredInterfaceTypes =
            new HashSet<INamedTypeSymbol>(SymbolEqualityComparer.Default);
        foreach (INamedTypeSymbol interfaceType in implementation.Interfaces)
            authoredInterfaceTypes.Add(interfaceType);
        if (declaration.BaseList != null)
        {
            foreach (BaseTypeSyntax baseType in declaration.BaseList.Types)
            {
                AddNamedType(semanticModel.GetTypeInfo(baseType.Type).Type);
                SymbolInfo symbolInfo = semanticModel.GetSymbolInfo(baseType.Type);
                AddNamedType(symbolInfo.Symbol);
                foreach (ISymbol candidate in symbolInfo.CandidateSymbols)
                    AddNamedType(candidate);
            }
        }

        foreach (INamedTypeSymbol interfaceType in authoredInterfaceTypes)
        {
            if (interfaceType.TypeKind != TypeKind.Interface)
                continue;
            string metadataName = MetadataQualifiedName(interfaceType.OriginalDefinition);
            foreach (KotlinManifestReference reference in manifests.References)
            {
                if (!SymbolEqualityComparer.Default.Equals(
                        interfaceType.ContainingAssembly,
                        reference.Assembly))
                    continue;
                KotlinInterfaceContract? contract = reference.Manifest.Interfaces
                    .FirstOrDefault(candidate =>
                        AuthoringOwnerMetadataName(candidate) == metadataName);
                if (contract == null)
                    continue;
                authoredRoots.Add(new BoundKotlinInterface(
                    reference,
                    contract,
                    interfaceType,
                    isAuthoredRoot: true));
            }
        }
        if (declaration.BaseList != null)
        {
            foreach (BaseTypeSyntax baseType in declaration.BaseList.Types)
            {
                string? sourceName = SourceTypeName(baseType.Type);
                if (sourceName == null)
                    continue;
                var matches = (
                    from reference in manifests.References
                    from contract in reference.Manifest.Interfaces
                    let metadataName = AuthoringOwnerMetadataName(contract)
                    where metadataName != null &&
                          (string.Equals(
                               SourceNameForMetadataName(metadataName),
                               sourceName,
                               StringComparison.Ordinal) ||
                           string.Equals(
                               SourceNameForMetadataName(metadataName).Split('.').Last(),
                               sourceName,
                               StringComparison.Ordinal))
                    select new { reference, contract, metadataName }
                ).ToArray();
                if (matches.Length != 1)
                    continue;
                var match = matches[0];
                INamedTypeSymbol? definition =
                    ResolveOwner(
                        match.reference.Assembly,
                        AuthoringOwnerPath(match.contract));
                if (definition == null ||
                    authoredRoots.Any(root =>
                        SymbolEqualityComparer.Default.Equals(
                            root.InterfaceType.OriginalDefinition,
                            definition)))
                    continue;
                authoredRoots.Add(new BoundKotlinInterface(
                    match.reference,
                    match.contract,
                    definition,
                    isAuthoredRoot: true));
            }
        }

        foreach (BoundKotlinInterface root in authoredRoots)
        {
            if (seen.Add(root.Contract.LogicalKey))
                result.Add(root);
            foreach (INamedTypeSymbol inheritedType in root.InterfaceType.AllInterfaces)
            {
                string metadataName = MetadataQualifiedName(inheritedType.OriginalDefinition);
                foreach (KotlinManifestReference reference in manifests.References)
                {
                    if (!SymbolEqualityComparer.Default.Equals(
                            inheritedType.ContainingAssembly,
                            reference.Assembly))
                        continue;
                    KotlinInterfaceContract? inheritedContract =
                        reference.Manifest.Interfaces.FirstOrDefault(candidate =>
                            MatchesAnyOwner(candidate, metadataName));
                    if (inheritedContract == null || !seen.Add(inheritedContract.LogicalKey))
                        continue;
                    result.Add(new BoundKotlinInterface(
                        reference,
                        inheritedContract,
                        inheritedType,
                        isAuthoredRoot: false));
                }
            }
        }
        return result
            .OrderBy(bound => bound.Contract.LogicalKey, StringComparer.Ordinal)
            .ToImmutableArray();

        void AddNamedType(ISymbol? symbol)
        {
            if (symbol is INamedTypeSymbol namedType)
                authoredInterfaceTypes.Add(namedType);
        }
    }

    private static string? AuthoringOwnerMetadataName(KotlinInterfaceContract contract)
    {
        return OwnerMetadataName(AuthoringOwnerPath(contract));
    }

    private static ImmutableArray<string> AuthoringOwnerPath(
        KotlinInterfaceContract contract)
    {
        return contract.TypeParameters.IsEmpty
            ? contract.CanonicalOwnerPath
            : contract.DeclaredOwnerPath;
    }

    private static INamedTypeSymbol? ResolveOwner(
        IAssemblySymbol assembly,
        ImmutableArray<string> ownerPath)
    {
        if (ownerPath.IsDefaultOrEmpty)
            return null;
        string metadataName = ownerPath[0];
        for (int index = 1; index < ownerPath.Length; index++)
            metadataName += "+" + ownerPath[index];
        return assembly.GetTypeByMetadataName(metadataName);
    }

    private static bool MatchesAnyOwner(
        KotlinInterfaceContract contract,
        string metadataName)
    {
        return OwnerMetadataName(contract.CanonicalOwnerPath) == metadataName ||
            OwnerMetadataName(contract.DeclaredOwnerPath) == metadataName ||
            OwnerMetadataName(contract.ExactOwnerPath) == metadataName;
    }

    private static string? OwnerMetadataName(ImmutableArray<string> ownerPath)
    {
        if (ownerPath.IsDefaultOrEmpty)
            return null;
        return string.Join(".", ownerPath);
    }

    private static string MetadataQualifiedName(INamedTypeSymbol type)
    {
        var components = new Stack<string>();
        INamedTypeSymbol? current = type;
        while (current != null)
        {
            components.Push(current.MetadataName);
            current = current.ContainingType;
        }
        string typePath = string.Join(".", components);
        return type.ContainingNamespace.IsGlobalNamespace
            ? typePath
            : type.ContainingNamespace.ToDisplayString() + "." + typePath;
    }

    private static string SourceNameForMetadataName(string metadataName)
    {
        return string.Join(
            ".",
            metadataName.Split('.').Select(component => component.Split('`')[0]));
    }

    private static string? SourceTypeName(TypeSyntax syntax)
    {
        switch (syntax)
        {
            case IdentifierNameSyntax identifier:
                return identifier.Identifier.ValueText;
            case GenericNameSyntax generic:
                return generic.Identifier.ValueText;
            case QualifiedNameSyntax qualified:
                string? left = SourceTypeName(qualified.Left);
                string? right = SourceTypeName(qualified.Right);
                return left == null || right == null ? null : left + "." + right;
            case AliasQualifiedNameSyntax aliasQualified:
                string? name = SourceTypeName(aliasQualified.Name);
                return name == null
                    ? null
                    : aliasQualified.Alias.Identifier.ValueText == "global"
                        ? name
                        : aliasQualified.Alias.Identifier.ValueText + "." + name;
            default:
                return null;
        }
    }

    private static ImmutableArray<string> FindInaccessibleBaseContracts(
        Compilation compilation,
        TypeDeclarationSyntax declaration,
        KotlinManifestSet manifests)
    {
        if (declaration.BaseList == null)
            return ImmutableArray<string>.Empty;
        var result = ImmutableArray.CreateBuilder<string>();
        foreach (BaseTypeSyntax baseType in declaration.BaseList.Types)
        {
            string? sourceName = SourceTypeName(baseType.Type);
            if (sourceName == null)
                continue;
            var matches = (
                from reference in manifests.References
                from contract in reference.Manifest.Interfaces
                let metadataName = AuthoringOwnerMetadataName(contract)
                where metadataName != null &&
                      (string.Equals(
                           SourceNameForMetadataName(metadataName),
                           sourceName,
                           StringComparison.Ordinal) ||
                       string.Equals(
                           SourceNameForMetadataName(metadataName).Split('.').Last(),
                           sourceName,
                           StringComparison.Ordinal))
                select new { reference, contract, metadataName }
            ).ToArray();
            if (matches.Length != 1)
                continue;
            var match = matches[0];
            INamedTypeSymbol? interfaceType =
                ResolveOwner(
                    match.reference.Assembly,
                    AuthoringOwnerPath(match.contract));
            if (interfaceType == null ||
                !IsContractAccessible(compilation, interfaceType))
                result.Add(SourceNameForMetadataName(match.metadataName));
        }
        return result.Distinct(StringComparer.Ordinal).ToImmutableArray();
    }

    private static bool HasSupportedSubstitution(
        INamedTypeSymbol interfaceType,
        out string reason)
    {
        if (interfaceType.IsUnboundGenericType)
        {
            reason = "open unbound generic interfaces do not preserve an authoring substitution";
            return false;
        }
        for (int index = 0; index < interfaceType.TypeArguments.Length; index++)
        {
            if (!HasSupportedSubstitutionType(
                    interfaceType.TypeArguments[index],
                    $"type argument {index}",
                    out reason))
                return false;
        }
        reason = "";
        return true;
    }

    private static bool HasSupportedSubstitutionType(
        ITypeSymbol type,
        string path,
        out string reason)
    {
        if (type.TypeKind == TypeKind.Error)
        {
            reason = $"{path} could not be resolved";
            return false;
        }
        if (type.TypeKind == TypeKind.Dynamic)
        {
            reason = $"{path} is dynamic, which is not a stable CLR interface substitution";
            return false;
        }
        if (type is IPointerTypeSymbol ||
            type is IFunctionPointerTypeSymbol)
        {
            reason = $"{path} is a pointer or function pointer outside Kotlin's interface ABI";
            return false;
        }
        if (type is IArrayTypeSymbol array)
        {
            if (array.Rank != 1 || !array.IsSZArray)
            {
                reason =
                    $"{path} is a rectangular or non-vector CLR array with no Kotlin Array identity";
                return false;
            }
            return HasSupportedSubstitutionType(
                array.ElementType,
                path + " array element",
                out reason);
        }
        if (type is INamedTypeSymbol named)
        {
            if (named.IsUnboundGenericType)
            {
                reason = $"{path} is an open unbound generic construction";
                return false;
            }
            for (int index = 0; index < named.TypeArguments.Length; index++)
            {
                if (!HasSupportedSubstitutionType(
                        named.TypeArguments[index],
                        $"{path} nested type argument {index}",
                        out reason))
                    return false;
            }
        }
        reason = "";
        return true;
    }

    private static IEnumerable<INamedTypeSymbol> ContainingTypeChain(
        INamedTypeSymbol? type)
    {
        INamedTypeSymbol? current = type;
        while (current != null)
        {
            yield return current;
            current = current.ContainingType;
        }
    }

    private static bool IsFileLocal(INamedTypeSymbol type)
    {
        return type.DeclaringSyntaxReferences
            .Select(reference => reference.GetSyntax())
            .OfType<TypeDeclarationSyntax>()
            .Any(declaration =>
                declaration.Modifiers.Any(SyntaxKind.FileKeyword));
    }

    private static bool IsPartial(INamedTypeSymbol type)
    {
        TypeDeclarationSyntax[] declarations = type.DeclaringSyntaxReferences
            .Select(reference => reference.GetSyntax())
            .OfType<TypeDeclarationSyntax>()
            .ToArray();
        return declarations.Length != 0 &&
            declarations.All(declaration =>
                declaration.Modifiers.Any(SyntaxKind.PartialKeyword));
    }

    private static IEnumerable<ISymbol> FindConflictingMembers(
        INamedTypeSymbol implementation,
        ImmutableArray<BoundKotlinInterface> interfaces)
    {
        var kotlinOwners = new HashSet<ISymbol>(
            interfaces.Select(bound => bound.InterfaceType.OriginalDefinition),
            SymbolEqualityComparer.Default);
        foreach (ISymbol member in implementation.GetMembers())
        {
            if (member is IMethodSymbol method &&
                method.ExplicitInterfaceImplementations.Any(implementationMethod =>
                    kotlinOwners.Contains(
                        implementationMethod.ContainingType.OriginalDefinition)))
            {
                yield return member;
            }
            else if (member is IPropertySymbol property &&
                     property.ExplicitInterfaceImplementations.Any(implementationProperty =>
                         kotlinOwners.Contains(
                             implementationProperty.ContainingType.OriginalDefinition)))
            {
                yield return member;
            }
        }
    }

    private static string ExplicitSlotDisplay(ISymbol member)
    {
        if (member is IMethodSymbol method)
            return method.ExplicitInterfaceImplementations.First().ToDisplayString();
        if (member is IPropertySymbol property)
            return property.ExplicitInterfaceImplementations.First().ToDisplayString();
        return member.ToDisplayString();
    }

    private static bool IsContractAccessible(
        Compilation compilation,
        INamedTypeSymbol interfaceType)
    {
        INamedTypeSymbol? owner = interfaceType;
        while (owner != null)
        {
            switch (owner.DeclaredAccessibility)
            {
                case Accessibility.Public:
                    break;
                case Accessibility.Internal:
                    if (!SymbolEqualityComparer.Default.Equals(
                            owner.ContainingAssembly,
                            compilation.Assembly) &&
                        !owner.ContainingAssembly.GivesAccessTo(compilation.Assembly))
                        return false;
                    break;
                default:
                    return false;
            }
            owner = owner.ContainingType;
        }
        return true;
    }
}
