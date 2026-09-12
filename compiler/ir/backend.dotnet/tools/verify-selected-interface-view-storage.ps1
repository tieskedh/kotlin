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
$dotnet = [IO.Path]::GetFullPath((Join-Path $DotNetDirectory 'dotnet.exe'))
$roslyn = [IO.Path]::GetFullPath((Join-Path $DotNetDirectory 'sdk\10.0.100\Roslyn\bincore\csc.dll'))
$modernReferences = [IO.Path]::GetFullPath((Join-Path $DotNetDirectory 'packs\Microsoft.NETCore.App.Ref\10.0.0\ref\net10.0'))
$frameworkCompilerPath = [IO.Path]::GetFullPath($FrameworkCompiler)
$frameworkReferences = [IO.Path]::GetFullPath($FrameworkReferenceDirectory)
$frameworkReferenceFiles = @('mscorlib.dll', 'System.dll') | ForEach-Object {
    Join-Path $frameworkReferences $_
}
$modernReferenceFiles = @('System.Runtime.dll', 'System.Console.dll', 'System.Reflection.dll',
    'System.Reflection.Extensions.dll', 'mscorlib.dll') | ForEach-Object {
    Join-Path $modernReferences $_
}
foreach ($required in @($source, $dotnet, $roslyn, $frameworkCompilerPath) +
    $frameworkReferenceFiles + $modernReferenceFiles) {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required proof input is unavailable: $required"
    }
}

New-Item -ItemType Directory -Path $outputPath | Out-Null
$frozenSource = Join-Path $outputPath 'SelectedViewStorageProbe.cs'
Copy-Item -LiteralPath $source -Destination $frozenSource
$sourceHash = (Get-FileHash -LiteralPath $frozenSource -Algorithm SHA256).Hash

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

$frameworkOutput = Join-Path $outputPath 'SelectedViewStorageProbe.net48.exe'
Invoke-ProofTool -Executable $frameworkCompilerPath -Arguments (@(
    '/nologo', '/noconfig', '/nostdlib+', '/warnaserror+', '/target:exe',
    "/out:$frameworkOutput", $frozenSource
) + @($frameworkReferenceFiles | ForEach-Object { "/r:$_" })) -LogName 'net48-compile.log' | Out-Null
@'
<?xml version="1.0" encoding="utf-8"?>
<configuration><startup><supportedRuntime version="v4.0" sku=".NETFramework,Version=v4.8" /></startup></configuration>
'@ | Out-File -LiteralPath "$frameworkOutput.config" -Encoding utf8
$frameworkResult = Invoke-ProofTool -Executable $frameworkOutput -Arguments @() -LogName 'net48-run.log'

$modernOutput = Join-Path $outputPath 'SelectedViewStorageProbe.net10.dll'
Invoke-ProofTool -Executable $dotnet -Arguments (@(
    $roslyn, '/nologo', '/noconfig', '/nostdlib+', '/warnaserror+', '/target:exe',
    "/out:$modernOutput", $frozenSource
) + @($modernReferenceFiles | ForEach-Object { "/r:$_" })) -LogName 'net10-compile.log' | Out-Null
@'
{"runtimeOptions":{"tfm":"net10.0","framework":{"name":"Microsoft.NETCore.App","version":"10.0.0"},"rollForward":"LatestPatch"}}
'@ | Out-File -LiteralPath (Join-Path $outputPath 'SelectedViewStorageProbe.net10.runtimeconfig.json') -Encoding utf8
$modernResult = Invoke-ProofTool -Executable $dotnet -Arguments @($modernOutput) -LogName 'net10-run.log'

foreach ($result in @($frameworkResult, $modernResult)) {
    if (@($result | Where-Object { $_ -like 'PASS: stored selections,*' }).Count -ne 1) {
        throw 'A zero-exit run did not report the complete proof assertions.'
    }
}
if ((Get-FileHash -LiteralPath $frozenSource -Algorithm SHA256).Hash -ne $sourceHash) {
    throw 'The frozen source changed during verification.'
}
$frameworkInstallation = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full'
[ordered]@{
    kind = 'standalone-clr-feasibility-only'
    sourceSha256 = $sourceHash
    net48ReferenceDirectory = $frameworkReferences
    installedFrameworkVersion = $frameworkInstallation.Version
    installedFrameworkRelease = $frameworkInstallation.Release
    net48 = [string[]]$frameworkResult
    net10 = [string[]]$modernResult
    installedModernRuntimes = [string[]](& $dotnet --list-runtimes)
    exclusions = @('Kotlin integration', 'ABI freeze', 'concurrency/volatile', 'trimming/NativeAOT', 'performance')
} | ConvertTo-Json -Depth 5 | Out-File -LiteralPath (Join-Path $outputPath 'verification.json') -Encoding utf8
Write-Output "PASS: net48-target and net10 selected-view storage proof; evidence: $outputPath"
