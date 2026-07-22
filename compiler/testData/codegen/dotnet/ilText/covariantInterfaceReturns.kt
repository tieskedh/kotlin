// ECMA-335 implicit interface mapping includes the return type. An inherited narrower class
// member therefore receives a private final MethodImpl adapter for the wider interface slot;
// ordinary class dispatch remains virtual and the adapter contains no source-body copy.
interface Maker {
    fun make(): Top
}

interface HasThing {
    val thing: Top
}

open class Top

class Bottom : Top()

open class Factory {
    open fun make(): Bottom = Bottom()
}

class Combo : Factory(), Maker

open class ThingHolder {
    open val thing: Bottom = Bottom()
}

class Combo2 : ThingHolder(), HasThing

interface RefinedMaker : Maker {
    override fun make(): Bottom
}

class RefinedMakerImplementation : RefinedMaker {
    override fun make(): Bottom = Bottom()
}

fun main() {
    println(Combo().make())
    println(Combo2().thing)
    println(RefinedMakerImplementation().make())
}
