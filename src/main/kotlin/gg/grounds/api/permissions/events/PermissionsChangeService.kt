package gg.grounds.api.permissions.events

import gg.grounds.domain.permissions.ApplyOutcome
import gg.grounds.persistence.permissions.PermissionsQueryRepository
import gg.grounds.persistence.permissions.PlayerGroupRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID

@ApplicationScoped
class PermissionsChangeService
@Inject
constructor(
    private val playerGroupRepository: PlayerGroupRepository,
    private val permissionsQueryRepository: PermissionsQueryRepository,
    private val permissionsChangeEmitter: PermissionsChangeEmitter,
) {
    fun emitPlayerDeltaIfChanged(
        playerId: UUID,
        reason: String,
        change: () -> ApplyOutcome,
    ): ApplyOutcome {
        val before =
            permissionsQueryRepository.getPlayerPermissions(
                playerId,
                includeEffectivePermissions = true,
                includeDirectPermissions = true,
                includeGroups = true,
            )
        val outcome = change()
        if (outcome != ApplyOutcome.NO_CHANGE && outcome != ApplyOutcome.ERROR) {
            val after =
                permissionsQueryRepository.getPlayerPermissions(
                    playerId,
                    includeEffectivePermissions = true,
                    includeDirectPermissions = true,
                    includeGroups = true,
                )
            permissionsChangeEmitter.emitPlayerDelta(playerId, reason, before, after)
        }
        return outcome
    }

    fun emitGroupPermissionsDeltaIfChanged(
        groupName: String,
        reason: String,
        change: () -> ApplyOutcome,
    ): ApplyOutcome {
        val playerIds = playerGroupRepository.listActivePlayersForGroup(groupName)
        val before =
            playerIds.associateWith { playerId ->
                permissionsQueryRepository.getPlayerPermissions(
                    playerId,
                    includeEffectivePermissions = true,
                    includeDirectPermissions = true,
                    includeGroups = true,
                )
            }
        val outcome = change()
        if (
            outcome != ApplyOutcome.NO_CHANGE &&
                outcome != ApplyOutcome.ERROR &&
                playerIds.isNotEmpty()
        ) {
            playerIds.forEach { playerId ->
                val after =
                    permissionsQueryRepository.getPlayerPermissions(
                        playerId,
                        includeEffectivePermissions = true,
                        includeDirectPermissions = true,
                        includeGroups = true,
                    )
                permissionsChangeEmitter.emitEffectiveDelta(
                    playerId,
                    reason,
                    before[playerId],
                    after,
                )
            }
        }
        return outcome
    }
}
