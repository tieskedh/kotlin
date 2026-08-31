// DOTNET_FOREIGN_MULTIPLE_EDGE_MEMBERLESS_INTERFACE

import Foreign.Multiple.IntSource
import Foreign.Multiple.Marker
import Foreign.Multiple.Source

fun readMultipleEdgeSource(source: IntSource): Int = source.Read()

fun retainMultipleEdgeSource(source: IntSource): Source<Int> = source

fun retainMultipleEdgeMarker(source: IntSource): Marker = source
