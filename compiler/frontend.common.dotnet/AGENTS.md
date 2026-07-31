# Kotlin/.NET common CLR loading

Read `../ir/backend.dotnet/AGENTS.md` before changing this module; its Kotlin/.NET design and test
rules are binding here too.

This module owns objective PE/ECMA-335 metadata, resolution, validation, and carrier-presence
evidence under `org.jetbrains.kotlin.load.dotnet`. It must not depend on FIR, IR, a backend, a CLI
pipeline, Gradle integration, Roslyn tooling, or Kotlin library/ABI carrier implementations.
Kotlin stdlib and JDK facilities are its only current dependencies.

Do not convert CLR evidence into Kotlin types, contracts, symbols, or diagnostics here. That policy
belongs under `org.jetbrains.kotlin.fir.dotnet`. The classpath discriminator receives a managed
carrier resource name from its caller and reports only `WithCarrier` or `WithoutCarrier`; it does
not infer a producer language or own `Kotlin.Metadata`.

Mirror mature target module and package ownership unless a documented CLR constraint requires a
different shape. The strict commit gate remains
`./gradlew :compiler:backend.dotnet:dotNetTest --rerun -q --no-daemon`, including the 16-suite XML
audit documented by the backend instructions.
