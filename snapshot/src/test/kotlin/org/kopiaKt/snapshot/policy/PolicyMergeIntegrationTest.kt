package org.kopiaKt.snapshot.policy

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * Integration tests for policy merging beyond the basic scenarios
 * covered in PolicyDataModelTest.PolicyMergeTest.
 *
 * Tests complex multi-level merge, noParent boundaries, list field merging,
 * validation passthrough, empty input, and duplicate handling.
 */
@DisplayName("PolicyMergeIntegrationTest")
class PolicyMergeIntegrationTest {

    private val globalSi = SourceInfo("", "", "")
    private val userSi = SourceInfo("host", "user", "")
    private val pathSi = SourceInfo("host", "user", "/path")

    @Nested
    @DisplayName("Retention merge hierarchy")
    inner class RetentionMergeHierarchy {

        @Test
        @DisplayName("should apply retention policy from most specific level")
        fun `should apply retention policy from most specific level`() {
            val globalPolicy = Policy(
                labels = Policy.labelsForSource(globalSi),
                retentionPolicy = RetentionPolicy(keepDaily = 30)
            )
            val userPolicy = Policy(
                labels = Policy.labelsForSource(userSi),
                retentionPolicy = RetentionPolicy(keepDaily = 7)
            )
            val pathPolicy = Policy(
                labels = Policy.labelsForSource(pathSi),
                retentionPolicy = RetentionPolicy(keepDaily = 14)
            )

            val (merged, _) = mergePolicies(
                listOf(pathPolicy, userPolicy, globalPolicy),
                pathSi
            )

            assertThat(merged.retentionPolicy.keepDaily).isEqualTo(14)
        }

        @Test
        @DisplayName("should fall through to parent when child has no value set")
        fun `should fall through to parent when child has no value set`() {
            val userPolicy = Policy(
                labels = Policy.labelsForSource(userSi),
                retentionPolicy = RetentionPolicy(keepWeekly = 4)
            )
            val pathPolicy = Policy(
                labels = Policy.labelsForSource(pathSi),
                retentionPolicy = RetentionPolicy(keepDaily = 14)
            )

            val (merged, _) = mergePolicies(
                listOf(pathPolicy, userPolicy),
                pathSi
            )

            // keepDaily set at path level
            assertThat(merged.retentionPolicy.keepDaily).isEqualTo(14)
            // keepWeekly inherited from user level
            assertThat(merged.retentionPolicy.keepWeekly).isEqualTo(4)
        }
    }

    @Nested
    @DisplayName("NoParent boundary")
    inner class NoParentBoundary {

        @Test
        @DisplayName("should stop at noParent boundary")
        fun `should stop at noParent boundary`() {
            val globalPolicy = Policy(
                labels = Policy.labelsForSource(globalSi),
                retentionPolicy = RetentionPolicy(keepMonthly = 12)
            )
            val pathPolicy = Policy(
                labels = Policy.labelsForSource(pathSi),
                retentionPolicy = RetentionPolicy(keepDaily = 14),
                noParent = true
            )

            val (merged, _) = mergePolicies(
                listOf(pathPolicy, globalPolicy),
                pathSi
            )

            // keepDaily from path level is present
            assertThat(merged.retentionPolicy.keepDaily).isEqualTo(14)
            // keepMonthly from global NOT merged because noParent stopped traversal
            assertNull(merged.retentionPolicy.keepMonthly)
            // Defaults are NOT applied either (noParent returns early before default merge)
            assertNull(merged.retentionPolicy.keepLatest)
            assertNull(merged.retentionPolicy.keepWeekly)
            assertNull(merged.retentionPolicy.keepAnnual)
        }
    }

    @Nested
    @DisplayName("File ignore rules merging")
    inner class FileIgnoreRulesMerging {

        @Test
        @DisplayName("should merge file ignore rules from all levels")
        fun `should merge file ignore rules from all levels`() {
            val globalPolicy = Policy(
                labels = Policy.labelsForSource(globalSi),
                filesPolicy = FilesPolicy(ignoreRules = listOf("*.tmp"))
            )
            val userPolicy = Policy(
                labels = Policy.labelsForSource(userSi),
                filesPolicy = FilesPolicy(ignoreRules = listOf("node_modules"))
            )
            val pathPolicy = Policy(
                labels = Policy.labelsForSource(pathSi),
                filesPolicy = FilesPolicy(ignoreRules = listOf("build"))
            )

            val (merged, _) = mergePolicies(
                listOf(pathPolicy, userPolicy, globalPolicy),
                pathSi
            )

            assertThat(merged.filesPolicy.ignoreRules).contains("build")
            assertThat(merged.filesPolicy.ignoreRules).contains("node_modules")
            assertThat(merged.filesPolicy.ignoreRules).contains("*.tmp")
        }
    }

    @Nested
    @DisplayName("Validation passthrough")
    inner class ValidationPassthrough {

        @Test
        @DisplayName("should validate merged policy")
        fun `should validate merged policy`() {
            // Create a policy with scheduling that is invalid:
            // manual=true combined with intervalSeconds is not allowed
            val pathPolicy = Policy(
                labels = Policy.labelsForSource(pathSi),
                schedulingPolicy = SchedulingPolicy(manual = true, intervalSeconds = 3600)
            )

            val (merged, _) = mergePolicies(
                listOf(pathPolicy),
                pathSi
            )

            // The merged policy carries through the invalid combination
            val error = validatePolicy(pathSi, merged)
            assertNotNull(error)
            assertThat(error).contains("scheduling")
        }
    }

    @Nested
    @DisplayName("Empty policy list")
    inner class EmptyPolicyList {

        @Test
        @DisplayName("should handle empty policy list")
        fun `should handle empty policy list`() {
            val (merged, _) = mergePolicies(emptyList(), pathSi)

            // Defaults are applied
            assertEquals(RetentionDefaults.KEEP_LATEST, merged.retentionPolicy.keepLatest)
            assertEquals(RetentionDefaults.KEEP_DAILY, merged.retentionPolicy.keepDaily)
            assertEquals("none", merged.compressionPolicy.compressorName)
            assertThat(merged.filesPolicy.dotIgnoreFiles).contains(".kopiaignore")
        }
    }

    @Nested
    @DisplayName("Duplicate ignore rules")
    inner class DuplicateIgnoreRules {

        @Test
        @DisplayName("should handle duplicate ignore rules across levels")
        fun `should handle duplicate ignore rules across levels`() {
            val globalPolicy = Policy(
                labels = Policy.labelsForSource(globalSi),
                filesPolicy = FilesPolicy(ignoreRules = listOf("*.tmp", "build"))
            )
            val pathPolicy = Policy(
                labels = Policy.labelsForSource(pathSi),
                filesPolicy = FilesPolicy(ignoreRules = listOf("build", "*.log"))
            )

            val (merged, _) = mergePolicies(
                listOf(pathPolicy, globalPolicy),
                pathSi
            )

            // The merge uses .distinct() so duplicates are removed
            assertThat(merged.filesPolicy.ignoreRules).containsExactly(
                "build", "*.log", "*.tmp"
            )
            // "build" appears only once
            assertThat(merged.filesPolicy.ignoreRules.count { it == "build" }).isEqualTo(1)
        }
    }
}
