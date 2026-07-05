package test

import kotlin.io.println

fun sumOneToTen(): Int {
    var sum = 0
    var i = 1
    while (i <= 10) {
        sum = sum + i
        i++
    }
    return sum
}

fun countDownDoWhile(start: Int): Int {
    var count = 0
    var i = start
    do {
        count++
        i--
    } while (i > 0)
    return count
}

fun doWhileWithBodyCondition(limit: Int): Int {
    var i = 0
    do {
        val next = i + 1
        i = next
    } while (next < limit)
    return i
}

fun firstMultipleAbove(step: Int, limit: Int): Int {
    var candidate = 0
    while (true) {
        candidate = candidate + step
        if (candidate > limit) break
    }
    return candidate
}

fun sumOfOdds(limit: Int): Int {
    var sum = 0
    var i = 0
    while (i < limit) {
        i++
        if (i % 2 == 0) continue
        sum = sum + i
    }
    return sum
}

fun countInnerBreaks(outer: Int, inner: Int): Int {
    var count = 0
    var i = 0
    while (i < outer) {
        var j = 0
        while (j < inner) {
            if (j == 2) break
            count++
            j++
        }
        i++
    }
    return count
}

fun shadowedSums(flag: Boolean): Int {
    var total = 0
    // Kotlin allows shadowing: the three `value` locals below live in sibling scopes and share
    // a source name, but each is a distinct IR variable and must get its own slot.
    if (flag) {
        val value = 10
        total = total + value
    } else {
        val value = 30
        total = total + value
    }
    while (total < 100) {
        val value = total
        total = total + value
    }
    return total
}

fun main() {
    if (sumOneToTen() == 55) println("while sum OK") else println("while sum FAIL")
    if (countDownDoWhile(3) == 3) println("doWhile OK") else println("doWhile FAIL")
    if (countDownDoWhile(0) == 1) println("doWhile runs once OK") else println("doWhile runs once FAIL")
    if (doWhileWithBodyCondition(5) == 5) println("doWhile body condition OK") else println("doWhile body condition FAIL")
    if (firstMultipleAbove(7, 40) == 42) println("break OK") else println("break FAIL")
    if (sumOfOdds(10) == 25) println("continue OK") else println("continue FAIL")
    if (countInnerBreaks(3, 5) == 6) println("nested break OK") else println("nested break FAIL")
    if (shadowedSums(true) == 160) println("shadowing true OK") else println("shadowing true FAIL")
    if (shadowedSums(false) == 120) println("shadowing false OK") else println("shadowing false FAIL")
}
