package gg.grounds.settings

import gg.grounds.persistence.PlayerNameRepository.Setting
import gg.grounds.presence.PlayerPostgresTestResource
import gg.grounds.support.StreamInfra
import gg.grounds.support.ValkeyInfra
import io.nats.client.Nats
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The settings cache in a running service: real Postgres, Valkey as the regional level and NATS for
 * invalidations from other runtimes.
 */
@QuarkusTest
@TestProfile(SettingsCacheIT.Cached::class)
@QuarkusTestResource(PlayerPostgresTestResource::class)
@QuarkusTestResource(StreamInfra::class, restrictToAnnotatedClass = true)
@QuarkusTestResource(ValkeyInfra::class, restrictToAnnotatedClass = true)
class SettingsCacheIT {

    class Cached : QuarkusTestProfile {
        override fun getConfigOverrides() =
            mapOf(
                "quarkus.flyway.migrate-at-start" to "true",
                "grounds.player.settings-cache.enabled" to "true",
                "grounds.player.settings-cache.valkey-timeout" to "2s",
            )
    }

    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var redis: RedisDataSource

    @Inject lateinit var invalidations: SettingsInvalidations

    @Test
    fun `a write through the API is read back at once, from the cache afterwards`() {
        val playerId = knownPlayer()
        locale(playerId).body("locale", equalTo(null))

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("locale" to "de"))
            .put("/v1/players/$playerId/locale")
            .then()
            .statusCode(204)
        locale(playerId).body("locale", equalTo("de"))
        // This runtime hears its own invalidation too; let it land before warming the cache.
        Thread.sleep(300)
        locale(playerId).body("locale", equalTo("de"))

        // Changed behind the service's back: the cache keeps answering until told otherwise.
        setLocaleInDatabase(playerId, "fr")
        locale(playerId).body("locale", equalTo("de"))
        awaitValkey(PlayerSettingsCache.key(Setting.LOCALE, playerId), "de")
    }

    @Test
    fun `an invalidation from another runtime drops this runtime's copies`() {
        invalidations.ensureSubscribed()
        val playerId = knownPlayer()
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("region" to "eu-central"))
            .put("/v1/players/$playerId/region")
            .then()
            .statusCode(204)
        region(playerId).body("region", equalTo("eu-central"))

        setRegionInDatabase(playerId, "us-east")
        Nats.connect(StreamInfra.natsUrl()).use {
            it.publish(SettingsInvalidations.SUBJECT_PREFIX + playerId, ByteArray(0))
            it.flush(java.time.Duration.ofSeconds(2))
        }
        awaitTrue { region(playerId).extract().path<String>("region") == "us-east" }
    }

    @Test
    fun `an unknown player reads as none and is not an error`() {
        val playerId = UUID.randomUUID()
        locale(playerId).body("locale", equalTo(null))
    }

    private fun locale(playerId: UUID) =
        given().get("/v1/players/$playerId/locale").then().statusCode(200)

    private fun region(playerId: UUID) =
        given().get("/v1/players/$playerId/region").then().statusCode(200)

    private fun knownPlayer(): UUID {
        val playerId = UUID.randomUUID()
        given()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "playerId" to playerId.toString(),
                    "sessionId" to UUID.randomUUID().toString(),
                    "playerName" to "Cached",
                    "proxyId" to "velocity-1",
                )
            )
            .post("/v2/players/sessions")
            .then()
            .statusCode(201)
        return playerId
    }

    private fun setLocaleInDatabase(playerId: UUID, locale: String) =
        update("UPDATE player_names SET locale = ? WHERE player_id = ?", locale, playerId)

    private fun setRegionInDatabase(playerId: UUID, region: String) =
        update("UPDATE player_names SET preferred_region = ? WHERE player_id = ?", region, playerId)

    private fun update(sql: String, value: String, playerId: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use {
                it.setString(1, value)
                it.setObject(2, playerId)
                assertEquals(1, it.executeUpdate())
            }
        }
    }

    private fun awaitValkey(key: String, expected: String) = awaitTrue {
        redis.value(String::class.java).get(key) == expected
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within 5s" }
            Thread.sleep(50)
        }
    }
}
