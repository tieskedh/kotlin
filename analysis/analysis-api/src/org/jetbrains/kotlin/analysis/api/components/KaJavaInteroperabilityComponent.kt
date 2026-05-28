// LATEST_LV_DIFFERENCE
/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.components

import com.intellij.psi.*
import org.jetbrains.kotlin.analysis.api.*
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeMappingMode
import org.jetbrains.kotlin.load.kotlin.TypeMappingMode
import org.jetbrains.kotlin.name.Name
import org.jetbrains.org.objectweb.asm.Type

@KaSessionComponentImplementationDetail
@SubclassOptInRequired(KaSessionComponentImplementationDetail::class)
public interface KaJavaInteroperabilityComponent : KaSessionComponent {
    /**
     * Converts the given [KaType] to a [PsiType] in the context of the [useSitePosition].
     *
     * [PsiType] is JVM conception, so this method will return `null` for non-JVM platforms, unless [allowNonJvmPlatforms] is set.
     *
     * @receiver The [KaType] to convert.
     *
     * @param useSitePosition Determines whether the given [KaType] needs to be approximated.
     * For instance, if the given type is local but the use site is in the same local scope, we do not need to approximate the local type.
     * However, when exposed to the public as a return type, the resulting type must be approximated accordingly.
     *
     * @param allowErrorTypes Determines whether the [KaType] should still be converted if it contains an error type. When this option is
     * `false`, the result will be `null` if the [KaType] contains an error type. When `true`, erroneous types will be replaced with the
     * `error.NonExistentClass` type.
     *
     * @param suppressWildcards Indicates whether wildcards in type arguments should be suppressed. This option works similar to adding a
     * [JvmSuppressWildcards] annotation to the containing declaration.
     *
     * - `true` means they should be suppressed.
     * - `false` means they should appear.
     * - `null` means that the default applies, where wildcard suppression/appearance is determined by type annotations.
     *
     * @param preserveAnnotations Whether annotations from the original [KaType] should be included in the resulting [PsiType] with an
     * appropriate conversion.
     *
     * @param allowNonJvmPlatforms Whether the [PsiType] should be computed even for non-JVM modules. The flag provides no validity
     * guarantees – the returned type may be unresolvable from Java, or `null`.
     */
    @KaExperimentalApi
    public fun KaType.asPsiType(
        useSitePosition: PsiElement,
        allowErrorTypes: Boolean,
        mode: KaTypeMappingMode = KaTypeMappingMode.DEFAULT,
        isAnnotationMethod: Boolean = false,
        suppressWildcards: Boolean? = null,
        preserveAnnotations: Boolean = true,
        allowNonJvmPlatforms: Boolean = false,
    ): PsiType?

    /**
     * Converts the given [PsiType] to a [KaType] in the context of the [useSitePosition].
     *
     * [useSitePosition] clarifies how to resolve some parts of the [PsiType]. For instance, it can be used to collect type parameters and
     * apply them during the conversion.
     *
     * @receiver The [PsiType] to be converted.
     *
     * @return The converted [KaType], or `null` if conversion is not possible. For example, [PsiType] might not be resolvable.
     */
    @KaExperimentalApi
    public fun PsiType.asKaType(useSitePosition: PsiElement): KaType?

    /**
     * Convert the given [KaType] to a JVM type descriptor with the [KaTypeMappingMode.DEFAULT].
     * To learn more about JVM descriptors, check out the
     * [JVM specification](https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-4.3).
     */
    @KaExperimentalApi
    public fun KaType.mapToJvmTypeDescriptor(): String

    /**
     * Convert the given [KaType] to a JVM [ASM](https://asm.ow2.io) type.
     *
     * @see TypeMappingMode
     */
    @Deprecated("Use 'mapToJvmTypeDescriptor' instead.", level = DeprecationLevel.HIDDEN)
    @KaExperimentalApi
    @KaNoContextParameterBridgeRequired
    public fun KaType.mapToJvmType(mode: TypeMappingMode = TypeMappingMode.DEFAULT): Type

    /**
     * Whether the given [KaType] is backed by a single JVM primitive type.
     */
    @KaExperimentalApi
    public val KaType.isPrimitiveBacked: Boolean

    /**
     * Converts the given [KaClassSymbol] to Java [PsiClass] in the context of the [useSiteModule].
     *
     * The resulting [PsiClass] is the view on the given Kotlin class from Java.
     * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
     * Since the produced [PsiClass] is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
     *
     * [PsiClass]es are used to represent the following Kotlin declarations:
     * - Regular named class declarations
     * - Companion objects
     * - Enum entry initializers
     *
     * If [useSiteModule] is not a JVM module or the provided [KaClassSymbol] is not visible from Java, returns `null`.
     *
     * ### Example:
     * The following Kotlin class:
     * ```kotlin
     * interface MyInterface {
     *     val property: String
     *
     *     fun function(argument: Int): Int
     * }
     * ```
     *
     * Is seen as the following [PsiClass] from Java:
     * ```java
     * public interface MyInterface {
     *     @org.jetbrains.annotations.NotNull()
     *     java.lang.String getProperty();
     *
     *     int function(int argument);
     * }
     * ```
     *
     * The following Kotlin enum class:
     * ```kotlin
     * package example
     *
     * enum class MyEnum {
     *     A {
     *         fun foo() {}
     *     },
     *     B,
     * }
     * ```
     *
     * Is seen as the following [PsiClass] from Java:
     * ```java
     * public enum MyEnum {
     *     A // PsiField
     *     {
     *         A();                     // Anonymous initializer PsiClass
     *         public final void foo(); //
     *     },
     *
     *     B; // Does not have an anonymous initializer class, just PsiField
     *
     *     public static kotlin.enums.EnumEntries<example.MyEnum> getEntries();
     *     public static example.MyEnum [] values();
     *     public static example.MyEnum valueOf(java.lang.String) throws java.lang.IllegalArgumentException, java.lang.NullPointerException;
     *     private  MyEnum();
     * }
     * ```
     */
    @KaExperimentalApi
    public fun KaClassSymbol.asPsiClass(): PsiClass?

    /**
     * Converts the given [KaFileSymbol] to Java facade [PsiClass] in the context of the [useSiteModule].
     *
     * The resulting [PsiClass] is the view on the given Kotlin file from Java. E.g., `main.kt` file is converted to `MainKt` class facade.
     * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
     * Since the produced [PsiClass] is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
     *
     * Note that the produced facade class only stores non-class declarations. Each Kotlin class is mapped to its own top-level [PsiClass].
     *
     * If [useSiteModule] is not a JVM module or the provided [KaFileSymbol] is not supported, returns `null`.
     *
     * Examples of non-supported files are:
     * - Scripts
     * - Files with no top-level callables
     *
     * ### Example:
     * The following Kotlin file:
     * ```kotlin
     * // MyFile.kt
     * class MyClass
     *
     * fun foo(t: Int) {}
     *
     * val x: Int = 0
     * ```
     *
     * Is seen as the following [PsiClass] from Java:
     * ```java
     * public final class MyFileKt { // Doesn't contain `MyClass` declaration
     *     private static final int x = 0;
     *
     *     public static int getX();
     *
     *     public static void foo(int);
     * }
     * ```
     *
     * @see KaScriptSymbol.asFacadePsiClass
     */
    @KaExperimentalApi
    public fun KaFileSymbol.asFacadePsiClass(): PsiClass?

    /**
     * Converts the given [KaScriptSymbol] to Java facade [PsiClass] in the context of the [useSiteModule].
     *
     * The resulting [PsiClass] is the view on the given Kotlin script from Java.
     * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
     * Since the produced [PsiClass] is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
     *
     * In contrast to [KaFileSymbol.asFacadePsiClass], regular Kotlin classes in scripts are mapped to nested [PsiClass]es.
     *
     * If [useSiteModule] is not a JVM module or the provided [KaScriptSymbol] comes from a code fragment, returns `null`.
     *
     * ### Example:
     * The following Kotlin script:
     * ```kotlin
     * // MyScript.kts
     * println("Hello, World!")
     *
     * val x = 1
     *
     * class MyClass {
     *     fun bar() {}
     * }
     * ```
     *
     * Is seen as the following [PsiClass] from Java:
     * ```java
     * public final class MyScript extends kotlin.script.templates.standard.ScriptTemplateWithArgs {
     *     public static void main(java.lang.String[]);
     *
     *     public MyScript(java.lang.String[]);
     *
     *     private final int x = 1;
     *     public int getX();
     *
     *     public static final class MyClass {
     *         public MyClass();
     *         public void bar();
     *     }
     * }
     * ```
     *
     * @see KaFileSymbol.asFacadePsiClass
     */
    @KaExperimentalApi
    public fun KaScriptSymbol.asFacadePsiClass(): PsiClass?

    /**
     * Converts the given [KaFunctionSymbol] to Java [PsiMethod]s in the context of the [useSiteModule].
     *
     * The resulting list is the view on the given Kotlin declaration from Java and contains all [PsiMethod]s produced by [this].
     *
     * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
     * Since the produced list is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
     *
     * If [useSiteModule] is not a JVM module or the provided [KaFunctionSymbol] is not visible from Java, returns an empty list.
     *
     * ### Example:
     * The following Kotlin function:
     * ```kotlin
     * // MyFile.kt
     * @JvmOverloads
     * @JvmName("jvmFoo")
     * fun foo(a: Int, b: Int = 1) {}
     * ```
     *
     * Is seen as the following [PsiMethod]s from Java:
     * ```java
     * public final class MyFileKt {
     *     @kotlin.jvm.JvmName(name = "jvmFoo")
     *     @kotlin.jvm.JvmOverloads()
     *     public static void jvmFoo(int);
     *
     *     @kotlin.jvm.JvmName(name = "jvmFoo")
     *     @kotlin.jvm.JvmOverloads()
     *     public static void jvmFoo(int, int);
     * }
     * ```
     */
    @KaExperimentalApi
    public fun KaFunctionSymbol.asPsiMethods(): List<PsiMethod>

    /**
     * Converts the given [KaTypeParameterSymbol] to Java [PsiTypeParameter]s in the context of the [useSiteModule].
     *
     * The resulting list is the view on the given Kotlin type parameter from Java and contains all [PsiTypeParameter]s produced by [this].
     * Multiple type parameters might be produced when the enclosing Kotlin declaration is mapped to multiple Java PSI declarations.
     *
     * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
     * Since the produced list is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
     *
     * If [useSiteModule] is not a JVM module or the provided [KaTypeParameterSymbol] is not visible from Java, returns an empty list.
     *
     * ### Example:
     * The following Kotlin type parameter `T`:
     * ```kotlin
     * // MyFile.kt
     * @JvmOverloads
     * @JvmName("jvmFoo")
     * fun <T> foo(a: T, b: Int = 1) {}
     * ```
     *
     * Is seen as the following [PsiTypeParameter]s from Java:
     * ```java
     * public final class MyFileKt {
     *     @kotlin.jvm.JvmName(name = "jvmFoo")
     *     @kotlin.jvm.JvmOverloads()
     *     public static final <T> void jvmFoo(T a);
     * //                      ^^^
     * //                  PsiTypeParameter
     *     @kotlin.jvm.JvmName(name = "jvmFoo")
     *     @kotlin.jvm.JvmOverloads()
     *     public static final <T> void jvmFoo(T a, int b);
     * //                      ^^^
     * //                  PsiTypeParameter
     * }
     * ```
     */
    @KaExperimentalApi
    public fun KaTypeParameterSymbol.asPsiTypeParameters(): List<PsiTypeParameter>

    /**
     * Converts the given [KaParameterSymbol] to Java [PsiParameter]s in the context of the [useSiteModule].
     *
     * The resulting list is the view on the given Kotlin parameter from Java and contains all [PsiParameter]s produced by [this].
     * Multiple parameters might be produced when the enclosing Kotlin declaration is mapped to multiple Java PSI declarations.
     *
     * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
     * Since the produced list is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
     *
     * If [useSiteModule] is not a JVM module or the provided [KaParameterSymbol] is not visible from Java, returns an empty list.
     *
     * ### Example:
     * The following Kotlin parameter `a`:
     * ```kotlin
     * // MyFile.kt
     * @JvmOverloads
     * @JvmName("jvmFoo")
     * fun foo(a: Int, b: Int = 1) {}
     * ```
     *
     * Is seen as the following [PsiParameter]s from Java:
     * ```java
     * public final class MyFileKt {
     *     @kotlin.jvm.JvmName(name = "jvmFoo")
     *     @kotlin.jvm.JvmOverloads()
     *     public static void jvmFoo(int a);
     * //                               ^^^
     * //                          PsiParameter
     *     @kotlin.jvm.JvmName(name = "jvmFoo")
     *     @kotlin.jvm.JvmOverloads()
     *     public static void jvmFoo(int a, int b);
     * //                               ^^^
     * //                          PsiParameter
     * }
     * ```
     */
    @KaExperimentalApi
    public fun KaParameterSymbol.asPsiParameters(): List<PsiParameter>

    /**
     * Converts the given [KaBackingFieldSymbol] to Java [PsiField] in the context of the [useSiteModule].
     *
     * The resulting [PsiField] is the view on the given Kotlin backing field from Java.
     * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
     * Since the produced [PsiField] is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
     *
     * If [useSiteModule] is not a JVM module or the provided [KaBackingFieldSymbol] is not visible from Java as a [PsiField],
     * returns `null`.
     *
     * ### Example:
     * The following Kotlin property with implicit backing field:
     * ```kotlin
     * // MyFile.kt
     * val x: Int = 0 // implicit backing field
     * ```
     *
     * Is seen as the following [PsiMethod] getter and [PsiField] from Java:
     * ```java
     * public final class MyFileKt {
     *     private static final int x = 0; // PsiField
     *
     *     public static int getX();
     * }
     * ```
     */
    @KaExperimentalApi
    public fun KaBackingFieldSymbol.asPsiField(): PsiField?

    /**
     * Converts the given [KaClassSymbol] to Java [PsiField] in the context of the [useSiteModule].
     *
     * The resulting [PsiField] is the view on the given Kotlin declaration from Java.
     * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
     * Since the produced [PsiField] is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
     *
     * [KaClassSymbol] has be mapped to [PsiField] in several cases:
     * - `INSTANCE` field for object declarations
     * - `Companion` field for companion objects
     *
     * If [useSiteModule] is not a JVM module or the provided [KaClassSymbol] is not visible from Java as a [PsiField],
     * returns `null`.
     *
     * ### Example:
     * The following Kotlin companion object
     * ```kotlin
     * package example
     *
     * class MyClass {
     *     companion object {
     *         fun foo() {}
     *     }
     * }
     * ```
     *
     * Is seen as the following [PsiField] from Java:
     * ```java
     * public final class MyClass {
     *     public static final example.MyClass.Companion Companion; // `Companion` instance field
     *
     *     public MyClass();
     *
     *     public static final class Companion {
     *         private  Companion();
     *
     *         public final void foo();
     *     }
     * }
     * ```
     *
     * The following Kotlin object
     * ```kotlin
     * package example
     *
     * object MyObject {
     *     fun foo() {}
     * }
     * ```
     *
     * Is seen as the following [PsiField] from Java:
     * ```java
     * public final class MyObject {
     *     public static final example.MyObject INSTANCE; // `INSTANCE` field
     *
     *     private MyObject();
     *
     *     public void foo();
     * }
     * ```
     */
    @KaExperimentalApi
    public fun KaClassSymbol.asPsiField(): PsiField?

    /**
     * Converts the given [KaEnumEntrySymbol] to Java [PsiEnumConstant] in the context of the [useSiteModule].
     *
     * The resulting [PsiEnumConstant] is the view on the given Kotlin enum entry from Java.
     * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
     * Since the produced [PsiEnumConstant] is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
     *
     * If [useSiteModule] is not a JVM module or the provided [KaEnumEntrySymbol] is not visible from Java as a [PsiEnumConstant],
     * returns `null`.
     *
     * ### Example:
     * The following Kotlin enum entries
     * ```kotlin
     * enum class MyEnum {
     *     A,
     *     B
     * }
     * ```
     *
     * Are seen as the following [PsiEnumConstant]s from Java:
     * ```java
     * public enum MyEnum {
     *     A, // PsiEnumConstant for MyEnum.A
     *     B; // PsiEnumConstant for MyEnum.B
     *
     *     public static kotlin.enums.EnumEntries<example.MyEnum> getEntries();
     *     public static example.MyEnum [] values();
     *     public static example.MyEnum valueOf(java.lang.String) throws java.lang.IllegalArgumentException, java.lang.NullPointerException;
     *     private  MyEnum();
     * }
     * ```
     */
    @KaExperimentalApi
    public fun KaEnumEntrySymbol.asPsiField(): PsiEnumConstant?

    /**
     * A [KaNamedClassSymbol] for the given [PsiClass], or `null` for anonymous classes, local classes, type parameters (which are also
     * [PsiClass]es), and Kotlin light classes.
     */
    public val PsiClass.namedClassSymbol: KaNamedClassSymbol?

    /**
     * A [KaCallableSymbol] for the given [PsiMember] method or field, or `null` for local declarations and Kotlin light classes.
     */
    public val PsiMember.callableSymbol: KaCallableSymbol?

    /**
     * The containing JVM class name for the given [KaCallableSymbol].
     *
     * The property works for both source and library declarations.
     * The JVM class name is a fully qualified name separated by dots, such as `foo.bar.Baz.Companion`.
     *
     * Applicable only to JVM modules, and common modules with JVM targets.
     * [containingJvmClassName] is always `null` all other kinds of modules.
     */
    @KaExperimentalApi
    public val KaCallableSymbol.containingJvmClassName: String?

    /**
     * The JVM getter method name for the given [KaPropertySymbol].
     * The behavior is undefined for modules other than JVM and common (with a JVM implementation).
     */
    @KaExperimentalApi
    public val KaPropertySymbol.javaGetterName: Name

    /**
     * The JVM setter method name for the given [KaPropertySymbol].
     * The behavior is undefined for modules other than JVM and common (with a JVM implementation).
     */
    @KaExperimentalApi
    public val KaPropertySymbol.javaSetterName: Name?
}

/**
 * Convert the given [KaType] to a JVM [ASM](https://asm.ow2.io) type.
 *
 * @see TypeMappingMode
 */
@Deprecated("Use 'mapToJvmTypeDescriptor' instead.", level = DeprecationLevel.HIDDEN)
@KaExperimentalApi
@KaContextParameterApi
@KaCustomContextParameterBridge
context(session: KaSession)
public fun KaType.mapToJvmType(mode: TypeMappingMode = TypeMappingMode.DEFAULT): Type {
    @OptIn(KaSessionComponentImplementationDetail::class)
    return KaJavaInteroperabilityComponent::class.java.getDeclaredMethod("mapToJvmType", KaType::class.java, TypeMappingMode::class.java)
        .invoke(session, this, mode) as Type
}

/**
 * Converts the given [KaType] to a [PsiType] in the context of the [useSitePosition].
 *
 * [PsiType] is JVM conception, so this method will return `null` for non-JVM platforms, unless [allowNonJvmPlatforms] is set.
 *
 * @receiver The [KaType] to convert.
 *
 * @param useSitePosition Determines whether the given [KaType] needs to be approximated.
 * For instance, if the given type is local but the use site is in the same local scope, we do not need to approximate the local type.
 * However, when exposed to the public as a return type, the resulting type must be approximated accordingly.
 *
 * @param allowErrorTypes Determines whether the [KaType] should still be converted if it contains an error type. When this option is
 * `false`, the result will be `null` if the [KaType] contains an error type. When `true`, erroneous types will be replaced with the
 * `error.NonExistentClass` type.
 *
 * @param suppressWildcards Indicates whether wildcards in type arguments should be suppressed. This option works similar to adding a
 * [JvmSuppressWildcards] annotation to the containing declaration.
 *
 * - `true` means they should be suppressed.
 * - `false` means they should appear.
 * - `null` means that the default applies, where wildcard suppression/appearance is determined by type annotations.
 *
 * @param preserveAnnotations Whether annotations from the original [KaType] should be included in the resulting [PsiType] with an
 * appropriate conversion.
 *
 * @param allowNonJvmPlatforms Whether the [PsiType] should be computed even for non-JVM modules. The flag provides no validity
 * guarantees – the returned type may be unresolvable from Java, or `null`.
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public fun KaType.asPsiType(
    useSitePosition: PsiElement,
    allowErrorTypes: Boolean,
    mode: KaTypeMappingMode = KaTypeMappingMode.DEFAULT,
    isAnnotationMethod: Boolean = false,
    suppressWildcards: Boolean? = null,
    preserveAnnotations: Boolean = true,
    allowNonJvmPlatforms: Boolean = false,
): PsiType? {
    return with(session) {
        asPsiType(
            useSitePosition = useSitePosition,
            allowErrorTypes = allowErrorTypes,
            mode = mode,
            isAnnotationMethod = isAnnotationMethod,
            suppressWildcards = suppressWildcards,
            preserveAnnotations = preserveAnnotations,
            allowNonJvmPlatforms = allowNonJvmPlatforms,
        )
    }
}

/**
 * Converts the given [PsiType] to a [KaType] in the context of the [useSitePosition].
 *
 * [useSitePosition] clarifies how to resolve some parts of the [PsiType]. For instance, it can be used to collect type parameters and
 * apply them during the conversion.
 *
 * @receiver The [PsiType] to be converted.
 *
 * @return The converted [KaType], or `null` if conversion is not possible. For example, [PsiType] might not be resolvable.
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public fun PsiType.asKaType(useSitePosition: PsiElement): KaType? {
    return with(session) {
        asKaType(
            useSitePosition = useSitePosition,
        )
    }
}

/**
 * Convert the given [KaType] to a JVM type descriptor with the [KaTypeMappingMode.DEFAULT].
 * To learn more about JVM descriptors, check out the
 * [JVM specification](https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-4.3).
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public fun KaType.mapToJvmTypeDescriptor(): String {
    return with(session) {
        mapToJvmTypeDescriptor()
    }
}

/**
 * Whether the given [KaType] is backed by a single JVM primitive type.
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public val KaType.isPrimitiveBacked: Boolean
    get() = with(session) { isPrimitiveBacked }

/**
 * Converts the given [KaClassSymbol] to Java [PsiClass] in the context of the [useSiteModule].
 *
 * The resulting [PsiClass] is the view on the given Kotlin class from Java.
 * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
 * Since the produced [PsiClass] is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
 *
 * [PsiClass]es are used to represent the following Kotlin declarations:
 * - Regular named class declarations
 * - Companion objects
 * - Enum entry initializers
 *
 * If [useSiteModule] is not a JVM module or the provided [KaClassSymbol] is not visible from Java, returns `null`.
 *
 * ### Example:
 * The following Kotlin class:
 * ```kotlin
 * interface MyInterface {
 *     val property: String
 *
 *     fun function(argument: Int): Int
 * }
 * ```
 *
 * Is seen as the following [PsiClass] from Java:
 * ```java
 * public interface MyInterface {
 *     @org.jetbrains.annotations.NotNull()
 *     java.lang.String getProperty();
 *
 *     int function(int argument);
 * }
 * ```
 *
 * The following Kotlin enum class:
 * ```kotlin
 * package example
 *
 * enum class MyEnum {
 *     A {
 *         fun foo() {}
 *     },
 *     B,
 * }
 * ```
 *
 * Is seen as the following [PsiClass] from Java:
 * ```java
 * public enum MyEnum {
 *     A // PsiField
 *     {
 *         A();                     // Anonymous initializer PsiClass
 *         public final void foo(); //
 *     },
 *
 *     B; // Does not have an anonymous initializer class, just PsiField
 *
 *     public static kotlin.enums.EnumEntries<example.MyEnum> getEntries();
 *     public static example.MyEnum [] values();
 *     public static example.MyEnum valueOf(java.lang.String) throws java.lang.IllegalArgumentException, java.lang.NullPointerException;
 *     private  MyEnum();
 * }
 * ```
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public fun KaClassSymbol.asPsiClass(): PsiClass? {
    return with(session) {
        asPsiClass()
    }
}

/**
 * Converts the given [KaFileSymbol] to Java facade [PsiClass] in the context of the [useSiteModule].
 *
 * The resulting [PsiClass] is the view on the given Kotlin file from Java. E.g., `main.kt` file is converted to `MainKt` class facade.
 * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
 * Since the produced [PsiClass] is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
 *
 * Note that the produced facade class only stores non-class declarations. Each Kotlin class is mapped to its own top-level [PsiClass].
 *
 * If [useSiteModule] is not a JVM module or the provided [KaFileSymbol] is not supported, returns `null`.
 *
 * Examples of non-supported files are:
 * - Scripts
 * - Files with no top-level callables
 *
 * ### Example:
 * The following Kotlin file:
 * ```kotlin
 * // MyFile.kt
 * class MyClass
 *
 * fun foo(t: Int) {}
 *
 * val x: Int = 0
 * ```
 *
 * Is seen as the following [PsiClass] from Java:
 * ```java
 * public final class MyFileKt { // Doesn't contain `MyClass` declaration
 *     private static final int x = 0;
 *
 *     public static int getX();
 *
 *     public static void foo(int);
 * }
 * ```
 *
 * @see KaScriptSymbol.asFacadePsiClass
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public fun KaFileSymbol.asFacadePsiClass(): PsiClass? {
    return with(session) {
        asFacadePsiClass()
    }
}

/**
 * Converts the given [KaScriptSymbol] to Java facade [PsiClass] in the context of the [useSiteModule].
 *
 * The resulting [PsiClass] is the view on the given Kotlin script from Java.
 * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
 * Since the produced [PsiClass] is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
 *
 * In contrast to [KaFileSymbol.asFacadePsiClass], regular Kotlin classes in scripts are mapped to nested [PsiClass]es.
 *
 * If [useSiteModule] is not a JVM module or the provided [KaScriptSymbol] comes from a code fragment, returns `null`.
 *
 * ### Example:
 * The following Kotlin script:
 * ```kotlin
 * // MyScript.kts
 * println("Hello, World!")
 *
 * val x = 1
 *
 * class MyClass {
 *     fun bar() {}
 * }
 * ```
 *
 * Is seen as the following [PsiClass] from Java:
 * ```java
 * public final class MyScript extends kotlin.script.templates.standard.ScriptTemplateWithArgs {
 *     public static void main(java.lang.String[]);
 *
 *     public MyScript(java.lang.String[]);
 *
 *     private final int x = 1;
 *     public int getX();
 *
 *     public static final class MyClass {
 *         public MyClass();
 *         public void bar();
 *     }
 * }
 * ```
 *
 * @see KaFileSymbol.asFacadePsiClass
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public fun KaScriptSymbol.asFacadePsiClass(): PsiClass? {
    return with(session) {
        asFacadePsiClass()
    }
}

/**
 * Converts the given [KaFunctionSymbol] to Java [PsiMethod]s in the context of the [useSiteModule].
 *
 * The resulting list is the view on the given Kotlin declaration from Java and contains all [PsiMethod]s produced by [this].
 *
 * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
 * Since the produced list is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
 *
 * If [useSiteModule] is not a JVM module or the provided [KaFunctionSymbol] is not visible from Java, returns an empty list.
 *
 * ### Example:
 * The following Kotlin function:
 * ```kotlin
 * // MyFile.kt
 * @JvmOverloads
 * @JvmName("jvmFoo")
 * fun foo(a: Int, b: Int = 1) {}
 * ```
 *
 * Is seen as the following [PsiMethod]s from Java:
 * ```java
 * public final class MyFileKt {
 *     @kotlin.jvm.JvmName(name = "jvmFoo")
 *     @kotlin.jvm.JvmOverloads()
 *     public static void jvmFoo(int);
 *
 *     @kotlin.jvm.JvmName(name = "jvmFoo")
 *     @kotlin.jvm.JvmOverloads()
 *     public static void jvmFoo(int, int);
 * }
 * ```
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public fun KaFunctionSymbol.asPsiMethods(): List<PsiMethod> {
    return with(session) {
        asPsiMethods()
    }
}

/**
 * Converts the given [KaTypeParameterSymbol] to Java [PsiTypeParameter]s in the context of the [useSiteModule].
 *
 * The resulting list is the view on the given Kotlin type parameter from Java and contains all [PsiTypeParameter]s produced by [this].
 * Multiple type parameters might be produced when the enclosing Kotlin declaration is mapped to multiple Java PSI declarations.
 *
 * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
 * Since the produced list is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
 *
 * If [useSiteModule] is not a JVM module or the provided [KaTypeParameterSymbol] is not visible from Java, returns an empty list.
 *
 * ### Example:
 * The following Kotlin type parameter `T`:
 * ```kotlin
 * // MyFile.kt
 * @JvmOverloads
 * @JvmName("jvmFoo")
 * fun <T> foo(a: T, b: Int = 1) {}
 * ```
 *
 * Is seen as the following [PsiTypeParameter]s from Java:
 * ```java
 * public final class MyFileKt {
 *     @kotlin.jvm.JvmName(name = "jvmFoo")
 *     @kotlin.jvm.JvmOverloads()
 *     public static final <T> void jvmFoo(T a);
 * //                      ^^^
 * //                  PsiTypeParameter
 *     @kotlin.jvm.JvmName(name = "jvmFoo")
 *     @kotlin.jvm.JvmOverloads()
 *     public static final <T> void jvmFoo(T a, int b);
 * //                      ^^^
 * //                  PsiTypeParameter
 * }
 * ```
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public fun KaTypeParameterSymbol.asPsiTypeParameters(): List<PsiTypeParameter> {
    return with(session) {
        asPsiTypeParameters()
    }
}

/**
 * Converts the given [KaParameterSymbol] to Java [PsiParameter]s in the context of the [useSiteModule].
 *
 * The resulting list is the view on the given Kotlin parameter from Java and contains all [PsiParameter]s produced by [this].
 * Multiple parameters might be produced when the enclosing Kotlin declaration is mapped to multiple Java PSI declarations.
 *
 * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
 * Since the produced list is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
 *
 * If [useSiteModule] is not a JVM module or the provided [KaParameterSymbol] is not visible from Java, returns an empty list.
 *
 * ### Example:
 * The following Kotlin parameter `a`:
 * ```kotlin
 * // MyFile.kt
 * @JvmOverloads
 * @JvmName("jvmFoo")
 * fun foo(a: Int, b: Int = 1) {}
 * ```
 *
 * Is seen as the following [PsiParameter]s from Java:
 * ```java
 * public final class MyFileKt {
 *     @kotlin.jvm.JvmName(name = "jvmFoo")
 *     @kotlin.jvm.JvmOverloads()
 *     public static void jvmFoo(int a);
 * //                               ^^^
 * //                          PsiParameter
 *     @kotlin.jvm.JvmName(name = "jvmFoo")
 *     @kotlin.jvm.JvmOverloads()
 *     public static void jvmFoo(int a, int b);
 * //                               ^^^
 * //                          PsiParameter
 * }
 * ```
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public fun KaParameterSymbol.asPsiParameters(): List<PsiParameter> {
    return with(session) {
        asPsiParameters()
    }
}

/**
 * Converts the given [KaBackingFieldSymbol] to Java [PsiField] in the context of the [useSiteModule].
 *
 * The resulting [PsiField] is the view on the given Kotlin backing field from Java.
 * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
 * Since the produced [PsiField] is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
 *
 * If [useSiteModule] is not a JVM module or the provided [KaBackingFieldSymbol] is not visible from Java as a [PsiField],
 * returns `null`.
 *
 * ### Example:
 * The following Kotlin property with implicit backing field:
 * ```kotlin
 * // MyFile.kt
 * val x: Int = 0 // implicit backing field
 * ```
 *
 * Is seen as the following [PsiMethod] getter and [PsiField] from Java:
 * ```java
 * public final class MyFileKt {
 *     private static final int x = 0; // PsiField
 *
 *     public static int getX();
 * }
 * ```
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public fun KaBackingFieldSymbol.asPsiField(): PsiField? {
    return with(session) {
        asPsiField()
    }
}

/**
 * Converts the given [KaClassSymbol] to Java [PsiField] in the context of the [useSiteModule].
 *
 * The resulting [PsiField] is the view on the given Kotlin declaration from Java.
 * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
 * Since the produced [PsiField] is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
 *
 * [KaClassSymbol] has be mapped to [PsiField] in several cases:
 * - `INSTANCE` field for object declarations
 * - `Companion` field for companion objects
 *
 * If [useSiteModule] is not a JVM module or the provided [KaClassSymbol] is not visible from Java as a [PsiField],
 * returns `null`.
 *
 * ### Example:
 * The following Kotlin companion object
 * ```kotlin
 * package example
 *
 * class MyClass {
 *     companion object {
 *         fun foo() {}
 *     }
 * }
 * ```
 *
 * Is seen as the following [PsiField] from Java:
 * ```java
 * public final class MyClass {
 *     public static final example.MyClass.Companion Companion; // `Companion` instance field
 *
 *     public MyClass();
 *
 *     public static final class Companion {
 *         private  Companion();
 *
 *         public final void foo();
 *     }
 * }
 * ```
 *
 * The following Kotlin object
 * ```kotlin
 * package example
 *
 * object MyObject {
 *     fun foo() {}
 * }
 * ```
 *
 * Is seen as the following [PsiField] from Java:
 * ```java
 * public final class MyObject {
 *     public static final example.MyObject INSTANCE; // `INSTANCE` field
 *
 *     private MyObject();
 *
 *     public void foo();
 * }
 * ```
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public fun KaClassSymbol.asPsiField(): PsiField? {
    return with(session) {
        asPsiField()
    }
}

/**
 * Converts the given [KaEnumEntrySymbol] to Java [PsiEnumConstant] in the context of the [useSiteModule].
 *
 * The resulting [PsiEnumConstant] is the view on the given Kotlin enum entry from Java.
 * [useSiteModule] for the current [KaSession] is used to provide proper actualizations for `expect` declarations.
 * Since the produced [PsiEnumConstant] is a JVM-specific representation, [useSiteModule] is expected to be a JVM one.
 *
 * If [useSiteModule] is not a JVM module or the provided [KaEnumEntrySymbol] is not visible from Java as a [PsiEnumConstant],
 * returns `null`.
 *
 * ### Example:
 * The following Kotlin enum entries
 * ```kotlin
 * enum class MyEnum {
 *     A,
 *     B
 * }
 * ```
 *
 * Are seen as the following [PsiEnumConstant]s from Java:
 * ```java
 * public enum MyEnum {
 *     A, // PsiEnumConstant for MyEnum.A
 *     B; // PsiEnumConstant for MyEnum.B
 *
 *     public static kotlin.enums.EnumEntries<example.MyEnum> getEntries();
 *     public static example.MyEnum [] values();
 *     public static example.MyEnum valueOf(java.lang.String) throws java.lang.IllegalArgumentException, java.lang.NullPointerException;
 *     private  MyEnum();
 * }
 * ```
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public fun KaEnumEntrySymbol.asPsiField(): PsiEnumConstant? {
    return with(session) {
        asPsiField()
    }
}

/**
 * A [KaNamedClassSymbol] for the given [PsiClass], or `null` for anonymous classes, local classes, type parameters (which are also
 * [PsiClass]es), and Kotlin light classes.
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaContextParameterApi
context(session: KaSession)
public val PsiClass.namedClassSymbol: KaNamedClassSymbol?
    get() = with(session) { namedClassSymbol }

/**
 * A [KaCallableSymbol] for the given [PsiMember] method or field, or `null` for local declarations and Kotlin light classes.
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaContextParameterApi
context(session: KaSession)
public val PsiMember.callableSymbol: KaCallableSymbol?
    get() = with(session) { callableSymbol }

/**
 * The containing JVM class name for the given [KaCallableSymbol].
 *
 * The property works for both source and library declarations.
 * The JVM class name is a fully qualified name separated by dots, such as `foo.bar.Baz.Companion`.
 *
 * Applicable only to JVM modules, and common modules with JVM targets.
 * [containingJvmClassName] is always `null` all other kinds of modules.
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public val KaCallableSymbol.containingJvmClassName: String?
    get() = with(session) { containingJvmClassName }

/**
 * The JVM getter method name for the given [KaPropertySymbol].
 * The behavior is undefined for modules other than JVM and common (with a JVM implementation).
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public val KaPropertySymbol.javaGetterName: Name
    get() = with(session) { javaGetterName }

/**
 * The JVM setter method name for the given [KaPropertySymbol].
 * The behavior is undefined for modules other than JVM and common (with a JVM implementation).
 */
// Auto-generated bridge. DO NOT EDIT MANUALLY!
@KaExperimentalApi
@KaContextParameterApi
context(session: KaSession)
public val KaPropertySymbol.javaSetterName: Name?
    get() = with(session) { javaSetterName }
