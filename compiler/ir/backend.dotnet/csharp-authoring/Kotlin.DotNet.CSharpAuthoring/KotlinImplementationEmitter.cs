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
        ImmutableArray<Diagnostic> diagnostics)
    {
        GeneratedMembers = generatedMembers;
        Diagnostics = diagnostics;
    }

    internal string GeneratedMembers { get; }
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

    internal static KotlinImplementationEmission Emit(AuthoringContract authoringContract)
    {
        if (authoringContract.ImplementationType.ContainingType != null)
        {
            return new KotlinImplementationEmission(
                "",
                ImmutableArray.Create(Diagnostic.Create(
                    Diagnostics.UnsupportedToolingShape,
                    authoringContract.Declaration.Identifier.GetLocation(),
                    authoringContract.Interfaces[0].InterfaceType.ToDisplayString(),
                    "a nested C# implementor requires containing partial declarations")));
        }

        // Generic split views require substitution-aware construction and are the next emission
        // slice. Keep their already-tested discovery seam without guessing an adapter shape.
        if (authoringContract.Interfaces.Any(bound =>
                !bound.Contract.TypeParameters.IsEmpty))
            return new KotlinImplementationEmission(
                "",
                ImmutableArray<Diagnostic>.Empty);

        var diagnostics = ImmutableArray.CreateBuilder<Diagnostic>();
        var generatedMembers = new StringBuilder();
        foreach (BoundKotlinInterface bound in authoringContract.Interfaces)
        {
            if (!bound.Contract.Intersections.IsEmpty)
            {
                diagnostics.Add(Diagnostic.Create(
                    Diagnostics.UnsupportedToolingShape,
                    authoringContract.Declaration.Identifier.GetLocation(),
                    bound.InterfaceType.ToDisplayString(),
                    "non-generic intersection slots"));
                continue;
            }

            EmitOrdinaryContract(
                authoringContract,
                bound,
                generatedMembers,
                diagnostics);
        }
        return new KotlinImplementationEmission(
            generatedMembers.ToString(),
            diagnostics.ToImmutable());
    }

    private static void EmitOrdinaryContract(
        AuthoringContract authoringContract,
        BoundKotlinInterface bound,
        StringBuilder output,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        var resolvedMembers = new List<ResolvedMember>();
        foreach (KotlinMemberContract member in bound.Contract.Members)
        {
            KotlinMethodLocator[] physicalSlots = member.Slots.Where(slot =>
                    slot.Role != KotlinSlotRole.Helper)
                .ToArray();
            if (physicalSlots.Length != 1 ||
                physicalSlots[0].Role != KotlinSlotRole.Canonical)
            {
                diagnostics.Add(Diagnostic.Create(
                    Diagnostics.UnsupportedToolingShape,
                    authoringContract.Declaration.Identifier.GetLocation(),
                    bound.InterfaceType.ToDisplayString(),
                    $"ordinary member '{member.SourceName}' has a split physical surface"));
                continue;
            }
            KotlinMethodLocator locator = physicalSlots[0];
            IMethodSymbol? method = ResolveMethod(
                bound.Reference.Assembly,
                locator);
            if (method == null)
            {
                diagnostics.Add(Diagnostic.Create(
                    Diagnostics.MalformedManifest,
                    authoringContract.Declaration.Identifier.GetLocation(),
                    bound.Reference.Assembly.Identity.Name,
                    $"method locator for '{member.LogicalKey}' does not resolve uniquely"));
                continue;
            }
            resolvedMembers.Add(new ResolvedMember(member, locator, method));
        }

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
        if (physicalProperty.RefKind != RefKind.None)
        {
            diagnostics.Add(Diagnostic.Create(
                Diagnostics.UnsupportedToolingShape,
                authoringContract.Declaration.Identifier.GetLocation(),
                physicalProperty.ContainingType.ToDisplayString(),
                $"ref-returning property '{physicalProperty.Name}'"));
            return;
        }
        string sourceName = accessors[0].Member.SourceName;
        IPropertySymbol? sourceProperty = FindSourceProperty(
            authoringContract.ImplementationType,
            sourceName,
            physicalProperty.Type,
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
                        accessor.Member,
                        diagnostics);
                    return null;
                }
                return "{ return this." + EscapeIdentifier(sourceProperty.Name) + "; }";
            }
            if (sourceProperty.SetMethod == null)
            {
                ReportMissingSourceMember(
                    authoringContract,
                    accessor.Member,
                    diagnostics);
                return null;
            }
            return "{ this." + EscapeIdentifier(sourceProperty.Name) + " = value; }";
        }

        if (HasEffectiveDim(authoringContract.ImplementationType, accessor.Method))
            return null;
        switch (accessor.Member.DefaultKind)
        {
            case KotlinDefaultKind.Abstract:
                ReportMissingSourceMember(
                    authoringContract,
                    accessor.Member,
                    diagnostics);
                return null;
            case KotlinDefaultKind.PortableHelper:
                return HelperAccessorBody(
                    authoringContract,
                    accessor,
                    diagnostics);
            case KotlinDefaultKind.DimWithHelper:
                return null;
            default:
                throw new InvalidOperationException("Unknown Kotlin default kind.");
        }
    }

    private static string? HelperAccessorBody(
        AuthoringContract authoringContract,
        ResolvedMember accessor,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        KotlinMethodLocator? helper = accessor.Member.Slots.SingleOrDefault(slot =>
            slot.Role == KotlinSlotRole.Helper);
        if (helper == null ||
            !TryHelperCall(
                accessor.Method.ContainingAssembly,
                helper,
                accessor.Member.Kind == KotlinMemberKind.PropertySetter
                    ? "this, value"
                    : "this",
                out string call))
        {
            diagnostics.Add(Diagnostic.Create(
                Diagnostics.MalformedManifest,
                authoringContract.Declaration.Identifier.GetLocation(),
                accessor.Method.ContainingAssembly.Identity.Name,
                $"helper locator for '{accessor.Member.LogicalKey}' cannot be emitted"));
            return null;
        }
        return accessor.Member.Kind == KotlinMemberKind.PropertyGetter
            ? "{ return " + call + "; }"
            : "{ " + call + "; }";
    }

    private static void EmitMethod(
        AuthoringContract authoringContract,
        ResolvedMember resolved,
        StringBuilder output,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        IMethodSymbol physicalMethod = resolved.Method;
        if (physicalMethod.Arity != 0 ||
            physicalMethod.ReturnsByRef ||
            physicalMethod.ReturnsByRefReadonly ||
            physicalMethod.Parameters.Any(parameter =>
                parameter.RefKind != RefKind.None))
        {
            diagnostics.Add(Diagnostic.Create(
                Diagnostics.UnsupportedToolingShape,
                authoringContract.Declaration.Identifier.GetLocation(),
                physicalMethod.ContainingType.ToDisplayString(),
                $"generic or by-reference method '{resolved.Member.SourceName}'"));
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
            resolved.Member.SourceName,
            physicalMethod,
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
            body = MethodSourceBody(sourceMethod, physicalMethod);
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
                        resolved.Member,
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
                            physicalMethod.ContainingAssembly,
                            helper,
                            arguments,
                            out string call))
                    {
                        diagnostics.Add(Diagnostic.Create(
                            Diagnostics.MalformedManifest,
                            authoringContract.Declaration.Identifier.GetLocation(),
                            physicalMethod.ContainingAssembly.Identity.Name,
                            $"helper locator for '{resolved.Member.LogicalKey}' cannot be emitted"));
                        return;
                    }
                    body = physicalMethod.ReturnsVoid
                        ? "{ " + call + "; }"
                        : "{ return " + call + "; }";
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
        output.Append('(');
        output.Append(string.Join(
            ", ",
            physicalMethod.Parameters.Select(ParameterDeclaration)));
        output.Append(") ");
        output.AppendLine(body);
        output.AppendLine();
    }

    private static string MethodSourceBody(
        IMethodSymbol sourceMethod,
        IMethodSymbol physicalMethod)
    {
        string call = "this." + EscapeIdentifier(sourceMethod.Name) + "(" +
            string.Join(
                ", ",
                physicalMethod.Parameters.Select((_, index) => "p" + index)) +
            ")";
        return physicalMethod.ReturnsVoid
            ? "{ " + call + "; }"
            : "{ return " + call + "; }";
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
                SymbolEqualityComparer.Default.Equals(
                    method.ReturnType,
                    expectedMethod.ReturnType) &&
                ParametersMatch(method.Parameters, expectedMethod.Parameters))
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

    private static IMethodSymbol? ResolveMethod(
        IAssemblySymbol assembly,
        KotlinMethodLocator locator)
    {
        INamedTypeSymbol? owner = ResolveType(assembly, locator.OwnerPath);
        if (owner == null)
            return null;
        IMethodSymbol[] candidates = owner.GetMembers(locator.MethodName)
            .OfType<IMethodSymbol>()
            .Where(method =>
                method.Arity == locator.GenericArity &&
                method.Parameters.Length == locator.ParameterTypes.Length)
            .ToArray();
        return candidates.Length == 1 ? candidates[0] : null;
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
        string arguments,
        out string call)
    {
        IMethodSymbol? helperMethod = ResolveMethod(assembly, helper);
        if (helperMethod == null ||
            !helperMethod.IsStatic ||
            !TryCSharpIdentifier(helperMethod.Name, out string helperName))
        {
            call = "";
            return false;
        }
        call = DisplayType(helperMethod.ContainingType) +
            "." + helperName + "(" + arguments + ")";
        return true;
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
        KotlinMemberContract member,
        ImmutableArray<Diagnostic>.Builder diagnostics)
    {
        diagnostics.Add(Diagnostic.Create(
            Diagnostics.MissingSourceMember,
            authoringContract.Declaration.Identifier.GetLocation(),
            authoringContract.ImplementationType.ToDisplayString(),
            member.SourceName,
            member.LogicalKey));
    }

    private sealed class ResolvedMember
    {
        internal ResolvedMember(
            KotlinMemberContract member,
            KotlinMethodLocator locator,
            IMethodSymbol method)
        {
            Member = member;
            Locator = locator;
            Method = method;
        }

        internal KotlinMemberContract Member { get; }
        internal KotlinMethodLocator Locator { get; }
        internal IMethodSymbol Method { get; }
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
}
