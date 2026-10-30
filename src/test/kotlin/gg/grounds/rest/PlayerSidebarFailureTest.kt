package gg.grounds.rest

import gg.grounds.persistence.PlayerNameRepository
import gg.grounds.settings.SettingsInvalidations
import jakarta.ws.rs.ServiceUnavailableException
import java.sql.SQLException
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*

class PlayerSidebarFailureTest {
    @Test
    fun `failed reads never substitute the visible default`() {
        val names: PlayerNameRepository = mock()
        val events: SettingsInvalidations = mock()
        val id = UUID.randomUUID()
        doAnswer { throw SQLException("database down") }.whenever(names).sidebarEnabled(id)
        assertThrows<ServiceUnavailableException> {
            PlayerSidebarResource(names, events).get(id.toString())
        }
        verifyNoInteractions(events)
    }

    @Test
    fun `failed writes do not acknowledge or invalidate`() {
        val names: PlayerNameRepository = mock()
        val events: SettingsInvalidations = mock()
        val id = UUID.randomUUID()
        doAnswer { throw SQLException("database down") }
            .whenever(names)
            .setSidebarEnabled(id, false)
        assertThrows<ServiceUnavailableException> {
            PlayerSidebarResource(names, events).put(id.toString(), SidebarSetting(false))
        }
        verifyNoInteractions(events)
    }
}
