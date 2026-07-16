// DOTNET_EXPORT: demo.makeZero=makeZeroDelegate
// DOTNET_EXPORT: demo.makeOne=makeOneDelegate
// DOTNET_EXPORT: demo.makeTwo=makeTwoDelegate
// DOTNET_EXPORT: demo.makeNullable=makeNullableDelegate
// DOTNET_EXPORT: demo.makeUnitZero=makeUnitZeroAction
// DOTNET_EXPORT: demo.makeUnitOne=makeUnitOneAction
// DOTNET_EXPORT: demo.makeUnitTwo=makeUnitTwoAction

package demo

class State(var value: Int)

fun makeZero(): () -> Int = { 7 }

fun makeOne(offset: Int): (Int) -> Int = { value -> value + offset }

fun makeTwo(scale: Int): (Int, Int) -> Int = { left, right -> (left + right) * scale }

fun makeNullable(): (Int?) -> Int? = { value -> value }

fun makeUnitZero(state: State): () -> Unit = { state.value = 10 }

fun makeUnitOne(state: State): (Int) -> Unit = { value -> state.value = value }

fun makeUnitTwo(state: State): (Int, Int) -> Unit = { left, right -> state.value = left + right }
