// DOTNET_GENERIC_OWNER_FOREIGN_OVERRIDE_SEPARATE_PROBE

// MODULE: lib
// FILE: lib.kt

public open class RehearsalSeparateStore<out T>(initial: T) {
    private var value: T = initial

    public open fun read(): T = value

    public fun write(value: @UnsafeVariance T) {
        this.value = value
    }
}

public class RehearsalSeparateReader {
    public fun read(store: RehearsalSeparateStore<Any?>): Any? = store.read()
}

public interface RehearsalSeparateProducer<out T> {
    public fun produce(): T
}

public interface RehearsalSeparateSecondaryProducer<out T> {
    public fun produceSecondary(): T
}

public interface RehearsalSeparateLocalIntersectionProducer<out T> :
    RehearsalSeparateProducer<T>,
    RehearsalSeparateSecondaryProducer<T>

public class RehearsalSeparateProducerReader {
    public fun read(producer: RehearsalSeparateProducer<Any?>): Any? = producer.produce()

    public fun same(producer: RehearsalSeparateProducer<Any?>, expected: Any?): Boolean =
        producer === expected
}

public class RehearsalSeparateSecondaryProducerReader {
    public fun read(producer: RehearsalSeparateSecondaryProducer<Any?>): Any? =
        producer.produceSecondary()
}

public class RehearsalSeparateLocalIntersectionProducerValue<T>(private val value: T) :
    RehearsalSeparateLocalIntersectionProducer<T> {
    public override fun produce(): T = value

    public override fun produceSecondary(): T = value
}

// MODULE: middle(lib)
// FILE: middle.kt

public open class RehearsalSeparateKotlinOverrideStore<T>(initial: T) :
    RehearsalSeparateStore<T>(initial) {
    public override fun read(): T = super.read()
}

public interface RehearsalSeparateChildProducer<out T> :
    RehearsalSeparateProducer<T>,
    RehearsalSeparateSecondaryProducer<T> {
    public fun produceChild(): T
}

public class RehearsalSeparateChildProducerReader {
    public fun read(producer: RehearsalSeparateChildProducer<Any?>): Any? =
        producer.produceChild()
}

public interface RehearsalSeparateMemberChildProducer<out T> :
    RehearsalSeparateProducer<T> {
    public fun produceMemberChild(): T
}

public class RehearsalSeparateMemberChildProducerReader {
    public fun read(producer: RehearsalSeparateMemberChildProducer<Any?>): Any? =
        producer.produceMemberChild()
}

public class RehearsalSeparateProducerValue<T>(private val value: T) :
    RehearsalSeparateProducer<T> {
    public override fun produce(): T = value
}

public class RehearsalSeparateChildProducerValue<T>(private val value: T) :
    RehearsalSeparateChildProducer<T> {
    public override fun produce(): T = value

    public override fun produceSecondary(): T = value

    public override fun produceChild(): T = value
}

public class RehearsalSeparateMemberChildProducerValue<T>(private val value: T) :
    RehearsalSeparateMemberChildProducer<T> {
    public override fun produce(): T = value

    public override fun produceMemberChild(): T = value
}

// MODULE: main(middle)
// FILE: main.kt

fun box(): String {
    val store = RehearsalSeparateKotlinOverrideStore("kotlin-middle")
    if (RehearsalSeparateReader().read(store) != "kotlin-middle") {
        return "fail: separate Kotlin override"
    }

    val exact = RehearsalSeparateKotlinOverrideStore(11)
    val widened: RehearsalSeparateStore<Any?> = exact
    widened.write("semantic")
    if (RehearsalSeparateReader().read(widened) != "semantic") {
        return "fail: separate raw widened read"
    }
    try {
        exact.read() + 1
        return "fail: separate typed incompatible read"
    } catch (_: ClassCastException) {
        // Only this actual typed use is a checked boundary.
    }
    widened.write(19)
    if (exact.read() != 19) return "fail: separate compatible recovery"

    val exactProducer: RehearsalSeparateProducer<Int> = RehearsalSeparateProducerValue(31)
    if (exactProducer.produce() != 31) return "fail: separate exact producer"
    val broadProducer: RehearsalSeparateProducer<Any?> = exactProducer
    if (RehearsalSeparateProducerReader().read(broadProducer) != 31) {
        return "fail: separate broad producer"
    }
    if (broadProducer !== exactProducer) return "fail: separate producer identity"

    val exactChild: RehearsalSeparateChildProducer<Int> =
        RehearsalSeparateChildProducerValue(47)
    if (exactChild.produce() != 47) return "fail: separate exact child producer"
    if (exactChild.produceSecondary() != 47) {
        return "fail: separate exact secondary child producer"
    }
    if (exactChild.produceChild() != 47) {
        return "fail: separate exact child-owned producer"
    }
    val broadChild: RehearsalSeparateChildProducer<Any?> = exactChild
    if (RehearsalSeparateProducerReader().read(broadChild) != 47) {
        return "fail: separate broad child producer"
    }
    if (RehearsalSeparateSecondaryProducerReader().read(broadChild) != 47) {
        return "fail: separate broad secondary child producer"
    }
    if (RehearsalSeparateChildProducerReader().read(broadChild) != 47) {
        return "fail: separate broad child-owned producer"
    }
    if (broadChild !== exactChild) return "fail: separate child producer identity"

    val exactMemberChild: RehearsalSeparateMemberChildProducer<Int> =
        RehearsalSeparateMemberChildProducerValue(59)
    if (exactMemberChild.produce() != 59) {
        return "fail: separate exact member-child root producer"
    }
    if (exactMemberChild.produceMemberChild() != 59) {
        return "fail: separate exact member-child-owned producer"
    }
    val broadMemberChild: RehearsalSeparateMemberChildProducer<Any?> = exactMemberChild
    if (RehearsalSeparateProducerReader().read(broadMemberChild) != 59) {
        return "fail: separate broad member-child root producer"
    }
    if (RehearsalSeparateMemberChildProducerReader().read(broadMemberChild) != 59) {
        return "fail: separate broad member-child-owned producer"
    }
    if (broadMemberChild !== exactMemberChild) {
        return "fail: separate member-child producer identity"
    }

    val exactLocalIntersection: RehearsalSeparateLocalIntersectionProducer<Int> =
        RehearsalSeparateLocalIntersectionProducerValue(53)
    if (exactLocalIntersection.produce() != 53) {
        return "fail: separate exact local-intersection producer"
    }
    if (exactLocalIntersection.produceSecondary() != 53) {
        return "fail: separate exact local-intersection secondary producer"
    }
    val broadLocalIntersection: RehearsalSeparateLocalIntersectionProducer<Any?> =
        exactLocalIntersection
    if (RehearsalSeparateProducerReader().read(broadLocalIntersection) != 53) {
        return "fail: separate broad local-intersection producer"
    }
    if (RehearsalSeparateSecondaryProducerReader().read(broadLocalIntersection) != 53) {
        return "fail: separate broad local-intersection secondary producer"
    }
    if (broadLocalIntersection !== exactLocalIntersection) {
        return "fail: separate local-intersection producer identity"
    }

    return "OK"
}
