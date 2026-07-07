// `const val` of every supported constant type: emitted as CLR `literal` fields (metadata only,
// no `.cctor` entry) with every read inlined by the frontend — the assembled dll proves the
// literal field spellings load on the real runtime.

const val I = 42
const val S = "hi"
const val B = true
const val C = 'x'
const val L = 4000000000L
const val D = 2.5

fun box(): String {
    if (I != 42) return "FAIL I: " + I
    if (S + I != "hi42") return "FAIL S: " + S + I
    if (!B) return "FAIL B"
    if (C != 'x') return "FAIL C: " + C
    if (L != 4000000000L) return "FAIL L: " + L
    if (D != 2.5) return "FAIL D: " + D
    return "OK"
}
