// The member pre-pass gates IL FIELD identity like method identity: the backing field of a user
// property named INSTANCE whose type maps to the object's own class (nullability erases) collides
// with the synthesized INSTANCE singleton field — same IL name and field signature, staticness and
// visibility are flags, not identity — which ilasm rejects as a duplicate field declaration, so
// the whole object is rejected with a warning and only the file facade is emitted. The identity
// key is name plus mapped IL type: a differently-typed INSTANCE property is a legal CLR shape, so
// B keeps both its class-typed singleton field and the int32 backing field.
object A {
    val INSTANCE: A? = null
}

object B {
    val INSTANCE = 1
}

fun main() {
    println(B.INSTANCE)
}
