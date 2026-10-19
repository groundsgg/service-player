package gg.grounds.presence

import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sessions this runtime has queued a logout for, until the writer has surely applied it.
 *
 * A queued logout leaves the session row in place for a moment. A player who reconnects in that
 * moment must be admitted, as the synchronous DELETE would have allowed: the claim treats a row
 * held by one of these sessions like a stale one. Exact player + session, so nothing else is
 * affected; kept for [RETENTION], longer than the session TTL, after which the row is stale anyway.
 *
 * In-process on purpose. With more than one replica a reconnect that lands on another replica waits
 * for the writer (well under a second) — the same answer the proxy would get for a fresh session.
 */
@ApplicationScoped
class EndedSessionFence(private val nanoTime: () -> Long) {
    constructor() : this(System::nanoTime)

    private val ended = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Long>>()

    fun add(playerId: UUID, sessionId: UUID) {
        ended.computeIfAbsent(playerId) { ConcurrentHashMap() }[sessionId] =
            nanoTime() + RETENTION.toNanos()
        if (ended.size > SWEEP_THRESHOLD) sweep()
    }

    /** The player's sessions with a queued logout that may not be applied yet. */
    fun endedSessions(playerId: UUID): List<UUID> {
        val sessions = ended[playerId] ?: return emptyList()
        val now = nanoTime()
        sessions.entries.removeIf { now - it.value > 0 }
        if (sessions.isEmpty()) ended.remove(playerId, sessions)
        return sessions.keys.toList()
    }

    fun sweep() {
        val now = nanoTime()
        ended.entries.removeIf { (_, sessions) ->
            sessions.entries.removeIf { now - it.value > 0 }
            sessions.isEmpty()
        }
    }

    companion object {
        val RETENTION: Duration = Duration.ofSeconds(120)
        private const val SWEEP_THRESHOLD = 10_000
    }
}
