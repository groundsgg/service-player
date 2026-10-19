package gg.grounds.presence

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EndedSessionFenceTest {
    private var now = 0L
    private val fence = EndedSessionFence { now }

    @Test
    fun `a queued logout is remembered per player until it surely applied`() {
        val playerId = UUID.randomUUID()
        val session = UUID.randomUUID()
        fence.add(playerId, session)

        assertEquals(listOf(session), fence.endedSessions(playerId))
        assertEquals(emptyList<UUID>(), fence.endedSessions(UUID.randomUUID()))

        now += EndedSessionFence.RETENTION.toNanos() + 1
        assertEquals(emptyList<UUID>(), fence.endedSessions(playerId))
    }

    @Test
    fun `a sweep forgets only what expired`() {
        val old = UUID.randomUUID()
        val recent = UUID.randomUUID()
        fence.add(old, UUID.randomUUID())
        now += EndedSessionFence.RETENTION.toNanos() / 2
        val session = UUID.randomUUID()
        fence.add(recent, session)
        now += EndedSessionFence.RETENTION.toNanos() / 2 + 1

        fence.sweep()
        assertEquals(emptyList<UUID>(), fence.endedSessions(old))
        assertEquals(listOf(session), fence.endedSessions(recent))
    }
}
