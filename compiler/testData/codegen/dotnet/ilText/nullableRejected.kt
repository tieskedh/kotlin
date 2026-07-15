// The identity shape below stays rejected loudly under the hybrid nullability model (the
// function is skipped with a specific warning and absent from the emitted IL):
// - `===` with a nullable-primitive operand: the operands would box, and reference identity of
//   separately boxed values is unrelated to value equality (probe-verified False for equal
//   payloads, boxprobe_s6; Kotlin deprecates identity on boxed primitives);
// (cross-primitive nullable `==` such as `Int? == Long?` never reaches the backend: the frontend
// rejects it with EQUALITY_NOT_APPLICABLE, the same way it rejects `==` between unrelated final
// classes — no backend gate is reachable, so none is pinned here)
// Structural equality against `Any?`, Any string conversion, and Any member calls are supported
// by the System.Object foundation and are pinned by inheritanceAnyOverride/box/anyMembers.
fun identity(a: Int?, b: Int?): Boolean = a === b

fun main() {
    println("ok")
}
