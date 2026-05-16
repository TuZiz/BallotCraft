package ym.ballotcraft.storage

import ym.ballotcraft.model.StartVoteResult
import ym.ballotcraft.model.VoteChoice
import ym.ballotcraft.model.OnlinePlayerSnapshot
import ym.ballotcraft.model.OnlinePlayerSnapshotForWrite
import ym.ballotcraft.model.VoteRecord
import ym.ballotcraft.model.VoteResolution
import ym.ballotcraft.model.VoteResolutionType
import ym.ballotcraft.model.VoteSession
import ym.ballotcraft.model.VoteTally
import java.time.Instant
import java.util.UUID

interface VoteRepository {
    suspend fun initialize()

    suspend fun findActiveSession(targetUuid: UUID, now: Instant): VoteSession?

    suspend fun findActiveSessionById(sessionId: Long, now: Instant): VoteSession?

    suspend fun findActiveSessions(now: Instant): List<VoteSession>

    suspend fun createSession(
        targetUuid: UUID,
        targetName: String,
        startedByUuid: UUID,
        startedByName: String,
        reason: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): StartVoteResult

    suspend fun createSessionIfAbsent(
        targetUuid: UUID,
        targetName: String,
        startedByUuid: UUID,
        startedByName: String,
        reason: String,
        createdAt: Instant,
        expiresAt: Instant,
    ): StartVoteResult?

    suspend fun findVote(sessionId: Long, voterUuid: UUID): VoteRecord?

    suspend fun tally(sessionId: Long): VoteTally

    suspend fun upsertVote(
        sessionId: Long,
        voterUuid: UUID,
        voterName: String,
        choice: VoteChoice,
        votedAt: Instant,
    ): VoteTally?

    suspend fun updateStarterCooldown(starterUuid: UUID, startedAt: Instant)

    suspend fun findStarterCooldown(starterUuid: UUID): Instant?

    suspend fun resolveExpiredSessions(
        now: Instant,
        blackExcessMultiplier: Double,
        minBlackVotesToSanction: Int,
    ): List<VoteResolution>

    suspend fun tryResolveSession(
        sessionId: Long,
        tally: VoteTally,
        resolvedAt: Instant,
        type: VoteResolutionType,
    ): VoteResolution?

    suspend fun findPendingResolutions(
        serverId: String,
        now: Instant,
        limit: Int,
    ): List<VoteResolution>

    suspend fun markResolutionDelivered(sessionId: Long, serverId: String)

    suspend fun heartbeatOnlinePlayersBatch(
        serverId: String,
        snapshots: List<OnlinePlayerSnapshotForWrite>,
        now: Instant,
    )

    suspend fun removeOnlinePlayersBatch(serverId: String, uuids: Collection<UUID>)

    suspend fun findOnlinePlayerByName(name: String, expireAfter: Instant): OnlinePlayerSnapshot?

    suspend fun purgeExpiredOnlinePlayers(serverId: String, expireBefore: Instant, limit: Int): Int
}
