package hybrid.composition

// Common-compatible declarations. No alternative owner, copied state, or unsafe cast.
class Box<T>(var value: T)

fun <T> make(value: T): Box<T> = Box(value)

fun <T> wrapProjected(value: Box<out T>): Box<Box<out T>> = Box(value)

fun <T> wrapNullable(value: T?): Box<T?> = Box(value)

open class Base<T>(var value: T)

class Derived<T>(value: T?) : Base<T?>(value)
