package gg.grounds.rest

import gg.grounds.persistence.PlayerNameRepository
import gg.grounds.persistence.PlayerNameRepository.SettingRead
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.ServiceUnavailableException
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
 * Who may see a player's public web profile beyond their name and head. Read by service-account for
 * every profile it serves and written from the web account page, never on a join, so unlike locale
 * and region it has no settings cache.
 */
@Path("/v1/players/{playerId}/profile-visibility")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Profile visibility", description = "Per-player web profile visibility.")
@SecurityRequirement(name = "bearerAuth")
class PlayerProfileVisibilityResource(private val names: PlayerNameRepository) {

    @GET
    @Operation(
        summary = "Read a player's profile visibility",
        description =
            "Null when the player has never chosen one; readers treat that as `public`. A read " +
                "failure is a 503, never null, so a private profile is not shown by accident.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The stored visibility, or null.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = ProfileVisibilityResponse::class),
                    )
                ],
        ),
        APIResponse(responseCode = "400", description = "`playerId` is not a UUID."),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(responseCode = "503", description = "The store could not be read."),
    )
    fun get(@PathParam("playerId") playerId: String): ProfileVisibilityResponse =
        when (val read = names.readProfileVisibility(requireUuid(playerId))) {
            is SettingRead.Value -> ProfileVisibilityResponse(read.value)
            SettingRead.Failed ->
                throw ServiceUnavailableException("Profile visibility is unavailable")
        }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Store or clear a player's profile visibility",
        description = "One of `public`, `friends`, `private`; null clears it back to the default.",
    )
    @APIResponses(
        APIResponse(responseCode = "204", description = "The visibility was stored or cleared."),
        APIResponse(
            responseCode = "400",
            description = "`playerId` is not a UUID, or `visibility` is not one of the choices.",
        ),
        APIResponse(
            responseCode = "404",
            description = "This service has never seen the player, so there is no row to write.",
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
    )
    fun put(
        @PathParam("playerId") playerId: String,
        request: SetProfileVisibilityRequest?,
    ): Response {
        val id = requireUuid(playerId)
        val visibility = request?.visibility
        if (visibility != null && visibility !in CHOICES) {
            throw InvalidRequestException("visibility must be one of ${CHOICES.joinToString()}")
        }
        if (!names.setProfileVisibility(id, visibility)) {
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

    private fun requireUuid(playerId: String): UUID =
        runCatching { UUID.fromString(playerId.trim()) }.getOrNull()
            ?: throw InvalidRequestException("playerId must be a UUID")

    private companion object {
        val CHOICES = listOf("public", "friends", "private")
    }
}

@Schema(name = "ProfileVisibilityResponse")
data class ProfileVisibilityResponse(
    @get:Schema(
        description = "public, friends or private. Null when never chosen (read as public).",
        enumeration = ["public", "friends", "private"],
        nullable = true,
    )
    val visibility: String?
)

@Schema(name = "SetProfileVisibilityRequest")
data class SetProfileVisibilityRequest(
    @get:Schema(
        description = "public, friends or private; null clears it.",
        enumeration = ["public", "friends", "private"],
        nullable = true,
    )
    val visibility: String? = null
)
