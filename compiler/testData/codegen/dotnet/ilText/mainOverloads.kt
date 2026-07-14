// Kotlin's enhanced-main convention prefers the string-array overload when one file also has a
// parameterless main. Only the selected overload carries `.entrypoint`; both remain ordinary
// callable IL methods.
fun main() {
    println("parameterless")
}

fun main(args: Array<String>) {
    println(args.size)
}
