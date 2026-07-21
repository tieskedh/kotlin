<#
.SYNOPSIS
    Provisions the modern .NET toolchain used by the Kotlin/.NET (CIL) backend.

.DESCRIPTION
    Installs, without admin rights, into a durable per-user location:
      <InstallDir>\dotnet\dotnet.exe   - .NET runtime and SDK (via the official dotnet-install.ps1)
      <InstallDir>\dotnet\sdk\...\csc.dll - modern Roslyn C# compiler for integration tests
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
    # Pinned .NET SDK used only by Roslyn/C# integration tests.
    [string]$SdkVersion = '10.0.100',
    # Pinned version of the runtime.win-x64.microsoft.netcore.ilasm NuGet package.
    [string]$IlasmVersion = '10.0.9'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$dotnetDir = Join-Path $InstallDir 'dotnet'
$dotnetExe = Join-Path $dotnetDir 'dotnet.exe'
$sdkMarker = Join-Path $dotnetDir "sdk\$SdkVersion"
$csharpCompiler = Join-Path $sdkMarker 'Roslyn\bincore\csc.dll'
$net10ReferencePack = Join-Path $dotnetDir 'packs\Microsoft.NETCore.App.Ref\10.0.0\ref\net10.0'
$ilasmDir  = Join-Path $InstallDir 'ilasm'
$ilasmExe  = Join-Path $ilasmDir 'ilasm.exe'

function Get-DotNetInstallScript {
    $installScript = Join-Path ([IO.Path]::GetTempPath()) 'dotnet-install.ps1'
    if (-not (Test-Path $installScript)) {
        Invoke-WebRequest 'https://dot.net/v1/dotnet-install.ps1' -OutFile $installScript
    }
    return $installScript
}

# --- .NET runtime -------------------------------------------------------------------------------
$runtimeMarker = Join-Path $dotnetDir "shared\Microsoft.NETCore.App\$RuntimeVersion"
if ((Test-Path $dotnetExe) -and (Test-Path $runtimeMarker)) {
    Write-Host ".NET runtime $RuntimeVersion already present - skipping."
} else {
    Write-Host "Installing .NET runtime $RuntimeVersion into $dotnetDir ..."
    # The script is Authenticode-signed by Microsoft; -NoPath keeps the install out of PATH.
    & (Get-DotNetInstallScript) -Runtime dotnet -Version $RuntimeVersion -InstallDir $dotnetDir -NoPath
    if (-not (Test-Path $runtimeMarker)) {
        throw "dotnet-install.ps1 finished but $runtimeMarker is missing."
    }
}

# --- .NET SDK / modern Roslyn ------------------------------------------------------------------
if ((Test-Path $sdkMarker) -and (Test-Path $csharpCompiler) -and (Test-Path $net10ReferencePack)) {
    Write-Host ".NET SDK $SdkVersion already present - skipping."
} else {
    Write-Host "Installing .NET SDK $SdkVersion into $dotnetDir ..."
    & (Get-DotNetInstallScript) -Version $SdkVersion -InstallDir $dotnetDir -NoPath
    if (-not (Test-Path $csharpCompiler) -or -not (Test-Path $net10ReferencePack)) {
        throw "dotnet-install.ps1 finished but Roslyn or the net10 reference pack is missing."
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
Write-Host "dotnet         : $dotnetExe (runtime $RuntimeVersion, SDK $SdkVersion)"
Write-Host "csc            : $csharpCompiler"
Write-Host "references     : $net10ReferencePack"
Write-Host "ilasm          : $ilasmExe (package version $IlasmVersion)"
