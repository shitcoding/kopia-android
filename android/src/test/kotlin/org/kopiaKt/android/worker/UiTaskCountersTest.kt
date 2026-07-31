package org.kopiaKt.android.worker

import androidx.work.Data
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.upload.UploadCounters

/**
 * The counter map the Tasks screen reads, pinned against Go's `CountingUploadProgress.UITaskCounters`
 * (`snapshot/upload/upload_progress.go:310-342`).
 *
 * The names are the contract: the UI looks them up by string ("Uploaded Bytes", "Estimated Bytes"),
 * and so does desktop Kopia's. A rename here is a silent UI regression, not a compile error.
 */
class UiTaskCountersTest {

    private val counters = UploadCounters(
        totalCachedBytes = 100,
        totalHashedBytes = 200,
        totalUploadedBytes = 250,
        estimatedBytes = 4000,
        totalCachedFiles = 4,
        totalHashedFiles = 6,
        totalExcludedFiles = 2,
        totalExcludedDirs = 1,
        fatalErrorCount = 3,
        estimatedFiles = 50,
    )

    @Test
    fun `names and values match Go's UITaskCounters`() {
        val m = counters.toUiTaskCounters(final = false)

        assertThat(m["Cached Files"]?.value).isEqualTo(4)
        assertThat(m["Hashed Files"]?.value).isEqualTo(6)
        assertThat(m["Processed Files"]?.value).isEqualTo(10)

        assertThat(m["Cached Bytes"]?.value).isEqualTo(100)
        assertThat(m["Hashed Bytes"]?.value).isEqualTo(200)
        assertThat(m["Processed Bytes"]?.value).isEqualTo(300)

        // Deliberately NOT hashed+cached (which is 300 here): Go reports bytes actually sent to
        // the server, which is what tells a user on a metered connection what this costs them.
        assertThat(m["Uploaded Bytes"]?.value).isEqualTo(250)

        assertThat(m["Excluded Files"]?.value).isEqualTo(2)
        assertThat(m["Excluded Directories"]?.value).isEqualTo(1)
        assertThat(m["Errors"]?.value).isEqualTo(3)
    }

    @Test
    fun `bytes carry the bytes unit and errors carry the error level`() {
        val m = counters.toUiTaskCounters(final = false)

        // The UI formats on `units`, so a byte count without it renders as a bare number.
        assertThat(m["Uploaded Bytes"]?.units).isEqualTo("bytes")
        assertThat(m["Cached Files"]?.units).isEmpty()
        assertThat(m["Errors"]?.level).isEqualTo("error")
    }

    @Test
    fun `counters survive the trip through WorkManager progress`() {
        // The worker publishes into WorkManager Data and the interactive task reads it back out,
        // often in a different process. A field dropped here is a number that silently reads zero
        // in the UI for the whole run.
        val restored = counters.toProgressData().toUploadCounters()

        assertThat(restored).isEqualTo(
            counters.copy(
                // Not carried: the wire exists for the counter map, and these two are neither in it
                // nor shown anywhere. Pinned so adding them later is a deliberate act.
                ignoredErrorCount = 0,
                lastErrorPath = "",
                lastError = "",
            ),
        )
    }

    @Test
    fun `the final counters ride out with the successful result`() {
        // The progress loop delays BEFORE its first publish, so a backup that finishes inside one
        // second -- an incremental run with nothing to do -- publishes no progress at all. Without
        // the counters in the terminal Data the finished task shows an empty map, which is the very
        // "task with no numbers" this whole change exists to remove.
        val output = Data.Builder()
            .putAll(counters.toProgressData())
            .putString("manifest_id", "m1")
            .build()

        assertThat(output.toUploadCounters()?.totalUploadedBytes).isEqualTo(250)
    }

    @Test
    fun `empty progress reads as no counters rather than as a run that did nothing`() {
        // WorkManager hands out empty progress before the first publish and again once the work
        // finishes. Zeroes there would redraw a finished backup as one that copied nothing.
        assertThat(Data.EMPTY.toUploadCounters()).isNull()
    }

    @Test
    fun `estimates are dropped once the run is final`() {
        // Go omits them from the final map: an estimate next to a finished total is noise at best,
        // and reads as a shortfall at worst.
        assertThat(counters.toUiTaskCounters(final = false).keys).containsAtLeast(
            "Estimated Files",
            "Estimated Bytes",
        )
        assertThat(counters.toUiTaskCounters(final = true).keys).containsNoneOf(
            "Estimated Files",
            "Estimated Bytes",
        )
    }
}
