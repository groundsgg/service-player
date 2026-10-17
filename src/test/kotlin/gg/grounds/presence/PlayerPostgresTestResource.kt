package gg.grounds.presence

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTestProfile
import org.testcontainers.containers.PostgreSQLContainer

class PlayerPostgresTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var postgres: PostgreSQLContainer<Nothing>

    override fun start(): Map<String, String> {
        postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        postgres.start()
        return mapOf(
            "quarkus.datasource.jdbc.url" to "${postgres.jdbcUrl}&currentSchema=player",
            "quarkus.datasource.username" to postgres.username,
            "quarkus.datasource.password" to postgres.password,
            "quarkus.flyway.migrate-at-start" to "true",
        )
    }

    override fun stop() {
        if (::postgres.isInitialized) postgres.stop()
    }
}

class PlayerSessionPostgresTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides() = mapOf("quarkus.flyway.migrate-at-start" to "true")
}
