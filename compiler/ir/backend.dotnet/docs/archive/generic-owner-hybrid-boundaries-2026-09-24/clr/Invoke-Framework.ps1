param([Parameter(Mandatory=$true)][string]$Assembly)
$ErrorActionPreference = 'Stop'
try {
    $probeAssembly = [Reflection.Assembly]::LoadFrom([IO.Path]::GetFullPath($Assembly))
    $probeExit = $probeAssembly.EntryPoint.Invoke($null, $null)
    exit [int]$probeExit
} catch {
    [Console]::Error.WriteLine($_.Exception.ToString())
    exit 1
}
