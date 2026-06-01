package gg.grounds.auth

import com.nimbusds.jwt.JWTClaimsSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthContextTest {
    @Test
    fun `from maps subject, audience and issuer`() {
        val claims =
            JWTClaimsSet.Builder()
                .subject("system:serviceaccount:user-hendrik:default")
                .audience(listOf("grounds-services"))
                .issuer("https://kubernetes.default.svc")
                .build()

        val mapped = AuthClaims.from(claims)

        assertEquals("system:serviceaccount:user-hendrik:default", mapped.subject)
        assertEquals(listOf("grounds-services"), mapped.audience)
        assertEquals("https://kubernetes.default.svc", mapped.issuer)
    }

    @Test
    fun `from tolerates absent subject, audience and issuer`() {
        val mapped = AuthClaims.from(JWTClaimsSet.Builder().build())

        assertEquals("", mapped.subject)
        assertTrue(mapped.audience.isEmpty())
        assertNull(mapped.issuer)
    }
}
