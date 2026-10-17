package gg.grounds.presence

import gg.grounds.domain.PlayerSession
import gg.grounds.persistence.PlayerNameRepository
import gg.grounds.persistence.PlayerSessionRepository
import gg.grounds.persistence.PlayerSessionRepository.CountPlayersByProxyResult
import gg.grounds.persistence.PlayerSessionRepository.CountPlayersByServerResult
import gg.grounds.persistence.PlayerSessionRepository.DeleteSessionResult
import gg.grounds.persistence.PlayerSessionRepository.ProxyPlayerCount
import gg.grounds.persistence.PlayerSessionRepository.ServerPlayerCount
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.math.min
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Everything the presence API does, expressed without reference to any wire format.
 *
 * The service is reachable over HTTP and — until every caller has moved — over gRPC. Both are
 * adapters over this class rather than two implementations of the same rules: a login that is
 * accepted on one transport and rejected on the other is the kind of divergence that only shows up
 * as a player who cannot join from one proxy.
 *
 * Ids arrive as strings on purpose. A malformed id is a routine event here (an older plugin, a
 * hand-typed name) and each operation has an established answer for it — usually its negative
 * outcome rather than an error — so parsing lives in one place with those answers.
 */
@ApplicationScoped
class PresenceService
@Inject
constructor(
    private val repository: PlayerSessionRepository,
    private val nameRepository: PlayerNameRepository,
) {
    @ConfigProperty(name = "grounds.player.sessions.ttl", defaultValue = "90s")
    private lateinit var sessionTtl: Duration

    /**
     * Claims the network-wide session for a player.
     *
     * Rejecting a second login is the point: the session table is what makes "already online" a
     * fact rather than one proxy's opinion.
     */
    fun login(
        playerId: String?,
        playerName: String?,
        proxyId: String?,
        region: String?,
    ): LoginOutcome {
        val id = parsePlayerId(playerId) ?: return LoginOutcome.InvalidPlayerId

        val now = Instant.now()
        val name = blankToNull(playerName)
        val session =
            PlayerSession(id, now, now, name, blankToNull(proxyId), region = blankToNull(region))
        if (repository.insertSession(session)) {
            if (name != null) nameRepository.upsertName(id, name, now)
            LOG.infof("Player session created (playerId=%s, result=accepted)", id)
            return LoginOutcome.Accepted
        }

        val existing = repository.findByPlayerId(id)
        if (existing != null) {
            if (existing.sessionId != null) {
                LOG.infof(
                    "Player session rejected (playerId=%s, reason=owned_by_exact_session)",
                    id,
                )
                return LoginOutcome.AlreadyOnline
            }
            val stale = existing.lastSeenAt.isBefore(now.minus(sessionTtl))
            if (stale) {
                if (repository.replaceSession(session)) {
                    if (name != null) nameRepository.upsertName(id, name, now)
                    LOG.infof(
                        "Player session expired and replaced (playerId=%s, lastSeenAt=%s)",
                        id,
                        existing.lastSeenAt,
                    )
                    return LoginOutcome.Accepted
                }
                LOG.errorf(
                    "Player session replacement failed (playerId=%s, reason=replace_failed)",
                    id,
                )
                return LoginOutcome.Failed("unable to replace player session")
            }

            LOG.infof("Player session rejected (playerId=%s, reason=already_online)", id)
            return LoginOutcome.AlreadyOnline
        }

        LOG.errorf("Player session verification failed (playerId=%s)", id)
        return LoginOutcome.Failed("unable to verify player session")
    }

    fun claimExactSession(
        playerId: String?,
        sessionId: String?,
        playerName: String?,
        proxyId: String?,
        region: String?,
    ): ExactSessionClaimOutcome {
        val id = parsePlayerId(playerId) ?: return ExactSessionClaimOutcome.InvalidId
        val connectionId = parsePlayerId(sessionId) ?: return ExactSessionClaimOutcome.InvalidId
        val now = Instant.now()
        val name = blankToNull(playerName)
        val session =
            PlayerSession(
                playerId = id,
                connectedAt = now,
                lastSeenAt = now,
                playerName = name,
                proxyId = blankToNull(proxyId),
                region = blankToNull(region),
                sessionId = connectionId,
            )
        return when (repository.claimSession(session, now.minus(sessionTtl))) {
            PlayerSessionRepository.ClaimSessionResult.Claimed -> {
                if (name != null) nameRepository.upsertName(id, name, now)
                ExactSessionClaimOutcome.Accepted(id, connectionId, now.plus(sessionTtl))
            }
            PlayerSessionRepository.ClaimSessionResult.AlreadyOnline ->
                ExactSessionClaimOutcome.AlreadyOnline
            PlayerSessionRepository.ClaimSessionResult.Error ->
                ExactSessionClaimOutcome.Failed("unable to claim player session")
        }
    }

    fun logoutExactSession(playerId: String?, sessionId: String?): LogoutOutcome {
        val id = parsePlayerId(playerId) ?: return LogoutOutcome.InvalidPlayerId
        val connectionId = parsePlayerId(sessionId) ?: return LogoutOutcome.InvalidPlayerId
        return when (repository.deleteSessionForSession(id, connectionId)) {
            DeleteSessionResult.REMOVED -> LogoutOutcome.Removed
            DeleteSessionResult.NOT_FOUND -> LogoutOutcome.NotFound
            DeleteSessionResult.ERROR -> LogoutOutcome.Failed("unable to remove player session")
        }
    }

    fun updateServerExactSession(
        playerId: String?,
        sessionId: String?,
        serverName: String?,
    ): Boolean {
        val id = parsePlayerId(playerId) ?: return false
        val connectionId = parsePlayerId(sessionId) ?: return false
        val server = blankToNull(serverName) ?: return false
        return repository.updateServerForSession(id, connectionId, server)
    }

    fun issueSessionHandoff(playerId: String?, sessionId: String?): HandoffIssueOutcome {
        val id = parsePlayerId(playerId) ?: return HandoffIssueOutcome.InvalidId
        val connectionId = parsePlayerId(sessionId) ?: return HandoffIssueOutcome.InvalidId
        val now = Instant.now()
        val expiresAt = now.plus(HANDOFF_TTL)
        val randomBytes = ByteArray(HANDOFF_TOKEN_BYTES).also(SECURE_RANDOM::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        return when (
            repository.createHandoff(
                playerId = id,
                sessionId = connectionId,
                tokenHash = hashHandoffToken(token),
                expiresAt = expiresAt,
                activeAfter = now.minus(sessionTtl),
            )
        ) {
            PlayerSessionRepository.CreateHandoffResult.Created ->
                HandoffIssueOutcome.Issued(token, expiresAt)
            PlayerSessionRepository.CreateHandoffResult.NoActiveSession ->
                HandoffIssueOutcome.NoActiveSession
            PlayerSessionRepository.CreateHandoffResult.Error ->
                HandoffIssueOutcome.Failed("unable to create session handoff")
        }
    }

    fun consumeSessionHandoff(
        playerId: String?,
        sessionId: String?,
        handoffToken: String?,
        playerName: String?,
        proxyId: String?,
        region: String?,
    ): ExactSessionClaimOutcome {
        val id = parsePlayerId(playerId) ?: return ExactSessionClaimOutcome.InvalidId
        val connectionId = parsePlayerId(sessionId) ?: return ExactSessionClaimOutcome.InvalidId
        val token =
            handoffToken?.takeIf(HANDOFF_TOKEN_PATTERN::matches)
                ?: return ExactSessionClaimOutcome.InvalidId
        val now = Instant.now()
        val name = blankToNull(playerName)
        return when (
            repository.consumeHandoff(
                tokenHash = hashHandoffToken(token),
                playerId = id,
                sessionId = connectionId,
                playerName = name,
                proxyId = blankToNull(proxyId),
                region = blankToNull(region),
                now = now,
                activeAfter = now.minus(sessionTtl),
            )
        ) {
            PlayerSessionRepository.ConsumeHandoffResult.Consumed -> {
                if (name != null) nameRepository.upsertName(id, name, now)
                ExactSessionClaimOutcome.Accepted(id, connectionId, now.plus(sessionTtl))
            }
            PlayerSessionRepository.ConsumeHandoffResult.NotFound ->
                ExactSessionClaimOutcome.AlreadyOnline
            PlayerSessionRepository.ConsumeHandoffResult.Error ->
                ExactSessionClaimOutcome.Failed("unable to consume session handoff")
        }
    }

    /**
     * Releases a session.
     *
     * Scoped to the calling proxy when it says who it is: a logout that raced a transfer must not
     * delete the session the next proxy just created. Unscoped when [proxyId] is absent, which is
     * what an older plugin sends.
     */
    fun logout(playerId: String?, proxyId: String?): LogoutOutcome {
        val id = parsePlayerId(playerId) ?: return LogoutOutcome.InvalidPlayerId

        val owner = blankToNull(proxyId)
        val deleted =
            if (owner != null) repository.deleteSessionOwnedBy(id, owner)
            else repository.deleteSession(id)

        return when (deleted) {
            DeleteSessionResult.REMOVED -> {
                LOG.infof("Player session removed (playerId=%s, result=logout)", id)
                LogoutOutcome.Removed
            }
            DeleteSessionResult.NOT_FOUND -> LogoutOutcome.NotFound
            DeleteSessionResult.ERROR -> {
                LOG.errorf("Player session removal failed (playerId=%s, reason=delete_failed)", id)
                LogoutOutcome.Failed("unable to remove player session")
            }
        }
    }

    /**
     * Who and where a player is. A proxy asks this for someone who is not connected to it — it has
     * no other way to know they exist.
     *
     * An unparseable id resolves to "not found" rather than an error: the callers are Velocity
     * command handlers, and there is nothing useful for them to do differently.
     */
    fun findSession(playerId: String?): PlayerSession? =
        parsePlayerId(playerId)?.let(repository::findByPlayerId)

    /**
     * Name to session — the lookup behind `/msg <name>` and `/party invite <name>` when the target
     * is on another proxy. Case-insensitive: Minecraft names are unique, their casing is not.
     */
    fun resolveName(playerName: String?): PlayerSession? =
        blankToNull(playerName)?.let(repository::findByName)

    /**
     * Tab-complete. A prefix search with a hard cap ([MAX_SUGGEST_LIMIT]) — deliberately NOT a list
     * of everyone online: Velocity fires tab-complete on every keystroke, so at 10k players a
     * roster would be a large response sent thousands of times a second, scanning the table each
     * time. A blank prefix returns nothing rather than everything, for the same reason.
     */
    fun suggestNames(prefix: String?, limit: Int): List<String> {
        val search = blankToNull(prefix) ?: return emptyList()
        return repository.suggestNames(search, cappedSuggestLimit(limit))
    }

    /**
     * Follows a player across backend servers, so a session says where they actually are — a party
     * warp needs the target's server, and only the proxy holding them knows when it changes.
     */
    fun updateServer(playerId: String?, serverName: String?): Boolean {
        val id = parsePlayerId(playerId) ?: return false
        val server = blankToNull(serverName) ?: return false
        return repository.updateServer(id, server)
    }

    /**
     * Turns ids back into names for players who are NOT online — the durable index behind a
     * leaderboard, match history, or anything else that outlives a session. Ids never seen are
     * simply absent from the result, not mapped to a placeholder.
     */
    fun lookupNames(playerIds: List<String>): Map<UUID, String> {
        val ids = playerIds.asSequence().take(MAX_LOOKUP_IDS).mapNotNull(::parsePlayerId).toSet()
        if (ids.isEmpty()) {
            return emptyMap()
        }
        return nameRepository.findNames(ids)
    }

    /**
     * Network-wide players per backend server. Velocity can only count the players connected to
     * itself, so with more than one proxy in front of a server each of them sees only part of it —
     * this reads the truth out of the session table instead.
     *
     * A repository failure throws rather than answering zero. The other operations collapse
     * "failed" into their negative answer — not found, not updated — because for them the caller
     * acts the same either way. A count is different: an empty answer reads as "nobody is online
     * anywhere", which is a perfectly plausible number that callers will render. Handing that out
     * when the database is unreachable is how a proxy came to print a player count it had no
     * business being sure of.
     */
    fun countPlayersByServer(): ServerCounts =
        when (val result = repository.countPlayersByServer()) {
            is CountPlayersByServerResult.Counted -> ServerCounts(result.servers, result.total)
            CountPlayersByServerResult.Error -> throw PresenceUnavailableException()
        }

    /**
     * How the network is spread across proxies, and where those proxies are.
     *
     * The sibling call groups the same players by backend server. Neither caller wants both:
     * `/agones` asks which servers are busy, `/online` asks which proxies and regions hold people.
     */
    fun countPlayersByProxy(): ProxyCounts =
        when (val result = repository.countPlayersByProxy()) {
            is CountPlayersByProxyResult.Counted -> ProxyCounts(result.proxies, result.total)
            CountPlayersByProxyResult.Error -> throw PresenceUnavailableException()
        }

    /**
     * A player's chosen interface language.
     *
     * Null is a normal answer — the player has never chosen one, and the caller falls back to the
     * client's announced locale. A read failure resolves to null too: a language preference is not
     * worth failing a join over, so unlike the counts this does not throw.
     */
    fun getLocale(playerId: String?): String? =
        parsePlayerId(playerId)?.let(nameRepository::getLocale)

    /**
     * Stores a language preference against the durable player row, so it survives logout. A null or
     * blank value clears it. Any other value is stored verbatim — the proxy validates the tag
     * against the languages it actually ships before calling here.
     */
    fun setLocale(playerId: String?, locale: String?): Boolean {
        val id = parsePlayerId(playerId) ?: return false
        return nameRepository.setLocale(id, blankToNull(locale))
    }

    /**
     * A player's preferred region code. Null when none is set; a read failure resolves to null too,
     * like [getLocale].
     */
    fun getPreferredRegion(playerId: String?): String? =
        parsePlayerId(playerId)?.let(nameRepository::getPreferredRegion)

    /**
     * Stores a preferred region against the durable player row. A null or blank value clears it.
     * The caller has already checked the code's shape; membership in the region catalogue is not
     * checked here — that catalogue lives with the proxies.
     */
    fun setPreferredRegion(playerId: String?, region: String?): Boolean {
        val id = parsePlayerId(playerId) ?: return false
        return nameRepository.setPreferredRegion(id, blankToNull(region))
    }

    /**
     * The outcomes carry no wording for a malformed request: each transport names the offending
     * field the way its own contract spells it (`player_id` on the wire, `playerId` in JSON), and a
     * shared string would have to be right for both.
     */
    sealed interface LoginOutcome {
        data object Accepted : LoginOutcome

        data object AlreadyOnline : LoginOutcome

        data object InvalidPlayerId : LoginOutcome

        data class Failed(val message: String) : LoginOutcome
    }

    sealed interface ExactSessionClaimOutcome {
        data class Accepted(val playerId: UUID, val sessionId: UUID, val leaseExpiresAt: Instant) :
            ExactSessionClaimOutcome

        data object AlreadyOnline : ExactSessionClaimOutcome

        data object InvalidId : ExactSessionClaimOutcome

        data class Failed(val message: String) : ExactSessionClaimOutcome
    }

    sealed interface HandoffIssueOutcome {
        data class Issued(val token: String, val expiresAt: Instant) : HandoffIssueOutcome

        data object NoActiveSession : HandoffIssueOutcome

        data object InvalidId : HandoffIssueOutcome

        data class Failed(val message: String) : HandoffIssueOutcome
    }

    sealed interface LogoutOutcome {
        data object Removed : LogoutOutcome

        data object NotFound : LogoutOutcome

        data object InvalidPlayerId : LogoutOutcome

        data class Failed(val message: String) : LogoutOutcome
    }

    data class ServerCounts(val servers: List<ServerPlayerCount>, val total: Int)

    data class ProxyCounts(val proxies: List<ProxyPlayerCount>, val total: Int)

    /** The session store could not answer a count. Never used for "the answer is zero". */
    class PresenceUnavailableException : RuntimeException("player session count is unavailable")

    companion object {
        private const val HANDOFF_TOKEN_BYTES = 32
        private val HANDOFF_TTL = Duration.ofSeconds(15)
        private val HANDOFF_TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{43}")
        private val SECURE_RANDOM = SecureRandom()
        private val LOG = Logger.getLogger(PresenceService::class.java)

        private const val MAX_SUGGEST_LIMIT = 25

        /**
         * A leaderboard page or a match's roster is at most a few dozen names; 100 is generous
         * headroom for that without letting one call ask for an unbounded id list.
         */
        const val MAX_LOOKUP_IDS = 100

        /** Clamps an untrusted client-supplied limit; `<= 0` falls back to the maximum. */
        internal fun cappedSuggestLimit(limit: Int): Int =
            if (limit <= 0) MAX_SUGGEST_LIMIT else min(limit, MAX_SUGGEST_LIMIT)

        internal fun blankToNull(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }

        internal fun parsePlayerId(value: String?): UUID? =
            blankToNull(value)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        private fun hashHandoffToken(token: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII))
    }
}
