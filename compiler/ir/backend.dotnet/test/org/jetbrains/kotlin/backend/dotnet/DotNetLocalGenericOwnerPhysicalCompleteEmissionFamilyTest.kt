/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import kotlin.test.Test
import kotlin.test.assertIs

class DotNetLocalGenericOwnerPhysicalCompleteEmissionFamilyTest {
    @Test
    fun `complete family binds the coherent method and MethodImpl matrix`() {
        val fixture = CompleteFamilyFixture()

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily,
                >>(fixture.bind(fixture.input))
    }

    @Test
    fun `complete family binds a coherent method generic parameter vector`() {
        val fixture = CompleteFamilyFixture(methodGeneric = true)

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily,
                >>(fixture.bind(fixture.input))
    }

    @Test
    fun `complete family rejects a method parameter bound to its sibling MethodDef`() {
        val fixture = CompleteFamilyFixture(methodGeneric = true)
        val corruptedKind =
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_DISPATCHER
        val sibling = fixture.methodIdentity(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind
                .INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.bind(
                fixture.input,
                methodParameterBinders = mapOf(corruptedKind to sibling),
            ),
        )
    }

    @Test
    fun `complete family rejects every wrong method identity role or root`() {
        val fixture = CompleteFamilyFixture()

        for (kind in DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.entries) {
            val wrongRole = fixture.replaceMethod(kind) { method ->
                method.copy(identity = method.identity.copy(
                    role = DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                ))
            }
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
                fixture.bind(wrongRole),
                "wrong role for $kind",
            )
            val wrongRoot = fixture.replaceMethod(kind) { method ->
                method.copy(identity = method.identity.copy(function = IrSimpleFunctionSymbolImpl()))
            }
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
                fixture.bind(wrongRoot),
                "wrong identity root for $kind",
            )
        }
    }

    @Test
    fun `complete family rejects swapped MethodImpl bodies`() {
        val fixture = CompleteFamilyFixture()
        val classDispatcher = fixture.methodIdentity(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        )
        val interfaceDispatcher = fixture.methodIdentity(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
        )
        val corruptions = listOf(
            fixture.replaceMethodImpl(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION,
            ) { methodImpl -> methodImpl.copy(body = interfaceDispatcher) },
            fixture.replaceMethodImpl(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.INTERFACE_SEMANTIC_CAPABILITY_IMPLEMENTATION,
            ) { methodImpl -> methodImpl.copy(body = classDispatcher) },
        )

        corruptions.forEachIndexed { index, corrupted ->
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
                fixture.bind(corrupted),
                "swapped MethodImpl body $index",
            )
        }
    }

    @Test
    fun `complete family rejects an unselected MethodImpl declaration endpoint`() {
        val fixture = CompleteFamilyFixture()
        val extraDeclaration = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            IrSimpleFunctionSymbolImpl(),
            role = null,
        )
        val corrupted = fixture.replaceMethodImpl(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION,
        ) { methodImpl -> methodImpl.copy(declaration = extraDeclaration) }

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.bind(
                corrupted,
                extraMethods = listOf(fixture.methodDescription(
                    extraDeclaration,
                    fixture.type(
                        DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY,
                    ),
                )),
            ),
        )
    }

    @Test
    fun `complete family rejects a MethodImpl on another implementation class`() {
        val fixture = CompleteFamilyFixture()
        val otherImplementation = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
            IrClassSymbolImpl(),
            view = null,
        )
        val otherBody = DotNetGenericOwnerPhysicalMethodDefIdentity.Local(
            IrSimpleFunctionSymbolImpl(),
            DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
        )
        val corrupted = fixture.replaceMethodImpl(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION,
        ) { methodImpl ->
            methodImpl.copy(
                implementingType = otherImplementation,
                body = otherBody,
            )
        }

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            fixture.bind(
                corrupted,
                extraTypes = listOf(DotNetGenericOwnerPhysicalTypeDefReference(
                    otherImplementation,
                    genericArity = 0,
                    category = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                )),
                extraMethods = listOf(fixture.methodDescription(otherBody, otherImplementation)),
            ),
        )
    }

    private inner class CompleteFamilyFixture(
        private val methodGeneric: Boolean = false,
    ) {
        private val logicalMember = IrSimpleFunctionSymbolImpl()
        private val implementationMember = IrSimpleFunctionSymbolImpl()
        private val interfaceCapabilityMember = IrSimpleFunctionSymbolImpl()
        private val classCapabilityMember = IrSimpleFunctionSymbolImpl()
        private val classDispatcher = IrSimpleFunctionSymbolImpl()
        private val interfaceDispatcher = IrSimpleFunctionSymbolImpl()

        private val naturalOwner = IrClassSymbolImpl()
        private val types = linkedMapOf(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE to
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                        naturalOwner,
                        DotNetGenericInterfaceView.DECLARED,
                    ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.INTERFACE_SEMANTIC_CAPABILITY to
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local(IrClassSymbolImpl(), view = null),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS to
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local(IrClassSymbolImpl(), view = null),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY to
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local(IrClassSymbolImpl(), view = null),
        )
        private val inputs = linkedMapOf(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE to typeInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE,
                genericArity = 1,
                role = DotNetLocalGenericOwnerPhysicalTypeRole.NATURAL_INTERFACE,
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.INTERFACE_SEMANTIC_CAPABILITY to typeInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.INTERFACE_SEMANTIC_CAPABILITY,
                genericArity = 0,
                role = DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY,
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS to typeInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS,
                genericArity = 1,
                role = DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS,
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY to typeInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY,
                genericArity = 0,
                role = DotNetLocalGenericOwnerPhysicalTypeRole.SEMANTIC_CAPABILITY,
            ),
        )
        private val aliases = types.mapValuesTo(linkedMapOf()) { entry ->
            if (entry.key == DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE) {
                listOf(
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                        naturalOwner,
                        DotNetGenericInterfaceView.CANONICAL,
                    ),
                    entry.value,
                )
            } else {
                listOf(entry.value)
            }
        }
        private val typeParameters = linkedMapOf(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE to listOf(
                DotNetGenericOwnerPhysicalGenericParameterReference(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                    constraints = emptyList(),
                ),
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.INTERFACE_SEMANTIC_CAPABILITY to emptyList(),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS to listOf(
                DotNetGenericOwnerPhysicalGenericParameterReference(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                    constraints = emptyList(),
                ),
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY to emptyList(),
        )
        private val methodInputs = listOf(
            methodInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.NATURAL_INTERFACE_SLOT,
                logicalMember,
                logicalMember,
                DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
            ),
            methodInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.INTERFACE_SEMANTIC_CAPABILITY_SLOT,
                interfaceCapabilityMember,
                interfaceCapabilityMember,
                role = null,
            ),
            methodInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.IMPLEMENTATION_TYPED_ENTRY,
                implementationMember,
                implementationMember,
                DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
            ),
            methodInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_SLOT,
                classCapabilityMember,
                classCapabilityMember,
                role = null,
            ),
            methodInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
                classDispatcher,
                implementationMember,
                DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
            ),
            methodInput(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
                interfaceDispatcher,
                interfaceDispatcher,
                role = null,
            ),
        )
        private val provisional = boundDeclarationIndex(
            inputs.values.map(DotNetLocalGenericOwnerPhysicalTypeInput::asReference),
            methodDefinitions(methodInputs),
        )
        private val implementationParameter = boundTypeParameter(
            provisional,
            type(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS),
            0,
        )
        private val naturalConstruction = boundConstruction(
            provisional,
            type(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE),
            listOf(implementationParameter),
        )
        private val interfaceCapabilityConstruction = nonGenericConstruction(
            provisional,
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.INTERFACE_SEMANTIC_CAPABILITY,
        )
        private val classCapabilityConstruction = nonGenericConstruction(
            provisional,
            DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY,
        )
        private val methodImpls = listOf(
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplReference(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION,
                type(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS),
                methodIdentity(
                    DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
                ),
                classCapabilityConstruction,
                methodIdentity(
                    DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_SLOT,
                ),
            ),
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplReference(
                DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind.INTERFACE_SEMANTIC_CAPABILITY_IMPLEMENTATION,
                type(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS),
                methodIdentity(
                    DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
                ),
                interfaceCapabilityConstruction,
                methodIdentity(
                    DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.INTERFACE_SEMANTIC_CAPABILITY_SLOT,
                ),
            ),
        )

        val input = DotNetLocalGenericOwnerPhysicalCompleteEmissionFamilyInput(
            logicalMember,
            implementationMember,
            types,
            aliases,
            typeParameters,
            methodInputs,
            methodImpls,
        )

        fun type(
            kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind,
        ): DotNetGenericOwnerPhysicalTypeDefIdentity.Local = types.getValue(kind)

        fun methodIdentity(
            kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
        ): DotNetGenericOwnerPhysicalMethodDefIdentity.Local =
            methodInputs.single { method -> method.kind == kind }.identity

        fun replaceMethod(
            kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
            transform: (DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodInput) ->
                    DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodInput,
        ) = input.copy(methods = input.methods.map { method ->
            if (method.kind == kind) transform(method) else method
        })

        fun replaceMethodImpl(
            kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplKind,
            transform: (DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplReference) ->
                    DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodImplReference,
        ) = input.copy(methodImpls = input.methodImpls.map { methodImpl ->
            if (methodImpl.kind == kind) transform(methodImpl) else methodImpl
        })

        fun bind(
            input: DotNetLocalGenericOwnerPhysicalCompleteEmissionFamilyInput,
            extraTypes: List<DotNetGenericOwnerPhysicalTypeDefReference> = emptyList(),
            extraMethods: List<DotNetGenericOwnerPhysicalMethodDefReference> = emptyList(),
            methodParameterBinders: Map<
                    DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
                    DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
                    > = emptyMap(),
        ): DotNetGenericOwnerPhysicalBindingResult<DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily> {
            val typeDefinitions = inputs.values.map(DotNetLocalGenericOwnerPhysicalTypeInput::asReference) +
                    extraTypes
            val methodDefinitions = methodDefinitions(input.methods, methodParameterBinders) + extraMethods
            return when (val declarations = DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                typeDefinitions,
                methodDefinitions,
                edgeSets(),
            )) {
                is DotNetGenericOwnerPhysicalBindingResult.Bound ->
                    DotNetLocalGenericOwnerPhysicalCompleteEmissionFamily.bindOrError(
                        input,
                        declarations.value,
                        inputs.values.associateBy(DotNetLocalGenericOwnerPhysicalTypeInput::identity),
                    )
                is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
                    DotNetGenericOwnerPhysicalBindingResult.Conflict(declarations.reason)
                DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable
            }
        }

        fun methodDescription(
            identity: DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
            declaringType: DotNetGenericOwnerPhysicalTypeDefIdentity.Local,
            methodParameterBinder: DotNetGenericOwnerPhysicalMethodDefIdentity.Local = identity,
        ) = DotNetGenericOwnerPhysicalMethodDefReference(
            identity,
            declaringType,
            DotNetGenericOwnerPhysicalMemberVisibility.PRIVATE,
            DotNetGenericOwnerPhysicalMemberDispatch.FINAL,
            DotNetGenericOwnerPhysicalMethodSignatureReference(
                isInstance = true,
                genericArity = if (methodGeneric) 1 else 0,
                resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutReference.Void,
                parameterSlots = if (methodGeneric) {
                    listOf(DotNetGenericOwnerPhysicalCallableValueSlotReference(
                        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
                        DotNetGenericOwnerSymbolicCarrierReference.Parameter.methodParameterReference(
                            methodParameterBinder,
                            0,
                        ),
                    ))
                } else {
                    emptyList()
                },
            ),
            genericParameters = if (methodGeneric) {
                listOf(DotNetGenericOwnerPhysicalGenericParameterReference(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                    constraints = emptyList(),
                ))
            } else {
                emptyList()
            },
        )

        private fun methodDefinitions(
            methods: List<DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodInput>,
            methodParameterBinders: Map<
                    DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
                    DotNetGenericOwnerPhysicalMethodDefIdentity.Local,
                    > = emptyMap(),
        ) = methods.map { method ->
            methodDescription(
                method.identity,
                declaringType(method.kind),
                methodParameterBinders[method.kind] ?: method.identity,
            )
        }

        private fun declaringType(
            kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
        ) = when (kind) {
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.NATURAL_INTERFACE_SLOT ->
                type(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE)
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.INTERFACE_SEMANTIC_CAPABILITY_SLOT ->
                type(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.INTERFACE_SEMANTIC_CAPABILITY)
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.IMPLEMENTATION_TYPED_ENTRY,
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
            -> type(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS)
            DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind.CLASS_SEMANTIC_CAPABILITY_SLOT ->
                type(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY)
        }

        private fun edgeSets() = listOf(
            DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(
                type(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.NATURAL_INTERFACE),
                emptyList(),
            ),
            DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(
                type(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.INTERFACE_SEMANTIC_CAPABILITY),
                emptyList(),
            ),
            DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(
                type(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.CLASS_SEMANTIC_CAPABILITY),
                listOf(interfaceEdge(interfaceCapabilityConstruction)),
            ),
            DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(
                type(DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind.IMPLEMENTATION_CLASS),
                listOf(
                    DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
                        DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS,
                        DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                    ),
                    interfaceEdge(naturalConstruction),
                    interfaceEdge(classCapabilityConstruction),
                    interfaceEdge(interfaceCapabilityConstruction),
                ),
            ),
        )

        private fun nonGenericConstruction(
            index: DotNetGenericOwnerPhysicalDeclarationIndex,
            kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind,
        ) = boundConstruction(index, type(kind), emptyList())

        private fun typeInput(
            kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionTypeKind,
            genericArity: Int,
            role: DotNetLocalGenericOwnerPhysicalTypeRole,
        ) = DotNetLocalGenericOwnerPhysicalTypeInput(
            type(kind),
            kind.name,
            genericArity,
            role,
        )

        private fun methodInput(
            kind: DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodKind,
            emittedFunction: IrSimpleFunctionSymbol,
            identityRoot: IrSimpleFunctionSymbol,
            role: DotNetGenericOwnerMemberFamilyRole?,
        ) = DotNetLocalGenericOwnerPhysicalCompleteEmissionMethodInput(
            kind,
            emittedFunction,
            DotNetGenericOwnerPhysicalMethodDefIdentity.Local(identityRoot, role),
        )
    }

    private fun boundDeclarationIndex(
        types: Iterable<DotNetGenericOwnerPhysicalTypeDefReference>,
        methods: Iterable<DotNetGenericOwnerPhysicalMethodDefReference>,
        edgeSets: Iterable<DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet> = emptyList(),
    ): DotNetGenericOwnerPhysicalDeclarationIndex = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetGenericOwnerPhysicalDeclarationIndex>,
            >(
        DotNetGenericOwnerPhysicalDeclarationIndex.bind(
            DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
            types,
            methods,
            edgeSets,
        ),
    ).value

    private fun boundConstruction(
        index: DotNetGenericOwnerPhysicalDeclarationIndex,
        owner: DotNetGenericOwnerPhysicalTypeDefIdentity,
        arguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
    ): DotNetGenericOwnerSymbolicCarrierReference.Constructed = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed,
                    >,
            >(index.constructTypeOrError(owner, arguments)).value

    private fun boundTypeParameter(
        index: DotNetGenericOwnerPhysicalDeclarationIndex,
        owner: DotNetGenericOwnerPhysicalTypeDefIdentity,
        parameterIndex: Int,
    ): DotNetGenericOwnerSymbolicCarrierReference.Parameter = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetGenericOwnerSymbolicCarrierReference.Parameter>,
            >(index.typeParameterOrError(owner, parameterIndex)).value

    private fun interfaceEdge(
        target: DotNetGenericOwnerSymbolicCarrierReference,
    ) = DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference(
        DotNetGenericOwnerDirectSupertypeKind.INTERFACE,
        target,
    )
}
