package com.example.mopedtrackv2.data.worker

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class WeeklyRegionRefreshWorkerTest {

    @Test
    fun `buildPeriodicWorkRequest configures 7-day interval and unmetered network constraint`() {
        val request = RegionRefreshWorker.buildPeriodicWorkRequest()
        val workSpec = request.workSpec

        assertEquals("weekly_region_refresh", RegionRefreshWorker.UNIQUE_WORK_NAME)
        assertTrue(workSpec.isPeriodic)
        assertEquals(TimeUnit.DAYS.toMillis(7), workSpec.intervalDuration)
        assertEquals(NetworkType.UNMETERED, workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, workSpec.backoffPolicy)
    }
}
