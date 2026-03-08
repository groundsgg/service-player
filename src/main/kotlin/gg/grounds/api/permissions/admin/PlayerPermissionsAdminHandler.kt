package gg.grounds.api.permissions.admin

import gg.grounds.api.permissions.ApplyResultMapper
import gg.grounds.api.permissions.PermissionsRequestParser
import gg.grounds.domain.ApplyOutcome
import gg.grounds.grpc.permissions.AddPlayerPermissionsReply
import gg.grounds.grpc.permissions.AddPlayerPermissionsRequest
import gg.grounds.grpc.permissions.RemovePlayerPermissionsReply
import gg.grounds.grpc.permissions.RemovePlayerPermissionsRequest
import gg.grounds.persistence.permissions.PlayerPermissionRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

@ApplicationScoped
class PlayerPermissionsAdminHandler
@Inject
constructor(private val playerPermissionRepository: PlayerPermissionRepository) {
    fun addPlayerPermissions(request: AddPlayerPermissionsRequest): AddPlayerPermissionsReply {
        val rawPlayerId = request.playerId?.trim().orEmpty()
        val playerId =
            PermissionsRequestParser.parsePlayerId(rawPlayerId)
                ?: run {
                    LOG.warnf(
                        "Add player permissions request rejected (playerId=%s, reason=invalid_player_id)",
                        rawPlayerId,
                    )
                    return AddPlayerPermissionsReply.newBuilder()
                        .setApplyResult(ApplyResultMapper.toProto(ApplyOutcome.ERROR))
                        .build()
                }
        val permissions = PermissionsRequestParser.sanitize(request.permissionsList)
        if (permissions.isEmpty()) {
            LOG.warnf(
                "Add player permissions request rejected (playerId=%s, reason=empty_permissions)",
                playerId,
            )
            return AddPlayerPermissionsReply.newBuilder()
                .setApplyResult(ApplyResultMapper.toProto(ApplyOutcome.ERROR))
                .build()
        }

        val outcome = playerPermissionRepository.addPlayerPermissions(playerId, permissions)
        if (outcome != ApplyOutcome.ERROR) {
            LOG.infof(
                "Add player permissions completed (playerId=%s, permissionsCount=%d, outcome=%s)",
                playerId,
                permissions.size,
                outcome,
            )
        }
        return AddPlayerPermissionsReply.newBuilder()
            .setApplyResult(ApplyResultMapper.toProto(outcome))
            .build()
    }

    fun removePlayerPermissions(
        request: RemovePlayerPermissionsRequest
    ): RemovePlayerPermissionsReply {
        val rawPlayerId = request.playerId?.trim().orEmpty()
        val playerId =
            PermissionsRequestParser.parsePlayerId(rawPlayerId)
                ?: run {
                    LOG.warnf(
                        "Remove player permissions request rejected (playerId=%s, reason=invalid_player_id)",
                        rawPlayerId,
                    )
                    return RemovePlayerPermissionsReply.newBuilder()
                        .setApplyResult(ApplyResultMapper.toProto(ApplyOutcome.ERROR))
                        .build()
                }
        val permissions = PermissionsRequestParser.sanitize(request.permissionsList)
        if (permissions.isEmpty()) {
            LOG.warnf(
                "Remove player permissions request rejected (playerId=%s, reason=empty_permissions)",
                playerId,
            )
            return RemovePlayerPermissionsReply.newBuilder()
                .setApplyResult(ApplyResultMapper.toProto(ApplyOutcome.ERROR))
                .build()
        }

        val outcome = playerPermissionRepository.removePlayerPermissions(playerId, permissions)
        if (outcome != ApplyOutcome.ERROR) {
            LOG.infof(
                "Remove player permissions completed (playerId=%s, permissionsCount=%d, outcome=%s)",
                playerId,
                permissions.size,
                outcome,
            )
        }
        return RemovePlayerPermissionsReply.newBuilder()
            .setApplyResult(ApplyResultMapper.toProto(outcome))
            .build()
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerPermissionsAdminHandler::class.java)
    }
}
