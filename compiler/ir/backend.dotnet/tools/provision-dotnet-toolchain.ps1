<#
.SYNOPSIS
    Provisions the modern .NET toolchain used by the Kotlin/.NET (CIL) backend.

.DESCRIPTION
    Installs, without admin rights, into a durable per-user location:
      <InstallDir>\dotnet\dotnet.exe   - .NET runtime (via the official dotnet-install.ps1)
      <InstallDir>\ilasm\ilasm.exe     - modern CoreCLR IL assembler (self-contained native exe
                                         from the NuGet package runtime.win-x64.microsoft.netcore.ilasm)

    Idempotent: components that are already present at the pinned versions are skipped.

.EXAMPLE
    pwsh compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1
#>
[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'kotlinc-dotnet\toolchain'),
    # Pinned .NET runtime version (latest 10.0.x patch at the time of writing).
    [string]$RuntimeVersion = '10.0.9',
    # Pinned version of the runtime.win-x64.microsoft.netcore.ilasm NuGet package.
    [string]$IlasmVersion = '10.0.9'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$dotnetDir = Join-Path $InstallDir 'dotnet'
$dotnetExe = Join-Path $dotnetDir 'dotnet.exe'
$ilasmDir  = Join-Path $InstallDir 'ilasm'
$ilasmExe  = Join-Path $ilasmDir 'ilasm.exe'

# --- .NET runtime -------------------------------------------------------------------------------
$runtimeMarker = Join-Path $dotnetDir "shared\Microsoft.NETCore.App\$RuntimeVersion"
if ((Test-Path $dotnetExe) -and (Test-Path $runtimeMarker)) {
    Write-Host ".NET runtime $RuntimeVersion already present - skipping."
} else {
    Write-Host "Installing .NET runtime $RuntimeVersion into $dotnetDir ..."
    $installScript = Join-Path ([IO.Path]::GetTempPath()) 'dotnet-install.ps1'
    Invoke-WebRequest 'https://dot.net/v1/dotnet-install.ps1' -OutFile $installScript
    # The script is Authenticode-signed by Microsoft; -NoPath keeps the install out of PATH.
    & $installScript -Runtime dotnet -Version $RuntimeVersion -InstallDir $dotnetDir -NoPath
    if (-not (Test-Path $runtimeMarker)) {
        throw "dotnet-install.ps1 finished but $runtimeMarker is missing."
    }
}

# --- modern ilasm -------------------------------------------------------------------------------
if (Test-Path $ilasmExe) {
    Write-Host "ilasm already present - skipping."
} else {
    Write-Host "Fetching modern ilasm $IlasmVersion from NuGet ..."
    $pkg = 'runtime.win-x64.microsoft.netcore.ilasm'
    $url = "https://api.nuget.org/v3-flatcontainer/$pkg/$IlasmVersion/$pkg.$IlasmVersion.nupkg"
    # A .nupkg is a plain zip; give it a .zip name so Expand-Archive accepts it.
    $zip = Join-Path ([IO.Path]::GetTempPath()) "netcore-ilasm-$IlasmVersion.zip"
    $extracted = Join-Path ([IO.Path]::GetTempPath()) "netcore-ilasm-$IlasmVersion"
    Invoke-WebRequest $url -OutFile $zip
    if (Test-Path $extracted) { Remove-Item -Recurse -Force $extracted }
    Expand-Archive $zip -DestinationPath $extracted
    New-Item -ItemType Directory -Force $ilasmDir | Out-Null
    Copy-Item (Join-Path $extracted 'runtimes\win-x64\native\ilasm.exe') $ilasmExe
    Remove-Item -Force $zip
    Remove-Item -Recurse -Force $extracted
    if (-not (Test-Path $ilasmExe)) {
        throw "ilasm extraction finished but $ilasmExe is missing."
    }
}

# --- report -------------------------------------------------------------------------------------
Write-Host ''
Write-Host "Toolchain root : $InstallDir"
Write-Host "dotnet         : $dotnetExe (runtime $RuntimeVersion)"
Write-Host "ilasm          : $ilasmExe (package version $IlasmVersion)"
