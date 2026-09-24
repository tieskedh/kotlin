# CLR composition mechanism hypothesis — prepared, not executed

This is hand-authored C#, not Kotlin compiler output and not an accepted Kotlin
ABI or interop policy. It tests whether the listed CLR mechanisms can compose;
success would not prove a complete representation grammar, Kotlin projection or
cast semantics, generic substitution closure, compiler integration, performance,
or deployment readiness. No build or execution was performed while preparing it.

## Proposed visible contract

- `IProducer` is one non-generic, object-result interface. There is deliberately
  no natural `IProducer<T>` promise.
- `Box<T>` owns one private `T` field and has natural virtual typed Read/Write.
- `IBoxView` is an **explicit, public, read-only proposed C# API**, implemented
  explicitly by the same object. It is not a hidden automatic Kotlin ABI.
  Projected cases mutate only through original typed aliases, or replace the
  outer `Box<IBoxView>` element; the view itself has no broad write operation.
- `MakeNested<T>(IProducer)` returns `Box<IProducer>`; `MakeProjected<T>(IBoxView)`
  returns `Box<IBoxView>`. Their unused T is a proposed logical-parameter stand-in,
  not a CLR-enforced relationship between T and the erased input.
- `MakeNullable<T>(object)` promises only `IBoxView`, actually allocated as
  `Box<object>`. It does not promise `Box<T?>` or a generic `Nullable<T>` form.

## Assembly boundary and observations

`Producer.cs` compiles once per profile to Producer.dll. `Writers.cs` then compiles
against that DLL only. `Consumer.cs` compiles against both DLLs. Source from an
earlier assembly is never compiled into its consumer. Profile.cs supplies the
target-framework attribute to each assembly.

Eight independently reported runtime groups cover typed scalar state; both open
factory forms storing Int/String/bottom producers; projected views holding the
original `Box<int>` and `Box<string>`; broad/open and closed nullable forms; two
expected invalid casts; and a separate C# subclass's virtual override through both
typed and view routes. Identity, original throwing exception, body counts, actual
runtime types, the single value field, typed signatures and interface maps are
checked/printed. Negative casts must preserve the original object and state.

The metadata-fixed `FixedObjectBase<T> : Base<object>` test does not claim this is
an implementation of Kotlin `Base<T?>`. It proves only that this chosen base edge
does not become `Base<int?>` after substitution.

`UnrestrictedNullable.cs` is a **separate expected compile failure** (CS0453).
It is never included in the three positive assemblies. This separates a C#
well-formedness constraint from the runtime identity/capacity negatives.

## Root-controlled execution

Wait until the shared Framework lane is idle. Use installed SDK 10.0.100 and
reference pack 10.0.0; no download, Gradle or Kotlin compilation occurs:

```powershell
pwsh -NoProfile -File .\Run-Probe.ps1
```

`-Profiles net10` or `-Profiles net48` limits the run. `-DotNetHost` overrides the
installed host path; `-OutputDirectory` must name an absent/empty directory.
The default creates a timestamped child. Both profiles use the same installed
Roslyn compiler with x64/optimized output. Framework uses installed CLR4 reference
assemblies and a fresh Windows PowerShell CLR process; .NET 10 uses a fresh dotnet
process. Runtime version and pointer width are reported separately from the target.

The driver snapshots/hashes all sources and records tool/reference/output hashes,
compile diagnostics, execution output and actual type/signature/state observations.
These are correctness mechanism artifacts, not measured timings or native code-size
evidence. All output remains under the selected run directory; existing inputs are
not edited. No Kotlin repository files are changed.
