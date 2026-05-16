package ym.ballotcraft

import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import org.bukkit.configuration.file.YamlConfiguration
import ym.ballotcraft.command.BallotCommand
import ym.ballotcraft.config.PluginConfigLoader
import ym.ballotcraft.listener.PlayerLifecycleListener
import ym.ballotcraft.platform.PlatformScheduler
import ym.ballotcraft.service.VoteService
import ym.ballotcraft.storage.DatabaseVoteRepository
import ym.ballotcraft.storage.HikariDatabase
import ym.ballotcraft.storage.VoteRepository
import ym.ballotcraft.storage.YamlVoteRepository

class BallotCraftPlugin : JavaPlugin() {
    lateinit var pluginConfig: BallotCraftConfig
        private set

    lateinit var platformScheduler: PlatformScheduler
        private set

    lateinit var voteService: VoteService
        private set

    private var database: HikariDatabase? = null

    override fun onEnable() {
        saveDefaultConfig()
        mergeBundledYamlDefaults("config.yml")
        reloadConfig()
        val langPath = config.getString("lang-file", "lang/zh_cn.yml") ?: "lang/zh_cn.yml"
        mergeBundledYamlDefaults(langPath)
        pluginConfig = PluginConfigLoader.load(this)
        platformScheduler = PlatformScheduler.create(this)

        val repository = createRepository()
        voteService = VoteService(
            plugin = this,
            config = pluginConfig,
            scheduler = platformScheduler,
            repository = repository,
        )

        val ballotCommand = BallotCommand(voteService)
        getCommand("ballotcraft")?.setExecutor(ballotCommand)
        getCommand("ballotcraft")?.tabCompleter = ballotCommand
        server.pluginManager.registerEvents(PlayerLifecycleListener(voteService), this)
        voteService.startAsync(
            onReady = {
                logger.info("BallotCraft vote service initialized successfully.")
            },
            onFailure = { exception ->
                logger.severe("BallotCraft failed to initialize database or vote service: ${exception.message}")
                exception.printStackTrace()
                platformScheduler.executeGlobal {
                    server.pluginManager.disablePlugin(this)
                }
            },
        )
    }

    override fun onDisable() {
        if (::voteService.isInitialized) {
            voteService.shutdown()
        }
        database?.close()
    }

    private fun saveBundledResourceIfMissing(path: String) {
        val target = File(dataFolder, path)
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            saveResource(path, false)
        }
    }

    private fun mergeBundledYamlDefaults(path: String) {
        val resource = getResource(path) ?: run {
            saveBundledResourceIfMissing(path)
            return
        }
        val target = File(dataFolder, path)
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            saveResource(path, false)
            return
        }

        val current = YamlConfiguration.loadConfiguration(target)
        val defaults = resource.use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                YamlConfiguration.loadConfiguration(reader)
            }
        }

        var updated = false
        defaults.getKeys(true).forEach { key ->
            if (!current.contains(key)) {
                current.set(key, defaults.get(key))
                updated = true
            }
        }
        if (updated) {
            current.save(target)
        }
    }

    private fun createRepository(): VoteRepository {
        return if (pluginConfig.storage.usesMysql) {
            val hikariDatabase = HikariDatabase(pluginConfig.database)
            database = hikariDatabase
            DatabaseVoteRepository(hikariDatabase, logger)
        } else {
            YamlVoteRepository(this, pluginConfig.storage.yamlFile)
        }
    }
}
