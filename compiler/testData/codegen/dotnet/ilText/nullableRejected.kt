// Shapes that stay rejected loudly under the hybrid nullability model (each function below is
// skipped with a specific warning and absent from the emitted IL):
// - `===` with a nullable-primitive operand: the operands would box, and reference identity of
//   separately boxed values is unrelated to value equality (probe-verified False for equal
//   payloads, boxprobe_s6; Kotlin deprecates identity on boxed primitives);
// - `==` mixing a nullable primitive with `Any?`: the primitive side would need boxing and an
//   Any.equals model;
// (cross-primitive nullable `==` such as `Int? == Long?` never reaches the backend: the frontend
// rejects it with EQUALITY_NOT_APPLICABLE, the same way it rejects `==` between unrelated final
// classes — no backend gate is reachable, so none is pinned here)
// - string conversion of an `Any?`-typed value (no Any.toString model);
// - member calls on an `Any?` receiver (`hashCode` — Any is storage-only, no member model).
fun identity(a: Int?, b: Int?): Boolean = a === b

fun mixed(a: Int?, b: Any?): Boolean = a == b

fun render(a: Any?): String = "$a"

fun hash(a: Any): Int = a.hashCode()

fun main() {
    println("ok")
}
