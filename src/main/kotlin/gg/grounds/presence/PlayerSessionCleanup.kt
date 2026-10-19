package gg.grounds.presence

import gg.grounds.PlayerRole
import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.time.TimeProvider
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@ApplicationScoped
class PlayerSessionCleanup
@Inject
constructor(
    private val repository: PlayerSessionRepository,
    private val timeProvider: TimeProvider,
) {
    @ConfigProperty(name = "grounds.player.sessions.ttl", defaultValue = "90s")
    private lateinit var sessionTtl: Duration

    // Regional only: the core writer applies queued writes and owns no presence of its own.
    @Scheduled(
        every = "{grounds.player.sessions.cleanup-interval}",
        skipExecutionIf = PlayerRole.RegionalOnly::class,
    )
    fun expireStaleSessions() {
        val cutoff = timeProvider.now().minus(sessionTtl)
        val removed = repository.deleteStaleSessions(cutoff)
        if (removed > 0) {
            LOG.infof("Player session cleanup completed (removed=%d, cutoff=%s)", removed, cutoff)
            return
        }
        LOG.debugf("Player session cleanup completed (removed=%d, cutoff=%s)", removed, cutoff)
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerSessionCleanup::class.java)
    }
}
