package gg.grounds.rest

import com.fasterxml.jackson.databind.JsonNode
import gg.grounds.persistence.PlayerLoadoutRepository
import io.smallrye.common.annotation.Blocking
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
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
 * A player's customised kit for one game mode.
 *
 * Stored against the player rather than the game server, because the game server is an Agones pod
 * with no database that lives for a handful of matches. A player who arranges their potion kit once
 * should find it arranged the next time they queue, on whichever server the matchmaker gives them.
 *
 * The body is opaque here: it is the game's own item encoding, and this service stores and returns
 * it without interpreting it. That is safe because the game server validates every loadout against
 * the kit before handing a player any items, so nothing stored here is trusted as items — the worst
 * a corrupted row can do is fail that check and give the player the stock kit.
 */
@Path("/v1/players/{playerId}/loadouts/{kitId}")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Loadouts", description = "Per-player, per-kit inventory arrangements.")
@SecurityRequirement(name = "bearerAuth")
class PlayerLoadoutResource(private val loadouts: PlayerLoadoutRepository) {

    @GET
    @Blocking
    @Operation(
        summary = "Read a player's arrangement of a kit",
        description =
            "Read by the game server on the way into a match. A player who has never customised " +
                "this kit reads as 404, which is a normal answer — the caller uses the stock kit.",
    )
    @APIResponses(
        APIResponse(
            responseCode = "200",
            description = "The stored arrangement.",
            content =
                [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = Schema(implementation = LoadoutResponse::class),
                    )
                ],
        ),
        APIResponse(responseCode = "400", description = "`playerId` or `kitId` is malformed."),
        APIResponse(responseCode = "404", description = "This kit has never been customised."),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(responseCode = "503", description = "The store could not be read."),
    )
    fun get(@PathParam("playerId") playerId: String, @PathParam("kitId") kitId: String): Response {
        val player = requireUuid(playerId)
        val kit = requireKitId(kitId)

        return when (val result = loadouts.find(player, kit)) {
            is PlayerLoadoutRepository.Result.Found ->
                Response.ok(LoadoutResponse(Json.parse(result.slots))).build()
            PlayerLoadoutRepository.Result.Absent ->
                problem(404, "No loadout", "This kit has not been customised.", "not_found")
            // Deliberately not answered as 404: "the store is down" and "you have
            // no loadout" would otherwise be the same answer, and the caller
            // silently handing out stock kits is exactly the outage nobody
            // notices.
            PlayerLoadoutRepository.Result.Unavailable ->
                problem(
                    503,
                    "Store unavailable",
                    "The loadout could not be read.",
                    "store_unavailable",
                )
        }
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Blocking
    @Operation(
        summary = "Store a player's arrangement of a kit",
        description =
            "Written when a player saves in the in-game editor. Idempotent on (player, kit): " +
                "saving twice leaves one arrangement, the second one.",
    )
    @APIResponses(
        APIResponse(responseCode = "204", description = "The arrangement was stored."),
        APIResponse(
            responseCode = "400",
            description = "`playerId`/`kitId` is malformed, or `slots` is missing or too large.",
        ),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(responseCode = "503", description = "The store could not be written."),
    )
    fun put(
        @PathParam("playerId") playerId: String,
        @PathParam("kitId") kitId: String,
        request: SaveLoadoutRequest?,
    ): Response {
        val player = requireUuid(playerId)
        val kit = requireKitId(kitId)

        val slots = request?.slots ?: throw InvalidRequestException("slots is required")
        if (!slots.isObject) throw InvalidRequestException("slots must be a JSON object")

        // A loadout is at most 41 item stacks. The cap is not a tuning knob, it
        // is a bound on what an authenticated workload can push into the row —
        // without it a single PUT decides how large this table gets.
        val encoded = slots.toString()
        if (encoded.length > MAX_SLOTS_BYTES) {
            throw InvalidRequestException("slots is larger than $MAX_SLOTS_BYTES bytes")
        }

        if (!loadouts.save(player, kit, encoded)) {
            return problem(
                503,
                "Store unavailable",
                "The loadout could not be stored.",
                "store_unavailable",
            )
        }
        return Response.noContent().build()
    }

    @DELETE
    @Blocking
    @Operation(
        summary = "Forget a player's arrangement of a kit",
        description =
            "Puts the player back on the stock kit. Deleting an arrangement that is not there " +
                "succeeds — the caller asked for 'no customisation' and that is the result.",
    )
    @APIResponses(
        APIResponse(responseCode = "204", description = "There is no stored arrangement."),
        APIResponse(responseCode = "400", description = "`playerId` or `kitId` is malformed."),
        APIResponse(responseCode = "401", description = "Authentication is missing or invalid."),
        APIResponse(responseCode = "503", description = "The store could not be written."),
    )
    fun delete(
        @PathParam("playerId") playerId: String,
        @PathParam("kitId") kitId: String,
    ): Response {
        val player = requireUuid(playerId)
        val kit = requireKitId(kitId)

        if (!loadouts.delete(player, kit)) {
            return problem(
                503,
                "Store unavailable",
                "The loadout could not be removed.",
                "store_unavailable",
            )
        }
        return Response.noContent().build()
    }

    private fun problem(status: Int, title: String, detail: String, code: String): Response =
        Response.status(status)
            .type(ProblemDetails.PROBLEM_JSON)
            .entity(ProblemDetails(title = title, status = status, detail = detail, code = code))
            .build()

    private fun requireUuid(playerId: String): UUID =
        runCatching { UUID.fromString(playerId.trim()) }.getOrNull()
            ?: throw InvalidRequestException("playerId must be a UUID")

    /**
     * The kit id is the game's, not ours, so it is not validated against a catalogue — only against
     * being a sane key. It is half of a primary key written by an authenticated workload, and
     * "anything at all, any length" is not a key shape worth storing.
     */
    private fun requireKitId(kitId: String): String {
        val trimmed = kitId.trim()
        if (!KIT_ID.matches(trimmed)) {
            throw InvalidRequestException("kitId must match ${KIT_ID.pattern}")
        }
        return trimmed
    }

    private companion object {
        val KIT_ID = Regex("^[a-z0-9_]{1,64}$")
        const val MAX_SLOTS_BYTES = 64 * 1024
    }
}

/** Parsing the stored blob back out. It went in as JSON, so it comes back as JSON. */
private object Json {
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    fun parse(raw: String): JsonNode =
        runCatching { mapper.readTree(raw) }.getOrDefault(mapper.createObjectNode())
}

@Schema(name = "Loadout", description = "A stored inventory arrangement for one kit.")
data class LoadoutResponse(
    @get:Schema(
        description =
            "The arrangement, keyed by inventory slot. Opaque to this service — the game " +
                "server owns the encoding and validates it before use."
    )
    val slots: JsonNode
)

@Schema(name = "SaveLoadoutRequest", description = "An inventory arrangement to store.")
data class SaveLoadoutRequest(
    @get:Schema(description = "The arrangement, keyed by inventory slot. Must be an object.")
    val slots: JsonNode?
)
