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

public interface RehearsalSeparateConsumer<in T> {
    public fun consume(value: T)
}

public interface RehearsalSeparateInvariantProducer<T> {
    public fun produceInvariant(): T
}

public interface RehearsalSeparateInvariantCell<T> {
    public fun readCell(): T

    public fun writeCell(value: T)
}

public interface RehearsalSeparateInvariantPropertyCell<T> {
    public var propertyCellValue: T
}

public fun rehearsalSeparateStarInvariantProduce(
    producer: RehearsalSeparateInvariantProducer<*>,
): Any? = producer.produceInvariant()

public fun rehearsalSeparateProjectedInvariantProduce(
    producer: RehearsalSeparateInvariantProducer<out Any?>,
): Any? = producer.produceInvariant()

public fun rehearsalSeparateStarInvariantCellRead(
    cell: RehearsalSeparateInvariantCell<*>,
): Any? = cell.readCell()

public fun rehearsalSeparateProjectedInvariantCellRead(
    cell: RehearsalSeparateInvariantCell<out Any?>,
): Any? = cell.readCell()

public fun rehearsalSeparateProjectedInvariantCellWrite(
    cell: RehearsalSeparateInvariantCell<in String>,
    value: String,
) {
    cell.writeCell(value)
}

public fun rehearsalSeparateProjectedInvariantCellWriteResult(
    cell: RehearsalSeparateInvariantCell<in String>,
    value: String,
): Any? = cell.writeCell(value)

public fun <T> rehearsalSeparateOpenInvariantCellIdentity(
    cell: RehearsalSeparateInvariantCell<T>,
): RehearsalSeparateInvariantCell<T> = cell

public fun rehearsalSeparateStarInvariantPropertyCellRead(
    cell: RehearsalSeparateInvariantPropertyCell<*>,
): Any? = cell.propertyCellValue

public fun rehearsalSeparateProjectedInvariantPropertyCellRead(
    cell: RehearsalSeparateInvariantPropertyCell<out Any?>,
): Any? = cell.propertyCellValue

public fun rehearsalSeparateProjectedInvariantPropertyCellWrite(
    cell: RehearsalSeparateInvariantPropertyCell<in String>,
    value: String,
) {
    cell.propertyCellValue = value
}

public fun <T> rehearsalSeparateOpenInvariantPropertyCellIdentity(
    cell: RehearsalSeparateInvariantPropertyCell<T>,
): RehearsalSeparateInvariantPropertyCell<T> = cell

public class RehearsalSeparateInvariantCellValue<T>(private var value: T) :
    RehearsalSeparateInvariantCell<T> {
    public override fun readCell(): T = value

    public override fun writeCell(value: T) {
        this.value = value
    }
}

public class RehearsalSeparateInvariantPropertyCellValue<T>(
    override var propertyCellValue: T,
) : RehearsalSeparateInvariantPropertyCell<T>

public class RehearsalSeparateConsumerValue<T>(initial: T) :
    RehearsalSeparateConsumer<T> {
    private var value: T = initial

    public override fun consume(value: T) {
        this.value = value
    }

    public fun read(): T = value
}

public class RehearsalSeparateConsumerReader {
    public fun consume(consumer: RehearsalSeparateConsumer<Int>, value: Int) {
        consumer.consume(value)
    }

    public fun identity(
        consumer: RehearsalSeparateConsumer<Int>,
    ): RehearsalSeparateConsumer<Int> = consumer

    public fun same(consumer: RehearsalSeparateConsumer<Int>, expected: Any?): Boolean =
        consumer === expected
}

public interface RehearsalSeparateLocalIntersectionProducer<out T> :
    RehearsalSeparateProducer<T>,
    RehearsalSeparateSecondaryProducer<T>

public class RehearsalSeparateProducerReader {
    public fun read(producer: RehearsalSeparateProducer<Any?>): Any? = producer.produce()

    public fun same(producer: RehearsalSeparateProducer<Any?>, expected: Any?): Boolean =
        producer === expected
}

public class RehearsalSeparateNestedBox<T>(initial: T) {
    private var value: T = initial

    public fun read(): T = value

    public fun write(value: T) {
        this.value = value
    }
}

public open class RehearsalSeparateNestedAnimal(public val label: String)

public class RehearsalSeparateNestedCat(label: String) :
    RehearsalSeparateNestedAnimal(label)

public fun rehearsalSeparateBroadProducerBox(
    producer: RehearsalSeparateProducer<Any?>,
): RehearsalSeparateNestedBox<RehearsalSeparateProducer<Any?>> =
    RehearsalSeparateNestedBox(producer)

public fun rehearsalSeparateExactProducerBox(
    producer: RehearsalSeparateProducer<String>,
): RehearsalSeparateNestedBox<RehearsalSeparateProducer<String>> =
    RehearsalSeparateNestedBox(producer)

public fun rehearsalSeparateComparableProducerBox(
    producer: RehearsalSeparateProducer<Comparable<Int>>,
): RehearsalSeparateNestedBox<RehearsalSeparateProducer<Comparable<Int>>> =
    RehearsalSeparateNestedBox(producer)

public fun rehearsalSeparateAnimalProducerBox(
    producer: RehearsalSeparateProducer<RehearsalSeparateNestedAnimal>,
): RehearsalSeparateNestedBox<RehearsalSeparateProducer<RehearsalSeparateNestedAnimal>> =
    RehearsalSeparateNestedBox(producer)

public fun rehearsalSeparateIntConsumerBox(
    consumer: RehearsalSeparateConsumer<Int>,
): RehearsalSeparateNestedBox<RehearsalSeparateConsumer<Int>> =
    RehearsalSeparateNestedBox(consumer)

public fun rehearsalSeparateCatConsumerBox(
    consumer: RehearsalSeparateConsumer<RehearsalSeparateNestedCat>,
): RehearsalSeparateNestedBox<RehearsalSeparateConsumer<RehearsalSeparateNestedCat>> =
    RehearsalSeparateNestedBox(consumer)

public fun <T> rehearsalSeparateOpenProducerBox(
    producer: RehearsalSeparateProducer<T>,
): RehearsalSeparateNestedBox<RehearsalSeparateProducer<T>> =
    RehearsalSeparateNestedBox(producer)

public fun <T> rehearsalSeparateOpenConsumerBox(
    consumer: RehearsalSeparateConsumer<T>,
): RehearsalSeparateNestedBox<RehearsalSeparateConsumer<T>> =
    RehearsalSeparateNestedBox(consumer)

public fun <T> rehearsalSeparateOpenProducerBoxIdentity(
    box: RehearsalSeparateNestedBox<RehearsalSeparateProducer<T>>,
): RehearsalSeparateNestedBox<RehearsalSeparateProducer<T>> = box

public fun <T> rehearsalSeparateOpenConsumerBoxIdentity(
    box: RehearsalSeparateNestedBox<RehearsalSeparateConsumer<T>>,
): RehearsalSeparateNestedBox<RehearsalSeparateConsumer<T>> = box

public fun <T> rehearsalSeparateStableOpenNestedBoxIdentity(
    box: RehearsalSeparateNestedBox<RehearsalSeparateNestedBox<T>>,
): RehearsalSeparateNestedBox<RehearsalSeparateNestedBox<T>> = box

public fun <T> rehearsalSeparateOpenInvariantProducerBoxIdentity(
    box: RehearsalSeparateNestedBox<RehearsalSeparateInvariantProducer<T>>,
): RehearsalSeparateNestedBox<RehearsalSeparateInvariantProducer<T>> = box

public fun rehearsalSeparateProjectedInvariantProducerBox(
    producer: RehearsalSeparateInvariantProducer<out Any?>,
): RehearsalSeparateNestedBox<RehearsalSeparateInvariantProducer<out Any?>> =
    RehearsalSeparateNestedBox(producer)

public fun <T> rehearsalSeparateOpenInvariantCellBoxIdentity(
    box: RehearsalSeparateNestedBox<RehearsalSeparateInvariantCell<T>>,
): RehearsalSeparateNestedBox<RehearsalSeparateInvariantCell<T>> = box

public fun rehearsalSeparateProjectedInvariantCellBox(
    cell: RehearsalSeparateInvariantCell<out Any?>,
): RehearsalSeparateNestedBox<RehearsalSeparateInvariantCell<out Any?>> =
    RehearsalSeparateNestedBox(cell)

public fun <T> rehearsalSeparateOpenInvariantPropertyCellBoxIdentity(
    box: RehearsalSeparateNestedBox<RehearsalSeparateInvariantPropertyCell<T>>,
): RehearsalSeparateNestedBox<RehearsalSeparateInvariantPropertyCell<T>> = box

public fun rehearsalSeparateProjectedInvariantPropertyCellBox(
    cell: RehearsalSeparateInvariantPropertyCell<out Any?>,
): RehearsalSeparateNestedBox<RehearsalSeparateInvariantPropertyCell<out Any?>> =
    RehearsalSeparateNestedBox(cell)

public class RehearsalSeparateStarProducerStore(
    private val producer: RehearsalSeparateProducer<*>,
) {
    public fun read(): Any? = producer.produce()

    public fun same(expected: Any?): Boolean = producer === expected
}

public class RehearsalSeparateProducerClassifier {
    public fun isProducer(value: Any?): Boolean = value is RehearsalSeparateProducer<*>

    public fun isNotProducer(value: Any?): Boolean = value !is RehearsalSeparateProducer<*>

    public fun isNullableProducer(value: Any?): Boolean = value is RehearsalSeparateProducer<*>?

    public fun smartRead(value: Any?): Any? =
        if (value is RehearsalSeparateProducer<*>) value.produce() else null

    @Suppress("UNCHECKED_CAST")
    public fun safeSame(value: Any?): Boolean {
        val producer = value as? RehearsalSeparateProducer<String>
        return producer === value
    }

    @Suppress("UNCHECKED_CAST")
    public fun safeRead(value: Any?): String? =
        (value as? RehearsalSeparateProducer<String>)?.produce()

    @Suppress("UNCHECKED_CAST")
    public fun safeView(value: Any?): RehearsalSeparateProducer<String>? =
        value as? RehearsalSeparateProducer<String>

    public fun safeStarSame(value: Any?): Boolean {
        val producer = value as? RehearsalSeparateProducer<*>
        return producer === value
    }

    public fun checkedStarSame(value: Any?): Boolean {
        val producer = value as RehearsalSeparateProducer<*>
        return producer === value
    }

    @Suppress("UNCHECKED_CAST")
    public fun safeAnySame(value: Any?): Boolean {
        val producer = value as? RehearsalSeparateProducer<Any>
        return producer === value
    }

    @Suppress("UNCHECKED_CAST")
    public fun checkedAnySame(value: Any?): Boolean {
        val producer = value as RehearsalSeparateProducer<Any>
        return producer === value
    }

    @Suppress("UNCHECKED_CAST")
    public fun checkedStringView(value: Any?): RehearsalSeparateProducer<String> =
        value as RehearsalSeparateProducer<String>

    @Suppress("UNCHECKED_CAST")
    public fun safeNestedAnySame(value: Any?): Boolean {
        val producer = value as? RehearsalSeparateProducer<RehearsalSeparateProducer<Any>>
        return producer === value
    }

    @Suppress("UNCHECKED_CAST")
    public fun safeNestedStringSame(value: Any?): Boolean {
        val producer = value as? RehearsalSeparateProducer<RehearsalSeparateProducer<String>>
        return producer === value
    }

    public fun exactView(
        value: RehearsalSeparateProducer<String>,
    ): RehearsalSeparateProducer<String> = value
}

public class RehearsalSeparateClassifierInput {
    public fun same(
        producer: RehearsalSeparateProducer<String>,
        expected: Any?,
    ): Boolean = producer === expected

    public fun read(producer: RehearsalSeparateProducer<String>): String =
        producer.produce()
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

public interface RehearsalSeparateInvariantPropertyCellChild<T> :
    RehearsalSeparateInvariantPropertyCell<T> {
    public var childPropertyCellValue: T
}

public class RehearsalSeparateInvariantPropertyCellChildValue<T>(
    override var propertyCellValue: T,
    override var childPropertyCellValue: T,
) : RehearsalSeparateInvariantPropertyCellChild<T>

public fun rehearsalSeparateProjectedInvariantPropertyCellChildRead(
    cell: RehearsalSeparateInvariantPropertyCellChild<out Any?>,
): Any? = cell.childPropertyCellValue

public fun rehearsalSeparateProjectedInvariantPropertyCellChildWrite(
    cell: RehearsalSeparateInvariantPropertyCellChild<in String>,
    value: String,
) {
    cell.childPropertyCellValue = value
}

public fun <T> rehearsalSeparateOpenInvariantPropertyCellChildIdentity(
    cell: RehearsalSeparateInvariantPropertyCellChild<T>,
): RehearsalSeparateInvariantPropertyCellChild<T> = cell

public fun <T> rehearsalSeparateOpenInvariantPropertyCellChildBoxIdentity(
    box: RehearsalSeparateNestedBox<RehearsalSeparateInvariantPropertyCellChild<T>>,
): RehearsalSeparateNestedBox<RehearsalSeparateInvariantPropertyCellChild<T>> = box

public fun rehearsalSeparateProjectedInvariantPropertyCellChildBox(
    cell: RehearsalSeparateInvariantPropertyCellChild<out Any?>,
): RehearsalSeparateNestedBox<RehearsalSeparateInvariantPropertyCellChild<out Any?>> =
    RehearsalSeparateNestedBox(cell)

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

public class RehearsalSeparateInvariantProducerValue<T>(private val value: T) :
    RehearsalSeparateInvariantProducer<T> {
    public override fun produceInvariant(): T = value
}

public class RehearsalSeparateClassifierBoundary {
    private val classifier = RehearsalSeparateProducerClassifier()
    private val input = RehearsalSeparateClassifierInput()

    public fun same(value: Any?): Boolean = classifier.safeView(value) === value

    public fun read(value: Any?): String? = classifier.safeView(value)?.produce()

    public fun sameThroughInput(value: Any?): Boolean {
        val producer = classifier.safeView(value)!!
        return input.same(producer, value)
    }

    public fun readThroughInput(value: Any?): String {
        val producer = classifier.safeView(value)!!
        return input.read(producer)
    }
}

public class RehearsalSeparateMiddleConsumerValue<T>(initial: T) :
    RehearsalSeparateConsumer<T> {
    private var value: T = initial

    public override fun consume(value: T) {
        this.value = value
    }

    public fun read(): T = value
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
    val invariantProducer: RehearsalSeparateInvariantProducer<String> =
        RehearsalSeparateInvariantProducerValue("separate-invariant")
    if (invariantProducer.produceInvariant() != "separate-invariant" ||
        rehearsalSeparateStarInvariantProduce(invariantProducer) != "separate-invariant"
    ) {
        return "fail: separate invariant producer"
    }
    val projectedInvariant: RehearsalSeparateInvariantProducer<out Any?> = invariantProducer
    if (rehearsalSeparateProjectedInvariantProduce(projectedInvariant) !=
        "separate-invariant" || projectedInvariant !== invariantProducer
    ) {
        return "fail: separate projected invariant producer"
    }
    val projectedInvariantBox =
        rehearsalSeparateProjectedInvariantProducerBox(projectedInvariant)
    if (projectedInvariantBox.read() !== invariantProducer ||
        projectedInvariantBox.read().produceInvariant() != "separate-invariant"
    ) {
        return "fail: separate projected invariant producer box"
    }
    val projectedInvariantInt: RehearsalSeparateInvariantProducer<Int> =
        RehearsalSeparateInvariantProducerValue(43)
    projectedInvariantBox.write(projectedInvariantInt)
    if (projectedInvariantBox.read() !== projectedInvariantInt ||
        projectedInvariantBox.read().produceInvariant() != 43
    ) {
        return "fail: separate projected invariant producer box write"
    }
    val invariantCell: RehearsalSeparateInvariantCell<String> =
        RehearsalSeparateInvariantCellValue("separate-cell")
    invariantCell.writeCell("separate-exact-cell")
    if (invariantCell.readCell() != "separate-exact-cell") {
        return "fail: separate exact invariant cell"
    }
    val projectedOutputCell: RehearsalSeparateInvariantCell<out Any?> = invariantCell
    if (rehearsalSeparateProjectedInvariantCellRead(projectedOutputCell) !=
        "separate-exact-cell" ||
        rehearsalSeparateStarInvariantCellRead(projectedOutputCell) !=
        "separate-exact-cell" ||
        projectedOutputCell !== invariantCell
    ) {
        return "fail: separate projected invariant cell read"
    }
    val projectedInputCell: RehearsalSeparateInvariantCell<in String> = invariantCell
    rehearsalSeparateProjectedInvariantCellWrite(projectedInputCell, "separate-projected-cell")
    val projectedWriteResult: Any? = rehearsalSeparateProjectedInvariantCellWriteResult(
        projectedInputCell,
        "separate-projected-cell-result",
    )
    val externalProjectedWriteResult: Any? =
        projectedInputCell.writeCell("separate-external-projected-cell-result")
    if (projectedWriteResult !== Unit || externalProjectedWriteResult !== Unit ||
        invariantCell.readCell() != "separate-external-projected-cell-result" ||
        projectedInputCell !== invariantCell
    ) {
        return "fail: separate projected invariant cell write"
    }
    val broadInvariantCell: RehearsalSeparateInvariantCell<Any?> =
        RehearsalSeparateInvariantCellValue("separate-broad-cell")
    val projectedBroadInputCell: RehearsalSeparateInvariantCell<in String> =
        broadInvariantCell
    rehearsalSeparateProjectedInvariantCellWrite(
        projectedBroadInputCell,
        "separate-broad-projected-cell",
    )
    if (broadInvariantCell.readCell() != "separate-broad-projected-cell" ||
        projectedBroadInputCell !== broadInvariantCell
    ) {
        return "fail: separate broad projected invariant cell write"
    }
    if (rehearsalSeparateOpenInvariantCellIdentity(invariantCell) !== invariantCell) {
        return "fail: separate open invariant cell identity"
    }
    val invariantCellBox = RehearsalSeparateNestedBox(invariantCell)
    if (rehearsalSeparateOpenInvariantCellBoxIdentity(invariantCellBox) !== invariantCellBox) {
        return "fail: separate open invariant cell box identity"
    }
    val projectedInvariantCellBox =
        rehearsalSeparateProjectedInvariantCellBox(projectedOutputCell)
    if (projectedInvariantCellBox.read() !== invariantCell) {
        return "fail: separate projected invariant cell box identity"
    }
    val intInvariantCell: RehearsalSeparateInvariantCell<Int> =
        RehearsalSeparateInvariantCellValue(61)
    projectedInvariantCellBox.write(intInvariantCell)
    if (projectedInvariantCellBox.read() !== intInvariantCell ||
        rehearsalSeparateProjectedInvariantCellRead(projectedInvariantCellBox.read()) != 61
    ) {
        return "fail: separate projected invariant cell box mutation"
    }
    val invariantPropertyCell: RehearsalSeparateInvariantPropertyCell<String> =
        RehearsalSeparateInvariantPropertyCellValue("separate-property-cell")
    invariantPropertyCell.propertyCellValue = "separate-exact-property-cell"
    if (invariantPropertyCell.propertyCellValue != "separate-exact-property-cell") {
        return "fail: separate exact invariant property cell"
    }
    val projectedOutputPropertyCell: RehearsalSeparateInvariantPropertyCell<out Any?> =
        invariantPropertyCell
    if (rehearsalSeparateProjectedInvariantPropertyCellRead(projectedOutputPropertyCell) !=
        "separate-exact-property-cell" ||
        rehearsalSeparateStarInvariantPropertyCellRead(projectedOutputPropertyCell) !=
        "separate-exact-property-cell" ||
        projectedOutputPropertyCell !== invariantPropertyCell
    ) {
        return "fail: separate projected invariant property cell read"
    }
    val projectedInputPropertyCell: RehearsalSeparateInvariantPropertyCell<in String> =
        invariantPropertyCell
    rehearsalSeparateProjectedInvariantPropertyCellWrite(
        projectedInputPropertyCell,
        "separate-projected-property-cell",
    )
    if (invariantPropertyCell.propertyCellValue != "separate-projected-property-cell" ||
        projectedInputPropertyCell !== invariantPropertyCell
    ) {
        return "fail: separate projected invariant property cell write"
    }
    val broadInvariantPropertyCell: RehearsalSeparateInvariantPropertyCell<Any?> =
        RehearsalSeparateInvariantPropertyCellValue("separate-broad-property-cell")
    val projectedBroadInputPropertyCell: RehearsalSeparateInvariantPropertyCell<in String> =
        broadInvariantPropertyCell
    rehearsalSeparateProjectedInvariantPropertyCellWrite(
        projectedBroadInputPropertyCell,
        "separate-broad-projected-property-cell",
    )
    if (broadInvariantPropertyCell.propertyCellValue !=
        "separate-broad-projected-property-cell" ||
        projectedBroadInputPropertyCell !== broadInvariantPropertyCell
    ) {
        return "fail: separate broad projected invariant property cell write"
    }
    if (rehearsalSeparateOpenInvariantPropertyCellIdentity(invariantPropertyCell) !==
        invariantPropertyCell
    ) {
        return "fail: separate open invariant property cell identity"
    }
    val invariantPropertyCellBox = RehearsalSeparateNestedBox(invariantPropertyCell)
    if (rehearsalSeparateOpenInvariantPropertyCellBoxIdentity(invariantPropertyCellBox) !==
        invariantPropertyCellBox
    ) {
        return "fail: separate open invariant property cell box identity"
    }
    val projectedInvariantPropertyCellBox =
        rehearsalSeparateProjectedInvariantPropertyCellBox(projectedOutputPropertyCell)
    if (projectedInvariantPropertyCellBox.read() !== invariantPropertyCell) {
        return "fail: separate projected invariant property cell box identity"
    }
    val intInvariantPropertyCell: RehearsalSeparateInvariantPropertyCell<Int> =
        RehearsalSeparateInvariantPropertyCellValue(71)
    projectedInvariantPropertyCellBox.write(intInvariantPropertyCell)
    if (projectedInvariantPropertyCellBox.read() !== intInvariantPropertyCell ||
        rehearsalSeparateProjectedInvariantPropertyCellRead(
            projectedInvariantPropertyCellBox.read()
        ) != 71
    ) {
        return "fail: separate projected invariant property cell box mutation"
    }
    val invariantPropertyCellChild: RehearsalSeparateInvariantPropertyCellChild<String> =
        RehearsalSeparateInvariantPropertyCellChildValue(
            "separate-property-parent",
            "separate-property-child",
        )
    invariantPropertyCellChild.propertyCellValue = "separate-exact-property-parent"
    invariantPropertyCellChild.childPropertyCellValue = "separate-exact-property-child"
    val projectedOutputPropertyCellChild:
            RehearsalSeparateInvariantPropertyCellChild<out Any?> =
        invariantPropertyCellChild
    if (rehearsalSeparateProjectedInvariantPropertyCellRead(
            projectedOutputPropertyCellChild
        ) != "separate-exact-property-parent" ||
        rehearsalSeparateProjectedInvariantPropertyCellChildRead(
            projectedOutputPropertyCellChild
        ) != "separate-exact-property-child"
    ) {
        return "fail: separate projected invariant property child read"
    }
    val projectedInputPropertyCellChild:
            RehearsalSeparateInvariantPropertyCellChild<in String> =
        invariantPropertyCellChild
    rehearsalSeparateProjectedInvariantPropertyCellWrite(
        projectedInputPropertyCellChild,
        "separate-projected-property-parent",
    )
    rehearsalSeparateProjectedInvariantPropertyCellChildWrite(
        projectedInputPropertyCellChild,
        "separate-projected-property-child",
    )
    if (invariantPropertyCellChild.propertyCellValue !=
        "separate-projected-property-parent" ||
        invariantPropertyCellChild.childPropertyCellValue !=
        "separate-projected-property-child" ||
        rehearsalSeparateOpenInvariantPropertyCellChildIdentity(
            invariantPropertyCellChild
        ) !== invariantPropertyCellChild
    ) {
        return "fail: separate projected invariant property child write"
    }
    val broadInvariantPropertyCellChild:
            RehearsalSeparateInvariantPropertyCellChild<Any?> =
        RehearsalSeparateInvariantPropertyCellChildValue(
            "separate-broad-property-parent",
            "separate-broad-property-child",
        )
    val projectedBroadInputPropertyCellChild:
            RehearsalSeparateInvariantPropertyCellChild<in String> =
        broadInvariantPropertyCellChild
    rehearsalSeparateProjectedInvariantPropertyCellWrite(
        projectedBroadInputPropertyCellChild,
        "separate-broad-projected-property-parent",
    )
    rehearsalSeparateProjectedInvariantPropertyCellChildWrite(
        projectedBroadInputPropertyCellChild,
        "separate-broad-projected-property-child",
    )
    if (broadInvariantPropertyCellChild.propertyCellValue !=
        "separate-broad-projected-property-parent" ||
        broadInvariantPropertyCellChild.childPropertyCellValue !=
        "separate-broad-projected-property-child"
    ) {
        return "fail: separate broad projected invariant property child write"
    }
    val invariantPropertyCellChildBox =
        RehearsalSeparateNestedBox(invariantPropertyCellChild)
    if (rehearsalSeparateOpenInvariantPropertyCellChildBoxIdentity(
            invariantPropertyCellChildBox
        ) !== invariantPropertyCellChildBox
    ) {
        return "fail: separate open invariant property child box identity"
    }
    val projectedInvariantPropertyCellChildBox =
        rehearsalSeparateProjectedInvariantPropertyCellChildBox(
            projectedOutputPropertyCellChild
        )
    val intInvariantPropertyCellChild:
            RehearsalSeparateInvariantPropertyCellChild<Int> =
        RehearsalSeparateInvariantPropertyCellChildValue(83, 89)
    projectedInvariantPropertyCellChildBox.write(intInvariantPropertyCellChild)
    if (projectedInvariantPropertyCellChildBox.read() !==
        intInvariantPropertyCellChild ||
        rehearsalSeparateProjectedInvariantPropertyCellRead(
            projectedInvariantPropertyCellChildBox.read()
        ) != 83 ||
        rehearsalSeparateProjectedInvariantPropertyCellChildRead(
            projectedInvariantPropertyCellChildBox.read()
        ) != 89
    ) {
        return "fail: separate projected invariant property child box mutation"
    }
    val invariantBox = RehearsalSeparateNestedBox(invariantProducer)
    val invariantBoxIdentity =
        rehearsalSeparateOpenInvariantProducerBoxIdentity(invariantBox)
    if (invariantBoxIdentity !== invariantBox ||
        invariantBoxIdentity.read() !== invariantProducer ||
        invariantBoxIdentity.read().produceInvariant() != "separate-invariant"
    ) {
        return "fail: separate invariant open nested box identity"
    }
    val stableInnerBox = RehearsalSeparateNestedBox("stable-open")
    val stableOpenNestedBox = RehearsalSeparateNestedBox(stableInnerBox)
    val stableOpenNestedBoxIdentity =
        rehearsalSeparateStableOpenNestedBoxIdentity(stableOpenNestedBox)
    if (stableOpenNestedBoxIdentity !== stableOpenNestedBox ||
        stableOpenNestedBoxIdentity.read() !== stableInnerBox ||
        stableOpenNestedBoxIdentity.read().read() != "stable-open"
    ) {
        return "fail: separate stable open nested box identity"
    }
    val broadProducerBox = rehearsalSeparateBroadProducerBox(broadProducer)
    if (broadProducerBox.read() !== exactProducer ||
        RehearsalSeparateProducerReader().read(broadProducerBox.read()) != 31
    ) {
        return "fail: separate broad nested producer box"
    }
    val exactStringProducer: RehearsalSeparateProducer<String> =
        RehearsalSeparateProducerValue("separate-nested")
    broadProducerBox.write(exactStringProducer)
    if (broadProducerBox.read() !== exactStringProducer ||
        RehearsalSeparateProducerReader().read(broadProducerBox.read()) != "separate-nested"
    ) {
        return "fail: separate broad nested producer box write"
    }
    val exactProducerBox = rehearsalSeparateExactProducerBox(exactStringProducer)
    if (exactProducerBox.read() !== exactStringProducer ||
        exactProducerBox.read().produce() != "separate-nested"
    ) {
        return "fail: separate exact nested producer box"
    }
    val comparableProducer: RehearsalSeparateProducer<Comparable<Int>> = exactProducer
    val comparableProducerBox = rehearsalSeparateComparableProducerBox(comparableProducer)
    if (comparableProducerBox.read() !== exactProducer ||
        RehearsalSeparateProducerReader().read(comparableProducerBox.read()) != 31
    ) {
        return "fail: separate comparable nested producer box"
    }
    val catProducer: RehearsalSeparateProducer<RehearsalSeparateNestedCat> =
        RehearsalSeparateProducerValue(RehearsalSeparateNestedCat("separate-cat"))
    val animalProducer: RehearsalSeparateProducer<RehearsalSeparateNestedAnimal> = catProducer
    val animalProducerBox = rehearsalSeparateAnimalProducerBox(animalProducer)
    if (animalProducerBox.read() !== catProducer ||
        animalProducerBox.read().produce().label != "separate-cat"
    ) {
        return "fail: separate reference-only nested producer box"
    }
    val openIntProducerBox = rehearsalSeparateOpenProducerBox(exactProducer)
    if (openIntProducerBox.read() !== exactProducer ||
        openIntProducerBox.read().produce() != 31
    ) {
        return "fail: separate open value nested producer box"
    }
    val openStringProducerBox = rehearsalSeparateOpenProducerBox(exactStringProducer)
    if (openStringProducerBox.read() !== exactStringProducer ||
        openStringProducerBox.read().produce() != "separate-nested"
    ) {
        return "fail: separate open reference nested producer box"
    }
    val openBroadProducerBox = rehearsalSeparateOpenProducerBox(broadProducer)
    if (openBroadProducerBox.read() !== exactProducer ||
        RehearsalSeparateProducerReader().read(openBroadProducerBox.read()) != 31 ||
        rehearsalSeparateOpenProducerBox(broadProducer).read().produce() != 31
    ) {
        return "fail: separate open broad nested producer box"
    }
    val exactProducerBoxIdentity = rehearsalSeparateOpenProducerBoxIdentity(exactProducerBox)
    if (exactProducerBoxIdentity !== exactProducerBox ||
        exactProducerBoxIdentity.read().produce() != "separate-nested"
    ) {
        return "fail: separate open exact producer box identity"
    }
    val identityStringProducer: RehearsalSeparateProducer<String> =
        RehearsalSeparateProducerValue("separate-identity-write")
    exactProducerBoxIdentity.write(identityStringProducer)
    if (exactProducerBox.read() !== identityStringProducer ||
        exactProducerBox.read().produce() != "separate-identity-write"
    ) {
        return "fail: separate open exact producer box identity write"
    }
    val broadProducerBoxIdentity = rehearsalSeparateOpenProducerBoxIdentity(broadProducerBox)
    if (broadProducerBoxIdentity !== broadProducerBox ||
        RehearsalSeparateProducerReader().read(broadProducerBoxIdentity.read()) != "separate-nested"
    ) {
        return "fail: separate open broad producer box identity"
    }
    broadProducerBoxIdentity.write(broadProducer)
    if (broadProducerBox.read() !== exactProducer ||
        RehearsalSeparateProducerReader().read(broadProducerBox.read()) != 31
    ) {
        return "fail: separate open broad producer box identity write"
    }
    val starProducerStore = RehearsalSeparateStarProducerStore(exactProducer)
    if (starProducerStore.read() != 31 || !starProducerStore.same(exactProducer)) {
        return "fail: separate star producer storage"
    }

    val exactIntConsumerValue = RehearsalSeparateConsumerValue(61)
    val exactIntConsumer: RehearsalSeparateConsumer<Int> = exactIntConsumerValue
    val exactIntConsumerAlias: RehearsalSeparateConsumer<Int> = exactIntConsumer
    exactIntConsumerAlias.consume(63)
    if (exactIntConsumerValue.read() != 63) return "fail: separate exact consumer alias"

    val anyConsumerValue = RehearsalSeparateConsumerValue<Any?>("initial")
    val anyConsumer: RehearsalSeparateConsumer<Any?> = anyConsumerValue
    val stringConsumer: RehearsalSeparateConsumer<String> = anyConsumer
    stringConsumer.consume("reference")
    if (anyConsumerValue.read() != "reference") return "fail: separate reference consumer"
    val narrowIntConsumer: RehearsalSeparateConsumer<Int> = anyConsumer
    narrowIntConsumer.consume(67)
    if (anyConsumerValue.read() != 67) return "fail: separate narrow consumer"
    val narrowIntConsumerBox = rehearsalSeparateIntConsumerBox(narrowIntConsumer)
    if (narrowIntConsumerBox.read() !== anyConsumer) {
        return "fail: separate value-type nested consumer identity"
    }
    narrowIntConsumerBox.read().consume(68)
    if (anyConsumerValue.read() != 68) {
        return "fail: separate value-type nested consumer dispatch"
    }
    val consumerReader = RehearsalSeparateConsumerReader()
    val returnedNarrowConsumer = consumerReader.identity(narrowIntConsumer)
    returnedNarrowConsumer.consume(69)
    if (anyConsumerValue.read() != 69) return "fail: separate returned narrow consumer"
    if (!consumerReader.same(returnedNarrowConsumer, anyConsumer)) {
        return "fail: separate consumer identity"
    }

    val middleIntConsumerValue = RehearsalSeparateMiddleConsumerValue(71)
    val middleIntConsumer: RehearsalSeparateConsumer<Int> = middleIntConsumerValue
    middleIntConsumer.consume(73)
    if (middleIntConsumerValue.read() != 73) return "fail: separate external exact consumer"
    val middleAnyConsumerValue = RehearsalSeparateMiddleConsumerValue<Any?>("middle")
    val middleAnyConsumer: RehearsalSeparateConsumer<Any?> = middleAnyConsumerValue
    val middleNarrowConsumer: RehearsalSeparateConsumer<Int> = middleAnyConsumer
    middleNarrowConsumer.consume(79)
    if (middleAnyConsumerValue.read() != 79) return "fail: separate external narrow consumer"
    if (!RehearsalSeparateConsumerReader().same(middleNarrowConsumer, middleAnyConsumer)) {
        return "fail: separate external consumer identity"
    }

    val animalConsumerValue = RehearsalSeparateConsumerValue(
        RehearsalSeparateNestedAnimal("separate-initial-animal"),
    )
    val animalConsumer: RehearsalSeparateConsumer<RehearsalSeparateNestedAnimal> =
        animalConsumerValue
    val catConsumer: RehearsalSeparateConsumer<RehearsalSeparateNestedCat> = animalConsumer
    val catConsumerBox = rehearsalSeparateCatConsumerBox(catConsumer)
    if (catConsumerBox.read() !== animalConsumer) {
        return "fail: separate reference-only nested consumer identity"
    }
    catConsumerBox.read().consume(RehearsalSeparateNestedCat("separate-consumed-cat"))
    if (animalConsumerValue.read().label != "separate-consumed-cat") {
        return "fail: separate reference-only nested consumer dispatch"
    }
    val openIntConsumerBox = rehearsalSeparateOpenConsumerBox(narrowIntConsumer)
    if (openIntConsumerBox.read() !== anyConsumer) {
        return "fail: separate open value nested consumer identity"
    }
    openIntConsumerBox.read().consume(81)
    if (anyConsumerValue.read() != 81) {
        return "fail: separate open value nested consumer dispatch"
    }
    rehearsalSeparateOpenConsumerBox(narrowIntConsumer).read().consume(82)
    if (anyConsumerValue.read() != 82) {
        return "fail: separate direct open nested consumer dispatch"
    }
    val openCatConsumerBox = rehearsalSeparateOpenConsumerBox(catConsumer)
    if (openCatConsumerBox.read() !== animalConsumer) {
        return "fail: separate open reference nested consumer identity"
    }
    openCatConsumerBox.read().consume(
        RehearsalSeparateNestedCat("separate-open-consumed-cat"),
    )
    if (animalConsumerValue.read().label != "separate-open-consumed-cat") {
        return "fail: separate open reference nested consumer dispatch"
    }
    val intConsumerBoxIdentity = rehearsalSeparateOpenConsumerBoxIdentity(narrowIntConsumerBox)
    if (intConsumerBoxIdentity !== narrowIntConsumerBox) {
        return "fail: separate open value consumer box identity"
    }
    intConsumerBoxIdentity.read().consume(83)
    if (anyConsumerValue.read() != 83) {
        return "fail: separate open value consumer box identity dispatch"
    }
    val catConsumerBoxIdentity = rehearsalSeparateOpenConsumerBoxIdentity(catConsumerBox)
    if (catConsumerBoxIdentity !== catConsumerBox) {
        return "fail: separate open reference consumer box identity"
    }
    catConsumerBoxIdentity.read().consume(
        RehearsalSeparateNestedCat("separate-identity-consumed-cat"),
    )
    if (animalConsumerValue.read().label != "separate-identity-consumed-cat") {
        return "fail: separate open reference consumer box identity dispatch"
    }

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
