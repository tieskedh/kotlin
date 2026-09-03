/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.types.Variance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DotNetGenericOwnerPhysicalOperationEmitterSealTest {
    @Test
    fun `owner and caller MethodSpec binders share one exact call-edge seal`() {
        val fixture = SealFixture()

        val ownerBound = fixture.route(fixture.ownerParameter)
        val ownerEdge = fixture.edge(DotNetIlValueType.TypeParameter(0, isMethodParameter = false))
        val sealedOwner = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationSealedCallEdge,
                >>(fixture.seal(ownerBound, ownerEdge, currentMethod = null))
        assertEquals(ownerEdge.methodInstantiation, sealedOwner.value.methodArguments)

        val callerBound = fixture.route(fixture.callerParameter)
        val callerEdge = fixture.edge(DotNetIlValueType.TypeParameter(0, isMethodParameter = true))
        val sealedCaller = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationSealedCallEdge,
                >>(fixture.seal(callerBound, callerEdge))
        assertEquals(callerEdge.methodInstantiation, sealedCaller.value.methodArguments)
        assertEquals(fixture.calleeMethod, sealedCaller.value.method)
    }

    @Test
    fun `equal binder indices cannot conceal wrong MethodDef authority`() {
        val fixture = SealFixture()
        val route = fixture.route(fixture.callerParameter)
        val callerArgument = DotNetIlValueType.TypeParameter(0, isMethodParameter = true)
        val edge = fixture.edge(callerArgument)

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.seal(route, edge, currentMethod = fixture.siblingCallerMethod),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.seal(
                route,
                edge,
                declaredSignature = fixture.declaredSignature.copy(
                    parameterTypes = listOf(
                        fixture.receiverType,
                        DotNetIlValueType.TypeParameter(0, isMethodParameter = false),
                    ),
                ),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.seal(
                route,
                edge.copy(
                    methodInstantiation = listOf(
                        DotNetIlValueType.TypeParameter(0, isMethodParameter = false),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `rendered owner live operands dispatch and result all remain conjunctive`() {
        val fixture = SealFixture()
        val callerArgument = DotNetIlValueType.TypeParameter(0, isMethodParameter = true)
        val route = fixture.route(fixture.callerParameter)
        val edge = fixture.edge(callerArgument)

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.seal(route, edge, ownerToken = "class 'Wrong'<!0>"),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.seal(
                route,
                edge,
                liveSourceCarriers = listOf(fixture.receiverType, DotNetIlValueType.Object),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.seal(route, edge.copy(isVirtual = false)),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.seal(
                route,
                edge.copy(
                    returnType = DotNetIlReturnType.Value(DotNetIlValueType.Object),
                ),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.seal(
                route,
                edge.copy(hasSplitNullableResult = true),
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.seal(
                route.copy(
                    producedResult = checkNotNull(route.producedResult).copy(
                        nullState = DotNetGenericOwnerPhysicalNullState.NON_NULL,
                    ),
                ),
                edge,
            ),
        )
    }

    @Test
    fun `split nullable remains an orthogonal result layout on the same MethodSpec edge`() {
        val fixture = SealFixture()
        val route = fixture.withSplitNullableResult(fixture.route(fixture.ownerParameter))
        val edge = fixture.edge(
            DotNetIlValueType.TypeParameter(0, isMethodParameter = false),
        ).copy(hasSplitNullableResult = true)

        val sealed = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationSealedCallEdge,
                >>(
            fixture.seal(
                route,
                edge,
                declaredSignature = fixture.declaredSignature.copy(
                    hasSplitNullableResult = true,
                ),
            ),
        )
        assertEquals(true, sealed.value.hasSplitNullableResult)
    }

    private class SealFixture {
        val ownerType = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            IrClassSymbolImpl(),
            view = null,
        )
        val naturalType = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            IrClassSymbolImpl(),
            DotNetGenericInterfaceView.DECLARED,
        )
        val calleeMethod = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            IrSimpleFunctionSymbolImpl(),
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        )
        val callerMethod = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            IrSimpleFunctionSymbolImpl(),
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        )
        val siblingCallerMethod = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            IrSimpleFunctionSymbolImpl(),
            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
        )
        val ownerClass = DotNetIlClassInfo(
            "SealOwner`1",
            typeParameterVariances = listOf(Variance.INVARIANT),
        )
        val naturalClass = DotNetIlClassInfo(
            "SealNatural`1",
            typeParameterVariances = listOf(Variance.INVARIANT),
        )
        private val provisional = declarationIndex(
            types = listOf(
                type(ownerType, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                type(naturalType, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            ),
            methods = emptyList(),
        )
        val ownerParameter = boundTypeParameter(provisional, ownerType)
        private val naturalParameter = boundTypeParameter(provisional, naturalType)
        private val calleeParameter =
            DotNetGenericOwnerSymbolicCarrierReference.Parameter.methodParameterReference(
                calleeMethod,
                0,
            )
        private val callee = DotNetGenericOwnerPhysicalMethodDefReference(
            identity = calleeMethod,
            declaringType = naturalType,
            visibility = DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
            dispatch = DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT,
            signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                isInstance = true,
                genericArity = 1,
                resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct(
                    DotNetGenericOwnerPhysicalCallableValueSlotReference(
                        DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT,
                        naturalParameter,
                    ),
                ),
                parameterSlots = listOf(
                    DotNetGenericOwnerPhysicalCallableValueSlotReference(
                        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                        calleeParameter,
                    ),
                ),
            ),
            genericParameters = listOf(unconstrainedParameter()),
        )
        private val declarations = declarationIndex(
            types = listOf(
                type(ownerType, DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
                type(naturalType, DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE),
            ),
            methods = listOf(
                callee,
                callerDescription(callerMethod),
                callerDescription(siblingCallerMethod),
            ),
        )
        val callerParameter = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerSymbolicCarrierReference.Parameter,
                >>(declarations.methodParameterOrError(callerMethod, 0)).value
        private val receiverConstruction = boundConstruction(
            declarations,
            naturalType,
            listOf(ownerParameter),
        )
        val receiverType = DotNetIlValueType.GenericInstance(
            naturalClass,
            listOf(DotNetIlValueType.TypeParameter(0, isMethodParameter = false)),
        )
        val declaredSignature = DotNetIlMethodSignature(
            returnType = DotNetIlReturnType.Value(
                DotNetIlValueType.TypeParameter(0, isMethodParameter = false),
            ),
            parameterTypes = listOf(
                receiverType,
                DotNetIlValueType.TypeParameter(0, isMethodParameter = true),
            ),
            hasThis = true,
            methodGenericParameterCount = 1,
        )
        private val classInfos = mapOf(
            ownerType to ownerClass,
            naturalType to naturalClass,
        )

        fun route(
            methodArgument: DotNetGenericOwnerSymbolicCarrierReference,
        ): DotNetGenericOwnerPhysicalOperationRoute {
            val receiver = directValue(boundCarrier(declarations, receiverConstruction))
            val argument = directValue(boundCarrier(declarations, methodArgument))
            return assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalOperationRoute,
                    >>(
                selectDotNetGenericOwnerPhysicalOperationRoute(
                    declarations,
                    calleeMethod,
                    DotNetGenericOwnerPhysicalOperationRouteRequest(
                        DotNetGenericOwnerPhysicalView(receiverConstruction),
                        listOf(methodArgument),
                    ),
                    receiver,
                    listOf(argument),
                ),
            ).value
        }

        fun edge(methodArgument: DotNetIlValueType) = DotNetIlRawForwardingCallEdge(
            targetFunction = calleeMethod.function,
            targetIdentity = calleeMethod,
            targetPhysicalOwner = naturalClass,
            targetOwner = receiverType,
            methodInstantiation = listOf(methodArgument),
            parameterTypes = listOf(receiverType, methodArgument),
            returnType = DotNetIlReturnType.Value(
                DotNetIlValueType.TypeParameter(0, isMethodParameter = false),
            ),
            hasSplitNullableResult = false,
            isVirtual = true,
        )

        fun withSplitNullableResult(
            route: DotNetGenericOwnerPhysicalOperationRoute,
        ): DotNetGenericOwnerPhysicalOperationRoute {
            val openResult = assertIs<
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct,
                    >(route.method.signature.resultLayout)
            val instantiatedResult = assertIs<
                    DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct,
                    >(route.instantiatedSignature.resultLayout)
            val produced = checkNotNull(route.producedResult)
            val producedCarrier = assertIs<DotNetGenericOwnerProducedValueLayout.Direct>(
                produced.layout,
            ).carrier
            val openSignature = route.method.signature
            val splitOpenSignature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                isInstance = openSignature.isInstance,
                genericArity = openSignature.genericArity,
                resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference
                    .SplitNullable(openResult.slot),
                parameterSlots = openSignature.parameterSlots,
            )
            val instantiatedSignature = route.instantiatedSignature
            return route.copy(
                method = DotNetGenericOwnerPhysicalMethodDefReference(
                    identity = route.method.identity,
                    declaringType = route.method.declaringType,
                    visibility = route.method.visibility,
                    dispatch = route.method.dispatch,
                    signature = splitOpenSignature,
                    genericParameters = route.method.genericParameters,
                ),
                instantiatedSignature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                    isInstance = instantiatedSignature.isInstance,
                    genericArity = instantiatedSignature.genericArity,
                    resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference
                        .SplitNullable(instantiatedResult.slot),
                    parameterSlots = instantiatedSignature.parameterSlots,
                ),
                producedResult = produced.copy(
                    layout = DotNetGenericOwnerProducedValueLayout.SplitNullable(producedCarrier),
                    nullState = DotNetGenericOwnerPhysicalNullState.MAYBE_NULL,
                ),
            )
        }

        fun seal(
            route: DotNetGenericOwnerPhysicalOperationRoute,
            edge: DotNetIlRawForwardingCallEdge,
            ownerToken: String = receiverType.nameInSignature,
            declaredSignature: DotNetIlMethodSignature = this.declaredSignature,
            liveSourceCarriers: List<DotNetIlValueType?> = edge.parameterTypes,
            currentMethod: DotNetGenericOwnerPhysicalMethodDefIdentity.Local? = callerMethod,
        ) = sealDotNetLocalGenericOwnerPhysicalOperationCallEdge(
            route = route,
            edge = edge,
            ownerToken = ownerToken,
            declaredSignature = declaredSignature,
            liveSourceCarriers = liveSourceCarriers,
            currentTypeOwner = ownerClass,
            currentMethod = currentMethod,
            currentMethodGenericArity = 1,
            classInfo = classInfos::get,
        )

        private fun directValue(
            carrier: DotNetGenericOwnerPhysicalCarrier,
        ): DotNetGenericOwnerProducedValueFact {
            val directView = (carrier.type as?
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed)
                ?.let(::DotNetGenericOwnerPhysicalView)
            val guaranteedViews = directView?.let {
                DotNetGenericOwnerGuaranteedViews.Known(
                    mapOf(
                        it to setOf(
                            DotNetGenericOwnerPhysicalViewEvidence.IDENTITY_PRESERVING_TRANSFER,
                        ),
                    ),
                )
            } ?: DotNetGenericOwnerGuaranteedViews.Unknown
            return DotNetGenericOwnerProducedValueFact(
                DotNetGenericOwnerProducedValueLayout.Direct(carrier),
                DotNetGenericOwnerPhysicalValueProvenance(guaranteedViews),
                DotNetGenericOwnerPhysicalNullState.NON_NULL,
            )
        }

        private fun boundCarrier(
            declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
            type: DotNetGenericOwnerSymbolicCarrierReference,
        ) = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalCarrier,
                >>(declarations.carrierOrError(type)).value

        private fun boundTypeParameter(
            declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
            owner: DotNetGenericOwnerPhysicalTypeDefIdentity,
        ) = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerSymbolicCarrierReference.Parameter,
                >>(declarations.typeParameterOrError(owner, 0)).value

        private fun boundConstruction(
            declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
            owner: DotNetGenericOwnerPhysicalTypeDefIdentity,
            arguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
        ) = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerSymbolicCarrierReference.Constructed,
                >>(declarations.constructTypeOrError(owner, arguments)).value

        private fun callerDescription(
            identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
        ) = DotNetGenericOwnerPhysicalMethodDefReference(
            identity = identity,
            declaringType = ownerType,
            visibility = DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC,
            dispatch = DotNetGenericOwnerPhysicalMemberDispatch.FINAL,
            signature = DotNetGenericOwnerPhysicalMethodSignatureReference(
                isInstance = true,
                genericArity = 1,
                resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void,
                parameterSlots = emptyList(),
            ),
            genericParameters = listOf(unconstrainedParameter()),
        )

        private fun type(
            identity: DotNetGenericOwnerPhysicalTypeDefIdentity,
            category: DotNetGenericOwnerPhysicalNamedTypeCategory,
        ) = DotNetGenericOwnerPhysicalTypeDefReference(
            identity,
            listOf(unconstrainedParameter()),
            category,
        )

        private fun declarationIndex(
            types: List<DotNetGenericOwnerPhysicalTypeDefReference>,
            methods: List<DotNetGenericOwnerPhysicalMethodDefReference>,
        ) = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                types,
                methods,
                emptyList(),
            ),
        ).value

        private fun unconstrainedParameter() =
            DotNetGenericOwnerPhysicalGenericParameterReference(
                DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                constraints = emptyList(),
            )
    }
}
