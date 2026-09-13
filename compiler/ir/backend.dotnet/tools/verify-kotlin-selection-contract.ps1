[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,
    [string]$KotlinHome = (Join-Path $PSScriptRoot '..\..\..\..\dist\kotlinc'),
    [string]$JavaExecutable = 'java'
)

# Common-source/JVM precedent only. This neither tests nor modifies .NET codegen.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputPath) { throw 'Choose a new output directory; evidence is never overwritten.' }
$kotlinPath = [IO.Path]::GetFullPath($KotlinHome)
$compilerJar = Join-Path $kotlinPath 'lib\kotlin-compiler.jar'
$stdlib = Join-Path $kotlinPath 'lib\kotlin-stdlib.jar'
$java = (Get-Command $JavaExecutable -ErrorAction Stop).Source
$sources = @('Library.kt', 'Consumer.kt', 'Inconsistent.kt') | ForEach-Object {
    [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "fixtures\selection-contract\$_"))
}
foreach ($required in @($compilerJar, $stdlib, $java) + $sources) {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required input unavailable: $required" }
}
New-Item -ItemType Directory -Path $outputPath | Out-Null
$hashes = [ordered]@{}
foreach ($source in $sources) {
    $name = [IO.Path]::GetFileName($source)
    Copy-Item -LiteralPath $source -Destination (Join-Path $outputPath $name)
    $hashes[$name] = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
}

function Invoke-ContractTool {
    param([string]$Executable, [string[]]$Arguments, [string]$Log, [int]$ExpectedExit = 0)
    $result = & $Executable @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $result | Out-File -LiteralPath (Join-Path $outputPath $Log) -Encoding utf8
    if ($exitCode -ne $ExpectedExit) { throw "Unexpected exit $exitCode (expected $ExpectedExit): $Log" }
    return [string[]]$result
}

function Invoke-KotlinTool {
    param([string[]]$Arguments, [string]$Log, [int]$ExpectedExit = 0)
    # Invoke Java directly: the batch wrapper reparses semicolon-separated
    # classpaths. Keep each caller argument one native argument end to end.
    Invoke-ContractTool $java (@('-cp', (Join-Path $kotlinPath 'lib\*'),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler') + $Arguments) $Log $ExpectedExit
}

$compilerVersion = Invoke-KotlinTool @('-version') 'compiler-version.log'
$javaVersion = Invoke-ContractTool $java @('-version') 'java-version.log'
$compilerHash = (Get-FileHash -LiteralPath $compilerJar -Algorithm SHA256).Hash
$stdlibHash = (Get-FileHash -LiteralPath $stdlib -Algorithm SHA256).Hash
$options = @('-no-reflect', '-no-stdlib', '-Xrender-internal-diagnostic-names')
$library = Join-Path $outputPath 'library.jar'
$consumer = Join-Path $outputPath 'consumer.jar'
Invoke-KotlinTool ($options + @('-Werror', '-classpath', $stdlib, '-d', $library,
    (Join-Path $outputPath 'Library.kt'))) 'library-compile.log' | Out-Null
$libraryHash = (Get-FileHash -LiteralPath $library -Algorithm SHA256).Hash
Invoke-KotlinTool ($options + @('-Werror', '-classpath', "$library;$stdlib", '-d', $consumer,
    (Join-Path $outputPath 'Consumer.kt'))) 'consumer-compile.log' | Out-Null
$result = Invoke-ContractTool $java @('-cp', "$consumer;$library;$stdlib", 'selection.contract.ConsumerKt') 'consumer-run.log'
if (@($result | Where-Object { $_ -like 'PASS: coherent Kotlin selection contract;*' }).Count -ne 1) {
    throw 'Missing positive contract assertions'
}
$negative = Invoke-KotlinTool ($options + @('-classpath', "$library;$stdlib", '-d',
    (Join-Path $outputPath 'inconsistent.jar'), (Join-Path $outputPath 'Inconsistent.kt'))) 'inconsistent-compile.log' 1
if (($negative -join "`n") -notmatch '\[INCONSISTENT_TYPE_PARAMETER_VALUES\]') { throw 'Missing precise inconsistent-supertype diagnostic' }
if (Test-Path -LiteralPath (Join-Path $outputPath 'inconsistent.jar')) { throw 'Rejected class was emitted' }
foreach ($source in $sources) {
    $name = [IO.Path]::GetFileName($source)
    foreach ($path in @($source, (Join-Path $outputPath $name))) {
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $hashes[$name]) { throw "Source changed: $path" }
    }
}
foreach ($entry in @(@{Path=$compilerJar; Hash=$compilerHash}, @{Path=$stdlib; Hash=$stdlibHash}, @{Path=$library; Hash=$libraryHash})) {
    if ((Get-FileHash -LiteralPath $entry.Path -Algorithm SHA256).Hash -ne $entry.Hash) { throw "Input changed: $($entry.Path)" }
}
[ordered]@{
    kind = 'common-source-jvm-precedent-only'
    compilerVersion = $compilerVersion
    compilerSha256 = $compilerHash
    javaVersion = $javaVersion
    stdlibSha256 = $stdlibHash
    sourceHashes = $hashes
    separateLibrarySha256 = $libraryHash
    consumerSha256 = (Get-FileHash -LiteralPath $consumer -Algorithm SHA256).Hash
    assertions = $result
    rejection = $negative
    exclusions = @('DotNet implementation', 'dual foreign receiver semantics', 'new storage ABI', 'BK-1 incompatibility tests')
} | ConvertTo-Json -Depth 5 | Out-File -LiteralPath (Join-Path $outputPath 'verification.json') -Encoding utf8
Write-Output "PASS: coherent Kotlin selection contract and inconsistent-supertype negative; evidence: $outputPath"
