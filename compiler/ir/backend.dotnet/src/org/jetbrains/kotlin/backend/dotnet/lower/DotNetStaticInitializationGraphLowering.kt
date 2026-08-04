/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetLoweredStaticInitialization
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalLibraries
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isPublishedApi
import org.jetbrains.kotlin.name.Name

internal val DOTNET_STATIC_INITIALIZATION_ENTRY: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_STATIC_INITIALIZATION_ENTRY")

/**
 * Materializes Kotlin's classifier-initialization graph on CLR type initializers.
 *
 * A call to the stable entry is intentionally empty: invoking a static method on an explicitly
 * initialized CLR type enters that type's `.cctor` under the CLR's once-only synchronization.
 * The `.cctor` first calls producer-recorded entries for the superclass and for direct
 * superinterfaces which declare a non-abstract instance member, then executes the classifier's
 * own initializers in their existing source order. This follows the common static-initialization
 * graph; an abstract-only implemented interface is deliberately not initialized as a side effect.
 *
 * Existing companion state keeps using its selected non-generic companion-static holder. A
 * logically generic classifier or interface which only needs an initialization event likewise
 * receives a dedicated non-generic static-initialization holder; this preserves the established
 * static-placement ABI while Kotlin-owned class TypeDefs themselves are now erased. External
 * edges are represented by synthetic IR calls whose physical owner and method come exclusively
 * from the producer's KLIB physical index.
 */
internal class DotNetStaticInitializationGraphLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    private val externalDeclarations = DotNetExternalDeclarations(context.configuration.dotNetExternalLibraries)
    private val localClasses = linkedSetOf<IrClass>()
    private val processing = hashSetOf<IrClass>()
    private val processed = hashMapOf<IrClass, IrSimpleFunction?>()
    private val externalEntries = hashMapOf<IrClass, IrSimpleFunction?>()

    override fun lower(irModule: IrModuleFragment) {
        for (file in irModule.files) {
            file.declarations.filterIsInstance<IrClass>().forEach(::collectClass)
        }
        localClasses.forEach(::entryFor)
    }

    private fun collectClass(irClass: IrClass) {
        localClasses += irClass
        irClass.declarations.filterIsInstance<IrClass>().forEach(::collectClass)
    }

    private fun entryFor(irClass: IrClass): IrSimpleFunction? {
        if (irClass !in localClasses) return externalEntryFor(irClass)
        if (processed.containsKey(irClass)) return processed[irClass]
        check(processing.add(irClass)) {
            "Internal .NET backend error: cyclic classifier-initialization graph at '${irClass.name}'"
        }
        val result = createLocalEntry(irClass)
        processing.remove(irClass)
        processed[irClass] = result
        return result
    }

    private fun createLocalEntry(irClass: IrClass): IrSimpleFunction? {
        if (irClass.isCompanion || irClass.origin == DOTNET_STATIC_HOLDER) return null

        val companionObject = irClass.declarations.filterIsInstance<IrClass>().firstOrNull(IrClass::isCompanion)
        val staticOwner = context.companionStaticOwners[irClass]

        val dependencies = dependencyClasses(irClass).mapNotNull(::entryFor)
        val hasOwnInitializer =
            staticOwner?.staticInitializerOrNull() != null || irClass.staticInitializerOrNull() != null
        if (!hasOwnInitializer && dependencies.isEmpty()) return null

        val physicalOwner = when {
            staticOwner != null -> staticOwner
            companionObject != null -> irClass
            irClass.isInterface || irClass.typeParameters.isNotEmpty() -> createInitializationHolder(irClass)
            else -> irClass
        }
        val physicalInitializer = physicalOwner.staticInitializerOrNull()
            ?: buildDotNetStaticInitializer(context, physicalOwner, emptyList()).also {
                physicalOwner.declarations += it
            }
        prependCalls(physicalInitializer, dependencies)

        val entry = createEntry(irClass, physicalOwner)
        physicalOwner.ensureInitializationEntryIsAccessible(entry)
        physicalOwner.declarations.add(0, entry)
        context.staticInitializations[irClass] =
            DotNetLoweredStaticInitialization(physicalOwner, entry)
        return entry
    }

    private fun externalEntryFor(irClass: IrClass): IrSimpleFunction? {
        if (externalEntries.containsKey(irClass)) return externalEntries[irClass]
        val binding = externalDeclarations.staticInitializationOrNull(irClass)
        val entry = binding?.let {
            context.irFactory.buildFun {
                name = Name.special("<EnsureInitialized>")
                returnType = context.irBuiltIns.unitType
                visibility = DescriptorVisibilities.PUBLIC
                origin = DOTNET_STATIC_INITIALIZATION_ENTRY
            }.apply {
                parent = irClass
                context.externalStaticInitializations[this] = binding
                context.externalStaticInitializationEntries[irClass] = this
            }
        }
        externalEntries[irClass] = entry
        return entry
    }

    private fun dependencyClasses(irClass: IrClass): List<IrClass> {
        val direct = irClass.superTypes
            .filterNot { it.isAny() }
            .mapNotNull { it.classOrNull?.owner }
        val superclasses = direct.filterNot(IrClass::isInterface)
        val interfaces = direct.filter(IrClass::isInterface).filter(::declaresNonAbstractInstanceMember)
        return superclasses + interfaces
    }

    private fun declaresNonAbstractInstanceMember(irInterface: IrClass): Boolean =
        irInterface.declarations.any { declaration ->
            when (declaration) {
                is IrSimpleFunction -> declaration.isKotlinDefaultBearingInstanceMember()
                is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
                    .any { it.isKotlinDefaultBearingInstanceMember() }
                else -> false
            }
        }

    private fun IrSimpleFunction.isKotlinDefaultBearingInstanceMember(): Boolean {
        if (isFakeOverride || dispatchReceiverParameter == null) return false
        val owningClass = (parent as? IrClass) ?: ((parent as? IrProperty)?.parent as? IrClass)
        return if (owningClass in localClasses) {
            this in context.interfaceDefaultImplementations ||
                    origin == IrDeclarationOrigin.DEFINED && modality != Modality.ABSTRACT
        } else {
            modality != Modality.ABSTRACT
        }
    }

    private fun createInitializationHolder(owner: IrClass): IrClass = context.irFactory.buildClass {
        origin = DOTNET_STATIC_HOLDER
        name = Name.special("<StaticInitialization>")
        kind = ClassKind.CLASS
        modality = Modality.FINAL
        visibility = when {
            owner.visibility == DescriptorVisibilities.PUBLIC ||
                    owner.visibility == DescriptorVisibilities.PROTECTED || owner.isPublishedApi() ->
                DescriptorVisibilities.PUBLIC
            owner.visibility == DescriptorVisibilities.INTERNAL -> DescriptorVisibilities.INTERNAL
            else -> DescriptorVisibilities.INTERNAL
        }
    }.apply {
        parent = owner
        superTypes = listOf(context.irBuiltIns.anyType)
        createThisReceiverParameter()
        owner.declarations.add(0, this)
    }

    private fun createEntry(logicalOwner: IrClass, physicalOwner: IrClass): IrSimpleFunction =
        context.irFactory.buildFun {
            name = Name.special("<EnsureInitialized>")
            returnType = context.irBuiltIns.unitType
            visibility = when {
                logicalOwner.visibility == DescriptorVisibilities.PUBLIC ||
                        logicalOwner.visibility == DescriptorVisibilities.PROTECTED || logicalOwner.isPublishedApi() ->
                    DescriptorVisibilities.PUBLIC
                else -> DescriptorVisibilities.INTERNAL
            }
            origin = DOTNET_STATIC_INITIALIZATION_ENTRY
        }.apply {
            parent = physicalOwner
            body = context.createIrBuilder(symbol).irBlockBody { }
        }

    private fun IrClass.ensureInitializationEntryIsAccessible(entry: IrSimpleFunction) {
        if (origin != DOTNET_STATIC_HOLDER) return
        visibility = when {
            entry.visibility == DescriptorVisibilities.PUBLIC -> DescriptorVisibilities.PUBLIC
            visibility == DescriptorVisibilities.PRIVATE || visibility == DescriptorVisibilities.PROTECTED ->
                DescriptorVisibilities.INTERNAL
            else -> visibility
        }
    }

    private fun prependCalls(initializer: IrSimpleFunction, entries: List<IrSimpleFunction>) {
        if (entries.isEmpty()) return
        val body = initializer.body as? IrBlockBody
            ?: error("Internal .NET backend error: CLR static initializer has no block body")
        val builder = context.createIrBuilder(initializer.symbol)
        body.statements.addAll(0, entries.map { builder.irCall(it.symbol) })
    }

    private fun IrClass.staticInitializerOrNull(): IrSimpleFunction? =
        declarations.filterIsInstance<IrSimpleFunction>()
            .singleOrNull { it.origin == DOTNET_STATIC_INITIALIZER }
}
