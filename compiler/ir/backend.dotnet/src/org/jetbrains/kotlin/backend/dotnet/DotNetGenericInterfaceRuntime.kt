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
    .field assembly initonly $methodType 'method'
    .field assembly initonly $entryType 'next'

    .method assembly hidebysig specialname rtspecialname instance void .ctor(
        $typeType 'openDefinition',
        string 'methodName',
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

    .method assembly hidebysig specialname rtspecialname instance void .ctor() cil managed
    {
      .maxstack 1
      ldarg.0
      call instance void ${coreLibraryReference}System.Object::.ctor()
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

    .method public hidebysig static object 'InvokeUniqueProducer'(
        object 'instance',
        $typeType 'openDefinition',
        string 'methodName') cil managed
    {
      .maxstack 5
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
        [9] $entryType 'entry'
      )
      ldarg.0
      brtrue.s GIF_InstanceReady
      newobj instance void ${coreLibraryReference}System.NullReferenceException::.ctor()
      throw
    GIF_InstanceReady:
      ldarg.0
      callvirt instance $typeType ${coreLibraryReference}System.Object::GetType()
      stloc.s 7

      ldsfld $tableType Kotlin.Runtime.Internal.GenericInterfaceDispatch::'producerMethods'
      call void ${coreLibraryReference}System.Threading.Monitor::Enter(object)
      .try
      {
        ldsfld $tableType Kotlin.Runtime.Internal.GenericInterfaceDispatch::'producerMethods'
        ldloc.s 7
        ldloca.s 'state'
        callvirt instance bool $tableType::TryGetValue(!0, !1&)
        brtrue.s GIF_StateReadyInTable
        newobj instance void $stateType::.ctor()
        stloc.s 8
        ldsfld $tableType Kotlin.Runtime.Internal.GenericInterfaceDispatch::'producerMethods'
        ldloc.s 7
        ldloc.s 8
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
        ldfld $methodType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'method'
        stloc.s 4
        leave GIF_MethodReady
      GIF_CacheContinue:
        ldloc.s 9
        ldfld $entryType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchEntry::'next'
        stloc.s 9
        br.s GIF_CacheNext

      GIF_CacheMiss:
        ldloc.s 7
        callvirt instance $typeType[] ${coreLibraryReference}System.Type::GetInterfaces()
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
        ldloc.2
        ldarg.2
        callvirt instance $methodType ${coreLibraryReference}System.Type::GetMethod(string)
        stloc.s 4
        ldloc.s 4
        brtrue.s GIF_CacheStore
        ldstr "The selected CLR generic interface has no required method"
        newobj instance void ${coreLibraryReference}System.MissingMethodException::.ctor(string)
        throw
      GIF_CacheStore:
        ldloc.s 8
        ldarg.1
        ldarg.2
        ldloc.s 4
        ldloc.s 8
        ldfld $entryType Kotlin.Runtime.Internal.GenericInterfaceProducerDispatchState::'head'
        newobj instance void $entryType::.ctor($typeType, string, $methodType, $entryType)
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
      .try
      {
        ldloc.s 4
        ldarg.0
        ldnull
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
  }
        """.trimIndent()
    }

    fun invokeUniqueProducerCallInstruction(coreLibraryReference: String): String =
        "call object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${"Kotlin.Runtime.Internal.GenericInterfaceDispatch".toIlIdentifier()}::" +
                "${"InvokeUniqueProducer".toIlIdentifier()}(" +
                "object, class ${coreLibraryReference}System.Type, string)"
}
