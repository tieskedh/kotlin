// MODULE: lib
// FILE: lib.kt

package sam.boundary

public fun interface Mapper<T> {
    public fun map(value: T): T
}

public fun interface Greeter {
    public fun greet(value: String): String
}

public fun apply(mapper: Mapper<String>, value: String): String = mapper.map(value)

public fun makePrefix(prefix: String): Mapper<String> = Mapper { prefix + it }

public fun makeGreeter(prefix: String): Greeter = Greeter { prefix + it }

public inline fun inlineMapper(noinline function: (String) -> String): Mapper<String> = Mapper(function)

// MODULE: main(lib)
// FILE: main.kt

import sam.boundary.*

fun box(): String {
    if (apply({ "[$it]" }, "OK") != "[OK]") return "fail 1: consumer conversion"

    val producerMapper = makePrefix("pre:")
    if (producerMapper.map("OK") != "pre:OK") return "fail 2: producer wrapper"

    val producerGreeter = makeGreeter("hello ")
    if (producerGreeter.greet("Kotlin") != "hello Kotlin") return "fail 3: producer non-generic wrapper"

    val function: (String) -> String = { it + "!" }
    val inlineFirst = inlineMapper(function)
    val inlineSecond = inlineMapper(function)
    if (inlineFirst === inlineSecond) return "fail 4: inline wrapper identity"
    if (inlineFirst != inlineSecond) return "fail 5: inline wrapper equality"
    if (inlineFirst.map("OK") != "OK!") return "fail 6: inline wrapper forwarding"

    return "OK"
}
