// DOTNET_GENERIC_OWNER_METHOD_GENERIC_SEALED_EMISSION_PROBE

// A method-generic producer keeps its method parameter independently on every physical
// MethodDef in the natural/semantic family. This probe is deliberately declaration-only:
// concrete MethodSpec call routing is a later operation-routing epoch, while this probe certifies
// the already-emitted MethodDefs, GenericParam rows, MethodImpls, and identity !!0 forwarding.

interface MethodGenericProducer<out T> {
    fun <R> produce(marker: R): T
}

private class MethodGenericFirstView<T>(private val value: T) : MethodGenericProducer<T> {
    override fun <R> produce(marker: R): T = value
}

private class MethodGenericSecondView<T>(private val value: T) : MethodGenericProducer<T> {
    override fun <R> produce(marker: R): T = value
}

fun box(): String = "OK"
