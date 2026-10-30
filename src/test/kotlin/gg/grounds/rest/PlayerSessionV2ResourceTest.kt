package gg.grounds.rest

import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.presence.PlayerPostgresTestResource
import gg.grounds.presence.PlayerSessionPostgresTestProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.Collections
import java.util.UUID
import javax.sql.DataSource
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(PlayerPostgresTestResource::class)
@TestProfile(PlayerSessionPostgresTestProfile::class)
class PlayerSessionV2ResourceTest {
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var sessions: PlayerSessionRepository

    @Test
    fun `source disconnect before destination login preserves the issued handoff`() {
        val playerId = UUID.randomUUID()
        val sourceSession = UUID.randomUUID()
        claim(playerId, sourceSession, "velocity-1").then().statusCode(201)
        val token = issueHandoff(playerId, sourceSession)

        given().delete("/v2/players/$playerId/session?sessionId=$sourceSession")
        claim(playerId, UUID.randomUUID(), "velocity-3").then().statusCode(409)
        consume(token, playerId, UUID.randomUUID(), "velocity-2").then().statusCode(201)
        consume(token, playerId, UUID.randomUUID(), "velocity-3").then().statusCode(409)
        given()
            .delete("/v2/players/$playerId/session?sessionId=$sourceSession")
            .then()
            .statusCode(404)
        given()
            .get("/v1/players/$playerId/session")
            .then()
            .statusCode(200)
            .body("proxyId", equalTo("velocity-2"))
    }

    @Test
    fun `queued source disconnect before destination login preserves the issued handoff`() {
        val playerId = UUID.randomUUID()
        val sourceSession = UUID.randomUUID()
        claim(playerId, sourceSession, "velocity-1").then().statusCode(201)
        val token = issueHandoff(playerId, sourceSession)

        sessions.applySessionEnded(playerId, sourceSession)

        consume(token, playerId, UUID.randomUUID(), "velocity-2").then().statusCode(201)
    }

    @Test
    fun `expired unconsumed handoff does not prevent logout`() {
        val playerId = UUID.randomUUID()
        val sourceSession = UUID.randomUUID()
        claim(playerId, sourceSession, "velocity-1").then().statusCode(201)
        val token = issueHandoff(playerId, sourceSession)
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE player_session_handoffs SET expires_at = NOW() - INTERVAL '1 second' WHERE player_id = ?"
                )
                .use { statement ->
                    statement.setObject(1, playerId)
                    statement.executeUpdate()
                }
        }

        given()
            .delete("/v2/players/$playerId/session?sessionId=$sourceSession")
            .then()
            .statusCode(204)
        consume(token, playerId, UUID.randomUUID(), "velocity-2").then().statusCode(409)
        given().get("/v1/players/$playerId/session").then().statusCode(404)
    }

    @Test
    fun `separate profile uuids can both be admitted`() {
        val javaProfile = UUID.randomUUID()
        val bedrockProfile = UUID.randomUUID()

        claim(javaProfile, UUID.randomUUID(), "velocity-java").then().statusCode(201)
        claim(bedrockProfile, UUID.randomUUID(), "velocity-bedrock").then().statusCode(201)
    }

    @Test
    fun `same profile only has one active session even on a different proxy`() {
        val playerId = UUID.randomUUID()
        val firstSession = UUID.randomUUID()

        claim(playerId, firstSession, "velocity-1")
            .then()
            .statusCode(201)
            .body("playerId", equalTo(playerId.toString()))
            .body("sessionId", equalTo(firstSession.toString()))
            .body("leaseExpiresAt", org.hamcrest.Matchers.notNullValue())

        claim(playerId, UUID.randomUUID(), "velocity-2").then().statusCode(409)
    }

    @Test
    fun `retry of the same claim is idempotent and older session cleanup cannot release it`() {
        val playerId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()

        claim(playerId, sessionId, "velocity-1").then().statusCode(201)
        claim(playerId, sessionId, "velocity-1").then().statusCode(201)
        claim(playerId, UUID.randomUUID(), "velocity-1").then().statusCode(409)

        given()
            .delete("/v2/players/$playerId/session?sessionId=${UUID.randomUUID()}")
            .then()
            .statusCode(404)
        given().get("/v1/players/$playerId/session").then().statusCode(200)
    }

    @Test
    fun `legacy v1 login logout and heartbeat cannot mutate v2 ownership`() {
        val playerId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        claim(playerId, sessionId, "velocity-1").then().statusCode(201)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("playerId" to playerId.toString(), "proxyId" to "velocity-2"))
            .post("/v1/players/sessions")
            .then()
            .statusCode(409)

        given().delete("/v1/players/$playerId/session").then().statusCode(404)
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("playerIds" to listOf(playerId.toString())))
            .post("/v1/players/sessions/heartbeats")
            .then()
            .statusCode(200)
            .body("updated", equalTo(0))

        given().get("/v1/players/$playerId/session").then().statusCode(200)
    }

    @Test
    fun `single-use handoff moves ownership only for the bound profile`() {
        val playerId = UUID.randomUUID()
        val sourceSession = UUID.randomUUID()
        val destinationSession = UUID.randomUUID()
        claim(playerId, sourceSession, "velocity-1").then().statusCode(201)

        val token =
            given()
                .contentType(ContentType.JSON)
                .body(mapOf("sessionId" to sourceSession.toString()))
                .post("/v2/players/$playerId/session/transfer")
                .then()
                .statusCode(201)
                .extract()
                .path<String>("token")

        consume(token, playerId, destinationSession, "velocity-2").then().statusCode(201)
        consume(token, playerId, UUID.randomUUID(), "velocity-3").then().statusCode(409)
        given()
            .delete("/v2/players/$playerId/session?sessionId=$sourceSession")
            .then()
            .statusCode(404)
        given()
            .get("/v1/players/$playerId/session")
            .then()
            .statusCode(200)
            .body("proxyId", equalTo("velocity-2"))
    }

    @Test
    fun `wrong profile cannot consume a handoff token`() {
        val playerId = UUID.randomUUID()
        val sourceSession = UUID.randomUUID()
        claim(playerId, sourceSession, "velocity-1").then().statusCode(201)
        val token = issueHandoff(playerId, sourceSession)

        consume(token, UUID.randomUUID(), UUID.randomUUID(), "velocity-2").then().statusCode(409)
        consume(token, playerId, UUID.randomUUID(), "velocity-2").then().statusCode(201)
    }

    @Test
    fun `heartbeat server update and logout require the exact session id`() {
        val playerId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        claim(playerId, sessionId, "velocity-1").then().statusCode(201)

        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "sessions" to
                        listOf(
                            mapOf(
                                "playerId" to playerId.toString(),
                                "sessionId" to UUID.randomUUID().toString(),
                            )
                        )
                )
            )
            .post("/v2/players/sessions/heartbeats")
            .then()
            .statusCode(200)
            .body("updated", equalTo(0))
            .body("missing", equalTo(1))

        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "sessions" to
                        listOf(
                            mapOf(
                                "playerId" to playerId.toString(),
                                "sessionId" to sessionId.toString(),
                            )
                        )
                )
            )
            .post("/v2/players/sessions/heartbeats")
            .then()
            .statusCode(200)
            .body("updated", equalTo(1))
            .body("missing", equalTo(0))

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("sessionId" to UUID.randomUUID().toString(), "serverName" to "lobby-2"))
            .put("/v2/players/$playerId/session/server")
            .then()
            .statusCode(404)
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("sessionId" to sessionId.toString(), "serverName" to "lobby-2"))
            .put("/v2/players/$playerId/session/server")
            .then()
            .statusCode(204)

        given()
            .delete("/v2/players/$playerId/session?sessionId=${UUID.randomUUID()}")
            .then()
            .statusCode(404)
        given().get("/v1/players/$playerId/session").then().statusCode(200)
        given().delete("/v2/players/$playerId/session?sessionId=$sessionId").then().statusCode(204)
        given().get("/v1/players/$playerId/session").then().statusCode(404)
    }

    @Test
    fun `expired handoff cannot release or replace the current session`() {
        val playerId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        claim(playerId, sessionId, "velocity-1").then().statusCode(201)
        val token = issueHandoff(playerId, sessionId)

        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE player_session_handoffs SET expires_at = NOW() - INTERVAL '1 second' WHERE player_id = ?"
                )
                .use { statement ->
                    statement.setObject(1, playerId)
                    statement.executeUpdate()
                }
        }

        consume(token, playerId, UUID.randomUUID(), "velocity-2").then().statusCode(409)
        given()
            .get("/v1/players/$playerId/session")
            .then()
            .statusCode(200)
            .body("proxyId", equalTo("velocity-1"))
    }

    @Test
    fun `concurrent claims for one profile have exactly one winner`() {
        val playerId = UUID.randomUUID()
        val statuses = Collections.synchronizedList(mutableListOf<Int>())
        val requests =
            (1..16).map {
                Thread {
                    statuses.add(
                        claim(playerId, UUID.randomUUID(), "velocity-$it")
                            .then()
                            .extract()
                            .statusCode()
                    )
                }
            }
        requests.forEach(Thread::start)
        requests.forEach { it.join() }
        assertEquals(16, statuses.size)
        assertEquals(1, statuses.count { it == 201 })
        assertEquals(15, statuses.count { it == 409 })
    }

    private fun claim(playerId: UUID, sessionId: UUID, proxyId: String) =
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "playerId" to playerId.toString(),
                    "sessionId" to sessionId.toString(),
                    "playerName" to "Player${playerId.toString().take(8)}",
                    "proxyId" to proxyId,
                    "region" to "eu",
                )
            )
            .post("/v2/players/sessions")

    private fun issueHandoff(playerId: UUID, sessionId: UUID): String =
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("sessionId" to sessionId.toString()))
            .post("/v2/players/$playerId/session/transfer")
            .then()
            .statusCode(201)
            .extract()
            .path("token")

    private fun consume(token: String, playerId: UUID, sessionId: UUID, proxyId: String) =
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "playerId" to playerId.toString(),
                    "sessionId" to sessionId.toString(),
                    "handoffToken" to token,
                    "proxyId" to proxyId,
                    "region" to "eu",
                )
            )
            .post("/v2/players/sessions/transfer")
}
