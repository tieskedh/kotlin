/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.MetadataSource
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrModuleFragmentImpl
import org.jetbrains.kotlin.ir.types.defaultType as typeParameterDefaultType
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssembly
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterConstraint
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationGraph
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedTypeSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrInterfaceImplementation
import org.jetbrains.kotlin.load.dotnet.DotNetClrMetadataHandle
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrPhysicalTypeCoreTypes
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedInterfaceImplementation
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedMethodSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeHierarchy
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeView
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSpecification
import org.jetbrains.kotlin.load.dotnet.DotNetManagedAssemblyIdentity
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.types.Variance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DotNetRetainedForeignGenericOwnerPhysicalAuthorityTest {
    @Test
    fun `retained foreign provenance is the terminal TypeDef and MethodDef authority`() {
        val fixture = fixture()
        val imported = retainedForeignIrFixture(fixture)
        val referencedAssemblies = mutableListOf<DotNetClrClasspathAssembly.WithoutCarrier>()
        val mapper = DotNetIlTypeMapper(
            availableClasses = emptyMap(),
            foreignAssemblyReferenceSink = referencedAssemblies::add,
        )

        val owner = assertNotNull(mapper.classInfoOrNull(imported.owner))
        val function = assertNotNull(mapper.referencedFunctionInfoOrNull(imported.function))

        assertEquals("Foreign.Source`1", owner.ilClassName)
        assertEquals("Foreign.Authority", owner.assemblyName)
        assertEquals(1, owner.typeParameterCount)
        assertEquals(owner, function.owner)
        assertEquals("Transfer", function.physicalMethodName)
        assertEquals(1, function.signature.methodGenericParameterCount)
        assertEquals(
            DotNetIlReturnType.Value(
                DotNetIlValueType.TypeParameter(0, isMethodParameter = false),
            ),
            function.signature.returnType,
        )
        assertEquals(listOf(fixture.source.assembly), referencedAssemblies.distinct())
    }

    @Test
    fun `retained foreign declarations never consume Kotlin producer records by logical key`() {
        val fixture = fixture()
        val imported = retainedForeignIrFixture(fixture)
        val ownerKey = checkNotNull(imported.owner.dotNetLibraryAbiKeyOrNull("C"))
        val functionKey = checkNotNull(imported.function.dotNetLibraryAbiKeyOrNull("F"))
        val externalDeclarations = retainedForeignCollisionDeclarations(
            ownerKey,
            functionKey,
        )
        val mapper = DotNetIlTypeMapper(
            availableClasses = emptyMap(),
            externalDeclarations = externalDeclarations,
            genericOwnerRehearsal = true,
        )

        assertFalse(externalDeclarations.hasClass(imported.owner))
        assertFalse(externalDeclarations.hasGenericInterface(imported.owner))
        assertFalse(externalDeclarations.hasReifiedGenericInterface(imported.owner))
        assertNull(externalDeclarations.classInfoOrNull(imported.owner, mapper))
        assertNull(externalDeclarations.genericOwnerMemberFamilyOrNull(imported.function))
        assertFalse(externalDeclarations.hasNaturalGenericOwnerFunctionReturn(imported.function))
        assertNull(externalDeclarations.functionInfoOrNull(imported.function, mapper))
    }

    @Test
    fun `simultaneous retained foreign and Kotlin producer TypeDef authority fails closed`() {
        val fixture = fixture()
        val imported = retainedForeignIrFixture(fixture)
        val ownerKey = checkNotNull(imported.owner.dotNetLibraryAbiKeyOrNull("C"))
        val functionKey = checkNotNull(imported.function.dotNetLibraryAbiKeyOrNull("F"))
        val mapper = DotNetIlTypeMapper(
            availableClasses = emptyMap(),
            externalDeclarations = retainedForeignCollisionDeclarations(ownerKey, functionKey),
            genericOwnerRehearsal = true,
        )

        val failure = assertFailsWith<DotNetIlUnsupportedException> {
            mapper.classInfoOrNull(imported.owner)
        }

        assertEquals(
            "retained foreign CLR TypeDef '$ownerKey' also has producer-recorded Kotlin authority (C)",
            failure.reason,
        )
    }

    @Test
    fun `simultaneous retained foreign and Kotlin producer MethodDef authority fails closed`() {
        val fixture = fixture()
        val imported = retainedForeignIrFixture(fixture)
        val ownerKey = checkNotNull(imported.owner.dotNetLibraryAbiKeyOrNull("C"))
        val functionKey = checkNotNull(imported.function.dotNetLibraryAbiKeyOrNull("F"))
        val mapper = DotNetIlTypeMapper(
            availableClasses = emptyMap(),
            externalDeclarations = retainedForeignCollisionDeclarations(ownerKey, functionKey),
            genericOwnerRehearsal = true,
        )

        val failure = assertFailsWith<DotNetIlUnsupportedException> {
            mapper.referencedFunctionInfoOrNull(imported.function)
        }

        assertEquals(
            "retained foreign CLR MethodDef '$functionKey' also has producer-recorded Kotlin authority (F)",
            failure.reason,
        )
    }

    @Test
    fun `semantic capability metadata cannot outrank a retained foreign MethodDef`() {
        val fixture = fixture()
        val imported = retainedForeignIrFixture(fixture)
        val mapper = DotNetIlTypeMapper(
            availableClasses = emptyMap(),
            genericOwnerRehearsal = true,
            genericOwnerCapabilityDeclarations = setOf(imported.function),
        )

        val failure = assertFailsWith<DotNetIlUnsupportedException> {
            mapper.referencedFunctionInfoOrNull(imported.function)
        }

        assertEquals(
            "retained foreign CLR MethodDef 'transfer' also has semantic capability declaration",
            failure.reason,
        )
    }

    @Test
    fun `retained metadata binds exact owner MethodDef and generic parameter rows`() {
        val fixture = fixture(
            ownerParameterAttributes = REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
            methodParameterAttributes = NOT_NULLABLE_VALUE_TYPE_CONSTRAINT_ATTRIBUTE or
                    DEFAULT_CONSTRUCTOR_CONSTRAINT_ATTRIBUTE or ALLOW_BY_REF_LIKE_ATTRIBUTE,
        )
        val declarations = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalDeclarationIndex,
                        >,
                >(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        ).value
        val ownerIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(fixture.source)
        val methodIdentity =
            DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr.retained(
                fixture.source,
                fixture.method,
            )

        val owner = assertNotNull(declarations.typeDescriptionOrNull(ownerIdentity))
        assertEquals(DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE, owner.category)
        assertEquals(1, owner.genericArity)
        assertEquals(
            DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
            owner.genericParameters.single().variance,
        )
        assertEquals(true, owner.genericParameters.single().hasReferenceTypeConstraint)
        assertEquals(false, owner.genericParameters.single().hasNotNullableValueTypeConstraint)
        assertEquals(false, owner.genericParameters.single().hasDefaultConstructorConstraint)
        assertEquals(false, owner.genericParameters.single().allowsByRefLike)
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Bound(emptySet()),
            declarations.directSupertypeEdgesOrUnavailable(ownerIdentity),
        )

        val method = assertNotNull(declarations.methodDescriptionOrNull(methodIdentity))
        assertEquals(ownerIdentity, method.declaringType)
        assertEquals(DotNetGenericOwnerPhysicalMemberVisibility.PUBLIC, method.visibility)
        assertEquals(DotNetGenericOwnerPhysicalMemberDispatch.ABSTRACT, method.dispatch)
        assertEquals(true, method.signature.isInstance)
        assertEquals(1, method.signature.genericArity)
        assertEquals(
            DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
            method.genericParameters.single().variance,
        )
        assertEquals(true, method.genericParameters.single().hasNotNullableValueTypeConstraint)
        assertEquals(true, method.genericParameters.single().hasDefaultConstructorConstraint)
        assertEquals(true, method.genericParameters.single().allowsByRefLike)

        val result = assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
            method.signature.resultLayout,
        ).slot
        assertEquals(DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT, result.domain)
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.Parameter.unboundTypeParameterReference(
                ownerIdentity,
                0,
            ),
            result.carrier,
        )
        assertEquals(2, method.signature.parameterSlots.size)
        assertEquals(
            DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
            method.signature.parameterSlots[0].domain,
        )
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.Parameter.methodParameterReference(
                methodIdentity,
                0,
            ),
            method.signature.parameterSlots[0].carrier,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT,
            method.signature.parameterSlots[1].domain,
        )
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.SzArray(
                DotNetGenericOwnerSymbolicCarrierReference.Parameter
                    .unboundTypeParameterReference(ownerIdentity, 0),
            ),
            method.signature.parameterSlots[1].carrier,
        )

        // Complete GenericParam authority is not itself a proof that String satisfies `class`.
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.constructTypeOrError(
                ownerIdentity,
                listOf(DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()),
            ),
        )
    }

    @Test
    fun `valid covariant metadata retains an output-only owner parameter`() {
        val fixture = fixture(
            ownerParameterAttributes = COVARIANT_ATTRIBUTE,
            ownerDependentInput = false,
        )
        val declarations = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalDeclarationIndex,
                        >,
                >(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        ).value
        val ownerIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(fixture.source)
        val methodIdentity =
            DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr.retained(
                fixture.source,
                fixture.method,
            )

        assertEquals(
            DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            assertNotNull(declarations.typeDescriptionOrNull(ownerIdentity))
                .genericParameters.single().variance,
        )
        assertEquals(
            false,
            assertNotNull(declarations.typeDescriptionOrNull(ownerIdentity))
                .supportsClrDelegateVariance,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
            assertNotNull(declarations.methodDescriptionOrNull(methodIdentity))
                .signature.parameterSlots[1].domain,
        )
    }

    @Test
    fun `retained CLR covariance selects the widened MethodDef owner without erasing its carrier`() {
        val fixture = fixture(
            ownerParameterAttributes = COVARIANT_ATTRIBUTE,
            ownerDependentInput = false,
        )
        val declarations = boundDeclarations(fixture)
        val owner = retainedOwnerIdentity(fixture)
        val sourceConstruction = boundConstruction(
            declarations,
            owner,
            listOf(DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()),
        )
        val targetConstruction = boundConstruction(
            declarations,
            owner,
            listOf(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
        )
        val sourceValue = directValue(declarations, sourceConstruction)
        val targetView = DotNetGenericOwnerPhysicalView(targetConstruction)

        assertEquals(
            setOf(DotNetGenericOwnerPhysicalView(sourceConstruction)),
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalInterfaceViewClosure,
                    >>(
                declarations.physicalInterfaceViewClosureOrError(sourceConstruction),
            ).value.interfaceViews,
        )
        val converted = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerProducedValueFact,
                >>(
            sourceValue.selectClrReferenceVarianceViewOrError(declarations, targetView),
        ).value
        assertEquals(sourceValue.layout, converted.layout)
        assertEquals(targetView, converted.provenance.selectedViewLineage[owner])

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                converted,
                exactTransferArguments(
                    declarations,
                    DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                ),
            )
        ).value
        assertEquals(targetView, route.requiredReceiverView)
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
    }

    @Test
    fun `direct retained constrained owner uses the same conversion-scoped authority`() {
        val fixture = deepCrossAssemblyFixture(
            rootOwnerParameterAttributes =
                COVARIANT_ATTRIBUTE or REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
            rootMethodParameterAttributes = REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
            rootOwnerDependentInput = false,
        )
        val identity = retainedOwnerIdentity(fixture)
        val sourceView = DotNetGenericOwnerPhysicalView(
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                identity,
                listOf(DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()),
            )
        )
        val targetView = DotNetGenericOwnerPhysicalView(
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                identity,
                listOf(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
            )
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            boundDeclarations(fixture)
                .proveClrReferenceVarianceConversionOrError(sourceView, targetView),
        )

        for (target in listOf(DotNetTarget.NET48, DotNetTarget.NET10_0)) {
            val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalDeclarationIndex,
                    >>(
                DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                    fixture.source,
                    fixture.method,
                    target,
                )
            ).value
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
                declarations.proveClrReferenceVarianceConversionOrError(
                    sourceView,
                    targetView,
                )
            )
            val openParameter = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter,
                    >>(
                declarations.typeParameterOrError(identity, 0)
            ).value
            val openSourceView = DotNetGenericOwnerPhysicalView(
                DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                    identity,
                    listOf(openParameter),
                )
            )
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
                declarations.proveClrReferenceVarianceConversionOrError(
                    openSourceView,
                    targetView,
                )
            )
            val openMethodParameter = assertIs<
                    DotNetGenericOwnerPhysicalBindingResult.Bound<
                            DotNetGenericOwnerSymbolicCarrierReference.Parameter,
                            >,
                    >(
                declarations.methodParameterOrError(retainedMethodIdentity(fixture), 0)
            ).value
            val openMethodSourceView = DotNetGenericOwnerPhysicalView(
                DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                    identity,
                    listOf(openMethodParameter),
                )
            )
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<*>>(
                declarations.proveClrReferenceVarianceConversionOrError(
                    openMethodSourceView,
                    targetView,
                )
            )
            val sourceValue = objectValueWithRetainedViews(
                declarations,
                listOf(sourceView),
                sourceView,
            )
            val converted = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerProducedValueFact,
                    >>(
                sourceValue.selectClrReferenceVarianceViewOrError(
                    declarations,
                    targetView,
                )
            ).value
            val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalOperationRoute,
                    >>(
                selectRetainedRoute(
                    fixture,
                    converted,
                    exactTransferArguments(
                        declarations,
                        DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                    ),
                    target = target,
                )
            ).value
            assertEquals(targetView, route.requiredReceiverView)
            assertEquals(sourceValue.layout, converted.layout)
            assertEquals(
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                declarations.constructTypeOrError(
                    identity,
                    targetView.construction.arguments,
                ),
            )
        }
    }

    @Test
    fun `retained foreign MethodDef routes through exact receiver and argument provenance`() {
        val fixture = fixture()
        val declarations = boundDeclarations(fixture)
        val ownerIdentity = retainedOwnerIdentity(fixture)
        val ownerConstruction = boundConstruction(
            declarations,
            ownerIdentity,
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, ownerConstruction),
                exactTransferArguments(declarations),
            )
        ).value

        assertEquals(retainedMethodIdentity(fixture), route.method.identity)
        assertEquals(ownerConstruction, route.requiredReceiverView.construction)
        assertEquals(
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
            route.methodArguments,
        )
        assertEquals(
            listOf(
                DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
                DotNetGenericOwnerSymbolicCarrierReference.SzArray(
                    DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
                ),
            ),
            route.instantiatedSignature.parameterSlots.map { slot -> slot.carrier },
        )
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
        val producedResult = assertNotNull(route.producedResult)
        assertEquals(
            DotNetGenericOwnerProducedValueLayout.Direct(
                boundCarrier(
                    declarations,
                    DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
                )
            ),
            producedResult.layout,
        )
        assertEquals(DotNetGenericOwnerPhysicalNullState.NON_NULL, producedResult.nullState)
    }

    @Test
    fun `logical widening preserves a selected retained foreign construction`() {
        val fixture = fixture()
        val declarations = boundDeclarations(fixture)
        val ownerConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        )
        val selectedView = DotNetGenericOwnerPhysicalView(ownerConstruction)
        val widenedReceiver = objectValueWithRetainedViews(
            declarations,
            listOf(selectedView),
            selectedView,
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                widenedReceiver,
                exactTransferArguments(declarations),
            )
        ).value

        assertEquals(ownerConstruction, route.requiredReceiverView.construction)
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )

    }

    @Test
    fun `multiple retained constructions require selected view lineage`() {
        val fixture = fixture()
        val declarations = boundDeclarations(fixture)
        val ownerIdentity = retainedOwnerIdentity(fixture)
        val intView = DotNetGenericOwnerPhysicalView(boundConstruction(
            declarations,
            ownerIdentity,
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        ))
        val stringView = DotNetGenericOwnerPhysicalView(boundConstruction(
            declarations,
            ownerIdentity,
            listOf(DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()),
        ))
        val ambiguousReceiver = objectValueWithRetainedViews(
            declarations,
            listOf(intView, stringView),
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectRetainedRoute(
                fixture,
                ambiguousReceiver,
                exactTransferArguments(declarations),
            ),
        )

        val selectedReceiver = objectValueWithRetainedViews(
            declarations,
            listOf(intView, stringView),
            intView,
        )
        val selectedRoute = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                selectedReceiver,
                exactTransferArguments(declarations),
            )
        ).value
        assertEquals(intView, selectedRoute.requiredReceiverView)
    }

    @Test
    fun `broad foreign receiver and incompatible owner input remain unavailable`() {
        val fixture = fixture()
        val declarations = boundDeclarations(fixture)
        val ownerConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        )
        val broadReceiver = DotNetGenericOwnerProducedValueFact(
            DotNetGenericOwnerProducedValueLayout.Direct(
                boundCarrier(
                    declarations,
                    DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                )
            ),
            DotNetGenericOwnerPhysicalValueProvenance(DotNetGenericOwnerGuaranteedViews.Unknown),
            DotNetGenericOwnerPhysicalNullState.NON_NULL,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectRetainedRoute(
                fixture,
                broadReceiver,
                exactTransferArguments(declarations),
            ),
        )

        val incompatibleArguments = listOf(
            directValue(
                declarations,
                DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
            ),
            directValue(
                declarations,
                DotNetGenericOwnerSymbolicCarrierReference.SzArray(
                    DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                ),
            ),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectRetainedRoute(
                fixture,
                directValue(declarations, ownerConstruction),
                incompatibleArguments,
            ),
        )
    }

    @Test
    fun `retained recursive result produces its exact foreign view`() {
        val fixture = fixture(recursiveResult = true)
        val declarations = boundDeclarations(fixture)
        val ownerConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, ownerConstruction),
                exactTransferArguments(declarations),
            )
        ).value
        val result = assertNotNull(route.producedResult)
        assertEquals(
            DotNetGenericOwnerProducedValueLayout.Direct(
                boundCarrier(declarations, ownerConstruction),
            ),
            result.layout,
        )
        assertEquals(DotNetGenericOwnerPhysicalNullState.MAYBE_NULL, result.nullState)
        val evidence = assertIs<DotNetGenericOwnerGuaranteedViews.Known>(
            result.provenance.guaranteedViews,
        ).evidenceByView
        assertEquals(
            setOf(DotNetGenericOwnerPhysicalViewEvidence.FROZEN_PARAMETER_OR_RESULT),
            evidence[DotNetGenericOwnerPhysicalView(ownerConstruction)],
        )
    }

    @Test
    fun `memberless retained TypeDef proves an inherited foreign MethodDef route`() {
        val fixture = fixture(includeInheritedReceiver = true)
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        assertEquals(
            emptyList(),
            receiverSource.assembly.metadata.methodDefinitions.filter { candidate ->
                candidate.declaringType == receiverSource.declaringType.handle
            },
        )
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val receiverIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
        val receiverConstruction = boundConstruction(
            declarations,
            receiverIdentity,
            emptyList(),
        )
        val parentConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        )

        val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalInterfaceViewClosure,
                >>(
            declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
        ).value
        assertEquals(true, closure.isComplete)
        assertEquals(
            setOf(
                DotNetGenericOwnerPhysicalView(receiverConstruction),
                DotNetGenericOwnerPhysicalView(parentConstruction),
            ),
            closure.interfaceViews,
        )

        val receiver = directValue(declarations, receiverConstruction)
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            selectRetainedRoute(
                fixture,
                receiver,
                exactTransferArguments(declarations),
            ),
        )
        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                receiver,
                exactTransferArguments(declarations),
                receiverSource,
            )
        ).value
        assertEquals(parentConstruction, route.requiredReceiverView.construction)
        assertEquals(retainedMethodIdentity(fixture), route.method.identity)
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )

    }

    @Test
    fun `cross assembly memberless TypeDef proves inherited foreign MethodDef route`() {
        val fixture = crossAssemblyFixture()
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        assertEquals(false, fixture.source.assembly === receiverSource.assembly)
        assertEquals(
            fixture.source.declaringType.handle,
            receiverSource.declaringType.handle,
        )

        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val receiverIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
        val receiverConstruction = boundConstruction(
            declarations,
            receiverIdentity,
            emptyList(),
        )
        val parentConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(declarations),
                receiverSource,
            )
        ).value
        assertEquals(parentConstruction, route.requiredReceiverView.construction)
        assertEquals(retainedMethodIdentity(fixture), route.method.identity)
    }

    @Test
    fun `cross assembly inherited route rejects an unbound AssemblyRef`() {
        val fixture = crossAssemblyFixture(parentReferenceVersion = "2.0.0.0")

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            )
        )
    }

    @Test
    fun `cross assembly intermediate binder preserves the exact inherited MethodDef route`() {
        val fixture = deepCrossAssemblyFixture()
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val intermediateType = assertNotNull(fixture.intermediateType)
        assertEquals(false, fixture.source.assembly.metadata === intermediateType.assembly)
        assertEquals(false, intermediateType.assembly === receiverSource.assembly.metadata)

        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val receiverIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
        val intermediateIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                receiverSource,
                intermediateType,
            )
        val parentIdentity = retainedOwnerIdentity(fixture)
        val intCarrier = DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()
        val receiverConstruction = boundConstruction(
            declarations,
            receiverIdentity,
            emptyList(),
        )
        val intermediateConstruction = boundConstruction(
            declarations,
            intermediateIdentity,
            listOf(intCarrier),
        )
        val parentConstruction = boundConstruction(
            declarations,
            parentIdentity,
            listOf(intCarrier),
        )

        assertEquals(
            DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            assertNotNull(declarations.typeDescriptionOrNull(intermediateIdentity))
                .genericParameters.single().variance,
        )
        val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalInterfaceViewClosure,
                >>(
            declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
        ).value
        assertEquals(true, closure.isComplete)
        assertEquals(
            setOf(
                DotNetGenericOwnerPhysicalView(receiverConstruction),
                DotNetGenericOwnerPhysicalView(intermediateConstruction),
                DotNetGenericOwnerPhysicalView(parentConstruction),
            ),
            closure.interfaceViews,
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(declarations),
                receiverSource,
            )
        ).value
        assertEquals(parentConstruction, route.requiredReceiverView.construction)
        assertEquals(retainedMethodIdentity(fixture), route.method.identity)
        assertEquals(
            intCarrier,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
    }

    @Test
    fun `recursive cross assembly binders preserve the exact inherited MethodDef route`() {
        val fixture = deepCrossAssemblyFixture(intermediateDepth = 2)
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val receiverType = receiverSource.declaringHierarchy.type.type
        val intermediateTypes = fixture.source.graph.hierarchies
            .map { hierarchy -> hierarchy.type.type }
            .filter { type ->
                !type.hasSameIdentityAs(fixture.source.declaringHierarchy.type.type) &&
                        !type.hasSameIdentityAs(receiverType)
            }
        assertEquals(2, intermediateTypes.size)

        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val intCarrier = DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()
        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            emptyList(),
        )
        val intermediateConstructions = intermediateTypes.map { type ->
            boundConstruction(
                declarations,
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource, type),
                listOf(intCarrier),
            )
        }
        val parentConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(intCarrier),
        )
        val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalInterfaceViewClosure,
                >>(
            declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
        ).value

        assertEquals(true, closure.isComplete)
        assertEquals(
            (listOf(receiverConstruction) + intermediateConstructions + parentConstruction)
                .map(::DotNetGenericOwnerPhysicalView)
                .toSet(),
            closure.interfaceViews,
        )
        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(declarations),
                receiverSource,
            )
        ).value
        assertEquals(parentConstruction, route.requiredReceiverView.construction)
        assertEquals(retainedMethodIdentity(fixture), route.method.identity)
    }

    @Test
    fun `dependent TypeDef constraint proves only its exact inherited edge`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            intermediateGenericArity = 2,
            dependentParameterConstraint = true,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val receiverType = receiverSource.declaringHierarchy.type.type
        val intermediateTypes = fixture.source.graph.hierarchies
            .map { hierarchy -> hierarchy.type.type }
            .filter { type ->
                !type.hasSameIdentityAs(fixture.source.declaringHierarchy.type.type) &&
                        !type.hasSameIdentityAs(receiverType)
            }
        assertEquals(2, intermediateTypes.size)

        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val objectCarrier = DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()
        val stringCarrier = DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()
        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            emptyList(),
        )
        val innerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
            receiverSource,
            intermediateTypes[0],
        )
        val outerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
            receiverSource,
            intermediateTypes[1],
        )
        val innerConstruction =
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                innerIdentity,
                listOf(objectCarrier, stringCarrier),
            )
        val outerConstruction =
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                outerIdentity,
                listOf(objectCarrier, stringCarrier),
            )
        val parentConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(stringCarrier),
        )

        val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalInterfaceViewClosure,
                >>(
            declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
        ).value
        assertEquals(true, closure.isComplete)
        assertEquals(
            setOf(
                receiverConstruction,
                outerConstruction,
                innerConstruction,
                parentConstruction,
            ).map(::DotNetGenericOwnerPhysicalView).toSet(),
            closure.interfaceViews,
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(declarations, stringCarrier),
                receiverSource,
            )
        ).value
        assertEquals(parentConstruction, route.requiredReceiverView.construction)
        assertEquals(
            stringCarrier,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )

        // The proof belongs to the retained InterfaceImpl edge. It does not turn the
        // general construction helper into a constraint solver for caller-authored values.
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.constructTypeOrError(
                outerIdentity,
                listOf(objectCarrier, stringCarrier),
            ),
        )
    }

    @Test
    fun `missing source implication rejects a constrained inherited edge`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            intermediateGenericArity = 2,
            dependentParameterConstraint = true,
            omitLastDependentConstraint = true,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            )
        )
    }

    @Test
    fun `retained constraint graph beyond its row budget is unavailable`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            intermediateGenericArity = 2,
            dependentParameterConstraint = true,
            dependentConstraintCopies = 1_025,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )

        val nestedFixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 1,
            nestedNominalCarrierNominalConstraint = true,
            nestedNominalCarrierConstraintCopies = 1_025,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                nestedFixture.source,
                nestedFixture.method,
                assertNotNull(nestedFixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `nominal interface constraint retains exact auxiliary TypeDef authority`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 0,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val receiverType = receiverSource.declaringHierarchy.type.type
        val intermediateTypes = fixture.source.graph.hierarchies
            .map { hierarchy -> hierarchy.type.type }
            .filter { type ->
                type.definition.namespaceName == "Foreign.Deep" &&
                        !type.hasSameIdentityAs(receiverType)
            }
        assertEquals(2, intermediateTypes.size)
        val markerType = fixture.source.graph.hierarchies
            .map { hierarchy -> hierarchy.type.type }
            .single { type -> type.definition.namespaceName == "Synthetic.Constraint" }

        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val markerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
            receiverSource,
            markerType,
        )
        val markerConstruction = boundConstruction(
            declarations,
            markerIdentity,
            emptyList(),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            assertNotNull(declarations.typeDescriptionOrNull(markerIdentity)).category,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.directSupertypeEdgesOrUnavailable(markerIdentity),
        )

        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            emptyList(),
        )
        val intermediateConstructions = intermediateTypes.map { type ->
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                    receiverSource,
                    type,
                ),
                listOf(markerConstruction),
            )
        }
        val parentConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(markerConstruction),
        )
        val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalInterfaceViewClosure,
                >>(
            declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
        ).value
        assertEquals(true, closure.isComplete)
        assertEquals(
            (listOf(receiverConstruction) + intermediateConstructions + parentConstruction)
                .map(::DotNetGenericOwnerPhysicalView)
                .toSet(),
            closure.interfaceViews,
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(declarations, markerConstruction),
                receiverSource,
            )
        ).value
        assertEquals(parentConstruction, route.requiredReceiverView.construction)
        assertEquals(
            markerConstruction,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.constructTypeOrError(
                intermediateConstructions.last().definition,
                listOf(markerConstruction),
            ),
        )
    }

    @Test
    fun `nominal interface carrier requires selected hierarchy authority`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 0,
            includeNominalConstraintHierarchy = false,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `missing nominal source constraint rejects an inherited edge`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 0,
            omitLastNominalConstraint = true,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            )
        )
    }

    @Test
    fun `nested nominal constraint retains recursive exact interface construction`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 2,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val markerType = fixture.source.graph.hierarchies
            .map { hierarchy -> hierarchy.type.type }
            .single { type ->
                type.definition.namespaceName == "Synthetic.Constraint" &&
                        type.definition.metadataName == "MarkerOf`1"
            }

        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val markerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
            receiverSource,
            markerType,
        )
        val markerDescription = assertNotNull(
            declarations.typeDescriptionOrNull(markerIdentity),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            markerDescription.category,
        )
        assertEquals(1, markerDescription.genericArity)
        assertEquals(
            DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            markerDescription.genericParameters.single().variance,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.directSupertypeEdgesOrUnavailable(markerIdentity),
        )

        var expectedCarrier: DotNetGenericOwnerSymbolicCarrierReference =
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()
        repeat(2) {
            expectedCarrier = boundConstruction(
                declarations,
                markerIdentity,
                listOf(expectedCarrier),
            )
        }
        val expectedConstruction = assertIs<
                DotNetGenericOwnerSymbolicCarrierReference.Constructed,
                >(expectedCarrier)
        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            emptyList(),
        )
        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(declarations, expectedConstruction),
                receiverSource,
            )
        ).value
        assertEquals(
            boundConstruction(
                declarations,
                retainedOwnerIdentity(fixture),
                listOf(expectedConstruction),
            ),
            route.requiredReceiverView.construction,
        )
        assertEquals(
            expectedConstruction,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )

        val constrainedIntermediate = assertNotNull(fixture.intermediateType)
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.constructTypeOrError(
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                    receiverSource,
                    constrainedIntermediate,
                ),
                listOf(expectedConstruction),
            ),
        )
    }

    @Test
    fun `nominal reference class constraints retain direct and recursive exact carriers`() {
        for (constructionDepth in listOf(0, 2)) {
            val fixture = deepCrossAssemblyFixture(
                intermediateDepth = 2,
                nominalConstraintConstructionDepth = constructionDepth,
                nominalCarrierKind = NominalCarrierKind.REFERENCE_CLASS,
            )
            val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
            val markerMetadataName = if (constructionDepth == 0) {
                "MarkerClass"
            } else {
                "MarkerClassOf`1"
            }
            val markerType = fixture.source.graph.hierarchies
                .map { hierarchy -> hierarchy.type.type }
                .single { type ->
                    type.definition.namespaceName == "Synthetic.Constraint" &&
                            type.definition.metadataName == markerMetadataName
                }
            val declarationBinding =
                DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                    fixture.source,
                    fixture.method,
                    receiverSource,
                )
            val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalDeclarationIndex,
                    >>(
                declarationBinding,
                declarationBinding.toString(),
            ).value
            val markerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                receiverSource,
                markerType,
            )
            val markerDescription = assertNotNull(
                declarations.typeDescriptionOrNull(markerIdentity),
            )
            assertEquals(
                DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                markerDescription.category,
            )
            assertEquals(if (constructionDepth == 0) 0 else 1, markerDescription.genericArity)
            assertEquals(
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                declarations.directSupertypeEdgesOrUnavailable(markerIdentity),
            )

            var expectedCarrier: DotNetGenericOwnerSymbolicCarrierReference =
                if (constructionDepth == 0) {
                    boundConstruction(declarations, markerIdentity, emptyList())
                } else {
                    DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()
                }
            repeat(constructionDepth) {
                expectedCarrier = boundConstruction(
                    declarations,
                    markerIdentity,
                    listOf(expectedCarrier),
                )
            }
            val expectedConstruction = assertIs<
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed,
                    >(expectedCarrier)
            val receiverConstruction = boundConstruction(
                declarations,
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
                emptyList(),
            )
            val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalOperationRoute,
                    >>(
                selectRetainedRoute(
                    fixture,
                    directValue(declarations, receiverConstruction),
                    exactTransferArguments(declarations, expectedConstruction),
                    receiverSource,
                )
            ).value
            assertEquals(
                boundConstruction(
                    declarations,
                    retainedOwnerIdentity(fixture),
                    listOf(expectedConstruction),
                ),
                route.requiredReceiverView.construction,
            )
            assertEquals(
                expectedConstruction,
                assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                    route.instantiatedSignature.resultLayout,
                ).slot.carrier,
            )
        }
    }

    @Test
    fun `variant delegate carriers preserve exact routes on both target profiles`() {
        for (contravariant in listOf(false, true)) {
            for (constructionDepth in listOf(1, 2)) {
                for (target in listOf(DotNetTarget.NET48, DotNetTarget.NET10_0)) {
                    val fixture = deepCrossAssemblyFixture(
                        intermediateDepth = 2,
                        nominalConstraintConstructionDepth = constructionDepth,
                        nominalCarrierKind = NominalCarrierKind.DELEGATE,
                        retainOuterNominalConstraint = false,
                        genericNominalDelegateContravariant = contravariant,
                        rootOwnerDependentInput = false,
                    )
                    val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
                    val delegateType = fixture.source.graph.hierarchies
                        .map { hierarchy -> hierarchy.type.type }
                        .single { type ->
                            type.definition.namespaceName == "Synthetic.Constraint" &&
                                    type.definition.metadataName == "MarkerDelegateOf`1"
                        }
                    assertEquals(
                        "Invoke",
                        delegateType.assembly.methodDefinitions.single { method ->
                            method.declaringType == delegateType.definition.handle
                        }.name,
                    )
                    val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                            DotNetGenericOwnerPhysicalDeclarationIndex,
                            >>(
                        DotNetGenericOwnerPhysicalDeclarationIndex
                            .bindRetainedForeignInheritedReceiver(
                                fixture.source,
                                fixture.method,
                                receiverSource,
                                target,
                            )
                    ).value
                    val delegateIdentity =
                        DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                            receiverSource,
                            delegateType,
                        )
                    val delegateDescription = assertNotNull(
                        declarations.typeDescriptionOrNull(delegateIdentity),
                    )
                    assertEquals(
                        DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
                        delegateDescription.category,
                    )
                    assertEquals(1, delegateDescription.genericArity)
                    assertEquals(
                        if (contravariant) {
                            DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT
                        } else {
                            DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT
                        },
                        delegateDescription.genericParameters.single().variance,
                    )
                    assertEquals(true, delegateDescription.supportsClrDelegateVariance)
                    assertEquals(
                        DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                        declarations.directSupertypeEdgesOrUnavailable(delegateIdentity),
                    )

                    if (constructionDepth == 1) {
                        val sourceArgument = if (contravariant) {
                            DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()
                        } else {
                            DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()
                        }
                        val targetArgument = if (contravariant) {
                            DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()
                        } else {
                            DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()
                        }
                        val sourceDelegate = boundConstruction(
                            declarations,
                            delegateIdentity,
                            listOf(sourceArgument),
                        )
                        val targetDelegate = boundConstruction(
                            declarations,
                            delegateIdentity,
                            listOf(targetArgument),
                        )
                        val sourceValue = directValue(declarations, sourceDelegate)
                        val targetView = DotNetGenericOwnerPhysicalView(targetDelegate)
                        val converted = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                                DotNetGenericOwnerProducedValueFact,
                                >>(
                            sourceValue.selectClrReferenceVarianceViewOrError(
                                declarations,
                                targetView,
                            )
                        ).value
                        assertEquals(sourceValue.layout, converted.layout)
                        assertEquals(
                            targetView,
                            converted.provenance.selectedViewLineage[delegateIdentity],
                        )
                        assertEquals(
                            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                            directValue(declarations, targetDelegate)
                                .selectClrReferenceVarianceViewOrError(
                                    declarations,
                                    DotNetGenericOwnerPhysicalView(sourceDelegate),
                                ),
                        )
                        val valueDelegate = boundConstruction(
                            declarations,
                            delegateIdentity,
                            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
                        )
                        assertEquals(
                            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                            directValue(declarations, valueDelegate)
                                .selectClrReferenceVarianceViewOrError(
                                    declarations,
                                    targetView,
                                ),
                        )
                    }

                    var expectedCarrier: DotNetGenericOwnerSymbolicCarrierReference =
                        DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()
                    repeat(constructionDepth) {
                        expectedCarrier = boundConstruction(
                            declarations,
                            delegateIdentity,
                            listOf(expectedCarrier),
                        )
                    }
                    val expectedConstruction = assertIs<
                            DotNetGenericOwnerSymbolicCarrierReference.Constructed,
                            >(expectedCarrier)
                    val receiverConstruction = boundConstruction(
                        declarations,
                        DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                            receiverSource,
                        ),
                        emptyList(),
                    )
                    val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                            DotNetGenericOwnerPhysicalOperationRoute,
                            >>(
                        selectRetainedRoute(
                            fixture,
                            directValue(declarations, receiverConstruction),
                            exactTransferArguments(
                                declarations,
                                DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                            ),
                            receiverSource,
                            target,
                        )
                    ).value
                    assertEquals(
                        expectedConstruction,
                        assertIs<
                                DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct,
                                >(route.instantiatedSignature.resultLayout).slot.carrier,
                    )
                }
            }
        }
    }

    @Test
    fun `nominal value carriers preserve exact routes on both target profiles`() {
        for (constructionDepth in listOf(0, 2)) {
            for (target in listOf(DotNetTarget.NET48, DotNetTarget.NET10_0)) {
                val fixture = deepCrossAssemblyFixture(
                    intermediateDepth = 2,
                    nominalConstraintConstructionDepth = constructionDepth,
                    nominalCarrierKind = NominalCarrierKind.VALUE_TYPE,
                    retainOuterNominalConstraint = false,
                    rootOwnerDependentInput = false,
                )
                val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
                val markerMetadataName = if (constructionDepth == 0) {
                    "MarkerValue"
                } else {
                    "MarkerValueOf`1"
                }
                val markerType = fixture.source.graph.hierarchies
                    .map { hierarchy -> hierarchy.type.type }
                    .single { type ->
                        type.definition.namespaceName == "Synthetic.Constraint" &&
                                type.definition.metadataName == markerMetadataName
                    }
                val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalDeclarationIndex,
                        >>(
                    DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                        fixture.source,
                        fixture.method,
                        receiverSource,
                        target,
                    )
                ).value
                val markerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                    receiverSource,
                    markerType,
                )
                val markerDescription = assertNotNull(
                    declarations.typeDescriptionOrNull(markerIdentity),
                )
                assertEquals(
                    DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
                    markerDescription.category,
                )
                assertEquals(false, markerDescription.supportsInlineNull)
                assertEquals(
                    if (constructionDepth == 0) 0 else 1,
                    markerDescription.genericArity,
                )

                var expectedCarrier: DotNetGenericOwnerSymbolicCarrierReference =
                    if (constructionDepth == 0) {
                        boundConstruction(declarations, markerIdentity, emptyList())
                    } else {
                        DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()
                    }
                repeat(constructionDepth) {
                    expectedCarrier = boundConstruction(
                        declarations,
                        markerIdentity,
                        listOf(expectedCarrier),
                    )
                }
                val expectedConstruction = assertIs<
                        DotNetGenericOwnerSymbolicCarrierReference.Constructed,
                        >(expectedCarrier)
                val receiverConstruction = boundConstruction(
                    declarations,
                    DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
                    emptyList(),
                )
                val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalOperationRoute,
                        >>(
                    selectRetainedRoute(
                        fixture,
                        directValue(declarations, receiverConstruction),
                        exactTransferArguments(
                            declarations,
                            DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                        ),
                        receiverSource,
                        target,
                    )
                ).value
                assertEquals(
                    expectedConstruction,
                    assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                        route.instantiatedSignature.resultLayout,
                    ).slot.carrier,
                )
                assertEquals(
                    DotNetGenericOwnerPhysicalNullEncoding.NON_NULL_ONLY,
                    assertIs<DotNetGenericOwnerProducedValueLayout.Direct>(
                        assertNotNull(route.producedResult).layout,
                    ).carrier.nullEncoding,
                )
            }
        }
    }

    @Test
    fun `bare nominal value constraint derives its kind from selected metadata`() {
        for (target in listOf(DotNetTarget.NET48, DotNetTarget.NET10_0)) {
            val fixture = deepCrossAssemblyFixture(
                intermediateDepth = 2,
                nominalConstraintConstructionDepth = 0,
                nominalCarrierKind = NominalCarrierKind.VALUE_TYPE,
                rootOwnerDependentInput = false,
            )
            val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
            val markerType = fixture.source.graph.hierarchies
                .map { hierarchy -> hierarchy.type.type }
                .single { type ->
                    type.definition.namespaceName == "Synthetic.Constraint" &&
                            type.definition.metadataName == "MarkerValue"
                }
            val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalDeclarationIndex,
                    >>(
                DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                    fixture.source,
                    fixture.method,
                    receiverSource,
                    target,
                )
            ).value
            val markerDescription = assertNotNull(
                declarations.typeDescriptionOrNull(
                    DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                        receiverSource,
                        markerType,
                    )
                ),
            )
            assertEquals(
                DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
                markerDescription.category,
            )
            assertEquals(false, markerDescription.supportsInlineNull)
        }
    }

    @Test
    fun `nullable value carrier preserves its inline-null layout`() {
        for (target in listOf(DotNetTarget.NET48, DotNetTarget.NET10_0)) {
            val fixture = deepCrossAssemblyFixture(
                intermediateDepth = 2,
                nominalConstraintConstructionDepth = 1,
                nominalCarrierKind = NominalCarrierKind.NULLABLE_VALUE_TYPE,
                retainOuterNominalConstraint = false,
                rootOwnerDependentInput = false,
            )
            val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
            val nullableType = fixture.source.graph.hierarchies
                .map { hierarchy -> hierarchy.type.type }
                .single { type ->
                    type.definition.namespaceName == "System" &&
                            type.definition.metadataName == "Nullable`1"
                }
            val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalDeclarationIndex,
                    >>(
                DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                    fixture.source,
                    fixture.method,
                    receiverSource,
                    target,
                )
            ).value
            val nullableIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                receiverSource,
                nullableType,
            )
            val nullableDescription = assertNotNull(
                declarations.typeDescriptionOrNull(nullableIdentity),
            )
            assertEquals(
                DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
                nullableDescription.category,
            )
            assertEquals(true, nullableDescription.supportsInlineNull)
            val nullableConstruction =
                DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                    nullableIdentity,
                    listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
                )
            assertEquals(
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                declarations.constructTypeOrError(
                    nullableIdentity,
                    listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
                ),
            )
            val receiverConstruction = boundConstruction(
                declarations,
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
                emptyList(),
            )
            val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalOperationRoute,
                    >>(
                selectRetainedRoute(
                    fixture,
                    directValue(declarations, receiverConstruction),
                    exactTransferArguments(
                        declarations,
                        DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                    ),
                    receiverSource,
                    target,
                )
            ).value
            assertEquals(
                nullableConstruction,
                assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                    route.instantiatedSignature.resultLayout,
                ).slot.carrier,
            )
            assertEquals(
                DotNetGenericOwnerPhysicalNullEncoding.INLINE_NULLABLE_VALUE,
                assertIs<DotNetGenericOwnerProducedValueLayout.Direct>(
                    assertNotNull(route.producedResult).layout,
                ).carrier.nullEncoding,
            )
        }
    }

    @Test
    fun `nominal value carrier requires target and selected hierarchy authority`() {
        val withoutTarget = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 0,
            nominalCarrierKind = NominalCarrierKind.VALUE_TYPE,
            retainOuterNominalConstraint = false,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                withoutTarget.source,
                withoutTarget.method,
                assertNotNull(withoutTarget.inheritedReceiverSource),
            ),
        )

        val withoutHierarchy = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 0,
            nominalCarrierKind = NominalCarrierKind.VALUE_TYPE,
            retainOuterNominalConstraint = false,
            includeNominalConstraintHierarchy = false,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                withoutHierarchy.source,
                withoutHierarchy.method,
                assertNotNull(withoutHierarchy.inheritedReceiverSource),
                DotNetTarget.NET10_0,
            ),
        )
    }

    @Test
    fun `nominal carrier rejects a false signature kind before and after constraint inference`() {
        val falseKinds = listOf(
            NominalCarrierKind.VALUE_TYPE to false,
            NominalCarrierKind.REFERENCE_CLASS to true,
            NominalCarrierKind.INTERFACE to true,
        )
        for (falseKind in falseKinds) {
            val carrierKind = falseKind.first
            val encodedAsValueType = falseKind.second
            for (retainConstraint in listOf(false, true)) {
                val fixture = deepCrossAssemblyFixture(
                    intermediateDepth = 2,
                    nominalConstraintConstructionDepth = 0,
                    nominalCarrierKind = carrierKind,
                    retainOuterNominalConstraint = retainConstraint,
                    nominalCarrierEncodedAsValueType = encodedAsValueType,
                )

                assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
                    DotNetGenericOwnerPhysicalDeclarationIndex
                        .bindRetainedForeignInheritedReceiver(
                            fixture.source,
                            fixture.method,
                            assertNotNull(fixture.inheritedReceiverSource),
                            DotNetTarget.NET10_0,
                        )
                )
            }
        }
    }

    @Test
    fun `CLR variant value TypeDef conflicts with the nominal carrier grammar`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 1,
            nominalCarrierKind = NominalCarrierKind.VALUE_TYPE,
            retainOuterNominalConstraint = false,
            genericNominalValueCovariant = true,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
                DotNetTarget.NET10_0,
            )
        )
    }

    @Test
    fun `nominal reference class carrier requires selected hierarchy authority`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 1,
            nominalCarrierKind = NominalCarrierKind.REFERENCE_CLASS,
            includeNominalConstraintHierarchy = false,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `CLR variant reference TypeDef conflicts with ordinary class carrier grammar`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 1,
            nominalCarrierKind = NominalCarrierKind.REFERENCE_CLASS,
            genericNominalClassCovariant = true,
        )
        val fakeDelegate = fixture.source.graph.hierarchies
            .map { hierarchy -> hierarchy.type.type }
            .single { type ->
                type.definition.namespaceName == "Synthetic.Constraint" &&
                        type.definition.metadataName == "MarkerClassOf`1"
            }
        assertEquals(
            "Invoke",
            fakeDelegate.assembly.methodDefinitions.single { method ->
                method.declaringType == fakeDelegate.definition.handle
            }.name,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            )
        )
    }

    @Test
    fun `non-sealed CLR delegate conflicts with variant carrier grammar`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 1,
            nominalCarrierKind = NominalCarrierKind.DELEGATE,
            retainOuterNominalConstraint = false,
            genericNominalDelegateSealed = false,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            )
        )
    }

    @Test
    fun `variant delegate requires selected root and hierarchy authority`() {
        val withoutRoot = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 1,
            nominalCarrierKind = NominalCarrierKind.DELEGATE,
            retainOuterNominalConstraint = false,
            includePhysicalCoreTypes = false,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                withoutRoot.source,
                withoutRoot.method,
                assertNotNull(withoutRoot.inheritedReceiverSource),
            ),
        )

        val withoutHierarchy = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 1,
            nominalCarrierKind = NominalCarrierKind.DELEGATE,
            retainOuterNominalConstraint = false,
            includeNominalConstraintHierarchy = false,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                withoutHierarchy.source,
                withoutHierarchy.method,
                assertNotNull(withoutHierarchy.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `missing nested nominal source constraint rejects an inherited edge`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 2,
            omitLastNominalConstraint = true,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            )
        )
    }

    @Test
    fun `nested nominal carrier beyond its depth budget is unavailable`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 65,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `nested special-constrained nominal TypeDef requires target authority`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 1,
            nestedNominalCarrierParameterAttributes = REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
            nestedNominalCarrierLeafPrimitive = DotNetClrPrimitiveType.STRING,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `special-constrained inherited edge requires target authority`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            intermediateParameterAttributes = REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
            closedLeafPrimitive = DotNetClrPrimitiveType.STRING,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `special-constrained inherited edges preserve exact operation routes`() {
        assertSpecialConstrainedInheritedEdgeRoute(
            REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
            DotNetClrPrimitiveType.STRING,
        )
        assertSpecialConstrainedInheritedEdgeRoute(
            NOT_NULLABLE_VALUE_TYPE_CONSTRAINT_ATTRIBUTE or
                    DEFAULT_CONSTRUCTOR_CONSTRAINT_ATTRIBUTE,
            DotNetClrPrimitiveType.INT32,
        )
    }

    @Test
    fun `special-constrained variance targets are validated without escaping construction scope`() {
        val cases = listOf(
            Triple(
                REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
                DotNetClrPrimitiveType.STRING,
                listOf(DotNetTarget.NET48, DotNetTarget.NET10_0),
            ),
            Triple(
                NOT_NULLABLE_VALUE_TYPE_CONSTRAINT_ATTRIBUTE or
                        DEFAULT_CONSTRUCTOR_CONSTRAINT_ATTRIBUTE,
                DotNetClrPrimitiveType.INT32,
                listOf(DotNetTarget.NET48, DotNetTarget.NET10_0),
            ),
            Triple(
                REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE or ALLOW_BY_REF_LIKE_ATTRIBUTE,
                DotNetClrPrimitiveType.STRING,
                listOf(DotNetTarget.NET10_0),
            ),
        )
        for (case in cases) {
            val parameterAttributes = case.first
            val exactArgument = case.second
            for (target in case.third) {
                val fixture = deepCrossAssemblyFixture(
                    intermediateGenericArity = 2,
                    intermediateParameterAttributes = parameterAttributes,
                    closedLeafPrimitive = exactArgument,
                    rootOwnerDependentInput = false,
                    rootOwnerParameterAttributes =
                        if (parameterAttributes and ALLOW_BY_REF_LIKE_ATTRIBUTE != 0) {
                            ALLOW_BY_REF_LIKE_ATTRIBUTE
                        } else {
                            0
                        },
                )
                val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
                val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalDeclarationIndex,
                        >>(
                    DotNetGenericOwnerPhysicalDeclarationIndex
                        .bindRetainedForeignInheritedReceiver(
                            fixture.source,
                            fixture.method,
                            receiverSource,
                            target,
                        )
                ).value
                val intermediateIdentity =
                    DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                        receiverSource,
                        assertNotNull(fixture.intermediateType),
                    )
                val exactCarrier = when (exactArgument) {
                    DotNetClrPrimitiveType.INT32 ->
                        DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()
                    DotNetClrPrimitiveType.STRING ->
                        DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()
                    else -> error("unsupported constrained variance argument: $exactArgument")
                }
                val sourceConstruction =
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                        intermediateIdentity,
                        listOf(
                            exactCarrier,
                            DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                        ),
                    )
                val targetConstruction =
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                        intermediateIdentity,
                        listOf(
                            exactCarrier,
                            DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                        ),
                    )
                val targetView = DotNetGenericOwnerPhysicalView(targetConstruction)
                val receiverConstruction = boundConstruction(
                    declarations,
                    DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                        receiverSource,
                    ),
                    emptyList(),
                )
                val receiverValue = directValue(declarations, receiverConstruction)
                val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalInterfaceViewClosure,
                        >>(
                    declarations.physicalInterfaceViewClosureOrError(receiverConstruction),
                ).value
                assertEquals(
                    true,
                    DotNetGenericOwnerPhysicalView(sourceConstruction) in closure.interfaceViews,
                )
                assertEquals(false, targetView in closure.interfaceViews)
                assertEquals(
                    DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                    declarations.constructTypeOrError(
                        intermediateIdentity,
                        targetConstruction.arguments,
                    ),
                )

                val converted = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerProducedValueFact,
                        >>(
                    receiverValue.selectClrReferenceVarianceViewOrError(
                        declarations,
                        targetView,
                    )
                ).value
                assertEquals(receiverValue.layout, converted.layout)
                assertEquals(
                    targetView,
                    converted.provenance.selectedViewLineage[intermediateIdentity],
                )
                assertEquals(
                    setOf(
                        DotNetGenericOwnerPhysicalViewEvidence
                            .CLR_REFERENCE_VARIANCE_CONVERSION,
                    ),
                    assertIs<DotNetGenericOwnerGuaranteedViews.Known>(
                        converted.provenance.guaranteedViews,
                    ).evidenceByView[targetView],
                )
                assertEquals(
                    false,
                    targetView in assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                            DotNetGenericOwnerPhysicalInterfaceViewClosure,
                            >>(
                        declarations.physicalInterfaceViewClosureOrError(receiverConstruction),
                    ).value.interfaceViews,
                )
            }
        }
    }

    @Test
    fun `violated special constraint rejects only the requested variance target`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateGenericArity = 2,
            intermediateParameterAttributes = REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
            closedLeafPrimitive = DotNetClrPrimitiveType.STRING,
            rootOwnerDependentInput = false,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
                DotNetTarget.NET10_0,
            )
        ).value
        val intermediateIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                receiverSource,
                assertNotNull(fixture.intermediateType),
            )
        val invalidTarget = DotNetGenericOwnerPhysicalView(
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                intermediateIdentity,
                listOf(
                    DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
                    DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                ),
            )
        )
        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            emptyList(),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            directValue(declarations, receiverConstruction)
                .selectClrReferenceVarianceViewOrError(declarations, invalidTarget),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.constructTypeOrError(
                intermediateIdentity,
                invalidTarget.construction.arguments,
            ),
        )
    }

    @Test
    fun `nominally constrained variance target reuses selected CLR assignability`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 1,
            nestedNominalCarrierLeafPrimitive = DotNetClrPrimitiveType.STRING,
            nestedNominalCarrierNominalConstraint = true,
            nestedNominalCarrierObjectConstraint = true,
            rootOwnerDependentInput = false,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val genericMarkerType = fixture.source.graph.hierarchies
            .map { hierarchy -> hierarchy.type.type }
            .single { type -> type.definition.metadataName == "MarkerOf`1" }
        val genericMarkerIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                receiverSource,
                genericMarkerType,
            )
        val sourceConstruction =
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                genericMarkerIdentity,
                listOf(DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()),
            )
        val targetConstruction =
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                genericMarkerIdentity,
                listOf(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
            )
        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            emptyList(),
        )
        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(
                    declarations,
                    DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                ),
                receiverSource,
            )
        ).value
        val produced = assertNotNull(route.producedResult)
        assertEquals(
            sourceConstruction,
            assertIs<DotNetGenericOwnerProducedValueLayout.Direct>(produced.layout)
                .carrier.type,
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.constructTypeOrError(
                genericMarkerIdentity,
                targetConstruction.arguments,
            ),
        )

        val targetView = DotNetGenericOwnerPhysicalView(targetConstruction)
        val converted = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerProducedValueFact,
                >>(
            produced.selectClrReferenceVarianceViewOrError(declarations, targetView),
        ).value
        assertEquals(produced.layout, converted.layout)
        assertEquals(
            targetView,
            converted.provenance.selectedViewLineage[genericMarkerIdentity],
        )
    }

    @Test
    fun `violated nominal constraint cannot borrow its exact retained edge proof`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 1,
            nestedNominalCarrierNominalConstraint = true,
            rootOwnerDependentInput = false,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val genericMarkerIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                receiverSource,
                fixture.source.graph.hierarchies
                    .map { hierarchy -> hierarchy.type.type }
                    .single { type -> type.definition.metadataName == "MarkerOf`1" },
            )
        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            emptyList(),
        )
        val produced = assertNotNull(
            assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalOperationRoute,
                    >>(
                selectRetainedRoute(
                    fixture,
                    directValue(declarations, receiverConstruction),
                    exactTransferArguments(
                        declarations,
                        DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                    ),
                    receiverSource,
                )
            ).value.producedResult
        )
        val invalidTarget = DotNetGenericOwnerPhysicalView(
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                genericMarkerIdentity,
                listOf(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
            )
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            produced.selectClrReferenceVarianceViewOrError(declarations, invalidTarget),
        )
        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.constructTypeOrError(
                genericMarkerIdentity,
                invalidTarget.construction.arguments,
            ),
        )
    }

    private fun assertSpecialConstrainedInheritedEdgeRoute(
        parameterAttributes: Int,
        argument: DotNetClrPrimitiveType,
    ) {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            intermediateParameterAttributes = parameterAttributes,
            closedLeafPrimitive = argument,
            rootOwnerDependentInput = false,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
                DotNetTarget.NET10_0,
            )
        ).value
        val argumentCarrier = when (argument) {
            DotNetClrPrimitiveType.INT32 ->
                DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()
            DotNetClrPrimitiveType.STRING ->
                DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()
            else -> error("unsupported special-constraint test argument: $argument")
        }
        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            emptyList(),
        )
        val parentConstruction =
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                retainedOwnerIdentity(fixture),
                listOf(argumentCarrier),
            )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(
                    declarations,
                    DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                ),
                receiverSource,
                DotNetTarget.NET10_0,
            )
        ).value
        assertEquals(parentConstruction, route.requiredReceiverView.construction)
        assertEquals(
            argumentCarrier,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
    }

    @Test
    fun `violated inherited special constraint conflicts through shared validation`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            intermediateParameterAttributes = REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
            closedLeafPrimitive = DotNetClrPrimitiveType.INT32,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
                DotNetTarget.NET10_0,
            )
        )
    }

    @Test
    fun `by-ref-like binder propagation is target aware`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            intermediateParameterAttributes = ALLOW_BY_REF_LIKE_ATTRIBUTE,
            rootOwnerParameterAttributes = ALLOW_BY_REF_LIKE_ATTRIBUTE,
            closedLeafPrimitive = DotNetClrPrimitiveType.STRING,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            ),
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
                DotNetTarget.NET10_0,
            )
        )
        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
                DotNetTarget.NET48,
            )
        )
    }

    @Test
    fun `nested special-constrained nominal carriers preserve exact operation routes`() {
        assertNestedSpecialConstrainedCarrierRoute(
            parameterAttributes = REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
            argument = DotNetClrPrimitiveType.STRING,
            usesReferenceClass = false,
        )
        assertNestedSpecialConstrainedCarrierRoute(
            parameterAttributes = NOT_NULLABLE_VALUE_TYPE_CONSTRAINT_ATTRIBUTE or
                    DEFAULT_CONSTRUCTOR_CONSTRAINT_ATTRIBUTE,
            argument = DotNetClrPrimitiveType.INT32,
            usesReferenceClass = true,
        )
        assertNestedSpecialConstrainedCarrierRoute(
            parameterAttributes = REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE or
                    ALLOW_BY_REF_LIKE_ATTRIBUTE,
            argument = DotNetClrPrimitiveType.STRING,
            usesReferenceClass = false,
        )
    }

    private fun assertNestedSpecialConstrainedCarrierRoute(
        parameterAttributes: Int,
        argument: DotNetClrPrimitiveType,
        usesReferenceClass: Boolean,
    ) {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 1,
            nominalCarrierKind = usesReferenceClass.toNominalCarrierKind(),
            nestedNominalCarrierParameterAttributes = parameterAttributes,
            nestedNominalCarrierLeafPrimitive = argument,
            rootOwnerDependentInput = false,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
                DotNetTarget.NET10_0,
            )
        ).value
        val carrierType = fixture.source.graph.hierarchies
            .map { hierarchy -> hierarchy.type.type }
            .single { type ->
                type.definition.metadataName ==
                        if (usesReferenceClass) "MarkerClassOf`1" else "MarkerOf`1"
            }
        val carrierIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
            receiverSource,
            carrierType,
        )
        val argumentCarrier = when (argument) {
            DotNetClrPrimitiveType.INT32 ->
                DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()
            DotNetClrPrimitiveType.STRING ->
                DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()
            else -> error("unsupported special-constraint test argument: $argument")
        }
        val constrainedConstruction =
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                carrierIdentity,
                listOf(argumentCarrier),
            )
        val parentConstruction =
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                retainedOwnerIdentity(fixture),
                listOf(constrainedConstruction),
            )
        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            emptyList(),
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.constructTypeOrError(
                carrierIdentity,
                listOf(argumentCarrier),
            ),
        )
        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(
                    declarations,
                    DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                ),
                receiverSource,
                DotNetTarget.NET10_0,
            )
        ).value
        assertEquals(parentConstruction, route.requiredReceiverView.construction)
        assertEquals(
            constrainedConstruction,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
    }

    @Test
    fun `violated nested special constraints conflict through shared validation`() {
        for (case in listOf(
            Triple(
                REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
                DotNetClrPrimitiveType.INT32,
                false,
            ),
            Triple(
                NOT_NULLABLE_VALUE_TYPE_CONSTRAINT_ATTRIBUTE,
                DotNetClrPrimitiveType.STRING,
                true,
            ),
        )) {
            val attributes = case.first
            val argument = case.second
            val usesReferenceClass = case.third
            val fixture = deepCrossAssemblyFixture(
                intermediateDepth = 2,
                nominalConstraintConstructionDepth = 1,
                nominalCarrierKind = usesReferenceClass.toNominalCarrierKind(),
                nestedNominalCarrierParameterAttributes = attributes,
                nestedNominalCarrierLeafPrimitive = argument,
            )

            assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
                DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                    fixture.source,
                    fixture.method,
                    assertNotNull(fixture.inheritedReceiverSource),
                    DotNetTarget.NET10_0,
                )
            )
        }
    }

    @Test
    fun `nested nominally constrained carrier stays scoped to its retained edge`() {
        assertNestedNominallyConstrainedCarrierStaysScoped(usesReferenceClass = false)
        assertNestedNominallyConstrainedCarrierStaysScoped(usesReferenceClass = true)
    }

    private fun assertNestedNominallyConstrainedCarrierStaysScoped(
        usesReferenceClass: Boolean,
    ) {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            nominalConstraintConstructionDepth = 1,
            nominalCarrierKind = usesReferenceClass.toNominalCarrierKind(),
            nestedNominalCarrierNominalConstraint = true,
            rootOwnerDependentInput = false,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val markerTypes = fixture.source.graph.hierarchies
            .map { hierarchy -> hierarchy.type.type }
            .filter { type -> type.definition.namespaceName == "Synthetic.Constraint" }
            .associateBy { type -> type.definition.metadataName }

        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val markerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
            receiverSource,
            assertNotNull(markerTypes["Marker"]),
        )
        val genericMarkerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
            receiverSource,
            assertNotNull(
                markerTypes[if (usesReferenceClass) "MarkerClassOf`1" else "MarkerOf`1"],
            ),
        )
        val markerConstruction = boundConstruction(
            declarations,
            markerIdentity,
            emptyList(),
        )
        val constrainedConstruction =
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                genericMarkerIdentity,
                listOf(markerConstruction),
            )
        val parentConstruction =
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                retainedOwnerIdentity(fixture),
                listOf(constrainedConstruction),
            )
        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            emptyList(),
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            declarations.constructTypeOrError(
                genericMarkerIdentity,
                listOf(markerConstruction),
            ),
        )
        val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalInterfaceViewClosure,
                >>(
            declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
        ).value
        assertEquals(true, closure.isComplete)
        assertEquals(
            true,
            DotNetGenericOwnerPhysicalView(parentConstruction) in closure.interfaceViews,
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(
                    declarations,
                    DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
                ),
                receiverSource,
            )
        ).value
        assertEquals(parentConstruction, route.requiredReceiverView.construction)
        assertEquals(
            constrainedConstruction,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
        assertEquals(
            constrainedConstruction,
            assertIs<DotNetGenericOwnerProducedValueLayout.Direct>(
                assertNotNull(route.producedResult).layout,
            ).carrier.type,
        )
    }

    @Test
    fun `invalid nested nominally constrained carrier conflicts through shared validation`() {
        for (usesReferenceClass in listOf(false, true)) {
            val fixture = deepCrossAssemblyFixture(
                intermediateDepth = 2,
                nominalConstraintConstructionDepth = 1,
                nominalCarrierKind = usesReferenceClass.toNominalCarrierKind(),
                nestedNominalCarrierNominalConstraint = true,
                violateNestedNominalCarrierConstraint = true,
                rootOwnerDependentInput = false,
            )

            assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
                DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                    fixture.source,
                    fixture.method,
                    assertNotNull(fixture.inheritedReceiverSource),
                )
            )
        }
    }

    @Test
    fun `multiple intermediate binders preserve ordered permutation across assemblies`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            intermediateGenericArity = 2,
            reverseIntermediateArguments = true,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val receiverType = receiverSource.declaringHierarchy.type.type
        val intermediateTypes = fixture.source.graph.hierarchies
            .map { hierarchy -> hierarchy.type.type }
            .filter { type ->
                !type.hasSameIdentityAs(fixture.source.declaringHierarchy.type.type) &&
                        !type.hasSameIdentityAs(receiverType)
            }
        assertEquals(2, intermediateTypes.size)

        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val intCarrier = DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()
        val stringCarrier = DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()
        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            emptyList(),
        )
        val innerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
            receiverSource,
            intermediateTypes[0],
        )
        val outerIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
            receiverSource,
            intermediateTypes[1],
        )
        val innerConstruction = boundConstruction(
            declarations,
            innerIdentity,
            listOf(stringCarrier, intCarrier),
        )
        val outerConstruction = boundConstruction(
            declarations,
            outerIdentity,
            listOf(intCarrier, stringCarrier),
        )
        val parentIdentity = retainedOwnerIdentity(fixture)
        val parentIntConstruction = boundConstruction(
            declarations,
            parentIdentity,
            listOf(intCarrier),
        )
        val parentStringConstruction = boundConstruction(
            declarations,
            parentIdentity,
            listOf(stringCarrier),
        )

        listOf(innerIdentity, outerIdentity).forEach { identity ->
            assertEquals(
                listOf(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
                ),
                assertNotNull(declarations.typeDescriptionOrNull(identity))
                    .genericParameters.map { parameter -> parameter.variance },
            )
        }
        val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalInterfaceViewClosure,
                >>(
            declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
        ).value
        assertEquals(true, closure.isComplete)
        assertEquals(
            setOf(
                receiverConstruction,
                outerConstruction,
                innerConstruction,
                parentIntConstruction,
            ).map(::DotNetGenericOwnerPhysicalView).toSet(),
            closure.interfaceViews,
        )
        assertEquals(
            false,
            DotNetGenericOwnerPhysicalView(parentStringConstruction) in closure.interfaceViews,
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(declarations),
                receiverSource,
            )
        ).value
        assertEquals(parentIntConstruction, route.requiredReceiverView.construction)
        assertEquals(
            intCarrier,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
    }

    @Test
    fun `intermediate binder vector beyond its resource budget is unavailable`() {
        val overLimit = DotNetGenericOwnerPhysicalFamilyCodec.MAX_PHYSICAL_COLLECTION_SIZE + 1
        val fixture = deepCrossAssemblyFixture(
            intermediateGenericArity = overLimit,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `duplicate binder handles cannot bypass the raw row budget`() {
        val overLimit = DotNetGenericOwnerPhysicalFamilyCodec.MAX_PHYSICAL_COLLECTION_SIZE + 1
        val fixture = deepCrossAssemblyFixture(
            duplicateFirstBinderRows = overLimit,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `recursive retained graph authenticates a complete intermediate diamond`() {
        val fixture = deepCrossAssemblyFixture(
            intermediateDepth = 2,
            includeIntermediateAuxiliaryBranch = true,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            emptyList(),
        )
        val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalInterfaceViewClosure,
                >>(
            declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
        ).value

        assertEquals(true, closure.isComplete)
        assertEquals(5, closure.interfaceViews.size)
        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(declarations),
                receiverSource,
            )
        ).value
        assertEquals(retainedOwnerIdentity(fixture), route.requiredReceiverView.construction.definition)
        assertEquals(
            listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
            route.requiredReceiverView.construction.arguments,
        )
    }

    @Test
    fun `cyclic retained intermediate graph is a declaration conflict`() {
        val fixture = deepCrossAssemblyFixture(includeIntermediateSelfCycle = true)

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            )
        )
    }

    @Test
    fun `retained intermediate graph beyond its depth budget is unavailable`() {
        val fixture = deepCrossAssemblyFixture(intermediateDepth = 65)

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `intermediate interface without retained hierarchy cannot prove a deep route`() {
        val fixture = deepCrossAssemblyFixture(includeRetainedIntermediateHierarchy = false)

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `TypeDef carrier rejects a mismatched retained hierarchy`() {
        val fixture = fixture(includeInheritedReceiver = true)
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)

        assertFailsWith<IllegalArgumentException> {
            DotNetClrImportedTypeSource(
                receiverSource.assembly,
                fixture.source.declaringType,
                receiverSource.declaringHierarchy,
                receiverSource.graph,
            )
        }
    }

    @Test
    fun `memberless IR class resolves its retained TypeDef and inherited construction`() {
        val fixture = fixture(includeInheritedReceiver = true)
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val importedClass = IrFactoryImpl.buildClass {
            name = Name.identifier("IntSource")
            kind = ClassKind.INTERFACE
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            metadata = object : MetadataSource.Class {
                override val name: Name = this@apply.name
                override val platformDeclarationSource: Any = receiverSource

                override fun recordLocalClassType(type: FqName) = Unit

                override fun asFirSymbol(): Any? = null
            }
        }
        val referencedAssemblies = mutableListOf<DotNetClrClasspathAssembly.WithoutCarrier>()
        val declarations = DotNetClrImportedDeclarations(
            assemblyReferenceSink = referencedAssemblies::add,
            coreLibraryReference = "System.Private.CoreLib",
        )

        val classInfo = assertNotNull(declarations.classInfoOrNull(importedClass))

        assertEquals(emptyList(), importedClass.declarations)
        assertEquals("Foreign.IntSource", classInfo.ilClassName)
        assertEquals(0, classInfo.typeParameterCount)
        val inherited = assertIs<DotNetIlValueType.GenericInstance>(classInfo.interfaces.single())
        assertEquals("Foreign.Source`1", inherited.classInfo.ilClassName)
        assertEquals(listOf(DotNetIlValueType.Int32), inherited.arguments)
        assertEquals(listOf(receiverSource.assembly), referencedAssemblies.distinct())
    }

    @Test
    fun `cross assembly memberless IR class retains both exact assembly references`() {
        val fixture = crossAssemblyFixture()
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val importedClass = IrFactoryImpl.buildClass {
            name = Name.identifier("IntSource")
            kind = ClassKind.INTERFACE
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            metadata = object : MetadataSource.Class {
                override val name: Name = this@apply.name
                override val platformDeclarationSource: Any = receiverSource

                override fun recordLocalClassType(type: FqName) = Unit

                override fun asFirSymbol(): Any? = null
            }
        }
        val referencedAssemblies = mutableListOf<DotNetClrClasspathAssembly.WithoutCarrier>()
        val declarations = DotNetClrImportedDeclarations(
            assemblyReferenceSink = referencedAssemblies::add,
            coreLibraryReference = "System.Private.CoreLib",
        )

        val classInfo = assertNotNull(declarations.classInfoOrNull(importedClass))

        assertEquals("Foreign.Child", classInfo.assemblyName)
        val inherited = assertIs<DotNetIlValueType.GenericInstance>(classInfo.interfaces.single())
        assertEquals("Foreign.Authority", inherited.classInfo.assemblyName)
        assertEquals(listOf(DotNetIlValueType.Int32), inherited.arguments)
        assertEquals(
            listOf(receiverSource.assembly, fixture.source.assembly),
            referencedAssemblies.distinct(),
        )
    }

    @Test
    fun `retained InterfaceImpl disagreement with raw TypeSpec is a conflict`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            retainedInheritedArgument = DotNetClrResolvedTypeSignature.Primitive(
                DotNetClrPrimitiveType.STRING,
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            )
        )
    }

    @Test
    fun `open retained InterfaceImpl forwards its receiver binder without boxing`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val receiverIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
        val parentIdentity = retainedOwnerIdentity(fixture)
        val receiverParameter = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerSymbolicCarrierReference.Parameter,
                >>(
            declarations.typeParameterOrError(receiverIdentity, 0)
        ).value
        val edgeSet = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                Set<DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference>,
                >>(
            declarations.directSupertypeEdgesOrUnavailable(receiverIdentity)
        ).value
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                parentIdentity,
                listOf(receiverParameter),
            ),
            edgeSet.single().target,
        )

        listOf(
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
            DotNetGenericOwnerSymbolicCarrierReference.stringCarrier(),
        ).forEach { argument ->
            val receiverConstruction = boundConstruction(
                declarations,
                receiverIdentity,
                listOf(argument),
            )
            val parentConstruction = boundConstruction(
                declarations,
                parentIdentity,
                listOf(argument),
            )
            val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalInterfaceViewClosure,
                    >>(
                declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
            ).value
            assertEquals(true, closure.isComplete)
            assertEquals(
                setOf(
                    DotNetGenericOwnerPhysicalView(receiverConstruction),
                    DotNetGenericOwnerPhysicalView(parentConstruction),
                ),
                closure.interfaceViews,
            )

            val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalOperationRoute,
                    >>(
                selectRetainedRoute(
                    fixture,
                    directValue(declarations, receiverConstruction),
                    exactTransferArguments(declarations, argument),
                    receiverSource,
                )
            ).value
            assertEquals(parentConstruction, route.requiredReceiverView.construction)
            assertEquals(
                argument,
                assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                    route.instantiatedSignature.resultLayout,
                ).slot.carrier,
            )
        }
    }

    @Test
    fun `open retained InterfaceImpl substitutes its binder inside an SZ array`() {
        val rawArray = DotNetClrTypeSignature.SzArray(
            DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, 0),
        )
        val retainedArray = DotNetClrResolvedTypeSignature.SzArray(
            DotNetClrResolvedTypeSignature.GenericParameter(
                DotNetClrGenericParameterKind.TYPE,
                0,
            ),
        )
        val fixture = fixture(
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
            rawInheritedArgument = rawArray,
            retainedInheritedArgument = retainedArray,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val int32 = DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()
        val int32Array = DotNetGenericOwnerSymbolicCarrierReference.SzArray(int32)
        val receiverConstruction = boundConstruction(
            declarations,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
            listOf(int32),
        )
        val parentConstruction = boundConstruction(
            declarations,
            retainedOwnerIdentity(fixture),
            listOf(int32Array),
        )

        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(declarations, int32Array),
                receiverSource,
            )
        ).value
        assertEquals(parentConstruction, route.requiredReceiverView.construction)
        assertEquals(
            int32Array,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
    }

    @Test
    fun `closed receiver view cannot authorize an open retained TypeDef`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
            closedInheritedReceiverView = true,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `open receiver retains CLR variance while forwarding its binder`() {
        val fixture = fixture(
            ownerParameterAttributes = COVARIANT_ATTRIBUTE,
            ownerDependentInput = false,
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
            inheritedReceiverParameterAttributes = COVARIANT_ATTRIBUTE,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val receiverIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
        assertEquals(
            DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT,
            assertNotNull(declarations.typeDescriptionOrNull(receiverIdentity))
                .genericParameters.single().variance,
        )

        val stringCarrier = DotNetGenericOwnerSymbolicCarrierReference.stringCarrier()
        val receiverConstruction = boundConstruction(
            declarations,
            receiverIdentity,
            listOf(stringCarrier),
        )
        val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalOperationRoute,
                >>(
            selectRetainedRoute(
                fixture,
                directValue(declarations, receiverConstruction),
                exactTransferArguments(declarations, stringCarrier),
                receiverSource,
            )
        ).value
        assertEquals(
            stringCarrier,
            assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                route.instantiatedSignature.resultLayout,
            ).slot.carrier,
        )
    }

    @Test
    fun `open receiver retains contravariant CLR binder authority`() {
        val int32Raw = DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32)
        val int32Retained = DotNetClrResolvedTypeSignature.Primitive(
            DotNetClrPrimitiveType.INT32,
        )
        val fixture = fixture(
            ownerParameterAttributes = CONTRAVARIANT_ATTRIBUTE,
            rawReturnType = int32Raw,
            retainedReturnType = int32Retained,
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
            inheritedReceiverParameterAttributes = CONTRAVARIANT_ATTRIBUTE,
        )
        val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                receiverSource,
            )
        ).value
        val receiverIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
        assertEquals(
            DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT,
            assertNotNull(declarations.typeDescriptionOrNull(receiverIdentity))
                .genericParameters.single().variance,
        )
    }

    @Test
    fun `open retained InterfaceImpl cannot escape its receiver binder`() {
        val escapedParameter = DotNetClrTypeSignature.GenericParameter(
            DotNetClrGenericParameterKind.TYPE,
            1,
        )
        val fixture = fixture(
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
            rawInheritedArgument = escapedParameter,
            retainedInheritedArgument = DotNetClrResolvedTypeSignature.GenericParameter(
                DotNetClrGenericParameterKind.TYPE,
                1,
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            )
        )
    }

    @Test
    fun `constrained open receiver retains its special binder authority`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            openInheritedReceiver = true,
            inheritedReceiverParameterAttributes = REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE,
        )

        val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                DotNetGenericOwnerPhysicalDeclarationIndex,
                >>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            )
        ).value
        val receiverIdentity = DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
            assertNotNull(fixture.inheritedReceiverSource),
        )
        val parameter = assertNotNull(declarations.typeDescriptionOrNull(receiverIdentity))
            .genericParameters.single()
        assertEquals(true, parameter.hasReferenceTypeConstraint)
        assertEquals(false, parameter.hasNotNullableValueTypeConstraint)
        assertEquals(false, parameter.hasDefaultConstructorConstraint)
        assertEquals(false, parameter.allowsByRefLike)
    }

    @Test
    fun `missing retained InterfaceImpl cannot prove the inherited MethodDef route`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            inheritedInterfaceCount = 0,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `multiple exact InterfaceImpl rows preserve every view and select owner by identity`() {
        for (relatedFirst in listOf(true, false)) {
            val fixture = fixture(
                includeInheritedReceiver = true,
                includeInheritedRelatedEdge = true,
                inheritedRelatedEdgeFirst = relatedFirst,
            )
            val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
            val relatedType = assertNotNull(fixture.relatedType)
            assertEquals(
                relatedFirst,
                receiverSource.declaringHierarchy.interfaces.first().interfaceType.type
                    .hasSameIdentityAs(relatedType),
            )
            val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalDeclarationIndex,
                    >>(
                DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                    fixture.source,
                    fixture.method,
                    receiverSource,
                )
            ).value
            val receiverIdentity =
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource)
            val parentIdentity = retainedOwnerIdentity(fixture)
            val relatedIdentity =
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                    receiverSource,
                    relatedType,
                )
            val receiverConstruction = boundConstruction(
                declarations,
                receiverIdentity,
                emptyList(),
            )
            val parentConstruction = boundConstruction(
                declarations,
                parentIdentity,
                listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
            )
            val relatedConstruction = boundConstruction(
                declarations,
                relatedIdentity,
                emptyList(),
            )

            val directEdges = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    Set<DotNetGenericOwnerPhysicalDirectSupertypeEdgeReference>,
                    >>(
                declarations.directSupertypeEdgesOrUnavailable(receiverIdentity)
            ).value
            assertEquals(
                setOf<DotNetGenericOwnerSymbolicCarrierReference>(
                    parentConstruction,
                    relatedConstruction,
                ),
                directEdges.mapTo(linkedSetOf()) { edge -> edge.target },
            )
            val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalInterfaceViewClosure,
                    >>(
                declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
            ).value
            assertEquals(true, closure.isComplete)
            assertEquals(
                setOf(
                    DotNetGenericOwnerPhysicalView(receiverConstruction),
                    DotNetGenericOwnerPhysicalView(parentConstruction),
                    DotNetGenericOwnerPhysicalView(relatedConstruction),
                ),
                closure.interfaceViews,
            )

            val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalOperationRoute,
                    >>(
                selectRetainedRoute(
                    fixture,
                    directValue(declarations, receiverConstruction),
                    exactTransferArguments(declarations),
                    receiverSource,
                )
            ).value
            assertEquals(parentConstruction, route.requiredReceiverView.construction)
            assertEquals(retainedMethodIdentity(fixture), route.method.identity)
        }
    }

    @Test
    fun `distinct constructions of one owner require independently proven lineage`() {
        for (booleanEdgeFirst in listOf(true, false)) {
            val booleanCarrier = DotNetGenericOwnerSymbolicCarrierReference.booleanCarrier()
            val fixture = fixture(
                includeInheritedReceiver = true,
                secondRawInheritedArgument = DotNetClrTypeSignature.Primitive(
                    DotNetClrPrimitiveType.BOOLEAN,
                ),
                secondRetainedInheritedArgument = DotNetClrResolvedTypeSignature.Primitive(
                    DotNetClrPrimitiveType.BOOLEAN,
                ),
                secondInheritedEdgeFirst = booleanEdgeFirst,
            )
            val receiverSource = assertNotNull(fixture.inheritedReceiverSource)
            val declarations = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalDeclarationIndex,
                    >>(
                DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                    fixture.source,
                    fixture.method,
                    receiverSource,
                )
            ).value
            val receiverConstruction = boundConstruction(
                declarations,
                DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(receiverSource),
                emptyList(),
            )
            val ownerIdentity = retainedOwnerIdentity(fixture)
            val intConstruction = boundConstruction(
                declarations,
                ownerIdentity,
                listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
            )
            val booleanConstruction = boundConstruction(
                declarations,
                ownerIdentity,
                listOf(booleanCarrier),
            )
            val intView = DotNetGenericOwnerPhysicalView(intConstruction)
            val booleanView = DotNetGenericOwnerPhysicalView(booleanConstruction)
            val receiver = directValue(declarations, receiverConstruction)

            val closure = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalInterfaceViewClosure,
                    >>(
                declarations.physicalInterfaceViewClosureOrError(receiverConstruction)
            ).value
            assertEquals(true, closure.isComplete)
            assertEquals(
                setOf(
                    DotNetGenericOwnerPhysicalView(receiverConstruction),
                    intView,
                    booleanView,
                ),
                closure.interfaceViews,
            )
            assertEquals(
                DotNetGenericOwnerPhysicalBindingResult.Unavailable,
                selectRetainedRoute(
                    fixture,
                    receiver,
                    exactTransferArguments(declarations),
                    receiverSource,
                ),
            )

            listOf(
                intView to DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
                booleanView to booleanCarrier,
            ).forEach { entry ->
                val selectedView = entry.first
                val ownerArgument = entry.second
                val selectedReceiver = assertNotNull(
                    receiver.selectRecordedPhysicalInterfaceViewOrNull(
                        declarations,
                        selectedView,
                    )
                )
                val route = assertIs<DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalOperationRoute,
                        >>(
                    selectRetainedRoute(
                        fixture,
                        selectedReceiver,
                        exactTransferArguments(declarations, ownerArgument),
                        receiverSource,
                    )
                ).value
                assertEquals(selectedView, route.requiredReceiverView)
                assertEquals(
                    ownerArgument,
                    assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
                        route.instantiatedSignature.resultLayout,
                    ).slot.carrier,
                )
            }

            val fabricatedObjectView = DotNetGenericOwnerPhysicalView(
                boundConstruction(
                    declarations,
                    ownerIdentity,
                    listOf(DotNetGenericOwnerSymbolicCarrierReference.objectCarrier()),
                )
            )
            assertEquals(
                null,
                receiver.selectRecordedPhysicalInterfaceViewOrNull(
                    declarations,
                    fabricatedObjectView,
                )
            )
        }
    }

    @Test
    fun `duplicate InterfaceImpl rows reaching the selected owner are a conflict`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            inheritedInterfaceCount = 2,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `retained InterfaceImpl outside the closed carrier grammar is unavailable`() {
        val fixture = fixture(
            includeInheritedReceiver = true,
            rawInheritedArgument = DotNetClrTypeSignature.Primitive(
                DotNetClrPrimitiveType.INT64,
            ),
            retainedInheritedArgument = DotNetClrResolvedTypeSignature.Primitive(
                DotNetClrPrimitiveType.INT64,
            ),
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                fixture.source,
                fixture.method,
                assertNotNull(fixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `retained receiver from another selected graph cannot authorize an inherited route`() {
        val methodFixture = fixture()
        val receiverFixture = fixture(includeInheritedReceiver = true)

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeignInheritedReceiver(
                methodFixture.source,
                methodFixture.method,
                assertNotNull(receiverFixture.inheritedReceiverSource),
            ),
        )
    }

    @Test
    fun `caller-authored foreign descriptions cannot bypass retained metadata adapter`() {
        val fixture = fixture()
        val retained = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalDeclarationIndex,
                        >,
                >(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        ).value
        val ownerIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(fixture.source)
        val methodIdentity =
            DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr.retained(
                fixture.source,
                fixture.method,
            )
        val owner = assertNotNull(retained.typeDescriptionOrNull(ownerIdentity))
        val method = assertNotNull(retained.methodDescriptionOrNull(methodIdentity))

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bind(
                DotNetGenericOwnerPhysicalAuthorityEpoch.BOUND_DECLARATION_INDEX,
                listOf(owner),
                listOf(method),
                listOf(DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(ownerIdentity, emptyList())),
            ),
        )
    }

    @Test
    fun `resolved carrier disagreement with raw MethodDef is a declaration conflict`() {
        val fixture = fixture(
            retainedReturnType = DotNetClrResolvedTypeSignature.Primitive(
                DotNetClrPrimitiveType.STRING,
            ),
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        )
    }

    @Test
    fun `valid foreign carrier outside current leaf grammar remains unavailable`() {
        val fixture = fixture(
            rawReturnType = DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT64),
            retainedReturnType = DotNetClrResolvedTypeSignature.Primitive(
                DotNetClrPrimitiveType.INT64,
            ),
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            ),
        )
    }

    @Test
    fun `recursive owner construction retains its exact foreign TypeDef binder`() {
        val fixture = fixture(recursiveResult = true)
        val declarations = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalDeclarationIndex,
                        >,
                >(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        ).value
        val ownerIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(fixture.source)
        val methodIdentity =
            DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr.retained(
                fixture.source,
                fixture.method,
            )
        val result = assertIs<DotNetGenericOwnerPhysicalCallableResultLayoutReference.Direct>(
            assertNotNull(declarations.methodDescriptionOrNull(methodIdentity)).signature.resultLayout,
        ).slot

        assertEquals(DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT, result.domain)
        assertEquals(
            DotNetGenericOwnerSymbolicCarrierReference.Constructed.unboundTypeReference(
                ownerIdentity,
                listOf(
                    DotNetGenericOwnerSymbolicCarrierReference.Parameter
                        .unboundTypeParameterReference(ownerIdentity, 0)
                ),
            ),
            result.carrier,
        )
    }

    @Test
    fun `CLR variance on a MethodDef generic parameter is contradictory metadata`() {
        val fixture = fixture(methodParameterAttributes = COVARIANT_ATTRIBUTE)

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        )
    }

    @Test
    fun `reserved combined CLR variance is a declaration conflict`() {
        val fixture = fixture(
            ownerParameterAttributes = COVARIANT_ATTRIBUTE or CONTRAVARIANT_ATTRIBUTE,
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        )
    }

    @Test
    fun `unknown GenericParam flags remain outside the admitted grammar`() {
        val fixture = fixture(ownerParameterAttributes = UNKNOWN_GENERIC_PARAMETER_ATTRIBUTE)

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            ),
        )
    }

    @Test
    fun `retained hierarchy may not omit a raw InterfaceImpl row`() {
        val fixture = fixture(rawInterfaceEdge = true)

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        )
    }

    @Test
    fun `complete retained hierarchy outside the root grammar is unavailable`() {
        val fixture = fixture(
            rawInterfaceEdge = true,
            retainedInterfaceEdge = true,
        )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            ),
        )
    }

    @Test
    fun `closed owner view cannot become open TypeDef authority`() {
        val fixture = fixture(closedOwnerView = true)

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            ),
        )
    }

    @Test
    fun `a MethodDef outside the retained source is a conflict without constructing its identity`() {
        val fixture = fixture()
        val detachedMethod = fixture.method.copy(
            handle = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 2),
            name = "Detached",
        )

        assertIs<DotNetGenericOwnerPhysicalBindingResult.Conflict>(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                detachedMethod,
            )
        )
    }

    @Test
    fun `foreign TypeDef identities retain exact selected rows beyond the declaring source`() {
        val fixture = fixture(includeRelatedType = true)
        val declaring =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(fixture.source)
        val related =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                fixture.source,
                assertNotNull(fixture.relatedType),
            )

        assertNotEquals(declaring, related)
        assertEquals(
            related,
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                fixture.source,
                assertNotNull(fixture.relatedType),
            ),
        )
    }

    @Test
    fun `advancing an index cannot authenticate another selected foreign row`() {
        val fixture = fixture(includeRelatedType = true)
        val retained = assertIs<
                DotNetGenericOwnerPhysicalBindingResult.Bound<
                        DotNetGenericOwnerPhysicalDeclarationIndex,
                        >,
                >(
            DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
                fixture.source,
                fixture.method,
            )
        ).value
        val relatedIdentity =
            DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(
                fixture.source,
                assertNotNull(fixture.relatedType),
            )

        assertEquals(
            DotNetGenericOwnerPhysicalBindingResult.Unavailable,
            retained.advance(
                DotNetGenericOwnerPhysicalAuthorityEpoch.SEALED_EMISSION_SIGNATURE_INDEX,
                listOf(
                    DotNetGenericOwnerPhysicalTypeDefReference(
                        relatedIdentity,
                        emptyList(),
                        DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
                    )
                ),
                emptyList(),
                listOf(
                    DotNetGenericOwnerPhysicalDirectSupertypeEdgeSet(
                        relatedIdentity,
                        emptyList(),
                    )
                ),
            ),
        )
    }

    private fun boundDeclarations(
        fixture: Fixture,
    ): DotNetGenericOwnerPhysicalDeclarationIndex = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerPhysicalDeclarationIndex,
                    >,
            >(
        DotNetGenericOwnerPhysicalDeclarationIndex.bindRetainedForeign(
            fixture.source,
            fixture.method,
        )
    ).value

    private fun retainedOwnerIdentity(
        fixture: Fixture,
    ): DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr =
        DotNetGenericOwnerPhysicalTypeDefIdentity.ForeignClr.retained(fixture.source)

    private fun retainedMethodIdentity(
        fixture: Fixture,
    ): DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr =
        DotNetGenericOwnerPhysicalMethodDefIdentity.ForeignClr.retained(
            fixture.source,
            fixture.method,
        )

    private fun boundConstruction(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        definition: DotNetGenericOwnerPhysicalTypeDefIdentity,
        arguments: List<DotNetGenericOwnerSymbolicCarrierReference>,
    ): DotNetGenericOwnerSymbolicCarrierReference.Constructed = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<
                    DotNetGenericOwnerSymbolicCarrierReference.Constructed,
                    >,
            >(
        declarations.constructTypeOrError(definition, arguments)
    ).value

    private fun boundCarrier(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        type: DotNetGenericOwnerSymbolicCarrierReference,
    ): DotNetGenericOwnerPhysicalCarrier = assertIs<
            DotNetGenericOwnerPhysicalBindingResult.Bound<DotNetGenericOwnerPhysicalCarrier>,
            >(
        declarations.carrierOrError(type)
    ).value

    private fun directValue(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        type: DotNetGenericOwnerSymbolicCarrierReference,
    ): DotNetGenericOwnerProducedValueFact {
        val construction = type as? DotNetGenericOwnerSymbolicCarrierReference.Constructed
        val provenance = construction?.let { exactConstruction ->
            DotNetGenericOwnerPhysicalValueProvenance(
                DotNetGenericOwnerGuaranteedViews.Known(
                    mapOf(
                        DotNetGenericOwnerPhysicalView(exactConstruction) to setOf(
                            DotNetGenericOwnerPhysicalViewEvidence.RETAINED_FOREIGN_METADATA,
                        )
                    ),
                ),
            )
        } ?: DotNetGenericOwnerPhysicalValueProvenance.noNonNullViews()
        return DotNetGenericOwnerProducedValueFact(
            DotNetGenericOwnerProducedValueLayout.Direct(boundCarrier(declarations, type)),
            provenance,
            DotNetGenericOwnerPhysicalNullState.NON_NULL,
        )
    }

    private fun objectValueWithRetainedViews(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        views: List<DotNetGenericOwnerPhysicalView>,
        selectedView: DotNetGenericOwnerPhysicalView? = null,
    ): DotNetGenericOwnerProducedValueFact {
        var provenance = DotNetGenericOwnerPhysicalValueProvenance(
            DotNetGenericOwnerGuaranteedViews.Known(
                views.associateWith {
                    setOf(DotNetGenericOwnerPhysicalViewEvidence.RETAINED_FOREIGN_METADATA)
                },
            ),
        )
        if (selectedView != null) {
            provenance = assertNotNull(provenance.selectViewOrNull(selectedView))
        }
        return DotNetGenericOwnerProducedValueFact(
            DotNetGenericOwnerProducedValueLayout.Direct(
                boundCarrier(
                    declarations,
                    DotNetGenericOwnerSymbolicCarrierReference.objectCarrier(),
                )
            ),
            provenance,
            DotNetGenericOwnerPhysicalNullState.NON_NULL,
        )
    }

    private fun exactTransferArguments(
        declarations: DotNetGenericOwnerPhysicalDeclarationIndex,
        ownerArgument: DotNetGenericOwnerSymbolicCarrierReference =
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
    ): List<DotNetGenericOwnerProducedValueFact> = listOf(
        directValue(
            declarations,
            DotNetGenericOwnerSymbolicCarrierReference.int32Carrier(),
        ),
        directValue(
            declarations,
            DotNetGenericOwnerSymbolicCarrierReference.SzArray(
                ownerArgument,
            ),
        ),
    )

    private fun selectRetainedRoute(
        fixture: Fixture,
        receiver: DotNetGenericOwnerProducedValueFact,
        arguments: List<DotNetGenericOwnerProducedValueFact>,
        inheritedReceiverSource: DotNetClrImportedTypeSource? = null,
        target: DotNetTarget? = null,
    ): DotNetGenericOwnerPhysicalBindingResult<DotNetGenericOwnerPhysicalOperationRoute> =
        selectDotNetRetainedForeignGenericOwnerPhysicalOperationRoute(
            source = fixture.source,
            method = fixture.method,
            receiver = receiver,
            arguments = arguments,
            methodArguments = listOf(DotNetGenericOwnerSymbolicCarrierReference.int32Carrier()),
            inheritedReceiverSource = inheritedReceiverSource,
            target = target,
        )

    private fun retainedForeignIrFixture(fixture: Fixture): RetainedForeignIrFixture {
        val moduleDescriptor = ModuleDescriptorImpl(
            Name.special("<retainedForeignAuthorityTestModule>"),
            LockBasedStorageManager("DotNetRetainedForeignAuthorityTest"),
            DefaultBuiltIns.Instance,
        )
        val module = IrModuleFragmentImpl(moduleDescriptor)
        val externalPackage = createEmptyExternalPackageFragment(module, FqName("collision"))
        val owner = IrFactoryImpl.buildClass {
            name = Name.identifier("Source")
            kind = ClassKind.INTERFACE
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            parent = externalPackage
            metadata = object : MetadataSource.Class {
                override val name: Name = this@apply.name
                override val platformDeclarationSource: Any = fixture.source

                override fun recordLocalClassType(type: FqName) = Unit

                override fun asFirSymbol(): Any? = null
            }
        }
        val ownerParameter = owner.addTypeParameter {
            name = Name.identifier("T")
            variance = Variance.INVARIANT
        }
        val function = IrFactoryImpl.buildFun {
            name = Name.identifier("transfer")
            returnType = ownerParameter.typeParameterDefaultType
            modality = Modality.ABSTRACT
            visibility = DescriptorVisibilities.PUBLIC
            containerSource = fixture.source
        }.apply {
            parent = owner
            addTypeParameter {
                name = Name.identifier("M")
                variance = Variance.INVARIANT
            }
        }
        owner.declarations += function
        externalPackage.declarations += owner
        return RetainedForeignIrFixture(owner, function)
    }

    private fun retainedForeignCollisionDeclarations(
        ownerKey: String,
        functionKey: String,
    ): DotNetExternalDeclarations = DotNetExternalDeclarations(listOf(
        DotNetExternalLibrary(
            artifact = DotNetLibraryArtifact("collision.Kotlin", "netstandard2.0"),
            assemblyFile = File("collision.Kotlin.dll"),
            declarations = mapOf(
                ownerKey to DotNetPhysicalDeclaration.Class(
                    ownerPath = listOf("collision.KotlinSource`1"),
                    physicalTypeParameterCount = 1,
                ),
                functionKey to DotNetPhysicalDeclaration.Function(
                    ownerPath = listOf("collision.KotlinSource`1"),
                    methodName = "KotlinTransfer",
                    isInstance = true,
                    methodGenericParameterCount = 1,
                ),
            ),
            friendAssemblies = emptySet(),
        ),
    ))

    private fun fixture(
        ownerParameterAttributes: Int = 0,
        methodParameterAttributes: Int = 0,
        rawReturnType: DotNetClrTypeSignature = DotNetClrTypeSignature.GenericParameter(
            DotNetClrGenericParameterKind.TYPE,
            0,
        ),
        retainedReturnType: DotNetClrResolvedTypeSignature =
            DotNetClrResolvedTypeSignature.GenericParameter(
                DotNetClrGenericParameterKind.TYPE,
                0,
            ),
        includeRelatedType: Boolean = false,
        rawInterfaceEdge: Boolean = false,
        retainedInterfaceEdge: Boolean = false,
        closedOwnerView: Boolean = false,
        recursiveResult: Boolean = false,
        ownerDependentInput: Boolean = true,
        includeInheritedReceiver: Boolean = false,
        openInheritedReceiver: Boolean = false,
        closedInheritedReceiverView: Boolean = false,
        inheritedReceiverParameterAttributes: Int = 0,
        rawInheritedArgument: DotNetClrTypeSignature? = null,
        retainedInheritedArgument: DotNetClrResolvedTypeSignature? = null,
        inheritedInterfaceCount: Int = 1,
        secondRawInheritedArgument: DotNetClrTypeSignature? = null,
        secondRetainedInheritedArgument: DotNetClrResolvedTypeSignature? = null,
        secondInheritedEdgeFirst: Boolean = false,
        includeInheritedRelatedEdge: Boolean = false,
        inheritedRelatedEdgeFirst: Boolean = true,
    ): Fixture {
        require(!retainedInterfaceEdge || rawInterfaceEdge)
        require(!closedInheritedReceiverView || openInheritedReceiver)
        require(inheritedInterfaceCount >= 0)
        require(!includeInheritedRelatedEdge || includeInheritedReceiver)
        require((secondRawInheritedArgument == null) == (secondRetainedInheritedArgument == null))
        require(secondRawInheritedArgument == null || includeInheritedReceiver)
        val ownerHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 1)
        val relatedHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 2)
        val inheritedReceiverHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 3)
        val methodHandle = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 1)
        val inheritedTypeSpecHandle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 1)
        val secondInheritedTypeSpecHandle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 2)
        val owner = DotNetClrTypeDefinition(
            handle = ownerHandle,
            namespaceName = "Foreign",
            metadataName = "Source`1",
            attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            baseType = null,
            declaringType = null,
        )
        val related = DotNetClrTypeDefinition(
            handle = relatedHandle,
            namespaceName = "Foreign",
            metadataName = "Related",
            attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            baseType = null,
            declaringType = null,
        )
        val inheritedReceiver = DotNetClrTypeDefinition(
            handle = inheritedReceiverHandle,
            namespaceName = "Foreign",
            metadataName = if (openInheritedReceiver) "ForwardingSource`1" else "IntSource",
            attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            baseType = null,
            declaringType = null,
        )
        val effectiveRawReturnType = if (recursiveResult) {
            DotNetClrTypeSignature.GenericInstance(
                DotNetClrTypeSignature.Named(ownerHandle, isValueType = false),
                listOf(
                    DotNetClrTypeSignature.GenericParameter(
                        DotNetClrGenericParameterKind.TYPE,
                        0,
                    )
                ),
            )
        } else {
            rawReturnType
        }
        val rawMethodSignature = DotNetClrMethodSignature(
            callingConvention = DotNetClrSignatureCallingConvention.DEFAULT,
            hasThis = true,
            hasExplicitThis = false,
            genericParameterCount = 1,
            returnType = effectiveRawReturnType,
            parameterTypes = listOf(
                DotNetClrTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.METHOD,
                    0,
                ),
                DotNetClrTypeSignature.SzArray(
                    if (ownerDependentInput) {
                        DotNetClrTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            0,
                        )
                    } else {
                        DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.STRING)
                    }
                ),
            ),
            varargParameterStart = null,
        )
        val method = DotNetClrMethodDefinition(
            handle = methodHandle,
            declaringType = ownerHandle,
            name = "Transfer",
            relativeVirtualAddress = 0L,
            implementationAttributes = 0,
            attributes = PUBLIC_ABSTRACT_VIRTUAL_METHOD_ATTRIBUTES,
            signature = rawMethodSignature,
            rawSignature = emptyList(),
        )
        val ownerParameter = DotNetClrGenericParameterDefinition(
            handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, 1),
            number = 0,
            attributes = ownerParameterAttributes,
            owner = ownerHandle,
            name = "T",
        )
        val methodParameter = DotNetClrGenericParameterDefinition(
            handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, 2),
            number = 0,
            attributes = methodParameterAttributes,
            owner = methodHandle,
            name = "R",
        )
        val inheritedReceiverParameter = DotNetClrGenericParameterDefinition(
            handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, 3),
            number = 0,
            attributes = inheritedReceiverParameterAttributes,
            owner = inheritedReceiverHandle,
            name = "T",
        )
        val effectiveRawInheritedArgument = rawInheritedArgument ?: if (openInheritedReceiver) {
            DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, 0)
        } else {
            DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32)
        }
        val effectiveRetainedInheritedArgument = retainedInheritedArgument ?: if (
            openInheritedReceiver && !closedInheritedReceiverView
        ) {
            DotNetClrResolvedTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, 0)
        } else {
            DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.INT32)
        }
        val hasRelatedType = includeRelatedType || rawInterfaceEdge || includeInheritedRelatedEdge
        val interfaceImplementation = DotNetClrInterfaceImplementation(
            handle = DotNetClrMetadataHandle(INTERFACE_IMPLEMENTATION_TABLE, 1),
            implementingType = ownerHandle,
            interfaceType = relatedHandle,
        )
        val inheritedTypeSpecification = DotNetClrTypeSpecification(
            handle = inheritedTypeSpecHandle,
            signature = DotNetClrTypeSignature.GenericInstance(
                DotNetClrTypeSignature.Named(ownerHandle, isValueType = false),
                listOf(effectiveRawInheritedArgument),
            ),
            rawSignature = emptyList(),
        )
        val secondInheritedTypeSpecification = secondRawInheritedArgument?.let { argument ->
            DotNetClrTypeSpecification(
                handle = secondInheritedTypeSpecHandle,
                signature = DotNetClrTypeSignature.GenericInstance(
                    DotNetClrTypeSignature.Named(ownerHandle, isValueType = false),
                    listOf(argument),
                ),
                rawSignature = emptyList(),
            )
        }
        val inheritedInterfaceTargets = buildList {
            if (includeInheritedRelatedEdge && inheritedRelatedEdgeFirst) add(relatedHandle)
            if (secondInheritedTypeSpecification != null && secondInheritedEdgeFirst) {
                add(secondInheritedTypeSpecHandle)
            }
            repeat(inheritedInterfaceCount) { add(inheritedTypeSpecHandle) }
            if (secondInheritedTypeSpecification != null && !secondInheritedEdgeFirst) {
                add(secondInheritedTypeSpecHandle)
            }
            if (includeInheritedRelatedEdge && !inheritedRelatedEdgeFirst) add(relatedHandle)
        }
        val inheritedInterfaceImplementations = inheritedInterfaceTargets.mapIndexed { index, target ->
            DotNetClrInterfaceImplementation(
                handle = DotNetClrMetadataHandle(INTERFACE_IMPLEMENTATION_TABLE, index + 2),
                implementingType = inheritedReceiverHandle,
                interfaceType = target,
            )
        }
        val metadata = DotNetClrAssemblyMetadata(
            identity = DotNetManagedAssemblyIdentity(
                name = "Foreign.Authority",
                version = "1.0.0.0",
                culture = "neutral",
                publicKey = emptyList(),
                publicKeyToken = emptyList(),
            ),
            assemblyReferences = emptyList(),
            typeReferences = emptyList(),
            typeDefinitions = buildList {
                add(owner)
                if (hasRelatedType) add(related)
                if (includeInheritedReceiver) add(inheritedReceiver)
            },
            interfaceImplementations = buildList {
                if (rawInterfaceEdge) add(interfaceImplementation)
                if (includeInheritedReceiver) addAll(inheritedInterfaceImplementations)
            },
            exportedTypes = emptyList(),
            typeSpecifications = if (includeInheritedReceiver) {
                buildList {
                    add(inheritedTypeSpecification)
                    secondInheritedTypeSpecification?.let(::add)
                }
            } else {
                emptyList()
            },
            fieldDefinitions = emptyList(),
            methodDefinitions = listOf(method),
            parameterDefinitions = emptyList(),
            constantDefinitions = emptyList(),
            fieldMarshalDefinitions = emptyList(),
            memberReferences = emptyList(),
            customAttributes = emptyList(),
            propertyDefinitions = emptyList(),
            methodSemantics = emptyList(),
            genericParameterDefinitions = buildList {
                add(ownerParameter)
                add(methodParameter)
                if (openInheritedReceiver) add(inheritedReceiverParameter)
            },
            genericParameterConstraints = emptyList(),
        )
        val assembly = DotNetClrClasspathAssembly.WithoutCarrier(
            File("Foreign.Authority.dll"),
            metadata,
        )
        val resolvedOwner = DotNetClrResolvedTypeDefinition(metadata, owner)
        val ownerView = DotNetClrResolvedTypeView(
            resolvedOwner,
            if (closedOwnerView) {
                listOf(DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.INT32))
            } else listOf(
                DotNetClrResolvedTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    0,
                )
            ),
        )
        val hierarchy = DotNetClrResolvedTypeHierarchy(
            type = ownerView,
            baseType = null,
            interfaces = if (retainedInterfaceEdge) {
                listOf(
                    DotNetClrResolvedInterfaceImplementation(
                        interfaceImplementation,
                        DotNetClrResolvedTypeView(
                            DotNetClrResolvedTypeDefinition(metadata, related),
                            emptyList(),
                        ),
                    )
                )
            } else {
                emptyList()
            },
        )
        val inheritedReceiverHierarchy = DotNetClrResolvedTypeHierarchy(
            type = DotNetClrResolvedTypeView(
                DotNetClrResolvedTypeDefinition(metadata, inheritedReceiver),
                if (openInheritedReceiver && !closedInheritedReceiverView) {
                    listOf(
                        DotNetClrResolvedTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            0,
                        )
                    )
                } else if (openInheritedReceiver) {
                    listOf(
                        DotNetClrResolvedTypeSignature.Primitive(
                            DotNetClrPrimitiveType.INT32,
                        )
                    )
                } else {
                    emptyList()
                },
            ),
            baseType = null,
            interfaces = inheritedInterfaceImplementations.mapIndexed { index, implementation ->
                val target = inheritedInterfaceTargets[index]
                DotNetClrResolvedInterfaceImplementation(
                    implementation,
                    if (target == relatedHandle) {
                        DotNetClrResolvedTypeView(
                            DotNetClrResolvedTypeDefinition(metadata, related),
                            emptyList(),
                        )
                    } else {
                        DotNetClrResolvedTypeView(
                            resolvedOwner,
                            listOf(
                                if (target == secondInheritedTypeSpecHandle) {
                                    checkNotNull(secondRetainedInheritedArgument)
                                } else {
                                    effectiveRetainedInheritedArgument
                                }
                            ),
                        )
                    },
                )
            },
        )
        val relatedHierarchy = DotNetClrResolvedTypeHierarchy(
            type = DotNetClrResolvedTypeView(
                DotNetClrResolvedTypeDefinition(metadata, related),
                emptyList(),
            ),
            baseType = null,
            interfaces = emptyList(),
        )
        val graph = DotNetClrImportedDeclarationGraph(
            assemblies = listOf(assembly),
            hierarchies = buildList {
                add(hierarchy)
                if (includeInheritedReceiver) add(inheritedReceiverHierarchy)
                if (includeInheritedRelatedEdge) add(relatedHierarchy)
            },
        )
        val effectiveRetainedReturnType = if (recursiveResult) {
            DotNetClrResolvedTypeSignature.GenericInstance(
                DotNetClrResolvedTypeSignature.Named(resolvedOwner, isValueType = false),
                listOf(
                    DotNetClrResolvedTypeSignature.GenericParameter(
                        DotNetClrGenericParameterKind.TYPE,
                        0,
                    )
                ),
            )
        } else {
            retainedReturnType
        }
        val resolvedMethodSignature = DotNetClrResolvedMethodSignature(
            callingConvention = DotNetClrSignatureCallingConvention.DEFAULT,
            hasThis = true,
            hasExplicitThis = false,
            genericParameterCount = 1,
            returnType = effectiveRetainedReturnType,
            parameterTypes = listOf(
                DotNetClrResolvedTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.METHOD,
                    0,
                ),
                DotNetClrResolvedTypeSignature.SzArray(
                    if (ownerDependentInput) {
                        DotNetClrResolvedTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            0,
                        )
                    } else {
                        DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.STRING)
                    }
                ),
            ),
            varargParameterStart = null,
        )
        val source = DotNetClrImportedMethodSource(
            assembly,
            owner,
            hierarchy,
            graph,
            method,
            resolvedMethodSignature,
        )
        val inheritedReceiverSource = if (includeInheritedReceiver) {
            DotNetClrImportedTypeSource(
                assembly,
                inheritedReceiver,
                inheritedReceiverHierarchy,
                graph,
            )
        } else {
            null
        }
        return Fixture(
            source,
            method,
            related.takeIf { includeRelatedType || includeInheritedRelatedEdge }
                ?.let { DotNetClrResolvedTypeDefinition(metadata, it) },
            inheritedReceiverSource,
        )
    }

    private fun crossAssemblyFixture(
        parentReferenceVersion: String? = null,
    ): Fixture {
        val parentFixture = fixture()
        val parentSource = parentFixture.source
        val parentAssembly = parentSource.assembly
        val parentMetadata = parentAssembly.metadata
        val assemblyReferenceHandle = DotNetClrMetadataHandle(ASSEMBLY_REFERENCE_TABLE, 1)
        val parentTypeReferenceHandle = DotNetClrMetadataHandle(TYPE_REFERENCE_TABLE, 1)
        val childTypeHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 1)
        val childTypeSpecHandle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 1)
        val childType = DotNetClrTypeDefinition(
            handle = childTypeHandle,
            namespaceName = "Foreign",
            metadataName = "IntSource",
            attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            baseType = null,
            declaringType = null,
        )
        val assemblyReference = DotNetClrAssemblyReference(
            handle = assemblyReferenceHandle,
            name = parentMetadata.identity.name,
            version = parentReferenceVersion ?: parentMetadata.identity.version,
            culture = parentMetadata.identity.culture,
            flags = 0,
            publicKeyOrToken = emptyList(),
            hashValue = emptyList(),
        )
        val parentTypeReference = DotNetClrTypeReference(
            handle = parentTypeReferenceHandle,
            namespaceName = parentSource.declaringType.namespaceName,
            metadataName = parentSource.declaringType.metadataName,
            resolutionScope = assemblyReferenceHandle,
        )
        val childTypeSpecification = DotNetClrTypeSpecification(
            handle = childTypeSpecHandle,
            signature = DotNetClrTypeSignature.GenericInstance(
                DotNetClrTypeSignature.Named(
                    parentTypeReferenceHandle,
                    isValueType = false,
                ),
                listOf(
                    DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32),
                ),
            ),
            rawSignature = emptyList(),
        )
        val childInterfaceImplementation = DotNetClrInterfaceImplementation(
            handle = DotNetClrMetadataHandle(INTERFACE_IMPLEMENTATION_TABLE, 1),
            implementingType = childTypeHandle,
            interfaceType = childTypeSpecHandle,
        )
        val childMetadata = DotNetClrAssemblyMetadata(
            identity = DotNetManagedAssemblyIdentity(
                name = "Foreign.Child",
                version = "1.0.0.0",
                culture = "neutral",
                publicKey = emptyList(),
                publicKeyToken = emptyList(),
            ),
            assemblyReferences = listOf(assemblyReference),
            typeReferences = listOf(parentTypeReference),
            typeDefinitions = listOf(childType),
            interfaceImplementations = listOf(childInterfaceImplementation),
            exportedTypes = emptyList(),
            typeSpecifications = listOf(childTypeSpecification),
            fieldDefinitions = emptyList(),
            methodDefinitions = emptyList(),
            parameterDefinitions = emptyList(),
            constantDefinitions = emptyList(),
            fieldMarshalDefinitions = emptyList(),
            memberReferences = emptyList(),
            customAttributes = emptyList(),
            propertyDefinitions = emptyList(),
            methodSemantics = emptyList(),
            genericParameterDefinitions = emptyList(),
            genericParameterConstraints = emptyList(),
        )
        val childAssembly = DotNetClrClasspathAssembly.WithoutCarrier(
            File("Foreign.Child.dll"),
            childMetadata,
        )
        val childHierarchy = DotNetClrResolvedTypeHierarchy(
            type = DotNetClrResolvedTypeView(
                DotNetClrResolvedTypeDefinition(childMetadata, childType),
                emptyList(),
            ),
            baseType = null,
            interfaces = listOf(
                DotNetClrResolvedInterfaceImplementation(
                    childInterfaceImplementation,
                    DotNetClrResolvedTypeView(
                        parentSource.declaringHierarchy.type.type,
                        listOf(
                            DotNetClrResolvedTypeSignature.Primitive(
                                DotNetClrPrimitiveType.INT32,
                            )
                        ),
                    ),
                )
            ),
        )
        val graph = DotNetClrImportedDeclarationGraph(
            assemblies = listOf(parentAssembly, childAssembly),
            hierarchies = listOf(parentSource.declaringHierarchy, childHierarchy),
        )
        val source = DotNetClrImportedMethodSource(
            parentAssembly,
            parentSource.declaringType,
            parentSource.declaringHierarchy,
            graph,
            parentFixture.method,
            parentSource.resolvedSignature,
        )
        val receiverSource = DotNetClrImportedTypeSource(
            childAssembly,
            childType,
            childHierarchy,
            graph,
        )
        return Fixture(
            source,
            parentFixture.method,
            relatedType = null,
            inheritedReceiverSource = receiverSource,
        )
    }

    private fun deepCrossAssemblyFixture(
        includeRetainedIntermediateHierarchy: Boolean = true,
        intermediateDepth: Int = 1,
        includeIntermediateAuxiliaryBranch: Boolean = false,
        includeIntermediateSelfCycle: Boolean = false,
        intermediateGenericArity: Int = 1,
        intermediateParameterAttributes: Int = 0,
        closedLeafPrimitive: DotNetClrPrimitiveType = DotNetClrPrimitiveType.INT32,
        reverseIntermediateArguments: Boolean = false,
        dependentParameterConstraint: Boolean = false,
        omitLastDependentConstraint: Boolean = false,
        dependentConstraintCopies: Int = 1,
        nominalConstraintConstructionDepth: Int? = null,
        nominalCarrierKind: NominalCarrierKind = NominalCarrierKind.INTERFACE,
        retainOuterNominalConstraint: Boolean = true,
        nominalCarrierEncodedAsValueType: Boolean? = null,
        genericNominalClassCovariant: Boolean = false,
        genericNominalValueCovariant: Boolean = false,
        genericNominalDelegateContravariant: Boolean = false,
        genericNominalDelegateSealed: Boolean = true,
        omitLastNominalConstraint: Boolean = false,
        includeNominalConstraintHierarchy: Boolean = true,
        includePhysicalCoreTypes: Boolean = true,
        nestedNominalCarrierParameterAttributes: Int = 0,
        nestedNominalCarrierLeafPrimitive: DotNetClrPrimitiveType = DotNetClrPrimitiveType.INT32,
        nestedNominalCarrierNominalConstraint: Boolean = false,
        nestedNominalCarrierObjectConstraint: Boolean = false,
        violateNestedNominalCarrierConstraint: Boolean = false,
        nestedNominalCarrierConstraintCopies: Int = 1,
        rootOwnerDependentInput: Boolean = true,
        rootOwnerParameterAttributes: Int = 0,
        rootMethodParameterAttributes: Int = 0,
        duplicateFirstBinderRows: Int = 1,
    ): Fixture {
        require(intermediateDepth >= 1)
        require(intermediateGenericArity >= 1)
        require(!reverseIntermediateArguments || intermediateGenericArity > 1)
        require(!dependentParameterConstraint || intermediateDepth >= 2)
        require(!dependentParameterConstraint || intermediateGenericArity == 2)
        require(!dependentParameterConstraint || !reverseIntermediateArguments)
        require(!omitLastDependentConstraint || dependentParameterConstraint)
        require(dependentConstraintCopies >= 1)
        require(dependentConstraintCopies == 1 || dependentParameterConstraint)
        require(nominalConstraintConstructionDepth == null ||
                nominalConstraintConstructionDepth >= 0)
        require(nominalConstraintConstructionDepth == null || intermediateDepth >= 2)
        require(nominalConstraintConstructionDepth == null || intermediateGenericArity == 1)
        require(nominalConstraintConstructionDepth == null || !reverseIntermediateArguments)
        require(nominalConstraintConstructionDepth == null || !dependentParameterConstraint)
        require(nominalCarrierKind == NominalCarrierKind.INTERFACE ||
                nominalConstraintConstructionDepth != null)
        require(!genericNominalClassCovariant ||
                nominalCarrierKind == NominalCarrierKind.REFERENCE_CLASS)
        require(!genericNominalClassCovariant || nominalConstraintConstructionDepth != 0)
        require(!genericNominalValueCovariant ||
                nominalCarrierKind == NominalCarrierKind.VALUE_TYPE)
        require(!genericNominalValueCovariant || nominalConstraintConstructionDepth != 0)
        require(!genericNominalDelegateContravariant ||
                nominalCarrierKind == NominalCarrierKind.DELEGATE)
        require(genericNominalDelegateSealed ||
                nominalCarrierKind == NominalCarrierKind.DELEGATE)
        require(nominalCarrierKind != NominalCarrierKind.DELEGATE ||
                checkNotNull(nominalConstraintConstructionDepth) > 0)
        require(retainOuterNominalConstraint || nominalConstraintConstructionDepth != null)
        require(nominalCarrierKind != NominalCarrierKind.NULLABLE_VALUE_TYPE ||
                checkNotNull(nominalConstraintConstructionDepth) > 0)
        require(!omitLastNominalConstraint || nominalConstraintConstructionDepth != null)
        require(nestedNominalCarrierParameterAttributes == 0 ||
                checkNotNull(nominalConstraintConstructionDepth) > 0)
        require(!nestedNominalCarrierNominalConstraint ||
                checkNotNull(nominalConstraintConstructionDepth) > 0)
        require(!nestedNominalCarrierObjectConstraint ||
                nestedNominalCarrierNominalConstraint)
        require(!violateNestedNominalCarrierConstraint ||
                nestedNominalCarrierNominalConstraint)
        require(nestedNominalCarrierConstraintCopies >= 1)
        require(nestedNominalCarrierConstraintCopies == 1 ||
                nestedNominalCarrierNominalConstraint)
        require(duplicateFirstBinderRows >= 1)
        require(duplicateFirstBinderRows == 1 || intermediateGenericArity == 1)
        require(duplicateFirstBinderRows == 1 || intermediateDepth == 1)
        require(duplicateFirstBinderRows == 1 || !dependentParameterConstraint)
        require(duplicateFirstBinderRows == 1 || nominalConstraintConstructionDepth == null)
        val hasNominalConstraint = nominalConstraintConstructionDepth != null
        val constraintCore = if (
            dependentParameterConstraint ||
            hasNominalConstraint ||
            intermediateParameterAttributes != 0 ||
            rootOwnerParameterAttributes != 0 ||
            rootMethodParameterAttributes != 0
        ) {
            minimalConstraintCoreFixture(
                genericMarkerParameterAttributes = nestedNominalCarrierParameterAttributes,
                genericMarkerClassCovariant = genericNominalClassCovariant,
                genericMarkerValueCovariant = genericNominalValueCovariant,
                genericMarkerDelegateContravariant = genericNominalDelegateContravariant,
                genericMarkerDelegateSealed = genericNominalDelegateSealed,
                genericMarkerHasNominalConstraint = nestedNominalCarrierNominalConstraint,
                genericMarkerNominalConstraintUsesObject =
                    nestedNominalCarrierObjectConstraint,
                genericMarkerNominalConstraintCopies = nestedNominalCarrierConstraintCopies,
            )
        } else {
            null
        }
        val parentFixture = fixture(
            ownerParameterAttributes = rootOwnerParameterAttributes,
            methodParameterAttributes = rootMethodParameterAttributes,
            ownerDependentInput = rootOwnerDependentInput,
        )
        val parentSource = parentFixture.source
        val parentAssembly = parentSource.assembly
        val intermediateAssemblies = mutableListOf<DotNetClrClasspathAssembly.WithoutCarrier>()
        val intermediateHierarchies = mutableListOf<DotNetClrResolvedTypeHierarchy>()
        val intermediateTypes = mutableListOf<DotNetClrResolvedTypeDefinition>()
        var previousAssembly = parentAssembly
        var previousType = parentSource.declaringHierarchy.type.type
        var previousGenericArity = 1
        repeat(intermediateDepth) { index ->
            val assemblyReferenceHandle = DotNetClrMetadataHandle(ASSEMBLY_REFERENCE_TABLE, 1)
            val constraintAssemblyReferenceHandle =
                DotNetClrMetadataHandle(ASSEMBLY_REFERENCE_TABLE, 2)
            val parentTypeReferenceHandle = DotNetClrMetadataHandle(TYPE_REFERENCE_TABLE, 1)
            val nominalConstraintTypeReferenceHandle =
                DotNetClrMetadataHandle(TYPE_REFERENCE_TABLE, 2)
            val typeHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 1)
            val auxiliaryTypeHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 2)
            val parentTypeSpecHandle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 1)
            val selfTypeSpecHandle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 2)
            val auxiliaryParentTypeSpecHandle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 3)
            val constraintTypeSpecHandle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 4)
            val nominalConstraintTypeSpecHandle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 5)
            val addsHostileEdges = index == intermediateDepth - 1
            val type = DotNetClrTypeDefinition(
                handle = typeHandle,
                namespaceName = "Foreign.Deep",
                metadataName = if (intermediateDepth == 1) {
                    "ForwardingSource`$intermediateGenericArity"
                } else {
                    "ForwardingSource${index + 1}`$intermediateGenericArity"
                },
                attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
                baseType = null,
                declaringType = null,
            )
            val auxiliaryType = DotNetClrTypeDefinition(
                handle = auxiliaryTypeHandle,
                namespaceName = "Foreign.Deep",
                metadataName = "Auxiliary${index + 1}",
                attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
                baseType = null,
                declaringType = null,
            )
            val assemblyReference = DotNetClrAssemblyReference(
                handle = assemblyReferenceHandle,
                name = previousAssembly.metadata.identity.name,
                version = previousAssembly.metadata.identity.version,
                culture = previousAssembly.metadata.identity.culture,
                flags = 0,
                publicKeyOrToken = emptyList(),
                hashValue = emptyList(),
            )
            val constraintAssemblyReference = if (hasNominalConstraint) {
                val core = checkNotNull(constraintCore)
                DotNetClrAssemblyReference(
                    handle = constraintAssemblyReferenceHandle,
                    name = core.assembly.metadata.identity.name,
                    version = core.assembly.metadata.identity.version,
                    culture = core.assembly.metadata.identity.culture,
                    flags = 0,
                    publicKeyOrToken = emptyList(),
                    hashValue = emptyList(),
                )
            } else {
                null
            }
            val parentTypeReference = DotNetClrTypeReference(
                handle = parentTypeReferenceHandle,
                namespaceName = previousType.definition.namespaceName,
                metadataName = previousType.definition.metadataName,
                resolutionScope = assemblyReferenceHandle,
            )
            val nominalConstraintTypeReference = if (hasNominalConstraint) {
                val core = checkNotNull(constraintCore)
                val marker = core.nominalCarrierType(
                    generic = nominalConstraintConstructionDepth != 0,
                    kind = nominalCarrierKind,
                ).definition
                DotNetClrTypeReference(
                    handle = nominalConstraintTypeReferenceHandle,
                    namespaceName = marker.namespaceName,
                    metadataName = marker.metadataName,
                    resolutionScope = constraintAssemblyReferenceHandle,
                )
            } else {
                null
            }
            val parentArgumentIndices = when {
                dependentParameterConstraint && index == 0 -> listOf(1)
                dependentParameterConstraint -> (0 until previousGenericArity).toList()
                reverseIntermediateArguments && previousGenericArity == 1 ->
                    listOf(intermediateGenericArity - 1)
                reverseIntermediateArguments ->
                    (0 until previousGenericArity).reversed()
                else -> (0 until previousGenericArity).toList()
            }
            val parentTypeSpecification = DotNetClrTypeSpecification(
                handle = parentTypeSpecHandle,
                signature = DotNetClrTypeSignature.GenericInstance(
                    DotNetClrTypeSignature.Named(parentTypeReferenceHandle, isValueType = false),
                    parentArgumentIndices.map { parameterIndex ->
                        DotNetClrTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            parameterIndex,
                        )
                    },
                ),
                rawSignature = emptyList(),
            )
            val selfTypeSpecification = DotNetClrTypeSpecification(
                handle = selfTypeSpecHandle,
                signature = DotNetClrTypeSignature.GenericInstance(
                    DotNetClrTypeSignature.Named(typeHandle, isValueType = false),
                    (0 until intermediateGenericArity).map { parameterIndex ->
                        DotNetClrTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            parameterIndex,
                        )
                    },
                ),
                rawSignature = emptyList(),
            )
            val auxiliaryParentTypeSpecification = DotNetClrTypeSpecification(
                handle = auxiliaryParentTypeSpecHandle,
                signature = DotNetClrTypeSignature.GenericInstance(
                    DotNetClrTypeSignature.Named(parentTypeReferenceHandle, isValueType = false),
                    List(previousGenericArity) {
                        DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32)
                    },
                ),
                rawSignature = emptyList(),
            )
            val constraintTypeSpecification = DotNetClrTypeSpecification(
                handle = constraintTypeSpecHandle,
                signature = DotNetClrTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    0,
                ),
                rawSignature = emptyList(),
            )
            val parentInterfaceImplementation = DotNetClrInterfaceImplementation(
                handle = DotNetClrMetadataHandle(INTERFACE_IMPLEMENTATION_TABLE, 1),
                implementingType = typeHandle,
                interfaceType = parentTypeSpecHandle,
            )
            val auxiliaryInterfaceImplementation = DotNetClrInterfaceImplementation(
                handle = DotNetClrMetadataHandle(INTERFACE_IMPLEMENTATION_TABLE, 2),
                implementingType = typeHandle,
                interfaceType = auxiliaryTypeHandle,
            )
            val selfInterfaceImplementation = DotNetClrInterfaceImplementation(
                handle = DotNetClrMetadataHandle(INTERFACE_IMPLEMENTATION_TABLE, 3),
                implementingType = typeHandle,
                interfaceType = selfTypeSpecHandle,
            )
            val auxiliaryParentInterfaceImplementation = DotNetClrInterfaceImplementation(
                handle = DotNetClrMetadataHandle(INTERFACE_IMPLEMENTATION_TABLE, 4),
                implementingType = auxiliaryTypeHandle,
                interfaceType = auxiliaryParentTypeSpecHandle,
            )
            val parameters = if (duplicateFirstBinderRows == 1) {
                List(intermediateGenericArity) { parameterIndex ->
                    DotNetClrGenericParameterDefinition(
                        handle = DotNetClrMetadataHandle(
                            GENERIC_PARAMETER_TABLE,
                            parameterIndex + 1,
                        ),
                        number = parameterIndex,
                        attributes = when {
                            dependentParameterConstraint || hasNominalConstraint -> 0
                            parameterIndex == 0 && intermediateParameterAttributes != 0 ->
                                intermediateParameterAttributes
                            else -> COVARIANT_ATTRIBUTE
                        },
                        owner = typeHandle,
                        name = "T$parameterIndex",
                    )
                }
            } else {
                List(duplicateFirstBinderRows) {
                    DotNetClrGenericParameterDefinition(
                        handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, 1),
                        number = 0,
                        attributes = COVARIANT_ATTRIBUTE,
                        owner = typeHandle,
                        name = "T0",
                    )
                }
            }
            val retainsDependentConstraint = dependentParameterConstraint &&
                    !(omitLastDependentConstraint && index == intermediateDepth - 1)
            val dependentConstraints = if (retainsDependentConstraint) {
                List(dependentConstraintCopies) { constraintIndex ->
                    DotNetClrGenericParameterConstraint(
                        handle = DotNetClrMetadataHandle(
                            GENERIC_PARAMETER_CONSTRAINT_TABLE,
                            constraintIndex + 1,
                        ),
                        owner = parameters[1].handle,
                        constraint = constraintTypeSpecHandle,
                    )
                }
            } else {
                emptyList()
            }
            val retainsNominalConstraint = hasNominalConstraint &&
                    retainOuterNominalConstraint &&
                    !nestedNominalCarrierNominalConstraint &&
                    nestedNominalCarrierParameterAttributes == 0 &&
                    !(omitLastNominalConstraint && index == intermediateDepth - 1)
            val nominalConstraintTypeSpecification = if (retainsNominalConstraint) {
                val constructionDepth = checkNotNull(nominalConstraintConstructionDepth)
                if (constructionDepth > 0) {
                    DotNetClrTypeSpecification(
                        handle = nominalConstraintTypeSpecHandle,
                        signature = nestedGenericTypeSignature(
                            nominalConstraintTypeReferenceHandle,
                            constructionDepth,
                            DotNetClrTypeSignature.Primitive(
                                nestedNominalCarrierLeafPrimitive,
                            ),
                            nominalCarrierEncodedAsValueType ?: nominalCarrierKind.isValueType,
                        ),
                        rawSignature = emptyList(),
                    )
                } else {
                    null
                }
            } else {
                null
            }
            val nominalConstraint = if (retainsNominalConstraint) {
                DotNetClrGenericParameterConstraint(
                    handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_CONSTRAINT_TABLE, 1),
                    owner = parameters.single().handle,
                    constraint = if (nominalConstraintConstructionDepth == 0) {
                        nominalConstraintTypeReferenceHandle
                    } else {
                        nominalConstraintTypeSpecHandle
                    },
                )
            } else {
                null
            }
            val metadata = DotNetClrAssemblyMetadata(
                identity = DotNetManagedAssemblyIdentity(
                    name = if (intermediateDepth == 1) "Foreign.Middle" else "Foreign.Middle${index + 1}",
                    version = "1.0.0.0",
                    culture = "neutral",
                    publicKey = emptyList(),
                    publicKeyToken = emptyList(),
                ),
                assemblyReferences = listOfNotNull(
                    assemblyReference,
                    constraintAssemblyReference,
                ),
                typeReferences = listOfNotNull(
                    parentTypeReference,
                    nominalConstraintTypeReference,
                ),
                typeDefinitions = buildList {
                    add(type)
                    if (addsHostileEdges && includeIntermediateAuxiliaryBranch) add(auxiliaryType)
                },
                interfaceImplementations = buildList {
                    add(parentInterfaceImplementation)
                    if (addsHostileEdges && includeIntermediateAuxiliaryBranch) {
                        add(auxiliaryInterfaceImplementation)
                        add(auxiliaryParentInterfaceImplementation)
                    }
                    if (addsHostileEdges && includeIntermediateSelfCycle) {
                        add(selfInterfaceImplementation)
                    }
                },
                exportedTypes = emptyList(),
                typeSpecifications = buildList {
                    add(parentTypeSpecification)
                    if (addsHostileEdges && includeIntermediateSelfCycle) add(selfTypeSpecification)
                    if (addsHostileEdges && includeIntermediateAuxiliaryBranch) {
                        add(auxiliaryParentTypeSpecification)
                    }
                    if (retainsDependentConstraint) add(constraintTypeSpecification)
                    if (nominalConstraintTypeSpecification != null) {
                        add(nominalConstraintTypeSpecification)
                    }
                },
                fieldDefinitions = emptyList(),
                methodDefinitions = emptyList(),
                parameterDefinitions = emptyList(),
                constantDefinitions = emptyList(),
                fieldMarshalDefinitions = emptyList(),
                memberReferences = emptyList(),
                customAttributes = emptyList(),
                propertyDefinitions = emptyList(),
                methodSemantics = emptyList(),
                genericParameterDefinitions = parameters,
                genericParameterConstraints = dependentConstraints +
                        listOfNotNull(nominalConstraint),
            )
            val assembly = DotNetClrClasspathAssembly.WithoutCarrier(
                File("${metadata.identity.name}.dll"),
                metadata,
            )
            val resolvedType = DotNetClrResolvedTypeDefinition(metadata, type)
            val resolvedAuxiliaryType = DotNetClrResolvedTypeDefinition(metadata, auxiliaryType)
            val hierarchy = DotNetClrResolvedTypeHierarchy(
                type = DotNetClrResolvedTypeView(
                    resolvedType,
                    (0 until intermediateGenericArity).map { parameterIndex ->
                        DotNetClrResolvedTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            parameterIndex,
                        )
                    },
                ),
                baseType = null,
                interfaces = buildList {
                    add(
                        DotNetClrResolvedInterfaceImplementation(
                            parentInterfaceImplementation,
                            DotNetClrResolvedTypeView(
                                previousType,
                                parentArgumentIndices.map { parameterIndex ->
                                    DotNetClrResolvedTypeSignature.GenericParameter(
                                        DotNetClrGenericParameterKind.TYPE,
                                        parameterIndex,
                                    )
                                },
                            ),
                        )
                    )
                    if (addsHostileEdges && includeIntermediateAuxiliaryBranch) {
                        add(
                            DotNetClrResolvedInterfaceImplementation(
                                auxiliaryInterfaceImplementation,
                                DotNetClrResolvedTypeView(resolvedAuxiliaryType, emptyList()),
                            )
                        )
                    }
                    if (addsHostileEdges && includeIntermediateSelfCycle) {
                        add(
                            DotNetClrResolvedInterfaceImplementation(
                                selfInterfaceImplementation,
                                DotNetClrResolvedTypeView(
                                    resolvedType,
                                    (0 until intermediateGenericArity).map { parameterIndex ->
                                        DotNetClrResolvedTypeSignature.GenericParameter(
                                            DotNetClrGenericParameterKind.TYPE,
                                            parameterIndex,
                                        )
                                    },
                                ),
                            )
                        )
                    }
                },
            )
            val auxiliaryHierarchy = DotNetClrResolvedTypeHierarchy(
                type = DotNetClrResolvedTypeView(resolvedAuxiliaryType, emptyList()),
                baseType = null,
                interfaces = listOf(
                    DotNetClrResolvedInterfaceImplementation(
                        auxiliaryParentInterfaceImplementation,
                        DotNetClrResolvedTypeView(
                            previousType,
                            List(previousGenericArity) {
                                DotNetClrResolvedTypeSignature.Primitive(
                                    DotNetClrPrimitiveType.INT32,
                                )
                            },
                        ),
                    )
                ),
            )
            intermediateAssemblies += assembly
            intermediateHierarchies += hierarchy
            if (addsHostileEdges && includeIntermediateAuxiliaryBranch) {
                intermediateHierarchies += auxiliaryHierarchy
            }
            intermediateTypes += resolvedType
            previousAssembly = assembly
            previousType = resolvedType
            previousGenericArity = if (duplicateFirstBinderRows == 1) {
                intermediateGenericArity
            } else {
                duplicateFirstBinderRows
            }
        }

        val childAssemblyReferenceHandle = DotNetClrMetadataHandle(ASSEMBLY_REFERENCE_TABLE, 1)
        val childConstraintAssemblyReferenceHandle =
            DotNetClrMetadataHandle(ASSEMBLY_REFERENCE_TABLE, 2)
        val childMiddleTypeReferenceHandle = DotNetClrMetadataHandle(TYPE_REFERENCE_TABLE, 1)
        val childNominalConstraintTypeReferenceHandle =
            DotNetClrMetadataHandle(TYPE_REFERENCE_TABLE, 2)
        val childNominalConstraintLeafTypeReferenceHandle =
            DotNetClrMetadataHandle(TYPE_REFERENCE_TABLE, 3)
        val childTypeHandle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, 1)
        val childMiddleTypeSpecHandle = DotNetClrMetadataHandle(TYPE_SPEC_TABLE, 1)
        val childType = DotNetClrTypeDefinition(
            handle = childTypeHandle,
            namespaceName = "Foreign.Deep",
            metadataName = "IntSource",
            attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            baseType = null,
            declaringType = null,
        )
        val childAssemblyReference = DotNetClrAssemblyReference(
            handle = childAssemblyReferenceHandle,
            name = previousAssembly.metadata.identity.name,
            version = previousAssembly.metadata.identity.version,
            culture = previousAssembly.metadata.identity.culture,
            flags = 0,
            publicKeyOrToken = emptyList(),
            hashValue = emptyList(),
        )
        val childConstraintAssemblyReference = if (hasNominalConstraint) {
            val core = checkNotNull(constraintCore)
            DotNetClrAssemblyReference(
                handle = childConstraintAssemblyReferenceHandle,
                name = core.assembly.metadata.identity.name,
                version = core.assembly.metadata.identity.version,
                culture = core.assembly.metadata.identity.culture,
                flags = 0,
                publicKeyOrToken = emptyList(),
                hashValue = emptyList(),
            )
        } else {
            null
        }
        val childMiddleTypeReference = DotNetClrTypeReference(
            handle = childMiddleTypeReferenceHandle,
            namespaceName = previousType.definition.namespaceName,
            metadataName = previousType.definition.metadataName,
            resolutionScope = childAssemblyReferenceHandle,
        )
        val childNominalConstraintTypeReference = if (hasNominalConstraint) {
            val core = checkNotNull(constraintCore)
            val marker = core.nominalCarrierType(
                generic = nominalConstraintConstructionDepth != 0,
                kind = nominalCarrierKind,
            ).definition
            DotNetClrTypeReference(
                handle = childNominalConstraintTypeReferenceHandle,
                namespaceName = marker.namespaceName,
                metadataName = marker.metadataName,
                resolutionScope = childConstraintAssemblyReferenceHandle,
            )
        } else {
            null
        }
        val childNominalConstraintLeafTypeReference =
            if (nestedNominalCarrierNominalConstraint) {
                val core = checkNotNull(constraintCore)
                DotNetClrTypeReference(
                    handle = childNominalConstraintLeafTypeReferenceHandle,
                    namespaceName = core.markerType.definition.namespaceName,
                    metadataName = core.markerType.definition.metadataName,
                    resolutionScope = childConstraintAssemblyReferenceHandle,
                )
            } else {
                null
            }
        val childMiddleTypeSpecification = DotNetClrTypeSpecification(
            handle = childMiddleTypeSpecHandle,
            signature = DotNetClrTypeSignature.GenericInstance(
                DotNetClrTypeSignature.Named(
                    childMiddleTypeReferenceHandle,
                    isValueType = false,
                ),
                (0 until previousGenericArity).map { parameterIndex ->
                    if (hasNominalConstraint && parameterIndex == 0) {
                        if (nominalConstraintConstructionDepth == 0) {
                            DotNetClrTypeSignature.Named(
                                childNominalConstraintTypeReferenceHandle,
                                isValueType = nominalCarrierEncodedAsValueType
                                    ?: nominalCarrierKind.isValueType,
                            )
                        } else {
                            nestedGenericTypeSignature(
                                childNominalConstraintTypeReferenceHandle,
                                checkNotNull(nominalConstraintConstructionDepth),
                                if (nestedNominalCarrierNominalConstraint &&
                                    !nestedNominalCarrierObjectConstraint &&
                                    !violateNestedNominalCarrierConstraint
                                ) {
                                    DotNetClrTypeSignature.Named(
                                        childNominalConstraintLeafTypeReferenceHandle,
                                        isValueType = false,
                                    )
                                } else {
                                    DotNetClrTypeSignature.Primitive(
                                        nestedNominalCarrierLeafPrimitive,
                                    )
                                },
                                nominalCarrierEncodedAsValueType
                                    ?: nominalCarrierKind.isValueType,
                            )
                        }
                    } else {
                        DotNetClrTypeSignature.Primitive(
                            if (dependentParameterConstraint && parameterIndex == 0) {
                                DotNetClrPrimitiveType.OBJECT
                            } else if (parameterIndex == 0) {
                                closedLeafPrimitive
                            } else {
                                DotNetClrPrimitiveType.STRING
                            }
                        )
                    }
                },
            ),
            rawSignature = emptyList(),
        )
        val childInterfaceImplementation = DotNetClrInterfaceImplementation(
            handle = DotNetClrMetadataHandle(INTERFACE_IMPLEMENTATION_TABLE, 1),
            implementingType = childTypeHandle,
            interfaceType = childMiddleTypeSpecHandle,
        )
        val childMetadata = DotNetClrAssemblyMetadata(
            identity = DotNetManagedAssemblyIdentity(
                name = "Foreign.Child",
                version = "1.0.0.0",
                culture = "neutral",
                publicKey = emptyList(),
                publicKeyToken = emptyList(),
            ),
            assemblyReferences = listOfNotNull(
                childAssemblyReference,
                childConstraintAssemblyReference,
            ),
            typeReferences = listOfNotNull(
                childMiddleTypeReference,
                childNominalConstraintTypeReference,
                childNominalConstraintLeafTypeReference,
            ),
            typeDefinitions = listOf(childType),
            interfaceImplementations = listOf(childInterfaceImplementation),
            exportedTypes = emptyList(),
            typeSpecifications = listOf(childMiddleTypeSpecification),
            fieldDefinitions = emptyList(),
            methodDefinitions = emptyList(),
            parameterDefinitions = emptyList(),
            constantDefinitions = emptyList(),
            fieldMarshalDefinitions = emptyList(),
            memberReferences = emptyList(),
            customAttributes = emptyList(),
            propertyDefinitions = emptyList(),
            methodSemantics = emptyList(),
            genericParameterDefinitions = emptyList(),
            genericParameterConstraints = emptyList(),
        )
        val childAssembly = DotNetClrClasspathAssembly.WithoutCarrier(
            File("Foreign.Child.dll"),
            childMetadata,
        )
        val childHierarchy = DotNetClrResolvedTypeHierarchy(
            type = DotNetClrResolvedTypeView(
                DotNetClrResolvedTypeDefinition(childMetadata, childType),
                emptyList(),
            ),
            baseType = null,
            interfaces = listOf(
                DotNetClrResolvedInterfaceImplementation(
                    childInterfaceImplementation,
                    DotNetClrResolvedTypeView(
                        previousType,
                        (0 until previousGenericArity).map { parameterIndex ->
                            if (hasNominalConstraint && parameterIndex == 0) {
                                val core = checkNotNull(constraintCore)
                                if (nominalConstraintConstructionDepth == 0) {
                                    DotNetClrResolvedTypeSignature.Named(
                                        core.nominalCarrierType(
                                            generic = false,
                                            kind = nominalCarrierKind,
                                        ),
                                        isValueType = nominalCarrierEncodedAsValueType
                                            ?: nominalCarrierKind.isValueType,
                                    )
                                } else {
                                    nestedResolvedGenericTypeSignature(
                                        core.nominalCarrierType(
                                            generic = true,
                                            kind = nominalCarrierKind,
                                        ),
                                        checkNotNull(nominalConstraintConstructionDepth),
                                        if (nestedNominalCarrierNominalConstraint &&
                                            !nestedNominalCarrierObjectConstraint &&
                                            !violateNestedNominalCarrierConstraint
                                        ) {
                                            DotNetClrResolvedTypeSignature.Named(
                                                core.markerType,
                                                isValueType = false,
                                            )
                                        } else {
                                            DotNetClrResolvedTypeSignature.Primitive(
                                                nestedNominalCarrierLeafPrimitive,
                                            )
                                        },
                                        nominalCarrierEncodedAsValueType
                                            ?: nominalCarrierKind.isValueType,
                                    )
                                }
                            } else {
                                DotNetClrResolvedTypeSignature.Primitive(
                                    if (dependentParameterConstraint && parameterIndex == 0) {
                                        DotNetClrPrimitiveType.OBJECT
                                    } else if (parameterIndex == 0) {
                                        closedLeafPrimitive
                                    } else {
                                        DotNetClrPrimitiveType.STRING
                                    },
                                )
                            }
                        },
                    ),
                )
            ),
        )
        val graph = DotNetClrImportedDeclarationGraph(
            assemblies = listOfNotNull(constraintCore?.assembly) +
                    parentAssembly + intermediateAssemblies + childAssembly,
            hierarchies = buildList {
                if (hasNominalConstraint && includeNominalConstraintHierarchy) {
                    val core = checkNotNull(constraintCore)
                    if (nestedNominalCarrierNominalConstraint) {
                        add(
                            if (nestedNominalCarrierObjectConstraint) {
                                core.systemObjectHierarchy
                            } else {
                                core.markerHierarchy
                            }
                        )
                    }
                    add(
                        core.nominalCarrierHierarchy(
                            generic = nominalConstraintConstructionDepth != 0,
                            kind = nominalCarrierKind,
                        )
                    )
                }
                add(parentSource.declaringHierarchy)
                if (includeRetainedIntermediateHierarchy) addAll(intermediateHierarchies)
                add(childHierarchy)
            },
            physicalCoreTypes = if (includePhysicalCoreTypes) {
                constraintCore?.physicalCoreTypes
            } else {
                null
            },
        )
        val source = DotNetClrImportedMethodSource(
            parentAssembly,
            parentSource.declaringType,
            parentSource.declaringHierarchy,
            graph,
            parentFixture.method,
            parentSource.resolvedSignature,
        )
        val receiverSource = DotNetClrImportedTypeSource(
            childAssembly,
            childType,
            childHierarchy,
            graph,
        )
        return Fixture(
            source = source,
            method = parentFixture.method,
            relatedType = null,
            inheritedReceiverSource = receiverSource,
            intermediateType = intermediateTypes.last(),
        )
    }

    private fun nestedGenericTypeSignature(
        genericType: DotNetClrMetadataHandle,
        depth: Int,
        leaf: DotNetClrTypeSignature,
        isValueType: Boolean = false,
    ): DotNetClrTypeSignature {
        require(depth >= 1)
        var result = leaf
        repeat(depth) {
            result = DotNetClrTypeSignature.GenericInstance(
                DotNetClrTypeSignature.Named(genericType, isValueType),
                listOf(result),
            )
        }
        return result
    }

    private fun nestedResolvedGenericTypeSignature(
        genericType: DotNetClrResolvedTypeDefinition,
        depth: Int,
        leaf: DotNetClrResolvedTypeSignature,
        isValueType: Boolean = false,
    ): DotNetClrResolvedTypeSignature {
        require(depth >= 1)
        var result = leaf
        repeat(depth) {
            result = DotNetClrResolvedTypeSignature.GenericInstance(
                DotNetClrResolvedTypeSignature.Named(genericType, isValueType),
                listOf(result),
            )
        }
        return result
    }

    private fun minimalConstraintCoreFixture(
        genericMarkerParameterAttributes: Int = 0,
        genericMarkerClassCovariant: Boolean = false,
        genericMarkerValueCovariant: Boolean = false,
        genericMarkerDelegateContravariant: Boolean = false,
        genericMarkerDelegateSealed: Boolean = true,
        genericMarkerHasNominalConstraint: Boolean = false,
        genericMarkerNominalConstraintUsesObject: Boolean = false,
        genericMarkerNominalConstraintCopies: Int = 1,
    ): ConstraintCoreFixture {
        require(genericMarkerNominalConstraintCopies >= 1)
        require(genericMarkerNominalConstraintCopies == 1 ||
                genericMarkerHasNominalConstraint)
        require(!genericMarkerNominalConstraintUsesObject ||
                genericMarkerHasNominalConstraint)
        val definitions = mutableListOf<DotNetClrTypeDefinition>()
        fun type(
            namespaceName: String,
            metadataName: String,
            attributes: Long = PUBLIC_CLASS_ATTRIBUTES,
            baseType: DotNetClrMetadataHandle? = null,
        ): DotNetClrTypeDefinition = DotNetClrTypeDefinition(
            handle = DotNetClrMetadataHandle(TYPE_DEF_TABLE, definitions.size + 1),
            namespaceName = namespaceName,
            metadataName = metadataName,
            attributes = attributes,
            baseType = baseType,
            declaringType = null,
        ).also(definitions::add)

        val systemObject = type("System", "Object")
        val systemValueType = type("System", "ValueType", baseType = systemObject.handle)
        val systemEnum = type("System", "Enum", baseType = systemValueType.handle)
        val systemNullable = type("System", "Nullable`1", baseType = systemValueType.handle)
        val systemAttribute = type("System", "Attribute", baseType = systemObject.handle)
        type(
            "System.Runtime.CompilerServices",
            "IsByRefLikeAttribute",
            baseType = systemAttribute.handle,
        )
        type(
            "System",
            "Type",
            attributes = PUBLIC_ABSTRACT_CLASS_ATTRIBUTES,
            baseType = systemObject.handle,
        )
        type("System", "Array", baseType = systemObject.handle)
        val systemMulticastDelegate = type(
            "System",
            "MulticastDelegate",
            attributes = PUBLIC_ABSTRACT_CLASS_ATTRIBUTES,
            baseType = systemObject.handle,
        )
        val vectorInterfaces = listOf(
            type(
                "System.Collections.Generic",
                "IList`1",
                attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            ),
            type(
                "System.Collections.Generic",
                "ICollection`1",
                attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            ),
            type(
                "System.Collections.Generic",
                "IEnumerable`1",
                attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            ),
            type(
                "System.Collections.Generic",
                "IReadOnlyList`1",
                attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            ),
            type(
                "System.Collections.Generic",
                "IReadOnlyCollection`1",
                attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
            ),
        )
        val marker = type(
            "Synthetic.Constraint",
            "Marker",
            attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
        )
        val genericMarker = type(
            "Synthetic.Constraint",
            "MarkerOf`1",
            attributes = PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES,
        )
        val markerClass = type(
            "Synthetic.Constraint",
            "MarkerClass",
            baseType = systemObject.handle,
        )
        val genericMarkerClass = type(
            "Synthetic.Constraint",
            "MarkerClassOf`1",
            attributes = if (genericMarkerClassCovariant) {
                PUBLIC_ABSTRACT_CLASS_ATTRIBUTES
            } else {
                PUBLIC_CLASS_ATTRIBUTES
            },
            baseType = systemObject.handle,
        )
        val genericMarkerDelegate = type(
            "Synthetic.Constraint",
            "MarkerDelegateOf`1",
            attributes = PUBLIC_CLASS_ATTRIBUTES or if (genericMarkerDelegateSealed) {
                SEALED_TYPE_ATTRIBUTE
            } else {
                0L
            },
            baseType = systemMulticastDelegate.handle,
        )
        val markerValue = type(
            "Synthetic.Constraint",
            "MarkerValue",
            baseType = systemValueType.handle,
        )
        val genericMarkerValue = type(
            "Synthetic.Constraint",
            "MarkerValueOf`1",
            baseType = systemValueType.handle,
        )
        for (primitive in DotNetClrPrimitiveType.entries) {
            if (primitive == DotNetClrPrimitiveType.OBJECT) continue
            type(
                "System",
                primitiveMetadataName(primitive),
                baseType = if (primitive == DotNetClrPrimitiveType.STRING) {
                    systemObject.handle
                } else {
                    systemValueType.handle
                },
            )
        }
        val genericOwners = listOf(systemNullable) +
                vectorInterfaces + genericMarker + genericMarkerClass +
                genericMarkerDelegate + genericMarkerValue
        val genericParameters = genericOwners.mapIndexed { index, owner ->
            DotNetClrGenericParameterDefinition(
                handle = DotNetClrMetadataHandle(GENERIC_PARAMETER_TABLE, index + 1),
                number = 0,
                attributes = if (owner === genericMarker) {
                    COVARIANT_ATTRIBUTE or genericMarkerParameterAttributes
                } else if (owner === genericMarkerClass) {
                    genericMarkerParameterAttributes or if (genericMarkerClassCovariant) {
                        COVARIANT_ATTRIBUTE
                    } else {
                        0
                    }
                } else if (owner === genericMarkerValue) {
                    if (genericMarkerValueCovariant) COVARIANT_ATTRIBUTE else 0
                } else if (owner === genericMarkerDelegate) {
                    if (genericMarkerDelegateContravariant) {
                        CONTRAVARIANT_ATTRIBUTE
                    } else {
                        COVARIANT_ATTRIBUTE
                    }
                } else if (owner === systemNullable) {
                    NOT_NULLABLE_VALUE_TYPE_CONSTRAINT_ATTRIBUTE or
                            DEFAULT_CONSTRUCTOR_CONSTRAINT_ATTRIBUTE
                } else {
                    0
                },
                owner = owner.handle,
                name = "T",
            )
        }
        val genericParameterConstraints = if (genericMarkerHasNominalConstraint) {
            buildList {
                for (owner in listOf(
                    genericMarker,
                    genericMarkerClass,
                    genericMarkerDelegate,
                )) {
                    repeat(genericMarkerNominalConstraintCopies) {
                        add(
                            DotNetClrGenericParameterConstraint(
                                handle = DotNetClrMetadataHandle(
                                    GENERIC_PARAMETER_CONSTRAINT_TABLE,
                                    size + 1,
                                ),
                                owner = genericParameters.single { parameter ->
                                    parameter.owner == owner.handle
                                }.handle,
                                constraint = if (genericMarkerNominalConstraintUsesObject) {
                                    systemObject.handle
                                } else {
                                    marker.handle
                                },
                            )
                        )
                    }
                }
            }
        } else {
            emptyList()
        }
        val delegateInvoke = DotNetClrMethodDefinition(
            handle = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 1),
            declaringType = genericMarkerDelegate.handle,
            name = "Invoke",
            relativeVirtualAddress = 0L,
            implementationAttributes = RUNTIME_METHOD_IMPLEMENTATION_ATTRIBUTES,
            attributes = PUBLIC_VIRTUAL_METHOD_ATTRIBUTES,
            signature = DotNetClrMethodSignature(
                callingConvention = DotNetClrSignatureCallingConvention.DEFAULT,
                hasThis = true,
                hasExplicitThis = false,
                genericParameterCount = 0,
                returnType = if (genericMarkerDelegateContravariant) {
                    DotNetClrTypeSignature.Void
                } else {
                    DotNetClrTypeSignature.GenericParameter(
                        DotNetClrGenericParameterKind.TYPE,
                        0,
                    )
                },
                parameterTypes = if (genericMarkerDelegateContravariant) {
                    listOf(
                        DotNetClrTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            0,
                        )
                    )
                } else {
                    emptyList()
                },
                varargParameterStart = null,
            ),
            rawSignature = emptyList(),
        )
        val fakeClassInvoke = if (genericMarkerClassCovariant) {
            delegateInvoke.copy(
                handle = DotNetClrMetadataHandle(METHOD_DEF_TABLE, 2),
                declaringType = genericMarkerClass.handle,
                implementationAttributes = 0,
                attributes = PUBLIC_ABSTRACT_VIRTUAL_METHOD_ATTRIBUTES,
            )
        } else {
            null
        }
        val metadata = DotNetClrAssemblyMetadata(
            identity = DotNetManagedAssemblyIdentity(
                name = "Synthetic.Constraint.Core",
                version = "1.0.0.0",
                culture = "neutral",
                publicKey = emptyList(),
                publicKeyToken = emptyList(),
            ),
            assemblyReferences = emptyList(),
            typeReferences = emptyList(),
            typeDefinitions = definitions,
            interfaceImplementations = emptyList(),
            exportedTypes = emptyList(),
            typeSpecifications = emptyList(),
            fieldDefinitions = emptyList(),
            methodDefinitions = listOfNotNull(delegateInvoke, fakeClassInvoke),
            parameterDefinitions = emptyList(),
            constantDefinitions = emptyList(),
            fieldMarshalDefinitions = emptyList(),
            memberReferences = emptyList(),
            customAttributes = emptyList(),
            propertyDefinitions = emptyList(),
            methodSemantics = emptyList(),
            genericParameterDefinitions = genericParameters,
            genericParameterConstraints = genericParameterConstraints,
        )
        val assembly = DotNetClrClasspathAssembly.WithoutCarrier(
            File("Synthetic.Constraint.Core.dll"),
            metadata,
        )
        val markerType = DotNetClrResolvedTypeDefinition(metadata, marker)
        val genericMarkerType = DotNetClrResolvedTypeDefinition(metadata, genericMarker)
        val systemObjectType = DotNetClrResolvedTypeDefinition(metadata, systemObject)
        val systemValueTypeType = DotNetClrResolvedTypeDefinition(metadata, systemValueType)
        val systemMulticastDelegateType =
            DotNetClrResolvedTypeDefinition(metadata, systemMulticastDelegate)
        val systemNullableType = DotNetClrResolvedTypeDefinition(metadata, systemNullable)
        val markerClassType = DotNetClrResolvedTypeDefinition(metadata, markerClass)
        val genericMarkerClassType = DotNetClrResolvedTypeDefinition(metadata, genericMarkerClass)
        val genericMarkerDelegateType =
            DotNetClrResolvedTypeDefinition(metadata, genericMarkerDelegate)
        val markerValueType = DotNetClrResolvedTypeDefinition(metadata, markerValue)
        val genericMarkerValueType = DotNetClrResolvedTypeDefinition(metadata, genericMarkerValue)
        return ConstraintCoreFixture(
            assembly,
            DotNetClrPhysicalTypeCoreTypes(
                systemValueTypeType,
                DotNetClrResolvedTypeDefinition(metadata, systemEnum),
                systemNullableType,
            ),
            systemObjectType,
            DotNetClrResolvedTypeHierarchy(
                DotNetClrResolvedTypeView(systemObjectType, emptyList()),
                baseType = null,
                interfaces = emptyList(),
            ),
            markerType,
            DotNetClrResolvedTypeHierarchy(
                DotNetClrResolvedTypeView(markerType, emptyList()),
                baseType = null,
                interfaces = emptyList(),
            ),
            genericMarkerType,
            DotNetClrResolvedTypeHierarchy(
                DotNetClrResolvedTypeView(
                    genericMarkerType,
                    listOf(
                        DotNetClrResolvedTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            0,
                        )
                    ),
                ),
                baseType = null,
                interfaces = emptyList(),
            ),
            markerClassType,
            DotNetClrResolvedTypeHierarchy(
                DotNetClrResolvedTypeView(markerClassType, emptyList()),
                baseType = DotNetClrResolvedTypeView(systemObjectType, emptyList()),
                interfaces = emptyList(),
            ),
            genericMarkerClassType,
            DotNetClrResolvedTypeHierarchy(
                DotNetClrResolvedTypeView(
                    genericMarkerClassType,
                    listOf(
                        DotNetClrResolvedTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            0,
                        )
                    ),
                ),
                baseType = DotNetClrResolvedTypeView(systemObjectType, emptyList()),
                interfaces = emptyList(),
            ),
            genericMarkerDelegateType,
            DotNetClrResolvedTypeHierarchy(
                DotNetClrResolvedTypeView(
                    genericMarkerDelegateType,
                    listOf(
                        DotNetClrResolvedTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            0,
                        )
                    ),
                ),
                baseType = DotNetClrResolvedTypeView(
                    systemMulticastDelegateType,
                    emptyList(),
                ),
                interfaces = emptyList(),
            ),
            markerValueType,
            DotNetClrResolvedTypeHierarchy(
                DotNetClrResolvedTypeView(markerValueType, emptyList()),
                baseType = DotNetClrResolvedTypeView(systemValueTypeType, emptyList()),
                interfaces = emptyList(),
            ),
            genericMarkerValueType,
            DotNetClrResolvedTypeHierarchy(
                DotNetClrResolvedTypeView(
                    genericMarkerValueType,
                    listOf(
                        DotNetClrResolvedTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            0,
                        )
                    ),
                ),
                baseType = DotNetClrResolvedTypeView(systemValueTypeType, emptyList()),
                interfaces = emptyList(),
            ),
            systemNullableType,
            DotNetClrResolvedTypeHierarchy(
                DotNetClrResolvedTypeView(
                    systemNullableType,
                    listOf(
                        DotNetClrResolvedTypeSignature.GenericParameter(
                            DotNetClrGenericParameterKind.TYPE,
                            0,
                        )
                    ),
                ),
                baseType = DotNetClrResolvedTypeView(systemValueTypeType, emptyList()),
                interfaces = emptyList(),
            ),
        )
    }

    private fun primitiveMetadataName(type: DotNetClrPrimitiveType): String = when (type) {
        DotNetClrPrimitiveType.BOOLEAN -> "Boolean"
        DotNetClrPrimitiveType.CHAR -> "Char"
        DotNetClrPrimitiveType.INT8 -> "SByte"
        DotNetClrPrimitiveType.UINT8 -> "Byte"
        DotNetClrPrimitiveType.INT16 -> "Int16"
        DotNetClrPrimitiveType.UINT16 -> "UInt16"
        DotNetClrPrimitiveType.INT32 -> "Int32"
        DotNetClrPrimitiveType.UINT32 -> "UInt32"
        DotNetClrPrimitiveType.INT64 -> "Int64"
        DotNetClrPrimitiveType.UINT64 -> "UInt64"
        DotNetClrPrimitiveType.FLOAT32 -> "Single"
        DotNetClrPrimitiveType.FLOAT64 -> "Double"
        DotNetClrPrimitiveType.STRING -> "String"
        DotNetClrPrimitiveType.NATIVE_INT -> "IntPtr"
        DotNetClrPrimitiveType.NATIVE_UINT -> "UIntPtr"
        DotNetClrPrimitiveType.OBJECT -> "Object"
    }

    private data class ConstraintCoreFixture(
        val assembly: DotNetClrClasspathAssembly.WithoutCarrier,
        val physicalCoreTypes: DotNetClrPhysicalTypeCoreTypes,
        val systemObjectType: DotNetClrResolvedTypeDefinition,
        val systemObjectHierarchy: DotNetClrResolvedTypeHierarchy,
        val markerType: DotNetClrResolvedTypeDefinition,
        val markerHierarchy: DotNetClrResolvedTypeHierarchy,
        val genericMarkerType: DotNetClrResolvedTypeDefinition,
        val genericMarkerHierarchy: DotNetClrResolvedTypeHierarchy,
        val markerClassType: DotNetClrResolvedTypeDefinition,
        val markerClassHierarchy: DotNetClrResolvedTypeHierarchy,
        val genericMarkerClassType: DotNetClrResolvedTypeDefinition,
        val genericMarkerClassHierarchy: DotNetClrResolvedTypeHierarchy,
        val genericMarkerDelegateType: DotNetClrResolvedTypeDefinition,
        val genericMarkerDelegateHierarchy: DotNetClrResolvedTypeHierarchy,
        val markerValueType: DotNetClrResolvedTypeDefinition,
        val markerValueHierarchy: DotNetClrResolvedTypeHierarchy,
        val genericMarkerValueType: DotNetClrResolvedTypeDefinition,
        val genericMarkerValueHierarchy: DotNetClrResolvedTypeHierarchy,
        val systemNullableType: DotNetClrResolvedTypeDefinition,
        val systemNullableHierarchy: DotNetClrResolvedTypeHierarchy,
    ) {
        fun nominalCarrierType(
            generic: Boolean,
            kind: NominalCarrierKind,
        ): DotNetClrResolvedTypeDefinition = when {
            kind == NominalCarrierKind.INTERFACE && generic -> genericMarkerType
            kind == NominalCarrierKind.INTERFACE -> markerType
            kind == NominalCarrierKind.REFERENCE_CLASS && generic -> genericMarkerClassType
            kind == NominalCarrierKind.REFERENCE_CLASS -> markerClassType
            kind == NominalCarrierKind.DELEGATE && generic -> genericMarkerDelegateType
            kind == NominalCarrierKind.VALUE_TYPE && generic -> genericMarkerValueType
            kind == NominalCarrierKind.VALUE_TYPE -> markerValueType
            kind == NominalCarrierKind.NULLABLE_VALUE_TYPE && generic -> systemNullableType
            else -> error("a nullable value carrier requires a generic construction")
        }

        fun nominalCarrierHierarchy(
            generic: Boolean,
            kind: NominalCarrierKind,
        ): DotNetClrResolvedTypeHierarchy = when {
            kind == NominalCarrierKind.INTERFACE && generic -> genericMarkerHierarchy
            kind == NominalCarrierKind.INTERFACE -> markerHierarchy
            kind == NominalCarrierKind.REFERENCE_CLASS && generic -> genericMarkerClassHierarchy
            kind == NominalCarrierKind.REFERENCE_CLASS -> markerClassHierarchy
            kind == NominalCarrierKind.DELEGATE && generic -> genericMarkerDelegateHierarchy
            kind == NominalCarrierKind.VALUE_TYPE && generic -> genericMarkerValueHierarchy
            kind == NominalCarrierKind.VALUE_TYPE -> markerValueHierarchy
            kind == NominalCarrierKind.NULLABLE_VALUE_TYPE && generic -> systemNullableHierarchy
            else -> error("a nullable value carrier requires a generic construction")
        }
    }

    private enum class NominalCarrierKind(
        val isValueType: Boolean,
    ) {
        INTERFACE(isValueType = false),
        REFERENCE_CLASS(isValueType = false),
        DELEGATE(isValueType = false),
        VALUE_TYPE(isValueType = true),
        NULLABLE_VALUE_TYPE(isValueType = true),
    }

    private fun Boolean.toNominalCarrierKind(): NominalCarrierKind =
        if (this) NominalCarrierKind.REFERENCE_CLASS else NominalCarrierKind.INTERFACE

    private data class RetainedForeignIrFixture(
        val owner: IrClass,
        val function: IrSimpleFunction,
    )

    private data class Fixture(
        val source: DotNetClrImportedMethodSource,
        val method: DotNetClrMethodDefinition,
        val relatedType: DotNetClrResolvedTypeDefinition?,
        val inheritedReceiverSource: DotNetClrImportedTypeSource?,
        val intermediateType: DotNetClrResolvedTypeDefinition? = null,
    )

    private companion object {
        const val TYPE_REFERENCE_TABLE = 1
        const val TYPE_DEF_TABLE = 2
        const val METHOD_DEF_TABLE = 6
        const val INTERFACE_IMPLEMENTATION_TABLE = 9
        const val TYPE_SPEC_TABLE = 27
        const val ASSEMBLY_REFERENCE_TABLE = 35
        const val GENERIC_PARAMETER_TABLE = 42
        const val GENERIC_PARAMETER_CONSTRAINT_TABLE = 44

        const val COVARIANT_ATTRIBUTE = 0x0001
        const val CONTRAVARIANT_ATTRIBUTE = 0x0002
        const val REFERENCE_TYPE_CONSTRAINT_ATTRIBUTE = 0x0004
        const val NOT_NULLABLE_VALUE_TYPE_CONSTRAINT_ATTRIBUTE = 0x0008
        const val DEFAULT_CONSTRUCTOR_CONSTRAINT_ATTRIBUTE = 0x0010
        const val ALLOW_BY_REF_LIKE_ATTRIBUTE = 0x0020
        const val UNKNOWN_GENERIC_PARAMETER_ATTRIBUTE = 0x0040

        const val PUBLIC_ABSTRACT_INTERFACE_ATTRIBUTES = 0x0000_00a1L
        const val PUBLIC_CLASS_ATTRIBUTES = 0x0000_0001L
        const val PUBLIC_ABSTRACT_CLASS_ATTRIBUTES = 0x0000_0081L
        const val SEALED_TYPE_ATTRIBUTE = 0x0000_0100L
        const val PUBLIC_VIRTUAL_METHOD_ATTRIBUTES = 0x0000_01c6
        const val PUBLIC_ABSTRACT_VIRTUAL_METHOD_ATTRIBUTES = 0x0000_05c6
        const val RUNTIME_METHOD_IMPLEMENTATION_ATTRIBUTES = 0x0003
    }
}
