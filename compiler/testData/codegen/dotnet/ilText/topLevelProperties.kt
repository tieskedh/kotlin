var counter = 10
val name = "kotlin"
val custom: Int
    get() = counter + 1
var observed: Int = 0
    set(value) {
        counter = counter + 1
        field = value
    }

fun main() {
    counter = counter + 5
    println(counter)
    println(name)
    println(custom)
    observed = 7
    println(observed)
}
