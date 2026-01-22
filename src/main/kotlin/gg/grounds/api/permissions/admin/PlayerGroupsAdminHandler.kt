package gg.grounds.api.permissions.admin

import gg.grounds.api.permissions.ApplyResultMapper
import gg.grounds.api.permissions.PermissionsRequestParser
import gg.grounds.domain.ApplyOutcome
import gg.grounds.grpc.permissions.AddPlayerGroupsReply
import gg.grounds.grpc.permissions.AddPlayerGroupsRequest
import gg.grounds.grpc.permissions.RemovePlayerGroupsReply
import gg.grounds.grpc.permissions.RemovePlayerGroupsRequest
import gg.grounds.persistence.permissions.PlayerGroupRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

@ApplicationScoped
class PlayerGroupsAdminHandler
@Inject
constructor(private val playerGroupRepository: PlayerGroupRepository) {
    fun addPlayerGroups(request: AddPlayerGroupsRequest): AddPlayerGroupsReply {
        val rawPlayerId = request.playerId?.trim().orEmpty()
        val playerId =
            PermissionsRequestParser.parsePlayerId(rawPlayerId)
                ?: run {
                    LOG.warnf(
                        "Add player groups request rejected (playerId=%s, reason=invalid_player_id)",
                        rawPlayerId,
                    )
                    return AddPlayerGroupsReply.newBuilder()
                        .setApplyResult(ApplyResultMapper.toProto(ApplyOutcome.ERROR))
                        .build()
                }
        val groupNames = PermissionsRequestParser.sanitize(request.groupNamesList)
        if (groupNames.isEmpty()) {
            LOG.warnf(
                "Add player groups request rejected (playerId=%s, reason=empty_group_names)",
                playerId,
            )
            return AddPlayerGroupsReply.newBuilder()
                .setApplyResult(ApplyResultMapper.toProto(ApplyOutcome.ERROR))
                .build()
        }

        val outcome = playerGroupRepository.addPlayerGroups(playerId, groupNames)
        if (outcome != ApplyOutcome.ERROR) {
            LOG.infof(
                "Add player groups completed (playerId=%s, groupCount=%d, outcome=%s)",
                playerId,
                groupNames.size,
                outcome,
            )
        }
        return AddPlayerGroupsReply.newBuilder()
            .setApplyResult(ApplyResultMapper.toProto(outcome))
            .build()
    }

    fun removePlayerGroups(request: RemovePlayerGroupsRequest): RemovePlayerGroupsReply {
        val rawPlayerId = request.playerId?.trim().orEmpty()
        val playerId =
            PermissionsRequestParser.parsePlayerId(rawPlayerId)
                ?: run {
                    LOG.warnf(
                        "Remove player groups request rejected (playerId=%s, reason=invalid_player_id)",
                        rawPlayerId,
                    )
                    return RemovePlayerGroupsReply.newBuilder()
                        .setApplyResult(ApplyResultMapper.toProto(ApplyOutcome.ERROR))
                        .build()
                }
        val groupNames = PermissionsRequestParser.sanitize(request.groupNamesList)
        if (groupNames.isEmpty()) {
            LOG.warnf(
                "Remove player groups request rejected (playerId=%s, reason=empty_group_names)",
                playerId,
            )
            return RemovePlayerGroupsReply.newBuilder()
                .setApplyResult(ApplyResultMapper.toProto(ApplyOutcome.ERROR))
                .build()
        }

        val outcome = playerGroupRepository.removePlayerGroups(playerId, groupNames)
        if (outcome != ApplyOutcome.ERROR) {
            LOG.infof(
                "Remove player groups completed (playerId=%s, groupCount=%d, outcome=%s)",
                playerId,
                groupNames.size,
                outcome,
            )
        }
        return RemovePlayerGroupsReply.newBuilder()
            .setApplyResult(ApplyResultMapper.toProto(outcome))
            .build()
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerGroupsAdminHandler::class.java)
    }
}
