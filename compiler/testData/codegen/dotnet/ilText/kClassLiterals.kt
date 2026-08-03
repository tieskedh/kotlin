import kotlin.reflect.KClass

class Plain

class Box<T>(val value: T)

fun runtimeClass(value: Any): KClass<out Any> = value::class

fun main() {
    println(Plain::class.simpleName)
    println(Box::class.isInstance(Box(1)))
    println(runtimeClass(Plain()).qualifiedName)
}
