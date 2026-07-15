// TARGET_BACKEND: DOTNET

private data class DefaultData<T>(val value: T, val label: String = "default")

fun box(): String {
    val default = DefaultData(3)
    if (default.label != "default" || default.copy(value = 4) != DefaultData(4)) {
        return "fail 1: constructor/copy defaults"
    }

    return "OK"
}
