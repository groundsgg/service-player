package gg.grounds.rest

import jakarta.ws.rs.ApplicationPath
import jakarta.ws.rs.core.Application
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType
import org.eclipse.microprofile.openapi.annotations.info.Info
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme

@ApplicationPath("/")
@OpenAPIDefinition(
    info =
        Info(
            title = "Player API",
            version = "1.0.0",
            description =
                "Network-wide player presence: who is online, on which proxy and backend " +
                    "server, under what name, and in which language they want to be spoken to.\n\n" +
                    "A proxy only knows the players connected to itself, so anything that spans " +
                    "proxies — a private message, a party invite, an accurate player count — has " +
                    "to ask here. The session table is the network's single answer to \"is this " +
                    "player online\"; a login is a claim on it, and a second one is refused.\n\n" +
                    "Sessions are presence and are deleted on logout. Names outlive them: a " +
                    "separate, never-deleted index is written at every login, which is what lets " +
                    "a leaderboard or a match history show a name rather than a raw UUID.",
        )
)
@SecurityScheme(
    securitySchemeName = "bearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description =
        "The projected ServiceAccount token from /var/run/secrets/grounds/token, with the " +
            "grounds-services audience.",
)
class OpenApiConfiguration : Application()
