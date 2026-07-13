// Reference identity between SIBLING interface types sharing a super-interface widens the `ceq`
// operand slots to the FIRST common supertype of the left operand's breadth-first supertype
// walk (`sibling` compares an A against a B through their shared `Root`). Two FINAL sibling
// CLASSES are not expressible operands — the frontend rejects them with
// EQUALITY_NOT_APPLICABLE (empty intersection type) — so sibling interface views are the
// user-reachable shape of the widening arm. Identity between two UNRELATED interface types
// stays rejected loudly — their only common supertype would need an Any model — so `unrelated`
// is skipped with a warning and absent from the output while `main` (which never calls it)
// survives. `Marker` is a SEALED interface: sealedness is pure frontend-enforced metadata (JVM
// precedent — the JVM backend emits an ordinary interface too), so it is deliberately accepted
// and emitted as a plain `.class interface`, pinned here including dispatch through the
// sealed-interface-typed local.
interface Root

interface A : Root

interface B : Root

interface Other

sealed interface Marker {
    fun tag(): Int
}

class LeafA : A

class LeafB : B

class Marked : Marker {
    override fun tag(): Int = 7
}

fun sibling(a: A, b: B): Boolean = a === b

fun unrelated(r: Root, o: Other): Boolean = r === o

fun main() {
    println(sibling(LeafA(), LeafB()))
    val m: Marker = Marked()
    println(m.tag())
}
