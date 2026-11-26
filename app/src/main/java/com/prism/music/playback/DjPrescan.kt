package com.prism.music.playback

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Compatibility tombstone for WorkManager entries persisted by old versions.
 * Never scan, download or analyze music for the retired DJ mode.
 */
class DjPrescanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()

    companion object {
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("dj_prescan")
        }

        // Kept for source compatibility with older callers and tests.
        fun schedule(context: Context) = cancel(context)
        internal fun mayRun(screenOn: Boolean, playing: Boolean) = false
    }
}
