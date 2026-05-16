package ym.ballotcraft

data class BallotCraftConfig(
    val storage: StorageSettings,
    val permissions: PermissionSettings,
    val vote: VoteSettings,
    val bossBar: BossBarSettings,
    val messages: MessageSettings,
    val database: DatabaseSettings,
) {
    data class StorageSettings(
        val type: String,
        val yamlFile: String,
    ) {
        val normalizedType: String
            get() = type.lowercase()

        val usesMysql: Boolean
            get() = normalizedType == "mysql"
    }

    data class PermissionSettings(
        val start: String,
        val vote: String,
        val notify: String,
        val bypassCooldown: String,
        val exemptTarget: String,
    )

    data class VoteSettings(
        val cooldownSeconds: Long,
        val openSeconds: Long,
        val syncPollSeconds: Long,
        val onlineHeartbeatSeconds: Long,
        val onlineExpireSeconds: Long,
        val blackExcessMultiplier: Double,
        val minBlackVotesToSanction: Int,
        val allowVoteChange: Boolean,
        val reasons: List<VoteReasonSettings>,
    )

    data class VoteReasonSettings(
        val id: String,
        val displayName: String,
        val displayColor: String,
        val description: String,
        val sanctionCommand: String,
        val aliases: List<String>,
    ) {
        fun matches(raw: String): Boolean {
            return id.equals(raw, ignoreCase = true) || aliases.any { it.equals(raw, ignoreCase = true) }
        }
    }

    data class BossBarSettings(
        val enabled: Boolean,
        val title: String,
        val color: String,
        val overlay: String,
        val progress: Double,
        val updateIntervalTicks: Long,
    )

    data class MessageSettings(
        val prefix: String,
        val voteStarted: String,
        val voteStartedBroadcast: String,
        val voteGuide: String,
        val voteGuideDetail: String,
        val voteLabelRed: String,
        val voteLabelBlack: String,
        val voteClickableRed: String,
        val voteClickableBlack: String,
        val voteHoverRed: String,
        val voteHoverBlack: String,
        val voteSuccess: String,
        val voteChanged: String,
        val voteDuplicate: String,
        val voteClosed: String,
        val votePassed: String,
        val voteFailed: String,
        val cooldown: String,
        val targetOffline: String,
        val targetProtected: String,
        val cannotTargetSelf: String,
        val noPermission: String,
        val usage: String,
        val reasonNotFound: String,
        val activeVoteExists: String,
        val playerOnly: String,
        val systemUnavailable: String,
    )

    data class DatabaseSettings(
        val serverId: String,
        val host: String,
        val port: Int,
        val database: String,
        val username: String,
        val password: String,
        val tablePrefix: String,
        val poolSize: Int,
        val connectionTimeoutMillis: Long,
    ) {
        val jdbcUrl: String
            get() = "jdbc:mysql://$host:$port/$database?useSSL=false&characterEncoding=utf8&serverTimezone=UTC"
    }
}
