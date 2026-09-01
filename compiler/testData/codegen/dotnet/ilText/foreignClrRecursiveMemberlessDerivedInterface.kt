// DOTNET_FOREIGN_RECURSIVE_MEMBERLESS_INTERFACE

import Foreign.Recursive.IntSource
import Foreign.Recursive.Source

fun readRecursiveSource(source: IntSource): Int = source.Read()

fun readRecursiveIntOverload(source: IntSource): Int = source.Read(1)

fun readRecursiveStringOverload(source: IntSource): Int = source.Read("selector")

fun retainRecursiveSource(source: IntSource): Source<Int> = source

fun readSelectedRecursiveSource(source: IntSource): Int {
    val selected: Source<Int> = source
    return selected.Read()
}
