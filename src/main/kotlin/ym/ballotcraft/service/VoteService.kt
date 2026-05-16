package ym.ballotcraft.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ym.ballotcraft.BallotCraftConfig
import ym.ballotcraft.BallotCraftPlugin
import ym.ballotcraft.model.OnlinePlayerSnapshot
import ym.ballotcraft.model.VoteChoice
import ym.ballotcraft.model.VoteResolution
import ym.ballotcraft.model.VoteResolutionType
import ym.ballotcraft.model.VoteSession
import ym.ballotcraft.model.VoteTally
import ym.ballotcraft.platform.PlatformScheduler
import ym.ballotcraft.platform.PlayerMessenger
import ym.ballotcraft.storage.VoteRepository
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

class VoteService(
    private val plugin: BallotCraftPlugin,
    private val config: BallotCraftConfig,
    private val scheduler: PlatformScheduler,
    private val repository: VoteRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val miniMessage = MiniMessage.miniMessage()
    private val legacySerializer = LegacyComponentSerializer.legacySection()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("BallotCraft"))
    private val bossBars = ConcurrentHashMap<UUID, ConcurrentHashMap<Long, BossBar>>()
    private val announcedSessions = ConcurrentHashMap.newKeySet<Long>()
    private val deliveredSessions = ConcurrentHashMap<UUID, MutableSet<Long>>()
    private val ready = AtomicBoolean(false)
    private val serverId = config.database.serverId
    private val reasonsById = config.vote.reasons.associateBy { it.id.lowercase(Locale.ROOT) }

    fun startAsync(onReady: () -> Unit, onFailure: (Throwable) -> Unit) {
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    repository.initialize()
                    if (config.storage.usesMysql) {
                        repository.purgeExpiredOnlinePlayers(Instant.now().minusSeconds(config.vote.onlineExpireSeconds))
                    }
                }
            }.onSuccess {
                ready.set(true)
                scheduleExpirySweep()
                if (config.bossBar.enabled) {
                    scheduleBossBarRefresh()
                }
                if (config.storage.usesMysql) {
                    scheduleSyncPoll()
                    scheduleOnlineHeartbeat()
                    scope.launch {
                        syncOnlinePlayersNow()
                    }
                }
                onReady()
            }.onFailure { throwable ->
                ready.set(false)
                onFailure(throwable)
            }
        }
    }

    fun shutdown() {
        ready.set(false)
        if (config.storage.usesMysql) {
            val onlinePlayers = Bukkit.getOnlinePlayers().map { it.uniqueId }
            scope.launch {
                withContext(ioDispatcher) {
                    onlinePlayers.forEach { uuid ->
                        repository.removeOnlinePlayer(serverId, uuid)
                    }
                }
            }
        }
        scope.cancel()
        bossBars.forEach { (uuid, playerBossBars) ->
            val player = Bukkit.getPlayer(uuid) ?: return@forEach
            scheduler.executeFor(player) {
                playerBossBars.values.forEach { bossBar ->
                    bossBar.removePlayer(player)
                    bossBar.isVisible = false
                }
            }
        }
        bossBars.clear()
        deliveredSessions.clear()
        announcedSessions.clear()
    }

    fun handleCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (!ready.get()) {
            sendPrefixed(sender, config.messages.systemUnavailable)
            return true
        }
        if (args.isEmpty()) {
            sendPrefixed(sender, config.messages.usage)
            return true
        }

        return when (args[0].lowercase(Locale.ROOT)) {
            "start" -> {
                if (sender !is Player) {
                    sendPrefixed(sender, config.messages.playerOnly)
                    return true
                }
                startVoteCommand(sender, args)
                true
            }

            "vote" -> {
                if (sender !is Player) {
                    sendPrefixed(sender, config.messages.playerOnly)
                    return true
                }
                handleVoteCommand(sender, args)
                true
            }

            else -> {
                sendPrefixed(sender, config.messages.usage)
                true
            }
        }
    }

    fun tabComplete(sender: CommandSender, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("start", "vote").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && sender is Player) {
            return when (args[0].lowercase(Locale.ROOT)) {
                "start" -> Bukkit.getOnlinePlayers()
                    .asSequence()
                    .map(Player::getName)
                    .filter { !it.equals(sender.name, ignoreCase = true) }
                    .filter { it.startsWith(args[1], ignoreCase = true) }
                    .toList()

                else -> emptyList()
            }
        }
        if (args.size == 3 && sender is Player && args[0].equals("start", ignoreCase = true)) {
            return config.vote.reasons
                .asSequence()
                .flatMap { sequenceOf(it.id, *it.aliases.toTypedArray()) }
                .distinct()
                .filter { it.startsWith(args[2], ignoreCase = true) }
                .toList()
        }
        return emptyList()
    }

    fun onPlayerJoin(player: Player) {
        clearPlayerState(player.uniqueId)
        if (!ready.get()) {
            return
        }
        scope.launch {
            if (config.storage.usesMysql) {
                withContext(ioDispatcher) {
                    repository.heartbeatOnlinePlayer(
                        serverId,
                        player.uniqueId,
                        player.name,
                        player.hasPermission(config.permissions.exemptTarget),
                        Instant.now(),
                    )
                }
            }
            val sessions = withContext(ioDispatcher) {
                repository.findActiveSessions(Instant.now())
            }
            sessions.forEach { session ->
                notifySinglePlayer(player, session)
            }
        }
    }

    fun onPlayerQuit(player: Player) {
        clearPlayerState(player.uniqueId)
        if (!ready.get() || !config.storage.usesMysql) {
            return
        }
        scope.launch {
            withContext(ioDispatcher) {
                repository.removeOnlinePlayer(serverId, player.uniqueId)
            }
        }
    }

    fun handleVoteClick(voter: Player, sessionId: Long, choice: VoteChoice) {
        if (!ready.get()) {
            sendPrefixed(voter, config.messages.systemUnavailable)
            return
        }
        if (!voter.hasPermission(config.permissions.vote)) {
            sendPrefixed(voter, config.messages.noPermission)
            return
        }
        scope.launch {
            val now = Instant.now()
            val session = withContext(ioDispatcher) {
                repository.findActiveSessionById(sessionId, now)
            }
            if (session == null) {
                sendForPlayer(voter, config.messages.voteClosed)
                return@launch
            }

            val existingVote = withContext(ioDispatcher) {
                repository.findVote(sessionId, voter.uniqueId)
            }
            if (existingVote != null && existingVote.choice == choice) {
                sendForPlayer(voter, config.messages.voteDuplicate)
                return@launch
            }
            if (existingVote != null && !config.vote.allowVoteChange) {
                sendForPlayer(voter, config.messages.voteDuplicate)
                return@launch
            }

            val tally = withContext(ioDispatcher) {
                repository.upsertVote(sessionId, voter.uniqueId, voter.name, choice, now)
            }
            if (tally == null) {
                sendForPlayer(voter, config.messages.voteClosed)
                return@launch
            }

            if (existingVote == null) {
                sendForPlayer(voter, config.messages.voteSuccess, "target" to session.targetName, "vote" to voteLabel(choice))
            } else {
                sendForPlayer(voter, config.messages.voteChanged, "target" to session.targetName, "vote" to voteLabel(choice))
            }

            if (shouldSanction(tally)) {
                resolveLiveSession(session, tally, VoteResolutionType.SANCTIONED)
            } else {
                refreshBossBarsForSession(session.id)
            }
        }
    }

    private fun handleVoteCommand(sender: Player, args: Array<out String>) {
        if (args.size < 3) {
            sendPrefixed(sender, config.messages.usage)
            return
        }
        val sessionId = args[1].toLongOrNull()
        if (sessionId == null) {
            sendPrefixed(sender, config.messages.voteClosed)
            return
        }
        val choice = VoteChoice.fromId(args[2])
        if (choice == null) {
            sendPrefixed(sender, config.messages.voteClosed)
            return
        }
        handleVoteClick(sender, sessionId, choice)
    }

    private fun startVoteCommand(sender: Player, args: Array<out String>) {
        if (!sender.hasPermission(config.permissions.start)) {
            sendPrefixed(sender, config.messages.noPermission)
            return
        }
        if (args.size < 3) {
            sendPrefixed(sender, config.messages.usage)
            return
        }

        val targetName = args[1]
        val localTarget = Bukkit.getPlayerExact(targetName)
        val reasonInput = args[2]
        val reason = findReason(reasonInput)
        if (reason == null) {
            sendPrefixed(sender, config.messages.reasonNotFound, "reason" to reasonInput)
            return
        }
        if (localTarget != null && localTarget.uniqueId == sender.uniqueId) {
            sendPrefixed(sender, config.messages.cannotTargetSelf)
            return
        }

        scope.launch {
            val now = Instant.now()
            if (!sender.hasPermission(config.permissions.bypassCooldown)) {
                val remaining = withContext(ioDispatcher) {
                    computeRemainingCooldown(sender.uniqueId, now)
                }
                if (remaining > 0L) {
                    sendForPlayer(sender, config.messages.cooldown, "seconds" to remaining)
                    return@launch
                }
            }

            val targetSnapshot = resolveTargetSnapshot(localTarget, targetName, now)
            if (targetSnapshot == null) {
                sendForPlayer(sender, config.messages.targetOffline)
                return@launch
            }
            if (targetSnapshot.exemptFromVote) {
                sendForPlayer(sender, config.messages.targetProtected, "target" to targetSnapshot.playerName)
                return@launch
            }
            if (targetSnapshot.playerUuid == sender.uniqueId) {
                sendForPlayer(sender, config.messages.cannotTargetSelf)
                return@launch
            }

            val result = withContext(ioDispatcher) {
                repository.createSessionIfAbsent(
                    targetUuid = targetSnapshot.playerUuid,
                    targetName = targetSnapshot.playerName,
                    startedByUuid = sender.uniqueId,
                    startedByName = sender.name,
                    reason = reason.id,
                    createdAt = now,
                    expiresAt = now.plusSeconds(config.vote.openSeconds),
                )?.also {
                    repository.updateStarterCooldown(sender.uniqueId, now)
                }
            }

            if (result == null) {
                sendForPlayer(sender, config.messages.activeVoteExists)
                return@launch
            }

            announcedSessions.add(result.session.id)
            sendForPlayer(
                sender,
                config.messages.voteStarted,
                "target" to targetSnapshot.playerName,
                "reason" to reasonDisplay(reason.id),
                "reason_description" to reasonDescription(reason.id),
            )
            broadcastVoteStarted(result.session)
        }
    }

    private suspend fun resolveTargetSnapshot(localTarget: Player?, targetName: String, now: Instant): OnlinePlayerSnapshot? {
        if (localTarget != null && localTarget.isOnline) {
            return OnlinePlayerSnapshot(
                playerUuid = localTarget.uniqueId,
                playerName = localTarget.name,
                exemptFromVote = localTarget.hasPermission(config.permissions.exemptTarget),
            )
        }
        if (!config.storage.usesMysql) {
            return null
        }
        return withContext(ioDispatcher) {
            repository.findOnlinePlayerByName(targetName, now)
        }
    }

    private suspend fun computeRemainingCooldown(starterUuid: UUID, now: Instant): Long {
        val lastStartedAt = repository.findStarterCooldown(starterUuid) ?: return 0L
        val elapsed = Duration.between(lastStartedAt, now).seconds
        return (config.vote.cooldownSeconds - elapsed).coerceAtLeast(0L)
    }

    private fun broadcastVoteStarted(session: VoteSession) {
        scheduler.executeGlobal {
            Bukkit.getOnlinePlayers().forEach { player ->
                notifySinglePlayer(player, session)
            }
        }
    }

    private fun notifySinglePlayer(player: Player, session: VoteSession) {
        scheduler.executeFor(player) {
            if (!markDelivered(player.uniqueId, session.id)) {
                return@executeFor
            }
            val renderedReason = reasonDisplay(session.reason)
            val reasonDescription = reasonDescription(session.reason)
            PlayerMessenger.sendPlayer(
                player,
                render(
                    config.messages.voteStartedBroadcast,
                    "starter" to session.startedByName,
                    "target" to session.targetName,
                    "reason" to renderedReason,
                    "reason_description" to reasonDescription,
                ),
            )
            PlayerMessenger.sendPlayer(
                player,
                render(
                    config.messages.voteGuide,
                    "target" to session.targetName,
                    "reason" to renderedReason,
                    "reason_description" to reasonDescription,
                ),
            )
            PlayerMessenger.sendPlayer(
                player,
                render(
                    config.messages.voteGuideDetail,
                    "reason" to renderedReason,
                    "reason_description" to reasonDescription,
                ),
            )
            PlayerMessenger.sendClickable(player, buildClickableVoteComponent(session))
            if (config.bossBar.enabled && player.hasPermission(config.permissions.notify)) {
                showBossBar(player, session)
            }
        }
    }

    private fun buildClickableVoteComponent(session: VoteSession): Component {
        val renderedReason = reasonDisplay(session.reason)
        val reasonDescription = reasonDescription(session.reason)
        val blackText = render(config.messages.voteClickableBlack)
            .hoverEvent(
                HoverEvent.showText(
                    render(
                        config.messages.voteHoverBlack,
                        "target" to session.targetName,
                        "reason" to renderedReason,
                        "reason_description" to reasonDescription,
                    ),
                ),
            )
            .clickEvent(ClickEvent.runCommand("/ballotcraft vote ${session.id} black"))
        val divider = miniMessage.deserialize(" <#888888>|</#888888> ")
        val redText = render(config.messages.voteClickableRed)
            .hoverEvent(
                HoverEvent.showText(
                    render(
                        config.messages.voteHoverRed,
                        "target" to session.targetName,
                        "reason" to renderedReason,
                        "reason_description" to reasonDescription,
                    ),
                ),
            )
            .clickEvent(ClickEvent.runCommand("/ballotcraft vote ${session.id} red"))
        return Component.empty().append(blackText).append(divider).append(redText)
    }

    private fun showBossBar(player: Player, session: VoteSession) {
        val playerBossBars = bossBars.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        playerBossBars.remove(session.id)?.let { existing ->
            existing.removePlayer(player)
            existing.isVisible = false
        }
        val bossBar = Bukkit.createBossBar(
            legacyText(
                buildBossBarTemplate(),
                "target" to session.targetName,
                "reason" to reasonDisplay(session.reason),
                "reason_description" to reasonDescription(session.reason),
                "red" to 0,
                "black" to 0,
                "seconds" to config.vote.openSeconds,
            ),
            parseBossBarColor(config.bossBar.color),
            parseBossBarOverlay(config.bossBar.overlay),
        )
        bossBar.progress = config.bossBar.progress.coerceIn(0.0, 1.0)
        playerBossBars[session.id] = bossBar
        bossBar.addPlayer(player)
        bossBar.isVisible = true
        refreshBossBarForPlayer(player.uniqueId, session.id)
    }

    private fun resolveLiveSession(session: VoteSession, tally: VoteTally, type: VoteResolutionType) {
        scope.launch {
            val resolution = withContext(ioDispatcher) {
                repository.tryResolveSession(session.id, tally, Instant.now(), type)
            } ?: return@launch
            applyResolution(resolution, announce = true, executeSanction = true)
        }
    }

    private fun scheduleExpirySweep() {
        scheduler.runLaterGlobal(20L) {
            if (!ready.get()) {
                return@runLaterGlobal
            }
            scope.launch {
                runExpirySweep()
                scheduleExpirySweep()
            }
        }
    }

    private fun scheduleSyncPoll() {
        val delayTicks = config.vote.syncPollSeconds.coerceAtLeast(1L) * 20L
        scheduler.runLaterGlobal(delayTicks) {
            if (!ready.get()) {
                return@runLaterGlobal
            }
            scope.launch {
                pollRemoteState()
                scheduleSyncPoll()
            }
        }
    }

    private fun scheduleOnlineHeartbeat() {
        val delayTicks = config.vote.onlineHeartbeatSeconds.coerceAtLeast(1L) * 20L
        scheduler.runLaterGlobal(delayTicks) {
            if (!ready.get()) {
                return@runLaterGlobal
            }
            scope.launch {
                syncOnlinePlayersNow()
                scheduleOnlineHeartbeat()
            }
        }
    }

    private fun scheduleBossBarRefresh() {
        val delayTicks = config.bossBar.updateIntervalTicks.coerceAtLeast(1L)
        scheduler.runLaterGlobal(delayTicks) {
            if (!ready.get()) {
                return@runLaterGlobal
            }
            scope.launch {
                refreshAllBossBars()
                scheduleBossBarRefresh()
            }
        }
    }

    private suspend fun runExpirySweep() {
        try {
            val resolved = withContext(ioDispatcher) {
                repository.resolveExpiredSessions(
                    now = Instant.now(),
                    blackExcessMultiplier = config.vote.blackExcessMultiplier,
                    minBlackVotesToSanction = config.vote.minBlackVotesToSanction,
                )
            }
            resolved.forEach { resolution ->
                applyResolution(
                    resolution = resolution,
                    announce = true,
                    executeSanction = resolution.type == VoteResolutionType.SANCTIONED,
                )
            }
        } catch (exception: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to sweep expired vote sessions", exception)
        }
    }

    private suspend fun pollRemoteState() {
        try {
            val now = Instant.now()
            val sessions = withContext(ioDispatcher) {
                repository.findActiveSessions(now)
            }
            sessions.forEach { session ->
                if (announcedSessions.add(session.id)) {
                    broadcastVoteStarted(session)
                }
            }
            val resolutions = withContext(ioDispatcher) {
                repository.findPendingResolutions(serverId, now, 100)
            }
            resolutions.forEach { resolution ->
                applyResolution(
                    resolution = resolution,
                    announce = true,
                    executeSanction = false,
                )
                withContext(ioDispatcher) {
                    repository.markResolutionDelivered(resolution.session.id, serverId)
                }
            }
            withContext(ioDispatcher) {
                repository.purgeExpiredOnlinePlayers(now.minusSeconds(config.vote.onlineExpireSeconds))
            }
        } catch (exception: Exception) {
            plugin.logger.log(Level.WARNING, "Failed to poll shared vote sessions", exception)
        }
    }

    private suspend fun syncOnlinePlayersNow() {
        val now = Instant.now()
        withContext(ioDispatcher) {
            repository.purgeExpiredOnlinePlayers(now.minusSeconds(config.vote.onlineExpireSeconds))
            Bukkit.getOnlinePlayers().forEach { player ->
                repository.heartbeatOnlinePlayer(
                    serverId,
                    player.uniqueId,
                    player.name,
                    player.hasPermission(config.permissions.exemptTarget),
                    now,
                )
            }
        }
    }

    private suspend fun applyResolution(
        resolution: VoteResolution,
        announce: Boolean,
        executeSanction: Boolean,
    ) {
        forgetSession(resolution.session.id)
        clearBossBarsForSession(resolution.session.id)

        if (executeSanction && resolution.type == VoteResolutionType.SANCTIONED) {
            val command = sanctionCommandFor(resolution.session.reason)
                .replace("{player}", resolution.session.targetName)
                .replace("{black}", resolution.tally.blackVotes.toString())
                .replace("{red}", resolution.tally.redVotes.toString())
                .replace("{reason}", reasonDisplayName(resolution.session.reason))
            val dispatched = dispatchConsoleCommand(command)
            if (!dispatched) {
                plugin.logger.warning("BallotCraft failed to dispatch sanction command: $command")
            }
        }

        if (!announce) {
            return
        }

        scheduler.executeGlobal {
            Bukkit.getOnlinePlayers().forEach { player ->
                val message = when (resolution.type) {
                    VoteResolutionType.SANCTIONED -> render(
                        config.messages.votePassed,
                        "target" to resolution.session.targetName,
                        "reason" to reasonDisplay(resolution.session.reason),
                        "reason_description" to reasonDescription(resolution.session.reason),
                    )

                    VoteResolutionType.FAILED -> render(
                        config.messages.voteFailed,
                        "target" to resolution.session.targetName,
                        "reason" to reasonDisplay(resolution.session.reason),
                        "reason_description" to reasonDescription(resolution.session.reason),
                        "black" to resolution.tally.blackVotes,
                        "red" to resolution.tally.redVotes,
                    )
                }
                PlayerMessenger.sendPlayer(player, message)
            }
        }
    }

    private suspend fun dispatchConsoleCommand(command: String): Boolean {
        return withTimeoutOrNull(5_000L) {
            val result = CompletableDeferred<Boolean>()
            scheduler.executeGlobal {
                try {
                    result.complete(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command))
                } catch (exception: Throwable) {
                    result.completeExceptionally(exception)
                }
            }
            result.await()
        } ?: false
    }

    private fun shouldSanction(tally: VoteTally): Boolean {
        return tally.blackVotes >= config.vote.minBlackVotesToSanction &&
            tally.blackVotes > (tally.redVotes * config.vote.blackExcessMultiplier)
    }

    private fun voteLabel(choice: VoteChoice): String {
        return when (choice) {
            VoteChoice.RED -> config.messages.voteLabelRed
            VoteChoice.BLACK -> config.messages.voteLabelBlack
        }
    }

    private fun findReason(raw: String): BallotCraftConfig.VoteReasonSettings? {
        return config.vote.reasons.firstOrNull { it.matches(raw) }
    }

    private fun findReasonById(raw: String): BallotCraftConfig.VoteReasonSettings? {
        return reasonsById[raw.lowercase(Locale.ROOT)]
    }

    private fun reasonDisplayName(raw: String): String {
        return findReasonById(raw)?.displayName ?: raw
    }

    private fun reasonDescription(raw: String): String {
        return findReasonById(raw)?.description?.let(miniMessage::escapeTags) ?: raw
    }

    private fun reasonDisplay(raw: String): String {
        val reason = findReasonById(raw) ?: return raw
        return "<${reason.displayColor}>${miniMessage.escapeTags(reason.displayName)}<reset>"
    }

    private fun sanctionCommandFor(reasonId: String): String {
        return findReasonById(reasonId)?.sanctionCommand
            ?: config.vote.reasons.first().sanctionCommand
    }

    private suspend fun refreshAllBossBars() {
        if (bossBars.isEmpty()) {
            return
        }
        val now = Instant.now()
        val snapshots = withContext(ioDispatcher) {
            val sessionIds = bossBars.values.asSequence()
                .flatMap { it.keys.asSequence() }
                .distinct()
                .toList()
            buildMap<Long, Pair<VoteSession, VoteTally>> {
                sessionIds.forEach { sessionId ->
                    val session = repository.findActiveSessionById(sessionId, now) ?: return@forEach
                    val tally = repository.tally(sessionId)
                    put(sessionId, session to tally)
                }
            }
        }

        bossBars.entries.toList().forEach { (playerUuid, playerBossBars) ->
            val player = Bukkit.getPlayer(playerUuid)
            if (player == null || !player.isOnline) {
                clearPlayerState(playerUuid)
                return@forEach
            }

            playerBossBars.keys.toList().forEach { sessionId ->
                val snapshot = snapshots[sessionId]
                if (snapshot == null) {
                    removeBossBar(playerUuid, sessionId)
                    return@forEach
                }

                val (session, tally) = snapshot
                val remainingSeconds = Duration.between(now, session.expiresAt).seconds.coerceAtLeast(0L)
                val progress = computeBossBarProgress(session, now)
                scheduler.executeFor(player) {
                    val current = bossBars[playerUuid]?.get(sessionId) ?: return@executeFor
                    current.setTitle(
                        legacyText(
                            buildBossBarTemplate(),
                            "target" to session.targetName,
                            "reason" to reasonDisplay(session.reason),
                            "reason_description" to reasonDescription(session.reason),
                            "red" to tally.redVotes,
                            "black" to tally.blackVotes,
                            "seconds" to remainingSeconds,
                        ),
                    )
                    current.progress = progress
                    if (!current.isVisible) {
                        current.isVisible = true
                    }
                    if (!current.players.contains(player)) {
                        current.addPlayer(player)
                    }
                }
            }
        }
    }

    private fun refreshBossBarsForSession(sessionId: Long) {
        bossBars.entries
            .filter { it.value.containsKey(sessionId) }
            .forEach { (playerUuid, _) ->
                refreshBossBarForPlayer(playerUuid, sessionId)
            }
    }

    private fun refreshBossBarForPlayer(playerUuid: UUID, sessionId: Long) {
        scope.launch {
            val tracked = bossBars[playerUuid]?.get(sessionId)
            if (tracked == null) {
                return@launch
            }
            val now = Instant.now()
            val snapshot = withContext(ioDispatcher) {
                val session = repository.findActiveSessionById(sessionId, now) ?: return@withContext null
                session to repository.tally(sessionId)
            } ?: run {
                removeBossBar(playerUuid, sessionId)
                return@launch
            }

            val player = Bukkit.getPlayer(playerUuid)
            if (player == null || !player.isOnline) {
                clearPlayerState(playerUuid)
                return@launch
            }

            val (session, tally) = snapshot
            val remainingSeconds = Duration.between(now, session.expiresAt).seconds.coerceAtLeast(0L)
            val progress = computeBossBarProgress(session, now)
            scheduler.executeFor(player) {
                val current = bossBars[playerUuid]?.get(sessionId) ?: return@executeFor
                current.setTitle(
                    legacyText(
                        buildBossBarTemplate(),
                        "target" to session.targetName,
                        "reason" to reasonDisplay(session.reason),
                        "reason_description" to reasonDescription(session.reason),
                        "red" to tally.redVotes,
                        "black" to tally.blackVotes,
                        "seconds" to remainingSeconds,
                    ),
                )
                current.progress = progress
            }
        }
    }

    private fun markDelivered(playerUuid: UUID, sessionId: Long): Boolean {
        return deliveredSessions.computeIfAbsent(playerUuid) {
            ConcurrentHashMap.newKeySet()
        }.add(sessionId)
    }

    private fun forgetSession(sessionId: Long) {
        announcedSessions.remove(sessionId)
        deliveredSessions.entries.removeIf { (_, sessions) ->
            sessions.remove(sessionId)
            sessions.isEmpty()
        }
    }

    private fun clearPlayerState(playerUuid: UUID) {
        deliveredSessions.remove(playerUuid)
        bossBars.remove(playerUuid)?.let { playerBossBars ->
            Bukkit.getPlayer(playerUuid)?.let { player ->
                scheduler.executeFor(player) {
                    playerBossBars.values.forEach { bossBar ->
                        bossBar.removePlayer(player)
                        bossBar.isVisible = false
                    }
                }
            }
        }
    }

    private fun clearBossBarsForSession(sessionId: Long) {
        bossBars.keys.toList().forEach { playerUuid ->
            removeBossBar(playerUuid, sessionId)
        }
    }

    private fun removeBossBar(playerUuid: UUID, sessionId: Long) {
        val playerBossBars = bossBars[playerUuid] ?: return
        val tracked = playerBossBars.remove(sessionId) ?: return
        if (playerBossBars.isEmpty()) {
            bossBars.remove(playerUuid, playerBossBars)
        }
        Bukkit.getPlayer(playerUuid)?.let { player ->
            scheduler.executeFor(player) {
                tracked.removePlayer(player)
                tracked.isVisible = false
            }
        }
    }

    private fun buildBossBarTemplate(): String {
        val configured = config.bossBar.title
        val withVotes = buildString {
            append(configured)
            if (!configured.contains("{reason}")) {
                append(" <#A4B0BE>| <#ECCC68>{reason}")
            }
            if (!configured.contains("{black}")) {
                append(" <#A4B0BE>| <#FF4757>${config.messages.voteLabelBlack} {black}")
            }
            if (!configured.contains("{red}")) {
                append(" <#A4B0BE>| <#2ED573>${config.messages.voteLabelRed} {red}")
            }
        }
        return if (withVotes.contains("{seconds}")) {
            withVotes
        } else {
            "$withVotes <#A4B0BE>| <#FFD166>{seconds}s"
        }
    }

    private fun computeBossBarProgress(session: VoteSession, now: Instant): Double {
        val totalMillis = Duration.between(session.createdAt, session.expiresAt).toMillis().coerceAtLeast(1L)
        val remainingMillis = Duration.between(now, session.expiresAt).toMillis().coerceAtLeast(0L)
        return (remainingMillis.toDouble() / totalMillis.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun sendForPlayer(player: Player, template: String, vararg placeholders: Pair<String, Any>) {
        scheduler.executeFor(player) {
            PlayerMessenger.sendPlayer(player, renderWithPrefix(template, *placeholders))
        }
    }

    private fun sendPrefixed(sender: CommandSender, template: String, vararg placeholders: Pair<String, Any>) {
        PlayerMessenger.send(sender, renderWithPrefix(template, *placeholders))
    }

    private fun renderWithPrefix(template: String, vararg placeholders: Pair<String, Any>): Component {
        return Component.empty()
            .append(render(config.messages.prefix))
            .append(render(template, *placeholders))
    }

    private fun render(template: String, vararg placeholders: Pair<String, Any>): Component {
        var processed = template
        buildList {
            add("black_excess_times" to formatDecimal(config.vote.blackExcessMultiplier))
            add("min_black_votes" to config.vote.minBlackVotesToSanction)
            addAll(placeholders)
        }.forEach { (key, value) ->
            processed = processed.replace("{$key}", value.toString())
        }
        return miniMessage.deserialize(processed)
    }

    private fun formatDecimal(value: Double): String {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    private fun legacyText(template: String, vararg placeholders: Pair<String, Any>): String {
        return legacySerializer.serialize(render(template, *placeholders))
    }

    private fun parseBossBarColor(raw: String): BarColor {
        return runCatching { BarColor.valueOf(raw.uppercase(Locale.ROOT)) }.getOrDefault(BarColor.RED)
    }

    private fun parseBossBarOverlay(raw: String): BarStyle {
        return runCatching { BarStyle.valueOf(raw.uppercase(Locale.ROOT)) }.getOrDefault(BarStyle.SOLID)
    }
}
