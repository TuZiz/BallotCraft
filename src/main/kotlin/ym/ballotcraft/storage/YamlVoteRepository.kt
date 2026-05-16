package ym.ballotcraft.storage

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ym.ballotcraft.model.OnlinePlayerSnapshot
import ym.ballotcraft.model.OnlinePlayerSnapshotForWrite
import ym.ballotcraft.model.StartVoteResult
import ym.ballotcraft.model.VoteChoice
import ym.ballotcraft.model.VoteRecord
import ym.ballotcraft.model.VoteResolution
import ym.ballotcraft.model.VoteResolutionType
import ym.ballotcraft.model.VoteSession
import ym.ballotcraft.model.VoteTally
import java.io.File
import java.time.Instant
import java.util.UUID

class YamlVoteRepository(
    plugin: JavaPlugin,
    yamlPath: String,
) : VoteRepository {
    private val file = File(plugin.dataFolder, yamlPath)
    private val lock = Any()
    private lateinit var config: YamlConfiguration

    override suspend fun initialize() {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                file.createNewFile()
            }
            config = YamlConfiguration.loadConfiguration(file)
            if (!config.contains("meta.next-session-id")) {
                config.set("meta.next-session-id", 1L)
                saveLocked()
            }
        }
    }

    override suspend fun findActiveSession(targetUuid: UUID, now: Instant): VoteSession? {
        return synchronized(lock) {
            sessionMap()
                .values
                .mapNotNull { loadSession(it) }
                .filter { !it.closed && it.expiresAt.isAfter(now) && it.targetUuid == targetUuid }
                .maxByOrNull { it.createdAt }
        }
    }

    override suspend fun findActiveSessionById(sessionId: Long, now: Instant): VoteSession? {
        return synchronized(lock) {
            loadSession(sessionSectionPath(sessionId))
                ?.takeIf { !it.closed && it.expiresAt.isAfter(now) }
        }
    }

    override suspend fun findActiveSessions(now: Instant): List<VoteSession> {
        return synchronized(lock) {
            sessionMap()
                .values
                .mapNotNull { loadSession(it) }
                .filter { !it.closed && it.expiresAt.isAfter(now) }
                .sortedByDescending { it.createdAt }
        }
    }

    override suspend fun createSession(
        targetUuid: UUID,
        targetName: String,
        startedByUuid: UUID,
        startedByName: String,
        reason: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): StartVoteResult {
        return synchronized(lock) {
            createSessionLocked(targetUuid, targetName, startedByUuid, startedByName, reason, createdAt, expiresAt)
        }
    }

    override suspend fun createSessionIfAbsent(
        targetUuid: UUID,
        targetName: String,
        startedByUuid: UUID,
        startedByName: String,
        reason: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): StartVoteResult? {
        return synchronized(lock) {
            val existing = sessionMap()
                .values
                .mapNotNull { loadSession(it) }
                .firstOrNull { !it.closed && it.expiresAt.isAfter(createdAt) && it.targetUuid == targetUuid }
            if (existing != null) {
                null
            } else {
                createSessionLocked(targetUuid, targetName, startedByUuid, startedByName, reason, createdAt, expiresAt)
            }
        }
    }

    override suspend fun findVote(sessionId: Long, voterUuid: UUID): VoteRecord? {
        return synchronized(lock) {
            val votePath = voteSectionPath(sessionId, voterUuid)
            if (!config.contains(votePath)) {
                return@synchronized null
            }
            loadVote(sessionId, votePath)
        }
    }

    override suspend fun tally(sessionId: Long): VoteTally {
        return synchronized(lock) {
            tallyLocked(sessionId)
        }
    }

    override suspend fun upsertVote(
        sessionId: Long,
        voterUuid: UUID,
        voterName: String,
        choice: VoteChoice,
        votedAt: Instant,
    ): VoteTally? {
        return synchronized(lock) {
            val session = loadSession(sessionSectionPath(sessionId)) ?: return@synchronized null
            if (session.closed || !session.expiresAt.isAfter(votedAt)) {
                return@synchronized null
            }
            val votePath = voteSectionPath(sessionId, voterUuid)
            config.set("$votePath.voter-uuid", voterUuid.toString())
            config.set("$votePath.voter-name", voterName)
            config.set("$votePath.choice", choice.id)
            config.set("$votePath.voted-at", votedAt.toEpochMilli())
            saveLocked()
            tallyLocked(sessionId)
        }
    }

    override suspend fun updateStarterCooldown(starterUuid: UUID, startedAt: Instant) {
        synchronized(lock) {
            config.set("cooldowns.${starterUuid}", startedAt.toEpochMilli())
            saveLocked()
        }
    }

    override suspend fun findStarterCooldown(starterUuid: UUID): Instant? {
        return synchronized(lock) {
            val epochMillis = config.getLong("cooldowns.${starterUuid}", Long.MIN_VALUE)
            if (epochMillis == Long.MIN_VALUE) null else Instant.ofEpochMilli(epochMillis)
        }
    }

    override suspend fun resolveExpiredSessions(
        now: Instant,
        blackExcessMultiplier: Double,
        minBlackVotesToSanction: Int,
    ): List<VoteResolution> {
        return synchronized(lock) {
            val resolved = mutableListOf<VoteResolution>()
            sessionMap().values.forEach { path ->
                val session = loadSession(path) ?: return@forEach
                if (!session.closed && !session.expiresAt.isAfter(now)) {
                    config.set("$path.closed", true)
                    val tally = tallyLocked(session.id)
                    val type = if (tally.blackVotes >= minBlackVotesToSanction &&
                        tally.blackVotes > (tally.redVotes * blackExcessMultiplier)
                    ) {
                        VoteResolutionType.SANCTIONED
                    } else {
                        VoteResolutionType.FAILED
                    }
                    if (type == VoteResolutionType.SANCTIONED) {
                        config.set("$path.sanction-executed", true)
                    }
                    resolved += VoteResolution(
                        session = session.copy(
                            closed = true,
                            sanctionExecuted = type == VoteResolutionType.SANCTIONED,
                        ),
                        tally = tally,
                        resolvedAt = now,
                        type = type,
                    )
                    storeResolutionLocked(
                        session = session.copy(
                            closed = true,
                            sanctionExecuted = type == VoteResolutionType.SANCTIONED,
                        ),
                        tally = tally,
                        resolvedAt = now,
                        type = type,
                    )
                }
            }
            if (resolved.isNotEmpty()) {
                saveLocked()
            }
            resolved
        }
    }

    override suspend fun tryResolveSession(
        sessionId: Long,
        tally: VoteTally,
        resolvedAt: Instant,
        type: VoteResolutionType,
    ): VoteResolution? {
        return synchronized(lock) {
            val path = sessionSectionPath(sessionId)
            val session = loadSession(path) ?: return@synchronized null
            if (session.closed) {
                return@synchronized null
            }
            config.set("$path.closed", true)
            config.set("$path.sanction-executed", type == VoteResolutionType.SANCTIONED)
            val resolvedSession = session.copy(closed = true, sanctionExecuted = type == VoteResolutionType.SANCTIONED)
            storeResolutionLocked(resolvedSession, tally, resolvedAt, type)
            saveLocked()
            VoteResolution(
                session = resolvedSession,
                tally = tally,
                resolvedAt = resolvedAt,
                type = type,
            )
        }
    }

    override suspend fun findPendingResolutions(
        serverId: String,
        now: Instant,
        limit: Int,
    ): List<VoteResolution> {
        return synchronized(lock) {
            resolutionMap().values
                .mapNotNull { loadResolution(it) }
                .sortedBy { it.resolvedAt }
                .take(limit)
        }
    }

    override suspend fun markResolutionDelivered(sessionId: Long, serverId: String) {
        synchronized(lock) {
            config.set("resolutions.$sessionId", null)
            saveLocked()
        }
    }

    override suspend fun heartbeatOnlinePlayersBatch(
        serverId: String,
        snapshots: List<OnlinePlayerSnapshotForWrite>,
        now: Instant,
    ) {
        if (snapshots.isEmpty()) {
            return
        }
        synchronized(lock) {
            snapshots.forEach { snapshot ->
                val path = "online-players.${snapshot.uuid}"
                config.set("$path.server-id", snapshot.serverId.ifBlank { serverId })
                config.set("$path.player-uuid", snapshot.uuid.toString())
                config.set("$path.player-name", snapshot.name)
                config.set("$path.exempt-from-vote", snapshot.exempt)
                val seenAt = snapshot.seenAt.takeUnless { it == Instant.EPOCH } ?: now
                config.set("$path.last-seen-at", seenAt.toEpochMilli())
            }
            saveLocked()
        }
    }

    override suspend fun removeOnlinePlayersBatch(serverId: String, uuids: Collection<UUID>) {
        if (uuids.isEmpty()) {
            return
        }
        synchronized(lock) {
            uuids.forEach { playerUuid ->
                val path = "online-players.$playerUuid"
                if (serverId == config.getString("$path.server-id")) {
                    config.set(path, null)
                }
            }
            saveLocked()
        }
    }

    override suspend fun findOnlinePlayerByName(name: String, expireAfter: Instant): OnlinePlayerSnapshot? {
        return synchronized(lock) {
            onlinePlayerMap().values
                .mapNotNull { path ->
                    val lastSeenAt = config.getLong("$path.last-seen-at", Long.MIN_VALUE)
                    if (lastSeenAt == Long.MIN_VALUE || !Instant.ofEpochMilli(lastSeenAt).isAfter(expireAfter)) {
                        null
                    } else {
                        val playerName = config.getString("$path.player-name") ?: return@mapNotNull null
                        val playerUuid = config.getString("$path.player-uuid") ?: return@mapNotNull null
                        OnlinePlayerSnapshot(
                            playerUuid = UUID.fromString(playerUuid),
                            playerName = playerName,
                            exemptFromVote = config.getBoolean("$path.exempt-from-vote", false),
                        )
                    }
                }
                .firstOrNull { it.playerName.equals(name, ignoreCase = true) }
        }
    }

    override suspend fun purgeExpiredOnlinePlayers(serverId: String, expireBefore: Instant, limit: Int): Int {
        return synchronized(lock) {
            var changed = false
            var removed = 0
            onlinePlayerMap().values.forEach { path ->
                if (removed >= limit.coerceAtLeast(1)) {
                    return@forEach
                }
                val lastSeenAt = config.getLong("$path.last-seen-at", Long.MIN_VALUE)
                val storedServerId = config.getString("$path.server-id")
                if (
                    serverId == storedServerId &&
                    lastSeenAt != Long.MIN_VALUE &&
                    Instant.ofEpochMilli(lastSeenAt).isBefore(expireBefore)
                ) {
                    config.set(path, null)
                    changed = true
                    removed++
                }
            }
            if (changed) {
                saveLocked()
            }
            removed
        }
    }

    private fun nextSessionId(): Long {
        val current = config.getLong("meta.next-session-id", 1L)
        config.set("meta.next-session-id", current + 1L)
        return current
    }

    private fun createSessionLocked(
        targetUuid: UUID,
        targetName: String,
        startedByUuid: UUID,
        startedByName: String,
        reason: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): StartVoteResult {
        val sessionId = nextSessionId()
        val path = sessionSectionPath(sessionId)
        config.set("$path.id", sessionId)
        config.set("$path.target-uuid", targetUuid.toString())
        config.set("$path.target-name", targetName)
        config.set("$path.started-by-uuid", startedByUuid.toString())
        config.set("$path.started-by-name", startedByName)
        config.set("$path.reason", reason)
        config.set("$path.created-at", createdAt.toEpochMilli())
        config.set("$path.expires-at", expiresAt.toEpochMilli())
        config.set("$path.closed", false)
        config.set("$path.sanction-executed", false)
        saveLocked()

        return StartVoteResult(
            session = VoteSession(
                id = sessionId,
                targetUuid = targetUuid,
                targetName = targetName,
                startedByUuid = startedByUuid,
                startedByName = startedByName,
                reason = reason,
                createdAt = createdAt,
                expiresAt = expiresAt,
                closed = false,
                sanctionExecuted = false,
            ),
            tally = VoteTally(redVotes = 0, blackVotes = 0),
        )
    }

    private fun tallyLocked(sessionId: Long): VoteTally {
        var redVotes = 0
        var blackVotes = 0
        voteMap(sessionId).values.forEach { path ->
            when (VoteChoice.fromId(config.getString("$path.choice"))) {
                VoteChoice.RED -> redVotes++
                VoteChoice.BLACK -> blackVotes++
                null -> Unit
            }
        }
        return VoteTally(redVotes = redVotes, blackVotes = blackVotes)
    }

    private fun sessionMap(): Map<String, String> {
        val section = config.getConfigurationSection("sessions") ?: return emptyMap()
        return section.getKeys(false).associateWith { key -> "sessions.$key" }
    }

    private fun voteMap(sessionId: Long): Map<String, String> {
        val section = config.getConfigurationSection("votes.$sessionId") ?: return emptyMap()
        return section.getKeys(false).associateWith { key -> "votes.$sessionId.$key" }
    }

    private fun resolutionMap(): Map<String, String> {
        val section = config.getConfigurationSection("resolutions") ?: return emptyMap()
        return section.getKeys(false).associateWith { key -> "resolutions.$key" }
    }

    private fun onlinePlayerMap(): Map<String, String> {
        val section = config.getConfigurationSection("online-players") ?: return emptyMap()
        return section.getKeys(false).associateWith { key -> "online-players.$key" }
    }

    private fun loadSession(path: String): VoteSession? {
        if (!config.contains(path)) {
            return null
        }
        return VoteSession(
            id = config.getLong("$path.id"),
            targetUuid = UUID.fromString(config.getString("$path.target-uuid") ?: return null),
            targetName = config.getString("$path.target-name") ?: return null,
            startedByUuid = UUID.fromString(config.getString("$path.started-by-uuid") ?: return null),
            startedByName = config.getString("$path.started-by-name") ?: return null,
            reason = config.getString("$path.reason") ?: "",
            createdAt = Instant.ofEpochMilli(config.getLong("$path.created-at")),
            expiresAt = Instant.ofEpochMilli(config.getLong("$path.expires-at")),
            closed = config.getBoolean("$path.closed", false),
            sanctionExecuted = config.getBoolean("$path.sanction-executed", false),
        )
    }

    private fun loadVote(sessionId: Long, path: String): VoteRecord? {
        val voterUuid = UUID.fromString(config.getString("$path.voter-uuid") ?: return null)
        return VoteRecord(
            sessionId = sessionId,
            voterUuid = voterUuid,
            voterName = config.getString("$path.voter-name") ?: return null,
            choice = VoteChoice.fromId(config.getString("$path.choice")) ?: return null,
            votedAt = Instant.ofEpochMilli(config.getLong("$path.voted-at")),
        )
    }

    private fun loadResolution(path: String): VoteResolution? {
        val sessionId = config.getLong("$path.session-id", Long.MIN_VALUE)
        if (sessionId == Long.MIN_VALUE) {
            return null
        }
        val session = VoteSession(
            id = sessionId,
            targetUuid = UUID.fromString(config.getString("$path.target-uuid") ?: return null),
            targetName = config.getString("$path.target-name") ?: return null,
            startedByUuid = UUID.fromString(config.getString("$path.started-by-uuid") ?: return null),
            startedByName = config.getString("$path.started-by-name") ?: return null,
            reason = config.getString("$path.reason") ?: "",
            createdAt = Instant.ofEpochMilli(config.getLong("$path.created-at")),
            expiresAt = Instant.ofEpochMilli(config.getLong("$path.expires-at")),
            closed = config.getBoolean("$path.closed", true),
            sanctionExecuted = config.getBoolean("$path.sanction-executed", false),
        )
        return VoteResolution(
            session = session,
            tally = VoteTally(
                redVotes = config.getInt("$path.red-votes", 0),
                blackVotes = config.getInt("$path.black-votes", 0),
            ),
            resolvedAt = Instant.ofEpochMilli(config.getLong("$path.resolved-at")),
            type = runCatching {
                VoteResolutionType.valueOf(config.getString("$path.type") ?: return null)
            }.getOrNull() ?: return null,
        )
    }

    private fun storeResolutionLocked(
        session: VoteSession,
        tally: VoteTally,
        resolvedAt: Instant,
        type: VoteResolutionType,
    ) {
        val path = "resolutions.${session.id}"
        config.set("$path.session-id", session.id)
        config.set("$path.target-uuid", session.targetUuid.toString())
        config.set("$path.target-name", session.targetName)
        config.set("$path.started-by-uuid", session.startedByUuid.toString())
        config.set("$path.started-by-name", session.startedByName)
        config.set("$path.reason", session.reason)
        config.set("$path.created-at", session.createdAt.toEpochMilli())
        config.set("$path.expires-at", session.expiresAt.toEpochMilli())
        config.set("$path.closed", true)
        config.set("$path.sanction-executed", type == VoteResolutionType.SANCTIONED)
        config.set("$path.red-votes", tally.redVotes)
        config.set("$path.black-votes", tally.blackVotes)
        config.set("$path.resolved-at", resolvedAt.toEpochMilli())
        config.set("$path.type", type.name)
    }

    private fun sessionSectionPath(sessionId: Long): String = "sessions.$sessionId"

    private fun voteSectionPath(sessionId: Long, voterUuid: UUID): String = "votes.$sessionId.$voterUuid"

    private fun saveLocked() {
        config.save(file)
    }
}
