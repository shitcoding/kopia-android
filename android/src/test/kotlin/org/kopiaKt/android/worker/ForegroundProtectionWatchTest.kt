package org.kopiaKt.android.worker

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Noticing that a backup is running without a foreground service (task-60).
 *
 * WorkManager cannot tell us. Its `SystemForegroundService` **catches and logs** the promotion-stage
 * refusal — verified in the shipped 2.10.0 artifact — so `setForeground` returns normally whenever
 * the service starts but the promotion is denied. Measured on a device: the framework refused the
 * promotion twice and the backup ran to completion, with the app none the wiser.
 *
 * So the run watches its own process importance instead, which answers the question that actually
 * matters — "am I unprotected RIGHT NOW" — rather than "did the promotion take". While the app is in
 * front the process is IMPORTANCE_FOREGROUND whether or not a service was promoted, and that is not
 * a blind spot: a foregrounded app is not going to be killed for lacking a service it does not yet
 * need. The answer only becomes interesting once the user leaves.
 */
@DisplayName("Foreground protection watch (task-60)")
class ForegroundProtectionWatchTest {

    @Test
    fun `a foregrounded app is protected, promoted or not`() {
        val watch = ForegroundProtectionWatch()

        assertThat(watch.observe(IMPORTANCE_FOREGROUND)).isFalse()
        assertThat(watch.observe(IMPORTANCE_FOREGROUND)).isFalse()
    }

    @Test
    fun `a promoted service in the background is protected`() {
        val watch = ForegroundProtectionWatch()

        assertThat(watch.observe(IMPORTANCE_FOREGROUND_SERVICE)).isFalse()
        assertThat(watch.observe(IMPORTANCE_FOREGROUND_SERVICE)).isFalse()
    }

    @Test
    fun `one bad reading is not enough`() {
        // Leaving the app passes through transitional states; acting on a single sample would call a
        // healthy run unprotected every time the user switched away.
        val watch = ForegroundProtectionWatch()

        assertThat(watch.observe(IMPORTANCE_SERVICE)).isFalse()
    }

    @Test
    fun `two consecutive bad readings mean the run is unprotected`() {
        val watch = ForegroundProtectionWatch()

        assertThat(watch.observe(IMPORTANCE_SERVICE)).isFalse()
        assertThat(watch.observe(IMPORTANCE_SERVICE)).isTrue()
    }

    @Test
    fun `it keeps saying so until delivery is confirmed, then stops`() {
        // The session it has to tell may not be registered yet -- the progress loop starts before
        // the session does. Latching on the READING would drop that report for the rest of the run.
        val watch = ForegroundProtectionWatch()

        assertThat(watch.observe(IMPORTANCE_SERVICE)).isFalse()
        assertThat(watch.observe(IMPORTANCE_SERVICE)).isTrue()
        assertThat(watch.observe(IMPORTANCE_SERVICE)).isTrue()

        watch.markDelivered()

        assertThat(watch.observe(IMPORTANCE_SERVICE)).isFalse()
        assertThat(watch.observe(IMPORTANCE_CACHED)).isFalse()
    }

    @Test
    fun `a good reading between two bad ones resets the count`() {
        val watch = ForegroundProtectionWatch()

        assertThat(watch.observe(IMPORTANCE_SERVICE)).isFalse()
        assertThat(watch.observe(IMPORTANCE_FOREGROUND)).isFalse()
        assertThat(watch.observe(IMPORTANCE_SERVICE)).isFalse()
        assertThat(watch.observe(IMPORTANCE_SERVICE)).isTrue()
    }

    @Test
    fun `losing protection shortens the checkpoint interval, floored`() {
        // What the report is FOR: a kill is now plausible, and everything since the last checkpoint
        // is what one costs. Nothing else about the run changes.
        val options = CheckpointOptions(intervalMillis = 5L * 60 * 1000)

        assertThat(options.effectiveIntervalMillis).isEqualTo(5L * 60 * 1000)
        assertThat(options.unprotectedIntervalMillis).isEqualTo(75L * 1000)

        val tiny = CheckpointOptions(intervalMillis = 1000)
        assertThat(tiny.unprotectedIntervalMillis)
            .isEqualTo(CheckpointOptions.MIN_CHECKPOINT_INTERVAL_MILLIS)
    }
}
