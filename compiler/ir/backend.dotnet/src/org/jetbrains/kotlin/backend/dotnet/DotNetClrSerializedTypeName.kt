package org.jetbrains.kotlin.backend.dotnet

data class DotNetClrSerializedTypeNamePart(
    val metadataName: String,
    val genericArity: Int,
)

data class DotNetClrSerializedNamedType(
    val namespaceName: String,
    val topLevelType: DotNetClrSerializedTypeNamePart,
    val nestedTypes: List<DotNetClrSerializedTypeNamePart>,
) {
    val totalGenericArity: Int =
        (listOf(topLevelType) + nestedTypes).sumOf { part -> part.genericArity }
}

sealed interface DotNetClrSerializedTypeModifier {
    data object Pointer : DotNetClrSerializedTypeModifier

    data object ByReference : DotNetClrSerializedTypeModifier

    data object SzArray : DotNetClrSerializedTypeModifier

    data class MdArray(
        val rank: Int,
    ) : DotNetClrSerializedTypeModifier
}

data class DotNetClrSerializedTypeName(
    val namedType: DotNetClrSerializedNamedType,
    val genericArguments: List<DotNetClrSerializedTypeName>,
    val modifiers: List<DotNetClrSerializedTypeModifier>,
    /**
     * The CLR AssemblyName display name without leading whitespace after the type-name comma.
     *
     * Assembly-name parsing and binding are deliberately separate from type-name syntax.
     */
    val assemblyDisplayName: String?,
)

enum class DotNetClrSerializedTypeNameFailure {
    EMPTY_NAME,
    NAME_TOO_LONG,
    INVALID_ESCAPE,
    EMPTY_TYPE_PART,
    INVALID_GENERIC_ARITY,
    GENERIC_ARGUMENT_COUNT_MISMATCH,
    EMPTY_GENERIC_ARGUMENT,
    INVALID_ARRAY_SHAPE,
    INVALID_BY_REFERENCE_SHAPE,
    EMPTY_ASSEMBLY_NAME,
    UNEXPECTED_TOKEN,
    NESTING_LIMIT_EXCEEDED,
    COMPONENT_LIMIT_EXCEEDED,
}

enum class DotNetClrSerializedTypeNameUnsupported {
    REFLECTION_EMIT_ARRAY_BOUND,
}

sealed interface DotNetClrSerializedTypeNameParsing {
    data class Parsed(
        val type: DotNetClrSerializedTypeName,
    ) : DotNetClrSerializedTypeNameParsing

    data class Invalid(
        val failure: DotNetClrSerializedTypeNameFailure,
        val offset: Int,
    ) : DotNetClrSerializedTypeNameParsing

    data class Unsupported(
        val unsupported: DotNetClrSerializedTypeNameUnsupported,
        val offset: Int,
    ) : DotNetClrSerializedTypeNameParsing
}

/**
 * Parses the reflection type-name grammar used by System.Type custom-attribute values.
 *
 * This parser is intentionally independent of assembly binding and Kotlin import policy. It
 * accepts the completed-runtime forms emitted by Type.AssemblyQualifiedName, normalizes the two
 * equivalent multidimensional-array spellings, and retains assembly display names for the
 * selected-graph resolver.
 */
object DotNetClrSerializedTypeNameParser {
    fun parse(value: String): DotNetClrSerializedTypeNameParsing {
        if (value.isEmpty()) {
            return DotNetClrSerializedTypeNameParsing.Invalid(
                DotNetClrSerializedTypeNameFailure.EMPTY_NAME,
                0,
            )
        }
        if (value.length > MAX_TYPE_NAME_LENGTH) {
            return DotNetClrSerializedTypeNameParsing.Invalid(
                DotNetClrSerializedTypeNameFailure.NAME_TOO_LONG,
                MAX_TYPE_NAME_LENGTH,
            )
        }
        val parser = Parser(value)
        return try {
            val type = parser.parseType(
                allowAssemblyName = true,
                terminator = null,
                allowGenericArgumentSeparator = false,
                depth = 0,
            )
            if (!parser.isAtEnd) {
                parser.invalid(DotNetClrSerializedTypeNameFailure.UNEXPECTED_TOKEN)
            }
            DotNetClrSerializedTypeNameParsing.Parsed(type)
        } catch (failure: ParseFailure) {
            DotNetClrSerializedTypeNameParsing.Invalid(failure.failure, failure.offset)
        } catch (unsupported: UnsupportedParseShape) {
            DotNetClrSerializedTypeNameParsing.Unsupported(
                unsupported.unsupported,
                unsupported.offset,
            )
        }
    }

    private class Parser(
        private val value: String,
    ) {
        private var offset = 0
        private var componentCount = 0

        val isAtEnd: Boolean
            get() = offset == value.length

        fun parseType(
            allowAssemblyName: Boolean,
            terminator: Char?,
            allowGenericArgumentSeparator: Boolean,
            depth: Int,
        ): DotNetClrSerializedTypeName {
            if (depth >= MAX_TYPE_NESTING_DEPTH) {
                invalid(DotNetClrSerializedTypeNameFailure.NESTING_LIMIT_EXCEEDED)
            }
            val namedType = parseNamedType(terminator)
            val genericArguments =
                if (namedType.totalGenericArity > 0 && startsGenericArgumentList()) {
                    parseGenericArguments(namedType.totalGenericArity, depth)
                } else {
                    emptyList()
                }
            val modifiers = parseModifiers()
            val assemblyDisplayName =
                if (allowAssemblyName && peek() == ASSEMBLY_SEPARATOR) {
                    offset++
                    parseAssemblyDisplayName(terminator)
                } else {
                    null
                }
            if (terminator == null) {
                if (!isAtEnd) invalid(DotNetClrSerializedTypeNameFailure.UNEXPECTED_TOKEN)
            } else if (peek() != terminator &&
                !(allowGenericArgumentSeparator && peek() == GENERIC_ARGUMENT_SEPARATOR)
            ) {
                invalid(DotNetClrSerializedTypeNameFailure.UNEXPECTED_TOKEN)
            }
            return DotNetClrSerializedTypeName(
                namedType = namedType,
                genericArguments = genericArguments,
                modifiers = modifiers,
                assemblyDisplayName = assemblyDisplayName,
            )
        }

        private fun parseNamedType(
            terminator: Char?,
        ): DotNetClrSerializedNamedType {
            val firstPart = readTypePart(isTopLevel = true, terminator)
            val nestedParts = mutableListOf<DotNetClrSerializedTypeNamePart>()
            while (peek() == NESTED_TYPE_SEPARATOR) {
                offset++
                val nestedPart = readTypePart(isTopLevel = false, terminator)
                nestedParts += typeNamePart(nestedPart.decoded, nestedPart.startOffset)
                countComponent()
            }
            val namespaceSeparator = firstPart.lastNamespaceSeparator
            val namespaceName =
                if (namespaceSeparator < 0) "" else firstPart.decoded.substring(
                    0,
                    namespaceSeparator,
                )
            val topLevelName = firstPart.decoded.substring(namespaceSeparator + 1)
            if (topLevelName.isEmpty()) {
                invalid(DotNetClrSerializedTypeNameFailure.EMPTY_TYPE_PART)
            }
            countComponent()
            return DotNetClrSerializedNamedType(
                namespaceName = namespaceName,
                topLevelType = typeNamePart(topLevelName, firstPart.startOffset),
                nestedTypes = nestedParts.toList(),
            )
        }

        private fun readTypePart(
            isTopLevel: Boolean,
            terminator: Char?,
        ): ReadTypePart {
            val start = offset
            val decoded = StringBuilder()
            var lastNamespaceSeparator = -1
            while (!isAtEnd) {
                val character = value[offset]
                if (character == ESCAPE) {
                    if (offset + 1 >= value.length) {
                        invalid(DotNetClrSerializedTypeNameFailure.INVALID_ESCAPE)
                    }
                    val escaped = value[offset + 1]
                    if (escaped !in ESCAPABLE_TYPE_NAME_CHARACTERS) {
                        invalid(DotNetClrSerializedTypeNameFailure.INVALID_ESCAPE)
                    }
                    decoded.append(escaped)
                    offset += 2
                    continue
                }
                if (character == NESTED_TYPE_SEPARATOR ||
                    character == GENERIC_OR_ARRAY_START ||
                    character == POINTER ||
                    character == BY_REFERENCE ||
                    character == ASSEMBLY_SEPARATOR ||
                    character == terminator
                ) {
                    break
                }
                if (isTopLevel && character == NAMESPACE_SEPARATOR) {
                    lastNamespaceSeparator = decoded.length
                }
                decoded.append(character)
                offset++
            }
            if (decoded.isEmpty()) {
                invalid(DotNetClrSerializedTypeNameFailure.EMPTY_TYPE_PART)
            }
            return ReadTypePart(
                decoded = decoded.toString(),
                lastNamespaceSeparator = lastNamespaceSeparator,
                startOffset = start,
            )
        }

        private fun typeNamePart(
            metadataName: String,
            startOffset: Int,
        ): DotNetClrSerializedTypeNamePart {
            val aritySeparator = metadataName.lastIndexOf(GENERIC_ARITY_SEPARATOR)
            val genericArity =
                if (aritySeparator < 0) {
                    0
                } else {
                    if (aritySeparator == 0) {
                        invalid(
                            DotNetClrSerializedTypeNameFailure.INVALID_GENERIC_ARITY,
                            startOffset,
                        )
                    }
                    val encodedArity = metadataName.substring(aritySeparator + 1)
                    if (encodedArity.isEmpty() ||
                        encodedArity.any { character -> character !in '0'..'9' }
                    ) {
                        invalid(
                            DotNetClrSerializedTypeNameFailure.INVALID_GENERIC_ARITY,
                            startOffset + aritySeparator,
                        )
                    }
                    encodedArity.toIntOrNull()?.takeIf { arity -> arity in 1..MAX_GENERIC_ARGUMENT_COUNT }
                        ?: invalid(
                            DotNetClrSerializedTypeNameFailure.INVALID_GENERIC_ARITY,
                            startOffset + aritySeparator,
                        )
                }
            return DotNetClrSerializedTypeNamePart(metadataName, genericArity)
        }

        private fun startsGenericArgumentList(): Boolean {
            if (peek() != GENERIC_OR_ARRAY_START) return false
            val next = peek(1) ?: return false
            return when (next) {
                GENERIC_OR_ARRAY_END,
                ARRAY_DIMENSION,
                GENERIC_ARGUMENT_SEPARATOR,
                -> false

                in '0'..'9',
                '-',
                -> !looksLikeReflectionEmitArrayShape()

                else -> true
            }
        }

        private fun looksLikeReflectionEmitArrayShape(): Boolean {
            var cursor = offset + 1
            while (cursor < value.length) {
                when (val character = value[cursor]) {
                    GENERIC_OR_ARRAY_END -> return true
                    ESCAPE,
                    GENERIC_OR_ARRAY_START,
                    -> return false

                    in '0'..'9',
                    '-',
                    '.',
                    '\u2026',
                    ARRAY_DIMENSION,
                    GENERIC_ARGUMENT_SEPARATOR,
                    -> cursor++

                    else -> return false
                }
            }
            return false
        }

        private fun parseGenericArguments(
            expectedCount: Int,
            depth: Int,
        ): List<DotNetClrSerializedTypeName> {
            expect(GENERIC_OR_ARRAY_START)
            val arguments = mutableListOf<DotNetClrSerializedTypeName>()
            while (true) {
                if (peek() == GENERIC_OR_ARRAY_END ||
                    peek() == GENERIC_ARGUMENT_SEPARATOR
                ) {
                    invalid(DotNetClrSerializedTypeNameFailure.EMPTY_GENERIC_ARGUMENT)
                }
                val bracketed = peek() == GENERIC_OR_ARRAY_START
                if (bracketed) offset++
                arguments += parseType(
                    allowAssemblyName = bracketed,
                    terminator = GENERIC_OR_ARRAY_END,
                    allowGenericArgumentSeparator = !bracketed,
                    depth = depth + 1,
                )
                countComponent()
                if (bracketed) expect(GENERIC_OR_ARRAY_END)
                when (peek()) {
                    GENERIC_ARGUMENT_SEPARATOR -> offset++
                    GENERIC_OR_ARRAY_END -> {
                        offset++
                        break
                    }

                    else -> invalid(DotNetClrSerializedTypeNameFailure.UNEXPECTED_TOKEN)
                }
            }
            if (arguments.size != expectedCount) {
                invalid(DotNetClrSerializedTypeNameFailure.GENERIC_ARGUMENT_COUNT_MISMATCH)
            }
            return arguments.toList()
        }

        private fun parseModifiers(): List<DotNetClrSerializedTypeModifier> {
            val modifiers = mutableListOf<DotNetClrSerializedTypeModifier>()
            var hasByReference = false
            while (true) {
                val modifier = when (peek()) {
                    POINTER -> {
                        offset++
                        DotNetClrSerializedTypeModifier.Pointer
                    }

                    BY_REFERENCE -> {
                        if (hasByReference) {
                            invalid(
                                DotNetClrSerializedTypeNameFailure.INVALID_BY_REFERENCE_SHAPE
                            )
                        }
                        offset++
                        hasByReference = true
                        DotNetClrSerializedTypeModifier.ByReference
                    }

                    GENERIC_OR_ARRAY_START -> parseArrayModifier()
                    else -> break
                }
                if (hasByReference && modifier !is DotNetClrSerializedTypeModifier.ByReference) {
                    invalid(DotNetClrSerializedTypeNameFailure.INVALID_BY_REFERENCE_SHAPE)
                }
                modifiers += modifier
                countComponent()
            }
            return modifiers.toList()
        }

        private fun parseArrayModifier(): DotNetClrSerializedTypeModifier {
            val start = offset
            expect(GENERIC_OR_ARRAY_START)
            if (peek() == GENERIC_OR_ARRAY_END) {
                offset++
                return DotNetClrSerializedTypeModifier.SzArray
            }
            val dimensions = mutableListOf<String>()
            val current = StringBuilder()
            while (true) {
                when (val character = peek()) {
                    null -> invalid(DotNetClrSerializedTypeNameFailure.INVALID_ARRAY_SHAPE)
                    GENERIC_ARGUMENT_SEPARATOR -> {
                        dimensions += current.toString()
                        current.clear()
                        offset++
                    }

                    GENERIC_OR_ARRAY_END -> {
                        dimensions += current.toString()
                        offset++
                        break
                    }

                    else -> {
                        current.append(character)
                        offset++
                    }
                }
            }
            if (dimensions.any { dimension ->
                    dimension.isNotEmpty() && dimension != ARRAY_DIMENSION.toString()
                }
            ) {
                throw UnsupportedParseShape(
                    DotNetClrSerializedTypeNameUnsupported.REFLECTION_EMIT_ARRAY_BOUND,
                    start,
                )
            }
            if (dimensions.size == 1 && dimensions.single().isEmpty()) {
                invalid(DotNetClrSerializedTypeNameFailure.INVALID_ARRAY_SHAPE, start)
            }
            return DotNetClrSerializedTypeModifier.MdArray(dimensions.size)
        }

        private fun parseAssemblyDisplayName(
            terminator: Char?,
        ): String {
            while (peek() == ' ') offset++
            val start = offset
            if (terminator == null) {
                offset = value.length
            } else {
                var quote: Char? = null
                while (!isAtEnd) {
                    val character = checkNotNull(peek())
                    if (character == ESCAPE && offset + 1 < value.length) {
                        offset += 2
                        continue
                    }
                    if (character == '\'' || character == '"') {
                        quote = if (quote == null) {
                            character
                        } else if (quote == character) {
                            null
                        } else {
                            quote
                        }
                        offset++
                        continue
                    }
                    if (character == terminator && quote == null) break
                    offset++
                }
            }
            val displayName = value.substring(start, offset)
            if (displayName.isEmpty()) {
                invalid(DotNetClrSerializedTypeNameFailure.EMPTY_ASSEMBLY_NAME, start)
            }
            return displayName
        }

        private fun expect(character: Char) {
            if (peek() != character) {
                invalid(DotNetClrSerializedTypeNameFailure.UNEXPECTED_TOKEN)
            }
            offset++
        }

        private fun peek(relativeOffset: Int = 0): Char? =
            value.getOrNull(offset + relativeOffset)

        private fun countComponent() {
            componentCount++
            if (componentCount > MAX_TYPE_COMPONENT_COUNT) {
                invalid(DotNetClrSerializedTypeNameFailure.COMPONENT_LIMIT_EXCEEDED)
            }
        }

        fun invalid(
            failure: DotNetClrSerializedTypeNameFailure,
            failureOffset: Int = offset,
        ): Nothing = throw ParseFailure(failure, failureOffset)
    }

    private data class ReadTypePart(
        val decoded: String,
        val lastNamespaceSeparator: Int,
        val startOffset: Int,
    )

    private class ParseFailure(
        val failure: DotNetClrSerializedTypeNameFailure,
        val offset: Int,
    ) : Exception()

    private class UnsupportedParseShape(
        val unsupported: DotNetClrSerializedTypeNameUnsupported,
        val offset: Int,
    ) : Exception()

    private const val MAX_TYPE_NAME_LENGTH = 1_000_000
    private const val MAX_TYPE_NESTING_DEPTH = 64
    private const val MAX_TYPE_COMPONENT_COUNT = 4_096
    private const val MAX_GENERIC_ARGUMENT_COUNT = 1_024
    private const val ESCAPE = '\\'
    private const val NAMESPACE_SEPARATOR = '.'
    private const val NESTED_TYPE_SEPARATOR = '+'
    private const val GENERIC_ARITY_SEPARATOR = '`'
    private const val GENERIC_OR_ARRAY_START = '['
    private const val GENERIC_OR_ARRAY_END = ']'
    private const val GENERIC_ARGUMENT_SEPARATOR = ','
    private const val ASSEMBLY_SEPARATOR = ','
    private const val POINTER = '*'
    private const val BY_REFERENCE = '&'
    private const val ARRAY_DIMENSION = '*'
    private val ESCAPABLE_TYPE_NAME_CHARACTERS =
        setOf(',', '+', '&', '*', '[', ']', '.', '\\')
}
