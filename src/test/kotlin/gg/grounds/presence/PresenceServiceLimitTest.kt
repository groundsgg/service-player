package gg.grounds.presence

import gg.grounds.presence.PresenceService.Companion.cappedSuggestLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PresenceServiceLimitTest {
    @Test
    fun `cappedSuggestLimit defaults to 25 when limit is zero`() {
        assertEquals(25, cappedSuggestLimit(0))
    }

    @Test
    fun `cappedSuggestLimit defaults to 25 when limit is negative`() {
        assertEquals(25, cappedSuggestLimit(-1))
    }

    @Test
    fun `cappedSuggestLimit passes through values within the cap`() {
        assertEquals(10, cappedSuggestLimit(10))
    }

    @Test
    fun `cappedSuggestLimit clamps values above the cap`() {
        assertEquals(25, cappedSuggestLimit(1000))
    }

    @Test
    fun `cappedSuggestLimit allows exactly the cap`() {
        assertEquals(25, cappedSuggestLimit(25))
    }
}
