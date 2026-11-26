package com.example.mopedtrackv2.data.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.mopedtrackv2.MopedApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class RegionRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = (applicationContext as MopedApplication).repository

        if (!repository.weeklyAutoRefreshFlow.first()) {
            Log.d(TAG, "Weekly auto-refresh is disabled; skipping.")
            return Result.success()
        }

        val regions = repository.getAllRegions().first()
        if (regions.isEmpty()) {
            Log.d(TAG, "No downloaded regions to refresh.")
            return Result.success()
        }

        var hadTransientFailure = false
        for (region in regions) {
            try {
                val result = repository.refreshRegion(region.regionId)
                Log.d(
                    TAG,
                    "Auto-refreshed '${result.regionName}': total=${result.totalSegments}, " +
                        "preserved=${result.preservedCompletedCount}, gpxMatched=${result.gpxMatchedNewSegmentsCount}, " +
                        "new=${result.newSegmentsCount}"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-refresh region '${region.name}' (${region.regionId})", e)
                hadTransientFailure = true
            }
        }

        return if (hadTransientFailure && runAttemptCount < MAX_RETRY_ATTEMPTS) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val TAG = "RegionRefreshWorker"
        private const val MAX_RETRY_ATTEMPTS = 3
        const val UNIQUE_WORK_NAME = "weekly_region_refresh"

        fun buildPeriodicWorkRequest(): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()

            return PeriodicWorkRequestBuilder<RegionRefreshWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
        }

        fun enqueuePeriodicRefresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                buildPeriodicWorkRequest()
            )
            Log.d(TAG, "Scheduled weekly region auto-refresh work ($UNIQUE_WORK_NAME)")
        }

        fun cancelPeriodicRefresh(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Cancelled weekly region auto-refresh work ($UNIQUE_WORK_NAME)")
        }
    }
}
