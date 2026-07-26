/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

using Microsoft.CodeAnalysis;

namespace Kotlin.DotNet.CSharpAuthoring;

internal static class Diagnostics
{
    private const string Category = "Kotlin.NET";

    internal static readonly DiagnosticDescriptor MissingPartial = new DiagnosticDescriptor(
        "KDNCS001",
        "Kotlin interface implementor must be partial",
        "C# type '{0}' implements Kotlin interface '{1}' and must be declared partial",
        Category,
        DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    internal static readonly DiagnosticDescriptor InaccessibleContract = new DiagnosticDescriptor(
        "KDNCS002",
        "Kotlin interface contract is not accessible",
        "Kotlin interface '{0}' is not accessible from assembly '{1}'; add producer-authorized friendship instead of bypassing CLR accessibility",
        Category,
        DiagnosticSeverity.Error,
        isEnabledByDefault: true,
        customTags: WellKnownDiagnosticTags.CompilationEnd);

    internal static readonly DiagnosticDescriptor ConflictingMember = new DiagnosticDescriptor(
        "KDNCS003",
        "Member conflicts with generated Kotlin ABI",
        "Member '{0}' conflicts with the generated implementation of Kotlin slot '{1}'",
        Category,
        DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    internal static readonly DiagnosticDescriptor UnsupportedSubstitution = new DiagnosticDescriptor(
        "KDNCS004",
        "Kotlin interface substitution is not supported",
        "Kotlin interface '{0}' uses a substitution that this generator cannot represent: {1}",
        Category,
        DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    internal static readonly DiagnosticDescriptor VersionMismatch = new DiagnosticDescriptor(
        "KDNCS005",
        "Kotlin C# tooling version mismatch",
        "Referenced Kotlin assembly '{0}' is incompatible with this generator: {1}",
        Category,
        DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    internal static readonly DiagnosticDescriptor MalformedManifest = new DiagnosticDescriptor(
        "KDNCS006",
        "Kotlin C# implementation manifest is invalid",
        "Referenced Kotlin assembly '{0}' has an invalid implementation manifest: {1}",
        Category,
        DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    internal static readonly DiagnosticDescriptor UnsupportedContract = new DiagnosticDescriptor(
        "KDNCS007",
        "Kotlin interface is not source-authorable",
        "Kotlin interface '{0}' cannot be implemented through the C# generator: {1}",
        Category,
        DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    internal static readonly DiagnosticDescriptor MissingSourceMember = new DiagnosticDescriptor(
        "KDNCS008",
        "Kotlin interface member has no C# body",
        "C# type '{0}' must provide source member '{1}' for Kotlin member '{2}'",
        Category,
        DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    internal static readonly DiagnosticDescriptor ErasedOwnerConstraint = new DiagnosticDescriptor(
        "KDNCS009",
        "Kotlin owner-relative constraint is erased in CLR metadata",
        "Kotlin member '{0}' has an owner-relative generic constraint erased from the CLR slot; do not add a C# where clause for it",
        Category,
        DiagnosticSeverity.Info,
        isEnabledByDefault: true);

    internal static readonly DiagnosticDescriptor UnsupportedToolingShape = new DiagnosticDescriptor(
        "KDNCS010",
        "Kotlin interface implementation shape is not generated yet",
        "Kotlin interface '{0}' uses a supported manifest contract whose C# adapter shape is not implemented by this generator version: {1}",
        Category,
        DiagnosticSeverity.Error,
        isEnabledByDefault: true);
}
