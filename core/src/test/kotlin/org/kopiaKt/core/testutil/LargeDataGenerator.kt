package org.kopiaKt.core.testutil

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Generates deterministic large byte arrays and files for testing
 * large file handling without excessive memory use.
 */
object LargeDataGenerator {

    private const val BUFFER_SIZE = 8192

    /**
     * Create a deterministic ByteArray of given size using a seed.
     * Uses kotlin.random.Random for repeatable generation.
     */
    fun generate(size: Int, seed: Long = 42L): ByteArray {
        val random = Random(seed)
        return ByteArray(size).also { random.nextBytes(it) }
    }

    /**
     * Create a file at path with deterministic content.
     * Streams content to avoid loading entire file in memory for very large sizes.
     * Returns the SHA-256 hash of the written content.
     */
    fun generateFile(path: Path, size: Long, seed: Long = 42L): ByteArray {
        val random = Random(seed)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)

        Files.newOutputStream(path).buffered().use { output ->
            var remaining = size
            while (remaining > 0) {
                val toWrite = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
                random.nextBytes(buffer)
                output.write(buffer, 0, toWrite)
                digest.update(buffer, 0, toWrite)
                remaining -= toWrite
            }
        }

        return digest.digest()
    }

    /**
     * Compute SHA-256 hash of a file, streaming to handle large files.
     */
    fun sha256(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)

        Files.newInputStream(path).buffered().use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }

        return digest.digest()
    }

    /**
     * Compute SHA-256 hash of a byte array.
     */
    fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    /**
     * Compare two files byte-by-byte, returning first mismatch offset or -1 if identical.
     * Streams both files to handle large sizes.
     */
    fun compareFiles(file1: Path, file2: Path): Long {
        val size1 = Files.size(file1)
        val size2 = Files.size(file2)

        Files.newInputStream(file1).buffered().use { in1 ->
            Files.newInputStream(file2).buffered().use { in2 ->
                var offset = 0L
                val buf1 = ByteArray(BUFFER_SIZE)
                val buf2 = ByteArray(BUFFER_SIZE)
                val minSize = minOf(size1, size2)

                while (offset < minSize) {
                    val toRead = minOf(minSize - offset, BUFFER_SIZE.toLong()).toInt()
                    val read1 = readFully(in1, buf1, toRead)
                    val read2 = readFully(in2, buf2, toRead)
                    val compareLen = minOf(read1, read2)

                    for (i in 0 until compareLen) {
                        if (buf1[i] != buf2[i]) {
                            return offset + i
                        }
                    }

                    if (read1 != read2) {
                        return offset + minOf(read1, read2)
                    }

                    offset += compareLen
                }

                // If sizes differ, mismatch at the end of the shorter file
                return if (size1 != size2) minSize else -1
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = input.read(buffer, total, length - total)
            if (read == -1) break
            total += read
        }
        return total
    }
}
