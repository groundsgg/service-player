package gg.grounds.rest

import gg.grounds.presence.PlayerHeartbeatService
import gg.grounds.presence.PlayerHeartbeatService.HeartbeatOutcome
import gg.grounds.presence.PresenceService
import gg.grounds.presence.PresenceService.LoginOutcome
import gg.grounds.presence.PresenceService.LogoutOutcome
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriBuilder
import java.util.UUID
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * Sessions: claiming one, releasing it, keeping it alive, and finding out who holds one.
 *
 * A session is the network's single answer to "is this player online". Only one can exist per
 * player, which is what makes the answer a fact rather than one proxy's opinion.
 */
@Path("/v1/players")
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Sessions",
    description = "Live presence: login, logout, heartbeats, and session lookup.",
)
@SecurityRequirement(name = "bearerAuth")
class PlayerSessionResource(
    private val presence: PresenceService,
    private val heartbeatService: PlayerHeartbeatService,
) {

    @POST
    @Path("/sessions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Claim a player's session",
        description =
            "Called by a proxy as the player joins. A second login for a player who already holds " +
                "a session is refused with 409 — unless the caller names a different proxy, which " +
                "is what a proxy-to-proxy transfer looks like from here and takes the session " +
                "over, or unless the existing session has gone quiet for longer than its TTL.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "201",
            description = "The session is now this proxy's. `Location` points at it.",
        ),
        APIResponse(
            responseCode = "400",
            description = "`playerId` is missing or not a UUID.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
        APIResponse(
            responseCode = "409",
            description =
                "The player already holds a live session elsewhere. Not an error condition — " +
                    "it is the answer to \"may this player join\", and the proxy disconnects them " +
                    "with an already-online message.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(
            responseCode = "503",
            description = "The session store could not be written.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
    )
    fun login(request: LoginRequest?): Response {
        val body = request ?: throw InvalidRequestException("a request body is required")
        return when (
            val outcome =
                presence.login(
                    playerId = body.playerId,
                    playerName = body.playerName,
                    proxyId = body.proxyId,
                    region = body.region,
                )
        ) {
            LoginOutcome.Accepted ->
                Response.created(
                        UriBuilder.fromResource(PlayerSessionResource::class.java)
                            .path("/{playerId}/session")
                            .build(body.playerId?.trim())
                    )
                    .build()
            LoginOutcome.AlreadyOnline ->
                problem(
                    409,
                    "Player already online",
                    "The player already holds a session on another proxy.",
                    "already_online",
                )
            LoginOutcome.InvalidPlayerId -> throw InvalidRequestException("playerId must be a UUID")
            is LoginOutcome.Failed ->
                problem(503, "Service unavailable", outcome.message, "store_unavailable")
        }
    }

    @DELETE
    @Path("/{playerId}/session")
    @Operation(
        summary = "Release a player's session",
        description =
            "Called by a proxy as the player leaves. Pass `proxyId`: the delete is then conditional " +
                "on that proxy still owning the session, so a logout that races a transfer cannot " +
                "delete the session the next proxy just created.",
    )
    @APIResponses(
        APIResponse(responseCode = "204", description = "The session is gone."),
        APIResponse(
            responseCode = "400",
            description = "`playerId` is not a UUID.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
        APIResponse(
            responseCode = "404",
            description =
                "No session to release — already logged out, expired, or now owned by a " +
                    "different proxy. Nothing for the caller to do about any of them.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(
            responseCode = "503",
            description = "The session store could not be written.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
    )
    fun logout(
        @PathParam("playerId") playerId: String,
        @Parameter(
            name = "proxyId",
            `in` = ParameterIn.QUERY,
            description =
                "The proxy releasing the session. Omitting it deletes unconditionally, which is " +
                    "what an older plugin does.",
        )
        @QueryParam("proxyId")
        proxyId: String?,
    ): Response =
        when (val outcome = presence.logout(playerId, proxyId)) {
            LogoutOutcome.Removed -> Response.noContent().build()
            LogoutOutcome.NotFound ->
                problem(404, "No session", "This player holds no session here.", "not_found")
            LogoutOutcome.InvalidPlayerId ->
                throw InvalidRequestException("playerId must be a UUID")
            is LogoutOutcome.Failed ->
                problem(503, "Service unavailable", outcome.message, "store_unavailable")
        }

    @POST
    @Path("/sessions/heartbeats")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Keep a batch of sessions alive",
        description =
            "One call per proxy per interval, naming every player it still holds. A session that " +
                "stops being named expires on its own — which is how a proxy that dies without " +
                "logging anyone out stops holding the network's players hostage.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The batch was applied.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = HeartbeatResponse::class),
                    )
                ],
        ),
        APIResponse(
            responseCode = "400",
            description =
                "The batch was empty, or an id was not a UUID. All-or-nothing on parsing: a " +
                    "partial success would hide the caller's bug.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(
            responseCode = "503",
            description = "The session store could not be written; nothing was touched.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
    )
    fun heartbeat(request: HeartbeatRequest?): Response {
        val playerIds = request?.playerIds ?: emptyList()
        return when (val outcome = heartbeatService.handleHeartbeatBatch(playerIds)) {
            is HeartbeatOutcome.Accepted ->
                Response.ok(HeartbeatResponse(updated = outcome.updated, missing = outcome.missing))
                    .build()
            is HeartbeatOutcome.Rejected ->
                throw InvalidRequestException(
                    when (outcome.reason) {
                        HeartbeatOutcome.Reason.INVALID_PLAYER_IDS -> "playerIds must be UUIDs"
                        HeartbeatOutcome.Reason.EMPTY -> "no player ids provided"
                    }
                )
            is HeartbeatOutcome.Failed ->
                problem(503, "Service unavailable", outcome.message, "store_unavailable")
        }
    }

    @GET
    @Path("/{playerId}/session")
    @Operation(
        summary = "Read a player's session",
        description =
            "Who and where a player is. A proxy asks this for someone who is not connected to it — " +
                "it has no other way to know they exist.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The live session.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = PlayerSessionResponse::class),
                    )
                ],
        ),
        APIResponse(
            responseCode = "400",
            description = "`playerId` is not a UUID.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
        APIResponse(
            responseCode = "404",
            description = "The player is not online.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
    )
    fun session(@PathParam("playerId") playerId: String): Response {
        requireUuid(playerId)
        val session =
            presence.findSession(playerId)
                ?: return problem(404, "Not online", "This player holds no session.", "not_found")
        return Response.ok(PlayerSessionResponse.from(session)).build()
    }

    @GET
    @Path("/sessions")
    @Operation(
        summary = "Find a session by player name",
        description =
            "The lookup behind `/msg <name>` and `/party invite <name>` when the target is on " +
                "another proxy. Matched case-insensitively: Minecraft names are unique, their " +
                "casing is not.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The live session for that name.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = PlayerSessionResponse::class),
                    )
                ],
        ),
        APIResponse(
            responseCode = "400",
            description = "`name` is missing or blank.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
        APIResponse(
            responseCode = "404",
            description = "Nobody is online under that name.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
    )
    fun sessionByName(
        @Parameter(
            name = "name",
            `in` = ParameterIn.QUERY,
            required = true,
            description = "The player name to resolve.",
        )
        @QueryParam("name")
        name: String?
    ): Response {
        if (name.isNullOrBlank()) {
            throw InvalidRequestException("name is required")
        }
        val session =
            presence.resolveName(name)
                ?: return problem(
                    404,
                    "Not online",
                    "Nobody is online under that name.",
                    "not_found",
                )
        return Response.ok(PlayerSessionResponse.from(session)).build()
    }

    @PUT
    @Path("/{playerId}/session/server")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Record the backend server a player moved to",
        description =
            "Follows a player across backend servers, so a session says where they actually are — " +
                "a party warp needs the target's server, and only the proxy holding them knows " +
                "when it changes.",
    )
    @APIResponses(
        APIResponse(responseCode = "204", description = "The session now points at that server."),
        APIResponse(
            responseCode = "400",
            description = "`playerId` is not a UUID, or `serverName` is missing.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
        APIResponse(
            responseCode = "404",
            description = "The player holds no session.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
    )
    fun updateServer(
        @PathParam("playerId") playerId: String,
        request: UpdateServerRequest?,
    ): Response {
        requireUuid(playerId)
        val serverName = request?.serverName
        if (serverName.isNullOrBlank()) {
            throw InvalidRequestException("serverName is required")
        }
        if (!presence.updateServer(playerId, serverName)) {
            return problem(404, "Not online", "This player holds no session.", "not_found")
        }
        return Response.noContent().build()
    }

    /**
     * A malformed id in the path is the caller's mistake, so it is a 400 rather than the 404 it
     * would otherwise decay into — "no such player" and "that is not a player id" are different
     * bugs to chase.
     */
    private fun requireUuid(playerId: String) {
        runCatching { UUID.fromString(playerId.trim()) }.getOrNull()
            ?: throw InvalidRequestException("playerId must be a UUID")
    }

    private fun problem(status: Int, title: String, detail: String, code: String): Response =
        Response.status(status)
            .type(ProblemDetails.PROBLEM_JSON)
            .entity(ProblemDetails(title = title, status = status, detail = detail, code = code))
            .build()
}
