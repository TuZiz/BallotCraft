package ym.ballotcraft.platform

fun interface TaskHandle {
    fun cancel()

    companion object {
        val NOOP = TaskHandle {}
    }
}
