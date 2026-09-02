// DOTNET_GENERIC_OWNER_SEMANTIC_EQUIVALENCE_CERTIFICATE_PROBE

// MODULE: lib
// FILE: certificate.kt

public interface SemanticEquivalenceCertificateProducer<out T> {
    public fun value(): T
}

public class SemanticEquivalenceCertificateValue<T>(private val stored: T) :
    SemanticEquivalenceCertificateProducer<T> {
    public override fun value(): T = stored

    public fun widenedValue(): Any? {
        val widened: SemanticEquivalenceCertificateProducer<Any?> = this
        return widened.value()
    }
}

// MODULE: main(lib)
// FILE: main.kt

private fun externalIntValue(value: SemanticEquivalenceCertificateValue<Int>): Any? {
    val widened: SemanticEquivalenceCertificateProducer<Any?> = value
    return widened.value()
}

private fun externalStringValue(value: SemanticEquivalenceCertificateValue<String>): Any? {
    val widened: SemanticEquivalenceCertificateProducer<Any?> = value
    return widened.value()
}

fun box(): String {
    val intImplementation = SemanticEquivalenceCertificateValue(41)
    val intValue: SemanticEquivalenceCertificateProducer<Int> =
        intImplementation
    if (intValue.value() != 41) return "FAIL: Int"
    if (intImplementation.widenedValue() != 41) return "FAIL: local widened Int"
    if (externalIntValue(intImplementation) != 41) return "FAIL: external widened Int"

    val stringImplementation = SemanticEquivalenceCertificateValue("certificate")
    val stringValue: SemanticEquivalenceCertificateProducer<String> =
        stringImplementation
    if (stringValue.value() != "certificate") return "FAIL: String"
    if (stringImplementation.widenedValue() != "certificate") return "FAIL: local widened String"
    if (externalStringValue(stringImplementation) != "certificate") {
        return "FAIL: external widened String"
    }

    return "OK"
}
