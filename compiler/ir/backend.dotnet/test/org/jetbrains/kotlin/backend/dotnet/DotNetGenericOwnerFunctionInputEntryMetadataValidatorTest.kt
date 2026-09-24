/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterConstraint
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrMetadataHandle
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetManagedAssemblyIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DotNetGenericOwnerFunctionInputEntryMetadataValidatorTest {
    @Test
    fun authenticatesStaticAndInstanceEntriesWithIndependentObjectResults() {
        listOf(false, true).forEach { isInstance ->
            val fixture = fixture(isInstance = isInstance)
            val binding = fixture.validate()
            assertEquals(SOURCE_METHOD, binding.sourceMethodDefinition.handle)
            assertEquals(INPUT_METHOD, binding.inputMethodDefinition.handle)
            assertEquals(OWNER, binding.declaringType.handle)
        }
    }

    @Test
    fun nullResultAuthorityRetainsTheExactSourceResult() {
        val fixture = fixture(objectResult = false)
        fixture.validate()
        assertFailsWith<IllegalArgumentException> {
            fixture.validate(assembly = fixture.replaceInput { method ->
                method.copy(signature = method.signature.copy(returnType = OBJECT_TYPE))
            })
        }
    }

    @Test
    fun rejectsMissingReceiverAndNonObjectSelectedParameters() {
        listOf(false, true).forEach { isInstance ->
            val fixture = fixture(isInstance = isInstance)
            assertFailsWith<IllegalArgumentException> {
                fixture.validate(entry = fixture.entry.copy(objectParameterIndices = setOf(3)))
            }
            if (isInstance) {
                assertFailsWith<IllegalArgumentException> {
                    fixture.validate(entry = fixture.entry.copy(objectParameterIndices = setOf(0)))
                }
            }
            assertFailsWith<IllegalArgumentException> {
                fixture.validate(assembly = fixture.replaceInput { method ->
                    method.copy(signature = method.signature.copy(parameterTypes = listOf(STRING_TYPE, BOOLEAN_TYPE)))
                })
            }
        }
    }

    @Test
    fun rejectsWrongObjectResultAndVoidSource() {
        val fixture = fixture()
        assertFailsWith<IllegalArgumentException> {
            fixture.validate(assembly = fixture.replaceInput { method ->
                method.copy(signature = method.signature.copy(returnType = STRING_TYPE))
            })
        }
        assertFailsWith<IllegalArgumentException> {
            fixture.validate(assembly = fixture.replaceSource { method ->
                method.copy(signature = method.signature.copy(returnType = DotNetClrTypeSignature.Void))
            })
        }
    }

    @Test
    fun rejectsChangedUnselectedParameterAndParameterCount() {
        val fixture = fixture()
        listOf(listOf(OBJECT_TYPE, STRING_TYPE), listOf(OBJECT_TYPE), listOf(OBJECT_TYPE, BOOLEAN_TYPE, STRING_TYPE))
            .forEach { parameters ->
                assertFailsWith<IllegalArgumentException> {
                    fixture.validate(assembly = fixture.replaceInput { method ->
                        method.copy(signature = method.signature.copy(parameterTypes = parameters))
                    })
                }
            }
    }

    @Test
    fun rejectsWrongSourceOwnerNameDispatchAndArity() {
        val fixture = fixture()
        listOf(
            fixture.source.copy(ownerPath = listOf("demo.Other")),
            fixture.source.copy(methodName = "missing"),
            fixture.source.copy(isInstance = true),
            fixture.source.copy(methodGenericParameterCount = 1),
            fixture.source.copy(methodName = fixture.entry.methodName),
        ).forEach { source ->
            assertFailsWith<IllegalArgumentException> { fixture.validate(source = source) }
        }
        assertFailsWith<IllegalArgumentException> {
            fixture.validate(entry = fixture.entry.copy(methodName = "missing"))
        }
    }

    @Test
    fun rejectsWrongMethodDefFlagsDispatchAndGenericArity() {
        val fixture = fixture()
        val input = fixture.assembly.methodDefinitions.single { method -> method.handle == INPUT_METHOD }
        listOf(
            input.copy(declaringType = INTERFACE),
            input.copy(attributes = input.attributes and STATIC_ATTRIBUTE.inv()),
            input.copy(attributes = input.attributes or VIRTUAL_ATTRIBUTE),
            input.copy(attributes = input.attributes or ABSTRACT_ATTRIBUTE),
            input.copy(attributes = input.attributes or SPECIAL_NAME_ATTRIBUTE),
            input.copy(attributes = input.attributes or RUNTIME_SPECIAL_NAME_ATTRIBUTE),
            input.copy(attributes = input.attributes and PUBLIC_ATTRIBUTE.inv()),
            input.copy(signature = input.signature.copy(hasThis = true)),
            input.copy(signature = input.signature.copy(hasExplicitThis = true)),
            input.copy(signature = input.signature.copy(varargParameterStart = 1)),
            input.copy(signature = input.signature.copy(genericParameterCount = 1)),
        ).forEach { hostile ->
            assertFailsWith<IllegalArgumentException> {
                fixture.validate(assembly = fixture.replaceInput { hostile })
            }
        }
    }

    @Test
    fun rejectsAnOverridableVirtualSource() {
        val fixture = fixture(isInstance = true)
        assertFailsWith<IllegalArgumentException> {
            fixture.validate(assembly = fixture.replaceSource { method ->
                method.copy(attributes = method.attributes or VIRTUAL_ATTRIBUTE)
            })
        }
    }

    @Test
    fun acceptsAFinalVirtualSourceOnAnUnsealedOwner() {
        val fixture = fixture(isInstance = true)
        val binding = fixture.validate(assembly = fixture.replaceSource { method ->
            method.copy(attributes = method.attributes or VIRTUAL_ATTRIBUTE or FINAL_ATTRIBUTE)
        })
        assertEquals(SOURCE_METHOD, binding.sourceMethodDefinition.handle)
    }

    @Test
    fun acceptsANonFinalVirtualSourceOnASealedOwner() {
        val fixture = fixture(isInstance = true)
        val assembly = fixture.replaceSource { method ->
            method.copy(attributes = method.attributes or VIRTUAL_ATTRIBUTE)
        }.copy(typeDefinitions = fixture.assembly.typeDefinitions.map { type ->
            if (type.handle == OWNER) type.copy(attributes = type.attributes or SEALED_TYPE_ATTRIBUTE) else type
        })
        val binding = fixture.validate(assembly = assembly)
        assertEquals(SOURCE_METHOD, binding.sourceMethodDefinition.handle)
    }

    @Test
    fun rejectsAmbiguousInputAndSourceMethodDefs() {
        val fixture = fixture()
        fixture.assembly.methodDefinitions.forEach { original ->
            val duplicate = original.copy(handle = DotNetClrMetadataHandle(6, 3))
            assertFailsWith<IllegalArgumentException> {
                fixture.validate(assembly = fixture.assembly.copy(
                    methodDefinitions = fixture.assembly.methodDefinitions + duplicate,
                ))
            }
        }
    }

    @Test
    fun selectsTheRecordedSourceOverloadBeforeReplacingItsInput() {
        val fixture = fixture()
        val source = fixture.assembly.methodDefinitions.single { method -> method.handle == SOURCE_METHOD }
        val decoy = source.copy(
            handle = DotNetClrMetadataHandle(6, 3),
            signature = source.signature.copy(parameterTypes = listOf(OBJECT_TYPE, BOOLEAN_TYPE)),
        )
        val binding = fixture.validate(assembly = fixture.assembly.copy(
            methodDefinitions = fixture.assembly.methodDefinitions + decoy,
        ))
        assertEquals(SOURCE_METHOD, binding.sourceMethodDefinition.handle)
    }

    @Test
    fun authenticatesImplicitCorePrimitiveCarriersWithoutExpandingTheTypeGrammar() {
        listOf(
            DotNetClrPrimitiveType.BOOLEAN to "Boolean",
            DotNetClrPrimitiveType.CHAR to "Char",
            DotNetClrPrimitiveType.INT8 to "SByte",
            DotNetClrPrimitiveType.UINT8 to "Byte",
            DotNetClrPrimitiveType.INT16 to "Int16",
            DotNetClrPrimitiveType.UINT16 to "UInt16",
            DotNetClrPrimitiveType.INT32 to "Int32",
            DotNetClrPrimitiveType.UINT32 to "UInt32",
            DotNetClrPrimitiveType.INT64 to "Int64",
            DotNetClrPrimitiveType.UINT64 to "UInt64",
            DotNetClrPrimitiveType.FLOAT32 to "Single",
            DotNetClrPrimitiveType.FLOAT64 to "Double",
            DotNetClrPrimitiveType.NATIVE_INT to "IntPtr",
            DotNetClrPrimitiveType.NATIVE_UINT to "UIntPtr",
        ).forEach { [primitive, name] ->
            val recorded = DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
                listOf("System", name), DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
            )
            val actual = DotNetClrTypeSignature.Primitive(primitive)
            fixture().withUnselectedCarrier(recorded, actual).validate()
            fixture().withUnselectedCarrier(
                DotNetGenericOwnerPhysicalTypeExpressionRecord.szArray(recorded),
                DotNetClrTypeSignature.SzArray(actual),
            ).validate()
        }
    }

    @Test
    fun primitiveAliasesRequireExactCoreScopeValueCategoryNameAndArity() {
        val recorded = DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
            listOf("System", "Int64"), DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
        )
        val actual = DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT64)
        listOf(
            recorded.copy(scope = DotNetGenericOwnerPhysicalTypeScope.ASSEMBLY, assemblyName = "System.Runtime"),
            recorded.copy(scope = DotNetGenericOwnerPhysicalTypeScope.PRODUCER),
            recorded.copy(namedTypeCategory = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS),
            recorded.copy(typePath = listOf("Other", "Int64")),
            recorded.copy(typePath = listOf("System", "UInt64")),
            recorded.copy(genericArity = 1, arguments = listOf(DotNetGenericOwnerPhysicalTypeExpressionRecord.int32Type())),
        ).forEach { hostile ->
            assertFailsWith<IllegalArgumentException> { fixture().withUnselectedCarrier(hostile, actual).validate() }
        }
    }

    @Test
    fun authenticatesNullableCorePrimitiveConstruction() {
        val recordedLong = DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
            listOf("System", "Int64"), DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
        )
        val recorded = DotNetGenericOwnerPhysicalTypeExpressionRecord.coreType(
            listOf("System", "Nullable"), DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE, listOf(recordedLong),
        )
        val nullableType = DotNetClrMetadataHandle(1, 1)
        val coreAssembly = DotNetClrMetadataHandle(35, 1)
        val actual = DotNetClrTypeSignature.GenericInstance(
            DotNetClrTypeSignature.Named(nullableType, isValueType = true),
            listOf(DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT64)),
        )
        val fixture = fixture().withUnselectedCarrier(recorded, actual)
        fixture.validate(assembly = fixture.assembly.copy(
            assemblyReferences = listOf(DotNetClrAssemblyReference(
                coreAssembly, "System.Runtime", "10.0.0.0", "neutral", 0, emptyList(), emptyList(),
            )),
            typeReferences = listOf(DotNetClrTypeReference(nullableType, "System", "Nullable`1", coreAssembly)),
        ))
    }

    @Test
    fun authenticatesExactExternalNestedTypeReferenceChains() {
        nestedExternalFixture().validate()
    }

    @Test
    fun rejectsExternalNestedNamespaceAssemblyNestingAndArityDrift() {
        val fixture = nestedExternalFixture()
        val recorded = fixture.entry.sourceSignature.parameterSlots[1].type
        listOf(
            recorded.copy(typePath = listOf("wrong", "Outer`1/Inner`1")),
            recorded.copy(assemblyName = "Wrong"),
            recorded.copy(typePath = listOf("foreign", "Different`1/Inner`1")),
            recorded.copy(typePath = listOf("foreign", "Outer`1/Different`1")),
            recorded.copy(typePath = listOf("foreign", "Outer`1", "Inner")),
            recorded.copy(genericArity = 1, arguments = recorded.arguments.take(1)),
        ).forEach { hostile ->
            assertFailsWith<IllegalArgumentException> {
                fixture.validate(entry = fixture.entry.copy(sourceSignature = fixture.entry.sourceSignature.copy(
                    parameterSlots = listOf(fixture.entry.sourceSignature.parameterSlots.first(), slot(hostile)),
                )))
            }
        }
        val inner = fixture.assembly.typeReferences.last()
        assertFailsWith<IllegalArgumentException> {
            fixture.validate(assembly = fixture.assembly.copy(typeReferences = fixture.assembly.typeReferences.map { reference ->
                if (reference == inner) reference.copy(namespaceName = "foreign") else reference
            }))
        }
    }

    @Test
    fun rejectsCyclicAndExcessivelyDeepExternalNestedTypeReferenceChains() {
        val fixture = nestedExternalFixture()
        val recorded = fixture.entry.sourceSignature.parameterSlots[1].type
        val outer = fixture.assembly.typeReferences.first()
        val inner = fixture.assembly.typeReferences.last()
        val cyclic = fixture.assembly.copy(typeReferences = listOf(
            outer.copy(namespaceName = "", resolutionScope = inner.handle), inner,
        ))
        listOf(
            "Outer`1/Inner`1/Outer`1/Inner`1",
            List(65) { "Inner`1" }.joinToString("/"),
        ).forEach { chain ->
            assertFailsWith<IllegalArgumentException> {
                fixture.validate(
                    entry = fixture.entry.copy(sourceSignature = fixture.entry.sourceSignature.copy(
                        parameterSlots = listOf(
                            fixture.entry.sourceSignature.parameterSlots.first(),
                            slot(recorded.copy(typePath = listOf("foreign", chain))),
                        ),
                    )),
                    assembly = cyclic,
                )
            }
        }
    }

    @Test
    fun authenticatesGenericBinderConstraintsAndRejectsDrift() {
        val fixture = fixture(generic = true)
        fixture.validate()
        val parameters = fixture.assembly.genericParameterDefinitions
        val inputParameter = parameters.single { parameter -> parameter.owner == INPUT_METHOD }
        listOf(
            fixture.assembly.copy(genericParameterDefinitions = parameters.filter { parameter -> parameter != inputParameter }),
            fixture.assembly.copy(genericParameterDefinitions = parameters.map { parameter ->
                if (parameter == inputParameter) parameter.copy(number = 1) else parameter
            }),
            fixture.assembly.copy(genericParameterDefinitions = parameters.map { parameter ->
                if (parameter == inputParameter) parameter.copy(attributes = 4) else parameter
            }),
            fixture.assembly.copy(genericParameterConstraints = listOf(DotNetClrGenericParameterConstraint(
                handle = DotNetClrMetadataHandle(44, 1),
                owner = inputParameter.handle,
                constraint = INTERFACE,
            ))),
        ).forEach { hostile ->
            assertFailsWith<IllegalArgumentException> { fixture.validate(assembly = hostile) }
        }
        val constraints = parameters.filter { parameter -> parameter.owner in setOf(SOURCE_METHOD, INPUT_METHOD) }
            .mapIndexed { index, parameter ->
                DotNetClrGenericParameterConstraint(DotNetClrMetadataHandle(44, index + 1), parameter.handle, INTERFACE)
            }
        fixture.validate(assembly = fixture.assembly.copy(genericParameterConstraints = constraints))
    }

    @Test
    fun preservesExactSameModuleTypeReferenceAliasesInUnchangedResults() {
        val fixture = fixture(objectResult = false)
        val reference = DotNetClrTypeReference(
            DotNetClrMetadataHandle(1, 1), "demo", "Source`1", DotNetClrMetadataHandle(0, 1),
        )
        val sourceResult = fixture.assembly.methodDefinitions.single { method -> method.handle == SOURCE_METHOD }
            .signature.returnType as DotNetClrTypeSignature.GenericInstance
        val assembly = fixture.replaceInput { method ->
            method.copy(signature = method.signature.copy(returnType = sourceResult.copy(
                genericType = sourceResult.genericType.copy(type = reference.handle),
            )))
        }.copy(typeReferences = listOf(reference))
        fixture.validate(assembly = assembly)
        assertFailsWith<IllegalArgumentException> {
            fixture.validate(assembly = assembly.copy(typeReferences = listOf(reference.copy(resolutionScope = null))))
        }
    }

    private data class Fixture(
        val entry: DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry,
        val source: DotNetPhysicalDeclaration.Function,
        val assembly: DotNetClrAssemblyMetadata,
    ) {
        fun validate(
            entry: DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry = this.entry,
            source: DotNetPhysicalDeclaration.Function = this.source,
            assembly: DotNetClrAssemblyMetadata = this.assembly,
        ) = validateDotNetGenericOwnerFunctionInputEntryAgainstClrMetadata(entry, source, assembly, DotNetTarget.NET10_0)

        fun replaceInput(transform: (DotNetClrMethodDefinition) -> DotNetClrMethodDefinition) =
            replaceMethod(INPUT_METHOD, transform)

        fun replaceSource(transform: (DotNetClrMethodDefinition) -> DotNetClrMethodDefinition) =
            replaceMethod(SOURCE_METHOD, transform)

        fun withUnselectedCarrier(
            recorded: DotNetGenericOwnerPhysicalTypeExpressionRecord,
            actual: DotNetClrTypeSignature,
        ) = copy(
            entry = entry.copy(sourceSignature = entry.sourceSignature.copy(
                parameterSlots = listOf(entry.sourceSignature.parameterSlots.first(), slot(recorded)),
            )),
            assembly = assembly.copy(methodDefinitions = assembly.methodDefinitions.map { method ->
                method.copy(signature = method.signature.copy(parameterTypes = listOf(method.signature.parameterTypes.first(), actual)))
            }),
        )

        private fun replaceMethod(
            handle: DotNetClrMetadataHandle,
            transform: (DotNetClrMethodDefinition) -> DotNetClrMethodDefinition,
        ) = assembly.copy(methodDefinitions = assembly.methodDefinitions.map { method ->
            if (method.handle == handle) transform(method) else method
        })
    }

    private fun nestedExternalFixture(): Fixture {
        val outer = DotNetClrMetadataHandle(1, 1)
        val inner = DotNetClrMetadataHandle(1, 2)
        val externalAssembly = DotNetClrMetadataHandle(35, 1)
        val recorded = DotNetGenericOwnerPhysicalTypeExpressionRecord(
            kind = DotNetGenericOwnerPhysicalTypeKind.NAMED,
            scope = DotNetGenericOwnerPhysicalTypeScope.ASSEMBLY,
            assemblyName = "Other",
            typePath = listOf("foreign", "Outer`1/Inner`1"),
            genericArity = 2,
            namedTypeCategory = DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS,
            arguments = listOf(
                DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType(),
                DotNetGenericOwnerPhysicalTypeExpressionRecord.int32Type(),
            ),
        )
        val fixture = fixture().withUnselectedCarrier(recorded, DotNetClrTypeSignature.GenericInstance(
            DotNetClrTypeSignature.Named(inner, isValueType = false),
            listOf(STRING_TYPE, DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32)),
        ))
        return fixture.copy(assembly = fixture.assembly.copy(
            assemblyReferences = listOf(DotNetClrAssemblyReference(
                externalAssembly, "Other", "1.0.0.0", "neutral", 0, emptyList(), emptyList(),
            )),
            typeReferences = listOf(
                DotNetClrTypeReference(outer, "foreign", "Outer`1", externalAssembly),
                DotNetClrTypeReference(inner, "", "Inner`1", outer),
            ),
        ))
    }

    private fun fixture(isInstance: Boolean = false, objectResult: Boolean = true, generic: Boolean = false): Fixture {
        val arity = if (generic) 1 else 0
        val naturalType = DotNetClrTypeSignature.GenericInstance(
            DotNetClrTypeSignature.Named(INTERFACE, isValueType = false),
            listOf(if (generic) DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.METHOD, 0) else STRING_TYPE),
        )
        val source = DotNetPhysicalDeclaration.Function(listOf("demo.Consumer"), "view", isInstance, arity)
        val recordedNaturalType = DotNetGenericOwnerPhysicalTypeExpressionRecord.producerType(
            typePath = listOf("demo.Source`1"),
            category = DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE,
            arguments = listOf(if (generic) {
                DotNetGenericOwnerPhysicalTypeExpressionRecord.methodParameter(0)
            } else {
                DotNetGenericOwnerPhysicalTypeExpressionRecord.stringType()
            }),
        )
        val entry = DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry(
            ownerPath = source.ownerPath,
            logicalFunctionKey = "source-view",
            methodName = "inputView",
            isInstance = isInstance,
            methodGenericParameterCount = arity,
            objectParameterIndices = setOf(if (isInstance) 1 else 0),
            returnCarrier = if (objectResult) DotNetGenericOwnerFunctionCarrierKind.OBJECT else null,
            sourceSignature = DotNetGenericOwnerPhysicalMethodSignatureRecord(
                isInstance = isInstance,
                genericArity = arity,
                resultLayout = DotNetGenericOwnerPhysicalCallableResultLayoutRecord.Direct(
                    slot(recordedNaturalType),
                ),
                parameterSlots = listOf(slot(recordedNaturalType), slot(DotNetGenericOwnerPhysicalTypeExpressionRecord.booleanType())),
            ),
        )
        fun method(handle: DotNetClrMetadataHandle, input: Boolean) = DotNetClrMethodDefinition(
            handle = handle,
            declaringType = OWNER,
            name = if (input) entry.methodName else source.methodName,
            relativeVirtualAddress = 0,
            implementationAttributes = 0,
            attributes = PUBLIC_ATTRIBUTE or if (isInstance) 0 else STATIC_ATTRIBUTE,
            signature = DotNetClrMethodSignature(
                callingConvention = DotNetClrSignatureCallingConvention.DEFAULT,
                hasThis = isInstance,
                hasExplicitThis = false,
                genericParameterCount = arity,
                returnType = if (input && objectResult) OBJECT_TYPE else naturalType,
                parameterTypes = listOf(if (input) OBJECT_TYPE else naturalType, BOOLEAN_TYPE),
                varargParameterStart = null,
            ),
            rawSignature = emptyList(),
        )
        return Fixture(entry, source, DotNetClrAssemblyMetadata(
            identity = DotNetManagedAssemblyIdentity("Demo", "1.0.0.0", "neutral", emptyList(), emptyList()),
            assemblyReferences = emptyList(),
            typeReferences = emptyList(),
            typeDefinitions = listOf(
                DotNetClrTypeDefinition(OWNER, "demo", "Consumer", 1, null, null),
                DotNetClrTypeDefinition(INTERFACE, "demo", "Source`1", 0xa1, null, null),
            ),
            interfaceImplementations = emptyList(),
            exportedTypes = emptyList(),
            typeSpecifications = emptyList(),
            fieldDefinitions = emptyList(),
            methodDefinitions = listOf(method(SOURCE_METHOD, false), method(INPUT_METHOD, true)),
            parameterDefinitions = emptyList(),
            constantDefinitions = emptyList(),
            fieldMarshalDefinitions = emptyList(),
            memberReferences = emptyList(),
            customAttributes = emptyList(),
            propertyDefinitions = emptyList(),
            methodSemantics = emptyList(),
            genericParameterDefinitions = listOf(
                DotNetClrGenericParameterDefinition(DotNetClrMetadataHandle(42, 1), 0, 1, INTERFACE, "T"),
            ) + if (generic) listOf(
                DotNetClrGenericParameterDefinition(DotNetClrMetadataHandle(42, 2), 0, 0, SOURCE_METHOD, "T"),
                DotNetClrGenericParameterDefinition(DotNetClrMetadataHandle(42, 3), 0, 0, INPUT_METHOD, "T"),
            ) else emptyList(),
            genericParameterConstraints = emptyList(),
        ))
    }

    private companion object {
        fun slot(type: DotNetGenericOwnerPhysicalTypeExpressionRecord) = DotNetGenericOwnerPhysicalValueSlotRecord(
            DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT,
            type,
        )
        val OWNER = DotNetClrMetadataHandle(2, 1)
        val INTERFACE = DotNetClrMetadataHandle(2, 2)
        val SOURCE_METHOD = DotNetClrMetadataHandle(6, 1)
        val INPUT_METHOD = DotNetClrMetadataHandle(6, 2)
        val OBJECT_TYPE = DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.OBJECT)
        val STRING_TYPE = DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.STRING)
        val BOOLEAN_TYPE = DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN)
        const val PUBLIC_ATTRIBUTE = 0x0006
        const val STATIC_ATTRIBUTE = 0x0010
        const val FINAL_ATTRIBUTE = 0x0020
        const val VIRTUAL_ATTRIBUTE = 0x0040
        const val SEALED_TYPE_ATTRIBUTE = 0x0000_0100L
        const val ABSTRACT_ATTRIBUTE = 0x0400
        const val SPECIAL_NAME_ATTRIBUTE = 0x0800
        const val RUNTIME_SPECIAL_NAME_ATTRIBUTE = 0x1000
    }
}
