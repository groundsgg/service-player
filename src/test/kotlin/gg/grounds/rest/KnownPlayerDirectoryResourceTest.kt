package gg.grounds.rest

import gg.grounds.presence.PlayerPostgresTestResource
import gg.grounds.presence.PlayerSessionPostgresTestProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(PlayerPostgresTestResource::class)
@TestProfile(PlayerSessionPostgresTestProfile::class)
class KnownPlayerDirectoryResourceTest {
    @Inject lateinit var dataSource: DataSource

    @BeforeEach
    fun clearOwnFixtures() {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "DELETE FROM player_names WHERE player_name LIKE 'QDIR%' OR player_name LIKE '.QDIR%'"
                )
                .use { it.executeUpdate() }
        }
    }

    @Test
    fun `durable Java and Bedrock names are suggested without an online session`() {
        val javaId = known("QDIR_Java")
        val bedrockId = known(".QDIR_Bedrock")

        given()
            .queryParam("prefix", "qdir_j")
            .get(PATH)
            .then()
            .statusCode(200)
            .body("players.playerId", contains(javaId.toString()))
            .body("players.playerName", contains("QDIR_Java"))
        given()
            .queryParam("prefix", ".qdir_b")
            .get(PATH)
            .then()
            .statusCode(200)
            .body("players.playerId", contains(bedrockId.toString()))
            .body("players.playerName", contains(".QDIR_Bedrock"))
    }

    @Test
    fun `exact lookup keeps both identities when a stored name is ambiguous`() {
        known("QDIR_Duplicate")
        known("QDIR_Duplicate")
        known("QDIR_Duplicate")

        given()
            .queryParam("name", "qdir_duplicate")
            .get(PATH)
            .then()
            .statusCode(200)
            .body("players", hasSize<Any>(2))
            .body("players.playerName", contains("QDIR_Duplicate", "QDIR_Duplicate"))
    }

    @Test
    fun `exact lookup returns a unique offline UUID and unknown names stay empty`() {
        val id = known("QDIR_Exact")

        given()
            .queryParam("name", "QDIR_EXACT")
            .get(PATH)
            .then()
            .statusCode(200)
            .body("players.playerId", contains(id.toString()))
        given()
            .queryParam("name", "QDIR_Missing")
            .get(PATH)
            .then()
            .statusCode(200)
            .body("players", hasSize<Any>(0))
    }

    @Test
    fun `prefix wildcards are literal and do not expand the directory`() {
        known("QDIR_Alice")
        known("QDIRXAlice")

        given()
            .queryParam("prefix", "qdir_")
            .get(PATH)
            .then()
            .statusCode(200)
            .body("players.playerName", contains("QDIR_Alice"))
        given()
            .queryParam("prefix", "qdir%")
            .get(PATH)
            .then()
            .statusCode(200)
            .body("players", hasSize<Any>(0))
        given()
            .queryParam("prefix", "qdir\\")
            .get(PATH)
            .then()
            .statusCode(200)
            .body("players", hasSize<Any>(0))
    }

    @Test
    fun `suggestions clamp a caller limit and do not return an unlimited roster`() {
        repeat(30) { known("QDIR_Cap${it.toString().padStart(2, '0')}") }

        given()
            .queryParam("prefix", "qdir_cap")
            .queryParam("limit", 1000)
            .get(PATH)
            .then()
            .statusCode(200)
            .body("players", hasSize<Any>(25))
        given()
            .queryParam("prefix", "qdir_cap")
            .queryParam("limit", 3)
            .get(PATH)
            .then()
            .statusCode(200)
            .body("players", hasSize<Any>(3))
    }

    @Test
    fun `short prefixes do not list known players`() {
        known("QDIR_Visible")

        given()
            .queryParam("prefix", "Q")
            .get(PATH)
            .then()
            .statusCode(200)
            .body("players", hasSize<Any>(0))
        given()
            .queryParam("prefix", "")
            .get(PATH)
            .then()
            .statusCode(200)
            .body("players", hasSize<Any>(0))
    }

    @Test
    fun `missing or conflicting selectors are rejected`() {
        given().get(PATH).then().statusCode(400)
        given()
            .queryParam("name", "QDIR_Test")
            .queryParam("prefix", "qd")
            .get(PATH)
            .then()
            .statusCode(400)
        given().queryParam("name", " ").get(PATH).then().statusCode(400)
    }

    private fun known(name: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO player_names(player_id,player_name,last_seen_at) VALUES (?,?,now()-interval '7 days')"
                )
                .use { statement ->
                    statement.setObject(1, id)
                    statement.setString(2, name)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement("SELECT count(*) FROM player_sessions WHERE player_id=?")
                .use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        org.junit.jupiter.api.Assertions.assertEquals(0, rows.getInt(1))
                    }
                }
        }
        return id
    }

    private companion object {
        const val PATH = "/v1/players/names/known"
    }
}
