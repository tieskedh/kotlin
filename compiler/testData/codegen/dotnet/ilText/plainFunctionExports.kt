// DOTNET_EXPORT: demo.add=Add
// DOTNET_EXPORT: demo.maybe=Maybe
// DOTNET_EXPORT: demo.consume=Consume
// DOTNET_EXPORT: demo.withDefaults=WithDefaults
// DOTNET_EXPORT: demo.increasedBy=Increase

package demo

fun add(left: Int, right: Int): Int = left + right

fun maybe(value: String?): String? = value

fun consume(value: String?) {
    println(value)
}

fun withDefaults(prefix: String = "value", amount: Int = 42): String = prefix + amount

fun Int.increasedBy(delta: Int = 1): Int = this + delta

fun main() {
    println(add(20, 22))
    println(maybe("ok"))
    consume(null)
    println(withDefaults())
    println(41.increasedBy())
}
