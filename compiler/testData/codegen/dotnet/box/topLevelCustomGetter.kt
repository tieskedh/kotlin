// Custom accessors of top-level properties: a computed `val` without a backing field and a `var`
// with a custom setter writing `field` (the direct static-field store) while counting writes
// through a sibling property.

var backing = 40
val custom: Int
    get() = backing + 2
var sets = 0
var tracked: Int = 0
    set(value) {
        sets = sets + 1
        field = value
    }

fun box(): String {
    if (custom != 42) return "FAIL custom: " + custom
    tracked = 9
    if (tracked != 9) return "FAIL tracked: " + tracked
    if (sets != 1) return "FAIL sets: " + sets
    backing = 100
    if (custom != 102) return "FAIL custom after write: " + custom
    return "OK"
}
