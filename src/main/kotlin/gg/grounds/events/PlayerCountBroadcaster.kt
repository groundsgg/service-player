package gg.grounds.events

import gg.grounds.persistence.PlayerSessionRepository
import io.nats.client.Connection
import io.nats.client.ConnectionListener
import io.nats.client.Nats
import io.nats.client.Options
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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
    @param:ConfigProperty(name = "nats.url") private val natsUrl: String,
    @param:ConfigProperty(name = "nats.max-reconnects") private val maxReconnects: Int,
    @param:ConfigProperty(name = "nats.reconnect-wait-seconds")
    private val reconnectWaitSeconds: Long,
    @param:ConfigProperty(name = "grounds.token-file") private val groundsTokenFile: String,
) {

    @Volatile private var connection: Connection? = null

    fun onStart(@Observes event: StartupEvent) = connect()

    fun onStop(@Observes event: ShutdownEvent) = close()

    @Synchronized
    fun connect() {
        try {
            val builder =
                Options.Builder()
                    .server(natsUrl)
                    .connectionName("service-player")
                    .maxReconnects(maxReconnects)
                    .reconnectWait(Duration.ofSeconds(reconnectWaitSeconds))
                    .connectionListener(
                        ConnectionListener { _, type ->
                            LOG.infof("NATS connection event (event=%s)", type)
                        }
                    )
            // The projected SA token as the NATS bearer, re-read per (re)connect so kubelet
            // rotation is picked up. Absent file means no token, which is right for local runs.
            val tokenPath = Path.of(groundsTokenFile)
            if (Files.exists(tokenPath)) {
                builder.tokenSupplier { Files.readString(tokenPath).trim().toCharArray() }
            }
            connection = Nats.connect(builder.build())
            LOG.infof("Connected to NATS for player-count broadcast (url=%s)", natsUrl)
        } catch (error: Exception) {
            // Not fatal. Everything else this service does works without NATS; the proxies simply
            // keep their previous number until a connection comes back.
            LOG.errorf(error, "Failed to connect to NATS (url=%s)", natsUrl)
            connection = null
        }
    }

    @Scheduled(
        every = "{grounds.player.counts.broadcast-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun broadcast() {
        val conn = connection
        if (conn == null || conn.status != Connection.Status.CONNECTED) return

        val total =
            when (val result = repository.countPlayersByProxy()) {
                is PlayerSessionRepository.CountPlayersByProxyResult.Counted -> result.total
                // Publishing a zero here would empty every server list on one failed query. Say
                // nothing instead and let the consumers hold the last value they had.
                PlayerSessionRepository.CountPlayersByProxyResult.Error -> return
            }

        try {
            conn.publish(SUBJECT, """{"total":$total}""".toByteArray(Charsets.UTF_8))
        } catch (error: Exception) {
            LOG.warnf(error, "Player-count broadcast failed (subject=%s)", SUBJECT)
        }
    }

    @Synchronized
    fun close() {
        try {
            connection?.close()
        } catch (error: Exception) {
            LOG.warnf(error, "Failed to close the NATS connection")
        } finally {
            connection = null
        }
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
