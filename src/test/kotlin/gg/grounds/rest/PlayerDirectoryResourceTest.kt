package gg.grounds.rest

import gg.grounds.persistence.PlayerNameRepository
import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.persistence.PlayerSessionRepository.CountPlayersByProxyResult
import gg.grounds.persistence.PlayerSessionRepository.CountPlayersByServerResult
import gg.grounds.persistence.PlayerSessionRepository.ProxyPlayerCount
import gg.grounds.persistence.PlayerSessionRepository.ServerPlayerCount
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.util.UUID
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Names, counts and locale — everything that outlives or aggregates over a single session. */
@QuarkusTest
class PlayerDirectoryResourceTest {

    @InjectMock lateinit var repository: PlayerSessionRepository

    @InjectMock lateinit var nameRepository: PlayerNameRepository

    private val playerId = UUID.randomUUID()

    @BeforeEach
    fun resetMocks() {
        reset(repository, nameRepository)
    }

    @Test
    fun `looking up ids returns the names that are known`() {
        whenever(nameRepository.findNames(setOf(playerId))).thenReturn(mapOf(playerId to "Notch"))

        given()
            .get("/v1/players/names?playerId=$playerId")
            .then()
            .statusCode(200)
            .body("names['$playerId']", equalTo("Notch"))
    }

    @Test
    fun `an unknown id is absent rather than a placeholder`() {
        whenever(nameRepository.findNames(any())).thenReturn(emptyMap())

        given()
            .get("/v1/players/names?playerId=$playerId")
            .then()
            .statusCode(200)
            .body("names", equalTo(emptyMap<String, String>()))
    }

    @Test
    fun `malformed ids are dropped, and a batch of only those never hits the store`() {
        given()
            .get("/v1/players/names?playerId=not-a-uuid")
            .then()
            .statusCode(200)
            .body("names", equalTo(emptyMap<String, String>()))

        verify(nameRepository, org.mockito.kotlin.never()).findNames(any())
    }

    @Test
    fun `suggestions prefix-search the online players`() {
        whenever(repository.suggestNames(eq("no"), any())).thenReturn(listOf("Notch", "Nobody"))

        given()
            .get("/v1/players/names/suggestions?prefix=no")
            .then()
            .statusCode(200)
            .body("playerNames", contains("Notch", "Nobody"))
    }

    @Test
    fun `a blank prefix returns nothing rather than everyone`() {
        given()
            .get("/v1/players/names/suggestions")
            .then()
            .statusCode(200)
            .body("playerNames", hasSize<String>(0))

        verify(repository, org.mockito.kotlin.never()).suggestNames(any(), any())
    }

    @Test
    fun `a caller-supplied limit is clamped to the server's cap`() {
        whenever(repository.suggestNames(eq("no"), eq(25))).thenReturn(emptyList())

        given().get("/v1/players/names/suggestions?prefix=no&limit=1000").then().statusCode(200)

        verify(repository).suggestNames("no", 25)
    }

    @Test
    fun `server counts list only occupied servers`() {
        whenever(repository.countPlayersByServer())
            .thenReturn(
                CountPlayersByServerResult.Counted(listOf(ServerPlayerCount("lobby-1", 3)), 5)
            )

        given()
            .get("/v1/players/counts/servers")
            .then()
            .statusCode(200)
            .body("total", equalTo(5))
            .body("servers[0].serverName", equalTo("lobby-1"))
            .body("servers[0].players", equalTo(3))
    }

    @Test
    fun `an unreadable store is 503 rather than a count of zero`() {
        whenever(repository.countPlayersByServer()).thenReturn(CountPlayersByServerResult.Error)

        given()
            .get("/v1/players/counts/servers")
            .then()
            .statusCode(503)
            .body("code", equalTo("store_unavailable"))
    }

    @Test
    fun `proxy counts carry the region and total to the sum of the proxies`() {
        whenever(repository.countPlayersByProxy())
            .thenReturn(
                CountPlayersByProxyResult.Counted(
                    listOf(ProxyPlayerCount("velocity-1", "nl-ams1", 4)),
                    4,
                )
            )

        given()
            .get("/v1/players/counts/proxies")
            .then()
            .statusCode(200)
            .body("total", equalTo(4))
            .body("proxies[0].proxyId", equalTo("velocity-1"))
            .body("proxies[0].region", equalTo("nl-ams1"))
    }

    @Test
    fun `an unset locale reads as null rather than 404`() {
        whenever(nameRepository.getLocale(playerId)).thenReturn(null)

        given()
            .get("/v1/players/$playerId/locale")
            .then()
            .statusCode(200)
            .body("locale", nullValue())
    }

    @Test
    fun `storing a locale writes it against the durable player row`() {
        whenever(nameRepository.setLocale(playerId, "de-DE")).thenReturn(true)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("locale" to "de-DE"))
            .put("/v1/players/$playerId/locale")
            .then()
            .statusCode(204)
    }

    @Test
    fun `a blank locale clears the preference`() {
        whenever(nameRepository.setLocale(playerId, null)).thenReturn(true)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("locale" to "  "))
            .put("/v1/players/$playerId/locale")
            .then()
            .statusCode(204)

        verify(nameRepository).setLocale(playerId, null)
    }

    @Test
    fun `a locale for a player this service has never seen is 404`() {
        whenever(nameRepository.setLocale(playerId, "de")).thenReturn(false)

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("locale" to "de"))
            .put("/v1/players/$playerId/locale")
            .then()
            .statusCode(404)
    }

    @Test
    fun `a malformed id in the path is a bad request, not a miss`() {
        given().get("/v1/players/not-a-uuid/locale").then().statusCode(400)
    }
}
