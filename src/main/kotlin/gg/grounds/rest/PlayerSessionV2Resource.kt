package gg.grounds.rest

import gg.grounds.presence.PlayerHeartbeatService
import gg.grounds.presence.PlayerHeartbeatService.HeartbeatOutcome
import gg.grounds.presence.PresenceService
import gg.grounds.presence.PresenceService.ExactSessionClaimOutcome
import gg.grounds.presence.PresenceService.HandoffIssueOutcome
import gg.grounds.presence.PresenceService.LogoutOutcome
import io.micrometer.core.annotation.Timed
import io.micrometer.core.instrument.MeterRegistry
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement

@Path("/v2/players")
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "bearerAuth")
class PlayerSessionV2Resource(
    private val presence: PresenceService,
    private val heartbeatService: PlayerHeartbeatService,
) {
    @Inject lateinit var metrics: MeterRegistry

    @POST
    @Path("/sessions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Claim an exact player connection session",
        description =
            "The authenticated Minecraft profile UUID and a per-connection session UUID identify " +
                "the lease. A proxy ID change cannot replace an active session.",
    )
    fun claim(request: ExactSessionClaimRequest?): Response {
        val body = request ?: throw InvalidRequestException("a request body is required")
        return when (
            val result =
                presence.claimExactSession(
                    body.playerId,
                    body.sessionId,
                    body.playerName,
                    body.proxyId,
                    body.region,
                )
        ) {
            is ExactSessionClaimOutcome.Accepted ->
                Response.status(Response.Status.CREATED)
                    .entity(
                        ExactSessionClaimResponse(
                            result.playerId.toString(),
                            result.sessionId.toString(),
                            result.leaseExpiresAt,
                        )
                    )
                    .build()
            ExactSessionClaimOutcome.AlreadyOnline ->
                problem(
                    409,
                    "Player already online",
                    "The profile already holds an active session.",
                    "already_online",
                )
            ExactSessionClaimOutcome.InvalidId ->
                throw InvalidRequestException("playerId and sessionId must be UUIDs")
            is ExactSessionClaimOutcome.Failed ->
                problem(503, "Service unavailable", result.message, "store_unavailable")
        }
    }

    @POST
    @Path("/{playerId}/session/transfer")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Issue a short-lived one-use session handoff token",
        description = "Only the active exact session can authorize a transfer of its profile.",
    )
    fun issueHandoff(
        @PathParam("playerId") playerId: String,
        request: ExactSessionHandoffIssueRequest?,
    ): Response {
        val body = request ?: throw InvalidRequestException("a request body is required")
        return when (
            val result =
                presence.issueSessionHandoff(playerId, body.sessionId, body.boundedAdmission)
        ) {
            is HandoffIssueOutcome.Issued ->
                Response.status(Response.Status.CREATED)
                    .entity(ExactSessionHandoffIssueResponse(result.token, result.expiresAt))
                    .build()
            HandoffIssueOutcome.NoActiveSession ->
                problem(
                    409,
                    "No active session",
                    "This connection cannot authorize a transfer.",
                    "not_found",
                )
            HandoffIssueOutcome.InvalidId ->
                throw InvalidRequestException("playerId and sessionId must be UUIDs")
            is HandoffIssueOutcome.Failed ->
                problem(503, "Service unavailable", result.message, "store_unavailable")
        }
    }

    @POST
    @Path("/sessions/transfer/admission/abort")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Fence an aborted admission and release only its exact destination session"
    )
    fun abortTransfer(request: TransferAdmissionRequest?): Response {
        val body = request ?: throw InvalidRequestException("a request body is required")
        return when (
            presence.abortTransferAdmission(
                body.playerId,
                body.handoffToken,
                body.connectionId,
                body.destinationProxyId,
            )
        ) {
            gg.grounds.persistence.TransferAdmissionRepository.AbortResult.Closed ->
                Response.noContent().build()
            gg.grounds.persistence.TransferAdmissionRepository.AbortResult.Invalid ->
                problem(
                    409,
                    "Transfer unavailable",
                    "Invalid admission identity.",
                    "handoff_unavailable",
                )
            gg.grounds.persistence.TransferAdmissionRepository.AbortResult.Error ->
                problem(
                    503,
                    "Service unavailable",
                    "Unable to close transfer.",
                    "store_unavailable",
                )
        }
    }

    @POST
    @Path("/sessions/transfer/admission")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Reserve a handoff for one destination connection before authentication")
    @Timed(value = "player.transfer.admission.duration")
    fun admitTransfer(request: TransferAdmissionRequest?): Response {
        val body = request ?: throw InvalidRequestException("a request body is required")
        val started = System.nanoTime()
        val response =
            when (
                val result =
                    presence.reserveTransferAdmission(
                        body.playerId,
                        body.handoffToken,
                        body.connectionId,
                        body.destinationProxyId,
                    )
            ) {
                is gg.grounds.persistence.TransferAdmissionRepository.Result.Admitted ->
                    Response.status(201)
                        .entity(
                            TransferAdmissionResponse(
                                body.playerId!!,
                                body.connectionId!!,
                                body.destinationProxyId!!,
                                result.expiresAt,
                            )
                        )
                        .build()
                gg.grounds.persistence.TransferAdmissionRepository.Result.Unavailable ->
                    problem(
                        409,
                        "Transfer unavailable",
                        "This connection cannot reserve this transfer.",
                        "handoff_unavailable",
                    )
                gg.grounds.persistence.TransferAdmissionRepository.Result.Error ->
                    problem(
                        503,
                        "Service unavailable",
                        "Unable to reserve transfer.",
                        "store_unavailable",
                    )
            }
        metrics
            .counter(
                "player.transfer.admission",
                "result",
                when (response.status) {
                    201 -> "admitted"
                    409 -> "rejected"
                    else -> "unavailable"
                },
            )
            .increment()
        metrics
            .timer("player.transfer.admission.wait")
            .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS)
        return response
    }

    @POST
    @Path("/sessions/transfer")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Consume a one-use profile-bound session handoff",
        description =
            "A valid token atomically fences the source session and admits the destination.",
    )
    fun consumeHandoff(request: ExactSessionHandoffConsumeRequest?): Response {
        val body = request ?: throw InvalidRequestException("a request body is required")
        return when (
            val result =
                presence.consumeSessionHandoff(
                    body.playerId,
                    body.sessionId,
                    body.handoffToken,
                    body.playerName,
                    body.proxyId,
                    body.region,
                    body.connectionId,
                )
        ) {
            is ExactSessionClaimOutcome.Accepted ->
                Response.status(Response.Status.CREATED)
                    .entity(
                        ExactSessionClaimResponse(
                            result.playerId.toString(),
                            result.sessionId.toString(),
                            result.leaseExpiresAt,
                        )
                    )
                    .build()
            ExactSessionClaimOutcome.AlreadyOnline ->
                problem(
                    409,
                    "Transfer unavailable",
                    "The handoff token is invalid, expired, or no longer matches this profile session.",
                    "handoff_unavailable",
                )
            ExactSessionClaimOutcome.InvalidId ->
                throw InvalidRequestException("playerId, sessionId, or handoffToken is invalid")
            is ExactSessionClaimOutcome.Failed ->
                problem(503, "Service unavailable", result.message, "store_unavailable")
        }
    }

    @POST
    @Path("/sessions/heartbeats")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Renew exact connection leases",
        description = "Each entry must match both the Minecraft profile UUID and session UUID.",
    )
    fun heartbeat(request: ExactSessionHeartbeatRequest?): Response {
        val entries = request?.sessions ?: emptyList()
        val outcome =
            heartbeatService.handleExactSessionHeartbeatBatch(
                entries.map {
                    PlayerHeartbeatService.SessionIdentity(
                        playerId = it.playerId.orEmpty(),
                        sessionId = it.sessionId.orEmpty(),
                    )
                }
            )
        return when (outcome) {
            is HeartbeatOutcome.Accepted ->
                Response.ok(HeartbeatResponse(outcome.updated, outcome.missing)).build()
            is HeartbeatOutcome.Rejected ->
                throw InvalidRequestException(
                    if (outcome.reason == HeartbeatOutcome.Reason.EMPTY) {
                        "no session identities provided"
                    } else {
                        "playerId and sessionId must be UUIDs"
                    }
                )
            is HeartbeatOutcome.Failed ->
                problem(503, "Service unavailable", outcome.message, "store_unavailable")
        }
    }

    @DELETE
    @Path("/{playerId}/session")
    @Operation(
        summary = "Release one exact connection session",
        description = "A delayed disconnect cannot remove a later session for the same profile.",
    )
    fun logout(
        @PathParam("playerId") playerId: String,
        @QueryParam("sessionId") sessionId: String?,
    ): Response =
        when (val outcome = presence.logoutExactSession(playerId, sessionId)) {
            LogoutOutcome.Removed -> Response.noContent().build()
            LogoutOutcome.NotFound ->
                problem(
                    404,
                    "No session",
                    "This connection no longer owns the profile.",
                    "not_found",
                )
            LogoutOutcome.InvalidPlayerId ->
                throw InvalidRequestException("playerId and sessionId must be UUIDs")
            is LogoutOutcome.Failed ->
                problem(503, "Service unavailable", outcome.message, "store_unavailable")
        }

    @PUT
    @Path("/{playerId}/session/server")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Set the backend server for an exact connection session",
        description = "A stale connection cannot overwrite its successor's server location.",
    )
    fun updateServer(
        @PathParam("playerId") playerId: String,
        request: ExactSessionServerRequest?,
    ): Response {
        val body = request ?: throw InvalidRequestException("a request body is required")
        if (presence.updateServerExactSession(playerId, body.sessionId, body.serverName)) {
            return Response.noContent().build()
        }
        if (
            runCatching { java.util.UUID.fromString(playerId) }.isFailure ||
                runCatching { java.util.UUID.fromString(body.sessionId) }.isFailure
        ) {
            throw InvalidRequestException("playerId and sessionId must be UUIDs")
        }
        return problem(
            404,
            "No session",
            "This connection no longer owns the profile.",
            "not_found",
        )
    }

    private fun problem(status: Int, title: String, detail: String, code: String): Response =
        Response.status(status)
            .type(ProblemDetails.PROBLEM_JSON)
            .entity(ProblemDetails(title, status, detail, code))
            .build()
}
