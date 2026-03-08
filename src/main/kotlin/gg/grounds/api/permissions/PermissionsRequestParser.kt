package gg.grounds.api.permissions

import java.util.UUID

object PermissionsRequestParser {
    fun parsePlayerId(value: String?): UUID? {
        return value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    fun sanitize(values: List<String>): Set<String> {
        return values.mapNotNull { it.trim().takeIf { name -> name.isNotEmpty() } }.toSet()
    }
}
