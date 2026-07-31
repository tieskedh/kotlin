# Kotlin Language Targets: .NET

.NET-specific compilation target vocabulary. This module provides `DotNetTarget`, the .NET
platform marker, and narrowly scoped target capabilities consumed independently by compiler
configuration, CLR loading, FIR, backend lowering, and artifact production.

Target framework, product kind, runtime identifier, and packaging asset selection are separate
axes. Only target-framework/API identity and capabilities that genuinely affect multiple compiler
layers belong here. Compiler configuration keys, library compatibility policy, IL rendering,
runtime construction, and packaging stay in their respective owners.

## Binary compatibility

The module's public API is intentionally small and free of compiler implementation types. The
compatibility validator and foreign-class usage tracker guard that boundary.
