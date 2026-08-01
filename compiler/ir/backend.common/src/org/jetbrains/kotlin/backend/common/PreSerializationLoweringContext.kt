/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.util.KotlinMangler
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl

/**
 * This backend context is used in the first compilation stage. Namely, it is passed to lowerings
 * that are run before serializing IR into a KLIB.
 */
abstract class PreSerializationLoweringContext(
    override val irBuiltIns: IrBuiltIns,
    override val configuration: CompilerConfiguration,
    override val diagnosticReporter: IrDiagnosticReporter,
) : LoweringContext {
    abstract val irMangler: KotlinMangler.IrMangler

    /**
     * Whether non-linking inline-body deserialization may use the dependency's main IR as a body
     * fallback and to bind declarations referenced by a prepared body. Binary-producing KLIB
     * targets normally have a later linker and keep this disabled.
     */
    open val linkInlineFunctionReferencesFromMainIr: Boolean = false

    /** Whether this target may inline functions whose type parameters are reified. */
    open val supportsReifiedInlineFunctions: Boolean = true

    override val irFactory: IrFactory
        get() = IrFactoryImpl

    override var inVerbosePhase: Boolean = false

    val typeSystem: IrTypeSystemContext
        get() = IrTypeSystemContextImpl(irBuiltIns)
}
