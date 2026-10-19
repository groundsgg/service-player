package gg.grounds.events

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.PlayerRole
import gg.grounds.persistence.PlayerSessionRepository
import io.nats.client.Connection
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Publishes how many players are online, network-wide, on a fixed interval.
 *
 * The server-list ping needs this number, and a ping is answered on every client that opens its
 * server list — far too often to ask a database. So the value is pushed to whoever is listening and
 * read from memory when a ping arrives. A proxy showing a number a few seconds stale is fine; a
 * proxy showing *its own* player count as if it were the network's is not, and that is what happens
 * without this.
 *
 * With `grounds.player.counts.broadcast-servers` on, the same message also carries the players per
 * backend server (`{"total":N,"servers":{"lobby-1":n,..}}`), so the proxies can answer a lobby's
 * per-server counts from memory instead of calling `GET /v1/players/counts/servers`. One GROUPING
 * SETS query per tick serves both; `total` stays the first key, which is what older proxy plugins
 * read.
 *
 * Fan-out over NATS rather than a gRPC stream: one publish reaches every proxy in every region
 * regardless of how many there are, and the NATS client already handles reconnects. A stream would
 * mean this service tracking each subscriber.
 *
 * **Best-effort, and deliberately so.** Nothing is retried and nothing is durable: the next tick is
 * a few seconds away and carries a fresher number than any redelivery would. A consumer that misses
 * one keeps showing the previous value, which is exactly what a missed tick should look like.
 */
@ApplicationScoped
class PlayerCountBroadcaster
@Inject
constructor(
    private val repository: PlayerSessionRepository,
    private val nats: NatsConnector,
    private val role: PlayerRole,
    private val json: ObjectMapper,
    @param:ConfigProperty(name = "grounds.player.counts.broadcast-servers")
    private val broadcastServers: Boolean,
) {

    /** Connects at startup, as before, so the first tick already has a connection. */
    fun onStart(@Observes event: StartupEvent) {
        if (!role.writer) nats.connection()
    }

    @Scheduled(
        every = "{grounds.player.counts.broadcast-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        skipExecutionIf = PlayerRole.RegionalOnly::class,
    )
    fun broadcast() {
        val conn = nats.connection()
        if (conn == null || conn.status != Connection.Status.CONNECTED) return

        // Publishing a zero on a failed query would empty every server list. Say nothing instead
        // and let the consumers hold the last value they had.
        val payload = payload() ?: return

        try {
            conn.publish(SUBJECT, payload)
        } catch (error: Exception) {
            LOG.warnf(error, "Player-count broadcast failed (subject=%s)", SUBJECT)
        }
    }

    internal fun payload(): ByteArray? {
        if (!broadcastServers) {
            val total =
                when (val result = repository.countPlayersByProxy()) {
                    is PlayerSessionRepository.CountPlayersByProxyResult.Counted -> result.total
                    PlayerSessionRepository.CountPlayersByProxyResult.Error -> return null
                }
            return """{"total":$total}""".toByteArray(Charsets.UTF_8)
        }
        val counted =
            when (val result = repository.countPlayersByServer()) {
                is PlayerSessionRepository.CountPlayersByServerResult.Counted -> result
                PlayerSessionRepository.CountPlayersByServerResult.Error -> return null
            }
        val message = LinkedHashMap<String, Any>()
        message["total"] = counted.total
        message["servers"] = counted.servers.associate { it.serverName to it.players }
        return json.writeValueAsBytes(message)
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerCountBroadcaster::class.java)

        /**
         * Not per environment or per region: every proxy on this NATS wants the same number, and
         * the leaf topology already scopes who can hear it.
         */
        const val SUBJECT = "proxy.player-counts"
    }
}
