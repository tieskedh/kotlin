param([string]$Assembly, [string]$Scenario, [int]$Iterations, [int]$Warmup)
$ErrorActionPreference = 'Stop'
try {
    $loadedMeasurement = [Reflection.Assembly]::LoadFrom([IO.Path]::GetFullPath($Assembly))
    $invocationArguments = New-Object 'System.Object[]' 1
    $invocationArguments[0] = [string[]]@($Scenario, [string]$Iterations, [string]$Warmup)
    $measurementResult = $loadedMeasurement.EntryPoint.Invoke($null, $invocationArguments)
    exit [int]$measurementResult
} catch {
    [Console]::Error.WriteLine($_.Exception.ToString())
    exit 1
}
