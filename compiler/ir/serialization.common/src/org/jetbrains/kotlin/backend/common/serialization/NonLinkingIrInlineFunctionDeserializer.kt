/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.serialization

import org.jetbrains.kotlin.backend.common.serialization.IrDeserializationSettings.DeserializeFunctionBodies
import org.jetbrains.kotlin.backend.common.serialization.encodings.BinarySymbolData
import org.jetbrains.kotlin.backend.common.serialization.signature.PublicIdSignatureComputer
import org.jetbrains.kotlin.ir.InternalSymbolFinderAPI
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrFileEntry
import org.jetbrains.kotlin.ir.LineAndColumn
import org.jetbrains.kotlin.ir.SourceRangeInfo
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrModuleFragmentImpl
import org.jetbrains.kotlin.ir.overrides.isEffectivelyPrivate
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrFakeOverrideSymbolBase
import org.jetbrains.kotlin.ir.symbols.impl.IrFileSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.components.KlibIrComponent
import org.jetbrains.kotlin.library.components.inlinableFunctionsIr
import org.jetbrains.kotlin.library.components.ir
import org.jetbrains.kotlin.library.metadata.KlibDeserializedContainerSource
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.protobuf.ExtensionRegistryLite
import org.jetbrains.kotlin.utils.addToStdlib.runIf
import org.jetbrains.kotlin.utils.addToStdlib.shouldNotBeCalled
import org.jetbrains.kotlin.backend.common.serialization.proto.IrFile as ProtoFile

class NonLinkingIrInlineFunctionDeserializer(
    private val irBuiltIns: IrBuiltIns,
    private val signatureComputer: PublicIdSignatureComputer,
    private val fallbackToMainIr: Boolean = false,
) {
    private val irInterner = IrInterningService()
    private val eagerlyKnownBuiltInClasses by lazy(LazyThreadSafetyMode.NONE) {
        buildList {
            addAll(listOf(
                irBuiltIns.anyClass, irBuiltIns.booleanClass, irBuiltIns.charClass, irBuiltIns.numberClass,
                irBuiltIns.byteClass, irBuiltIns.shortClass, irBuiltIns.intClass, irBuiltIns.longClass,
                irBuiltIns.floatClass, irBuiltIns.doubleClass, irBuiltIns.nothingClass, irBuiltIns.unitClass,
                irBuiltIns.stringClass, irBuiltIns.charSequenceClass, irBuiltIns.collectionClass,
                irBuiltIns.arrayClass, irBuiltIns.setClass, irBuiltIns.listClass, irBuiltIns.mapClass,
                irBuiltIns.mapEntryClass, irBuiltIns.iterableClass, irBuiltIns.iteratorClass,
                irBuiltIns.listIteratorClass, irBuiltIns.mutableCollectionClass, irBuiltIns.mutableSetClass,
                irBuiltIns.mutableListClass, irBuiltIns.mutableMapClass, irBuiltIns.mutableMapEntryClass,
                irBuiltIns.mutableIterableClass, irBuiltIns.mutableIteratorClass, irBuiltIns.mutableListIteratorClass,
                irBuiltIns.comparableClass, irBuiltIns.throwableClass, irBuiltIns.kCallableClass,
                irBuiltIns.kPropertyClass, irBuiltIns.kClassClass, irBuiltIns.kTypeClass,
                irBuiltIns.kProperty0Class, irBuiltIns.kProperty1Class, irBuiltIns.kProperty2Class,
                irBuiltIns.kMutableProperty0Class, irBuiltIns.kMutableProperty1Class, irBuiltIns.kMutableProperty2Class,
                irBuiltIns.functionClass, irBuiltIns.kFunctionClass, irBuiltIns.annotationClass,
                irBuiltIns.enumClass, irBuiltIns.deprecatedSymbol, irBuiltIns.deprecationLevelSymbol,
            ))
            addAll(irBuiltIns.arrays)
            addAll(irBuiltIns.unsignedTypesToUnsignedArrays.values)
            addAll(listOfNotNull(
                irBuiltIns.ubyteClass, irBuiltIns.ushortClass, irBuiltIns.uintClass, irBuiltIns.ulongClass,
            ))
        }
    }
    private val eagerlyKnownBuiltInFunctions by lazy(LazyThreadSafetyMode.NONE) {
        buildList {
            addAll(listOf(
                irBuiltIns.eqeqeqSymbol, irBuiltIns.eqeqSymbol,
                irBuiltIns.throwCceSymbol, irBuiltIns.throwIseSymbol,
                irBuiltIns.andandSymbol, irBuiltIns.ororSymbol,
                irBuiltIns.noWhenBranchMatchedExceptionSymbol,
                irBuiltIns.illegalArgumentExceptionSymbol,
                irBuiltIns.dataClassArrayMemberHashCodeSymbol,
                irBuiltIns.dataClassArrayMemberToStringSymbol,
                irBuiltIns.checkNotNullSymbol,
                irBuiltIns.linkageErrorSymbol,
            ))
            addAll(irBuiltIns.ieee754equalsFunByOperandType.values)
            addAll(irBuiltIns.lessFunByOperandType.values)
            addAll(irBuiltIns.lessOrEqualFunByOperandType.values)
            addAll(irBuiltIns.greaterOrEqualFunByOperandType.values)
            addAll(irBuiltIns.greaterFunByOperandType.values)
        }
    }

    /**
     * This is a separate symbol table ("detached") from the symbol table ("main") that is used in IR linker.
     *
     * The goal is to separate the linkage process, which should end with all symbols been bound to the respective declarations,
     * and the process of partial deserialization of inline functions, which should produce some amount of unbound symbols
     * that are not supposed to be linked and therefore should not be tracked in the main symbol table.
     */
    private val detachedSymbolTable = SymbolTable(signaturer = null, irBuiltIns.irFactory)

    private val moduleDeserializers = hashMapOf<KotlinLibrary, ModuleDeserializer?>()
    private val modules = hashMapOf<KotlinLibrary, IrModuleFragment>()

    fun deserializeInlineFunction(function: IrSimpleFunction): IrSimpleFunction? {
        check(function.isInline) { "Non-inline function: ${function.render()}" }
        check(!function.isFakeOverride) { "Deserialization of fake overrides is not supported: ${function.render()}" }

        if (function.body != null) return null

        check(!function.isEffectivelyPrivate()) { "Deserialization of private inline functions is not supported: ${function.render()}" }

        // We can deserialize only functions from other modules
        if (function.getPackageFragment() !is IrExternalPackageFragment) return null

        val deserializedContainerSource = function.containerSource
        check(deserializedContainerSource is KlibDeserializedContainerSource) {
            "Cannot deserialize inline function from a non-Kotlin library: ${function.render()}\nFunction source: " +
                    deserializedContainerSource?.let { "${it::class.java}, ${it.presentableString}" }
        }

        val library = deserializedContainerSource.klib
        val moduleDeserializer = moduleDeserializers.getOrPut(library) {
            val inlinableFunctionsIr = library.inlinableFunctionsIr
            val mainIr = library.ir
            val ir = inlinableFunctionsIr ?: mainIr.takeIf { fallbackToMainIr }
            ir?.let {
                ModuleDeserializer(
                    ir = it,
                    supportingMainIr = mainIr.takeIf { inlinableFunctionsIr != null && fallbackToMainIr },
                    containsPreparedInlineFunctionCopies = inlinableFunctionsIr != null,
                    detachedSymbolTable = detachedSymbolTable,
                    irInterner = irInterner,
                    irFactory = irBuiltIns.irFactory,
                    anyNType = irBuiltIns.anyNType,
                    unitType = irBuiltIns.unitType,
                    nothingType = irBuiltIns.nothingType,
                    externalSymbolResolver = if (fallbackToMainIr) ::findSelectedDependencySymbol else null,
                )
            }
        } ?: return null

        val functionSignature: IdSignature = signatureComputer.computeSignature(function)
        // Inside the module deserializer "functionSignature" will be mapped to erased copy of inline function and this copy will be returned.
        val originalFunctionModule = modules.getOrPut(library) { function.moduleFragment }
        val deserializedFunction: IrSimpleFunction =
            moduleDeserializer.deserializeInlineFunction(functionSignature, function.getPackageFragment(), originalFunctionModule)
                ?: return null
        deserializedFunction.originalOfPreparedInlineFunctionCopy = function
        return deserializedFunction
    }

    @OptIn(InternalSymbolFinderAPI::class)
    private fun findSelectedDependencySymbol(
        signature: IdSignature,
        symbolKind: BinarySymbolData.SymbolKind,
    ): IrSymbol? {
        fun IrSymbol.matches(target: IdSignature): Boolean {
            if (this.signature == target) return true
            // Fake-override symbols deliberately have no owner. Their synthetic signature may
            // still match directly above; otherwise only a genuinely bound declaration can be
            // inspected or have a signature recomputed.
            if (!isBound) return false
            val declaration = owner as? IrDeclaration ?: return false
            return runCatching { signatureComputer.computeSignature(declaration) }.getOrNull() == target
        }

        fun IrSymbol.unwrapFakeOverride(): IrSymbol {
            var current = this
            while (current is IrFakeOverrideSymbolBase<*, *, *>) {
                current = current.originalSymbol
            }
            return current
        }

        fun resolveCandidate(candidate: IrSymbol?, symbolKind: BinarySymbolData.SymbolKind): IrSymbol? {
            val unwrapped = candidate?.unwrapFakeOverride() ?: return null
            val originalSignature = unwrapped.signature
            return if (!unwrapped.isBound && originalSignature != null && originalSignature != signature) {
                findSelectedDependencySymbol(originalSignature, symbolKind)
            } else {
                unwrapped
            }
        }

        if (signature is IdSignature.AccessorSignature) {
            val property = findSelectedDependencySymbol(
                signature.propertySignature,
                BinarySymbolData.SymbolKind.PROPERTY_SYMBOL,
            )?.owner as? IrProperty ?: return null
            val accessors = listOfNotNull(property.getter, property.setter).map { it.symbol }
            return accessors.firstOrNull { it.matches(signature) } ?: accessors.singleOrNull()
        }

        val commonSignature = signature as? IdSignature.CommonSignature ?: return null
        val packageFqName = FqName(commonSignature.packageFqName)
        val segments = commonSignature.nameSegments

        fun classId(segmentCount: Int): ClassId? {
            if (segmentCount <= 0) return null
            return ClassId(
                packageFqName,
                FqName(segments.take(segmentCount).joinToString(".")),
                isLocal = false,
            )
        }

        fun callableId(): CallableId {
            val callableName = Name.identifier(segments.last())
            val owner = classId(segments.lastIndex)
            return if (owner == null) CallableId(packageFqName, callableName) else CallableId(owner, callableName)
        }

        return when (symbolKind) {
            BinarySymbolData.SymbolKind.CLASS_SYMBOL -> {
                val requestedFqName = if (commonSignature.packageFqName.isEmpty()) {
                    commonSignature.declarationFqName
                } else {
                    "${commonSignature.packageFqName}.${commonSignature.declarationFqName}"
                }
                eagerlyKnownBuiltInClasses.firstOrNull {
                    it.matches(signature) || it.owner.kotlinFqName?.asString() == requestedFqName
                }
                    ?: classId(segments.size)?.let(irBuiltIns.symbolFinder::findClass)
            }
            BinarySymbolData.SymbolKind.FUNCTION_SYMBOL -> {
                val callableId = callableId()
                val knownBuiltIns = eagerlyKnownBuiltInFunctions.filter { it.owner.callableId == callableId }
                val selectedFunctions = irBuiltIns.symbolFinder.findFunctions(callableId)
                // Synthetic built-ins can be reconstructed with a signature variant different
                // from the serialized copy. The same is true for a selected fake override: its
                // synthetic owner signature may differ from the serialized call while its stable
                // callable identity names exactly one inherited declaration. Accept that logical
                // identity only when unambiguous; overloaded operators still require an exact
                // signature match.
                resolveCandidate(knownBuiltIns.firstOrNull { it.matches(signature) }
                    ?: knownBuiltIns.singleOrNull()
                    ?: selectedFunctions.firstOrNull { it.matches(signature) }
                    ?: selectedFunctions.singleOrNull(), symbolKind)
            }
            BinarySymbolData.SymbolKind.PROPERTY_SYMBOL -> {
                val selectedProperties = irBuiltIns.symbolFinder.findProperties(callableId())
                resolveCandidate(
                    selectedProperties.firstOrNull { it.matches(signature) }
                        ?: selectedProperties.singleOrNull(),
                    symbolKind,
                )
            }
            BinarySymbolData.SymbolKind.CONSTRUCTOR_SYMBOL ->
                classId(segments.lastIndex)?.let(irBuiltIns.symbolFinder::findClass)?.owner?.constructors
                    ?.firstOrNull { it.symbol.matches(signature) }?.symbol
            BinarySymbolData.SymbolKind.FIELD_SYMBOL,
            BinarySymbolData.SymbolKind.STANDALONE_FIELD_SYMBOL ->
                irBuiltIns.symbolFinder.findProperties(callableId()).asSequence()
                    .mapNotNull { it.owner.backingField?.symbol }
                    .firstOrNull { it.matches(signature) }
            BinarySymbolData.SymbolKind.ENUM_ENTRY_SYMBOL ->
                classId(segments.lastIndex)?.let(irBuiltIns.symbolFinder::findClass)?.owner?.declarations
                    ?.asSequence()?.filterIsInstance<IrEnumEntry>()
                    ?.map { it.symbol }?.firstOrNull { it.matches(signature) }
            else -> null
        }
    }

    class ModuleDeserializer(
        ir: KlibIrComponent,
        supportingMainIr: KlibIrComponent?,
        private val containsPreparedInlineFunctionCopies: Boolean,
        private val detachedSymbolTable: SymbolTable,
        private val irInterner: IrInterningService,
        private val irFactory: IrFactory,
        private val anyNType: IrType,
        private val unitType: IrType,
        private val nothingType: IrType,
        private val externalSymbolResolver: ((IdSignature, BinarySymbolData.SymbolKind) -> IrSymbol?)?,
    ) {
        private val files = List(ir.irFileCount) { fileIndex ->
            FileDeserializer(ir, fileIndex, containsPreparedInlineFunctionCopies)
        }
        private val signatureToFile = buildMap {
            for (file in files) {
                for (signature in file.reversedSignatureIndex.keys) {
                    putIfAbsent(signature, file)
                }
            }
        }
        private val supportingFiles = when {
            supportingMainIr != null -> List(supportingMainIr.irFileCount) { fileIndex ->
                FileDeserializer(supportingMainIr, fileIndex, containsPreparedInlineFunctionCopies = false)
            }
            !containsPreparedInlineFunctionCopies -> files
            else -> emptyList()
        }
        private val supportingSignatureToFile = buildMap {
            for (file in supportingFiles) {
                for (signature in file.reversedSignatureIndex.keys) {
                    putIfAbsent(signature, file)
                }
            }
        }

        private val deserializedFunctionCache = mutableMapOf<IdSignature, IrSimpleFunction?>()
        private var originalFunctionModule: IrModuleFragment? = null

        fun deserializeInlineFunction(
            signature: IdSignature,
            originalFunctionPackage: IrPackageFragment,
            originalFunctionModule: IrModuleFragment,
        ): IrSimpleFunction? =
            deserializedFunctionCache.getOrPut(signature) {
                this.originalFunctionModule = originalFunctionModule
                supportingSignatureToFile[signature.topLevelSignature()]?.deserializedFunction(signature)?.let {
                    return@getOrPut it
                }
                val lookupSignature = if (containsPreparedInlineFunctionCopies) signature else signature.topLevelSignature()
                signatureToFile[lookupSignature]?.deserializeInlineFunction(
                    signature,
                    originalFunctionPackage,
                    originalFunctionModule,
                )
            }

        private fun referenceAndDeserializeSymbol(
            signature: IdSignature,
            symbolKind: BinarySymbolData.SymbolKind,
        ): IrSymbol {
            fun reference(): IrSymbol = referenceDeserializedSymbol(
                detachedSymbolTable,
                fileSymbol = null,
                symbolKind,
                signature,
            )

            val symbol = reference()
            if (symbol.isBound) return symbol
            externalSymbolResolver?.invoke(signature, symbolKind)?.let { return it }
            val module = originalFunctionModule ?: return symbol
            supportingSignatureToFile[signature.topLevelSignature()]?.deserializeTopLevel(
                signature.topLevelSignature(),
                module,
            )
            return reference()
        }

        private inner class FileDeserializer(
            ir: KlibIrComponent,
            fileIndex: Int,
            private val containsPreparedInlineFunctionCopies: Boolean,
        ) {
            private val fileReader = IrLibraryFileFromBytes(IrKlibBytesSource(ir, fileIndex))
            private val fileProto = ProtoFile.parseFrom(
                ir.irFile(fileIndex).codedInputStream,
                ExtensionRegistryLite.getEmptyRegistry(),
            )
            private val dummyFileSymbol = IrFileImpl(
                fileEntry = object : IrFileEntry {
                    override val name: String get() = "<dummy>"
                    override val maxOffset: Int get() = shouldNotBeCalled()
                    override val lineStartOffsets: IntArray get() = shouldNotBeCalled()
                    override val firstRelevantLineIndex: Int get() = shouldNotBeCalled()
                    override fun getSourceRangeInfo(beginOffset: Int, endOffset: Int): SourceRangeInfo = shouldNotBeCalled()
                    override fun getLineNumber(offset: Int): Int = shouldNotBeCalled()
                    override fun getColumnNumber(offset: Int): Int = shouldNotBeCalled()
                    override fun getLineAndColumnNumbers(offset: Int): LineAndColumn = shouldNotBeCalled()
                },
                symbol = IrFileSymbolImpl(),
                packageFqName = FqName("<uninitialized>"),
                module = IrErrorModuleFragment
            ).symbol
            private val symbolDeserializer = IrSymbolDeserializer(
                detachedSymbolTable,
                fileReader,
                dummyFileSymbol,
                enqueueLocalTopLevelDeclaration = {},
                irInterner,
                deserializePublicSymbolWithOwnerInUnknownFile = { signature, symbolKind ->
                    referenceAndDeserializeSymbol(signature, symbolKind)
                }
            )
            private val fileEntryDeserializer = FileEntryDeserializer(irInterner)
            private val declarationDeserializer = IrDeclarationDeserializer(
                unitType = unitType,
                nothingType = nothingType,
                symbolTable = detachedSymbolTable,
                irFactory = irFactory,
                libraryFile = fileReader,
                parent = dummyFileSymbol.owner,
                settings = IrDeserializationSettings(
                    deserializeFunctionBodies = DeserializeFunctionBodies.ONLY_INLINE,
                    nullableAnyAsAnnotationConstructorCallType = anyNType,
                ),
                symbolDeserializer = symbolDeserializer,
                onDeserializedClass = { _, _ -> },
                needToDeserializeFakeOverrides = { false },
                specialProcessingForMismatchedSymbolKind = null,
                irInterner = irInterner,
                fileEntryDeserializer = fileEntryDeserializer,
            )

            val reversedSignatureIndex: Map<IdSignature, Int> =
                fileProto.declarationIdList.associateBy { symbolDeserializer.deserializeIdSignature(it) }

            private var mainFile: IrFile? = null
            private val deserializedMainFunctions = mutableMapOf<IdSignature, IrSimpleFunction>()
            private val deserializedTopLevels = mutableSetOf<IdSignature>()

            fun deserializedFunction(signature: IdSignature): IrSimpleFunction? = deserializedMainFunctions[signature]

            fun deserializeTopLevel(
                topLevelSignature: IdSignature,
                originalFunctionModule: IrModuleFragment,
            ) {
                if (!deserializedTopLevels.add(topLevelSignature)) return
                val idSigIndex = reversedSignatureIndex[topLevelSignature] ?: return
                val file = mainFile ?: fileReader.createFile(originalFunctionModule, fileProto, fileEntryDeserializer).also {
                    mainFile = it
                }
                val declaration = declarationDeserializer.deserializeDeclaration(fileReader.declaration(idSigIndex), file.startOffset)
                declaration.parent = file
                file.declarations += declaration
                declaration.acceptVoid(object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) {
                        element.acceptChildrenVoid(this)
                    }

                    override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                        declaration.symbol.signature?.let { deserializedMainFunctions[it] = declaration }
                        super.visitSimpleFunction(declaration)
                    }
                })
            }

            fun deserializeInlineFunction(
                signature: IdSignature,
                originalFunctionPackage: IrPackageFragment,
                originalFunctionModule: IrModuleFragment,
            ): IrSimpleFunction? {
                if (containsPreparedInlineFunctionCopies) {
                    val idSigIndex = reversedSignatureIndex[signature] ?: return null
                    val functionProto = fileReader.declaration(idSigIndex)
                    val fileEntry = fileEntryDeserializer.fileEntry(
                        fileReader,
                        functionProto.irFunction.preparedInlineFunctionFileEntryId,
                    )
                    val file = IrFileImpl(
                        symbol = IrFileSymbolImpl(with(originalFunctionPackage.symbol) { runIf(hasDescriptor) { descriptor } }),
                        packageFqName = originalFunctionPackage.packageFqName,
                        fileEntry = fileEntry,
                        module = originalFunctionModule,
                    )
                    return (declarationDeserializer.deserializeDeclaration(functionProto, file.startOffset) as IrSimpleFunction).also {
                        it.parent = file
                        file.declarations += it
                    }
                }

                val topLevelSignature = signature.topLevelSignature()
                deserializeTopLevel(topLevelSignature, originalFunctionModule)
                return deserializedMainFunctions[signature]
            }
        }
    }
}
