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
) {
    private val bodyBuilder = StringBuilder()
    private val slots = hashMapOf<IrValueSymbol, DotNetIlSlot>()
    private val localSlots = mutableListOf<DotNetIlSlot.Local>()
    private val usedLocalNames = hashSetOf<String>()
    private val branchTargetStackDepths = hashMapOf<String, Int>()
    private val loopLabels = hashMapOf<IrLoop, DotNetIlLoopLabels>()
    private val requiredHelpers = linkedSetOf<DotNetIlRuntimeHelper>()
    private var labelCounter = 0
    private var stackDepth = 0
    private var maxStackDepth = 0

    /**
     * Whether the last emitted instruction unconditionally leaves the current emission point
     * (`ret` or an unconditional `br`). Used to suppress dead branches after a mid-body return,
     * `break`, or `continue`: a dead `br` targeting the end of a method is rejected by the CLR.
     */
    var isTerminated: Boolean = false
        private set

    init {
        parameters.zip(parameterTypes).forEachIndexed { index, (parameter, type) ->
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
        bodyBuilder.appendLine("    $instruction")
        adjustStackDepth(pops, pushes)
        isTerminated = false
    }

    fun emitReturn(pops: Int = 0) {
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

    fun emitBranch(instruction: String, targetLabel: String, pops: Int = 0) {
        bodyBuilder.appendLine("    $instruction $targetLabel")
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

/** Branch targets of a loop: `break` jumps to [breakLabel], `continue` to [continueLabel]. */
internal class DotNetIlLoopLabels(val breakLabel: String, val continueLabel: String)

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
