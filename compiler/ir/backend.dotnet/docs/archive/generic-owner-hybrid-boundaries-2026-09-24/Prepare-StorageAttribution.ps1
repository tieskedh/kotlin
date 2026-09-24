<#
Prepare three matched diagnostic variants of the already audited actual-product probe.
Does not compile or execute anything. The root task runs the printed commands serially.
All generated files live under the new OutputDirectory; original evidence is read-only.
#>
[CmdletBinding()]
param(
    [string]$ProtocolDirectory = 'D:\CodexTemp\generic-owner-storage-cycle-20260924',
    [string]$ArtifactDirectory = 'D:\CodexTemp\generic-owner-storage-cycle-20260924',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'attribution-variants')
)
$ErrorActionPreference = 'Stop'
$protocolRoot = [IO.Path]::GetFullPath($ProtocolDirectory)
$artifactRoot = [IO.Path]::GetFullPath($ArtifactDirectory)
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputRoot) {
    if (@(Get-ChildItem -LiteralPath $outputRoot -Force).Count -ne 0) { throw "Output must be empty: $outputRoot" }
} else { New-Item -ItemType Directory -Path $outputRoot | Out-Null }
$templatePath = Join-Path $protocolRoot 'StorageCycleMeasurement.cs.in'
$template = [IO.File]::ReadAllText($templatePath)
$utf8 = [Text.UTF8Encoding]::new($false)
function Replace-Once([string]$Text, [string]$Old, [string]$New) {
    if ([regex]::Matches($Text, [regex]::Escape($Old)).Count -ne 1) { throw "Expected one exact template anchor: $Old" }
    return $Text.Replace($Old, $New)
}
$stateAnchor = '        internal object LastBox;'
$stateFields = @'
        internal object LastBox;
        internal readonly IntView IntInterface;
        internal readonly StringView StringInterface;
        internal State()
        {
            IntInterface = Integers;
            StringInterface = Strings;
        }
'@
# Three reads, one allocation, two writes, 2N/N producer effects and checksum 151
# remain identical. The identity guards are deliberately present in ALL variants.
$loopAnchor = @'
            checksum += (int)((IntView)alias.read()).__PRODUCE__();
            writersKt.__WRITE_STRING__(box, state.Strings);
            checksum += ((string)((StringView)alias.read()).__PRODUCE__()).Length;
            writersKt.__WRITE_INT__(box, state.Integers);
            checksum += (int)((IntView)alias.read()).__PRODUCE__();
'@
$normalTemplate = $template.Replace("`r`n", "`n")
$normalState = $stateFields.Replace("`r`n", "`n")
$normalAnchor = $loopAnchor.Replace("`r`n", "`n")
foreach ($variant in @('checked-interface', 'cached-interface', 'concrete-receiver')) {
    $initial = switch ($variant) {
        'checked-interface' { '(int)((IntView)initial).__PRODUCE__()' }
        'cached-interface' { '(int)state.IntInterface.__PRODUCE__()' }
        'concrete-receiver' { 'state.Integers.produce()' }
    }
    $reference = switch ($variant) {
        'checked-interface' { '((string)((StringView)reference).__PRODUCE__()).Length' }
        'cached-interface' { '((string)state.StringInterface.__PRODUCE__()).Length' }
        'concrete-receiver' { 'state.Strings.produce().Length' }
    }
    $replacement = switch ($variant) {
        'checked-interface' { '(int)((IntView)replacement).__PRODUCE__()' }
        'cached-interface' { '(int)state.IntInterface.__PRODUCE__()' }
        'concrete-receiver' { 'state.Integers.produce()' }
    }
    $loop = @"
            object initial = alias.read();
            Require(Object.ReferenceEquals(initial, state.Integers), "initial identity");
            checksum += $initial;
            writersKt.__WRITE_STRING__(box, state.Strings);
            object reference = alias.read();
            Require(Object.ReferenceEquals(reference, state.Strings), "reference identity");
            checksum += $reference;
            writersKt.__WRITE_INT__(box, state.Integers);
            object replacement = alias.read();
            Require(Object.ReferenceEquals(replacement, state.Integers), "replacement identity");
            checksum += $replacement;
"@
    $source = Replace-Once $normalTemplate $stateAnchor $normalState
    $source = Replace-Once $source $normalAnchor $loop.Replace("`r`n", "`n")
    $source = $source.Replace('// No reflection, assertions, timing or allocation instrumentation in these loops.',
        '// Matched diagnostic: identical identity guards in every variant; no timing/reflection in the loop.')
    $source = "// Diagnostic variant: $variant. Compare only against these matched variants.`n" + $source
    $destination = Join-Path $outputRoot $variant
    New-Item -ItemType Directory -Path $destination | Out-Null
    foreach ($name in @('Measure-StorageCycle.ps1', 'Invoke-FrameworkStorageMeasurement.ps1')) {
        Copy-Item -LiteralPath (Join-Path $protocolRoot $name) -Destination (Join-Path $destination $name)
    }
    [IO.File]::WriteAllText((Join-Path $destination 'StorageCycleMeasurement.cs.in'), $source, $utf8)
    $manifest = [ordered]@{
        variant = $variant; sourceProtocolDirectory = $protocolRoot; artifactDirectory = $artifactRoot
        baseTemplateSha256 = (Get-FileHash -LiteralPath $templatePath -Algorithm SHA256).Hash
        preparedTemplateSha256 = (Get-FileHash -LiteralPath (Join-Path $destination 'StorageCycleMeasurement.cs.in') -Algorithm SHA256).Hash
        method = 'Identical guarded storage/effect work; checked interface, cached interface or concrete call expression only.'
        limits = 'Diagnostic, not storage policy. JIT knowledge/inlining may change; no native instruction attribution.'
    }
    [IO.File]::WriteAllText((Join-Path $destination 'attribution.json'), ($manifest | ConvertTo-Json), $utf8)
    Write-Host "Prepared $variant. Root may run serially:"
    Write-Host "pwsh -NoProfile -File `"$(Join-Path $destination 'Measure-StorageCycle.ps1')`" -ArtifactDirectory `"$artifactRoot`" -Profiles net48 -Iterations 10000000 -Warmup 1000000 -Samples 7 -OutputDirectory `"$(Join-Path $destination 'results-net48')`""
}
