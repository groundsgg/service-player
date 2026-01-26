package gg.grounds.api.permissions.events

import gg.grounds.persistence.permissions.GroupRepository
import gg.grounds.persistence.permissions.PlayerGroupRepository
import gg.grounds.persistence.permissions.PlayerPermissionRepository
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.jboss.logging.Logger

@ApplicationScoped
class PermissionsExpiryScheduler
@Inject
constructor(
    private val playerPermissionRepository: PlayerPermissionRepository,
    private val playerGroupRepository: PlayerGroupRepository,
    private val groupRepository: GroupRepository,
    private val permissionsChangeEmitter: PermissionsChangeEmitter,
) {
    private val lastScan = AtomicReference(Instant.now().minusSeconds(DEFAULT_BOOTSTRAP_SECONDS))

    @PostConstruct
    fun refreshOnStartup() {
        val now = Instant.now()
        val affectedPlayers =
            linkedSetOf<UUID>().apply {
                addAll(playerPermissionRepository.listPlayersWithPermissions())
                addAll(playerGroupRepository.listPlayersWithGroups())
            }
        affectedPlayers.forEach { playerId ->
            permissionsChangeEmitter.emitRefresh(playerId, "startup")
        }
        lastScan.set(now)
        LOG.infof(
            "Permissions startup refresh completed (affectedPlayers=%d, scannedAt=%s)",
            affectedPlayers.size,
            now,
        )
    }

    @Scheduled(every = "{permissions.expiry.scan.every}")
    fun scanExpirations() {
        val now = Instant.now()
        val since = lastScan.getAndSet(now)
        if (!since.isBefore(now)) {
            return
        }

        val expiredPlayers =
            linkedSetOf<UUID>().apply {
                addAll(playerPermissionRepository.listPlayersWithExpiredPermissions(since, now))
                addAll(playerGroupRepository.listPlayersWithExpiredGroups(since, now))
            }

        val expiredGroups = groupRepository.listGroupsWithExpiredPermissions(since, now)
        expiredGroups.forEach { groupName ->
            expiredPlayers.addAll(playerGroupRepository.listActivePlayersForGroup(groupName))
        }

        expiredPlayers.forEach { playerId ->
            permissionsChangeEmitter.emitRefresh(playerId, "expiry")
        }

        LOG.debugf(
            "Permissions expiry scan completed (since=%s, until=%s, affectedPlayers=%d, expiredGroups=%d)",
            since,
            now,
            expiredPlayers.size,
            expiredGroups.size,
        )
    }

    companion object {
        private const val DEFAULT_BOOTSTRAP_SECONDS = 60L
        private val LOG = Logger.getLogger(PermissionsExpiryScheduler::class.java)
    }
}
