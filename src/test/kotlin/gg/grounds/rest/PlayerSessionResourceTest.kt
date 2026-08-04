package gg.grounds.rest

import gg.grounds.domain.PlayerSession
import gg.grounds.persistence.PlayerNameRepository
import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.persistence.PlayerSessionRepository.DeleteSessionResult
import gg.grounds.persistence.PlayerSessionRepository.TouchSessionsResult
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.time.Instant
import java.util.UUID
import org.hamcrest.Matchers.endsWith
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@QuarkusTest
class PlayerSessionResourceTest {

    @InjectMock lateinit var repository: PlayerSessionRepository

    @InjectMock lateinit var nameRepository: PlayerNameRepository

    private val playerId = UUID.randomUUID()

    @BeforeEach
    fun resetMocks() {
        reset(repository, nameRepository)
    }

    @Test
    fun `login creates the session and points at it`() {
        whenever(repository.insertSession(any())).thenReturn(true)

        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "playerId" to playerId.toString(),
                    "playerName" to "Notch",
                    "proxyId" to "velocity-1",
                    "region" to "nl-ams1",
                )
            )
            .post("/v1/players/sessions")
            .then()
            .statusCode(201)
            .header("Location", endsWith("/v1/players/$playerId/session"))

        verify(nameRepository).upsertName(eq(playerId), eq("Notch"), any())
    }

    @Test
    fun `login is refused when the player already holds a session on the same proxy`() {
        whenever(repository.insertSession(any())).thenReturn(false)
        whenever(repository.findByPlayerId(playerId)).thenReturn(session(proxyId = "velocity-1"))

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("playerId" to playerId.toString(), "proxyId" to "velocity-1"))
            .post("/v1/players/sessions")
            .then()
            .statusCode(409)
            .body("code", equalTo("already_online"))
    }

    @Test
    fun `a login from a different proxy takes the session over`() {
        whenever(repository.insertSession(any())).thenReturn(false)
        whenever(repository.findByPlayerId(playerId)).thenReturn(session(proxyId = "velocity-1"))
        whenever(repository.replaceSession(any())).thenReturn(true)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("playerId" to playerId.toString(), "proxyId" to "velocity-2"))
            .post("/v1/players/sessions")
            .then()
            .statusCode(201)
    }

    @Test
    fun `login rejects a malformed player id without touching the store`() {
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("playerId" to "not-a-uuid"))
            .post("/v1/players/sessions")
            .then()
            .statusCode(400)
            .body("code", equalTo("invalid_request"))

        verifyNoInteractions(repository)
    }

    @Test
    fun `logout is scoped to the calling proxy`() {
        whenever(repository.deleteSessionOwnedBy(playerId, "velocity-1"))
            .thenReturn(DeleteSessionResult.REMOVED)

        given().delete("/v1/players/$playerId/session?proxyId=velocity-1").then().statusCode(204)

        verify(repository).deleteSessionOwnedBy(playerId, "velocity-1")
    }

    @Test
    fun `logout without a proxy deletes unconditionally`() {
        whenever(repository.deleteSession(playerId)).thenReturn(DeleteSessionResult.REMOVED)

        given().delete("/v1/players/$playerId/session").then().statusCode(204)

        verify(repository).deleteSession(playerId)
    }

    @Test
    fun `logout reports no session as 404`() {
        whenever(repository.deleteSession(playerId)).thenReturn(DeleteSessionResult.NOT_FOUND)

        given()
            .delete("/v1/players/$playerId/session")
            .then()
            .statusCode(404)
            .body("code", equalTo("not_found"))
    }

    @Test
    fun `a failed delete is 503, not 404`() {
        whenever(repository.deleteSession(playerId)).thenReturn(DeleteSessionResult.ERROR)

        given()
            .delete("/v1/players/$playerId/session")
            .then()
            .statusCode(503)
            .body("code", equalTo("store_unavailable"))
    }

    @Test
    fun `heartbeat reports what it touched`() {
        val other = UUID.randomUUID()
        whenever(repository.touchSessions(eq(listOf(playerId, other)), any()))
            .thenReturn(TouchSessionsResult.Updated(1))

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("playerIds" to listOf(playerId.toString(), other.toString())))
            .post("/v1/players/sessions/heartbeats")
            .then()
            .statusCode(200)
            .body("updated", equalTo(1))
            .body("missing", equalTo(1))
    }

    @Test
    fun `one malformed id rejects the whole heartbeat batch`() {
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("playerIds" to listOf(playerId.toString(), "nope")))
            .post("/v1/players/sessions/heartbeats")
            .then()
            .statusCode(400)

        verifyNoInteractions(repository)
    }

    @Test
    fun `reading a session answers who and where`() {
        whenever(repository.findByPlayerId(playerId)).thenReturn(session(proxyId = "velocity-1"))

        given()
            .get("/v1/players/$playerId/session")
            .then()
            .statusCode(200)
            .body("playerId", equalTo(playerId.toString()))
            .body("playerName", equalTo("Notch"))
            .body("proxyId", equalTo("velocity-1"))
            .body("region", equalTo("nl-ams1"))
    }

    @Test
    fun `a player who is not online is 404`() {
        whenever(repository.findByPlayerId(playerId)).thenReturn(null)

        given().get("/v1/players/$playerId/session").then().statusCode(404)
    }

    @Test
    fun `a name resolves to the session holding it`() {
        whenever(repository.findByName("notch")).thenReturn(session(proxyId = "velocity-1"))

        given()
            .get("/v1/players/sessions?name=notch")
            .then()
            .statusCode(200)
            .body("playerId", equalTo(playerId.toString()))
    }

    @Test
    fun `resolving without a name is a bad request`() {
        given().get("/v1/players/sessions").then().statusCode(400)
    }

    @Test
    fun `recording a server move updates the session`() {
        whenever(repository.updateServer(playerId, "lobby-2")).thenReturn(true)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("serverName" to "lobby-2"))
            .put("/v1/players/$playerId/session/server")
            .then()
            .statusCode(204)
    }

    @Test
    fun `a server move for a player with no session is 404`() {
        whenever(repository.updateServer(playerId, "lobby-2")).thenReturn(false)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("serverName" to "lobby-2"))
            .put("/v1/players/$playerId/session/server")
            .then()
            .statusCode(404)
    }

    private fun session(proxyId: String?): PlayerSession =
        PlayerSession(
            playerId = playerId,
            connectedAt = Instant.parse("2026-08-04T10:00:00Z"),
            lastSeenAt = Instant.now(),
            playerName = "Notch",
            proxyId = proxyId,
            serverName = null,
            region = "nl-ams1",
        )
}
