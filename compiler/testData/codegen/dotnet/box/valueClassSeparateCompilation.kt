// MODULE: lib
// FILE: lib.kt

package values

value class Token(val value: String)

value class Count<T : Int>(val value: T)

fun makeToken(value: String): Token = Token(value)

fun eraseToken(token: Token): Any = token

fun readToken(value: Any): String = (value as Token).value

fun <T : Int> makeCount(value: T): Count<T> = Count(value)

fun <T : Int> eraseCount(count: Count<T>): Any = count

fun <T : Int> readCount(value: Any): T = (value as Count<T>).value

// MODULE: main(lib)
// FILE: main.kt

import values.Token
import values.Count
import values.eraseCount
import values.eraseToken
import values.makeCount
import values.makeToken
import values.readCount
import values.readToken

fun box(): String {
    val direct = Token("direct")
    if (direct.value != "direct") return "direct consumer construction failed"

    val consumerBox: Any = makeToken("consumer")
    val consumerRoundTrip: Token = consumerBox as Token
    if (consumerRoundTrip.value != "consumer") return "consumer round trip failed"

    val producerBox = eraseToken(makeToken("OK"))
    if (readToken(producerBox) != "OK") return "producer token round trip failed"

    val directCount = Count(4)
    if (directCount.value != 4) return "direct generic consumer construction failed"

    val countBox: Any = eraseCount(makeCount(5))
    val countRoundTrip: Count<Int> = countBox as Count<Int>
    if (countRoundTrip.value != 5) return "generic consumer round trip failed"
    if (readCount<Int>(countBox) != 5) return "generic producer round trip failed"

    return "OK"
}
