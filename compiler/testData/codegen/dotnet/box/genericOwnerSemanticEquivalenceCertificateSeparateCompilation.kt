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

// The same separate-assembly proof with one method parameter forces the objective consumer to
// decode and authenticate a real MethodSpec<!0> call rather than only direct MemberRef tokens.
public interface SemanticEquivalenceMethodCertificateProducer<out T> {
    public fun <R> value(marker: R): T
}

public class SemanticEquivalenceMethodCertificateValue<T>(private val stored: T) :
    SemanticEquivalenceMethodCertificateProducer<T> {
    public override fun <R> value(marker: R): T = stored

    public fun widenedValue(marker: Any?): Any? {
        val widened: SemanticEquivalenceMethodCertificateProducer<Any?> = this
        return widened.value(marker)
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

private fun externalMethodIntValue(
    value: SemanticEquivalenceMethodCertificateValue<Int>,
): Any? {
    val widened: SemanticEquivalenceMethodCertificateProducer<Any?> = value
    return widened.value("external-int")
}

private fun externalMethodStringValue(
    value: SemanticEquivalenceMethodCertificateValue<String>,
): Any? {
    val widened: SemanticEquivalenceMethodCertificateProducer<Any?> = value
    return widened.value(17)
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

    val methodInt = SemanticEquivalenceMethodCertificateValue(43)
    if (methodInt.value("exact") != 43 || methodInt.widenedValue(19) != 43 ||
        externalMethodIntValue(methodInt) != 43
    ) {
        return "FAIL: method-generic Int"
    }
    val methodString = SemanticEquivalenceMethodCertificateValue("method-certificate")
    if (methodString.value(23) != "method-certificate" ||
        methodString.widenedValue("local") != "method-certificate" ||
        externalMethodStringValue(methodString) != "method-certificate"
    ) {
        return "FAIL: method-generic String"
    }

    return "OK"
}
