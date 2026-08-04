package gg.grounds.rest

import gg.grounds.presence.PresenceService
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement
import org.eclipse.microprofile.openapi.annotations.tags.Tag

/**
 * How many players are online, grouped two ways.
 *
 * A proxy can only count the players connected to itself, so with two proxies in front of one lobby
 * each reports half of it. Anything that states a number about the network has to read it here.
 *
 * Both are aggregates rather than rosters, so the response stays small at any player count.
 */
@Path("/v1/players/counts")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Counts", description = "Network-wide player counts by backend server and by proxy.")
@SecurityRequirement(name = "bearerAuth")
class PlayerCountResource(private val presence: PresenceService) {

    @GET
    @Path("/servers")
    @Operation(
        summary = "Players per backend server",
        description = "Which backend servers are busy — what a server selector or `/agones` asks.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "One entry per occupied server, plus the network total.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = ServerCountsResponse::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(
            responseCode = "503",
            description =
                "The session store could not be read. Deliberately not an empty answer: zero " +
                    "reads as \"nobody is online anywhere\", which callers will render.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
    )
    fun byServer(): ServerCountsResponse =
        ServerCountsResponse.from(presence.countPlayersByServer())

    @GET
    @Path("/proxies")
    @Operation(
        summary = "Players per proxy and region",
        description =
            "How the network is spread across proxies and where those proxies are — what `/online` " +
                "asks. A separate call from the server counts rather than another field on their " +
                "response: the two group the same players along different axes, and no caller " +
                "wants both.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "One entry per occupied proxy, plus the network total.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = ProxyCountsResponse::class),
                    )
                ],
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(
            responseCode = "503",
            description = "The session store could not be read.",
            content =
                [
                    Content(
                        mediaType = ProblemDetails.PROBLEM_JSON,
                        schema = Schema(implementation = ProblemDetails::class),
                    )
                ],
        ),
    )
    fun byProxy(): ProxyCountsResponse = ProxyCountsResponse.from(presence.countPlayersByProxy())
}
