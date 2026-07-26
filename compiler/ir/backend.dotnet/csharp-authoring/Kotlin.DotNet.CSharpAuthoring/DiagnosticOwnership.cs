/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

using System;
using System.Collections.Immutable;
using System.Linq;
using System.Threading;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp.Syntax;

namespace Kotlin.DotNet.CSharpAuthoring;

internal static class DiagnosticOwnership
{
    internal static ImmutableArray<Diagnostic> CompilerErrors(
        Compilation compilation,
        CancellationToken cancellationToken)
    {
        return compilation.GetDiagnostics(cancellationToken)
            .Where(diagnostic =>
                diagnostic.Severity == DiagnosticSeverity.Error &&
                diagnostic.Id.StartsWith("CS", StringComparison.Ordinal))
            .ToImmutableArray();
    }

    internal static bool HasBlockingCompilerError(
        Location location,
        ImmutableArray<Diagnostic> compilerErrors,
        CancellationToken cancellationToken)
    {
        if (!location.IsInSource || location.SourceTree == null)
            return false;
        SyntaxNode root = location.SourceTree.GetRoot(cancellationToken);
        TypeDeclarationSyntax? declaration = root
            .FindNode(
                location.SourceSpan,
                getInnermostNodeForTie: true)
            .AncestorsAndSelf()
            .OfType<TypeDeclarationSyntax>()
            .FirstOrDefault();
        if (declaration == null)
            return false;
        return compilerErrors.Any(error =>
            error.Location.IsInSource &&
            error.Location.SourceTree == location.SourceTree &&
            declaration.FullSpan.Contains(error.Location.SourceSpan));
    }
}
