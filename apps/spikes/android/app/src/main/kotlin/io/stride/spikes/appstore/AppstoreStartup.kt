package io.stride.spikes.appstore

import java.util.concurrent.Executor

/**
 * Runs launch-time catalog restoration without retaining an Activity or tying work to its lifecycle.
 */
internal class AppstoreStartup(private val executor: Executor) {
    fun initialize(
        shouldCheck: Boolean,
        check: () -> Unit,
        restore: () -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        if (shouldCheck) {
            check()
        } else {
            run(restore, onFailure)
        }
    }

    fun run(work: () -> Unit, onFailure: (Exception) -> Unit) {
        executor.execute {
            try {
                work()
            } catch (e: Exception) {
                onFailure(e)
            }
        }
    }
}
