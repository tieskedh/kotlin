<# Standalone C# CLR mechanism only. No Kotlin build, provisioning or network. #>
[CmdletBinding()]
param(
    [ValidateSet('net48', 'net10')][string[]]$Profiles = @('net48', 'net10'),
    [string]$DotNetHost,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Use PowerShell 7.' }
if (@($Profiles | Select-Object -Unique).Count -ne $Profiles.Count) { throw 'Duplicate profile.' }
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot ('run-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
$probeRun = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $probeRun) {
    if (@(Get-ChildItem -LiteralPath $probeRun -Force).Count -ne 0) { throw 'Output must be empty.' }
} else { New-Item -ItemType Directory -Path $probeRun | Out-Null }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Save([string]$Path, [string]$Text) { [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false)) }
function Invoke-ProbeProcess([string]$Executable, [string[]]$ProcessArguments, [string]$Directory) {
    $probeStart = [Diagnostics.ProcessStartInfo]::new()
    $probeStart.FileName = $Executable
    $probeStart.WorkingDirectory = $Directory
    $probeStart.UseShellExecute = $false
    $probeStart.RedirectStandardOutput = $true
    $probeStart.RedirectStandardError = $true
    foreach ($argument in $ProcessArguments) { $probeStart.ArgumentList.Add($argument) }
    $probeProcess = [Diagnostics.Process]::Start($probeStart)
    $probeOut = $probeProcess.StandardOutput.ReadToEndAsync()
    $probeError = $probeProcess.StandardError.ReadToEndAsync()
    if (-not $probeProcess.WaitForExit(180000)) {
        $probeProcess.Kill($true); $probeProcess.WaitForExit()
        throw 'Owned probe process timed out.'
    }
    $probeResult = [pscustomobject]@{ ExitCode=$probeProcess.ExitCode; Stdout=$probeOut.GetAwaiter().GetResult(); Stderr=$probeError.GetAwaiter().GetResult() }
    $probeProcess.Dispose()
    return $probeResult
}
if ([string]::IsNullOrWhiteSpace($DotNetHost)) {
    $DotNetHost = Join-Path $env:LOCALAPPDATA 'kotlinc-dotnet\toolchain\dotnet\dotnet.exe'
}
$DotNetHost = (Resolve-Path -LiteralPath $DotNetHost).Path
$probeDotNetRoot = Split-Path -Parent $DotNetHost
$probeCompiler = Join-Path $probeDotNetRoot 'sdk\10.0.100\Roslyn\bincore\csc.dll'
if (-not (Test-Path -LiteralPath $probeCompiler -PathType Leaf)) { throw 'Installed SDK 10.0.100 compiler missing.' }
$probeFramework = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319'
$probeFrameworkHost = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
$probeSourceNames = @('Profile.cs','Producer.cs','Writers.cs','Consumer.cs','UnrestrictedNullable.cs','Invoke-Framework.ps1','Run-Probe.ps1','README.md')
$probeManifest = [ordered]@{
    label='standalone-CLR-mechanism-only-not-Kotlin'; createdUtc=[DateTime]::UtcNow.ToString('o')
    host=$DotNetHost; hostSha256=Hash $DotNetHost; compiler=$probeCompiler; compilerSha256=Hash $probeCompiler
    profiles=$Profiles; sources=@(); runs=@()
}
$probeSources = Join-Path $probeRun 'sources'
New-Item -ItemType Directory -Path $probeSources | Out-Null
foreach ($name in $probeSourceNames) {
    $probeSource = Join-Path $PSScriptRoot $name
    $probeHash = Hash $probeSource
    Copy-Item -LiteralPath $probeSource -Destination (Join-Path $probeSources $name)
    if ((Hash (Join-Path $probeSources $name)) -ne $probeHash) { throw 'Source copy changed.' }
    $probeManifest.sources += [ordered]@{name=$name; sha256=$probeHash}
}
Save (Join-Path $probeRun 'manifest.json') ($probeManifest | ConvertTo-Json -Depth 8)
foreach ($profile in $Profiles) {
    $probeDirectory = Join-Path $probeRun $profile
    New-Item -ItemType Directory -Path $probeDirectory | Out-Null
    $probeReferences = if ($profile -eq 'net10') {
        @(Get-ChildItem -LiteralPath (Join-Path $probeDotNetRoot 'packs\Microsoft.NETCore.App.Ref\10.0.0\ref\net10.0') -Filter '*.dll' | Sort-Object Name | ForEach-Object FullName)
    } else {
        @('mscorlib.dll','System.dll','System.Core.dll') | ForEach-Object { Join-Path $probeFramework $_ }
    }
    if ($probeReferences.Count -eq 0) { throw 'Reference assemblies missing.' }
    $probeReferenceHashes = @($probeReferences | ForEach-Object { [ordered]@{path=$_;sha256=Hash $_} })
    $probeCommon = @($probeCompiler,'/nologo','/noconfig','/nostdlib+','/deterministic+','/optimize+','/platform:x64','/warnaserror+')
    if ($profile -eq 'net48') { $probeCommon += '/define:NET48' }
    foreach ($reference in $probeReferences) { $probeCommon += "/reference:$reference" }
    $probeProfileSource = Join-Path $probeSources 'Profile.cs'
    $probeProducer = Join-Path $probeDirectory 'Producer.dll'
    $probeWriters = Join-Path $probeDirectory 'Writers.dll'
    $probeConsumer = Join-Path $probeDirectory $(if ($profile -eq 'net48') { 'Consumer.exe' } else { 'Consumer.dll' })
    $probeBuilds = @(
        @{Name='producer'; Output=$probeProducer; Arguments=@('/target:library',"/out:$probeProducer",$probeProfileSource,(Join-Path $probeSources 'Producer.cs'))},
        @{Name='writers'; Output=$probeWriters; Arguments=@('/target:library',"/out:$probeWriters","/reference:$probeProducer",$probeProfileSource,(Join-Path $probeSources 'Writers.cs'))},
        @{Name='consumer'; Output=$probeConsumer; Arguments=@('/target:exe',"/out:$probeConsumer","/reference:$probeProducer","/reference:$probeWriters",$probeProfileSource,(Join-Path $probeSources 'Consumer.cs'))}
    )
    foreach ($build in $probeBuilds) {
        $result = Invoke-ProbeProcess $DotNetHost ($probeCommon + $build.Arguments) $probeDirectory
        Save (Join-Path $probeDirectory ($build.Name + '-compilation.txt')) ($result.Stdout + $result.Stderr)
        if ($result.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $build.Output)) { throw "$profile $($build.Name) compilation failed." }
    }
    $probeNegative = Join-Path $probeDirectory 'UnrestrictedNullable.dll'
    $probeNegativeResult = Invoke-ProbeProcess $DotNetHost ($probeCommon + @('/target:library',"/out:$probeNegative",$probeProfileSource,(Join-Path $probeSources 'UnrestrictedNullable.cs'))) $probeDirectory
    $probeNegativeText = $probeNegativeResult.Stdout + $probeNegativeResult.Stderr
    Save (Join-Path $probeDirectory 'unrestricted-nullable-compilation.txt') $probeNegativeText
    if ($probeNegativeResult.ExitCode -eq 0 -or $probeNegativeText -notmatch '\berror CS0453\b' -or (Test-Path -LiteralPath $probeNegative)) {
        throw 'Unrestricted Nullable<T> did not fail with CS0453 as expected.'
    }
    if ($profile -eq 'net10') {
        Save (Join-Path $probeDirectory 'Consumer.runtimeconfig.json') '{"runtimeOptions":{"tfm":"net10.0","framework":{"name":"Microsoft.NETCore.App","version":"10.0.0"},"rollForward":"LatestMinor"}}'
        $probeExecution = Invoke-ProbeProcess $DotNetHost @('exec',$probeConsumer) $probeDirectory
    } else {
        $probeExecution = Invoke-ProbeProcess $probeFrameworkHost @('-NoLogo','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',(Join-Path $probeSources 'Invoke-Framework.ps1'),'-Assembly',$probeConsumer) $probeDirectory
    }
    Save (Join-Path $probeDirectory 'execution.txt') ($probeExecution.Stdout + $probeExecution.Stderr)
    $probeManifest.runs += [ordered]@{
        profile=$profile; exitCode=$probeExecution.ExitCode; expectedCS0453=$true; referenceAssemblies=$probeReferenceHashes
        outputs=@($probeBuilds | ForEach-Object { [ordered]@{path=$_.Output;bytes=(Get-Item -LiteralPath $_.Output).Length;sha256=Hash $_.Output} })
    }
    Save (Join-Path $probeRun 'manifest.json') ($probeManifest | ConvertTo-Json -Depth 8)
    if ($probeExecution.ExitCode -ne 0 -or $probeExecution.Stdout -notmatch 'SUMMARY scenarios=8 failures=0') {
        throw "$profile runtime mechanism proof failed. Inspect execution.txt/results.txt."
    }
    Write-Host "${profile}: 8 mechanism scenarios passed; distinct CS0453 negative confirmed."
}
foreach ($source in $probeManifest.sources) {
    if ((Hash (Join-Path $PSScriptRoot $source.name)) -ne $source.sha256) { throw 'Source changed during probe.' }
}
Write-Host "Mechanism-only artifacts: $probeRun"
