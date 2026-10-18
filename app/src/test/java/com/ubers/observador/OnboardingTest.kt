package com.ubers.observador

import com.ubers.observador.domain.Onboarding
import com.ubers.observador.domain.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.29.0: the welcome shows once, never to a driver updating with reading on, and again on demand. */
class OnboardingTest {

    @Test fun whoSeesTheWelcome() {
        assertTrue(Onboarding.shouldShow(done = null, accessibilityOn = false)) // new install
        assertFalse(Onboarding.shouldShow(done = null, accessibilityOn = true)) // updating driver
        assertFalse(Onboarding.shouldShow(done = true, accessibilityOn = false)) // skipped once: never again
        assertTrue(Onboarding.shouldShow(done = false, accessibilityOn = true)) // «Ver bienvenida»
    }

    @Test fun notificationsAreOnlyAskedFromAndroid13() {
        val old = Onboarding.steps(30)
        val new = Onboarding.steps(33)
        assertFalse(OnboardingStep.NOTIFICATIONS in old)
        assertEquals(OnboardingStep.entries.toList(), new)
        assertEquals(OnboardingStep.WELCOME, old.first())
        assertEquals(OnboardingStep.READY, old.last())
        assertEquals(OnboardingStep.READY, Onboarding.next(OnboardingStep.LOCATION, old))
        assertEquals(OnboardingStep.NOTIFICATIONS, Onboarding.next(OnboardingStep.LOCATION, new))
        assertNull(Onboarding.next(OnboardingStep.READY, new))
        assertNull(Onboarding.previous(OnboardingStep.WELCOME, new))
        assertEquals(OnboardingStep.LOCATION, Onboarding.previous(OnboardingStep.READY, old))
    }

    @Test fun theLastPageCountsOnlyWhatWasAsked() {
        assertEquals(2 to 2, Onboarding.granted(true, true, notifications = false, steps = Onboarding.steps(30)))
        assertEquals(2 to 3, Onboarding.granted(true, true, notifications = false, steps = Onboarding.steps(34)))
        assertEquals(0 to 3, Onboarding.granted(false, false, false, Onboarding.steps(36)))
    }
}
