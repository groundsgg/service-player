package gg.grounds.rest

import com.fasterxml.jackson.databind.JsonNode
import gg.grounds.persistence.PlayerProfileRepository
import gg.grounds.profile.PlayerProfile
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.ServiceUnavailableException
import jakarta.ws.rs.core.MediaType
import java.sql.SQLException
import java.util.UUID
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement
import org.eclipse.microprofile.openapi.annotations.tags.Tag

@Path("/v1/players/{playerId}/profile")
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Profile details")
class PlayerProfileResource(private val profiles: PlayerProfileRepository) {
    @GET
    fun get(@PathParam("playerId") playerId: String): PlayerProfile = store {
        profiles.read(id(playerId))
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    fun put(@PathParam("playerId") playerId: String, body: JsonNode?): PlayerProfile {
        val player = id(playerId)
        val profile = PlayerProfile.parse(body)
        return store { profiles.save(player, profile) }
    }

    private fun id(value: String): UUID =
        runCatching { UUID.fromString(value) }.getOrNull()
            ?: throw InvalidRequestException("playerId must be a UUID")

    private fun <T> store(operation: () -> T): T =
        try {
            operation()
        } catch (error: SQLException) {
            throw ServiceUnavailableException(
                jakarta.ws.rs.core.Response.status(503).build(),
                error,
            )
        }
}
