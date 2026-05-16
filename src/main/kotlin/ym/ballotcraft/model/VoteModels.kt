package ym.ballotcraft.model

import java.time.Instant
import java.util.UUID

enum class VoteChoice(val id: String) {
    RED("red"),
    BLACK("black");

    companion object {
        fun fromId(raw: String?): VoteChoice? {
            return entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
        }
    }
}

data class VoteSession(
    val id: Long,
    val targetUuid: UUID,
    val targetName: String,
    val startedByUuid: UUID,
    val startedByName: String,
    val reason: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val closed: Boolean,
    val sanctionExecuted: Boolean,
)

data class VoteTally(
    val redVotes: Int,
    val blackVotes: Int,
)

data class VoteRecord(
    val sessionId: Long,
    val voterUuid: UUID,
    val voterName: String,
    val choice: VoteChoice,
    val votedAt: Instant,
)

data class StartVoteResult(
    val session: VoteSession,
    val tally: VoteTally,
)

enum class VoteResolutionType {
    SANCTIONED,
    FAILED,
}

data class VoteResolution(
    val session: VoteSession,
    val tally: VoteTally,
    val resolvedAt: Instant,
    val type: VoteResolutionType,
)

data class OnlinePlayerSnapshot(
    val playerUuid: UUID,
    val playerName: String,
    val exemptFromVote: Boolean,
)
