package gg.grounds.api.permissions.events

import com.google.protobuf.Timestamp
import gg.grounds.domain.permissions.PlayerGroupMembership
import gg.grounds.domain.permissions.PlayerPermissionGrant
import gg.grounds.domain.permissions.PlayerPermissionsData
import gg.grounds.grpc.permissions.EffectivePermissionDelta
import gg.grounds.grpc.permissions.GroupMembershipDelta
import gg.grounds.grpc.permissions.PermissionDelta
import gg.grounds.grpc.permissions.PermissionsChangeEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import org.jboss.logging.Logger

@ApplicationScoped
class PermissionsChangeEmitter
@Inject
constructor(private val publisher: PermissionsEventsPublisher) {
    fun emitPlayerDelta(
        playerId: UUID,
        reason: String,
        before: PlayerPermissionsData?,
        after: PlayerPermissionsData?,
    ) {
        emitDeltaEvent(
            playerId = playerId,
            reason = reason,
            before = before,
            after = after,
            includeDirect = true,
            includeGroups = true,
        )
    }

    fun emitEffectiveDelta(
        playerId: UUID,
        reason: String,
        before: PlayerPermissionsData?,
        after: PlayerPermissionsData?,
    ) {
        emitDeltaEvent(
            playerId = playerId,
            reason = reason,
            before = before,
            after = after,
            includeDirect = false,
            includeGroups = false,
        )
    }

    fun emitRefresh(playerId: UUID, reason: String) {
        val event =
            PermissionsChangeEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setPlayerId(playerId.toString())
                .setOccurredAt(nowTimestamp())
                .setReason(reason)
                .setRequiresFullRefresh(true)
                .build()
        publisher.publish(event)
        LOG.debugf(
            "Permissions refresh event published (playerId=%s, eventId=%s, reason=%s)",
            playerId,
            event.eventId,
            reason,
        )
    }

    private fun emitDeltaEvent(
        playerId: UUID,
        reason: String,
        before: PlayerPermissionsData?,
        after: PlayerPermissionsData?,
        includeDirect: Boolean,
        includeGroups: Boolean,
    ) {
        if (before == null || after == null) {
            emitRefresh(playerId, reason)
            return
        }

        val directDeltas =
            if (includeDirect) {
                diffDirectPermissions(before.directPermissionGrants, after.directPermissionGrants)
            } else {
                emptyList()
            }
        val groupDeltas =
            if (includeGroups) {
                diffGroupMemberships(before.groupMemberships, after.groupMemberships)
            } else {
                emptyList()
            }
        val effectiveDeltas =
            diffEffectivePermissions(before.effectivePermissions, after.effectivePermissions)

        if (directDeltas.isEmpty() && groupDeltas.isEmpty() && effectiveDeltas.isEmpty()) {
            LOG.debugf("Permissions change event skipped (playerId=%s, reason=no_deltas)", playerId)
            return
        }

        val event =
            PermissionsChangeEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setPlayerId(playerId.toString())
                .setOccurredAt(nowTimestamp())
                .setReason(reason)
                .addAllDirectPermissionDeltas(directDeltas)
                .addAllGroupMembershipDeltas(groupDeltas)
                .addAllEffectivePermissionDeltas(effectiveDeltas)
                .build()

        publisher.publish(event)
        LOG.debugf(
            "Permissions change event published (playerId=%s, eventId=%s, reason=%s, refresh=%s)",
            playerId,
            event.eventId,
            reason,
            event.requiresFullRefresh,
        )
    }

    private fun diffDirectPermissions(
        before: Set<PlayerPermissionGrant>,
        after: Set<PlayerPermissionGrant>,
    ): List<PermissionDelta> {
        val beforeMap = before.associateBy { it.permission }
        val afterMap = after.associateBy { it.permission }
        val deltas = mutableListOf<PermissionDelta>()

        beforeMap.forEach { (permission, grant) ->
            val afterGrant = afterMap[permission]
            if (afterGrant == null) {
                deltas.add(
                    PermissionDelta.newBuilder()
                        .setAction(PermissionDelta.Action.REMOVE)
                        .setPermission(permission)
                        .build()
                )
            } else if (grant.expiresAt != afterGrant.expiresAt) {
                deltas.add(
                    PermissionDelta.newBuilder()
                        .setAction(PermissionDelta.Action.REMOVE)
                        .setPermission(permission)
                        .build()
                )
                deltas.add(
                    PermissionDelta.newBuilder()
                        .setAction(PermissionDelta.Action.ADD)
                        .setPermission(permission)
                        .apply { afterGrant.expiresAt?.let { setExpiresAt(toTimestamp(it)) } }
                        .build()
                )
            }
        }

        afterMap.forEach { (permission, grant) ->
            if (beforeMap.containsKey(permission)) return@forEach
            deltas.add(
                PermissionDelta.newBuilder()
                    .setAction(PermissionDelta.Action.ADD)
                    .setPermission(permission)
                    .apply { grant.expiresAt?.let { setExpiresAt(toTimestamp(it)) } }
                    .build()
            )
        }

        return deltas
    }

    private fun diffGroupMemberships(
        before: Set<PlayerGroupMembership>,
        after: Set<PlayerGroupMembership>,
    ): List<GroupMembershipDelta> {
        val beforeMap = before.associateBy { it.groupName }
        val afterMap = after.associateBy { it.groupName }
        val deltas = mutableListOf<GroupMembershipDelta>()

        beforeMap.forEach { (groupName, membership) ->
            val afterMembership = afterMap[groupName]
            if (afterMembership == null) {
                deltas.add(
                    GroupMembershipDelta.newBuilder()
                        .setAction(GroupMembershipDelta.Action.REMOVE)
                        .setGroupName(groupName)
                        .build()
                )
            } else if (membership.expiresAt != afterMembership.expiresAt) {
                deltas.add(
                    GroupMembershipDelta.newBuilder()
                        .setAction(GroupMembershipDelta.Action.REMOVE)
                        .setGroupName(groupName)
                        .build()
                )
                deltas.add(
                    GroupMembershipDelta.newBuilder()
                        .setAction(GroupMembershipDelta.Action.ADD)
                        .setGroupName(groupName)
                        .apply { afterMembership.expiresAt?.let { setExpiresAt(toTimestamp(it)) } }
                        .build()
                )
            }
        }

        afterMap.forEach { (groupName, membership) ->
            if (beforeMap.containsKey(groupName)) return@forEach
            deltas.add(
                GroupMembershipDelta.newBuilder()
                    .setAction(GroupMembershipDelta.Action.ADD)
                    .setGroupName(groupName)
                    .apply { membership.expiresAt?.let { setExpiresAt(toTimestamp(it)) } }
                    .build()
            )
        }

        return deltas
    }

    private fun diffEffectivePermissions(
        before: Set<String>,
        after: Set<String>,
    ): List<EffectivePermissionDelta> {
        val deltas = mutableListOf<EffectivePermissionDelta>()
        before.filterNot(after::contains).forEach { permission ->
            deltas.add(
                EffectivePermissionDelta.newBuilder()
                    .setAction(EffectivePermissionDelta.Action.REMOVE)
                    .setPermission(permission)
                    .build()
            )
        }
        after.filterNot(before::contains).forEach { permission ->
            deltas.add(
                EffectivePermissionDelta.newBuilder()
                    .setAction(EffectivePermissionDelta.Action.ADD)
                    .setPermission(permission)
                    .build()
            )
        }
        return deltas
    }

    private fun nowTimestamp(): Timestamp = toTimestamp(Instant.now())

    private fun toTimestamp(instant: Instant): Timestamp {
        return Timestamp.newBuilder().setSeconds(instant.epochSecond).setNanos(instant.nano).build()
    }

    companion object {
        private val LOG = Logger.getLogger(PermissionsChangeEmitter::class.java)
    }
}
