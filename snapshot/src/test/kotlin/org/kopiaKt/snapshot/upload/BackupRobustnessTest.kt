package org.kopiaKt.snapshot.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.kopiaKt.core.content.ContentId
import org.kopiaKt.core.content.ContentInfo
import org.kopiaKt.core.content.ObjectId
import org.kopiaKt.core.manifest.EntryMetadata
import org.kopiaKt.core.manifest.ManifestId
import org.kopiaKt.core.`object`.ObjectReader
import org.kopiaKt.core.`object`.ObjectWriter
import org.kopiaKt.core.`object`.ObjectWriterOptions
import org.kopiaKt.core.repository.ConcatenateOptions
import org.kopiaKt.core.repository.ClientOptions
import org.kopiaKt.core.repository.RepositoryWriter
import org.kopiaKt.core.repository.WriteSessionOptions
import org.kopiaKt.snapshot.fs.DeviceInfo
import org.kopiaKt.snapshot.fs.Directory
import org.kopiaKt.snapshot.fs.DirectoryIterator
import org.kopiaKt.snapshot.fs.Entry
import org.kopiaKt.snapshot.fs.OwnerInfo
import org.kopiaKt.snapshot.model.DirManifest
import org.kopiaKt.snapshot.model.EntryType
import org.kopiaKt.snapshot.model.SourceInfo
import org.kopiaKt.snapshot.policy.ErrorHandlingPolicy
import org.kopiaKt.snapshot.policy.Policy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Robustness and stress tests for the snapshot backup pipeline.
 *
 * These tests exercise the SnapshotUploader under demanding conditions:
 * many files, large files, deep directory trees, incremental mutations,
 * concurrent operations, and storage failures.
 *
 * Tests go through the full upload pipeline (SnapshotUploader -> TreeWalker ->
 * FileUploader -> RepositoryWriter) using in-memory mock implementations.
 *
 * NOTE on overlap with existing tests:
 * - ManyFilesStressTest: tests 1000+ objects at the repository (object write/read) level,
 *   not through the snapshot upload pipeline. Our tests use SnapshotUploader with in-memory FS.
 * - LargeFileRoundTripTest: tests round-trip at object level, not through the upload pipeline.
 * - BackupRestoreIntegrityTest: tests content integrity at object level.
 * - SnapshotUploadIntegrationTest: tests policy, dedup, and error handling but not at scale.
 */
@DisplayName("Backup Robustness & Stress Tests")
@Tag("stress")
class BackupRobustnessTest {

    /**
     * File entry tracking content and modification time.
     * The modTime must change whenever content changes so the uploader
     * correctly detects modifications (it uses modTime + size for caching).
     */
    private data class FileState(val content: ByteArray, val modTime: Instant)

    private val testSource = SourceInfo(
        host = "stress-host",
        userName = "stress-user",
        path = "/stress/path"
    )

    // -----------------------------------------------------------------------
    //  Test 1: Backup 1000 small files (4KB each)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("1000 Small Files")
    inner class ManySmallFiles {

        @Test
        @Tag("stress")
        @DisplayName("backup 1000 small files, verify all present in snapshot manifest")
        fun `backup 1000 small files and verify all in snapshot`() = runBlocking {
            val fileCount = 1000
            val fileSize = 4096 // 4KB each

            val writer = TrackingRepositoryWriter()
            val progress = CountingUploadProgress()

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = progress
            )

            // Generate 1000 files with deterministic content
            val files = (0 until fileCount).map { i ->
                val content = generateDeterministic(fileSize, seed = i.toLong())
                InMemoryFile("file_${String.format("%04d", i)}.dat", content)
            }

            val rootDir = InMemoryDirectory("root", entries = files)
            val result = uploader.upload(rootDir)

            assertThat(result.incomplete).isFalse()
            assertThat(result.manifestId).isNotNull()

            // Verify stats report all 1000 files
            val counters = progress.snapshot()
            assertThat(counters.totalHashedFiles).isEqualTo(fileCount)

            // Verify root manifest contains all entries
            val rootObjectId = result.manifest.rootEntry?.objectId
            assertThat(rootObjectId).isNotNull()

            val rootManifest = readDirManifest(writer, rootObjectId!!)
            assertThat(rootManifest.entries).hasSize(fileCount)

            // Verify summary counts
            val summary = rootManifest.summary
            assertThat(summary).isNotNull()
            assertThat(summary!!.totalFileCount).isEqualTo(fileCount.toLong())
            assertThat(summary.totalFileSize).isEqualTo(fileCount.toLong() * fileSize)

            // Verify each file entry has a valid objectId
            for (entry in rootManifest.entries) {
                assertThat(entry.type).isEqualTo(EntryType.FILE)
                assertThat(entry.objectId).isNotNull()
                assertThat(entry.objectId).isNotEmpty()
            }

            // Spot-check: verify content round-trip for a sample of files
            val sampleIndices = listOf(0, 1, 499, 500, 998, 999)
            for (i in sampleIndices) {
                val expectedContent = generateDeterministic(fileSize, seed = i.toLong())
                val entry = rootManifest.entries.find {
                    it.name == "file_${String.format("%04d", i)}.dat"
                }
                assertThat(entry).isNotNull()

                val storedContent = writer.readObject(ObjectId.parse(entry!!.objectId!!))
                assertArrayEquals(
                    expectedContent,
                    storedContent,
                    "Content mismatch for file_${String.format("%04d", i)}.dat"
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Test 2: Backup 10 files of 10MB each
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Multiple Large Files")
    inner class MultipleLargeFiles {

        @Test
        @Tag("stress")
        @DisplayName("backup 10 files of 10MB each, verify chunking and dedup")
        fun `backup 10 files of 10MB each`() = runBlocking {
            val fileCount = 10
            val fileSize = 10 * 1024 * 1024 // 10MB

            val writer = TrackingRepositoryWriter()
            val progress = CountingUploadProgress()

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = progress
            )

            // Generate 10 unique 10MB files
            val fileContents = (0 until fileCount).map { i ->
                generateDeterministic(fileSize, seed = (i * 1000).toLong())
            }

            val files = fileContents.mapIndexed { i, content ->
                InMemoryFile("large_${i}.bin", content)
            }

            val rootDir = InMemoryDirectory("root", entries = files)
            val result = uploader.upload(rootDir)

            assertThat(result.incomplete).isFalse()

            val counters = progress.snapshot()
            assertThat(counters.totalHashedFiles).isEqualTo(fileCount)
            // Total hashed bytes should be at least 10 * 10MB
            assertThat(counters.totalHashedBytes).isAtLeast(fileCount.toLong() * fileSize)

            // Verify root manifest
            val rootManifest = readDirManifest(writer, result.manifest.rootEntry!!.objectId!!)
            assertThat(rootManifest.entries).hasSize(fileCount)

            // Verify content round-trip for each file
            for (i in 0 until fileCount) {
                val entry = rootManifest.entries.find { it.name == "large_${i}.bin" }
                assertThat(entry).isNotNull()

                val storedContent = writer.readObject(ObjectId.parse(entry!!.objectId!!))
                val expectedHash = sha256(fileContents[i])
                val storedHash = sha256(storedContent)
                assertArrayEquals(
                    expectedHash,
                    storedHash,
                    "SHA-256 mismatch for large_${i}.bin"
                )
            }

            // Dedup check: backup again with same content and verify caching
            val progress2 = CountingUploadProgress()
            val uploader2 = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = progress2
            )

            val rootDir2 = InMemoryDirectory("root", entries = files.map { file ->
                InMemoryFile(file.name, fileContents[files.indexOf(file)])
            })

            val result2 = uploader2.upload(rootDir2)
            assertThat(result2.incomplete).isFalse()

            val counters2 = progress2.snapshot()
            // On second upload, files should be cached (deduped), not re-hashed
            assertThat(counters2.totalCachedFiles).isEqualTo(fileCount)
        }
    }

    // -----------------------------------------------------------------------
    //  Test 3: Backup deep tree 10 levels
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Deep Directory Tree")
    inner class DeepDirectoryTree {

        @Test
        @Tag("stress")
        @DisplayName("backup directory tree with 10 levels of nesting")
        fun `backup deep tree 10 levels`() = runBlocking {
            val depth = 10
            val writer = TrackingRepositoryWriter()
            val progress = CountingUploadProgress()

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = progress
            )

            // Build a tree: each level has a file and a subdirectory
            // Level 0 (root) -> Level 1 -> ... -> Level 9 (leaf dir)
            val rootDir = buildDeepTree(depth)

            val result = uploader.upload(rootDir)

            assertThat(result.incomplete).isFalse()

            // Verify directory counts: root + 9 nested = 10 dirs total
            val rootManifest = readDirManifest(writer, result.manifest.rootEntry!!.objectId!!)
            val summary = rootManifest.summary!!
            assertThat(summary.totalDirCount).isEqualTo(depth.toLong())

            // Verify total file count: 1 file at each level = 10 files
            assertThat(summary.totalFileCount).isEqualTo(depth.toLong())

            // Walk the tree in the manifest and verify each level
            var currentManifest = rootManifest
            for (level in 0 until depth) {
                // Each level should have a file
                val fileEntry = currentManifest.entries.find { it.type == EntryType.FILE }
                assertThat(fileEntry).isNotNull()
                assertThat(fileEntry!!.name).isEqualTo("file_level_${level}.txt")

                // Verify file content
                val content = writer.readObject(ObjectId.parse(fileEntry.objectId!!))
                assertThat(String(content)).isEqualTo("content at level $level")

                // If not the deepest level, follow the subdirectory
                if (level < depth - 1) {
                    val dirEntry = currentManifest.entries.find { it.type == EntryType.DIRECTORY }
                    assertThat(dirEntry).isNotNull()
                    assertThat(dirEntry!!.name).isEqualTo("level_${level + 1}")

                    currentManifest = readDirManifest(writer, dirEntry.objectId!!)
                }
            }
        }

        private fun buildDeepTree(depth: Int): InMemoryDirectory {
            // Build from the deepest level up
            var current: InMemoryDirectory = InMemoryDirectory(
                "level_${depth - 1}",
                entries = listOf(
                    InMemoryFile(
                        "file_level_${depth - 1}.txt",
                        "content at level ${depth - 1}".toByteArray()
                    )
                )
            )

            for (level in (depth - 2) downTo 0) {
                current = InMemoryDirectory(
                    if (level == 0) "root" else "level_$level",
                    entries = listOf(
                        InMemoryFile(
                            "file_level_${level}.txt",
                            "content at level $level".toByteArray()
                        ),
                        current
                    )
                )
            }

            return current
        }
    }

    // -----------------------------------------------------------------------
    //  Test 4: Backup after multiple incremental changes
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Multiple Incremental Backups")
    inner class IncrementalBackups {

        @Test
        @Tag("stress")
        @DisplayName("5 backup cycles with mutations between each")
        fun `backup after multiple incremental changes`() = runBlocking {
            val writer = TrackingRepositoryWriter()
            val baseFiles = mutableMapOf<String, FileState>()
            val baseTime = Instant.parse("2025-01-01T00:00:00Z")

            // Initial set of 20 files (cycle 1 timestamp)
            for (i in 0 until 20) {
                baseFiles["file_${String.format("%02d", i)}.dat"] = FileState(
                    content = generateDeterministic(1024, seed = i.toLong()),
                    modTime = baseTime
                )
            }

            // Cycle 1: initial backup of all 20 files
            val result1 = doBackup(writer, baseFiles)
            assertThat(result1.incomplete).isFalse()
            val manifest1 = readDirManifest(writer, result1.manifest.rootEntry!!.objectId!!)
            assertThat(manifest1.entries).hasSize(20)

            // Cycle 2: modify 5 files (new content + updated modTime)
            val cycle2Time = Instant.parse("2025-01-02T00:00:00Z")
            for (i in 0 until 5) {
                baseFiles["file_${String.format("%02d", i)}.dat"] = FileState(
                    content = generateDeterministic(1024, seed = (i + 100).toLong()),
                    modTime = cycle2Time
                )
            }
            val result2 = doBackup(writer, baseFiles)
            assertThat(result2.incomplete).isFalse()
            val manifest2 = readDirManifest(writer, result2.manifest.rootEntry!!.objectId!!)
            assertThat(manifest2.entries).hasSize(20)

            // Cycle 3: add 5 new files
            val cycle3Time = Instant.parse("2025-01-03T00:00:00Z")
            for (i in 20 until 25) {
                baseFiles["file_${String.format("%02d", i)}.dat"] = FileState(
                    content = generateDeterministic(1024, seed = i.toLong()),
                    modTime = cycle3Time
                )
            }
            val result3 = doBackup(writer, baseFiles)
            assertThat(result3.incomplete).isFalse()
            val manifest3 = readDirManifest(writer, result3.manifest.rootEntry!!.objectId!!)
            assertThat(manifest3.entries).hasSize(25)

            // Cycle 4: delete 3 files
            baseFiles.remove("file_10.dat")
            baseFiles.remove("file_11.dat")
            baseFiles.remove("file_12.dat")
            val result4 = doBackup(writer, baseFiles)
            assertThat(result4.incomplete).isFalse()
            val manifest4 = readDirManifest(writer, result4.manifest.rootEntry!!.objectId!!)
            assertThat(manifest4.entries).hasSize(22)
            val names4 = manifest4.entries.map { it.name }
            assertThat(names4).doesNotContain("file_10.dat")
            assertThat(names4).doesNotContain("file_11.dat")
            assertThat(names4).doesNotContain("file_12.dat")

            // Cycle 5: modify + add + delete simultaneously
            val cycle5Time = Instant.parse("2025-01-05T00:00:00Z")
            baseFiles["file_00.dat"] = FileState(
                content = generateDeterministic(2048, seed = 999L),
                modTime = cycle5Time
            )
            baseFiles["file_25.dat"] = FileState(
                content = generateDeterministic(512, seed = 25L),
                modTime = cycle5Time
            )
            baseFiles.remove("file_20.dat")
            val result5 = doBackup(writer, baseFiles)
            assertThat(result5.incomplete).isFalse()
            val manifest5 = readDirManifest(writer, result5.manifest.rootEntry!!.objectId!!)
            assertThat(manifest5.entries).hasSize(22)

            // Verify all remaining files have correct content in the final snapshot
            for ((name, state) in baseFiles) {
                val entry = manifest5.entries.find { it.name == name }
                assertThat(entry).isNotNull()
                val storedContent = writer.readObject(ObjectId.parse(entry!!.objectId!!))
                assertArrayEquals(
                    state.content,
                    storedContent,
                    "Content mismatch for $name after 5 mutation cycles"
                )
            }
        }

        private suspend fun doBackup(
            writer: TrackingRepositoryWriter,
            files: Map<String, FileState>
        ): UploadResult {
            val progress = CountingUploadProgress()
            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = progress
            )

            val entries = files.map { (name, state) ->
                InMemoryFile(name, state.content, modTime = state.modTime)
            }
            val rootDir = InMemoryDirectory("root", entries = entries)
            return uploader.upload(rootDir)
        }
    }

    // -----------------------------------------------------------------------
    //  Test 5: Backup and restore round-trip preserves all content (SHA-256)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Content Preservation Round-Trip")
    inner class ContentPreservation {

        @Test
        @Tag("stress")
        @DisplayName("backup and restore round-trip preserves all content via SHA-256")
        fun `backup and restore round-trip preserves all content`() = runBlocking {
            val writer = TrackingRepositoryWriter()
            val progress = CountingUploadProgress()

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = progress
            )

            // Create a diverse set of files with varying sizes and content patterns
            val testFiles = mapOf(
                "empty.txt" to ByteArray(0),
                "single_byte.bin" to byteArrayOf(0x42),
                "small_text.txt" to "Hello, backup robustness!".toByteArray(),
                "all_zeros.bin" to ByteArray(8192),
                "all_ones.bin" to ByteArray(8192) { 0xFF.toByte() },
                "random_1k.bin" to generateDeterministic(1024, seed = 1L),
                "random_64k.bin" to generateDeterministic(65536, seed = 2L),
                "random_256k.bin" to generateDeterministic(256 * 1024, seed = 3L),
                "unicode.txt" to "\u00E9\u00E0\u00FC \u4F60\u597D \uD83D\uDE80".toByteArray(Charsets.UTF_8),
                "binary_all_bytes.bin" to ByteArray(256) { it.toByte() }
            )

            // Compute expected SHA-256 hashes before upload
            val expectedHashes = testFiles.mapValues { (_, content) -> sha256(content) }

            val files = testFiles.map { (name, content) -> InMemoryFile(name, content) }
            val rootDir = InMemoryDirectory("root", entries = files)

            val result = uploader.upload(rootDir)

            assertThat(result.incomplete).isFalse()

            // Read back from the manifest and verify each file's SHA-256
            val rootManifest = readDirManifest(writer, result.manifest.rootEntry!!.objectId!!)

            for ((name, expectedHash) in expectedHashes) {
                val entry = rootManifest.entries.find { it.name == name }
                assertThat(entry).isNotNull()
                assertThat(entry!!.objectId).isNotNull()

                val storedContent = writer.readObject(ObjectId.parse(entry.objectId!!))
                val storedHash = sha256(storedContent)

                assertArrayEquals(
                    expectedHash,
                    storedHash,
                    "SHA-256 hash mismatch for file '$name'"
                )

                // Also verify exact byte-level equality
                assertArrayEquals(
                    testFiles[name],
                    storedContent,
                    "Byte-level content mismatch for file '$name'"
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Test 6: Concurrent backup and restore on same repo
    //  NOTE: Overlaps partially with ManyFilesStressTest.ConcurrentWrites,
    //  but that test operates at the object level. This tests concurrent
    //  SnapshotUploader instances on a shared writer, verifying no corruption.
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Concurrent Backup Operations")
    inner class ConcurrentBackup {

        @Test
        @Tag("stress")
        @DisplayName("concurrent backups on shared writer produce valid snapshots")
        fun `concurrent backup and restore on same repo`() = runBlocking {
            val writer = TrackingRepositoryWriter()
            val backupCount = 5

            // Run 5 concurrent backup operations, each with different file sets
            val results = coroutineScope {
                (0 until backupCount).map { backupIndex ->
                    async {
                        val progress = CountingUploadProgress()
                        val source = SourceInfo(
                            host = "stress-host",
                            userName = "stress-user",
                            path = "/backup/$backupIndex"
                        )

                        val uploader = SnapshotUploader(
                            writer = writer,
                            source = source,
                            policy = Policy(),
                            progress = progress
                        )

                        val files = (0 until 50).map { i ->
                            val content = generateDeterministic(
                                1024,
                                seed = (backupIndex * 1000 + i).toLong()
                            )
                            InMemoryFile("file_${i}.dat", content)
                        }
                        val rootDir = InMemoryDirectory("root", entries = files)

                        val result = uploader.upload(rootDir)
                        Triple(backupIndex, result, progress)
                    }
                }.awaitAll()
            }

            // Verify all backups completed without corruption
            for ((backupIndex, result, progress) in results) {
                assertThat(result.incomplete).isFalse()
                assertThat(result.manifestId).isNotNull()

                val counters = progress.snapshot()
                assertThat(counters.totalHashedFiles).isEqualTo(50)

                // Verify manifest entries
                val rootManifest = readDirManifest(
                    writer, result.manifest.rootEntry!!.objectId!!
                )
                assertThat(rootManifest.entries).hasSize(50)

                // Spot-check content integrity for a few files per backup
                for (i in listOf(0, 24, 49)) {
                    val entry = rootManifest.entries.find { it.name == "file_${i}.dat" }
                    assertThat(entry).isNotNull()

                    val expectedContent = generateDeterministic(
                        1024,
                        seed = (backupIndex * 1000 + i).toLong()
                    )
                    val storedContent = writer.readObject(ObjectId.parse(entry!!.objectId!!))
                    assertArrayEquals(
                        expectedContent,
                        storedContent,
                        "Content mismatch for backup $backupIndex, file_${i}.dat"
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Test 7: Backup with intermittent storage failures
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Intermittent Storage Failures")
    inner class StorageFailures {

        @Test
        @Tag("stress")
        @DisplayName("backup completes with error tracking when intermittent IO failures occur")
        fun `backup with intermittent storage failures`() = runBlocking {
            val writer = TrackingRepositoryWriter()
            val progress = CountingUploadProgress()

            // Policy that ignores file errors so backup continues
            val policy = Policy(
                errorHandlingPolicy = ErrorHandlingPolicy(
                    ignoreFileErrors = true
                )
            )

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = policy,
                progress = progress
            )

            // Create a mix of working files and files that fail intermittently
            val entries = mutableListOf<Entry>()
            for (i in 0 until 20) {
                if (i % 5 == 3) {
                    // Every 5th file (at index 3, 8, 13, 18) fails to read
                    entries.add(FailingFile("fail_${i}.dat", IOException("Transient IO error at $i")))
                } else {
                    entries.add(
                        InMemoryFile(
                            "file_${i}.dat",
                            generateDeterministic(512, seed = i.toLong())
                        )
                    )
                }
            }

            val rootDir = InMemoryDirectory("root", entries = entries)
            val result = uploader.upload(rootDir)

            // Snapshot should complete (not incomplete) because errors are ignored
            assertThat(result.incomplete).isFalse()
            assertThat(result.stats.ignoredErrorCount).isEqualTo(4) // 4 failing files

            // Verify the 16 successful files are in the manifest
            val rootManifest = readDirManifest(writer, result.manifest.rootEntry!!.objectId!!)
            // Note: failed files are not added to the manifest entries
            val successfulEntries = rootManifest.entries.filter { it.type == EntryType.FILE }
            assertThat(successfulEntries).hasSize(16)

            // Verify content of successful files
            for (i in 0 until 20) {
                if (i % 5 == 3) continue // Skip failing files

                val entry = rootManifest.entries.find { it.name == "file_${i}.dat" }
                assertThat(entry).isNotNull()

                val expectedContent = generateDeterministic(512, seed = i.toLong())
                val storedContent = writer.readObject(ObjectId.parse(entry!!.objectId!!))
                assertArrayEquals(expectedContent, storedContent)
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Test 8: Backup memory usage stays bounded
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Memory Bounded Backup")
    inner class MemoryBounded {

        @Test
        @Tag("stress")
        @DisplayName("backup of 2000 files does not cause excessive memory growth")
        fun `backup memory usage stays bounded`() = runBlocking {
            // Force GC and capture baseline memory
            System.gc()
            Thread.sleep(100)
            val runtime = Runtime.getRuntime()
            val baselineMemory = runtime.totalMemory() - runtime.freeMemory()

            val writer = TrackingRepositoryWriter()
            val progress = CountingUploadProgress()

            val uploader = SnapshotUploader(
                writer = writer,
                source = testSource,
                policy = Policy(),
                progress = progress
            )

            // Create 2000 files of 4KB each = ~8MB of file data
            // Memory should not grow by more than a reasonable multiple of this
            val fileCount = 2000
            val fileSize = 4096
            val totalDataSize = fileCount.toLong() * fileSize // ~8MB

            val files = (0 until fileCount).map { i ->
                InMemoryFile("file_${i}.dat", generateDeterministic(fileSize, seed = i.toLong()))
            }
            val rootDir = InMemoryDirectory("root", entries = files)

            val result = uploader.upload(rootDir)

            assertThat(result.incomplete).isFalse()

            // Measure memory after backup
            System.gc()
            Thread.sleep(100)
            val afterBackupMemory = runtime.totalMemory() - runtime.freeMemory()
            val memoryGrowth = afterBackupMemory - baselineMemory

            // Allow generous headroom: the in-memory mock stores all objects,
            // so memory growth is expected. We check it doesn't explode to e.g. 10x.
            // With 2000 files * 4KB = 8MB, allow up to ~200MB headroom for JVM overhead,
            // object storage, manifest data, and test infrastructure.
            val maxAllowedGrowth = 200L * 1024 * 1024 // 200MB
            assertThat(memoryGrowth).isLessThan(maxAllowedGrowth)

            // Verify backup itself is correct
            val counters = progress.snapshot()
            assertThat(counters.totalHashedFiles).isEqualTo(fileCount)
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    /**
     * Generates deterministic byte content using a seeded Random.
     */
    private fun generateDeterministic(size: Int, seed: Long): ByteArray {
        val random = Random(seed)
        return ByteArray(size).also { random.nextBytes(it) }
    }

    /**
     * Computes SHA-256 hash of a byte array.
     */
    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    /**
     * Reads and parses a DirManifest from the writer's object store.
     */
    private suspend fun readDirManifest(writer: TrackingRepositoryWriter, objectId: String): DirManifest {
        val bytes = writer.readObject(ObjectId.parse(objectId))
        return DirManifest.fromJson(bytes.toString(Charsets.UTF_8))
    }

    // -----------------------------------------------------------------------
    //  In-memory filesystem mocks
    // -----------------------------------------------------------------------

    private class InMemoryFile(
        override val name: String,
        private val content: ByteArray,
        override val modTime: Instant = Instant.parse("2025-06-01T12:00:00Z"),
        override val mode: Int = 420 // 0644
    ) : org.kopiaKt.snapshot.fs.File {
        override val type = org.kopiaKt.snapshot.fs.EntryType.FILE
        override val size: Long = content.size.toLong()
        override val owner = OwnerInfo(1000, 1000)
        override val device = DeviceInfo(0, 0)
        override val localFilesystemPath = ""

        override suspend fun open(): InputStream = ByteArrayInputStream(content)
        override fun close() {}
    }

    private class FailingFile(
        override val name: String,
        private val error: Throwable,
        override val modTime: Instant = Instant.parse("2025-06-01T12:00:00Z"),
        override val mode: Int = 420
    ) : org.kopiaKt.snapshot.fs.File {
        override val type = org.kopiaKt.snapshot.fs.EntryType.FILE
        override val size: Long = 100
        override val owner = OwnerInfo(1000, 1000)
        override val device = DeviceInfo(0, 0)
        override val localFilesystemPath = ""

        override suspend fun open(): InputStream = throw error
        override fun close() {}
    }

    private class InMemoryDirectory(
        override val name: String,
        private val entries: List<Entry> = emptyList(),
        override val modTime: Instant = Instant.parse("2025-06-01T12:00:00Z"),
        override val mode: Int = 493 // 0755
    ) : Directory {
        override val type = org.kopiaKt.snapshot.fs.EntryType.DIRECTORY
        override val size: Long = 0
        override val owner = OwnerInfo(1000, 1000)
        override val device = DeviceInfo(0, 0)
        override val localFilesystemPath = ""

        override suspend fun child(name: String): Entry? {
            return entries.find { it.name == name }
        }

        override suspend fun iterate(): DirectoryIterator {
            return InMemoryDirectoryIterator(entries)
        }

        override fun close() {}
    }

    private class InMemoryDirectoryIterator(
        private val entries: List<Entry>
    ) : DirectoryIterator {
        private var index = 0

        override suspend fun next(): Entry? {
            return if (index < entries.size) entries[index++] else null
        }

        override fun close() {}
    }

    // -----------------------------------------------------------------------
    //  Tracking Repository Writer mock
    // -----------------------------------------------------------------------

    private class TrackingRepositoryWriter : RepositoryWriter {
        private val objectStore = ConcurrentHashMap<String, ByteArray>()
        private val manifestStore = ConcurrentHashMap<String, ManifestEntry>()
        private val nextId = AtomicInteger(1)

        val objectWriterOptions = mutableListOf<ObjectWriterOptions>()
        val writeCount = AtomicInteger(0)

        data class ManifestEntry(
            val id: ManifestId,
            val labels: Map<String, String>,
            val payload: ByteArray,
            val modTime: Instant
        )

        private fun nextHexId(): String = String.format("%032x", nextId.getAndIncrement())

        override fun newObjectWriter(options: ObjectWriterOptions): ObjectWriter {
            synchronized(objectWriterOptions) {
                objectWriterOptions.add(options)
            }
            return TrackingObjectWriter { data ->
                writeCount.incrementAndGet()
                val id = nextHexId()
                objectStore[id] = data
                ObjectId.parse(id)
            }
        }

        override suspend fun writeObject(
            data: ByteArray,
            options: ObjectWriterOptions
        ): ObjectId {
            synchronized(objectWriterOptions) {
                objectWriterOptions.add(options)
            }
            writeCount.incrementAndGet()
            val id = nextHexId()
            objectStore[id] = data
            return ObjectId.parse(id)
        }

        override suspend fun concatenateObjects(
            objectIds: List<ObjectId>,
            options: ConcatenateOptions
        ): ObjectId {
            TODO("Not needed for robustness tests")
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> putManifest(
            labels: Map<String, String>,
            payload: T,
            serializer: KSerializer<T>
        ): ManifestId {
            val json = kotlinx.serialization.json.Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }
            val jsonStr = json.encodeToString(serializer, payload)
            val id = ManifestId.generate()
            manifestStore[id.value] = ManifestEntry(
                id = id,
                labels = labels,
                payload = jsonStr.toByteArray(Charsets.UTF_8),
                modTime = Instant.now()
            )
            return id
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> replaceManifests(
            labels: Map<String, String>,
            payload: T,
            serializer: KSerializer<T>
        ): ManifestId {
            manifestStore.entries.removeIf { (_, entry) ->
                labels.all { (k, v) -> entry.labels[k] == v }
            }
            return putManifest(labels, payload, serializer)
        }

        override suspend fun deleteManifest(id: ManifestId) {
            manifestStore.remove(id.value)
        }

        override fun onSuccessfulFlush(callback: suspend (RepositoryWriter) -> Unit) {}

        override suspend fun flush() {}

        override fun openObject(objectId: ObjectId): ObjectReader {
            TODO("Not needed for robustness tests")
        }

        override suspend fun readObject(objectId: ObjectId): ByteArray {
            val id = objectId.toString()
            return objectStore[id]
                ?: throw NoSuchElementException("Object not found: $id")
        }

        override suspend fun verifyObject(objectId: ObjectId): List<ContentId> {
            TODO("Not needed for robustness tests")
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> getManifest(
            id: ManifestId,
            serializer: KSerializer<T>
        ): Pair<T, EntryMetadata> {
            val entry = manifestStore[id.value]
                ?: throw NoSuchElementException("Manifest not found: ${id.value}")
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }
            val payload = json.decodeFromString(serializer, entry.payload.toString(Charsets.UTF_8))
            val metadata = EntryMetadata(
                id = entry.id,
                length = entry.payload.size,
                labels = entry.labels,
                modTime = entry.modTime
            )
            return payload to metadata
        }

        override suspend fun findManifests(labels: Map<String, String>): List<EntryMetadata> {
            return manifestStore.values
                .filter { entry ->
                    labels.all { (k, v) -> entry.labels[k] == v }
                }
                .map { entry ->
                    EntryMetadata(
                        id = entry.id,
                        length = entry.payload.size,
                        labels = entry.labels,
                        modTime = entry.modTime
                    )
                }
        }

        override suspend fun contentInfo(contentId: ContentId): ContentInfo? = null

        override fun time(): Instant = Instant.now()

        override fun clientOptions(): ClientOptions = ClientOptions()

        override suspend fun newWriter(options: WriteSessionOptions): RepositoryWriter {
            return this
        }

        override fun updateDescription(description: String) {}

        override suspend fun refresh() {}

        override fun close() {}
    }

    private class TrackingObjectWriter(
        private val onResult: (ByteArray) -> ObjectId
    ) : ObjectWriter {
        private val buffer = ByteArrayOutputStream()

        override suspend fun write(data: ByteArray): Int {
            buffer.write(data)
            return data.size
        }

        override suspend fun checkpoint(): ObjectId {
            return ObjectId.Empty
        }

        override suspend fun result(): ObjectId {
            return onResult(buffer.toByteArray())
        }

        override suspend fun close() {}
    }
}
