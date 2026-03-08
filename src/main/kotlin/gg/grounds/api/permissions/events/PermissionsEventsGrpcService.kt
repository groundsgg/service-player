package gg.grounds.api.permissions.events

import gg.grounds.grpc.permissions.PermissionsChangeEvent
import gg.grounds.grpc.permissions.PermissionsEventsService
import gg.grounds.grpc.permissions.SubscribePermissionsChangesRequest
import gg.grounds.persistence.PlayerSessionRepository
import io.quarkus.grpc.GrpcService
import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Multi
import jakarta.inject.Inject
import org.jboss.logging.Logger

@GrpcService
@Blocking
class PermissionsEventsGrpcService
@Inject
constructor(
    private val publisher: PermissionsEventsPublisher,
    private val permissionsChangeEmitter: PermissionsChangeEmitter,
    private val playerSessionRepository: PlayerSessionRepository,
) : PermissionsEventsService {
    override fun subscribePermissionsChanges(
        request: SubscribePermissionsChangesRequest
    ): Multi<PermissionsChangeEvent> {
        LOG.infof(
            "Permissions event subscription established (serverId=%s, lastEventId=%s)",
            request.serverId,
            request.lastEventId,
        )
        if (request.lastEventId.isNotBlank()) {
            val playerIds = playerSessionRepository.listActivePlayers()
            playerIds.forEach { playerId ->
                permissionsChangeEmitter.emitRefresh(playerId, "subscription_resume")
            }
            LOG.infof(
                "Permissions subscription refresh completed (serverId=%s, lastEventId=%s, affectedPlayers=%d)",
                request.serverId,
                request.lastEventId,
                playerIds.size,
            )
        }
        return publisher
            .stream()
            .onFailure()
            .invoke { error ->
                val reason = error.message ?: error::class.java.name
                LOG.warnf(
                    error,
                    "Permissions event subscription failed (serverId=%s, lastEventId=%s, reason=%s)",
                    request.serverId,
                    request.lastEventId,
                    reason,
                )
            }
            .onCancellation()
            .invoke {
                LOG.infof(
                    "Permissions event subscription cancelled (serverId=%s, lastEventId=%s)",
                    request.serverId,
                    request.lastEventId,
                )
            }
            .onCompletion()
            .invoke {
                LOG.infof(
                    "Permissions event subscription completed (serverId=%s, lastEventId=%s)",
                    request.serverId,
                    request.lastEventId,
                )
            }
    }

    companion object {
        private val LOG = Logger.getLogger(PermissionsEventsGrpcService::class.java)
    }
}
