package gg.grounds.events

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.PlayerRole
import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.persistence.PlayerSessionRepository.CountPlayersByProxyResult
import gg.grounds.persistence.PlayerSessionRepository.CountPlayersByServerResult
import gg.grounds.persistence.PlayerSessionRepository.ServerPlayerCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PlayerCountBroadcasterTest {
    private val repository = mock<PlayerSessionRepository>()

    private fun broadcaster(servers: Boolean) =
        PlayerCountBroadcaster(repository, mock(), PlayerRole("all"), ObjectMapper(), servers)

    @Test
    fun `flag off publishes the total alone, from the per-proxy aggregate as before`() {
        whenever(repository.countPlayersByProxy())
            .thenReturn(CountPlayersByProxyResult.Counted(emptyList(), 7))
        assertEquals("""{"total":7}""", String(broadcaster(false).payload()!!))
        verify(repository, never()).countPlayersByServer()
    }

    @Test
    fun `flag on adds the servers and keeps total as the first key`() {
        whenever(repository.countPlayersByServer())
            .thenReturn(
                CountPlayersByServerResult.Counted(
                    listOf(ServerPlayerCount("lobby-1", 3), ServerPlayerCount("game\"x", 1)),
                    5,
                )
            )
        assertEquals(
            """{"total":5,"servers":{"lobby-1":3,"game\"x":1}}""",
            String(broadcaster(true).payload()!!),
        )
        verify(repository, never()).countPlayersByProxy()
    }

    @Test
    fun `a failed count publishes nothing rather than zero`() {
        whenever(repository.countPlayersByServer()).thenReturn(CountPlayersByServerResult.Error)
        whenever(repository.countPlayersByProxy()).thenReturn(CountPlayersByProxyResult.Error)
        assertNull(broadcaster(true).payload())
        assertNull(broadcaster(false).payload())
    }
}
