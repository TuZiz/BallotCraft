package ym.ballotcraft.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import ym.ballotcraft.BallotCraftConfig
import ym.ballotcraft.BallotCraftPlugin
import java.io.File

object PluginConfigLoader {
    fun load(plugin: BallotCraftPlugin): BallotCraftConfig {
        plugin.reloadConfig()
        val config = plugin.config
        val languageConfig = loadLanguageConfig(plugin, config)
        return BallotCraftConfig(
            storage = loadStorage(config),
            permissions = loadPermissions(config),
            vote = loadVote(config),
            bossBar = loadBossBar(config),
            messages = loadMessages(languageConfig),
            database = loadDatabase(config),
        )
    }

    private fun loadStorage(config: FileConfiguration): BallotCraftConfig.StorageSettings {
        return BallotCraftConfig.StorageSettings(
            type = requireString(config, "storage.type"),
            yamlFile = requireString(config, "storage.yaml-file"),
        )
    }

    private fun loadLanguageConfig(plugin: BallotCraftPlugin, config: FileConfiguration): FileConfiguration {
        val langPath = config.getString("lang-file", "lang/zh_cn.yml")!!
        val langFile = File(plugin.dataFolder, langPath)
        require(langFile.exists()) { "Language file not found: ${langFile.absolutePath}" }
        return YamlConfiguration.loadConfiguration(langFile)
    }

    private fun loadPermissions(config: FileConfiguration): BallotCraftConfig.PermissionSettings {
        return BallotCraftConfig.PermissionSettings(
            start = requireString(config, "permissions.start"),
            vote = requireString(config, "permissions.vote"),
            notify = requireString(config, "permissions.notify"),
            bypassCooldown = requireString(config, "permissions.bypass-cooldown"),
            exemptTarget = requireString(config, "permissions.exempt-target"),
        )
    }

    private fun loadVote(config: FileConfiguration): BallotCraftConfig.VoteSettings {
        return BallotCraftConfig.VoteSettings(
            cooldownSeconds = config.getLong("vote.cooldown-seconds", 600L),
            openSeconds = config.getLong("vote.open-seconds", 120L),
            syncPollSeconds = config.getLong("vote.sync-poll-seconds", 5L),
            onlineHeartbeatSeconds = config.getLong("vote.online-heartbeat-seconds", 5L).coerceAtLeast(1L),
            onlineCleanupSeconds = config.getLong("vote.online-cleanup-seconds", 60L).coerceAtLeast(10L),
            onlineExpireSeconds = config.getLong("vote.online-expire-seconds", 30L).coerceAtLeast(5L),
            blackExcessMultiplier = config.getDouble("vote.black-excess-multiplier", 2.0),
            minBlackVotesToSanction = config.getInt("vote.min-black-votes-to-sanction", 3).coerceAtLeast(1),
            allowVoteChange = config.getBoolean("vote.allow-vote-change", true),
            reasons = loadVoteReasons(config),
        )
    }

    private fun loadVoteReasons(config: FileConfiguration): List<BallotCraftConfig.VoteReasonSettings> {
        val section = requireNotNull(config.getConfigurationSection("vote.reasons")) {
            "Missing required config path: vote.reasons"
        }
        val reasons = section.getKeys(false).mapNotNull { key ->
            val path = "vote.reasons.$key"
            val command = config.getString("$path.sanction-command") ?: return@mapNotNull null
            BallotCraftConfig.VoteReasonSettings(
                id = key,
                displayName = config.getString("$path.display-name") ?: key,
                displayColor = config.getString("$path.display-color", "#ECCC68") ?: "#ECCC68",
                description = config.getString("$path.description") ?: key,
                sanctionCommand = command,
                aliases = config.getStringList("$path.aliases").filter { it.isNotBlank() },
            )
        }
        require(reasons.isNotEmpty()) { "At least one vote reason must be defined under vote.reasons" }
        return reasons
    }

    private fun loadBossBar(config: FileConfiguration): BallotCraftConfig.BossBarSettings {
        return BallotCraftConfig.BossBarSettings(
            enabled = config.getBoolean("bossbar.enabled", true),
            title = requireString(config, "bossbar.title"),
            color = requireString(config, "bossbar.color"),
            overlay = requireString(config, "bossbar.overlay"),
            progress = config.getDouble("bossbar.progress", 1.0),
            updateIntervalTicks = config.getLong("bossbar.update-interval-ticks", config.getLong("bossbar.duration-ticks", 20L)),
        )
    }

    private fun loadMessages(config: FileConfiguration): BallotCraftConfig.MessageSettings {
        return BallotCraftConfig.MessageSettings(
            prefix = requireString(config, "messages.prefix"),
            voteStarted = requireString(config, "messages.vote-started"),
            voteStartedBroadcast = requireString(config, "messages.vote-started-broadcast"),
            voteGuide = requireString(config, "messages.vote-guide"),
            voteGuideDetail = requireString(config, "messages.vote-guide-detail"),
            voteLabelRed = requireString(config, "messages.vote-label-red"),
            voteLabelBlack = requireString(config, "messages.vote-label-black"),
            voteClickableRed = requireString(config, "messages.vote-clickable-red"),
            voteClickableBlack = requireString(config, "messages.vote-clickable-black"),
            voteHoverRed = requireString(config, "messages.vote-hover-red"),
            voteHoverBlack = requireString(config, "messages.vote-hover-black"),
            voteSuccess = requireString(config, "messages.vote-success"),
            voteChanged = requireString(config, "messages.vote-changed"),
            voteDuplicate = requireString(config, "messages.vote-duplicate"),
            voteClosed = requireString(config, "messages.vote-closed"),
            votePassed = requireString(config, "messages.vote-passed"),
            voteFailed = requireString(config, "messages.vote-failed"),
            cooldown = requireString(config, "messages.cooldown"),
            targetOffline = requireString(config, "messages.target-offline"),
            targetProtected = requireString(config, "messages.target-protected"),
            cannotTargetSelf = requireString(config, "messages.cannot-target-self"),
            noPermission = requireString(config, "messages.no-permission"),
            usage = requireString(config, "messages.usage"),
            reasonNotFound = requireString(config, "messages.reason-not-found"),
            activeVoteExists = requireString(config, "messages.active-vote-exists"),
            playerOnly = requireString(config, "messages.player-only"),
            systemUnavailable = requireString(config, "messages.system-unavailable"),
        )
    }

    private fun loadDatabase(config: FileConfiguration): BallotCraftConfig.DatabaseSettings {
        return BallotCraftConfig.DatabaseSettings(
            serverId = requireString(config, "database.server-id"),
            host = requireString(config, "database.host"),
            port = config.getInt("database.port", 3306),
            database = requireString(config, "database.name"),
            username = requireString(config, "database.username"),
            password = requireString(config, "database.password"),
            tablePrefix = requireString(config, "database.table-prefix"),
            poolSize = config.getInt("database.pool-size", 10),
            connectionTimeoutMillis = config.getLong("database.connection-timeout-millis", 10000L),
        )
    }

    private fun requireString(config: FileConfiguration, path: String): String {
        return requireNotNull(config.getString(path)) { "Missing required config path: $path" }
    }
}
