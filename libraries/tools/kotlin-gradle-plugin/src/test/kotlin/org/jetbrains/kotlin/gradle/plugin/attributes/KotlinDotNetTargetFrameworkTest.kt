/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.attributes

import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.attributes.MultipleCandidatesDetails
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalKotlinGradlePluginApi::class)
class KotlinDotNetTargetFrameworkTest {
    @Test
    fun `target frameworks use canonical TFM names`() {
        assertEquals("org.jetbrains.kotlin.dotnet.targetFramework", KotlinDotNetTargetFramework.ATTRIBUTE.name)
        val expectedMonikers = mapOf(
            KotlinDotNetTargetFramework.NET48 to "net48",
            KotlinDotNetTargetFramework.NETSTANDARD_2_0 to "netstandard2.0",
            KotlinDotNetTargetFramework.NET10_0 to "net10.0",
        )

        for ((targetFramework, expectedMoniker) in expectedMonikers) {
            assertEquals(expectedMoniker, targetFramework.targetFrameworkMoniker)
            assertEquals(expectedMoniker, targetFramework.getName())
            assertEquals(expectedMoniker, targetFramework.toString())
        }
    }

    @Test
    fun `only runtime profiles consume the portable profile`() {
        val compatiblePairs = setOf(
            KotlinDotNetTargetFramework.NET48 to KotlinDotNetTargetFramework.NETSTANDARD_2_0,
            KotlinDotNetTargetFramework.NET10_0 to KotlinDotNetTargetFramework.NETSTANDARD_2_0,
        )

        for (consumer in KotlinDotNetTargetFramework.entries) {
            for (producer in KotlinDotNetTargetFramework.entries) {
                val details = TestCompatibilityDetails(consumer, producer)

                KotlinDotNetTargetFramework.CompatibilityRule().execute(details)

                assertEquals(
                    consumer to producer in compatiblePairs,
                    details.compatible,
                    "$consumer consuming $producer",
                )
                assertFalse(details.incompatible)
            }
        }
    }

    @Test
    fun `exact runtime profile wins over portable fallback`() {
        for (requested in listOf(KotlinDotNetTargetFramework.NET48, KotlinDotNetTargetFramework.NET10_0)) {
            val details = TestMultipleCandidatesDetails(
                consumerValue = requested,
                candidateValues = setOf(requested, KotlinDotNetTargetFramework.NETSTANDARD_2_0),
            )

            KotlinDotNetTargetFramework.DisambiguationRule().execute(details)

            assertEquals(requested, details.closestMatch)
        }
    }

    @Test
    fun `portable profile is selected only as a compatible fallback`() {
        for (requested in listOf(KotlinDotNetTargetFramework.NET48, KotlinDotNetTargetFramework.NET10_0)) {
            val details = TestMultipleCandidatesDetails(
                consumerValue = requested,
                candidateValues = setOf(KotlinDotNetTargetFramework.NETSTANDARD_2_0),
            )

            KotlinDotNetTargetFramework.DisambiguationRule().execute(details)

            assertEquals(KotlinDotNetTargetFramework.NETSTANDARD_2_0, details.closestMatch)
        }
    }

    @Test
    fun `consumer without a profile remains ambiguous`() {
        val details = TestMultipleCandidatesDetails(
            consumerValue = null,
            candidateValues = KotlinDotNetTargetFramework.entries.toSet(),
        )

        KotlinDotNetTargetFramework.DisambiguationRule().execute(details)

        assertNull(details.closestMatch)
    }

    private class TestCompatibilityDetails(
        private val consumerValue: KotlinDotNetTargetFramework,
        private val producerValue: KotlinDotNetTargetFramework,
    ) : CompatibilityCheckDetails<KotlinDotNetTargetFramework> {
        var compatible: Boolean = false
            private set

        var incompatible: Boolean = false
            private set

        override fun getConsumerValue(): KotlinDotNetTargetFramework = consumerValue

        override fun getProducerValue(): KotlinDotNetTargetFramework = producerValue

        override fun compatible() {
            compatible = true
        }

        override fun incompatible() {
            incompatible = true
        }
    }

    private class TestMultipleCandidatesDetails(
        private val consumerValue: KotlinDotNetTargetFramework?,
        private val candidateValues: Set<KotlinDotNetTargetFramework>,
    ) : MultipleCandidatesDetails<KotlinDotNetTargetFramework> {
        var closestMatch: KotlinDotNetTargetFramework? = null
            private set

        override fun getConsumerValue(): KotlinDotNetTargetFramework? = consumerValue

        override fun getCandidateValues(): Set<KotlinDotNetTargetFramework> = candidateValues

        override fun closestMatch(candidate: KotlinDotNetTargetFramework) {
            closestMatch = candidate
        }
    }
}
