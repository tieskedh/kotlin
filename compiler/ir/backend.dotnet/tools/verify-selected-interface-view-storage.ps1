[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,
    [string]$DotNetDirectory = (Join-Path $env:LOCALAPPDATA 'kotlinc-dotnet\toolchain\dotnet'),
    [string]$FrameworkReferenceDirectory = (Join-Path $env:USERPROFILE '.nuget\packages\microsoft.netframework.referenceassemblies.net48\1.0.3\build\.NETFramework\v4.8'),
    [string]$FrameworkCompiler = (Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe')
)

# Standalone CLR feasibility evidence, not Kotlin compiler integration or an ABI gate.
# Keep this run separate from compiler gates using the Framework toolchain.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputPath) {
    throw 'Choose a new output directory; existing proof evidence is never overwritten.'
}
$source = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'fixtures\SelectedViewStorageProbe.cs'))
$dispatchSource = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'fixtures\TargetDirectedDispatchProbe.cs'))
$librarySource = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'fixtures\SelectedViewStorageLibrary.cs'))
$dotnet = [IO.Path]::GetFullPath((Join-Path $DotNetDirectory 'dotnet.exe'))
$roslyn = [IO.Path]::GetFullPath((Join-Path $DotNetDirectory 'sdk\10.0.100\Roslyn\bincore\csc.dll'))
$modernReferences = [IO.Path]::GetFullPath((Join-Path $DotNetDirectory 'packs\Microsoft.NETCore.App.Ref\10.0.0\ref\net10.0'))
$frameworkCompilerPath = [IO.Path]::GetFullPath($FrameworkCompiler)
$frameworkReferences = [IO.Path]::GetFullPath($FrameworkReferenceDirectory)
$frameworkReferenceFiles = @('mscorlib.dll', 'System.dll') | ForEach-Object {
    Join-Path $frameworkReferences $_
}
$modernReferenceFiles = @('System.Runtime.dll', 'System.Console.dll', 'System.Reflection.dll',
    'System.Reflection.Extensions.dll', 'System.Threading.dll', 'System.Threading.Thread.dll', 'mscorlib.dll') | ForEach-Object {
    Join-Path $modernReferences $_
}
foreach ($required in @($source, $dispatchSource, $librarySource, $dotnet, $roslyn, $frameworkCompilerPath) +
    $frameworkReferenceFiles + $modernReferenceFiles) {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required proof input is unavailable: $required"
    }
}

New-Item -ItemType Directory -Path $outputPath | Out-Null
$frozenSource = Join-Path $outputPath 'SelectedViewStorageProbe.cs'
Copy-Item -LiteralPath $source -Destination $frozenSource
$sourceHash = (Get-FileHash -LiteralPath $frozenSource -Algorithm SHA256).Hash
$frozenDispatchSource = Join-Path $outputPath 'TargetDirectedDispatchProbe.cs'
Copy-Item -LiteralPath $dispatchSource -Destination $frozenDispatchSource
$dispatchSourceHash = (Get-FileHash -LiteralPath $frozenDispatchSource -Algorithm SHA256).Hash
$frozenLibrarySource = Join-Path $outputPath 'SelectedViewStorageLibrary.cs'
Copy-Item -LiteralPath $librarySource -Destination $frozenLibrarySource
$librarySourceHash = (Get-FileHash -LiteralPath $frozenLibrarySource -Algorithm SHA256).Hash

function Invoke-ProofTool {
    param([string]$Executable, [string[]]$Arguments, [string]$LogName)
    $output = & $Executable @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $output | Out-File -LiteralPath (Join-Path $outputPath $LogName) -Encoding utf8
    if ($exitCode -ne 0) {
        throw "Proof tool failed ($exitCode); see $LogName in $outputPath"
    }
    return $output
}

$frameworkLibrary = Join-Path $outputPath 'SelectedViewStorageLibrary.net48.dll'
Invoke-ProofTool -Executable $frameworkCompilerPath -Arguments (@(
    '/nologo', '/noconfig', '/nostdlib+', '/warnaserror+', '/target:library',
    "/out:$frameworkLibrary", $frozenLibrarySource
) + @($frameworkReferenceFiles | ForEach-Object { "/r:$_" })) -LogName 'net48-library-compile.log' | Out-Null
$frameworkLibraryHash = (Get-FileHash -LiteralPath $frameworkLibrary -Algorithm SHA256).Hash
$frameworkOutput = Join-Path $outputPath 'SelectedViewStorageProbe.net48.exe'
Invoke-ProofTool -Executable $frameworkCompilerPath -Arguments (@(
    '/nologo', '/noconfig', '/nostdlib+', '/warnaserror+', '/target:exe',
    "/out:$frameworkOutput", "/r:$frameworkLibrary", $frozenSource, $frozenDispatchSource
) + @($frameworkReferenceFiles | ForEach-Object { "/r:$_" })) -LogName 'net48-compile.log' | Out-Null
@'
<?xml version="1.0" encoding="utf-8"?>
<configuration><startup><supportedRuntime version="v4.0" sku=".NETFramework,Version=v4.8" /></startup></configuration>
'@ | Out-File -LiteralPath "$frameworkOutput.config" -Encoding utf8
$frameworkResult = Invoke-ProofTool -Executable $frameworkOutput -Arguments @() -LogName 'net48-run.log'

$modernLibrary = Join-Path $outputPath 'SelectedViewStorageLibrary.net10.dll'
Invoke-ProofTool -Executable $dotnet -Arguments (@(
    $roslyn, '/nologo', '/noconfig', '/nostdlib+', '/warnaserror+', '/target:library',
    "/out:$modernLibrary", $frozenLibrarySource
) + @($modernReferenceFiles | ForEach-Object { "/r:$_" })) -LogName 'net10-library-compile.log' | Out-Null
$modernLibraryHash = (Get-FileHash -LiteralPath $modernLibrary -Algorithm SHA256).Hash
$modernOutput = Join-Path $outputPath 'SelectedViewStorageProbe.net10.dll'
Invoke-ProofTool -Executable $dotnet -Arguments (@(
    $roslyn, '/nologo', '/noconfig', '/nostdlib+', '/warnaserror+', '/target:exe',
    "/out:$modernOutput", "/r:$modernLibrary", $frozenSource, $frozenDispatchSource
) + @($modernReferenceFiles | ForEach-Object { "/r:$_" })) -LogName 'net10-compile.log' | Out-Null
@'
{"runtimeOptions":{"tfm":"net10.0","framework":{"name":"Microsoft.NETCore.App","version":"10.0.0"},"rollForward":"LatestPatch"}}
'@ | Out-File -LiteralPath (Join-Path $outputPath 'SelectedViewStorageProbe.net10.runtimeconfig.json') -Encoding utf8
$modernResult = Invoke-ProofTool -Executable $dotnet -Arguments @($modernOutput) -LogName 'net10-run.log'

foreach ($result in @($frameworkResult, $modernResult)) {
    foreach ($assertion in @('PASS: stored selections,*', 'PASS: separate generic DLL,*',
        'PASS: object boundary preserves receiver*', 'PASS: locked whole-pair storage;*',
        'PASS: native target without exact row;*', 'PASS: exact native target wins;*',
        'PASS: child interface reimplementation;*', 'PASS: bounded producer-only hypothesis;*')) {
        if (@($result | Where-Object { $_ -like $assertion }).Count -ne 1) {
            throw "A zero-exit run did not report the complete proof assertions: $assertion"
        }
    }
}
foreach ($inputCheck in @(
    @{ Paths = @($source, $frozenSource); Hash = $sourceHash },
    @{ Paths = @($dispatchSource, $frozenDispatchSource); Hash = $dispatchSourceHash },
    @{ Paths = @($librarySource, $frozenLibrarySource); Hash = $librarySourceHash },
    @{ Paths = @($frameworkLibrary); Hash = $frameworkLibraryHash },
    @{ Paths = @($modernLibrary); Hash = $modernLibraryHash }
)) {
    foreach ($path in $inputCheck.Paths) {
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $inputCheck.Hash) {
            throw "A proof input or separately compiled library changed during verification: $path"
        }
    }
}
$frameworkInstallation = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full'
[ordered]@{
    kind = 'standalone-clr-feasibility-only'
    sourceSha256 = $sourceHash
    dispatchSourceSha256 = $dispatchSourceHash
    librarySourceSha256 = $librarySourceHash
    net48LibrarySha256 = $frameworkLibraryHash
    net10LibrarySha256 = $modernLibraryHash
    net48ReferenceDirectory = $frameworkReferences
    installedFrameworkVersion = $frameworkInstallation.Version
    installedFrameworkRelease = $frameworkInstallation.Release
    net48 = [string[]]$frameworkResult
    net10 = [string[]]$modernResult
    installedModernRuntimes = [string[]](& $dotnet --list-runtimes)
    exclusions = @('Kotlin integration and identity lowering', 'Kotlin generic storage ABI', 'ABI freeze',
        'volatile/lock-free storage', 'Any selection-preserving round trip', 'Kotlin-owned conflicting-family policy',
        'multi-member/input-dependent semantic dispatch', 'trimming/NativeAOT', 'performance')
} | ConvertTo-Json -Depth 5 | Out-File -LiteralPath (Join-Path $outputPath 'verification.json') -Encoding utf8
Write-Output "PASS: net48-target and net10 selected-view storage proof; evidence: $outputPath"
