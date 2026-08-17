<#
.SYNOPSIS
    Produces and verifies the paired generic-owner application corpus.

.DESCRIPTION
    Builds a separate-compilation application through PSI and LightTree on
    Framework CLR and CoreCLR, verifies the closed bundles,
    compiler-derived call-route manifests, and every SHA-256 fingerprint,
    compares exact executable CLR content and all KLIB entries outside the
    parser-owned body stream, and runs every candidate/erased application
    product present in the selected corpus schema.
#>
[CmdletBinding()]
param(
    [ValidateSet('hostile', 'octo-tree')]
    [string]$CorpusKind = 'hostile',
    [string]$OutputDirectory,
    [string]$ExistingBundle,
    [string]$ExistingCorpus,
    [ValidateSet('net10', 'net48')]
    [string[]]$Profiles = @('net10', 'net48')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
if ($Profiles.Count -eq 0 -or @($Profiles | Select-Object -Unique).Count -ne $Profiles.Count) {
    throw 'Application profiles must contain one or two unique values'
}
$specifiedLocations = @($OutputDirectory, $ExistingBundle, $ExistingCorpus) | Where-Object {
    -not [string]::IsNullOrWhiteSpace($_)
}
if ($specifiedLocations.Count -gt 1) {
    throw 'OutputDirectory, ExistingBundle, and ExistingCorpus are mutually exclusive'
}
$backendDirectory = Split-Path -Parent $PSScriptRoot
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Read-ApplicationManifest([string]$Path) {
    $values = [ordered]@{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw "Malformed application manifest line: $line" }
        $key = $line.Substring(0, $separator)
        if ($values.Contains($key)) { throw "Duplicate application manifest key: $key" }
        $values[$key] = $line.Substring($separator + 1)
    }
    return $values
}

function ConvertFrom-RouteText([string]$Value) {
    if ([string]::IsNullOrEmpty($Value)) {
        throw 'A generic-owner route contains empty encoded text'
    }
    $standard = $Value.Replace('-', '+').Replace('_', '/')
    switch ($standard.Length % 4) {
        0 { }
        2 { $standard += '==' }
        3 { $standard += '=' }
        default { throw "A generic-owner route contains invalid base64url text '$Value'" }
    }
    try {
        $bytes = [Convert]::FromBase64String($standard)
        $utf8 = New-Object System.Text.UTF8Encoding($false, $true)
        $decoded = $utf8.GetString($bytes)
    } catch {
        throw "A generic-owner route contains invalid encoded text '$Value'"
    }
    $canonical = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    if ($canonical -cne $Value) {
        throw "A generic-owner route contains non-canonical encoded text '$Value'"
    }
    return $decoded
}

function Read-CallRouteManifest([string]$Path, [string]$Kind) {
    $lines = @(Get-Content -LiteralPath $Path)
    if ($lines.Count -lt 2) { throw 'The generic-owner call-route manifest is truncated' }
    $header = @($lines[0].Split("`t", [StringSplitOptions]::None))
    if ($header.Count -ne 2 -or
            $header[0] -cne 'kotlin-dotnet-generic-owner-call-routes' -or $header[1] -cne '1') {
        throw "Unsupported generic-owner call-route manifest header '$($lines[0])'"
    }
    $countRecord = @($lines[1].Split("`t", [StringSplitOptions]::None))
    [int]$routeCount = 0
    if ($countRecord.Count -ne 2 -or $countRecord[0] -cne 'N' -or
            -not [int]::TryParse($countRecord[1], [ref]$routeCount) -or $routeCount -le 0 -or
            $lines.Count -ne 2 + $routeCount) {
        throw 'The generic-owner call-route manifest has an invalid route count'
    }
    $provenances = @('EXACT_CONSTRUCTION', 'SEMANTIC_VIEW', 'UNRESOLVED')
    $requirements = @(
        'EXACT_TYPED_ENTRY',
        'SEMANTIC_CAPABILITY',
        'MISSING_CAPABILITY',
        'PRODUCER_ERASED_OWNER'
    )
    $records = @()
    $previousIndex = -1
    for ($lineIndex = 2; $lineIndex -lt $lines.Count; $lineIndex++) {
        $fields = @($lines[$lineIndex].Split("`t", [StringSplitOptions]::None))
        [int]$callSiteIndex = -1
        if ($fields.Count -ne 7 -or $fields[0] -cne 'R' -or
                -not [int]::TryParse($fields[1], [ref]$callSiteIndex) -or
                $callSiteIndex -le $previousIndex) {
            throw "The generic-owner call-route manifest has an invalid ordered call-site at line $($lineIndex + 1)"
        }
        $previousIndex = $callSiteIndex
        if ($fields[2] -cnotin @('0', '1') -or
                ($fields[2] -ceq '0' -and $fields[3] -cne '-')) {
            throw "The generic-owner call-route manifest has an invalid caller binding at line $($lineIndex + 1)"
        }
        $callerKey = if ($fields[2] -ceq '1') { ConvertFrom-RouteText $fields[3] } else { $null }
        $calleeKey = ConvertFrom-RouteText $fields[4]
        if ([string]::IsNullOrEmpty($callerKey) -and $null -ne $callerKey -or
                [string]::IsNullOrEmpty($calleeKey) -or
                $callerKey -match '^[A-Za-z]:\\' -or $calleeKey -match '^[A-Za-z]:\\' -or
                $callerKey -match '^(?!F:/)[A-Za-z]:/' -or $calleeKey -match '^(?!F:/)[A-Za-z]:/') {
            throw "The generic-owner call-route manifest contains an empty or absolute logical binding"
        }
        if ($fields[5] -cnotin $provenances -or $fields[6] -cnotin $requirements -or
                ($fields[6] -ceq 'EXACT_TYPED_ENTRY' -and $fields[5] -cne 'EXACT_CONSTRUCTION') -or
                ($fields[6] -ceq 'SEMANTIC_CAPABILITY' -and $fields[5] -ceq 'EXACT_CONSTRUCTION')) {
            throw "The generic-owner call-route manifest has an invalid route at line $($lineIndex + 1)"
        }
        $records += [pscustomobject]@{
            CompilationCallSiteIndex = $callSiteIndex
            CallerLogicalBindingKey = $callerKey
            CalleeLogicalBindingKey = $calleeKey
            ReceiverProvenance = $fields[5]
            RouteRequirement = $fields[6]
        }
    }
    $expectedCounts = if ($Kind -eq 'hostile') {
        [ordered]@{
            PRODUCER_ERASED_OWNER = 24
            EXACT_TYPED_ENTRY = 18
            SEMANTIC_CAPABILITY = 12
            MISSING_CAPABILITY = 1
        }
    } else {
        [ordered]@{
            EXACT_TYPED_ENTRY = 21
            SEMANTIC_CAPABILITY = 9
        }
    }
    foreach ($requirement in $expectedCounts.Keys) {
        $actualCount = @($records | Where-Object { $_.RouteRequirement -ceq $requirement }).Count
        if ($actualCount -ne $expectedCounts[$requirement]) {
            throw "The $Kind compiler-derived route count for $requirement is $actualCount, expected $($expectedCounts[$requirement])"
        }
    }
    $expectedTotal = [int](($expectedCounts.Values | Measure-Object -Sum).Sum)
    if ($records.Count -ne $expectedTotal) {
        throw "The $Kind compiler-derived route manifest has $($records.Count) sites, expected $expectedTotal"
    }
    return $records
}

function Get-FileByHashKey([string]$TargetProfile, [string]$Kind) {
    $executableExtension = if ($TargetProfile -eq 'NET48') { 'exe' } else { 'dll' }
    $files = if ($Kind -eq 'hostile') {
        [ordered]@{
            applicationSourceSha256 = 'genericOwnerHardestModelOracle.kt'
            candidateProducerSha256 = 'SnapshotProducer.dll'
            candidateConsumerSha256 = "RecordedFamilyConsumer.$executableExtension"
            candidateSourceSha256 = 'RecordedFamilyConsumer.cs'
            physicalFamilyArtifactSha256 = 'SnapshotProducer.generic-owner-families'
            callRouteManifestSha256 = 'generic-owner-call-routes.tsv'
            erasedProducerSha256 = 'lib.dll'
            erasedConsumerSha256 = "ErasedConsumer.$executableExtension"
            erasedCSharpSourceSha256 = 'ErasedCSharpConsumer.cs'
            erasedCSharpAssemblySha256 = "ErasedCSharpConsumer.$executableExtension"
            runtimeSha256 = 'Kotlin.Runtime.dll'
            stdlibSha256 = 'Kotlin.Stdlib.dll'
        }
    } else {
        [ordered]@{
            applicationSourceSha256 = 'genericOwnerRepresentativeOctoTree.kt'
            representativeSourceSha256 = 'ocTree.kt'
            candidateProducerSourceSha256 = 'OctoTreeCandidateProducer.cs'
            candidateProducerSha256 = 'SnapshotProducer.dll'
            candidateConsumerSha256 = "RecordedFamilyConsumer.$executableExtension"
            candidateSourceSha256 = 'RecordedFamilyConsumer.cs'
            physicalFamilyArtifactSha256 = 'SnapshotProducer.generic-owner-families'
            callRouteManifestSha256 = 'generic-owner-call-routes.tsv'
            erasedProducerSha256 = 'lib.dll'
            erasedCSharpSourceSha256 = 'ErasedCSharpConsumer.cs'
            erasedCSharpAssemblySha256 = "ErasedCSharpConsumer.$executableExtension"
            runtimeSha256 = 'Kotlin.Runtime.dll'
            stdlibSha256 = 'Kotlin.Stdlib.dll'
        }
    }
    if ($TargetProfile -eq 'NET10_0') {
        $files.candidateRuntimeConfigSha256 = 'RecordedFamilyConsumer.runtimeconfig.json'
        if ($Kind -eq 'hostile') {
            $files.erasedConsumerRuntimeConfigSha256 = 'ErasedConsumer.runtimeconfig.json'
        }
        $files.erasedCSharpRuntimeConfigSha256 = 'ErasedCSharpConsumer.runtimeconfig.json'
        $files.globalJsonSha256 = 'global.json'
    }
    return $files
}

function Assert-ApplicationBundle([string]$Directory) {
    $manifestPath = Join-Path $Directory 'generic-owner-application.properties'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "No generic-owner application manifest exists at $manifestPath"
    }
    $manifest = Read-ApplicationManifest $manifestPath
    if ($manifest.targetProfile -notin @('NET10_0', 'NET48')) {
        throw "Unsupported generic-owner application target: $($manifest.targetProfile)"
    }
    $manifestKind = if ($manifest.schema -eq '3') { $manifest.corpusKind } else { 'hostile' }
    if ($manifestKind -ne $CorpusKind) {
        throw "Expected a $CorpusKind application bundle, found $manifestKind at $Directory"
    }
    $fileByHashKey = Get-FileByHashKey $manifest.targetProfile $manifestKind
    $requiredKeys = if ($manifestKind -eq 'hostile') {
        @('schema', 'sdkVersion', 'targetProfile') + @($fileByHashKey.Keys)
    } else {
        @('schema', 'corpusKind', 'workloadVersion', 'sdkVersion', 'targetProfile') +
            @($fileByHashKey.Keys)
    }
    if (@(Compare-Object @($manifest.Keys) $requiredKeys).Count -ne 0) {
        throw "The application manifest has an unexpected shape: $($manifest | ConvertTo-Json -Compress)"
    }
    $expectedSdk = if ($manifest.targetProfile -eq 'NET10_0') { '10.0.100' } else { 'framework-clr' }
    $expectedSchema = if ($manifestKind -eq 'hostile') { '2' } else { '3' }
    $expectedWorkload = if ($manifestKind -eq 'octo-tree') { '2' } else { $null }
    if ($manifest.schema -ne $expectedSchema -or $manifest.sdkVersion -ne $expectedSdk -or
            ($null -ne $expectedWorkload -and $manifest.workloadVersion -ne $expectedWorkload)) {
        throw "Unsupported generic-owner application manifest: $($manifest | ConvertTo-Json -Compress)"
    }

    $expectedNames = @($fileByHashKey.Values) + 'generic-owner-application.properties'
    $entries = @(Get-ChildItem -LiteralPath $Directory -Force)
    if (@($entries | Where-Object { $_.PSIsContainer }).Count -ne 0 -or
            @(Compare-Object @($entries.Name) $expectedNames).Count -ne 0) {
        throw "The application bundle has an unexpected closed file set: $Directory"
    }
    foreach ($hashKey in $fileByHashKey.Keys) {
        $path = Join-Path $Directory $fileByHashKey[$hashKey]
        $actual = Get-Sha256 $path
        if ($actual -ne $manifest[$hashKey]) {
            throw "Stale $($fileByHashKey[$hashKey]): expected $($manifest[$hashKey]), found $actual"
        }
    }
    $callRoutes = @(
        Read-CallRouteManifest (Join-Path $Directory $fileByHashKey.callRouteManifestSha256) $manifestKind
    )
    $kotlinSource = Get-Content -LiteralPath (Join-Path $Directory $fileByHashKey.applicationSourceSha256) -Raw
    if ($kotlinSource -notmatch 'MODULE:\s+lib' -or
            $kotlinSource -notmatch 'MODULE:\s+main\(lib\)') {
        throw 'The application corpus lost its separate Kotlin producer/consumer source'
    }
    $candidateSource = Get-Content -LiteralPath (Join-Path $Directory $fileByHashKey.candidateSourceSha256) -Raw
    $erasedCSharpSource = Get-Content -LiteralPath (
        Join-Path $Directory $fileByHashKey.erasedCSharpSourceSha256) -Raw
    if ($manifestKind -eq 'hostile') {
        foreach ($requiredKotlinShape in @(
                '@kotlin.concurrent.Volatile', 'private var published: T',
                'open fun publish(next: T)', 'open fun observe(): T')) {
            if ($kotlinSource -notmatch [Regex]::Escape($requiredKotlinShape)) {
                throw "The hostile Kotlin application lost volatile shape '$requiredKotlinShape'"
            }
        }
        foreach ($sourceText in @($candidateSource, $erasedCSharpSource)) {
            foreach ($requiredConcurrencyShape in @(
                    'VerifyVolatileOneState', 'System.Threading.AutoResetEvent',
                    'typed volatile handoff', 'capability volatile handoff')) {
                if ($sourceText -notmatch [Regex]::Escape($requiredConcurrencyShape)) {
                    throw "The paired hostile application lost concurrency shape '$requiredConcurrencyShape'"
                }
            }
        }
        $physicalFamilyText = Get-Content -LiteralPath (
            Join-Path $Directory $fileByHashKey.physicalFamilyArtifactSha256) -Raw
        if ($physicalFamilyText -notmatch 'VOLATILE_OBJECT_STORAGE_REQUIRED\s+VOLATILE') {
            throw 'The hostile physical family lost its volatile object-state migration condition'
        }
        foreach ($requiredShape in @('Guid', 'DateTime', 'decimal', 'ApplicationEnum',
                'ValueTuple<int, string>', 'ApplicationStruct', 'ErasedCSharpGrandchild')) {
            if ($erasedCSharpSource -notmatch [Regex]::Escape($requiredShape)) {
                throw "The direct C# application lost required shape '$requiredShape'"
            }
        }
        $hostileCellInterfaces = [GenericOwnerApplicationAudit]::DirectInterfaces(
            (Join-Path $Directory $fileByHashKey.erasedProducerSha256),
            'generic.owner.oracle.HostileCell'
        )
        $requiredReimplementations = @(
            'Kotlin.Collections.Collection',
            'Kotlin.Collections.Iterable',
            'Kotlin.Collections.MutableCollection',
            'Kotlin.Collections.MutableIterable'
        )
        $missingReimplementations = @($requiredReimplementations | Where-Object {
            $_ -notin $hostileCellInterfaces
        })
        if ($missingReimplementations.Count -ne 0) {
            throw "The erased HostileCell lost direct CLR interface reimplementation edges: " +
                    ($missingReimplementations -join ', ') + "; found: " +
                    ($hostileCellInterfaces -join ', ')
        }
    } else {
        $representativeSource = Get-Content -LiteralPath (
            Join-Path $Directory $fileByHashKey.representativeSourceSha256) -Raw
        $candidateProducerSource = Get-Content -LiteralPath (
            Join-Path $Directory $fileByHashKey.candidateProducerSourceSha256) -Raw
        foreach ($requiredShape in @(
                'class OctoTree<T>', 'private var root: Node<T>?',
                'class Leaf<T>(var value: T)', 'val nodes = arrayOfNulls<Node<T>>(8)'
            )) {
            if ($representativeSource -notmatch [Regex]::Escape($requiredShape)) {
                throw "The OctoTree representative source lost required shape '$requiredShape'"
            }
        }
        foreach ($requiredPhysicalShape in @(
                'public class OctoTree<T0>',
                'private KotlinRepresentativeCandidate.OctoTreeNode<T0> root;', 'private T0 value;',
                'private readonly KotlinRepresentativeCandidate.OctoTreeNode<T0>[] nodes;',
                'Kotlin.Runtime.Internal.Intrinsics.AreEqualGeneric<T0>'
            )) {
            if ($candidateProducerSource -notmatch [Regex]::Escape($requiredPhysicalShape)) {
                throw "The OctoTree candidate lost required physical shape '$requiredPhysicalShape'"
            }
        }
        if ($candidateProducerSource -match 'EqualityComparer\s*<') {
            throw 'The OctoTree candidate replaced Kotlin generic equality with a CLR comparer'
        }
        if ($candidateProducerSource -match 'Intrinsics\.AreEqual\s*\(') {
            throw 'The OctoTree candidate retained the two-box object equality fallback'
        }
        foreach ($sourceText in @($candidateSource, $erasedCSharpSource)) {
            foreach ($requiredShape in @(
                    'octo-tree-typed-path', 'octo-tree-capability-path',
                    'octo-tree-clusterization', 'octo-tree-rendering', 'workloadVersion=2',
                    'HostileEquality : IEquatable<HostileEquality>',
                    'Intrinsics.AreEqualGeneric<HostileEquality>',
                    'Intrinsics.AreEqualGeneric<double?>'
                )) {
                if ($sourceText -notmatch [Regex]::Escape($requiredShape)) {
                    throw "The paired OctoTree application lost required shape '$requiredShape'"
                }
            }
            if ($sourceText -match 'MakeGenericType|Activator\.CreateInstance|\bdynamic\b') {
                throw 'The paired OctoTree application introduced an unbounded dynamic-code path'
            }
        }
    }
    return [pscustomobject]@{
        Directory = $Directory
        Manifest = $manifest
        Files = $fileByHashKey
        CallRoutes = $callRoutes
    }
}

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Security.Cryptography;

public static class GenericOwnerApplicationAudit
{
    private static string Hash(byte[] bytes)
    {
        using (var sha = SHA256.Create())
            return BitConverter.ToString(sha.ComputeHash(bytes)).Replace("-", "").ToLowerInvariant();
    }

    private static string TypeName(MetadataReader metadata, EntityHandle handle)
    {
        if (handle.Kind == HandleKind.TypeDefinition)
        {
            var type = metadata.GetTypeDefinition((TypeDefinitionHandle)handle);
            return metadata.GetString(type.Namespace) + "." + metadata.GetString(type.Name);
        }
        if (handle.Kind == HandleKind.TypeReference)
        {
            var type = metadata.GetTypeReference((TypeReferenceHandle)handle);
            return metadata.GetString(type.Namespace) + "." + metadata.GetString(type.Name);
        }
        throw new InvalidDataException("Unexpected direct interface handle " + handle.Kind);
    }

    public static string[] DirectInterfaces(string path, string fullTypeName)
    {
        using (var stream = File.OpenRead(path))
        using (var pe = new PEReader(stream))
        {
            var metadata = pe.GetMetadataReader();
            foreach (var handle in metadata.TypeDefinitions)
            {
                var type = metadata.GetTypeDefinition(handle);
                var name = metadata.GetString(type.Namespace) + "." + metadata.GetString(type.Name);
                if (name != fullTypeName) continue;
                return type.GetInterfaceImplementations()
                    .Select(implementation => TypeName(
                        metadata,
                        metadata.GetInterfaceImplementation(implementation).Interface))
                    .OrderBy(interfaceName => interfaceName, StringComparer.Ordinal)
                    .ToArray();
            }
        }
        throw new InvalidDataException("Type " + fullTypeName + " is missing from " + path);
    }

    public static string[] ClrRecords(string path)
    {
        var records = new List<string>();
        using (var stream = File.OpenRead(path))
        using (var pe = new PEReader(stream))
        {
            var metadataBytes = pe.GetMetadata().GetContent().ToArray();
            records.Add("metadata|" + metadataBytes.Length + "|" + Hash(metadataBytes));
            var metadata = pe.GetMetadataReader();
            foreach (var handle in metadata.MethodDefinitions)
            {
                var definition = metadata.GetMethodDefinition(handle);
                if (definition.RelativeVirtualAddress == 0) continue;
                var body = pe.GetMethodBody(definition.RelativeVirtualAddress);
                var exceptionRegions = string.Join(";", body.ExceptionRegions.Select(region =>
                    region.Kind + ":" + region.TryOffset + ":" + region.TryLength + ":" +
                    region.HandlerOffset + ":" + region.HandlerLength + ":" + region.FilterOffset +
                    ":" + MetadataTokens.GetToken(region.CatchType)));
                records.Add("method|" + MetadataTokens.GetToken(handle).ToString("x8") + "|" +
                    body.MaxStack + "|" + body.LocalVariablesInitialized + "|" +
                    MetadataTokens.GetToken(body.LocalSignature).ToString("x8") + "|" +
                    exceptionRegions + "|" + Hash(body.GetILBytes()));
            }
            var resources = pe.GetSectionData(pe.PEHeaders.CorHeader.ResourcesDirectory.RelativeVirtualAddress)
                .GetContent().ToArray();
            foreach (var handle in metadata.ManifestResources)
            {
                var resource = metadata.GetManifestResource(handle);
                var name = metadata.GetString(resource.Name);
                if (name == "Kotlin.Metadata") continue;
                var offset = (int)resource.Offset;
                var length = BitConverter.ToInt32(resources, offset);
                var payload = new byte[length];
                Buffer.BlockCopy(resources, offset + 4, payload, 0, length);
                records.Add("resource|" + name + "|" + length + "|" + Hash(payload));
            }
        }
        return records.ToArray();
    }

    public static string[] KlibRecordsExceptBodies(string path)
    {
        using (var stream = File.OpenRead(path))
        using (var pe = new PEReader(stream))
        {
            var metadata = pe.GetMetadataReader();
            var resources = pe.GetSectionData(pe.PEHeaders.CorHeader.ResourcesDirectory.RelativeVirtualAddress)
                .GetContent().ToArray();
            foreach (var handle in metadata.ManifestResources)
            {
                var resource = metadata.GetManifestResource(handle);
                if (metadata.GetString(resource.Name) != "Kotlin.Metadata") continue;
                var offset = (int)resource.Offset;
                var length = BitConverter.ToInt32(resources, offset);
                using (var archive = new ZipArchive(
                    new MemoryStream(resources, offset + 4, length), ZipArchiveMode.Read))
                {
                    return archive.Entries
                        .Where(entry => entry.FullName != "default/ir/bodies.knb")
                        .OrderBy(entry => entry.FullName, StringComparer.Ordinal)
                        .Select(entry =>
                        {
                            using (var input = entry.Open())
                            using (var output = new MemoryStream())
                            {
                                input.CopyTo(output);
                                return entry.FullName + "|" + entry.Length + "|" + Hash(output.ToArray());
                            }
                        }).ToArray();
                }
            }
        }
        throw new InvalidDataException("Kotlin.Metadata resource is missing from " + path);
    }
}
'@

function Assert-FrontendEquivalent($PsiBundle, $LightTreeBundle) {
    if ($PsiBundle.Manifest.targetProfile -ne $LightTreeBundle.Manifest.targetProfile) {
        throw 'PSI and LightTree bundles have different target profiles'
    }
    foreach ($hashKey in $PsiBundle.Files.Keys) {
        if ($hashKey -eq 'erasedProducerSha256') { continue }
        if ($PsiBundle.Manifest[$hashKey] -ne $LightTreeBundle.Manifest[$hashKey]) {
            throw "PSI and LightTree disagree on $($PsiBundle.Files[$hashKey])"
        }
    }
    $psiProducer = Join-Path $PsiBundle.Directory $PsiBundle.Files.erasedProducerSha256
    $lightTreeProducer = Join-Path $LightTreeBundle.Directory $LightTreeBundle.Files.erasedProducerSha256
    if (@(Compare-Object `
            ([GenericOwnerApplicationAudit]::ClrRecords($psiProducer)) `
            ([GenericOwnerApplicationAudit]::ClrRecords($lightTreeProducer))).Count -ne 0) {
        throw 'PSI and LightTree erased producers have different CLR metadata, code, or non-KLIB resources'
    }
    if (@(Compare-Object `
            ([GenericOwnerApplicationAudit]::KlibRecordsExceptBodies($psiProducer)) `
            ([GenericOwnerApplicationAudit]::KlibRecordsExceptBodies($lightTreeProducer))).Count -ne 0) {
        throw 'PSI and LightTree erased producers have different KLIB content outside parser-owned IR body locations'
    }
}

$dotnetCandidates = @()
if (-not [string]::IsNullOrWhiteSpace($env:KOTLIN_DOTNET_ROOT)) {
    $dotnetCandidates += Join-Path $env:KOTLIN_DOTNET_ROOT 'dotnet\dotnet.exe'
}
$dotnetCandidates += Join-Path $env:LOCALAPPDATA 'kotlinc-dotnet\toolchain\dotnet\dotnet.exe'
$systemDotnet = Get-Command dotnet.exe -ErrorAction SilentlyContinue
if ($null -ne $systemDotnet) { $dotnetCandidates += $systemDotnet.Source }
$dotnet = $dotnetCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    Select-Object -First 1
if ($null -eq $dotnet) {
    throw 'No .NET SDK was found. Run tools/provision-dotnet-toolchain.ps1 first.'
}
$sdkVersion = (& $dotnet --version).Trim()
if ($LASTEXITCODE -ne 0 -or $sdkVersion -ne '10.0.100') {
    throw "The application corpus requires SDK 10.0.100, found '$sdkVersion' at $dotnet"
}

function Invoke-Application([string]$Directory, [string]$AssemblyName, [string]$TargetProfile) {
    $assembly = Join-Path $Directory $AssemblyName
    if ($TargetProfile -eq 'NET48') {
        $frameworkHost = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        if (-not (Test-Path -LiteralPath $frameworkHost -PathType Leaf)) {
            throw "No Framework CLR PowerShell host exists at $frameworkHost"
        }
        $escapedAssembly = $assembly.Replace("'", "''")
        $frameworkScript = "`$ErrorActionPreference='Stop'; try { " +
            "`$a=[Reflection.Assembly]::LoadFrom('$escapedAssembly'); " +
            "`$arguments=`$null; if(`$a.EntryPoint.GetParameters().Length -ne 0){" +
            "`$arguments=New-Object 'System.Object[]' 1; `$arguments[0]=[string[]]@()}; " +
            "`$r=`$a.EntryPoint.Invoke(`$null,`$arguments); if([int]`$r -ne 0){exit [int]`$r} " +
            "} catch { [Console]::Error.WriteLine(`$_.Exception.ToString()); exit 1 }"
        $applicationOutput = @(& $frameworkHost -NoLogo -NoProfile -NonInteractive -Command $frameworkScript 2>&1)
    } else {
        $applicationOutput = @(& $dotnet exec $assembly 2>&1)
    }
    if ($LASTEXITCODE -ne 0) {
        throw "$AssemblyName failed with exit code $LASTEXITCODE`n$($applicationOutput -join [Environment]::NewLine)"
    }
}

function Invoke-ProducerTest([string]$BundleDirectory, [string]$TestClass) {
    New-Item -ItemType Directory -Force -Path $BundleDirectory | Out-Null
    $gradle = Join-Path $repositoryRoot 'gradlew.bat'
    $testMethod = if ($CorpusKind -eq 'hostile') {
        'testGenericOwnerHardestModelOracleSeparateCompilation'
    } else {
        'testGenericOwnerRepresentativeOctoTreeSeparateCompilation'
    }
    $testFilter = "org.jetbrains.kotlin.test.runners.codegen.${TestClass}`$Box.$testMethod"
    $applicationProperty = "-Pkotlin.dotnet.genericOwnerApplicationDir=$BundleDirectory"
    & $gradle --no-daemon $applicationProperty -q `
        :compiler:fir:fir2ir:dotNetTest --rerun --tests $testFilter
    if ($LASTEXITCODE -ne 0) {
        throw "The $TestClass generic-owner application producer failed with exit code $LASTEXITCODE"
    }
}

if (-not [string]::IsNullOrWhiteSpace($ExistingBundle)) {
    $bundleDirectory = [IO.Path]::GetFullPath($ExistingBundle)
    $verifiedBundles = @(Assert-ApplicationBundle $bundleDirectory)
} else {
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        if ([string]::IsNullOrWhiteSpace($ExistingCorpus)) {
            $timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
            $OutputDirectory = Join-Path $backendDirectory `
                "build\generic-owner-$CorpusKind-applications\$timestamp"
        } else {
            $OutputDirectory = $ExistingCorpus
        }
    }
    $runDirectory = [IO.Path]::GetFullPath($OutputDirectory)
    if ([string]::IsNullOrWhiteSpace($ExistingCorpus)) {
        if (Test-Path -LiteralPath $runDirectory) {
            if (-not (Test-Path -LiteralPath $runDirectory -PathType Container) -or
                    @(Get-ChildItem -LiteralPath $runDirectory -Force).Count -ne 0) {
                throw "The application output directory must not exist or must be empty: $runDirectory"
            }
        } else {
            New-Item -ItemType Directory -Path $runDirectory | Out-Null
        }
    } elseif (-not (Test-Path -LiteralPath $runDirectory -PathType Container)) {
        throw "The existing application corpus does not exist: $runDirectory"
    }
    $verifiedBundles = @()
    $profileDefinitions = @(
        @{ Directory = 'net10'; Psi = 'FirPsiDotNetBoxTestGenerated'; LightTree = 'FirLightTreeDotNetBoxTestGenerated' },
        @{ Directory = 'net48'; Psi = 'FirPsiDotNetFrameworkBoxTestGenerated'; LightTree = 'FirLightTreeDotNetFrameworkBoxTestGenerated' }
    ) | Where-Object { $_.Directory -in $Profiles }
    foreach ($profile in $profileDefinitions) {
        $profileDirectory = Join-Path $runDirectory $profile.Directory
        $psiDirectory = Join-Path $profileDirectory 'psi'
        $lightTreeDirectory = Join-Path $profileDirectory 'light-tree'
        if ([string]::IsNullOrWhiteSpace($ExistingCorpus)) {
            Invoke-ProducerTest $psiDirectory $profile.Psi
            Invoke-ProducerTest $lightTreeDirectory $profile.LightTree
        }
        $psiBundle = Assert-ApplicationBundle $psiDirectory
        $lightTreeBundle = Assert-ApplicationBundle $lightTreeDirectory
        Assert-FrontendEquivalent $psiBundle $lightTreeBundle
        $verifiedBundles += $psiBundle
    }
    if ($verifiedBundles.Count -eq 2 -and
            $verifiedBundles[0].Manifest.callRouteManifestSha256 -cne
            $verifiedBundles[1].Manifest.callRouteManifestSha256) {
        throw 'Framework CLR and CoreCLR bundles disagree on the profile-neutral compiler call-route census'
    }
}

foreach ($bundle in $verifiedBundles) {
    Invoke-Application $bundle.Directory $bundle.Files.candidateConsumerSha256 $bundle.Manifest.targetProfile
    if ($bundle.Files.Contains('erasedConsumerSha256')) {
        Invoke-Application $bundle.Directory $bundle.Files.erasedConsumerSha256 $bundle.Manifest.targetProfile
    }
    Invoke-Application $bundle.Directory $bundle.Files.erasedCSharpAssemblySha256 $bundle.Manifest.targetProfile
    Write-Host "Verified $CorpusKind/$($bundle.Manifest.targetProfile) generic-owner application bundle: $($bundle.Directory)"
}
