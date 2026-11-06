package gg.grounds.profile

import com.fasterxml.jackson.databind.JsonNode
import gg.grounds.rest.InvalidRequestException
import org.eclipse.microprofile.openapi.annotations.media.Schema

@Schema(name = "PlayerProfileDetails")
data class PlayerProfile(
    val bio: String = "",
    val pronouns: String = "",
    val favouriteMode: String? = null,
    val showFavouriteMode: Boolean = false,
    val favouriteModes: List<String> = listOfNotNull(favouriteMode),
) {
    companion object {
        private val fields = setOf("bio", "pronouns", "favouriteMode", "showFavouriteMode")
        private val modes =
            setOf(
                "duel",
                "bedwars",
                "survivalgames",
                "keystone",
                "getdown",
                "skywars",
                "battlegrounds",
                "stickfight",
                "bridging",
            )

        fun parse(body: JsonNode?): PlayerProfile {
            if (
                body == null ||
                    !body.isObject ||
                    body.fieldNames().asSequence().toSet() !in
                        setOf(fields, fields + "favouriteModes")
            )
                throw InvalidRequestException(
                    "Send bio, pronouns, favouriteMode and showFavouriteMode only."
                )
            val bio = body.path("bio")
            val pronouns = body.path("pronouns")
            val mode = body.path("favouriteMode")
            val show = body.path("showFavouriteMode")
            if (
                !bio.isTextual ||
                    bio.textValue().length > 240 ||
                    bio.textValue().any { it.isISOControl() && it !in "\n\r\t" }
            )
                throw InvalidRequestException("bio must be text of at most 240 characters.")
            if (
                !pronouns.isTextual ||
                    pronouns.textValue().length > 24 ||
                    pronouns.textValue().any { it.isISOControl() }
            )
                throw InvalidRequestException("pronouns must be text of at most 24 characters.")
            if (!mode.isNull && (!mode.isTextual || mode.textValue() !in modes))
                throw InvalidRequestException("favouriteMode must be a known mode or null.")
            val favourites =
                if (body.has("favouriteModes")) {
                    val list = body.path("favouriteModes")
                    if (
                        !list.isArray ||
                            list.size() > 3 ||
                            list.any { !it.isTextual || it.textValue() !in modes }
                    )
                        throw InvalidRequestException(
                            "favouriteModes must contain at most three known modes."
                        )
                    val values = list.map { it.textValue() }
                    if (
                        values.distinct().size != values.size ||
                            values.firstOrNull() != mode.textValue()
                    )
                        throw InvalidRequestException(
                            "favouriteModes must be unique and start with favouriteMode."
                        )
                    values
                } else listOfNotNull(mode.textValue())
            if (!show.isBoolean)
                throw InvalidRequestException("showFavouriteMode must be a boolean.")
            return PlayerProfile(
                bio.textValue(),
                pronouns.textValue(),
                mode.textValue(),
                show.booleanValue(),
                favourites,
            )
        }
    }
}
