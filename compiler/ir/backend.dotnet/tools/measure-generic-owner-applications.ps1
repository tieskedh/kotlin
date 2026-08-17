<#
.SYNOPSIS
    Measures aggregate or route-attributed erased/candidate generic-owner applications.

.DESCRIPTION
    Verifies or regenerates the paired corpus, compiles one checksum-identical
    workload for the production-erased and test-owned candidate owners, and
    records build, metadata, deployment, startup, throughput, allocation,
    working-set, and dispatch-route evidence. Supplying AttributionRoutes
    compiles a separate fail-closed protocol that isolates selected entry,
    state, construction, array, and override routes. Published product bytes
    are kept explicitly non-comparable because the candidate is not a Kotlin
    product and therefore does not yet carry Runtime, Stdlib, or KLIB costs.
#>
[CmdletBinding()]
param(
    [ValidateSet('hostile', 'octo-tree')]
    [string]$CorpusKind = 'hostile',
    [ValidateSet('framework', 'jit', 'ready-to-run', 'trimmed', 'native-aot')]
    [string[]]$Modes = @('framework', 'jit', 'ready-to-run', 'trimmed'),
    [ValidateRange(1, 1000000000)]
    [int]$Iterations = 20000,
    [ValidateRange(1, 100)]
    [int]$StartupRuns = 5,
    [ValidateRange(1, 100)]
    [int]$ThroughputRuns = 3,
    [ValidateRange(1, 10)]
    [int]$CompileRuns = 1,
    [ValidateSet(
        'typed-entry-typed-state',
        'capability-value-typed-state',
        'typed-entry-struct-typed-state',
        'capability-struct-typed-state',
        'typed-entry-nullable-typed-state',
        'capability-nullable-typed-state',
        'typed-entry-object-state',
        'capability-value-state',
        'capability-reference-state',
        'fallback-struct-state',
        'exact-value-construction',
        'typed-array',
        'semantic-array',
        'method-generic-array',
        'compatible-override-object-state',
        'hostile-override-state',
        'octo-tree-typed-path',
        'octo-tree-capability-path',
        'octo-tree-clusterization',
        'octo-tree-rendering'
    )]
    [string[]]$AttributionRoutes = @(),
    [string]$OutputDirectory,
    [string]$ExistingCorpus,
    [string]$NativeLinker,
    [string[]]$NativeLibraryDirectories
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
if ($Modes.Count -eq 0 -or @($Modes | Select-Object -Unique).Count -ne $Modes.Count) {
    throw 'Measurement modes must contain one to five unique values'
}
if (@($AttributionRoutes | Select-Object -Unique).Count -ne $AttributionRoutes.Count) {
    throw 'Attribution routes must be unique'
}
$octoTreeRoutes = @(
    'octo-tree-typed-path',
    'octo-tree-capability-path',
    'octo-tree-clusterization',
    'octo-tree-rendering'
)
$invalidRoutes = if ($CorpusKind -eq 'octo-tree') {
    @($AttributionRoutes | Where-Object { $_ -notin $octoTreeRoutes })
} else {
    @($AttributionRoutes | Where-Object { $_ -in $octoTreeRoutes })
}
if ($invalidRoutes.Count -ne 0) {
    throw "Attribution routes do not belong to the $CorpusKind corpus: $($invalidRoutes -join ', ')"
}
$workloadVersion = if ($CorpusKind -eq 'octo-tree') { 2 } else { 1 }
$isRouteAttribution = $AttributionRoutes.Count -gt 0
$measurementDefine = if ($isRouteAttribution) {
    'GENERIC_OWNER_APPLICATION_ROUTE_MEASUREMENT'
} else {
    'GENERIC_OWNER_APPLICATION_MEASUREMENT'
}
if ([string]::IsNullOrWhiteSpace($NativeLinker) -ne ($null -eq $NativeLibraryDirectories)) {
    throw 'NativeLinker and NativeLibraryDirectories must be supplied together'
}
if (-not [string]::IsNullOrWhiteSpace($NativeLinker) -and 'native-aot' -notin $Modes) {
    throw 'An explicit native toolchain is valid only for native-aot mode'
}

$backendDirectory = Split-Path -Parent $PSScriptRoot
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$verifyTool = Join-Path $PSScriptRoot 'verify-generic-owner-applications.ps1'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $OutputDirectory = Join-Path $backendDirectory `
        "build\generic-owner-$CorpusKind-application-measurement\$timestamp"
}
$runDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $runDirectory) {
    if (-not (Test-Path -LiteralPath $runDirectory -PathType Container) -or
            @(Get-ChildItem -LiteralPath $runDirectory -Force).Count -ne 0) {
        throw "The measurement output directory must not exist or must be empty: $runDirectory"
    }
} else {
    New-Item -ItemType Directory -Path $runDirectory | Out-Null
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-Median([double[]]$Values) {
    if ($Values.Count -eq 0) { throw 'Cannot take the median of an empty collection' }
    $ordered = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($ordered.Count / 2)
    if (($ordered.Count % 2) -eq 1) { return $ordered[$middle] }
    return ($ordered[$middle - 1] + $ordered[$middle]) / 2.0
}

function Invoke-CapturedProcess(
    [string]$FilePath,
    [string[]]$Arguments,
    [string]$WorkingDirectory,
    [hashtable]$Environment = @{}
) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $startInfo.ArgumentList.Add($argument) }
    foreach ($name in $Environment.Keys) { $startInfo.Environment[$name] = $Environment[$name] }
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $process = [Diagnostics.Process]::Start($startInfo)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stopwatch.Stop()
    $process.Refresh()
    return [ordered]@{
        exitCode = $process.ExitCode
        elapsedMilliseconds = $stopwatch.Elapsed.TotalMilliseconds
        driverPeakWorkingSetBytes = [long]$process.PeakWorkingSet64
        stdout = $stdoutTask.GetAwaiter().GetResult().Trim()
        stderr = $stderrTask.GetAwaiter().GetResult().Trim()
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
    throw "The paired measurement requires SDK 10.0.100, found '$sdkVersion' at $dotnet"
}
$sdkDirectory = Split-Path -Parent (Split-Path -Parent $dotnet)
$csc = Join-Path (Split-Path -Parent $dotnet) "sdk\$sdkVersion\Roslyn\bincore\csc.dll"
if (-not (Test-Path -LiteralPath $csc -PathType Leaf)) {
    throw "The paired measurement lacks Roslyn at $csc"
}
$frameworkDirectory = Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319'
$frameworkPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$frameworkReferences = @('mscorlib.dll', 'System.dll', 'System.Core.dll') | ForEach-Object {
    Join-Path $frameworkDirectory $_
}
if (@($frameworkReferences | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) }).Count -ne 0 -or
        -not (Test-Path -LiteralPath $frameworkPowerShell -PathType Leaf)) {
    throw 'The paired measurement requires the Framework CLR host and CLR 4 reference assemblies'
}
$frameworkClrVersion = (& $frameworkPowerShell -NoProfile -NonInteractive -Command `
    '[Environment]::Version.ToString()').Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($frameworkClrVersion)) {
    throw 'The Framework CLR host did not report its runtime version'
}
$frameworkRegistryPath = 'HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full'
if (-not (Test-Path -LiteralPath $frameworkRegistryPath)) {
    throw 'The paired measurement requires a registered .NET Framework 4.8 installation'
}
$frameworkInstallation = Get-ItemProperty -LiteralPath $frameworkRegistryPath
if ([int]$frameworkInstallation.Install -ne 1 -or [int]$frameworkInstallation.Release -lt 528040) {
    throw "The paired measurement requires .NET Framework 4.8 or newer; found release $($frameworkInstallation.Release)"
}

$nativeEnvironment = @{}
$nativeToolchainInfo = $null
if (-not [string]::IsNullOrWhiteSpace($NativeLinker)) {
    $NativeLinker = [IO.Path]::GetFullPath($NativeLinker)
    if (-not (Test-Path -LiteralPath $NativeLinker -PathType Leaf)) {
        throw "The explicit NativeAOT linker does not exist: $NativeLinker"
    }
    $resolvedNativeLibraryDirectories = @($NativeLibraryDirectories | ForEach-Object {
        $directory = [IO.Path]::GetFullPath($_)
        if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
            throw "The explicit NativeAOT library directory does not exist: $directory"
        }
        $directory
    })
    if ($resolvedNativeLibraryDirectories.Count -ne 3 -or
            @($resolvedNativeLibraryDirectories | Select-Object -Unique).Count -ne 3) {
        throw 'The explicit NativeAOT toolchain requires three unique library directories'
    }
    $requiredNativeLibraries = @(
        'advapi32.lib', 'bcrypt.lib', 'crypt32.lib', 'iphlpapi.lib', 'kernel32.lib',
        'mswsock.lib', 'ncrypt.lib', 'normaliz.lib', 'ntdll.lib', 'ole32.lib',
        'oleaut32.lib', 'secur32.lib', 'user32.lib', 'version.lib', 'ws2_32.lib',
        'libcmt.lib', 'libvcruntime.lib', 'oldnames.lib', 'ucrt.lib'
    )
    foreach ($library in $requiredNativeLibraries) {
        if (-not ($resolvedNativeLibraryDirectories | Where-Object {
            Test-Path -LiteralPath (Join-Path $_ $library) -PathType Leaf
        })) {
            throw "The explicit NativeAOT toolchain lacks $library"
        }
    }
    $linkerSignature = Get-AuthenticodeSignature -LiteralPath $NativeLinker
    if ($linkerSignature.Status -ne [Management.Automation.SignatureStatus]::Valid -or
            $linkerSignature.SignerCertificate.Subject -notmatch
            '(^|, )O=Microsoft Corporation(,|$)') {
        throw "The explicit NativeAOT linker is not validly signed by Microsoft: $NativeLinker"
    }
    $nativeEnvironment.PATH =
        "$(Split-Path -Parent $NativeLinker)$([IO.Path]::PathSeparator)$env:PATH"
    $nativeEnvironment.LIB = $resolvedNativeLibraryDirectories -join [IO.Path]::PathSeparator
    $nativeEnvironment.IlcUseEnvironmentalTools = 'true'
    $nativeToolchainInfo = [ordered]@{
        discovery = 'explicit-msvc'
        linker = $NativeLinker
        linkerVersion = (Get-Item -LiteralPath $NativeLinker).VersionInfo.FileVersion
        linkerSha256 = Get-Sha256 $NativeLinker
        linkerSigner = $linkerSignature.SignerCertificate.Subject
        libraryDirectories = $resolvedNativeLibraryDirectories
        requiredLibraries = $requiredNativeLibraries
    }
} elseif ('native-aot' -in $Modes) {
    $nativeToolchainInfo = [ordered]@{ discovery = 'dotnet-sdk-auto-discovery' }
}

if ([string]::IsNullOrWhiteSpace($ExistingCorpus)) {
    $corpusDirectory = Join-Path $runDirectory 'corpus'
    & $verifyTool -CorpusKind $CorpusKind -OutputDirectory $corpusDirectory
    if ($LASTEXITCODE -ne 0) { throw 'The paired application corpus producer failed' }
} else {
    $corpusDirectory = [IO.Path]::GetFullPath($ExistingCorpus)
    & $verifyTool -CorpusKind $CorpusKind -ExistingCorpus $corpusDirectory
    if ($LASTEXITCODE -ne 0) { throw 'The existing paired application corpus failed verification' }
}
$net10Bundle = Join-Path $corpusDirectory 'net10\psi'
$net48Bundle = Join-Path $corpusDirectory 'net48\psi'

Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Linq;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;

public sealed class GenericOwnerPeInventory
{
    public long FileBytes;
    public int TypeDefinitions;
    public int MethodDefinitions;
    public int FieldDefinitions;
    public int PropertyDefinitions;
    public int MethodImplementations;
    public int GenericParameters;
    public int TypeSpecifications;
    public int AssemblyReferences;
    public long IlBytes;
    public long ManagedResourceBytes;
    public long KotlinMetadataBytes;
}

public static class GenericOwnerPeInventoryReader
{
    public static GenericOwnerPeInventory Read(string path)
    {
        var result = new GenericOwnerPeInventory { FileBytes = new FileInfo(path).Length };
        using (var stream = File.OpenRead(path))
        using (var pe = new PEReader(stream))
        {
            var metadata = pe.GetMetadataReader();
            result.TypeDefinitions = metadata.TypeDefinitions.Count;
            result.MethodDefinitions = metadata.MethodDefinitions.Count;
            result.FieldDefinitions = metadata.FieldDefinitions.Count;
            result.PropertyDefinitions = metadata.PropertyDefinitions.Count;
            result.GenericParameters = metadata.GetTableRowCount(TableIndex.GenericParam);
            result.TypeSpecifications = metadata.GetTableRowCount(TableIndex.TypeSpec);
            result.MethodImplementations = metadata.GetTableRowCount(TableIndex.MethodImpl);
            result.AssemblyReferences = metadata.AssemblyReferences.Count;
            foreach (var handle in metadata.MethodDefinitions)
            {
                var method = metadata.GetMethodDefinition(handle);
                if (method.RelativeVirtualAddress != 0)
                    result.IlBytes += pe.GetMethodBody(method.RelativeVirtualAddress).GetILBytes().Length;
            }
            if (pe.PEHeaders.CorHeader.ResourcesDirectory.Size > 0)
            {
                var resourcesDirectory = pe.PEHeaders.CorHeader.ResourcesDirectory;
                var resources = pe.GetSectionData(resourcesDirectory.RelativeVirtualAddress)
                    .GetContent(0, resourcesDirectory.Size).ToArray();
                result.ManagedResourceBytes = resources.Length;
                foreach (var handle in metadata.ManifestResources)
                {
                    var resource = metadata.GetManifestResource(handle);
                    if (metadata.GetString(resource.Name) == "Kotlin.Metadata")
                        result.KotlinMetadataBytes = BitConverter.ToInt32(resources, (int)resource.Offset);
                }
            }
        }
        return result;
    }
}
'@

function Read-Manifest([string]$Bundle) {
    $result = [ordered]@{}
    foreach ($line in Get-Content -LiteralPath (Join-Path $Bundle 'generic-owner-application.properties')) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $separator = $line.IndexOf('=')
        $result[$line.Substring(0, $separator)] = $line.Substring($separator + 1)
    }
    return $result
}

function Write-MeasurementProject([string]$Representation, [string]$Bundle, [string]$Directory) {
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    $isCandidate = $Representation -eq 'candidate'
    $sourceName = if ($isCandidate) { 'RecordedFamilyConsumer.cs' } else { 'ErasedCSharpConsumer.cs' }
    $assemblyName = if ($isCandidate) {
        'CandidateGenericOwnerApplication'
    } else {
        'ErasedGenericOwnerApplication'
    }
    $references = if ($isCandidate) {
        @('SnapshotProducer.dll')
    } else {
        @('lib.dll', 'Kotlin.Runtime.dll', 'Kotlin.Stdlib.dll')
    }
    $referenceXml = $references | ForEach-Object {
        $name = [IO.Path]::GetFileNameWithoutExtension($_)
        $path = [Security.SecurityElement]::Escape((Join-Path $Bundle $_))
        "    <Reference Include=`"$name`"><HintPath>$path</HintPath><Private>true</Private></Reference>"
    }
    $sourcePath = [Security.SecurityElement]::Escape((Join-Path $Bundle $sourceName))
    $projectPath = Join-Path $Directory "$assemblyName.csproj"
    @"
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <AssemblyName>$assemblyName</AssemblyName>
    <RootNamespace>$assemblyName</RootNamespace>
    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>
    <DefineConstants>$measurementDefine</DefineConstants>
    <ImplicitUsings>disable</ImplicitUsings>
    <Nullable>disable</Nullable>
    <Optimize>true</Optimize>
    <Deterministic>true</Deterministic>
    <DebugSymbols>false</DebugSymbols>
    <DebugType>none</DebugType>
    <InvariantGlobalization>false</InvariantGlobalization>
    <WarningsAsErrors>IL2026;IL3050</WarningsAsErrors>
  </PropertyGroup>
  <ItemGroup>
    <Compile Include="$sourcePath" />
$($referenceXml -join [Environment]::NewLine)
  </ItemGroup>
</Project>
"@ | Set-Content -LiteralPath $projectPath -Encoding utf8NoBOM
    Copy-Item -LiteralPath (Join-Path $Bundle 'global.json') -Destination (Join-Path $Directory 'global.json')
    return [ordered]@{
        representation = $Representation
        assemblyName = $assemblyName
        sourceName = $sourceName
        projectPath = $projectPath
        references = $references
        bundle = $Bundle
    }
}

$projects = [ordered]@{
    candidate = Write-MeasurementProject 'candidate' $net10Bundle (Join-Path $runDirectory 'projects\candidate')
    erased = Write-MeasurementProject 'erased' $net10Bundle (Join-Path $runDirectory 'projects\erased')
}

$modeDefinitions = [ordered]@{
    'jit' = [ordered]@{
        selfContained = $false
        properties = @('PublishReadyToRun=false', 'PublishTrimmed=false', 'PublishAot=false')
    }
    'ready-to-run' = [ordered]@{
        selfContained = $false
        properties = @('PublishReadyToRun=true', 'PublishTrimmed=false', 'PublishAot=false')
    }
    'trimmed' = [ordered]@{
        selfContained = $true
        properties = @('PublishReadyToRun=false', 'PublishTrimmed=true', 'TrimMode=full', 'PublishAot=false')
    }
    'native-aot' = [ordered]@{
        selfContained = $true
        properties = @('PublishAot=true', 'PublishTrimmed=true', 'TrimMode=full')
    }
}

function Invoke-FrameworkCompile(
    [System.Collections.IDictionary]$Project,
    [int]$RunIndex
) {
    $outputDirectory = Join-Path $runDirectory "compile\framework\$($Project.representation)\$RunIndex"
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $assembly = Join-Path $outputDirectory "$($Project.assemblyName).exe"
    $arguments = @(
        $csc, '/nologo', '/noconfig', '/nostdlib+', '/deterministic+', '/optimize+',
        '/debug-', "/define:$measurementDefine", '/target:exe',
        "/out:$assembly"
    )
    $arguments += $frameworkReferences | ForEach-Object { "/reference:$_" }
    $arguments += $Project.references | ForEach-Object {
        "/reference:$(Join-Path $net48Bundle $_)"
    }
    $arguments += Join-Path $net48Bundle $Project.sourceName
    $compile = Invoke-CapturedProcess $dotnet $arguments $outputDirectory
    if ($compile.exitCode -ne 0) {
        throw "Framework $($Project.representation) compilation failed: $($compile.stderr)`n$($compile.stdout)"
    }
    foreach ($reference in $Project.references) {
        Copy-Item -LiteralPath (Join-Path $net48Bundle $reference) -Destination $outputDirectory
    }
    return [ordered]@{
        compile = $compile
        outputDirectory = $outputDirectory
        assembly = $assembly
        framework = $true
    }
}

function Invoke-SdkPublish(
    [System.Collections.IDictionary]$Project,
    [string]$Mode,
    [int]$RunIndex
) {
    $definition = $modeDefinitions[$Mode]
    $base = Join-Path $runDirectory "compile\$Mode\$($Project.representation)\$RunIndex"
    $publishDirectory = Join-Path $base 'publish'
    $objDirectory = Join-Path $base 'obj'
    $binDirectory = Join-Path $base 'bin'
    New-Item -ItemType Directory -Force -Path $publishDirectory | Out-Null
    $common = @(
        $Project.projectPath, '-r', 'win-x64', '--nologo', '--disable-build-servers',
        "-p:BaseIntermediateOutputPath=$objDirectory$([IO.Path]::DirectorySeparatorChar)",
        "-p:BaseOutputPath=$binDirectory$([IO.Path]::DirectorySeparatorChar)",
        '-p:UseSharedCompilation=false'
    )
    $properties = @($definition.properties | ForEach-Object { "-p:$_" })
    $restore = Invoke-CapturedProcess $dotnet (@('restore') + $common + $properties) `
        (Split-Path -Parent $Project.projectPath) $nativeEnvironment
    if ($restore.exitCode -ne 0) {
        throw "$Mode $($Project.representation) restore failed: $($restore.stderr)`n$($restore.stdout)"
    }
    $publishArguments = @(
        'publish', '-c', 'Release', '--no-restore',
        '--self-contained', $definition.selfContained.ToString().ToLowerInvariant(),
        '-o', $publishDirectory
    ) + $common + $properties
    $publish = Invoke-CapturedProcess $dotnet $publishArguments `
        (Split-Path -Parent $Project.projectPath) $nativeEnvironment
    $log = ($publish.stdout + [Environment]::NewLine + $publish.stderr).Trim()
    $log | Set-Content -LiteralPath (Join-Path $publishDirectory 'publish.log') -Encoding utf8NoBOM
    if ($publish.exitCode -ne 0) {
        throw "$Mode $($Project.representation) publish failed: $log"
    }
    if ($log -match '(?im):\s*warning\s|\bIL2026\b|\bIL3050\b|:\s*error\s') {
        throw "$Mode $($Project.representation) publish was not warning-clean: $log"
    }
    $assembly = if ($definition.selfContained) {
        Join-Path $publishDirectory "$($Project.assemblyName).exe"
    } else {
        Join-Path $publishDirectory "$($Project.assemblyName).dll"
    }
    if (-not (Test-Path -LiteralPath $assembly -PathType Leaf)) {
        throw "$Mode $($Project.representation) did not produce $assembly"
    }
    return [ordered]@{
        compile = $publish
        outputDirectory = $publishDirectory
        assembly = $assembly
        framework = $false
        selfContained = $definition.selfContained
    }
}

function Get-ExpectedRouteProtocol(
    [string]$Representation,
    [string]$Route,
    [int]$ExpectedIterations
) {
    if ($CorpusKind -eq 'octo-tree') {
        $result = [ordered]@{
            typedEntryCalls = 0L
            semanticCapabilityCalls = 0L
            erasedVirtualCalls = 0L
            ownerConstructions = if ($Route -eq 'octo-tree-clusterization') {
                [long]$ExpectedIterations
            } else {
                1L
            }
            loopValueBoxOrUnboxOperations = 0L
            runtimeCompatibilityChecks = 0L
            expectedFailures = 0L
        }
        if ($Representation -eq 'candidate') {
            switch ($Route) {
                'octo-tree-typed-path' {
                    $result.typedEntryCalls = $ExpectedIterations * 2L
                    $result.semanticCapabilityCalls = $ExpectedIterations * 4L
                    $result.loopValueBoxOrUnboxOperations = $ExpectedIterations * 4L
                    $result.runtimeCompatibilityChecks = [long]$ExpectedIterations
                }
                'octo-tree-capability-path' {
                    $result.semanticCapabilityCalls = $ExpectedIterations * 6L
                    $result.loopValueBoxOrUnboxOperations = $ExpectedIterations * 6L
                    $result.runtimeCompatibilityChecks = $ExpectedIterations * 2L
                }
                'octo-tree-clusterization' {
                    $result.typedEntryCalls = $ExpectedIterations * 9L
                    $result.semanticCapabilityCalls = $ExpectedIterations * 9L
                    $result.loopValueBoxOrUnboxOperations = $ExpectedIterations * 18L
                    $result.runtimeCompatibilityChecks = $ExpectedIterations * 8L
                }
                'octo-tree-rendering' {
                    $result.typedEntryCalls = [long]$ExpectedIterations
                }
                default { throw "Unknown candidate OctoTree attribution route: $Route" }
            }
        } elseif ($Representation -eq 'erased') {
            switch ($Route) {
                { $_ -in @('octo-tree-typed-path', 'octo-tree-capability-path') } {
                    $result.erasedVirtualCalls = $ExpectedIterations * 2L
                    $result.loopValueBoxOrUnboxOperations = $ExpectedIterations * 2L
                }
                'octo-tree-clusterization' {
                    $result.erasedVirtualCalls = $ExpectedIterations * 9L
                    $result.loopValueBoxOrUnboxOperations = $ExpectedIterations * 9L
                }
                'octo-tree-rendering' {
                    $result.erasedVirtualCalls = [long]$ExpectedIterations
                }
                default { throw "Unknown erased OctoTree attribution route: $Route" }
            }
        } else {
            throw "Unknown generic-owner representation: $Representation"
        }
        return $result
    }
    $result = [ordered]@{
        typedEntryCalls = 0L
        semanticCapabilityCalls = 0L
        erasedVirtualCalls = 0L
        ownerConstructions = if ($Route -eq 'exact-value-construction') {
            [long]$ExpectedIterations
        } else {
            1L
        }
        loopValueBoxOrUnboxOperations = 0L
        runtimeCompatibilityChecks = 0L
        expectedFailures = 0L
    }
    if ($Representation -eq 'candidate') {
        switch ($Route) {
            { $_ -in @(
                    'typed-entry-typed-state', 'typed-entry-struct-typed-state',
                    'typed-entry-nullable-typed-state'
                ) } {
                $result.typedEntryCalls = $ExpectedIterations * 2L
            }
            { $_ -in @(
                    'capability-value-typed-state', 'capability-struct-typed-state',
                    'capability-nullable-typed-state'
                ) } {
                $result.semanticCapabilityCalls = $ExpectedIterations * 2L
                $result.loopValueBoxOrUnboxOperations = $ExpectedIterations * 2L
                $result.runtimeCompatibilityChecks = [long]$ExpectedIterations
            }
            'typed-entry-object-state' {
                $result.typedEntryCalls = $ExpectedIterations * 2L
                $result.loopValueBoxOrUnboxOperations = $ExpectedIterations * 2L
            }
            'capability-value-state' {
                $result.semanticCapabilityCalls = $ExpectedIterations * 2L
                $result.loopValueBoxOrUnboxOperations = $ExpectedIterations * 4L
                $result.runtimeCompatibilityChecks = [long]$ExpectedIterations
            }
            'capability-reference-state' {
                $result.semanticCapabilityCalls = $ExpectedIterations * 2L
                $result.runtimeCompatibilityChecks = [long]$ExpectedIterations
            }
            'fallback-struct-state' {
                $result.semanticCapabilityCalls = $ExpectedIterations * 2L
                $result.loopValueBoxOrUnboxOperations = $ExpectedIterations * 2L
                $result.runtimeCompatibilityChecks = [long]$ExpectedIterations
            }
            'exact-value-construction' {
                $result.semanticCapabilityCalls = $ExpectedIterations * 2L
                $result.loopValueBoxOrUnboxOperations = $ExpectedIterations * 4L
                $result.runtimeCompatibilityChecks = [long]$ExpectedIterations
            }
            'typed-array' {
                $result.typedEntryCalls = [long]$ExpectedIterations
            }
            'semantic-array' {
                $result.semanticCapabilityCalls = [long]$ExpectedIterations
                $result.runtimeCompatibilityChecks = [long]$ExpectedIterations
            }
            'method-generic-array' {
                $result.typedEntryCalls = [long]$ExpectedIterations
            }
            'compatible-override-object-state' {
                $result.typedEntryCalls = $ExpectedIterations * 2L
                $result.loopValueBoxOrUnboxOperations = $ExpectedIterations * 2L
            }
            'hostile-override-state' {
                $result.typedEntryCalls = [long]$ExpectedIterations
                $result.semanticCapabilityCalls = $ExpectedIterations * 2L
                $result.loopValueBoxOrUnboxOperations = [long]$ExpectedIterations
                $result.runtimeCompatibilityChecks = [long]$ExpectedIterations
                $result.expectedFailures = [long]$ExpectedIterations
            }
            default { throw "Unknown candidate attribution route: $Route" }
        }
    } elseif ($Representation -eq 'erased') {
        switch ($Route) {
            { $_ -in @(
                    'typed-entry-typed-state', 'capability-value-typed-state',
                    'typed-entry-struct-typed-state', 'capability-struct-typed-state',
                    'typed-entry-nullable-typed-state', 'capability-nullable-typed-state',
                    'typed-entry-object-state', 'capability-value-state',
                    'fallback-struct-state', 'exact-value-construction',
                    'compatible-override-object-state'
                ) } {
                $result.erasedVirtualCalls = $ExpectedIterations * 2L
                $result.loopValueBoxOrUnboxOperations = $ExpectedIterations * 2L
            }
            'capability-reference-state' {
                $result.erasedVirtualCalls = $ExpectedIterations * 2L
            }
            { $_ -in @('typed-array', 'semantic-array', 'method-generic-array') } {
                $result.erasedVirtualCalls = [long]$ExpectedIterations
            }
            'hostile-override-state' {
                $result.erasedVirtualCalls = $ExpectedIterations * 3L
                $result.loopValueBoxOrUnboxOperations = [long]$ExpectedIterations
                $result.expectedFailures = [long]$ExpectedIterations
            }
            default { throw "Unknown erased attribution route: $Route" }
        }
    } else {
        throw "Unknown generic-owner representation: $Representation"
    }
    return $result
}

function Invoke-MeasuredApplication(
    [System.Collections.IDictionary]$Build,
    [string]$Representation,
    [int]$ExpectedIterations,
    [string]$Route
) {
    if ($isRouteAttribution) {
        if ([string]::IsNullOrWhiteSpace($Route)) {
            throw 'Route attribution requires an explicit route'
        }
        $measurementArguments = @(
            '--route-measurement', $Route, $ExpectedIterations.ToString(),
            '--hold-for-peak-working-set'
        )
        $measurementPrefix = 'GENERIC_OWNER_APPLICATION_ROUTE_MEASUREMENT|'
    } else {
        if (-not [string]::IsNullOrWhiteSpace($Route)) {
            throw 'Aggregate measurement does not accept a route'
        }
        $measurementArguments = @(
            '--measurement', $ExpectedIterations.ToString(), '--hold-for-peak-working-set'
        )
        $measurementPrefix = 'GENERIC_OWNER_APPLICATION_MEASUREMENT|'
    }
    if ($Build.framework) {
        $escapedAssembly = $Build.assembly.Replace("'", "''")
        $escapedArguments = $measurementArguments | ForEach-Object { "'$($_.Replace("'", "''"))'" }
        $script = "`$ErrorActionPreference='Stop'; try { " +
            "`$assembly=[Reflection.Assembly]::LoadFrom('$escapedAssembly'); " +
            "`$entryArguments=[string[]]@($($escapedArguments -join ',')); " +
            "`$invokeArguments=New-Object 'System.Object[]' 1; " +
            "`$invokeArguments[0]=`$entryArguments; " +
            "`$result=`$assembly.EntryPoint.Invoke(`$null,`$invokeArguments); " +
            "if([int]`$result -ne 0){exit [int]`$result} " +
            "} catch { [Console]::Error.WriteLine(`$_.Exception.ToString()); exit 1 }"
        $filePath = $frameworkPowerShell
        $arguments = @('-NoLogo', '-NoProfile', '-NonInteractive', '-Command', $script)
    } elseif ($Build.selfContained) {
        $filePath = $Build.assembly
        $arguments = $measurementArguments
    } else {
        $filePath = $dotnet
        $arguments = @('exec', $Build.assembly) + $measurementArguments
    }

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $filePath
    $startInfo.WorkingDirectory = $Build.outputDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.RedirectStandardInput = $true
    foreach ($argument in $arguments) { $startInfo.ArgumentList.Add($argument) }
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $process = [Diagnostics.Process]::Start($startInfo)
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $stdoutLines = [Collections.Generic.List[string]]::new()
    $measurementLine = $null
    $peakWorkingSet = 0L
    try {
        while ($true) {
            $line = $process.StandardOutput.ReadLine()
            if ($null -eq $line) { break }
            $stdoutLines.Add($line)
            if ($line.StartsWith($measurementPrefix)) {
                $measurementLine = $line
                break
            }
        }
        $stopwatch.Stop()
        if ($null -ne $measurementLine) {
            $process.Refresh()
            $peakWorkingSet = [long]$process.PeakWorkingSet64
        }
    } finally {
        if ($stopwatch.IsRunning) { $stopwatch.Stop() }
        try {
            if (-not $process.HasExited) {
                $process.StandardInput.WriteLine('release')
                $process.StandardInput.Close()
            }
        } catch [InvalidOperationException], [IO.IOException], [ObjectDisposedException] {
        }
    }
    $remaining = $process.StandardOutput.ReadToEnd()
    if (-not [string]::IsNullOrEmpty($remaining)) {
        foreach ($line in $remaining -split "`r?`n") {
            if (-not [string]::IsNullOrEmpty($line)) { $stdoutLines.Add($line) }
        }
    }
    $process.WaitForExit()
    $stdout = ($stdoutLines -join [Environment]::NewLine).Trim()
    $stderr = $stderrTask.GetAwaiter().GetResult().Trim()
    if ($process.ExitCode -ne 0 -or $null -eq $measurementLine -or $peakWorkingSet -le 0) {
        throw "$Representation measurement failed with $($process.ExitCode): $stderr`n$stdout"
    }
    $fields = [ordered]@{}
    foreach ($field in $measurementLine.Split('|') | Select-Object -Skip 1) {
        $separator = $field.IndexOf('=')
        if ($separator -le 0) { throw "Malformed application measurement field: $field" }
        $name = $field.Substring(0, $separator)
        if ($fields.Contains($name)) { throw "Duplicate application measurement field: $name" }
        $fields[$name] = $field.Substring($separator + 1)
    }
    $required = @(
        'workloadVersion', 'representation', 'iterations', 'checksum', 'elapsedTicks',
        'frequency', 'allocatedBytes', 'typedEntryCalls', 'semanticCapabilityCalls',
        'erasedVirtualCalls'
    )
    if ($isRouteAttribution) {
        $required += @(
            'route', 'ownerStateCarrierRequirement', 'ownerConstructions',
            'loopValueBoxOrUnboxOperations', 'runtimeCompatibilityChecks',
            'expectedFailures'
        )
    }
    if (@(Compare-Object @($fields.Keys) $required).Count -ne 0 -or
            $fields.workloadVersion -ne $workloadVersion.ToString() -or
            $fields.representation -ne $Representation -or
            [int]$fields.iterations -ne $ExpectedIterations -or
            ($isRouteAttribution -and $fields.route -ne $Route)) {
        throw "Unexpected application measurement protocol: $measurementLine"
    }
    if ($isRouteAttribution) {
        $expectedStateCarrier = if ($Representation -eq 'candidate') {
            if ($CorpusKind -eq 'octo-tree') {
                'MIXED_EXACT_AND_SEMANTIC'
            } else {
                if ($Route -in @(
                        'typed-entry-typed-state', 'capability-value-typed-state',
                        'typed-entry-struct-typed-state', 'capability-struct-typed-state',
                        'typed-entry-nullable-typed-state', 'capability-nullable-typed-state'
                    )) {
                    'TYPED_STORAGE_PRODUCER_GRAPH_PROVEN'
                } else {
                    'SEMANTIC_OBJECT_REQUIRED'
                }
            }
        } else {
            'ERASED_OBJECT'
        }
        if ($fields.ownerStateCarrierRequirement -ne $expectedStateCarrier) {
            throw "$Representation reported an inconsistent owner state carrier: $measurementLine"
        }
        $expectedProtocol = Get-ExpectedRouteProtocol `
            $Representation $Route $ExpectedIterations
        foreach ($name in $expectedProtocol.Keys) {
            if ([long]$fields[$name] -ne [long]$expectedProtocol[$name]) {
                throw "$Representation reported inconsistent $Route ${name}: $measurementLine"
            }
        }
    } else {
        if ($CorpusKind -eq 'octo-tree') {
            $periodicRoutes = [long]([Math]::Floor(($ExpectedIterations + 511L) / 512L))
            if ($Representation -eq 'candidate') {
                $expectedTyped = $ExpectedIterations * 2L + $periodicRoutes
                $expectedSemantic = $ExpectedIterations * 4L
                $expectedErased = 0L
            } else {
                $expectedTyped = 0L
                $expectedSemantic = 0L
                $expectedErased = $ExpectedIterations * 2L + $periodicRoutes
            }
        } else {
            $periodicRoutes = [long]([Math]::Floor(($ExpectedIterations + 63L) / 64L))
            if ($Representation -eq 'candidate') {
                $expectedTyped = $ExpectedIterations * 3L + $periodicRoutes
                $expectedSemantic = $ExpectedIterations * 24L + $periodicRoutes * 2L
                $expectedErased = 0L
            } else {
                $expectedTyped = 0L
                $expectedSemantic = 0L
                $expectedErased = $ExpectedIterations * 27L + $periodicRoutes * 2L
            }
        }
        if ([long]$fields.typedEntryCalls -ne $expectedTyped -or
                [long]$fields.semanticCapabilityCalls -ne $expectedSemantic -or
                [long]$fields.erasedVirtualCalls -ne $expectedErased) {
            throw "$Representation reported inconsistent route counts: $measurementLine"
        }
    }
    $measurement = [ordered]@{
        wallMilliseconds = $stopwatch.Elapsed.TotalMilliseconds
        peakWorkingSetBytes = $peakWorkingSet
        iterations = [int]$fields.iterations
        checksum = [int]$fields.checksum
        workloadMilliseconds = 1000.0 * [long]$fields.elapsedTicks / [long]$fields.frequency
        allocatedBytes = [long]$fields.allocatedBytes
        typedEntryCalls = [long]$fields.typedEntryCalls
        semanticCapabilityCalls = [long]$fields.semanticCapabilityCalls
        erasedVirtualCalls = [long]$fields.erasedVirtualCalls
        stdout = $stdout
        stderr = $stderr
    }
    if ($isRouteAttribution) {
        $measurement.route = $fields.route
        $measurement.ownerStateCarrierRequirement = $fields.ownerStateCarrierRequirement
        $measurement.ownerConstructions = [long]$fields.ownerConstructions
        $measurement.loopValueBoxOrUnboxOperations = [long]$fields.loopValueBoxOrUnboxOperations
        $measurement.runtimeCompatibilityChecks = [long]$fields.runtimeCompatibilityChecks
        $measurement.expectedFailures = [long]$fields.expectedFailures
    }
    return $measurement
}

$inputInventory = [ordered]@{
    candidateProducer = [GenericOwnerPeInventoryReader]::Read(
        (Join-Path $net10Bundle 'SnapshotProducer.dll'))
    erasedProducer = [GenericOwnerPeInventoryReader]::Read((Join-Path $net10Bundle 'lib.dll'))
    physicalFamilyArtifactBytes = (Get-Item -LiteralPath (
        Join-Path $net10Bundle 'SnapshotProducer.generic-owner-families')).Length
    erasedRuntimeBytes = (Get-Item -LiteralPath (Join-Path $net10Bundle 'Kotlin.Runtime.dll')).Length
    erasedStdlibBytes = (Get-Item -LiteralPath (Join-Path $net10Bundle 'Kotlin.Stdlib.dll')).Length
}

$measurements = @()
$expectedChecksum = $null
$expectedChecksumByRoute = [ordered]@{}
foreach ($mode in $Modes) {
    $compileByRepresentation = [ordered]@{ candidate = @(); erased = @() }
    $selectedBuild = [ordered]@{}
    for ($runIndex = 0; $runIndex -lt $CompileRuns; $runIndex++) {
        $order = if (($runIndex % 2) -eq 0) { @('candidate', 'erased') } else { @('erased', 'candidate') }
        foreach ($representation in $order) {
            $build = if ($mode -eq 'framework') {
                Invoke-FrameworkCompile $projects[$representation] $runIndex
            } else {
                Invoke-SdkPublish $projects[$representation] $mode $runIndex
            }
            $compileByRepresentation[$representation] += $build.compile
            $selectedBuild[$representation] = $build
        }
    }

    if ($isRouteAttribution) {
        $compilationResults = [ordered]@{}
        foreach ($representation in @('candidate', 'erased')) {
            $outputFiles = @(Get-ChildItem -LiteralPath $selectedBuild[$representation].outputDirectory `
                -File -Recurse | Where-Object { $_.Name -ne 'publish.log' })
            $compilationResults[$representation] = [ordered]@{
                compileMedianMilliseconds = Get-Median @(
                    $compileByRepresentation[$representation] |
                    ForEach-Object { $_.elapsedMilliseconds })
                compileDriverPeakWorkingSetMedianBytes = Get-Median @(
                    $compileByRepresentation[$representation] |
                    ForEach-Object { [double]$_.driverPeakWorkingSetBytes })
                compileRuns = $compileByRepresentation[$representation]
                publishedFileCount = $outputFiles.Count
                publishedBytes = [long](($outputFiles | Measure-Object Length -Sum).Sum)
            }
        }
        $routeMeasurements = @()
        foreach ($route in $AttributionRoutes) {
            $startup = [ordered]@{ candidate = @(); erased = @() }
            for ($runIndex = 0; $runIndex -lt $StartupRuns; $runIndex++) {
                $order = if (($runIndex % 2) -eq 0) {
                    @('candidate', 'erased')
                } else {
                    @('erased', 'candidate')
                }
                foreach ($representation in $order) {
                    $startup[$representation] += Invoke-MeasuredApplication `
                        $selectedBuild[$representation] $representation 0 $route
                }
            }
            $throughput = [ordered]@{ candidate = @(); erased = @() }
            for ($runIndex = 0; $runIndex -lt $ThroughputRuns; $runIndex++) {
                $order = if (($runIndex % 2) -eq 0) {
                    @('erased', 'candidate')
                } else {
                    @('candidate', 'erased')
                }
                foreach ($representation in $order) {
                    $throughput[$representation] += Invoke-MeasuredApplication `
                        $selectedBuild[$representation] $representation $Iterations $route
                }
            }
            $checksums = @($throughput.Values | ForEach-Object { $_ } |
                ForEach-Object { $_.checksum } | Select-Object -Unique)
            if ($checksums.Count -ne 1) {
                throw "$mode/$route produced unstable or cross-representation checksums"
            }
            if (-not $expectedChecksumByRoute.Contains($route)) {
                $expectedChecksumByRoute[$route] = $checksums[0]
            } elseif ($checksums[0] -ne $expectedChecksumByRoute[$route]) {
                throw "$mode/$route disagrees with the cross-mode checksum"
            }

            $runtimeResults = [ordered]@{}
            foreach ($representation in @('candidate', 'erased')) {
                $runtimeResults[$representation] = [ordered]@{
                    startupMedianMilliseconds = Get-Median @(
                        $startup[$representation] | ForEach-Object { $_.wallMilliseconds })
                    startupRuns = $startup[$representation]
                    throughputMedianMilliseconds = Get-Median @(
                        $throughput[$representation] | ForEach-Object { $_.workloadMilliseconds })
                    allocationMedianBytes = Get-Median @(
                        $throughput[$representation] | ForEach-Object { [double]$_.allocatedBytes })
                    peakWorkingSetMedianBytes = Get-Median @(
                        $throughput[$representation] |
                        ForEach-Object { [double]$_.peakWorkingSetBytes })
                    throughputRuns = $throughput[$representation]
                }
            }
            $candidateRuntime = $runtimeResults.candidate
            $erasedRuntime = $runtimeResults.erased
            $allocationPercent = if ($erasedRuntime.allocationMedianBytes -eq 0) {
                $null
            } else {
                100.0 * ($candidateRuntime.allocationMedianBytes -
                    $erasedRuntime.allocationMedianBytes) / $erasedRuntime.allocationMedianBytes
            }
            $routeMeasurements += [ordered]@{
                route = $route
                checksum = $checksums[0]
                runtimeResults = $runtimeResults
                boundedComparison = [ordered]@{
                    candidateToErasedWorkloadTimeRatio =
                        $candidateRuntime.throughputMedianMilliseconds /
                        $erasedRuntime.throughputMedianMilliseconds
                    candidateMinusErasedAllocationBytes =
                        $candidateRuntime.allocationMedianBytes -
                        $erasedRuntime.allocationMedianBytes
                    candidateMinusErasedAllocationPercent = $allocationPercent
                    candidateMinusErasedStartupMilliseconds =
                        $candidateRuntime.startupMedianMilliseconds -
                        $erasedRuntime.startupMedianMilliseconds
                }
            }
        }
        $measurements += [ordered]@{
            mode = $mode
            compilationResults = $compilationResults
            routeMeasurements = $routeMeasurements
        }
        continue
    }

    $startup = [ordered]@{ candidate = @(); erased = @() }
    for ($runIndex = 0; $runIndex -lt $StartupRuns; $runIndex++) {
        $order = if (($runIndex % 2) -eq 0) { @('candidate', 'erased') } else { @('erased', 'candidate') }
        foreach ($representation in $order) {
            $startup[$representation] += Invoke-MeasuredApplication `
                $selectedBuild[$representation] $representation 0
        }
    }
    $throughput = [ordered]@{ candidate = @(); erased = @() }
    for ($runIndex = 0; $runIndex -lt $ThroughputRuns; $runIndex++) {
        $order = if (($runIndex % 2) -eq 0) { @('erased', 'candidate') } else { @('candidate', 'erased') }
        foreach ($representation in $order) {
            $throughput[$representation] += Invoke-MeasuredApplication `
                $selectedBuild[$representation] $representation $Iterations
        }
    }
    $checksums = @($throughput.Values | ForEach-Object { $_ } |
        ForEach-Object { $_.checksum } | Select-Object -Unique)
    if ($checksums.Count -ne 1) { throw "$mode produced unstable or cross-representation checksums" }
    if ($null -eq $expectedChecksum) { $expectedChecksum = $checksums[0] }
    if ($checksums[0] -ne $expectedChecksum) { throw "$mode disagrees with the cross-mode checksum" }

    $representationResults = [ordered]@{}
    foreach ($representation in @('candidate', 'erased')) {
        $outputFiles = @(Get-ChildItem -LiteralPath $selectedBuild[$representation].outputDirectory `
            -File -Recurse | Where-Object { $_.Name -ne 'publish.log' })
        $representationResults[$representation] = [ordered]@{
            compileMedianMilliseconds = Get-Median @(
                $compileByRepresentation[$representation] | ForEach-Object { $_.elapsedMilliseconds })
            compileDriverPeakWorkingSetMedianBytes = Get-Median @(
                $compileByRepresentation[$representation] |
                ForEach-Object { [double]$_.driverPeakWorkingSetBytes })
            compileRuns = $compileByRepresentation[$representation]
            publishedFileCount = $outputFiles.Count
            publishedBytes = [long](($outputFiles | Measure-Object Length -Sum).Sum)
            startupMedianMilliseconds = Get-Median @(
                $startup[$representation] | ForEach-Object { $_.wallMilliseconds })
            startupRuns = $startup[$representation]
            throughputMedianMilliseconds = Get-Median @(
                $throughput[$representation] | ForEach-Object { $_.workloadMilliseconds })
            allocationMedianBytes = Get-Median @(
                $throughput[$representation] | ForEach-Object { [double]$_.allocatedBytes })
            peakWorkingSetMedianBytes = Get-Median @(
                $throughput[$representation] | ForEach-Object { [double]$_.peakWorkingSetBytes })
            throughputRuns = $throughput[$representation]
        }
    }
    $candidateResult = $representationResults.candidate
    $erasedResult = $representationResults.erased
    $measurements += [ordered]@{
        mode = $mode
        representationResults = $representationResults
        boundedComparison = [ordered]@{
            candidateToErasedWorkloadTimeRatio =
                $candidateResult.throughputMedianMilliseconds / $erasedResult.throughputMedianMilliseconds
            candidateMinusErasedAllocationBytes =
                $candidateResult.allocationMedianBytes - $erasedResult.allocationMedianBytes
            candidateMinusErasedAllocationPercent =
                100.0 * ($candidateResult.allocationMedianBytes - $erasedResult.allocationMedianBytes) /
                    $erasedResult.allocationMedianBytes
            candidateMinusErasedStartupMilliseconds =
                $candidateResult.startupMedianMilliseconds - $erasedResult.startupMedianMilliseconds
        }
    }
}

$net10Manifest = Read-Manifest $net10Bundle
$net48Manifest = Read-Manifest $net48Bundle
$repositoryHead = (& git -C $repositoryRoot rev-parse HEAD).Trim()
$repositoryStatus = @(& git -C $repositoryRoot status --porcelain --untracked-files=no)
$result = [ordered]@{
    schema = if ($CorpusKind -eq 'octo-tree') { 3 } elseif ($isRouteAttribution) { 2 } else { 1 }
    corpusKind = $CorpusKind
    workloadVersion = $workloadVersion
    measurementKind = if ($isRouteAttribution) { 'route-attribution' } else { 'aggregate' }
    measuredAtUtc = [DateTime]::UtcNow.ToString('O')
    scope = [ordered]@{
        candidateIsTestOwnedPhysicalization = $true
        candidateIsCompleteKotlinProduct = $false
        publishedBytesAreEndToEndComparable = $false
        compileDriverPeakWorkingSetIncludesChildren = $false
        representativeApplicationGateClosed = $false
        pairedApplicationCorpusClosed = $CorpusKind -eq 'octo-tree'
        routeAttributionIsBoundedMicroWorkload = $isRouteAttribution
        frameworkAndNet10AreIndependentEvidenceLanes = $true
        routeCallCountersCountWorkloadEntriesNotInternalOverrideFrames =
            $isRouteAttribution -and $CorpusKind -eq 'hostile'
        valueConversionCountersExcludeSetupAndIncludeFailedLoopUnboxAttempts = $isRouteAttribution
    }
    environment = [ordered]@{
        os = [Environment]::OSVersion.VersionString
        architecture = [Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString()
        processorCount = [Environment]::ProcessorCount
        dotnet = $dotnet
        sdkVersion = $sdkVersion
        measurementHost = [ordered]@{
            frameworkDescription = [Runtime.InteropServices.RuntimeInformation]::FrameworkDescription
            clrVersion = [Environment]::Version.ToString()
        }
        frameworkRuntime = [ordered]@{
            powershellHost = $frameworkPowerShell
            clrVersion = $frameworkClrVersion
            productVersion = [string]$frameworkInstallation.Version
            release = [int]$frameworkInstallation.Release
            clrDirectory = $frameworkDirectory
            references = @($frameworkReferences | ForEach-Object {
                [ordered]@{
                    path = $_
                    fileVersion = (Get-Item -LiteralPath $_).VersionInfo.FileVersion
                    sha256 = Get-Sha256 $_
                }
            })
        }
        repositoryHead = $repositoryHead
        repositoryDirty = $repositoryStatus.Count -gt 0
        measurementToolSha256 = Get-Sha256 $PSCommandPath
        nativeToolchain = $nativeToolchainInfo
    }
    corpus = [ordered]@{
        directory = $corpusDirectory
        net10ManifestSha256 = Get-Sha256 (Join-Path $net10Bundle 'generic-owner-application.properties')
        net48ManifestSha256 = Get-Sha256 (Join-Path $net48Bundle 'generic-owner-application.properties')
        net10ErasedProducerSha256 = $net10Manifest.erasedProducerSha256
        net10CandidateProducerSha256 = $net10Manifest.candidateProducerSha256
        net48ErasedProducerSha256 = $net48Manifest.erasedProducerSha256
        net48CandidateProducerSha256 = $net48Manifest.candidateProducerSha256
    }
    inputInventory = $inputInventory
    iterations = $Iterations
    compileRuns = $CompileRuns
    startupRuns = $StartupRuns
    throughputRuns = $ThroughputRuns
    attributionRoutes = @($AttributionRoutes)
    checksum = if ($isRouteAttribution) { $expectedChecksumByRoute } else { $expectedChecksum }
    measurements = $measurements
}
$resultPath = Join-Path $runDirectory 'results.json'
$result | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $resultPath -Encoding utf8NoBOM
Write-Host "Paired generic-owner $($result.measurementKind) measurements completed: $($Modes -join ', ')"
Write-Host "Result: $resultPath"
