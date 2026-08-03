/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/**
 * Physical exception identities and the identity-associated Common Throwable state.
 *
 * Kotlin values remain their original System.Exception objects. The ConditionalWeakTable key is
 * that exact reference; its value deliberately has no owner field, so the associated state cannot
 * keep an otherwise unreachable exception alive. Suppressed snapshots are immutable CLR vectors
 * wrapped by Kotlin.Stdlib at the public List boundary.
 */
internal object DotNetThrowableRuntime {
    fun exceptionTypesIl(coreLibraryReference: String): String = """
          .class public auto ansi beforefieldinit ConcurrentModificationException
                 extends Kotlin.RuntimeException
          {
${runtimeExceptionConstructorsIl("ConcurrentModificationException", coreLibraryReference)}
          }

          .class public auto ansi sealed beforefieldinit UninitializedPropertyAccessException
                 extends Kotlin.RuntimeException
          {
${runtimeExceptionConstructorsIl("UninitializedPropertyAccessException", coreLibraryReference)}
          }

          .class public auto ansi beforefieldinit AssertionError
                 extends Kotlin.Error
          {
            .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 1
              ldarg.0
              call instance void Kotlin.Error::.ctor()
              ret
            }

            // The extra String overload follows the mature JS/Wasm actual surface.
            .method public hidebysig specialname rtspecialname instance void .ctor(string 'message') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              call instance void Kotlin.Error::.ctor(string)
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(object 'message') cil managed
            {
              .maxstack 3
              .locals init (
                [0] string 'text',
                [1] class ${coreLibraryReference}System.Exception 'cause'
              )
              ldarg.1
              brtrue.s AE_MessageNotNull
              ldnull
              br.s AE_TextReady
        AE_MessageNotNull:
              ldarg.1
              callvirt instance string ${coreLibraryReference}System.Object::ToString()
        AE_TextReady:
              stloc.0
              ldarg.1
              isinst ${coreLibraryReference}System.Exception
              stloc.1
              ldarg.0
              ldloc.0
              ldloc.1
              call instance void Kotlin.Error::.ctor(
                  string, class ${coreLibraryReference}System.Exception)
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(
                string 'message', class ${coreLibraryReference}System.Exception 'cause') cil managed
            {
              .maxstack 3
              ldarg.0
              ldarg.1
              ldarg.2
              call instance void Kotlin.Error::.ctor(
                  string, class ${coreLibraryReference}System.Exception)
              ret
            }
          }
    """.trimIndent()

    private fun runtimeExceptionConstructorsIl(
        simpleName: String,
        coreLibraryReference: String,
    ): String = """
            .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 1
              ldarg.0
              call instance void Kotlin.RuntimeException::.ctor()
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(string 'message') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              call instance void Kotlin.RuntimeException::.ctor(string)
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(
                string 'message', class ${coreLibraryReference}System.Exception 'cause') cil managed
            {
              .maxstack 3
              ldarg.0
              ldarg.1
              ldarg.2
              call instance void Kotlin.RuntimeException::.ctor(
                  string, class ${coreLibraryReference}System.Exception)
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor(
                class ${coreLibraryReference}System.Exception 'cause') cil managed
            {
              .maxstack 2
              ldarg.0
              ldarg.1
              call instance void Kotlin.RuntimeException::.ctor(
                  class ${coreLibraryReference}System.Exception)
              ret
            }
    """.trimIndent().prependIndent("            ")

    fun supportTypesIl(
        coreLibraryReference: String,
        compilerAbiTypeAttributesIl: String,
    ): String {
        val exceptionType = "class ${coreLibraryReference}System.Exception"
        val stateType = "class Kotlin.Runtime.Internal.ThrowableState"
        val listType =
            "class ${coreLibraryReference}System.Collections.Generic.List`1<$exceptionType>"
        val tableType =
            "class ${coreLibraryReference}System.Runtime.CompilerServices.ConditionalWeakTable`2" +
                    "<$exceptionType, $stateType>"
        return """
  .class private auto ansi sealed beforefieldinit ThrowableState
         extends ${coreLibraryReference}System.Object
  {
    .field assembly initonly $listType 'suppressed'
    .field assembly int32 'exactTypeId'

    .method assembly hidebysig specialname rtspecialname instance void .ctor() cil managed
    {
      .maxstack 2
      ldarg.0
      call instance void ${coreLibraryReference}System.Object::.ctor()
      ldarg.0
      newobj instance void $listType::.ctor()
      stfld $listType Kotlin.Runtime.Internal.ThrowableState::'suppressed'
      ldarg.0
      ldc.i4.0
      stfld int32 Kotlin.Runtime.Internal.ThrowableState::'exactTypeId'
      ret
    }
  }

  // Public only as compiler/runtime ABI. This service never wraps, clones, translates, or writes
  // Exception.Data: its weak key is the original exception identity for every carrier class.
  .class public abstract sealed auto ansi beforefieldinit ThrowableSupport
         extends ${coreLibraryReference}System.Object
  {
    $compilerAbiTypeAttributesIl
    .field private static initonly $tableType 'states'

    .method private hidebysig specialname rtspecialname static void .cctor() cil managed
    {
      .maxstack 1
      newobj instance void $tableType::.ctor()
      stsfld $tableType Kotlin.Runtime.Internal.ThrowableSupport::'states'
      ret
    }

    // Records which logical Kotlin constructor allocated a classified System.Exception carrier.
    // The original exception remains the weak-table key and is never wrapped or mutated.
    .method public hidebysig static void 'SetExactTypeId'(
        $exceptionType 'exception', int32 'exactTypeId') cil managed
    {
      .maxstack 3
      .locals init ([0] $stateType 'state')
      ldarg.0
      brtrue.s TS_ExactExceptionReady
      ldstr "exception"
      newobj instance void ${coreLibraryReference}System.ArgumentNullException::.ctor(string)
      throw
    TS_ExactExceptionReady:
      ldsfld $tableType Kotlin.Runtime.Internal.ThrowableSupport::'states'
      call void ${coreLibraryReference}System.Threading.Monitor::Enter(object)
      .try
      {
        ldsfld $tableType Kotlin.Runtime.Internal.ThrowableSupport::'states'
        ldarg.0
        ldloca.s 'state'
        callvirt instance bool $tableType::TryGetValue(!0, !1&)
        brtrue.s TS_ExactStateReady
        newobj instance void Kotlin.Runtime.Internal.ThrowableState::.ctor()
        stloc.0
        ldsfld $tableType Kotlin.Runtime.Internal.ThrowableSupport::'states'
        ldarg.0
        ldloc.0
        callvirt instance void $tableType::Add(!0, !1)
      TS_ExactStateReady:
        ldloc.0
        ldarg.1
        stfld int32 Kotlin.Runtime.Internal.ThrowableState::'exactTypeId'
        leave.s TS_ExactStored
      }
      finally
      {
        ldsfld $tableType Kotlin.Runtime.Internal.ThrowableSupport::'states'
        call void ${coreLibraryReference}System.Threading.Monitor::Exit(object)
        endfinally
      }
    TS_ExactStored:
      ret
    }

    .method public hidebysig static int32 'GetExactTypeId'(
        $exceptionType 'exception') cil managed
    {
      .maxstack 3
      .locals init ([0] $stateType 'state')
      ldarg.0
      brtrue.s TS_GetExact
      ldc.i4.0
      ret
    TS_GetExact:
      ldsfld $tableType Kotlin.Runtime.Internal.ThrowableSupport::'states'
      ldarg.0
      ldloca.s 'state'
      callvirt instance bool $tableType::TryGetValue(!0, !1&)
      brfalse.s TS_NoExact
      ldloc.0
      ldfld int32 Kotlin.Runtime.Internal.ThrowableState::'exactTypeId'
      ret
    TS_NoExact:
      ldc.i4.0
      ret
    }

    .method public hidebysig static void 'AddSuppressed'(
        $exceptionType 'owner', $exceptionType 'exception') cil managed
    {
      .maxstack 3
      .locals init ([0] $stateType 'state')
      ldarg.0
      brtrue.s TS_OwnerNotNull
      ldstr "owner"
      newobj instance void ${coreLibraryReference}System.ArgumentNullException::.ctor(string)
      throw
    TS_OwnerNotNull:
      ldarg.1
      brtrue.s TS_ExceptionNotNull
      ldstr "exception"
      newobj instance void ${coreLibraryReference}System.ArgumentNullException::.ctor(string)
      throw
    TS_ExceptionNotNull:
      ldarg.0
      ldarg.1
      ceq
      brfalse.s TS_Add
      ret

    TS_Add:
      ldsfld $tableType Kotlin.Runtime.Internal.ThrowableSupport::'states'
      call void ${coreLibraryReference}System.Threading.Monitor::Enter(object)
      .try
      {
        ldsfld $tableType Kotlin.Runtime.Internal.ThrowableSupport::'states'
        ldarg.0
        ldloca.s 'state'
        callvirt instance bool $tableType::TryGetValue(!0, !1&)
        brtrue.s TS_StateReady
        newobj instance void Kotlin.Runtime.Internal.ThrowableState::.ctor()
        stloc.0
        ldsfld $tableType Kotlin.Runtime.Internal.ThrowableSupport::'states'
        ldarg.0
        ldloc.0
        callvirt instance void $tableType::Add(!0, !1)
      TS_StateReady:
        ldloc.0
        ldfld $listType Kotlin.Runtime.Internal.ThrowableState::'suppressed'
        ldarg.1
        callvirt instance void $listType::Add(!0)
        leave.s TS_Added
      }
      finally
      {
        ldsfld $tableType Kotlin.Runtime.Internal.ThrowableSupport::'states'
        call void ${coreLibraryReference}System.Threading.Monitor::Exit(object)
        endfinally
      }
    TS_Added:
      ret
    }

    .method public hidebysig static $exceptionType[] 'GetSuppressed'(
        $exceptionType 'owner') cil managed
    {
      .maxstack 3
      .locals init (
        [0] $stateType 'state',
        [1] $exceptionType[] 'snapshot'
      )
      ldarg.0
      brtrue.s TS_Get
      ldstr "owner"
      newobj instance void ${coreLibraryReference}System.ArgumentNullException::.ctor(string)
      throw
    TS_Get:
      ldsfld $tableType Kotlin.Runtime.Internal.ThrowableSupport::'states'
      call void ${coreLibraryReference}System.Threading.Monitor::Enter(object)
      .try
      {
        ldsfld $tableType Kotlin.Runtime.Internal.ThrowableSupport::'states'
        ldarg.0
        ldloca.s 'state'
        callvirt instance bool $tableType::TryGetValue(!0, !1&)
        brfalse.s TS_Empty
        ldloc.0
        ldfld $listType Kotlin.Runtime.Internal.ThrowableState::'suppressed'
        callvirt instance !0[] $listType::ToArray()
        stloc.1
        leave.s TS_SnapshotReady
      TS_Empty:
        ldc.i4.0
        newarr ${coreLibraryReference}System.Exception
        stloc.1
        leave.s TS_SnapshotReady
      }
      finally
      {
        ldsfld $tableType Kotlin.Runtime.Internal.ThrowableSupport::'states'
        call void ${coreLibraryReference}System.Threading.Monitor::Exit(object)
        endfinally
      }
    TS_SnapshotReady:
      ldloc.1
      ret
    }

    .method public hidebysig static string 'StackTraceToString'(
        $exceptionType 'exception') cil managed
    {
      .maxstack 4
      .locals init (
        [0] class ${coreLibraryReference}System.Text.StringBuilder 'builder',
        [1] class ${coreLibraryReference}System.Collections.ArrayList 'active'
      )
      ldarg.0
      brtrue.s TS_Render
      ldstr "exception"
      newobj instance void ${coreLibraryReference}System.ArgumentNullException::.ctor(string)
      throw
    TS_Render:
      newobj instance void ${coreLibraryReference}System.Text.StringBuilder::.ctor()
      stloc.0
      newobj instance void ${coreLibraryReference}System.Collections.ArrayList::.ctor()
      stloc.1
      ldarg.0
      ldloc.0
      ldloc.1
      call void Kotlin.Runtime.Internal.ThrowableSupport::'AppendRoot'(
          $exceptionType,
          class ${coreLibraryReference}System.Text.StringBuilder,
          class ${coreLibraryReference}System.Collections.ArrayList)
      ldloc.0
      callvirt instance string ${coreLibraryReference}System.Object::ToString()
      ret
    }

    .method public hidebysig static void 'PrintStackTrace'(
        $exceptionType 'exception') cil managed
    {
      .maxstack 2
      call class ${coreLibraryReference}System.IO.TextWriter ${coreLibraryReference}System.Console::get_Error()
      ldarg.0
      call string Kotlin.Runtime.Internal.ThrowableSupport::'StackTraceToString'($exceptionType)
      callvirt instance void ${coreLibraryReference}System.IO.TextWriter::WriteLine(string)
      ret
    }

    .method private hidebysig static void 'AppendRoot'(
        $exceptionType 'exception',
        class ${coreLibraryReference}System.Text.StringBuilder 'builder',
        class ${coreLibraryReference}System.Collections.ArrayList 'active') cil managed
    {
      .maxstack 5
      .locals init (
        [0] $exceptionType[] 'suppressed',
        [1] int32 'index'
      )
      ldarg.2
      ldarg.0
      callvirt instance int32 ${coreLibraryReference}System.Collections.ArrayList::Add(object)
      pop
      ldarg.1
      ldarg.0
      callvirt instance string ${coreLibraryReference}System.Object::ToString()
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.0
      call $exceptionType[] Kotlin.Runtime.Internal.ThrowableSupport::'GetSuppressed'($exceptionType)
      stloc.0
      ldc.i4.0
      stloc.1
      br.s TS_RootTest
    TS_RootLoop:
      ldloc.0
      ldloc.1
      ldelem.ref
      ldarg.1
      ldarg.2
      ldstr "\t"
      call void Kotlin.Runtime.Internal.ThrowableSupport::'AppendSuppressed'(
          $exceptionType,
          class ${coreLibraryReference}System.Text.StringBuilder,
          class ${coreLibraryReference}System.Collections.ArrayList,
          string)
      ldloc.1
      ldc.i4.1
      add
      stloc.1
    TS_RootTest:
      ldloc.1
      ldloc.0
      ldlen
      conv.i4
      blt.s TS_RootLoop
      ldarg.0
      ldarg.1
      ldarg.2
      ldstr "\t"
      call void Kotlin.Runtime.Internal.ThrowableSupport::'AppendCauseSuppressed'(
          $exceptionType,
          class ${coreLibraryReference}System.Text.StringBuilder,
          class ${coreLibraryReference}System.Collections.ArrayList,
          string)
      ldarg.2
      ldarg.2
      callvirt instance int32 ${coreLibraryReference}System.Collections.ArrayList::get_Count()
      ldc.i4.1
      sub
      callvirt instance void ${coreLibraryReference}System.Collections.ArrayList::RemoveAt(int32)
      ret
    }

    .method private hidebysig static void 'AppendSuppressed'(
        $exceptionType 'exception',
        class ${coreLibraryReference}System.Text.StringBuilder 'builder',
        class ${coreLibraryReference}System.Collections.ArrayList 'active',
        string 'indent') cil managed
    {
      .maxstack 5
      .locals init (
        [0] $exceptionType[] 'suppressed',
        [1] int32 'index',
        [2] string 'childIndent'
      )
      ldarg.1
      call string ${coreLibraryReference}System.Environment::get_NewLine()
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.1
      ldarg.3
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.1
      ldstr "Suppressed: "
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.2
      ldarg.0
      call bool Kotlin.Runtime.Internal.ThrowableSupport::'ContainsReference'(
          class ${coreLibraryReference}System.Collections.ArrayList, object)
      brfalse.s TS_NotCircular
      ldarg.1
      ldstr "[CIRCULAR REFERENCE: "
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.1
      ldarg.0
      callvirt instance string ${coreLibraryReference}System.Object::ToString()
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.1
      ldstr "]"
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ret

    TS_NotCircular:
      ldarg.2
      ldarg.0
      callvirt instance int32 ${coreLibraryReference}System.Collections.ArrayList::Add(object)
      pop
      ldarg.1
      ldarg.0
      callvirt instance string ${coreLibraryReference}System.Object::ToString()
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.0
      call $exceptionType[] Kotlin.Runtime.Internal.ThrowableSupport::'GetSuppressed'($exceptionType)
      stloc.0
      ldarg.3
      ldstr "\t"
      call string ${coreLibraryReference}System.String::Concat(string, string)
      stloc.2
      ldc.i4.0
      stloc.1
      br.s TS_ChildTest
    TS_ChildLoop:
      ldloc.0
      ldloc.1
      ldelem.ref
      ldarg.1
      ldarg.2
      ldloc.2
      call void Kotlin.Runtime.Internal.ThrowableSupport::'AppendSuppressed'(
          $exceptionType,
          class ${coreLibraryReference}System.Text.StringBuilder,
          class ${coreLibraryReference}System.Collections.ArrayList,
          string)
      ldloc.1
      ldc.i4.1
      add
      stloc.1
    TS_ChildTest:
      ldloc.1
      ldloc.0
      ldlen
      conv.i4
      blt.s TS_ChildLoop
      ldarg.0
      ldarg.1
      ldarg.2
      ldloc.2
      call void Kotlin.Runtime.Internal.ThrowableSupport::'AppendCauseSuppressed'(
          $exceptionType,
          class ${coreLibraryReference}System.Text.StringBuilder,
          class ${coreLibraryReference}System.Collections.ArrayList,
          string)
      ldarg.2
      ldarg.2
      callvirt instance int32 ${coreLibraryReference}System.Collections.ArrayList::get_Count()
      ldc.i4.1
      sub
      callvirt instance void ${coreLibraryReference}System.Collections.ArrayList::RemoveAt(int32)
      ret
    }

    // Exception.ToString() already owns the exact CLR cause diagnostics. This traversal appends
    // only the Kotlin side-table state that the CLR cannot observe, including state on causes of
    // suppressed exceptions. Active-path reference checks also cover mixed cause/suppression
    // cycles without consulting hostile virtual Equals implementations.
    .method private hidebysig static void 'AppendCauseSuppressed'(
        $exceptionType 'exception',
        class ${coreLibraryReference}System.Text.StringBuilder 'builder',
        class ${coreLibraryReference}System.Collections.ArrayList 'active',
        string 'indent') cil managed
    {
      .maxstack 5
      .locals init (
        [0] $exceptionType 'cause',
        [1] $exceptionType[] 'suppressed',
        [2] int32 'index',
        [3] string 'childIndent'
      )
      ldarg.0
      callvirt instance $exceptionType ${coreLibraryReference}System.Exception::get_InnerException()
      stloc.0
      ldloc.0
      brtrue.s TS_CausePresent
      ret

    TS_CausePresent:
      ldarg.2
      ldloc.0
      call bool Kotlin.Runtime.Internal.ThrowableSupport::'ContainsReference'(
          class ${coreLibraryReference}System.Collections.ArrayList, object)
      brfalse TS_CauseNotCircular
      ldarg.1
      call string ${coreLibraryReference}System.Environment::get_NewLine()
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.1
      ldarg.3
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.1
      ldstr "[CIRCULAR CAUSE REFERENCE: "
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.1
      ldloc.0
      callvirt instance string ${coreLibraryReference}System.Object::ToString()
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.1
      ldstr "]"
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ret

    TS_CauseNotCircular:
      ldarg.2
      ldloc.0
      callvirt instance int32 ${coreLibraryReference}System.Collections.ArrayList::Add(object)
      pop
      ldloc.0
      call $exceptionType[] Kotlin.Runtime.Internal.ThrowableSupport::'GetSuppressed'($exceptionType)
      stloc.1
      ldarg.3
      ldstr "\t"
      call string ${coreLibraryReference}System.String::Concat(string, string)
      stloc.3
      ldloc.1
      ldlen
      conv.i4
      brfalse TS_CauseChildrenDone
      ldarg.1
      call string ${coreLibraryReference}System.Environment::get_NewLine()
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.1
      ldarg.3
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.1
      ldstr "Suppressed exceptions attached to cause: "
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldarg.1
      ldloc.0
      callvirt instance string ${coreLibraryReference}System.Object::ToString()
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(string)
      pop
      ldc.i4.0
      stloc.2
      br.s TS_CauseChildTest
    TS_CauseChildLoop:
      ldloc.1
      ldloc.2
      ldelem.ref
      ldarg.1
      ldarg.2
      ldloc.3
      call void Kotlin.Runtime.Internal.ThrowableSupport::'AppendSuppressed'(
          $exceptionType,
          class ${coreLibraryReference}System.Text.StringBuilder,
          class ${coreLibraryReference}System.Collections.ArrayList,
          string)
      ldloc.2
      ldc.i4.1
      add
      stloc.2
    TS_CauseChildTest:
      ldloc.2
      ldloc.1
      ldlen
      conv.i4
      blt.s TS_CauseChildLoop

    TS_CauseChildrenDone:
      ldloc.0
      ldarg.1
      ldarg.2
      ldloc.3
      call void Kotlin.Runtime.Internal.ThrowableSupport::'AppendCauseSuppressed'(
          $exceptionType,
          class ${coreLibraryReference}System.Text.StringBuilder,
          class ${coreLibraryReference}System.Collections.ArrayList,
          string)
      ldarg.2
      ldarg.2
      callvirt instance int32 ${coreLibraryReference}System.Collections.ArrayList::get_Count()
      ldc.i4.1
      sub
      callvirt instance void ${coreLibraryReference}System.Collections.ArrayList::RemoveAt(int32)
      ret
    }

    .method private hidebysig static bool 'ContainsReference'(
        class ${coreLibraryReference}System.Collections.ArrayList 'values',
        object 'candidate') cil managed
    {
      .maxstack 2
      .locals init ([0] int32 'index')
      ldc.i4.0
      stloc.0
      br.s TS_ReferenceTest
    TS_ReferenceLoop:
      ldarg.0
      ldloc.0
      callvirt instance object ${coreLibraryReference}System.Collections.ArrayList::get_Item(int32)
      ldarg.1
      ceq
      brtrue.s TS_ReferenceTrue
      ldloc.0
      ldc.i4.1
      add
      stloc.0
    TS_ReferenceTest:
      ldloc.0
      ldarg.0
      callvirt instance int32 ${coreLibraryReference}System.Collections.ArrayList::get_Count()
      blt.s TS_ReferenceLoop
      ldc.i4.0
      ret
    TS_ReferenceTrue:
      ldc.i4.1
      ret
    }
  }
        """.trimIndent()
    }

    fun addSuppressedCallInstruction(coreLibraryReference: String): String =
        "call void [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "'Kotlin.Runtime.Internal.ThrowableSupport'::'AddSuppressed'(" +
                "class ${coreLibraryReference}System.Exception, " +
                "class ${coreLibraryReference}System.Exception)"

    fun setExactTypeIdCallInstruction(coreLibraryReference: String): String =
        "call void [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "'Kotlin.Runtime.Internal.ThrowableSupport'::'SetExactTypeId'(" +
                "class ${coreLibraryReference}System.Exception, int32)"

    fun getSuppressedCallInstruction(coreLibraryReference: String): String =
        "call class ${coreLibraryReference}System.Exception[] " +
                "[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "'Kotlin.Runtime.Internal.ThrowableSupport'::'GetSuppressed'(" +
                "class ${coreLibraryReference}System.Exception)"

    fun stackTraceToStringCallInstruction(coreLibraryReference: String): String =
        "call string [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "'Kotlin.Runtime.Internal.ThrowableSupport'::'StackTraceToString'(" +
                "class ${coreLibraryReference}System.Exception)"

    fun printStackTraceCallInstruction(coreLibraryReference: String): String =
        "call void [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "'Kotlin.Runtime.Internal.ThrowableSupport'::'PrintStackTrace'(" +
                "class ${coreLibraryReference}System.Exception)"
}
