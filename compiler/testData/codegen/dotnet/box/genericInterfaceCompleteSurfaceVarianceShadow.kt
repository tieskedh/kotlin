// DOTNET_GENERIC_INTERFACE_COMPLETE_SURFACE_VARIANCE_SHADOW_PROBE

private interface OutputOnlySurface<out T> {
    fun readOutput(): T
}

private interface InputBearingSurface<out T> {
    fun readInputBearing(): T

    fun acceptsInputBearing(candidate: @UnsafeVariance T): Boolean
}

private interface PerParameterSurface<out A, out B> {
    fun readSecond(): B

    fun acceptsFirst(candidate: @UnsafeVariance A): Boolean
}

private interface InputParentSurface<out T> {
    fun acceptsParent(candidate: @UnsafeVariance T): Boolean
}

private interface InputChildSurface<out T> : InputParentSurface<T> {
    fun readChild(): T
}

fun box(): String = "OK"
