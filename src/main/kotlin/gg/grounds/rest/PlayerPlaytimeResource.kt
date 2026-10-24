package gg.grounds.rest

import gg.grounds.presence.PresenceService
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import java.util.UUID
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * How long a player has been on the network, accumulated from their sessions: every server change
 * and every logout credits the time since the previous one.
 */
@Path("/v1/players/{playerId}/playtime")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Playtime", description = "Accumulated online and game time per player.")
@SecurityRequirement(name = "bearerAuth")
class PlayerPlaytimeResource(private val presence: PresenceService) {

    @GET
    @Operation(
        summary = "Read a player's playtime",
        description =
            "Includes the segment that is still running, so the number ticks up while the player " +
                "is online. A player who has never been seen reads as zero, not as a missing " +
                "resource.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "Total and game time in milliseconds.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = PlaytimeResponse::class),
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
        APIResponse(
            responseCode = "503",
            description = "The store could not be read. Deliberately not zero.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
    )
    fun get(@PathParam("playerId") playerId: String): PlaytimeResponse {
        val id =
            runCatching { UUID.fromString(playerId.trim()) }.getOrNull()
                ?: throw InvalidRequestException("playerId must be a UUID")
        return PlaytimeResponse.from(presence.playtime(id))
    }
}
