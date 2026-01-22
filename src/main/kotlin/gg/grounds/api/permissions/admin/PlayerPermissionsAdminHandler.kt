package gg.grounds.api.permissions.admin

import gg.grounds.api.permissions.ApplyResultMapper
import gg.grounds.api.permissions.PermissionsRequestParser
import gg.grounds.domain.permissions.ApplyOutcome
import gg.grounds.grpc.permissions.AddPlayerPermissionsReply
import gg.grounds.grpc.permissions.AddPlayerPermissionsRequest
import gg.grounds.grpc.permissions.RemovePlayerPermissionsReply
import gg.grounds.grpc.permissions.RemovePlayerPermissionsRequest
import gg.grounds.persistence.permissions.PlayerPermissionRepository
import io.grpc.Status
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
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
                    throw Status.INVALID_ARGUMENT.withDescription("player_id must be a UUID")
                        .asRuntimeException()
                }
        val permissionGrants =
            PermissionsRequestParser.sanitizePermissionGrants(request.permissionGrantsList)
        if (permissionGrants.isEmpty()) {
            LOG.warnf(
                "Add player permissions request rejected (playerId=%s, reason=empty_permission_grants)",
                playerId,
            )
            throw Status.INVALID_ARGUMENT.withDescription("permission_grants must be provided")
                .asRuntimeException()
        }
        val now = Instant.now()
        if (
            permissionGrants.any { grant ->
                PermissionsRequestParser.hasPastExpiry(grant.expiresAt, now)
            }
        ) {
            LOG.warnf(
                "Add player permissions request rejected (playerId=%s, reason=expired_grant)",
                playerId,
            )
            throw Status.INVALID_ARGUMENT.withDescription("expires_at must be in the future")
                .asRuntimeException()
        }

        val outcome = playerPermissionRepository.addPlayerPermissions(playerId, permissionGrants)
        if (outcome == ApplyOutcome.ERROR) {
            throw Status.INTERNAL.withDescription("Failed to add player permissions")
                .asRuntimeException()
        }
        LOG.infof(
            "Add player permissions completed (playerId=%s, permissionsCount=%d, outcome=%s)",
            playerId,
            permissionGrants.size,
            outcome,
        )
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
                    throw Status.INVALID_ARGUMENT.withDescription("player_id must be a UUID")
                        .asRuntimeException()
                }
        val permissions = PermissionsRequestParser.sanitize(request.permissionsList)
        if (permissions.isEmpty()) {
            LOG.warnf(
                "Remove player permissions request rejected (playerId=%s, reason=empty_permissions)",
                playerId,
            )
            throw Status.INVALID_ARGUMENT.withDescription("permissions must be provided")
                .asRuntimeException()
        }

        val outcome = playerPermissionRepository.removePlayerPermissions(playerId, permissions)
        if (outcome == ApplyOutcome.ERROR) {
            throw Status.INTERNAL.withDescription("Failed to remove player permissions")
                .asRuntimeException()
        }
        LOG.infof(
            "Remove player permissions completed (playerId=%s, permissionsCount=%d, outcome=%s)",
            playerId,
            permissions.size,
            outcome,
        )
        return RemovePlayerPermissionsReply.newBuilder()
            .setApplyResult(ApplyResultMapper.toProto(outcome))
            .build()
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerPermissionsAdminHandler::class.java)
    }
}
