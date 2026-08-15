<#
.SYNOPSIS
    Compares the former erased Array.fill loop with exact CLR-vector loops.

.DESCRIPTION
    Builds one checksum-identical C# workload for .NET Framework 4.8 and the
    pinned .NET 10 toolchain. The erased route matches the runtime helper used
    for System.Array capabilities: one object conversion followed by
    System.Array.SetValue per element. Exact routes cover the portable typed
    stelem shape and, on .NET 10, the generic System.Array.Fill<T> candidate.
#>
[CmdletBinding()]
param(
    [ValidateRange(1, 1000000000)]
    [int]$Iterations = 20000,
    [ValidateRange(1, 1048576)]
    [int]$ArrayLength = 256,
    [ValidateRange(1, 21)]
    [int]$Runs = 5,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$backendDirectory = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $OutputDirectory = Join-Path $backendDirectory "build\generic-array-fill-measurement\$timestamp"
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
    throw "Array-fill measurement requires SDK 10.0.100, found '$sdkVersion' at $dotnet"
}

$frameworkCsc = Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $frameworkCsc -PathType Leaf)) {
    throw 'The Framework CLR 4 compiler was not found'
}
$frameworkRegistryPath = 'HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full'
if (-not (Test-Path -LiteralPath $frameworkRegistryPath)) {
    throw 'A registered .NET Framework 4.8 installation is required'
}
$frameworkRelease = [int](Get-ItemProperty -LiteralPath $frameworkRegistryPath).Release
if ($frameworkRelease -lt 528040) {
    throw "A .NET Framework 4.8 installation is required; found release $frameworkRelease"
}

$source = @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.Runtime.CompilerServices;

internal static class Program
{
    private const int WorkloadVersion = 1;
    private delegate void IntFill(int[] values, int element);
    private delegate void NullableIntFill(int?[] values, int? element);
    private delegate void StringFill(string[] values, string element);

    private sealed class Result
    {
        internal long Ticks;
        internal long Checksum;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void ErasedFill(Array values, object element)
    {
        int fromIndex = 0;
        int toIndex = values.Length;
        if (fromIndex < 0 || toIndex > values.Length) throw new IndexOutOfRangeException();
        if (fromIndex > toIndex) throw new ArgumentException();
        for (int index = fromIndex; index < toIndex; index++) values.SetValue(element, index);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void ErasedInts(int[] values, int element) { ErasedFill(values, element); }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void TypedInts(int[] values, int element)
    {
        int fromIndex = 0;
        int toIndex = values.Length;
        if (fromIndex < 0 || toIndex > values.Length) throw new IndexOutOfRangeException();
        if (fromIndex > toIndex) throw new ArgumentException();
        for (int index = fromIndex; index < toIndex; index++) values[index] = element;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void TypedOpen<T>(T[] values, T element)
    {
        int fromIndex = 0;
        int toIndex = values.Length;
        if (fromIndex < 0 || toIndex > values.Length) throw new IndexOutOfRangeException();
        if (fromIndex > toIndex) throw new ArgumentException();
        for (int index = fromIndex; index < toIndex; index++) values[index] = element;
    }

#if NET10
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void BclFill<T>(T[] values, T element)
    {
        int fromIndex = 0;
        int toIndex = values.Length;
        if (fromIndex < 0 || toIndex > values.Length) throw new IndexOutOfRangeException();
        if (fromIndex > toIndex) throw new ArgumentException();
        Array.Fill<T>(values, element, fromIndex, toIndex - fromIndex);
    }
#endif

    private static void TypedOpenInts(int[] values, int element) { TypedOpen<int>(values, element); }
#if NET10
    private static void BclInts(int[] values, int element) { BclFill<int>(values, element); }
#endif
    private static void ErasedNullableInts(int?[] values, int? element) { ErasedFill(values, element); }
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void TypedNullableInts(int?[] values, int? element)
    {
        int fromIndex = 0;
        int toIndex = values.Length;
        if (fromIndex < 0 || toIndex > values.Length) throw new IndexOutOfRangeException();
        if (fromIndex > toIndex) throw new ArgumentException();
        for (int index = fromIndex; index < toIndex; index++) values[index] = element;
    }
    private static void TypedOpenNullableInts(int?[] values, int? element) { TypedOpen<int?>(values, element); }
#if NET10
    private static void BclNullableInts(int?[] values, int? element) { BclFill<int?>(values, element); }
#endif
    private static void ErasedStrings(string[] values, string element) { ErasedFill(values, element); }
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void TypedStrings(string[] values, string element)
    {
        int fromIndex = 0;
        int toIndex = values.Length;
        if (fromIndex < 0 || toIndex > values.Length) throw new IndexOutOfRangeException();
        if (fromIndex > toIndex) throw new ArgumentException();
        for (int index = fromIndex; index < toIndex; index++) values[index] = element;
    }
    private static void TypedOpenStrings(string[] values, string element) { TypedOpen<string>(values, element); }
#if NET10
    private static void BclStrings(string[] values, string element) { BclFill<string>(values, element); }
#endif

    private static Result MeasureInts(IntFill fill, int iterations, int length, int runs)
    {
        for (int warm = 0; warm < 512; warm++) fill(new int[8], warm);
        var ticks = new List<long>();
        long expectedChecksum = 0;
        for (int run = 0; run < runs; run++)
        {
            var values = new int[length];
            long checksum = 17;
            GC.Collect();
            GC.WaitForPendingFinalizers();
            GC.Collect();
            var watch = Stopwatch.StartNew();
            for (int iteration = 0; iteration < iterations; iteration++)
            {
                int element = unchecked(iteration * 31 + 7);
                fill(values, element);
                int sampleIndex = (int)((uint)iteration * 17u % (uint)length);
                checksum = unchecked(checksum * 33 + values[sampleIndex]);
            }
            watch.Stop();
            if (run == 0) expectedChecksum = checksum;
            else if (checksum != expectedChecksum) throw new InvalidOperationException("unstable int checksum");
            ticks.Add(watch.ElapsedTicks);
        }
        ticks.Sort();
        return new Result { Ticks = ticks[ticks.Count / 2], Checksum = expectedChecksum };
    }

    private static Result MeasureNullableInts(NullableIntFill fill, int iterations, int length, int runs)
    {
        for (int warm = 0; warm < 512; warm++) fill(new int?[8], warm);
        var ticks = new List<long>();
        long expectedChecksum = 0;
        for (int run = 0; run < runs; run++)
        {
            var values = new int?[length];
            long checksum = 19;
            GC.Collect();
            GC.WaitForPendingFinalizers();
            GC.Collect();
            var watch = Stopwatch.StartNew();
            for (int iteration = 0; iteration < iterations; iteration++)
            {
                int? element = (iteration & 7) == 0 ? (int?)null : iteration;
                fill(values, element);
                int sampleIndex = (int)((uint)iteration * 17u % (uint)length);
                checksum = unchecked(checksum * 33 + values[sampleIndex].GetValueOrDefault());
            }
            watch.Stop();
            if (run == 0) expectedChecksum = checksum;
            else if (checksum != expectedChecksum) throw new InvalidOperationException("unstable nullable checksum");
            ticks.Add(watch.ElapsedTicks);
        }
        ticks.Sort();
        return new Result { Ticks = ticks[ticks.Count / 2], Checksum = expectedChecksum };
    }

    private static Result MeasureStrings(StringFill fill, int iterations, int length, int runs)
    {
        for (int warm = 0; warm < 512; warm++) fill(new string[8], "warm");
        var ticks = new List<long>();
        long expectedChecksum = 0;
        for (int run = 0; run < runs; run++)
        {
            var values = new string[length];
            long checksum = 23;
            GC.Collect();
            GC.WaitForPendingFinalizers();
            GC.Collect();
            var watch = Stopwatch.StartNew();
            for (int iteration = 0; iteration < iterations; iteration++)
            {
                string element = (iteration & 1) == 0 ? "a" : "value";
                fill(values, element);
                int sampleIndex = (int)((uint)iteration * 17u % (uint)length);
                checksum = unchecked(checksum * 33 + values[sampleIndex].Length);
            }
            watch.Stop();
            if (run == 0) expectedChecksum = checksum;
            else if (checksum != expectedChecksum) throw new InvalidOperationException("unstable reference checksum");
            ticks.Add(watch.ElapsedTicks);
        }
        ticks.Sort();
        return new Result { Ticks = ticks[ticks.Count / 2], Checksum = expectedChecksum };
    }

    private static string Ratio(long baseline, long candidate)
    {
        return ((double)baseline / candidate).ToString("R", CultureInfo.InvariantCulture);
    }

    private static void Main(string[] args)
    {
        int iterations = int.Parse(args[0], CultureInfo.InvariantCulture);
        int length = int.Parse(args[1], CultureInfo.InvariantCulture);
        int runs = int.Parse(args[2], CultureInfo.InvariantCulture);
        Result erasedInts = MeasureInts(ErasedInts, iterations, length, runs);
        Result typedInts = MeasureInts(TypedInts, iterations, length, runs);
        Result openInts = MeasureInts(TypedOpenInts, iterations, length, runs);
        Result erasedNullable = MeasureNullableInts(ErasedNullableInts, iterations, length, runs);
        Result typedNullable = MeasureNullableInts(TypedNullableInts, iterations, length, runs);
        Result openNullable = MeasureNullableInts(TypedOpenNullableInts, iterations, length, runs);
        Result erasedStrings = MeasureStrings(ErasedStrings, iterations, length, runs);
        Result typedStrings = MeasureStrings(TypedStrings, iterations, length, runs);
        Result openStrings = MeasureStrings(TypedOpenStrings, iterations, length, runs);
#if NET10
        Result bclInts = MeasureInts(BclInts, iterations, length, runs);
        Result bclNullable = MeasureNullableInts(BclNullableInts, iterations, length, runs);
        Result bclStrings = MeasureStrings(BclStrings, iterations, length, runs);
#endif
        if (erasedInts.Checksum != typedInts.Checksum || erasedInts.Checksum != openInts.Checksum)
            throw new InvalidOperationException("int route checksum mismatch");
        if (erasedNullable.Checksum != typedNullable.Checksum || erasedNullable.Checksum != openNullable.Checksum)
            throw new InvalidOperationException("nullable route checksum mismatch");
        if (erasedStrings.Checksum != typedStrings.Checksum || erasedStrings.Checksum != openStrings.Checksum)
            throw new InvalidOperationException("reference route checksum mismatch");
#if NET10
        if (erasedInts.Checksum != bclInts.Checksum || erasedNullable.Checksum != bclNullable.Checksum ||
            erasedStrings.Checksum != bclStrings.Checksum)
            throw new InvalidOperationException("BCL route checksum mismatch");
#endif

        Console.WriteLine("workloadVersion=" + WorkloadVersion);
        Console.WriteLine("runtimeVersion=" + Environment.Version);
        Console.WriteLine("iterations=" + iterations);
        Console.WriteLine("arrayLength=" + length);
        Console.WriteLine("runs=" + runs);
        Console.WriteLine("frequency=" + Stopwatch.Frequency);
        Console.WriteLine("intChecksum=" + erasedInts.Checksum);
        Console.WriteLine("nullableChecksum=" + erasedNullable.Checksum);
        Console.WriteLine("referenceChecksum=" + erasedStrings.Checksum);
        Console.WriteLine("erasedIntTicks=" + erasedInts.Ticks);
        Console.WriteLine("typedIntTicks=" + typedInts.Ticks);
        Console.WriteLine("typedOpenIntTicks=" + openInts.Ticks);
        Console.WriteLine("typedIntSpeedup=" + Ratio(erasedInts.Ticks, typedInts.Ticks));
        Console.WriteLine("typedOpenIntSpeedup=" + Ratio(erasedInts.Ticks, openInts.Ticks));
        Console.WriteLine("erasedNullableTicks=" + erasedNullable.Ticks);
        Console.WriteLine("typedNullableTicks=" + typedNullable.Ticks);
        Console.WriteLine("typedOpenNullableTicks=" + openNullable.Ticks);
        Console.WriteLine("typedNullableSpeedup=" + Ratio(erasedNullable.Ticks, typedNullable.Ticks));
        Console.WriteLine("typedOpenNullableSpeedup=" + Ratio(erasedNullable.Ticks, openNullable.Ticks));
        Console.WriteLine("erasedReferenceTicks=" + erasedStrings.Ticks);
        Console.WriteLine("typedReferenceTicks=" + typedStrings.Ticks);
        Console.WriteLine("typedOpenReferenceTicks=" + openStrings.Ticks);
        Console.WriteLine("typedReferenceSpeedup=" + Ratio(erasedStrings.Ticks, typedStrings.Ticks));
        Console.WriteLine("typedOpenReferenceSpeedup=" + Ratio(erasedStrings.Ticks, openStrings.Ticks));
#if NET10
        Console.WriteLine("bclIntTicks=" + bclInts.Ticks);
        Console.WriteLine("bclIntSpeedup=" + Ratio(erasedInts.Ticks, bclInts.Ticks));
        Console.WriteLine("bclNullableTicks=" + bclNullable.Ticks);
        Console.WriteLine("bclNullableSpeedup=" + Ratio(erasedNullable.Ticks, bclNullable.Ticks));
        Console.WriteLine("bclReferenceTicks=" + bclStrings.Ticks);
        Console.WriteLine("bclReferenceSpeedup=" + Ratio(erasedStrings.Ticks, bclStrings.Ticks));
#endif
    }
}
'@

$utf8NoBom = [Text.UTF8Encoding]::new($false)
$sourcePath = Join-Path $runDirectory 'Program.cs'
[IO.File]::WriteAllText($sourcePath, $source, $utf8NoBom)
$projectPath = Join-Path $runDirectory 'ArrayFillMeasurement.csproj'
$project = @'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <ImplicitUsings>disable</ImplicitUsings>
    <Nullable>disable</Nullable>
    <Optimize>true</Optimize>
    <DebugType>none</DebugType>
    <DefineConstants>NET10</DefineConstants>
  </PropertyGroup>
</Project>
'@
[IO.File]::WriteAllText($projectPath, $project, $utf8NoBom)

$frameworkExe = Join-Path $runDirectory 'ArrayFillMeasurement-framework.exe'
& $frameworkCsc /nologo /optimize+ "/out:$frameworkExe" $sourcePath
if ($LASTEXITCODE -ne 0) { throw 'Framework array-fill measurement compilation failed' }
$net10Output = Join-Path $runDirectory 'net10'
& $dotnet build $projectPath --configuration Release --output $net10Output --nologo --verbosity quiet
if ($LASTEXITCODE -ne 0) { throw '.NET 10 array-fill measurement compilation failed' }

$arguments = @($Iterations.ToString(), $ArrayLength.ToString(), $Runs.ToString())
$frameworkOutput = & $frameworkExe @arguments
if ($LASTEXITCODE -ne 0) { throw 'Framework array-fill measurement execution failed' }
$net10Dll = Join-Path $net10Output 'ArrayFillMeasurement.dll'
$previousTieredCompilation = $env:DOTNET_TieredCompilation
try {
    # Measure stable optimized code, independently of route order and tier-promotion timing.
    $env:DOTNET_TieredCompilation = '0'
    $net10OutputText = & $dotnet $net10Dll @arguments
    if ($LASTEXITCODE -ne 0) { throw '.NET 10 array-fill measurement execution failed' }
} finally {
    $env:DOTNET_TieredCompilation = $previousTieredCompilation
}

$result = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    sdkVersion = $sdkVersion
    frameworkRelease = $frameworkRelease
    iterations = $Iterations
    arrayLength = $ArrayLength
    runs = $Runs
    net10TieredCompilation = $false
    framework = @($frameworkOutput)
    net10 = @($net10OutputText)
}
$resultPath = Join-Path $runDirectory 'result.json'
[IO.File]::WriteAllText(
    $resultPath,
    ($result | ConvertTo-Json -Depth 4),
    $utf8NoBom
)
$result | ConvertTo-Json -Depth 4
