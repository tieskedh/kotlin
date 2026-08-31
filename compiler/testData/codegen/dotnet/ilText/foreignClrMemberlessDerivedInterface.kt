// DOTNET_FOREIGN_MEMBERLESS_INTERFACE

import Foreign.IntSource
import Foreign.Source

fun readMemberlessSource(source: IntSource): Int = source.Read()

fun retainMemberlessSource(source: IntSource): Source<Int> = source
