package gg.grounds.persistence

import gg.grounds.persistence.PlayerSessionRepository.Companion.escapeLikePattern
import gg.grounds.persistence.PlayerSessionRepository.Companion.prefixUpperBound
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlayerSessionRepositoryTest {
    @Test
    fun `escapeLikePattern leaves plain text untouched`() {
        assertEquals("hendrik", escapeLikePattern("hendrik"))
    }

    @Test
    fun `escapeLikePattern escapes percent so it is not a wildcard`() {
        assertEquals("100\\%", escapeLikePattern("100%"))
    }

    @Test
    fun `escapeLikePattern escapes underscore so it is not a single-char wildcard`() {
        assertEquals("foo\\_bar", escapeLikePattern("foo_bar"))
    }

    @Test
    fun `escapeLikePattern escapes backslash before escaping metacharacters`() {
        assertEquals("foo\\\\\\%bar", escapeLikePattern("foo\\%bar"))
    }

    @Test
    fun `escapeLikePattern handles a bare percent prefix without matching everything`() {
        assertEquals("\\%", escapeLikePattern("%"))
    }

    @Test
    fun `prefixUpperBound bounds the range that the index scans`() {
        // "dah" must match dahendriik but stop before anything starting with "dai".
        assertEquals("dai", prefixUpperBound("dah"))
        assertEquals("b", prefixUpperBound("a"))
        assertEquals("az", prefixUpperBound("ay"))
    }

    @Test
    fun `prefixUpperBound carries over when the last character is the highest one`() {
        val maxed = "a" + Char.MAX_VALUE
        assertEquals("b", prefixUpperBound(maxed))
    }

    @Test
    fun `prefixUpperBound is null when no bound exists`() {
        // Nothing to narrow the scan with — the caller must not run the query.
        assertNull(prefixUpperBound(""))
        assertNull(prefixUpperBound(Char.MAX_VALUE.toString()))
    }
}
