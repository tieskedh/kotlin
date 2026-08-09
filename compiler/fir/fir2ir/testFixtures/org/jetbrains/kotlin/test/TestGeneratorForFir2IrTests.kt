/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test

import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5
import org.jetbrains.kotlin.generators.util.TestGeneratorUtil
import org.jetbrains.kotlin.spec.utils.tasks.detectDirsWithTestsMapFileOnly
import org.jetbrains.kotlin.test.runners.AbstractFirBlackBoxCodegenTestSpec
import org.jetbrains.kotlin.test.runners.codegen.*
import org.jetbrains.kotlin.test.runners.ir.AbstractFirLightTreeJvmIrSourceRangesTest
import org.jetbrains.kotlin.test.runners.ir.AbstractFirLightTreeJvmIrTextTest
import org.jetbrains.kotlin.test.runners.ir.AbstractFirPsiJvmIrSourceRangesTest
import org.jetbrains.kotlin.test.runners.ir.AbstractFirPsiJvmIrTextTest

private const val DOT_NET_COROUTINE_ROOT_PATTERN =
    "^(beginWithException|coercionToUnit|createCoroutineSafe|emptyClosure|falseUnitCoercion|handleException|" +
            "handleResultSuspended|localCallableRef|multipleInvokeCalls|simple|simpleSuspendCallableReference|" +
            "simpleWithHandleResult|suspendCoroutineFromStateMachine|suspendLambdaInInterface)\\.kt$"
private const val DOT_NET_COROUTINE_CONTROL_FLOW_PATTERN =
    "^(returnWithFinally|throwFromCatch|throwFromFinally)\\.kt$"
private const val DOT_NET_COROUTINE_FEATURE_INTERSECTION_PATTERN =
    "^(breakWithNonEmptyStack)\\.kt$"
private const val DOT_NET_GENERIC_OBJECT_BRIDGE_PATTERN = "^(simpleObject)\\.kt$"
private const val DOT_NET_COROUTINE_INTRINSIC_PATTERN = "^(intercepted|releaseIntercepted)\\.kt$"
private const val DOT_NET_COROUTINE_VALUE_CLASS_DIRECT_PATTERN = "^(boxUnboxInsideCoroutine_InlineInt)\\.kt$"
private const val DOT_NET_COROUTINE_VALUE_CLASS_RESUME_PATTERN =
    "^(boxUnboxInsideCoroutine_InlineInt|boxUnboxInsideCoroutine_NAny|genericOverrideSuspendFun)\\.kt$"
private const val DOT_NET_COROUTINE_VALUE_CLASS_EXCEPTION_PATTERN = "^(boxUnboxInsideCoroutine_InlineInt)\\.kt$"
private const val DOT_NET_COROUTINE_MULTI_MODULE_PATTERN = "^(inlineCrossModule)\\.kt$"

fun main(args: Array<String>) {
    val mainClassName = TestGeneratorUtil.getMainClassName()
    val testRoot = args[0]

    generateTestGroupSuiteWithJUnit5(args, mainClassName) {
        testGroup(testRoot, testDataRoot = "compiler/testData/codegen") {
            testClass<AbstractFirLightTreeBlackBoxCodegenTest> {
                model("box")
                model("boxJvm")
            }

            testClass<AbstractValhallaPrimitivesBlackBoxSmokeTest> {
                model("box")
                model("boxJvm")
            }

            testClass<AbstractValhallaPrimitivesAndFullValueClassesBlackBoxSmokeTest> {
                model("box")
                model("boxJvm")
            }

            testClass<AbstractValhallaAllValuesBlackBoxSmokeTest> {
                model("box")
                model("boxJvm")
            }

            testClass<AbstractFirLightTreeHeaderModeCodegenTest> {
                model("box")
                model("boxJvm")
            }

            testClass<AbstractFirPsiBlackBoxCodegenTest> {
                model("box")
                model("boxJvm")
            }

            testClass<AbstractFirLightTreeDotNetBoxTest> {
                // The fallback .NET stdlib is compiled from source in the same product. Do not
                // select intrinsicConstEvaluationInSources: that test intentionally redeclares
                // the stdlib builtin and is ignored by the other non-JVM backends as well.
                model("box/annotations", pattern = "^(nestedAnnotation|resolveWithLowPriorityAnnotation)\\.kt$")
                model(
                    "box/annotations/instances",
                    pattern = "^(AnnotationInstantiationWithArray|annotationAnnotationParam|annotationEqHc|" +
                            "arrayContentEqAny|multifileEqHc|naNAndZero|withDefaults)\\.kt$",
                )
                model("box/contracts", pattern = "^(constructorArgument|exactlyOnceNotInline|valInWhen)\\.kt$")
                model("box/bridges", pattern = DOT_NET_GENERIC_OBJECT_BRIDGE_PATTERN, recursive = false)
                model(
                    "box/inlineClasses",
                    pattern = "^(boxImplDoesNotExecuteInSecondaryConstructor|boxImplDoesNotExecuteInitBlock|" +
                            "boxImplDoesNotExecuteInitBlockGeneric|boxNullableValueOfInlineClassWithNonNullUnderlyingType|" +
                            "boxNullableValueOfInlineClassWithPrimitiveUnderlyingType|castInsideWhenExpression|" +
                            "boxNullableValueOfInlineClassWithPrimitiveUnderlyingTypeGeneric|" +
                            "boxUnboxOfInlineClassForCapturedVars|bridgeForFunctionReturningInlineClass|" +
                            "bridgesWhenInlineClassImplementsGenericInterface|checkBoxingOnFunctionCalls|" +
                            "checkBoxingOnLocalVariableAssignments|checkBoxingOnLocalVariableAssignmentsGeneric|" +
                            "checkCallingMembersInsideInlineClass|checkCastToInlineClass|checkForInstanceOfInlineClass|" +
                            "checkUnboxingResultFromTypeVariable|correctBoxingForBranchExpressions|" +
                            "correctBoxingForBranchExpressionsGeneric|createInlineClassInArgumentPosition|" +
                            "defaultFunctionsFromAnyForInlineClass|equalityChecksMixedNullability|" +
                            "equalityForBoxesOfNullableValuesOfInlineClass|genericInlineClassSynthMembers|initBlock|" +
                            "inlineClassEqualityShouldUseTotalOrderForFloatingPointData|" +
                            "inlineClassEqualityShouldUseTotalOrderForFloatingPointDataGeneric|inlineClassPropertyReferenceGetAndSet|" +
                            "iterateOverArrayOfInlineClassValues|iterateOverArrayOfInlineClassValuesGeneric|" +
                            "iterateOverListOfInlineClassValues|kt27096_nullablePrimitive|kt27096_nullableReference|nestedInlineClass|" +
                            "nullableWrapperEquality|passInlineClassAsVararg|passInlineClassAsVarargGeneric|" +
                            "passInlineClassWithSpreadOperatorToVarargs|passInlineClassWithSpreadOperatorToVarargsGeneric|" +
                            "referToUnderlyingPropertyOfInlineClass|secondaryConstructorsInsideInlineClassWithPrimitiveCarrierType|" +
                            "secondaryConstructorsWithBody|typeChecksForInlineClasses|useInlineClassesInsideElvisOperator|" +
                            "useInlineClassesInsideElvisOperatorGeneric)\\.kt$",
                    recursive = false,
                )
                model("box/inlineClasses/boxReturnValueOnOverride", pattern = "^(uncastInlineClassToAnyAndBack)\\.kt$")
                model("box/inlineClasses/boxReturnValueInLambda", pattern = "^(boxNullableAny|boxNullableAnyNull)\\.kt$")
                model(
                    "box/inlineClasses/callableReferences",
                    pattern = "^(boundInlineClassMemberFun|constructorWithInlineClassParameters|equalsHashCodeToString)\\.kt$",
                )
                model("box/inlineClasses/contextsAndAccessors", pattern = "^(lambdaInInlineClassFun)\\.kt$")
                model(
                    "box/inlineClasses/defaultParameterValues",
                    pattern = "^(defaultParameterValuesOfInlineClassType|defaultParameterValuesOfInlineClassTypeBoxing|" +
                            "inlineClassSecondaryConstructor)\\.kt$",
                )
                model(
                    "box/inlineClasses/functionNameMangling",
                    pattern = "^(extensionFunctionsDoNotClash|functionsWithDifferentNullabilityDoNotClash|" +
                            "genericFunctionsDoNotClash|mangledFunctionsCanBeOverridden|mangledFunctionsDoNotClash)\\.kt$",
                )
                model("box/inlineClasses/genericUnderlyingValue", pattern = "^(simple)\\.kt$")
                model("box/inlineClasses/hiddenConstructor", pattern = "^(constructorWithDefaultParameters)\\.kt$")
                model("box/inlineClasses/interfaceMethodCalls", pattern = "^(genericInterfaceMethodCall)\\.kt$")
                model("box/ktype")
                model(
                    "box/reflection/typeOf",
                    pattern = "^(arrayOfNullableReified|classes|definitelyNotNullType|inNestedInline|intersectionType|" +
                            "ktype1_anonymousObject|localClass|manyTypeArguments|multiModuleNullCheck|multipleLayers|" +
                            "reifiedAsNestedArgument|typeAliasedType|typeOfCapturedStar)\\.kt$",
                    recursive = false,
                )
                model(
                    "box/reflection/typeOf/nonReifiedTypeParameters",
                    pattern = "^(defaultUpperBound|equalsOnClassParameters|equalsOnFunctionParameters|innerGeneric|" +
                            "insideInlineLambda_class|insideNonInlineLambda_class|recursiveBoundWithInline|" +
                            "recursiveBoundWithoutInline|simpleClassParameter|simpleFunctionParameter|" +
                            "simplePropertyParameter|starProjectionInUpperBound|typeParameterFlags|upperBounds|" +
                            "upperBoundUsesOuterClassParameter)\\.kt$",
                )
                model(
                    "box/reflection/typeOf/noReflect",
                    pattern = "^(typeReferenceEqualsHashCode)\\.kt$",
                )
                model("box/reflection/functions", pattern = "^(genericOverriddenFunction)\\.kt$", recursive = false)
                model("box/reflection/properties", pattern = "^(genericOverriddenProperty)\\.kt$", recursive = false)
                model("box/typealias", pattern = "^(incorrectTypeOfTypealiasForSuspendFunctionalType)\\.kt$")
                model("box/strings", pattern = "^(kt50140|stringPlusOverride)\\.kt$")
                model("box/coroutines", pattern = DOT_NET_COROUTINE_ROOT_PATTERN, recursive = false)
                model("box/coroutines/controlFlow", pattern = DOT_NET_COROUTINE_CONTROL_FLOW_PATTERN, recursive = false)
                model(
                    "box/coroutines/featureIntersection",
                    pattern = DOT_NET_COROUTINE_FEATURE_INTERSECTION_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/intrinsicSemantics",
                    pattern = DOT_NET_COROUTINE_INTRINSIC_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/inlineClasses/direct",
                    pattern = DOT_NET_COROUTINE_VALUE_CLASS_DIRECT_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/inlineClasses/resume",
                    pattern = DOT_NET_COROUTINE_VALUE_CLASS_RESUME_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/inlineClasses/resumeWithException",
                    pattern = DOT_NET_COROUTINE_VALUE_CLASS_EXCEPTION_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/multiModule",
                    pattern = DOT_NET_COROUTINE_MULTI_MODULE_PATTERN,
                    recursive = false,
                )
                model("dotnet/box")
            }

            testClass<AbstractFirPsiDotNetBoxTest> {
                model("box/annotations", pattern = "^(nestedAnnotation|resolveWithLowPriorityAnnotation)\\.kt$")
                model(
                    "box/annotations/instances",
                    pattern = "^(AnnotationInstantiationWithArray|annotationAnnotationParam|annotationEqHc|" +
                            "arrayContentEqAny|multifileEqHc|naNAndZero|withDefaults)\\.kt$",
                )
                model("box/contracts", pattern = "^(constructorArgument|exactlyOnceNotInline|valInWhen)\\.kt$")
                model("box/bridges", pattern = DOT_NET_GENERIC_OBJECT_BRIDGE_PATTERN, recursive = false)
                model(
                    "box/inlineClasses",
                    pattern = "^(boxImplDoesNotExecuteInSecondaryConstructor|boxImplDoesNotExecuteInitBlock|" +
                            "boxImplDoesNotExecuteInitBlockGeneric|boxNullableValueOfInlineClassWithNonNullUnderlyingType|" +
                            "boxNullableValueOfInlineClassWithPrimitiveUnderlyingType|castInsideWhenExpression|" +
                            "boxNullableValueOfInlineClassWithPrimitiveUnderlyingTypeGeneric|" +
                            "boxUnboxOfInlineClassForCapturedVars|bridgeForFunctionReturningInlineClass|" +
                            "bridgesWhenInlineClassImplementsGenericInterface|checkBoxingOnFunctionCalls|" +
                            "checkBoxingOnLocalVariableAssignments|checkBoxingOnLocalVariableAssignmentsGeneric|" +
                            "checkCallingMembersInsideInlineClass|checkCastToInlineClass|checkForInstanceOfInlineClass|" +
                            "checkUnboxingResultFromTypeVariable|correctBoxingForBranchExpressions|" +
                            "correctBoxingForBranchExpressionsGeneric|createInlineClassInArgumentPosition|" +
                            "defaultFunctionsFromAnyForInlineClass|equalityChecksMixedNullability|" +
                            "equalityForBoxesOfNullableValuesOfInlineClass|genericInlineClassSynthMembers|initBlock|" +
                            "inlineClassEqualityShouldUseTotalOrderForFloatingPointData|" +
                            "inlineClassEqualityShouldUseTotalOrderForFloatingPointDataGeneric|inlineClassPropertyReferenceGetAndSet|" +
                            "iterateOverArrayOfInlineClassValues|iterateOverArrayOfInlineClassValuesGeneric|" +
                            "iterateOverListOfInlineClassValues|kt27096_nullablePrimitive|kt27096_nullableReference|nestedInlineClass|" +
                            "nullableWrapperEquality|passInlineClassAsVararg|passInlineClassAsVarargGeneric|" +
                            "passInlineClassWithSpreadOperatorToVarargs|passInlineClassWithSpreadOperatorToVarargsGeneric|" +
                            "referToUnderlyingPropertyOfInlineClass|secondaryConstructorsInsideInlineClassWithPrimitiveCarrierType|" +
                            "secondaryConstructorsWithBody|typeChecksForInlineClasses|useInlineClassesInsideElvisOperator|" +
                            "useInlineClassesInsideElvisOperatorGeneric)\\.kt$",
                    recursive = false,
                )
                model("box/inlineClasses/boxReturnValueOnOverride", pattern = "^(uncastInlineClassToAnyAndBack)\\.kt$")
                model("box/inlineClasses/boxReturnValueInLambda", pattern = "^(boxNullableAny|boxNullableAnyNull)\\.kt$")
                model(
                    "box/inlineClasses/callableReferences",
                    pattern = "^(boundInlineClassMemberFun|constructorWithInlineClassParameters|equalsHashCodeToString)\\.kt$",
                )
                model("box/inlineClasses/contextsAndAccessors", pattern = "^(lambdaInInlineClassFun)\\.kt$")
                model(
                    "box/inlineClasses/defaultParameterValues",
                    pattern = "^(defaultParameterValuesOfInlineClassType|defaultParameterValuesOfInlineClassTypeBoxing|" +
                            "inlineClassSecondaryConstructor)\\.kt$",
                )
                model(
                    "box/inlineClasses/functionNameMangling",
                    pattern = "^(extensionFunctionsDoNotClash|functionsWithDifferentNullabilityDoNotClash|" +
                            "genericFunctionsDoNotClash|mangledFunctionsCanBeOverridden|mangledFunctionsDoNotClash)\\.kt$",
                )
                model("box/inlineClasses/genericUnderlyingValue", pattern = "^(simple)\\.kt$")
                model("box/inlineClasses/hiddenConstructor", pattern = "^(constructorWithDefaultParameters)\\.kt$")
                model("box/inlineClasses/interfaceMethodCalls", pattern = "^(genericInterfaceMethodCall)\\.kt$")
                model("box/ktype")
                model(
                    "box/reflection/typeOf",
                    pattern = "^(arrayOfNullableReified|classes|definitelyNotNullType|inNestedInline|intersectionType|" +
                            "ktype1_anonymousObject|localClass|manyTypeArguments|multiModuleNullCheck|multipleLayers|" +
                            "reifiedAsNestedArgument|typeAliasedType|typeOfCapturedStar)\\.kt$",
                    recursive = false,
                )
                model(
                    "box/reflection/typeOf/nonReifiedTypeParameters",
                    pattern = "^(defaultUpperBound|equalsOnClassParameters|equalsOnFunctionParameters|innerGeneric|" +
                            "insideInlineLambda_class|insideNonInlineLambda_class|recursiveBoundWithInline|" +
                            "recursiveBoundWithoutInline|simpleClassParameter|simpleFunctionParameter|" +
                            "simplePropertyParameter|starProjectionInUpperBound|typeParameterFlags|upperBounds|" +
                            "upperBoundUsesOuterClassParameter)\\.kt$",
                )
                model(
                    "box/reflection/typeOf/noReflect",
                    pattern = "^(typeReferenceEqualsHashCode)\\.kt$",
                )
                model("box/reflection/functions", pattern = "^(genericOverriddenFunction)\\.kt$", recursive = false)
                model("box/reflection/properties", pattern = "^(genericOverriddenProperty)\\.kt$", recursive = false)
                model("box/typealias", pattern = "^(incorrectTypeOfTypealiasForSuspendFunctionalType)\\.kt$")
                model("box/strings", pattern = "^(kt50140|stringPlusOverride)\\.kt$")
                model("box/coroutines", pattern = DOT_NET_COROUTINE_ROOT_PATTERN, recursive = false)
                model("box/coroutines/controlFlow", pattern = DOT_NET_COROUTINE_CONTROL_FLOW_PATTERN, recursive = false)
                model(
                    "box/coroutines/featureIntersection",
                    pattern = DOT_NET_COROUTINE_FEATURE_INTERSECTION_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/intrinsicSemantics",
                    pattern = DOT_NET_COROUTINE_INTRINSIC_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/inlineClasses/direct",
                    pattern = DOT_NET_COROUTINE_VALUE_CLASS_DIRECT_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/inlineClasses/resume",
                    pattern = DOT_NET_COROUTINE_VALUE_CLASS_RESUME_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/inlineClasses/resumeWithException",
                    pattern = DOT_NET_COROUTINE_VALUE_CLASS_EXCEPTION_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/multiModule",
                    pattern = DOT_NET_COROUTINE_MULTI_MODULE_PATTERN,
                    recursive = false,
                )
                model("dotnet/box")
            }

            testClass<AbstractFirLightTreeDotNetFrameworkBoxTest> {
                model("box/annotations", pattern = "^(nestedAnnotation|resolveWithLowPriorityAnnotation)\\.kt$")
                model(
                    "box/annotations/instances",
                    pattern = "^(AnnotationInstantiationWithArray|annotationAnnotationParam|annotationEqHc|" +
                            "arrayContentEqAny|multifileEqHc|naNAndZero|withDefaults)\\.kt$",
                )
                model("box/contracts", pattern = "^(constructorArgument|exactlyOnceNotInline|valInWhen)\\.kt$")
                model("box/bridges", pattern = DOT_NET_GENERIC_OBJECT_BRIDGE_PATTERN, recursive = false)
                model(
                    "box/inlineClasses",
                    pattern = "^(boxImplDoesNotExecuteInSecondaryConstructor|boxImplDoesNotExecuteInitBlock|" +
                            "boxImplDoesNotExecuteInitBlockGeneric|boxNullableValueOfInlineClassWithNonNullUnderlyingType|" +
                            "boxNullableValueOfInlineClassWithPrimitiveUnderlyingType|castInsideWhenExpression|" +
                            "boxNullableValueOfInlineClassWithPrimitiveUnderlyingTypeGeneric|" +
                            "boxUnboxOfInlineClassForCapturedVars|bridgeForFunctionReturningInlineClass|" +
                            "bridgesWhenInlineClassImplementsGenericInterface|checkBoxingOnFunctionCalls|" +
                            "checkBoxingOnLocalVariableAssignments|checkBoxingOnLocalVariableAssignmentsGeneric|" +
                            "checkCallingMembersInsideInlineClass|checkCastToInlineClass|checkForInstanceOfInlineClass|" +
                            "checkUnboxingResultFromTypeVariable|correctBoxingForBranchExpressions|" +
                            "correctBoxingForBranchExpressionsGeneric|createInlineClassInArgumentPosition|" +
                            "defaultFunctionsFromAnyForInlineClass|equalityChecksMixedNullability|" +
                            "equalityForBoxesOfNullableValuesOfInlineClass|genericInlineClassSynthMembers|initBlock|" +
                            "inlineClassEqualityShouldUseTotalOrderForFloatingPointData|" +
                            "inlineClassEqualityShouldUseTotalOrderForFloatingPointDataGeneric|inlineClassPropertyReferenceGetAndSet|" +
                            "iterateOverArrayOfInlineClassValues|iterateOverArrayOfInlineClassValuesGeneric|" +
                            "iterateOverListOfInlineClassValues|kt27096_nullablePrimitive|kt27096_nullableReference|nestedInlineClass|" +
                            "nullableWrapperEquality|passInlineClassAsVararg|passInlineClassAsVarargGeneric|" +
                            "passInlineClassWithSpreadOperatorToVarargs|passInlineClassWithSpreadOperatorToVarargsGeneric|" +
                            "referToUnderlyingPropertyOfInlineClass|secondaryConstructorsInsideInlineClassWithPrimitiveCarrierType|" +
                            "secondaryConstructorsWithBody|typeChecksForInlineClasses|useInlineClassesInsideElvisOperator|" +
                            "useInlineClassesInsideElvisOperatorGeneric)\\.kt$",
                    recursive = false,
                )
                model("box/inlineClasses/boxReturnValueOnOverride", pattern = "^(uncastInlineClassToAnyAndBack)\\.kt$")
                model("box/inlineClasses/boxReturnValueInLambda", pattern = "^(boxNullableAny|boxNullableAnyNull)\\.kt$")
                model(
                    "box/inlineClasses/callableReferences",
                    pattern = "^(boundInlineClassMemberFun|constructorWithInlineClassParameters|equalsHashCodeToString)\\.kt$",
                )
                model("box/inlineClasses/contextsAndAccessors", pattern = "^(lambdaInInlineClassFun)\\.kt$")
                model(
                    "box/inlineClasses/defaultParameterValues",
                    pattern = "^(defaultParameterValuesOfInlineClassType|defaultParameterValuesOfInlineClassTypeBoxing|" +
                            "inlineClassSecondaryConstructor)\\.kt$",
                )
                model(
                    "box/inlineClasses/functionNameMangling",
                    pattern = "^(extensionFunctionsDoNotClash|functionsWithDifferentNullabilityDoNotClash|" +
                            "genericFunctionsDoNotClash|mangledFunctionsCanBeOverridden|mangledFunctionsDoNotClash)\\.kt$",
                )
                model("box/inlineClasses/genericUnderlyingValue", pattern = "^(simple)\\.kt$")
                model("box/inlineClasses/hiddenConstructor", pattern = "^(constructorWithDefaultParameters)\\.kt$")
                model("box/inlineClasses/interfaceMethodCalls", pattern = "^(genericInterfaceMethodCall)\\.kt$")
                model("box/ktype")
                model(
                    "box/reflection/typeOf",
                    pattern = "^(arrayOfNullableReified|classes|definitelyNotNullType|inNestedInline|intersectionType|" +
                            "ktype1_anonymousObject|localClass|manyTypeArguments|multiModuleNullCheck|multipleLayers|" +
                            "reifiedAsNestedArgument|typeAliasedType|typeOfCapturedStar)\\.kt$",
                    recursive = false,
                )
                model(
                    "box/reflection/typeOf/nonReifiedTypeParameters",
                    pattern = "^(defaultUpperBound|equalsOnClassParameters|equalsOnFunctionParameters|innerGeneric|" +
                            "insideInlineLambda_class|insideNonInlineLambda_class|recursiveBoundWithInline|" +
                            "recursiveBoundWithoutInline|simpleClassParameter|simpleFunctionParameter|" +
                            "simplePropertyParameter|starProjectionInUpperBound|typeParameterFlags|upperBounds|" +
                            "upperBoundUsesOuterClassParameter)\\.kt$",
                )
                model(
                    "box/reflection/typeOf/noReflect",
                    pattern = "^(typeReferenceEqualsHashCode)\\.kt$",
                )
                model("box/reflection/functions", pattern = "^(genericOverriddenFunction)\\.kt$", recursive = false)
                model("box/reflection/properties", pattern = "^(genericOverriddenProperty)\\.kt$", recursive = false)
                model("box/typealias", pattern = "^(incorrectTypeOfTypealiasForSuspendFunctionalType)\\.kt$")
                model("box/strings", pattern = "^(kt50140|stringPlusOverride)\\.kt$")
                model("box/coroutines", pattern = DOT_NET_COROUTINE_ROOT_PATTERN, recursive = false)
                model("box/coroutines/controlFlow", pattern = DOT_NET_COROUTINE_CONTROL_FLOW_PATTERN, recursive = false)
                model(
                    "box/coroutines/featureIntersection",
                    pattern = DOT_NET_COROUTINE_FEATURE_INTERSECTION_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/intrinsicSemantics",
                    pattern = DOT_NET_COROUTINE_INTRINSIC_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/inlineClasses/direct",
                    pattern = DOT_NET_COROUTINE_VALUE_CLASS_DIRECT_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/inlineClasses/resume",
                    pattern = DOT_NET_COROUTINE_VALUE_CLASS_RESUME_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/inlineClasses/resumeWithException",
                    pattern = DOT_NET_COROUTINE_VALUE_CLASS_EXCEPTION_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/multiModule",
                    pattern = DOT_NET_COROUTINE_MULTI_MODULE_PATTERN,
                    recursive = false,
                )
                model("dotnet/box")
            }

            testClass<AbstractFirPsiDotNetFrameworkBoxTest> {
                model("box/annotations", pattern = "^(nestedAnnotation|resolveWithLowPriorityAnnotation)\\.kt$")
                model(
                    "box/annotations/instances",
                    pattern = "^(AnnotationInstantiationWithArray|annotationAnnotationParam|annotationEqHc|" +
                            "arrayContentEqAny|multifileEqHc|naNAndZero|withDefaults)\\.kt$",
                )
                model("box/contracts", pattern = "^(constructorArgument|exactlyOnceNotInline|valInWhen)\\.kt$")
                model("box/bridges", pattern = DOT_NET_GENERIC_OBJECT_BRIDGE_PATTERN, recursive = false)
                model(
                    "box/inlineClasses",
                    pattern = "^(boxImplDoesNotExecuteInSecondaryConstructor|boxImplDoesNotExecuteInitBlock|" +
                            "boxImplDoesNotExecuteInitBlockGeneric|boxNullableValueOfInlineClassWithNonNullUnderlyingType|" +
                            "boxNullableValueOfInlineClassWithPrimitiveUnderlyingType|castInsideWhenExpression|" +
                            "boxNullableValueOfInlineClassWithPrimitiveUnderlyingTypeGeneric|" +
                            "boxUnboxOfInlineClassForCapturedVars|bridgeForFunctionReturningInlineClass|" +
                            "bridgesWhenInlineClassImplementsGenericInterface|checkBoxingOnFunctionCalls|" +
                            "checkBoxingOnLocalVariableAssignments|checkBoxingOnLocalVariableAssignmentsGeneric|" +
                            "checkCallingMembersInsideInlineClass|checkCastToInlineClass|checkForInstanceOfInlineClass|" +
                            "checkUnboxingResultFromTypeVariable|correctBoxingForBranchExpressions|" +
                            "correctBoxingForBranchExpressionsGeneric|createInlineClassInArgumentPosition|" +
                            "defaultFunctionsFromAnyForInlineClass|equalityChecksMixedNullability|" +
                            "equalityForBoxesOfNullableValuesOfInlineClass|genericInlineClassSynthMembers|initBlock|" +
                            "inlineClassEqualityShouldUseTotalOrderForFloatingPointData|" +
                            "inlineClassEqualityShouldUseTotalOrderForFloatingPointDataGeneric|inlineClassPropertyReferenceGetAndSet|" +
                            "iterateOverArrayOfInlineClassValues|iterateOverArrayOfInlineClassValuesGeneric|" +
                            "iterateOverListOfInlineClassValues|kt27096_nullablePrimitive|kt27096_nullableReference|nestedInlineClass|" +
                            "nullableWrapperEquality|passInlineClassAsVararg|passInlineClassAsVarargGeneric|" +
                            "passInlineClassWithSpreadOperatorToVarargs|passInlineClassWithSpreadOperatorToVarargsGeneric|" +
                            "referToUnderlyingPropertyOfInlineClass|secondaryConstructorsInsideInlineClassWithPrimitiveCarrierType|" +
                            "secondaryConstructorsWithBody|typeChecksForInlineClasses|useInlineClassesInsideElvisOperator|" +
                            "useInlineClassesInsideElvisOperatorGeneric)\\.kt$",
                    recursive = false,
                )
                model("box/inlineClasses/boxReturnValueOnOverride", pattern = "^(uncastInlineClassToAnyAndBack)\\.kt$")
                model("box/inlineClasses/boxReturnValueInLambda", pattern = "^(boxNullableAny|boxNullableAnyNull)\\.kt$")
                model(
                    "box/inlineClasses/callableReferences",
                    pattern = "^(boundInlineClassMemberFun|constructorWithInlineClassParameters|equalsHashCodeToString)\\.kt$",
                )
                model("box/inlineClasses/contextsAndAccessors", pattern = "^(lambdaInInlineClassFun)\\.kt$")
                model(
                    "box/inlineClasses/defaultParameterValues",
                    pattern = "^(defaultParameterValuesOfInlineClassType|defaultParameterValuesOfInlineClassTypeBoxing|" +
                            "inlineClassSecondaryConstructor)\\.kt$",
                )
                model(
                    "box/inlineClasses/functionNameMangling",
                    pattern = "^(extensionFunctionsDoNotClash|functionsWithDifferentNullabilityDoNotClash|" +
                            "genericFunctionsDoNotClash|mangledFunctionsCanBeOverridden|mangledFunctionsDoNotClash)\\.kt$",
                )
                model("box/inlineClasses/genericUnderlyingValue", pattern = "^(simple)\\.kt$")
                model("box/inlineClasses/hiddenConstructor", pattern = "^(constructorWithDefaultParameters)\\.kt$")
                model("box/inlineClasses/interfaceMethodCalls", pattern = "^(genericInterfaceMethodCall)\\.kt$")
                model("box/ktype")
                model(
                    "box/reflection/typeOf",
                    pattern = "^(arrayOfNullableReified|classes|definitelyNotNullType|inNestedInline|intersectionType|" +
                            "ktype1_anonymousObject|localClass|manyTypeArguments|multiModuleNullCheck|multipleLayers|" +
                            "reifiedAsNestedArgument|typeAliasedType|typeOfCapturedStar)\\.kt$",
                    recursive = false,
                )
                model(
                    "box/reflection/typeOf/nonReifiedTypeParameters",
                    pattern = "^(defaultUpperBound|equalsOnClassParameters|equalsOnFunctionParameters|innerGeneric|" +
                            "insideInlineLambda_class|insideNonInlineLambda_class|recursiveBoundWithInline|" +
                            "recursiveBoundWithoutInline|simpleClassParameter|simpleFunctionParameter|" +
                            "simplePropertyParameter|starProjectionInUpperBound|typeParameterFlags|upperBounds|" +
                            "upperBoundUsesOuterClassParameter)\\.kt$",
                )
                model(
                    "box/reflection/typeOf/noReflect",
                    pattern = "^(typeReferenceEqualsHashCode)\\.kt$",
                )
                model("box/reflection/functions", pattern = "^(genericOverriddenFunction)\\.kt$", recursive = false)
                model("box/reflection/properties", pattern = "^(genericOverriddenProperty)\\.kt$", recursive = false)
                model("box/typealias", pattern = "^(incorrectTypeOfTypealiasForSuspendFunctionalType)\\.kt$")
                model("box/strings", pattern = "^(kt50140|stringPlusOverride)\\.kt$")
                model("box/coroutines", pattern = DOT_NET_COROUTINE_ROOT_PATTERN, recursive = false)
                model("box/coroutines/controlFlow", pattern = DOT_NET_COROUTINE_CONTROL_FLOW_PATTERN, recursive = false)
                model(
                    "box/coroutines/featureIntersection",
                    pattern = DOT_NET_COROUTINE_FEATURE_INTERSECTION_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/intrinsicSemantics",
                    pattern = DOT_NET_COROUTINE_INTRINSIC_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/inlineClasses/direct",
                    pattern = DOT_NET_COROUTINE_VALUE_CLASS_DIRECT_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/inlineClasses/resume",
                    pattern = DOT_NET_COROUTINE_VALUE_CLASS_RESUME_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/inlineClasses/resumeWithException",
                    pattern = DOT_NET_COROUTINE_VALUE_CLASS_EXCEPTION_PATTERN,
                    recursive = false,
                )
                model(
                    "box/coroutines/multiModule",
                    pattern = DOT_NET_COROUTINE_MULTI_MODULE_PATTERN,
                    recursive = false,
                )
                model("dotnet/box")
            }

            testClass<AbstractJvmLightTreeBlackBoxCodegenWithSeparateKmpCompilationTest> {
                model("box/multiplatform/k2")
                model("boxJvm/multiplatform/k2")
            }

            testClass<AbstractReflectionLegacyImplementationTest> {
                model("box/reflection")
                model("boxJvm/reflection")
            }

            testClass<AbstractReflectionLoadMetadataDirectlyTest> {
                model("box/reflection")
                model("boxJvm/reflection")
            }
        }

        testGroup(testRoot, testDataRoot = "compiler/testData") {
            testClass<AbstractFirLightTreeBlackBoxCodegenTest>("FirLightTreeBlackBoxModernJdkCodegenTestGenerated") {
                model("codegen/boxModernJdk")
            }

            testClass<AbstractFirPsiBlackBoxCodegenTest>("FirPsiBlackBoxModernJdkCodegenTestGenerated") {
                model("codegen/boxModernJdk")
            }

            testClass<AbstractFirPsiBlackBoxInlineCodegenTest> {
                model("codegen/boxInline")
            }

            testClass<AbstractFirLightTreeBlackBoxInlineCodegenTest> {
                model("codegen/boxInline")
                model("klib/syntheticAccessors")
            }

            testClass<AbstractFirLightTreeSteppingTest> {
                model("debug/stepping")
            }

            testClass<AbstractFirPsiSteppingTest> {
                model("debug/stepping")
            }

            testClass<AbstractFirLightTreeLocalVariableTest> {
                model("debug/localVariables")
            }

            testClass<AbstractLocalVariableTableTest> {
                model("checkLocalVariablesTable")
            }

            testClass<AbstractFirPsiLocalVariableTest> {
                model("debug/localVariables")
            }

            testClass<AbstractFirPsiBytecodeListingTest> {
                model("codegen/bytecodeListing")
            }

            testClass<AbstractFirLightTreeBytecodeListingTest> {
                model("codegen/bytecodeListing")
            }

            testClass<AbstractFirPsiDotNetIlTextTest> {
                model("codegen/dotnet/ilText")
            }

            testClass<AbstractFirLightTreeDotNetIlTextTest> {
                model("codegen/dotnet/ilText")
            }

            testClass<AbstractFirLightTreeDotNetCrossAssemblerTest> {
                model(
                    "codegen/dotnet/ilText",
                    pattern = "^(annotationValues|genericConstraints|genericFunctions|interfaceDefaultBodiesPortable|" +
                            "mainOverloads|nestedClasses|printlnEscapedString|propertyReferences)\\.kt$",
                    recursive = false,
                )
            }

            testClass<AbstractFirPsiAsmLikeInstructionListingTest> {
                model("codegen/asmLike")
            }

            testClass<AbstractFirLightTreeAsmLikeInstructionListingTest> {
                model("codegen/asmLike")
            }

            testClass<AbstractWriteSignatureTest> {
                model("writeSignature")
            }

            testClass<AbstractWriteFlagsTest> {
                model("writeFlags")
            }
        }

        testGroup(testRoot, testDataRoot = "compiler/testData") {
            testClass<AbstractFirLightTreeJvmIrTextTest> {
                model(
                    "ir/irText",
                    excludeDirs = listOf("declarations/multiplatform/k1")
                )
            }

            testClass<AbstractFirPsiJvmIrTextTest> {
                model(
                    "ir/irText",
                    excludeDirs = listOf("declarations/multiplatform/k1")
                )
            }

            testClass<AbstractFirLightTreeJvmIrSourceRangesTest> {
                model("ir/sourceRanges")
            }

            testClass<AbstractFirPsiJvmIrSourceRangesTest> {
                model("ir/sourceRanges")
            }

            testClass<AbstractFirLightTreeBytecodeTextTest> {
                model("codegen/bytecodeText")
            }

            testClass<AbstractFirPsiBytecodeTextTest> {
                model("codegen/bytecodeText")
            }
        }

        testGroup(testRoot, "compiler/tests-spec/testData") {
            testClass<AbstractFirBlackBoxCodegenTestSpec> {
                model(
                    relativeRootPath = "codegen/box",
                    excludeDirs = listOf("helpers", "templates") + detectDirsWithTestsMapFileOnly("codegen/box"),
                )
            }
        }
    }
}
