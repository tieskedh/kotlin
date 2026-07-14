// The full null-aware '==' truth table over the Nullable<T> representation: both-null true,
// one-null false, else value comparison — per primitive, plus the mixed T?/T directions and the
// (none, some(0)) corner the GetValueOrDefault/HasValue shape must rescue (boxprobe_s5).
fun box(): String {
    val someA: Int? = 5
    val someB: Int? = 5
    val someC: Int? = 6
    val noneI: Int? = null
    val nonePair: Int? = null
    val zeroI: Int? = 0
    if (!(someA == someB)) return "fail: int some==some"
    if (someA == someC) return "fail: int 5==6"
    if (someA == noneI) return "fail: int some==none"
    if (noneI == someA) return "fail: int none==some"
    if (!(noneI == nonePair)) return "fail: int none==none"
    if (!(noneI == null)) return "fail: int none==null"
    if (someA == null) return "fail: int some==null"
    if (!(noneI === null)) return "fail: int none===null"
    if (someA === null) return "fail: int some===null"
    if (!(null === noneI)) return "fail: null===int none"
    if (null === someA) return "fail: null===int some"
    if (noneI !== null) return "fail: int none!==null"
    if (!(someA !== null)) return "fail: int some!==null"
    if (null !== noneI) return "fail: null!==int none"
    if (!(null !== someA)) return "fail: null!==int some"
    if (noneI == zeroI) return "fail: int none==some(0)"
    val plainI = 5
    if (!(someA == plainI)) return "fail: int? == int"
    if (!(plainI == someA)) return "fail: int == int?"
    if (noneI == plainI) return "fail: none == plain int"
    if (plainI == someC) return "fail: plain int == some(6)"

    val someL: Long? = 7L
    val noneL: Long? = null
    if (!(someL == 7L)) return "fail: long? == long"
    if (noneL == 0L) return "fail: long none==some(0)"
    if (!(noneL == null)) return "fail: long none==null"

    val someD: Double? = 2.5
    val noneD: Double? = null
    if (!(someD == 2.5)) return "fail: double? == double"
    if (noneD == 0.0) return "fail: double none==some(0.0)"
    if (someD == noneD) return "fail: double some==none"
    val nan = 0.0 / 0.0
    val someNanA: Double? = nan
    val someNanB: Double? = nan
    // IEEE semantics survive the nullable representation (JVM parity: the ieee754equals path
    // uses the Intrinsics.areEqual(Double, Double) specialization, which compares unboxed).
    if (someNanA == someNanB) return "fail: NaN? == NaN? must be false"
    val negZero: Double? = -0.0
    val posZero: Double? = 0.0
    if (!(negZero == posZero)) return "fail: -0.0? == 0.0? must be true"

    val someT: Boolean? = true
    val noneB: Boolean? = null
    if (!(someT == true)) return "fail: bool? == true"
    if (someT == false) return "fail: bool? == false"
    if (noneB == false) return "fail: bool none==some(false)"
    if (!(noneB == null)) return "fail: bool none==null"

    // when with a nullable-primitive subject composes from the same '==' pieces
    val described = when (someA) {
        null -> "none"
        5 -> "five"
        else -> "other"
    }
    if (described != "five") return "fail: when subject some(5)"
    val describedNone = when (noneI) {
        null -> "none"
        0 -> "zero"
        else -> "other"
    }
    if (describedNone != "none") return "fail: when subject none"

    val someCh: Char? = 'a'
    val noneCh: Char? = null
    if (!(someCh == 'a')) return "fail: char? == char"
    if (someCh == 'b') return "fail: char? == other char"
    if (noneCh == someCh) return "fail: char none==some"
    if (!(noneCh == null)) return "fail: char none==null"

    return "OK"
}
