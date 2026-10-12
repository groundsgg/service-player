package gg.grounds.rest

import gg.grounds.presence.PresenceService
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * The region a player wants to play in by default.
 *
 * Stored against the durable player row rather than the session, so it survives logout. The value
 * is a region-catalogue code; only its shape is checked here, because the catalogue itself lives in
 * the proxies' configuration.
 */
@Path("/v1/players/{playerId}/region")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Region", description = "Per-player preferred region.")
@SecurityRequirement(name = "bearerAuth")
class PlayerRegionResource(private val presence: PresenceService) {

    @GET
    @Operation(
        summary = "Read a player's preferred region",
        description =
            "A player who has never chosen one reads as null, and the caller falls back to its " +
                "own default — that is a normal answer, not a missing resource.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The stored region code, or null when the player has never chosen one.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = RegionResponse::class),
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
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
    )
    fun get(@PathParam("playerId") playerId: String): RegionResponse {
        requireUuid(playerId)
        return RegionResponse(presence.getPreferredRegion(playerId))
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Store or clear a player's preferred region",
        description =
            "A null or blank `region` clears the preference. Only the code's shape " +
                "(`[a-z0-9-]{1,32}`) is checked — the caller validates it against the region " +
                "catalogue it actually serves.",
    )
    @APIResponses(
        APIResponse(responseCode = "204", description = "The preference was stored or cleared."),
        APIResponse(
            responseCode = "400",
            description = "`playerId` is not a UUID, or `region` is not a well-formed code.",
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
                "This service has never seen the player. The preference lives on the durable " +
                    "player row, which is written at login, so there is nothing to write against.",
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
    fun put(@PathParam("playerId") playerId: String, request: SetRegionRequest?): Response {
        requireUuid(playerId)
        val region = request?.region?.trim()?.takeIf { it.isNotEmpty() }
        if (region != null && !REGION_CODE.matches(region)) {
            throw InvalidRequestException("region must match [a-z0-9-]{1,32}")
        }
        if (!presence.setPreferredRegion(playerId, region)) {
            return Response.status(404)
                .type(ProblemDetails.PROBLEM_JSON)
                .entity(
                    ProblemDetails(
                        title = "Unknown player",
                        status = 404,
                        detail = "This service has never seen this player.",
                        code = "not_found",
                    )
                )
                .build()
        }
        return Response.noContent().build()
    }

    private fun requireUuid(playerId: String) {
        runCatching { UUID.fromString(playerId.trim()) }.getOrNull()
            ?: throw InvalidRequestException("playerId must be a UUID")
    }

    private companion object {
        val REGION_CODE = Regex("[a-z0-9-]{1,32}")
    }
}
