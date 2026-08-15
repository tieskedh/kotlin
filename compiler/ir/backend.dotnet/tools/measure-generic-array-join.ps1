<#
.SYNOPSIS
    Measures the typed projected-array join load against its former erased load.

.DESCRIPTION
    Rebuilds the reusable Framework 4.8 and .NET 10 Kotlin.Stdlib products,
    verifies that Array.joinTo contains one T[] probe plus typed and semantic
    loop arms, then compiles one checksum-identical C# consumer for both CLRs.

    The causal pair keeps T, Appendable, transform, rendering, and output
    identical. Only T[]/ldelem T versus System.Array/GetValue/unbox T differs.
    The actual exact and widened Kotlin.Stdlib routes are executed separately;
    widened is correctness context, not the causal performance baseline.
#>
[CmdletBinding()]
param(
    [ValidateRange(1, 1000000000)]
    [int]$Iterations = 100000,
    [ValidateRange(1, 21)]
    [int]$Runs = 5,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$backendDirectory = Split-Path -Parent $PSScriptRoot
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $backendDirectory '..\..\..'))
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $OutputDirectory = Join-Path $backendDirectory "build\generic-array-join-measurement\$timestamp"
}
$runDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $runDirectory) {
    if (-not (Test-Path -LiteralPath $runDirectory -PathType Container) -or
            @(Get-ChildItem -LiteralPath $runDirectory -Force).Count -ne 0) {
        throw "Measurement output must not exist or must be empty: $runDirectory"
    }
} else {
    New-Item -ItemType Directory -Path $runDirectory | Out-Null
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
if ($null -eq $dotnet) { throw 'No .NET SDK was found; provision the Kotlin/.NET toolchain first' }
$sdkVersion = (& $dotnet --version).Trim()
if ($LASTEXITCODE -ne 0 -or $sdkVersion -ne '10.0.100') {
    throw "Generic-array join measurement requires SDK 10.0.100, found '$sdkVersion' at $dotnet"
}

$frameworkRegistryPath = 'HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full'
if (-not (Test-Path -LiteralPath $frameworkRegistryPath)) {
    throw 'A registered .NET Framework 4.8 installation is required'
}
$frameworkRelease = [int](Get-ItemProperty -LiteralPath $frameworkRegistryPath).Release
if ($frameworkRelease -lt 528040) {
    throw "A .NET Framework 4.8 installation is required; found release $frameworkRelease"
}

$gradle = Join-Path $repositoryRoot 'gradlew.bat'
& $gradle --no-daemon -q `
    :compiler:fir:fir2ir:produceDotNetTestPlatformNet100 `
    :compiler:fir:fir2ir:produceDotNetTestPlatformNet48
if ($LASTEXITCODE -ne 0) { throw 'Kotlin/.NET test-platform production failed' }

$platformRoot = Join-Path $repositoryRoot 'compiler\fir\fir2ir\build\dotnet-test-platform'
$profiles = [ordered]@{
    net10 = Join-Path $platformRoot 'net10.0'
    framework = Join-Path $platformRoot 'net48'
}
$artifacts = @{}
foreach ($entry in $profiles.GetEnumerator()) {
    $profileArtifacts = [ordered]@{
        Runtime = Join-Path $entry.Value 'Kotlin.Runtime.dll'
        Stdlib = Join-Path $entry.Value 'Kotlin.Stdlib.dll'
        StdlibIl = Join-Path $entry.Value 'Kotlin.Stdlib.il'
    }
    foreach ($artifact in $profileArtifacts.Values) {
        if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
            throw "The $($entry.Key) platform lacks $artifact"
        }
    }
    $artifacts[$entry.Key] = $profileArtifacts
}

function Assert-JoinIl([string]$Path) {
    $text = (Get-Content -LiteralPath $Path -Raw).Replace("`r`n", "`n")
    $signature = ".method public hidebysig static !!1 'joinTo'<'T', " +
        "(class 'Kotlin.Text.Appendable') 'A'>(class [mscorlib]System.Array '<this>'"
    $defaultSignature = ".method public hidebysig static !!1 'joinTo`$default'<'T', "
    $start = $text.IndexOf($signature, [StringComparison]::Ordinal)
    $end = $text.IndexOf($defaultSignature, $start + 1, [StringComparison]::Ordinal)
    if ($start -lt 0 -or $end -le $start) { throw "Cannot isolate Array.joinTo in $Path" }
    $body = $text.Substring($start, $end - $start)
    foreach ($required in @(
        'isinst !!0[]',
        'ldelem !!0',
        'callvirt instance object [mscorlib]System.Array::GetValue(int32)'
    )) {
        if (-not $body.Contains($required)) { throw "Array.joinTo lacks '$required' in $Path" }
    }
    if ($text.Contains('dotNetExactArrayOrNull') -or $text.Contains('dotNetJoinToExact')) {
        throw "A projected-array optimization helper leaked into CLR metadata: $Path"
    }
}

Assert-JoinIl $artifacts.net10.StdlibIl
Assert-JoinIl $artifacts.framework.StdlibIl

$source = @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.Runtime.CompilerServices;
using Kotlin.Collections;
using Kotlin.Text;

internal static class Program
{
    private const int WorkloadVersion = 1;
    private static readonly int[] Values = { 1, 2, 3, 4, 5, 6, 7, 8 };

    private sealed class Result
    {
        internal long Ticks;
        internal long Allocated;
        internal int Checksum;
    }

    private static string ActualExact()
    {
        return CollectionsKt.joinToString<int>(Values, ", ", "", "", -1, "...", null);
    }

    private static string ActualWidened()
    {
        return CollectionsKt.joinToString<object>(Values, ", ", "", "", -1, "...", null);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static A TypedGeneric<T, A>(T[] values, A buffer, object separator, Kotlin.Function1 transform)
        where A : Appendable
    {
        buffer.append((object)"");
        for (int index = 0; index < values.Length; index++)
        {
            if (index > 0) buffer.append(separator);
            StringsKt.appendElement<T>(buffer, values[index], transform);
        }
        buffer.append((object)"");
        return buffer;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static A LegacyGeneric<T, A>(Array values, A buffer, object separator, Kotlin.Function1 transform)
        where A : Appendable
    {
        buffer.append((object)"");
        for (int index = 0; index < values.Length; index++)
        {
            if (index > 0) buffer.append(separator);
            T element = (T)values.GetValue(index);
            StringsKt.appendElement<T>(buffer, element, transform);
        }
        buffer.append((object)"");
        return buffer;
    }

    private static string TypedRoute()
    {
        return TypedGeneric<int, Kotlin.Text.StringBuilder>(
            Values, new Kotlin.Text.StringBuilder(), ", ", null).ToString();
    }

    private static string LegacyRoute()
    {
        return LegacyGeneric<int, Kotlin.Text.StringBuilder>(
            Values, new Kotlin.Text.StringBuilder(), ", ", null).ToString();
    }

    private static Result Measure(Func<string> operation, int iterations, int runs)
    {
        for (int warm = 0; warm < 2000; warm++) operation();
        var ticks = new List<long>();
        var allocations = new List<long>();
        int expectedChecksum = 0;
        for (int run = 0; run < runs; run++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
            GC.Collect();
            long before = GC.GetAllocatedBytesForCurrentThread();
            int checksum = 0;
            var watch = Stopwatch.StartNew();
            for (int iteration = 0; iteration < iterations; iteration++) checksum += operation().Length;
            watch.Stop();
            long allocated = GC.GetAllocatedBytesForCurrentThread() - before;
            if (run == 0) expectedChecksum = checksum;
            else if (checksum != expectedChecksum) throw new InvalidOperationException("unstable checksum");
            ticks.Add(watch.ElapsedTicks);
            allocations.Add(allocated);
        }
        ticks.Sort();
        allocations.Sort();
        return new Result {
            Ticks = ticks[ticks.Count / 2],
            Allocated = allocations[allocations.Count / 2],
            Checksum = expectedChecksum,
        };
    }

    private static string Ratio(long baseline, long candidate)
    {
        return ((double)baseline / candidate).ToString("R", CultureInfo.InvariantCulture);
    }

    private static void Main(string[] args)
    {
        int iterations = int.Parse(args[0], CultureInfo.InvariantCulture);
        int runs = int.Parse(args[1], CultureInfo.InvariantCulture);
        Result actualExact = Measure(ActualExact, iterations, runs);
        Result actualWidened = Measure(ActualWidened, iterations, runs);
        Result typed = Measure(TypedRoute, iterations, runs);
        Result legacy = Measure(LegacyRoute, iterations, runs);
        if (actualExact.Checksum != actualWidened.Checksum || actualExact.Checksum != typed.Checksum ||
            actualExact.Checksum != legacy.Checksum)
            throw new InvalidOperationException("route checksum mismatch");
        long elementCount = checked((long)iterations * Values.Length);
        long allocationDelta = legacy.Allocated - typed.Allocated;
        if (allocationDelta <= 0 || allocationDelta % elementCount != 0)
            throw new InvalidOperationException("load allocation delta is not integral per element");

        Console.WriteLine("workloadVersion=" + WorkloadVersion);
        Console.WriteLine("runtimeVersion=" + Environment.Version);
        Console.WriteLine("iterations=" + iterations);
        Console.WriteLine("arrayLength=" + Values.Length);
        Console.WriteLine("runs=" + runs);
        Console.WriteLine("frequency=" + Stopwatch.Frequency);
        Console.WriteLine("checksum=" + actualExact.Checksum);
        Console.WriteLine("actualExactTicks=" + actualExact.Ticks);
        Console.WriteLine("actualExactAllocated=" + actualExact.Allocated);
        Console.WriteLine("actualWidenedTicks=" + actualWidened.Ticks);
        Console.WriteLine("actualWidenedAllocated=" + actualWidened.Allocated);
        Console.WriteLine("typedTicks=" + typed.Ticks);
        Console.WriteLine("typedAllocated=" + typed.Allocated);
        Console.WriteLine("legacyTicks=" + legacy.Ticks);
        Console.WriteLine("legacyAllocated=" + legacy.Allocated);
        Console.WriteLine("typedSpeedup=" + Ratio(legacy.Ticks, typed.Ticks));
        Console.WriteLine("allocationBytesRemovedPerElement=" + (allocationDelta / elementCount));
    }
}
'@

$utf8NoBom = [Text.UTF8Encoding]::new($false)
$sourcePath = Join-Path $runDirectory 'Program.cs'
[IO.File]::WriteAllText($sourcePath, $source, $utf8NoBom)
$projectPath = Join-Path $runDirectory 'GenericArrayJoinMeasurement.csproj'
$project = @'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFrameworks>net10.0;net48</TargetFrameworks>
    <ImplicitUsings>disable</ImplicitUsings>
    <Nullable>disable</Nullable>
    <Optimize>true</Optimize>
    <DebugType>none</DebugType>
  </PropertyGroup>
  <ItemGroup>
    <Reference Include="Kotlin.Runtime" Condition="'$(TargetFramework)' == 'net10.0'">
      <HintPath>__NET10_RUNTIME__</HintPath>
    </Reference>
    <Reference Include="Kotlin.Stdlib" Condition="'$(TargetFramework)' == 'net10.0'">
      <HintPath>__NET10_STDLIB__</HintPath>
    </Reference>
    <Reference Include="Kotlin.Runtime" Condition="'$(TargetFramework)' == 'net48'">
      <HintPath>__FRAMEWORK_RUNTIME__</HintPath>
    </Reference>
    <Reference Include="Kotlin.Stdlib" Condition="'$(TargetFramework)' == 'net48'">
      <HintPath>__FRAMEWORK_STDLIB__</HintPath>
    </Reference>
  </ItemGroup>
</Project>
'@
$project = $project.Replace('__NET10_RUNTIME__', $artifacts.net10.Runtime)
$project = $project.Replace('__NET10_STDLIB__', $artifacts.net10.Stdlib)
$project = $project.Replace('__FRAMEWORK_RUNTIME__', $artifacts.framework.Runtime)
$project = $project.Replace('__FRAMEWORK_STDLIB__', $artifacts.framework.Stdlib)
[IO.File]::WriteAllText($projectPath, $project, $utf8NoBom)

$net10Output = Join-Path $runDirectory 'bin\Release\net10.0'
$frameworkOutput = Join-Path $runDirectory 'bin\Release\net48'
# Keep the normal target-framework output separation. Building the two targets
# into explicit --output directories lets MSBuild reuse copy-local state between
# them and can copy the net10 Kotlin.Stdlib into the net48 directory.
& $dotnet build $projectPath --configuration Release --nologo --verbosity quiet
if ($LASTEXITCODE -ne 0) { throw 'Generic-array join measurement compilation failed' }

$arguments = @($Iterations.ToString(), $Runs.ToString())
$frameworkExe = Join-Path $frameworkOutput 'GenericArrayJoinMeasurement.exe'
$frameworkText = & $frameworkExe @arguments
if ($LASTEXITCODE -ne 0) { throw 'Framework generic-array join measurement execution failed' }
$net10Dll = Join-Path $net10Output 'GenericArrayJoinMeasurement.dll'
$previousTieredCompilation = $env:DOTNET_TieredCompilation
try {
    $env:DOTNET_TieredCompilation = '0'
    $net10Text = & $dotnet $net10Dll @arguments
    if ($LASTEXITCODE -ne 0) { throw '.NET 10 generic-array join measurement execution failed' }
} finally {
    $env:DOTNET_TieredCompilation = $previousTieredCompilation
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

$result = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    sdkVersion = $sdkVersion
    frameworkRelease = $frameworkRelease
    iterations = $Iterations
    arrayLength = 8
    runs = $Runs
    net10TieredCompilation = $false
    measurementToolSha256 = Get-Sha256 $PSCommandPath
    sourceSha256 = Get-Sha256 $sourcePath
    frameworkStdlibSha256 = Get-Sha256 $artifacts.framework.Stdlib
    frameworkStdlibIlSha256 = Get-Sha256 $artifacts.framework.StdlibIl
    net10StdlibSha256 = Get-Sha256 $artifacts.net10.Stdlib
    net10StdlibIlSha256 = Get-Sha256 $artifacts.net10.StdlibIl
    framework = @($frameworkText)
    net10 = @($net10Text)
}
$resultPath = Join-Path $runDirectory 'result.json'
[IO.File]::WriteAllText($resultPath, ($result | ConvertTo-Json -Depth 4), $utf8NoBom)
$result | ConvertTo-Json -Depth 4
