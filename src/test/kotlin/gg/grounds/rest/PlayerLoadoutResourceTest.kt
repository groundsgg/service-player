package gg.grounds.rest

import gg.grounds.persistence.PlayerLoadoutRepository
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.util.UUID
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@QuarkusTest
class PlayerLoadoutResourceTest {

    @InjectMock lateinit var loadouts: PlayerLoadoutRepository

    private val playerId = UUID.randomUUID()

    @BeforeEach
    fun resetMocks() {
        reset(loadouts)
    }

    @Test
    fun `a stored loadout comes back as it went in`() {
        whenever(loadouts.find(eq(playerId), eq("pot")))
            .thenReturn(PlayerLoadoutRepository.Result.Found("""{"0":{"id":"diamond_sword"}}"""))

        given()
            .get("/v1/players/$playerId/loadouts/pot")
            .then()
            .statusCode(200)
            .body("slots.'0'.id", equalTo("diamond_sword"))
    }

    @Test
    fun `a kit that was never customised is a 404`() {
        whenever(loadouts.find(any(), any())).thenReturn(PlayerLoadoutRepository.Result.Absent)

        given()
            .get("/v1/players/$playerId/loadouts/uhc")
            .then()
            .statusCode(404)
            .body("code", equalTo("not_found"))
    }

    /**
     * The distinction that matters: a caller told "you have no loadout" hands out the stock kit and
     * nobody notices the database is down.
     */
    @Test
    fun `a store that cannot answer is a 503, not an empty answer`() {
        whenever(loadouts.find(any(), any())).thenReturn(PlayerLoadoutRepository.Result.Unavailable)

        given()
            .get("/v1/players/$playerId/loadouts/uhc")
            .then()
            .statusCode(503)
            .body("code", equalTo("store_unavailable"))
    }

    @Test
    fun `saving stores the arrangement verbatim`() {
        whenever(loadouts.save(any(), any(), any())).thenReturn(true)

        given()
            .contentType(ContentType.JSON)
            .body("""{"slots":{"0":{"id":"mace"},"39":{"id":"netherite_helmet"}}}""")
            .put("/v1/players/$playerId/loadouts/mace")
            .then()
            .statusCode(204)

        verify(loadouts).save(eq(playerId), eq("mace"), any())
    }

    @Test
    fun `saving without slots is refused`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{}""")
            .put("/v1/players/$playerId/loadouts/mace")
            .then()
            .statusCode(400)

        verify(loadouts, never()).save(any(), any(), any())
    }

    @Test
    fun `slots has to be an object, not a list`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"slots":[1,2,3]}""")
            .put("/v1/players/$playerId/loadouts/mace")
            .then()
            .statusCode(400)

        verify(loadouts, never()).save(any(), any(), any())
    }

    /** An authenticated workload should not get to decide how large this table grows. */
    @Test
    fun `an oversized arrangement is refused rather than stored`() {
        val huge = "x".repeat(70_000)
        given()
            .contentType(ContentType.JSON)
            .body("""{"slots":{"0":"$huge"}}""")
            .put("/v1/players/$playerId/loadouts/pot")
            .then()
            .statusCode(400)

        verify(loadouts, never()).save(any(), any(), any())
    }

    @Test
    fun `a malformed player id is refused before the store is touched`() {
        given().get("/v1/players/not-a-uuid/loadouts/pot").then().statusCode(400)

        verify(loadouts, never()).find(any(), any())
    }

    @Test
    fun `a kit id that is not a sane key is refused`() {
        given().get("/v1/players/$playerId/loadouts/Pot%20Kit!").then().statusCode(400)

        verify(loadouts, never()).find(any(), any())
    }

    @Test
    fun `deleting an arrangement that is not there still succeeds`() {
        whenever(loadouts.delete(any(), any())).thenReturn(true)

        given().delete("/v1/players/$playerId/loadouts/sword").then().statusCode(204)

        verify(loadouts).delete(eq(playerId), eq("sword"))
    }
}
