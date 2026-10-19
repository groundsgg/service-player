package gg.grounds.settings

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import gg.grounds.persistence.PlayerNameRepository.Setting
import gg.grounds.persistence.PlayerNameRepository.SettingRead
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * A player's locale and preferred region, cached in this process (L1) and in the region's
 * `valkey-cache` (L2), so the awaited locale read at PostLogin costs no database round trip.
 *
 * Invalidation: this service is the only writer of both settings. A write drops both levels here
 * and publishes `player.settings.changed.<id>` ([SettingsInvalidations]), on which every runtime of
 * every region drops its L1 and its region's L2 key.
 *
 * A read that raced a write must not put the old value back: a load only fills the cache if no
 * invalidation arrived in this process while it ran. A failed read is never cached — it would
 * otherwise pin "none set" for the whole TTL.
 */
@ApplicationScoped
class PlayerSettingsCache
@Inject
constructor(
    @param:ConfigProperty(name = "grounds.player.settings-cache.enabled") val enabled: Boolean,
    @param:ConfigProperty(name = "grounds.player.settings-cache.ttl") ttl: Duration,
    @param:ConfigProperty(name = "grounds.player.settings-cache.max-entries") maxEntries: Long,
    private val l2: SettingsL2,
    private val registry: MeterRegistry,
) {
    private val l1: Cache<String, Optional<String>> =
        Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxEntries).build()

    private val invalidations = AtomicLong()

    fun get(setting: Setting, playerId: UUID, load: () -> SettingRead): String? {
        val key = key(setting, playerId)
        l1.getIfPresent(key)?.let {
            count(setting, "l1")
            return it.orElse(null)
        }
        val generation = invalidations.get()
        when (val cached = l2.get(key)) {
            is SettingsL2.Read.Hit -> {
                if (generation == invalidations.get()) {
                    l1.put(key, Optional.ofNullable(cached.value))
                }
                count(setting, "l2")
                return cached.value
            }
            SettingsL2.Read.Miss -> Unit
        }
        return when (val read = load()) {
            is SettingRead.Value -> {
                if (generation == invalidations.get()) {
                    l1.put(key, Optional.ofNullable(read.value))
                    l2.put(key, read.value)
                }
                count(setting, "db")
                read.value
            }
            SettingRead.Failed -> {
                count(setting, "failed")
                null
            }
        }
    }

    /** Drops both settings of [playerId] from both levels. */
    fun invalidate(playerId: UUID) {
        invalidations.incrementAndGet()
        val keys = Setting.entries.map { key(it, playerId) }
        l1.invalidateAll(keys)
        l2.delete(*keys.toTypedArray())
    }

    private fun count(setting: Setting, result: String) {
        registry
            .counter("player.settings.cache", "setting", setting.name.lowercase(), "result", result)
            .increment()
    }

    companion object {
        fun key(setting: Setting, playerId: UUID): String =
            when (setting) {
                Setting.LOCALE -> "player:locale:$playerId"
                Setting.REGION -> "player:region:$playerId"
            }
    }
}
