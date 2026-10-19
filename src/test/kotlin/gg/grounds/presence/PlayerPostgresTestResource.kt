package gg.grounds.presence

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTestProfile
import org.testcontainers.containers.PostgreSQLContainer

/**
 * A Postgres container by default (CI has Docker). `TEST_POSTGRES_URL` (a JDBC URL with
 * `currentSchema=player`, plus `TEST_POSTGRES_USER` / `TEST_POSTGRES_PASSWORD`) points the tests at
 * a running server instead.
 */
class PlayerPostgresTestResource : QuarkusTestResourceLifecycleManager {
    private var postgres: PostgreSQLContainer<Nothing>? = null

    override fun start(): Map<String, String> {
        val external = System.getenv("TEST_POSTGRES_URL")
        if (external != null) {
            return mapOf(
                "quarkus.datasource.jdbc.url" to external,
                "quarkus.datasource.username" to (System.getenv("TEST_POSTGRES_USER") ?: "app"),
                "quarkus.datasource.password" to (System.getenv("TEST_POSTGRES_PASSWORD") ?: "app"),
                "quarkus.flyway.migrate-at-start" to "true",
            )
        }
        val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        container.start()
        postgres = container
        return mapOf(
            "quarkus.datasource.jdbc.url" to "${container.jdbcUrl}&currentSchema=player",
            "quarkus.datasource.username" to container.username,
            "quarkus.datasource.password" to container.password,
            "quarkus.flyway.migrate-at-start" to "true",
        )
    }

    override fun stop() {
        postgres?.stop()
    }
}

class PlayerSessionPostgresTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides() = mapOf("quarkus.flyway.migrate-at-start" to "true")
}
