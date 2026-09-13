/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package selection.contract

private class IntProducer(private val result: Int) : Producer<Int> {
    override fun read(): Int = result
}

private class StringProducer(private val result: String) : Producer<String> {
    override fun read(): String = result
}

private fun verify(value: Producer<*>, receiver: Any, expected: Any?) {
    check(value === receiver) { "Receiver identity changed" }
    check(value.read() == expected) { "Dispatch changed" }
}

@Suppress("UNCHECKED_CAST")
fun main() {
    val original = IntProducer(7)
    val exact: Producer<Int> = original
    val alias: Producer<Int> = exact
    val wide: Producer<Any> = alias
    verify(alias, original, 7)
    verify(wide, original, 7)
    verify(forward(wide), original, 7)

    val typed = Box<Producer<Any>>(wide)
    val erased = ObjectBox<Producer<Any>>(wide)
    verify(typed.value, original, 7)
    verify(erased.value, original, 7)
    verify(throughObject(wide), original, 7)
    val replacement = IntProducer(11)
    typed.value = replacement
    erased.value = replacement
    verify(typed.value, replacement, 11)
    verify(erased.value, replacement, 11)
    val differentConstruction = StringProducer("replacement")
    typed.value = differentConstruction
    erased.value = differentConstruction
    verify(typed.value, differentConstruction, "replacement")
    verify(erased.value, differentConstruction, "replacement")

    val any: Any = wide
    val nullableAny: Any? = erase(any)
    check(any === original && nullableAny === original)
    check(genericErase(wide) === original)
    check(erase(wide) is Producer<*>)
    verify(erase(wide) as Producer<*>, original, 7)
    verify(erase(wide) as Producer<Int>, original, 7)
    verify(erase(wide) as Producer<Any>, original, 7)
    verify((erase(wide) as? Producer<Any>)!!, original, 7)
    check(erase("not a producer") as? Producer<*> == null)
    check(erase(null) as? Producer<*> == null)
    check(erase(null) is Producer<*>?)

    val nullResult = object : Producer<Nothing?> { override fun read(): Nothing? = null }
    val nullableWide: Producer<Any?> = nullResult
    verify(throughObject(nullableWide), nullResult, null)
    verify(ObjectBox(nullableWide).value, nullResult, null)
    check(genericErase(nullableWide) === nullResult)
    println("PASS: coherent Kotlin selection contract; identity, widening, separate generic storage, Any, casts, null")
}
