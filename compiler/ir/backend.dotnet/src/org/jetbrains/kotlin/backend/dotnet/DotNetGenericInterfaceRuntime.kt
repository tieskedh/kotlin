/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/** Runtime fallback for a foreign implementation which carries no Kotlin semantic capability. */
internal object DotNetGenericInterfaceRuntime {
    fun supportTypeIl(
        coreLibraryReference: String,
        compilerAbiTypeAttributesIl: String,
    ): String {
        val typeType = "class ${coreLibraryReference}System.Type"
        val methodType = "class ${coreLibraryReference}System.Reflection.MethodInfo"
        val entryType = "class Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry"
        val stateType = "class Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchState"
        val tableType =
            "class ${coreLibraryReference}System.Runtime.CompilerServices.ConditionalWeakTable`2" +
                    "<$typeType, $stateType>"
        return """
  .class private auto ansi sealed beforefieldinit GenericInterfaceProducerDispatchEntry
         extends ${coreLibraryReference}System.Object
  {
    .field assembly initonly $typeType 'openDefinition'
    .field assembly initonly string 'methodName'
    .field assembly initonly int32 'unaryResolutionKind'
    .field assembly initonly $typeType 'unaryParameterOpenDefinition'
    .field assembly initonly $methodType 'method'
    .field assembly initonly $entryType 'next'

    .method assembly hidebysig specialname rtspecialname instance void .ctor(
        $typeType 'openDefinition',
        string 'methodName',
        int32 'unaryResolutionKind',
        $typeType 'unaryParameterOpenDefinition',
        $methodType 'method',
        $entryType 'next') cil managed
    {
      .maxstack 2
      ldarg.0
      call instance void ${coreLibraryReference}System.Object::.ctor()
      ldarg.0
      ldarg.1
      stfld $typeType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'openDefinition'
      ldarg.0
      ldarg.2
      stfld string Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'methodName'
      ldarg.0
      ldarg.3
      stfld int32 Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'unaryResolutionKind'
      ldarg.0
      ldarg.s 'unaryParameterOpenDefinition'
      stfld $typeType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'unaryParameterOpenDefinition'
      ldarg.0
      ldarg.s 'method'
      stfld $methodType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'method'
      ldarg.0
      ldarg.s 'next'
      stfld $entryType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'next'
      ret
    }
  }

  .class private auto ansi sealed beforefieldinit GenericInterfaceProducerDispatchState
         extends ${coreLibraryReference}System.Object
  {
    .field assembly $entryType 'head'
    .field assembly initonly $typeType[] 'interfaces'

    .method assembly hidebysig specialname rtspecialname instance void .ctor(
        $typeType 'runtimeType') cil managed
    {
      .maxstack 2
      ldarg.0
      call instance void ${coreLibraryReference}System.Object::.ctor()
      ldarg.0
      ldarg.1
      callvirt instance $typeType[] ${coreLibraryReference}System.Type::GetInterfaces()
      stfld $typeType[] Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchState::'interfaces'
      ret
    }
  }

  .class public abstract sealed auto ansi beforefieldinit GenericInterfaceDispatch
         extends ${coreLibraryReference}System.Object
  {
    $compilerAbiTypeAttributesIl
    .field private static initonly $tableType 'producerMethods'

    .method private hidebysig specialname rtspecialname static void .cctor() cil managed
    {
      .maxstack 1
      newobj instance void $tableType::.ctor()
      stsfld $tableType Kotlin.Runtime.Internal.GenericInterfaceDispatch::'producerMethods'
      ret
    }

    .method private hidebysig static $stateType 'GetProducerState'(
        $typeType 'runtimeType') cil managed
    {
      .maxstack 3
      .locals init ([0] $stateType 'state')
      ldsfld $tableType Kotlin.Runtime.Internal.GenericInterfaceDispatch::'producerMethods'
      call void ${coreLibraryReference}System.Threading.Monitor::Enter(object)
      .try
      {
        ldsfld $tableType Kotlin.Runtime.Internal.GenericInterfaceDispatch::'producerMethods'
        ldarg.0
        ldloca.s 'state'
        callvirt instance bool $tableType::TryGetValue(!0, !1&)
        brtrue.s GIF_StateReadyInTable
        ldarg.0
        newobj instance void $stateType::.ctor($typeType)
        stloc.0
        ldsfld $tableType Kotlin.Runtime.Internal.GenericInterfaceDispatch::'producerMethods'
        ldarg.0
        ldloc.0
        callvirt instance void $tableType::Add(!0, !1)
      GIF_StateReadyInTable:
        leave.s GIF_StateReady
      }
      finally
      {
        ldsfld $tableType Kotlin.Runtime.Internal.GenericInterfaceDispatch::'producerMethods'
        call void ${coreLibraryReference}System.Threading.Monitor::Exit(object)
        endfinally
      }
    GIF_StateReady:
      ldloc.0
      ret
    }

    .method public hidebysig static bool 'IsOpenGenericInterfaceInstance'(
        object 'instance',
        $typeType 'openDefinition') cil managed
    {
      .maxstack 2
      .locals init (
        [0] $typeType[] 'interfaces',
        [1] int32 'index',
        [2] $typeType 'candidate'
      )
      ldarg.0
      brtrue.s GIF_TestInstanceReady
      ldc.i4.0
      ret
    GIF_TestInstanceReady:
      ldarg.0
      callvirt instance $typeType ${coreLibraryReference}System.Object::GetType()
      call $stateType Kotlin.Runtime.Internal.GenericInterfaceDispatch::'GetProducerState'($typeType)
      ldfld $typeType[] Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchState::'interfaces'
      stloc.0
      ldc.i4.0
      stloc.1
    GIF_TestNext:
      ldloc.1
      ldloc.0
      ldlen
      conv.i4
      bge.s GIF_TestMiss
      ldloc.0
      ldloc.1
      ldelem.ref
      stloc.2
      ldloc.2
      callvirt instance bool ${coreLibraryReference}System.Type::get_IsGenericType()
      brfalse.s GIF_TestContinue
      ldloc.2
      callvirt instance $typeType ${coreLibraryReference}System.Type::GetGenericTypeDefinition()
      ldarg.1
      call bool ${coreLibraryReference}System.Type::op_Equality($typeType, $typeType)
      brfalse.s GIF_TestContinue
      ldc.i4.1
      ret
    GIF_TestContinue:
      ldloc.1
      ldc.i4.1
      add
      stloc.1
      br.s GIF_TestNext
    GIF_TestMiss:
      ldc.i4.0
      ret
    }

    .method private hidebysig static bool 'IsKotlinGenericConstructionAssignable'(
        $typeType 'source',
        $typeType 'target') cil managed
    {
      .maxstack 4
      .locals init (
        [0] $typeType 'sourceDefinition',
        [1] $typeType 'targetDefinition',
        [2] $typeType[] 'sourceArguments',
        [3] $typeType[] 'targetArguments',
        [4] $typeType[] 'parameters',
        [5] int32 'index',
        [6] int32 'variance'
      )
      ldarg.0
      ldarg.1
      call bool ${coreLibraryReference}System.Type::op_Equality($typeType, $typeType)
      brfalse.s GIF_AssignableNominal
      ldc.i4.1
      ret
    GIF_AssignableNominal:
      ldarg.1
      ldarg.0
      callvirt instance bool ${coreLibraryReference}System.Type::IsAssignableFrom($typeType)
      brfalse.s GIF_AssignableStructural
      ldc.i4.1
      ret
    GIF_AssignableStructural:
      ldarg.0
      callvirt instance bool ${coreLibraryReference}System.Type::get_IsGenericType()
      brfalse GIF_AssignableFalse
      ldarg.1
      callvirt instance bool ${coreLibraryReference}System.Type::get_IsGenericType()
      brfalse GIF_AssignableFalse
      ldarg.0
      callvirt instance $typeType ${coreLibraryReference}System.Type::GetGenericTypeDefinition()
      stloc.0
      ldarg.1
      callvirt instance $typeType ${coreLibraryReference}System.Type::GetGenericTypeDefinition()
      stloc.1
      ldloc.0
      ldloc.1
      call bool ${coreLibraryReference}System.Type::op_Equality($typeType, $typeType)
      brfalse GIF_AssignableFalse
      ldarg.0
      callvirt instance $typeType[] ${coreLibraryReference}System.Type::GetGenericArguments()
      stloc.2
      ldarg.1
      callvirt instance $typeType[] ${coreLibraryReference}System.Type::GetGenericArguments()
      stloc.3
      ldloc.1
      callvirt instance $typeType[] ${coreLibraryReference}System.Type::GetGenericArguments()
      stloc.s 4
      ldloc.2
      ldlen
      conv.i4
      ldloc.3
      ldlen
      conv.i4
      bne.un GIF_AssignableFalse
      ldloc.2
      ldlen
      conv.i4
      ldloc.s 4
      ldlen
      conv.i4
      bne.un GIF_AssignableFalse
      ldc.i4.0
      stloc.s 5
    GIF_AssignableNext:
      ldloc.s 5
      ldloc.2
      ldlen
      conv.i4
      bge.s GIF_AssignableTrue
      ldloc.s 4
      ldloc.s 5
      ldelem.ref
      callvirt instance valuetype ${coreLibraryReference}System.Reflection.GenericParameterAttributes ${coreLibraryReference}System.Type::get_GenericParameterAttributes()
      conv.i4
      ldc.i4.3
      and
      stloc.s 6
      ldloc.s 6
      ldc.i4.1
      beq.s GIF_AssignableCovariant
      ldloc.s 6
      ldc.i4.2
      beq.s GIF_AssignableContravariant
      ldloc.2
      ldloc.s 5
      ldelem.ref
      ldloc.3
      ldloc.s 5
      ldelem.ref
      call bool ${coreLibraryReference}System.Type::op_Equality($typeType, $typeType)
      brfalse.s GIF_AssignableFalse
      br.s GIF_AssignableContinue
    GIF_AssignableCovariant:
      ldloc.2
      ldloc.s 5
      ldelem.ref
      ldloc.3
      ldloc.s 5
      ldelem.ref
      call bool Kotlin.Runtime.Internal.GenericInterfaceDispatch::'IsKotlinGenericConstructionAssignable'($typeType, $typeType)
      brfalse.s GIF_AssignableFalse
      br.s GIF_AssignableContinue
    GIF_AssignableContravariant:
      ldloc.3
      ldloc.s 5
      ldelem.ref
      ldloc.2
      ldloc.s 5
      ldelem.ref
      call bool Kotlin.Runtime.Internal.GenericInterfaceDispatch::'IsKotlinGenericConstructionAssignable'($typeType, $typeType)
      brfalse.s GIF_AssignableFalse
    GIF_AssignableContinue:
      ldloc.s 5
      ldc.i4.1
      add
      stloc.s 5
      br.s GIF_AssignableNext
    GIF_AssignableTrue:
      ldc.i4.1
      ret
    GIF_AssignableFalse:
      ldc.i4.0
      ret
    }

    .method public hidebysig static bool 'IsCompatibleGenericOwnerInstance'(
        object 'instance',
        $typeType 'requestedConstruction') cil managed
    {
      .maxstack 3
      .locals init (
        [0] $typeType[] 'interfaces',
        [1] int32 'index',
        [2] $typeType 'candidate',
        [3] $typeType 'requestedDefinition',
        [4] $typeType 'runtimeClass'
      )
      ldarg.0
      brfalse GIF_CompatibleMiss
      ldarg.1
      brfalse GIF_CompatibleMiss
      ldarg.1
      callvirt instance bool ${coreLibraryReference}System.Type::get_IsGenericType()
      brfalse GIF_CompatibleMiss
      ldarg.1
      callvirt instance $typeType ${coreLibraryReference}System.Type::GetGenericTypeDefinition()
      stloc.3
      ldarg.0
      callvirt instance $typeType ${coreLibraryReference}System.Object::GetType()
      stloc.s 4
    GIF_CompatibleClassNext:
      ldloc.s 4
      brfalse GIF_CompatibleInterfaces
      ldloc.s 4
      stloc.2
      ldloc.2
      callvirt instance bool ${coreLibraryReference}System.Type::get_IsGenericType()
      brfalse.s GIF_CompatibleClassContinue
      ldloc.2
      callvirt instance $typeType ${coreLibraryReference}System.Type::GetGenericTypeDefinition()
      ldloc.3
      call bool ${coreLibraryReference}System.Type::op_Equality($typeType, $typeType)
      brfalse.s GIF_CompatibleClassContinue
      ldloc.2
      ldarg.1
      call bool Kotlin.Runtime.Internal.GenericInterfaceDispatch::'IsKotlinGenericConstructionAssignable'($typeType, $typeType)
      brtrue GIF_CompatibleHit
    GIF_CompatibleClassContinue:
      ldloc.2
      callvirt instance $typeType ${coreLibraryReference}System.Type::get_BaseType()
      stloc.s 4
      br GIF_CompatibleClassNext
    GIF_CompatibleInterfaces:
      ldarg.0
      callvirt instance $typeType ${coreLibraryReference}System.Object::GetType()
      call $stateType Kotlin.Runtime.Internal.GenericInterfaceDispatch::'GetProducerState'($typeType)
      ldfld $typeType[] Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchState::'interfaces'
      stloc.0
      ldc.i4.0
      stloc.1
    GIF_CompatibleNext:
      ldloc.1
      ldloc.0
      ldlen
      conv.i4
      bge GIF_CompatibleMiss
      ldloc.0
      ldloc.1
      ldelem.ref
      stloc.2
      ldloc.2
      callvirt instance bool ${coreLibraryReference}System.Type::get_IsGenericType()
      brfalse.s GIF_CompatibleContinue
      ldloc.2
      callvirt instance $typeType ${coreLibraryReference}System.Type::GetGenericTypeDefinition()
      ldloc.3
      call bool ${coreLibraryReference}System.Type::op_Equality($typeType, $typeType)
      brfalse.s GIF_CompatibleContinue
      ldloc.2
      ldarg.1
      call bool Kotlin.Runtime.Internal.GenericInterfaceDispatch::'IsKotlinGenericConstructionAssignable'($typeType, $typeType)
      brfalse.s GIF_CompatibleContinue
    GIF_CompatibleHit:
      ldc.i4.1
      ret
    GIF_CompatibleContinue:
      ldloc.1
      ldc.i4.1
      add
      stloc.1
      br GIF_CompatibleNext
    GIF_CompatibleMiss:
      ldc.i4.0
      ret
    }

    .method private hidebysig static object 'InvokeUniqueMemberCore'(
        object 'instance',
        $typeType 'openDefinition',
        string 'methodName',
        object[] 'arguments',
        int32 'unaryResolutionKind',
        $typeType 'unaryParameterOpenDefinition') cil managed
    {
      .maxstack 7
      .locals init (
        [0] $typeType[] 'interfaces',
        [1] int32 'index',
        [2] $typeType 'selected',
        [3] $typeType 'candidate',
        [4] $methodType 'method',
        [5] class ${coreLibraryReference}System.Reflection.TargetInvocationException 'invocationException',
        [6] object 'result',
        [7] $typeType 'runtimeType',
        [8] $stateType 'state',
        [9] $entryType 'entry',
        [10] $typeType 'expectedParameterType',
        [11] object 'fixedBarrierValue'
      )
      ldarg.0
      brtrue.s GIF_InstanceReady
      newobj instance void ${coreLibraryReference}System.NullReferenceException::.ctor()
      throw
    GIF_InstanceReady:
      ldarg.0
      callvirt instance $typeType ${coreLibraryReference}System.Object::GetType()
      stloc.s 7
      ldloc.s 7
      call $stateType Kotlin.Runtime.Internal.GenericInterfaceDispatch::'GetProducerState'($typeType)
      stloc.s 8
      ldloc.s 8
      call void ${coreLibraryReference}System.Threading.Monitor::Enter(object)
      .try
      {
        ldloc.s 8
        ldfld $entryType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchState::'head'
        stloc.s 9
      GIF_CacheNext:
        ldloc.s 9
        brfalse.s GIF_CacheMiss
        ldloc.s 9
        ldfld $typeType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'openDefinition'
        ldarg.1
        call bool ${coreLibraryReference}System.Type::op_Equality($typeType, $typeType)
        brfalse.s GIF_CacheContinue
        ldloc.s 9
        ldfld string Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'methodName'
        ldarg.2
        call bool ${coreLibraryReference}System.String::op_Equality(string, string)
        brfalse.s GIF_CacheContinue
        ldloc.s 9
        ldfld int32 Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'unaryResolutionKind'
        ldarg.s 'unaryResolutionKind'
        bne.un.s GIF_CacheContinue
        ldloc.s 9
        ldfld $typeType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'unaryParameterOpenDefinition'
        ldarg.s 'unaryParameterOpenDefinition'
        call bool ${coreLibraryReference}System.Type::op_Equality($typeType, $typeType)
        brfalse.s GIF_CacheContinue
        ldloc.s 9
        ldfld $methodType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'method'
        stloc.s 4
        leave GIF_MethodReady
      GIF_CacheContinue:
        ldloc.s 9
        ldfld $entryType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'next'
        stloc.s 9
        br.s GIF_CacheNext

      GIF_CacheMiss:
        ldloc.s 8
        ldfld $typeType[] Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchState::'interfaces'
        stloc.0
        ldc.i4.0
        stloc.1
        ldnull
        stloc.2
    GIF_Next:
        ldloc.1
        ldloc.0
        ldlen
        conv.i4
        bge.s GIF_SearchComplete
        ldloc.0
        ldloc.1
        ldelem.ref
        stloc.3
        ldloc.3
        callvirt instance bool ${coreLibraryReference}System.Type::get_IsGenericType()
        brfalse.s GIF_Continue
        ldloc.3
        callvirt instance $typeType ${coreLibraryReference}System.Type::GetGenericTypeDefinition()
        ldarg.1
        call bool ${coreLibraryReference}System.Type::op_Equality($typeType, $typeType)
        brfalse.s GIF_Continue
        ldloc.2
        brfalse.s GIF_Select
        ldloc.2
        ldloc.3
        call bool ${coreLibraryReference}System.Type::op_Equality($typeType, $typeType)
        brtrue.s GIF_Continue
        ldstr "A foreign Kotlin generic-interface view has multiple CLR constructions"
        newobj instance void ${coreLibraryReference}System.InvalidOperationException::.ctor(string)
        throw
    GIF_Select:
        ldloc.3
        stloc.2
    GIF_Continue:
        ldloc.1
        ldc.i4.1
        add
        stloc.1
        br.s GIF_Next
    GIF_SearchComplete:
        ldloc.2
        brtrue.s GIF_ConstructionReady
        ldstr "The value does not implement the required CLR generic interface"
        newobj instance void ${coreLibraryReference}System.InvalidCastException::.ctor(string)
        throw
    GIF_ConstructionReady:
        ldarg.s 'unaryResolutionKind'
        brtrue.s GIF_ResolveConcreteMethod
        ldloc.2
        ldarg.2
        callvirt instance $methodType ${coreLibraryReference}System.Type::GetMethod(string)
        stloc.s 4
        br.s GIF_MethodResolved
    GIF_ResolveConcreteMethod:
        ldloc.s 7
        ldarg.2
        ldc.i4.1
        newarr ${coreLibraryReference}System.Type
        dup
        ldc.i4.0
        ldarg.s 'unaryResolutionKind'
        ldc.i4.1
        beq.s GIF_ConcreteInterfaceParameter
        ldarg.s 'unaryResolutionKind'
        ldc.i4.3
        beq.s GIF_ConstructedInterfaceParameter
        ldloc.2
        callvirt instance $typeType[] ${coreLibraryReference}System.Type::GetGenericArguments()
        ldc.i4.0
        ldelem.ref
        br.s GIF_ConcreteParameterReady
    GIF_ConcreteInterfaceParameter:
        ldloc.2
        br.s GIF_ConcreteParameterReady
    GIF_ConstructedInterfaceParameter:
        ldarg.s 'unaryParameterOpenDefinition'
        ldloc.2
        callvirt instance $typeType[] ${coreLibraryReference}System.Type::GetGenericArguments()
        callvirt instance $typeType ${coreLibraryReference}System.Type::MakeGenericType($typeType[])
    GIF_ConcreteParameterReady:
        stelem.ref
        callvirt instance $methodType ${coreLibraryReference}System.Type::GetMethod(
            string,
            $typeType[])
        stloc.s 4
    GIF_MethodResolved:
        ldloc.s 4
        brtrue.s GIF_CacheStore
        ldstr "The selected CLR generic implementation has no unique required method"
        newobj instance void ${coreLibraryReference}System.MissingMethodException::.ctor(string)
        throw
      GIF_CacheStore:
        ldloc.s 8
        ldarg.1
        ldarg.2
        ldarg.s 'unaryResolutionKind'
        ldarg.s 'unaryParameterOpenDefinition'
        ldloc.s 4
        ldloc.s 8
        ldfld $entryType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchState::'head'
        newobj instance void $entryType::.ctor(
            $typeType, string, int32, $typeType, $methodType, $entryType)
        stfld $entryType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchState::'head'
        leave.s GIF_MethodReady
      }
      finally
      {
        ldloc.s 8
        call void ${coreLibraryReference}System.Threading.Monitor::Exit(object)
        endfinally
      }

    GIF_MethodReady:
      ldarg.s 'unaryResolutionKind'
      ldc.i4.2
      beq.s GIF_CheckFixedBarrier
      ldarg.s 'unaryResolutionKind'
      ldc.i4.3
      bne.un.s GIF_Invoke
      ldloc.s 4
      callvirt instance class ${coreLibraryReference}System.Reflection.ParameterInfo[] ${coreLibraryReference}System.Reflection.MethodBase::GetParameters()
      ldc.i4.0
      ldelem.ref
      callvirt instance $typeType ${coreLibraryReference}System.Reflection.ParameterInfo::get_ParameterType()
      ldarg.3
      ldc.i4.0
      ldelem.ref
      callvirt instance bool ${coreLibraryReference}System.Type::IsInstanceOfType(object)
      brtrue.s GIF_Invoke
      ldarg.0
      ldarg.1
      ldarg.3
      ldc.i4.0
      ldelem.ref
      call object Kotlin.Runtime.Internal.GenericInterfaceDispatch::'InvokeCollectionContainsAllFallback'(
          object, $typeType, object)
      ret
    GIF_CheckFixedBarrier:
      ldloc.s 4
      callvirt instance class ${coreLibraryReference}System.Reflection.ParameterInfo[] ${coreLibraryReference}System.Reflection.MethodBase::GetParameters()
      ldc.i4.0
      ldelem.ref
      callvirt instance $typeType ${coreLibraryReference}System.Reflection.ParameterInfo::get_ParameterType()
      stloc.s 10
      ldarg.3
      ldc.i4.0
      ldelem.ref
      stloc.s 11
      ldloc.s 11
      brtrue.s GIF_TestFixedBarrierValue
      ldloc.s 10
      callvirt instance bool ${coreLibraryReference}System.Type::get_IsValueType()
      brfalse.s GIF_Invoke
      ldloc.s 10
      call $typeType ${coreLibraryReference}System.Nullable::GetUnderlyingType($typeType)
      brtrue.s GIF_Invoke
      br.s GIF_FixedBarrierFallback
    GIF_TestFixedBarrierValue:
      ldloc.s 10
      ldloc.s 11
      callvirt instance bool ${coreLibraryReference}System.Type::IsInstanceOfType(object)
      brtrue.s GIF_Invoke
    GIF_FixedBarrierFallback:
      ldc.i4.0
      box ${coreLibraryReference}System.Boolean
      ret
    GIF_Invoke:
      nop
      .try
      {
        ldloc.s 4
        ldarg.0
        ldarg.3
        callvirt instance object ${coreLibraryReference}System.Reflection.MethodBase::Invoke(object, object[])
        stloc.s 6
        leave.s GIF_Return
      }
      catch ${coreLibraryReference}System.Reflection.TargetInvocationException
      {
        stloc.s 5
        ldloc.s 5
        callvirt instance class ${coreLibraryReference}System.Exception ${coreLibraryReference}System.Exception::get_InnerException()
        dup
        brtrue.s GIF_RethrowInner
        pop
        rethrow
      GIF_RethrowInner:
        call class ${coreLibraryReference}System.Runtime.ExceptionServices.ExceptionDispatchInfo ${coreLibraryReference}System.Runtime.ExceptionServices.ExceptionDispatchInfo::Capture(class ${coreLibraryReference}System.Exception)
        callvirt instance void ${coreLibraryReference}System.Runtime.ExceptionServices.ExceptionDispatchInfo::Throw()
        ldnull
        stloc.s 6
        leave.s GIF_Return
      }
    GIF_Return:
      ldloc.s 6
      ret
    }

    // A covariant natural Collection<T>/Set<T> cannot expose its input methods directly. A
    // foreign implementation may nevertheless provide ordinary public Contains(T) and
    // ContainsAll(Collection<T>) methods. Preserve that exact method when the argument has the
    // same physical construction; otherwise evaluate the Kotlin containsAll contract element by
    // element without requiring the foreign class to implement a compiler-only capability.
    .method private hidebysig static object 'InvokeCollectionContainsAllFallback'(
        object 'instance',
        $typeType 'openDefinition',
        object 'elements') cil managed
    {
      .maxstack 8
      .locals init (
        [0] object 'iterator',
        [1] object[] 'predicateArguments'
      )
      ldarg.2
      ldtoken 'Kotlin.Collections.Iterable`1'
      call $typeType ${coreLibraryReference}System.Type::GetTypeFromHandle(
          valuetype ${coreLibraryReference}System.RuntimeTypeHandle)
      ldstr "GetIterator"
      ldnull
      ldc.i4.0
      ldnull
      call object Kotlin.Runtime.Internal.GenericInterfaceDispatch::'InvokeUniqueMemberCore'(
          object, $typeType, string, object[], int32, $typeType)
      stloc.0
    GIF_ContainsAllNext:
      ldloc.0
      ldtoken 'Kotlin.Collections.Iterator`1'
      call $typeType ${coreLibraryReference}System.Type::GetTypeFromHandle(
          valuetype ${coreLibraryReference}System.RuntimeTypeHandle)
      ldstr "HasNext"
      ldnull
      ldc.i4.0
      ldnull
      call object Kotlin.Runtime.Internal.GenericInterfaceDispatch::'InvokeUniqueMemberCore'(
          object, $typeType, string, object[], int32, $typeType)
      unbox.any ${coreLibraryReference}System.Boolean
      brfalse.s GIF_ContainsAllTrue
      ldc.i4.1
      newarr ${coreLibraryReference}System.Object
      stloc.1
      ldloc.1
      ldc.i4.0
      ldloc.0
      ldtoken 'Kotlin.Collections.Iterator`1'
      call $typeType ${coreLibraryReference}System.Type::GetTypeFromHandle(
          valuetype ${coreLibraryReference}System.RuntimeTypeHandle)
      ldstr "Next"
      ldnull
      ldc.i4.0
      ldnull
      call object Kotlin.Runtime.Internal.GenericInterfaceDispatch::'InvokeUniqueMemberCore'(
          object, $typeType, string, object[], int32, $typeType)
      stelem.ref
      ldarg.0
      ldarg.1
      ldstr "Contains"
      ldloc.1
      ldc.i4.2
      ldnull
      call object Kotlin.Runtime.Internal.GenericInterfaceDispatch::'InvokeUniqueMemberCore'(
          object, $typeType, string, object[], int32, $typeType)
      unbox.any ${coreLibraryReference}System.Boolean
      brtrue.s GIF_ContainsAllNext
      ldc.i4.0
      box ${coreLibraryReference}System.Boolean
      ret
    GIF_ContainsAllTrue:
      ldc.i4.1
      box ${coreLibraryReference}System.Boolean
      ret
    }

    .method public hidebysig static object 'InvokeUniqueMember'(
        object 'instance',
        $typeType 'openDefinition',
        string 'methodName',
        object[] 'arguments') cil managed
    {
      .maxstack 6
      ldarg.0
      ldarg.1
      ldarg.2
      ldarg.3
      ldc.i4.0
      ldnull
      call object Kotlin.Runtime.Internal.GenericInterfaceDispatch::'InvokeUniqueMemberCore'(
          object,
          $typeType,
          string,
          object[],
          int32,
          $typeType)
      ret
    }

    .method public hidebysig static object 'InvokeUniqueConcreteUnaryMember'(
        object 'instance',
        $typeType 'openDefinition',
        string 'methodName',
        object[] 'arguments') cil managed
    {
      .maxstack 6
      ldarg.0
      ldarg.1
      ldarg.2
      ldarg.3
      ldc.i4.1
      ldnull
      call object Kotlin.Runtime.Internal.GenericInterfaceDispatch::'InvokeUniqueMemberCore'(
          object,
          $typeType,
          string,
          object[],
          int32,
          $typeType)
      ret
    }

    .method public hidebysig static object 'InvokeUniqueTypeArgumentUnaryMemberWithFalseBarrier'(
        object 'instance',
        $typeType 'openDefinition',
        string 'methodName',
        object[] 'arguments') cil managed
    {
      .maxstack 6
      ldarg.0
      ldarg.1
      ldarg.2
      ldarg.3
      ldc.i4.2
      ldnull
      call object Kotlin.Runtime.Internal.GenericInterfaceDispatch::'InvokeUniqueMemberCore'(
          object,
          $typeType,
          string,
          object[],
          int32,
          $typeType)
      ret
    }

    .method public hidebysig static object 'InvokeUniqueCollectionContainsAll'(
        object 'instance',
        $typeType 'openDefinition',
        $typeType 'parameterOpenDefinition',
        string 'methodName',
        object[] 'arguments') cil managed
    {
      .maxstack 6
      ldarg.0
      ldarg.1
      ldarg.3
      ldarg.s 'arguments'
      ldc.i4.3
      ldarg.2
      call object Kotlin.Runtime.Internal.GenericInterfaceDispatch::'InvokeUniqueMemberCore'(
          object,
          $typeType,
          string,
          object[],
          int32,
          $typeType)
      ret
    }

    .method public hidebysig static object 'InvokeUniqueProducer'(
        object 'instance',
        $typeType 'openDefinition',
        string 'methodName') cil managed
    {
      .maxstack 4
      ldarg.0
      ldarg.1
      ldarg.2
      ldnull
      call object Kotlin.Runtime.Internal.GenericInterfaceDispatch::'InvokeUniqueMember'(
          object,
          $typeType,
          string,
          object[])
      ret
    }
  }
        """.trimIndent()
    }

    fun invokeUniqueProducerCallInstruction(coreLibraryReference: String): String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.GenericInterfaceDispatch".toIlIdentifier()}::" +
                "${"InvokeUniqueProducer".toIlIdentifier()}(" +
                "object, class ${coreLibraryReference}System.Type, string)"

    fun invokeUniqueMemberCallInstruction(coreLibraryReference: String): String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.GenericInterfaceDispatch".toIlIdentifier()}::" +
                "${"InvokeUniqueMember".toIlIdentifier()}(" +
                "object, class ${coreLibraryReference}System.Type, string, object[])"

    fun invokeUniqueConcreteUnaryMemberCallInstruction(coreLibraryReference: String): String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.GenericInterfaceDispatch".toIlIdentifier()}::" +
                "${"InvokeUniqueConcreteUnaryMember".toIlIdentifier()}(" +
                "object, class ${coreLibraryReference}System.Type, string, object[])"

    fun invokeUniqueTypeArgumentUnaryMemberWithFalseBarrierCallInstruction(
        coreLibraryReference: String,
    ): String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.GenericInterfaceDispatch".toIlIdentifier()}::" +
                "${"InvokeUniqueTypeArgumentUnaryMemberWithFalseBarrier".toIlIdentifier()}(" +
                "object, class ${coreLibraryReference}System.Type, string, object[])"

    fun invokeUniqueCollectionContainsAllCallInstruction(coreLibraryReference: String): String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.GenericInterfaceDispatch".toIlIdentifier()}::" +
                "${"InvokeUniqueCollectionContainsAll".toIlIdentifier()}(" +
                "object, class ${coreLibraryReference}System.Type, " +
                "class ${coreLibraryReference}System.Type, string, object[])"

    fun isOpenGenericInterfaceInstanceCallInstruction(coreLibraryReference: String): String =
        "call bool [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.GenericInterfaceDispatch".toIlIdentifier()}::" +
                "${"IsOpenGenericInterfaceInstance".toIlIdentifier()}(" +
                "object, class ${coreLibraryReference}System.Type)"

    fun isCompatibleGenericOwnerInstanceCallInstruction(coreLibraryReference: String): String =
        "call bool [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.GenericInterfaceDispatch".toIlIdentifier()}::" +
                "${"IsCompatibleGenericOwnerInstance".toIlIdentifier()}(" +
                "object, class ${coreLibraryReference}System.Type)"
}
