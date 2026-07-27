/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.attributes

import org.gradle.api.Named
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.AttributeDisambiguationRule
import org.gradle.api.attributes.AttributesSchema
import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.attributes.MultipleCandidatesDetails
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import java.io.Serializable

/**
 * A supported .NET target-framework/API profile.
 *
 * This is a secondary attribute below
 * [org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.dotnet]. It selects the available BCL
 * surface and emitted CLR metadata without changing Kotlin language semantics.
 *
 * @since 2.5.0
 */
@ExperimentalKotlinGradlePluginApi
enum class KotlinDotNetTargetFramework(
    /**
     * The canonical .NET target framework moniker.
     */
    val targetFrameworkMoniker: String,
) : Named, Serializable {
    NET48("net48"),
    NETSTANDARD_2_0("netstandard2.0"),
    NET10_0("net10.0");

    /**
     * Returns the canonical .NET target framework moniker.
     */
    override fun getName(): String = targetFrameworkMoniker

    /**
     * Returns the canonical .NET target framework moniker.
     */
    override fun toString(): String = targetFrameworkMoniker

    /**
     * Allows runtime profiles to consume the portable `netstandard2.0` API floor.
     */
    class CompatibilityRule : AttributeCompatibilityRule<KotlinDotNetTargetFramework> {
        override fun execute(details: CompatibilityCheckDetails<KotlinDotNetTargetFramework>) = with(details) {
            if (
                producerValue == NETSTANDARD_2_0 &&
                (consumerValue == NET48 || consumerValue == NET10_0)
            ) {
                compatible()
            }
        }
    }

    /**
     * Prefers an exact profile and otherwise selects the portable `netstandard2.0` fallback.
     *
     * A consumer that does not declare a target framework is deliberately left unresolved.
     */
    class DisambiguationRule : AttributeDisambiguationRule<KotlinDotNetTargetFramework> {
        override fun execute(details: MultipleCandidatesDetails<KotlinDotNetTargetFramework>) = with(details) {
            val requested = consumerValue ?: return@with
            if (requested in candidateValues) {
                closestMatch(requested)
                return@with
            }
            if (requested != NETSTANDARD_2_0 && NETSTANDARD_2_0 in candidateValues) {
                closestMatch(NETSTANDARD_2_0)
            }
        }
    }

    companion object {
        /**
         * The Gradle attribute carrying the .NET target framework moniker.
         */
        @JvmField
        val ATTRIBUTE: Attribute<KotlinDotNetTargetFramework> = Attribute.of(
            "org.jetbrains.kotlin.dotnet.targetFramework",
            KotlinDotNetTargetFramework::class.java,
        )

        /**
         * Registers the target-framework compatibility and disambiguation rules.
         */
        fun setupAttributesMatchingStrategy(attributesSchema: AttributesSchema) {
            attributesSchema.attribute(ATTRIBUTE) { strategy ->
                strategy.compatibilityRules.add(CompatibilityRule::class.java)
                strategy.disambiguationRules.add(DisambiguationRule::class.java)
            }
        }
    }
}
