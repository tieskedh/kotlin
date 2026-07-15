package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.util.render

/**
 * Per-method emission state: the buffered body text, operand stack depth tracking used to compute
 * `.maxstack`, local slots collected lazily as declarations are encountered, and label allocation.
 *
 * The body is buffered so that `.maxstack` and the `.locals init` block can be written into the
 * method header only after the whole body has been rendered.
 *
 * [firstArgumentIndex] is the CLR argument slot of `parameters[0]`: 0 for static methods and for
 * instance member methods (whose `parameters[0]` IS the dispatch receiver, landing on the CLR
 * `this` slot 0 naturally), 1 for constructors, whose parameter list carries no dispatch
 * receiver — the implicit `this` occupies slot 0 and is registered separately via [registerThis].
 */
internal class DotNetIlMethodContext(
    parameters: List<IrValueParameter>,
    parameterTypes: List<DotNetIlValueType>,
    private val typeMapper: DotNetIlTypeMapper,
    firstArgumentIndex: Int = 0,
    private val erasedCallableParameters: Set<IrValueSymbol> = emptySet(),
) {
    private val bodyBuilder = StringBuilder()
    private val slots = hashMapOf<IrValueSymbol, DotNetIlSlot>()
    private val localSlots = mutableListOf<DotNetIlSlot.Local>()
    private val usedLocalNames = hashSetOf<String>()
    private val branchTargetStackDepths = hashMapOf<String, Int>()
    private val loopLabels = hashMapOf<IrLoop, DotNetIlLoopLabels>()
    private val requiredHelpers = linkedSetOf<DotNetIlRuntimeHelper>()
    private val ehRegions = ArrayDeque<EhRegion>()
    private var labelCounter = 0
    private var maxStackDepth = 0

    /**
     * Current operand stack depth at the emission point. Exposed read-only so try-expression
     * emission can reject a `try` with operands already on the evaluation stack: the CLR
     * requires an empty stack at `.try` entry (ECMA-335 I.12.4.2).
     */
    var stackDepth = 0
        private set

    /**
     * The number of exception-handling regions (`.try` bodies, `catch` handlers and `finally`
     * bodies) enclosing the current emission point. `break`/`continue`/`return` compare it
     * against the depth at their target to decide between a plain `br`/`ret` and the
     * [emitLeave] discipline.
     */
    val ehDepth: Int
        get() = ehRegions.size

    /**
     * Whether a `finally` body lies between the current emission point and the region depth of
     * a branch target (the loop's registration depth for `break`/`continue`, 0 for `return`).
     * The CLR's only legal exit from a `finally` body is `endfinally` — even `leave` may not
     * cross it — so callers reject such non-local exits instead of emitting an [emitLeave].
     */
    fun crossesFinallyRegion(targetDepth: Int): Boolean {
        for (index in targetDepth until ehRegions.size) {
            if (ehRegions[index] == EhRegion.FINALLY_BODY) return true
        }
        return false
    }

    /**
     * Whether the last emitted instruction unconditionally leaves the current emission point
     * (`ret` or an unconditional `br`). Used to suppress dead branches after a mid-body return,
     * `break`, or `continue`: a dead `br` targeting the end of a method is rejected by the CLR.
     */
    var isTerminated: Boolean = false
        private set

    init {
        parameters.zip(parameterTypes).forEachIndexed { index, [parameter, type] ->
            slots[parameter.symbol] = DotNetIlSlot.Parameter(firstArgumentIndex + index, type)
        }
    }

    /**
     * Registers the implicit `this` of a constructor body as argument slot 0, so that every
     * `IrGetValue` of the class's `thisReceiver` — including references inside initializer code
     * that [InitializersLowering][org.jetbrains.kotlin.backend.common.lower.InitializersLowering]
     * copied into the constructor, which `deepCopyWithSymbols` deliberately does not remap —
     * emits a plain `ldarg.0` through the existing slot machinery.
     */
    fun registerThis(symbol: IrValueSymbol, type: DotNetIlValueType) {
        slots[symbol] = DotNetIlSlot.Parameter(0, type)
    }

    /** True only for a logical callable parameter stored in an erased object-shaped Invoke slot. */
    fun isErasedCallableParameter(symbol: IrValueSymbol): Boolean = symbol in erasedCallableParameters

    val locals: List<DotNetIlSlot.Local>
        get() = localSlots

    /** The [runtime helpers][DotNetIlRuntimeHelper] this method body called; see [requireRuntimeHelper]. */
    val requiredRuntimeHelpers: Set<DotNetIlRuntimeHelper>
        get() = requiredHelpers

    /**
     * Records that the body being rendered calls [helper], so that [DotNetIlEmitter] emits the
     * shared runtime helper class into the module. The caller still emits the `call` instruction
     * itself (via [DotNetIlRuntimeHelper.callInstruction]).
     */
    fun requireRuntimeHelper(helper: DotNetIlRuntimeHelper) {
        requiredHelpers += helper
    }

    /** The computed `.maxstack` value; ilasm requires at least 1. */
    val maxStack: Int
        get() = maxOf(maxStackDepth, 1)

    fun emit(instruction: String, pops: Int = 0, pushes: Int = 0) {
        appendIndentedLine(instruction)
        adjustStackDepth(pops, pushes)
        isTerminated = false
    }

    fun emitReturn(pops: Int = 0) {
        // `ret` inside a protected region assembles silently but throws InvalidProgramException
        // at runtime (probe-verified); returns crossing regions must go through emitLeave.
        check(ehRegions.isEmpty()) { "Internal .NET backend error: 'ret' inside a protected region" }
        emit("ret", pops = pops)
        isTerminated = true
    }

    /**
     * Emits `throw`, which pops the exception reference and unconditionally leaves the current
     * emission point (mirrors [emitReturn]; ECMA-335 `throw` never falls through).
     */
    fun emitThrow() {
        emit("throw", pops = 1)
        isTerminated = true
    }

    /**
     * Records a phantom stack value after a `throw` in value position: `IrThrow` has type
     * `kotlin.Nothing`, so a value-position consumer (a `stloc`, an argument list, a trailing
     * `ret`) keeps emitting instructions that expect the value the throw never produces. Those
     * instructions are unreachable — `throw` never falls through, and dead code after it
     * assembles and executes fine (ilasm-probe-verified, including dead `stloc`/`br`/labels/`ret`)
     * — but the stack tracker must stay balanced for them, so the depth is adjusted as if the
     * expression had produced its value.
     */
    fun notePhantomValueAfterThrow() {
        check(isTerminated) { "Internal .NET backend error: phantom stack value outside a terminated emission point" }
        adjustStackDepth(pops = 0, pushes = 1)
    }

    /**
     * Records the phantom result of a value-position `try`/`catch` construct none of whose
     * branches reached its join label: every branch terminated (threw or left toward an outer
     * target such as the return join), so nothing was drained into the result local and the
     * label plus its reload are skipped. Like [notePhantomValueAfterThrow], the consumer's dead
     * instructions still need the tracker to show the value the construct never produces;
     * unlike after a plain `throw`, the depth left behind by the last terminated branch varies
     * (a value-position `throw` leaves its own phantom, a return/break/continue `leave` drains
     * to 0), so the depth is set absolutely — the construct entered at depth 0 (checked at
     * `.try` entry) and its net effect is exactly the one phantom result.
     */
    fun notePhantomValueAtTerminatedTryJoin() {
        check(isTerminated) { "Internal .NET backend error: phantom stack value outside a terminated emission point" }
        stackDepth = 1
        if (maxStackDepth < 1) maxStackDepth = 1
    }

    fun emitBranch(instruction: String, targetLabel: String, pops: Int = 0) {
        appendIndentedLine("$instruction $targetLabel")
        adjustStackDepth(pops, pushes = 0)
        isTerminated = false
        val previousDepth = branchTargetStackDepths.put(targetLabel, stackDepth)
        if (previousDepth != null && previousDepth != stackDepth) {
            error("Internal .NET backend error: inconsistent stack depth at label $targetLabel: $previousDepth vs $stackDepth")
        }
    }

    /** Emits an unconditional `br` and marks the emission point as terminated. */
    fun emitGoto(targetLabel: String) {
        emitBranch("br", targetLabel)
        isTerminated = true
    }

    fun isLabelReferenced(label: String): Boolean =
        label in branchTargetStackDepths

    fun emitLabel(label: String) {
        bodyBuilder.appendLine("$label:")
        stackDepth = branchTargetStackDepths[label] ?: stackDepth
        isTerminated = false
    }

    /**
     * Opens a `.try {` block. The CLR requires an empty evaluation stack at `.try` entry
     * (ECMA-335 I.12.4.2); callers reject or drain operands beforehand, so a non-empty stack
     * here is an internal error. Body text inside exception-handling blocks is indented two
     * extra spaces per region, matching the probe-verified rendered shape.
     */
    fun beginTry() {
        check(stackDepth == 0) { "Internal .NET backend error: non-empty evaluation stack at '.try' entry" }
        appendIndentedLine(".try {")
        ehRegions.addLast(EhRegion.TRY_BODY)
        isTerminated = false
    }

    /**
     * Closes the currently open try body (or preceding handler) and opens a `catch <typeRef> {`
     * handler on the same `.try` — consecutive `catch T {` blocks after one `.try {` are the
     * probe-verified multi-catch shape, matched by the CLR strictly in declaration order.
     * [catchTypeRef] is the bare corelib-qualified reference (the
     * [MappedClass.ilTypeRef][DotNetIlValueType.MappedClass.ilTypeRef] form). At handler entry
     * the CLR discards the evaluation stack and pushes exactly the exception object, so the
     * tracked depth is set to 1 absolutely (also flushing any phantom depth a terminated
     * branch left behind, see [notePhantomValueAfterThrow]).
     */
    fun beginCatch(catchTypeRef: String) {
        check(ehRegions.isNotEmpty()) { "Internal .NET backend error: 'catch' without an open '.try'" }
        ehRegions.removeLast()
        appendIndentedLine("}")
        appendIndentedLine("catch $catchTypeRef {")
        ehRegions.addLast(EhRegion.CATCH_HANDLER)
        stackDepth = 1
        if (maxStackDepth < 1) maxStackDepth = 1
        isTerminated = false
    }

    /**
     * Closes the currently open try body and opens the `finally {` handler of the same `.try` —
     * the probe-verified single-handler shape: a `.try` may carry EITHER catch handlers OR one
     * `finally` (ECMA-335 I.12.4.2; combining them on one `.try` assembles silently but throws
     * `InvalidProgramException` at runtime), so a Kotlin `try`/`catch`/`finally` nests the whole
     * try/catch construct inside an outer `.try`/`finally`. At handler entry the CLR discards
     * the evaluation stack and pushes nothing, so the tracked depth is set to 0 absolutely
     * (also flushing any phantom depth a terminated branch left behind, see
     * [notePhantomValueAfterThrow]).
     */
    fun beginFinally() {
        check(ehRegions.isNotEmpty()) { "Internal .NET backend error: 'finally' without an open '.try'" }
        ehRegions.removeLast()
        appendIndentedLine("}")
        appendIndentedLine("finally {")
        ehRegions.addLast(EhRegion.FINALLY_BODY)
        stackDepth = 0
        isTerminated = false
    }

    /**
     * Emits `endfinally`, the only legal exit from a `finally` body (ECMA-335). When the body
     * itself terminated (ended in a `throw`), the `endfinally` is dead code, which assembles
     * and executes fine (ilasm-probe-verified). Terminates the emission point: the CLR resumes
     * at the `leave` target (or continues unwinding), never at the next instruction.
     */
    fun emitEndFinally() {
        check(ehRegions.lastOrNull() == EhRegion.FINALLY_BODY) { "Internal .NET backend error: 'endfinally' outside a 'finally' body" }
        check(stackDepth == 0) { "Internal .NET backend error: non-empty evaluation stack at 'endfinally'" }
        appendIndentedLine("endfinally")
        isTerminated = true
    }

    /**
     * Closes the final block of a `.try`/`catch` or `.try`/`finally` construct. [isTerminated]
     * is left as-is: every branch ended in `leave`/`throw`/`endfinally`, so the point after the
     * construct is reachable only through the join label the leaves target (whose [emitLabel]
     * resets the flag).
     */
    fun endEhBlock() {
        check(ehRegions.isNotEmpty()) { "Internal .NET backend error: closing an exception-handling block without one open" }
        ehRegions.removeLast()
        appendIndentedLine("}")
    }

    /**
     * Emits `leave <targetLabel>`, the only legal exit from a protected region (ECMA-335;
     * plain `br`/`ret` across a region boundary fail at runtime). `leave` discards the
     * evaluation stack, so branch results are always drained to locals first — a non-empty
     * stack here is an internal error. Terminates the emission point like [emitGoto].
     */
    fun emitLeave(targetLabel: String) {
        check(stackDepth == 0) { "Internal .NET backend error: non-empty evaluation stack at 'leave'" }
        emitBranch("leave", targetLabel)
        isTerminated = true
    }

    /**
     * Declares a compiler-synthesized local slot with no IR symbol behind it — the result local
     * of a `try` expression or the return-value local of returns crossing protected regions.
     * The name is deduplicated like every local name but never enters the symbol map.
     */
    fun declareSyntheticLocal(type: DotNetIlValueType, namePrefix: String): DotNetIlSlot.Local {
        val slot = DotNetIlSlot.Local(
            index = localSlots.size,
            type = type,
            name = uniqueLocalName(namePrefix),
        )
        localSlots += slot
        return slot
    }

    fun declareLocal(variable: IrVariable): DotNetIlSlot.Local {
        slots[variable.symbol]?.let { existingSlot ->
            return existingSlot as? DotNetIlSlot.Local
                ?: dotNetUnsupported("local '${variable.name.asString()}' shadows a parameter")
        }

        val type = typeMapper.toDotNetIlValueType(variable.type)
            ?: dotNetUnsupported("local '${variable.name.asString()}' has unsupported type ${variable.type.render()}")
        val slot = DotNetIlSlot.Local(
            index = localSlots.size,
            type = type,
            name = uniqueLocalName(variable.name.asString()),
        )
        localSlots += slot
        slots[variable.symbol] = slot
        return slot
    }

    fun reference(symbol: IrValueSymbol): DotNetIlSlot =
        slots[symbol] ?: dotNetUnsupported("reference to unsupported value '${symbol.owner.name.asString()}'")

    /**
     * ilasm rejects duplicate names in `.locals init`; distinct IR variables can share a name
     * (e.g. the `<unary>` temporaries of two desugared `x++` statements), so clashes get a
     * `@slotIndex` suffix.
     */
    private fun uniqueLocalName(name: String): String {
        if (usedLocalNames.add(name)) return name
        return "$name@${localSlots.size}".also { usedLocalNames.add(it) }
    }

    fun nextLabel(prefix: String): String = "IL_${prefix}_${labelCounter++}"

    /**
     * Registers the branch targets of a loop currently being emitted, so that `break`/`continue`
     * expressions targeting it can be resolved while its body is rendered.
     */
    fun registerLoop(loop: IrLoop, labels: DotNetIlLoopLabels) {
        loopLabels[loop] = labels
    }

    fun unregisterLoop(loop: IrLoop) {
        loopLabels.remove(loop)
    }

    fun loopLabelsOrNull(loop: IrLoop): DotNetIlLoopLabels? = loopLabels[loop]

    fun renderBody(): String = bodyBuilder.toString()

    /** One body line at the current exception-region indentation (labels stay at column 0). */
    private fun appendIndentedLine(line: String) {
        bodyBuilder.append("    ")
        repeat(ehRegions.size) { bodyBuilder.append("  ") }
        bodyBuilder.appendLine(line)
    }

    private fun adjustStackDepth(pops: Int, pushes: Int) {
        if (stackDepth < pops) {
            error("Internal .NET backend error: IL operand stack underflow")
        }
        stackDepth = stackDepth - pops + pushes
        if (stackDepth > maxStackDepth) {
            maxStackDepth = stackDepth
        }
    }
}

/** The kind of exception-handling region currently being emitted; see [DotNetIlMethodContext.beginTry]. */
private enum class EhRegion { TRY_BODY, CATCH_HANDLER, FINALLY_BODY }

/**
 * Branch targets of a loop: `break` jumps to [breakLabel], `continue` to [continueLabel].
 * [ehDepth] is the exception-region depth at loop registration: a `break`/`continue` emitted at
 * a deeper depth crosses protected regions and must use `leave` instead of `br` (a single
 * `leave` legally crosses any number of nested regions in one hop, probe-verified).
 */
internal class DotNetIlLoopLabels(val breakLabel: String, val continueLabel: String, val ehDepth: Int)

internal sealed class DotNetIlSlot {
    abstract val index: Int
    abstract val type: DotNetIlValueType

    data class Parameter(
        override val index: Int,
        override val type: DotNetIlValueType,
    ) : DotNetIlSlot()

    data class Local(
        override val index: Int,
        override val type: DotNetIlValueType,
        val name: String,
    ) : DotNetIlSlot()
}
