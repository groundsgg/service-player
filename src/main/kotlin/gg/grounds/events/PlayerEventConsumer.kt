package gg.grounds.events

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.persistence.PlayerNameRepository
import gg.grounds.persistence.PlayerSessionRepository
import io.micrometer.core.instrument.MeterRegistry
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * The core writer: applies the session and name writes regions queued on PLAYER_EVENTS.
 *
 * Every statement is idempotent and order-safe on its own (see [PlayerSessionRepository] and
 * [PlayerNameRepository]): a redelivery or a duplicate changes nothing, an older name sighting
 * never overwrites a newer one, a server change only lands on its own session and never behind a
 * newer one, and a logout deletes exactly its session. One message in flight (`max_ack_pending =
 * 1`) keeps them in stream order anyway, so the common case needs none of that.
 *
 * Unreadable messages are terminated; a database error redelivers after a delay and holds the line.
 */
@ApplicationScoped
class PlayerEventConsumer
@Inject
constructor(
    private val nats: NatsConnector,
    private val sessions: PlayerSessionRepository,
    private val names: PlayerNameRepository,
    private val json: ObjectMapper,
    private val registry: MeterRegistry,
    @param:ConfigProperty(name = "grounds.player.events.consumer.enabled")
    private val enabled: Boolean,
    @param:ConfigProperty(name = "grounds.player.events.consumer.stream")
    private val stream: String,
    @param:ConfigProperty(name = "grounds.player.events.consumer.durable")
    private val durable: String,
    @param:ConfigProperty(name = "grounds.player.events.consumer.batch-size")
    private val batchSize: Int,
    @param:ConfigProperty(name = "grounds.player.events.consumer.retry-delay")
    private val retryDelay: Duration,
) {

    enum class Outcome {
        /** The write changed a row. */
        APPLIED,
        /** Nothing to change: a duplicate, an older event, or a session that is gone. */
        NOOP,
        INVALID,
        RETRY,
    }

    @Volatile private var subscription: JetStreamSubscription? = null

    @Scheduled(
        every = "{grounds.player.events.consumer.poll-interval}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun poll() {
        if (!enabled) return
        drain()
    }

    /** Processes up to one batch; returns how many messages were handled. */
    fun drain(): Int {
        val sub = subscription() ?: return 0
        var handled = 0
        while (handled < batchSize) {
            val message =
                try {
                    sub.fetch(1, FETCH_TIMEOUT).firstOrNull()
                } catch (error: Exception) {
                    LOG.warnf(error, "Fetch failed on %s, resubscribing", stream)
                    subscription = null
                    return handled
                } ?: break
            process(message)
            handled++
        }
        if (handled == 0) verifyConsumer()
        return handled
    }

    /**
     * An idle poll checks the durable still exists. A pull from a consumer that was deleted (the
     * stream recreated, an operator's `nats consumer rm`) just returns nothing, so without this the
     * writer would sit idle forever while events pile up.
     */
    private fun verifyConsumer() {
        try {
            checkNotNull(nats.connection()).jetStreamManagement().getConsumerInfo(stream, durable)
        } catch (error: Exception) {
            LOG.warnf(
                "Consumer %s on %s is gone, resubscribing: %s",
                durable,
                stream,
                error.message,
            )
            subscription = null
        }
    }

    private fun process(message: Message) {
        val outcome =
            try {
                handle(message.subject, message.data)
            } catch (error: Exception) {
                LOG.warnf(
                    error,
                    "Applying a player event failed, will retry (subject=%s)",
                    message.subject,
                )
                Outcome.RETRY
            }
        when (outcome) {
            Outcome.APPLIED,
            Outcome.NOOP -> message.ack()
            Outcome.INVALID -> message.term()
            Outcome.RETRY -> message.nakWithDelay(retryDelay)
        }
        registry
            .counter(
                "player.events.consumer.messages",
                "event",
                eventName(message.subject),
                "outcome",
                outcome.name.lowercase(),
            )
            .increment()
    }

    /**
     * Applies one message. Throws on a transient failure (the caller redelivers); returns
     * [Outcome.INVALID] for anything a redelivery could not fix.
     */
    fun handle(subject: String, data: ByteArray): Outcome {
        val write =
            try {
                parse(subject, data)
            } catch (error: Exception) {
                LOG.errorf(
                    "Discarding an unreadable player event (subject=%s): %s",
                    subject,
                    error.message,
                )
                return Outcome.INVALID
            }
        if (write == null) {
            LOG.errorf("Discarding an event on an unknown subject (subject=%s)", subject)
            return Outcome.INVALID
        }
        return if (write()) Outcome.APPLIED else Outcome.NOOP
    }

    /** The statement one message stands for, or null for a subject this writer does not know. */
    private fun parse(subject: String, data: ByteArray): (() -> Boolean)? =
        when {
            subject.startsWith(PlayerEvents.NAME_SEEN_PREFIX) -> {
                val event = json.readValue(data, PlayerEvents.NameSeen::class.java)
                val playerId = UUID.fromString(event.playerId)
                val name = event.playerName.trim()
                require(name.isNotEmpty()) { "blank playerName" }
                val seenAt = Instant.parse(event.seenAt)
                ({ names.applyNameSeen(playerId, name, seenAt) })
            }
            subject.startsWith(PlayerEvents.SERVER_CHANGED_PREFIX) -> {
                val event = json.readValue(data, PlayerEvents.ServerChanged::class.java)
                val playerId = UUID.fromString(event.playerId)
                val sessionId = UUID.fromString(event.sessionId)
                val server = event.serverName.trim()
                require(server.isNotEmpty()) { "blank serverName" }
                val changedAt = Instant.parse(event.changedAt)
                ({ sessions.applyServerChanged(playerId, sessionId, server, changedAt) })
            }
            subject.startsWith(PlayerEvents.SESSION_ENDED_PREFIX) -> {
                val event = json.readValue(data, PlayerEvents.SessionEnded::class.java)
                val playerId = UUID.fromString(event.playerId)
                val sessionId = UUID.fromString(event.sessionId)
                ({ sessions.applySessionEnded(playerId, sessionId) })
            }
            else -> null
        }

    private fun eventName(subject: String): String =
        when {
            subject.startsWith(PlayerEvents.NAME_SEEN_PREFIX) -> "name_seen"
            subject.startsWith(PlayerEvents.SERVER_CHANGED_PREFIX) -> "server_changed"
            subject.startsWith(PlayerEvents.SESSION_ENDED_PREFIX) -> "session_ended"
            else -> "unknown"
        }

    private fun subscription(): JetStreamSubscription? {
        subscription?.let {
            return it
        }
        val connection = nats.connection() ?: return null
        return try {
            val options =
                PullSubscribeOptions.builder()
                    .stream(stream)
                    .durable(durable)
                    .configuration(
                        ConsumerConfiguration.builder()
                            .ackPolicy(AckPolicy.Explicit)
                            .ackWait(ACK_WAIT)
                            .maxAckPending(1)
                            .build()
                    )
                    .build()
            connection.jetStream().subscribe(PlayerEvents.CONSUMER_FILTER, options).also {
                subscription = it
                LOG.infof(
                    "Consuming %s on %s (durable=%s)",
                    PlayerEvents.CONSUMER_FILTER,
                    stream,
                    durable,
                )
            }
        } catch (error: Exception) {
            // Most likely the stream or this service's NATS user is not provisioned yet. The next
            // poll tries again.
            LOG.warnf(error, "Cannot consume %s on %s", PlayerEvents.CONSUMER_FILTER, stream)
            null
        }
    }

    private companion object {
        val FETCH_TIMEOUT: Duration = Duration.ofMillis(500)
        val ACK_WAIT: Duration = Duration.ofSeconds(30)
        val LOG: Logger = Logger.getLogger(PlayerEventConsumer::class.java)
    }
}
