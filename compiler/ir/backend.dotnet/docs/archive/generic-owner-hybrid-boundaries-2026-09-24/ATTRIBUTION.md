# Framework broad-path static attribution and matched diagnostic

Attribution of the frozen actual storage-cycle products. The root task prepared
and ran the Framework diagnostics; the independent audit below only reads those
results. No optimization or compiler change is proposed.

## What the actual CIL establishes

Files below are relative to
`D:\CodexTemp\generic-owner-storage-cycle-20260924\isolated-candidate-lighttree-net48`.

- `lib2.il:58`: `makeBroad(IntProducer)` passes the original receiver as `object`
  to the real generic `make<object>` MethodSpec. There is no lookup or semantic call.
- `lib1.il:475`: `make<T>` directly executes one `newobj Box<!!0>`; the selected
  `object` instantiation owns one object-valued field.
- `lib2.il:146` and `:159`: both broad writers make natural calls to
  `Box<object>.write(!0)`. They do not cast or box the reference argument.
- `lib1.il:71` and `:78`: natural Box read/write just forward to private typed
  field accessors. The same getter/setter forwarding exists on erased Box.
- `lib1.il:86` and `:95`: Box capability methods exist, but these loops do not
  invoke them. Producer capability methods also exist but are not called here.
- C# performs three natural box reads and three interface conversions/calls:
  `Producer<int>` twice and `Producer<string>` once. The producer bodies themselves
  have the same counter/update/value instructions in both epochs.
- Erased C# calls its one canonical `Producer` interface. Its Int implementation
  bridge calls the typed Int body then boxes the result; C# unboxes it. These two
  boxes per iteration account for the observed extra 48 bytes, not for a candidate
  allocation increase. Candidate returns Int directly.
- `lib2.il:96`: only the nested path adds a compiler-selected alternate factory
  entry followed by a cast to the recorded Box capability result. C# then checks
  the actual Box<object> construction. `lib1.il:489` shows that this alternate
  factory body is still just `ldarg; newobj Box<object>; ret`, not dynamic mapping.

The long Framework run records medians 130.77/25.88 ns for candidate/erased broad
and 134.56/27.26 ns for nested. Thus the common broad path, not an extra nested
semantic dispatcher, contains the dominant gap. This is structural localization,
not a claim that the roughly 105 ns difference is already assigned to one opcode.

No `InvokeRecordedMember`, reflection, runtime family lookup or argument mapping
function is reached by the measured broad path. Generic dictionary/type checks,
interface conversion cost, dispatch strategy, inlining and reference-generic code
sharing are CLR/JIT possibilities, not extra Kotlin mapping functions visible here.
Native JIT output would be needed to identify generated helper calls precisely.

## Minimal matched subtraction

`Prepare-StorageAttribution.ps1` only writes three derived templates and copies
the audited driver/Framework launcher into a NEW output directory. It performs no
compilation or execution. The root task reviews the generated source and runs the
printed commands serially against the frozen actual DLLs. Candidate and erased
remain in separate processes. The preparation script accepts external protocol,
artifact and output directories.

All three broad variants preserve one factory allocation, two Kotlin writes,
three natural box reads, the same receiver/value sequence, checksum **151**, and
the original producer effects **2N/N**. Each variant also has the same three
identity guards, so reads cannot be dropped merely because calls use cached views.
Those guards deliberately change the diagnostic baseline: do NOT subtract these
times directly from the original unguarded benchmark.

| Variant | Receiver used for the three identical body calls | Boundary removed |
| --- | --- | --- |
| checked-interface | Cast the freshly read object to its actual natural interface | None; matched diagnostic baseline |
| cached-interface | Natural interface captured from the same producer before timing; read-object identity still checked | Repeated interface conversion / source of receiver provenance |
| concrete-receiver | Original sealed IntProducer/StringProducer after the same identity check | Interface dispatch and, in erased, canonical result bridge/boxing |

If checked-to-cached removes the candidate Framework gap, repeated interface
conversion or optimizer knowledge tied to that conversion is implicated. If
cached remains slow but concrete is fast, generic-interface dispatch/inlining is
implicated. The experiment cannot separate an opcode's runtime implementation
from JIT optimization enabled by changed receiver provenance. Inspect native code
before claiming a precise cast or call count explains the wall time.

The erased concrete variant intentionally also bypasses its canonical bridge:
observable work stays equal, physical boxing does not. Report allocations per
variant and compare within a variant; do not call that extra physical difference
an isolated generic-interface call cost. Candidate allocations should remain one
box across all three if the JIT retains these allocations.

If the candidate/erased gap persists in concrete-receiver, this test leaves only
the storage/factory/reference-generic substrate as the major differing path. Then
the NEXT smallest control is to replace the two Kotlin writer wrappers with the
same actual Box public `write` calls on BOTH sides, keeping reads/calls/identities
unchanged; a further `new Box<object>` versus Kotlin make control can separate
generic factory entry from storage. Do not add those controls before the first
three results identify whether that branch is needed.

Framework is the first diagnostic target. The existing scalar case remains an
unchanged environmental control. A .NET 10 run of the same variants is useful only
after Framework results; all remain exploratory because unrelated user apps are
active. No new ABI, C# contract, erased fallback or performance optimization follows
automatically from this attribution.

## Independent audit of the executed Framework diagnostics

The root task executed all three variants serially. Each has 28 samples: seven
candidate and seven erased samples for scalar and broad, one million warmup cycles
and ten million measured cycles per fresh CLR process. All report the same
Framework runtime `4.0.30319.42000`, eight-byte pointers and the same driver hash
`85a07a7d9025a83add9dfe38776172434bc18e40482f46ba190032137fa7ec1d`.

The independent read-only audit found **84 unique samples, 84 raw protocol logs,
84 CSV rows, and zero mismatches**. It checked every JSON sample against its raw
`RESULT` line, expected checksum/counters/allocations and alternating pair order;
recomputed the medians; and rehashed every manifest-listed original and copied
input file. Sample IDs are exactly 0 through 6 in each group.

- Scalar checksum is **460,000,000**, with zero producer calls. Allocation is
  exactly **240,000,000 bytes candidate / 720,000,000 bytes erased** per sample.
- Broad checksum is **1,510,000,000**, with **20,000,000 Int** and
  **10,000,000 String** calls per sample.
- Checked/cached broad allocate exactly **24/72 bytes per cycle** for
  candidate/erased. Concrete broad allocates **24/24 bytes per cycle**, consistent
  with the erased concrete calls bypassing their boxing result bridge.

| Matched broad variant | Candidate median ns/cycle | Erased median ns/cycle | Candidate / erased |
| --- | ---: | ---: | ---: |
| Checked interface | 127.89900 | 25.51364 | 5.013 |
| Cached interface | 13.25756 | 20.33097 | 0.652 |
| Concrete receiver | 9.68474 | 9.87426 | 0.981 |

The raw ranges are respectively 127.31–129.49 / 24.93–27.08 ns for checked,
11.66–13.75 / 20.09–22.02 ns for cached, and 9.58–10.54 / 9.20–10.69 ns for
concrete. The unchanged scalar-control medians remain 3.82–3.89 ns candidate and
10.54–10.77 ns erased across the three runs.

Sources and raw results remain under:

- [checked-interface](attribution-variants/checked-interface/results-net48/samples.json)
- [cached-interface](attribution-variants/cached-interface/results-net48/samples.json)
- [concrete-receiver](attribution-variants/concrete-receiver/results-net48/samples.json)

Line-by-line comparison of each epoch's generated C# found exactly four changed
lines between checked and either other variant: the variant comment at line 1
and the three invocation expressions at lines 73, 77 and 81. Factory choice,
loop structure, all three reads/identity guards, both writes, checksum, validation,
timing, producer effects and scalar control are unchanged. Cached uses the same
natural interface methods as checked; concrete calls the existing public typed
producer bodies. There is no hidden semantic call or dropped storage operation.

### Attribution boundary after these results

The large Framework penalty disappears when repeatedly recovering an interface
receiver from the object-valued read is replaced by the pre-captured interface
receiver, while the identity of every read is still checked. This localizes the
measured penalty to that **conversion/source-provenance and its JIT consequences**,
not to an unavoidable Box<object> storage cost or a semantic runtime dispatcher.
Candidate cached still makes the interface calls, so interface dispatch alone is
not sufficient to explain the old fivefold gap. Concrete parity further argues
against pursuing a general storage/factory-overhead explanation for this result.

The 114.64 ns candidate difference is NOT an isolated per-opcode cast cost. The
changed receiver source can affect CLR generic interface checks, call-site type
knowledge, devirtualization, inlining and code generation. No native JIT output
was inspected. The concrete erased variant additionally removes canonical bridge
boxing, so its delta cannot isolate only dispatch cost either.

This is a bounded diagnostic on known, coherent Kotlin-produced receivers with
pre-capturable identities, not permission to cache or specialize arbitrary mutable
Kotlin values or to change public interop. The original roughly fivefold result is
**not evidence of semantic-dispatcher overhead**: the static broad path contains
no such invocation, and the matched experiment changes no semantic dispatcher.
Background applications, the exploratory protocol, unmeasured native code and the
unresolved narrow-storage contract still bound any strategic conclusion. No
additional storage/factory subtraction is currently required to explain this
large observed difference; any next experiment should target runtime conversion
behavior or inspect JIT output, without treating these results as an optimization
implementation mandate.
