package org.kopiaKt.snapshot.maintenance

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class SnapshotGCTest {

    @Test
    fun `GCOptions has correct defaults`() {
        val options = GCOptions()

        assertThat(options.delete).isFalse()
        assertThat(options.safety).isEqualTo(SafetyParameters.Default)
        assertThat(options.onProgress).isNull()
    }

    @Test
    fun `GCOptions can be customized`() {
        var progressCalled = false
        val customSafety = SafetyParameters(
            minContentAgeSubjectToGC = Duration.ofHours(12)
        )

        val options = GCOptions(
            delete = true,
            safety = customSafety,
            onProgress = { progressCalled = true }
        )

        assertThat(options.delete).isTrue()
        assertThat(options.safety.minContentAgeSubjectToGC).isEqualTo(Duration.ofHours(12))

        options.onProgress?.invoke(GCProgress("test"))
        assertThat(progressCalled).isTrue()
    }

    @Test
    fun `GCProgress holds progress information`() {
        val progress = GCProgress(
            phase = "Walking snapshot trees",
            processedSnapshots = 5,
            totalSnapshots = 10,
            processedContents = 1000,
            inUseContents = 500
        )

        assertThat(progress.phase).isEqualTo("Walking snapshot trees")
        assertThat(progress.processedSnapshots).isEqualTo(5)
        assertThat(progress.totalSnapshots).isEqualTo(10)
        assertThat(progress.processedContents).isEqualTo(1000)
        assertThat(progress.inUseContents).isEqualTo(500)
    }

    @Test
    fun `SnapshotGCStats defaults are zero`() {
        val stats = SnapshotGCStats()

        assertThat(stats.unreferencedContentCount).isEqualTo(0)
        assertThat(stats.unreferencedContentSize).isEqualTo(0)
        assertThat(stats.deletedContentCount).isEqualTo(0)
        assertThat(stats.deletedContentSize).isEqualTo(0)
        assertThat(stats.unreferencedRecentContentCount).isEqualTo(0)
        assertThat(stats.unreferencedRecentContentSize).isEqualTo(0)
        assertThat(stats.inUseContentCount).isEqualTo(0)
        assertThat(stats.inUseContentSize).isEqualTo(0)
        assertThat(stats.inUseSystemContentCount).isEqualTo(0)
        assertThat(stats.inUseSystemContentSize).isEqualTo(0)
        assertThat(stats.recoveredContentCount).isEqualTo(0)
        assertThat(stats.recoveredContentSize).isEqualTo(0)
    }

    @Test
    fun `SnapshotGCStats can be created with values`() {
        val stats = SnapshotGCStats(
            unreferencedContentCount = 100,
            unreferencedContentSize = 1000000,
            deletedContentCount = 50,
            deletedContentSize = 500000,
            unreferencedRecentContentCount = 50,
            unreferencedRecentContentSize = 500000,
            inUseContentCount = 1000,
            inUseContentSize = 10000000,
            inUseSystemContentCount = 10,
            inUseSystemContentSize = 100000,
            recoveredContentCount = 5,
            recoveredContentSize = 50000
        )

        assertThat(stats.unreferencedContentCount).isEqualTo(100)
        assertThat(stats.unreferencedContentSize).isEqualTo(1000000)
        assertThat(stats.deletedContentCount).isEqualTo(50)
        assertThat(stats.inUseContentCount).isEqualTo(1000)
        assertThat(stats.inUseSystemContentCount).isEqualTo(10)
        assertThat(stats.recoveredContentCount).isEqualTo(5)
    }

    // Note: Full integration tests for SnapshotGC.run() would require
    // a mock DirectRepository with test data. Those tests would be added
    // when the ContentManager has the necessary methods for iterating
    // all content including deleted.
}
