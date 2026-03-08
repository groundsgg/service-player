package gg.grounds.api.permissions.admin

import gg.grounds.api.permissions.ApplyResultMapper
import gg.grounds.api.permissions.PermissionsRequestParser
import gg.grounds.api.permissions.events.PermissionsChangeService
import gg.grounds.domain.permissions.ApplyOutcome
import gg.grounds.grpc.permissions.AddPlayerGroupsReply
import gg.grounds.grpc.permissions.AddPlayerGroupsRequest
import gg.grounds.grpc.permissions.RemovePlayerGroupsReply
import gg.grounds.grpc.permissions.RemovePlayerGroupsRequest
import gg.grounds.persistence.permissions.PlayerGroupRepository
import io.grpc.Status
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import org.jboss.logging.Logger

@ApplicationScoped
class PlayerGroupsAdminHandler
@Inject
constructor(
    private val playerGroupRepository: PlayerGroupRepository,
    private val permissionsChangeService: PermissionsChangeService,
) {
    fun addPlayerGroups(request: AddPlayerGroupsRequest): AddPlayerGroupsReply {
        val rawPlayerId = request.playerId?.trim().orEmpty()
        val playerId =
            PermissionsRequestParser.parsePlayerId(rawPlayerId)
                ?: run {
                    LOG.warnf(
                        "Add player groups request rejected (playerId=%s, reason=invalid_player_id)",
                        rawPlayerId,
                    )
                    throw Status.INVALID_ARGUMENT.withDescription("player_id must be a UUID")
                        .asRuntimeException()
                }
        val groupMemberships =
            PermissionsRequestParser.sanitizeGroupMemberships(request.groupMembershipsList)
        if (groupMemberships.isEmpty()) {
            LOG.warnf(
                "Add player groups request rejected (playerId=%s, reason=empty_group_memberships)",
                playerId,
            )
            throw Status.INVALID_ARGUMENT.withDescription("group_memberships must be provided")
                .asRuntimeException()
        }
        if (
            groupMemberships.any { membership ->
                val now = Instant.now()
                PermissionsRequestParser.hasPastExpiry(membership.expiresAt, now)
            }
        ) {
            LOG.warnf(
                "Add player groups request rejected (playerId=%s, reason=expired_membership)",
                playerId,
            )
            throw Status.INVALID_ARGUMENT.withDescription("expires_at must be in the future")
                .asRuntimeException()
        }

        val outcome =
            permissionsChangeService.emitPlayerDeltaIfChanged(playerId, "player_group_add") {
                playerGroupRepository.addPlayerGroups(playerId, groupMemberships)
            }
        if (outcome == ApplyOutcome.ERROR) {
            throw Status.INTERNAL.withDescription("Failed to add player groups")
                .asRuntimeException()
        }
        LOG.infof(
            "Add player groups completed (playerId=%s, groupCount=%d, outcome=%s)",
            playerId,
            groupMemberships.size,
            outcome,
        )
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
                    throw Status.INVALID_ARGUMENT.withDescription("player_id must be a UUID")
                        .asRuntimeException()
                }
        val groupNames = PermissionsRequestParser.sanitize(request.groupNamesList)
        if (groupNames.isEmpty()) {
            LOG.warnf(
                "Remove player groups request rejected (playerId=%s, reason=empty_group_names)",
                playerId,
            )
            throw Status.INVALID_ARGUMENT.withDescription("group_names must be provided")
                .asRuntimeException()
        }

        val outcome =
            permissionsChangeService.emitPlayerDeltaIfChanged(playerId, "player_group_remove") {
                playerGroupRepository.removePlayerGroups(playerId, groupNames)
            }
        if (outcome == ApplyOutcome.ERROR) {
            throw Status.INTERNAL.withDescription("Failed to remove player groups")
                .asRuntimeException()
        }
        LOG.infof(
            "Remove player groups completed (playerId=%s, groupCount=%d, outcome=%s)",
            playerId,
            groupNames.size,
            outcome,
        )
        return RemovePlayerGroupsReply.newBuilder()
            .setApplyResult(ApplyResultMapper.toProto(outcome))
            .build()
    }

    companion object {
        private val LOG = Logger.getLogger(PlayerGroupsAdminHandler::class.java)
    }
}
