// Initialization order: member property initializers and init blocks run in
// declaration order inside the primary constructor; a secondary constructor
// delegating via this(...) runs its own body only after the delegation.

class C(suffix: String) {
    var order: String = ""
    val a: Int = 1

    init {
        order = order + "1"
    }

    val b: Int = 2

    init {
        order = order + "2" + suffix
    }

    constructor() : this("-") {
        order = order + "s"
    }
}

fun box(): String {
    val primary = C("x")
    if (primary.order != "12x") return "fail primary: " + primary.order
    if (primary.a != 1 || primary.b != 2) return "fail fields: " + primary.a + "," + primary.b
    val secondary = C()
    if (secondary.order != "12-s") return "fail secondary: " + secondary.order
    return "OK"
}
