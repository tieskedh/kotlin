import kotlin.enums.EnumEntries

enum class Direction {
    NORTH,
    SOUTH,
    EAST,
}

private interface IntOperation {
    fun apply(left: Int, right: Int): Int
}

private enum class Operation(val token: String) : IntOperation {
    PLUS("+") {
        override fun apply(left: Int, right: Int): Int = left + right
    },
    MINUS("-") {
        override fun apply(left: Int, right: Int): Int = left - right
    },
}

private enum class Link(val previous: Link?) {
    FIRST(null),
    SECOND(FIRST),
}

private var initializationTrace = ""

private fun tracedCode(marker: String, value: Int): Int {
    initializationTrace += marker
    return value
}

private enum class Initialized(val code: Int) {
    FIRST(tracedCode("A", 1)),
    SECOND(tracedCode("B", 2));

    init {
        initializationTrace += name
    }

    companion object {
        val companionCode = tracedCode("C", 3)
    }
}

private enum class Empty {
    ;
}

private annotation class EntryMarker

private enum class Annotated {
    @EntryMarker
    MARKED,
}

private fun describe(direction: Direction): String = when (direction) {
    Direction.NORTH -> "north"
    Direction.SOUTH -> "south"
    Direction.EAST -> "east"
}

fun box(): String {
    if (Direction.NORTH.name != "NORTH") return "name: ${Direction.NORTH.name}"
    if (Direction.SOUTH.ordinal != 1) return "ordinal"
    if (Direction.EAST.toString() != "EAST") return "toString"
    if (Direction.NORTH.compareTo(Direction.SOUTH) >= 0) return "compareTo"
    if (Direction.NORTH != Direction.NORTH || Direction.NORTH == Direction.SOUTH) return "equality"
    if (Direction.NORTH.hashCode() != Direction.NORTH.hashCode()) return "hashCode"
    if (describe(Direction.SOUTH) != "south") return "exhaustive when"

    val firstValues = Direction.values()
    val secondValues = Direction.values()
    if (firstValues === secondValues) return "values identity"
    if (firstValues.size != 3 || firstValues[0] !== Direction.NORTH) return "values content"
    firstValues[0] = Direction.SOUTH
    if (Direction.values()[0] !== Direction.NORTH) return "values mutation"

    val firstEntries = Direction.entries
    val secondEntries = Direction.entries
    if (firstEntries !== secondEntries) return "entries identity"
    if (firstEntries.size != 3 || firstEntries[2] !== Direction.EAST) return "entries content"
    if (!firstEntries.contains(Direction.SOUTH) || firstEntries.indexOf(Direction.EAST) != 2) return "entries lookup"
    var iteration = ""
    for (entry in firstEntries) iteration += entry.name
    if (iteration != "NORTHSOUTHEAST") return "entries iteration: $iteration"

    val widenedEnum: Enum<*> = Direction.EAST
    if (widenedEnum.name != "EAST" || widenedEnum.ordinal != 2 || widenedEnum !is Direction) return "widened enum"
    val nullableEnum: Direction? = Direction.NORTH
    if (nullableEnum !== Direction.NORTH) return "nullable enum"
    val widenedEntries: EnumEntries<*> = firstEntries
    if (widenedEntries.size != 3 || widenedEntries[1] !== Direction.SOUTH) return "widened entries"

    if (Direction.valueOf("SOUTH") !== Direction.SOUTH) return "valueOf"
    try {
        Direction.valueOf("missing")
        return "missing valueOf did not throw"
    } catch (failure: IllegalArgumentException) {
        if (failure.message != "No enum constant Direction.missing") return "missing valueOf message: ${failure.message}"
    }

    if (Operation.PLUS.token != "+" || Operation.MINUS.token != "-") return "entry constructor arguments"
    if (Operation.PLUS.apply(7, 2) != 9 || Operation.MINUS.apply(7, 2) != 5) return "entry subclass dispatch"
    val operation: IntOperation = Operation.MINUS
    if (operation.apply(8, 3) != 5) return "entry interface dispatch"

    if (Link.SECOND.previous !== Link.FIRST || Link.FIRST.previous != null) return "previous entry reference"

    if (Initialized.SECOND.code != 2) return "initialized value"
    if (initializationTrace != "AFIRSTBSECONDC") return "entry initialization order: $initializationTrace"
    if (Initialized.companionCode != 3) return "companion value"
    if (initializationTrace != "AFIRSTBSECONDC") return "companion initialization order: $initializationTrace"

    if (Empty.values().size != 0 || Empty.entries.size != 0) return "empty enum"
    try {
        Empty.valueOf("")
        return "empty valueOf did not throw"
    } catch (failure: IllegalArgumentException) {
        if (failure.message != "No enum constant Empty.") return "empty valueOf message: ${failure.message}"
    }

    if (Annotated.MARKED.name != "MARKED") return "annotated entry"
    return "OK"
}
