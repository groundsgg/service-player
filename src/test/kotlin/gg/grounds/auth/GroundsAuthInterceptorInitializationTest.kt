package gg.grounds.auth

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroundsAuthInterceptorInitializationTest {
    @Test
    fun `initialization fetches the JWKS before serving requests`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/jwks") { exchange ->
            requests.incrementAndGet()
            val body = "{\"keys\":[]}".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/jwk-set+json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val interceptor =
                GroundsAuthInterceptor(
                    enabled = true,
                    jwksUrl = "http://127.0.0.1:${server.address.port}/jwks",
                    expectedAudience = "grounds-services",
                    caFile = "/path/that/does/not/exist/ca.crt",
                    tokenFile = "/path/that/does/not/exist/token",
                )

            interceptor.init()

            assertTrue(requests.get() > 0)
        } finally {
            server.stop(0)
        }
    }
}
