package org.kopiaKt.snapshot.policy

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.core.repository.DirectRepositoryImpl
import org.kopiaKt.core.testutil.TestRepositoryFactory
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * Integration tests for PolicyManager using real in-memory repositories.
 *
 * Tests cover CRUD operations, hierarchical policy resolution,
 * scheduling resolution, and edge cases.
 */
@DisplayName("PolicyManager")
class PolicyManagerTest {

    private var repo: DirectRepositoryImpl? = null

    @AfterEach
    fun tearDown() {
        repo?.close()
    }

    private suspend fun createRepo(): DirectRepositoryImpl {
        val (r, _) = TestRepositoryFactory.createInMemory()
        repo = r
        return r
    }

    // --- Test sources ---
    private val globalSource = SourceInfo(host = "", userName = "", path = "")
    private val hostSource = SourceInfo(host = "myhost", userName = "", path = "")
    private val userSource = SourceInfo(host = "myhost", userName = "myuser", path = "")
    private val pathSource = SourceInfo(host = "myhost", userName = "myuser", path = "/data/backups")
    private val pathSource2 = SourceInfo(host = "myhost", userName = "myuser", path = "/data/documents")
    private val pathSource3 = SourceInfo(host = "otherhost", userName = "otheruser", path = "/var/data")

    @Nested
    @DisplayName("Policy CRUD")
    inner class PolicyCrud {

        @Test
        @DisplayName("policy persists across repository close and reopen")
        fun `policy persists across repository close and reopen`() = runTest {
            val (r, storage) = TestRepositoryFactory.createInMemory()
            val policy = Policy(retentionPolicy = RetentionPolicy(keepLatest = 10))
            PolicyManager.setPolicy(r, pathSource, policy)
            r.close()

            // Reopen against the same storage - the app-restart scenario the E2E flow exercises.
            val reopened = DirectRepositoryImpl.open(storage, "test-password")
            repo = reopened
            val loaded = PolicyManager.getPolicy(reopened, pathSource)

            assertThat(loaded).isNotNull()
            assertThat(loaded!!.retentionPolicy?.keepLatest).isEqualTo(10)
        }

        @Test
        @DisplayName("setPolicy stores policy as manifest")
        fun `setPolicy stores policy as manifest`() = runTest {
            val r = createRepo()
            val policy = Policy(
                retentionPolicy = RetentionPolicy(keepLatest = 5),
            )

            PolicyManager.setPolicy(r, pathSource, policy)

            // Verify it can be found via manifest labels
            val labels = Policy.labelsForSource(pathSource)
            val manifests = r.findManifests(labels)
            assertThat(manifests).isNotEmpty()
        }

        @Test
        @DisplayName("getPolicy retrieves stored policy")
        fun `getPolicy retrieves stored policy`() = runTest {
            val r = createRepo()
            val policy = Policy(
                retentionPolicy = RetentionPolicy(keepLatest = 42, keepDaily = 14),
            )

            PolicyManager.setPolicy(r, pathSource, policy)
            val retrieved = PolicyManager.getPolicy(r, pathSource)

            assertThat(retrieved).isNotNull()
            assertThat(retrieved!!.retentionPolicy.keepLatest).isEqualTo(42)
            assertThat(retrieved.retentionPolicy.keepDaily).isEqualTo(14)
        }

        @Test
        @DisplayName("deletePolicy removes manifest")
        fun `deletePolicy removes manifest`() = runTest {
            val r = createRepo()
            val policy = Policy(
                retentionPolicy = RetentionPolicy(keepLatest = 5),
            )

            PolicyManager.setPolicy(r, pathSource, policy)
            assertThat(PolicyManager.getPolicy(r, pathSource)).isNotNull()

            PolicyManager.deletePolicy(r, pathSource)
            assertThat(PolicyManager.getPolicy(r, pathSource)).isNull()
        }

        @Test
        @DisplayName("listPolicies returns all policies")
        fun `listPolicies returns all policies`() = runTest {
            val r = createRepo()

            PolicyManager.setPolicy(
                r,
                pathSource,
                Policy(
                    retentionPolicy = RetentionPolicy(keepLatest = 1),
                ),
            )
            PolicyManager.setPolicy(
                r,
                pathSource2,
                Policy(
                    retentionPolicy = RetentionPolicy(keepLatest = 2),
                ),
            )
            PolicyManager.setPolicy(
                r,
                pathSource3,
                Policy(
                    retentionPolicy = RetentionPolicy(keepLatest = 3),
                ),
            )

            val policies = PolicyManager.listPolicies(r)
            assertThat(policies).hasSize(3)
        }

        @Test
        @DisplayName("setPolicy overwrites existing")
        fun `setPolicy overwrites existing`() = runTest {
            val r = createRepo()

            PolicyManager.setPolicy(
                r,
                pathSource,
                Policy(
                    retentionPolicy = RetentionPolicy(keepLatest = 5),
                ),
            )
            PolicyManager.setPolicy(
                r,
                pathSource,
                Policy(
                    retentionPolicy = RetentionPolicy(keepLatest = 99),
                ),
            )

            val retrieved = PolicyManager.getPolicy(r, pathSource)
            assertThat(retrieved).isNotNull()
            assertThat(retrieved!!.retentionPolicy.keepLatest).isEqualTo(99)
        }
    }

    @Nested
    @DisplayName("Policy Hierarchy")
    inner class PolicyHierarchy {

        @Test
        @DisplayName("effective policy resolves global default")
        fun `effective policy resolves global default`() = runTest {
            val r = createRepo()

            // Set only a global policy
            PolicyManager.setPolicy(
                r,
                globalSource,
                Policy(
                    retentionPolicy = RetentionPolicy(keepLatest = 20),
                ),
            )

            // Get effective for a specific path source with no source-specific policy
            val effective = PolicyManager.getEffectivePolicy(r, pathSource)

            assertThat(effective.retentionPolicy.keepLatest).isEqualTo(20)
        }

        @Test
        @DisplayName("source policy overrides global default")
        fun `source policy overrides global default`() = runTest {
            val r = createRepo()

            PolicyManager.setPolicy(
                r,
                globalSource,
                Policy(
                    retentionPolicy = RetentionPolicy(keepLatest = 20),
                ),
            )
            PolicyManager.setPolicy(
                r,
                pathSource,
                Policy(
                    retentionPolicy = RetentionPolicy(keepLatest = 5),
                ),
            )

            val effective = PolicyManager.getEffectivePolicy(r, pathSource)

            assertThat(effective.retentionPolicy.keepLatest).isEqualTo(5)
        }

        @Test
        @DisplayName("host-level policy applies to all sources on host")
        fun `host-level policy applies to all sources on host`() = runTest {
            val r = createRepo()

            PolicyManager.setPolicy(
                r,
                hostSource,
                Policy(
                    retentionPolicy = RetentionPolicy(keepDaily = 30),
                ),
            )

            // pathSource is on "myhost" so should inherit
            val effective = PolicyManager.getEffectivePolicy(r, pathSource)

            assertThat(effective.retentionPolicy.keepDaily).isEqualTo(30)
        }

        @Test
        @DisplayName("most specific policy wins")
        fun `most specific policy wins`() = runTest {
            val r = createRepo()

            PolicyManager.setPolicy(
                r,
                globalSource,
                Policy(
                    retentionPolicy = RetentionPolicy(keepLatest = 100),
                ),
            )
            PolicyManager.setPolicy(
                r,
                hostSource,
                Policy(
                    retentionPolicy = RetentionPolicy(keepLatest = 50),
                ),
            )
            PolicyManager.setPolicy(
                r,
                pathSource,
                Policy(
                    retentionPolicy = RetentionPolicy(keepLatest = 10),
                ),
            )

            val effective = PolicyManager.getEffectivePolicy(r, pathSource)

            // Source-level (most specific) wins
            assertThat(effective.retentionPolicy.keepLatest).isEqualTo(10)
        }

        @Test
        @DisplayName("merge combines non-overlapping fields")
        fun `merge combines non-overlapping fields`() = runTest {
            val r = createRepo()

            // Global has retention keepWeekly
            PolicyManager.setPolicy(
                r,
                globalSource,
                Policy(
                    retentionPolicy = RetentionPolicy(keepWeekly = 8),
                ),
            )
            // Source has compression
            PolicyManager.setPolicy(
                r,
                pathSource,
                Policy(
                    compressionPolicy = CompressionPolicy(compressorName = "zstd"),
                ),
            )

            val effective = PolicyManager.getEffectivePolicy(r, pathSource)

            // Compression from source-level
            assertThat(effective.compressionPolicy.compressorName).isEqualTo("zstd")
            // Retention from global
            assertThat(effective.retentionPolicy.keepWeekly).isEqualTo(8)
        }
    }

    @Nested
    @DisplayName("Scheduling Resolution")
    inner class SchedulingResolution {

        @Test
        @DisplayName("resolvePolicy returns effective policy with scheduling")
        fun `resolvePolicy returns effective policy with scheduling`() = runTest {
            val r = createRepo()

            PolicyManager.setPolicy(
                r,
                pathSource,
                Policy(
                    schedulingPolicy = SchedulingPolicy(
                        timesOfDay = listOf(TimeOfDay(2, 0), TimeOfDay(14, 30)),
                    ),
                ),
            )

            val effective = PolicyManager.getEffectivePolicy(r, pathSource)

            assertThat(effective.schedulingPolicy.timesOfDay).hasSize(2)
            assertThat(effective.schedulingPolicy.timesOfDay[0]).isEqualTo(TimeOfDay(2, 0))
            assertThat(effective.schedulingPolicy.timesOfDay[1]).isEqualTo(TimeOfDay(14, 30))
        }

        @Test
        @DisplayName("resolvePolicy with no scheduling returns empty scheduling")
        fun `resolvePolicy with no scheduling returns empty scheduling`() = runTest {
            val r = createRepo()

            // Set a policy with only retention, no scheduling
            PolicyManager.setPolicy(
                r,
                pathSource,
                Policy(
                    retentionPolicy = RetentionPolicy(keepLatest = 5),
                ),
            )

            val effective = PolicyManager.getEffectivePolicy(r, pathSource)

            // Scheduling defaults from merged defaults
            assertThat(effective.schedulingPolicy.manual).isFalse()
            assertThat(effective.schedulingPolicy.timesOfDay).isEmpty()
        }

        @Test
        @DisplayName("resolvePolicy with intervalSeconds present")
        fun `resolvePolicy with intervalSeconds present`() = runTest {
            val r = createRepo()

            PolicyManager.setPolicy(
                r,
                pathSource,
                Policy(
                    schedulingPolicy = SchedulingPolicy(intervalSeconds = 3600),
                ),
            )

            val effective = PolicyManager.getEffectivePolicy(r, pathSource)

            assertThat(effective.schedulingPolicy.intervalSeconds).isEqualTo(3600L)
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCases {

        @Test
        @DisplayName("getPolicy for nonexistent source returns null")
        fun `getPolicy for nonexistent source returns null`() = runTest {
            val r = createRepo()

            val result = PolicyManager.getPolicy(r, pathSource)

            assertThat(result).isNull()
        }

        @Test
        @DisplayName("deletePolicy for nonexistent source is no-op")
        fun `deletePolicy for nonexistent source is no-op`() = runTest {
            val r = createRepo()

            // Should not throw
            PolicyManager.deletePolicy(r, pathSource)

            // Verify repo is still functional
            val policies = PolicyManager.listPolicies(r)
            assertThat(policies).isEmpty()
        }

        @Test
        @DisplayName("listPolicies empty on fresh repository")
        fun `listPolicies empty on fresh repository`() = runTest {
            val r = createRepo()

            val policies = PolicyManager.listPolicies(r)

            assertThat(policies).isEmpty()
        }
    }
}
