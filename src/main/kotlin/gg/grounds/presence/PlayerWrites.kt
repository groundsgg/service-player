package gg.grounds.presence

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.events.JetStreamPublisher
import gg.grounds.events.PlayerEvents
import gg.grounds.persistence.PlayerNameRepository
import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.persistence.PlayerSessionRepository.DeleteSessionResult
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * The regional side of the writes nobody waits on: a player's name sighting, a server change and a
 * logout.
 *
 * With `grounds.player.async-writes` on, each is published to the region's JetStream buffer
 * (PLAYER_OUTBOX, sourced into the hub's PLAYER_EVENTS) and applied by the core writer; the caller
 * gets its answer as soon as the buffer acknowledged it. Logouts follow only with
 * `grounds.player.async-logout` as well, because they also need the [EndedSessionFence] in the
 * claim.
 *
 * Anything the buffer does not acknowledge is applied synchronously right here, with the writer's
 * own statement, so a missing stream, a broker outage or a full buffer costs latency, never the
 * write. If the unacknowledged publish did land after all, the writer's statements are idempotent.
 */
@ApplicationScoped
class PlayerWrites
@Inject
constructor(
    private val publisher: JetStreamPublisher,
    private val json: ObjectMapper,
    private val registry: MeterRegistry,
    private val sessions: PlayerSessionRepository,
    private val names: PlayerNameRepository,
    private val fence: EndedSessionFence,
    @param:ConfigProperty(name = "grounds.player.async-writes") val asyncWrites: Boolean,
    @param:ConfigProperty(name = "grounds.player.async-logout") asyncLogout: Boolean,
) {
    /** Logouts are queued only together with the other writes; alone the flag means nothing. */
    val asyncLogout: Boolean = asyncWrites && asyncLogout

    /** Sessions the claim may take over because their logout is queued. */
    fun endedSessions(playerId: UUID): List<UUID> =
        if (asyncLogout) fence.endedSessions(playerId) else emptyList()

    fun nameSeen(playerId: UUID, sessionId: UUID, playerName: String, seenAt: Instant) {
        val at = PlayerEvents.stamp(seenAt)
        val queued =
            queue(
                EVENT_NAME_SEEN,
                PlayerEvents.nameSeenSubject(playerId),
                PlayerEvents.nameSeenId(sessionId),
                PlayerEvents.NameSeen(playerId.toString(), playerName, at.toString()),
            )
        if (queued) return
        try {
            names.applyNameSeen(playerId, playerName, at)
        } catch (error: Exception) {
            LOG.errorf(
                error,
                "Player name index upsert failed (playerId=%s, reason=sql_error)",
                playerId,
            )
        }
    }

    /** True once the change is queued or applied; false when the session is not this one's. */
    fun serverChanged(
        playerId: UUID,
        sessionId: UUID,
        serverName: String,
        changedAt: Instant,
    ): Boolean {
        val at = PlayerEvents.stamp(changedAt)
        val queued =
            queue(
                EVENT_SERVER_CHANGED,
                PlayerEvents.serverChangedSubject(playerId),
                PlayerEvents.serverChangedId(sessionId, at),
                PlayerEvents.ServerChanged(
                    playerId.toString(),
                    sessionId.toString(),
                    serverName,
                    at.toString(),
                ),
            )
        if (queued) return true
        return try {
            sessions.applyServerChanged(playerId, sessionId, serverName, at)
        } catch (error: Exception) {
            LOG.errorf(
                error,
                "Player session server update failed (playerId=%s, reason=sql_error)",
                playerId,
            )
            false
        }
    }

    /**
     * REMOVED once the logout is queued (the caller only logs the answer); otherwise what the
     * synchronous delete found.
     */
    fun sessionEnded(playerId: UUID, sessionId: UUID, endedAt: Instant): DeleteSessionResult {
        val queued =
            queue(
                EVENT_SESSION_ENDED,
                PlayerEvents.sessionEndedSubject(playerId),
                PlayerEvents.sessionEndedId(sessionId),
                PlayerEvents.SessionEnded(
                    playerId.toString(),
                    sessionId.toString(),
                    PlayerEvents.stamp(endedAt).toString(),
                ),
            )
        if (queued) {
            fence.add(playerId, sessionId)
            return DeleteSessionResult.REMOVED
        }
        return sessions.deleteSessionForSession(playerId, sessionId)
    }

    private fun queue(event: String, subject: String, messageId: String, body: Any): Boolean {
        val outcome = publisher.publish(subject, messageId, json.writeValueAsBytes(body))
        val path =
            when (outcome) {
                JetStreamPublisher.Outcome.STORED -> "queued"
                JetStreamPublisher.Outcome.DUPLICATE -> "duplicate"
                JetStreamPublisher.Outcome.FAILED -> "sync_fallback"
            }
        registry.counter("player.writes", "event", event, "path", path).increment()
        return outcome != JetStreamPublisher.Outcome.FAILED
    }

    private companion object {
        const val EVENT_NAME_SEEN = "name_seen"
        const val EVENT_SERVER_CHANGED = "server_changed"
        const val EVENT_SESSION_ENDED = "session_ended"
        val LOG: Logger = Logger.getLogger(PlayerWrites::class.java)
    }
}
