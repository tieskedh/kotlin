private open class ProjectedBase(val value: String)

private class ProjectedDerived(value: String) : ProjectedBase(value)

private class UserList<T>(private val values: Array<out T>) : AbstractList<T>() {
    override val size: Int
        get() = values.size

    override fun get(index: Int): T = values[index]
}

private fun projectedView(values: Array<out ProjectedBase>): List<ProjectedBase> =
    values.asList()

private fun fail(message: String): String = "fail: $message"

fun box(): String {
    val userList = UserList(arrayOf("left", "middle", "right"))
    if (userList.iterator().next() != "left") return fail("AbstractList iterator")
    val userIterator = userList.listIterator(2)
    if (userIterator.previous() != "middle" || userIterator.next() != "middle") {
        return fail("AbstractList listIterator")
    }
    if (userList.subList(1, 3).toString() != "[middle, right]") {
        return fail("AbstractList subList")
    }
    if (userList != arrayOf("left", "middle", "right").asList()) {
        return fail("AbstractList equality")
    }
    if (userList.hashCode() != arrayOf("left", "middle", "right").asList().hashCode()) {
        return fail("AbstractList hashCode")
    }
    if (userList.toString() != "[left, middle, right]") return fail("AbstractList toString")

    val values = arrayOf(7, 9, 7)
    val list = values.asList()
    if (list.size != 3 || list.isEmpty()) return fail("size")
    if (list[0] != 7 || list[1] != 9 || list[2] != 7) return fail("get")
    if (list !is RandomAccess) return fail("RandomAccess")
    if (!list.contains(9) || list.indexOf(7) != 0 || list.lastIndexOf(7) != 2) {
        return fail("search")
    }
    if (!list.containsAll(arrayOf(7, 9).asList())) return fail("containsAll")

    val widened: List<Any?> = list
    if (widened !== list) return fail("List widening identity")
    if (widened.contains("7") || widened.contains(null)) return fail("contains barrier")
    if (widened.indexOf("9") != -1 || widened.lastIndexOf(null) != -1) {
        return fail("search barrier")
    }
    if (!widened.containsAll(arrayOf<Any?>(7).asList())) {
        return fail("containsAll widened true")
    }
    if (!widened.containsAll(emptyArray<Any?>().asList())) {
        return fail("containsAll widened empty")
    }
    if (
        widened.containsAll(arrayOf<Any?>(7, "wrong").asList()) ||
        widened.containsAll(arrayOf<Any?>(7, null).asList())
    ) {
        return fail("containsAll barrier")
    }

    values[1] = 11
    if (list[1] != 11 || widened[1] != 11) return fail("array alias")
    val tail = list.subList(1, 3)
    val nested = tail.subList(1, 2)
    if (tail.size != 2 || tail[0] != 11 || nested.size != 1 || nested[0] != 7) {
        return fail("subList")
    }
    values[2] = 13
    if (tail[1] != 13 || nested[0] != 13) return fail("subList alias")

    val iterator = list.listIterator(1)
    if (iterator.nextIndex() != 1 || iterator.previousIndex() != 0) return fail("iterator indices")
    if (!iterator.hasNext() || iterator.next() != 11) return fail("iterator next")
    if (!iterator.hasPrevious() || iterator.previous() != 11) return fail("iterator previous")
    if (iterator.previous() != 7 || iterator.hasPrevious()) return fail("iterator start")
    try {
        iterator.previous()
        return fail("iterator previous bound")
    } catch (_: NoSuchElementException) {
    }
    val end = list.listIterator(list.size)
    if (end.hasNext() || end.nextIndex() != 3 || end.previousIndex() != 2) {
        return fail("iterator end state")
    }
    try {
        end.next()
        return fail("iterator next bound")
    } catch (_: NoSuchElementException) {
    }

    val equal = arrayOf(7, 11, 13).asList()
    if (list != equal || equal != list) return fail("structural equality")
    if (list.hashCode() != equal.hashCode() || list.hashCode() != 36872) {
        return fail("structural hash ${list.hashCode()}")
    }
    if (list.toString() != "[7, 11, 13]") return fail("structural text ${list}")

    val nullableArray = arrayOf<String?>(null, "value")
    val nullableList: List<Any?> = nullableArray.asList()
    if (!nullableList.contains(null) || nullableList.toString() != "[null, value]") {
        return fail("nullable elements")
    }

    val floating = arrayOf(Double.NaN, -0.0).asList()
    if (floating != arrayOf(Double.NaN, -0.0).asList()) return fail("NaN equality")
    if (floating == arrayOf(Double.NaN, 0.0).asList()) return fail("signed-zero equality")

    val recursiveArray = arrayOfNulls<Any>(1)
    val recursiveList = recursiveArray.asList()
    recursiveArray[0] = recursiveList
    if (recursiveList.toString() != "[(this Collection)]") return fail("recursive text")

    val derived = arrayOf(ProjectedDerived("first"), ProjectedDerived("second"))
    val projectedArray: Array<out ProjectedBase> = derived
    val projected = projectedView(projectedArray)
    if (projected[0].value != "first") return fail("reference projection")
    derived[0] = ProjectedDerived("changed")
    if (projected[0].value != "changed") return fail("projected alias")

    val empty = emptyArray<String>().asList()
    val anotherEmpty = emptyArray<String>().asList()
    if (!empty.isEmpty() || empty.size != 0 || empty !is RandomAccess) return fail("empty")
    if (empty === anotherEmpty || empty === emptyList<String>()) return fail("empty view identity")
    if (empty.toString() != "[]" || empty.hashCode() != 1 || empty != emptyList<String>()) {
        return fail("empty structure")
    }

    val singletonArray = arrayOf("only")
    val singleton = singletonArray.asList()
    if (singleton.size != 1 || singleton[0] != "only" || singleton.subList(0, 1)[0] != "only") {
        return fail("singleton")
    }
    singletonArray[0] = "changed"
    if (singleton[0] != "changed") return fail("singleton alias")

    try {
        list[-1]
        return fail("negative get")
    } catch (_: IndexOutOfBoundsException) {
    }
    try {
        list[list.size]
        return fail("oversized get")
    } catch (_: IndexOutOfBoundsException) {
    }
    try {
        list.listIterator(-1)
        return fail("negative iterator position")
    } catch (_: IndexOutOfBoundsException) {
    }
    try {
        list.listIterator(list.size + 1)
        return fail("oversized iterator position")
    } catch (_: IndexOutOfBoundsException) {
    }
    try {
        list.subList(-1, 0)
        return fail("negative subList")
    } catch (_: IndexOutOfBoundsException) {
    }
    try {
        list.subList(0, list.size + 1)
        return fail("oversized subList")
    } catch (_: IndexOutOfBoundsException) {
    }
    try {
        list.subList(2, 1)
        return fail("reversed subList")
    } catch (_: IllegalArgumentException) {
    }

    return "OK"
}
