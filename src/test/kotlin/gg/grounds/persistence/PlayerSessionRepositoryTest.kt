package gg.grounds.persistence

import gg.grounds.persistence.PlayerSessionRepository.Companion.escapeLikePattern
import gg.grounds.persistence.PlayerSessionRepository.Companion.prefixUpperBound
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PlayerSessionRepositoryTest {
    @Test
    fun `escapeLikePattern leaves plain text untouched`() {
        assertEquals("hendrik", escapeLikePattern("hendrik"))
    }

    @Test
    fun `escapeLikePattern escapes percent so it is not a wildcard`() {
        assertEquals("100\\%", escapeLikePattern("100%"))
    }

    @Test
    fun `escapeLikePattern escapes underscore so it is not a single-char wildcard`() {
        assertEquals("foo\\_bar", escapeLikePattern("foo_bar"))
    }

    @Test
    fun `escapeLikePattern escapes backslash before escaping metacharacters`() {
        assertEquals("foo\\\\\\%bar", escapeLikePattern("foo\\%bar"))
    }

    @Test
    fun `escapeLikePattern handles a bare percent prefix without matching everything`() {
        assertEquals("\\%", escapeLikePattern("%"))
    }

    @Test
    fun `prefixUpperBound bounds the range that the index scans`() {
        // "dah" must match dahendriik but stop before anything starting with "dai".
        assertEquals("dai", prefixUpperBound("dah"))
        assertEquals("b", prefixUpperBound("a"))
        assertEquals("az", prefixUpperBound("ay"))
    }

    @Test
    fun `prefixUpperBound carries over when the last character is the highest one`() {
        val maxed = "a" + Char.MAX_VALUE
        assertEquals("b", prefixUpperBound(maxed))
    }

    @Test
    fun `prefixUpperBound is null when no bound exists`() {
        // Nothing to narrow the scan with — the caller must not run the query.
        assertNull(prefixUpperBound(""))
        assertNull(prefixUpperBound(Char.MAX_VALUE.toString()))
    }

    @Test
    fun `countPlayersByServer counts two players on the same server as one row`() {
        val resultSet =
            mockCountResultSet(
                isTotal = listOf(0, 1),
                serverName = listOf("lobby"),
                players = listOf(2, 2),
            )
        val repository = repositoryWith(resultSet)

        val result = repository.countPlayersByServer()

        assertTrue(result is PlayerSessionRepository.CountPlayersByServerResult.Counted)
        val counted = result as PlayerSessionRepository.CountPlayersByServerResult.Counted
        assertEquals(listOf(PlayerSessionRepository.ServerPlayerCount("lobby", 2)), counted.servers)
        assertEquals(2, counted.total)
    }

    @Test
    fun `countPlayersByServer groups players on different servers separately`() {
        val resultSet =
            mockCountResultSet(
                isTotal = listOf(0, 0, 1),
                serverName = listOf("lobby", "arena"),
                players = listOf(1, 1, 2),
            )
        val repository = repositoryWith(resultSet)

        val result = repository.countPlayersByServer()

        assertTrue(result is PlayerSessionRepository.CountPlayersByServerResult.Counted)
        val counted = result as PlayerSessionRepository.CountPlayersByServerResult.Counted
        assertEquals(
            listOf(
                PlayerSessionRepository.ServerPlayerCount("lobby", 1),
                PlayerSessionRepository.ServerPlayerCount("arena", 1),
            ),
            counted.servers,
        )
        assertEquals(2, counted.total)
    }

    @Test
    fun `countPlayersByServer counts a player with no server in total but not in servers`() {
        // Row order: the "no server yet" group (is_total=0, server_name=NULL) is skipped from the
        // servers list but its player is still folded into the total row.
        val resultSet =
            mockCountResultSet(
                isTotal = listOf(0, 1),
                serverName = listOf(null),
                players = listOf(1, 1),
            )
        val repository = repositoryWith(resultSet)

        val result = repository.countPlayersByServer()

        assertTrue(result is PlayerSessionRepository.CountPlayersByServerResult.Counted)
        val counted = result as PlayerSessionRepository.CountPlayersByServerResult.Counted
        assertEquals(emptyList<PlayerSessionRepository.ServerPlayerCount>(), counted.servers)
        assertEquals(1, counted.total)
    }

    @Test
    fun `countPlayersByServer reports the error result on a SQLException`() {
        val dataSource = mock<DataSource>()
        whenever(dataSource.connection).thenThrow(SQLException("boom"))
        val repository = PlayerSessionRepository(dataSource)

        val result = repository.countPlayersByServer()

        assertEquals(PlayerSessionRepository.CountPlayersByServerResult.Error, result)
    }

    /**
     * Builds a [ResultSet] mock that replays [isTotal] rows in order, each paired with the matching
     * entry in [players]; [serverName] supplies `server_name` only for the non-total rows (the
     * total row never reads that column, matching the real repository code path).
     */
    private fun mockCountResultSet(
        isTotal: List<Int>,
        serverName: List<String?>,
        players: List<Int>,
    ): ResultSet {
        val resultSet = mock<ResultSet>()
        val nextAnswers = isTotal.map { true }.toTypedArray() + false
        whenever(resultSet.next()).thenReturn(nextAnswers[0], *nextAnswers.drop(1).toTypedArray())
        whenever(resultSet.getInt("is_total"))
            .thenReturn(isTotal[0], *isTotal.drop(1).toTypedArray())
        whenever(resultSet.getInt("players"))
            .thenReturn(players[0], *players.drop(1).toTypedArray())
        if (serverName.isNotEmpty()) {
            whenever(resultSet.getString("server_name"))
                .thenReturn(serverName[0], *serverName.drop(1).toTypedArray())
        }
        return resultSet
    }

    private fun repositoryWith(resultSet: ResultSet): PlayerSessionRepository {
        val dataSource = mock<DataSource>()
        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        whenever(dataSource.connection).thenReturn(connection)
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        whenever(statement.executeQuery()).thenReturn(resultSet)
        return PlayerSessionRepository(dataSource)
    }
}
