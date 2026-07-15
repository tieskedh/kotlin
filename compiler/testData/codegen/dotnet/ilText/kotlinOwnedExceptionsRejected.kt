// Kotlin.Runtime now contains the physical RuntimeException root needed by exact Kotlin-owned
// exceptions, but source use remains rejected until all existing mapped children have a coherent
// catch policy. Enabling it now would make a thrown IllegalStateException (currently mapped to
// System.InvalidOperationException) escape catch (RuntimeException). Error still has no CLR fatal
// branch, and NumberFormatException still cannot map to System.FormatException without breaking
// its IllegalArgumentException edge. Each function below is therefore skipped; main survives.
fun runtimeValue(): Throwable = RuntimeException()

fun catchRuntime(): String = try {
    throw IllegalStateException()
} catch (failure: RuntimeException) {
    "caught"
}

fun errorValue(): Throwable = Error()

fun numberFormatValue(): Throwable = NumberFormatException()

fun main() {
    println("owned-exception-boundary")
}
