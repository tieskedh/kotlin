<#
Exploratory overlap-only measurements of the actual exported Kotlin lib1/lib2 products.
Requires PowerShell 7; no Gradle, downloads, deployment, Kotlin compilation or source mutation.
Candidate and erased share assembly identities: every sample uses a fresh CLR process.
#>
[CmdletBinding()]
param(
    [ValidateSet('net10', 'net48')][string[]]$Profiles = @('net10', 'net48'),
    [ValidateRange(1, 100000000)][int]$Iterations = 250000,
    [ValidateRange(1, 100000000)][int]$Warmup = 50000,
    [ValidateRange(2, 50)][int]$Samples = 7,
    [switch]$IncludeNested,
    [switch]$PrepareOnly,
    [ValidateNotNullOrEmpty()][string]$ArtifactDirectory = $PSScriptRoot,
    [string]$OutputDirectory,
    [string]$DotNetHost
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Run this driver with PowerShell 7 (pwsh).' }
if (@($Profiles | Select-Object -Unique).Count -ne $Profiles.Count) { throw 'Profiles must be unique.' }
$artifactRoot = [IO.Path]::GetFullPath($ArtifactDirectory)
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $artifactRoot ('measurement-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
}
$runDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $runDirectory) {
    if (@(Get-ChildItem -LiteralPath $runDirectory -Force).Count -ne 0) { throw "Output must be empty: $runDirectory" }
} else { New-Item -ItemType Directory -Path $runDirectory | Out-Null }
$utf8 = [Text.UTF8Encoding]::new($false)
function Write-Text([string]$Path, [string]$Text) { [IO.File]::WriteAllText($Path, $Text, $utf8) }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Invoke-OwnedProcess([string]$Executable, [string[]]$ProcessArguments, [string]$Directory) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.WorkingDirectory = $Directory
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $ProcessArguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($start)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(180000)) {
        $process.Kill($true)
        $process.WaitForExit()
        throw "Owned measurement process timed out: $Executable"
    }
    $result = [pscustomobject]@{ ExitCode = $process.ExitCode; Stdout = $stdout.GetAwaiter().GetResult(); Stderr = $stderr.GetAwaiter().GetResult() }
    $process.Dispose()
    return $result
}
if ([string]::IsNullOrWhiteSpace($DotNetHost)) {
    $candidates = @()
    if ($env:KOTLIN_DOTNET_ROOT) { $candidates += Join-Path $env:KOTLIN_DOTNET_ROOT 'dotnet\dotnet.exe' }
    $candidates += Join-Path $env:LOCALAPPDATA 'kotlinc-dotnet\toolchain\dotnet\dotnet.exe'
    $DotNetHost = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}
if (-not $DotNetHost) { throw 'No installed pinned .NET host; supply -DotNetHost. No provisioning is performed.' }
$DotNetHost = (Resolve-Path -LiteralPath $DotNetHost).Path
$dotNetDirectory = Split-Path -Parent $DotNetHost
$compiler = Join-Path $dotNetDirectory 'sdk\10.0.100\Roslyn\bincore\csc.dll'
$modernReferences = Join-Path $dotNetDirectory 'packs\Microsoft.NETCore.App.Ref\10.0.0\ref\net10.0'
$frameworkDirectory = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319'
$frameworkHost = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) { throw "Pinned Roslyn missing: $compiler" }
$templatePath = Join-Path $PSScriptRoot 'StorageCycleMeasurement.cs.in'
$template = [IO.File]::ReadAllText($templatePath)
$frameworkScript = Join-Path $PSScriptRoot 'Invoke-FrameworkStorageMeasurement.ps1'
$logicalSource = Join-Path $artifactRoot 'isolated-candidate-lighttree-net10\genericOwnerStorageCycle.kt'
if ((Hash $logicalSource) -ne '2b91786078f7605b16e8cc56ac47527facecbdb61827c244dc18a561c81c3a47') {
    throw 'Frozen matrix source snapshot differs.'
}
# The serialized matrix used this unchanged source; exports did not copy it per lane.
Copy-Item -LiteralPath $logicalSource -Destination (Join-Path $runDirectory 'genericOwnerStorageCycle.kt')
$caseNames = @('scalar', 'broad')
if ($IncludeNested) { $caseNames += 'nested' }
$manifest = [ordered]@{
    protocol = 1; label = 'exploratory-overlap-only'; createdUtc = [DateTime]::UtcNow.ToString('o')
    limitations = 'Unrelated Compose/apps may be active. Correct shared subset only; narrow failing cycles excluded. No Kotlin compile/startup/AOT/trimming measurement.'
    machine = [Environment]::MachineName; os = [Environment]::OSVersion.ToString(); logicalProcessors = [Environment]::ProcessorCount
    powershell = $PSVersionTable.PSVersion.ToString(); iterations = $Iterations; warmup = $Warmup; samples = $Samples
    profiles = $Profiles; scenarios = $caseNames; artifactDirectory = $artifactRoot; dotNetHost = $DotNetHost; hostSha256 = Hash $DotNetHost
    compiler = $compiler; compilerSha256 = Hash $compiler; templateSha256 = Hash $templatePath
    driverSha256 = Hash $PSCommandPath; frameworkLauncherSha256 = Hash $frameworkScript
    sharedFrozenMatrixSource = $logicalSource; sharedFrozenMatrixSourceSha256 = Hash $logicalSource
    inheritedRuntimeEnvironment = @($env:DOTNET_TieredCompilation, $env:DOTNET_TieredPGO, $env:COMPlus_TieredCompilation, $env:COMPlus_TieredPGO)
    inputs = @()
}
$jobs = @{}
foreach ($targetProfile in $Profiles) {
    $referenceFiles = if ($targetProfile -eq 'net10') {
        @(Get-ChildItem -LiteralPath $modernReferences -Filter '*.dll' | Sort-Object Name | ForEach-Object FullName)
    } else {
        if (-not (Test-Path -LiteralPath $frameworkHost -PathType Leaf)) { throw 'Framework host missing.' }
        @('mscorlib.dll', 'System.dll', 'System.Core.dll') | ForEach-Object { Join-Path $frameworkDirectory $_ }
    }
    foreach ($reference in $referenceFiles) {
        if (-not (Test-Path -LiteralPath $reference -PathType Leaf)) { throw "Reference missing: $reference" }
    }
    foreach ($epoch in @('candidate', 'erased')) {
        $inputDirectory = Join-Path $artifactRoot "isolated-$epoch-lighttree-$targetProfile"
        $jobDirectory = Join-Path $runDirectory "$epoch-$targetProfile"
        New-Item -ItemType Directory -Path $jobDirectory | Out-Null
        $inputNames = @('lib1.dll', 'lib2.dll', 'Kotlin.Runtime.dll', 'Kotlin.Stdlib.dll', 'lib1.il', 'lib2.il',
            'lib1-physical.txt', 'lib2-physical.txt', 'StorageCycle.cs', 'StorageCycle-results.txt')
        foreach ($name in $inputNames) {
            $inputPath = Join-Path $inputDirectory $name
            if (-not (Test-Path -LiteralPath $inputPath -PathType Leaf)) { throw "Frozen input missing: $inputPath" }
            $hash = Hash $inputPath
            Copy-Item -LiteralPath $inputPath -Destination (Join-Path $jobDirectory $name)
            if ((Hash (Join-Path $jobDirectory $name)) -ne $hash) { throw "Copy changed: $inputPath" }
            $manifest.inputs += [ordered]@{ epoch = $epoch; profile = $targetProfile; path = $inputPath; sha256 = $hash }
        }
        $capturedSource = [IO.File]::ReadAllText((Join-Path $jobDirectory 'StorageCycle.cs'))
        $observations = [IO.File]::ReadAllText((Join-Path $jobDirectory 'StorageCycle-results.txt'))
        foreach ($required in @('PASS make<Int> scalar later write', 'PASS make<Producer<Any>> Int widening/object read',
            'PASS C# allocated broad object box/Int later write')) {
            if (-not $observations.Contains($required)) { throw "No successful actual-artifact proof for '$required': $inputDirectory" }
        }
        if ($IncludeNested -and -not $observations.Contains('PASS makeNested<Any> Int widening/object read')) {
            throw "No successful nested proof: $inputDirectory"
        }
        $libIl = [IO.File]::ReadAllText((Join-Path $jobDirectory 'lib1.il'))
        $writerIl = [IO.File]::ReadAllText((Join-Path $jobDirectory 'lib2.il'))
        function Surface-Name([string]$BaseName, [bool]$Writer) {
            $prefix = if ($Writer) { 'writersKt\.' } else { '\.' }
            $found = @([regex]::Matches($capturedSource, $prefix + '(' + $BaseName + '(?:__KotlinErased__[0-9a-f]+)?)\(') |
                ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
            if ($found.Count -ne 1) { throw "No unique captured public $BaseName spelling: $inputDirectory" }
            $il = if ($Writer) { $writerIl } else { $libIl }
            if ($il -notmatch ('(?m)^\s*\.method public[^\r\n]*\x27' + [regex]::Escape($found[0]) + '\x27\(')) {
                throw "Captured spelling is not a public emitted MethodDef: $($found[0])"
            }
            return $found[0]
        }
        $tokens = @{
            '__EPOCH__' = $epoch; '__WRITE_SCALAR__' = Surface-Name 'writeInt' $true
            '__WRITE_INT__' = Surface-Name 'writeBroad' $true; '__WRITE_STRING__' = Surface-Name 'writeBroadString' $true
            '__PRODUCE__' = Surface-Name 'produce' $false
            '__BROAD_BOX__' = $(if ($epoch -eq 'candidate') { 'storage.cycle.Box<object>' } else { 'storage.cycle.Box' })
            '__SCALAR_BOX__' = $(if ($epoch -eq 'candidate') { 'Box<int>' } else { 'Box' })
            '__INT_VIEW__' = $(if ($epoch -eq 'candidate') { 'storage.cycle.Producer<int>' } else { 'storage.cycle.Producer' })
            '__STRING_VIEW__' = $(if ($epoch -eq 'candidate') { 'storage.cycle.Producer<string>' } else { 'storage.cycle.Producer' })
        }
        $measurementSource = $template
        foreach ($token in $tokens.Keys) { $measurementSource = $measurementSource.Replace($token, $tokens[$token]) }
        # Tokens are uppercase; valid mixed-case __KotlinErased__ member names are not tokens.
        if ($measurementSource -cmatch '__[A-Z_]+__') { throw 'Unexpanded measurement token.' }
        $sourcePath = Join-Path $jobDirectory 'StorageCycleMeasurement.cs'
        Write-Text $sourcePath $measurementSource
        $extension = if ($targetProfile -eq 'net10') { 'dll' } else { 'exe' }
        $assembly = Join-Path $jobDirectory "StorageCycleMeasurement.$extension"
        $arguments = @($compiler, '/nologo', '/noconfig', '/nostdlib+', '/deterministic+', '/optimize+', '/platform:x64', '/warnaserror+', '/target:exe', "/out:$assembly")
        foreach ($reference in $referenceFiles) { $arguments += "/reference:$reference" }
        foreach ($name in @('lib1.dll', 'lib2.dll', 'Kotlin.Runtime.dll', 'Kotlin.Stdlib.dll')) {
            $arguments += '/reference:' + (Join-Path $jobDirectory $name)
        }
        $arguments += $sourcePath
        $compilation = Invoke-OwnedProcess $DotNetHost $arguments $jobDirectory
        Write-Text (Join-Path $jobDirectory 'compilation.txt') ($compilation.Stdout + $compilation.Stderr)
        if ($compilation.ExitCode -ne 0) { throw "Ordinary C# measurement compile failed: $jobDirectory" }
        if ($targetProfile -eq 'net10') {
            Write-Text (Join-Path $jobDirectory 'StorageCycleMeasurement.runtimeconfig.json') '{"runtimeOptions":{"tfm":"net10.0","framework":{"name":"Microsoft.NETCore.App","version":"10.0.0"},"rollForward":"LatestMinor"}}'
        }
        Write-Text (Join-Path $jobDirectory 'surface.json') ($tokens | ConvertTo-Json)
        $jobs["$epoch-$targetProfile"] = [pscustomobject]@{ Directory = $jobDirectory; Assembly = $assembly }
    }
}
Write-Text (Join-Path $runDirectory 'manifest.json') ($manifest | ConvertTo-Json -Depth 8)
if ($PrepareOnly) { Write-Host "Prepared actual-artifact measurements without running: $runDirectory"; return }
$rows = [Collections.Generic.List[object]]::new()
foreach ($targetProfile in $Profiles) {
    foreach ($scenario in $caseNames) {
        for ($sample = 0; $sample -lt $Samples; $sample++) {
            $order = if (($sample % 2) -eq 0) { @('candidate', 'erased') } else { @('erased', 'candidate') }
            foreach ($epoch in $order) {
                $job = $jobs["$epoch-$targetProfile"]
                $startedUtc = [DateTime]::UtcNow.ToString('o')
                $invocation = if ($targetProfile -eq 'net10') {
                    Invoke-OwnedProcess $DotNetHost @('exec', $job.Assembly, $scenario, [string]$Iterations, [string]$Warmup) $job.Directory
                } else {
                    Invoke-OwnedProcess $frameworkHost @('-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                        '-File', $frameworkScript, '-Assembly', $job.Assembly, '-Scenario', $scenario,
                        '-Iterations', [string]$Iterations, '-Warmup', [string]$Warmup) $job.Directory
                }
                Write-Text (Join-Path $job.Directory "$scenario-$sample.txt") ($invocation.Stdout + $invocation.Stderr)
                if ($invocation.ExitCode -ne 0) { throw "Measurement correctness/runtime failure: $epoch/$targetProfile/$scenario/$sample" }
                $lines = @($invocation.Stdout -split '\r?\n' | Where-Object { $_.StartsWith('RESULT|') })
                if ($lines.Count -ne 1) { throw 'Missing/duplicate result protocol.' }
                $parts = $lines[0].Split('|')
                if ($parts.Count -ne 11 -or $parts[1] -ne $epoch -or $parts[2] -ne $scenario -or [int]$parts[3] -ne $Iterations) {
                    throw 'Mismatched result protocol.'
                }
                $milliseconds = [double]::Parse($parts[5], [Globalization.CultureInfo]::InvariantCulture)
                $allocated = if ($parts[6] -eq 'unavailable') { $null } else { [long]$parts[6] }
                $rows.Add([pscustomobject]@{
                    profile = $targetProfile; scenario = $scenario; epoch = $epoch; sample = $sample; firstEpoch = $order[0]
                    startedUtc = $startedUtc; iterations = $Iterations; checksum = [long]$parts[4]
                    milliseconds = $milliseconds; nanosecondsPerCycle = $milliseconds * 1000000.0 / $Iterations
                    allocatedBytes = $allocated; allocatedBytesPerCycle = $(if ($null -eq $allocated) { $null } else { $allocated / [double]$Iterations })
                    intCalls = [int]$parts[7]; stringCalls = [int]$parts[8]; runtime = $parts[9]; pointerBytes = [int]$parts[10]
                })
                $rows | Export-Csv -LiteralPath (Join-Path $runDirectory 'samples.csv') -NoTypeInformation
                Write-Host "$targetProfile $scenario $epoch sample=$sample ms=$milliseconds allocated=$($parts[6])"
            }
        }
    }
}
foreach ($inputRecord in $manifest.inputs) {
    if ((Hash $inputRecord.path) -ne $inputRecord.sha256) { throw "Frozen input changed during measurement: $($inputRecord.path)" }
}
$summary = foreach ($group in ($rows | Group-Object profile, scenario, epoch)) {
    $ordered = @($group.Group.nanosecondsPerCycle | Sort-Object)
    $middle = [int][Math]::Floor($ordered.Count / 2)
    $median = if (($ordered.Count % 2) -eq 0) { ($ordered[$middle - 1] + $ordered[$middle]) / 2.0 } else { $ordered[$middle] }
    $first = $group.Group[0]
    [pscustomobject]@{ profile = $first.profile; scenario = $first.scenario; epoch = $first.epoch; samples = $ordered.Count
        minNsPerCycle = $ordered[0]; medianNsPerCycle = $median; maxNsPerCycle = $ordered[-1]
        allocationBytesPerCycle = ($group.Group.allocatedBytesPerCycle | Select-Object -Unique) -join ';'
        label = 'exploratory-overlap-only' }
}
$summary | Export-Csv -LiteralPath (Join-Path $runDirectory 'summary.csv') -NoTypeInformation
Write-Text (Join-Path $runDirectory 'samples.json') ($rows | ConvertTo-Json -Depth 5)
Write-Host "Exploratory paired results: $runDirectory"
