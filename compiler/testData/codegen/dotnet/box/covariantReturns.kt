private open class Animal(val tag: String)
private open class Cat(tag: String) : Animal(tag)
private class Siamese(tag: String) : Cat(tag)

private open class Source {
    open fun make(): Animal = Animal("base-method")
    open val item: Animal get() = Animal("base-property")
}

private open class CatSource : Source() {
    override fun make(): Cat = Cat("cat-method")
    override val item: Cat get() = Cat("cat-property")
}

private class SiameseSource : CatSource() {
    override fun make(): Siamese = Siamese("siamese-method")
    override val item: Siamese get() = Siamese("siamese-property")
}

private interface Maker {
    fun make(): Animal
}

private interface HasItem {
    val item: Animal
}

private class DirectMaker : Maker {
    override fun make(): Cat = Cat("direct-interface")
}

private open class InheritedMaker {
    open fun make(): Cat = Cat("inherited-interface")
}

private class CombinedMaker : InheritedMaker(), Maker

private open class InheritedItem {
    open val item: Cat get() = Cat("inherited-property-interface")
}

private class CombinedItem : InheritedItem(), HasItem

private open class NullableNumberSource {
    open fun number(): Int? = null
}

private class ExactNumberSource : NullableNumberSource() {
    override fun number(): Int = 42
}

private open class NullableTextSource {
    open fun text(): String? = null
}

private class ExactTextSource : NullableTextSource() {
    override fun text(): String = "same-il"
}

private abstract class AbstractCatSource : Source() {
    abstract override fun make(): Cat
}

private class ConcreteAbstractCatSource : AbstractCatSource() {
    override fun make(): Cat = Cat("abstract-class")
}

private interface RefinedMaker : Maker {
    override fun make(): Cat
}

private class RefinedMakerImplementation : RefinedMaker {
    override fun make(): Cat = Cat("abstract-interface")
}

private open class GenericSource {
    open fun <T> make(value: T): Animal = Animal("generic-base")
}

private class GenericCatSource : GenericSource() {
    override fun <T> make(value: T): Cat = Cat("generic-cat")
}

fun box(): String {
    val cat = CatSource()
    val exactCat: Cat = cat.make()
    val exactCatItem: Cat = cat.item
    if (exactCat.tag != "cat-method") return "fail 1a: exact method"
    if (exactCatItem.tag != "cat-property") return "fail 1b: exact property"
    val catAsSource: Source = cat
    if (catAsSource.make().tag != "cat-method") return "fail 1: base method"
    if (catAsSource.item.tag != "cat-property") return "fail 2: base property"

    val siamese = SiameseSource()
    val exactSiamese: Siamese = siamese.make()
    val exactSiameseItem: Siamese = siamese.item
    if (exactSiamese.tag != "siamese-method") return "fail 2a: exact leaf method"
    if (exactSiameseItem.tag != "siamese-property") return "fail 2b: exact leaf property"
    val siameseAsSource: Source = siamese
    val siameseAsCatSource: CatSource = siamese
    if (siameseAsSource.make().tag != "siamese-method") return "fail 3: root method"
    if (siameseAsCatSource.make().tag != "siamese-method") return "fail 4: middle method"
    if (siameseAsSource.item.tag != "siamese-property") return "fail 5: root property"
    if (siameseAsCatSource.item.tag != "siamese-property") return "fail 6: middle property"

    val direct: Maker = DirectMaker()
    if (direct.make().tag != "direct-interface") return "fail 7: direct interface"
    val inherited: Maker = CombinedMaker()
    if (inherited.make().tag != "inherited-interface") return "fail 8: inherited interface"
    val inheritedProperty: HasItem = CombinedItem()
    if (inheritedProperty.item.tag != "inherited-property-interface") return "fail 9: interface property"

    val number: NullableNumberSource = ExactNumberSource()
    if (number.number() != 42) return "fail 10: nullable primitive slot"
    val text: NullableTextSource = ExactTextSource()
    if (text.text() != "same-il") return "fail 11: same IL return"
    val abstractClass: Source = ConcreteAbstractCatSource()
    if (abstractClass.make().tag != "abstract-class") return "fail 12: abstract class"
    val abstractInterface: Maker = RefinedMakerImplementation()
    if (abstractInterface.make().tag != "abstract-interface") return "fail 13: abstract interface"
    val genericExact = GenericCatSource()
    val genericAsBase: GenericSource = genericExact
    val exactGenericResult: Cat = genericExact.make(42)
    if (exactGenericResult.tag != "generic-cat") return "fail 14: exact generic method"
    if (genericAsBase.make("value").tag != "generic-cat") return "fail 15: generic base method"
    return "OK"
}
