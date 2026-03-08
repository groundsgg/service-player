package gg.grounds.time

import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class TimeProvider {
    fun now(): Instant = Instant.now()
}
