package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.lower.AbstractSuspendFunctionsLowering
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_STATIC_INITIALIZER
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_STATIC_INITIALIZATION_ENTRY
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_COVARIANT_RETURN_BRIDGE
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_INTERFACE_DEFAULT_FORWARDER
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_INTERFACE_DEFAULT_SLOT_BRIDGE
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_INTERFACE_DEFAULT_HELPER
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_GENERIC_INTERFACE_DEFAULT_FORWARDER_TARGET
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_GENERIC_INTERFACE_DEFAULT_ERASED_ADAPTER
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_GENERIC_OWNER_CAPABILITY_DISPATCHER
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_GENERIC_OWNER_FOREIGN_OVERRIDE_PROBE
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_ENUM_ENTRY_CONSTRUCTOR
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_VALUE_CLASS_BOX_HELPER
import org.jetbrains.kotlin.backend.dotnet.lower.DOTNET_VALUE_CLASS_UNBOX_HELPER
import org.jetbrains.kotlin.backend.dotnet.lower.dotNetGenericInterfaceBridgeMemberViewOrNull
import org.jetbrains.kotlin.backend.dotnet.lower.isDotNetGenericInterfaceBridge
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrBreakContinue
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrCatch
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.AbstractIrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.defaultType as typeParameterDefaultType
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.isFalseConst
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isOriginallyLocalDeclaration
import org.jetbrains.kotlin.ir.util.isPublishedApi
import org.jetbrains.kotlin.ir.util.isSubclassOf
import org.jetbrains.kotlin.ir.util.isTrueConst
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.StandardClassIds

/** A successfully rendered method and its complete IL text. */
internal class DotNetIlRenderedMethod(val ilText: String)

/**
 * Renders a single function — a top-level `static` one, a user-class constructor, an instance
 * member method/accessor, or a synthetic `<clinit>` (file- or class-parented, rendered as the
 * owning class's `.cctor`, see [DOTNET_STATIC_INITIALIZER]) — into IL text. The body is rendered into its own fresh buffer first, so
 * `.maxstack` and the `.locals init` block are computed from what was actually emitted; any
 * unsupported construct aborts the render with [DotNetIlUnsupportedException].
 *
 * For an [IrConstructor] the implicit `this` is CLR argument slot 0 and the declared parameters
 * shift up by one ([DotNetIlMethodContext]'s `firstArgumentIndex`); the constructor's
 * [functionInfo] carries the owning class's IL name as its class name and a `void` signature.
 * An instance member method needs no offset at all: its dispatch receiver IS `parameters[0]`, so
 * the plain zip already assigns it slot 0 (probe-confirmed CLR argument numbering).
 */
internal class DotNetIlMethodCodegen(
    private val function: IrFunction,
    private val functionInfo: DotNetIlFunctionInfo,
    private val isEntryPoint: Boolean,
    private val availableFunctions: Map<IrSimpleFunction, DotNetIlFunctionInfo>,
    private val intrinsicMethods: DotNetIlIntrinsicMethods,
    private val typeMapper: DotNetIlTypeMapper,
    facadeClassInfoByFile: Map<IrFile, DotNetIlClassInfo> = emptyMap(),
    private val covariantReturnImplementations: Set<IrSimpleFunction> = emptySet(),
    private val genericOwnerCallRouteTraceHook: DotNetGenericOwnerCallRouteTraceHook? = null,
    private val genericOwnerCapabilitySlots: Map<IrSimpleFunction, IrSimpleFunction> = emptyMap(),
    private val genericOwnerDirectForeignOverrideDispatch:
        DotNetGenericOwnerDirectForeignOverrideDispatch? = null,
    private val genericOwnerForeignOverrideProbeTarget: IrSimpleFunction? = null,
) {
    private val signature = functionInfo.signature
    private val methodContext = DotNetIlMethodContext(
        function.parameters,
        signature.parameterTypes,
        typeMapper,
        firstArgumentIndex = if (function is IrConstructor) 1 else 0,
        erasedRuntimeParameters = if (
            function is IrSimpleFunction &&
            (function.isDotNetErasedCallableInvoke() || function.isDotNetErasedPropertyAccess())
        ) {
            function.parameters.drop(if (signature.hasThis) 1 else 0).mapTo(linkedSetOf()) { it.symbol }
        } else {
            emptySet()
        },
    ).apply {
        if (function is IrConstructor) {
            val constructedClass = function.constructedClass
            // Kotlin-owned generic classes map their open default type to the same erased owner;
            // imported CLR generic classes retain their native open instantiation.
            val thisType =
                if (constructedClass.typeParameters.isEmpty() ||
                    constructedClass.defaultType.dotNetValueClassOrNull() != null
                ) {
                    // A generic Kotlin value class still has one non-generic nominal box owner.
                    // Its constructor initializes that owner; only exact values outside the
                    // constructor use the substituted underlying carrier.
                    DotNetIlValueType.UserClass(functionInfo.owner)
                }
                else typeMapper.toDotNetIlValueType(constructedClass.defaultType)
                    ?: dotNetUnsupported(
                        "constructor of generic class '${constructedClass.name.asString()}' whose open type cannot be mapped"
                    )
            registerThis(constructedClass.thisReceiver!!.symbol, thisType)
        }
    }
    private val expressionCodegen =
        DotNetIlExpressionCodegen(
            methodContext, availableFunctions, intrinsicMethods, typeMapper, facadeClassInfoByFile,
            functionInfo.owner,
            object : DotNetIlStatementScopeEmitter {
                override fun emitTryExpression(expression: IrTry, expectedType: DotNetIlValueType) =
                    this@DotNetIlMethodCodegen.emitTryExpression(expression, expectedType)

                override fun emitBlockExpression(block: IrContainerExpression, expectedType: DotNetIlValueType) =
                    emitValueExpression(block, expectedType)

                override fun emitControlFlowValueExpression(
                    expression: IrExpression,
                    expectedType: DotNetIlValueType,
                ) = emitValueExpression(expression, expectedType)

                override fun emitUnitEffectExpression(expression: IrExpression) {
                    emitVoidExpression(expression)
                    if (!methodContext.isTerminated) {
                        methodContext.emit(DotNetRuntimeTypes.unitInstanceLoadInstruction, pushes = 1)
                    }
                }
            },
            genericOwnerCapabilitySlots,
        )

    /**
     * The join label of returns that crossed protected regions and the synthetic return-value
     * local shared by those returns and ordinary returns that must clear older expression operands.
     * Both are created lazily; see [emitReturnAcrossRegions] and [emitReturnValueOnCleanStack].
     */
    private var returnJoinLabel: String? = null
    private var returnValueSlot: DotNetIlSlot.Local? = null

    fun render(): DotNetIlRenderedMethod {
        // An abstract interface or class member has no body by definition: its `.method` block
        // stays empty — no `.maxstack`, no `.locals`, no instructions (spelling probe-verified,
        // `ifaceprobe_s1`/`_s2` and `abstractprobe_s1`).
        val isAbstractMember = function is IrSimpleFunction && function.modality == Modality.ABSTRACT
        if (!isAbstractMember) {
            try {
                if (genericOwnerCallRouteTraceHook == null) {
                    when {
                        genericOwnerDirectForeignOverrideDispatch != null ->
                            emitGenericOwnerDirectForeignOverrideDispatch(
                                genericOwnerDirectForeignOverrideDispatch
                            )
                        genericOwnerForeignOverrideProbeTarget != null ->
                            emitGenericOwnerForeignOverrideProbe(genericOwnerForeignOverrideProbeTarget)
                        else -> emitBody()
                    }
                } else {
                    emitGenericOwnerCallRouteTraceHook(genericOwnerCallRouteTraceHook)
                }
            } catch (failure: IllegalStateException) {
                throw IllegalStateException(
                    "${failure.message} while rendering ${function.render()}",
                    failure,
                )
            }
        }
        val ilText = buildString {
            // The printed parameter list never contains the implicit `this` of an instance
            // method: the dispatch-receiver pair of the zip is dropped (an IrConstructor's
            // parameter list carries no dispatch receiver to begin with).
            val parameters = function.parameters.zip(signature.parameterTypes)
                .drop(if (signature.hasThis) 1 else 0)
                .joinToString(", ") { [parameter, type] ->
                    "${type.nameInSignature} ${parameter.name.asString().toIlIdentifier()}"
                }
            if (function is IrConstructor) {
                // `.ctor` is a bare keyword, not a quoted identifier; the spelling including the
                // specialname/rtspecialname flags is ilasm-probe-verified. The visibility follows
                // the Kotlin declaration: an object's primary constructor is private from the
                // frontend, and a public `.ctor` would let any other .NET code mint a second
                // instance of the singleton (the `newobj` of a private `.ctor` from the same
                // class's `.cctor` is probe-verified, objprobe_s1). A COMPANION's Kotlin-private
                // constructor is the one exception, emitted as IL `assembly` instead — see
                // [dotNetMemberVisibility].
                val visibility = function.dotNetMemberVisibility()
                appendLine("  .method $visibility hidebysig specialname rtspecialname instance void .ctor($parameters) cil managed")
            } else if (function.origin == DOTNET_STATIC_INITIALIZER) {
                // The synthetic `<clinit>` (see DotNetStaticInitializersLowering) — file-parented
                // for a facade, class-parented for an object class — renders as the CLR class
                // initializer; like `.ctor`, `.cctor` is a bare keyword. The spelling is
                // ilasm-probe-verified (statprobe_s1, objprobe_s1), including that the CLR runs
                // it before the first active use of the non-beforefieldinit class.
                appendLine("  .method private hidebysig specialname rtspecialname static void .cctor() cil managed")
            } else {
                // Instance member methods differ from static ones only in the `instance` flag;
                // property accessors additionally carry `specialname`, binding them to the
                // `.property` metadata (both spellings are ilasm-probe-verified). Source-private
                // companion members remain private; illegal enclosing-to-nested calls were
                // redirected to synthetic assembly bridges by DotNetPrivateNestedAccessLowering.
                // Members of the inheritance model additionally carry virtual flags — see
                // [dotNetVirtualFlags].
                val visibility = function.dotNetMemberVisibility()
                val specialname = if (function.isPropertyAccessor) "specialname " else ""
                val dispatch = if (signature.hasThis) "instance" else "static"
                val methodName = functionInfo.physicalMethodName
                    ?: (function as IrSimpleFunction).dotNetIlMethodName()
                // A generic METHOD declares its formal list between the name and parameters:
                // `<'T'>`, or `<(class 'Base', class 'Mark') 'T'>` with supported constraints.
                // Class/interface members use the identical formal spelling while the owner may
                // independently carry `!n` parameters (genprobe_s1/_s8, genconstraintprobe_s1,
                // genmemberprobe_s1).
                val genericParameters = function.typeParameters
                    .renderDotNetIlGenericParameters(typeMapper)
                    .orEmpty()
                appendLine(
                    "  .method $visibility hidebysig $specialname" +
                            "${function.dotNetVirtualFlags()}$dispatch " +
                            "${signature.returnType.nameInSignature} " +
                            "${methodName.toIlIdentifier()}$genericParameters($parameters) cil managed"
                )
            }
            appendLine("  {")
            if (function.isDotNetCompilerAbiSurface()) {
                appendLine("    ${DotNetCompilerAbi.markerAttributeIl()}")
                appendLine(
                    "    ${DotNetCompilerAbi.editorBrowsableNeverAttributeIl(typeMapper.coreLibrary.editorBrowsableReference)}"
                )
            }
            for (attribute in function.dotNetRuntimeAttributes(typeMapper)) {
                appendLine("    $attribute")
            }
            function.parameters.zip(signature.parameterTypes)
                .drop(if (signature.hasThis) 1 else 0)
                .forEachIndexed { index, parameterAndType ->
                    val parameter = parameterAndType.first
                    val attributes = parameter.dotNetRuntimeAttributes(typeMapper)
                    if (attributes.isNotEmpty()) {
                        appendLine("    .param [${index + 1}]")
                        for (attribute in attributes) appendLine("    $attribute")
                    }
                }
            val valueClassImplementation =
                (function as? IrSimpleFunction)?.dotNetValueClassImplementationSourceOrNull()
            if (valueClassImplementation != null) {
                // Common copies the source member's origin onto its static value-class
                // implementation. MethodImpl rows remain owned by the retained nominal wrapper;
                // the static carrier method has no virtual/interface slot of its own.
            } else if (function.origin == DOTNET_INTERFACE_DEFAULT_FORWARDER || function.origin == DOTNET_INTERFACE_DEFAULT_SLOT_BRIDGE) {
                appendInterfaceDefaultSlotOverrides()
            } else if (
                function.origin == DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE ||
                function.origin == DOTNET_GENERIC_INTERFACE_DEFAULT_ERASED_ADAPTER
            ) {
                appendGenericInterfaceCanonicalBridgeOverride()
            } else if (function.origin.dotNetGenericInterfaceBridgeMemberViewOrNull != null) {
                appendGenericInterfaceTypedBridgeOverride()
            } else if (function.origin == DOTNET_COVARIANT_RETURN_BRIDGE) {
                appendCovariantReturnBridgeOverride()
            } else if (function.origin == DOTNET_GENERIC_OWNER_CAPABILITY_DISPATCHER) {
                appendGenericOwnerCapabilityOverride()
            } else {
                appendRenamedErasedInterfaceSlotOverrides()
            }
            if (!isAbstractMember) {
                if (isEntryPoint) {
                    appendLine("    .entrypoint")
                }
                appendLine("    .maxstack ${methodContext.maxStack}")
                appendLocals()
                append(methodContext.renderBody())
            }
            appendLine("  }")
        }
        return DotNetIlRenderedMethod(ilText)
    }

    /**
     * The CLR visibility of a Kotlin callable. Public/internal/protected map to
     * public/assembly/family. A normal private member maps to private; a private top-level
     * callable maps to assembly because code in another CLR type generated from the same Kotlin
     * file may call it. The facade type and Kotlin metadata keep that callable non-public across
     * module boundaries.
     *
     * For Kotlin-private declarations:
     * - A lifted local function is IL `assembly` on a file facade so lifted sibling types can
     *   call it, and IL `private` under a class because CLR nested→enclosing private access works.
     * - Kotlin-private companion declarations remain IL `private`. CLR grants
     *   nested→enclosing private access but NOT the reverse (objprobe_s7a/objprobe_s7b), so
     *   [DotNetPrivateNestedAccessLowering][org.jetbrains.kotlin.backend.dotnet.lower.DotNetPrivateNestedAccessLowering]
     *   redirects enclosing→companion calls through assembly-visible `access$...` methods or a
     *   constructor-marker overload. This follows the mature synthetic-accessor architecture
     *   without widening the source declaration. Ordinary named nested classes need no such
     *   accessor: Kotlin does not allow their enclosing class to call private nested members.
     * - A private nested object is physically different: object lowering creates a private
     *   singleton field on that nested type and Kotlin references it from the enclosing type.
     *   [DotNetPrivateNestedAccessLowering][org.jetbrains.kotlin.backend.dotnet.lower.DotNetPrivateNestedAccessLowering]
     *   redirects that synthesized field read through an assembly-visible getter while retaining
     *   the private field.
     *   The reverse direction needs nothing because CLR nested→enclosing private access works
     *   directly (objprobe_s7a, nestedprobe_s2).
     */
    private fun IrFunction.dotNetMemberVisibility(): String {
        if (origin == DOTNET_VALUE_CLASS_BOX_HELPER || origin == DOTNET_VALUE_CLASS_UNBOX_HELPER) return "public"
        // Common's explicit coroutine lowering starts a named suspend function from its
        // file-facade method by invoking the generated state-machine override. JS has no
        // enforced member visibility at that call site; CLR `family` does and therefore rejects
        // the same sibling-type call. Give only the generated override CLR `family or assembly`
        // visibility: the `family` half preserves (and cannot illegally narrow) the protected
        // virtual slot, while the `assembly` half is the JVM package-private analogue needed by
        // the generated file facade. The private state-machine class still exposes no callable
        // surface outside its DLL.
        if (origin == AbstractSuspendFunctionsLowering.DECLARATION_ORIGIN_COROUTINE_IMPL_INVOKE) return "famorassem"
        // CLR enclosing types cannot call private members of their nested types. The enum entry
        // implementation class itself remains nested-private; only its compiler-generated
        // constructor is assembly-visible so the enclosing enum's `.cctor` can instantiate it.
        if (origin == DOTNET_ENUM_ENTRY_CONSTRUCTOR) return "assembly"
        if (origin == DOTNET_INTERFACE_DEFAULT_FORWARDER) return "private"
        if (origin == DOTNET_INTERFACE_DEFAULT_SLOT_BRIDGE) return "private"
        if (origin == DOTNET_GENERIC_INTERFACE_DEFAULT_FORWARDER_TARGET) return "private"
        if (origin == DOTNET_GENERIC_INTERFACE_DEFAULT_ERASED_ADAPTER) return "private"
        if (origin.isDotNetGenericInterfaceBridge) return "private"
        if (origin == DOTNET_COVARIANT_RETURN_BRIDGE) return "private"
        // The embedded KLIB declaration is public Kotlin API, but the surviving CLR method is
        // only the Native-shaped deterministic throwing remainder. A Kotlin consumer must inline
        // it; publishing it as a normal C# generic would falsely advertise executable semantics.
        if (isInline && typeParameters.any { it.isReified }) return "assembly"
        if (isDotNetInlineOnly()) return "assembly"
        // @PublishedApi stdlib carriers used by cross-module inline bodies need CLR-public
        // constructors even when their Kotlin declaration is internal. Ordinary stdlib classes
        // retain each constructor's source visibility: in particular StringBuilder's public
        // constructors remain C#-constructible while its private storage constructor stays private.
        if (
            this is IrConstructor &&
            constructedClass.isDotNetStdlibImplementation &&
            constructedClass.isPublishedApi()
        ) return "public"
        if (this is IrSimpleFunction && isDotNetStdlibImplementation) return "public"
        if (isOriginallyLocalDeclaration) return if (parent is IrFile) "assembly" else "private"
        if (isDotNetPublishedCompilerAbi()) return "public"
        if (
            this is IrConstructor &&
            constructedClass.modality == Modality.SEALED &&
            visibility == DescriptorVisibilities.PROTECTED
        ) {
            // CLR has no top-level `sealed hierarchy` metadata equivalent. Restrict construction
            // to derived types in this assembly: Kotlin subclasses in the defining module keep
            // working, while a foreign C# assembly cannot derive merely because the metadata
            // class itself must be CLR `abstract` rather than CLR `sealed`.
            return "famandassem"
        }
        return when (visibility) {
            DescriptorVisibilities.PUBLIC -> "public"
            DescriptorVisibilities.INTERNAL -> "assembly"
            DescriptorVisibilities.PROTECTED -> "family"
            DescriptorVisibilities.PRIVATE -> {
                val isTopLevelCallable = parent is IrFile ||
                        (parent as? org.jetbrains.kotlin.ir.declarations.IrProperty)?.parent is IrFile
                if (isTopLevelCallable) "assembly" else "private"
            }
            else -> error("Internal .NET backend error: unsupported function visibility '$visibility'")
        }
    }

    /** Whether this method is CLR-public for compiler linking rather than Kotlin/C# user API. */
    private fun IrFunction.isDotNetCompilerAbiSurface(): Boolean =
        dotNetMemberVisibility() == "public" &&
                (origin == IrDeclarationOrigin.SYNTHETIC_ACCESSOR ||
                        origin == IrDeclarationOrigin.FUNCTION_FOR_DEFAULT_PARAMETER ||
                        origin == DOTNET_INTERFACE_DEFAULT_HELPER ||
                        origin == DOTNET_STATIC_INITIALIZATION_ENTRY ||
                        origin == DOTNET_VALUE_CLASS_BOX_HELPER ||
                        origin == DOTNET_VALUE_CLASS_UNBOX_HELPER ||
                        (this is IrSimpleFunction && name.asString().endsWith("\$default")) ||
                        visibility != DescriptorVisibilities.PUBLIC)

    /** `@PublishedApi internal` is Kotlin-internal metadata backed by CLR-public compiler ABI. */
    private fun IrFunction.isDotNetPublishedCompilerAbi(): Boolean =
        visibility == DescriptorVisibilities.INTERNAL &&
                (isPublishedApi() ||
                        (this as? IrSimpleFunction)?.correspondingPropertySymbol?.owner?.isPublishedApi() == true ||
                        (this as? IrSimpleFunction)
                            ?.dotNetValueClassImplementationSourceOrNull()
                            ?.isPublishedApi() == true)

    /** Mirrors JVM's package-private physical treatment through CLR assembly visibility. */
    private fun IrFunction.isDotNetInlineOnly(): Boolean =
        (isInline && hasAnnotation(StandardClassIds.Annotations.InlineOnly)) ||
                (this is IrSimpleFunction &&
                        correspondingPropertySymbol?.owner?.hasAnnotation(StandardClassIds.Annotations.InlineOnly) == true)

    /** Maps a hidden helper-backed class or DIM bridge to every physical Kotlin interface slot. */
    private fun StringBuilder.appendInterfaceDefaultSlotOverrides() {
        val bridge = function as? IrSimpleFunction
            ?: error("Internal .NET backend error: an interface-default slot bridge is not a simple function")
        check(bridge.overriddenSymbols.isNotEmpty()) {
            "Internal .NET backend error: an interface-default slot bridge has no slots"
        }
        bridge.overriddenSymbols.forEach { overriddenSymbol ->
            val overridden = overriddenSymbol.owner
            val overrideInfo = availableFunctions[overridden]
                ?: typeMapper.referencedFunctionInfoOrNull(overridden)
                ?: dotNetUnsupported("interface-default slot is unavailable")
            if (overrideInfo.signature.returnType != signature.returnType) {
                // A differently returned slot is owned by DotNetCovariantReturnBridgeLowering.
                // Emitting it here would attach one precise body to an incompatible declaration
                // in addition to the correctly typed forwarding MethodImpl.
                return@forEach
            }
            check(overridden.typeParameters.size == bridge.typeParameters.size) {
                "Internal .NET backend error: interface-default slot bridge changed method arity"
            }
            val physicalMethodName = overrideInfo.physicalMethodName ?: overridden.dotNetIlMethodName()
            appendLine(
                "    .override method " +
                        overrideInfo.renderOverrideMethodReference(
                            physicalMethodName,
                            genericArity = bridge.typeParameters.size,
                        )
            )
        }
    }

    /**
     * Maps an ordinary class method to a non-generic interface slot when stable erased-carrier
     * naming makes their CLR names differ. This occurs when one Kotlin override simultaneously
     * reuses an unmangled generic-base slot (`Base<T>.f(T)`) and implements an interface slot whose
     * parameter is directly classified (an exception or erased Kotlin generic interface). The class vtable
     * name remains authoritative; an explicit MethodImpl row preserves the additional interface
     * decision without copying the body.
     *
     * Erased Kotlin generic-interface slots are owned by their canonical bridge and are
     * deliberately excluded here. An independently mapped host capability, such as
     * `IComparable<T>`, is handled by [appendGenericInterfaceTypedBridgeOverride]. A signature
     * difference also remains bridge territory; attaching an incompatible body through
     * MethodImpl would merely defer the error to type loading.
     */
    private fun StringBuilder.appendRenamedErasedInterfaceSlotOverrides() {
        val implementation = function as? IrSimpleFunction ?: return
        val owner = implementation.parent as? IrClass ?: return
        if (owner.isInterface) return
        val implementationName = functionInfo.physicalMethodName ?: implementation.dotNetIlMethodName()
        for (overriddenSymbol in implementation.overriddenSymbols) {
            val overridden = overriddenSymbol.owner
            val interfaceClass = overridden.parent as? IrClass ?: continue
            if (!interfaceClass.isInterface || interfaceClass.isDotNetGenericInterfaceDeclaration) continue
            if (overridden.dotNetErasedCarrierMethodNameOrNull(typeMapper::isErasedGenericClass) == null) continue
            val overrideInfo = availableFunctions[overridden]
                ?: typeMapper.referencedFunctionInfoOrNull(overridden)
                ?: dotNetUnsupported("renamed exception interface slot is unavailable")
            val slotName = overrideInfo.physicalMethodName ?: overridden.dotNetIlMethodName()
            if (slotName == implementationName) continue
            val hasErasedCarrierName = listOf(slotName, implementationName).any { methodName ->
                "__KotlinException__" in methodName || "__KotlinErased__" in methodName
            }
            if (!hasErasedCarrierName) continue
            if (
                overrideInfo.signature.returnType != signature.returnType ||
                overrideInfo.signature.renderParameterTypes() != signature.renderParameterTypes()
            ) {
                continue
            }
            appendLine("    .override method ${overrideInfo.renderMethodReference(slotName)}")
        }
    }

    /** Binds one private erased adapter to every canonical identity selected by Kotlin override resolution. */
    private fun StringBuilder.appendGenericInterfaceCanonicalBridgeOverride() {
        val bridge = function as? IrSimpleFunction
            ?: error("Internal .NET backend error: a generic interface bridge is not a simple function")
        check(bridge.overriddenSymbols.isNotEmpty()) {
            "Internal .NET backend error: a generic interface bridge has no canonical slots"
        }
        val canonicalTypeMapper = typeMapper.canonicalGenericInterfaceSignatureView()
        for (overriddenSymbol in bridge.overriddenSymbols) {
            val overridden = overriddenSymbol.owner
            val interfaceClass = overridden.parent as? IrClass
                ?: error("Internal .NET backend error: a generic interface slot has no interface owner")
            val interfaceInfo = canonicalTypeMapper.classInfoOrNull(interfaceClass)
                ?: dotNetUnsupported("generic interface canonical identity is unavailable")
            check(interfaceInfo.typeParameterCount == 0) {
                "Internal .NET backend error: a canonical generic-interface identity must be non-generic"
            }
            check(overridden.typeParameters.size == bridge.typeParameters.size) {
                "Internal .NET backend error: canonical generic-interface override changed method arity"
            }
            val referencedInfo = canonicalTypeMapper.referencedFunctionInfoOrNull(overridden)
            val physicalMethodName = referencedInfo?.physicalMethodName ?: if (
                interfaceClass.isDotNetGenericInterfaceDeclaration
            ) {
                overridden.dotNetGenericInterfaceCanonicalMethodName()
            } else {
                overridden.dotNetIlMethodName()
            }
            val overrideInfo = referencedInfo ?: DotNetIlFunctionInfo(
                interfaceInfo,
                overridden.dotNetSignature(canonicalTypeMapper),
                physicalMethodName,
            )
            appendLine(
                "    .override method " +
                        overrideInfo.renderOverrideMethodReference(
                            physicalMethodName,
                            interfaceInfo.ilTypeRef,
                            bridge.typeParameters.size,
                        )
            )
        }
    }

    /** Binds a forwarding bridge to the closed declared or exact capability slot. */
    private fun StringBuilder.appendGenericInterfaceTypedBridgeOverride() {
        val bridge = function as? IrSimpleFunction
            ?: error("Internal .NET backend error: a typed generic interface bridge is not a simple function")
        val overridden = bridge.overriddenSymbols.singleOrNull()?.owner
            ?: error("Internal .NET backend error: a typed generic interface bridge has no unique slot")
        val interfaceClass = overridden.parent as? IrClass
            ?: error("Internal .NET backend error: a typed generic interface slot has no interface owner")
        val interfaceInfo = typeMapper.genericInterfaceInfoOrNull(interfaceClass)
            ?: dotNetUnsupported("generic interface typed capability is unavailable")
        val memberView = bridge.origin.dotNetGenericInterfaceBridgeMemberViewOrNull
            ?: error("Internal .NET backend error: typed generic interface bridge has no physical view")
        val capabilityInfo = interfaceInfo.classInfo(memberView.physicalView)
            ?: dotNetUnsupported("generic interface ${memberView.name.lowercase()} capability is unavailable")
        val bridgeClass = bridge.parent as? IrClass
            ?: error("Internal .NET backend error: a typed generic interface bridge has no class owner")
        val substitutor = AbstractIrTypeSubstitutor.forSuperClass(
            interfaceClass.symbol,
            bridgeClass.defaultType,
        ) ?: error(
            "Internal .NET backend error: '${bridgeClass.name}' is not a subtype of " +
                    "generic interface '${interfaceClass.name}'"
        )
        val signatureMapper = typeMapper.genericInterfaceSignatureView(memberView)
        val arguments = interfaceClass.typeParameters.map { parameter ->
            val argumentType = substitutor.substitute(parameter.typeParameterDefaultType)
            signatureMapper.toDotNetIlGenericArgumentType(argumentType)
                ?: dotNetUnsupported(
                    "typed generic interface argument '${argumentType.render()}' is unavailable"
                )
        }
        val ownerToken = DotNetIlValueType.GenericInstance(capabilityInfo, arguments).nameInSignature
        val overrideInfo = DotNetIlFunctionInfo(
            capabilityInfo,
            overridden.dotNetSignature(signatureMapper),
        )
        appendLine(
            "    .override method " +
                    overrideInfo.renderOverrideMethodReference(
                        typeMapper.genericInterfaceTypedMethodName(overridden),
                        ownerToken,
                        bridge.typeParameters.size,
                    )
        )
    }

    /** Binds one private generic-owner selector to its non-generic semantic interface slot. */
    private fun StringBuilder.appendGenericOwnerCapabilityOverride() {
        val dispatcher = function as? IrSimpleFunction
            ?: error("Internal .NET backend error: a generic-owner capability dispatcher is not a simple function")
        val slot = dispatcher.overriddenSymbols.singleOrNull()?.owner
            ?: error("Internal .NET backend error: a generic-owner capability dispatcher has no unique slot")
        val slotOwner = (slot.parent as? IrClass)?.takeIf(IrClass::isInterface)
            ?: error("Internal .NET backend error: a generic-owner capability slot has no interface owner")
        val overrideInfo = availableFunctions[slot]
            ?: typeMapper.referencedFunctionInfoOrNull(slot)
            ?: dotNetUnsupported("generic-owner capability slot is unavailable")
        // A local slot is parented by its synthetic non-generic IR interface. An un-emitted
        // separate-consumer stub deliberately remains parented by the logical generic owner so
        // KLIB identity and override discovery stay intact; its producer-bound MethodRef is still
        // authoritative. The physical owner, not that stub parent, is the ABI invariant.
        check(overrideInfo.owner.typeParameterCount == 0) {
            "Internal .NET backend error: a generic-owner semantic capability has a generic physical owner"
        }
        check(overrideInfo.signature.hasThis && signature.hasThis &&
                overrideInfo.signature.returnType == signature.returnType &&
                overrideInfo.signature.renderParameterTypes() == signature.renderParameterTypes()
        ) {
            "Internal .NET backend error: a generic-owner capability dispatcher changed its slot signature"
        }
        val physicalMethodName = overrideInfo.physicalMethodName ?: slot.dotNetIlMethodName()
        appendLine(
            "    .override method " +
                    overrideInfo.renderOverrideMethodReference(
                        physicalMethodName,
                        overrideInfo.owner.ilTypeRef,
                        dispatcher.typeParameters.size,
                    )
        )
    }

    /** Binds one exact-return forwarding method to its wider ordinary class or interface slot. */
    private fun StringBuilder.appendCovariantReturnBridgeOverride() {
        val bridge = function as? IrSimpleFunction
            ?: error("Internal .NET backend error: a covariant-return bridge is not a simple function")
        val overridden = bridge.overriddenSymbols.singleOrNull()?.owner
            ?: error("Internal .NET backend error: a covariant-return bridge has no unique slot")
        val overriddenOwner = overridden.parent as? IrClass
            ?: error("Internal .NET backend error: a covariant-return slot has no class owner")
        val bridgeOwner = bridge.parent as? IrClass
            ?: error("Internal .NET backend error: a covariant-return bridge has no class owner")
        val referencedInfo = availableFunctions[overridden]
            ?: typeMapper.referencedFunctionInfoOrNull(overridden)
            ?: dotNetUnsupported("covariant-return slot is unavailable")
        val ownerToken = if (referencedInfo.owner.typeParameterCount == 0) {
            referencedInfo.owner.ilTypeRef
        } else {
            val substitutor = AbstractIrTypeSubstitutor.forSuperClass(
                overriddenOwner.symbol,
                bridgeOwner.defaultType,
            ) ?: error(
                "Internal .NET backend error: '${bridgeOwner.name}' is not a subtype of " +
                        "covariant-return owner '${overriddenOwner.name}'"
            )
            val arguments = overriddenOwner.typeParameters.map { parameter ->
                val argumentType = substitutor.substitute(parameter.typeParameterDefaultType)
                typeMapper.toDotNetIlGenericArgumentType(argumentType)
                    ?: dotNetUnsupported("covariant-return owner argument '${argumentType.render()}' is unavailable")
            }
            DotNetIlValueType.GenericInstance(referencedInfo.owner, arguments).nameInSignature
        }
        val physicalMethodName = referencedInfo.physicalMethodName ?: overridden.dotNetIlMethodName()
        appendLine(
            "    .override method " +
                    referencedInfo.renderOverrideMethodReference(
                        physicalMethodName,
                        ownerToken,
                        bridge.typeParameters.size,
                    )
        )
    }

    /**
     * The virtual-slot flags of a member method's header, agreeing by construction with the
     * call-site dispatch predicate [isDotNetVirtual] (declaring a slot virtual and calling it
     * with plain `call` — or vice versa — must never diverge). All spellings and their flag
     * order are ilasm-probe-verified (`inheritprobe_s1`/`_s2`/`_s3`; `_s3` additionally showed
     * ilasm treats the flags as an unordered keyword set, so the emitter standardizes on the
     * s2-verified order; interface spellings `ifaceprobe_s1`–`_s4`):
     * - a new abstract interface or class member is `newslot abstract virtual` with an empty
     *   method block; an abstract override of a base-class slot is `abstract virtual` with NO
     *   `newslot` (`ifaceprobe_s1`/`_s2`, `abstractprobe_s1`);
     * - an `open` member that overrides nothing introduces a fresh slot: `newslot virtual`
     *   (`specialname newslot virtual` for accessors);
     * - a Kotlin `override` of a BASE-CLASS member REUSES the base slot: `virtual` with NO
     *   `newslot` — adding `newslot` would silently detach it from base-typed dispatch. CLR
     *   interface mapping follows the class vtable slot, so this also covers a derived override
     *   of an interface-implementing base member (`ifaceprobe_s4`), and a member overriding
     *   BOTH a base-class member and an interface member keeps the base slot;
     * - a Kotlin `override` of ONLY interface members introduces a fresh CLASS slot that the
     *   CLR's implicit interface mapping binds by name and signature: `newslot virtual` on an
     *   open implementer (`ifaceprobe_s4`), `newslot virtual final` on a final one — the exact
     *   Roslyn shape for an implicit implementation on a sealed class (`ifaceprobe_s1`; the
     *   implementation MUST be virtual even there: the plain non-virtual member shape
     *   load-poisons the type, `ifaceprobe_s1b`);
     * - a `final override` of a base-class member adds `final`: it still occupies the virtual
     *   slot (and still dispatches under `callvirt`) but seals it, the exact Roslyn shape for
     *   C# `sealed override`;
     * - everything else (final members of the final-class model, static facade methods) carries
     *   no virtual flags — the established plain-`call` model.
     */
    private fun IrFunction.dotNetVirtualFlags(): String {
        if ((this as? IrSimpleFunction)?.dotNetValueClassImplementationSourceOrNull() != null) return ""
        if (origin == DOTNET_INTERFACE_DEFAULT_FORWARDER) return "newslot virtual final "
        if (origin == DOTNET_INTERFACE_DEFAULT_SLOT_BRIDGE) return "newslot virtual final "
        if (origin == DOTNET_GENERIC_INTERFACE_DEFAULT_ERASED_ADAPTER) return "newslot virtual final "
        if (origin.isDotNetGenericInterfaceBridge) return "newslot virtual final "
        if (origin == DOTNET_COVARIANT_RETURN_BRIDGE) return "newslot virtual final "
        if (this !is IrSimpleFunction || !signature.hasThis) return ""
        if ((parent as? IrClass)?.isInterface == true) {
            return if (modality == Modality.ABSTRACT) "newslot abstract virtual " else "newslot virtual "
        }
        val abstractFlag = if (modality == Modality.ABSTRACT) "abstract " else ""
        val final = if (modality == Modality.FINAL) "final " else ""
        // Some built-in-interface fake-override chains do not retain kotlin.Any in
        // overriddenSymbols. dotNetAnyMethodOrNull recognizes the frontend-validated shape;
        // it must reuse System.Object's slot (virtual, never newslot) just like a direct Any
        // override, or interface-typed hashCode/toString/equals calls bypass the implementation.
        if (dotNetAnyMethodOrNull() != null) return "${abstractFlag}virtual $final"
        val overridesClassMember = overriddenSymbols.any { (it.owner.parent as? IrClass)?.isInterface != true }
        return when {
            this in covariantReturnImplementations -> "newslot ${abstractFlag}virtual $final"
            overridesClassMember -> "${abstractFlag}virtual $final"
            overriddenSymbols.isNotEmpty() -> "newslot ${abstractFlag}virtual $final"
            modality == Modality.ABSTRACT -> "newslot abstract virtual "
            isDotNetVirtual() -> "newslot virtual "
            else -> ""
        }
    }

    private fun StringBuilder.appendLocals() {
        val locals = methodContext.locals
        if (locals.isEmpty()) return

        appendLine("    .locals init (")
        for ([index, local] in locals.withIndex()) {
            val separator = if (index == locals.lastIndex) "" else ","
            appendLine("      [${local.index}] ${local.type.nameInSignature} ${local.name.toIlIdentifier()}$separator")
        }
        appendLine("    )")
    }

    private fun emitBody() {
        when (val body = function.body) {
            is IrBlockBody -> {
                body.statements.forEach { emitStatement(it) }
                // A dead trailing ret after a mid-body return is harmless.
                if (signature.returnType == DotNetIlReturnType.Void) {
                    methodContext.emitReturn()
                } else if (
                    !methodContext.isTerminated &&
                    function is IrSimpleFunction &&
                    function.isDotNetErasedObjectResult() &&
                    function.returnType.isUnit()
                ) {
                    // A Unit callable/property getter whose final operation is already a
                    // statement (not an IrReturn) falls through its block body. The physical
                    // erased slot still returns object, so materialize Unit exactly as
                    // emitReturnValue does for an explicit return. Without this epilogue ILAsm
                    // accepts the method, but the CLR rejects it as an invalid program.
                    expressionCodegen.emitRuntimeUnitInstance()
                    methodContext.emitReturn(pops = 1)
                }
            }
            is IrExpressionBody -> when (val returnType = signature.returnType) {
                is DotNetIlReturnType.Value -> {
                    emitReturnValue(body.expression, returnType.type)
                    if (!methodContext.isTerminated) methodContext.emitReturn(pops = 1)
                }
                DotNetIlReturnType.Void -> {
                    emitVoidExpression(body.expression)
                    methodContext.emitReturn()
                }
            }
            null -> dotNetUnsupported("function has no body")
            else -> dotNetUnsupported("unsupported function body shape ${body.javaClass.simpleName}")
        }
        emitReturnJoinEpilogue()
    }

    /**
     * Keeps raw Kotlin output semantics and the natural C# override slot coherent without
     * reflection. On this exact Kotlin declaration both virtual targets are unchanged. A direct
     * foreign subclass changes only the typed target; a Kotlin subclass changes both because the
     * compiler emits its paired semantic hook as well.
     */
    private fun emitGenericOwnerDirectForeignOverrideDispatch(
        dispatch: DotNetGenericOwnerDirectForeignOverrideDispatch,
    ) {
        check(function is IrSimpleFunction &&
                function.origin == DOTNET_GENERIC_OWNER_CAPABILITY_DISPATCHER &&
                signature.parameterTypes.size == 1 &&
                signature.returnType == DotNetIlReturnType.Value(DotNetIlValueType.Object)) {
            "Direct foreign override dispatch requires an object-returning no-input capability dispatcher"
        }
        val typedInfo = checkNotNull(availableFunctions[dispatch.typedEntry]) {
            "Direct foreign override dispatch lacks its typed MethodDef"
        }
        val semanticInfo = checkNotNull(availableFunctions[dispatch.semanticHook]) {
            "Direct foreign override dispatch lacks its semantic MethodDef"
        }
        check(typedInfo.owner == functionInfo.owner && semanticInfo.owner == functionInfo.owner &&
                dispatch.typedEntry.typeParameters.isEmpty() && dispatch.semanticHook.typeParameters.isEmpty()) {
            "Direct foreign override dispatch must compare one non-generic local owner family"
        }
        val ownerToken = if (functionInfo.owner.typeParameterCount == 0) {
            functionInfo.owner.ilTypeRef
        } else {
            DotNetIlValueType.GenericInstance(
                functionInfo.owner,
                List(functionInfo.owner.typeParameterCount) { index ->
                    DotNetIlValueType.TypeParameter(index, isMethodParameter = false)
                },
            ).nameInSignature
        }
        fun DotNetIlFunctionInfo.reference(target: IrSimpleFunction): String = renderMethodReference(
            physicalMethodName ?: target.dotNetIlMethodName(),
            ownerToken = ownerToken,
        )
        val probeInfo = checkNotNull(availableFunctions[dispatch.foreignOverrideProbe]) {
            "Direct foreign override dispatch lacks its virtual probe MethodDef"
        }
        val probeReference = probeInfo.reference(dispatch.foreignOverrideProbe)
        val typedReturn = (typedInfo.signature.returnType as? DotNetIlReturnType.Value)?.type
        check(typedInfo.signature.parameterTypes.size == 1 && probeInfo.signature.parameterTypes.size == 1 &&
                semanticInfo.signature.parameterTypes.size == 1 &&
                typedReturn is DotNetIlValueType.TypeParameter &&
                probeInfo.signature.returnType == DotNetIlReturnType.Value(DotNetIlValueType.Boolean) &&
                semanticInfo.signature.returnType == DotNetIlReturnType.Value(DotNetIlValueType.Object)) {
            "Direct foreign override dispatch requires paired () -> !T and () -> object slots"
        }

        // The virtual probe belongs to the most-derived Kotlin declaration. It reports whether a
        // later ordinary foreign subclass changed only the natural typed slot.
        methodContext.emit("ldarg.0", pushes = 1)
        methodContext.emit("callvirt $probeReference", pops = 1, pushes = 1)
        val semanticLabel = methodContext.nextLabel("semanticOutput")
        methodContext.emitBranch("brfalse", semanticLabel, pops = 1)

        methodContext.emit("ldarg.0", pushes = 1)
        methodContext.emit(
            typedInfo.renderCallInstruction(
                typedInfo.physicalMethodName ?: dispatch.typedEntry.dotNetIlMethodName(),
                virtual = true,
                ownerToken = ownerToken,
            ),
            pops = 1,
            pushes = 1,
        )
        methodContext.emit("box ${typedReturn.nameInSignature}", pops = 1, pushes = 1)
        methodContext.emitReturn(pops = 1)

        methodContext.emitLabel(semanticLabel)
        methodContext.emit("ldarg.0", pushes = 1)
        methodContext.emit(
            semanticInfo.renderCallInstruction(
                semanticInfo.physicalMethodName ?: dispatch.semanticHook.dotNetIlMethodName(),
                virtual = true,
                ownerToken = ownerToken,
            ),
            pops = 1,
            pushes = 1,
        )
        methodContext.emitReturn(pops = 1)
    }

    private fun emitGenericOwnerForeignOverrideProbe(typedEntry: IrSimpleFunction) {
        check(function is IrSimpleFunction &&
                function.origin == DOTNET_GENERIC_OWNER_FOREIGN_OVERRIDE_PROBE &&
                signature.parameterTypes.size == 1 &&
                signature.returnType == DotNetIlReturnType.Value(DotNetIlValueType.Boolean)) {
            "A generic-owner foreign override probe must be an instance () -> Boolean method"
        }
        val typedInfo = checkNotNull(availableFunctions[typedEntry]) {
            "A generic-owner foreign override probe lacks its typed MethodDef"
        }
        check(typedInfo.owner == functionInfo.owner && typedEntry.typeParameters.isEmpty() &&
                typedInfo.signature.parameterTypes.size == 1) {
            "A generic-owner foreign override probe must target one no-input local member"
        }
        val ownerToken = if (functionInfo.owner.typeParameterCount == 0) {
            functionInfo.owner.ilTypeRef
        } else {
            DotNetIlValueType.GenericInstance(
                functionInfo.owner,
                List(functionInfo.owner.typeParameterCount) { index ->
                    DotNetIlValueType.TypeParameter(index, isMethodParameter = false)
                },
            ).nameInSignature
        }
        val typedReference = typedInfo.renderMethodReference(
            typedInfo.physicalMethodName ?: typedEntry.dotNetIlMethodName(),
            ownerToken = ownerToken,
        )
        methodContext.emit("ldarg.0", pushes = 1)
        methodContext.emit("ldvirtftn $typedReference", pops = 1, pushes = 1)
        methodContext.emit("ldftn $typedReference", pushes = 1)
        methodContext.emit("ceq", pops = 2, pushes = 1)
        methodContext.emit("ldc.i4.0", pushes = 1)
        methodContext.emit("ceq", pops = 2, pushes = 1)
        methodContext.emitReturn(pops = 1)
    }

    private fun emitGenericOwnerCallRouteTraceHook(hook: DotNetGenericOwnerCallRouteTraceHook) {
        check(function is IrSimpleFunction && !signature.hasThis && signature.returnType == DotNetIlReturnType.Void) {
            "Generic-owner call-route trace hooks must be static Unit functions"
        }
        when (hook) {
            DotNetGenericOwnerCallRouteTraceHook.RECORD -> {
                check(signature.parameterTypes == listOf(DotNetIlValueType.Int32)) {
                    "The generic-owner call-route recorder must have the physical signature (Int) -> Unit"
                }
                methodContext.emit("ldarg.0", pushes = 1)
                methodContext.emit(
                    DotNetGenericOwnerCallRouteTraceSupport.callInstruction(hook),
                    pops = 1,
                )
            }
            DotNetGenericOwnerCallRouteTraceHook.FLUSH -> {
                check(signature.parameterTypes.isEmpty()) {
                    "The generic-owner call-route flusher must have the physical signature () -> Unit"
                }
                methodContext.emit(DotNetGenericOwnerCallRouteTraceSupport.callInstruction(hook))
            }
        }
        methodContext.emitReturn()
    }

    /**
     * The join point of returns that crossed protected regions (see [emitReturnAcrossRegions]):
     * reloads the drained return value and returns, once, after the rendered body (dead trailing
     * instructions before the label are harmless, probe-verified). No epilogue exists when no
     * return crossed a region.
     */
    private fun emitReturnJoinEpilogue() {
        val label = returnJoinLabel ?: return
        methodContext.emitLabel(label)
        val slot = returnValueSlot
        if (slot == null) {
            methodContext.emitReturn()
        } else {
            methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
            methodContext.emitReturn(pops = 1)
        }
    }

    private fun emitStatement(statement: IrStatement) {
        when (statement) {
            is IrVariable -> emitVariable(statement)
            is IrExpression -> emitVoidExpression(statement)
            else -> dotNetUnsupported("unsupported statement ${statement.javaClass.simpleName}")
        }
    }

    private fun emitVariable(variable: IrVariable) {
        val initializer = variable.initializer
        val exactArrayStorage = if (initializer == null) null else variable.exactCompilerTemporaryArrayStorageOrNull()
        val localOpenNullableArrayStorage = if (
            exactArrayStorage == null && initializer != null && !variable.isVar &&
            initializer is IrWhen && variable.type.isDotNetInvariantOpenNullableGenericArray()
        ) {
            // Common RingBuffer conditionally selects either a resized copy or the supplied
            // exact vector. This local view retains that result through System.Array and its
            // component checks; a bare cast/local remains outside the selected representation.
            DotNetIlValueType.ErasedGenericArray(expressionCodegen.coreLibraryReference)
        } else {
            null
        }
        val slot = methodContext.declareLocal(variable, exactArrayStorage ?: localOpenNullableArrayStorage)
        if (initializer == null) return
        // Shared lowerings may place statement-bearing expressions in compiler-temporary
        // initializers (including a Nothing-typed break/continue on a dead value path). Route
        // them through the same method-scope value emitter as try/when branch results; ordinary
        // expressions still delegate directly to expression codegen.
        emitValueExpression(initializer, slot.type)
        if (methodContext.isTerminated) return
        methodContext.emit(storeLocalInstruction(slot.index), pops = 1)
    }

    /**
     * Retains an exact CLR vector through the immutable argument temporaries introduced by the
     * shared inliner. Kotlin inference can instantiate an InlineOnly parameter at a wider
     * element type through a contravariant receiver (for example
     * `MutableCollection<in T>.plusAssign(Array<T>)`), producing a temporary logical
     * `Array<Any?>` around the original `Array<Int>`. JVM reference arrays tolerate that
     * temporary covariance; CLR `int32[]` cannot be stored in `object[]`.
     *
     * These locals are compiler scaffolding, not ABI. Preserve the initializer's exact vector
     * type through the complete inliner-temporary chain. The inline body still observes its KLIB
     * logical type, while projected array consumers widen that exact slot to `System.Array`.
     * Source locals and mutable compiler locals keep their declared physical type, so this is not
     * a general invariant-array covariance rule.
     */
    private fun IrVariable.exactCompilerTemporaryArrayStorageOrNull(
        visited: MutableSet<IrVariable> = hashSetOf(),
    ): DotNetIlValueType.GenericArray? {
        if (isVar || !type.isDotNetGenericArray() || !visited.add(this)) return null
        if (origin != IrDeclarationOrigin.IR_TEMPORARY_VARIABLE &&
            origin != IrDeclarationOrigin.IR_TEMPORARY_VARIABLE_FOR_INLINED_PARAMETER &&
            origin != IrDeclarationOrigin.IR_TEMPORARY_VARIABLE_FOR_INLINED_EXTENSION_RECEIVER
        ) {
            return null
        }
        return initializer?.exactArrayStorageProvenanceOrNull(visited)
    }

    private fun IrExpression.exactArrayStorageProvenanceOrNull(
        visited: MutableSet<IrVariable>,
    ): DotNetIlValueType.GenericArray? {
        return when (this) {
            is IrTypeOperatorCall -> if (
                operator == IrTypeOperator.IMPLICIT_CAST &&
                argument.type.isDotNetGenericArray() &&
                typeOperand.isDotNetGenericArray()
            ) {
                argument.exactArrayStorageProvenanceOrNull(visited)
            } else {
                expressionCodegen.toDotNetIlValueType(type) as? DotNetIlValueType.GenericArray
            }
            is IrGetValue ->
                (symbol.owner as? IrVariable)?.exactCompilerTemporaryArrayStorageOrNull(visited)
                    ?: expressionCodegen.toDotNetIlValueType(type) as? DotNetIlValueType.GenericArray
            else -> expressionCodegen.toDotNetIlValueType(type) as? DotNetIlValueType.GenericArray
        }
    }

    private fun emitVoidExpression(expression: IrExpression) {
        when {
            expression is IrReturn -> emitReturn(expression)
            // Calls in statement position get the same handling as explicitly discarded values,
            // so intrinsic-only callees work identically in both shapes.
            expression is IrCall -> emitDiscardableExpression(expression)
            expression is IrSetValue -> emitSetValue(expression)
            expression is IrSetField -> expressionCodegen.emitSetField(expression)
            // An instantiation in statement position (`Point(5)`) is created and discarded.
            expression is IrConstructorCall -> emitDiscardableExpression(expression)
            expression is IrDelegatingConstructorCall -> emitDelegatingConstructorCall(expression)
            expression is IrInstanceInitializerCall ->
                dotNetUnsupported("internal: IrInstanceInitializerCall survived InitializersLowering")
            expression is IrThrow -> expressionCodegen.emitThrow(expression)
            expression is IrTry -> emitTryStatement(expression)
            expression is IrWhen -> emitWhenStatement(expression)
            expression is IrWhileLoop -> emitWhileLoop(expression)
            expression is IrDoWhileLoop -> emitDoWhileLoop(expression)
            expression is IrBreakContinue -> emitBreakContinue(expression)
            expression is IrTypeOperatorCall && expression.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT ->
                emitDiscardableExpression(expression.argument)
            // A deserialized inline body can expose any substituted type operation in statement
            // position through an unused compiler temporary. The operation is still observable:
            // checked casts and implicit not-null assertions may throw, and type tests still have
            // to evaluate their operand. Execute the ordinary expression path and discard only
            // its result. `Iterable.fold<Int, Int>(...)` is the canonical implicit-cast shape;
            // reified `Nothing?` and erased-interface substitutions cover the wider matrix.
            expression is IrTypeOperatorCall ->
                emitDiscardableExpression(expression)
            expression is IrGetObjectValue && expression.type.isUnit() -> Unit
            expression is IrContainerExpression -> emitBlockStatement(expression)
            // A side-effect-free value read in statement position (e.g. the trailing `<unary>`
            // read of a desugared `x++` block) compiles to nothing.
            expression is IrGetValue -> Unit
            // A static singleton-field read is an active-use operation even when its value is
            // discarded. The static-initialization usage lowering prefixes it with the Kotlin
            // failure barrier; still perform and discard the physical read so CLR initialization
            // and volatile/publication rules are not silently weakened.
            expression is IrGetField -> emitDiscardableExpression(expression)
            // A side-effect-free constant in statement position compiles to nothing — the
            // canonical shape is the `null` result branch of a Unit-typed safe call
            // (`obj?.method()` desugars to a when whose null branch is a bare `null` constant).
            expression is IrConst -> Unit
            else -> dotNetUnsupported("unsupported statement expression ${expression.javaClass.simpleName}")
        }
    }

    private fun emitReturn(expression: IrReturn) {
        if (expression.returnTargetSymbol != function.symbol) {
            dotNetUnsupported("non-local return is not supported")
        }
        if (methodContext.ehDepth > 0) {
            // A return inside a `finally` body would have to leave the finally region, and the
            // CLR's only legal exit from one is `endfinally` — even `leave` may not cross it.
            if (methodContext.crossesFinallyRegion(0)) {
                dotNetUnsupported("'return' inside a 'finally' block is not supported")
            }
            emitReturnAcrossRegions(expression)
            return
        }
        when (val returnType = signature.returnType) {
            is DotNetIlReturnType.Value -> {
                if (!emitReturnValueOnCleanStack(expression.value, returnType.type)) return
                methodContext.emitReturn(pops = 1)
            }
            DotNetIlReturnType.Void -> {
                emitVoidExpression(expression.value)
                if (methodContext.isTerminated) return
                methodContext.drainEvaluationStack()
                methodContext.emitReturn()
            }
        }
    }

    /**
     * A `return` inside a protected region: `ret` there assembles silently but throws
     * `InvalidProgramException` at runtime, so the return value is drained into a lazily created
     * synthetic local and control leaves to a shared return-join label whose epilogue reloads it
     * and returns (`stloc`/`leave`/`ldloc`/`ret`, the probe-verified pattern — the same shape
     * Roslyn emits for returns crossing protected regions). A single `leave` legally crosses any
     * number of nested regions in one hop, so the depth never matters.
     */
    private fun emitReturnAcrossRegions(expression: IrReturn) {
        when (val returnType = signature.returnType) {
            is DotNetIlReturnType.Value -> {
                emitReturnValue(expression.value, returnType.type)
                if (methodContext.isTerminated) return // the value itself terminated; nothing returns
                val slot = returnValueSlot
                    ?: methodContext.declareSyntheticLocal(returnType.type, "<return>").also { returnValueSlot = it }
                methodContext.emit(storeLocalInstruction(slot.index), pops = 1)
                methodContext.drainEvaluationStack()
            }
            DotNetIlReturnType.Void -> {
                emitVoidExpression(expression.value)
                if (methodContext.isTerminated) return
                methodContext.drainEvaluationStack()
            }
        }
        val label = returnJoinLabel
            ?: methodContext.nextLabel("returnJoin").also { returnJoinLabel = it }
        methodContext.emitLeave(label)
    }

    /**
     * Ordinary Kotlin Unit functions remain CLR-void. An erased `Invoke` or property `Get`
     * override instead executes its Unit expression for effects, then returns the runtime
     * singleton through the object-shaped ABI slot.
     */
    private fun emitReturnValue(expression: IrExpression, expectedType: DotNetIlValueType) {
        if (
            function is IrSimpleFunction &&
            function.isDotNetErasedObjectResult() &&
            expectedType == DotNetIlValueType.Object &&
            expression.type.isUnit()
        ) {
            emitVoidExpression(expression)
            if (!methodContext.isTerminated) expressionCodegen.emitRuntimeUnitInstance()
        } else {
            expressionCodegen.emitExpression(expression, expectedType)
        }
    }

    /**
     * Evaluates one method result while preserving source order, then ensures it is the only CIL
     * stack value. Inlined non-local control flow can expose a return beneath an outer expression
     * whose earlier operands are already pending. The result is spilled only for that dirty-stack
     * case; ordinary returns retain their direct value/`ret` shape.
     *
     * Returns false when evaluating [expression] already terminated control flow.
     */
    private fun emitReturnValueOnCleanStack(expression: IrExpression, expectedType: DotNetIlValueType): Boolean {
        val pendingOperandCount = methodContext.stackDepth
        emitReturnValue(expression, expectedType)
        if (methodContext.isTerminated) return false
        check(methodContext.stackDepth == pendingOperandCount + 1) {
            "Internal .NET backend error: return expression did not produce exactly one value"
        }
        if (pendingOperandCount == 0) return true

        val slot = returnValueSlot
            ?: methodContext.declareSyntheticLocal(expectedType, "<return>").also { returnValueSlot = it }
        methodContext.emit(storeLocalInstruction(slot.index), pops = 1)
        methodContext.drainEvaluationStack()
        methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
        return true
    }

    /**
     * The delegation statement of a constructor body: `ldarg.0`, the arguments, then a plain
     * (non-virtual) `call` to either `System.Object::.ctor()` — the `kotlin.Any` supertype
     * constructor, `kotlin.Any` having no IL class of its own — or the constructor of another
     * user class: the sibling constructor of a `this(...)` delegation and the BASE-class
     * constructor of the inheritance model share the exact same IL shape,
     * `call instance void 'C'::.ctor(...)` (the CLR has one delegation form for both, unlike
     * the JVM where both are also just `invokespecial <init>`). All call shapes, including code
     * before and after the delegation, are ilasm-probe-verified (base chaining with arguments
     * and a constructor body after the chain: `inheritprobe_s1`).
     */
    private fun emitDelegatingConstructorCall(call: IrDelegatingConstructorCall) {
        if (function !is IrConstructor) {
            dotNetUnsupported("delegating constructor call outside a constructor body")
        }
        val target = call.symbol.owner
        val targetClass = target.constructedClass
        methodContext.emit("ldarg.0", pushes = 1)
        if (targetClass.defaultType.isAny()) {
            val physicalBase = if (function.constructedClass.isAnnotationClass) {
                "${typeMapper.coreLibrary.reference}System.Attribute"
            } else {
                "${typeMapper.coreLibrary.reference}System.Object"
            }
            methodContext.emit(
                "call instance void $physicalBase::.ctor()",
                pops = 1,
            )
            return
        }
        DotNetMappedExceptions.mappedEntry(targetClass.fqNameWhenAvailable)?.let { entry ->
            val parameterTypes = target.parameters.map { parameter ->
                typeMapper.toDotNetIlValueType(parameter.type)
                    ?: dotNetUnsupported(
                        "parameter '${parameter.name.asString()}' of mapped exception constructor " +
                                "has unsupported type ${parameter.type.render()}"
                    )
            }
            val className = targetClass.name.asString()
            entry.checkConstructorShape(className, parameterTypes, typeMapper.coreLibrary.reference)
            expressionCodegen.emitArguments(call.arguments, parameterTypes, "constructor of '$className'")
            methodContext.emit(
                "call instance void ${entry.subclassBaseTypeRef(typeMapper.coreLibrary.reference)}" +
                        "::.ctor(${parameterTypes.joinToString(", ") { it.nameInSignature }})",
                pops = 1 + parameterTypes.size,
            )
            return
        }
        val genericClassInfo = typeMapper.genericClassInfoOrNull(targetClass)
        val constructorTypeMapper = typeMapper
        val classInfo = genericClassInfo?.classInfo ?: constructorTypeMapper.classInfoOrNull(targetClass)
            ?: dotNetUnsupported("delegating call to a constructor of unsupported class '${targetClass.name.asString()}'")
        val parameterTypes = target.dotNetSignature(constructorTypeMapper).parameterTypes
        if (genericClassInfo != null || targetClass.typeParameters.isEmpty()) {
            expressionCodegen.emitArguments(call.arguments, parameterTypes, "constructor of '${targetClass.name.asString()}'")
            methodContext.emit("call ${classInfo.renderConstructorReference(parameterTypes)}", pops = 1 + parameterTypes.size)
            return
        }
        // A genuinely generic CLR delegation target: a closed, open, or permuted imported base,
        // or that CLR class's own `this(...)` delegation. Kotlin-owned generic classes took the
        // erased-owner branch above. The parameter slots stay open while argument values use the
        // substituted types.
        if (call.typeArguments.size != targetClass.typeParameters.size) {
            dotNetUnsupported("delegating constructor call of '${targetClass.name.asString()}' has an unsupported type-argument shape")
        }
        val instantiation = call.typeArguments.map { argumentType ->
            argumentType?.let { constructorTypeMapper.toDotNetIlGenericArgumentType(it) }
                ?: dotNetUnsupported(
                    "delegating constructor call of '${targetClass.name.asString()}' instantiates a type parameter " +
                            "with an unsupported type argument"
                )
        }
        val ownerToken = DotNetIlValueType.GenericInstance(classInfo, instantiation).nameInSignature
        val substitutedParameterTypes = parameterTypes.map { it.substituteDotNetTypeParameters(instantiation) }
        expressionCodegen.emitArguments(call.arguments, substitutedParameterTypes, "constructor of '${targetClass.name.asString()}'")
        methodContext.emit(
            "call ${classInfo.renderConstructorReference(parameterTypes, ownerToken)}",
            pops = 1 + parameterTypes.size,
        )
    }

    private fun emitSetValue(expression: IrSetValue) {
        val slot = methodContext.reference(expression.symbol)
        expressionCodegen.emitExpression(expression.value, slot.type)
        if (methodContext.isTerminated) return
        val instruction = when (slot) {
            is DotNetIlSlot.Local -> storeLocalInstruction(slot.index)
            // Source parameters are immutable. This shape is produced by the common masked
            // default stub, which replaces an omitted placeholder before dispatching to the
            // original declaration. CLR `starg` is the direct JVM-local-slot counterpart.
            is DotNetIlSlot.Parameter -> storeArgumentInstruction(slot.index)
        }
        methodContext.emit(instruction, pops = 1)
    }

    private fun emitWhenStatement(expression: IrWhen) {
        val endLabel = methodContext.nextLabel("whenEnd")

        for (branch in expression.branches) {
            if (branch.condition.isFalseConst()) continue

            if (branch.condition.isTrueConst()) {
                emitVoidExpression(branch.result)
                break
            }

            val nextBranchLabel = methodContext.nextLabel("whenNext")
            expressionCodegen.emitBranchIfFalse(branch.condition, nextBranchLabel)
            emitVoidExpression(branch.result)
            if (!methodContext.isTerminated) {
                methodContext.emitGoto(endLabel)
            }
            methodContext.emitLabel(nextBranchLabel)
        }

        // The end label is skipped when every branch returned: an unreferenced label at the very
        // end of a method would leave a branch target past the last instruction.
        if (methodContext.isLabelReferenced(endLabel)) {
            methodContext.emitLabel(endLabel)
        }
    }

    /**
     * A block (or composite) in statement position, e.g. a loop body or the desugaring of `x++`:
     * every child is a statement; the block's value, if any, is a trailing side-effect-free value
     * read that [emitVoidExpression] drops. Any [IrVariable] declared inside gets its local slot
     * lazily through [DotNetIlMethodContext.declareLocal], so shadowing locals in sibling scopes
     * simply occupy distinct slots (per-symbol) with deduplicated names.
     */
    private fun emitBlockStatement(block: IrContainerExpression) {
        for (statement in block.statements) {
            emitStatement(statement)
            if (methodContext.isTerminated) return
        }
    }

    /**
     * `while (cond) body`:
     * ```
     * condLabel: cond; brfalse endLabel; body; br condLabel; endLabel:
     * ```
     * `continue` jumps to `condLabel`, `break` to `endLabel`.
     */
    private fun emitWhileLoop(loop: IrWhileLoop) {
        val conditionLabel = methodContext.nextLabel("whileCond")
        val endLabel = methodContext.nextLabel("whileEnd")
        val conditionIsAlwaysTrue = loop.condition.isTrueConst()
        methodContext.registerLoop(
            loop,
            DotNetIlLoopLabels(
                breakLabel = endLabel,
                continueLabel = conditionLabel,
                ehDepth = methodContext.ehDepth,
                stackDepth = methodContext.stackDepth,
            ),
        )

        methodContext.emitLabel(conditionLabel)
        // Omitting the impossible false edge is required for verifiable CIL when an infinite
        // loop is the final statement of a non-Unit method. A literal `true; brfalse end` still
        // gives the CLR verifier a syntactic path which falls off the method without `ret`.
        if (!conditionIsAlwaysTrue) {
            expressionCodegen.emitBranchIfFalse(loop.condition, endLabel)
        }
        loop.body?.let { emitVoidExpression(it) }
        // The back edge is dead when the body ends with return/break/continue.
        if (!methodContext.isTerminated) {
            methodContext.emitGoto(conditionLabel)
        }
        if (!conditionIsAlwaysTrue || methodContext.isLabelReferenced(endLabel)) {
            methodContext.emitLabel(endLabel)
        }

        methodContext.unregisterLoop(loop)
    }

    /**
     * `do body while (cond)`:
     * ```
     * bodyLabel: body; condLabel: cond; brtrue bodyLabel; endLabel:
     * ```
     * `continue` jumps to `condLabel`, `break` to `endLabel`. The end label is only emitted when
     * a `break` referenced it: execution otherwise just falls through past `brtrue`.
     */
    private fun emitDoWhileLoop(loop: IrDoWhileLoop) {
        val bodyLabel = methodContext.nextLabel("doWhileBody")
        val conditionLabel = methodContext.nextLabel("doWhileCond")
        val endLabel = methodContext.nextLabel("doWhileEnd")
        val conditionIsAlwaysTrue = loop.condition.isTrueConst()
        methodContext.registerLoop(
            loop,
            DotNetIlLoopLabels(
                breakLabel = endLabel,
                continueLabel = conditionLabel,
                ehDepth = methodContext.ehDepth,
                stackDepth = methodContext.stackDepth,
            ),
        )

        methodContext.emitLabel(bodyLabel)
        loop.body?.let { emitVoidExpression(it) }
        // Like the end label below, the condition label is only emitted when a `continue`
        // referenced it; execution otherwise just falls through from the body.
        if (methodContext.isLabelReferenced(conditionLabel)) {
            methodContext.emitLabel(conditionLabel)
        }
        // Like `while (true)`, a conditional back-edge leaves a syntactic false path which falls
        // off the end of a non-Unit CLR method even though Kotlin knows the condition is true.
        // Emit the unconditional edge for the literal case so the method remains verifiable.
        if (conditionIsAlwaysTrue) {
            methodContext.emitGoto(bodyLabel)
        } else {
            expressionCodegen.emitExpression(loop.condition, DotNetIlValueType.Boolean)
            methodContext.emitBranch("brtrue", bodyLabel, pops = 1)
        }
        if (methodContext.isLabelReferenced(endLabel)) {
            methodContext.emitLabel(endLabel)
        }

        methodContext.unregisterLoop(loop)
    }

    private fun emitBreakContinue(jump: IrBreakContinue) {
        val keyword = if (jump is IrBreak) "break" else "continue"
        val labels = methodContext.loopLabelsOrNull(jump.loop)
            ?: dotNetUnsupported(
                "'$keyword${jump.label?.let { "@$it" }.orEmpty()}' targets a loop outside the function being compiled"
            )
        val targetLabel = if (jump is IrBreak) labels.breakLabel else labels.continueLabel
        // A break/continue at a deeper exception-region depth than its loop crosses protected
        // regions and must exit via `leave` — legal toward any label of an enclosing scope,
        // forward or backward, crossing nested regions in one hop (probe-verified).
        when {
            methodContext.ehDepth == labels.ehDepth -> {
                // A bottom-typed transfer abandons values produced inside the loop, but an
                // inlined local return uses a synthetic loop while an earlier outer operand can
                // already be pending. Preserve exactly that loop-entry stack prefix.
                methodContext.drainEvaluationStackTo(labels.stackDepth)
                methodContext.emitGoto(targetLabel)
            }
            methodContext.ehDepth > labels.ehDepth -> {
                // A `leave` may cross any number of `.try`/`catch` regions, but never a
                // `finally` body: its only legal exit is `endfinally`. `leave` also clears the
                // complete evaluation stack, so protected-region entry must already have caused
                // any ambient operands to be spilled before this loop was registered.
                if (methodContext.crossesFinallyRegion(labels.ehDepth)) {
                    dotNetUnsupported("'$keyword' crossing out of a 'finally' block is not supported")
                }
                check(labels.stackDepth == 0) {
                    "Internal .NET backend error: '$keyword' crosses a protected region from a loop with " +
                            "non-empty entry stack ${labels.stackDepth}"
                }
                methodContext.drainEvaluationStack()
                methodContext.emitLeave(targetLabel)
            }
            else -> error("Internal .NET backend error: '$keyword' at a shallower exception-region depth than its loop")
        }
    }

    private fun emitDiscardableExpression(expression: IrExpression) {
        when (expression) {
            is IrContainerExpression -> emitBlockStatement(expression)
            is IrGetValue -> Unit
            is IrBreakContinue -> emitBreakContinue(expression)
            // A discarded throw produces no value to pop: `throw` terminates the emission point.
            is IrThrow -> expressionCodegen.emitThrow(expression)
            is IrTry -> emitDiscardedTry(expression)
            is IrCall -> {
                val intrinsic = intrinsicMethods.getIntrinsic(expression.symbol)
                if (intrinsic != null) {
                    if (intrinsic.tryEmitAsStatement(expression, expressionCodegen)) return
                    val valueType = typeMapper.toDotNetIlValueType(expression.type)
                    if (valueType != null && intrinsic.tryEmitAsExpression(expression, expressionCodegen, valueType)) {
                        if (methodContext.isTerminated) return
                        methodContext.emit("pop", pops = 1)
                        return
                    }
                }
                emitCallStatement(expression)
            }
            is IrGetObjectValue -> {
                if (!expression.type.isUnit()) {
                    dotNetUnsupported("unsupported object discard: ${expression.symbol.owner.name.asString()}")
                }
            }
            else -> {
                val valueType = typeMapper.toDotNetIlValueType(expression.type)
                    ?: dotNetUnsupported("cannot discard value of unsupported type ${expression.javaClass.simpleName}")
                expressionCodegen.emitExpression(expression, valueType)
                if (methodContext.isTerminated) return
                methodContext.emit("pop", pops = 1)
            }
        }
    }

    private fun emitCallStatement(call: IrCall) {
        if (expressionCodegen.tryEmitCapabilityCallForDiscard(call)) return
        if (expressionCodegen.emitCall(call) is DotNetIlReturnType.Value) {
            if (methodContext.isTerminated) return
            methodContext.emit("pop", pops = 1)
        }
    }

    /**
     * A `try`/`catch` in statement position — following the JVM backend, [IrTry] maps 1:1 onto
     * the platform exception table (a `.try` block with consecutive typed `catch` handlers) with
     * no lowering machinery. Every branch that completes normally exits with `leave` to the join
     * label after the construct; the label is skipped when every branch terminated (returned or
     * threw), exactly like [emitWhenStatement]'s end label.
     */
    private fun emitTryStatement(expression: IrTry) {
        val endLabel = methodContext.nextLabel("tryEnd")
        emitTryCatchRegions(expression) { branchResult ->
            emitVoidExpression(branchResult)
            if (!methodContext.isTerminated) {
                methodContext.emitLeave(endLabel)
            }
        }
        if (methodContext.isLabelReferenced(endLabel)) {
            methodContext.emitLabel(endLabel)
        }
    }

    /**
     * A `try`/`catch` in value position (Kotlin's `IrTry` has a value): `leave` discards the
     * evaluation stack (ECMA-335), so the branch results cannot cross the region boundary on the
     * stack — each branch drains its value into a synthetic result local and the join label
     * reloads it (probe-verified template). The join is skipped entirely when every branch
     * terminated (threw or left toward an outer target), exactly like [emitTryStatement]'s end
     * label: the construct is then `Nothing`-like and only a phantom result keeps the tracker
     * balanced for the consumer's dead instructions. The CLR additionally requires an empty
     * evaluation stack at `.try` entry. The expression emitter therefore evaluates and spills
     * every receiver/argument of an enclosing call before entering a nested `try`; reaching this
     * method with older operands still indicates a missing parent-expression isolation rule.
     */
    private fun emitTryExpression(expression: IrTry, expectedType: DotNetIlValueType) {
        if (methodContext.stackDepth != 0) {
            dotNetUnsupported("'try' expression with operands already on the evaluation stack is not supported")
        }
        val endLabel = methodContext.nextLabel("tryEnd")
        val resultSlot = methodContext.declareSyntheticLocal(expectedType, "<try>")
        emitTryCatchRegions(expression) { branchResult ->
            emitValueExpression(branchResult, expectedType)
            if (!methodContext.isTerminated) {
                methodContext.emit(storeLocalInstruction(resultSlot.index), pops = 1)
                methodContext.emitLeave(endLabel)
            }
        }
        if (methodContext.isLabelReferenced(endLabel)) {
            methodContext.emitLabel(endLabel)
            methodContext.emit(loadLocalInstruction(resultSlot.index), pushes = 1)
        } else {
            // No branch ever emitted `leave` to the join: nothing was drained into the result
            // local, and emitting the label plus the reload would resurrect whatever phantom
            // depth the last terminated branch left behind (emitLabel keeps the current depth
            // for an unreferenced label), permanently unbalancing the stack tracker.
            methodContext.notePhantomValueAtTerminatedTryJoin()
        }
    }

    /**
     * A non-Unit `try` in statement position (it arrives under `IMPLICIT_COERCION_TO_UNIT`; a
     * Unit-typed `try` statement arrives bare in [emitVoidExpression]). When the try's type
     * maps, it is emitted in expression form and the reloaded result is popped — the branch
     * values are expressions (e.g. a trailing constant) that statement emission cannot handle.
     * A non-null `Nothing`-typed `try` deliberately uses statement form even though it now maps
     * to the physical `Kotlin.Nothing` carrier: it cannot complete normally, so value form would
     * only manufacture a phantom result and a dead `pop`. Other unmapped tries use statement form
     * as before.
     *
     * An `object`-typed `try` ALSO uses statement form: since `kotlin.Any` maps to CLR `object`
     * (the hybrid nullability model's storage type), a `try` whose branch types merely LUB to
     * `Any` reaches here — the routine `try { intCall() } catch (e: Exception) { flag = true }`
     * statement shape, whose catch branch is Unit-typed and ends in an assignment that value
     * emission cannot handle. A discarded `Any`-typed value is never materialized, so the
     * statement path (the exact pre-hybrid behavior, when `Any` was unmapped) is kept; mapped
     * NON-`object` types imply every branch result is value-shaped (a statement-shaped branch
     * would have pushed the LUB to `Any`), so the expression form stays correct for them.
     * Pinned by `box/tryDiscardedValue.kt` and `ilText/tryCatchExpressionNothing.kt`.
     */
    private fun emitDiscardedTry(expression: IrTry) {
        val valueType = typeMapper.toDotNetIlValueType(expression.type)
        if (expression.type.isNothing() || valueType == null || valueType == DotNetIlValueType.Object) {
            emitTryStatement(expression)
        } else {
            emitTryExpression(expression, valueType)
            methodContext.emit("pop", pops = 1)
        }
    }

    /**
     * The region structure shared by both `try` forms. Without a `finally` this is `.try {`
     * around the try branch, then one CLR handler per Kotlin catch clause in source order. A
     * logical class exactly expressible by one CLR type uses a typed catch; broad Exception,
     * RuntimeException, and Error categories use a filter over `System.Exception` and the single
     * Kotlin.Runtime classifier. The CLR searches typed handlers and filters strictly in metadata
     * order, so Kotlin source ordering and first-pass filter semantics are retained. Each selected
     * handler binds the original exception object to its catch local.
     *
     * A `finally` wraps that whole try/catch construct in an OUTER `.try { } finally { }`: a
     * `.try` may carry either catch handlers or one `finally`, never both — combining them on
     * one `.try` assembles silently but throws `InvalidProgramException` at runtime
     * (probe-verified) — and with no catches the single `.try { } finally { }` region suffices.
     * Branch `leave`s keep targeting the join label after the WHOLE construct; the CLR runs the
     * finally automatically on every exit — normal leaves (including `break`/`continue` and
     * return-join ones) and the exceptional path alike — with NO JVM-style finally
     * inlining/duplication, a CLR-forced deviation from the JVM backend, whose platform has no
     * finally handlers to delegate to. The finally body is emitted as void and exits through
     * `endfinally`, its only legal exit.
     */
    private fun emitTryCatchRegions(expression: IrTry, emitBranchResult: (IrExpression) -> Unit) {
        val finallyExpression = expression.finallyExpression
        if (finallyExpression == null) {
            emitTryCatches(expression, emitBranchResult)
            return
        }
        methodContext.beginTry()
        if (expression.catches.isEmpty()) {
            emitBranchResult(expression.tryResult)
        } else {
            emitTryCatches(expression, emitBranchResult)
        }
        methodContext.beginFinally()
        emitVoidExpression(finallyExpression)
        methodContext.emitEndFinally()
        methodContext.endEhBlock()
    }

    /** The `.try` block plus its consecutive `catch` handlers; see [emitTryCatchRegions]. */
    private fun emitTryCatches(expression: IrTry, emitBranchResult: (IrExpression) -> Unit) {
        methodContext.beginTry()
        emitBranchResult(expression.tryResult)
        for (irCatch in expression.catches) {
            val filtered = beginCatchHandler(irCatch)
            val slot = methodContext.declareLocal(irCatch.catchParameter)
            if (filtered) {
                // A filter handler receives the original thrown value with stack type object.
                // Narrow its physical view to the universal carrier without replacing it.
                methodContext.emit(
                    "castclass ${DotNetMappedExceptions.exceptionTypeRef(typeMapper.coreLibrary.reference)}",
                    pops = 1,
                    pushes = 1,
                )
            }
            methodContext.emit(storeLocalInstruction(slot.index), pops = 1)
            emitBranchResult(irCatch.result)
        }
        methodContext.endEhBlock()
    }

    /**
     * Opens the CLR handler for one Kotlin catch. Returns true for a classified filter handler so
     * the caller can narrow the handler-entry `object` stack value to `System.Exception` before
     * binding it. A mapped built-in uses a typed handler only when the registry proves it
     * equivalent to the same runtime classifier rule; Throwable -> System.Exception and
     * exact/BCL-mapped classes satisfy that condition. A user-defined Kotlin exception is already
     * one exact CLR class, so its ordinary typed handler preserves Kotlin exact-type semantics.
     */
    private fun beginCatchHandler(irCatch: IrCatch): Boolean {
        val parameter = irCatch.catchParameter
        val type = typeMapper.toDotNetIlValueType(parameter.type)
        if (type is DotNetIlValueType.UserClass || type is DotNetIlValueType.GenericInstance) {
            val exceptionCarrier = DotNetIlValueType.MappedClass(
                DotNetMappedExceptions.exceptionTypeRef(typeMapper.coreLibrary.reference)
            )
            if (!type.isDotNetAssignableTo(exceptionCarrier)) {
                dotNetUnsupported(
                    "catch type ${parameter.type.render()} is not physically assignable to System.Exception"
                )
            }
            methodContext.beginCatch(type.nameInSignature)
            return false
        }
        if (type !is DotNetIlValueType.MappedClass) {
            dotNetUnsupported("catch type ${parameter.type.render()} has no CLR exception representation")
        }
        val entry = DotNetMappedExceptions.mappedEntry(parameter.type.classFqName)
            ?: dotNetUnsupported("catch type ${parameter.type.render()} has no logical exception classifier id")
        val typedCatchTypeRef = entry.typedCatchTypeRefOrNull(typeMapper.coreLibrary.reference)
        if (typedCatchTypeRef != null) {
            methodContext.beginCatch(typedCatchTypeRef)
            return false
        }

        methodContext.beginFilter()
        methodContext.emit(
            "isinst ${DotNetMappedExceptions.exceptionTypeRef(typeMapper.coreLibrary.reference)}",
            pops = 1,
            pushes = 1,
        )
        methodContext.emit("ldc.i4 ${entry.classifierTypeId.abiValue}", pushes = 1)
        methodContext.emit(
            DotNetRuntimeLibrary.exceptionClassifierCallInstruction(typeMapper.coreLibrary.reference),
            pops = 2,
            pushes = 1,
        )
        methodContext.endFilterAndBeginHandler()
        return true
    }

    /**
     * A value expression that may be a block: `try` branch bodies arrive as [IrContainerExpression]s
     * whose trailing expression is the branch value, preceded by arbitrary statements. A trailing
     * [IrReturn] terminates the real branch, then records the one phantom value required by dead
     * enclosing expression bookkeeping; everything else is emitted against [expectedType].
     */
    private fun emitValueExpression(expression: IrExpression, expectedType: DotNetIlValueType) {
        if (expression is IrReturn) {
            val entryStackDepth = methodContext.stackDepth
            emitReturn(expression)
            if (methodContext.isTerminated) {
                methodContext.notePhantomValueAtTerminatedExpression(entryStackDepth)
            }
            return
        }
        if (expression is IrBreakContinue) {
            val entryStackDepth = methodContext.stackDepth
            emitBreakContinue(expression)
            if (methodContext.isTerminated) {
                // Like Nothing-typed return/throw, a break or continue satisfies the enclosing
                // value type only vacuously. Retain one phantom value for the dead branch/join
                // bookkeeping; the real CIL path has already transferred with br/leave.
                methodContext.notePhantomValueAtTerminatedExpression(entryStackDepth)
            }
            return
        }
        if (expression !is IrContainerExpression) {
            expressionCodegen.emitExpression(expression, expectedType)
            return
        }
        val entryStackDepth = methodContext.stackDepth
        val last = expression.statements.lastOrNull()
            ?: dotNetUnsupported("empty block in value position")
        for (statement in expression.statements.dropLast(1)) {
            emitStatement(statement)
            if (methodContext.isTerminated) return
        }
        when (last) {
            is IrReturn -> {
                emitReturn(last)
                if (methodContext.isTerminated) {
                    methodContext.notePhantomValueAtTerminatedExpression(entryStackDepth)
                }
            }
            is IrBreakContinue -> {
                emitBreakContinue(last)
                if (methodContext.isTerminated) {
                    methodContext.notePhantomValueAtTerminatedExpression(entryStackDepth)
                }
            }
            is IrExpression -> if (last.type.isUnit() && expectedType == DotNetRuntimeTypes.unitType) {
                // A Unit-valued block may end in an effect expression such as IrSetValue. The
                // statement emitter owns those shapes; materialize Kotlin's Unit singleton only
                // after the effect, exactly as for a Unit-returning call used as a value.
                emitVoidExpression(last)
                if (!methodContext.isTerminated) expressionCodegen.emitRuntimeUnitInstance()
            } else {
                emitValueExpression(last, expectedType)
            }
            else -> dotNetUnsupported("unsupported trailing statement ${last.javaClass.simpleName} in a block in value position")
        }
    }
}
