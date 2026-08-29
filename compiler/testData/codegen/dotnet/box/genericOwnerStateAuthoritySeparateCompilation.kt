// DOTNET_GENERIC_OWNER_STATE_AUTHORITY_CSHARP_PROBE
// MODULE: lib
// FILE: stateOwners.kt

package generic.owner.state.authority

/** Every producer-visible write has the exact owner-dependent `T` carrier. */
public open class TypedStateOwner<T>(initial: T) {
    private var state: T = initial

    public fun write(next: T) {
        state = next
    }

    public fun read(): T = state
}

/** A legal widened Kotlin write requires one authoritative object-domain state slot. */
public open class BroadStateOwner<out T>(initial: T) {
    private var state: T = initial

    public fun write(next: @UnsafeVariance T) {
        state = next
    }

    public fun read(): T = state
}

/**
 * The base's widened writer makes its own state semantic. A separately compiled override will
 * expose whether that inherited semantic route reaches a later owner-dependent child write.
 */
public open class LateStateBase<out T>(initial: T) {
    private var state: T = initial

    public fun poison(next: @UnsafeVariance T) {
        state = next
    }

    public open fun transfer(): T = state
}

// MODULE: middle(lib)
// FILE: stateMiddle.kt

package generic.owner.state.authority

/** Memberless children must reuse the exact base construction and must not acquire shadow state. */
public open class TypedStateChild<T>(initial: T) : TypedStateOwner<T>(initial)

public open class BroadStateChild<out T>(initial: T) : BroadStateOwner<T>(initial)

/**
 * `copied` looks typed before inherited semantic hooks are paired. The override's write must be
 * promoted after that pairing because a widened call can copy the base's object-domain value.
 */
public open class LateStateChild<out T>(initial: T) : LateStateBase<T>(initial) {
    private var copied: T = initial

    public fun poisonChild(next: @UnsafeVariance T) {
        super.poison(next)
    }

    public override fun transfer(): T {
        copied = super.transfer()
        return copied
    }

    public fun peek(): T = copied
}

public fun readTypedStar(owner: TypedStateOwner<*>): Any? = owner.read()

public fun readBroad(owner: BroadStateOwner<Any?>): Any? = owner.read()

public fun writeBroad(owner: BroadStateOwner<Any?>, next: Any?) {
    owner.write(next)
}

public fun sameBroad(owner: BroadStateOwner<Any?>, expected: Any?): Boolean =
    owner === expected

public fun poisonLate(owner: LateStateChild<Any?>, next: Any?) {
    owner.poisonChild(next)
}

public fun transferLate(owner: LateStateChild<Any?>): Any? = owner.transfer()

public fun peekLate(owner: LateStateChild<Any?>): Any? = owner.peek()

public fun sameLate(owner: LateStateChild<Any?>, expected: Any?): Boolean =
    owner === expected

// MODULE: main(middle)
// FILE: stateMain.kt

package generic.owner.state.authority

fun box(): String {
    val typedInt = TypedStateChild(11)
    typedInt.write(12)
    if (typedInt.read() != 12 || readTypedStar(typedInt) != 12) return "typed int"

    val typedString = TypedStateChild("typed")
    typedString.write("updated")
    if (typedString.read() != "updated" || readTypedStar(typedString) != "updated") {
        return "typed string"
    }

    val broadInt = BroadStateChild(13)
    val widened: BroadStateOwner<Any?> = broadInt
    if (!sameBroad(widened, broadInt)) return "broad identity"
    writeBroad(widened, "poison")
    if (readBroad(widened) != "poison") return "broad widened read"
    try {
        val impossible = broadInt.read() + 1
        return "broad exact read accepted poison: $impossible"
    } catch (_: ClassCastException) {
        // The widened view and the exact view retain one object and one state; the typed use fails.
    }
    writeBroad(widened, 17)
    if (broadInt.read() != 17) return "broad recovery"

    val lateInt = LateStateChild(19)
    val lateWidened: LateStateChild<Any?> = lateInt
    if (!sameLate(lateWidened, lateInt)) return "late identity"
    poisonLate(lateWidened, "late-poison")
    if (transferLate(lateWidened) != "late-poison" ||
        peekLate(lateWidened) != "late-poison"
    ) {
        return "late widened transfer"
    }
    try {
        val impossible = lateInt.peek() + 1
        return "late exact read accepted poison: $impossible"
    } catch (_: ClassCastException) {
        // The inherited semantic call populated the child's one authoritative object state.
    }
    poisonLate(lateWidened, 23)
    if (transferLate(lateWidened) != 23 || lateInt.peek() != 23) return "late recovery"

    val broadString = BroadStateChild("broad")
    if (readBroad(broadString) != "broad" || !sameBroad(broadString, broadString)) {
        return "broad string"
    }
    return "OK"
}
