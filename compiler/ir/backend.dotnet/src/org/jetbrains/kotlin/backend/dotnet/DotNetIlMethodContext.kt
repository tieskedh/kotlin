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
 */
internal class DotNetIlMethodContext(
    parameters: List<IrValueParameter>,
    parameterTypes: List<DotNetIlValueType>,
) {
    private val bodyBuilder = StringBuilder()
    private val slots = hashMapOf<IrValueSymbol, DotNetIlSlot>()
    private val localSlots = mutableListOf<DotNetIlSlot.Local>()
    private val usedLocalNames = hashSetOf<String>()
    private val branchTargetStackDepths = hashMapOf<String, Int>()
    private val loopLabels = hashMapOf<IrLoop, DotNetIlLoopLabels>()
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
            slots[parameter.symbol] = DotNetIlSlot.Parameter(index, type)
        }
    }

    val locals: List<DotNetIlSlot.Local>
        get() = localSlots

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

        val type = variable.type.toDotNetIlValueType()
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
