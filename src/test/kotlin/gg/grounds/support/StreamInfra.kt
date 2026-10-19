package gg.grounds.support

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * A JetStream broker for the queued-write tests. A container by default (CI has Docker);
 * `TEST_NATS_URL` points the tests at a running broker started with `-js` instead.
 */
class StreamInfra : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> = mapOf("nats.url" to natsUrl())

    override fun stop() = Unit

    companion object {
        /** Shared by every test class in the JVM. */
        fun natsUrl(): String = shared

        private val shared: String by lazy {
            System.getenv("TEST_NATS_URL")
                ?: GenericContainer<Nothing>(DockerImageName.parse("nats:2.15.1-alpine")).run {
                    withCommand("-js")
                    withExposedPorts(4222)
                    waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))
                    start()
                    "nats://$host:${getMappedPort(4222)}"
                }
        }
    }
}

/** Valkey for the settings L2. A container by default; `TEST_VALKEY_URL` overrides it. */
class ValkeyInfra : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> =
        mapOf(
            "quarkus.redis.hosts" to valkeyUrl(),
            "grounds.player.settings-cache.valkey-url" to valkeyUrl(),
        )

    override fun stop() = Unit

    companion object {
        fun valkeyUrl(): String = shared

        private val shared: String by lazy {
            System.getenv("TEST_VALKEY_URL")
                ?: GenericContainer<Nothing>(DockerImageName.parse("valkey/valkey:8-alpine")).run {
                    withExposedPorts(6379)
                    start()
                    "redis://$host:${getMappedPort(6379)}"
                }
        }
    }
}
