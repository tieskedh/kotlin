/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

const val DOTNET_GENERIC_OWNER_CALL_ROUTE_TRACE_PREFIX: String =
    "KOTLIN_DOTNET_GENERIC_OWNER_CALL_ROUTE|site="
const val DOTNET_GENERIC_OWNER_CALL_ROUTE_TRACE_COUNT_SEPARATOR: String = "|count="

/**
 * Private physical support emitted only when the test fixture installs explicit IR trace hooks.
 *
 * Calls use one fixed primitive array whose exact size is the compiler's route-site count.
 * `Interlocked.Increment` makes recording linearizable without console I/O, allocation, generic
 * collection calls, or a runtime/KLIB ABI. The final read also uses `Interlocked`, so the emitted
 * snapshot has well-defined primitive reads on every supported CLR. A traced application must
 * join its workload before returning from `box()`; the final flush is a correctness observation,
 * not a stop-the-world protocol and never performance evidence.
 */
internal object DotNetGenericOwnerCallRouteTraceSupport {
    private const val TYPE_NAME = "Kotlin.DotNet.Testing.GenericOwnerCallRouteCounters"
    private const val SITE_LIMIT = 1_048_576

    fun callInstruction(hook: DotNetGenericOwnerCallRouteTraceHook): String = when (hook) {
        DotNetGenericOwnerCallRouteTraceHook.RECORD ->
            "call void $TYPE_NAME::'Record'(int32)"
        DotNetGenericOwnerCallRouteTraceHook.FLUSH ->
            "call void $TYPE_NAME::'Flush'()"
    }

    fun helperTypeIl(coreLibraryReference: String, siteCount: Int): String {
        require(siteCount in 0..SITE_LIMIT) {
            "Generic-owner route tracing supports at most $SITE_LIMIT compiler call sites, got $siteCount"
        }
        return """
  .namespace Kotlin.DotNet.Testing
  {
   .class private auto ansi abstract sealed beforefieldinit GenericOwnerCallRouteCounters
          extends ${coreLibraryReference}System.Object
   {
    .field private static initonly int64[] '_counts'

    .method private hidebysig specialname rtspecialname static void .cctor() cil managed
    {
      .maxstack 1
      ldc.i4 $siteCount
      newarr ${coreLibraryReference}System.Int64
      stsfld int64[] $TYPE_NAME::'_counts'
      ret
    }

    .method assembly hidebysig static void 'Record'(int32 'callSiteIndex') cil managed
    {
      .maxstack 2
      ldarg.0
      ldc.i4.0
      blt IL_invalidSite
      ldarg.0
      ldc.i4 $siteCount
      bge IL_invalidSite
      ldsfld int64[] $TYPE_NAME::'_counts'
      ldarg.0
      ldelema ${coreLibraryReference}System.Int64
      call int64 ${coreLibraryReference}System.Threading.Interlocked::Increment(int64&)
      pop
      ret
    IL_invalidSite:
      newobj instance void ${coreLibraryReference}System.IndexOutOfRangeException::.ctor()
      throw
    }

    .method assembly hidebysig static void 'Flush'() cil managed
    {
      .maxstack 4
      .locals init (
        [0] int32 'callSiteIndex',
        [1] int64 'count'
      )
      ldc.i4.0
      stloc.0
    IL_nextSite:
      ldloc.0
      ldc.i4 $siteCount
      bge IL_complete
      ldsfld int64[] $TYPE_NAME::'_counts'
      ldloc.0
      ldelema ${coreLibraryReference}System.Int64
      call int64 ${coreLibraryReference}System.Threading.Interlocked::Read(int64&)
      stloc.1
      ldloc.1
      brfalse IL_advance
      ldstr "$DOTNET_GENERIC_OWNER_CALL_ROUTE_TRACE_PREFIX"
      ldloca.s 0
      call instance string ${coreLibraryReference}System.Int32::ToString()
      call string ${coreLibraryReference}System.String::Concat(string, string)
      ldstr "$DOTNET_GENERIC_OWNER_CALL_ROUTE_TRACE_COUNT_SEPARATOR"
      ldloca.s 1
      call instance string ${coreLibraryReference}System.Int64::ToString()
      call string ${coreLibraryReference}System.String::Concat(string, string)
      call string ${coreLibraryReference}System.String::Concat(string, string)
      call void ${coreLibraryReference}System.Console::WriteLine(string)
    IL_advance:
      ldloc.0
      ldc.i4.1
      add
      stloc.0
      br IL_nextSite
    IL_complete:
      ret
    }
   }
  }
        """.trimIndent()
    }
}
