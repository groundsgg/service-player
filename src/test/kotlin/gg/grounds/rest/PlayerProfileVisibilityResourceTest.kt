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
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(PlayerPostgresTestResource::class)
@TestProfile(PlayerSessionPostgresTestProfile::class)
class PlayerProfileVisibilityResourceTest {
    @Inject lateinit var dataSource: DataSource

    @Test
    fun `a player who never chose reads as null`() {
        val id = known()

        given().get(path(id)).then().statusCode(200).body("visibility", nullValue())
    }

    @Test
    fun `a stored visibility is read back and can be cleared`() {
        val id = known()

        put(id, """{"visibility":"private"}""").then().statusCode(204)
        given().get(path(id)).then().statusCode(200).body("visibility", equalTo("private"))

        put(id, """{"visibility":null}""").then().statusCode(204)
        given().get(path(id)).then().statusCode(200).body("visibility", nullValue())
    }

    @Test
    fun `a value outside the choices is refused and nothing is written`() {
        val id = known()

        put(id, """{"visibility":"everyone"}""").then().statusCode(400)
        given().get(path(id)).then().statusCode(200).body("visibility", nullValue())
    }

    @Test
    fun `a player this service has never seen is a 404 on write`() {
        put(UUID.randomUUID(), """{"visibility":"public"}""").then().statusCode(404)
    }

    @Test
    fun `a malformed player id is a 400`() {
        given().get("/v1/players/not-a-uuid/profile-visibility").then().statusCode(400)
    }

    private fun put(id: UUID, body: String) =
        given().contentType(ContentType.JSON).body(body).put(path(id))

    private fun path(id: UUID) = "/v1/players/$id/profile-visibility"

    private fun known(): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO player_names(player_id,player_name,last_seen_at) VALUES (?,?,now())"
                )
                .use { statement ->
                    statement.setObject(1, id)
                    statement.setString(2, "QVIS_${id.toString().take(8)}")
                    statement.executeUpdate()
                }
        }
        return id
    }
}
