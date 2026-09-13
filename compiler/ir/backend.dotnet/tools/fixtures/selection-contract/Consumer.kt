/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package selection.contract

private class IntProducer(private val result: Int) : Producer<Int> {
    var calls: Int = 0
        private set

    override fun read(): Int {
        calls++
        return result
    }
}

private class StringProducer(private val result: String) : Producer<String> {
    override fun read(): String = result
}

private class NeverProducer(private val failure: IllegalStateException) : Producer<Nothing> {
    var calls: Int = 0
        private set

    override fun read(): Nothing {
        calls++
        throw failure
    }
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
    check(exact.read() == 7 && original.calls == 1)
    check(wide.read() == 7 && original.calls == 2)
    check(readExact(exact) == 7 && original.calls == 3)
    check(readWide(wide) == 7 && original.calls == 4)
    check(readStar(wide) == 7 && original.calls == 5)
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

    // Even a final reference argument does not exclude a legal bottom producer.
    // The non-null receiver exists; only its Nothing result is uninhabited.
    val sentinel = IllegalStateException("bottom producer")
    val never = NeverProducer(sentinel)
    val stringView: Producer<String> = never
    val stringBox = Box<Producer<String>>(never)
    val stringObjectBox = ObjectBox<Producer<String>>(never)
    for (stored in arrayOf(stringView, stringBox.value, stringObjectBox.value,
        throughObject(stringView), erase(stringView) as Producer<String>)) {
        check(stored === never)
        val before = never.calls
        try {
            stored.read()
            error("Bottom producer returned")
        } catch (failure: IllegalStateException) {
            check(failure === sentinel && never.calls == before + 1)
        }
    }
    stringBox.value = StringProducer("normal replacement")
    check(stringBox.value.read() == "normal replacement")
    println("PASS: coherent Kotlin selection contract; direct and separate exact/wide/star calls, effects, identity, storage, Any, casts, null, bottom")
}
