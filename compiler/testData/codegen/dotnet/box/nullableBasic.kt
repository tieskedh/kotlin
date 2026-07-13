// Null flow through params/returns/fields/locals, safe call, elvis, the '!!' success path,
// smartcast unwrap — reference and primitive flavors of the hybrid Nullable<T> representation.
class Node(val value: Int?, val next: Node?) {
    fun weight(): Int = value ?: 0
}

class Tally(var total: Int) {
    fun add(n: Int) {
        total = total + n
    }
}

var counter: Int? = null

fun addMaybe(a: Int?, b: Int?): Int? {
    if (a == null) return b
    if (b == null) return a
    return a + b
}

fun box(): String {
    val five: Int? = 5
    val none: Int? = null
    if (addMaybe(five, none) != 5) return "fail: addMaybe(5, null)"
    if (addMaybe(none, none) != null) return "fail: addMaybe(null, null)"
    if (addMaybe(2, 3) != 5) return "fail: addMaybe(2, 3)"

    val chain = Node(7, Node(null, null))
    if (chain.weight() != 7) return "fail: weight"
    if (chain.next?.weight() != 0) return "fail: next weight"
    if (chain.next?.next?.weight() != null) return "fail: chain end"
    val tailValue: Int? = chain.next?.value
    if (tailValue != null) return "fail: tail value"
    if ((chain.next?.value ?: -1) != -1) return "fail: elvis after safe call"

    if (counter != null) return "fail: counter initial"
    counter = 3
    if ((counter ?: 0) != 3) return "fail: counter set"
    counter = null
    if ((counter ?: -9) != -9) return "fail: counter cleared"

    // statement-position safe calls: run on a value, no-op on null
    val tally: Tally? = Tally(1)
    tally?.add(4)
    val missing: Tally? = null
    missing?.add(100)
    if (tally!!.total != 5) return "fail: statement-position safe call"

    // safe call on a nullable-PRIMITIVE receiver
    val bumped: Int? = five?.plus(1)
    if (bumped != 6) return "fail: safe call on Int? receiver"
    if (none?.plus(1) != null) return "fail: safe call on null Int? receiver"

    val sure: Int? = 41
    if (sure!! + 1 != 42) return "fail: !! primitive"
    val name: String? = "ok"
    if (name!! != "ok") return "fail: !! reference"

    val maybe: Long? = 10L
    if (maybe != null) {
        if (maybe + 5L != 15L) return "fail: smartcast long"
    } else {
        return "fail: maybe was null"
    }

    val flag: Boolean? = true
    if (flag != true) return "fail: flag"
    val ch: Char? = 'k'
    if ((ch ?: 'x') != 'k') return "fail: char elvis"
    val d: Double? = null
    if ((d ?: 1.5) != 1.5) return "fail: double elvis"

    return "OK"
}
