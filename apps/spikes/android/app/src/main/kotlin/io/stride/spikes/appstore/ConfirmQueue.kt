package io.stride.spikes.appstore

/**
 * Whose install confirmation is on screen, and who is waiting.
 *
 * Pure policy, no Android: the ordering is the part that has to be right, and it is the part that
 * cannot be tested through a `PackageInstaller` dialog.
 *
 * The rule is one prompt at a time. Starting a second confirmation activity while the first is up
 * destroys the first *silently* - the platform delivers no success and no `STATUS_FAILURE_ABORTED` -
 * so that package would otherwise sit in AWAITING_USER forever with its Install button disabled.
 */
class ConfirmQueue {
    private val waiting = LinkedHashSet<String>()
    private var holder: String? = null

    /** Who currently owns the screen, if anyone. */
    fun holder(): String? = holder

    fun isWaiting(packageName: String): Boolean = packageName in waiting

    /**
     * Ask to show a confirmation. True means show it now; false means someone else holds the
     * screen and this one has been queued behind them.
     */
    fun offer(packageName: String): Boolean {
        waiting.add(packageName)
        val current = holder
        if (current != null && current != packageName) return false
        holder = packageName
        return true
    }

    /** Re-raise a prompt already queued or held. False when there is nothing to re-raise. */
    fun reshow(packageName: String): Boolean {
        if (packageName !in waiting) return false
        holder = packageName
        return true
    }

    /**
     * This package reached a terminal state. Returns whoever should be shown next, so a queued
     * install is never stranded behind one the user already dealt with.
     */
    fun settled(packageName: String): String? {
        waiting.remove(packageName)
        if (holder == packageName) holder = null
        if (holder != null) return null
        val next = waiting.firstOrNull() ?: return null
        holder = next
        return next
    }

    /** The prompt could not be raised at all; drop it rather than block everyone behind it. */
    fun abandon(packageName: String): String? = settled(packageName)

    fun reset() {
        waiting.clear()
        holder = null
    }
}
