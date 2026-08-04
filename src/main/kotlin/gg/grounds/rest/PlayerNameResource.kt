package gg.grounds.rest

import gg.grounds.presence.PresenceService
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
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
 * Names, which outlive sessions.
 *
 * A session row is deleted at logout, so a name read from it only exists while the player is
 * connected. Anything that outlasts a session — a leaderboard, a match history, a ban list — would
 * otherwise be stuck showing raw UUIDs. These read a separate index written at every login and
 * never deleted.
 */
@Path("/v1/players/names")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Names", description = "The durable id-to-name index and tab-complete.")
@SecurityRequirement(name = "bearerAuth")
class PlayerNameResource(private val presence: PresenceService) {

    @GET
    @Operation(
        summary = "Look up names for player ids",
        description =
            "Batched on purpose: a leaderboard page needs ten names at once, and ten round trips " +
                "to render one screen is not a thing worth building. Players need not be online.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description =
                "The names that are known. Ids this service has never seen are absent from the " +
                    "map rather than mapped to a placeholder — including when none are known, " +
                    "which is an empty map and not a 404.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = NameLookupResponse::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
    )
    fun lookup(
        @Parameter(
            name = "playerId",
            `in` = ParameterIn.QUERY,
            description =
                "Repeat once per player. Ids beyond the server's cap of " +
                    "${PresenceService.MAX_LOOKUP_IDS} are ignored, as are malformed ones.",
        )
        @QueryParam("playerId")
        playerIds: List<String>?
    ): NameLookupResponse = NameLookupResponse.from(presence.lookupNames(playerIds ?: emptyList()))

    @GET
    @Path("/suggestions")
    @Operation(
        summary = "Prefix-search online player names",
        description =
            "Tab-complete. Deliberately not \"list everyone online\": clients call this per " +
                "keystroke, and at 10k players a full roster would be a large response sent " +
                "thousands of times a second. Only players with a live session are suggested, and " +
                "a blank prefix returns nothing rather than everything.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "Matching names, capped by the server.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = NameSuggestionsResponse::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
    )
    fun suggestions(
        @Parameter(
            name = "prefix",
            `in` = ParameterIn.QUERY,
            description = "What the player has typed so far. Blank or absent returns nothing.",
        )
        @QueryParam("prefix")
        prefix: String?,
        @Parameter(
            name = "limit",
            `in` = ParameterIn.QUERY,
            description = "How many to return. Clamped to the server's maximum; absent uses it.",
        )
        @QueryParam("limit")
        limit: Int?,
    ): NameSuggestionsResponse = NameSuggestionsResponse(presence.suggestNames(prefix, limit ?: 0))
}
