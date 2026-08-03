/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/**
 * Versioned classifier kinds stored in Kotlin.Runtime's physical KClass implementation.
 * The values cross assembly boundaries in generated calls and are therefore append-only after
 * the first Kotlin/.NET ABI freeze.
 */
internal enum class DotNetKClassClassifierKind(val abiValue: Int) {
    EXACT(0),
    OPEN_GENERIC(1),
    GENERIC_ARRAY(2),
    CHAR_SEQUENCE(3),
    EXCEPTION(4),
    NUMBER(5),
    NOTHING(6),
}

/** Physical Common-KClass floor and the compiler/runtime factory used by class-literal codegen. */
internal object DotNetKClassRuntime {
    private const val KCLASS_TYPE_NAME = "Kotlin.KClass"
    private const val KCLASS_IMPL_TYPE_NAME = "Kotlin.KClassImpl"
    private const val FACTORY_TYPE_NAME = "Kotlin.Runtime.Internal.KClassFactory"
    private const val LOCAL_NAME_ATTRIBUTE_TYPE_NAME =
        "Kotlin.Runtime.Internal.KotlinLocalClassNameAttribute"

    val kClassifierClassInfo = DotNetIlClassInfo(
        ilClassName = "Kotlin.KClassifier",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
    val kClassClassInfo = DotNetIlClassInfo(
        ilClassName = KCLASS_TYPE_NAME,
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    fun createCallInstruction(coreLibraryReference: String): String =
        "call class [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]${KCLASS_TYPE_NAME.toIlIdentifier()} " +
                "[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]${FACTORY_TYPE_NAME.toIlIdentifier()}::'Create'(" +
                "class ${coreLibraryReference}System.Type, string, string, int32, int32)"

    fun getClassCallInstruction(coreLibraryReference: String): String =
        "call class [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]${KCLASS_TYPE_NAME.toIlIdentifier()} " +
                "[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]${FACTORY_TYPE_NAME.toIlIdentifier()}::'GetClass'(object)"

    fun localClassNameAttributeIl(simpleName: String?): String {
        val value = simpleName?.let(::serializedCustomAttributeString) ?: listOf(0xff)
        val blob = (listOf(0x01, 0x00) + value + listOf(0x00, 0x00))
            .joinToString(" ") { byte -> byte.toString(16).padStart(2, '0') }
        return ".custom instance void [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                "${LOCAL_NAME_ATTRIBUTE_TYPE_NAME.toIlIdentifier()}::.ctor(string) = ($blob)"
    }

    fun kotlinTypesIl(coreLibraryReference: String): String = """
          .class interface public abstract auto ansi KClassifier
          {
          }

          .class interface public abstract auto ansi KClass
                 implements Kotlin.KClassifier
          {
            .method public hidebysig specialname newslot abstract virtual instance string 'get_simpleName'() cil managed
            {
            }

            .method public hidebysig specialname newslot abstract virtual instance string 'get_qualifiedName'() cil managed
            {
            }

            .method public hidebysig newslot abstract virtual instance bool 'isInstance'(object 'value') cil managed
            {
            }

            .property instance string simpleName()
            {
              .get instance string Kotlin.KClass::'get_simpleName'()
            }

            .property instance string qualifiedName()
            {
              .get instance string Kotlin.KClass::'get_qualifiedName'()
            }
          }

          .class private sealed auto ansi beforefieldinit KClassImpl
                 extends ${coreLibraryReference}System.Object
                 implements Kotlin.KClass
          {
            .field assembly initonly class ${coreLibraryReference}System.Type '_clrType'
            .field private initonly string '_simpleName'
            .field private initonly string '_qualifiedName'
            .field private initonly int32 '_kind'
            .field private initonly int32 '_classifierId'

            .method assembly hidebysig specialname rtspecialname instance void .ctor(
                class ${coreLibraryReference}System.Type 'clrType',
                string 'simpleName',
                string 'qualifiedName',
                int32 'kind',
                int32 'classifierId') cil managed
            {
              .maxstack 2
              ldarg.0
              call instance void ${coreLibraryReference}System.Object::.ctor()
              ldarg.0
              ldarg.1
              stfld class ${coreLibraryReference}System.Type Kotlin.KClassImpl::'_clrType'
              ldarg.0
              ldarg.2
              stfld string Kotlin.KClassImpl::'_simpleName'
              ldarg.0
              ldarg.3
              stfld string Kotlin.KClassImpl::'_qualifiedName'
              ldarg.0
              ldarg.s 'kind'
              stfld int32 Kotlin.KClassImpl::'_kind'
              ldarg.0
              ldarg.s 'classifierId'
              stfld int32 Kotlin.KClassImpl::'_classifierId'
              ret
            }

            .method public hidebysig specialname newslot virtual final instance string 'get_simpleName'() cil managed
            {
              .maxstack 1
              ldarg.0
              ldfld string Kotlin.KClassImpl::'_simpleName'
              ret
            }

            .method public hidebysig specialname newslot virtual final instance string 'get_qualifiedName'() cil managed
            {
              .maxstack 1
              ldarg.0
              ldfld string Kotlin.KClassImpl::'_qualifiedName'
              ret
            }

            .method public hidebysig newslot virtual final instance bool 'isInstance'(object 'value') cil managed
            {
              .maxstack 4
              ldarg.1
              ldarg.0
              ldfld class ${coreLibraryReference}System.Type Kotlin.KClassImpl::'_clrType'
              ldarg.0
              ldfld int32 Kotlin.KClassImpl::'_kind'
              ldarg.0
              ldfld int32 Kotlin.KClassImpl::'_classifierId'
              call bool Kotlin.Runtime.Internal.KClassFactory::'IsInstance'(
                  object, class ${coreLibraryReference}System.Type, int32, int32)
              ret
            }

            .method public hidebysig virtual instance bool Equals(object 'other') cil managed
            {
              .maxstack 2
              .locals init ([0] class Kotlin.KClassImpl 'otherClass')
              ldarg.1
              isinst Kotlin.KClassImpl
              stloc.0
              ldloc.0
              brfalse.s KC_EqualsFalse
              ldarg.0
              ldfld int32 Kotlin.KClassImpl::'_kind'
              ldloc.0
              ldfld int32 Kotlin.KClassImpl::'_kind'
              bne.un.s KC_EqualsFalse
              ldarg.0
              ldfld int32 Kotlin.KClassImpl::'_classifierId'
              ldloc.0
              ldfld int32 Kotlin.KClassImpl::'_classifierId'
              bne.un.s KC_EqualsFalse
              ldarg.0
              ldfld class ${coreLibraryReference}System.Type Kotlin.KClassImpl::'_clrType'
              ldloc.0
              ldfld class ${coreLibraryReference}System.Type Kotlin.KClassImpl::'_clrType'
              call bool ${coreLibraryReference}System.Type::op_Equality(
                  class ${coreLibraryReference}System.Type, class ${coreLibraryReference}System.Type)
              ret
            KC_EqualsFalse:
              ldc.i4.0
              ret
            }

            .method public hidebysig virtual instance int32 GetHashCode() cil managed
            {
              .maxstack 2
              .locals init ([0] int32 'hash')
              ldarg.0
              ldfld int32 Kotlin.KClassImpl::'_kind'
              ldc.i4 397
              mul
              ldarg.0
              ldfld int32 Kotlin.KClassImpl::'_classifierId'
              xor
              stloc.0
              ldarg.0
              ldfld class ${coreLibraryReference}System.Type Kotlin.KClassImpl::'_clrType'
              brfalse.s KC_HashReady
              ldloc.0
              ldc.i4 397
              mul
              ldarg.0
              ldfld class ${coreLibraryReference}System.Type Kotlin.KClassImpl::'_clrType'
              callvirt instance int32 ${coreLibraryReference}System.Object::GetHashCode()
              xor
              stloc.0
            KC_HashReady:
              ldloc.0
              ret
            }

            .method public hidebysig virtual instance string ToString() cil managed
            {
              .maxstack 2
              ldstr "class "
              ldarg.0
              ldfld string Kotlin.KClassImpl::'_qualifiedName'
              dup
              brtrue.s KC_ToStringName
              pop
              ldarg.0
              ldfld string Kotlin.KClassImpl::'_simpleName'
              dup
              brtrue.s KC_ToStringName
              pop
              ldstr "<anonymous>"
            KC_ToStringName:
              call string ${coreLibraryReference}System.String::Concat(string, string)
              ret
            }

            .property instance string simpleName()
            {
              .get instance string Kotlin.KClassImpl::'get_simpleName'()
            }

            .property instance string qualifiedName()
            {
              .get instance string Kotlin.KClassImpl::'get_qualifiedName'()
            }
          }
    """.trimIndent().prependIndent("          ").trimStart()

    fun supportTypesIl(
        coreLibraryReference: String,
        compilerAbiTypeAttributesIl: String,
    ): String {
        val mappedExceptionIdBody = mappedExceptionIdBody(coreLibraryReference)
        val createExceptionBody = createExceptionBody(coreLibraryReference)
        val exact = DotNetKClassClassifierKind.EXACT.abiValue
        val openGeneric = DotNetKClassClassifierKind.OPEN_GENERIC.abiValue
        val genericArray = DotNetKClassClassifierKind.GENERIC_ARRAY.abiValue
        val charSequence = DotNetKClassClassifierKind.CHAR_SEQUENCE.abiValue
        val exception = DotNetKClassClassifierKind.EXCEPTION.abiValue
        val number = DotNetKClassClassifierKind.NUMBER.abiValue
        val nothing = DotNetKClassClassifierKind.NOTHING.abiValue
        return """
  .class public sealed auto ansi beforefieldinit KotlinLocalClassNameAttribute
         extends ${coreLibraryReference}System.Attribute
  {
    $compilerAbiTypeAttributesIl
    .field public initonly string 'SimpleName'

    .method public hidebysig specialname rtspecialname instance void .ctor(string 'simpleName') cil managed
    {
      .maxstack 2
      ldarg.0
      call instance void ${coreLibraryReference}System.Attribute::.ctor()
      ldarg.0
      ldarg.1
      stfld string Kotlin.Runtime.Internal.KotlinLocalClassNameAttribute::'SimpleName'
      ret
    }
  }

  // Public only as compiler/runtime ABI. Logical KClass identity remains in KLIB; System.Type is
  // retained as exact or partial physical evidence and never becomes the metadata authority.
  .class public abstract sealed auto ansi beforefieldinit KClassFactory
         extends ${coreLibraryReference}System.Object
  {
    $compilerAbiTypeAttributesIl
    .method public hidebysig static class Kotlin.KClass 'Create'(
        class ${coreLibraryReference}System.Type 'clrType',
        string 'simpleName',
        string 'qualifiedName',
        int32 'kind',
        int32 'classifierId') cil managed
    {
      .maxstack 5
      ldarg.0
      ldarg.1
      ldarg.2
      ldarg.3
      ldarg.s 'classifierId'
      newobj instance void Kotlin.KClassImpl::.ctor(
          class ${coreLibraryReference}System.Type, string, string, int32, int32)
      ret
    }

    .method public hidebysig static class ${coreLibraryReference}System.Type 'GetClrType'(
        class Kotlin.KClass 'kClass') cil managed
    {
      .maxstack 1
      ldarg.0
      isinst Kotlin.KClassImpl
      dup
      brtrue.s KCF_GetClrType
      pop
      ldnull
      ret
    KCF_GetClrType:
      ldfld class ${coreLibraryReference}System.Type Kotlin.KClassImpl::'_clrType'
      ret
    }

    .method public hidebysig static class Kotlin.KClass 'GetClass'(object 'value') cil managed
    {
      .maxstack 5
      .locals init (
        [0] class ${coreLibraryReference}System.Type 'runtimeType',
        [1] int32 'exceptionId',
        [2] object[] 'attributes',
        [3] class Kotlin.Runtime.Internal.KotlinLocalClassNameAttribute 'localName'
      )
      ldarg.0
      brtrue.s KCF_ValueReady
      newobj instance void ${coreLibraryReference}System.NullReferenceException::.ctor()
      throw
    KCF_ValueReady:
      ldarg.0
      callvirt instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Object::GetType()
      stloc.0

      ldarg.0
      isinst ${coreLibraryReference}System.Exception
      dup
      brfalse.s KCF_NotException
      call int32 Kotlin.Runtime.Internal.ThrowableSupport::'GetExactTypeId'(
          class ${coreLibraryReference}System.Exception)
      stloc.1
      ldloc.1
      brtrue KCF_CreateException
      ldloc.0
      call int32 Kotlin.Runtime.Internal.KClassFactory::'MappedExceptionId'(
          class ${coreLibraryReference}System.Type)
      stloc.1
      ldloc.1
      brtrue KCF_CreateException
      br.s KCF_AfterException
    KCF_NotException:
      pop
    KCF_AfterException:

      ldarg.0
      call bool Kotlin.Runtime.Internal.Intrinsics::'IsGenericArray'(object)
      brfalse.s KCF_NotArray
      ldtoken ${coreLibraryReference}System.Array
      call class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::GetTypeFromHandle(
          valuetype ${coreLibraryReference}System.RuntimeTypeHandle)
      ldstr "Array"
      ldstr "kotlin.Array"
      ldc.i4 $genericArray
      ldc.i4.0
      call class Kotlin.KClass Kotlin.Runtime.Internal.KClassFactory::'Create'(
          class ${coreLibraryReference}System.Type, string, string, int32, int32)
      ret
    KCF_NotArray:

${dynamicExactTypeCases(coreLibraryReference).prependIndent("      ")}

      ldloc.0
      callvirt instance bool ${coreLibraryReference}System.Type::get_IsGenericType()
      brfalse.s KCF_Normalized
      ldloc.0
      callvirt instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::GetGenericTypeDefinition()
      stloc.0
      ldc.i4 $openGeneric
      stloc.1
      br.s KCF_ReadLocalName
    KCF_Normalized:
      ldc.i4 $exact
      stloc.1
    KCF_ReadLocalName:
      ldloc.0
      ldtoken Kotlin.Runtime.Internal.KotlinLocalClassNameAttribute
      call class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::GetTypeFromHandle(
          valuetype ${coreLibraryReference}System.RuntimeTypeHandle)
      ldc.i4.0
      callvirt instance object[] ${coreLibraryReference}System.Reflection.MemberInfo::GetCustomAttributes(
          class ${coreLibraryReference}System.Type, bool)
      stloc.2
      ldloc.2
      ldlen
      conv.i4
      brfalse.s KCF_DeriveNames
      ldloc.2
      ldc.i4.0
      ldelem.ref
      castclass Kotlin.Runtime.Internal.KotlinLocalClassNameAttribute
      stloc.3
      ldloc.0
      ldloc.3
      ldfld string Kotlin.Runtime.Internal.KotlinLocalClassNameAttribute::'SimpleName'
      ldnull
      ldloc.1
      ldc.i4.0
      call class Kotlin.KClass Kotlin.Runtime.Internal.KClassFactory::'Create'(
          class ${coreLibraryReference}System.Type, string, string, int32, int32)
      ret
    KCF_DeriveNames:
      ldloc.0
      ldloc.0
      callvirt instance string ${coreLibraryReference}System.Reflection.MemberInfo::get_Name()
      call string Kotlin.Runtime.Internal.KClassFactory::'StripGenericArity'(string)
      ldloc.0
      callvirt instance string ${coreLibraryReference}System.Type::get_FullName()
      call string Kotlin.Runtime.Internal.KClassFactory::'SourceQualifiedName'(string)
      ldloc.1
      ldc.i4.0
      call class Kotlin.KClass Kotlin.Runtime.Internal.KClassFactory::'Create'(
          class ${coreLibraryReference}System.Type, string, string, int32, int32)
      ret

    KCF_CreateException:
      ldloc.1
      ldloc.0
      call class Kotlin.KClass Kotlin.Runtime.Internal.KClassFactory::'CreateException'(
          int32, class ${coreLibraryReference}System.Type)
      ret
    }

    .method assembly hidebysig static bool 'IsInstance'(
        object 'value', class ${coreLibraryReference}System.Type 'clrType', int32 'kind', int32 'classifierId') cil managed
    {
      .maxstack 3
      ldarg.0
      brfalse KCF_InstanceFalse
      ldarg.2
      ldc.i4 $exact
      beq KCF_InstanceExact
      ldarg.2
      ldc.i4 $openGeneric
      beq KCF_InstanceOpenGeneric
      ldarg.2
      ldc.i4 $genericArray
      beq KCF_InstanceArray
      ldarg.2
      ldc.i4 $charSequence
      beq KCF_InstanceCharSequence
      ldarg.2
      ldc.i4 $exception
      beq KCF_InstanceException
      ldarg.2
      ldc.i4 $number
      beq KCF_InstanceNumber
      ldarg.2
      ldc.i4 $nothing
      beq KCF_InstanceFalse
      br KCF_InstanceFalse
    KCF_InstanceExact:
      ldarg.1
      ldarg.0
      callvirt instance bool ${coreLibraryReference}System.Type::IsInstanceOfType(object)
      ret
    KCF_InstanceOpenGeneric:
      ldarg.0
      ldarg.1
      call bool Kotlin.Runtime.Internal.KClassFactory::'IsOpenGenericInstance'(
          object, class ${coreLibraryReference}System.Type)
      ret
    KCF_InstanceArray:
      ldarg.0
      call bool Kotlin.Runtime.Internal.Intrinsics::'IsGenericArray'(object)
      ret
    KCF_InstanceCharSequence:
      ldarg.0
      call bool Kotlin.Runtime.Internal.Intrinsics::'IsCharSequence'(object)
      ret
    KCF_InstanceException:
      ldarg.0
      isinst ${coreLibraryReference}System.Exception
      ldarg.3
      call bool Kotlin.Runtime.Internal.ExceptionClassifier::'IsKotlinExceptionInstance'(
          class ${coreLibraryReference}System.Exception, int32)
      ret
    KCF_InstanceNumber:
      ldarg.0
      isinst ${coreLibraryReference}System.SByte
      brtrue.s KCF_InstanceTrue
      ldarg.0
      isinst ${coreLibraryReference}System.Int16
      brtrue.s KCF_InstanceTrue
      ldarg.0
      isinst ${coreLibraryReference}System.Int32
      brtrue.s KCF_InstanceTrue
      ldarg.0
      isinst ${coreLibraryReference}System.Int64
      brtrue.s KCF_InstanceTrue
      ldarg.0
      isinst ${coreLibraryReference}System.Single
      brtrue.s KCF_InstanceTrue
      ldarg.0
      isinst ${coreLibraryReference}System.Double
      brtrue.s KCF_InstanceTrue
    KCF_InstanceFalse:
      ldc.i4.0
      ret
    KCF_InstanceTrue:
      ldc.i4.1
      ret
    }

    .method private hidebysig static bool 'IsOpenGenericInstance'(
        object 'value', class ${coreLibraryReference}System.Type 'openDefinition') cil managed
    {
      .maxstack 3
      .locals init (
        [0] class ${coreLibraryReference}System.Type 'currentType',
        [1] class ${coreLibraryReference}System.Type[] 'interfaces',
        [2] int32 'index',
        [3] class ${coreLibraryReference}System.Type 'candidate'
      )
      ldarg.0
      brfalse KCF_OpenFalse
      ldarg.0
      callvirt instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Object::GetType()
      stloc.0
    KCF_OpenNextType:
      ldloc.0
      brfalse KCF_OpenFalse
      ldloc.0
      ldarg.1
      call bool Kotlin.Runtime.Internal.KClassFactory::'MatchesOpenDefinition'(
          class ${coreLibraryReference}System.Type, class ${coreLibraryReference}System.Type)
      brtrue KCF_OpenTrue
      ldloc.0
      callvirt instance class ${coreLibraryReference}System.Type[] ${coreLibraryReference}System.Type::GetInterfaces()
      stloc.1
      ldc.i4.0
      stloc.2
    KCF_OpenNextInterface:
      ldloc.2
      ldloc.1
      ldlen
      conv.i4
      bge.s KCF_OpenBase
      ldloc.1
      ldloc.2
      ldelem.ref
      stloc.3
      ldloc.3
      ldarg.1
      call bool Kotlin.Runtime.Internal.KClassFactory::'MatchesOpenDefinition'(
          class ${coreLibraryReference}System.Type, class ${coreLibraryReference}System.Type)
      brtrue.s KCF_OpenTrue
      ldloc.2
      ldc.i4.1
      add
      stloc.2
      br.s KCF_OpenNextInterface
    KCF_OpenBase:
      ldloc.0
      callvirt instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::get_BaseType()
      stloc.0
      br KCF_OpenNextType
    KCF_OpenTrue:
      ldc.i4.1
      ret
    KCF_OpenFalse:
      ldc.i4.0
      ret
    }

    .method private hidebysig static bool 'MatchesOpenDefinition'(
        class ${coreLibraryReference}System.Type 'candidate',
        class ${coreLibraryReference}System.Type 'openDefinition') cil managed
    {
      .maxstack 2
      ldarg.0
      callvirt instance bool ${coreLibraryReference}System.Type::get_IsGenericType()
      brfalse.s KCF_MatchesFalse
      ldarg.0
      callvirt instance class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::GetGenericTypeDefinition()
      ldarg.1
      call bool ${coreLibraryReference}System.Type::op_Equality(
          class ${coreLibraryReference}System.Type, class ${coreLibraryReference}System.Type)
      ret
    KCF_MatchesFalse:
      ldc.i4.0
      ret
    }

    .method private hidebysig static int32 'MappedExceptionId'(
        class ${coreLibraryReference}System.Type 'runtimeType') cil managed
    {
      .maxstack 2
$mappedExceptionIdBody
      ldc.i4.0
      ret
    }

    .method private hidebysig static class Kotlin.KClass 'CreateException'(
        int32 'classifierId', class ${coreLibraryReference}System.Type 'runtimeType') cil managed
    {
      .maxstack 5
$createExceptionBody
    }

    .method private hidebysig static string 'SourceQualifiedName'(string 'fullName') cil managed
    {
      .maxstack 3
      ldarg.0
      brtrue.s KCF_FullNamePresent
      ldnull
      ret
    KCF_FullNamePresent:
      ldarg.0
      ldc.i4.s 43
      ldc.i4.s 46
      callvirt instance string ${coreLibraryReference}System.String::Replace(char, char)
      call string Kotlin.Runtime.Internal.KClassFactory::'StripGenericArity'(string)
      ret
    }

    .method private hidebysig static string 'StripGenericArity'(string 'name') cil managed
    {
      .maxstack 3
      .locals init (
        [0] class ${coreLibraryReference}System.Text.StringBuilder 'builder',
        [1] int32 'index',
        [2] char 'current'
      )
      ldarg.0
      brtrue.s KCF_StripStart
      ldnull
      ret
    KCF_StripStart:
      newobj instance void ${coreLibraryReference}System.Text.StringBuilder::.ctor()
      stloc.0
      ldc.i4.0
      stloc.1
    KCF_StripNext:
      ldloc.1
      ldarg.0
      callvirt instance int32 ${coreLibraryReference}System.String::get_Length()
      bge.s KCF_StripDone
      ldarg.0
      ldloc.1
      callvirt instance char ${coreLibraryReference}System.String::get_Chars(int32)
      stloc.2
      ldloc.2
      ldc.i4.s 96
      bne.un.s KCF_StripAppend
      ldloc.1
      ldc.i4.1
      add
      stloc.1
    KCF_StripDigits:
      ldloc.1
      ldarg.0
      callvirt instance int32 ${coreLibraryReference}System.String::get_Length()
      bge.s KCF_StripNext
      ldarg.0
      ldloc.1
      callvirt instance char ${coreLibraryReference}System.String::get_Chars(int32)
      call bool ${coreLibraryReference}System.Char::IsDigit(char)
      brfalse.s KCF_StripNext
      ldloc.1
      ldc.i4.1
      add
      stloc.1
      br.s KCF_StripDigits
    KCF_StripAppend:
      ldloc.0
      ldloc.2
      callvirt instance class ${coreLibraryReference}System.Text.StringBuilder ${coreLibraryReference}System.Text.StringBuilder::Append(char)
      pop
      ldloc.1
      ldc.i4.1
      add
      stloc.1
      br.s KCF_StripNext
    KCF_StripDone:
      ldloc.0
      callvirt instance string ${coreLibraryReference}System.Object::ToString()
      ret
    }
  }
        """.trimIndent()
    }

    private fun mappedExceptionIdBody(coreLibraryReference: String): String {
        val entries = DotNetMappedExceptions.entries.entries
            .mapNotNull { mapEntry ->
                (mapEntry.value as? DotNetMappedExceptions.Entry.Mapped)?.let {
                    Triple(mapEntry.key.asString(), it, it.classifierTypeId)
                }
            }
            .filterNot { triple -> triple.third == DotNetKotlinExceptionTypeId.THROWABLE }
        // Each comparison needs to skip over its own true label. Rebuild the straight-line chain
        // with a distinct continuation so false never falls into the matching return.
        return buildString {
            for (indexedEntry in entries.withIndex()) {
                val index = indexedEntry.index
                val triple = indexedEntry.value
                val entry = triple.second
                val id = triple.third
                val typeRef = entry.constructorTypeRef(coreLibraryReference).runtimeLocalTypeRef()
                if (entries.take(index).any { previousTriple ->
                        previousTriple.second.constructorTypeRef(coreLibraryReference).runtimeLocalTypeRef() == typeRef
                    }) continue
                val next = "KCF_MappedExceptionNext_${id.name}"
                appendLine("      ldarg.0")
                appendLine("      ldtoken $typeRef")
                appendLine("      call class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::GetTypeFromHandle(")
                appendLine("          valuetype ${coreLibraryReference}System.RuntimeTypeHandle)")
                appendLine("      call bool ${coreLibraryReference}System.Type::op_Equality(")
                appendLine("          class ${coreLibraryReference}System.Type, class ${coreLibraryReference}System.Type)")
                appendLine("      brfalse.s $next")
                appendLine("      ldc.i4 ${id.abiValue}")
                appendLine("      ret")
                appendLine("    $next:")
            }
        }.trimEnd()
    }

    private fun createExceptionBody(coreLibraryReference: String): String = buildString {
        for (mapEntry in DotNetMappedExceptions.entries.entries) {
            val mapped = mapEntry.value as? DotNetMappedExceptions.Entry.Mapped ?: continue
            val id = mapped.classifierTypeId
            val label = "KCF_CreateException_${id.name}"
            appendLine("      ldarg.0")
            appendLine("      ldc.i4 ${id.abiValue}")
            appendLine("      beq $label")
        }
        appendLine("      newobj instance void ${coreLibraryReference}System.InvalidOperationException::.ctor()")
        appendLine("      throw")
        for (mapEntry in DotNetMappedExceptions.entries.entries) {
            val fqName = mapEntry.key
            val mapped = mapEntry.value as? DotNetMappedExceptions.Entry.Mapped ?: continue
            val id = mapped.classifierTypeId
            val simpleName = fqName.shortName().asString()
            appendLine("    KCF_CreateException_${id.name}:")
            appendLine("      ldarg.1")
            appendLine("      ldstr ${simpleName.toIlStringLiteral()}")
            appendLine("      ldstr ${fqName.asString().toIlStringLiteral()}")
            appendLine("      ldc.i4 ${DotNetKClassClassifierKind.EXCEPTION.abiValue}")
            appendLine("      ldarg.0")
            appendLine("      newobj instance void Kotlin.KClassImpl::.ctor(")
            appendLine("          class ${coreLibraryReference}System.Type, string, string, int32, int32)")
            appendLine("      ret")
        }
    }.trimEnd()

    private fun dynamicExactTypeCases(coreLibraryReference: String): String {
        val cases = buildList {
            add(Triple("${coreLibraryReference}System.Boolean", "Boolean", "kotlin.Boolean"))
            add(Triple("${coreLibraryReference}System.SByte", "Byte", "kotlin.Byte"))
            add(Triple("${coreLibraryReference}System.Int16", "Short", "kotlin.Short"))
            add(Triple("${coreLibraryReference}System.Int32", "Int", "kotlin.Int"))
            add(Triple("${coreLibraryReference}System.Int64", "Long", "kotlin.Long"))
            add(Triple("${coreLibraryReference}System.Single", "Float", "kotlin.Float"))
            add(Triple("${coreLibraryReference}System.Double", "Double", "kotlin.Double"))
            add(Triple("${coreLibraryReference}System.Char", "Char", "kotlin.Char"))
            add(Triple("${coreLibraryReference}System.String", "String", "kotlin.String"))
            add(Triple("${coreLibraryReference}System.Object", "Any", "kotlin.Any"))
            add(Triple("Kotlin.Unit", "Unit", "kotlin.Unit"))
            for (entry in DotNetPrimitiveArrays.entries) {
                add(
                    Triple(
                        entry.wrapperTypeRef.runtimeLocalTypeRef(),
                        entry.wrapperSimpleName,
                        entry.kotlinFqName.asString(),
                    )
                )
            }
        }
        return buildString {
            for (index in cases.indices) {
                val case = cases[index]
                val typeRef = case.first
                val simpleName = case.second
                val qualifiedName = case.third
                val next = "KCF_ExactTypeNext$index"
                appendLine("ldloc.0")
                appendLine("ldtoken $typeRef")
                appendLine("call class ${coreLibraryReference}System.Type ${coreLibraryReference}System.Type::GetTypeFromHandle(")
                appendLine("    valuetype ${coreLibraryReference}System.RuntimeTypeHandle)")
                appendLine("call bool ${coreLibraryReference}System.Type::op_Equality(")
                appendLine("    class ${coreLibraryReference}System.Type, class ${coreLibraryReference}System.Type)")
                appendLine("brfalse.s $next")
                appendLine("ldloc.0")
                appendLine("ldstr ${simpleName.toIlStringLiteral()}")
                appendLine("ldstr ${qualifiedName.toIlStringLiteral()}")
                appendLine("ldc.i4 ${DotNetKClassClassifierKind.EXACT.abiValue}")
                appendLine("ldc.i4.0")
                appendLine("call class Kotlin.KClass Kotlin.Runtime.Internal.KClassFactory::'Create'(")
                appendLine("    class ${coreLibraryReference}System.Type, string, string, int32, int32)")
                appendLine("ret")
                appendLine("$next:")
            }
        }.trimEnd()
    }

    private fun String.runtimeLocalTypeRef(): String =
        removePrefix("[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]")

    private fun String.toIlStringLiteral(): String = buildString(length + 2) {
        append('"')
        for (character in this@toIlStringLiteral) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun serializedCustomAttributeString(value: String): List<Int> {
        val bytes = value.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff }
        val size = bytes.size
        val length = when {
            size <= 0x7f -> listOf(size)
            size <= 0x3fff -> listOf(0x80 or (size shr 8), size and 0xff)
            size <= 0x1fffffff -> listOf(
                0xc0 or (size shr 24),
                (size shr 16) and 0xff,
                (size shr 8) and 0xff,
                size and 0xff,
            )
            else -> error("local KClass name is too large for a custom attribute")
        }
        return length + bytes
    }
}
