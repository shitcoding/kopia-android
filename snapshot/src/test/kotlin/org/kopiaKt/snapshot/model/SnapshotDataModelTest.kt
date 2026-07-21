package org.kopiaKt.snapshot.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for snapshot data models, verifying Go-compatible JSON serialization.
 *
 * These tests follow TDD principles - they define the expected behavior
 * before implementation is complete.
 */
class SnapshotDataModelTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }

    @Nested
    inner class SourceInfoTest {
        @Test
        fun `should deserialize SourceInfo from Go JSON`() {
            val goJson = """{"host":"myhost","userName":"myuser","path":"/home/myuser/documents"}"""

            val sourceInfo = json.decodeFromString<SourceInfo>(goJson)

            assertEquals("myhost", sourceInfo.host)
            assertEquals("myuser", sourceInfo.userName)
            assertEquals("/home/myuser/documents", sourceInfo.path)
        }

        @Test
        fun `should serialize SourceInfo to Go-compatible JSON`() {
            val sourceInfo = SourceInfo(
                host = "myhost",
                userName = "myuser",
                path = "/home/myuser/documents",
            )

            val serialized = json.encodeToString(sourceInfo)

            assertTrue(serialized.contains(""""host":"myhost""""))
            assertTrue(serialized.contains(""""userName":"myuser""""))
            assertTrue(serialized.contains(""""path":"/home/myuser/documents""""))
        }

        @Test
        fun `should format toString as user@host path`() {
            val sourceInfo = SourceInfo(
                host = "myhost",
                userName = "myuser",
                path = "/home/myuser/documents",
            )

            assertEquals("myuser@myhost:/home/myuser/documents", sourceInfo.toString())
        }

        @Test
        fun `should format global source`() {
            val sourceInfo = SourceInfo(host = "", userName = "", path = "")
            assertEquals("(global)", sourceInfo.toString())
        }

        @Test
        fun `should format source without path`() {
            val sourceInfo = SourceInfo(host = "myhost", userName = "myuser", path = "")
            assertEquals("myuser@myhost", sourceInfo.toString())
        }

        @Test
        fun `should parse source string`() {
            val parsed = SourceInfo.parse("myuser@myhost:/home/myuser/documents")

            assertNotNull(parsed)
            assertEquals("myhost", parsed!!.host)
            assertEquals("myuser", parsed.userName)
            assertEquals("/home/myuser/documents", parsed.path)
        }

        @Test
        fun `should parse source string without path`() {
            val parsed = SourceInfo.parse("myuser@myhost")

            assertNotNull(parsed)
            assertEquals("myhost", parsed!!.host)
            assertEquals("myuser", parsed.userName)
            assertEquals("", parsed.path)
        }

        @Test
        fun `should return null for invalid source string`() {
            assertNull(SourceInfo.parse("invalid"))
            assertNull(SourceInfo.parse("@"))
            assertNull(SourceInfo.parse(":"))
        }
    }

    @Nested
    inner class SnapshotStatsTest {
        @Test
        fun `should deserialize SnapshotStats from Go JSON`() {
            val goJson = """{
                "totalSize": 1234567890,
                "excludedTotalSize": 5000,
                "fileCount": 1000,
                "cachedFiles": 950,
                "nonCachedFiles": 50,
                "dirCount": 100,
                "excludedFileCount": 5,
                "excludedDirCount": 2,
                "ignoredErrorCount": 1,
                "errorCount": 0
            }
            """.trimIndent()

            val stats = json.decodeFromString<SnapshotStats>(goJson)

            assertEquals(1234567890L, stats.totalFileSize)
            assertEquals(5000L, stats.excludedTotalFileSize)
            assertEquals(1000, stats.totalFileCount)
            assertEquals(950, stats.cachedFiles)
            assertEquals(50, stats.nonCachedFiles)
            assertEquals(100, stats.totalDirectoryCount)
            assertEquals(5, stats.excludedFileCount)
            assertEquals(2, stats.excludedDirCount)
            assertEquals(1, stats.ignoredErrorCount)
            assertEquals(0, stats.errorCount)
        }

        @Test
        fun `should serialize SnapshotStats to Go-compatible JSON`() {
            val stats = SnapshotStats(
                totalFileSize = 1234567890L,
                totalFileCount = 1000,
                totalDirectoryCount = 100,
            )

            val serialized = json.encodeToString(stats)

            assertTrue(serialized.contains(""""totalSize":1234567890"""))
            assertTrue(serialized.contains(""""fileCount":1000"""))
            assertTrue(serialized.contains(""""dirCount":100"""))
        }

        @Test
        fun `should have sensible defaults`() {
            val stats = SnapshotStats()

            assertEquals(0L, stats.totalFileSize)
            assertEquals(0L, stats.excludedTotalFileSize)
            assertEquals(0, stats.totalFileCount)
            assertEquals(0, stats.cachedFiles)
            assertEquals(0, stats.nonCachedFiles)
            assertEquals(0, stats.totalDirectoryCount)
            assertEquals(0, stats.excludedFileCount)
            assertEquals(0, stats.excludedDirCount)
            assertEquals(0, stats.ignoredErrorCount)
            assertEquals(0, stats.errorCount)
        }
    }

    @Nested
    inner class DirectorySummaryTest {
        @Test
        fun `should deserialize DirectorySummary from Go JSON`() {
            val goJson = """{
                "size": 11264,
                "files": 2,
                "symlinks": 1,
                "dirs": 3,
                "maxTime": "2019-05-09T22:33:06-07:00",
                "numFailed": 0
            }
            """.trimIndent()

            val summary = json.decodeFromString<DirectorySummary>(goJson)

            assertEquals(11264L, summary.totalFileSize)
            assertEquals(2L, summary.totalFileCount)
            assertEquals(1L, summary.totalSymlinkCount)
            assertEquals(3L, summary.totalDirCount)
            assertNotNull(summary.maxModTime)
            assertEquals(0, summary.fatalErrorCount)
        }

        @Test
        fun `should serialize DirectorySummary to Go-compatible JSON`() {
            val summary = DirectorySummary(
                totalFileSize = 11264L,
                totalFileCount = 2L,
                totalDirCount = 3L,
            )

            val serialized = json.encodeToString(summary)

            assertTrue(serialized.contains(""""size":11264"""))
            assertTrue(serialized.contains(""""files":2"""))
            assertTrue(serialized.contains(""""dirs":3"""))
        }

        @Test
        fun `should handle incomplete reason`() {
            val goJson = """{
                "size": 0,
                "files": 0,
                "dirs": 0,
                "incomplete": "cancelled"
            }
            """.trimIndent()

            val summary = json.decodeFromString<DirectorySummary>(goJson)

            assertEquals("cancelled", summary.incompleteReason)
        }

        @Test
        fun `should handle failed entries`() {
            val goJson = """{
                "size": 1000,
                "files": 5,
                "dirs": 1,
                "numFailed": 2,
                "numIgnoredErrors": 1,
                "errors": [
                    {"path": "/some/file.txt", "error": "permission denied"},
                    {"path": "/another/file.txt", "error": "file not found"}
                ]
            }
            """.trimIndent()

            val summary = json.decodeFromString<DirectorySummary>(goJson)

            assertEquals(2, summary.fatalErrorCount)
            assertEquals(1, summary.ignoredErrorCount)
            assertNotNull(summary.failedEntries)
            assertEquals(2, summary.failedEntries!!.size)
            assertEquals("/some/file.txt", summary.failedEntries!![0].entryPath)
            assertEquals("permission denied", summary.failedEntries!![0].error)
        }
    }

    @Nested
    inner class EntryWithErrorTest {
        @Test
        fun `should deserialize EntryWithError from Go JSON`() {
            val goJson = """{"path": "/some/file.txt", "error": "permission denied"}"""

            val entry = json.decodeFromString<EntryWithError>(goJson)

            assertEquals("/some/file.txt", entry.entryPath)
            assertEquals("permission denied", entry.error)
        }
    }

    @Nested
    inner class DirEntryTest {
        @Test
        fun `should deserialize file entry from Go JSON`() {
            val goJson = """{
                "name": "document.pdf",
                "type": "f",
                "mode": "0644",
                "size": 12345,
                "mtime": "2024-01-15T10:30:00Z",
                "uid": 1000,
                "gid": 1000,
                "obj": "abc123def456"
            }
            """.trimIndent()

            val entry = json.decodeFromString<DirEntry>(goJson)

            assertEquals("document.pdf", entry.name)
            assertEquals(EntryType.FILE, entry.type)
            assertEquals(420, entry.permissions) // 0644 octal = 420 decimal
            assertEquals(12345L, entry.fileSize)
            assertNotNull(entry.modTime)
            assertEquals(1000, entry.userId)
            assertEquals(1000, entry.groupId)
            assertEquals("abc123def456", entry.objectId)
        }

        @Test
        fun `should deserialize directory entry from Go JSON`() {
            val goJson = """{
                "name": "subdir",
                "type": "d",
                "mode": "0755",
                "mtime": "2024-01-15T10:30:00Z",
                "uid": 1000,
                "gid": 1000,
                "obj": "dir123abc",
                "summ": {
                    "size": 5000,
                    "files": 10,
                    "dirs": 2
                }
            }
            """.trimIndent()

            val entry = json.decodeFromString<DirEntry>(goJson)

            assertEquals("subdir", entry.name)
            assertEquals(EntryType.DIRECTORY, entry.type)
            assertEquals(493, entry.permissions) // 0755 octal = 493 decimal
            assertNotNull(entry.dirSummary)
            assertEquals(5000L, entry.dirSummary!!.totalFileSize)
            assertEquals(10L, entry.dirSummary!!.totalFileCount)
            assertEquals(2L, entry.dirSummary!!.totalDirCount)
        }

        @Test
        fun `should deserialize symlink entry from Go JSON`() {
            val goJson = """{
                "name": "link",
                "type": "s",
                "mode": "0777",
                "mtime": "2024-01-15T10:30:00Z",
                "obj": "target_content_id"
            }
            """.trimIndent()

            val entry = json.decodeFromString<DirEntry>(goJson)

            assertEquals("link", entry.name)
            assertEquals(EntryType.SYMLINK, entry.type)
            assertEquals(511, entry.permissions) // 0777 octal = 511 decimal
        }

        @Test
        fun `should handle unknown entry type gracefully`() {
            val goJson = """{
                "name": "unknown",
                "type": "",
                "obj": "content_id"
            }
            """.trimIndent()

            val entry = json.decodeFromString<DirEntry>(goJson)

            assertEquals(EntryType.UNKNOWN, entry.type)
        }

        @Test
        fun `should serialize file entry to Go-compatible JSON`() {
            val entry = DirEntry(
                name = "document.pdf",
                type = EntryType.FILE,
                permissions = 420, // 0644 octal
                fileSize = 12345L,
                objectId = "abc123def456",
            )

            val serialized = json.encodeToString(entry)

            assertTrue(serialized.contains(""""name":"document.pdf""""))
            assertTrue(serialized.contains(""""type":"f""""))
            assertTrue(serialized.contains(""""mode":"0644"""")) // Should serialize as octal string
            assertTrue(serialized.contains(""""size":12345"""))
            assertTrue(serialized.contains(""""obj":"abc123def456""""))
        }

        @Test
        fun `should serialize directory entry with summary`() {
            val entry = DirEntry(
                name = "subdir",
                type = EntryType.DIRECTORY,
                permissions = 493, // 0755 octal
                objectId = "dir123abc",
                dirSummary = DirectorySummary(
                    totalFileSize = 5000L,
                    totalFileCount = 10L,
                    totalDirCount = 2L,
                ),
            )

            val serialized = json.encodeToString(entry)

            assertTrue(serialized.contains(""""type":"d""""))
            assertTrue(serialized.contains(""""mode":"0755""""))
            assertTrue(serialized.contains(""""summ":"""))
        }

        @Test
        fun `should omit null fields when serializing`() {
            val entry = DirEntry(
                name = "simple",
                type = EntryType.FILE,
                objectId = "content_id",
            )

            val serialized = json.encodeToString(entry)

            assertTrue(!serialized.contains(""""mode"""") || serialized.contains(""""mode":null""").not())
            assertTrue(!serialized.contains(""""summ""""))
            assertTrue(!serialized.contains(""""uid""""))
        }

        @Test
        fun `should handle zero permissions`() {
            // In Go, mode 0 means unspecified - should serialize to null/omit
            val goJson = """{"name": "nomode", "type": "f", "obj": "id"}"""

            val entry = json.decodeFromString<DirEntry>(goJson)

            assertEquals(0, entry.permissions)
        }
    }

    @Nested
    inner class DirManifestTest {
        @Test
        fun `should deserialize directory manifest from Go JSON`() {
            val goJson = """{
                "stream": "kopia:directory",
                "entries": [
                    {
                        "name": "file1.txt",
                        "type": "f",
                        "mode": "0644",
                        "size": 100,
                        "obj": "content1"
                    },
                    {
                        "name": "subdir",
                        "type": "d",
                        "mode": "0755",
                        "obj": "dir1"
                    }
                ],
                "summary": {
                    "size": 100,
                    "files": 1,
                    "dirs": 1
                }
            }
            """.trimIndent()

            val dirManifest = json.decodeFromString<DirManifest>(goJson)

            assertEquals("kopia:directory", dirManifest.streamType)
            assertEquals(2, dirManifest.entries.size)
            assertEquals("file1.txt", dirManifest.entries[0].name)
            assertEquals(EntryType.FILE, dirManifest.entries[0].type)
            assertEquals("subdir", dirManifest.entries[1].name)
            assertEquals(EntryType.DIRECTORY, dirManifest.entries[1].type)
            assertNotNull(dirManifest.summary)
            assertEquals(100L, dirManifest.summary!!.totalFileSize)
        }

        @Test
        fun `should serialize directory manifest to Go-compatible JSON`() {
            val dirManifest = DirManifest(
                entries = listOf(
                    DirEntry(name = "file1.txt", type = EntryType.FILE, objectId = "c1"),
                    DirEntry(name = "subdir", type = EntryType.DIRECTORY, objectId = "d1"),
                ),
                summary = DirectorySummary(totalFileSize = 100L, totalFileCount = 1L, totalDirCount = 1L),
            )

            val serialized = json.encodeToString(dirManifest)

            assertTrue(serialized.contains(""""stream":"kopia:directory""""))
            assertTrue(serialized.contains(""""entries":"""))
        }

        @Test
        fun `should reject invalid stream type`() {
            val dirManifest = DirManifest(
                streamType = "invalid:type",
                entries = listOf(),
            )

            assertTrue(!dirManifest.isValidDirectoryStream())
        }
    }

    @Nested
    inner class SnapshotManifestTest {
        @Test
        fun `should deserialize SnapshotManifest from Go JSON`() {
            val goJson = """{
                "id": "abc123def456",
                "source": {
                    "host": "myhost",
                    "userName": "myuser",
                    "path": "/home/myuser/documents"
                },
                "description": "Daily backup",
                "startTime": "2024-01-15T10:30:00Z",
                "endTime": "2024-01-15T10:35:00Z",
                "stats": {
                    "totalSize": 1000000,
                    "fileCount": 100,
                    "dirCount": 10
                },
                "rootEntry": {
                    "name": "documents",
                    "type": "d",
                    "mode": "0755",
                    "obj": "root_object_id"
                },
                "tags": {
                    "environment": "production"
                },
                "pins": ["keep-forever"]
            }
            """.trimIndent()

            val manifest = json.decodeFromString<SnapshotManifest>(goJson)

            assertEquals("abc123def456", manifest.id)
            assertEquals("myhost", manifest.source.host)
            assertEquals("myuser", manifest.source.userName)
            assertEquals("/home/myuser/documents", manifest.source.path)
            assertEquals("Daily backup", manifest.description)
            assertNotNull(manifest.startTime)
            assertNotNull(manifest.endTime)
            assertNotNull(manifest.stats)
            assertEquals(1000000L, manifest.stats!!.totalFileSize)
            assertNotNull(manifest.rootEntry)
            assertEquals("documents", manifest.rootEntry!!.name)
            assertEquals(EntryType.DIRECTORY, manifest.rootEntry!!.type)
            assertEquals("production", manifest.tags["environment"])
            assertEquals(listOf("keep-forever"), manifest.pins)
        }

        @Test
        fun `should handle incomplete snapshot`() {
            val goJson = """{
                "id": "incomplete123",
                "source": {"host": "h", "userName": "u", "path": "/p"},
                "startTime": "2024-01-15T10:30:00Z",
                "stats": {},
                "incomplete": "cancelled by user"
            }
            """.trimIndent()

            val manifest = json.decodeFromString<SnapshotManifest>(goJson)

            assertEquals("cancelled by user", manifest.incompleteReason)
            assertNull(manifest.endTime)
        }

        @Test
        fun `should serialize SnapshotManifest to Go-compatible JSON`() {
            val manifest = SnapshotManifest(
                id = "abc123def456",
                source = SourceInfo(host = "myhost", userName = "myuser", path = "/home/myuser"),
                startTime = java.time.Instant.parse("2024-01-15T10:30:00Z"),
                stats = SnapshotStats(totalFileSize = 1000L, totalFileCount = 10),
            )

            val serialized = json.encodeToString(manifest)

            assertTrue(serialized.contains(""""id":"abc123def456""""))
            assertTrue(serialized.contains(""""source":"""))
            assertTrue(serialized.contains(""""startTime":"""))
        }

        @Test
        fun `should omit empty optional fields`() {
            val manifest = SnapshotManifest(
                id = "test",
                source = SourceInfo(host = "h", userName = "u", path = "/p"),
                startTime = java.time.Instant.now(),
            )

            val serialized = json.encodeToString(manifest)

            // These should be omitted when empty/null
            assertTrue(!serialized.contains(""""description":""""") || !serialized.contains(""""description""""))
            assertTrue(!serialized.contains(""""incomplete""""))
            assertTrue(!serialized.contains(""""pins":[]"""))
            assertTrue(!serialized.contains(""""tags":{}"""))
        }
    }

    @Nested
    inner class StorageStatsTest {
        @Test
        fun `should deserialize StorageStats from Go JSON`() {
            val goJson = """{
                "newData": {
                    "objectBytes": 1000000,
                    "originalContentBytes": 800000,
                    "packedContentBytes": 500000,
                    "fileObjects": 100,
                    "dirObjects": 10,
                    "contents": 110
                },
                "runningTotal": {
                    "objectBytes": 5000000,
                    "originalContentBytes": 4000000,
                    "packedContentBytes": 2500000,
                    "fileObjects": 500,
                    "dirObjects": 50,
                    "contents": 550
                }
            }
            """.trimIndent()

            val stats = json.decodeFromString<StorageStats>(goJson)

            assertEquals(1000000L, stats.newData.objectBytes)
            assertEquals(800000L, stats.newData.originalContentBytes)
            assertEquals(500000L, stats.newData.packedContentBytes)
            assertEquals(100, stats.newData.fileObjectCount)
            assertEquals(10, stats.newData.dirObjectCount)
            assertEquals(110, stats.newData.contentCount)

            assertEquals(5000000L, stats.runningTotal.objectBytes)
        }
    }

    @Nested
    inner class ManifestLabelsTest {
        @Test
        fun `should have correct label constants`() {
            assertEquals("type", ManifestLabels.TYPE)
            assertEquals("snapshot", ManifestLabels.TYPE_SNAPSHOT)
            assertEquals("hostname", ManifestLabels.HOST)
            assertEquals("username", ManifestLabels.USERNAME)
            assertEquals("path", ManifestLabels.PATH)
        }

        @Test
        fun `should create snapshot labels`() {
            val sourceInfo = SourceInfo(host = "myhost", userName = "myuser", path = "/mypath")
            val labels = ManifestLabels.forSnapshot(sourceInfo)

            assertEquals("snapshot", labels["type"])
            assertEquals("myhost", labels["hostname"])
            assertEquals("myuser", labels["username"])
            assertEquals("/mypath", labels["path"])
        }
    }

    @Nested
    inner class PermissionsSerializationTest {
        @Test
        fun `should parse octal permission string`() {
            // Go serializes permissions as octal strings like "0755"
            val goJson = """{"name": "test", "type": "f", "mode": "0755", "obj": "id"}"""

            val entry = json.decodeFromString<DirEntry>(goJson)

            assertEquals(493, entry.permissions) // 0755 octal = 493 decimal
        }

        @Test
        fun `should parse permissions without leading zero`() {
            // Support both "0755" and "755" formats
            val goJson = """{"name": "test", "type": "f", "mode": "755", "obj": "id"}"""

            val entry = json.decodeFromString<DirEntry>(goJson)

            assertEquals(493, entry.permissions)
        }

        @Test
        fun `should serialize permissions as octal string with leading zero`() {
            val entry = DirEntry(name = "test", type = EntryType.FILE, permissions = 493, objectId = "id")

            val serialized = json.encodeToString(entry)

            assertTrue(serialized.contains(""""mode":"0755""""))
        }

        @Test
        fun `should omit zero permissions`() {
            val entry = DirEntry(name = "test", type = EntryType.FILE, permissions = 0, objectId = "id")

            val serialized = json.encodeToString(entry)

            // Zero permissions should be omitted entirely (Go behavior)
            assertTrue(!serialized.contains(""""mode""""))
        }
    }

    @Nested
    inner class TimestampSerializationTest {
        @Test
        fun `should parse Go UTC timestamp`() {
            val goJson = """{"name": "test", "type": "f", "mtime": "2024-01-15T10:30:00Z", "obj": "id"}"""

            val entry = json.decodeFromString<DirEntry>(goJson)

            assertNotNull(entry.modTime)
            assertEquals(2024, entry.modTime!!.atZone(java.time.ZoneOffset.UTC).year)
        }

        @Test
        fun `should parse Go timestamp with timezone offset`() {
            val goJson = """{"name": "test", "type": "f", "mtime": "2024-01-15T10:30:00-08:00", "obj": "id"}"""

            val entry = json.decodeFromString<DirEntry>(goJson)

            assertNotNull(entry.modTime)
        }

        @Test
        fun `should parse Go timestamp with nanoseconds`() {
            val goJson = """{"name": "test", "type": "f", "mtime": "2024-01-15T10:30:00.123456789Z", "obj": "id"}"""

            val entry = json.decodeFromString<DirEntry>(goJson)

            assertNotNull(entry.modTime)
            assertEquals(123456789, entry.modTime!!.nano)
        }

        @Test
        fun `should serialize timestamp in Go-compatible format`() {
            val instant = java.time.Instant.parse("2024-01-15T10:30:00.123456789Z")
            val entry = DirEntry(name = "test", type = EntryType.FILE, modTime = instant, objectId = "id")

            val serialized = json.encodeToString(entry)

            assertTrue(serialized.contains(""""mtime":"2024-01-15T10:30:00.123456789Z""""), serialized)
        }

        // Byte-exact formatting is covered by core's Rfc3339NanoTest; these exercise the snapshot
        // serializer end-to-end (through kotlinx serialization).

        @Test
        fun `serialized mtime never uses ISO_INSTANT zero-padded fraction`() {
            val instant = java.time.Instant.parse("2024-01-15T10:30:00.5Z")
            val entry = DirEntry(name = "t", type = EntryType.FILE, modTime = instant, objectId = "id")
            val serialized = json.encodeToString(entry)
            assertTrue(serialized.contains(""""mtime":"2024-01-15T10:30:00.5Z""""), serialized)
            assertTrue(!serialized.contains(".500Z"), serialized)
        }

        @Test
        fun `RFC3339Nano round-trips through the serializer for all fraction shapes`() {
            listOf(0L, 500_000_000L, 120_000_000L, 1_000L, 123_456_789L).forEach { nanos ->
                val instant = java.time.Instant.parse("2024-01-15T10:30:00Z").plusNanos(nanos)
                val entry = DirEntry(name = "t", type = EntryType.FILE, modTime = instant, objectId = "id")
                val decoded = json.decodeFromString<DirEntry>(json.encodeToString(entry))
                assertEquals(instant, decoded.modTime, "round-trip failed for nanos=$nanos")
            }
        }
    }
}
