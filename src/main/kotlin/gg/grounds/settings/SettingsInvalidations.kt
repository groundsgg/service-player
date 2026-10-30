package gg.grounds.settings

import gg.grounds.PlayerRole
import gg.grounds.events.NatsConnector
import io.nats.client.Dispatcher
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID
import org.jboss.logging.Logger

/**
 * Carries settings writes to every runtime: `player.settings.changed.<playerId>`, plain NATS (not
 * stored — a runtime that misses one serves the old value until the TTL, which is the bound the
 * cache accepts anyway).
 *
 * The subscription is (re)established by a regional tick rather than at startup, so a broker that
 * is not reachable when the pod starts only delays it.
 */
@ApplicationScoped
class SettingsInvalidations
@Inject
constructor(
    private val nats: NatsConnector,
    private val cache: PlayerSettingsCache,
) {
    @Volatile private var dispatcher: Dispatcher? = null

    fun publish(playerId: UUID, force: Boolean = false) {
        if (!force && !cache.enabled) return
        try {
            nats.connection()?.publish(SUBJECT_PREFIX + playerId, ByteArray(0))
        } catch (error: Exception) {
            LOG.warnf(error, "Settings invalidation publish failed (playerId=%s)", playerId)
        }
    }

    @Scheduled(
        every = "10s",
        delay = 1,
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        skipExecutionIf = PlayerRole.RegionalOnly::class,
    )
    fun ensureSubscribed() {
        if (!cache.enabled || dispatcher != null) return
        val connection = nats.connection() ?: return
        dispatcher =
            connection
                .createDispatcher { message -> receive(message.subject) }
                .also { it.subscribe(SUBJECT_PREFIX + "*") }
        LOG.infof("Subscribed to settings invalidations (subject=%s*)", SUBJECT_PREFIX)
    }

    internal fun receive(subject: String) {
        val playerId =
            runCatching { UUID.fromString(subject.removePrefix(SUBJECT_PREFIX)) }.getOrNull()
                ?: return
        cache.invalidate(playerId)
    }

    companion object {
        const val SUBJECT_PREFIX = "player.settings.changed."
        private val LOG: Logger = Logger.getLogger(SettingsInvalidations::class.java)
    }
}
