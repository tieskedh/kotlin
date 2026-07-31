package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.load.dotnet.DotNetClrConstructedTypeNominalConstraintValidation
import org.jetbrains.kotlin.load.dotnet.DotNetClrNominalConstraintSatisfaction
import org.jetbrains.kotlin.load.dotnet.DotNetClrNominalConstraintValidation
import org.jetbrains.kotlin.load.dotnet.DotNetClrNominalConstraintValidator
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedConstructedTypeConstraints
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericParameterContext

data class DotNetClrNominalConstraintIssue(
    val parameterIndex: Int,
    val constraintIndex: Int,
    val validation: DotNetClrNominalConstraintValidation,
)

data class DotNetClrSpecialConstraintIssue(
    val parameterIndex: Int,
    val constraintIndex: Int,
    val validation: DotNetClrSpecialConstraintValidation,
)

sealed interface DotNetClrConstructedTypeConstraintStatus {
    data object Satisfied : DotNetClrConstructedTypeConstraintStatus

    data class Violated(
        val nominal: List<DotNetClrNominalConstraintIssue>,
        val special: List<DotNetClrSpecialConstraintIssue>,
    ) : DotNetClrConstructedTypeConstraintStatus

    data class Unsupported(
        val nominal: List<DotNetClrNominalConstraintIssue>,
        val special: List<DotNetClrSpecialConstraintIssue>,
    ) : DotNetClrConstructedTypeConstraintStatus

    data class Invalid(
        val nominal: List<DotNetClrNominalConstraintIssue>,
        val special: List<DotNetClrSpecialConstraintIssue>,
    ) : DotNetClrConstructedTypeConstraintStatus
}

data class DotNetClrConstructedTypeConstraintValidation(
    val constraints: DotNetClrResolvedConstructedTypeConstraints,
    val nominal: DotNetClrConstructedTypeNominalConstraintValidation,
    val special: DotNetClrConstructedTypeSpecialConstraintValidation,
    val status: DotNetClrConstructedTypeConstraintStatus,
)

/**
 * Combines the independent nominal and special constraint validators without hiding partial
 * support behind a Boolean. Invalid selected metadata wins over unsupported semantics, which wins
 * over a proven violation; only wholly supported and satisfied rows produce
 * [DotNetClrConstructedTypeConstraintStatus.Satisfied].
 */
class DotNetClrConstructedTypeConstraintValidator(
    private val nominalValidator: DotNetClrNominalConstraintValidator,
    private val specialValidator: DotNetClrSpecialConstraintValidator,
) {
    fun validate(
        constraints: DotNetClrResolvedConstructedTypeConstraints,
        genericParameterContext: DotNetClrResolvedGenericParameterContext? = null,
    ): DotNetClrConstructedTypeConstraintValidation {
        val nominal =
            nominalValidator.validate(constraints, genericParameterContext)
        val special =
            specialValidator.validate(constraints, genericParameterContext)
        return DotNetClrConstructedTypeConstraintValidation(
            constraints,
            nominal,
            special,
            classify(nominal, special),
        )
    }

    private fun classify(
        nominal: DotNetClrConstructedTypeNominalConstraintValidation,
        special: DotNetClrConstructedTypeSpecialConstraintValidation,
    ): DotNetClrConstructedTypeConstraintStatus {
        val invalidNominal = mutableListOf<DotNetClrNominalConstraintIssue>()
        val unsupportedNominal = mutableListOf<DotNetClrNominalConstraintIssue>()
        val violatedNominal = mutableListOf<DotNetClrNominalConstraintIssue>()
        nominal.parameters.forEachIndexed { parameterIndex, parameter ->
            parameter.constraints.forEachIndexed { constraintIndex, validation ->
                val issue = DotNetClrNominalConstraintIssue(
                    parameterIndex,
                    constraintIndex,
                    validation,
                )
                when (validation.satisfaction) {
                    DotNetClrNominalConstraintSatisfaction.Satisfied -> Unit
                    DotNetClrNominalConstraintSatisfaction.Violated ->
                        violatedNominal += issue

                    is DotNetClrNominalConstraintSatisfaction.Unsupported ->
                        unsupportedNominal += issue

                    is DotNetClrNominalConstraintSatisfaction.InvalidAssignability ->
                        invalidNominal += issue
                }
            }
        }

        val invalidSpecial = mutableListOf<DotNetClrSpecialConstraintIssue>()
        val unsupportedSpecial = mutableListOf<DotNetClrSpecialConstraintIssue>()
        val violatedSpecial = mutableListOf<DotNetClrSpecialConstraintIssue>()
        special.parameters.forEachIndexed { parameterIndex, parameter ->
            parameter.constraints.forEachIndexed { constraintIndex, validation ->
                val issue = DotNetClrSpecialConstraintIssue(
                    parameterIndex,
                    constraintIndex,
                    validation,
                )
                when (validation.satisfaction) {
                    DotNetClrSpecialConstraintSatisfaction.Satisfied -> Unit
                    is DotNetClrSpecialConstraintSatisfaction.Violated ->
                        violatedSpecial += issue

                    is DotNetClrSpecialConstraintSatisfaction.Unsupported ->
                        unsupportedSpecial += issue

                    is DotNetClrSpecialConstraintSatisfaction.InvalidClassification ->
                        invalidSpecial += issue
                }
            }
        }

        if (invalidNominal.isNotEmpty() || invalidSpecial.isNotEmpty()) {
            return DotNetClrConstructedTypeConstraintStatus.Invalid(
                invalidNominal,
                invalidSpecial,
            )
        }

        if (unsupportedNominal.isNotEmpty() || unsupportedSpecial.isNotEmpty()) {
            return DotNetClrConstructedTypeConstraintStatus.Unsupported(
                unsupportedNominal,
                unsupportedSpecial,
            )
        }

        if (violatedNominal.isNotEmpty() || violatedSpecial.isNotEmpty()) {
            return DotNetClrConstructedTypeConstraintStatus.Violated(
                violatedNominal,
                violatedSpecial,
            )
        }

        return DotNetClrConstructedTypeConstraintStatus.Satisfied
    }
}
