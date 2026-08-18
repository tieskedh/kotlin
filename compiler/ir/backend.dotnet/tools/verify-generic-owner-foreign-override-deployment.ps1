<#
.SYNOPSIS
    Verifies foreign generic-owner override dispatch in .NET deployment modes.

.DESCRIPTION
    Produces the actual separate Kotlin base/override rehearsal assemblies,
    validates the allocation-free probe IL, then compiles one ordinary C#
    subclass against those assemblies. The same closed input is published and
    executed under JIT, ReadyToRun, full trimming, and NativeAOT. A successful
    NativeAOT analyzer run never substitutes for native link and execution.

.EXAMPLE
    pwsh compiler/ir/backend.dotnet/tools/verify-generic-owner-foreign-override-deployment.ps1 `
        -Modes jit,ready-to-run,trimmed

.EXAMPLE
    pwsh compiler/ir/backend.dotnet/tools/verify-generic-owner-foreign-override-deployment.ps1 `
        -Modes native-aot `
        -ExistingProduct C:\path\to\rehearsal-export `
        -NativeLinker C:\path\to\link.exe `
        -NativeLibraryDirectories C:\path\to\msvc-lib,C:\path\to\sdk-um,C:\path\to\sdk-ucrt
#>
[CmdletBinding()]
param(
    [ValidateSet('jit', 'ready-to-run', 'trimmed', 'native-aot')]
    [string[]]$Modes = @('jit', 'ready-to-run', 'trimmed', 'native-aot'),
    [string]$OutputDirectory,
    [string]$ExistingProduct,
    [string]$NativeLinker,
    [string[]]$NativeLibraryDirectories
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
if ($Modes.Count -eq 0 -or @($Modes | Select-Object -Unique).Count -ne $Modes.Count) {
    throw 'Deployment modes must contain one or more unique values'
}
if ([string]::IsNullOrWhiteSpace($NativeLinker) -ne ($null -eq $NativeLibraryDirectories)) {
    throw 'NativeLinker and NativeLibraryDirectories must be supplied together'
}
if (-not [string]::IsNullOrWhiteSpace($NativeLinker) -and 'native-aot' -notin $Modes) {
    throw 'An explicit native toolchain is only valid for a NativeAOT run'
}

$backendDirectory = Split-Path -Parent $PSScriptRoot
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $OutputDirectory = Join-Path $backendDirectory "build\generic-owner-foreign-override-deployment\$timestamp"
}
$runDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $runDirectory) {
    if (-not (Test-Path -LiteralPath $runDirectory -PathType Container) -or
            @(Get-ChildItem -LiteralPath $runDirectory -Force).Count -ne 0) {
        throw "The deployment output directory must not exist or must be empty: $runDirectory"
    }
} else {
    New-Item -ItemType Directory -Path $runDirectory | Out-Null
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-Product([string]$Directory) {
    $expectedNames = @(
        'lib.dll',
        'lib.il',
        'middle.dll',
        'middle.il',
        'main-genericOwnerForeignOverrideSeparateCompilation.dll',
        'main-genericOwnerForeignOverrideSeparateCompilation.il'
    )
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
        throw "The rehearsal product directory does not exist: $Directory"
    }
    $entries = @(Get-ChildItem -LiteralPath $Directory -Force)
    if (@($entries | Where-Object { $_.PSIsContainer }).Count -ne 0 -or
            @(Compare-Object @($entries.Name) $expectedNames).Count -ne 0) {
        throw "The rehearsal product is not the expected closed six-file export: $Directory"
    }

    $libIl = Get-Content -LiteralPath (Join-Path $Directory 'lib.il') -Raw
    $middleIl = Get-Content -LiteralPath (Join-Path $Directory 'middle.il') -Raw
    $mainIl = Get-Content -LiteralPath (
        Join-Path $Directory 'main-genericOwnerForeignOverrideSeparateCompilation.il'
    ) -Raw
    if ($mainIl -notmatch "\.assembly extern 'lib'" -or
            $mainIl -notmatch "\.assembly extern 'middle'" -or
            $mainIl -notmatch "\.method public hidebysig static string 'box'\(\)" -or
            $mainIl -notmatch '\.entrypoint') {
        throw 'The rehearsal export lost its separate Kotlin main semantic oracle'
    }
    $probePattern = "read__KotlinForeignOverrideProbe__[0-9a-f]{32}"
    $libProbe = [regex]::Match($libIl, $probePattern)
    $middleProbe = [regex]::Match($middleIl, $probePattern)
    if (-not $libProbe.Success -or -not $middleProbe.Success -or
            $libProbe.Value -cne $middleProbe.Value) {
        throw 'The base and separate Kotlin override do not share one recorded foreign-override probe'
    }
    $escapedProbe = [regex]::Escape($libProbe.Value)
    if ($libIl -notmatch "\.method family hidebysig newslot virtual instance bool '$escapedProbe'\(\)" -or
            $middleIl -notmatch "\.method family hidebysig virtual instance bool '$escapedProbe'\(\)" -or
            $middleIl -match "\.method family hidebysig newslot virtual instance bool '$escapedProbe'\(\)") {
        throw 'The separate Kotlin override lost the inherited protected probe slot'
    }
    foreach ($entry in @(
        [pscustomobject]@{ Name = 'base'; Text = $libIl },
        [pscustomobject]@{ Name = 'middle'; Text = $middleIl }
    )) {
        if ($entry.Text -notmatch "ldvirtftn instance !0 class [^\r\n]+::'read'\(\)" -or
                $entry.Text -notmatch "ldftn instance !0 class [^\r\n]+::'read'\(\)") {
            throw "The $($entry.Name) Kotlin owner lost its allocation-free managed-function probe"
        }
    }
}

function Invoke-Application([string]$FileName, [string[]]$Arguments, [string]$WorkingDirectory) {
    Push-Location $WorkingDirectory
    try {
        $output = (& $FileName @Arguments 2>&1 | Out-String).Trim()
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($exitCode -ne 0) {
        throw "Deployment application failed with exit code $exitCode`n$output"
    }
    if ($output -cne 'OK') {
        throw "Deployment application returned an unexpected oracle result '$output'"
    }
    return $output
}

$nativeToolchainInfo = $null
if (-not [string]::IsNullOrWhiteSpace($NativeLinker)) {
    $NativeLinker = [IO.Path]::GetFullPath($NativeLinker)
    if (-not (Test-Path -LiteralPath $NativeLinker -PathType Leaf)) {
        throw "The explicit NativeAOT linker does not exist: $NativeLinker"
    }
    $resolvedNativeLibraryDirectories = @($NativeLibraryDirectories | ForEach-Object {
        if ([string]::IsNullOrWhiteSpace($_)) { throw 'Native library directories must not be blank' }
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
            $linkerSignature.SignerCertificate.Subject -notmatch '(^|, )O=Microsoft Corporation(,|$)') {
        throw "The explicit NativeAOT linker is not validly signed by Microsoft: $NativeLinker"
    }
    $env:PATH = "$(Split-Path -Parent $NativeLinker)$([IO.Path]::PathSeparator)$env:PATH"
    $env:LIB = $resolvedNativeLibraryDirectories -join [IO.Path]::PathSeparator
    $env:IlcUseEnvironmentalTools = 'true'
    $nativeToolchainInfo = [ordered]@{
        discovery = 'explicit-msvc'
        linker = $NativeLinker
        linkerVersion = (Get-Item -LiteralPath $NativeLinker).VersionInfo.FileVersion
        linkerSha256 = Get-Sha256 $NativeLinker
        linkerSigner = $linkerSignature.SignerCertificate.Subject
        libraryDirectories = $resolvedNativeLibraryDirectories
    }
} elseif ('native-aot' -in $Modes) {
    $nativeToolchainInfo = [ordered]@{ discovery = 'dotnet-sdk-auto-discovery' }
}

if ([string]::IsNullOrWhiteSpace($ExistingProduct)) {
    $productDirectory = Join-Path $runDirectory 'product'
    New-Item -ItemType Directory -Path $productDirectory | Out-Null
    $gradle = Join-Path $repositoryRoot 'gradlew.bat'
    $testFilter =
        'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated$Box.' +
        'testGenericOwnerForeignOverrideSeparateCompilation'
    & $gradle --no-daemon `
        '-Pkotlin.dotnet.genericOwnerRehearsal=true' `
        "-Pkotlin.dotnet.genericOwnerRehearsalDir=$productDirectory" `
        -q ':compiler:fir:fir2ir:dotNetTest' --rerun '--tests' $testFilter
    if ($LASTEXITCODE -ne 0) {
        throw "The Kotlin rehearsal producer failed with exit code $LASTEXITCODE"
    }
} else {
    $productDirectory = [IO.Path]::GetFullPath($ExistingProduct)
}
Assert-Product $productDirectory

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
    throw "The deployment proof requires SDK 10.0.100, found '$sdkVersion' at $dotnet"
}

$platformDirectory = Join-Path $repositoryRoot 'compiler\fir\fir2ir\build\dotnet-test-platform\net10.0'
$runtime = Join-Path $platformDirectory 'Kotlin.Runtime.dll'
$stdlib = Join-Path $platformDirectory 'Kotlin.Stdlib.dll'
if (-not (Test-Path -LiteralPath $runtime -PathType Leaf) -or
        -not (Test-Path -LiteralPath $stdlib -PathType Leaf)) {
    throw 'The reusable net10 Kotlin Runtime/Stdlib test platform is absent; run the focused test first'
}

$bundleDirectory = Join-Path $runDirectory 'bundle'
New-Item -ItemType Directory -Path $bundleDirectory | Out-Null
foreach ($input in @(
    (Join-Path $productDirectory 'lib.dll'),
    (Join-Path $productDirectory 'middle.dll'),
    $runtime,
    $stdlib
)) {
    Copy-Item -LiteralPath $input -Destination (Join-Path $bundleDirectory (Split-Path -Leaf $input))
}

$sourcePath = Join-Path $bundleDirectory 'ForeignOverrideDeployment.cs'
$sourceText = @'
using System;

public sealed class RehearsalDeploymentCSharpOverrideStore :
    RehearsalSeparateKotlinOverrideStore<string>
{
    public RehearsalDeploymentCSharpOverrideStore() : base("kotlin-middle") {}

    public override string read()
    {
        return "csharp-after-separate-kotlin";
    }
}

public static class Program
{
    public static int Main()
    {
        var reader = new RehearsalSeparateReader();
        var kotlin = new RehearsalSeparateKotlinOverrideStore<string>("kotlin-middle");
        if (!object.Equals(reader.read(kotlin), "kotlin-middle"))
            throw new InvalidOperationException("unchanged Kotlin probe did not use semantic dispatch");

        var value = new RehearsalSeparateKotlinOverrideStore<int>(23);
        if (value.read() != 23 || !object.Equals(reader.read(value), 23))
            throw new InvalidOperationException("value-type Kotlin probe changed its value");

        var foreign = new RehearsalDeploymentCSharpOverrideStore();
        if (foreign.read() != "csharp-after-separate-kotlin")
            throw new InvalidOperationException("direct typed C# override was not invoked");
        if (!object.Equals(reader.read(foreign), "csharp-after-separate-kotlin"))
            throw new InvalidOperationException("Kotlin semantic dispatch bypassed the typed C# override");

        Console.WriteLine("OK");
        return 0;
    }
}
'@
[IO.File]::WriteAllText($sourcePath, $sourceText, [Text.UTF8Encoding]::new($false))

$projectPath = Join-Path $bundleDirectory 'ForeignOverrideDeployment.csproj'
$projectText = @'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <AssemblyName>ForeignOverrideDeployment</AssemblyName>
    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>
    <ImplicitUsings>disable</ImplicitUsings>
    <Nullable>disable</Nullable>
    <Optimize>true</Optimize>
    <Deterministic>true</Deterministic>
    <DebugSymbols>false</DebugSymbols>
    <DebugType>none</DebugType>
    <InvariantGlobalization>true</InvariantGlobalization>
    <IsAotCompatible>true</IsAotCompatible>
    <WarningsAsErrors>IL2026;IL3050</WarningsAsErrors>
  </PropertyGroup>
  <ItemGroup>
    <Compile Include="ForeignOverrideDeployment.cs" />
    <Reference Include="lib"><HintPath>lib.dll</HintPath><Private>true</Private></Reference>
    <Reference Include="middle"><HintPath>middle.dll</HintPath><Private>true</Private></Reference>
    <Reference Include="Kotlin.Runtime"><HintPath>Kotlin.Runtime.dll</HintPath><Private>true</Private></Reference>
    <Reference Include="Kotlin.Stdlib"><HintPath>Kotlin.Stdlib.dll</HintPath><Private>true</Private></Reference>
  </ItemGroup>
</Project>
'@
[IO.File]::WriteAllText($projectPath, $projectText, [Text.UTF8Encoding]::new($false))
$globalJsonPath = Join-Path $bundleDirectory 'global.json'
$globalJsonText = @'
{
  "sdk": {
    "version": "10.0.100",
    "rollForward": "disable",
    "allowPrerelease": false
  }
}
'@
[IO.File]::WriteAllText($globalJsonPath, $globalJsonText, [Text.UTF8Encoding]::new($false))

$inputHashes = [ordered]@{}
foreach ($file in @(Get-ChildItem -LiteralPath $bundleDirectory -File | Sort-Object Name)) {
    $inputHashes[$file.Name] = Get-Sha256 $file.FullName
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

$results = @()
foreach ($mode in $Modes) {
    $definition = $modeDefinitions[$mode]
    $publishDirectory = Join-Path $runDirectory "publish\$mode"
    $intermediateDirectory = Join-Path $runDirectory "obj\$mode"
    $binaryDirectory = Join-Path $runDirectory "bin\$mode"
    New-Item -ItemType Directory -Force -Path $publishDirectory | Out-Null
    $arguments = @(
        'publish', $projectPath,
        '-c', 'Release',
        '-r', 'win-x64',
        '--self-contained', $definition.selfContained.ToString().ToLowerInvariant(),
        '--nologo',
        '--disable-build-servers',
        '-o', $publishDirectory,
        "-p:BaseIntermediateOutputPath=$intermediateDirectory$([IO.Path]::DirectorySeparatorChar)",
        "-p:BaseOutputPath=$binaryDirectory$([IO.Path]::DirectorySeparatorChar)"
    )
    foreach ($property in $definition.properties) { $arguments += "-p:$property" }
    Push-Location $bundleDirectory
    try {
        $publishOutput = (& $dotnet @arguments 2>&1 | Out-String)
        $publishExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    $publishLog = Join-Path $publishDirectory 'publish.log'
    [IO.File]::WriteAllText($publishLog, $publishOutput, [Text.UTF8Encoding]::new($false))
    if ($publishExitCode -ne 0) {
        throw "Publishing '$mode' failed with $publishExitCode. See $publishLog`n$publishOutput"
    }
    if ($publishOutput -match '(?i)IL2026|IL3050|:\s*(warning|error)\s') {
        throw "Publishing '$mode' emitted a trimming, AOT, compiler, or linker diagnostic. See $publishLog"
    }

    $application = Join-Path $publishDirectory 'ForeignOverrideDeployment.exe'
    $applicationDll = Join-Path $publishDirectory 'ForeignOverrideDeployment.dll'
    if ($definition.selfContained) {
        if (-not (Test-Path -LiteralPath $application -PathType Leaf)) {
            throw "Mode '$mode' did not publish a self-contained executable"
        }
        $runFile = $application
        $runArguments = @()
    } else {
        if (-not (Test-Path -LiteralPath $applicationDll -PathType Leaf)) {
            throw "Mode '$mode' did not publish a framework-dependent assembly"
        }
        $runFile = $dotnet
        $runArguments = @('exec', $applicationDll)
    }
    $oracle = Invoke-Application $runFile $runArguments $publishDirectory
    $publishedFiles = @(Get-ChildItem -LiteralPath $publishDirectory -File -Recurse |
        Where-Object { $_.FullName -ne $publishLog })
    $results += [ordered]@{
        mode = $mode
        oracle = $oracle
        nativeAotExecuted = $mode -eq 'native-aot'
        publishedFileCount = $publishedFiles.Count
        publishedBytes = [long](($publishedFiles | Measure-Object Length -Sum).Sum)
        executableSha256 = if ($definition.selfContained) { Get-Sha256 $application } else { $null }
    }
}

foreach ($entry in $inputHashes.GetEnumerator()) {
    $path = Join-Path $bundleDirectory $entry.Key
    if ((Get-Sha256 $path) -cne $entry.Value) {
        throw "Publishing mutated closed input $($entry.Key)"
    }
}

$repositoryHead = (& git -C $repositoryRoot rev-parse HEAD).Trim()
$repositoryStatus = @(& git -C $repositoryRoot status --short)
$report = [ordered]@{
    schema = 1
    verifiedAtUtc = [DateTime]::UtcNow.ToString('o')
    environment = [ordered]@{
        os = [Environment]::OSVersion.VersionString
        architecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
        dotnet = $dotnet
        sdkVersion = $sdkVersion
        repositoryHead = $repositoryHead
        repositoryDirty = $repositoryStatus.Count -gt 0
        toolSha256 = Get-Sha256 $PSCommandPath
        nativeToolchain = $nativeToolchainInfo
    }
    product = [ordered]@{
        directory = $productDirectory
        libSha256 = Get-Sha256 (Join-Path $productDirectory 'lib.dll')
        middleSha256 = Get-Sha256 (Join-Path $productDirectory 'middle.dll')
    }
    inputHashes = $inputHashes
    modes = $results
}
$resultPath = Join-Path $runDirectory 'results.json'
[IO.File]::WriteAllText(
    $resultPath,
    ($report | ConvertTo-Json -Depth 10),
    [Text.UTF8Encoding]::new($false)
)
Write-Output "Generic-owner foreign override deployment proof passed: $resultPath"
