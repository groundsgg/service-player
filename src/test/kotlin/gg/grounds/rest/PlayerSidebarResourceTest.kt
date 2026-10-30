package gg.grounds.rest

import gg.grounds.presence.PlayerPostgresTestResource
import gg.grounds.settings.PlayerSettingsCache
import gg.grounds.settings.SettingsInvalidations
import gg.grounds.support.StreamInfra
import io.nats.client.Nats
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@QuarkusTest
@TestProfile(PlayerSidebarResourceTest.NoCache::class)
@QuarkusTestResource(PlayerPostgresTestResource::class)
@QuarkusTestResource(StreamInfra::class, restrictToAnnotatedClass = true)
class PlayerSidebarResourceTest {
    class NoCache : QuarkusTestProfile {
        override fun getConfigOverrides() =
            mapOf(
                "quarkus.flyway.migrate-at-start" to "true",
                "grounds.player.settings-cache.enabled" to "false",
            )
    }

    @Inject lateinit var database: DataSource
    @Inject lateinit var cache: PlayerSettingsCache

    private fun path(id: Any) = "/v1/players/$id/sidebar"

    private fun put(id: Any, body: String) =
        given().contentType(ContentType.JSON).body(body).put(path(id))

    private fun known(): UUID {
        val id = UUID.randomUUID()
        database.connection.use { c ->
            c.prepareStatement(
                    "INSERT INTO player_names(player_id,player_name,last_seen_at,locale,preferred_region,profile_visibility) VALUES (?,?,now(),'de','eu-central','private')"
                )
                .use {
                    it.setObject(1, id)
                    it.setString(2, "Sidebar_${id.toString().take(8)}")
                    it.executeUpdate()
                }
        }
        return id
    }

    @Test
    fun `unknown and existing players default to visible`() {
        given().get(path(UUID.randomUUID())).then().statusCode(200).body("enabled", equalTo(true))
        given().get(path(known())).then().statusCode(200).body("enabled", equalTo(true))
    }

    @Test
    fun `visibility persists and preserves every other durable preference`() {
        val id = known()
        put(id, """{"enabled":false}""").then().statusCode(200).body("enabled", equalTo(false))
        given().get(path(id)).then().statusCode(200).body("enabled", equalTo(false))
        database.connection.use { c ->
            c.prepareStatement(
                    "SELECT locale,preferred_region,profile_visibility FROM player_names WHERE player_id=?"
                )
                .use {
                    it.setObject(1, id)
                    it.executeQuery().use { row ->
                        assertTrue(row.next())
                        assertEquals("de", row.getString(1))
                        assertEquals("eu-central", row.getString(2))
                        assertEquals("private", row.getString(3))
                    }
                }
        }
        given()
            .contentType(ContentType.JSON)
            .body("""{"locale":"fr"}""")
            .put("/v1/players/$id/locale")
            .then()
            .statusCode(204)
        given().get(path(id)).then().statusCode(200).body("enabled", equalTo(false))
        put(id, """{"enabled":true}""").then().statusCode(200)
        given().get(path(id)).then().statusCode(200).body("enabled", equalTo(true))
    }

    @Test
    fun `invalid choices and unknown writes never invent a player`() {
        val id = known()
        for (body in listOf("{}", "null", """{"enabled":null}""")) put(id, body)
            .then()
            .statusCode(400)
        put("not-a-uuid", """{"enabled":false}""").then().statusCode(400)
        given().get(path("not-a-uuid")).then().statusCode(400)
        put(UUID.randomUUID(), """{"enabled":false}""").then().statusCode(404)
        given().get(path(id)).then().statusCode(200).body("enabled", equalTo(true))
    }

    @Test
    fun `a committed save reaches both independent subscribers even with cache disabled`() {
        assertFalse(cache.enabled)
        val id = known()
        Nats.connect(StreamInfra.natsUrl()).use { a ->
            Nats.connect(StreamInfra.natsUrl()).use { b ->
                val first = a.subscribe(SettingsInvalidations.SUBJECT_PREFIX + id)
                val second = b.subscribe(SettingsInvalidations.SUBJECT_PREFIX + id)
                a.flush(Duration.ofSeconds(3))
                b.flush(Duration.ofSeconds(3))
                put(id, """{"enabled":false}""").then().statusCode(200)
                for (sub in listOf(first, second)) {
                    val event = sub.nextMessage(Duration.ofSeconds(5))
                    assertNotNull(event)
                    assertEquals(0, event.data.size)
                }
                given().get(path(id)).then().statusCode(200).body("enabled", equalTo(false))
            }
        }
    }
}
