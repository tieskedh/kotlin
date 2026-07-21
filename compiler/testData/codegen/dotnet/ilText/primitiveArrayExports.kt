// DOTNET_EXPORT: demo.mutate(kotlin.IntArray)=Mutate
// DOTNET_EXPORT: demo.maybe(kotlin.IntArray?)=Maybe
// DOTNET_EXPORT: demo.same(kotlin.IntArray,kotlin.IntArray)=Same
// DOTNET_EXPORT: demo.generic(kotlin.Array<kotlin.Int>)=Generic
// DOTNET_EXPORT_PROPERTY: demo.buffer=Buffer

package demo

var buffer: IntArray = intArrayOf(1, 2)

fun mutate(values: IntArray): IntArray {
    values[0] = values[0] + 1
    return values
}

fun maybe(values: IntArray?): IntArray? = values

fun same(first: IntArray, second: IntArray): Boolean = first === second

fun generic(values: Array<Int>): Array<Int> = values

fun main() {
    println("primitive-array-exports")
}
