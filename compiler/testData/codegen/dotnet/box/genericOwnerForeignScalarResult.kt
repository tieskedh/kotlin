// DOTNET_GENERIC_OWNER_FOREIGN_SCALAR_RESULT_PROBE
// MODULE: lib
// FILE: decisions.kt

package generic.owner.scalar

interface Decision<out E> {
    fun decide(candidate: @UnsafeVariance E): Boolean
    fun score(candidate: @UnsafeVariance E): Long
    fun touch(candidate: @UnsafeVariance E)
}

open class KotlinDecision<D> : Decision<Any?> {
    override fun decide(candidate: Any?): Boolean = false
    override fun score(candidate: Any?): Long = -1L
    override fun touch(candidate: Any?) {}
}

fun decideStar(decision: KotlinDecision<*>, candidate: Any?): Boolean = decision.decide(candidate)
fun decideExact(decision: KotlinDecision<Int>, candidate: Any?): Boolean = decision.decide(candidate)
fun decideInterface(decision: Decision<Any?>, candidate: Any?): Boolean = decision.decide(candidate)
fun scoreStar(decision: KotlinDecision<*>, candidate: Any?): Long = decision.score(candidate)
fun scoreInterface(decision: Decision<Any?>, candidate: Any?): Long = decision.score(candidate)
fun touchStar(decision: KotlinDecision<*>, candidate: Any?) = decision.touch(candidate)
fun touchInterface(decision: Decision<Any?>, candidate: Any?) = decision.touch(candidate)

// MODULE: middle(lib)
// FILE: inherited.kt

package generic.owner.scalar

interface OtherDecision<out E> {
    fun decide(candidate: @UnsafeVariance E): Boolean
}

open class InheritedDecision<D> : KotlinDecision<D>(), OtherDecision<Any?>

fun decideOther(decision: OtherDecision<Any?>, candidate: Any?): Boolean = decision.decide(candidate)

open class OverridingDecision<D> : KotlinDecision<D>() {
    override fun decide(candidate: Any?): Boolean = false
    override fun score(candidate: Any?): Long = -2L
    override fun touch(candidate: Any?) {}
    fun parentScore(candidate: Any?): Long = super.score(candidate)
}

// MODULE: main(lib, middle)
// FILE: main.kt

package generic.owner.scalar

fun box(): String {
    val decision = KotlinDecision<Int>()
    if (decideStar(decision, "value") || decideExact(decision, 41) || decideInterface(decision, null)) return "base"
    if (scoreStar(decision, null) != -1L || scoreInterface(OverridingDecision<Int>(), null) != -2L) return "score"
    touchStar(decision, 43)
    touchInterface(InheritedDecision<String>(), "touch")
    return "OK"
}
