<#
.SYNOPSIS
    Produces and verifies the paired generic-owner application corpus.

.DESCRIPTION
    Builds the hostile separate-compilation application through PSI and
    LightTree on Framework CLR and CoreCLR, verifies the closed bundles and
    every SHA-256 fingerprint, compares exact executable CLR content and all
    KLIB entries outside the parser-owned body stream, and runs the candidate,
    erased Kotlin, and direct erased C# applications.
#>
[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [string]$ExistingBundle,
    [ValidateSet('net10', 'net48')]
    [string[]]$Profiles = @('net10', 'net48')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
if ($Profiles.Count -eq 0 -or @($Profiles | Select-Object -Unique).Count -ne $Profiles.Count) {
    throw 'Application profiles must contain one or two unique values'
}
if (-not [string]::IsNullOrWhiteSpace($ExistingBundle) -and
        -not [string]::IsNullOrWhiteSpace($OutputDirectory)) {
    throw 'ExistingBundle and OutputDirectory cannot be supplied together'
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

function Get-FileByHashKey([string]$TargetProfile) {
    $executableExtension = if ($TargetProfile -eq 'NET48') { 'exe' } else { 'dll' }
    $files = [ordered]@{
        applicationSourceSha256 = 'genericOwnerHardestModelOracle.kt'
        candidateProducerSha256 = 'SnapshotProducer.dll'
        candidateConsumerSha256 = "RecordedFamilyConsumer.$executableExtension"
        candidateSourceSha256 = 'RecordedFamilyConsumer.cs'
        physicalFamilyArtifactSha256 = 'SnapshotProducer.generic-owner-families'
        erasedProducerSha256 = 'lib.dll'
        erasedConsumerSha256 = "ErasedConsumer.$executableExtension"
        erasedCSharpSourceSha256 = 'ErasedCSharpConsumer.cs'
        erasedCSharpAssemblySha256 = "ErasedCSharpConsumer.$executableExtension"
        runtimeSha256 = 'Kotlin.Runtime.dll'
        stdlibSha256 = 'Kotlin.Stdlib.dll'
    }
    if ($TargetProfile -eq 'NET10_0') {
        $files.candidateRuntimeConfigSha256 = 'RecordedFamilyConsumer.runtimeconfig.json'
        $files.erasedConsumerRuntimeConfigSha256 = 'ErasedConsumer.runtimeconfig.json'
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
    $fileByHashKey = Get-FileByHashKey $manifest.targetProfile
    $requiredKeys = @('schema', 'sdkVersion', 'targetProfile') + @($fileByHashKey.Keys)
    if (@(Compare-Object @($manifest.Keys) $requiredKeys).Count -ne 0) {
        throw "The application manifest has an unexpected shape: $($manifest | ConvertTo-Json -Compress)"
    }
    $expectedSdk = if ($manifest.targetProfile -eq 'NET10_0') { '10.0.100' } else { 'framework-clr' }
    if ($manifest.schema -ne '1' -or $manifest.sdkVersion -ne $expectedSdk) {
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
    $kotlinSource = Get-Content -LiteralPath (Join-Path $Directory $fileByHashKey.applicationSourceSha256) -Raw
    if ($kotlinSource -notmatch 'MODULE:\s+lib' -or
            $kotlinSource -notmatch 'MODULE:\s+main\(lib\)') {
        throw 'The application corpus lost its separate Kotlin producer/consumer source'
    }
    $erasedCSharpSource =
        Get-Content -LiteralPath (Join-Path $Directory $fileByHashKey.erasedCSharpSourceSha256) -Raw
    foreach ($requiredShape in @('Guid', 'DateTime', 'decimal', 'ApplicationEnum',
            'ValueTuple<int, string>', 'ApplicationStruct', 'ErasedCSharpGrandchild')) {
        if ($erasedCSharpSource -notmatch [Regex]::Escape($requiredShape)) {
            throw "The direct C# application lost required shape '$requiredShape'"
        }
    }
    return [pscustomobject]@{
        Directory = $Directory
        Manifest = $manifest
        Files = $fileByHashKey
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
            "`$r=`$a.EntryPoint.Invoke(`$null,`$null); if([int]`$r -ne 0){exit [int]`$r} " +
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
    $testFilter = "org.jetbrains.kotlin.test.runners.codegen.${TestClass}`$Box." +
        'testGenericOwnerHardestModelOracleSeparateCompilation'
    $applicationProperty = "-Pkotlin.dotnet.genericOwnerApplicationDir=$BundleDirectory"
    & $gradle --no-daemon $applicationProperty -q `
        :compiler:fir:fir2ir:dotNetTest --rerun --tests $testFilter
    if ($LASTEXITCODE -ne 0) {
        throw "The $TestClass generic-owner application producer failed with exit code $LASTEXITCODE"
    }
}

if ([string]::IsNullOrWhiteSpace($ExistingBundle)) {
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
        $OutputDirectory = Join-Path $backendDirectory "build\generic-owner-applications\$timestamp"
    }
    $runDirectory = [IO.Path]::GetFullPath($OutputDirectory)
    if (Test-Path -LiteralPath $runDirectory) {
        if (-not (Test-Path -LiteralPath $runDirectory -PathType Container) -or
                @(Get-ChildItem -LiteralPath $runDirectory -Force).Count -ne 0) {
            throw "The application output directory must not exist or must be empty: $runDirectory"
        }
    } else {
        New-Item -ItemType Directory -Path $runDirectory | Out-Null
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
        Invoke-ProducerTest $psiDirectory $profile.Psi
        Invoke-ProducerTest $lightTreeDirectory $profile.LightTree
        $psiBundle = Assert-ApplicationBundle $psiDirectory
        $lightTreeBundle = Assert-ApplicationBundle $lightTreeDirectory
        Assert-FrontendEquivalent $psiBundle $lightTreeBundle
        $verifiedBundles += $psiBundle
    }
} else {
    $bundleDirectory = [IO.Path]::GetFullPath($ExistingBundle)
    $verifiedBundles = @(Assert-ApplicationBundle $bundleDirectory)
}

foreach ($bundle in $verifiedBundles) {
    Invoke-Application $bundle.Directory $bundle.Files.candidateConsumerSha256 $bundle.Manifest.targetProfile
    Invoke-Application $bundle.Directory $bundle.Files.erasedConsumerSha256 $bundle.Manifest.targetProfile
    Invoke-Application $bundle.Directory $bundle.Files.erasedCSharpAssemblySha256 $bundle.Manifest.targetProfile
    Write-Host "Verified $($bundle.Manifest.targetProfile) generic-owner application bundle: $($bundle.Directory)"
}
