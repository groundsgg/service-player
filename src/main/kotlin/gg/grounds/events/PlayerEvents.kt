package gg.grounds.events

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The writes a regional runtime queues for the core writer instead of applying them itself.
 *
 * Wire contract (PLAYER_OUTBOX in the region, sourced into PLAYER_EVENTS on the hub):
 *
 * | subject                                    | Nats-Msg-Id                                     |
 * |--------------------------------------------|-------------------------------------------------|
 * | `player.name.seen.<playerId>`              | `name-seen:<sessionId>`                         |
 * | `player.session.server-changed.<playerId>` | `server-changed:<sessionId>:<changedAt micros>` |
 * | `player.session.ended.<playerId>`          | `session-ended:<sessionId>`                     |
 *
 * Timestamps are ISO-8601 UTC truncated to microseconds — Postgres' precision, so the writer's
 * "never older than what is stored" comparisons see exactly the value the region stamped.
 */
object PlayerEvents {
    const val NAME_SEEN_PREFIX = "player.name.seen."
    const val SERVER_CHANGED_PREFIX = "player.session.server-changed."
    const val SESSION_ENDED_PREFIX = "player.session.ended."

    /** What the writer's durable consumes; PLAYER_EVENTS holds nothing else. */
    const val CONSUMER_FILTER = "player.>"

    data class NameSeen(val playerId: String, val playerName: String, val seenAt: String)

    data class ServerChanged(
        val playerId: String,
        val sessionId: String,
        val serverName: String,
        val changedAt: String,
    )

    data class SessionEnded(val playerId: String, val sessionId: String, val endedAt: String)

    fun stamp(at: Instant): Instant = at.truncatedTo(ChronoUnit.MICROS)

    fun epochMicros(at: Instant): Long =
        Math.addExact(Math.multiplyExact(at.epochSecond, 1_000_000L), at.nano / 1_000L)

    fun nameSeenSubject(playerId: UUID) = NAME_SEEN_PREFIX + playerId

    fun nameSeenId(sessionId: UUID) = "name-seen:$sessionId"

    fun serverChangedSubject(playerId: UUID) = SERVER_CHANGED_PREFIX + playerId

    fun serverChangedId(sessionId: UUID, changedAt: Instant) =
        "server-changed:$sessionId:${epochMicros(changedAt)}"

    fun sessionEndedSubject(playerId: UUID) = SESSION_ENDED_PREFIX + playerId

    fun sessionEndedId(sessionId: UUID) = "session-ended:$sessionId"
}
