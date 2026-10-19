package gg.grounds.settings

import gg.grounds.persistence.PlayerNameRepository.Setting
import gg.grounds.persistence.PlayerNameRepository.SettingRead
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlayerSettingsCacheTest {
    private class MapL2 : SettingsL2 {
        val values = HashMap<String, String>()

        override fun get(key: String): SettingsL2.Read =
            values[key]?.let { SettingsL2.Read.Hit(it.ifEmpty { null }) } ?: SettingsL2.Read.Miss

        override fun put(key: String, value: String?) {
            values[key] = value ?: ""
        }

        override fun delete(vararg keys: String) {
            keys.forEach(values::remove)
        }
    }

    private val l2 = MapL2()
    private val cache =
        PlayerSettingsCache(true, Duration.ofMinutes(30), 100, l2, SimpleMeterRegistry())
    private val playerId: UUID = UUID.randomUUID()
    private var loads = 0

    private fun load(value: String?): () -> SettingRead = {
        loads++
        SettingRead.Value(value)
    }

    @Test
    fun `a second read is served from memory`() {
        assertEquals("de", cache.get(Setting.LOCALE, playerId, load("de")))
        assertEquals("de", cache.get(Setting.LOCALE, playerId, load("en")))
        assertEquals(1, loads)
        assertEquals("de", l2.values[PlayerSettingsCache.key(Setting.LOCALE, playerId)])
    }

    @Test
    fun `none set is cached as none`() {
        assertNull(cache.get(Setting.REGION, playerId, load(null)))
        assertNull(cache.get(Setting.REGION, playerId, load("eu")))
        assertEquals(1, loads)
        assertEquals("", l2.values[PlayerSettingsCache.key(Setting.REGION, playerId)])
    }

    @Test
    fun `the regional level answers another runtime's miss`() {
        l2.values[PlayerSettingsCache.key(Setting.LOCALE, playerId)] = "fr"
        assertEquals("fr", cache.get(Setting.LOCALE, playerId, load("de")))
        assertEquals(0, loads)
    }

    @Test
    fun `an invalidation drops both levels and both settings`() {
        cache.get(Setting.LOCALE, playerId, load("de"))
        cache.get(Setting.REGION, playerId, load("eu"))
        cache.invalidate(playerId)
        assertEquals(emptyMap<String, String>(), l2.values)
        assertEquals("en", cache.get(Setting.LOCALE, playerId, load("en")))
        assertEquals(3, loads)
    }

    @Test
    fun `a read that raced a write does not put the old value back`() {
        val stale = {
            cache.invalidate(playerId) // the write lands while the old value is being read
            SettingRead.Value("old")
        }
        assertEquals("old", cache.get(Setting.LOCALE, playerId, stale))
        assertEquals("new", cache.get(Setting.LOCALE, playerId, load("new")))
        assertEquals("new", l2.values[PlayerSettingsCache.key(Setting.LOCALE, playerId)])
    }

    @Test
    fun `a failed read is not cached`() {
        assertNull(cache.get(Setting.LOCALE, playerId) { SettingRead.Failed })
        assertEquals("de", cache.get(Setting.LOCALE, playerId, load("de")))
        assertEquals(1, loads)
    }
}
