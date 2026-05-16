package ym.ballotcraft.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import ym.ballotcraft.service.VoteService

class PlayerLifecycleListener(
    private val voteService: VoteService,
) : Listener {
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        voteService.onPlayerJoin(event.player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        voteService.onPlayerQuit(event.player)
    }
}
