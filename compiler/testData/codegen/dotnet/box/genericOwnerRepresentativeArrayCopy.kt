// DOTNET_REPRESENTATIVE_SOURCE: array-copy

package org.jetbrains.ring

fun box(): String {
    val values = ArrayCopyBenchmark.CustomArray<Int>()
    var index = 0
    while (index < 512) {
        if (!values.add(0, index)) return "FAIL: add $index"
        index += 1
    }
    return "OK"
}
