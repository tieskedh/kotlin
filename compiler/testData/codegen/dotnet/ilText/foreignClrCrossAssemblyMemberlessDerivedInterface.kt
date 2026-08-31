// DOTNET_FOREIGN_CROSS_ASSEMBLY_MEMBERLESS_INTERFACE

import Foreign.IntSource
import Foreign.Source

fun readCrossAssemblySource(source: IntSource): Int = source.Read()

fun retainCrossAssemblySource(source: IntSource): Source<Int> = source
