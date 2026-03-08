package gg.grounds.persistence.permissions

import java.sql.Statement

object BatchUpdateHelper {
    fun countSuccessful(results: IntArray): Int {
        return results.count { it == Statement.SUCCESS_NO_INFO || it > 0 }
    }
}
