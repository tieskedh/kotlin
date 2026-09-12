# Exact Kotlin results through retained foreign interface slots

Base: `3724aeab9cf38649229b842c4b9d6382862da9cd`, whose full production
checkpoint contains 2,932 passing tests. This is a test-only follow-on to the
[incoming result-reference proof](foreign-kotlin-interface-results-2026-09-12.md),
not a new compiler representation or bridge rule.

## Question and controls

The earlier `KotlinFactory(private val source: Source<Int>)` reproducer failed
to supply the retained foreign `Source<int>` result from its object-domain
state. That alone did not establish that every Kotlin override returning a
Kotlin-owned interface needed a compiler change.

Two final Kotlin implementations now distinguish the result forms:

```kotlin
class FreshFactory : ForeignReturn.KotlinFactory {
    override fun read(): Source<Int> = IntSource()
}

class NominalFreshFactory : ForeignReturn.KotlinFactory {
    override fun read(): IntSource = IntSource()
}
```

Both use the same fresh constructor result. The first deliberately declares
the interface as its public result; the second declares the implementation
class. Kotlin calls both source members. A separately compiled C# executable
calls both objects through the unchanged foreign interface MethodDef.

Reflection additionally verifies that the public source members retain their
declared `Source<int>` and `IntSource` return surfaces, and that both interface
targets have the exact retained `Source<int>` slot signature. In production,
that slot remains the original non-generic `Source`; the inverse implements
and calls its actual existing public contract rather than changing its mangled
accessor name. No hidden C# authoring obligation is introduced.

## Evidence and scope

Both one-lane candidate probes passed before any compiler change: ordinary
execution and the strengthened MethodDef/InterfaceMap checks. Final candidate
and production-erased inverse verification each passed four suites / four tests
over PSI and LightTree on Framework 4.8 and .NET 10, with zero failures, errors
or skips. Both frozen source hashes match the audited runs.

The proof changes only the existing test data and its fixture-local validator.
The compiler, ABI, Runtime and Stdlib are unchanged. The full production gate
on the base remains the inherited checkpoint; new assertions need their own
focused parser/runtime matrix, not a claim that the older full run executed
them. Evidence is under `D:\CodexTemp\foreign-kotlin-override-20260912`, including
`fresh-and-nominal-candidate.xml`, `fresh-physical-candidate.xml`, the test patch,
`candidate.zip`, `inverse.zip` and frozen source hashes.

This proves neither broad constructor/state inputs nor arbitrary virtual,
mutable, projected or separately inherited result flows. In particular, it is
not permission to cast every object-domain `Source<Int>` result to `Source<int>`.
The earlier broad-field negative remains separate evidence. The source-built
stdlib census on the base is unchanged at 215 diagnostic lines, with no added
or removed lines relative to `2a13f45341`; this proof does not advance it.
