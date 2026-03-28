package gg.grounds.api.permissions

import gg.grounds.grpc.permissions.CheckPlayerPermissionReply
import gg.grounds.grpc.permissions.CheckPlayerPermissionRequest
import gg.grounds.grpc.permissions.GetPlayerPermissionsReply
import gg.grounds.grpc.permissions.GetPlayerPermissionsRequest
import gg.grounds.grpc.permissions.PermissionsService
import gg.grounds.persistence.permissions.PermissionsQueryRepository
import io.grpc.Status
import io.quarkus.grpc.GrpcService
import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import org.jboss.logging.Logger

@GrpcService
@Blocking
class PermissionsPluginGrpcService
@Inject
constructor(private val queryRepository: PermissionsQueryRepository) : PermissionsService {
    override fun getPlayerPermissions(
        request: GetPlayerPermissionsRequest
    ): Uni<GetPlayerPermissionsReply> {
        return Uni.createFrom().item { handleGetPlayerPermissions(request) }
    }

    override fun checkPlayerPermission(
        request: CheckPlayerPermissionRequest
    ): Uni<CheckPlayerPermissionReply> {
        return Uni.createFrom().item { handleCheckPlayerPermission(request) }
    }

    private fun handleGetPlayerPermissions(
        request: GetPlayerPermissionsRequest
    ): GetPlayerPermissionsReply {
        val rawPlayerId = request.playerId?.trim().orEmpty()
        val playerId =
            PermissionsRequestParser.parsePlayerId(rawPlayerId)
                ?: run {
                    LOG.warnf(
                        "Get player permissions request rejected (playerId=%s, reason=invalid_player_id)",
                        rawPlayerId,
                    )
                    throw Status.INVALID_ARGUMENT.withDescription("player_id must be a UUID")
                        .asRuntimeException()
                }

        val data =
            queryRepository.getPlayerPermissions(
                playerId,
                request.includeEffectivePermissions,
                request.includeDirectPermissions,
                request.includeGroups,
            )
                ?: throw Status.INTERNAL.withDescription("Failed to load player permissions")
                    .asRuntimeException()

        LOG.debugf(
            "Fetch player permissions completed (playerId=%s, includeEffectivePermissions=%s, includeDirectPermissions=%s, includeGroups=%s)",
            playerId,
            request.includeEffectivePermissions,
            request.includeDirectPermissions,
            request.includeGroups,
        )
        return GetPlayerPermissionsReply.newBuilder()
            .setPlayer(PermissionsProtoMapper.toProto(data))
            .build()
    }

    private fun handleCheckPlayerPermission(
        request: CheckPlayerPermissionRequest
    ): CheckPlayerPermissionReply {
        val rawPlayerId = request.playerId?.trim().orEmpty()
        val playerId =
            PermissionsRequestParser.parsePlayerId(rawPlayerId)
                ?: run {
                    LOG.warnf(
                        "Check player permission request rejected (playerId=%s, reason=invalid_player_id)",
                        rawPlayerId,
                    )
                    throw Status.INVALID_ARGUMENT.withDescription("player_id must be a UUID")
                        .asRuntimeException()
                }
        val permission = request.permission?.trim().orEmpty()
        if (permission.isEmpty()) {
            LOG.warnf(
                "Check player permission request rejected (playerId=%s, reason=empty_permission)",
                playerId,
            )
            throw Status.INVALID_ARGUMENT.withDescription("permission must be provided")
                .asRuntimeException()
        }

        val allowed =
            queryRepository.checkPlayerPermission(playerId, permission)
                ?: throw Status.INTERNAL.withDescription("Failed to check player permission")
                    .asRuntimeException()

        LOG.debugf(
            "Check player permission completed (playerId=%s, permission=%s, allowed=%s)",
            playerId,
            permission,
            allowed,
        )
        return CheckPlayerPermissionReply.newBuilder().setAllowed(allowed).build()
    }

    companion object {
        private val LOG = Logger.getLogger(PermissionsPluginGrpcService::class.java)
    }
}
