/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DotNetGenericOwnerSemanticEquivalenceForwardingEvidenceTest {
    @Test
    fun `two exact final call edges form one known certificate input`() {
        val fixture = Fixture()

        val evidence = fixture.inspect(fixture.observations())

        val known = assertIs<DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Known>(evidence)
        assertEquals(
            DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind.entries.toSet(),
            known.edges.mapTo(linkedSetOf()) { edge -> edge.kind },
        )
    }

    @Test
    fun `method generic edges preserve every exact MethodSpec binder position`() {
        val fixture = Fixture(methodGenericArity = 2)

        val known = assertIs<DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Known>(
            fixture.inspect(fixture.observations()),
        )

        assertEquals(listOf(2, 2), known.edges.map { edge -> edge.methodGenericArity })
    }

    @Test
    fun `reordered MethodSpec binder positions conflict`() {
        val fixture = Fixture(methodGenericArity = 2)
        val observations = fixture.observations()
        val classIndex = observations.indexOfFirst { observation ->
            observation.physicalFunction === fixture.classDispatcher
        }
        val classObservation = observations[classIndex]
        val forwarding = assertIs<DotNetGenericOwnerPhysicalForwardingBodyEvidence.Forwarding>(
            classObservation.forwardingBodyEvidence,
        )
        val reordered = observations.toMutableList().apply {
            this[classIndex] = classObservation.copy(
                forwardingBodyEvidence = DotNetGenericOwnerPhysicalForwardingBodyEvidence.Forwarding(
                    forwarding.edge.copy(
                        methodInstantiation = forwarding.edge.methodInstantiation.reversed(),
                    ),
                ),
            )
        }

        assertIs<DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Conflict>(
            fixture.inspect(reordered),
        )
    }

    @Test
    fun `call result and target header must describe the same instantiated signature`() {
        val fixture = Fixture()
        val observations = fixture.observations()
        val classIndex = observations.indexOfFirst { observation ->
            observation.physicalFunction === fixture.classDispatcher
        }
        val classObservation = observations[classIndex]
        val forwarding = assertIs<DotNetGenericOwnerPhysicalForwardingBodyEvidence.Forwarding>(
            classObservation.forwardingBodyEvidence,
        )
        val changedCallResult = observations.toMutableList().apply {
            this[classIndex] = classObservation.copy(
                forwardingBodyEvidence = DotNetGenericOwnerPhysicalForwardingBodyEvidence.Forwarding(
                    forwarding.edge.copy(returnCarrier = fixture.booleanCarrier),
                ),
            )
        }
        val targetIndex = observations.indexOfFirst { observation ->
            observation.physicalFunction === fixture.typedEntry
        }
        val changedTargetHeader = observations.toMutableList().apply {
            this[targetIndex] = observations[targetIndex].copy(
                signature = observations[targetIndex].signature.copy(
                    returnCarrier = fixture.booleanCarrier,
                ),
            )
        }

        assertIs<DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Conflict>(
            fixture.inspect(changedCallResult),
        )
        assertIs<DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Conflict>(
            fixture.inspect(changedTargetHeader),
        )
    }

    @Test
    fun `forwarding must preserve the exact receiver construction`() {
        val fixture = Fixture()
        val observations = fixture.observations()
        val classIndex = observations.indexOfFirst { observation ->
            observation.physicalFunction === fixture.classDispatcher
        }
        val classObservation = observations[classIndex]
        val forwarding = assertIs<DotNetGenericOwnerPhysicalForwardingBodyEvidence.Forwarding>(
            classObservation.forwardingBodyEvidence,
        )
        val changedTargetOwner = observations.toMutableList().apply {
            this[classIndex] = classObservation.copy(
                forwardingBodyEvidence = DotNetGenericOwnerPhysicalForwardingBodyEvidence.Forwarding(
                    forwarding.edge.copy(targetOwner = fixture.otherOwnerCarrier),
                ),
            )
        }
        val changedBodyReceiver = observations.toMutableList().apply {
            this[classIndex] = classObservation.copy(
                signature = classObservation.signature.copy(
                    receiverCarrier = fixture.otherOwnerCarrier,
                ),
            )
        }

        assertIs<DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Conflict>(
            fixture.inspect(changedTargetOwner),
        )
        assertIs<DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Conflict>(
            fixture.inspect(changedBodyReceiver),
        )
    }

    @Test
    fun `known evidence requires the exact role mapping`() {
        val fixture = Fixture()
        val known = assertIs<DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Known>(
            fixture.inspect(fixture.observations()),
        )
        val first = known.edges.first()

        assertFailsWith<IllegalArgumentException> {
            DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Known(
                listOf(
                    first.copy(
                        bodyMethodKind = first.targetMethodKind,
                        targetMethodKind = first.bodyMethodKind,
                    ),
                    known.edges.last(),
                ),
            )
        }
    }

    @Test
    fun `missing forwarding evidence remains unavailable`() {
        val fixture = Fixture()
        val observations = fixture.observations().map { observation ->
            if (observation.physicalFunction === fixture.classDispatcher) {
                observation.copy(forwardingBodyEvidence = null)
            } else {
                observation
            }
        }

        assertIs<DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Unavailable>(
            fixture.inspect(observations),
        )
    }

    @Test
    fun `wrong target and duplicate body observations conflict`() {
        val fixture = Fixture()
        val observations = fixture.observations()
        val classIndex = observations.indexOfFirst { observation ->
            observation.physicalFunction === fixture.classDispatcher
        }
        val classObservation = observations[classIndex]
        val forwarding = assertIs<DotNetGenericOwnerPhysicalForwardingBodyEvidence.Forwarding>(
            classObservation.forwardingBodyEvidence,
        )
        val wrongTarget = IrSimpleFunctionSymbolImpl()
        val wrongTargetObservations = observations.toMutableList().apply {
            this[classIndex] = classObservation.copy(
                forwardingBodyEvidence = DotNetGenericOwnerPhysicalForwardingBodyEvidence.Forwarding(
                    forwarding.edge.copy(targetFunction = wrongTarget),
                ),
            )
        }

        assertIs<DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Conflict>(
            fixture.inspect(wrongTargetObservations),
        )
        assertIs<DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Conflict>(
            fixture.inspect(observations + classObservation),
        )
    }

    @Test
    fun `another physical scope cannot contribute a forwarding body`() {
        val fixture = Fixture()
        val observations = fixture.observations()
        val classObservation = observations.single { observation ->
            observation.physicalFunction === fixture.classDispatcher
        }

        assertIs<DotNetGenericOwnerSemanticEquivalenceForwardingEvidence.Conflict>(
            fixture.inspect(observations, listOf(classObservation)),
        )
    }

    private class Fixture(
        private val methodGenericArity: Int = 0,
    ) {
        val typedEntry = IrSimpleFunctionSymbolImpl()
        val classDispatcher = IrSimpleFunctionSymbolImpl()
        private val interfaceDispatcher = IrSimpleFunctionSymbolImpl()
        private val typedIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            typedEntry,
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        )
        private val classDispatcherIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            typedEntry,
            DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
        )
        private val interfaceDispatcherIdentity = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            interfaceDispatcher,
            role = null,
        )
        private val observedOwner = DotNetGenericOwnerObservedLocalTypeDef(
            physicalKey = DotNetGenericOwnerObservedPhysicalTypeDefKey(0),
            identity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                IrClassSymbolImpl(),
                DotNetGenericInterfaceView.DECLARED,
            ),
            genericArity = 0,
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
        )
        private val owner = DotNetGenericOwnerObservedMethodDefOwner.Local(observedOwner)
        private val objectCarrier = DotNetGenericOwnerObservedMethodCarrier.Leaf(
            DotNetGenericOwnerPhysicalTypeKind.OBJECT,
        )
        val booleanCarrier = DotNetGenericOwnerObservedMethodCarrier.Leaf(
            DotNetGenericOwnerPhysicalTypeKind.BOOLEAN,
        )
        private val ownerCarrier = DotNetGenericOwnerObservedMethodCarrier.LocalConstruction(
            observedOwner,
            emptyList(),
        )
        val otherOwnerCarrier = DotNetGenericOwnerObservedMethodCarrier.LocalConstruction(
            DotNetGenericOwnerObservedLocalTypeDef(
                physicalKey = DotNetGenericOwnerObservedPhysicalTypeDefKey(1),
                identity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                    IrClassSymbolImpl(),
                    DotNetGenericInterfaceView.DECLARED,
                ),
                genericArity = 0,
                category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
            ),
            emptyList(),
        )

        private val expectedEdges = listOf(
            expected(
                DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind
                    .CLASS_DISPATCHER_TO_TYPED_ENTRY,
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                    .CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
                classDispatcher,
                classDispatcherIdentity,
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.IMPLEMENTATION_TYPED_ENTRY,
                typedEntry,
                typedIdentity,
            ),
            expected(
                DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind
                    .INTERFACE_DISPATCHER_TO_CLASS_DISPATCHER,
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                    .INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
                interfaceDispatcher,
                interfaceDispatcherIdentity,
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                    .CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
                classDispatcher,
                classDispatcherIdentity,
            ),
        )

        fun observations() = listOf(
            header(typedEntry, typedIdentity),
            header(
                classDispatcher,
                classDispatcherIdentity,
                forwarding(
                    classDispatcher,
                    classDispatcherIdentity,
                    typedEntry,
                    typedIdentity,
                ),
            ),
            header(
                interfaceDispatcher,
                interfaceDispatcherIdentity,
                forwarding(
                    interfaceDispatcher,
                    interfaceDispatcherIdentity,
                    classDispatcher,
                    classDispatcherIdentity,
                ),
            ),
        )

        fun inspect(
            observations: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation>,
            other: List<DotNetGenericOwnerPhysicalMethodDefHeaderObservation> = emptyList(),
        ) = inspectDotNetGenericOwnerSemanticEquivalenceForwardingBodies(
            expectedEdges,
            observations,
            other,
        )

        private fun expected(
            kind: DotNetGenericOwnerSemanticEquivalenceForwardingEdgeKind,
            bodyKind: DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
            bodyFunction: IrSimpleFunctionSymbol,
            bodyIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
            targetKind: DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
            targetFunction: IrSimpleFunctionSymbol,
            targetIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
        ) = DotNetGenericOwnerExpectedSemanticEquivalenceForwardingEdge(
            kind,
            bodyKind,
            bodyFunction,
            bodyIdentity,
            targetKind,
            targetFunction,
            targetIdentity,
        )

        private fun header(
            function: IrSimpleFunctionSymbol,
            identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
            forwarding: DotNetGenericOwnerPhysicalForwardingBodyEvidence? = null,
        ) = DotNetGenericOwnerPhysicalMethodDefHeaderObservation(
            physicalFunction = function,
            physicalMethodIdentity = identity,
            physicalMethodOwner = owner,
            physicalMethodName = "method",
            visibility = DotNetIlRawMethodDefVisibility.PRIVATE,
            dispatch = DotNetIlRawMethodDefDispatch(
                isInstance = true,
                isVirtual = false,
                isNewSlot = false,
                isAbstract = false,
                isFinal = false,
            ),
            isHideBySig = true,
            isSpecialName = false,
            isRuntimeSpecialName = false,
            genericArity = methodGenericArity,
            genericParameters = List(methodGenericArity) { index ->
                DotNetGenericOwnerPhysicalMethodDefGenericParameterObservation(
                    physicalName = "T$index",
                    variance = DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                    constraints = emptyList(),
                )
            },
            signature = DotNetGenericOwnerObservedMethodSignature(
                receiverCarrier = ownerCarrier,
                returnCarrier = objectCarrier,
                parameterCarriers = emptyList(),
                hasSplitNullableResult = false,
            ),
            forwardingBodyEvidence = forwarding,
        )

        private fun forwarding(
            bodyFunction: IrSimpleFunctionSymbol,
            bodyIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
            targetFunction: IrSimpleFunctionSymbol,
            targetIdentity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
        ) = DotNetGenericOwnerPhysicalForwardingBodyEvidence.Forwarding(
            DotNetGenericOwnerPhysicalForwardingCallEdge(
                targetFunction = targetFunction,
                targetIdentity = targetIdentity,
                targetPhysicalOwner = owner,
                targetOwner = ownerCarrier,
                methodInstantiation = List(methodGenericArity) { index ->
                    DotNetGenericOwnerObservedMethodCarrier.MethodParameter(
                        physicalOwner = observedOwner,
                        physicalFunction = bodyFunction,
                        physicalMethodIdentity = bodyIdentity,
                        index = index,
                    )
                },
                parameterCarriers = listOf(ownerCarrier),
                returnCarrier = objectCarrier,
                hasSplitNullableResult = false,
                isVirtual = false,
            ),
        )
    }
}
