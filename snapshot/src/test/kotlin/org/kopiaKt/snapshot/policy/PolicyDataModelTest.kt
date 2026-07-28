package org.kopiaKt.snapshot.policy

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.snapshot.model.SourceInfo

/**
 * Tests for policy data models, verifying Go-compatible JSON serialization
 * and policy merging behavior.
 */
class PolicyDataModelTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }

    @Nested
    inner class PolicyTest {
        @Test
        fun `should deserialize Policy from Go JSON`() {
            val goJson = """{
                "retention": {
                    "keepLatest": 10,
                    "keepDaily": 7,
                    "keepWeekly": 4
                },
                "files": {
                    "ignore": ["*.tmp", "*.log"],
                    "maxFileSize": 1073741824
                },
                "compression": {
                    "compressorName": "zstd"
                },
                "noParent": false
            }
            """.trimIndent()

            val policy = json.decodeFromString<Policy>(goJson)

            assertEquals(10, policy.retentionPolicy.keepLatest)
            assertEquals(7, policy.retentionPolicy.keepDaily)
            assertEquals(4, policy.retentionPolicy.keepWeekly)
            assertEquals(listOf("*.tmp", "*.log"), policy.filesPolicy.ignoreRules)
            assertEquals(1073741824L, policy.filesPolicy.maxFileSize)
            assertEquals("zstd", policy.compressionPolicy.compressorName)
            assertFalse(policy.noParent)
        }

        @Test
        fun `should serialize Policy to Go-compatible JSON`() {
            val policy = Policy(
                retentionPolicy = RetentionPolicy(keepLatest = 10, keepDaily = 7),
                filesPolicy = FilesPolicy(ignoreRules = listOf("*.tmp")),
                compressionPolicy = CompressionPolicy(compressorName = "zstd"),
            )

            val serialized = json.encodeToString(policy)

            assertTrue(serialized.contains(""""retention":"""))
            assertTrue(serialized.contains(""""keepLatest":10"""))
            assertTrue(serialized.contains(""""files":"""))
            assertTrue(serialized.contains(""""compression":"""))
        }

        @Test
        fun `should create labels for global source`() {
            val labels = Policy.labelsForSource(SourceInfo("", "", ""))

            assertEquals("policy", labels["type"])
            assertEquals("global", labels["policyType"])
        }

        @Test
        fun `should create labels for host source`() {
            val labels = Policy.labelsForSource(SourceInfo(host = "myhost", userName = "", path = ""))

            assertEquals("policy", labels["type"])
            assertEquals("host", labels["policyType"])
            assertEquals("myhost", labels["hostname"])
        }

        @Test
        fun `should create labels for user source`() {
            val labels = Policy.labelsForSource(SourceInfo(host = "myhost", userName = "myuser", path = ""))

            assertEquals("policy", labels["type"])
            assertEquals("user", labels["policyType"])
            assertEquals("myhost", labels["hostname"])
            assertEquals("myuser", labels["username"])
        }

        @Test
        fun `should create labels for path source`() {
            val labels = Policy.labelsForSource(
                SourceInfo(host = "myhost", userName = "myuser", path = "/home/myuser"),
            )

            assertEquals("policy", labels["type"])
            assertEquals("path", labels["policyType"])
            assertEquals("myhost", labels["hostname"])
            assertEquals("myuser", labels["username"])
            assertEquals("/home/myuser", labels["path"])
        }

        @Test
        fun `should get target from labels`() {
            val policy = Policy(
                labels = mapOf(
                    "hostname" to "myhost",
                    "username" to "myuser",
                    "path" to "/mypath",
                ),
            )

            val target = policy.target()

            assertEquals("myhost", target.host)
            assertEquals("myuser", target.userName)
            assertEquals("/mypath", target.path)
        }
    }

    @Nested
    inner class RetentionPolicyTest {
        @Test
        fun `should deserialize RetentionPolicy from Go JSON`() {
            val goJson = """{
                "keepLatest": 10,
                "keepHourly": 48,
                "keepDaily": 7,
                "keepWeekly": 4,
                "keepMonthly": 24,
                "keepAnnual": 3,
                "ignoreIdenticalSnapshots": true
            }
            """.trimIndent()

            val policy = json.decodeFromString<RetentionPolicy>(goJson)

            assertEquals(10, policy.keepLatest)
            assertEquals(48, policy.keepHourly)
            assertEquals(7, policy.keepDaily)
            assertEquals(4, policy.keepWeekly)
            assertEquals(24, policy.keepMonthly)
            assertEquals(3, policy.keepAnnual)
            assertEquals(true, policy.ignoreIdenticalSnapshots)
        }

        @Test
        fun `should compute effective keep latest`() {
            val policy = RetentionPolicy(keepLatest = 10, keepDaily = 7)
            assertEquals(10, policy.effectiveKeepLatest())
        }

        @Test
        fun `should return max value when all retention values are zero`() {
            val policy = RetentionPolicy()
            assertEquals(Int.MAX_VALUE, policy.effectiveKeepLatest())
        }

        @Test
        fun `should merge retention policies`() {
            val target = RetentionPolicy(keepLatest = 5)
            val source = RetentionPolicy(keepLatest = 10, keepDaily = 7)
            val def = RetentionPolicyDefinition()
            val si = SourceInfo("host", "user", "/path")

            val (merged, _) = target.merge(source, def, si)

            assertEquals(5, merged.keepLatest) // Target value takes precedence
            assertEquals(7, merged.keepDaily) // Merged from source
        }

        @Test
        fun `should have correct defaults`() {
            val defaults = RetentionPolicy.Default

            assertEquals(RetentionDefaults.KEEP_LATEST, defaults.keepLatest)
            assertEquals(RetentionDefaults.KEEP_HOURLY, defaults.keepHourly)
            assertEquals(RetentionDefaults.KEEP_DAILY, defaults.keepDaily)
            assertEquals(RetentionDefaults.KEEP_WEEKLY, defaults.keepWeekly)
            assertEquals(RetentionDefaults.KEEP_MONTHLY, defaults.keepMonthly)
            assertEquals(RetentionDefaults.KEEP_ANNUAL, defaults.keepAnnual)
        }
    }

    @Nested
    inner class RetentionTagsTest {
        @Test
        fun `should sort retention tags`() {
            val tags = listOf("daily-3", "latest-1", "annual-1", "hourly-2")
            val sorted = sortRetentionTags(tags)

            assertEquals(listOf("latest-1", "hourly-2", "daily-3", "annual-1"), sorted)
        }

        @Test
        fun `should compact consecutive retention reasons`() {
            val reasons = listOf("daily-1", "daily-2", "daily-3", "daily-5", "daily-6")
            val compacted = compactRetentionReasons(reasons)

            assertEquals(listOf("daily-1..3", "daily-5..6"), compacted)
        }

        @Test
        fun `should compact pins`() {
            val pins = listOf("keep", "important", "keep", "archive")
            val compacted = compactPins(pins)

            assertEquals(listOf("archive", "important", "keep"), compacted)
        }
    }

    @Nested
    inner class FilesPolicyTest {
        @Test
        fun `should deserialize FilesPolicy from Go JSON`() {
            val goJson = """{
                "ignore": ["*.tmp", "*.log"],
                "noParentIgnore": false,
                "ignoreDotFiles": [".kopiaignore", ".gitignore"],
                "ignoreCacheDirs": true,
                "maxFileSize": 1073741824,
                "oneFileSystem": true
            }
            """.trimIndent()

            val policy = json.decodeFromString<FilesPolicy>(goJson)

            assertEquals(listOf("*.tmp", "*.log"), policy.ignoreRules)
            assertFalse(policy.noParentIgnoreRules)
            assertEquals(listOf(".kopiaignore", ".gitignore"), policy.dotIgnoreFiles)
            assertEquals(true, policy.ignoreCacheDirectories)
            assertEquals(1073741824L, policy.maxFileSize)
            assertEquals(true, policy.oneFileSystem)
        }

        @Test
        fun `should merge files policies - append ignore rules`() {
            val target = FilesPolicy(ignoreRules = listOf("*.tmp"))
            val source = FilesPolicy(ignoreRules = listOf("*.log"))
            val def = FilesPolicyDefinition()
            val si = SourceInfo("host", "user", "/path")

            val (merged, _) = target.merge(source, def, si)

            assertTrue(merged.ignoreRules.contains("*.tmp"))
            assertTrue(merged.ignoreRules.contains("*.log"))
        }

        @Test
        fun `should not merge when noParent is set`() {
            val target = FilesPolicy(ignoreRules = listOf("*.tmp"), noParentIgnoreRules = true)
            val source = FilesPolicy(ignoreRules = listOf("*.log"))
            val def = FilesPolicyDefinition()
            val si = SourceInfo("host", "user", "/path")

            val (merged, _) = target.merge(source, def, si)

            assertEquals(listOf("*.tmp"), merged.ignoreRules)
            assertFalse(merged.ignoreRules.contains("*.log"))
        }

        @Test
        fun `should have correct defaults`() {
            val defaults = FilesPolicy.Default

            assertEquals(listOf(".kopiaignore"), defaults.dotIgnoreFiles)
        }
    }

    @Nested
    inner class CompressionPolicyTest {
        @Test
        fun `should deserialize CompressionPolicy from Go JSON`() {
            val goJson = """{
                "compressorName": "zstd",
                "onlyCompress": [".txt", ".json"],
                "neverCompress": [".jpg", ".mp4"],
                "minSize": 1024,
                "maxSize": 1073741824
            }
            """.trimIndent()

            val policy = json.decodeFromString<CompressionPolicy>(goJson)

            assertEquals("zstd", policy.compressorName)
            assertEquals(listOf(".txt", ".json"), policy.onlyCompress)
            assertEquals(listOf(".jpg", ".mp4"), policy.neverCompress)
            assertEquals(1024L, policy.minSize)
            assertEquals(1073741824L, policy.maxSize)
        }

        @Test
        fun `should return compressor name for eligible file`() {
            val policy = CompressionPolicy(compressorName = "zstd", minSize = 100, maxSize = 10000)

            assertEquals("zstd", policy.compressorForFile("document.txt", 1000))
        }

        @Test
        fun `should skip compression for small files`() {
            val policy = CompressionPolicy(compressorName = "zstd", minSize = 1000)

            assertEquals("", policy.compressorForFile("small.txt", 100))
        }

        @Test
        fun `should skip compression for large files`() {
            val policy = CompressionPolicy(compressorName = "zstd", maxSize = 1000)

            assertEquals("", policy.compressorForFile("large.txt", 10000))
        }

        @Test
        fun `should skip compression when compressor is none`() {
            val policy = CompressionPolicy(compressorName = "none")

            assertEquals("", policy.compressorForFile("document.txt", 1000))
        }

        @Test
        fun `should skip compression for never compress extensions`() {
            val policy = CompressionPolicy(
                compressorName = "zstd",
                neverCompress = listOf(".jpg", ".mp4"),
            )

            assertEquals("", policy.compressorForFile("photo.jpg", 1000))
            assertEquals("zstd", policy.compressorForFile("document.txt", 1000))
        }

        @Test
        fun `should only compress specified extensions`() {
            val policy = CompressionPolicy(
                compressorName = "zstd",
                onlyCompress = listOf(".txt", ".json"),
            )

            assertEquals("zstd", policy.compressorForFile("document.txt", 1000))
            assertEquals("", policy.compressorForFile("photo.jpg", 1000))
        }
    }

    @Nested
    inner class MetadataCompressionPolicyTest {
        @Test
        fun `should return compressor name`() {
            val policy = MetadataCompressionPolicy(compressorName = "zstd-fastest")
            assertEquals("zstd-fastest", policy.metadataCompressor())
        }

        @Test
        fun `should return empty for none compressor`() {
            val policy = MetadataCompressionPolicy(compressorName = "none")
            assertEquals("", policy.metadataCompressor())
        }
    }

    @Nested
    inner class SplitterPolicyTest {
        @Test
        fun `should deserialize SplitterPolicy from Go JSON`() {
            val goJson = """{"algorithm": "DYNAMIC-4M-BUZHASH"}"""

            val policy = json.decodeFromString<SplitterPolicy>(goJson)

            assertEquals("DYNAMIC-4M-BUZHASH", policy.algorithm)
        }

        @Test
        fun `should merge splitter policies`() {
            val target = SplitterPolicy()
            val source = SplitterPolicy(algorithm = "DYNAMIC-8M-BUZHASH")
            val def = SplitterPolicyDefinition()
            val si = SourceInfo("host", "user", "/path")

            val (merged, _) = target.merge(source, def, si)

            assertEquals("DYNAMIC-8M-BUZHASH", merged.algorithm)
        }
    }

    @Nested
    inner class SchedulingPolicyTest {
        @Test
        fun `should deserialize SchedulingPolicy from Go JSON`() {
            val goJson = """{
                "intervalSeconds": 3600,
                "timeOfDay": [{"hour": 2, "min": 0}, {"hour": 14, "min": 30}],
                "manual": false,
                "cron": ["0 2 * * *"],
                "runMissed": true
            }
            """.trimIndent()

            val policy = json.decodeFromString<SchedulingPolicy>(goJson)

            assertEquals(3600L, policy.intervalSeconds)
            assertEquals(2, policy.timesOfDay.size)
            assertEquals(2, policy.timesOfDay[0].hour)
            assertEquals(0, policy.timesOfDay[0].minute)
            assertFalse(policy.manual)
            assertEquals(listOf("0 2 * * *"), policy.cron)
            assertEquals(true, policy.runMissed)
        }

        @Test
        fun `should compute interval as Duration`() {
            val policy = SchedulingPolicy(intervalSeconds = 3600)
            assertEquals(3600L, policy.interval().seconds)
        }

        @Test
        fun `should validate manual policy`() {
            val validManual = SchedulingPolicy(manual = true)
            assertNull(validateSchedulingPolicy(validManual))

            val invalidManual = SchedulingPolicy(manual = true, intervalSeconds = 3600)
            assertNotNull(validateSchedulingPolicy(invalidManual))
        }
    }

    @Nested
    inner class TimeOfDayTest {
        @Test
        fun `should parse time of day string`() {
            val tod = TimeOfDay.parse("14:30")

            assertEquals(14, tod.hour)
            assertEquals(30, tod.minute)
        }

        @Test
        fun `should format time of day to string`() {
            val tod = TimeOfDay(14, 5)
            assertEquals("14:05", tod.toString())
        }

        @Test
        fun `should sort and dedupe times of day`() {
            val times = listOf(
                TimeOfDay(14, 30),
                TimeOfDay(2, 0),
                TimeOfDay(14, 30),
                TimeOfDay(8, 15),
            )

            val sorted = sortAndDedupeTimesOfDay(times)

            assertEquals(3, sorted.size)
            assertEquals(TimeOfDay(2, 0), sorted[0])
            assertEquals(TimeOfDay(8, 15), sorted[1])
            assertEquals(TimeOfDay(14, 30), sorted[2])
        }
    }

    @Nested
    inner class ErrorHandlingPolicyTest {
        @Test
        fun `should deserialize ErrorHandlingPolicy from Go JSON`() {
            val goJson = """{
                "ignoreFileErrors": true,
                "ignoreDirectoryErrors": false,
                "ignoreUnknownTypes": true
            }
            """.trimIndent()

            val policy = json.decodeFromString<ErrorHandlingPolicy>(goJson)

            assertEquals(true, policy.ignoreFileErrors)
            assertEquals(false, policy.ignoreDirectoryErrors)
            assertEquals(true, policy.ignoreUnknownTypes)
        }

        @Test
        fun `should have correct defaults`() {
            val defaults = ErrorHandlingPolicy.Default

            assertEquals(false, defaults.ignoreFileErrors)
            assertEquals(false, defaults.ignoreDirectoryErrors)
            assertEquals(true, defaults.ignoreUnknownTypes)
        }
    }

    @Nested
    inner class ActionsPolicyTest {
        @Test
        fun `should deserialize ActionsPolicy from Go JSON`() {
            val goJson = """{
                "beforeSnapshotRoot": {
                    "path": "/usr/bin/notify",
                    "args": ["start"],
                    "timeout": 60,
                    "mode": "essential"
                },
                "afterSnapshotRoot": {
                    "script": "echo done",
                    "mode": "optional"
                }
            }
            """.trimIndent()

            val policy = json.decodeFromString<ActionsPolicy>(goJson)

            assertNotNull(policy.beforeSnapshotRoot)
            assertEquals("/usr/bin/notify", policy.beforeSnapshotRoot!!.command)
            assertEquals(listOf("start"), policy.beforeSnapshotRoot!!.arguments)
            assertEquals(60, policy.beforeSnapshotRoot!!.timeoutSeconds)
            assertEquals("essential", policy.beforeSnapshotRoot!!.mode)

            assertNotNull(policy.afterSnapshotRoot)
            assertEquals("echo done", policy.afterSnapshotRoot!!.script)
        }

        @Test
        fun `should merge actions policies - only inheritable`() {
            val target = ActionsPolicy(
                beforeFolder = ActionCommand(command = "/bin/before"),
                beforeSnapshotRoot = ActionCommand(command = "/bin/root-before"),
            )
            val source = ActionsPolicy(
                beforeFolder = ActionCommand(command = "/bin/source-before"),
                afterSnapshotRoot = ActionCommand(command = "/bin/root-after"),
            )
            val def = ActionsPolicyDefinition()
            val si = SourceInfo("host", "user", "/path")

            val (merged, _) = target.merge(source, def, si)

            // beforeFolder is not inherited
            assertEquals("/bin/before", merged.beforeFolder?.command)
            // beforeSnapshotRoot from target takes precedence
            assertEquals("/bin/root-before", merged.beforeSnapshotRoot?.command)
            // afterSnapshotRoot merged from source
            assertEquals("/bin/root-after", merged.afterSnapshotRoot?.command)
        }
    }

    @Nested
    inner class LoggingPolicyTest {
        @Test
        fun `should deserialize LoggingPolicy from Go JSON`() {
            val goJson = """{
                "directories": {
                    "snapshotted": 5,
                    "ignored": 5
                },
                "entries": {
                    "snapshotted": 0,
                    "ignored": 5,
                    "cacheHit": 0,
                    "cacheMiss": 0
                }
            }
            """.trimIndent()

            val policy = json.decodeFromString<LoggingPolicy>(goJson)

            assertEquals(LogDetailLevels.NORMAL, policy.directories.snapshotted)
            assertEquals(LogDetailLevels.NORMAL, policy.directories.ignored)
            assertEquals(LogDetailLevels.NONE, policy.entries.snapshotted)
        }

        @Test
        fun `should have correct defaults`() {
            val defaults = LoggingPolicy.Default

            assertEquals(LogDetailLevels.NORMAL, defaults.directories.snapshotted)
            assertEquals(LogDetailLevels.NORMAL, defaults.directories.ignored)
            assertEquals(LogDetailLevels.NONE, defaults.entries.snapshotted)
            assertEquals(LogDetailLevels.NORMAL, defaults.entries.ignored)
        }
    }

    @Nested
    inner class UploadPolicyTest {
        @Test
        fun `should deserialize UploadPolicy from Go JSON`() {
            val goJson = """{
                "maxParallelSnapshots": 1,
                "maxParallelFileReads": 4,
                "parallelUploadAboveSize": 2147483648
            }
            """.trimIndent()

            val policy = json.decodeFromString<UploadPolicy>(goJson)

            assertEquals(1, policy.maxParallelSnapshots)
            assertEquals(4, policy.maxParallelFileReads)
            assertEquals(2147483648L, policy.parallelUploadAboveSize)
        }

        @Test
        fun `should validate upload policy for path`() {
            val si = SourceInfo("host", "user", "/path")
            val policy = UploadPolicy(maxParallelSnapshots = 2)

            val error = validateUploadPolicy(si, policy)

            assertNotNull(error)
            assertTrue(error!!.contains("max parallel snapshots"))
        }

        @Test
        fun `should allow maxParallelSnapshots for global`() {
            val si = SourceInfo("", "", "")
            val policy = UploadPolicy(maxParallelSnapshots = 2)

            val error = validateUploadPolicy(si, policy)

            assertNull(error)
        }
    }

    @Nested
    inner class OSSnapshotPolicyTest {
        @Test
        fun `should deserialize OSSnapshotPolicy from Go JSON`() {
            // Verbatim from `kopia policy show --global --json` on a repository Go created. This
            // test used to assert a quoted "when-available", which no Go build ever writes:
            // OSSnapshotMode is a bare byte with no custom marshalling (os_snapshot_policy.go).
            val goJson = """{"volumeShadowCopy":{"enable":0}}"""

            val policy = json.decodeFromString<OSSnapshotPolicy>(goJson)

            assertEquals(OSSnapshotMode.NEVER, policy.volumeShadowCopy.enable)
        }

        @Test
        fun `should serialize OSSnapshotMode correctly`() {
            val policy = OSSnapshotPolicy(
                volumeShadowCopy = VolumeShadowCopyPolicy(enable = OSSnapshotMode.ALWAYS),
            )

            val serialized = json.encodeToString(policy)

            // A number, as Go writes it - the string form made every Go-written policy manifest
            // undecodable, and with it every backup into a desktop-created repository.
            assertTrue(serialized.contains(""""enable":1"""))
        }

        @Test
        fun `should have correct defaults`() {
            val defaults = OSSnapshotPolicy.Default

            assertEquals(OSSnapshotMode.NEVER, defaults.volumeShadowCopy.enable)
        }
    }

    @Nested
    inner class PolicyMergeTest {
        @Test
        fun `should merge policies in order`() {
            val pathPolicy = Policy(
                labels = Policy.labelsForSource(SourceInfo("host", "user", "/path")),
                retentionPolicy = RetentionPolicy(keepDaily = 14),
            )
            val userPolicy = Policy(
                labels = Policy.labelsForSource(SourceInfo("host", "user", "")),
                retentionPolicy = RetentionPolicy(keepDaily = 7, keepWeekly = 8),
            )
            val globalPolicy = Policy(
                labels = Policy.labelsForSource(SourceInfo("", "", "")),
                retentionPolicy = RetentionPolicy(keepDaily = 30, keepWeekly = 4, keepMonthly = 12),
            )

            val (merged, _) = mergePolicies(
                listOf(pathPolicy, userPolicy, globalPolicy),
                SourceInfo("host", "user", "/path"),
            )

            // Path policy value takes precedence
            assertEquals(14, merged.retentionPolicy.keepDaily)
            // User policy fills in missing value
            assertEquals(8, merged.retentionPolicy.keepWeekly)
            // Global policy fills in missing value
            assertEquals(12, merged.retentionPolicy.keepMonthly)
        }

        @Test
        fun `should stop merging when noParent is set`() {
            val pathPolicy = Policy(
                labels = Policy.labelsForSource(SourceInfo("host", "user", "/path")),
                retentionPolicy = RetentionPolicy(keepDaily = 14),
                noParent = true,
            )
            val globalPolicy = Policy(
                labels = Policy.labelsForSource(SourceInfo("", "", "")),
                retentionPolicy = RetentionPolicy(keepWeekly = 4),
            )

            val (merged, _) = mergePolicies(
                listOf(pathPolicy, globalPolicy),
                SourceInfo("host", "user", "/path"),
            )

            assertEquals(14, merged.retentionPolicy.keepDaily)
            // Global policy should NOT be merged
            assertNull(merged.retentionPolicy.keepWeekly)
        }

        @Test
        fun `should merge with defaults when no policies provided`() {
            val (merged, _) = mergePolicies(emptyList(), SourceInfo("host", "user", "/path"))

            // Should have all default values
            assertEquals(RetentionDefaults.KEEP_LATEST, merged.retentionPolicy.keepLatest)
            assertEquals(RetentionDefaults.KEEP_DAILY, merged.retentionPolicy.keepDaily)
            assertEquals("none", merged.compressionPolicy.compressorName)
            assertEquals(listOf(".kopiaignore"), merged.filesPolicy.dotIgnoreFiles)
        }

        @Test
        fun `should copy non-inheritable actions from most specific policy`() {
            val pathPolicy = Policy(
                labels = Policy.labelsForSource(SourceInfo("host", "user", "/path")),
                actionsPolicy = ActionsPolicy(
                    beforeFolder = ActionCommand(command = "/bin/path-before"),
                ),
            )
            val globalPolicy = Policy(
                labels = Policy.labelsForSource(SourceInfo("", "", "")),
                actionsPolicy = ActionsPolicy(
                    beforeFolder = ActionCommand(command = "/bin/global-before"),
                    beforeSnapshotRoot = ActionCommand(command = "/bin/global-root"),
                ),
            )

            val (merged, _) = mergePolicies(
                listOf(pathPolicy, globalPolicy),
                SourceInfo("host", "user", "/path"),
            )

            // Non-inheritable from most specific policy
            assertEquals("/bin/path-before", merged.actionsPolicy.beforeFolder?.command)
            // Inheritable from global
            assertEquals("/bin/global-root", merged.actionsPolicy.beforeSnapshotRoot?.command)
        }
    }

    @Nested
    inner class DefaultPolicyTest {
        @Test
        fun `should have all default values set`() {
            assertNotNull(DefaultPolicy.retentionPolicy.keepLatest)
            assertNotNull(DefaultPolicy.retentionPolicy.keepDaily)
            assertNotNull(DefaultPolicy.errorHandlingPolicy.ignoreFileErrors)
            assertNotNull(DefaultPolicy.loggingPolicy.directories.snapshotted)
            assertNotNull(DefaultPolicy.uploadPolicy.maxParallelSnapshots)
        }
    }

    @Nested
    inner class PolicyValidationTest {
        @Test
        fun `should validate valid policy`() {
            val si = SourceInfo("host", "user", "/path")
            val policy = Policy()

            val error = validatePolicy(si, policy)

            assertNull(error)
        }

        @Test
        fun `should detect invalid scheduling policy`() {
            val si = SourceInfo("host", "user", "/path")
            val policy = Policy(
                schedulingPolicy = SchedulingPolicy(manual = true, intervalSeconds = 3600),
            )

            val error = validatePolicy(si, policy)

            assertNotNull(error)
            assertTrue(error!!.contains("scheduling"))
        }

        @Test
        fun `should detect invalid upload policy`() {
            val si = SourceInfo("host", "user", "/path")
            val policy = Policy(
                uploadPolicy = UploadPolicy(maxParallelSnapshots = 2),
            )

            val error = validatePolicy(si, policy)

            assertNotNull(error)
            assertTrue(error!!.contains("upload"))
        }
    }
}
