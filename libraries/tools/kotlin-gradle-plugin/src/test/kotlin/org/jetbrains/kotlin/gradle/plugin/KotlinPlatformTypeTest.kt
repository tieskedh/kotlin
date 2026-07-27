/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin

import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.attributes.MultipleCandidatesDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinPlatformTypeTest {
    @Test
    fun `dotnet has a distinct stable Gradle identity`() {
        assertEquals("dotnet", KotlinPlatformType.dotnet.name)
        assertEquals("dotnet", KotlinPlatformType.dotnet.toString())
        assertEquals("org.jetbrains.kotlin.platform.type", KotlinPlatformType.attribute.name)
    }

    @Test
    fun `dotnet does not gain compatibility with another platform`() {
        for (producer in KotlinPlatformType.entries - KotlinPlatformType.dotnet) {
            val details = TestCompatibilityDetails(
                consumerValue = KotlinPlatformType.dotnet,
                producerValue = producer,
            )

            KotlinPlatformType.CompatibilityRule().execute(details)

            assertFalse(details.compatible, "dotnet must not consume $producer as a platform artifact")
            assertFalse(details.incompatible, "Gradle's default mismatch must remain authoritative")
        }
    }

    @Test
    fun `common metadata fallback includes dotnet like every platform`() {
        val details = TestCompatibilityDetails(
            consumerValue = KotlinPlatformType.common,
            producerValue = KotlinPlatformType.dotnet,
        )

        KotlinPlatformType.CompatibilityRule().execute(details)

        assertTrue(details.compatible)
        assertFalse(details.incompatible)
    }

    @Test
    fun `exact dotnet variant wins disambiguation`() {
        val details = TestMultipleCandidatesDetails(
            consumerValue = KotlinPlatformType.dotnet,
            candidateValues = setOf(KotlinPlatformType.common, KotlinPlatformType.dotnet),
        )

        KotlinPlatformType.DisambiguationRule().execute(details)

        assertEquals(KotlinPlatformType.dotnet, details.closestMatch)
    }

    private class TestCompatibilityDetails(
        private val consumerValue: KotlinPlatformType,
        private val producerValue: KotlinPlatformType,
    ) : CompatibilityCheckDetails<KotlinPlatformType> {
        var compatible: Boolean = false
            private set

        var incompatible: Boolean = false
            private set

        override fun getConsumerValue(): KotlinPlatformType = consumerValue

        override fun getProducerValue(): KotlinPlatformType = producerValue

        override fun compatible() {
            compatible = true
        }

        override fun incompatible() {
            incompatible = true
        }
    }

    private class TestMultipleCandidatesDetails(
        private val consumerValue: KotlinPlatformType?,
        private val candidateValues: Set<KotlinPlatformType>,
    ) : MultipleCandidatesDetails<KotlinPlatformType> {
        var closestMatch: KotlinPlatformType? = null
            private set

        override fun getConsumerValue(): KotlinPlatformType? = consumerValue

        override fun getCandidateValues(): Set<KotlinPlatformType> = candidateValues

        override fun closestMatch(candidate: KotlinPlatformType) {
            closestMatch = candidate
        }
    }
}
