package org.kopiaKt.snapshot.maintenance

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Duration

class SafetyParametersTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `default values are correct`() {
        val params = SafetyParameters.Default

        assertThat(params.minContentAgeSubjectToGC).isEqualTo(Duration.ofHours(24))
        assertThat(params.marginBetweenSnapshotGC).isEqualTo(Duration.ofHours(4))
        assertThat(params.requireTwoGCCycles).isTrue()
        assertThat(params.minRewriteToOrphanDeletionDelay).isEqualTo(Duration.ofHours(4))
        assertThat(params.orphanedPackMinAge).isEqualTo(Duration.ofHours(24))
        assertThat(params.rewriteMinAge).isEqualTo(Duration.ofHours(2))
        assertThat(params.disableEventualConsistencySafety).isFalse()
    }

    @Test
    fun `serialization round-trip preserves values`() {
        val params = SafetyParameters(
            minContentAgeSubjectToGC = Duration.ofHours(48),
            marginBetweenSnapshotGC = Duration.ofHours(8),
            requireTwoGCCycles = false,
            minRewriteToOrphanDeletionDelay = Duration.ofHours(2),
            orphanedPackMinAge = Duration.ofHours(12),
            rewriteMinAge = Duration.ofHours(1),
            disableEventualConsistencySafety = true,
        )

        val serialized = json.encodeToString(params)
        val deserialized = json.decodeFromString<SafetyParameters>(serialized)

        assertThat(deserialized).isEqualTo(params)
    }

    @Test
    fun `custom constructor values`() {
        val params = SafetyParameters(
            minContentAgeSubjectToGC = Duration.ofMinutes(30),
        )

        assertThat(params.minContentAgeSubjectToGC).isEqualTo(Duration.ofMinutes(30))
        // Other values should be defaults
        assertThat(params.marginBetweenSnapshotGC).isEqualTo(Duration.ofHours(4))
    }
}
