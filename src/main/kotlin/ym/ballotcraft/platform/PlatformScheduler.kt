package ym.ballotcraft.platform

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

interface PlatformScheduler {
    val isFolia: Boolean

    fun executeFor(player: Player, task: Runnable)

    fun executeGlobal(task: Runnable)

    fun runLaterFor(player: Player, delayTicks: Long, task: Runnable): TaskHandle

    fun runLaterGlobal(delayTicks: Long, task: Runnable): TaskHandle

    companion object {
        fun create(plugin: JavaPlugin): PlatformScheduler {
            return if (Bukkit::class.java.methods.any { it.name == "getGlobalRegionScheduler" }) {
                FoliaPlatformScheduler(plugin)
            } else {
                BukkitPlatformScheduler(plugin)
            }
        }
    }
}

class BukkitPlatformScheduler(private val plugin: JavaPlugin) : PlatformScheduler {
    override val isFolia: Boolean = false

    override fun executeFor(player: Player, task: Runnable) {
        if (Bukkit.isPrimaryThread()) {
            task.run()
            return
        }
        Bukkit.getScheduler().runTask(plugin, task)
    }

    override fun executeGlobal(task: Runnable) {
        if (Bukkit.isPrimaryThread()) {
            task.run()
            return
        }
        Bukkit.getScheduler().runTask(plugin, task)
    }

    override fun runLaterFor(player: Player, delayTicks: Long, task: Runnable): TaskHandle {
        val scheduled = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks.coerceAtLeast(0L))
        return TaskHandle { scheduled.cancel() }
    }

    override fun runLaterGlobal(delayTicks: Long, task: Runnable): TaskHandle {
        val scheduled = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks.coerceAtLeast(0L))
        return TaskHandle { scheduled.cancel() }
    }
}

class FoliaPlatformScheduler(private val plugin: JavaPlugin) : PlatformScheduler {
    private val fallback = BukkitPlatformScheduler(plugin)
    private val bridge = ReflectiveFoliaBridge(plugin)

    override val isFolia: Boolean = true

    override fun executeFor(player: Player, task: Runnable) {
        if (!bridge.executeForNow(player, task)) {
            fallback.executeFor(player, task)
        }
    }

    override fun executeGlobal(task: Runnable) {
        if (!bridge.executeGlobal(task)) {
            fallback.executeGlobal(task)
        }
    }

    override fun runLaterFor(player: Player, delayTicks: Long, task: Runnable): TaskHandle {
        return if (delayTicks <= 0L) {
            bridge.runNowFor(player, task) ?: fallback.runLaterFor(player, 0L, task)
        } else {
            bridge.runDelayedFor(player, delayTicks, task) ?: fallback.runLaterFor(player, delayTicks, task)
        }
    }

    override fun runLaterGlobal(delayTicks: Long, task: Runnable): TaskHandle {
        return bridge.runDelayedGlobal(delayTicks, task) ?: fallback.runLaterGlobal(delayTicks, task)
    }

    private class RecursiveRepeatingTask(
        private val player: Player,
        private val scheduler: PlatformScheduler,
        private val delayTicks: Long,
        private val task: Runnable,
    ) : TaskHandle {
        private val cancelled = AtomicBoolean(false)
        @Volatile
        private var currentHandle: TaskHandle = TaskHandle.NOOP

        fun start() {
            schedule(delayTicks)
        }

        override fun cancel() {
            cancelled.set(true)
            currentHandle.cancel()
        }

        private fun schedule(nextDelay: Long) {
            if (cancelled.get()) {
                return
            }
            currentHandle = scheduler.runLaterFor(player, nextDelay) {
                if (cancelled.get()) {
                    return@runLaterFor
                }
                task.run()
                schedule(delayTicks)
            }
        }
    }
}

private class ReflectiveFoliaBridge(private val plugin: JavaPlugin) {
    private val bukkitClass = Bukkit::class.java

    fun executeGlobal(task: Runnable): Boolean {
        val scheduler = globalScheduler() ?: return false

        findMethod(scheduler.javaClass, "execute", 2)?.let { method ->
            method.invoke(scheduler, plugin, task)
            return true
        }

        findMethod(scheduler.javaClass, "run", 2)?.let { method ->
            method.invoke(scheduler, plugin, Consumer<Any?> { task.run() })
            return true
        }

        return false
    }

    fun runDelayedGlobal(delayTicks: Long, task: Runnable): TaskHandle? {
        val scheduler = globalScheduler() ?: return null

        findMethod(scheduler.javaClass, "runDelayed", 3)?.let { method ->
            val scheduledTask = method.invoke(
                scheduler,
                plugin,
                Consumer<Any?> { task.run() },
                delayTicks.coerceAtLeast(1L),
            )
            return ReflectiveTaskHandle(scheduledTask)
        }

        return null
    }

    fun executeForNow(player: Player, task: Runnable): Boolean {
        return runNowFor(player, task) != null
    }

    fun runNowFor(player: Player, task: Runnable): TaskHandle? {
        val scheduler = playerScheduler(player) ?: return null
        val retiredTask = Runnable {}

        findMethod(scheduler.javaClass, "run", 3)?.let { method ->
            val scheduledTask = method.invoke(
                scheduler,
                plugin,
                Consumer<Any?> { task.run() },
                retiredTask,
            )
            return ReflectiveTaskHandle(scheduledTask)
        }

        findMethod(scheduler.javaClass, "execute", 4)?.let { method ->
            method.invoke(scheduler, plugin, task, retiredTask, 1L)
            return TaskHandle.NOOP
        }

        return null
    }

    fun runDelayedFor(player: Player, delayTicks: Long, task: Runnable): TaskHandle? {
        val scheduler = playerScheduler(player) ?: return null
        val retiredTask = Runnable {}

        findMethod(scheduler.javaClass, "runDelayed", 4)?.let { method ->
            val scheduledTask = method.invoke(
                scheduler,
                plugin,
                Consumer<Any?> { task.run() },
                retiredTask,
                delayTicks.coerceAtLeast(1L),
            )
            return ReflectiveTaskHandle(scheduledTask)
        }

        findMethod(scheduler.javaClass, "execute", 4)?.let { method ->
            method.invoke(scheduler, plugin, task, retiredTask, delayTicks.coerceAtLeast(1L))
            return TaskHandle.NOOP
        }

        return null
    }

    private fun globalScheduler(): Any? = findMethod(bukkitClass, "getGlobalRegionScheduler", 0)?.invoke(null)

    private fun playerScheduler(player: Player): Any? = findMethod(player.javaClass, "getScheduler", 0)?.invoke(player)

    private fun findMethod(type: Class<*>, name: String, parameterCount: Int): Method? {
        return type.methods.firstOrNull { method ->
            method.name == name && method.parameterCount == parameterCount
        }
    }
}

private class ReflectiveTaskHandle(private val scheduledTask: Any?) : TaskHandle {
    private val cancelMethod = scheduledTask?.javaClass?.methods?.firstOrNull { method ->
        method.name == "cancel" && method.parameterCount == 0
    }?.apply {
        try {
            isAccessible = true
        } catch (_: Throwable) {
        }
    }

    override fun cancel() {
        try {
            cancelMethod?.invoke(scheduledTask)
        } catch (_: IllegalAccessException) {
            cancelMethod?.trySetAccessible()
            cancelMethod?.invoke(scheduledTask)
        }
    }
}
