/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/** Physical Runtime closure for the JVM-shaped KCallable visibility surface. */
internal object DotNetKVisibilityRuntime {
    fun ilText(
        coreLibraryReference: String,
        compilerAbiUseAttributesIl: String,
    ): String = """
.namespace Kotlin.Enums
{
  // Logical generic information remains in Common KLIB. The no-member erased interface lives
  // below Stdlib so Runtime-owned KVisibility can expose its complete enum entries surface.
  .class interface public abstract auto ansi EnumEntries
         implements Kotlin.Collections.List
  {
  }
}

.namespace Kotlin
{
  // KVisibility is the ordinary Kotlin reference enum declared by the reused JVM stdlib source;
  // it is deliberately not a System.Enum value type.
  .class public auto ansi sealed beforefieldinit KVisibility
         extends Kotlin.Enum
         implements class ${coreLibraryReference}'System.IComparable`1'<class Kotlin.KVisibility>
  {
    .field public static initonly class Kotlin.KVisibility PUBLIC
    .field public static initonly class Kotlin.KVisibility PROTECTED
    .field public static initonly class Kotlin.KVisibility INTERNAL
    .field public static initonly class Kotlin.KVisibility PRIVATE
    .field private static class Kotlin.Enums.EnumEntries '${'$'}ENTRIES'
    .field private static object '<static-initialization-failure>'

    .method private hidebysig specialname rtspecialname static void .cctor() cil managed
    {
      .maxstack 4
      .locals init ([0] class ${coreLibraryReference}System.Exception 'reason')
      .try {
        ldstr "PUBLIC"
        ldc.i4.0
        newobj instance void Kotlin.KVisibility::.ctor(string, int32)
        stsfld class Kotlin.KVisibility Kotlin.KVisibility::PUBLIC
        ldstr "PROTECTED"
        ldc.i4.1
        newobj instance void Kotlin.KVisibility::.ctor(string, int32)
        stsfld class Kotlin.KVisibility Kotlin.KVisibility::PROTECTED
        ldstr "INTERNAL"
        ldc.i4.2
        newobj instance void Kotlin.KVisibility::.ctor(string, int32)
        stsfld class Kotlin.KVisibility Kotlin.KVisibility::INTERNAL
        ldstr "PRIVATE"
        ldc.i4.3
        newobj instance void Kotlin.KVisibility::.ctor(string, int32)
        stsfld class Kotlin.KVisibility Kotlin.KVisibility::PRIVATE
        ldc.i4.4
        newarr ${coreLibraryReference}System.Object
        dup
        ldc.i4.0
        ldsfld class Kotlin.KVisibility Kotlin.KVisibility::PUBLIC
        stelem.ref
        dup
        ldc.i4.1
        ldsfld class Kotlin.KVisibility Kotlin.KVisibility::PROTECTED
        stelem.ref
        dup
        ldc.i4.2
        ldsfld class Kotlin.KVisibility Kotlin.KVisibility::INTERNAL
        stelem.ref
        dup
        ldc.i4.3
        ldsfld class Kotlin.KVisibility Kotlin.KVisibility::PRIVATE
        stelem.ref
        newobj instance void Kotlin.Runtime.Internal.KVisibilityEntries::.ctor(object[])
        stsfld class Kotlin.Enums.EnumEntries Kotlin.KVisibility::'${'$'}ENTRIES'
        leave KVIS_CCTOR_END
      }
      catch ${coreLibraryReference}System.Exception {
        stloc.0
        ldloc.0
        call object Kotlin.Runtime.Internal.StaticInitialization::Capture(
            class ${coreLibraryReference}System.Exception)
        stsfld object Kotlin.KVisibility::'<static-initialization-failure>'
        leave KVIS_CCTOR_END
      }
    KVIS_CCTOR_END:
      ret
    }

    .method public hidebysig static void '<EnsureInitialized>'() cil managed
    {
      $compilerAbiUseAttributesIl
      .maxstack 2
      ldsfld object Kotlin.KVisibility::'<static-initialization-failure>'
      ldnull
      ceq
      ldc.i4.0
      ceq
      brfalse KVIS_INITIALIZED
      ldsfld object Kotlin.KVisibility::'<static-initialization-failure>'
      call class ${coreLibraryReference}System.Exception Kotlin.Runtime.Internal.StaticInitialization::Observe(object)
      ldstr "kotlin.reflect.KVisibility"
      call void Kotlin.Runtime.Internal.StaticInitialization::Throw(
          class ${coreLibraryReference}System.Exception, string)
      newobj instance void Kotlin.KotlinNothingValueException::.ctor()
      throw
    KVIS_INITIALIZED:
      ret
    }

    .method private hidebysig specialname rtspecialname instance void .ctor(
        string '${'$'}enum${'$'}name', int32 '${'$'}enum${'$'}ordinal') cil managed
    {
      .maxstack 3
      ldarg.0
      ldarg.1
      ldarg.2
      call instance void Kotlin.Enum::.ctor(string, int32)
      ret
    }

    .method public hidebysig static class Kotlin.KVisibility[] values() cil managed
    {
      .maxstack 4
      call void Kotlin.KVisibility::'<EnsureInitialized>'()
      ldc.i4.4
      newarr class Kotlin.KVisibility
      dup
      ldc.i4.0
      ldsfld class Kotlin.KVisibility Kotlin.KVisibility::PUBLIC
      stelem.ref
      dup
      ldc.i4.1
      ldsfld class Kotlin.KVisibility Kotlin.KVisibility::PROTECTED
      stelem.ref
      dup
      ldc.i4.2
      ldsfld class Kotlin.KVisibility Kotlin.KVisibility::INTERNAL
      stelem.ref
      dup
      ldc.i4.3
      ldsfld class Kotlin.KVisibility Kotlin.KVisibility::PRIVATE
      stelem.ref
      ret
    }

    .method public hidebysig static class Kotlin.KVisibility valueOf(string 'value') cil managed
    {
      .maxstack 3
      call void Kotlin.KVisibility::'<EnsureInitialized>'()
      ldarg.0
      ldstr "PUBLIC"
      call bool ${coreLibraryReference}System.String::op_Equality(string, string)
      brfalse KVIS_VALUE_PROTECTED
      ldsfld class Kotlin.KVisibility Kotlin.KVisibility::PUBLIC
      ret
    KVIS_VALUE_PROTECTED:
      ldarg.0
      ldstr "PROTECTED"
      call bool ${coreLibraryReference}System.String::op_Equality(string, string)
      brfalse KVIS_VALUE_INTERNAL
      ldsfld class Kotlin.KVisibility Kotlin.KVisibility::PROTECTED
      ret
    KVIS_VALUE_INTERNAL:
      ldarg.0
      ldstr "INTERNAL"
      call bool ${coreLibraryReference}System.String::op_Equality(string, string)
      brfalse KVIS_VALUE_PRIVATE
      ldsfld class Kotlin.KVisibility Kotlin.KVisibility::INTERNAL
      ret
    KVIS_VALUE_PRIVATE:
      ldarg.0
      ldstr "PRIVATE"
      call bool ${coreLibraryReference}System.String::op_Equality(string, string)
      brfalse KVIS_VALUE_MISSING
      ldsfld class Kotlin.KVisibility Kotlin.KVisibility::PRIVATE
      ret
    KVIS_VALUE_MISSING:
      ldstr "No enum constant kotlin.reflect.KVisibility."
      ldarg.0
      call string ${coreLibraryReference}System.String::Concat(string, string)
      newobj instance void ${coreLibraryReference}System.ArgumentException::.ctor(string)
      dup
      ldc.i4.5
      call void Kotlin.Runtime.Internal.ThrowableSupport::SetExactTypeId(
          class ${coreLibraryReference}System.Exception, int32)
      throw
    }

    .method public hidebysig specialname static class Kotlin.Enums.EnumEntries get_entries() cil managed
    {
      .maxstack 1
      call void Kotlin.KVisibility::'<EnsureInitialized>'()
      ldsfld class Kotlin.Enums.EnumEntries Kotlin.KVisibility::'${'$'}ENTRIES'
      ret
    }

    .method private hidebysig newslot virtual final instance int32
        '<GenericInterfaceDeclaredBridge-kotlin.Comparable-compareTo-KVisibility>'(
            class Kotlin.KVisibility 'other') cil managed
    {
      .override method instance int32 class ${coreLibraryReference}'System.IComparable`1'<class Kotlin.KVisibility>::CompareTo(!0)
      .maxstack 2
      ldarg.0
      ldarg.1
      callvirt instance int32 Kotlin.Enum::compareTo(object)
      ret
    }

    .property class Kotlin.Enums.EnumEntries entries()
    {
      .get class Kotlin.Enums.EnumEntries Kotlin.KVisibility::get_entries()
    }
  }
}

.namespace Kotlin.Runtime.Internal
{
  // EnumEntries adds no members beyond List. Reuse the complete Runtime read-only list carrier
  // rather than copying Common's ordinary EnumEntriesList implementation into Runtime.
  .class private sealed auto ansi beforefieldinit KVisibilityEntries
         extends Kotlin.Runtime.Internal.ReflectionAnnotationList
         implements Kotlin.Enums.EnumEntries
  {
    .method assembly hidebysig specialname rtspecialname instance void .ctor(
        object[] 'items') cil managed
    {
      .maxstack 2
      ldarg.0
      ldarg.1
      call instance void Kotlin.Runtime.Internal.ReflectionAnnotationList::.ctor(object[])
      ret
    }
  }
}
    """.trimIndent()
}
