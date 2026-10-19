package gg.grounds.settings

import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands
import io.quarkus.redis.datasource.value.ReactiveValueCommands
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import java.time.Duration
import java.util.Optional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * The regional second level of the settings cache, shared by the runtimes of one region
 * (`valkey-cache`, never the matchmaking Valkey). Best effort: every failure reads as a miss and
 * never fails the request.
 */
interface SettingsL2 {
    sealed interface Read {
        /** A cached answer; null is a cached "none set". */
        data class Hit(val value: String?) : Read

        data object Miss : Read
    }

    fun get(key: String): Read

    fun put(key: String, value: String?)

    fun delete(vararg keys: String)
}

object DisabledSettingsL2 : SettingsL2 {
    override fun get(key: String): SettingsL2.Read = SettingsL2.Read.Miss

    override fun put(key: String, value: String?) = Unit

    override fun delete(vararg keys: String) = Unit
}

/**
 * Valkey-backed L2. Reads wait at most [timeout]; after a failure reads are skipped for
 * [OPEN_DURATION], so an unreachable Valkey costs one timeout per pause instead of one per login.
 * Writes and deletes never block the request and are always sent, so an invalidation is not lost
 * while reads are paused. "None set" is stored as the empty string, which no locale or region code
 * can be.
 */
class ValkeySettingsL2(
    private val values: ReactiveValueCommands<String, String>,
    private val keys: ReactiveKeyCommands<String>,
    private val ttl: Duration,
    private val timeout: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) : SettingsL2 {
    @Volatile private var openUntilNanos: Long? = null

    override fun get(key: String): SettingsL2.Read {
        if (isOpen()) return SettingsL2.Read.Miss
        val cached =
            try {
                values.get(key).await().atMost(timeout)
            } catch (error: Exception) {
                trip("get")
                return SettingsL2.Read.Miss
            } ?: return SettingsL2.Read.Miss
        return SettingsL2.Read.Hit(cached.ifEmpty { null })
    }

    override fun put(key: String, value: String?) {
        values
            .psetex(key, ttl.toMillis(), value ?: "")
            .ifNoItem()
            .after(timeout)
            .fail()
            .subscribe()
            .with({}, { trip("put") })
    }

    override fun delete(vararg keys: String) {
        this.keys
            .del(*keys)
            .ifNoItem()
            .after(timeout)
            .fail()
            .subscribe()
            .with({}, { trip("delete") })
    }

    private fun isOpen(): Boolean {
        val until = openUntilNanos ?: return false
        if (nanoTime() - until < 0) return true
        openUntilNanos = null
        return false
    }

    private fun trip(operation: String) {
        if (openUntilNanos == null) {
            LOG.warnf(
                "Player settings L2 cache skipped after failure (operation=%s, pauseSeconds=%d, reason=valkey_unavailable)",
                operation,
                OPEN_DURATION.toSeconds(),
            )
        }
        openUntilNanos = nanoTime() + OPEN_DURATION.toNanos()
    }

    companion object {
        val OPEN_DURATION: Duration = Duration.ofSeconds(10)
        private val LOG: Logger = Logger.getLogger(ValkeySettingsL2::class.java)
    }
}

@ApplicationScoped
class SettingsL2Producer
@Inject
constructor(
    @param:ConfigProperty(name = "grounds.player.settings-cache.enabled")
    private val enabled: Boolean,
    @param:ConfigProperty(name = "grounds.player.settings-cache.valkey-url")
    private val valkeyUrl: Optional<String>,
    @param:ConfigProperty(name = "grounds.player.settings-cache.ttl") private val ttl: Duration,
    @param:ConfigProperty(name = "grounds.player.settings-cache.valkey-timeout")
    private val timeout: Duration,
    private val redis: Instance<ReactiveRedisDataSource>,
) {
    @Produces
    @ApplicationScoped
    fun settingsL2(): SettingsL2 =
        if (enabled && valkeyUrl.isPresent) {
            val dataSource = redis.get()
            ValkeySettingsL2(
                dataSource.value(String::class.java),
                dataSource.key(String::class.java),
                ttl,
                timeout,
            )
        } else {
            DisabledSettingsL2
        }
}
