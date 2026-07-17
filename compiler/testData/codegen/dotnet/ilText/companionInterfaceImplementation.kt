// A companion may implement supported interfaces: it remains one ordinary singleton object, and
// the enclosing class still owns only its field and .cctor. Concrete companion base classes stay
// outside the supported model because they require constructor chaining beyond the object slice.
interface Marker

class WithMarkedCompanion {
    companion object : Marker
}

fun main() {
    println("supported")
}
