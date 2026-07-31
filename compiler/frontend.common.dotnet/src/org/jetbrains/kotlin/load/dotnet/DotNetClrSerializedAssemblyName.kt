package org.jetbrains.kotlin.load.dotnet

data class DotNetClrSerializedAssemblyVersion(
    val components: List<Int>,
) {
    init {
        require(components.size in 2..4)
        require(components.all { component -> component in 0..0xffff })
    }
}

enum class DotNetClrSerializedProcessorArchitecture {
    MSIL,
    X86,
    IA64,
    AMD64,
    ARM,
}

enum class DotNetClrSerializedAssemblyContentType {
    WINDOWS_RUNTIME,
}

data class DotNetClrSerializedAssemblyProperty(
    val name: String,
    val value: String,
)

data class DotNetClrSerializedAssemblyName(
    val name: String,
    val version: DotNetClrSerializedAssemblyVersion?,
    /**
     * Null means absent; the empty string is neutral culture.
     */
    val cultureName: String?,
    /**
     * Null means absent; an empty list is an explicit `null` key/token.
     */
    val publicKeyOrToken: List<Int>?,
    val hasPublicKey: Boolean,
    val processorArchitecture: DotNetClrSerializedProcessorArchitecture?,
    val isRetargetable: Boolean,
    val contentType: DotNetClrSerializedAssemblyContentType?,
    /**
     * Unknown properties retained for Desktop-compatible selected-graph policy and diagnostics.
     */
    val unknownProperties: List<DotNetClrSerializedAssemblyProperty>,
)

enum class DotNetClrSerializedAssemblyNameFailure {
    EMPTY_NAME,
    UNEXPECTED_TOKEN,
    EMPTY_PROPERTY_NAME,
    DUPLICATE_PROPERTY,
    INVALID_VERSION,
    INVALID_PUBLIC_KEY_OR_TOKEN,
    INVALID_PROCESSOR_ARCHITECTURE,
    INVALID_RETARGETABLE,
    INVALID_CONTENT_TYPE,
    INVALID_ESCAPE,
    UNCLOSED_QUOTE,
    EMBEDDED_NULL,
}

sealed interface DotNetClrSerializedAssemblyNameParsing {
    data class Parsed(
        val assemblyName: DotNetClrSerializedAssemblyName,
    ) : DotNetClrSerializedAssemblyNameParsing

    data class Invalid(
        val failure: DotNetClrSerializedAssemblyNameFailure,
        val offset: Int,
    ) : DotNetClrSerializedAssemblyNameParsing
}

/**
 * JVM-hosted counterpart of the CLR AssemblyName display-name lexer.
 *
 * It parses identity input but performs no binding. Unknown properties follow Desktop
 * compatibility and are retained rather than treated as identity or discarded.
 */
object DotNetClrSerializedAssemblyNameParser {
    fun parse(value: String): DotNetClrSerializedAssemblyNameParsing {
        if (value.isEmpty()) {
            return DotNetClrSerializedAssemblyNameParsing.Invalid(
                DotNetClrSerializedAssemblyNameFailure.EMPTY_NAME,
                0,
            )
        }
        val parser = Parser(value)
        return try {
            DotNetClrSerializedAssemblyNameParsing.Parsed(parser.parse())
        } catch (failure: AssemblyNameParseFailure) {
            DotNetClrSerializedAssemblyNameParsing.Invalid(
                failure.failure,
                failure.offset,
            )
        }
    }

    private class Parser(
        private val value: String,
    ) {
        private var offset = 0

        fun parse(): DotNetClrSerializedAssemblyName {
            val name = readStringToken()
            if (name.value.isEmpty()) {
                invalid(DotNetClrSerializedAssemblyNameFailure.EMPTY_NAME, name.offset)
            }

            var version: DotNetClrSerializedAssemblyVersion? = null
            var cultureName: String? = null
            var publicKeyOrToken: List<Int>? = null
            var hasPublicKey = false
            var processorArchitecture: DotNetClrSerializedProcessorArchitecture? = null
            var isRetargetable = false
            var contentType: DotNetClrSerializedAssemblyContentType? = null
            val unknownProperties = mutableListOf<DotNetClrSerializedAssemblyProperty>()
            val seenProperties = mutableSetOf<KnownProperty>()

            while (true) {
                skipWhitespace()
                if (isAtEnd) break
                expect(PROPERTY_SEPARATOR)
                val propertyName = readStringToken()
                if (propertyName.value.isEmpty()) {
                    invalid(
                        DotNetClrSerializedAssemblyNameFailure.EMPTY_PROPERTY_NAME,
                        propertyName.offset,
                    )
                }
                skipWhitespace()
                expect(PROPERTY_ASSIGNMENT)
                val propertyValue = readStringToken()
                if (propertyValue.value.isEmpty() && !propertyValue.wasQuoted) {
                    invalid(
                        DotNetClrSerializedAssemblyNameFailure.UNEXPECTED_TOKEN,
                        propertyValue.offset,
                    )
                }
                when (val property = knownProperty(propertyName.value)) {
                    KnownProperty.VERSION -> {
                        recordProperty(seenProperties, property, propertyName.offset)
                        version = parseVersion(propertyValue)
                    }

                    KnownProperty.CULTURE -> {
                        recordProperty(seenProperties, property, propertyName.offset)
                        cultureName =
                            if (propertyValue.value.equals("neutral", ignoreCase = true)) {
                                ""
                            } else {
                                propertyValue.value
                            }
                    }

                    KnownProperty.PUBLIC_KEY_TOKEN -> {
                        recordPublicKeyProperty(
                            seenProperties,
                            propertyName.offset,
                        )
                        publicKeyOrToken = parseKey(propertyValue, isToken = true)
                    }

                    KnownProperty.PUBLIC_KEY -> {
                        recordPublicKeyProperty(
                            seenProperties,
                            propertyName.offset,
                        )
                        publicKeyOrToken = parseKey(propertyValue, isToken = false)
                        hasPublicKey = true
                    }

                    KnownProperty.PROCESSOR_ARCHITECTURE -> {
                        recordProperty(seenProperties, property, propertyName.offset)
                        processorArchitecture =
                            DotNetClrSerializedProcessorArchitecture.entries.singleOrNull {
                                architecture ->
                                architecture.name.equals(
                                    propertyValue.value,
                                    ignoreCase = true,
                                )
                            } ?: invalid(
                                DotNetClrSerializedAssemblyNameFailure
                                    .INVALID_PROCESSOR_ARCHITECTURE,
                                propertyValue.offset,
                            )
                    }

                    KnownProperty.RETARGETABLE -> {
                        recordProperty(seenProperties, property, propertyName.offset)
                        isRetargetable = when {
                            propertyValue.value.equals("yes", ignoreCase = true) -> true
                            propertyValue.value.equals("no", ignoreCase = true) -> false
                            else -> invalid(
                                DotNetClrSerializedAssemblyNameFailure.INVALID_RETARGETABLE,
                                propertyValue.offset,
                            )
                        }
                    }

                    KnownProperty.CONTENT_TYPE -> {
                        recordProperty(seenProperties, property, propertyName.offset)
                        contentType =
                            if (
                                propertyValue.value.equals(
                                    "WindowsRuntime",
                                    ignoreCase = true,
                                )
                            ) {
                                DotNetClrSerializedAssemblyContentType.WINDOWS_RUNTIME
                            } else {
                                invalid(
                                    DotNetClrSerializedAssemblyNameFailure.INVALID_CONTENT_TYPE,
                                    propertyValue.offset,
                                )
                            }
                    }

                    null -> unknownProperties += DotNetClrSerializedAssemblyProperty(
                        propertyName.value,
                        propertyValue.value,
                    )
                }
            }

            return DotNetClrSerializedAssemblyName(
                name = name.value,
                version = version,
                cultureName = cultureName,
                publicKeyOrToken = publicKeyOrToken,
                hasPublicKey = hasPublicKey,
                processorArchitecture = processorArchitecture,
                isRetargetable = isRetargetable,
                contentType = contentType,
                unknownProperties = unknownProperties.toList(),
            )
        }

        private fun parseVersion(
            token: StringToken,
        ): DotNetClrSerializedAssemblyVersion {
            val parts = token.value.split('.')
            if (parts.size !in 2..4) {
                invalid(
                    DotNetClrSerializedAssemblyNameFailure.INVALID_VERSION,
                    token.offset,
                )
            }
            val components = parts.map { part ->
                if (part.isEmpty() || part.any { character -> character !in '0'..'9' }) {
                    invalid(
                        DotNetClrSerializedAssemblyNameFailure.INVALID_VERSION,
                        token.offset,
                    )
                }
                part.toIntOrNull()?.takeIf { component -> component in 0..0xffff }
                    ?: invalid(
                        DotNetClrSerializedAssemblyNameFailure.INVALID_VERSION,
                        token.offset,
                    )
            }
            return DotNetClrSerializedAssemblyVersion(components)
        }

        private fun parseKey(
            token: StringToken,
            isToken: Boolean,
        ): List<Int> {
            if (token.value.isEmpty() ||
                token.value.equals("null", ignoreCase = true)
            ) {
                return emptyList()
            }
            if (token.value.length % 2 != 0 ||
                isToken && token.value.length != PUBLIC_KEY_TOKEN_HEX_LENGTH
            ) {
                invalid(
                    DotNetClrSerializedAssemblyNameFailure.INVALID_PUBLIC_KEY_OR_TOKEN,
                    token.offset,
                )
            }
            return token.value.chunked(2).map { encodedByte ->
                encodedByte.toIntOrNull(16)
                    ?: invalid(
                        DotNetClrSerializedAssemblyNameFailure.INVALID_PUBLIC_KEY_OR_TOKEN,
                        token.offset,
                    )
            }
        }

        private fun recordPublicKeyProperty(
            seenProperties: MutableSet<KnownProperty>,
            propertyOffset: Int,
        ) {
            if (KnownProperty.PUBLIC_KEY in seenProperties ||
                KnownProperty.PUBLIC_KEY_TOKEN in seenProperties
            ) {
                invalid(
                    DotNetClrSerializedAssemblyNameFailure.DUPLICATE_PROPERTY,
                    propertyOffset,
                )
            }
            seenProperties += KnownProperty.PUBLIC_KEY_TOKEN
        }

        private fun recordProperty(
            seenProperties: MutableSet<KnownProperty>,
            property: KnownProperty,
            propertyOffset: Int,
        ) {
            if (!seenProperties.add(property)) {
                invalid(
                    DotNetClrSerializedAssemblyNameFailure.DUPLICATE_PROPERTY,
                    propertyOffset,
                )
            }
        }

        private fun knownProperty(name: String): KnownProperty? =
            KnownProperty.entries.singleOrNull { property ->
                property.displayName.equals(name, ignoreCase = true)
            }

        private fun readStringToken(): StringToken {
            skipWhitespace()
            val tokenOffset = offset
            val result = StringBuilder()
            val quote = when (peek()) {
                '\'',
                '"',
                -> value[offset++]

                else -> null
            }
            while (!isAtEnd) {
                val character = checkNotNull(peek())
                if (character == '\u0000') {
                    invalid(
                        DotNetClrSerializedAssemblyNameFailure.EMBEDDED_NULL,
                        offset,
                    )
                }
                if (quote != null && character == quote) {
                    offset++
                    return StringToken(
                        result.toString(),
                        tokenOffset,
                        wasQuoted = true,
                    )
                }
                if (quote == null &&
                    (character == PROPERTY_SEPARATOR ||
                            character == PROPERTY_ASSIGNMENT)
                ) {
                    break
                }
                if (quote == null && (character == '\'' || character == '"')) {
                    invalid(
                        DotNetClrSerializedAssemblyNameFailure.UNEXPECTED_TOKEN,
                        offset,
                    )
                }
                if (character == ESCAPE) {
                    offset++
                    val escaped = peek() ?: invalid(
                        DotNetClrSerializedAssemblyNameFailure.INVALID_ESCAPE,
                        offset,
                    )
                    result.append(
                        when (escaped) {
                            '\\',
                            ',',
                            '=',
                            '\'',
                            '"',
                            -> escaped

                            't' -> '\t'
                            'r' -> '\r'
                            'n' -> '\n'
                            else -> invalid(
                                DotNetClrSerializedAssemblyNameFailure.INVALID_ESCAPE,
                                offset,
                            )
                        }
                    )
                    offset++
                } else {
                    result.append(character)
                    offset++
                }
            }
            if (quote != null) {
                invalid(
                    DotNetClrSerializedAssemblyNameFailure.UNCLOSED_QUOTE,
                    tokenOffset,
                )
            }
            var resultLength = result.length
            while (resultLength > 0 && result[resultLength - 1].isClrAssemblyWhitespace()) {
                resultLength--
            }
            return StringToken(
                result.substring(0, resultLength),
                tokenOffset,
                wasQuoted = false,
            )
        }

        private fun skipWhitespace() {
            while (peek()?.isClrAssemblyWhitespace() == true) offset++
        }

        private fun expect(character: Char) {
            if (peek() != character) {
                invalid(DotNetClrSerializedAssemblyNameFailure.UNEXPECTED_TOKEN, offset)
            }
            offset++
        }

        private val isAtEnd: Boolean
            get() = offset == value.length

        private fun peek(): Char? = value.getOrNull(offset)

        private fun invalid(
            failure: DotNetClrSerializedAssemblyNameFailure,
            failureOffset: Int,
        ): Nothing = throw AssemblyNameParseFailure(failure, failureOffset)
    }

    private data class StringToken(
        val value: String,
        val offset: Int,
        val wasQuoted: Boolean,
    )

    private enum class KnownProperty(
        val displayName: String,
    ) {
        VERSION("Version"),
        CULTURE("Culture"),
        PUBLIC_KEY_TOKEN("PublicKeyToken"),
        PUBLIC_KEY("PublicKey"),
        PROCESSOR_ARCHITECTURE("ProcessorArchitecture"),
        RETARGETABLE("Retargetable"),
        CONTENT_TYPE("ContentType"),
    }

    private class AssemblyNameParseFailure(
        val failure: DotNetClrSerializedAssemblyNameFailure,
        val offset: Int,
    ) : Exception()

    private fun Char.isClrAssemblyWhitespace(): Boolean =
        this == '\n' || this == '\r' || this == ' ' || this == '\t'

    private const val PROPERTY_SEPARATOR = ','
    private const val PROPERTY_ASSIGNMENT = '='
    private const val ESCAPE = '\\'
    private const val PUBLIC_KEY_TOKEN_HEX_LENGTH = 16
}
