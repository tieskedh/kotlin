/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

using System.Collections.Immutable;
using System.Linq;
using Kotlin.DotNet.CSharpAuthoring.Manifest;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp.Syntax;
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
        foreach (Diagnostic compilerDiagnostic in context.Compilation.GetDiagnostics(
                     context.CancellationToken))
        {
            if (compilerDiagnostic.Id != "CS0122" ||
                !compilerDiagnostic.Location.IsInSource ||
                compilerDiagnostic.Location.SourceTree == null)
                continue;
            SyntaxNode root = compilerDiagnostic.Location.SourceTree.GetRoot(
                context.CancellationToken);
            BaseTypeSyntax? baseType = root
                .FindNode(compilerDiagnostic.Location.SourceSpan)
                .AncestorsAndSelf()
                .OfType<BaseTypeSyntax>()
                .FirstOrDefault();
            if (baseType == null)
                continue;
            string? contractName = AuthoringContractDiscovery.KotlinAuthoringBaseName(
                baseType.Type,
                manifests);
            if (contractName == null)
                continue;
            context.ReportDiagnostic(Diagnostic.Create(
                Diagnostics.InaccessibleContract,
                compilerDiagnostic.Location,
                contractName,
                context.Compilation.AssemblyName ?? "<unnamed>"));
        }
        foreach (AuthoringContract contract in AuthoringContractDiscovery.Discover(
            context.Compilation,
            manifests,
            context.ReportDiagnostic))
        {
            foreach (Diagnostic diagnostic in KotlinImplementationEmitter
                         .Emit(contract)
                         .Diagnostics)
                context.ReportDiagnostic(diagnostic);
        }
    }
}
