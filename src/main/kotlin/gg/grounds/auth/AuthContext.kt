package gg.grounds.auth

import com.nimbusds.jwt.JWTClaimsSet
import io.grpc.Context

/**
 * Verified caller identity for the current gRPC call. The subject is the projected
 * ServiceAccount-Token's `sub` (`system:serviceaccount:<ns>:<sa>`).
 */
data class AuthClaims(val subject: String, val audience: List<String>, val issuer: String?) {
    companion object {
        fun from(claims: JWTClaimsSet): AuthClaims =
            AuthClaims(
                subject = claims.subject ?: "",
                audience = claims.audience ?: emptyList(),
                issuer = claims.issuer,
            )
    }
}

object AuthContext {
    internal val KEY: Context.Key<AuthClaims> = Context.key("grounds-auth-claims")

    /** Returns the current caller's claims, or null when auth is disabled. */
    fun current(): AuthClaims? = KEY.get()
}
