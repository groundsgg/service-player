package gg.grounds.rest

import gg.grounds.presence.PresenceService
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider

/**
 * The session store could not answer, rendered as 503.
 *
 * Deliberately not an empty answer with 200: a count of zero reads as "nobody is online anywhere",
 * which is a plausible number that callers will render. 503 lets them say they could not ask.
 */
@Provider
class PresenceUnavailableMapper : ExceptionMapper<PresenceService.PresenceUnavailableException> {
    override fun toResponse(exception: PresenceService.PresenceUnavailableException): Response =
        Response.status(503)
            .type(ProblemDetails.PROBLEM_JSON)
            .entity(
                ProblemDetails(
                    title = "Service unavailable",
                    status = 503,
                    detail = exception.message,
                    code = "store_unavailable",
                )
            )
            .build()
}

/** Argument validation, thrown by the resources before anything is read or written. */
class InvalidRequestException(message: String) : RuntimeException(message)

@Provider
class InvalidRequestMapper : ExceptionMapper<InvalidRequestException> {
    override fun toResponse(exception: InvalidRequestException): Response =
        Response.status(Response.Status.BAD_REQUEST)
            .type(ProblemDetails.PROBLEM_JSON)
            .entity(
                ProblemDetails(
                    title = "Invalid request",
                    status = Response.Status.BAD_REQUEST.statusCode,
                    detail = exception.message,
                    code = "invalid_request",
                )
            )
            .build()
}
