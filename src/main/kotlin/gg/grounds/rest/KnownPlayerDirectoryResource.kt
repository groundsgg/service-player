package gg.grounds.rest

import gg.grounds.persistence.KnownPlayerDirectoryRepository
import gg.grounds.persistence.KnownPlayerName
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.ServiceUnavailableException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.UriInfo
import java.sql.SQLException
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import org.jboss.logging.Logger

data class KnownPlayerDirectoryResponse(val players: List<KnownPlayerName>)

@Path("/v1/players/names/known")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Names")
@SecurityRequirement(name = "bearerAuth")
class KnownPlayerDirectoryResource(private val directory: KnownPlayerDirectoryRepository) {
    @GET
    @Operation(
        summary = "Search known player identities, including offline profiles",
        description =
            "Supply either a prefix of at least two characters or an exact name. Prefix replies are capped at 25. Exact replies contain at most two identities so callers can reject ambiguous names. No online presence is implied.",
    )
    fun known(
        @QueryParam("prefix") prefix: String?,
        @QueryParam("name") name: String?,
        @QueryParam("limit") limit: Int?,
        @Context uriInfo: UriInfo,
    ): KnownPlayerDirectoryResponse {
        val hasPrefix = uriInfo.queryParameters.containsKey("prefix")
        val hasName = uriInfo.queryParameters.containsKey("name")
        if (hasPrefix == hasName) throw BadRequestException("Supply one selector")
        return try {
            val matches =
                if (hasName) {
                    val exact = name?.trim().orEmpty()
                    if (exact.length !in 1..64 || exact.any(Char::isWhitespace)) {
                        throw BadRequestException("Invalid player name")
                    }
                    directory.resolve(exact)
                } else {
                    val search = prefix?.trim().orEmpty()
                    if (search.length > 64) throw BadRequestException("Prefix is too long")
                    if (search.length < 2) emptyList()
                    else directory.search(search, limit?.takeIf { it > 0 }?.coerceAtMost(25) ?: 25)
                }
            KnownPlayerDirectoryResponse(matches)
        } catch (failure: SQLException) {
            LOG.warn("Known-player directory read failed", failure)
            throw ServiceUnavailableException("Player directory is unavailable")
        }
    }

    private companion object {
        val LOG: Logger = Logger.getLogger(KnownPlayerDirectoryResource::class.java)
    }
}
