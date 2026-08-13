<#
.SYNOPSIS
    Builds and measures the record-driven generic-owner architecture probe.

.DESCRIPTION
    Regenerates one exact net10 hostile producer/consumer bundle through the
    normal compiler test, verifies every recorded SHA-256 fingerprint, then
    publishes and executes the same measurement workload under the selected
    deployment modes. NativeAOT is opt-in and a failed native link is fatal;
    analyzer, ReadyToRun, and trimming success never count as NativeAOT proof.

.EXAMPLE
    pwsh compiler/ir/backend.dotnet/tools/measure-generic-owner.ps1

.EXAMPLE
    pwsh compiler/ir/backend.dotnet/tools/measure-generic-owner.ps1 `
        -Modes native-aot -ExistingBundle C:\path\to\bundle

.EXAMPLE
    pwsh compiler/ir/backend.dotnet/tools/measure-generic-owner.ps1 `
        -Modes native-aot -ExistingBundle C:\path\to\bundle `
        -NativeLinker C:\path\to\link.exe `
        -NativeLibraryDirectories C:\path\to\msvc-lib,C:\path\to\sdk-um,C:\path\to\sdk-ucrt
#>
[CmdletBinding()]
param(
    [ValidateSet('jit', 'ready-to-run', 'trimmed', 'native-aot')]
    [string[]]$Modes = @('jit', 'ready-to-run', 'trimmed'),
    [ValidateRange(1, 1000000000)]
    [int]$Iterations = 50000,
    [ValidateRange(1, 100)]
    [int]$StartupRuns = 5,
    [ValidateRange(1, 100)]
    [int]$ThroughputRuns = 3,
    [string]$OutputDirectory,
    [string]$ExistingBundle,
    [string]$NativeLinker,
    [string[]]$NativeLibraryDirectories,
    [switch]$PrepareOnly
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
if (-not $PrepareOnly -and $Modes.Count -eq 0) {
    throw 'At least one measurement mode is required unless -PrepareOnly is selected'
}
if (@($Modes | Select-Object -Unique).Count -ne $Modes.Count) {
    throw 'Measurement modes must be unique'
}
if ([string]::IsNullOrWhiteSpace($NativeLinker) -ne ($null -eq $NativeLibraryDirectories)) {
    throw 'NativeLinker and NativeLibraryDirectories must be supplied together'
}
if (-not [string]::IsNullOrWhiteSpace($NativeLinker) -and 'native-aot' -notin $Modes) {
    throw 'An explicit native toolchain is only valid when native-aot mode is selected'
}

$backendDirectory = Split-Path -Parent $PSScriptRoot
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $OutputDirectory = Join-Path $backendDirectory "build\generic-owner-measurement\$timestamp"
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

function Read-MeasurementManifest([string]$Path) {
    $values = [ordered]@{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw "Malformed measurement manifest line: $line" }
        $key = $line.Substring(0, $separator)
        if ($values.Contains($key)) { throw "Duplicate measurement manifest key: $key" }
        $values[$key] = $line.Substring($separator + 1)
    }
    return $values
}

function Assert-Hash([string]$Path, [string]$Expected, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "The measurement bundle lacks $Label at $Path"
    }
    $actual = Get-Sha256 $Path
    if ($actual -ne $Expected) {
        throw "The measurement bundle has stale ${Label}: expected $Expected, found $actual"
    }
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
        throw 'The explicit NativeAOT toolchain requires three unique MSVC, SDK um, and SDK ucrt library directories'
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
            throw "The explicit NativeAOT toolchain lacks required library $library"
        }
    }
    $linkerSignature = Get-AuthenticodeSignature -LiteralPath $NativeLinker
    if ($linkerSignature.Status -ne [Management.Automation.SignatureStatus]::Valid -or
            $linkerSignature.SignerCertificate.Subject -notmatch '(^|, )O=Microsoft Corporation(,|$)') {
        throw "The explicit NativeAOT linker is not validly signed by Microsoft: $NativeLinker"
    }
    $linkerVersion = (Get-Item -LiteralPath $NativeLinker).VersionInfo.FileVersion
    if ([string]::IsNullOrWhiteSpace($linkerVersion)) {
        throw "The explicit NativeAOT linker has no file version: $NativeLinker"
    }
    $env:PATH = "$(Split-Path -Parent $NativeLinker)$([IO.Path]::PathSeparator)$env:PATH"
    $env:LIB = $resolvedNativeLibraryDirectories -join [IO.Path]::PathSeparator
    $env:IlcUseEnvironmentalTools = 'true'
    $nativeToolchainInfo = [ordered]@{
        discovery = 'explicit-msvc'
        linker = $NativeLinker
        linkerVersion = $linkerVersion
        linkerSha256 = Get-Sha256 $NativeLinker
        linkerSigner = $linkerSignature.SignerCertificate.Subject
        libraryDirectories = $resolvedNativeLibraryDirectories
        requiredLibraries = $requiredNativeLibraries
    }
} elseif ('native-aot' -in $Modes) {
    $nativeToolchainInfo = [ordered]@{
        discovery = 'dotnet-sdk-auto-discovery'
    }
}

if ([string]::IsNullOrWhiteSpace($ExistingBundle)) {
    $bundleDirectory = Join-Path $runDirectory 'bundle'
    New-Item -ItemType Directory -Force -Path $bundleDirectory | Out-Null
    if (@(Get-ChildItem -LiteralPath $bundleDirectory -Force).Count -ne 0) {
        throw "The new measurement bundle directory must be empty: $bundleDirectory"
    }
    $gradle = Join-Path $repositoryRoot 'gradlew.bat'
    $testFilter =
        'org.jetbrains.kotlin.test.runners.codegen.FirPsiDotNetBoxTestGenerated$Box.' +
        'testGenericOwnerHardestModelOracleSeparateCompilation'
    $measurementProperty = "-Pkotlin.dotnet.genericOwnerMeasurementDir=$bundleDirectory"
    & $gradle --no-daemon $measurementProperty -q `
        :compiler:fir:fir2ir:dotNetTest --rerun --tests $testFilter
    if ($LASTEXITCODE -ne 0) {
        throw "The generic-owner measurement producer test failed with exit code $LASTEXITCODE"
    }
} else {
    $bundleDirectory = [IO.Path]::GetFullPath($ExistingBundle)
}

$manifestPath = Join-Path $bundleDirectory 'generic-owner-measurement.properties'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "No generic-owner measurement manifest was produced at $manifestPath"
}
$manifest = Read-MeasurementManifest $manifestPath
$requiredManifestKeys = @(
    'schema', 'workloadVersion', 'sdkVersion', 'targetProfile', 'logicalConstructionKey',
    'producerSha256', 'sourceSha256', 'projectSha256', 'globalJsonSha256',
    'physicalFamilyArtifactSha256'
)
if (@(Compare-Object @($manifest.Keys) $requiredManifestKeys).Count -ne 0) {
    throw "The generic-owner measurement manifest has an unexpected shape: $($manifest | ConvertTo-Json -Compress)"
}
if ($manifest.schema -ne '1' -or $manifest.workloadVersion -ne '2' -or
        $manifest.targetProfile -ne 'NET10_0' -or $manifest.sdkVersion -ne '10.0.100') {
    throw "Unsupported generic-owner measurement manifest: $($manifest | ConvertTo-Json -Compress)"
}

$sourcePath = Join-Path $bundleDirectory 'RecordedFamilyConsumer.cs'
$producerPath = Join-Path $bundleDirectory 'SnapshotProducer.dll'
$projectPath = Join-Path $bundleDirectory 'RecordedFamilyMeasurement.csproj'
$globalJsonPath = Join-Path $bundleDirectory 'global.json'
$artifactPath = Join-Path $bundleDirectory 'SnapshotProducer.generic-owner-families'

function Assert-ExactBundle {
    $expectedNames = @(
        'RecordedFamilyConsumer.cs', 'SnapshotProducer.dll', 'RecordedFamilyMeasurement.csproj',
        'global.json', 'SnapshotProducer.generic-owner-families', 'generic-owner-measurement.properties'
    )
    $entries = @(Get-ChildItem -LiteralPath $bundleDirectory -Force)
    if (@($entries | Where-Object { $_.PSIsContainer }).Count -ne 0 -or
            @(Compare-Object @($entries.Name) $expectedNames).Count -ne 0) {
        throw "The measurement bundle must contain exactly the six recorded files: $bundleDirectory"
    }
}

Assert-ExactBundle
Assert-Hash $sourcePath $manifest.sourceSha256 'consumer source'
Assert-Hash $producerPath $manifest.producerSha256 'producer assembly'
Assert-Hash $projectPath $manifest.projectSha256 'measurement project'
Assert-Hash $globalJsonPath $manifest.globalJsonSha256 'SDK selection'
Assert-Hash $artifactPath $manifest.physicalFamilyArtifactSha256 'physical-family artifact'
if ((Get-Content -LiteralPath $sourcePath -Raw) -match 'MakeGenericType|Activator\.CreateInstance') {
    throw 'The measurement source contains an unbounded dynamic generic construction path'
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
if ($LASTEXITCODE -ne 0 -or $sdkVersion -ne $manifest.sdkVersion) {
    throw "The measurement requires SDK $($manifest.sdkVersion), found '$sdkVersion' at $dotnet"
}

$repositoryHead = (& git -C $repositoryRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the repository HEAD for measurement provenance' }
$repositoryStatus = @(& git -C $repositoryRoot status --porcelain --untracked-files=no)
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve repository status for measurement provenance' }
$environmentInfo = [ordered]@{
    os = [Environment]::OSVersion.VersionString
    architecture = [Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString()
    processorCount = [Environment]::ProcessorCount
    dotnet = $dotnet
    sdkVersion = $sdkVersion
    repositoryHead = $repositoryHead
    repositoryDirty = $repositoryStatus.Count -gt 0
    measurementToolSha256 = Get-Sha256 $PSCommandPath
    nativeToolchain = $nativeToolchainInfo
}

$bundleInfo = [ordered]@{
    directory = $bundleDirectory
    logicalConstructionKey = $manifest.logicalConstructionKey
    producerSha256 = $manifest.producerSha256
    sourceSha256 = $manifest.sourceSha256
    projectSha256 = $manifest.projectSha256
    globalJsonSha256 = $manifest.globalJsonSha256
    physicalFamilyArtifactSha256 = $manifest.physicalFamilyArtifactSha256
}

if ($PrepareOnly) {
    $prepared = [ordered]@{
        schema = 1
        workloadVersion = [int]$manifest.workloadVersion
        preparedAtUtc = [DateTime]::UtcNow.ToString('O')
        environment = $environmentInfo
        bundle = $bundleInfo
        nativeAotProven = $false
        measurements = @()
    }
    $preparedPath = Join-Path $runDirectory 'results.json'
    $prepared | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $preparedPath -Encoding utf8NoBOM
    Write-Host "Prepared verified generic-owner measurement bundle: $bundleDirectory"
    Write-Host "Result: $preparedPath"
    exit 0
}

function Invoke-MeasuredProcess(
    [string]$FilePath,
    [string[]]$Arguments,
    [string]$WorkingDirectory,
    [int]$ExpectedIterations
) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.RedirectStandardInput = $true
    foreach ($argument in $Arguments) { $startInfo.ArgumentList.Add($argument) }
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
            if ($line.StartsWith('GENERIC_OWNER_MEASUREMENT|')) {
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
            # A failed process may exit before the release is written.
        }
    }
    $remainingStdout = $process.StandardOutput.ReadToEnd()
    if (-not [string]::IsNullOrEmpty($remainingStdout)) {
        foreach ($line in $remainingStdout -split "`r?`n") {
            if (-not [string]::IsNullOrEmpty($line)) { $stdoutLines.Add($line) }
        }
    }
    $process.WaitForExit()
    $stdout = ($stdoutLines -join [Environment]::NewLine).Trim()
    $stderr = $stderrTask.GetAwaiter().GetResult().Trim()
    if ($process.ExitCode -ne 0) {
        throw "Measured process failed with $($process.ExitCode): $stderr`n$stdout"
    }
    if ($peakWorkingSet -le 0) {
        throw 'Measured process did not expose a positive peak working-set counter'
    }
    if ($null -eq $measurementLine) {
        throw "Measured process did not report the generic-owner protocol: $stdout"
    }
    $fields = [ordered]@{}
    foreach ($field in $measurementLine.Split('|') | Select-Object -Skip 1) {
        $separator = $field.IndexOf('=')
        if ($separator -le 0) { throw "Malformed measurement field: $field" }
        $name = $field.Substring(0, $separator)
        if ($fields.Contains($name)) { throw "Duplicate measurement field: $name" }
        $fields[$name] = $field.Substring($separator + 1)
    }
    $requiredFields = @(
        'workloadVersion', 'iterations', 'checksum', 'elapsedTicks', 'frequency', 'allocatedBytes'
    )
    if (@(Compare-Object @($fields.Keys) $requiredFields).Count -ne 0) {
        throw "Measured process reported an unexpected protocol shape: $measurementLine"
    }
    if ($fields.workloadVersion -ne $manifest.workloadVersion) {
        throw "Measured process reported workload $($fields.workloadVersion), expected $($manifest.workloadVersion)"
    }
    if ([int]$fields.iterations -ne $ExpectedIterations) {
        throw "Measured process reported $($fields.iterations) iterations, expected $ExpectedIterations"
    }
    return [ordered]@{
        wallMilliseconds = $stopwatch.Elapsed.TotalMilliseconds
        peakWorkingSetBytes = $peakWorkingSet
        iterations = [int]$fields.iterations
        checksum = [int]$fields.checksum
        elapsedTicks = [long]$fields.elapsedTicks
        frequency = [long]$fields.frequency
        workloadMilliseconds = 1000.0 * [long]$fields.elapsedTicks / [long]$fields.frequency
        allocatedBytes = [long]$fields.allocatedBytes
        stdout = $stdout
        stderr = $stderr
    }
}

function Get-Median([double[]]$Values) {
    $ordered = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($ordered.Count / 2)
    if (($ordered.Count % 2) -eq 1) { return $ordered[$middle] }
    return ($ordered[$middle - 1] + $ordered[$middle]) / 2.0
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

$measurements = @()
$expectedChecksum = $null
foreach ($mode in $Modes) {
    $definition = $modeDefinitions[$mode]
    $publishDirectory = Join-Path $runDirectory "publish\$mode"
    New-Item -ItemType Directory -Force -Path $publishDirectory | Out-Null
    $intermediateDirectory = Join-Path $runDirectory "obj\$mode"
    $binaryDirectory = Join-Path $runDirectory "bin\$mode"
    $publishArguments = @(
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
    foreach ($property in $definition.properties) { $publishArguments += "-p:$property" }
    $publishStopwatch = [Diagnostics.Stopwatch]::StartNew()
    Push-Location $bundleDirectory
    try {
        $publishOutput = (& $dotnet @publishArguments 2>&1 | Out-String)
        $publishExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    $publishStopwatch.Stop()
    $publishLog = Join-Path $publishDirectory 'publish.log'
    $publishOutput | Set-Content -LiteralPath $publishLog -Encoding utf8NoBOM
    if ($publishExitCode -ne 0) {
        throw "Publishing mode '$mode' failed with $publishExitCode. See $publishLog`n$publishOutput"
    }

    $application = Join-Path $publishDirectory 'RecordedFamilyMeasurement.exe'
    $applicationDll = Join-Path $publishDirectory 'RecordedFamilyMeasurement.dll'
    if ($definition.selfContained) {
        if (-not (Test-Path -LiteralPath $application -PathType Leaf)) {
            throw "Mode '$mode' did not publish its self-contained executable"
        }
        $runFile = $application
        $runPrefix = @()
    } else {
        if (-not (Test-Path -LiteralPath $applicationDll -PathType Leaf)) {
            throw "Mode '$mode' did not publish its framework-dependent assembly"
        }
        $runFile = $dotnet
        $runPrefix = @('exec', $applicationDll)
    }

    $startup = @()
    for ($index = 0; $index -lt $StartupRuns; $index++) {
        $startup += Invoke-MeasuredProcess `
            $runFile ($runPrefix + @('--measurement', '0', '--hold-for-peak-working-set')) `
            $publishDirectory 0
    }
    $throughput = @()
    for ($index = 0; $index -lt $ThroughputRuns; $index++) {
        $throughput += Invoke-MeasuredProcess `
            $runFile ($runPrefix + @(
                '--measurement', $Iterations.ToString(), '--hold-for-peak-working-set'
            )) $publishDirectory $Iterations
    }
    $checksums = @($throughput | ForEach-Object { $_.checksum } | Select-Object -Unique)
    if ($checksums.Count -ne 1) { throw "Mode '$mode' produced unstable workload checksums" }
    if ($null -eq $expectedChecksum) { $expectedChecksum = $checksums[0] }
    if ($checksums[0] -ne $expectedChecksum) {
        throw "Mode '$mode' disagrees with the cross-mode workload checksum"
    }
    $publishedFiles = @(Get-ChildItem -LiteralPath $publishDirectory -File -Recurse |
        Where-Object { $_.FullName -ne $publishLog })
    $measurements += [ordered]@{
        mode = $mode
        nativeAotExecuted = $mode -eq 'native-aot'
        publishMilliseconds = $publishStopwatch.Elapsed.TotalMilliseconds
        publishedFileCount = $publishedFiles.Count
        publishedBytes = [long](($publishedFiles | Measure-Object Length -Sum).Sum)
        startupMedianMilliseconds = Get-Median @($startup | ForEach-Object { $_.wallMilliseconds })
        startupRuns = $startup
        throughputMedianMilliseconds = Get-Median @($throughput | ForEach-Object { $_.workloadMilliseconds })
        allocationMedianBytes = Get-Median @($throughput | ForEach-Object { [double]$_.allocatedBytes })
        peakWorkingSetMedianBytes = Get-Median @($throughput | ForEach-Object { [double]$_.peakWorkingSetBytes })
        throughputRuns = $throughput
    }
}

Assert-ExactBundle
Assert-Hash $sourcePath $manifest.sourceSha256 'consumer source after publication'
Assert-Hash $producerPath $manifest.producerSha256 'producer assembly after publication'
Assert-Hash $projectPath $manifest.projectSha256 'measurement project after publication'
Assert-Hash $globalJsonPath $manifest.globalJsonSha256 'SDK selection after publication'
Assert-Hash $artifactPath $manifest.physicalFamilyArtifactSha256 'physical-family artifact after publication'

$result = [ordered]@{
    schema = 1
    workloadVersion = [int]$manifest.workloadVersion
    measuredAtUtc = [DateTime]::UtcNow.ToString('O')
    environment = $environmentInfo
    bundle = $bundleInfo
    nativeAotProven = @($measurements | Where-Object { $_.nativeAotExecuted }).Count -gt 0
    measurements = $measurements
}
$resultPath = Join-Path $runDirectory 'results.json'
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $resultPath -Encoding utf8NoBOM
Write-Host "Generic-owner measurements completed: $($Modes -join ', ')"
Write-Host "Result: $resultPath"
