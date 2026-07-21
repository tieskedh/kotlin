package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.lower.InitializersCleanupLowering
import org.jetbrains.kotlin.backend.common.lower.InitializersLowering
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.dotNetUnsupported
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

/**
 * Merges member-property initializers and `init {}` blocks into constructor bodies at the
 * `IrInstanceInitializerCall` site, in declaration order — the JVM precedent
 * (`JvmInitializersLowering` is the same one-line subclass of the backend.common lowering).
 *
 * The only .NET-specific addition is the local-class guard: the shared lowering hard-asserts
 * (an `AssertionError` naming `LocalDeclarationPopupLowering`) on a local class inside an
 * initializer, because deep-copying class declarations is never acceptable. This backend has no
 * local-class support at all, so the guard turns that assertion into the regular
 * fail-loud diagnostic before the shared lowering can trip over it. The guard covers exactly the
 * initializers the shared lowering merges — non-static fields and `init {}` blocks of a class
 * (see `InitializersLoweringBase.extractInitializers` and the `container !is IrConstructor`
 * early return in `InitializersLowering.lower`) — and deliberately nothing more: a top-level
 * property initializer is never merged into any constructor, so it must not fail the module here
 * (skip-to-fixpoint granularity stays with the emitter). The guard runs at module
 * granularity, before [FileLoweringPass.lower][org.jetbrains.kotlin.backend.common.FileLoweringPass.lower]'s
 * per-file wrapping, so the [DotNetIlUnsupportedException][org.jetbrains.kotlin.backend.dotnet.DotNetIlUnsupportedException]
 * reaches [DotNetBackend.compile][org.jetbrains.kotlin.backend.dotnet.DotNetBackend.compile]
 * unwrapped and is reported as an ERROR instead of crashing the compiler.
 *
 * The second .NET-specific pre-step marks the backing field of every `const val` declared in an
 * `object` as static before the merge runs. JVM precedent: `MoveOrCopyCompanionObjectFieldsLowering`
 * (via `JvmCachedDeclarations.getStaticBackingField`) makes every object-parented property field
 * static ahead of the JVM's initializer merge, which is exactly what keeps those fields out of
 * the shared lowering's `!isStatic` constructor-merge filter. Stated deviation: this backend
 * staticizes only the `const` fields — they are emitted as CLR `literal` fields (static, no
 * storage, no `.cctor` entry), so a constructor-merged write to one would be unrenderable —
 * while non-const object state deliberately stays on the instance, initialized by the merged
 * private constructor, because the CLR's real singleton field makes the JVM's static-state
 * hoist unnecessary.
 */
internal class DotNetInitializersLowering(context: DotNetBackendContext) : InitializersLowering(context) {
    override fun lower(irModule: IrModuleFragment) {
        irModule.acceptVoid(ObjectConstFieldStaticizer)
        irModule.acceptVoid(LocalClassInInitializerRejector)
        super.lower(irModule)
    }

    private object ObjectConstFieldStaticizer : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitClass(declaration: IrClass) {
            if (declaration.kind == ClassKind.OBJECT) {
                for (member in declaration.declarations) {
                    if (member is IrProperty && member.isConst) {
                        member.backingField?.isStatic = true
                    }
                }
            }
            declaration.acceptChildrenVoid(this)
        }
    }

    private object LocalClassInInitializerRejector : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer) {
            if (!declaration.isStatic) {
                declaration.body.rejectLocalClasses()
            }
        }

        override fun visitField(declaration: IrField) {
            if (declaration.parent is IrClass && !declaration.isStatic) {
                declaration.initializer?.rejectLocalClasses()
            }
        }

        private fun IrElement.rejectLocalClasses() {
            acceptVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitClass(declaration: IrClass) {
                    dotNetUnsupported(
                        "local class '${declaration.name.asString()}' inside a property initializer or init block is not supported"
                    )
                }
            })
        }
    }
}

/**
 * Removes merged-away `IrAnonymousInitializer`s and nulls instance-field initializers after
 * [DotNetInitializersLowering] copied them into constructors. Static field initializers are
 * preserved (including companion-block state) for [DotNetStaticInitializersLowering], which
 * moves them into the physical owner's `.cctor`. Const initializers retain the common lowering's
 * copied constant expression for literal-field emission.
 */
internal class DotNetInitializersCleanupLowering(context: DotNetBackendContext) : InitializersCleanupLowering(
    context,
    shouldEraseFieldInitializer = { field ->
        !field.isStatic && field.correspondingPropertySymbol?.owner?.isConst != true
    },
)
