package gg.grounds.rest

import gg.grounds.auth.AuthClaims
import gg.grounds.auth.WorkloadAuthenticator
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The filter is the only thing standing between the presence endpoints and an unauthenticated
 * caller, and it decides by path prefix rather than per-resource annotation. A wrong prefix would
 * not fail anything — it would quietly serve every endpoint to anyone, which is why the prefix
 * itself is asserted here rather than only the happy path.
 *
 * The `@QuarkusTest` suites all run with `grounds.auth.enabled=false`, so without this the
 * enforcing branch is never executed.
 */
class WorkloadAuthFilterTest {

    @Test
    fun `a guarded request without a bearer is rejected as unauthenticated`() {
        val context = requestContext(path = "v1/players/counts/servers", authorization = null)

        filter(enabled = true).filter(context)

        assertEquals(401, abortedWith(context).status)
        assertEquals("unauthenticated", (abortedWith(context).entity as ProblemDetails).code)
    }

    @Test
    fun `a bearer the authenticator rejects is unauthenticated`() {
        val authenticator =
            mock<WorkloadAuthenticator> {
                on { enabled } doReturn true
                on { authenticate(any()) } doReturn null
            }
        val context = requestContext("v1/players/counts/servers", "Bearer forged")

        WorkloadAuthFilter(authenticator).filter(context)

        assertEquals(401, abortedWith(context).status)
    }

    /**
     * A caller told its credentials are wrong stops retrying, which turns a moment without keys
     * into an outage.
     */
    @Test
    fun `an unverifiable token is 503 rather than 401`() {
        val authenticator =
            mock<WorkloadAuthenticator> {
                on { enabled } doReturn true
                on { authenticate(any()) } doThrow
                    WorkloadAuthenticator.VerificationUnavailableException(null)
            }
        val context = requestContext("v1/players/counts/servers", "Bearer valid-looking")

        WorkloadAuthFilter(authenticator).filter(context)

        val response = abortedWith(context)
        assertEquals(503, response.status)
        assertEquals("verification_unavailable", (response.entity as ProblemDetails).code)
    }

    @Test
    fun `a verified caller passes through as the request's principal`() {
        val authenticator =
            mock<WorkloadAuthenticator> {
                on { enabled } doReturn true
                on { authenticate(any()) } doReturn
                    AuthClaims(
                        subject = "system:serviceaccount:api:velocity",
                        audience = listOf("grounds-services"),
                        issuer = null,
                    )
            }
        val context = requestContext("v1/players/counts/servers", "Bearer good")

        WorkloadAuthFilter(authenticator).filter(context)

        verify(context, never()).abortWith(any())
        val captor = argumentCaptor<jakarta.ws.rs.core.SecurityContext>()
        verify(context).securityContext = captor.capture()
        assertEquals("system:serviceaccount:api:velocity", captor.firstValue.userPrincipal.name)
    }

    @Test
    fun `every presence path is guarded, with or without a leading slash`() {
        listOf(
                "v1/players/sessions",
                "/v1/players/sessions",
                "v1/players/sessions/heartbeats",
                "v1/players/names",
                "v1/players/names/suggestions",
                "v1/players/counts/proxies",
                "v1/players/8f3a1c2e-4b5d-4e6f-8a9b-0c1d2e3f4a5b/session",
                "v1/players/8f3a1c2e-4b5d-4e6f-8a9b-0c1d2e3f4a5b/locale",
            )
            .forEach { path ->
                val context = requestContext(path, authorization = null)

                filter(enabled = true).filter(context)

                assertEquals(401, abortedWith(context).status, "expected $path to be guarded")
            }
    }

    /** Probes and scrapes sit outside the prefix and must stay reachable without a token. */
    @Test
    fun `health and metrics are not guarded`() {
        listOf("q/health/ready", "q/health/live", "q/metrics").forEach { path ->
            val context = requestContext(path, authorization = null)

            filter(enabled = true).filter(context)

            verify(context, never()).abortWith(any())
            verify(context, never()).securityContext = any()
        }
    }

    /** Local runs and tests, where no kubelet projects a token. */
    @Test
    fun `with auth disabled the caller is a local principal rather than a rejection`() {
        val context = requestContext("v1/players/counts/servers", authorization = null)

        filter(enabled = false).filter(context)

        verify(context, never()).abortWith(any())
        val captor = argumentCaptor<jakarta.ws.rs.core.SecurityContext>()
        verify(context).securityContext = captor.capture()
        assertEquals("local-development", captor.firstValue.userPrincipal.name)
    }

    private fun filter(enabled: Boolean): WorkloadAuthFilter =
        WorkloadAuthFilter(mock<WorkloadAuthenticator> { on { this.enabled } doReturn enabled })

    private fun requestContext(path: String, authorization: String?): ContainerRequestContext {
        val uriInfo = mock<UriInfo>()
        whenever(uriInfo.path).thenReturn(path)
        val context = mock<ContainerRequestContext>()
        whenever(context.uriInfo).thenReturn(uriInfo)
        whenever(context.getHeaderString("Authorization")).thenReturn(authorization)
        return context
    }

    private fun abortedWith(context: ContainerRequestContext): Response {
        val captor = argumentCaptor<Response>()
        verify(context).abortWith(captor.capture())
        return captor.firstValue
    }
}
