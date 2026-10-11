package com.ubers.observador

import com.ubers.observador.domain.TimeAgo
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeAgoTest {

    private val now = 1_800_000_000_000L
    private fun minutesAgo(m: Long) = TimeAgo.format(now, now - m * 60_000L)

    @Test
    fun `under a minute reads as less than 1 min`() {
        assertEquals("hace menos de 1 min", TimeAgo.format(now, now - 59_000L))
        assertEquals("hace menos de 1 min", TimeAgo.format(now, now))
    }

    @Test
    fun `minutes, hours and days`() {
        assertEquals("hace 1 min", minutesAgo(1))
        assertEquals("hace 59 min", minutesAgo(59))
        assertEquals("hace 1 h", minutesAgo(60))
        assertEquals("hace 23 h", minutesAgo(24 * 60 - 1))
        assertEquals("hace 1 día", minutesAgo(24 * 60))
        assertEquals("hace 3 días", minutesAgo(3 * 24 * 60 + 5))
    }

    @Test
    fun `a time in the future never prints a negative value`() {
        assertEquals("hace menos de 1 min", TimeAgo.format(now, now + 5 * 60_000L))
    }
}
