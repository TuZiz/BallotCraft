package ym.ballotcraft.platform

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.chat.ComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

object PlayerMessenger {
    private val componentSendMethod = Player::class.java.methods.firstOrNull { method ->
        method.name == "sendMessage" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == Component::class.java
    }

    fun send(sender: CommandSender, component: Component) {
        when (sender) {
            is Player -> sendPlayer(sender, component)
            else -> sender.sendMessage(PlainTextComponentSerializer.plainText().serialize(component))
        }
    }

    fun sendPlayer(player: Player, component: Component) {
        val invoked = runCatching {
            componentSendMethod?.invoke(player, component)
            componentSendMethod != null
        }.getOrDefault(false)
        if (!invoked) {
            val baseComponents = toBaseComponents(component)
            player.spigot().sendMessage(*baseComponents)
        }
    }

    fun sendClickable(player: Player, component: Component) {
        val baseComponents = toBaseComponents(component)
        player.spigot().sendMessage(*baseComponents)
    }

    private fun toBaseComponents(component: Component): Array<BaseComponent> {
        val json = GsonComponentSerializer.gson().serialize(component)
        return ComponentSerializer.parse(json)
    }
}
