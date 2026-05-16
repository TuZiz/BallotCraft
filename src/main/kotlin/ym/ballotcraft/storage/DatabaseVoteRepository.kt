package ym.ballotcraft.storage

import ym.ballotcraft.model.OnlinePlayerSnapshot
import ym.ballotcraft.model.StartVoteResult
import ym.ballotcraft.model.VoteChoice
import ym.ballotcraft.model.VoteRecord
import ym.ballotcraft.model.VoteResolution
import ym.ballotcraft.model.VoteResolutionType
import ym.ballotcraft.model.VoteSession
import ym.ballotcraft.model.VoteTally
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

class DatabaseVoteRepository(
    private val database: HikariDatabase,
    private val logger: Logger,
) : VoteRepository {
    private val sessionsTable = "${database.tablePrefix}sessions"
    private val votesTable = "${database.tablePrefix}votes"
    private val cooldownTable = "${database.tablePrefix}starter_cooldowns"
    private val onlinePlayersTable = "${database.tablePrefix}online_players"
    private val resolutionsTable = "${database.tablePrefix}resolutions"
    private val resolutionDeliveriesTable = "${database.tablePrefix}resolution_deliveries"

    override suspend fun initialize() {
        database.start()
        database.connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS $sessionsTable (
                        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        target_uuid VARCHAR(36) NOT NULL,
                        target_name VARCHAR(16) NOT NULL,
                        started_by_uuid VARCHAR(36) NOT NULL,
                        started_by_name VARCHAR(16) NOT NULL,
                        reason VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP NOT NULL,
                        expires_at TIMESTAMP NOT NULL,
                        closed TINYINT(1) NOT NULL DEFAULT 0,
                        sanction_executed TINYINT(1) NOT NULL DEFAULT 0,
                        active_token VARCHAR(64) DEFAULT NULL,
                        INDEX idx_target_active (target_uuid, closed, expires_at),
                        UNIQUE KEY uk_active_token (active_token)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS $votesTable (
                        session_id BIGINT NOT NULL,
                        voter_uuid VARCHAR(36) NOT NULL,
                        voter_name VARCHAR(16) NOT NULL,
                        choice VARCHAR(8) NOT NULL,
                        voted_at TIMESTAMP NOT NULL,
                        PRIMARY KEY (session_id, voter_uuid),
                        INDEX idx_session_choice (session_id, choice),
                        CONSTRAINT fk_${database.tablePrefix}vote_session
                            FOREIGN KEY (session_id) REFERENCES $sessionsTable(id)
                            ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS $cooldownTable (
                        starter_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        last_started_at TIMESTAMP NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS $onlinePlayersTable (
                        player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        player_name VARCHAR(16) NOT NULL,
                        server_id VARCHAR(64) NOT NULL,
                        exempt_from_vote TINYINT(1) NOT NULL DEFAULT 0,
                        last_seen_at TIMESTAMP NOT NULL,
                        INDEX idx_online_name (player_name),
                        INDEX idx_online_seen (last_seen_at)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS $resolutionsTable (
                        session_id BIGINT NOT NULL PRIMARY KEY,
                        target_uuid VARCHAR(36) NOT NULL,
                        target_name VARCHAR(16) NOT NULL,
                        started_by_uuid VARCHAR(36) NOT NULL,
                        started_by_name VARCHAR(16) NOT NULL,
                        reason VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP NOT NULL,
                        expires_at TIMESTAMP NOT NULL,
                        resolved_at TIMESTAMP NOT NULL,
                        resolution_type VARCHAR(16) NOT NULL,
                        red_votes INT NOT NULL,
                        black_votes INT NOT NULL,
                        sanction_executed TINYINT(1) NOT NULL DEFAULT 0,
                        INDEX idx_resolved_at (resolved_at)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS $resolutionDeliveriesTable (
                        session_id BIGINT NOT NULL,
                        server_id VARCHAR(64) NOT NULL,
                        delivered_at TIMESTAMP NOT NULL,
                        PRIMARY KEY (session_id, server_id),
                        CONSTRAINT fk_${database.tablePrefix}resolution_delivery
                            FOREIGN KEY (session_id) REFERENCES $resolutionsTable(session_id)
                            ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
            ensureColumn(
                connection,
                onlinePlayersTable,
                "exempt_from_vote",
                "ALTER TABLE $onlinePlayersTable ADD COLUMN exempt_from_vote TINYINT(1) NOT NULL DEFAULT 0",
            )
            ensureColumn(connection, sessionsTable, "active_token", "ALTER TABLE $sessionsTable ADD COLUMN active_token VARCHAR(64) DEFAULT NULL")
            ensureIndex(connection, sessionsTable, "uk_active_token", "ALTER TABLE $sessionsTable ADD UNIQUE KEY uk_active_token (active_token)")
            backfillActiveTokens(connection)
            cleanupResolvedDeliveries(connection)
        }
    }

    override suspend fun findActiveSession(targetUuid: UUID, now: Instant): VoteSession? {
        val sql = """
            SELECT * FROM $sessionsTable
            WHERE target_uuid = ? AND closed = 0 AND expires_at > ?
            ORDER BY created_at DESC
            LIMIT 1
        """.trimIndent()
        return database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, targetUuid.toString())
                statement.setTimestamp(2, Timestamp.from(now))
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toSession() else null
                }
            }
        }
    }

    override suspend fun findActiveSessionById(sessionId: Long, now: Instant): VoteSession? {
        val sql = """
            SELECT * FROM $sessionsTable
            WHERE id = ? AND closed = 0 AND expires_at > ?
            LIMIT 1
        """.trimIndent()
        return database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, sessionId)
                statement.setTimestamp(2, Timestamp.from(now))
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toSession() else null
                }
            }
        }
    }

    override suspend fun findActiveSessions(now: Instant): List<VoteSession> {
        val sql = """
            SELECT * FROM $sessionsTable
            WHERE closed = 0 AND expires_at > ?
            ORDER BY created_at DESC
        """.trimIndent()
        return database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.toSession())
                        }
                    }
                }
            }
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
        return createSessionInternal(
            targetUuid = targetUuid,
            targetName = targetName,
            startedByUuid = startedByUuid,
            startedByName = startedByName,
            reason = reason,
            createdAt = createdAt,
            expiresAt = expiresAt,
            enforceUniqueness = false,
        ) ?: error("Failed to create vote session")
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
        return createSessionInternal(
            targetUuid = targetUuid,
            targetName = targetName,
            startedByUuid = startedByUuid,
            startedByName = startedByName,
            reason = reason,
            createdAt = createdAt,
            expiresAt = expiresAt,
            enforceUniqueness = true,
        )
    }

    override suspend fun findVote(sessionId: Long, voterUuid: UUID): VoteRecord? {
        val sql = "SELECT * FROM $votesTable WHERE session_id = ? AND voter_uuid = ? LIMIT 1"
        return database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, sessionId)
                statement.setString(2, voterUuid.toString())
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toVoteRecord() else null
                }
            }
        }
    }

    override suspend fun tally(sessionId: Long): VoteTally {
        return database.connection().use { connection ->
            tallyInternal(connection, sessionId)
        }
    }

    override suspend fun upsertVote(
        sessionId: Long,
        voterUuid: UUID,
        voterName: String,
        choice: VoteChoice,
        votedAt: Instant,
    ): VoteTally? {
        val sql = """
            INSERT INTO $votesTable (session_id, voter_uuid, voter_name, choice, voted_at)
            SELECT s.id, ?, ?, ?, ?
            FROM $sessionsTable s
            WHERE s.id = ? AND s.closed = 0 AND s.expires_at > ?
            ON DUPLICATE KEY UPDATE
                voter_name = VALUES(voter_name),
                choice = VALUES(choice),
                voted_at = VALUES(voted_at)
        """.trimIndent()
        return database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, voterUuid.toString())
                statement.setString(2, voterName)
                statement.setString(3, choice.id)
                statement.setTimestamp(4, Timestamp.from(votedAt))
                statement.setLong(5, sessionId)
                statement.setTimestamp(6, Timestamp.from(votedAt))
                val changed = statement.executeUpdate()
                if (changed <= 0) {
                    return@use null
                }
            }
            tallyInternal(connection, sessionId)
        }
    }

    override suspend fun updateStarterCooldown(starterUuid: UUID, startedAt: Instant) {
        val sql = """
            INSERT INTO $cooldownTable (starter_uuid, last_started_at)
            VALUES (?, ?)
            ON DUPLICATE KEY UPDATE last_started_at = VALUES(last_started_at)
        """.trimIndent()
        database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, starterUuid.toString())
                statement.setTimestamp(2, Timestamp.from(startedAt))
                statement.executeUpdate()
            }
        }
    }

    override suspend fun findStarterCooldown(starterUuid: UUID): Instant? {
        val sql = "SELECT last_started_at FROM $cooldownTable WHERE starter_uuid = ? LIMIT 1"
        return database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, starterUuid.toString())
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getTimestamp("last_started_at").toInstant() else null
                }
            }
        }
    }

    override suspend fun resolveExpiredSessions(
        now: Instant,
        blackExcessMultiplier: Double,
        minBlackVotesToSanction: Int,
    ): List<VoteResolution> {
        return database.connection().use { connection ->
            connection.autoCommit = false
            try {
                val selectSql = """
                    SELECT * FROM $sessionsTable
                    WHERE closed = 0 AND expires_at <= ?
                    FOR UPDATE
                """.trimIndent()
                val sessions = mutableListOf<VoteSession>()
                connection.prepareStatement(selectSql).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            sessions += resultSet.toSession()
                        }
                    }
                }
                val results = mutableListOf<VoteResolution>()
                sessions.forEach { session ->
                    val tally = tallyInternal(connection, session.id)
                    val type = if (tally.blackVotes >= minBlackVotesToSanction &&
                        tally.blackVotes > (tally.redVotes * blackExcessMultiplier)
                    ) {
                        VoteResolutionType.SANCTIONED
                    } else {
                        VoteResolutionType.FAILED
                    }
                    val resolved = resolveSessionInternal(connection, session, tally, now, type)
                    if (resolved != null) {
                        results += resolved
                    }
                }
                connection.commit()
                results
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override suspend fun tryResolveSession(
        sessionId: Long,
        tally: VoteTally,
        resolvedAt: Instant,
        type: VoteResolutionType,
    ): VoteResolution? {
        return database.connection().use { connection ->
            connection.autoCommit = false
            try {
                val session = lockSession(connection, sessionId) ?: run {
                    connection.rollback()
                    return@use null
                }
                val resolved = resolveSessionInternal(connection, session, tally, resolvedAt, type)
                connection.commit()
                resolved
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override suspend fun findPendingResolutions(
        serverId: String,
        now: Instant,
        limit: Int,
    ): List<VoteResolution> {
        val sql = """
            SELECT r.*
            FROM $resolutionsTable r
            LEFT JOIN $resolutionDeliveriesTable d
                ON d.session_id = r.session_id AND d.server_id = ?
            WHERE d.session_id IS NULL
            ORDER BY r.resolved_at ASC
            LIMIT ?
        """.trimIndent()
        return database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, serverId)
                statement.setInt(2, limit.coerceAtLeast(1))
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.toResolution())
                        }
                    }
                }
            }
        }
    }

    override suspend fun markResolutionDelivered(sessionId: Long, serverId: String) {
        val sql = """
            INSERT INTO $resolutionDeliveriesTable (session_id, server_id, delivered_at)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE delivered_at = VALUES(delivered_at)
        """.trimIndent()
        database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, sessionId)
                statement.setString(2, serverId)
                statement.setTimestamp(3, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
        }
    }

    override suspend fun heartbeatOnlinePlayer(
        serverId: String,
        playerUuid: UUID,
        playerName: String,
        exemptFromVote: Boolean,
        now: Instant,
    ) {
        val sql = """
            INSERT INTO $onlinePlayersTable (player_uuid, player_name, server_id, exempt_from_vote, last_seen_at)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                player_name = VALUES(player_name),
                server_id = VALUES(server_id),
                exempt_from_vote = VALUES(exempt_from_vote),
                last_seen_at = VALUES(last_seen_at)
        """.trimIndent()
        database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, playerUuid.toString())
                statement.setString(2, playerName)
                statement.setString(3, serverId)
                statement.setBoolean(4, exemptFromVote)
                statement.setTimestamp(5, Timestamp.from(now))
                statement.executeUpdate()
            }
        }
    }

    override suspend fun removeOnlinePlayer(serverId: String, playerUuid: UUID) {
        val sql = "DELETE FROM $onlinePlayersTable WHERE player_uuid = ? AND server_id = ?"
        database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, playerUuid.toString())
                statement.setString(2, serverId)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun findOnlinePlayerByName(name: String, now: Instant): OnlinePlayerSnapshot? {
        val sql = """
            SELECT player_uuid, player_name
            FROM $onlinePlayersTable
            WHERE LOWER(player_name) = LOWER(?) AND last_seen_at > ?
            ORDER BY last_seen_at DESC
            LIMIT 1
        """.trimIndent()
        return database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, name)
                statement.setTimestamp(2, Timestamp.from(now.minusSeconds(30)))
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        OnlinePlayerSnapshot(
                            playerUuid = UUID.fromString(resultSet.getString("player_uuid")),
                            playerName = resultSet.getString("player_name"),
                            exemptFromVote = resultSet.getBoolean("exempt_from_vote"),
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    override suspend fun purgeExpiredOnlinePlayers(expireBefore: Instant) {
        val sql = "DELETE FROM $onlinePlayersTable WHERE last_seen_at < ?"
        database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setTimestamp(1, Timestamp.from(expireBefore))
                statement.executeUpdate()
            }
        }
    }

    private fun createSessionInternal(
        targetUuid: UUID,
        targetName: String,
        startedByUuid: UUID,
        startedByName: String,
        reason: String,
        createdAt: Instant,
        expiresAt: Instant,
        enforceUniqueness: Boolean,
    ): StartVoteResult? {
        val activeToken = "active:${targetUuid}"
        val sql = """
            INSERT INTO $sessionsTable
            (target_uuid, target_name, started_by_uuid, started_by_name, reason, created_at, expires_at, closed, sanction_executed, active_token)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?)
        """.trimIndent()
        return database.connection().use { connection ->
            try {
                connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
                    statement.setString(1, targetUuid.toString())
                    statement.setString(2, targetName)
                    statement.setString(3, startedByUuid.toString())
                    statement.setString(4, startedByName)
                    statement.setString(5, reason)
                    statement.setTimestamp(6, Timestamp.from(createdAt))
                    statement.setTimestamp(7, Timestamp.from(expiresAt))
                    statement.setString(8, if (enforceUniqueness) activeToken else null)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        if (!keys.next()) {
                            error("Failed to create vote session generated key")
                        }
                        val session = VoteSession(
                            id = keys.getLong(1),
                            targetUuid = targetUuid,
                            targetName = targetName,
                            startedByUuid = startedByUuid,
                            startedByName = startedByName,
                            reason = reason,
                            createdAt = createdAt,
                            expiresAt = expiresAt,
                            closed = false,
                            sanctionExecuted = false,
                        )
                        StartVoteResult(session = session, tally = VoteTally(redVotes = 0, blackVotes = 0))
                    }
                }
            } catch (exception: SQLException) {
                if (enforceUniqueness && isDuplicateKey(exception)) {
                    null
                } else {
                    throw exception
                }
            }
        }
    }

    private fun lockSession(connection: Connection, sessionId: Long): VoteSession? {
        val sql = """
            SELECT * FROM $sessionsTable
            WHERE id = ?
            FOR UPDATE
        """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, sessionId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                return resultSet.toSession()
            }
        }
    }

    private fun resolveSessionInternal(
        connection: Connection,
        session: VoteSession,
        tally: VoteTally,
        resolvedAt: Instant,
        type: VoteResolutionType,
    ): VoteResolution? {
        if (session.closed) {
            return null
        }
        val updateSql = """
            UPDATE $sessionsTable
            SET closed = 1,
                sanction_executed = ?,
                active_token = NULL
            WHERE id = ? AND closed = 0
        """.trimIndent()
        connection.prepareStatement(updateSql).use { statement ->
            statement.setBoolean(1, type == VoteResolutionType.SANCTIONED)
            statement.setLong(2, session.id)
            if (statement.executeUpdate() <= 0) {
                return null
            }
        }
        val resolvedSession = session.copy(closed = true, sanctionExecuted = type == VoteResolutionType.SANCTIONED)
        val insertResolutionSql = """
            INSERT INTO $resolutionsTable
            (session_id, target_uuid, target_name, started_by_uuid, started_by_name, reason, created_at, expires_at, resolved_at, resolution_type, red_votes, black_votes, sanction_executed)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                target_uuid = VALUES(target_uuid),
                target_name = VALUES(target_name),
                started_by_uuid = VALUES(started_by_uuid),
                started_by_name = VALUES(started_by_name),
                reason = VALUES(reason),
                created_at = VALUES(created_at),
                expires_at = VALUES(expires_at),
                resolved_at = VALUES(resolved_at),
                resolution_type = VALUES(resolution_type),
                red_votes = VALUES(red_votes),
                black_votes = VALUES(black_votes),
                sanction_executed = VALUES(sanction_executed)
        """.trimIndent()
        connection.prepareStatement(insertResolutionSql).use { statement ->
            statement.setLong(1, session.id)
            statement.setString(2, session.targetUuid.toString())
            statement.setString(3, session.targetName)
            statement.setString(4, session.startedByUuid.toString())
            statement.setString(5, session.startedByName)
            statement.setString(6, session.reason)
            statement.setTimestamp(7, Timestamp.from(session.createdAt))
            statement.setTimestamp(8, Timestamp.from(session.expiresAt))
            statement.setTimestamp(9, Timestamp.from(resolvedAt))
            statement.setString(10, type.name)
            statement.setInt(11, tally.redVotes)
            statement.setInt(12, tally.blackVotes)
            statement.setBoolean(13, type == VoteResolutionType.SANCTIONED)
            statement.executeUpdate()
        }
        return VoteResolution(
            session = resolvedSession,
            tally = tally,
            resolvedAt = resolvedAt,
            type = type,
        )
    }

    private fun tallyInternal(connection: Connection, sessionId: Long): VoteTally {
        val sql = """
            SELECT
                COALESCE(SUM(CASE WHEN choice = 'red' THEN 1 ELSE 0 END), 0) AS red_votes,
                COALESCE(SUM(CASE WHEN choice = 'black' THEN 1 ELSE 0 END), 0) AS black_votes
            FROM $votesTable
            WHERE session_id = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, sessionId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    return VoteTally(
                        redVotes = resultSet.getInt("red_votes"),
                        blackVotes = resultSet.getInt("black_votes"),
                    )
                }
            }
        }
        return VoteTally(0, 0)
    }

    private fun backfillActiveTokens(connection: Connection) {
        val sql = """
            UPDATE $sessionsTable
            SET active_token = CONCAT('active:', target_uuid)
            WHERE closed = 0 AND active_token IS NULL
        """.trimIndent()
        runCatching {
            connection.createStatement().use { statement ->
                statement.executeUpdate(sql)
            }
        }.onFailure { exception ->
            logger.warning("BallotCraft failed to backfill active vote tokens: ${exception.message}")
        }
    }

    private fun cleanupResolvedDeliveries(connection: Connection) {
        val sql = """
            DELETE d FROM $resolutionDeliveriesTable d
            INNER JOIN $resolutionsTable r ON r.session_id = d.session_id
            WHERE r.resolved_at < ?
        """.trimIndent()
        runCatching {
            connection.prepareStatement(sql).use { statement ->
                statement.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(86_400)))
                statement.executeUpdate()
            }
        }
    }

    private fun ensureColumn(connection: Connection, table: String, column: String, ddl: String) {
        if (hasColumn(connection, table, column)) {
            return
        }
        connection.createStatement().use { statement ->
            statement.executeUpdate(ddl)
        }
    }

    private fun ensureIndex(connection: Connection, table: String, index: String, ddl: String) {
        if (hasIndex(connection, table, index)) {
            return
        }
        connection.createStatement().use { statement ->
            statement.executeUpdate(ddl)
        }
    }

    private fun hasColumn(connection: Connection, table: String, column: String): Boolean {
        connection.metaData.getColumns(connection.catalog, null, table, column).use { resultSet ->
            return resultSet.next()
        }
    }

    private fun hasIndex(connection: Connection, table: String, index: String): Boolean {
        connection.metaData.getIndexInfo(connection.catalog, null, table, false, false).use { resultSet ->
            while (resultSet.next()) {
                if (index.equals(resultSet.getString("INDEX_NAME"), ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    private fun isDuplicateKey(exception: SQLException): Boolean {
        return exception.sqlState == "23000" || exception.errorCode == 1062
    }

    private fun ResultSet.toSession(): VoteSession {
        return VoteSession(
            id = getLong("id"),
            targetUuid = UUID.fromString(getString("target_uuid")),
            targetName = getString("target_name"),
            startedByUuid = UUID.fromString(getString("started_by_uuid")),
            startedByName = getString("started_by_name"),
            reason = getString("reason"),
            createdAt = getTimestamp("created_at").toInstant(),
            expiresAt = getTimestamp("expires_at").toInstant(),
            closed = getBoolean("closed"),
            sanctionExecuted = getBoolean("sanction_executed"),
        )
    }

    private fun ResultSet.toVoteRecord(): VoteRecord {
        return VoteRecord(
            sessionId = getLong("session_id"),
            voterUuid = UUID.fromString(getString("voter_uuid")),
            voterName = getString("voter_name"),
            choice = VoteChoice.fromId(getString("choice")) ?: VoteChoice.BLACK,
            votedAt = getTimestamp("voted_at").toInstant(),
        )
    }

    private fun ResultSet.toResolution(): VoteResolution {
        val type = VoteResolutionType.valueOf(getString("resolution_type"))
        return VoteResolution(
            session = VoteSession(
                id = getLong("session_id"),
                targetUuid = UUID.fromString(getString("target_uuid")),
                targetName = getString("target_name"),
                startedByUuid = UUID.fromString(getString("started_by_uuid")),
                startedByName = getString("started_by_name"),
                reason = getString("reason"),
                createdAt = getTimestamp("created_at").toInstant(),
                expiresAt = getTimestamp("expires_at").toInstant(),
                closed = true,
                sanctionExecuted = getBoolean("sanction_executed"),
            ),
            tally = VoteTally(
                redVotes = getInt("red_votes"),
                blackVotes = getInt("black_votes"),
            ),
            resolvedAt = getTimestamp("resolved_at").toInstant(),
            type = type,
        )
    }
}
