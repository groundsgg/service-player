package gg.grounds.rest

import gg.grounds.persistence.PlayerNameRepository
import gg.grounds.settings.SettingsInvalidations
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.ServiceUnavailableException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.sql.SQLException
import java.util.UUID
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement
import org.eclipse.microprofile.openapi.annotations.tags.Tag

@Path("/v1/players/{playerId}/sidebar")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Scoreboard", description = "Durable per-player sidebar visibility.")
@SecurityRequirement(name = "bearerAuth")
class PlayerSidebarResource(
    private val names: PlayerNameRepository,
    private val invalidations: SettingsInvalidations,
) {
    @GET
    @Operation(
        summary = "Read scoreboard visibility",
        description = "Visible by default; unavailable reads are 503.",
    )
    fun get(@PathParam("playerId") playerId: String): SidebarSetting {
        val id = requireUuid(playerId)
        return storage { SidebarSetting(names.sidebarEnabled(id)) }
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
        summary = "Show or hide the scoreboard",
        description =
            "Stored on the durable player row; successful writes notify every backend independently of the old player connection.",
    )
    fun put(@PathParam("playerId") playerId: String, request: SidebarSetting?): Response {
        val id = requireUuid(playerId)
        val enabled = request?.enabled ?: throw InvalidRequestException("enabled is required")
        if (!storage { names.setSidebarEnabled(id, enabled) }) {
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
        invalidations.publish(id, force = true)
        return Response.ok(SidebarSetting(enabled)).build()
    }

    private fun requireUuid(value: String): UUID =
        runCatching { UUID.fromString(value.trim()) }.getOrNull()
            ?: throw InvalidRequestException("playerId must be a UUID")

    private fun <T> storage(read: () -> T): T =
        try {
            read()
        } catch (failure: SQLException) {
            throw ServiceUnavailableException(Response.status(503).build(), failure)
        }
}

@Schema(name = "SidebarSetting")
data class SidebarSetting(
    @get:Schema(description = "Whether the player's scoreboard is visible.", required = true)
    val enabled: Boolean?
)
