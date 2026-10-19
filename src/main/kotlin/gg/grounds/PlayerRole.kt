package gg.grounds

import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.ScheduledExecution
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Which half of the service this process is.
 *
 * `all` (the default) is the regional runtime as it has always been: the presence API, the stale
 * session cleanup and the player-count broadcast. `writer` is the same image deployed in core, next
 * to the database and the NATS hub, whose only job is to apply the session and name writes the
 * regions queue on the PLAYER_EVENTS stream. A writer serves no players, so the regional ticks stay
 * off there: it would otherwise broadcast counts onto the hub and race the regions' cleanup.
 */
@Singleton
class PlayerRole(@param:ConfigProperty(name = "grounds.player.role") role: String) {
    val writer: Boolean =
        when (role.trim().lowercase()) {
            "all" -> false
            "writer" -> true
            else ->
                throw IllegalArgumentException(
                    "grounds.player.role must be all or writer, got '$role'"
                )
        }

    /** Skips a `@Scheduled` regional tick when this process is the core writer. */
    @Singleton
    class RegionalOnly(private val role: PlayerRole) : Scheduled.SkipPredicate {
        override fun test(execution: ScheduledExecution): Boolean = role.writer
    }
}
