package gg.grounds.rest

import gg.grounds.presence.PlayerPostgresTestResource
import gg.grounds.presence.PlayerSessionPostgresTestProfile
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(PlayerPostgresTestResource::class)
@TestProfile(PlayerSessionPostgresTestProfile::class)
class PlayerProfileResourceTest {
    @Inject lateinit var dataSource: DataSource

    @Test
    fun `all four fields survive independent reads and a later proxy login`() {
        val id = known()
        put(
                id,
                """{"bio":"Hello Grounds","pronouns":"they/them","favouriteMode":"keystone","showFavouriteMode":true}""",
            )
            .then()
            .statusCode(200)
        // Another proxy updates identity on login; profile details must remain durable.
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE player_names SET player_name = 'NewName', last_seen_at = now() WHERE player_id = ?"
                )
                .use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
        }
        given()
            .get(path(id))
            .then()
            .statusCode(200)
            .body("bio", equalTo("Hello Grounds"))
            .body("pronouns", equalTo("they/them"))
            .body("favouriteMode", equalTo("keystone"))
            .body("showFavouriteMode", equalTo(true))
        put(id, """{"bio":"","pronouns":"","favouriteMode":"duel","showFavouriteMode":false}""")
            .then()
            .statusCode(200)
        given()
            .get(path(id))
            .then()
            .statusCode(200)
            .body("bio", equalTo(""))
            .body("pronouns", equalTo(""))
            .body("favouriteMode", equalTo("duel"))
            .body("showFavouriteMode", equalTo(false))
    }

    @Test
    fun `invalid updates cannot overwrite saved details`() {
        val id = known()
        val good =
            """{"bio":"Keep this","pronouns":"","favouriteMode":"bedwars","showFavouriteMode":false}"""
        put(id, good).then().statusCode(200)
        listOf(
                good.replace("Keep this", "x".repeat(241)),
                good.replace("\"pronouns\":\"\"", "\"pronouns\":\"${"x".repeat(25)}\""),
                good.replace("bedwars", "unknown"),
                good.replace("false", "\"false\""),
                "{}",
                "null",
                good.replace("Keep this", "Hi\\u0000there"),
                good.replace("{", "{\"playerId\":\"other\","),
            )
            .forEach { put(id, it).then().statusCode(400) }
        given().get(path(id)).then().statusCode(200).body("bio", equalTo("Keep this"))
    }

    @Test
    fun `unknown players have empty details and can store a profile before joining`() {
        val id = UUID.randomUUID()
        given()
            .get(path(id))
            .then()
            .statusCode(200)
            .body("bio", equalTo(""))
            .body("showFavouriteMode", equalTo(false))
        put(
                id,
                """{"bio":"New player","pronouns":"","favouriteMode":"duel","showFavouriteMode":true}""",
            )
            .then()
            .statusCode(200)
        given().get(path(id)).then().statusCode(200).body("bio", equalTo("New player"))
    }

    private fun path(id: UUID) = "/v1/players/$id/profile"

    private fun put(id: UUID, body: String) =
        given().contentType(ContentType.JSON).body(body).put(path(id))

    private fun known(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement(
                    "INSERT INTO player_names(player_id,player_name,last_seen_at) VALUES (?,?,now())"
                )
                .use {
                    it.setObject(1, id)
                    it.setString(2, "PROFILE_TEST")
                    it.executeUpdate()
                }
        }
        return id
    }
}
