// DOTNET_FOREIGN_DEEP_MEMBERLESS_INTERFACE

import Foreign.Deep.IntSource
import Foreign.Deep.Source

fun readDeepSource(source: IntSource): Int = source.Read()

fun retainDeepSource(source: IntSource): Source<Int> = source

fun readSelectedDeepSource(source: IntSource): Int {
    val selected: Source<Int> = source
    return selected.Read()
}
