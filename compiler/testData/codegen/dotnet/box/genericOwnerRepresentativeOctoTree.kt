// DOTNET_REPRESENTATIVE_SOURCE: octo-tree

fun box(): String {
    val tree = OctoTree<Boolean>(2)
    val extent = 2 shl tree.depth
    var x = 0
    while (x < extent) {
        var y = 0
        while (y < extent) {
            var z = 0
            while (z < extent) {
                tree.set(x, y, z, (z + extent * y + extent * extent * x) % 2 == 0)
                z++
            }
            y++
        }
        x++
    }

    var matched = 0
    x = 0
    while (x < extent) {
        var y = 0
        while (y < extent) {
            var z = 0
            while (z < extent) {
                val expected = (z + extent * y + extent * extent * x) % 2 == 0
                if (tree.get(x, y, z) == expected) matched++
                z++
            }
            y++
        }
        x++
    }
    if (matched != extent * extent * extent) return "FAIL: matched $matched"
    if (tree.toString().isEmpty()) return "FAIL: empty rendering"
    return "OK"
}
