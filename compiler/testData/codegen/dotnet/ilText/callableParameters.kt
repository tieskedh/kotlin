// DOTNET_EXPORT: demo.callZero=callZeroDelegate
// DOTNET_EXPORT: demo.callOne=callOneDelegate
// DOTNET_EXPORT: demo.callTwo=callTwoDelegate
// DOTNET_EXPORT: demo.callNullable=callNullableDelegate
// DOTNET_EXPORT: demo.runZero=runZeroAction
// DOTNET_EXPORT: demo.runOne=runOneAction
// DOTNET_EXPORT: demo.runTwo=runTwoAction
// DOTNET_EXPORT: demo.echoOne=echoOneDelegate
// DOTNET_EXPORT: demo.echoUnitOne=echoUnitOneAction

package demo

fun callZero(callback: () -> Int): Int = callback()

fun callOne(callback: (Int) -> Int, value: Int): Int = callback(value)

fun callTwo(callback: (Int, Int) -> Int, left: Int, right: Int): Int = callback(left, right)

fun callNullable(callback: (Int?) -> Int?, value: Int?): Int? = callback(value)

fun runZero(callback: () -> Unit) {
    callback()
}

fun runOne(callback: (Int) -> Unit, value: Int) {
    callback(value)
}

fun runTwo(callback: (Int, Int) -> Unit, left: Int, right: Int) {
    callback(left, right)
}

fun echoOne(callback: (Int) -> Int): (Int) -> Int = callback

fun echoUnitOne(callback: (Int) -> Unit): (Int) -> Unit = callback
