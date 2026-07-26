/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

using System.Collections.Immutable;
using Kotlin.DotNet.CSharpAuthoring.Manifest;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.Diagnostics;

namespace Kotlin.DotNet.CSharpAuthoring;

/// <summary>
/// Diagnoses invalid C# source-authoring attempts for Kotlin-owned interfaces.
/// </summary>
[DiagnosticAnalyzer(LanguageNames.CSharp)]
public sealed class KotlinInterfaceImplementationAnalyzer : DiagnosticAnalyzer
{
    /// <inheritdoc />
    public override ImmutableArray<DiagnosticDescriptor> SupportedDiagnostics =>
        ImmutableArray.Create(
            Diagnostics.MissingPartial,
            Diagnostics.InaccessibleContract,
            Diagnostics.ConflictingMember,
            Diagnostics.UnsupportedSubstitution,
            Diagnostics.VersionMismatch,
            Diagnostics.MalformedManifest,
            Diagnostics.UnsupportedContract,
            Diagnostics.MissingSourceMember,
            Diagnostics.ErasedOwnerConstraint,
            Diagnostics.UnsupportedToolingShape,
            Diagnostics.MissingContainingPartial);

    /// <inheritdoc />
    public override void Initialize(AnalysisContext context)
    {
        context.ConfigureGeneratedCodeAnalysis(GeneratedCodeAnalysisFlags.None);
        context.EnableConcurrentExecution();
        context.RegisterCompilationAction(AnalyzeCompilation);
    }

    private static void AnalyzeCompilation(CompilationAnalysisContext context)
    {
        KotlinManifestSet manifests = ManifestReader.Read(context.Compilation);
        foreach (KotlinManifestProblem problem in manifests.Problems)
        {
            context.ReportDiagnostic(Diagnostic.Create(
                problem.VersionMismatch
                    ? Diagnostics.VersionMismatch
                    : Diagnostics.MalformedManifest,
                Location.None,
                problem.Assembly.Identity.Name,
                problem.Message));
        }
        ImmutableArray<Diagnostic> compilerErrors =
            DiagnosticOwnership.CompilerErrors(
                context.Compilation,
                context.CancellationToken);
        foreach (AuthoringContract contract in AuthoringContractDiscovery.Discover(
            context.Compilation,
            manifests,
            diagnostic =>
            {
                if (!DiagnosticOwnership.HasBlockingCompilerError(
                        diagnostic.Location,
                        compilerErrors,
                        context.CancellationToken))
                    context.ReportDiagnostic(diagnostic);
            }))
        {
            foreach (Diagnostic diagnostic in KotlinImplementationEmitter
                         .Emit(contract)
                         .Diagnostics)
            {
                if (!DiagnosticOwnership.HasBlockingCompilerError(
                        diagnostic.Location,
                        compilerErrors,
                        context.CancellationToken))
                    context.ReportDiagnostic(diagnostic);
            }
        }
    }
}
