package com.ubers.observador.domain

/**
 * The first-launch welcome (1.29.0). Pure, so who sees it and the order of the steps are tested
 * on the JVM. Every step only explains and opens Android's own screen: nothing is granted by the
 * app, and «Saltar» always works.
 */
enum class OnboardingStep { WELCOME, ACCESSIBILITY, LOCATION, NOTIFICATIONS, READY }

object Onboarding {

    /**
     * Whether to show the welcome. [done] is null when it was never decided: a driver who already
     * has reading turned on is updating from an older version and skips it. False means «Ver
     * bienvenida» asked for it again, so it shows whatever the permissions are.
     */
    fun shouldShow(done: Boolean?, accessibilityOn: Boolean): Boolean = when (done) {
        true -> false
        false -> true
        null -> !accessibilityOn
    }

    /** The steps for this phone: notifications are only asked for from Android 13 (API 33). */
    fun steps(sdkInt: Int): List<OnboardingStep> =
        OnboardingStep.entries.filter { it != OnboardingStep.NOTIFICATIONS || sdkInt >= 33 }

    fun next(step: OnboardingStep, steps: List<OnboardingStep>): OnboardingStep? =
        steps.getOrNull(steps.indexOf(step) + 1)

    fun previous(step: OnboardingStep, steps: List<OnboardingStep>): OnboardingStep? =
        steps.indexOf(step).takeIf { it > 0 }?.let { steps[it - 1] }

    /** How many of the permission steps are already granted, for the last page. */
    fun granted(accessibility: Boolean, location: Boolean, notifications: Boolean, steps: List<OnboardingStep>): Pair<Int, Int> {
        val asked = steps.filter { it == OnboardingStep.ACCESSIBILITY || it == OnboardingStep.LOCATION || it == OnboardingStep.NOTIFICATIONS }
        val ok = asked.count {
            when (it) {
                OnboardingStep.ACCESSIBILITY -> accessibility
                OnboardingStep.LOCATION -> location
                else -> notifications
            }
        }
        return ok to asked.size
    }
}
