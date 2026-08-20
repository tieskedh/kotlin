/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using System.Text;
using Kotlin.DotNet.CSharpAuthoring.Manifest;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;

namespace Kotlin.DotNet.CSharpAuthoring;

internal sealed class KotlinImplementationEmission
{
    internal KotlinImplementationEmission(
        string generatedMembers,
        ImmutableArray<INamedTypeSymbol> additionalInterfaces,
        ImmutableArray<Diagnostic> diagnostics)
    {
        GeneratedMembers = generatedMembers;
        AdditionalInterfaces = additionalInterfaces;
        Diagnostics = diagnostics;
    }

    internal string GeneratedMembers { get; }
    internal ImmutableArray<INamedTypeSymbol> AdditionalInterfaces { get; }
    internal ImmutableArray<Diagnostic> Diagnostics { get; }
    internal bool HasErrors => Diagnostics.Any(
        diagnostic => diagnostic.Severity == DiagnosticSeverity.Error);
}

internal static class KotlinImplementationEmitter
{
    private static readonly SymbolDisplayFormat TypeDisplayFormat =
        SymbolDisplayFormat.FullyQualifiedFormat
            .WithMiscellaneousOptions(
                SymbolDisplayMiscellaneousOptions.EscapeKeywordIdentifiers |
                SymbolDisplayMiscellaneousOptions.UseSpecialTypes |
                SymbolDisplayMiscellaneousOptions.IncludeNullableReferenceTypeModifier);
    private static readonly string[] CoreLibraryAssemblyPrefixes =
    {
        "[mscorlib]",
        "[netstandard]",
        "[System.Runtime]",
        "[System.Private.CoreLib]",
    };

    internal static KotlinImplementationEmission Emit(AuthoringContract authoringContract)
    {
        var diagnostics = ImmutableArray.CreateBuilder<Diagnostic>();
        var generatedMembers = new StringBuilder();
        var additionalInterfaces = ImmutableArray.CreateBuilder<INamedTypeSymbol>();
        ImmutableArray<IntersectionBinding> intersections =
            ResolveIntersections(authoringContract, diagnostics);
        OverrideResolution overrideResolution =
            ResolveOverrideSelections(authoringContract, diagnostics);
        var intersectionsByContributor =
            new Dictionary<string, IntersectionBinding>(StringComparer.Ordinal);
        foreach (IntersectionBinding intersection in intersections)
        {
            foreach (string contributor in
                     intersection.Contract.ContributingLogicalMemberKeys)
            {
                if (intersectionsByContributor.TryGetValue(
                        contributor,
                        out IntersectionBinding? existing) &&
                    existing.Contract.LogicalKey !=
                        intersection.Contract.LogicalKey)
                {
                    diagnostics.Add(Diagnostic.Create(
                        Diagnostics.UnsupportedToolingShape,
                        authoringContract.Declaration.Identifier.GetLocation(),
                        intersection.Owner.InterfaceType.ToDisplayString(),
                        $"contributor '{contributor}' belongs to multiple intersection bodies"));
                }
                else
                {
                    intersectionsByContributor[contributor] = intersection;
                }
            }
        }
        foreach (BoundKotlinInterface bound in authoringContract.Interfaces)
        {
            if (!bound.Contract.DeclaredOwnerPath.IsDefaultOrEmpty &&
                !bound.Contract.CanonicalOwnerPath.IsDefaultOrEmpty &&
                !bound.Contract.DeclaredOwnerPath.SequenceEqual(
                    bound.Contract.CanonicalOwnerPath))
            {
                INamedTypeSymbol? semanticDefinition = ResolveType(
                    bound.Reference.Assembly,
                    bound.Contract.CanonicalOwnerPath);
                if (semanticDefinition == null || semanticDefinition.Arity != 0)
                {
                    diagnostics.Add(Diagnostic.Create(
                        Diagnostics.MalformedManifest,
                        authoringContract.Declaration.Identifier.GetLocation(),
                        bound.Reference.Assembly.Identity.Name,
                        $"semantic owner for '{bound.Contract.LogicalKey}' does not resolve uniquely"));
                }
                else
                {
                    additionalInterfaces.Add(semanticDefinition);
                }
            }
            if (bound.IsAuthoredRoot &&
                !bound.Contract.ExactOwnerPath.IsDefaultOrEmpty)
            {
                INamedTypeSymbol? exactDefinition = ResolveType(
                    bound.Reference.Assembly,
                    bound.Contract.ExactOwnerPath);
                if (exactDefinition == null ||
                    exactDefinition.Arity != bound.InterfaceType.TypeArguments.Length)
                {
                    diagnostics.Add(Diagnostic.Create(
                        Diagnostics.MalformedManifest,
                        authoringContract.Declaration.Identifier.GetLocation(),
                        bound.Reference.Assembly.Identity.Name,
                        $"exact owner for '{bound.Contract.LogicalKey}' cannot be constructed"));
                }
                else
                {
                    additionalInterfaces.Add(
                        exactDefinition.Construct(
                            bound.InterfaceType.TypeArguments.ToArray()));
                }
            }

            EmitContract(
                authoringContract,
                bound,
                intersectionsByContributor,
                overrideResolution,
                generatedMembers,
                diagnostics);
        }
        EmitIntersections(
            authoringContract,
            intersections,
            generatedMembers,
            diagnostics);
        return new KotlinImplementationEmission(
            generatedMembers.ToString(),
            additionalInterfaces
                .Distinct(NamedTypeSymbolEqualityComparer.Instance)
                .ToImmutableArray(),
            diagnostics.ToImmutable());
    }

    private static void EmitContract(
        AuthoringContract authoringContract,
        BoundKotlinInterface bound,
        IReadOnlyDictionary<string, IntersectionBinding> intersectionsByContributor,
        OverrideResolution overrideResolution,
        StringBuilder output,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        var resolvedMembers = new List<ResolvedMember>();
        foreach (KotlinMemberContract member in bound.Contract.Members)
        {
            bool hasSelectedOverride =
                overrideResolution.SelectedByOverriddenKey.TryGetValue(
                    member.LogicalKey,
                    out MemberBinding? selected);
            MemberBinding semanticBinding = hasSelectedOverride
                ? selected!
                : new MemberBinding(bound, member);
            KotlinMemberContract semanticMember = semanticBinding.Member;
            KotlinMethodLocator[] physicalSlots = member.Slots.Where(slot =>
                    slot.Role != KotlinSlotRole.Helper)
                .ToArray();
            intersectionsByContributor.TryGetValue(
                member.LogicalKey,
                out IntersectionBinding? intersection);
            KotlinMethodLocator? authoringLocator = null;
            if (intersection == null)
            {
                authoringLocator = AuthoringLocator(semanticBinding);
            }
            IMethodSymbol? authoringMethod = intersection?.AuthoringMethod ??
                (authoringLocator == null
                    ? null
                    : ResolveMethod(
                        semanticBinding.Bound.Reference.Assembly,
                        authoringLocator,
                        semanticBinding.Bound.InterfaceType.TypeArguments));
            if (authoringMethod == null)
            {
                diagnostics.Add(Diagnostic.Create(
                    Diagnostics.MalformedManifest,
                    authoringContract.Declaration.Identifier.GetLocation(),
                    semanticBinding.Bound.Reference.Assembly.Identity.Name,
                    $"authoring locator for '{semanticMember.LogicalKey}' does not resolve uniquely"));
                continue;
            }
            foreach (KotlinMethodLocator locator in physicalSlots)
            {
                IMethodSymbol? method = ResolveMethod(
                    bound.Reference.Assembly,
                    locator,
                    bound.InterfaceType.TypeArguments);
                if (method == null)
                {
                    diagnostics.Add(Diagnostic.Create(
                        Diagnostics.MalformedManifest,
                        authoringContract.Declaration.Identifier.GetLocation(),
                        bound.Reference.Assembly.Identity.Name,
                        $"method locator for '{member.LogicalKey}' does not resolve uniquely"));
                    continue;
                }
                resolvedMembers.Add(new ResolvedMember(
                    semanticMember,
                    locator,
                    method,
                    authoringMethod,
                    semanticBinding.Bound.InterfaceType.TypeArguments,
                    semanticBinding.Bound.Reference.Assembly,
                    intersection?.Contract.SourceName ?? semanticMember.SourceName,
                    intersection?.Contract.LogicalKey ?? semanticMember.LogicalKey,
                    requiresSource:
                        intersection != null ||
                        overrideResolution.AmbiguousOverriddenKeys.Contains(member.LogicalKey),
                    requiresDimAdapter:
                        semanticMember.DefaultKind == KotlinDefaultKind.DimWithHelper &&
                        (
                            hasSelectedOverride &&
                            !string.Equals(
                                member.LogicalKey,
                                semanticMember.LogicalKey,
                                StringComparison.Ordinal) ||
                            locator.Role == KotlinSlotRole.Erased
                        )));
            }
        }

        EmitResolvedMembers(
            authoringContract,
            resolvedMembers,
            output,
            diagnostics);
    }

    private static OverrideResolution ResolveOverrideSelections(
        AuthoringContract authoringContract,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        MemberBinding[] bindings = authoringContract.Interfaces
            .SelectMany(bound => bound.Contract.Members.Select(member =>
                new MemberBinding(bound, member)))
            .ToArray();
        var bindingsByLogicalKey = bindings.ToDictionary(
            binding => binding.Member.LogicalKey,
            StringComparer.Ordinal);
        var candidatesByOverriddenKey =
            new Dictionary<string, List<MemberBinding>>(StringComparer.Ordinal);
        foreach (MemberBinding binding in bindings)
        {
            var pending = new Stack<string>(
                binding.Member.OverriddenLogicalMemberKeys);
            var visited = new HashSet<string>(StringComparer.Ordinal);
            while (pending.Count != 0)
            {
                string overriddenKey = pending.Pop();
                if (!visited.Add(overriddenKey))
                    continue;
                if (bindingsByLogicalKey.TryGetValue(
                        overriddenKey,
                        out MemberBinding? overriddenBinding) &&
                    (!IsStrictlyMoreDerived(
                         binding.Bound.InterfaceType,
                         overriddenBinding.Bound.InterfaceType) ||
                     !IsOverrideSignatureCompatible(
                         authoringContract.Compilation,
                         binding,
                         overriddenBinding)))
                {
                    diagnostics.Add(Diagnostic.Create(
                        Diagnostics.MalformedManifest,
                        authoringContract.Declaration.Identifier.GetLocation(),
                        binding.Bound.Reference.Assembly.Identity.Name,
                        $"member '{binding.Member.LogicalKey}' has an invalid override edge to '{overriddenKey}'"));
                    continue;
                }
                if (!candidatesByOverriddenKey.TryGetValue(
                        overriddenKey,
                        out List<MemberBinding>? candidates))
                {
                    candidates = new List<MemberBinding>();
                    candidatesByOverriddenKey.Add(overriddenKey, candidates);
                }
                candidates.Add(binding);
                if (overriddenBinding != null)
                {
                    foreach (string transitiveKey in
                             overriddenBinding.Member.OverriddenLogicalMemberKeys)
                        pending.Push(transitiveKey);
                }
            }
        }

        var selected =
            new Dictionary<string, MemberBinding>(StringComparer.Ordinal);
        var ambiguous = new HashSet<string>(StringComparer.Ordinal);
        foreach (KeyValuePair<string, List<MemberBinding>> entry in
                 candidatesByOverriddenKey)
        {
            MemberBinding[] mostDerived = entry.Value
                .Where(candidate =>
                    !entry.Value.Any(other =>
                        !ReferenceEquals(candidate, other) &&
                        IsStrictlyMoreDerived(other.Bound.InterfaceType,
                            candidate.Bound.InterfaceType)))
                .GroupBy(
                    candidate => candidate.Member.LogicalKey,
                    StringComparer.Ordinal)
                .Select(group => group.First())
                .ToArray();
            if (mostDerived.Length == 1)
                selected.Add(entry.Key, mostDerived[0]);
            else
                ambiguous.Add(entry.Key);
        }
        return new OverrideResolution(selected, ambiguous);
    }

    private static bool IsStrictlyMoreDerived(
        INamedTypeSymbol candidate,
        INamedTypeSymbol possibleBase)
    {
        if (SymbolEqualityComparer.Default.Equals(
                candidate.OriginalDefinition,
                possibleBase.OriginalDefinition))
            return false;
        return candidate.AllInterfaces.Any(inherited =>
            SymbolEqualityComparer.Default.Equals(
                inherited.OriginalDefinition,
                possibleBase.OriginalDefinition));
    }

    private static bool IsOverrideSignatureCompatible(
        Compilation compilation,
        MemberBinding implementation,
        MemberBinding overridden)
    {
        if (implementation.Member.Kind != overridden.Member.Kind ||
            !string.Equals(
                implementation.Member.SourceName,
                overridden.Member.SourceName,
                StringComparison.Ordinal))
            return false;
        IMethodSymbol? implementationMethod = ResolveAuthoringMethod(implementation);
        IMethodSymbol? overriddenMethod = ResolveAuthoringMethod(overridden);
        if (implementationMethod == null ||
            overriddenMethod == null ||
            implementationMethod.Arity != overriddenMethod.Arity ||
            implementationMethod.Parameters.Length !=
                overriddenMethod.Parameters.Length)
            return false;
        for (int index = 0; index < implementationMethod.Parameters.Length; index++)
        {
            IParameterSymbol implementationParameter =
                implementationMethod.Parameters[index];
            IParameterSymbol overriddenParameter =
                overriddenMethod.Parameters[index];
            if (implementationParameter.RefKind != overriddenParameter.RefKind ||
                !OverrideTypeEquals(
                    implementationParameter.Type,
                    overriddenParameter.Type))
                return false;
        }
        if (implementationMethod.ReturnsVoid || overriddenMethod.ReturnsVoid)
            return implementationMethod.ReturnsVoid && overriddenMethod.ReturnsVoid;
        return OverrideTypeEquals(
                   implementationMethod.ReturnType,
                   overriddenMethod.ReturnType) ||
            compilation.ClassifyConversion(
                implementationMethod.ReturnType,
                overriddenMethod.ReturnType).IsImplicit;
    }

    private static IMethodSymbol? ResolveAuthoringMethod(
        MemberBinding binding)
    {
        KotlinMethodLocator? locator = AuthoringLocator(binding);
        return locator == null
            ? null
            : ResolveMethod(
                binding.Bound.Reference.Assembly,
                locator,
                binding.Bound.InterfaceType.TypeArguments);
    }

    private static KotlinMethodLocator? AuthoringLocator(
        MemberBinding binding)
    {
        KotlinSlotRole role = AuthoringRole(
            binding.Bound.Contract,
            binding.Member.AuthoringView);
        return binding.Member.Slots
            .Where(slot => slot.Role != KotlinSlotRole.Helper)
            .SingleOrDefault(slot => slot.Role == role);
    }

    private static bool OverrideTypeEquals(
        ITypeSymbol left,
        ITypeSymbol right)
    {
        if (left is ITypeParameterSymbol leftParameter &&
            right is ITypeParameterSymbol rightParameter)
        {
            return leftParameter.TypeParameterKind ==
                       rightParameter.TypeParameterKind &&
                leftParameter.Ordinal == rightParameter.Ordinal;
        }
        if (left is IArrayTypeSymbol leftArray &&
            right is IArrayTypeSymbol rightArray)
        {
            return leftArray.Rank == rightArray.Rank &&
                leftArray.IsSZArray == rightArray.IsSZArray &&
                OverrideTypeEquals(
                    leftArray.ElementType,
                    rightArray.ElementType);
        }
        if (left is INamedTypeSymbol leftNamed &&
            right is INamedTypeSymbol rightNamed &&
            SymbolEqualityComparer.Default.Equals(
                leftNamed.OriginalDefinition,
                rightNamed.OriginalDefinition) &&
            leftNamed.TypeArguments.Length == rightNamed.TypeArguments.Length)
        {
            return leftNamed.TypeArguments
                .Zip(
                    rightNamed.TypeArguments,
                    OverrideTypeEquals)
                .All(equal => equal);
        }
        return SymbolEqualityComparer.Default.Equals(left, right);
    }

    private static ImmutableArray<IntersectionBinding> ResolveIntersections(
        AuthoringContract authoringContract,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        var result = ImmutableArray.CreateBuilder<IntersectionBinding>();
        var membersByLogicalKey =
            new Dictionary<string, KotlinMemberContract>(StringComparer.Ordinal);
        foreach (KotlinMemberContract member in authoringContract.Interfaces
                     .SelectMany(bound => bound.Contract.Members))
        {
            if (!membersByLogicalKey.ContainsKey(member.LogicalKey))
                membersByLogicalKey.Add(member.LogicalKey, member);
        }

        foreach (BoundKotlinInterface bound in authoringContract.Interfaces)
        {
            foreach (KotlinIntersectionContract intersection in
                     bound.Contract.Intersections)
            {
                bool contributorsValid = true;
                foreach (string contributor in
                         intersection.ContributingLogicalMemberKeys)
                {
                    if (!membersByLogicalKey.TryGetValue(
                            contributor,
                            out KotlinMemberContract? member) ||
                        member.Kind != intersection.Kind)
                    {
                        diagnostics.Add(Diagnostic.Create(
                            Diagnostics.MalformedManifest,
                            authoringContract.Declaration.Identifier.GetLocation(),
                            bound.Reference.Assembly.Identity.Name,
                            $"intersection '{intersection.LogicalKey}' has an unavailable contributor '{contributor}'"));
                        contributorsValid = false;
                    }
                }

                KotlinSlotRole authoringRole = AuthoringRole(
                    bound.Contract,
                    intersection.AuthoringView);
                KotlinMethodLocator? authoringLocator =
                    intersection.Slots.SingleOrDefault(slot =>
                        slot.Role == authoringRole);
                IMethodSymbol? authoringMethod = authoringLocator == null
                    ? null
                    : ResolveMethod(
                        bound.Reference.Assembly,
                        authoringLocator,
                        bound.InterfaceType.TypeArguments);
                if (authoringMethod == null)
                {
                    diagnostics.Add(Diagnostic.Create(
                        Diagnostics.MalformedManifest,
                        authoringContract.Declaration.Identifier.GetLocation(),
                        bound.Reference.Assembly.Identity.Name,
                        $"authoring locator for intersection '{intersection.LogicalKey}' does not resolve uniquely"));
                    continue;
                }
                if (contributorsValid)
                {
                    result.Add(new IntersectionBinding(
                        bound,
                        intersection,
                        authoringMethod));
                }
            }
        }
        return result.ToImmutable();
    }

    private static void EmitIntersections(
        AuthoringContract authoringContract,
        ImmutableArray<IntersectionBinding> intersections,
        StringBuilder output,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        var resolvedMembers = new List<ResolvedMember>();
        foreach (IntersectionBinding intersection in intersections)
        {
            var member = new KotlinMemberContract(
                intersection.Contract.LogicalKey,
                intersection.Contract.Kind,
                intersection.Contract.SourceName,
                intersection.Contract.AuthoringView,
                KotlinDefaultKind.Abstract,
                semanticBodyView: null,
                wrongShapePolicy: null,
                intersection.Contract.ErasedOwnerRelativeConstraints,
                ImmutableArray<string>.Empty,
                intersection.Contract.Slots);
            foreach (KotlinMethodLocator locator in intersection.Contract.Slots)
            {
                IMethodSymbol? method = ResolveMethod(
                    intersection.Owner.Reference.Assembly,
                    locator,
                    intersection.Owner.InterfaceType.TypeArguments);
                if (method == null)
                {
                    diagnostics.Add(Diagnostic.Create(
                        Diagnostics.MalformedManifest,
                        authoringContract.Declaration.Identifier.GetLocation(),
                        intersection.Owner.Reference.Assembly.Identity.Name,
                        $"method locator for intersection '{intersection.Contract.LogicalKey}' does not resolve uniquely"));
                    continue;
                }
                resolvedMembers.Add(new ResolvedMember(
                    member,
                    locator,
                    method,
                    intersection.AuthoringMethod,
                    intersection.Owner.InterfaceType.TypeArguments,
                    intersection.Owner.Reference.Assembly,
                    intersection.Contract.SourceName,
                    intersection.Contract.LogicalKey,
                    requiresSource: true,
                    requiresDimAdapter: false));
            }
        }
        EmitResolvedMembers(
            authoringContract,
            resolvedMembers,
            output,
            diagnostics);
    }

    private static void EmitResolvedMembers(
        AuthoringContract authoringContract,
        IEnumerable<ResolvedMember> members,
        StringBuilder output,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        List<ResolvedMember> resolvedMembers = members.ToList();
        foreach (IGrouping<PropertyIdentity, ResolvedMember> propertyGroup in resolvedMembers
                     .Where(member => member.Member.Kind != KotlinMemberKind.Method)
                     .GroupBy(member => new PropertyIdentity(
                         member.Method.ContainingType,
                         member.Locator.PropertyName)))
        {
            EmitProperty(
                authoringContract,
                propertyGroup.ToImmutableArray(),
                output,
                diagnostics);
        }
        foreach (ResolvedMember method in resolvedMembers.Where(member =>
                     member.Member.Kind == KotlinMemberKind.Method))
        {
            EmitMethod(
                authoringContract,
                method,
                output,
                diagnostics);
        }
    }

    private static void EmitProperty(
        AuthoringContract authoringContract,
        ImmutableArray<ResolvedMember> accessors,
        StringBuilder output,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        if (accessors.IsEmpty ||
            accessors[0].Locator.PropertyName == null ||
            accessors.Any(accessor =>
                accessor.Method.AssociatedSymbol is not IPropertySymbol))
        {
            diagnostics.Add(Diagnostic.Create(
                Diagnostics.MalformedManifest,
                authoringContract.Declaration.Identifier.GetLocation(),
                accessors.IsEmpty
                    ? "<unknown>"
                    : accessors[0].Method.ContainingAssembly.Identity.Name,
                "property accessor locator does not resolve to a CLR Property row"));
            return;
        }

        IPropertySymbol physicalProperty =
            (IPropertySymbol)accessors[0].Method.AssociatedSymbol!;
        IPropertySymbol? authoringProperty =
            accessors[0].AuthoringMethod.AssociatedSymbol as IPropertySymbol;
        if (authoringProperty == null)
        {
            diagnostics.Add(Diagnostic.Create(
                Diagnostics.MalformedManifest,
                authoringContract.Declaration.Identifier.GetLocation(),
                accessors[0].Method.ContainingAssembly.Identity.Name,
                "property authoring locator does not resolve to a CLR Property row"));
            return;
        }
        if (physicalProperty.RefKind != RefKind.None)
        {
            diagnostics.Add(Diagnostic.Create(
                Diagnostics.UnsupportedToolingShape,
                authoringContract.Declaration.Identifier.GetLocation(),
                physicalProperty.ContainingType.ToDisplayString(),
                $"ref-returning property '{physicalProperty.Name}'"));
            return;
        }
        string sourceName = accessors[0].SourceName;
        IPropertySymbol? sourceProperty = FindSourceProperty(
            authoringContract.ImplementationType,
            sourceName,
            authoringProperty.Type,
            out ImmutableArray<IPropertySymbol> ambiguousProperties);
        if (!ambiguousProperties.IsEmpty)
        {
            foreach (IPropertySymbol property in ambiguousProperties)
            {
                diagnostics.Add(Diagnostic.Create(
                    Diagnostics.ConflictingMember,
                    property.Locations.FirstOrDefault() ??
                        authoringContract.Declaration.Identifier.GetLocation(),
                    property.ToDisplayString(),
                    physicalProperty.ToDisplayString()));
            }
            return;
        }

        var accessorBodies = new Dictionary<KotlinMemberKind, string>();
        foreach (ResolvedMember accessor in accessors)
        {
            string? body = PropertyAccessorBody(
                authoringContract,
                accessor,
                sourceProperty,
                diagnostics);
            if (body != null)
                accessorBodies[accessor.Member.Kind] = body;
        }
        if (diagnostics.Any(diagnostic => diagnostic.Severity == DiagnosticSeverity.Error))
            return;
        if (accessorBodies.Count == 0)
            return;

        if (!TryCSharpIdentifier(accessors[0].Locator.PropertyName!, out string propertyName))
        {
            diagnostics.Add(Diagnostic.Create(
                Diagnostics.UnsupportedToolingShape,
                authoringContract.Declaration.Identifier.GetLocation(),
                physicalProperty.ContainingType.ToDisplayString(),
                $"property name '{accessors[0].Locator.PropertyName}' is not expressible in C#"));
            return;
        }

        output.Append("        ");
        output.Append(DisplayType(physicalProperty.Type));
        output.Append(' ');
        output.Append(DisplayType(physicalProperty.ContainingType));
        output.Append('.');
        output.Append(propertyName);
        output.AppendLine();
        output.AppendLine("        {");
        if (accessorBodies.TryGetValue(
                KotlinMemberKind.PropertyGetter,
                out string getterBody))
        {
            output.Append("            get ");
            output.AppendLine(getterBody);
        }
        if (accessorBodies.TryGetValue(
                KotlinMemberKind.PropertySetter,
                out string setterBody))
        {
            output.Append("            set ");
            output.AppendLine(setterBody);
        }
        output.AppendLine("        }");
        output.AppendLine();
    }

    private static string? PropertyAccessorBody(
        AuthoringContract authoringContract,
        ResolvedMember accessor,
        IPropertySymbol? sourceProperty,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        if (sourceProperty != null)
        {
            if (accessor.Member.Kind == KotlinMemberKind.PropertyGetter)
            {
                if (sourceProperty.GetMethod == null)
                {
                    ReportMissingSourceMember(
                        authoringContract,
                        accessor,
                        diagnostics);
                    return null;
                }
                string sourceExpression =
                    "this." + EscapeIdentifier(sourceProperty.Name);
                if (!TryConvertExpression(
                        authoringContract.Compilation,
                        sourceProperty.Type,
                        ((IPropertySymbol)accessor.Method.AssociatedSymbol!).Type,
                        sourceExpression,
                        out string resultExpression))
                {
                    ReportUnsupportedConversion(
                        authoringContract,
                        accessor,
                        sourceProperty.Type,
                        ((IPropertySymbol)accessor.Method.AssociatedSymbol!).Type,
                        diagnostics);
                    return null;
                }
                return "{ return " + resultExpression + "; }";
            }
            if (sourceProperty.SetMethod == null)
            {
                ReportMissingSourceMember(
                    authoringContract,
                    accessor,
                    diagnostics);
                return null;
            }
            if (!TryConvertExpression(
                    authoringContract.Compilation,
                    ((IPropertySymbol)accessor.Method.AssociatedSymbol!).Type,
                    sourceProperty.Type,
                    "value",
                    out string valueExpression))
            {
                ReportUnsupportedConversion(
                    authoringContract,
                    accessor,
                    ((IPropertySymbol)accessor.Method.AssociatedSymbol!).Type,
                    sourceProperty.Type,
                    diagnostics);
                return null;
            }
            return "{ this." + EscapeIdentifier(sourceProperty.Name) +
                " = " + valueExpression + "; }";
        }

        if (accessor.RequiresSource)
        {
            ReportMissingSourceMember(
                authoringContract,
                accessor,
                diagnostics);
            return null;
        }
        if (!accessor.RequiresDimAdapter &&
            (
                accessor.Member.DefaultKind == KotlinDefaultKind.DimWithHelper ||
                HasEffectiveDim(authoringContract.ImplementationType, accessor.Method)
            ))
            return null;
        switch (accessor.Member.DefaultKind)
        {
            case KotlinDefaultKind.Abstract:
                ReportMissingSourceMember(
                    authoringContract,
                    accessor,
                    diagnostics);
                return null;
            case KotlinDefaultKind.PortableHelper:
                return HelperAccessorBody(
                    authoringContract,
                    accessor,
                    diagnostics);
            case KotlinDefaultKind.DimWithHelper:
                return DimAccessorBody(
                    authoringContract,
                    accessor,
                    diagnostics);
            default:
                throw new InvalidOperationException("Unknown Kotlin default kind.");
        }
    }

    private static string? DimAccessorBody(
        AuthoringContract authoringContract,
        ResolvedMember accessor,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        if (accessor.AuthoringMethod.AssociatedSymbol is not
                IPropertySymbol semanticProperty ||
            accessor.Method.AssociatedSymbol is not
                IPropertySymbol physicalProperty ||
            !TryCSharpIdentifier(
                semanticProperty.Name,
                out string semanticPropertyName))
        {
            diagnostics.Add(Diagnostic.Create(
                Diagnostics.MalformedManifest,
                authoringContract.Declaration.Identifier.GetLocation(),
                accessor.MemberAssembly.Identity.Name,
                $"DIM property locator for '{accessor.Member.LogicalKey}' cannot be emitted"));
            return null;
        }

        string semanticExpression =
            "((" + DisplayType(semanticProperty.ContainingType) +
            ")this)." + semanticPropertyName;
        if (accessor.Member.Kind == KotlinMemberKind.PropertySetter)
        {
            if (semanticProperty.SetMethod == null ||
                !TryConvertExpression(
                    authoringContract.Compilation,
                    physicalProperty.Type,
                    semanticProperty.Type,
                    "value",
                    out string valueExpression))
            {
                ReportUnsupportedConversion(
                    authoringContract,
                    accessor,
                    physicalProperty.Type,
                    semanticProperty.Type,
                    diagnostics);
                return null;
            }
            return "{ " + semanticExpression + " = " + valueExpression + "; }";
        }

        if (semanticProperty.GetMethod == null ||
            !TryConvertExpression(
                authoringContract.Compilation,
                semanticProperty.Type,
                physicalProperty.Type,
                semanticExpression,
                out string resultExpression))
        {
            ReportUnsupportedConversion(
                authoringContract,
                accessor,
                semanticProperty.Type,
                physicalProperty.Type,
                diagnostics);
            return null;
        }
        return "{ return " + resultExpression + "; }";
    }

    private static string? HelperAccessorBody(
        AuthoringContract authoringContract,
        ResolvedMember accessor,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        KotlinMethodLocator? helper = accessor.Member.Slots.SingleOrDefault(slot =>
            slot.Role == KotlinSlotRole.Helper);
        bool isSetter =
            accessor.Member.Kind == KotlinMemberKind.PropertySetter;
        if (helper == null ||
            !TryHelperCall(
                accessor.MemberAssembly,
                helper,
                HelperTypeArguments(accessor, accessor.Method, helper),
                isSetter
                    ? "this, value"
                    : "this",
                out string call,
                out IMethodSymbol? helperMethod))
        {
            diagnostics.Add(Diagnostic.Create(
                Diagnostics.MalformedManifest,
                authoringContract.Declaration.Identifier.GetLocation(),
                accessor.MemberAssembly.Identity.Name,
                $"helper locator for '{accessor.Member.LogicalKey}' cannot be emitted"));
            return null;
        }
        if (isSetter)
        {
            if (helperMethod!.Parameters.Length < 2)
            {
                diagnostics.Add(Diagnostic.Create(
                    Diagnostics.MalformedManifest,
                    authoringContract.Declaration.Identifier.GetLocation(),
                    accessor.MemberAssembly.Identity.Name,
                    $"setter helper locator for '{accessor.Member.LogicalKey}' has no value parameter"));
                return null;
            }
            ITypeSymbol setterPhysicalType =
                ((IPropertySymbol)accessor.Method.AssociatedSymbol!).Type;
            ITypeSymbol helperType = helperMethod.Parameters.Last().Type;
            if (!TryConvertExpression(
                    authoringContract.Compilation,
                    setterPhysicalType,
                    helperType,
                    "value",
                    out string valueExpression))
            {
                ReportUnsupportedConversion(
                    authoringContract,
                    accessor,
                    setterPhysicalType,
                    helperType,
                    diagnostics);
                return null;
            }
            if (!string.Equals(valueExpression, "value", StringComparison.Ordinal) &&
                !TryHelperCall(
                    accessor.MemberAssembly,
                    helper,
                    HelperTypeArguments(accessor, accessor.Method, helper),
                    "this, " + valueExpression,
                    out call,
                    out helperMethod))
            {
                diagnostics.Add(Diagnostic.Create(
                    Diagnostics.MalformedManifest,
                    authoringContract.Declaration.Identifier.GetLocation(),
                    accessor.MemberAssembly.Identity.Name,
                    $"helper locator for '{accessor.Member.LogicalKey}' cannot be emitted"));
                return null;
            }
            return "{ " + call + "; }";
        }
        ITypeSymbol physicalType =
            ((IPropertySymbol)accessor.Method.AssociatedSymbol!).Type;
        if (!TryConvertExpression(
                authoringContract.Compilation,
                helperMethod!.ReturnType,
                physicalType,
                call,
                out string result))
        {
            ReportUnsupportedConversion(
                authoringContract,
                accessor,
                helperMethod!.ReturnType,
                physicalType,
                diagnostics);
            return null;
        }
        return "{ return " + result + "; }";
    }

    private static void EmitMethod(
        AuthoringContract authoringContract,
        ResolvedMember resolved,
        StringBuilder output,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        IMethodSymbol physicalMethod = resolved.Method;
        if (physicalMethod.ReturnsByRef ||
            physicalMethod.ReturnsByRefReadonly ||
            physicalMethod.Parameters.Any(parameter =>
                parameter.RefKind != RefKind.None))
        {
            diagnostics.Add(Diagnostic.Create(
                Diagnostics.UnsupportedToolingShape,
                authoringContract.Declaration.Identifier.GetLocation(),
                physicalMethod.ContainingType.ToDisplayString(),
                $"by-reference method '{resolved.SourceName}'"));
            return;
        }
        if (!TryCSharpIdentifier(physicalMethod.Name, out string methodName))
        {
            diagnostics.Add(Diagnostic.Create(
                Diagnostics.UnsupportedToolingShape,
                authoringContract.Declaration.Identifier.GetLocation(),
                physicalMethod.ContainingType.ToDisplayString(),
                $"method name '{physicalMethod.Name}' is not expressible in C#"));
            return;
        }

        IMethodSymbol? sourceMethod = FindSourceMethod(
            authoringContract.ImplementationType,
            resolved.SourceName,
            resolved.AuthoringMethod,
            out ImmutableArray<IMethodSymbol> ambiguousMethods);
        if (!ambiguousMethods.IsEmpty)
        {
            foreach (IMethodSymbol method in ambiguousMethods)
            {
                diagnostics.Add(Diagnostic.Create(
                    Diagnostics.ConflictingMember,
                    method.Locations.FirstOrDefault() ??
                        authoringContract.Declaration.Identifier.GetLocation(),
                    method.ToDisplayString(),
                    physicalMethod.ToDisplayString()));
            }
            return;
        }

        string? body;
        if (sourceMethod != null)
        {
            body = MethodSourceBody(
                authoringContract,
                sourceMethod,
                resolved,
                diagnostics);
            if (body == null)
                return;
        }
        else if (resolved.RequiresSource)
        {
            ReportMissingSourceMember(
                authoringContract,
                resolved,
                diagnostics);
            return;
        }
        else if (resolved.Member.DefaultKind == KotlinDefaultKind.DimWithHelper &&
                 resolved.RequiresDimAdapter)
        {
            body = DimMethodBody(
                authoringContract,
                resolved,
                diagnostics);
            if (body == null)
                return;
        }
        else if (HasEffectiveDim(authoringContract.ImplementationType, physicalMethod))
        {
            return;
        }
        else
        {
            switch (resolved.Member.DefaultKind)
            {
                case KotlinDefaultKind.Abstract:
                    ReportMissingSourceMember(
                        authoringContract,
                        resolved,
                        diagnostics);
                    return;
                case KotlinDefaultKind.PortableHelper:
                    KotlinMethodLocator? helper =
                        resolved.Member.Slots.SingleOrDefault(slot =>
                            slot.Role == KotlinSlotRole.Helper);
                    string arguments = string.Join(
                        ", ",
                        new[] { "this" }.Concat(
                            physicalMethod.Parameters.Select(
                                (_, index) => "p" + index)));
                    if (helper == null ||
                        !TryHelperCall(
                            resolved.MemberAssembly,
                            helper,
                            HelperTypeArguments(resolved, physicalMethod, helper),
                            arguments,
                            out string call,
                            out IMethodSymbol? helperMethod))
                    {
                        diagnostics.Add(Diagnostic.Create(
                            Diagnostics.MalformedManifest,
                            authoringContract.Declaration.Identifier.GetLocation(),
                            resolved.MemberAssembly.Identity.Name,
                            $"helper locator for '{resolved.Member.LogicalKey}' cannot be emitted"));
                        return;
                    }
                    if (physicalMethod.ReturnsVoid)
                    {
                        body = "{ " + call + "; }";
                    }
                    else if (TryConvertExpression(
                                 authoringContract.Compilation,
                                 helperMethod!.ReturnType,
                                 physicalMethod.ReturnType,
                                 call,
                                 out string helperResult))
                    {
                        body = "{ return " + helperResult + "; }";
                    }
                    else
                    {
                        ReportUnsupportedConversion(
                            authoringContract,
                            resolved,
                            helperMethod!.ReturnType,
                            physicalMethod.ReturnType,
                            diagnostics);
                        return;
                    }
                    break;
                case KotlinDefaultKind.DimWithHelper:
                    return;
                default:
                    throw new InvalidOperationException("Unknown Kotlin default kind.");
            }
        }

        output.Append("        ");
        output.Append(DisplayType(physicalMethod.ReturnType));
        output.Append(' ');
        output.Append(DisplayType(physicalMethod.ContainingType));
        output.Append('.');
        output.Append(methodName);
        if (physicalMethod.Arity != 0)
        {
            output.Append('<');
            output.Append(string.Join(
                ", ",
                physicalMethod.TypeParameters.Select(parameter =>
                    EscapeIdentifier(parameter.Name))));
            output.Append('>');
        }
        output.Append('(');
        output.Append(string.Join(
            ", ",
            physicalMethod.Parameters.Select(ParameterDeclaration)));
        output.Append(") ");
        output.AppendLine(body);
        output.AppendLine();
    }

    private static string? DimMethodBody(
        AuthoringContract authoringContract,
        ResolvedMember resolved,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        IMethodSymbol physicalMethod = resolved.Method;
        IMethodSymbol authoringMethod = resolved.AuthoringMethod;
        IMethodSymbol substitutedAuthoringMethod = authoringMethod.Arity == 0
            ? authoringMethod
            : authoringMethod.Construct(
                physicalMethod.TypeParameters.Cast<ITypeSymbol>().ToArray());
        if (!TryCSharpIdentifier(
                substitutedAuthoringMethod.Name,
                out string authoringMethodName) ||
            !TryWrongShapePrelude(
                authoringContract,
                resolved,
                physicalMethod,
                substitutedAuthoringMethod,
                diagnostics,
                out string wrongShapePrelude))
            return null;

        var arguments = new List<string>();
        for (int index = 0; index < physicalMethod.Parameters.Length; index++)
        {
            if (!TryConvertExpression(
                    authoringContract.Compilation,
                    physicalMethod.Parameters[index].Type,
                    substitutedAuthoringMethod.Parameters[index].Type,
                    "p" + index,
                    out string argument))
            {
                ReportUnsupportedConversion(
                    authoringContract,
                    physicalMethod,
                    physicalMethod.Parameters[index].Type,
                    substitutedAuthoringMethod.Parameters[index].Type,
                    diagnostics);
                return null;
            }
            arguments.Add(argument);
        }
        string call = "((" + DisplayType(substitutedAuthoringMethod.ContainingType) +
            ")this)." + authoringMethodName;
        if (physicalMethod.Arity != 0)
        {
            call += "<" + string.Join(
                ", ",
                physicalMethod.TypeParameters.Select(parameter =>
                    EscapeIdentifier(parameter.Name))) + ">";
        }
        call += "(" + string.Join(", ", arguments) + ")";
        if (physicalMethod.ReturnsVoid)
            return "{ " + wrongShapePrelude + call + "; }";
        if (TryConvertExpression(
                authoringContract.Compilation,
                substitutedAuthoringMethod.ReturnType,
                physicalMethod.ReturnType,
                call,
                out string result))
            return "{ " + wrongShapePrelude + "return " + result + "; }";
        ReportUnsupportedConversion(
            authoringContract,
            physicalMethod,
            substitutedAuthoringMethod.ReturnType,
            physicalMethod.ReturnType,
            diagnostics);
        return null;
    }

    private static string? MethodSourceBody(
        AuthoringContract authoringContract,
        IMethodSymbol sourceMethod,
        ResolvedMember resolved,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        IMethodSymbol physicalMethod = resolved.Method;
        IMethodSymbol authoringMethod = resolved.AuthoringMethod;
        IMethodSymbol substitutedAuthoringMethod = authoringMethod.Arity == 0
            ? authoringMethod
            : authoringMethod.Construct(
                physicalMethod.TypeParameters.Cast<ITypeSymbol>().ToArray());
        if (!TryWrongShapePrelude(
                authoringContract,
                resolved,
                physicalMethod,
                substitutedAuthoringMethod,
                diagnostics,
                out string wrongShapePrelude))
            return null;
        var arguments = new List<string>();
        for (int index = 0; index < physicalMethod.Parameters.Length; index++)
        {
            if (!TryConvertExpression(
                    authoringContract.Compilation,
                    physicalMethod.Parameters[index].Type,
                    substitutedAuthoringMethod.Parameters[index].Type,
                    "p" + index,
                    out string argument))
            {
                ReportUnsupportedConversion(
                    authoringContract,
                    physicalMethod,
                    physicalMethod.Parameters[index].Type,
                    substitutedAuthoringMethod.Parameters[index].Type,
                    diagnostics);
                return null;
            }
            arguments.Add(argument);
        }
        string call = "this." + EscapeIdentifier(sourceMethod.Name);
        if (physicalMethod.Arity != 0)
        {
            call += "<" + string.Join(
                ", ",
                physicalMethod.TypeParameters.Select(parameter =>
                    EscapeIdentifier(parameter.Name))) + ">";
        }
        call += "(" + string.Join(", ", arguments) + ")";
        return physicalMethod.ReturnsVoid
            ? "{ " + wrongShapePrelude + call + "; }"
            : TryConvertExpression(
                authoringContract.Compilation,
                substitutedAuthoringMethod.ReturnType,
                physicalMethod.ReturnType,
                call,
                out string result)
                ? "{ " + wrongShapePrelude + "return " + result + "; }"
                : ReportUnsupportedResult();

        string? ReportUnsupportedResult()
        {
            ReportUnsupportedConversion(
                authoringContract,
                physicalMethod,
                substitutedAuthoringMethod.ReturnType,
                physicalMethod.ReturnType,
                diagnostics);
            return null;
        }
    }

    private static bool TryWrongShapePrelude(
        AuthoringContract authoringContract,
        ResolvedMember resolved,
        IMethodSymbol physicalMethod,
        IMethodSymbol authoringMethod,
        ImmutableArray<Diagnostic>.Builder diagnostics,
        out string prelude)
    {
        KotlinWrongShapePolicy? policy = resolved.Member.WrongShapePolicy;
        if (policy == null || resolved.Locator.Role != KotlinSlotRole.Erased)
        {
            prelude = "";
            return true;
        }
        if (physicalMethod.ReturnsVoid ||
            policy.CheckedParameterCount > physicalMethod.Parameters.Length ||
            policy.CheckedParameterCount > authoringMethod.Parameters.Length ||
            !TryWrongShapeFallback(
                authoringContract.Compilation,
                physicalMethod,
                policy,
                out string fallback))
        {
            diagnostics.Add(Diagnostic.Create(
                Diagnostics.MalformedManifest,
                authoringContract.Declaration.Identifier.GetLocation(),
                physicalMethod.ContainingAssembly.Identity.Name,
                $"wrong-shape policy for '{resolved.Member.LogicalKey}' cannot be emitted"));
            prelude = "";
            return false;
        }

        var result = new StringBuilder();
        for (int index = 0; index < policy.CheckedParameterCount; index++)
        {
            ITypeSymbol physicalType = physicalMethod.Parameters[index].Type;
            ITypeSymbol expectedType = authoringMethod.Parameters[index].Type;
            if (SymbolEqualityComparer.Default.Equals(physicalType, expectedType))
                continue;
            if (!TryTypeTest(expectedType, "p" + index, out string condition))
            {
                diagnostics.Add(Diagnostic.Create(
                    Diagnostics.UnsupportedToolingShape,
                    authoringContract.Declaration.Identifier.GetLocation(),
                    physicalMethod.ContainingType.ToDisplayString(),
                    $"wrong-shape check for '{DisplayType(expectedType)}'"));
                prelude = "";
                return false;
            }
            result.Append("if (!(");
            result.Append(condition);
            result.Append(")) return ");
            result.Append(fallback);
            result.Append("; ");
        }
        prelude = result.ToString();
        return true;
    }

    private static bool TryWrongShapeFallback(
        Compilation compilation,
        IMethodSymbol physicalMethod,
        KotlinWrongShapePolicy policy,
        out string fallback)
    {
        switch (policy.Fallback)
        {
            case KotlinWrongShapeFallback.False:
                return TryConvertExpression(
                    compilation,
                    compilation.GetSpecialType(SpecialType.System_Boolean),
                    physicalMethod.ReturnType,
                    "false",
                    out fallback);
            case KotlinWrongShapeFallback.Null:
                if (CanAcceptNull(physicalMethod.ReturnType))
                {
                    fallback = "null";
                    return true;
                }
                fallback = "";
                return false;
            case KotlinWrongShapeFallback.MinusOne:
                return TryConvertExpression(
                    compilation,
                    compilation.GetSpecialType(SpecialType.System_Int32),
                    physicalMethod.ReturnType,
                    "-1",
                    out fallback);
            case KotlinWrongShapeFallback.Argument:
                int? index = policy.FallbackParameterIndex;
                if (index == null || index >= physicalMethod.Parameters.Length)
                {
                    fallback = "";
                    return false;
                }
                return TryConvertExpression(
                    compilation,
                    physicalMethod.Parameters[index.Value].Type,
                    physicalMethod.ReturnType,
                    "p" + index.Value,
                    out fallback);
            default:
                throw new InvalidOperationException(
                    "Unknown Kotlin wrong-shape fallback.");
        }
    }

    private static bool TryTypeTest(
        ITypeSymbol expectedType,
        string expression,
        out string condition)
    {
        if (expectedType.TypeKind == TypeKind.Dynamic ||
            expectedType.TypeKind == TypeKind.Pointer ||
            expectedType.TypeKind == TypeKind.FunctionPointer)
        {
            condition = "";
            return false;
        }

        bool acceptsNull =
            expectedType.NullableAnnotation == NullableAnnotation.Annotated;
        ITypeSymbol testedType = expectedType.WithNullableAnnotation(
            NullableAnnotation.NotAnnotated);
        if (testedType is INamedTypeSymbol namedType &&
            namedType.OriginalDefinition.SpecialType ==
                SpecialType.System_Nullable_T)
        {
            acceptsNull = true;
            testedType = namedType.TypeArguments[0].WithNullableAnnotation(
                NullableAnnotation.NotAnnotated);
        }

        condition = expression + " is " + DisplayType(testedType);
        if (acceptsNull)
            condition = expression + " is null || " + condition;
        return true;
    }

    private static bool CanAcceptNull(ITypeSymbol type)
    {
        return type.IsReferenceType ||
            type.NullableAnnotation == NullableAnnotation.Annotated ||
            type is INamedTypeSymbol namedType &&
            namedType.OriginalDefinition.SpecialType ==
                SpecialType.System_Nullable_T;
    }

    private static IPropertySymbol? FindSourceProperty(
        INamedTypeSymbol implementationType,
        string sourceName,
        ITypeSymbol expectedType,
        out ImmutableArray<IPropertySymbol> ambiguous)
    {
        ImmutableArray<IPropertySymbol> candidates = implementationType.GetMembers()
            .OfType<IPropertySymbol>()
            .Where(property =>
                !property.IsImplicitlyDeclared &&
                property.ExplicitInterfaceImplementations.IsEmpty &&
                IsSourceName(property.Name, sourceName) &&
                SymbolEqualityComparer.Default.Equals(property.Type, expectedType))
            .ToImmutableArray();
        if (candidates.Length == 1)
        {
            ambiguous = ImmutableArray<IPropertySymbol>.Empty;
            return candidates[0];
        }
        ambiguous = candidates.Length > 1
            ? candidates
            : ImmutableArray<IPropertySymbol>.Empty;
        return null;
    }

    private static IMethodSymbol? FindSourceMethod(
        INamedTypeSymbol implementationType,
        string sourceName,
        IMethodSymbol expectedMethod,
        out ImmutableArray<IMethodSymbol> ambiguous)
    {
        ImmutableArray<IMethodSymbol> candidates = implementationType.GetMembers()
            .OfType<IMethodSymbol>()
            .Where(method =>
                method.MethodKind == MethodKind.Ordinary &&
                !method.IsImplicitlyDeclared &&
                method.ExplicitInterfaceImplementations.IsEmpty &&
                IsSourceName(method.Name, sourceName) &&
                method.Arity == expectedMethod.Arity &&
                MethodShapeMatches(method, expectedMethod))
            .ToImmutableArray();
        if (candidates.Length == 1)
        {
            ambiguous = ImmutableArray<IMethodSymbol>.Empty;
            return candidates[0];
        }
        ambiguous = candidates.Length > 1
            ? candidates
            : ImmutableArray<IMethodSymbol>.Empty;
        return null;
    }

    private static bool MethodShapeMatches(
        IMethodSymbol sourceMethod,
        IMethodSymbol expectedMethod)
    {
        IMethodSymbol substitutedExpected = expectedMethod.Arity == 0
            ? expectedMethod
            : expectedMethod.Construct(
                sourceMethod.TypeParameters.Cast<ITypeSymbol>().ToArray());
        if (!SymbolEqualityComparer.Default.Equals(
                sourceMethod.ReturnType,
                substitutedExpected.ReturnType) ||
            !ParametersMatch(
                sourceMethod.Parameters,
                substitutedExpected.Parameters))
            return false;
        for (int index = 0; index < sourceMethod.TypeParameters.Length; index++)
        {
            ITypeParameterSymbol source = sourceMethod.TypeParameters[index];
            ITypeParameterSymbol expected = substitutedExpected.TypeParameters[index];
            if (source.HasConstructorConstraint != expected.HasConstructorConstraint ||
                source.HasReferenceTypeConstraint != expected.HasReferenceTypeConstraint ||
                source.ReferenceTypeConstraintNullableAnnotation !=
                    expected.ReferenceTypeConstraintNullableAnnotation ||
                source.HasValueTypeConstraint != expected.HasValueTypeConstraint ||
                source.HasUnmanagedTypeConstraint != expected.HasUnmanagedTypeConstraint ||
                source.HasNotNullConstraint != expected.HasNotNullConstraint ||
                !TypeArraysEqual(
                    source.ConstraintTypes,
                    expected.ConstraintTypes))
                return false;
        }
        return true;
    }

    private static bool TypeArraysEqual(
        ImmutableArray<ITypeSymbol> left,
        ImmutableArray<ITypeSymbol> right)
    {
        if (left.Length != right.Length)
            return false;
        for (int index = 0; index < left.Length; index++)
        {
            if (!SymbolEqualityComparer.Default.Equals(left[index], right[index]))
                return false;
        }
        return true;
    }

    private static bool ParametersMatch(
        ImmutableArray<IParameterSymbol> left,
        ImmutableArray<IParameterSymbol> right)
    {
        if (left.Length != right.Length)
            return false;
        for (int index = 0; index < left.Length; index++)
        {
            if (left[index].RefKind != right[index].RefKind ||
                !SymbolEqualityComparer.Default.Equals(
                    left[index].Type,
                    right[index].Type))
                return false;
        }
        return true;
    }

    private static bool IsSourceName(string actualName, string kotlinName)
    {
        return string.Equals(actualName, kotlinName, StringComparison.Ordinal) ||
            string.Equals(actualName, PascalCase(kotlinName), StringComparison.Ordinal);
    }

    private static string PascalCase(string value)
    {
        if (value.Length == 0 || char.IsUpper(value[0]))
            return value;
        return char.ToUpperInvariant(value[0]) + value.Substring(1);
    }

    private static bool HasEffectiveDim(
        INamedTypeSymbol implementationType,
        IMethodSymbol physicalMethod)
    {
        ISymbol? implementation =
            implementationType.FindImplementationForInterfaceMember(physicalMethod);
        return implementation is IMethodSymbol method &&
            method.ContainingType.TypeKind == TypeKind.Interface &&
            !method.IsAbstract;
    }

    private static KotlinSlotRole AuthoringRole(
        KotlinInterfaceContract contract,
        KotlinInterfaceView view)
    {
        switch (view)
        {
            case KotlinInterfaceView.Canonical:
                return contract.TypeParameters.IsEmpty
                    ? KotlinSlotRole.Canonical
                    : KotlinSlotRole.Erased;
            case KotlinInterfaceView.Declared:
                return KotlinSlotRole.Declared;
            case KotlinInterfaceView.Exact:
                return KotlinSlotRole.Exact;
            default:
                throw new InvalidOperationException("Unknown Kotlin authoring view.");
        }
    }

    private static IMethodSymbol? ResolveMethod(
        IAssemblySymbol assembly,
        KotlinMethodLocator locator,
        ImmutableArray<ITypeSymbol> ownerTypeArguments = default)
    {
        INamedTypeSymbol? ownerDefinition =
            ResolveType(assembly, locator.OwnerPath);
        if (ownerDefinition == null)
            return null;
        IMethodSymbol[] definitions = ownerDefinition
            .GetMembers(locator.MethodName)
            .OfType<IMethodSymbol>()
            .Where(method => MethodMatchesLocator(method, locator))
            .ToArray();
        if (definitions.Length != 1)
            return null;
        IMethodSymbol definition = definitions[0];
        if (ownerDefinition.Arity == 0)
            return definition;
        if (ownerTypeArguments.IsDefault ||
            ownerDefinition.Arity != ownerTypeArguments.Length)
            return null;
        INamedTypeSymbol owner = ownerDefinition.Construct(
            ownerTypeArguments.ToArray());
        IMethodSymbol[] constructedMethods = owner
            .GetMembers(locator.MethodName)
            .OfType<IMethodSymbol>()
            .Where(method => SymbolEqualityComparer.Default.Equals(
                method.OriginalDefinition,
                definition))
            .ToArray();
        return constructedMethods.Length == 1
            ? constructedMethods[0]
            : null;
    }

    private static bool MethodMatchesLocator(
        IMethodSymbol method,
        KotlinMethodLocator locator)
    {
        if (method.Arity != locator.GenericArity ||
            method.Parameters.Length != locator.ParameterTypes.Length ||
            !TryPhysicalSignatureType(
                method.ReturnType,
                method.ContainingAssembly,
                returnsVoid: method.ReturnsVoid,
                out string returnType) ||
            !PhysicalSignatureEquals(
                returnType,
                locator.ReturnType,
                method.ContainingAssembly.Identity.Name))
            return false;
        for (int index = 0; index < method.Parameters.Length; index++)
        {
            if (method.Parameters[index].RefKind != RefKind.None ||
                !TryPhysicalSignatureType(
                    method.Parameters[index].Type,
                    method.ContainingAssembly,
                    returnsVoid: false,
                    out string parameterType) ||
                !PhysicalSignatureEquals(
                    parameterType,
                    locator.ParameterTypes[index],
                    method.ContainingAssembly.Identity.Name))
                return false;
        }
        return true;
    }

    private static bool TryPhysicalSignatureType(
        ITypeSymbol type,
        IAssemblySymbol declaringAssembly,
        bool returnsVoid,
        out string signature)
    {
        if (returnsVoid)
        {
            signature = "void";
            return type.SpecialType == SpecialType.System_Void;
        }
        switch (type.SpecialType)
        {
            case SpecialType.System_Boolean:
                signature = "bool";
                return true;
            case SpecialType.System_Int32:
                signature = "int32";
                return true;
            case SpecialType.System_Int64:
                signature = "int64";
                return true;
            case SpecialType.System_Double:
                signature = "float64";
                return true;
            case SpecialType.System_Char:
                signature = "char";
                return true;
            case SpecialType.System_String:
                signature = "string";
                return true;
            case SpecialType.System_Object:
                signature = "object";
                return true;
            case SpecialType.System_Void:
                signature = "";
                return false;
        }
        if (type is ITypeParameterSymbol typeParameter)
        {
            signature = (typeParameter.TypeParameterKind ==
                    TypeParameterKind.Method
                    ? "!!"
                    : "!") + typeParameter.Ordinal;
            return true;
        }
        if (type is IArrayTypeSymbol arrayType &&
            arrayType.Rank == 1 &&
            arrayType.IsSZArray &&
            TryPhysicalSignatureType(
                arrayType.ElementType,
                declaringAssembly,
                returnsVoid: false,
                out string elementType))
        {
            signature = elementType + "[]";
            return true;
        }
        if (!(type is INamedTypeSymbol namedType) ||
            namedType.TypeKind == TypeKind.Error)
        {
            signature = "";
            return false;
        }
        if (namedType.OriginalDefinition.SpecialType ==
            SpecialType.System_Nullable_T)
        {
            if (namedType.TypeArguments.Length != 1 ||
                !TryPhysicalSignatureType(
                    namedType.TypeArguments[0],
                    declaringAssembly,
                    returnsVoid: false,
                    out string nullableElement))
            {
                signature = "";
                return false;
            }
            signature = "valuetype [" +
                namedType.ContainingAssembly.Identity.Name +
                "]System.Nullable`1<" + nullableElement + ">";
            return true;
        }

        string typeReference = PhysicalTypeReference(
            namedType.OriginalDefinition);
        if (!SymbolEqualityComparer.Default.Equals(
                namedType.ContainingAssembly,
                declaringAssembly))
        {
            typeReference = "[" +
                namedType.ContainingAssembly.Identity.Name +
                "]" + typeReference;
        }
        string prefix = namedType.IsValueType ? "valuetype " : "class ";
        if (namedType.Arity == 0)
        {
            signature = prefix + typeReference;
            return true;
        }
        var arguments = new List<string>();
        foreach (ITypeSymbol argument in namedType.TypeArguments)
        {
            if (!TryPhysicalSignatureType(
                    argument,
                    declaringAssembly,
                    returnsVoid: false,
                    out string argumentSignature))
            {
                signature = "";
                return false;
            }
            arguments.Add(argumentSignature);
        }
        signature = prefix + typeReference + "<" +
            string.Join(", ", arguments) + ">";
        return true;
    }

    private static string PhysicalTypeReference(INamedTypeSymbol type)
    {
        string current = IlIdentifier(type.MetadataName);
        if (type.ContainingType != null)
            return PhysicalTypeReference(type.ContainingType) + "/" + current;
        string namespaceName = type.ContainingNamespace?.ToDisplayString() ?? "";
        return IlIdentifier(
            namespaceName.Length == 0
                ? type.MetadataName
                : namespaceName + "." + type.MetadataName);
    }

    private static string IlIdentifier(string value)
    {
        return "'" +
            value.Replace("\\", "\\\\").Replace("'", "\\'") +
            "'";
    }

    private static bool PhysicalSignatureEquals(
        string symbolSignature,
        string locatorSignature,
        string declaringAssemblyName)
    {
        string localAssemblyPrefix = "[" + declaringAssemblyName + "]";
        return string.Equals(
            NormalizePhysicalSignature(
                symbolSignature,
                localAssemblyPrefix),
            NormalizePhysicalSignature(
                locatorSignature,
                localAssemblyPrefix),
            StringComparison.Ordinal);
    }

    private static string NormalizePhysicalSignature(
        string signature,
        string localAssemblyPrefix)
    {
        var result = new StringBuilder(signature.Length);
        bool quoted = false;
        for (int index = 0; index < signature.Length; index++)
        {
            char current = signature[index];
            if (!quoted &&
                MatchesAt(signature, index, localAssemblyPrefix))
            {
                index += localAssemblyPrefix.Length - 1;
                continue;
            }
            if (!quoted &&
                TryCoreLibraryPrefixLength(
                    signature,
                    index,
                    out int corePrefixLength))
            {
                result.Append("[corelib]");
                index += corePrefixLength - 1;
                continue;
            }
            if (current == '\'')
            {
                quoted = !quoted;
                continue;
            }
            if (quoted && current == '\\' && index + 1 < signature.Length)
            {
                char next = signature[index + 1];
                if (next == '\\' || next == '\'')
                {
                    result.Append(next);
                    index++;
                    continue;
                }
            }
            result.Append(current);
        }
        return quoted ? signature : result.ToString();
    }

    private static bool TryCoreLibraryPrefixLength(
        string signature,
        int index,
        out int prefixLength)
    {
        foreach (string prefix in CoreLibraryAssemblyPrefixes)
        {
            int systemNameIndex = index + prefix.Length;
            if (MatchesAt(signature, index, prefix) &&
                MatchesAt(signature, systemNameIndex, "System."))
            {
                prefixLength = prefix.Length;
                return true;
            }
        }
        prefixLength = 0;
        return false;
    }

    private static bool MatchesAt(
        string value,
        int index,
        string expected)
    {
        return index >= 0 &&
            index + expected.Length <= value.Length &&
            string.CompareOrdinal(
                value,
                index,
                expected,
                0,
                expected.Length) == 0;
    }

    private static INamedTypeSymbol? ResolveType(
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

    private static bool TryHelperCall(
        IAssemblySymbol assembly,
        KotlinMethodLocator helper,
        ImmutableArray<ITypeSymbol> typeArguments,
        string arguments,
        out string call,
        out IMethodSymbol? helperMethod)
    {
        helperMethod = ResolveMethod(assembly, helper);
        if (helperMethod == null ||
            !helperMethod.IsStatic ||
            helperMethod.Arity != typeArguments.Length ||
            !TryCSharpIdentifier(helperMethod.Name, out string helperName))
        {
            call = "";
            return false;
        }
        if (helperMethod.Arity != 0)
            helperMethod = helperMethod.Construct(typeArguments.ToArray());
        call = DisplayType(helperMethod.ContainingType) +
            "." + helperName;
        if (!typeArguments.IsEmpty)
        {
            call += "<" + string.Join(
                ", ",
                typeArguments.Select(DisplayType)) + ">";
        }
        call += "(" + arguments + ")";
        return true;
    }

    private static ImmutableArray<ITypeSymbol> HelperTypeArguments(
        ResolvedMember resolved,
        IMethodSymbol physicalMethod,
        KotlinMethodLocator helper)
    {
        ImmutableArray<ITypeSymbol> methodArguments =
            physicalMethod.TypeParameters.Cast<ITypeSymbol>().ToImmutableArray();
        ImmutableArray<ITypeSymbol> allArguments =
            resolved.OwnerTypeArguments.AddRange(methodArguments);
        if (helper.GenericArity == allArguments.Length)
            return allArguments;
        if (helper.GenericArity == methodArguments.Length)
            return methodArguments;
        return ImmutableArray<ITypeSymbol>.Empty;
    }

    private static string ParameterDeclaration(
        IParameterSymbol parameter,
        int index)
    {
        string prefix;
        switch (parameter.RefKind)
        {
            case RefKind.Ref:
                prefix = "ref ";
                break;
            case RefKind.Out:
                prefix = "out ";
                break;
            case RefKind.In:
                prefix = "in ";
                break;
            default:
                prefix = "";
                break;
        }
        return prefix + DisplayType(parameter.Type) + " p" + index;
    }

    private static string DisplayType(ITypeSymbol type)
    {
        return type.ToDisplayString(TypeDisplayFormat);
    }

    private static bool TryConvertExpression(
        Compilation compilation,
        ITypeSymbol sourceType,
        ITypeSymbol targetType,
        string expression,
        out string convertedExpression)
    {
        if (SymbolEqualityComparer.Default.Equals(sourceType, targetType))
        {
            convertedExpression = expression;
            return true;
        }
        if (!(compilation is CSharpCompilation csharpCompilation) ||
            !csharpCompilation.ClassifyConversion(sourceType, targetType).Exists)
        {
            convertedExpression = "";
            return false;
        }
        convertedExpression =
            "(" + DisplayType(targetType) + ")(" + expression + ")";
        return true;
    }

    private static void ReportUnsupportedConversion(
        AuthoringContract authoringContract,
        ResolvedMember member,
        ITypeSymbol sourceType,
        ITypeSymbol targetType,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        ReportUnsupportedConversion(
            authoringContract,
            member.Method,
            sourceType,
            targetType,
            diagnostics);
    }

    private static void ReportUnsupportedConversion(
        AuthoringContract authoringContract,
        ISymbol physicalMember,
        ITypeSymbol sourceType,
        ITypeSymbol targetType,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        diagnostics.Add(Diagnostic.Create(
            Diagnostics.UnsupportedToolingShape,
            authoringContract.Declaration.Identifier.GetLocation(),
            physicalMember.ContainingType.ToDisplayString(),
            $"conversion from '{DisplayType(sourceType)}' to '{DisplayType(targetType)}'"));
    }

    private static bool TryCSharpIdentifier(string name, out string escaped)
    {
        if (!SyntaxFacts.IsValidIdentifier(name))
        {
            escaped = "";
            return false;
        }
        escaped = EscapeIdentifier(name);
        return true;
    }

    private static string EscapeIdentifier(string name)
    {
        return SyntaxFacts.GetKeywordKind(name) == SyntaxKind.None
            ? name
            : "@" + name;
    }

    private static void ReportMissingSourceMember(
        AuthoringContract authoringContract,
        ResolvedMember member,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        diagnostics.Add(Diagnostic.Create(
            Diagnostics.MissingSourceMember,
            authoringContract.Declaration.Identifier.GetLocation(),
            authoringContract.ImplementationType.ToDisplayString(),
            member.SourceName,
            member.SourceLogicalKey));
    }

    private sealed class IntersectionBinding
    {
        internal IntersectionBinding(
            BoundKotlinInterface owner,
            KotlinIntersectionContract contract,
            IMethodSymbol authoringMethod)
        {
            Owner = owner;
            Contract = contract;
            AuthoringMethod = authoringMethod;
        }

        internal BoundKotlinInterface Owner { get; }
        internal KotlinIntersectionContract Contract { get; }
        internal IMethodSymbol AuthoringMethod { get; }
    }

    private sealed class ResolvedMember
    {
        internal ResolvedMember(
            KotlinMemberContract member,
            KotlinMethodLocator locator,
            IMethodSymbol method,
            IMethodSymbol authoringMethod,
            ImmutableArray<ITypeSymbol> ownerTypeArguments,
            IAssemblySymbol memberAssembly,
            string sourceName,
            string sourceLogicalKey,
            bool requiresSource,
            bool requiresDimAdapter)
        {
            Member = member;
            Locator = locator;
            Method = method;
            AuthoringMethod = authoringMethod;
            OwnerTypeArguments = ownerTypeArguments;
            MemberAssembly = memberAssembly;
            SourceName = sourceName;
            SourceLogicalKey = sourceLogicalKey;
            RequiresSource = requiresSource;
            RequiresDimAdapter = requiresDimAdapter;
        }

        internal KotlinMemberContract Member { get; }
        internal KotlinMethodLocator Locator { get; }
        internal IMethodSymbol Method { get; }
        internal IMethodSymbol AuthoringMethod { get; }
        internal ImmutableArray<ITypeSymbol> OwnerTypeArguments { get; }
        internal IAssemblySymbol MemberAssembly { get; }
        internal string SourceName { get; }
        internal string SourceLogicalKey { get; }
        internal bool RequiresSource { get; }
        internal bool RequiresDimAdapter { get; }
    }

    private sealed class MemberBinding
    {
        internal MemberBinding(
            BoundKotlinInterface bound,
            KotlinMemberContract member)
        {
            Bound = bound;
            Member = member;
        }

        internal BoundKotlinInterface Bound { get; }
        internal KotlinMemberContract Member { get; }
    }

    private sealed class OverrideResolution
    {
        internal OverrideResolution(
            IReadOnlyDictionary<string, MemberBinding> selectedByOverriddenKey,
            ISet<string> ambiguousOverriddenKeys)
        {
            SelectedByOverriddenKey = selectedByOverriddenKey;
            AmbiguousOverriddenKeys = ambiguousOverriddenKeys;
        }

        internal IReadOnlyDictionary<string, MemberBinding> SelectedByOverriddenKey { get; }
        internal ISet<string> AmbiguousOverriddenKeys { get; }
    }

    private readonly struct PropertyIdentity : IEquatable<PropertyIdentity>
    {
        private readonly INamedTypeSymbol owner;
        private readonly string? propertyName;

        internal PropertyIdentity(
            INamedTypeSymbol owner,
            string? propertyName)
        {
            this.owner = owner;
            this.propertyName = propertyName;
        }

        public bool Equals(PropertyIdentity other)
        {
            return SymbolEqualityComparer.Default.Equals(owner, other.owner) &&
                string.Equals(propertyName, other.propertyName, StringComparison.Ordinal);
        }

        public override bool Equals(object? value)
        {
            return value is PropertyIdentity other && Equals(other);
        }

        public override int GetHashCode()
        {
            return SymbolEqualityComparer.Default.GetHashCode(owner) * 31 +
                (propertyName == null
                    ? 0
                    : StringComparer.Ordinal.GetHashCode(propertyName));
        }
    }

    private sealed class NamedTypeSymbolEqualityComparer :
        IEqualityComparer<INamedTypeSymbol>
    {
        internal static readonly NamedTypeSymbolEqualityComparer Instance =
            new NamedTypeSymbolEqualityComparer();

        public bool Equals(INamedTypeSymbol? left, INamedTypeSymbol? right)
        {
            return SymbolEqualityComparer.Default.Equals(left, right);
        }

        public int GetHashCode(INamedTypeSymbol value)
        {
            return SymbolEqualityComparer.Default.GetHashCode(value);
        }
    }
}
