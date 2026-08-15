[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [string]$ExistingCorpus,
    [ValidateSet('hostile', 'array-copy', 'octo-tree')]
    [string]$Corpus = 'hostile',
    [ValidateSet('net10', 'net48')]
    [string[]]$Profiles = @('net10', 'net48')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$backendDirectory = Split-Path -Parent $PSScriptRoot
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $backendDirectory '..\..\..'))
$routeFileName = 'generic-owner-call-routes.tsv'
$countsFileName = 'generic-owner-call-route-counts.tsv'
$traceFileName = 'generic-owner-call-route-trace.properties'
$expectedFiles = @($countsFileName, $routeFileName, $traceFileName) | Sort-Object
$corpusDefinition = if ($Corpus -eq 'hostile') {
    $siteIndices = @(0, 1, 2, 3, 4, 5, 6, 7) + @(14..41) + @(44, 46, 47, 48)
    $siteCounts = @{}
    foreach ($siteIndex in $siteIndices) {
        $siteCounts[$siteIndex] =
            if ($siteIndex -eq 2) { 0L } elseif ($siteIndex -eq 3) { 2L } else { 1L }
    }
    [pscustomobject]@{
        TestMethod = 'testGenericOwnerHardestModelOracleSeparateCompilation'
        SiteIndices = $siteIndices
        SiteCounts = $siteCounts
        StaticRequirementCounts = @{
            PRODUCER_ERASED_OWNER = 24
            EXACT_TYPED_ENTRY = 11
            SEMANTIC_CAPABILITY = 4
            MISSING_CAPABILITY = 1
        }
        DynamicRequirementCounts = @{
            PRODUCER_ERASED_OWNER = 24L
            EXACT_TYPED_ENTRY = 11L
            SEMANTIC_CAPABILITY = 4L
            MISSING_CAPABILITY = 1L
        }
        AllEventCount = 49L
        ProducerEventCount = 40L
        UnrelatedEventCount = 9L
    }
} elseif ($Corpus -eq 'array-copy') {
    [pscustomobject]@{
        TestMethod = 'testGenericOwnerRepresentativeArrayCopy'
        SiteIndices = @(0..11) + @(13..16)
        SiteCounts = @{
            0 = 512L
            1 = 512L
            2 = 512L
            3 = 9L
            4 = 9L
            5 = 9L
            6 = 9L
            7 = 511L
            8 = 511L
            9 = 511L
            10 = 511L
            11 = 512L
            13 = 512L
            14 = 512L
            15 = 512L
            16 = 0L
        }
        StaticRequirementCounts = @{ EXACT_TYPED_ENTRY = 16 }
        DynamicRequirementCounts = @{ EXACT_TYPED_ENTRY = 5664L }
        AllEventCount = 6176L
        ProducerEventCount = 5664L
        UnrelatedEventCount = 512L
    }
} else {
    [pscustomobject]@{
        TestMethod = 'testGenericOwnerRepresentativeOctoTree'
        SiteIndices = @(
            0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
            19, 21, 22, 24, 26, 27, 29, 30, 31, 32, 34, 35, 36, 38, 40, 41, 42
        )
        SiteCounts = @{
            0 = 1L
            1 = 512L
            2 = 512L
            3 = 1L
            4 = 512L
            5 = 512L
            6 = 512L
            8 = 1024L
            9 = 512L
            10 = 1L
            11 = 512L
            12 = 512L
            13 = 512L
            14 = 0L
            15 = 512L
            16 = 1L
            17 = 64L
            19 = 0L
            21 = 96L
            22 = 88L
            24 = 1024L
            26 = 64L
            27 = 64L
            29 = 8L
            30 = 448L
            31 = 0L
            32 = 0L
            34 = 0L
            35 = 0L
            36 = 512L
            38 = 512L
            40 = 0L
            41 = 0L
            42 = 9L
        }
        StaticRequirementCounts = @{
            EXACT_TYPED_ENTRY = 25
            SEMANTIC_CAPABILITY = 9
        }
        DynamicRequirementCounts = @{
            EXACT_TYPED_ENTRY = 5941L
            SEMANTIC_CAPABILITY = 3096L
        }
        AllEventCount = 11765L
        ProducerEventCount = 9037L
        UnrelatedEventCount = 2728L
    }
}
$expectedSiteIndices = $corpusDefinition.SiteIndices
$expectedSiteCounts = $corpusDefinition.SiteCounts
$expectedRequirementCounts = $corpusDefinition.StaticRequirementCounts
$expectedDynamicRequirementCounts = $corpusDefinition.DynamicRequirementCounts

if (-not [string]::IsNullOrWhiteSpace($OutputDirectory) -and
        -not [string]::IsNullOrWhiteSpace($ExistingCorpus)) {
    throw 'Specify either OutputDirectory or ExistingCorpus, not both'
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Read-CanonicalProperties([string]$Path) {
    $expectedKeys = @(
        'schema',
        'targetProfile',
        'counterProtocol',
        'routeManifestSha256',
        'countsSha256',
        'instrumentedAssemblySha256',
        'allEventCount',
        'producerEventCount',
        'unrelatedEventCount'
    )
    $lines = @(Get-Content -LiteralPath $Path)
    if ($lines.Count -ne $expectedKeys.Count) {
        throw "The trace manifest is not canonical: $Path"
    }
    $properties = [ordered]@{}
    for ($index = 0; $index -lt $lines.Count; $index++) {
        $separator = $lines[$index].IndexOf('=')
        if ($separator -le 0) {
            throw "Malformed trace property at line $($index + 1): $Path"
        }
        $key = $lines[$index].Substring(0, $separator)
        $value = $lines[$index].Substring($separator + 1)
        if ($key -ne $expectedKeys[$index] -or $properties.Contains($key)) {
            throw "Non-canonical trace property '$key' at line $($index + 1): $Path"
        }
        $properties[$key] = $value
    }
    if ($properties.schema -ne '2') {
        throw "Unsupported trace schema '$($properties.schema)': $Path"
    }
    if ($properties.counterProtocol -ne 'FINAL_FLUSH') {
        throw "Unsupported trace counter protocol '$($properties.counterProtocol)': $Path"
    }
    foreach ($hashKey in @('routeManifestSha256', 'countsSha256', 'instrumentedAssemblySha256')) {
        if ($properties[$hashKey] -notmatch '^[0-9a-f]{64}$') {
            throw "The trace property $hashKey is not a canonical SHA-256: $Path"
        }
    }
    return $properties
}

function Read-RouteManifest([string]$Path) {
    $lines = @(Get-Content -LiteralPath $Path)
    if ($lines.Count -lt 3 -or $lines[0] -ne "kotlin-dotnet-generic-owner-call-routes`t1") {
        throw "Unsupported generic-owner route manifest: $Path"
    }
    $countFields = @($lines[1].Split("`t"))
    $recordCount = 0
    if ($countFields.Count -ne 2 -or $countFields[0] -ne 'N' -or
            -not [int]::TryParse($countFields[1], [ref]$recordCount) -or
            $recordCount -le 0 -or $lines.Count -ne $recordCount + 2) {
        throw "Malformed generic-owner route count: $Path"
    }
    $routes = @{}
    $previousSiteIndex = -1
    foreach ($line in $lines[2..($lines.Count - 1)]) {
        $fields = @($line.Split("`t"))
        $siteIndex = -1
        $moduleIndex = -1
        if ($fields.Count -ne 7 -or $fields[0] -ne 'R' -or
                -not [int]::TryParse($fields[1], [ref]$siteIndex) -or $siteIndex -lt 0 -or
                -not [int]::TryParse($fields[2], [ref]$moduleIndex) -or $moduleIndex -ne 0 -or
                $fields[3] -ne '-' -or
                $fields[5] -notin @('EXACT_CONSTRUCTION', 'SEMANTIC_VIEW', 'UNRESOLVED') -or
                $fields[6] -notin $expectedRequirementCounts.Keys -or
                $routes.ContainsKey($siteIndex) -or $siteIndex -le $previousSiteIndex) {
            throw "Malformed generic-owner route record '$line': $Path"
        }
        $encodedBinding = $fields[4]
        $remainder = $encodedBinding.Length % 4
        if ($encodedBinding -notmatch '^[A-Za-z0-9_-]+$' -or $remainder -eq 1) {
            throw "Invalid route logical-binding encoding for site $siteIndex`: $Path"
        }
        try {
            $standardBase64 = $encodedBinding.Replace('-', '+').Replace('_', '/')
            $bindingBytes = [Convert]::FromBase64String(
                $standardBase64 + ('=' * ((4 - $remainder) % 4))
            )
        } catch {
            throw "Invalid route logical-binding encoding for site $siteIndex`: $Path"
        }
        $canonicalBinding = [Convert]::ToBase64String($bindingBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
        if ($canonicalBinding -ne $encodedBinding) {
            throw "Non-canonical route logical-binding encoding for site $siteIndex`: $Path"
        }
        $routes[$siteIndex] = [pscustomobject]@{
            SiteIndex = $siteIndex
            Requirement = $fields[6]
        }
        $previousSiteIndex = $siteIndex
    }
    $actualIndices = @($routes.Keys | ForEach-Object { [int]$_ } | Sort-Object)
    if (Compare-Object $expectedSiteIndices $actualIndices) {
        throw "The $Corpus compiler route-site set changed: $Path"
    }
    $staticCounts = @{}
    foreach ($route in $routes.Values) {
        if (-not $staticCounts.ContainsKey($route.Requirement)) {
            $staticCounts[$route.Requirement] = 0
        }
        $staticCounts[$route.Requirement]++
    }
    foreach ($requirement in $expectedRequirementCounts.Keys) {
        if ($staticCounts[$requirement] -ne $expectedRequirementCounts[$requirement]) {
            throw "The $Corpus static $requirement route count changed: $Path"
        }
    }
    return [pscustomobject]@{
        Routes = $routes
        Sha256 = Get-Sha256 $Path
    }
}

function Read-RouteCounts([string]$Path, $RouteManifest) {
    $lines = @(Get-Content -LiteralPath $Path)
    if ($lines.Count -lt 4 -or $lines[0] -ne "kotlin-dotnet-generic-owner-call-route-counts`t1") {
        throw "Unsupported generic-owner route-count file: $Path"
    }
    $routeFields = @($lines[1].Split("`t"))
    $countFields = @($lines[2].Split("`t"))
    $recordCount = 0
    if ($routeFields.Count -ne 2 -or $routeFields[0] -ne 'R' -or
            $routeFields[1] -ne $RouteManifest.Sha256 -or
            $countFields.Count -ne 2 -or $countFields[0] -ne 'N' -or
            -not [int]::TryParse($countFields[1], [ref]$recordCount) -or
            $recordCount -ne $RouteManifest.Routes.Count -or
            $lines.Count -ne $recordCount + 3) {
        throw "Malformed or unjoined generic-owner route-count header: $Path"
    }
    $counts = @{}
    $previousSiteIndex = -1
    foreach ($line in $lines[3..($lines.Count - 1)]) {
        $fields = @($line.Split("`t"))
        $siteIndex = -1
        $eventCount = 0L
        if ($fields.Count -ne 3 -or $fields[0] -ne 'C' -or
                -not [int]::TryParse($fields[1], [ref]$siteIndex) -or
                -not [long]::TryParse($fields[2], [ref]$eventCount) -or $eventCount -lt 0 -or
                -not $RouteManifest.Routes.ContainsKey($siteIndex) -or
                $counts.ContainsKey($siteIndex) -or $siteIndex -le $previousSiteIndex) {
            throw "Malformed generic-owner route count '$line': $Path"
        }
        $counts[$siteIndex] = $eventCount
        $previousSiteIndex = $siteIndex
    }
    if (Compare-Object @($RouteManifest.Routes.Keys | Sort-Object) @($counts.Keys | Sort-Object)) {
        throw "The route-count file does not cover the exact compiler route-site set: $Path"
    }
    foreach ($siteIndex in $expectedSiteIndices) {
        if ($counts[$siteIndex] -ne $expectedSiteCounts[$siteIndex]) {
            throw "The $Corpus route count at compiler site $siteIndex changed: $Path"
        }
    }
    $dynamicByRequirement = @{}
    foreach ($siteIndex in $counts.Keys) {
        $requirement = $RouteManifest.Routes[$siteIndex].Requirement
        if (-not $dynamicByRequirement.ContainsKey($requirement)) {
            $dynamicByRequirement[$requirement] = 0L
        }
        $dynamicByRequirement[$requirement] += $counts[$siteIndex]
    }
    foreach ($requirement in $expectedDynamicRequirementCounts.Keys) {
        if ($dynamicByRequirement[$requirement] -ne $expectedDynamicRequirementCounts[$requirement]) {
            throw "The $Corpus dynamic $requirement route count changed: $Path"
        }
    }
    return [pscustomobject]@{
        Counts = $counts
        DynamicByRequirement = $dynamicByRequirement
        ProducerEventCount = [long](($counts.Values | Measure-Object -Sum).Sum)
        Sha256 = Get-Sha256 $Path
    }
}

function Assert-TraceBundle([string]$Directory, [string]$ExpectedTarget) {
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
        throw "The generic-owner route-trace bundle does not exist: $Directory"
    }
    $actualFiles = @(Get-ChildItem -LiteralPath $Directory -File -Force | ForEach-Object Name | Sort-Object)
    if (Compare-Object $expectedFiles $actualFiles) {
        throw "The route-trace bundle does not have its exact closed file set: $Directory"
    }
    $routePath = Join-Path $Directory $routeFileName
    $countsPath = Join-Path $Directory $countsFileName
    $tracePath = Join-Path $Directory $traceFileName
    $routeManifest = Read-RouteManifest $routePath
    $counts = Read-RouteCounts $countsPath $routeManifest
    $trace = Read-CanonicalProperties $tracePath
    if ($trace.targetProfile -ne $ExpectedTarget -or
            $trace.counterProtocol -ne 'FINAL_FLUSH' -or
            $trace.routeManifestSha256 -ne $routeManifest.Sha256 -or
            $trace.countsSha256 -ne $counts.Sha256 -or
            [long]$trace.producerEventCount -ne $counts.ProducerEventCount -or
            [long]$trace.allEventCount -ne $corpusDefinition.AllEventCount -or
            [long]$trace.producerEventCount -ne $corpusDefinition.ProducerEventCount -or
            [long]$trace.unrelatedEventCount -ne $corpusDefinition.UnrelatedEventCount -or
            [long]$trace.allEventCount -ne [long]$trace.producerEventCount + [long]$trace.unrelatedEventCount) {
        throw "The generic-owner route-trace manifest does not match its evidence: $Directory"
    }
    return [pscustomobject]@{
        Directory = $Directory
        RouteSha256 = $routeManifest.Sha256
        CountsSha256 = $counts.Sha256
        AssemblySha256 = $trace.instrumentedAssemblySha256
        Target = $ExpectedTarget
    }
}

function Invoke-TraceTest([string]$BundleDirectory, [string]$TestClass) {
    New-Item -ItemType Directory -Path $BundleDirectory | Out-Null
    $gradle = Join-Path $repositoryRoot 'gradlew.bat'
    $testFilter = "org.jetbrains.kotlin.test.runners.codegen.${TestClass}`$Box." +
        $corpusDefinition.TestMethod
    $traceProperty = "-Pkotlin.dotnet.genericOwnerCallRouteTraceDir=$BundleDirectory"
    & $gradle --no-daemon $traceProperty -q `
        :compiler:fir:fir2ir:dotNetTest --rerun --tests $testFilter
    if ($LASTEXITCODE -ne 0) {
        throw "The $TestClass generic-owner route-trace producer failed with exit code $LASTEXITCODE"
    }
}

$profileDefinitions = @(
    [pscustomobject]@{
        Name = 'net10'
        Target = 'NET10_0'
        Psi = 'FirPsiDotNetBoxTestGenerated'
        LightTree = 'FirLightTreeDotNetBoxTestGenerated'
    },
    [pscustomobject]@{
        Name = 'net48'
        Target = 'NET48'
        Psi = 'FirPsiDotNetFrameworkBoxTestGenerated'
        LightTree = 'FirLightTreeDotNetFrameworkBoxTestGenerated'
    }
) | Where-Object { $_.Name -in $Profiles }

if ([string]::IsNullOrWhiteSpace($ExistingCorpus)) {
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
        $OutputDirectory = Join-Path $backendDirectory "build\generic-owner-call-route-traces\$Corpus-$timestamp"
    }
    $runDirectory = [IO.Path]::GetFullPath($OutputDirectory)
    if (Test-Path -LiteralPath $runDirectory) {
        if (-not (Test-Path -LiteralPath $runDirectory -PathType Container) -or
                @(Get-ChildItem -LiteralPath $runDirectory -Force).Count -ne 0) {
            throw "The route-trace output directory must not exist or must be empty: $runDirectory"
        }
    } else {
        New-Item -ItemType Directory -Path $runDirectory | Out-Null
    }
    foreach ($profile in $profileDefinitions) {
        $profileDirectory = Join-Path $runDirectory $profile.Name
        New-Item -ItemType Directory -Path $profileDirectory | Out-Null
        Invoke-TraceTest (Join-Path $profileDirectory 'psi') $profile.Psi
        Invoke-TraceTest (Join-Path $profileDirectory 'light-tree') $profile.LightTree
    }
} else {
    $runDirectory = [IO.Path]::GetFullPath($ExistingCorpus)
    if (-not (Test-Path -LiteralPath $runDirectory -PathType Container)) {
        throw "The existing route-trace corpus does not exist: $runDirectory"
    }
}

$verified = @()
foreach ($profile in $profileDefinitions) {
    $profileDirectory = Join-Path $runDirectory $profile.Name
    $psi = Assert-TraceBundle (Join-Path $profileDirectory 'psi') $profile.Target
    $lightTree = Assert-TraceBundle (Join-Path $profileDirectory 'light-tree') $profile.Target
    if ($psi.RouteSha256 -ne $lightTree.RouteSha256 -or
            $psi.CountsSha256 -ne $lightTree.CountsSha256 -or
            $psi.AssemblySha256 -ne $lightTree.AssemblySha256) {
        throw "PSI and LightTree route-trace evidence differs for $($profile.Name)"
    }
    $verified += $psi
    $verified += $lightTree
}

$routeHashes = @($verified.RouteSha256 | Sort-Object -Unique)
$countHashes = @($verified.CountsSha256 | Sort-Object -Unique)
if ($routeHashes.Count -ne 1 -or $countHashes.Count -ne 1) {
    throw 'The generic-owner route/count evidence differs across selected target profiles'
}

Write-Output "Verified compiler-indexed generic-owner route traces: $runDirectory"
$dynamicSummary = @($expectedDynamicRequirementCounts.GetEnumerator() | Sort-Object Key | ForEach-Object {
    "$($_.Key)=$($_.Value)"
}) -join ', '
Write-Output "Corpus: $Corpus"
Write-Output "Dynamic producer routes: $dynamicSummary"
Write-Output (
    "Events: producer=$($corpusDefinition.ProducerEventCount), " +
    "unrelated=$($corpusDefinition.UnrelatedEventCount), all=$($corpusDefinition.AllEventCount) " +
    '(correctness tracing only; not performance evidence)'
)
