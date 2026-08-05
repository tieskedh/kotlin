/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.load.dotnet

/**
 * Version of the in-process carrier for an exact CLR view of one Kotlin contract.
 *
 * The carrier is neither KLIB metadata nor a second contract store. FIR derives it from the
 * authoritative resolved Kotlin contract, and an export consumer may discard it without changing
 * Kotlin compilation or execution.
 */
enum class DotNetExactContractProjectionVersion {
    V1,
}

/** An already validated, additive CLR projection for one Kotlin function. */
data class DotNetExactContractProjection(
    val effects: List<DotNetExactContractEffect>,
    val carrierVersion: DotNetExactContractProjectionVersion =
        DotNetExactContractProjectionVersion.V1,
) {
    init {
        require(effects.isNotEmpty()) { "An exact contract projection must contain an effect" }
        require(effects.distinct().size == effects.size) {
            "An exact contract projection must not contain duplicate effects"
        }
    }
}

/**
 * The complete first export algebra. [valueParameterIndex] addresses Kotlin value parameters;
 * extension receivers and context parameters are deliberately not representable.
 */
sealed interface DotNetExactContractEffect {
    data class ParameterNotNull(val valueParameterIndex: Int) : DotNetExactContractEffect {
        init {
            require(valueParameterIndex >= 0)
        }
    }

    data class ParameterNotNullWhen(
        val valueParameterIndex: Int,
        val returnValue: Boolean,
    ) : DotNetExactContractEffect {
        init {
            require(valueParameterIndex >= 0)
        }
    }

    data class ReturnNotNullIfParameterNotNull(
        val valueParameterIndex: Int,
    ) : DotNetExactContractEffect {
        init {
            require(valueParameterIndex >= 0)
        }
    }

    data class DoesNotReturnIf(
        val valueParameterIndex: Int,
        val parameterValue: Boolean,
    ) : DotNetExactContractEffect {
        init {
            require(valueParameterIndex >= 0)
        }
    }

    data object DoesNotReturn : DotNetExactContractEffect
}
