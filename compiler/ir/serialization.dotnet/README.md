# Kotlin/.NET IR serialization

This module owns the stable Kotlin IR identity and KLIB IR serialization used by the .NET target.
It is intentionally shared by FIR-to-IR integration, the CLI library producer, and the .NET backend;
it does not own CLR lowering or CIL generation.
