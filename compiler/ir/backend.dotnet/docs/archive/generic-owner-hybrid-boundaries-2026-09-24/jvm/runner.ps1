[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,
    [string]$KotlinHome = 'C:\Users\thijs\IdeaProjects\kotlin\dist\kotlinc',
    [string]$JavaExecutable = 'java'
)

# Common/JVM precedent only. This does not build or modify Kotlin/.NET.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputPath) { throw 'Choose a new output directory; evidence is never overwritten.' }
$kotlinPath = [IO.Path]::GetFullPath($KotlinHome)
$compilerJar = Join-Path $kotlinPath 'lib\kotlin-compiler.jar'
$stdlib = Join-Path $kotlinPath 'lib\kotlin-stdlib.jar'
$java = (Get-Command $JavaExecutable -ErrorAction Stop).Source
$sources = @('Library.kt', 'Writers.kt', 'Consumer.kt') | ForEach-Object {
    [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $_))
}
foreach ($required in @($compilerJar, $stdlib, $java, $PSCommandPath) + $sources) {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required input unavailable: $required" }
}
New-Item -ItemType Directory -Path $outputPath | Out-Null
$hashes = [ordered]@{}
foreach ($source in $sources) {
    $name = [IO.Path]::GetFileName($source)
    Copy-Item -LiteralPath $source -Destination (Join-Path $outputPath $name)
    $hashes[$name] = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
}
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $outputPath 'runner.ps1')
$compilerInputs = [ordered]@{}
foreach ($inputFile in (Get-ChildItem -LiteralPath (Join-Path $kotlinPath 'lib') -Filter '*.jar' -File | Sort-Object Name)) {
    $compilerInputs[$inputFile.Name] = (Get-FileHash -LiteralPath $inputFile.FullName -Algorithm SHA256).Hash
}
$report = [ordered]@{
    kind = 'common-source-jvm-composition-precedent-only'
    status = 'STARTED'
    kotlinHome = $kotlinPath
    javaExecutable = $java
    javaExecutableSha256 = (Get-FileHash -LiteralPath $java -Algorithm SHA256).Hash
    compilerSha256 = (Get-FileHash -LiteralPath $compilerJar -Algorithm SHA256).Hash
    stdlibSha256 = (Get-FileHash -LiteralPath $stdlib -Algorithm SHA256).Hash
    compilerClasspathHashes = $compilerInputs
    sourceHashes = $hashes
    runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
    compilerVersion = @()
    javaVersion = @()
    artifactHashes = [ordered]@{}
    toolRuns = @()
    assertions = @()
    failure = $null
    exclusions = @('DotNet implementation', 'CLR storage or C# contract', 'hybrid ABI admission', 'performance measurement')
}

function Invoke-RecordedTool {
    param([string]$Executable, [string[]]$Arguments, [string]$Log)
    $result = & $Executable @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $lines = [string[]]@($result | ForEach-Object { "$_" })
    $lines | Out-File -LiteralPath (Join-Path $outputPath $Log) -Encoding utf8
    $report.toolRuns += [ordered]@{ executable = $Executable; arguments = $Arguments; exitCode = $exitCode; log = $Log }
    if ($exitCode -ne 0) { throw "Unexpected exit ${exitCode}: $Log" }
    return $lines
}

function Invoke-KotlinTool {
    param([string[]]$Arguments, [string]$Log)
    # Direct Java invocation preserves each semicolon-separated classpath as one argument.
    Invoke-RecordedTool $java (@('-cp', (Join-Path $kotlinPath 'lib\*'),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler') + $Arguments) $Log
}

try {
    $report.compilerVersion = @(Invoke-KotlinTool @('-version') 'compiler-version.log')
    $report.javaVersion = @(Invoke-RecordedTool $java @('-version') 'java-version.log')
    $options = @('-Werror', '-no-reflect', '-no-stdlib', '-Xrender-internal-diagnostic-names')
    $library = Join-Path $outputPath 'library.jar'
    $writers = Join-Path $outputPath 'writers.jar'
    $consumer = Join-Path $outputPath 'consumer.jar'
    Invoke-KotlinTool ($options + @('-module-name', 'composition_library', '-classpath', $stdlib,
        '-d', $library, (Join-Path $outputPath 'Library.kt'))) 'library-compile.log' | Out-Null
    $report.artifactHashes['library.jar'] = (Get-FileHash -LiteralPath $library -Algorithm SHA256).Hash
    Invoke-KotlinTool ($options + @('-module-name', 'composition_writers', '-classpath', "$library;$stdlib",
        '-d', $writers, (Join-Path $outputPath 'Writers.kt'))) 'writers-compile.log' | Out-Null
    $report.artifactHashes['writers.jar'] = (Get-FileHash -LiteralPath $writers -Algorithm SHA256).Hash
    Invoke-KotlinTool ($options + @('-module-name', 'composition_consumer', '-classpath', "$writers;$library;$stdlib",
        '-d', $consumer, (Join-Path $outputPath 'Consumer.kt'))) 'consumer-compile.log' | Out-Null
    $report.artifactHashes['consumer.jar'] = (Get-FileHash -LiteralPath $consumer -Algorithm SHA256).Hash
    $report.assertions = @(Invoke-RecordedTool $java @('-cp', "$consumer;$writers;$library;$stdlib",
        'hybrid.composition.ConsumerKt') 'consumer-run.log')
    $expected = @(
        'PASS: nested-out-replacement',
        'PASS: reference-only-in-projection',
        'PASS: open-projected-factory-and-writer',
        'PASS: nullable-factory-nested-storage',
        'PASS: nullable-substitution-idempotence',
        'PASS: nullable-base-separate-writer',
        'PASS: Common/JVM composition baseline; groups=6; separate-jars=3'
    )
    if (($report.assertions -join "`n") -cne ($expected -join "`n")) { throw 'Unexpected assertion output; inspect consumer-run.log.' }
    foreach ($source in $sources) {
        $name = [IO.Path]::GetFileName($source)
        foreach ($path in @($source, (Join-Path $outputPath $name))) {
            if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $hashes[$name]) { throw "Source changed: $path" }
        }
    }
    foreach ($name in $compilerInputs.Keys) {
        $path = Join-Path (Join-Path $kotlinPath 'lib') $name
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $compilerInputs[$name]) { throw "Compiler input changed: $path" }
    }
    foreach ($name in $report.artifactHashes.Keys) {
        $path = Join-Path $outputPath $name
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $report.artifactHashes[$name]) { throw "Compiled library changed: $path" }
    }
    $report.status = 'PASS'
} catch {
    $report.status = 'FAIL'
    $report.failure = $_.ToString()
    throw
} finally {
    $report | ConvertTo-Json -Depth 8 | Out-File -LiteralPath (Join-Path $outputPath 'verification.json') -Encoding utf8
}
Write-Output "PASS: Common/JVM composition baseline; groups=6; separate-jars=3; evidence: $outputPath"
