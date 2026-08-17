// MODULE: lib
// FILE: marker.kt
// DOTNET_REPRESENTATIVE_SOURCE: octo-tree

// Control for the physical null-write proof: unlike OctoTree.Node<T>?, bare T? has no one
// truthful CLR field carrier across value- and reference-type substitutions.
private class GenericOwnerBareNullableSlot<T> {
    private var value: T? = null
}

// MODULE: main(lib)
// FILE: main.kt

fun box(): String {
    val tree = OctoTree<Int>(2)
    tree.set(0, 0, 0, 17)
    tree.set(7, 7, 7, 23)
    if (tree.get(0, 0, 0) != 17) return "FAIL: first value"
    if (tree.get(7, 7, 7) != 23) return "FAIL: second value"
    if (tree.toString().isEmpty()) return "FAIL: empty rendering"
    return "OK"
}
