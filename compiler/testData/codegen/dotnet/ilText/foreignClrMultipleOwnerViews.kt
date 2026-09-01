// DOTNET_FOREIGN_MULTIPLE_OWNER_VIEW_INTERFACE

import Foreign.Dual.DualSource
import Foreign.Dual.Source

fun retainDualInt(source: DualSource): Source<Int> = source

fun retainDualBoolean(source: DualSource): Source<Boolean> = source

fun readDualInt(source: DualSource): Int {
    val selected: Source<Int> = source
    return selected.Read()
}

fun readDualBoolean(source: DualSource): Boolean {
    val selected: Source<Boolean> = source
    return selected.Read()
}
