package gg.grounds.presence

import gg.grounds.persistence.PlayerSessionRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@ApplicationScoped
class PlayerSessionCleanup @Inject constructor(private val repository: PlayerSessionRepository) {
    @ConfigProperty(name = "grounds.player.sessions.ttl", defaultValue = "90s")
    private lateinit var sessionTtl: Duration

    @Scheduled(every = "{grounds.player.sessions.cleanup-interval}")
    fun expireStaleSessions() {
        val cutoff = Instant.now().minus(sessionTtl)
        val removed = repository.deleteStaleSessions(cutoff)
        LOG.infof("Player session cleanup completed (removed=%d, cutoff=%s)", removed, cutoff)
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerSessionCleanup::class.java)
    }
}
