package gg.grounds.presence

import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.time.TimeProvider
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@QuarkusTest
@TestProfile(PlayerSessionCleanupTestProfile::class)
class PlayerSessionCleanupTest {

    @InjectMock lateinit var repository: PlayerSessionRepository

    @InjectMock lateinit var timeProvider: TimeProvider

    @Inject lateinit var cleanup: PlayerSessionCleanup

    @BeforeEach
    fun resetMocks() {
        reset(repository, timeProvider)
    }

    @Test
    fun expireStaleSessionsUsesConfiguredTtl() {
        val fixedInstant = Instant.parse("2025-01-02T03:04:05Z")
        whenever(timeProvider.now()).thenReturn(fixedInstant)
        cleanup.expireStaleSessions()

        val cutoffCaptor = argumentCaptor<Instant>()
        verify(repository).deleteStaleSessions(cutoffCaptor.capture())
        assertEquals(fixedInstant.minusSeconds(120), cutoffCaptor.firstValue)
        verify(timeProvider).now()
    }
}

class PlayerSessionCleanupTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf("grounds.player.sessions.ttl" to "120s")
}
