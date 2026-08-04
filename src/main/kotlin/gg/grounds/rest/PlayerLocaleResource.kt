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
 * The language a player wants to be spoken to in.
 *
 * Stored against the durable player row rather than the session, so it survives logout: a player
 * who picked German once should not have to pick it again next time they join.
 */
@Path("/v1/players/{playerId}/locale")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Locale", description = "Per-player interface language.")
@SecurityRequirement(name = "bearerAuth")
class PlayerLocaleResource(private val presence: PresenceService) {

    @GET
    @Operation(
        summary = "Read a player's language preference",
        description =
            "Read on join to seed the proxy's cache. A player who has never chosen one reads as " +
                "null, and the caller falls back to the locale the client announces — that is a " +
                "normal answer, not a missing resource.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The stored tag, or null when the player has never chosen one.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = LocaleResponse::class),
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
    fun get(@PathParam("playerId") playerId: String): LocaleResponse {
        requireUuid(playerId)
        return LocaleResponse(presence.getLocale(playerId))
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Store or clear a player's language preference",
        description =
            "Written by the in-game `/lang` command. A null or blank `locale` clears the " +
                "preference. The value is stored verbatim — the caller validates it against the " +
                "languages it actually ships.",
    )
    @APIResponses(
        APIResponse(responseCode = "204", description = "The preference was stored or cleared."),
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
    fun put(@PathParam("playerId") playerId: String, request: SetLocaleRequest?): Response {
        requireUuid(playerId)
        if (!presence.setLocale(playerId, request?.locale)) {
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
}
