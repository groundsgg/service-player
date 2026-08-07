package gg.grounds.auth

import com.nimbusds.jwt.JWTClaimsSet

/**
 * Verified caller identity for the current request. The subject is the projected
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
