// DOTNET_EXPORT: demo.apply=applyDelegate
// DOTNET_EXPORT: demo.invokeWithDefaults=invokeWithDefaultsDelegate
// DOTNET_EXPORT: demo.invokeNullableDefault=invokeNullableDefaultDelegate
// DOTNET_EXPORT: demo.makeAdder=makeAdderDelegate
// DOTNET_EXPORT: demo.nonTrailing=nonTrailingDelegate
// DOTNET_EXPORT: demo.extensionApply=extensionApplyDelegate

package demo

fun apply(
    prefix: Int,
    callback: (Int) -> Int = { it + prefix },
    value: Int = prefix + 1,
): Int = callback(value)

fun invokeWithDefaults(
    callback: (Int) -> Int = { it + 2 },
    value: Int = 40,
): Int = callback(value)

fun invokeNullableDefault(
    callback: (Int?) -> Int = { it ?: 5 },
    value: Int? = null,
): Int = callback(value)

fun makeAdder(offset: Int = 2): (Int) -> Int = { it + offset }

fun nonTrailing(
    callback: (Int) -> Int = { it + 1 },
    value: Int,
): Int = callback(value)

fun Int.extensionApply(
    callback: (Int) -> Int = { it + this },
    value: Int = this + 1,
): Int = callback(value)
